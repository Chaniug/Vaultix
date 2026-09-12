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
import io.vaultix.data.repository.VaultSessionManager
import io.vaultix.domain.ItemRepository
import io.vaultix.model.VaultFido2Credential
import io.vaultix.vaultix.R
import io.vaultix.vaultix.autofill.AutofillLogger
import io.vaultix.vaultix.autofill.match.UriMatcher
import io.vaultix.vaultix.ui.theme.VaultixTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
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

    /**
     * 只用于**读锁态快照**（[VaultSessionManager.isUnlocked] / [VaultSessionManager.isAnyUnlocked]）。
     *
     * 对齐 Bitwarden `CredentialProviderProcessorImpl` 的 `userState.activeAccount.isVaultUnlocked`
     * 判定：凭据链路上必须能区分「库锁定」与「条目不存在」，否则会把锁定误报成错误。
     * 密钥材料仍只经 [ItemRepository] 在解锁会话内取，本字段不持有任何密钥。
     */
    @Inject
    lateinit var sessions: VaultSessionManager

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

    /**
     * 用户是否已通过设备验证（生物识别 / 设备凭据）。
     *
     * 照抄 Bitwarden `BitwardenCredentialManagerImpl.isUserVerified`（第 71 行）的语义：
     * **签名前必须为 true**；流程结束（成功 / 失败 / 取消）后必须复位为 false，避免
     * 上一次验证结果"泄漏"到下一次请求。
     */
    private var isUserVerified: Boolean = false

    /**
     * 本次流程内已失败的验证尝试次数。
     *
     * 照抄 Bitwarden 的簿记（第 73、153-154 行）：
     * ```
     * override var authenticationAttempts: Int = 0
     * override fun hasAuthenticationAttemptsRemaining(): Boolean =
     *     authenticationAttempts < MAX_AUTHENTICATION_ATTEMPTS   // private const val ... = 5
     * ```
     * 达到上限即不再弹生物识别，直接走失败，避免用户被无限次弹窗"锁住"。
     */
    private var authenticationAttempts: Int = 0

    /** 是否还有剩余验证尝试（对齐 Bitwarden `hasAuthenticationAttemptsRemaining`）。 */
    private fun hasAuthenticationAttemptsRemaining(): Boolean =
        authenticationAttempts < MAX_AUTHENTICATION_ATTEMPTS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 注：旧版在这里调用 CredentialFlowGuard.markFlowStarted() 来"豁免"自动锁定。
        // 该启发式已被**结构性豁免**取代（对齐 Bitwarden）：拉起本 Activity 的
        // CredentialProviderActivity 会以 createdForAutofill = true 通知
        // VaultLockManager.onAppCreated()，由 VaultTimeout.OnAppRestart 的豁免分支处理，
        // 不再需要时间戳窗口。

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

        // ⚠️ **origin 的取法照抄 Bitwarden `authenticateFido2Credential`**：
        //
        // Bitwarden 原文（`BitwardenCredentialManagerImpl` 第 103-140 行）：
        // ```
        // val clientData = request.clientDataHash
        //     ?.let { ClientData.DefaultWithCustomHash(hash = it) }
        //     ?: ClientData.DefaultWithExtraData(androidPackageName = callingAppInfo.getAppOrigin())
        // val sdkOrigin = if (!origin.isNullOrEmpty()) {
        //     Origin.Web(origin)
        // } else {
        //     val hostUrl = getOriginUrlFromAssertionOptionsOrNull(request.requestJson)
        //         ?: return Fido2CredentialAssertionResult.Error.MissingHostUrl
        //     Origin.Android(UnverifiedAssetLink(packageName, sha256CertFingerprint, hostUrl, ...))
        // }
        // ```
        //
        // 关键在 `getOriginUrlFromAssertionOptionsOrNull`：**主机名来自请求 JSON 里的 rpId**
        // （`PasskeyAssertionOptions.relyingPartyId`，补 https://），而**不是**来自浏览器。
        // 这正是「资产链接校验通过的 App」（`ValidateOriginResult.Success(origin = null)`）
        // 也能拿到正确 rpId 的原因 —— 它不依赖 `callingAppInfo.origin`。
        //
        // Vaultix 此前的取法多了一级兜底 `?: "https://$rpId"`，但**顺序错了**：它把
        // 「浏览器给的 origin」排在「请求 JSON 的 rpId」之前，且在两者都空时**构造**一个
        // 猜的 origin（rpId 可能是空串，就得到 "https://"）。现按 Bitwarden 的顺序重排。
        val originFromCaller = callingOrigin?.takeIf { it.isNotBlank() }
        val originFromRequest = runCatching {
            val json = JSONObject(requestJson)
            val host = UriMatcher.hostOf(json.optString("rpId", ""))
            host?.let { prefixHttpsIfNecessary(it) }
        }.getOrNull()
        // 判定「本次是否走浏览器/外部哈希流程」：有 clientDataHash 即外部给哈希。
        val browserFlow = clientDataHash != null
        origin = originFromCaller ?: originFromRequest.orEmpty()
        AutofillLogger.d(
            "PK origin caller=${originFromCaller ?: "-"} request=${originFromRequest ?: "-"} " +
                "resolved=${origin.ifBlank { "-" }} pkg=$callerPackageName browserFlow=$browserFlow",
        )

        if (listOf(vaultId, itemId, credentialId, requestJson).any { it.isBlank() }) {
            fail(GetCredentialUnknownException("Missing passkey parameters"))
            return
        }
        // ⚠️ **原生流程（无 clientDataHash）必须有可用 origin**，否则自拼的 clientDataJSON
        // 会带一个空/伪造的 origin，RP 必然拒绝。对齐 Bitwarden 的 `Error.MissingHostUrl`：
        // 此时宁可明确失败，也不要发出注定被拒的断言。
        if (!browserFlow && origin.isBlank()) {
            AutofillLogger.d("PK aborted: native flow but no origin/host url (Bitwarden: MissingHostUrl)")
            fail(GetCredentialUnknownException("Missing host url"))
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val item = runCatching { itemRepository.observeItem(vaultId, itemId).first() }.getOrNull()
            val cred = item?.fido2Credentials?.firstOrNull { it.credentialId == credentialId }
            // ⚠️ **必须把「库锁定」与「凭证不存在」分开**（2026-09-11 修正，对齐 Bitwarden）
            //
            // 旧写法（已证伪）：拿不到 cred 就一律 `fail("Passkey not found")`。
            // 但 `ItemRepositoryImpl.observeState` 在**库未解锁**时恒发空列表（安全设计，正确），
            // 于是「库在候选展示后重新上锁」会被误报成「通行密钥不存在」——一条**假错误**，
            // 把用户引向排查库里有没有这条密钥，而真实原因是会话被锁。
            //
            // Bitwarden 的做法（`CredentialProviderProcessorImpl` + `RootNavViewModel`）是把
            // 锁定态**单独成一路**：锁定 → 返回解锁动作 / 跳解锁界面，绝不报「找不到」。
            // 这里照抄该语义：先探测锁定态，锁定时走解锁引导并把**解锁引导**作为结果回灌。
            val vaultUnlocked = sessions.isUnlocked(vaultId)
            AutofillLogger.d(
                "PK lookup itemFound=${item != null} credFound=${cred != null} " +
                    "vaultUnlocked=$vaultUnlocked anyUnlocked=${sessions.isAnyUnlocked()}",
            )
            withContext(Dispatchers.Main) {
                if (cred == null) {
                    // 分支一：库（或全部库）锁定 → 引导解锁，不用「找不到」误导用户。
                    if (!vaultUnlocked || !sessions.isAnyUnlocked()) {
                        unlockAndFinish()
                        return@withContext
                    }
                    // 分支二：库确实已解锁，凭证明细确实不在 → 这才是真的「找不到」。
                    AutofillLogger.d("PK lookup: unlocked but credential missing")
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

    /**
     * 库锁定时的出路。
     *
     * 2026-09-11 修正：**不再由本 Activity 自己 `startActivity` 拉起解锁界面**。
     *
     * 原因（对齐 Bitwarden 的两段式语义）：Credential Manager 的认证动作是
     * 「**先解锁、再重新发起请求**」——系统在用户完成动作后会重新调用
     * `onBeginGetCredentialRequest`，届时库已解锁，正常返回候选。
     * 而本 Activity 是被**候选点击**拉起的（说明列候选时库是解锁的），
     * 此时若发现库已锁，正确处置是**结束本次断言并回灌取消**，让系统回到
     * 「重新列候选 → 发现锁定 → 给出解锁动作」这条正路；
     * 自己另起一个解锁 Activity 会打断系统对本次 Credential Manager 请求的追踪，
     * 表现为「解锁了但浏览器没反应」。
     *
     * 所以这里只做两件事：打日志 + 回灌取消（不是错误，是「本次改走解锁引导」）。
     */
    private fun unlockAndFinish() {
        AutofillLogger.d("PK locked → cancel assertion (system will re-list & offer unlock action)")
        cancel()
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
        // 对齐 Bitwarden：尝试次数用尽 → 不再弹窗，直接判定失败（见 hasAuthenticationAttemptsRemaining）。
        if (!hasAuthenticationAttemptsRemaining()) {
            AutofillLogger.d("PK biometric attempts exhausted ($authenticationAttempts/$MAX_AUTHENTICATION_ATTEMPTS)")
            isUserVerified = false
            fail(GetCredentialUnknownException("Too many attempts"))
            return
        }
        AutofillLogger.d("PK biometric requested attempt=$authenticationAttempts")
        val executor = ContextCompat.getMainExecutor(this)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                // 成功：复位尝试计数（对齐 Bitwarden `handleUserVerificationSuccess`：attempts = 0）。
                isUserVerified = true
                authenticationAttempts = 0
                sign()
            }

            override fun onAuthenticationFailed() {
                // 单次失败（如指纹不匹配）系统会让用户直接重试，不计入 lockout；
                // 但本地计数递增，避免绕过尝试上限（对齐 Bitwarden 失败分支 attempts += 1）。
                authenticationAttempts += 1
                isUserVerified = false
                AutofillLogger.d("PK biometric failed attempt=$authenticationAttempts")
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // 不可恢复错误（取消 / 用户回退 / 系统 lockout）→ 复位并结束（对齐
                // Bitwarden `handleUserVerificationCancelled`：isUserVerified = false）。
                isUserVerified = false
                cancel()
            }
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
        // 照抄 Bitwarden 的调用契约：签名只在「设备验证已完成」之后发生
        // （其 `authenticateFido2Credential` 仅在 `isUserVerified == true` 或
        //  RP 要求 DISCOURAGED 时才被调到）。这里做一次防御性断言。
        if (!isUserVerified) {
            AutofillLogger.d("PK aborted: sign() called while not user-verified")
            fail(GetCredentialUnknownException("User not verified"))
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
            // ⚠️ **两条流程的 clientDataJSON 是两种东西，不能混用（2026-09-12 二次修正）**
            //
            // 上一版（2026-09-11）在此回传 `ByteArray(0)` 占位符，依据是官方文档那句
            // "set a placeholder value for clientDataJSON"。该句有**前置条件**
            // `If you retrieve an origin` —— 特指经 `CallingAppInfo.getOrigin(privilegedAllowlist)`
            // + 特权应用名单拿到 origin 的场景（Google Password Manager 走那条路）。
            // Vaultix 的 `CallingAppOrigin` 走「自证式读取」、**不用特权名单**，故不适用。
            //
            // 规范层面 RP 一定会解析 clientDataJSON **明文**逐项校验（W3C WebAuthn L2 §7.2）：
            //   - `C.type` 必须为 "webauthn.get"；
            //   - `C.challenge` 必须等于 base64url(options.challenge)；
            //   - `C.origin` 必须与 RP origin 匹配。
            // 空字节数组连 JSON 解析都过不了 → 站点报 "Security key authentication failed"。
            //
            // 两条流程的**唯一差别**是签名覆盖哪份哈希（见下方 signature 分支）；
            // 回传的 clientDataJSON 一律是这份自建的真实 JSON —— 它供 RP 读明文校验，
            // 与签名用的哈希互不冲突。androidPackageName 一律传 null：浏览器那份 JSON
            // 没有该字段，写进去会让 RP 重算哈希时与浏览器签名值不符（Bastion 实测：
            // Microsoft 登录失败）。
            val clientDataBytes = WebAuthn.buildGetClientDataJson(
                challenge = challenge,
                origin = origin,
                includeCrossOrigin = true,
                androidPackageName = null,
            )
            // 仅日志诊断：浏览器流程下通常为 false 属预期（系统那份哈希来自浏览器）。
            AutofillLogger.d(
                "assertion origin=$origin browserFlow=${clientDataHash != null} " +
                    "cdjLen=${clientDataBytes.size} jsonMatchesBrowserHash=" +
                    "${WebAuthn.clientDataJsonMatchesHash(clientDataBytes, clientDataHash)} " +
                    "pkg=$callerPackageName",
            )
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
            // ⚠️ **rawId 必须用库里那份文本原样回传（2026-09-12 根因修复）**
            //
            // 旧实现是 `decodeBase64UrlOrStandard(cred.credentialId)?.let { buildGetResponseJson(it, ...) }`，
            // 即「把 b64url 文本解码成字节，再在 buildGetResponseJson 里 base64Url() 编码回去」。
            // 这在合法 b64url 上表现为恒等变换，看似无害，实际有两处会**逐字节失配**：
            //  a) 标准 Base64（`+`/`/`）写的 ID 被规范化成 b64url（`-`/`_`）→ 文本不同；
            //  b) 解码抛异常时被 `?:` 兜底成 `text.toByteArray(UTF_8)` → 直接把 Base64 文本当 ID 发出去。
            // RP 对 `rawId` 做的是与 `allowCredentials[].id` 的**字节比对**，任何一处失配都会
            // 让整条断言被判为「未知凭证」——这正是真机「候选能列出、能选、最后一步校验报错」的原因。
            //
            // 注册侧（PasskeyCreateActivity:279/329）写库与回传都用 `WebAuthn.base64Url(key.credentialId)`，
            // 两侧同源，故此处直接用存储文本即可闭环。详见 [WebAuthn.rawIdFromStored]。
            //
            // userHandle 同陷阱：库里的 `userHandle` 是**注册时原样存下的 Base64 文本**
            // （PasskeyCreateActivity:293 取自请求 JSON 的 `user.id`，line 300 直接落库，未解码）。
            // 因此这里也不能 decode→re-encode，直接原样回传。
            val responseJson = WebAuthn.buildGetResponseJsonFromStoredId(
                storedCredentialId = cred.credentialId,
                clientDataJson = clientDataBytes,
                authData = authData,
                signature = signature,
                userHandleText = cred.userHandle?.takeIf { it.isNotBlank() },
            )
            val storedIdForm = WebAuthn.describeStoredIdForm(cred.credentialId)
            AutofillLogger.d(
                "PK assertion ready sigLen=${signature.size} authDataLen=${authData.size} " +
                    "cdjLen=${clientDataBytes.size} browserFlow=${clientDataHash != null} " +
                    "rawId=${WebAuthn.rawIdFromStored(cred.credentialId)} storedIdForm=$storedIdForm",
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

    /**
     * 给裸主机名补 `https://`（照抄 Bitwarden `String.prefixHttpsIfNecessary`）。
     *
     * Bitwarden 的判定是「已是合法 URI 且含 http(s) scheme → 原样；否则前缀 https://」。
     * 本处输入已由 [UriMatcher.hostOf] 归一化为裸主机名（无 scheme / 无端口 / 小写），
     * 故直接前缀即可；保留函数以便与上游语义一一对应。
     */
    private fun prefixHttpsIfNecessary(host: String): String =
        if (host.contains("://")) host else "https://$host"

    private fun fail(e: GetCredentialException) {
        // 关键：任何终结路径都必须复位验证态（对齐 Bitwarden `handleFido2AssertionResultReceive`
        // 首行 `isUserVerified = false`），否则同一实例复用时会带着上次的"已验证"结论。
        isUserVerified = false
        AutofillLogger.d("PK fail: ${e.javaClass.simpleName}: ${e.message}")
        val resultIntent = Intent()
        PendingIntentHandler.setGetCredentialException(resultIntent, e)
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    private fun cancel() {
        isUserVerified = false
        AutofillLogger.d("PK cancelled by user / biometric error")
        val resultIntent = Intent()
        PendingIntentHandler.setGetCredentialException(resultIntent, GetCredentialCancellationException("Cancelled"))
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }

    private companion object {
        /** 验证尝试上限，照抄 Bitwarden `private const val MAX_AUTHENTICATION_ATTEMPTS = 5`。 */
        const val MAX_AUTHENTICATION_ATTEMPTS = 5
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
