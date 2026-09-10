/*
 * Vaultix — app:passkey
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * 通行密钥「注册（attestation）」宿主：由 Credential Provider 的 CREATE 候选 PendingIntent 拉起。
 *
 * 行为（对齐 Bitwarden：通行密钥永远绑定到登录条目）：
 *  - 用户选择目标登录条目（或新建一个登录条目）；
 *  - 设备验证（生物识别 / 设备凭据）后生成 P-256 密钥对，私钥落库（VaultItem.fido2.keyValue），
 *    公钥写入 attestationObject；
 *  - 经 `PendingIntentHandler.setCreateCredentialResponse` 回灌给 Credential Manager。
 *
 * 注：attestation 用 `none`（软件密钥，密钥材料存于库内，与 Bitwarden 一致）；signCount 恒 0
 * （同步型 passkey 不推进计数器，避免多设备分叉，WebAuthn §6.1.1 允许）。
 */
package io.vaultix.vaultix.passkey

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.provider.PendingIntentHandler
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.vaultix.common.WebAuthn
import io.vaultix.domain.ItemRepository
import io.vaultix.domain.VaultRepository
import io.vaultix.model.VaultFido2Credential
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.theme.VaultixTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import javax.inject.Inject

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
@AndroidEntryPoint
class PasskeyCreateActivity : FragmentActivity() {

    @Inject
    lateinit var vaultRepository: VaultRepository

    @Inject
    lateinit var itemRepository: ItemRepository

    private var biometricPrompt: BiometricPrompt? = null

    private lateinit var requestJson: String
    private lateinit var rpId: String
    private lateinit var rpName: String
    private lateinit var userName: String
    private lateinit var userDisplayName: String
    private var origin: String = ""

    /**
     * 是否浏览器发起的创建请求（系统给了 `clientDataHash`）。
     * 为真时 clientDataJSON 必须逐字节复刻浏览器版（只 {type, challenge, origin}），
     * 否则 RP 再哈希对不上 → 站点报「验证失败」（见 [io.vaultix.common.WebAuthn.buildClientDataJson]）。
     */
    private var browserFlow: Boolean = false

    private val unlockedVaultIds = mutableStateOf<List<String>>(emptyList())
    private val vaultNameMap = mutableStateOf<Map<String, String>>(emptyMap())
    private val loginCandidates = mutableStateOf<List<VaultItem>>(emptyList())

    private var selectedVaultId by mutableStateOf("")
    private var selectedLoginId by mutableStateOf<String?>(null)
    private var createNewLogin by mutableStateOf(false)
    private var isCreating by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestJson = intent.getStringExtra(PasskeyProviderIntents.EXTRA_REQUEST_JSON).orEmpty()
        rpId = intent.getStringExtra(PasskeyProviderIntents.EXTRA_RP_ID).orEmpty()
        rpName = intent.getStringExtra(PasskeyProviderIntents.EXTRA_RP_NAME).orEmpty().ifBlank { rpId }
        userName = intent.getStringExtra(PasskeyProviderIntents.EXTRA_USER_NAME).orEmpty()
        userDisplayName = intent
            .getStringExtra(PasskeyProviderIntents.EXTRA_USER_DISPLAY_NAME)
            .orEmpty()
            .ifBlank { userName }

