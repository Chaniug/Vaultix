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
 *  2. 用户确认 + 设备验证（生物识别 / 设备凭据）后构造 authenticatorData；
 *  3. 对 `authData ‖ clientDataHash` 做 P-256 签名（浏览器流程用系统给的 hash；
 *     原生流程自己对 clientDataJSON 取哈希）。浏览器流程回传的 clientDataJSON 只放
 *     占位符（官方要求），详见 `sign()` 内注释；
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
import io.vaultix.vaultix.autofill.AutofillLogger
import io.vaultix.vaultix.ui.theme.VaultixTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import javax.inject.Inject

/**
 * 现场诊断日志统一走 [AutofillLogger] 的 `VaultixAutofill` tag。
 *
 * ⚠️ 不用本类名派生的独立 tag：Android log tag 上限 23 字符，而过长的 tag 在本 ROM 上
 * **一条日志都进不了 logcat**（真机实测 `VaultixCredentialProvider`/`VaultixPasskey` 均无输出），
 * 会让「现场零日志 → 排障只能靠猜」重演。
 */
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
    private var callerPackageName: String = ""

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
        // ⚠️ 不直接读已收紧的 `callingAppInfo.origin`（1.6.0 起为 internal），走 CallingAppOrigin 兼容层。
        val providerReq = runCatching { PendingIntentHandler.retrieveProviderGetCredentialRequest(intent) }.getOrNull()
        val callingOrigin = CallingAppOrigin.originOrNull(providerReq?.callingAppInfo)
        callerPackageName = providerReq?.callingAppInfo?.packageName.orEmpty()
        origin = callingOrigin
            ?: runCatching { JSONObject(requestJson).optString("origin").takeIf { it.isNotBlank() } }.getOrNull()
            ?: "https://$rpId"

        if (listOf(vaultId, itemId, credentialId, requestJson).any { it.isBlank() }) {
            fail(GetCredentialUnknownException("Missing passkey parameters"))
            return
        }
        // 现场诊断：参数是否齐、origin 来源、是否浏览器流程（有 clientDataHash）
        AutofillLogger.d(
            "PK start origin=$origin originFromCaller=${callingOrigin != null} " +
                "browserFlow=${clientDataHash != null}",
        )

        lifecycleScope.launch(Dispatchers.IO) {
            val item = runCatching { itemRepository.observeItem(vaultId, itemId).first() }.getOrNull()
            val cred = item?.fido2Credentials?.firstOrNull { it.credentialId == credentialId }
            AutofillLogger.d("PK lookup itemFound=${item != null} credFound=${cred != null}")
            withContext(Dispatchers.Main) {
                if (cred == null) {
                    fail(GetCredentialUnknownException("Passkey not found"))
                    return@withContext
                }
                credential = cred
                rpName = cred.rpName.ifBlank { cred.rpId }
                AutofillLogger.d("PK confirm shown, waiting for user")
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
        AutofillLogger.d("PK biometric requested")
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
            // 现场实测 Edge 侧报的就是这条：CredMan ... (Cannot parse passkey key)
            AutofillLogger.d(
                "PK aborted: cannot parse keyValue (${WebAuthn.describeEcPrivateKeyFailure(cred.keyValue)})",
            )
            fail(GetCredentialUnknownException("Cannot parse passkey key"))
            return
        }
        runCatching {
            val json = JSONObject(requestJson)
            val challenge = decodeChallenge(json.getString("challenge"))
            // ⚠️ **两条流程的 clientDataJSON 是两种东西，不能混用（2026-09-11 修正）**
            //
            // 之前的写法（已证伪）：浏览器流程里"逐字节复刻浏览器的 clientDataJSON"再回传。
            // 之所以错，是因为 RP 校验时用的 clientDataJSON 是**网页交给它的那一份**，
            // 不是 authenticator 回传的那一份；而 provider 根本拿不到浏览器那份 JSON 的
            // 明文（浏览器只给 32 字节 SHA-256 哈希）。自造的 JSON 永远不可能与浏览器
            // 逐字节相同 → RP 拿自己的 JSON 重新哈希后与签名里的哈希对不上 → 验签失败
            // → 站点报 "Authentication failed"。
            //
            // 官方口径（Android 凭据提供方文档，developer.android.com/identity/sign-in/credential-provider）：
            // "use the clientDataHash that's provided directly in ... GetPublicKeyCredentialOption()
            //  instead of assembling and hashing clientDataJSON during the signature request.
            //  To avoid JSON parsing issues, **set a placeholder value for clientDataJSON
            //  in the attestation and assertion response**."
            //
            // 所以分两条路：
            // - 浏览器流程（有 clientDataHash）：签名只覆盖 `authData ‖ clientDataHash`（系统给的哈希，
            //   即浏览器那份真实 JSON 的哈希）；回传的 clientDataJSON 只放占位符 —— 它不参与
            //   任何密码学校验，只为了填满协议字段。
            // - 原生 App 流程（没有 clientDataHash）：本模块自己拼 JSON、自己哈希、自己签，
            //   回传的必须是**同一份** JSON（这种场合 RP 用的就是 provider 给的那份）。
            //
            // 参考实现对照：Bitwarden 把这件事整体交给 SDK（`ClientData.DefaultWithCustomHash(hash)`
            // 与 `DefaultWithExtraData(androidPackageName)` 二选一，Android 侧从不重建 JSON）；
            // Bastion / Keyguard 与旧版 Vaultix 一样仍在重建 JSON，属于同一类缺陷。
            val clientDataBytes = if (clientDataHash != null) {
                WebAuthn.BROWSER_FLOW_CLIENT_DATA_PLACEHOLDER
            } else {
                WebAuthn.buildClientDataJson(
                    type = "webauthn.get",
                    challenge = challenge,
                    origin = origin,
                    includeCrossOrigin = true,
                )
            }
            if (clientDataHash != null) {
                // 现场诊断：占位符方案下这里恒为 false，属**预期行为**，不再是故障信号。
                AutofillLogger.d(
                    "assertion origin=$origin browserFlow=true " +
                        "jsonMatchesBrowserHash=" +
                        "${WebAuthn.clientDataJsonMatchesHash(clientDataBytes, clientDataHash)} " +
                        "pkg=$callerPackageName",
                )
            }
            val authData = WebAuthn.buildAuthenticatorData(
                rpId = rpId,
                userPresent = true,
                userVerified = true,
                // ⚠️ **signCount 必须恒为 0**（对齐 Bastion `PasskeyAuthActivity`：`newSignCount = 0L`）。
                //
                // WebAuthn §6.1.1 明确允许 authenticator 始终返回 0，表示「本 authenticator 不实现
                // 计数器」——RP 据此跳过单调性校验。Bitwarden / 1Password / iCloud Keychain 这类
                // 「同步型 passkey」全部走这条路。
                //
                // **不能改读库里的值。** 规范对计数器的校验是 `new > stored`（严格大于）：
                // 若库中存着非零 counter（Bitwarden 官方客户端每签一次会递增并写回服务端，
                // 同步下来就是非零），「原样发送但不递增」会导致第二次登录发出与上次**相同**的值，
                // `new > stored` 不成立 → RP 判定重放并拒绝整条断言。
                // 这是 2026-09-11 一次错误改动的回退（那次把 0 改成了读库）。
                counter = 0,
                withAttested = false,
            )
            val signature = if (clientDataHash != null) {
                // 浏览器流程：**只能**用系统给的哈希（浏览器那份真实 JSON 的 SHA-256）。
                // 自己重算 clientDataJSON 再哈希必然对不上（见上）。
                WebAuthn.signAssertionHash(authData, clientDataHash!!, key)
            } else {
                // 原生 App 流程：本模块自己拼 JSON、自己哈希、自己签，三处用的是同一份字节。
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
            AutofillLogger.d(
                "PK assertion ready sigLen=${signature.size} authDataLen=${authData.size} " +
                    "cdjLen=${clientDataBytes.size} browserFlow=${clientDataHash != null}",
            )
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }.onFailure { fail(GetCredentialUnknownException(it.message)) }
    }

    private fun decodeChallenge(b64: String): ByteArray =
        runCatching { Base64.decode(b64, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) }.getOrNull()
            ?: Base64.decode(b64, Base64.DEFAULT)

    private fun fail(e: GetCredentialException) {
        AutofillLogger.d("PK fail: ${e.javaClass.simpleName}: ${e.message}")
        val resultIntent = Intent()
        PendingIntentHandler.setGetCredentialException(resultIntent, e)
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    private fun cancel() {
        AutofillLogger.d("PK cancelled by user / biometric error")
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
