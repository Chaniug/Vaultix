/*
 * Vaultix — app:passkey
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * 通行密钥「断言（assertion）」宿主：由 Credential Provider 的 GET 候选 PendingIntent 拉起。
 *
 * 流程：
 *  1. 按 (vaultId, itemId, credentialId) 从已解锁内存仓储取出凭证（私钥材料绝不走 Intent）；
 *  2. 用户确认 + 设备验证（生物识别 / 设备凭据）后构造 clientDataJSON + authenticatorData；
 *  3. 对 `authData ‖ clientDataHash` 做 P-256 签名（浏览器流程用系统给的 hash；
 *     原生流程自己对 clientDataJSON 取哈希）；
 *  4. 经 `PendingIntentHandler.setGetCredentialResponse` 回灌给 Credential Manager。
 *
 * 参考：Bastion PasskeyAuthActivity（GPL-3.0 同源思路，按 Vaultix 架构重写；
 * 密钥材料存于库内 VaultItem，不用 AndroidKeyStore 包裹）。
 */
package io.vaultix.vaultix.passkey

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Base64
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.credentials.GetCredentialResponse
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.PendingIntentHandler
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.vaultix.common.WebAuthn
import io.vaultix.domain.ItemRepository
import io.vaultix.model.VaultFido2Credential
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.theme.VaultixTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import javax.inject.Inject

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
@AndroidEntryPoint
class PasskeyGetActivity : FragmentActivity() {

    @Inject
    lateinit var itemRepository: ItemRepository

    private var biometricPrompt: BiometricPrompt? = null

    private lateinit var vaultId: String
    private lateinit var itemId: String
    private lateinit var credentialId: String
    private lateinit var rpId: String
    private lateinit var requestJson: String
    private var clientDataHash: ByteArray? = null
    private var origin: String = ""

    private var credential: VaultFido2Credential? = null
    private var rpName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        vaultId = intent.getStringExtra(PasskeyProviderIntents.EXTRA_VAULT_ID).orEmpty()
        itemId = intent.getStringExtra(PasskeyProviderIntents.EXTRA_ITEM_ID).orEmpty()
        credentialId = intent.getStringExtra(PasskeyProviderIntents.EXTRA_CREDENTIAL_ID).orEmpty()
        rpId = intent.getStringExtra(PasskeyProviderIntents.EXTRA_RP_ID).orEmpty()
        requestJson = intent.getStringExtra(PasskeyProviderIntents.EXTRA_REQUEST_JSON).orEmpty()
        clientDataHash = intent.getByteArrayExtra(PasskeyProviderIntents.EXTRA_CLIENT_DATA_HASH)

        // 系统把原始请求（含 callingAppInfo）附在 Intent 上；用其补全 origin。
        // clientDataHash 由 Service 经 extra 透传（option.clientDataHash 在 BEGIN 选项中可用）。
        val providerReq = runCatching { PendingIntentHandler.retrieveProviderGetCredentialRequest(intent) }.getOrNull()
        val callingOrigin = providerReq?.callingAppInfo?.origin?.takeIf { it.isNotBlank() }
        origin = callingOrigin
            ?: runCatching { JSONObject(requestJson).optString("origin").takeIf { it.isNotBlank() } }.getOrNull()
            ?: "https://$rpId"

        if (listOf(vaultId, itemId, credentialId, requestJson).any { it.isBlank() }) {
            fail(GetCredentialUnknownException("Missing passkey parameters"))
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val item = runCatching { itemRepository.observeItem(vaultId, itemId).first() }.getOrNull()
            val cred = item?.fido2Credentials?.firstOrNull { it.credentialId == credentialId }
            withContext(Dispatchers.Main) {
                if (cred == null) {
                    fail(GetCredentialUnknownException("Passkey not found"))
                    return@withContext
                }
                credential = cred
                rpName = cred.rpName.ifBlank { cred.rpId }
                showConfirm()
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

    private fun showConfirm() {
        setContent {
            VaultixTheme {
                PasskeyAuthSheet(
                    rpName = rpName,
                    rpId = rpId,
                    userName = credential?.userName.orEmpty(),
                    onConfirm = { verifyUser() },
                    onCancel = { cancel() },
                )
            }
        }
    }

    private fun verifyUser() {
        val executor = ContextCompat.getMainExecutor(this)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = sign()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = cancel()
        }
        val prompt = BiometricPrompt(this, executor, callback)
        biometricPrompt = prompt
        prompt.authenticate(promptInfo())
    }

    private fun promptInfo(): BiometricPrompt.PromptInfo {
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.passkey_auth_title))
            .setSubtitle(getString(R.string.passkey_auth_subtitle, rpName.ifBlank { rpId }))
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