        val providerReq = runCatching {
            PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent)
        }.getOrNull()
        origin = providerReq?.callingAppInfo?.origin?.takeIf { it.isNotBlank() }
            ?: runCatching { JSONObject(requestJson).optString("origin").takeIf { it.isNotBlank() } }.getOrNull()
            ?: "https://$rpId"
        // clientDataHash 只挂在 CreatePublicKeyCredentialRequest 上（基类没有）→ 需下转型。
        browserFlow = (providerReq?.callingRequest as? CreatePublicKeyCredentialRequest)
            ?.clientDataHash != null

        if (requestJson.isBlank() || rpId.isBlank()) {
            fail(CreateCredentialUnknownException("Missing create parameters"))
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val ids = vaultRepository.observeUnlockedVaultIds().first()
            val vaults = vaultRepository.observeVaults().first()
            withContext(Dispatchers.Main) {
                unlockedVaultIds.value = ids.toList()
                vaultNameMap.value = vaults.associate { it.id to it.name }
                if (ids.isEmpty()) {
                    fail(CreateCredentialUnknownException("Vault is locked"))
                    return@withContext
                }
                selectedVaultId = ids.first()
                loadLogins(selectedVaultId)
                showUi()
            }
        }
    }

    override fun finish() {
        biometricPrompt?.cancelAuthentication()
        biometricPrompt = null
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun loadLogins(vaultId: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val items = runCatching { itemRepository.observeItems(vaultId).first() }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) {
                loginCandidates.value = items.filter { it.type == VaultItemType.Login }
            }
        }
    }

    private fun showUi() {
        setContent {
            VaultixTheme {
                PasskeyCreateSheet(
                    rpName = rpName,
                    rpId = rpId,
                    userName = userName,
                    vaults = unlockedVaultIds.value.map { id -> id to (vaultNameMap.value[id].orEmpty()) },
                    vaultName = vaultNameMap.value[selectedVaultId].orEmpty(),
                    onVaultSelected = {
                        selectedVaultId = it
                        selectedLoginId = null
                        createNewLogin = false
                        loadLogins(it)
                    },
                    logins = loginCandidates.value,
                    selectedLoginId = selectedLoginId,
                    onLoginSelected = { selectedLoginId = it; createNewLogin = false },
                    createNewLogin = createNewLogin,
                    onToggleNewLogin = { createNewLogin = it; if (it) selectedLoginId = null },
                    isCreating = isCreating,
                    onConfirm = { verifyUser() },
                    onCancel = { cancel() },
                )
            }
        }
    }

    private fun verifyUser() {
        val executor = ContextCompat.getMainExecutor(this)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = createPasskey()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = cancel()
        }
        val prompt = BiometricPrompt(this, executor, callback)
        biometricPrompt = prompt
        prompt.authenticate(promptInfo())
    }

    private fun promptInfo(): BiometricPrompt.PromptInfo {
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.passkey_create_title))
            .setSubtitle(getString(R.string.passkey_create_biometric_title))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
        } else {
            builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText(getString(R.string.action_cancel))
        }
        return builder.build()
    }

    private fun createPasskey() {
        val vaultId = selectedVaultId
        if (vaultId.isBlank()) {
            fail(CreateCredentialUnknownException("No vault selected"))
            return
        }
        isCreating = true
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val key = WebAuthn.generateKeyPair()
                val cose = WebAuthn.encodeCoseP256(key.publicX, key.publicY)
                val authData = WebAuthn.buildAuthenticatorData(
                    rpId = rpId,
                    userPresent = true,
                    userVerified = true,
                    counter = 0,
                    withAttested = true,
                    credentialId = key.credentialId,
                    cosePublicKey = cose,
                )
                val attObj = WebAuthn.buildNoneAttestationObject(authData)
                val json = JSONObject(requestJson)
                val challenge = decodeChallenge(json.getString("challenge"))
                // 浏览器流程同样必须逐字节复刻浏览器版 JSON（不额外带 crossOrigin）
                val clientDataBytes = WebAuthn.buildClientDataJson(
                    type = "webauthn.create",
                    challenge = challenge,
                    origin = origin,
                    includeCrossOrigin = !browserFlow,
                )
                val userId = json.optJSONObject("user")?.optString("id")?.takeIf { it.isNotBlank() }
                val cred = VaultFido2Credential(
                    credentialId = WebAuthn.base64Url(key.credentialId),
                    rpId = rpId,
                    rpName = rpName,
                    userName = userName,
                    userDisplayName = userDisplayName,
                    userHandle = userId,
                    keyAlgorithm = "ECDSA",
                    keyType = "public-key",
                    keyCurve = "P-256",
                    keyValue = WebAuthn.base64Url(key.privateKeyPkcs8),
                    creationDate = Instant.now().toString(),
                    counter = 0,
                    discoverable = true,
                )
                if (createNewLogin || selectedLoginId == null) {
                    val item = VaultItem(
                        id = "",
                        title = rpName.ifBlank { rpId },
                        username = userName,
                        type = VaultItemType.Login,
                        fido2Credentials = listOf(cred),
                    )
                    itemRepository.createItem(vaultId, item)
                } else {
                    val login = loginCandidates.value.firstOrNull { it.id == selectedLoginId }
                    val merged = (login?.fido2Credentials ?: emptyList()) + cred
                    itemRepository.updateFido2Credentials(vaultId, selectedLoginId!!, merged)
                }
                val responseJson = WebAuthn.buildCreateResponseJson(key.credentialId, clientDataBytes, attObj)
                withContext(Dispatchers.Main) {
                    val resultIntent = Intent()
                    PendingIntentHandler.setCreateCredentialResponse(
                        resultIntent,
                        CreatePublicKeyCredentialResponse(responseJson),
                    )
                    setResult(Activity.RESULT_OK, resultIntent)
                    finish()
                }
            }.onFailure {
                withContext(Dispatchers.Main) {
                    isCreating = false
                    fail(CreateCredentialUnknownException(it.message))
                }
            }
        }
    }

    private fun decodeChallenge(b64: String): ByteArray =
        runCatching {
            android.util.Base64.decode(
                b64,
                android.util.Base64.URL_SAFE
                    or android.util.Base64.NO_PADDING
                    or android.util.Base64.NO_WRAP,
            )
        }.getOrNull()
            ?: android.util.Base64.decode(b64, android.util.Base64.DEFAULT)

    private fun fail(e: CreateCredentialException) {
        val resultIntent = Intent()
        PendingIntentHandler.setCreateCredentialException(resultIntent, e)
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    private fun cancel() {
        val resultIntent = Intent()
        PendingIntentHandler.setCreateCredentialException(
            resultIntent,
            CreateCredentialCancellationException("Cancelled"),
        )
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PasskeyCreateSheet(
    rpName: String,
    rpId: String,
    userName: String,
    vaults: List<Pair<String, String>>,
    vaultName: String,
    onVaultSelected: (String) -> Unit,
    logins: List<VaultItem>,
    selectedLoginId: String?,
    onLoginSelected: (String) -> Unit,
    createNewLogin: Boolean,
    onToggleNewLogin: (Boolean) -> Unit,
    isCreating: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    var vaultExpanded by remember { mutableStateOf(false) }
    var loginExpanded by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.passkey_create_for, rpName.ifBlank { rpId }),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (userName.isNotBlank()) Text(text = userName, style = MaterialTheme.typography.bodyMedium)

                if (vaults.size > 1) {
                    ExposedDropdownMenuBox(expanded = vaultExpanded, onExpandedChange = { vaultExpanded = it }) {
                        OutlinedTextField(
                            value = vaultName,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.passkey_select_login)) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                        )
                        ExposedDropdownMenu(expanded = vaultExpanded, onDismissRequest = { vaultExpanded = false }) {
                            vaults.forEach { (id, name) ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = { onVaultSelected(id); vaultExpanded = false },
                                )
                            }
                        }
                    }
                }

                if (!createNewLogin) {
                    ExposedDropdownMenuBox(expanded = loginExpanded, onExpandedChange = { loginExpanded = it }) {
                        OutlinedTextField(
                            value = logins.firstOrNull { it.id == selectedLoginId }
                                ?.let { it.title.ifBlank { it.username } }
                                .orEmpty(),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.passkey_create_choose_login)) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                        )
                        ExposedDropdownMenu(expanded = loginExpanded, onDismissRequest = { loginExpanded = false }) {
                            logins.forEach { login ->
                                DropdownMenuItem(
                                    text = { Text(login.title.ifBlank { login.username }) },
                                    onClick = { onLoginSelected(login.id); loginExpanded = false },
                                )
                            }
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = createNewLogin, onCheckedChange = onToggleNewLogin)
                    Text(text = stringResource(R.string.passkey_create_new_login))
                }

                Button(onClick = onConfirm, enabled = !isCreating, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.passkey_create_confirm))
                }
                TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            }
        }
    }
}