    private fun sign() {
        val cred = credential ?: run {
            fail(GetCredentialUnknownException("Passkey not available"))
            return
        }
        val key = WebAuthn.parseEcPrivateKey(cred.keyValue)
        if (key == null) {
            fail(GetCredentialUnknownException("Cannot parse passkey key"))
            return
        }
        runCatching {
            val json = JSONObject(requestJson)
            val challenge = decodeChallenge(json.getString("challenge"))
            // ⚠️ 浏览器流程（系统给了 clientDataHash）必须逐字节复刻浏览器版 JSON：
            // 只 {type, challenge, origin}，**不能**多带 crossOrigin ——否则 RP 对返回的
            // clientDataJSON 再哈希后与已签名哈希对不上，站点报「验证失败」。
            val clientDataBytes = WebAuthn.buildClientDataJson(
                type = "webauthn.get",
                challenge = challenge,
                origin = origin,
                includeCrossOrigin = clientDataHash == null,
            )
            val authData = WebAuthn.buildAuthenticatorData(
                rpId = rpId,
                userPresent = true,
                userVerified = true,
                counter = 0,
                withAttested = false,
            )
            val signature = if (clientDataHash != null) {
                // 浏览器流程：系统已给 hash，直接拼接签名（不可再自行哈希）。
                WebAuthn.signAssertionHash(authData, clientDataHash!!, key)
            } else {
                // 原生 App 流程：自己对 clientDataJSON 取哈希后拼接签名。
                WebAuthn.signAssertion(authData, clientDataBytes, key)
            }
            val userHandle = cred.userHandle
                ?.let { runCatching { WebAuthn.decodeBase64UrlOrStandard(it) }.getOrNull() }
            val idBytes = runCatching { WebAuthn.decodeBase64UrlOrStandard(cred.credentialId) }.getOrNull()
                ?: cred.credentialId.toByteArray(StandardCharsets.UTF_8)
            val responseJson = WebAuthn.buildGetResponseJson(
                credentialId = idBytes,
                clientDataJson = clientDataBytes,
                authData = authData,
                signature = signature,
                userHandle = userHandle,
            )
            val resultIntent = Intent()
            PendingIntentHandler.setGetCredentialResponse(
                resultIntent,
                GetCredentialResponse(PublicKeyCredential(responseJson)),
            )
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }.onFailure { fail(GetCredentialUnknownException(it.message)) }
    }

    private fun decodeChallenge(b64: String): ByteArray =
        runCatching { Base64.decode(b64, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) }.getOrNull()
            ?: Base64.decode(b64, Base64.DEFAULT)

    private fun fail(e: GetCredentialException) {
        val resultIntent = Intent()
        PendingIntentHandler.setGetCredentialException(resultIntent, e)
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    private fun cancel() {
        val resultIntent = Intent()
        PendingIntentHandler.setGetCredentialException(resultIntent, GetCredentialCancellationException("Cancelled"))
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }
}

@Composable
private fun PasskeyAuthSheet(
    rpName: String,
    rpId: String,
    userName: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
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
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(text = stringResource(R.string.passkey_auth_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(R.string.passkey_auth_message, rpName.ifBlank { rpId }),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (userName.isNotBlank()) {
                    Text(text = userName, style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.passkey_auth_confirm))
                }
                TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.action_cancel))
                }
            }
        }
    }
}
