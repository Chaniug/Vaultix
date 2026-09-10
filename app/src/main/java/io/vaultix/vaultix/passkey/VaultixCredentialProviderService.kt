/*
 * Vaultix — app:passkey
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * Credential Provider（M3 最高优先批次）：Android 14+ 系统层凭据供应方。
 *
 * 根因（见 next-steps「Edge 填充失效根因」）：Chromium（Chrome/Edge）在 Android 14+/17 上
 * 取凭据优先走 Credential Manager；不注册 `CredentialProviderService`，系统根本不会向 Vaultix
 * 询问 passkey —— 表现为「网站登录时通行密钥不出现」。注册后即被系统识别。
 *
 * 行为：
 *  - onBeginGetCredentialRequest：
 *      * 通行密钥（public-key）：按请求 rpId 匹配已解锁库的 passkey → 列 PublicKeyCredentialEntry；
 *      * 密码（password）：列已解锁库的 Login 条目 → PasswordCredentialEntry（点击经 PasswordGetActivity
 *        取解密后的明文用户名/密码回灌 Credential Manager）；
 *      * 全部库锁定时，列一条「解锁 Vaultix」入口（按选项类型生成对应 Entry），其 PendingIntent 复用
 *        AutofillActivity 解锁链（与自动填充下拉的解锁同款：用户解锁后回 Edge 重新触发，届时库已解锁）。
 *  - onBeginCreateCredentialRequest：列 CreateEntry → 引导用户在 Vaultix 内创建并绑定到登录条目。
 *  - onClearCredentialStateRequest：Vaultix 不维护登录态，直接成功。
 *
 * 重要：注册 CredentialProviderService 后，Android 14+ 的 Credential Manager 会接管「登录字段」的
 * 自动填充（次要 autofill = com.android.credentialmanager），并同时向所有启用方询问 密码 + 通行密钥。
 * 因此本服务必须同时声明 password 能力（见 credential_provider.xml）：若只声明 public-key，系统只见
 * passkey、密码框为空、传统 VaultixAutofillService 在凭据字段上被绕过（即「启用 provider 后密码弹框消失」）。
 */
package io.vaultix.vaultix.passkey

import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import androidx.annotation.RequiresApi
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.AuthenticationAction
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginCreatePublicKeyCredentialRequest
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.BeginGetPasswordOption
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.credentials.provider.BiometricPromptData
import androidx.credentials.provider.CreateEntry
import androidx.credentials.provider.CredentialEntry
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.PasswordCredentialEntry
import androidx.credentials.provider.PublicKeyCredentialEntry
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import dagger.hilt.android.AndroidEntryPoint
import io.vaultix.common.WebAuthn
import io.vaultix.domain.ItemRepository
import io.vaultix.domain.VaultRepository
import io.vaultix.model.VaultFido2Credential
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import io.vaultix.vaultix.R
import io.vaultix.vaultix.autofill.AutofillIntents
import io.vaultix.vaultix.autofill.AutofillLogger
import io.vaultix.vaultix.autofill.engine.AutofillCredentialMapper
import io.vaultix.vaultix.autofill.match.BitwardenLikeAutofillMatcher
import io.vaultix.vaultix.autofill.match.UriMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import javax.crypto.Cipher
import javax.inject.Inject

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
@AndroidEntryPoint
class VaultixCredentialProviderService : CredentialProviderService() {

    @Inject
    lateinit var vaultRepository: VaultRepository

    @Inject
    lateinit var itemRepository: ItemRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBeginGetCredentialRequest(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>,
    ) {
        // 对齐 Bitwarden `processGetCredentialRequest`：必须接收取消信号，否则系统
        // 在用户切走 / 请求超时后仍等待回调，表现为「列表长时间空白后消失」。
        val job = serviceScope.launch {
            runCatching { buildGetResponse(request) }
                .onSuccess {
                    log("GET ok entries=${it.credentialEntries.size} actions=${it.authenticationActions.size}")
                    callback.onResult(it)
                }
                .onFailure {
                    // 现场排障关键：任何异常都会让整张凭据列表为空（浏览器表现为「毫无反应」）。
                    log("GET failed: ${it.javaClass.simpleName}: ${it.message}")
                    callback.onError(GetCredentialUnknownException(it.message))
                }
        }
        cancellationSignal.setOnCancelListener {
            log("GET cancelled by system")
            job.cancel()
            callback.onError(GetCredentialCancellationException("Cancelled"))
        }
    }

    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>,
    ) {
        val job = serviceScope.launch {
            runCatching { buildCreateResponse(request) }
                .onSuccess { callback.onResult(it) }
                .onFailure { callback.onError(CreateCredentialUnknownException(it.message)) }
        }
        cancellationSignal.setOnCancelListener {
            log("CREATE cancelled by system")
            job.cancel()
            callback.onError(CreateCredentialCancellationException("Cancelled"))
        }
    }

    override fun onClearCredentialStateRequest(
        request: ProviderClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, ClearCredentialException>,
    ) {
        // Vaultix 不维护登录态（密钥只在内存，重启必锁），直接成功。
        callback.onResult(null)
    }

    // ===================== GET =====================

    private suspend fun buildGetResponse(request: BeginGetCredentialRequest): BeginGetCredentialResponse {
        val pkOptions = request.beginGetCredentialOptions.filterIsInstance<BeginGetPublicKeyCredentialOption>()
        val pwOptions = request.beginGetCredentialOptions.filterIsInstance<BeginGetPasswordOption>()
        // 调用来源：Chromium 系浏览器会带 origin（如 https://github.com），普通 App 只有包名。
        // ⚠️ 该值用于**密码条目的域名过滤**（对齐 Bitwarden filterCiphersForMatches 的
        // `callingAppInfo.packageName` / origin 口径）。取不到时不做过滤（宁可多列，不可漏列）。
        val callingAppInfo = request.callingAppInfo
        val callingOrigin = CallingAppOrigin.originOrNull(callingAppInfo)
        val callingPackage = callingAppInfo?.packageName
        log(
            "GET options pk=${pkOptions.size} pw=${pwOptions.size} " +
                "total=${request.beginGetCredentialOptions.size} " +
                "caller=$callingPackage origin=${callingOrigin ?: "-"}",
        )
        if (pkOptions.isEmpty() && pwOptions.isEmpty()) return BeginGetCredentialResponse.Builder().build()

        val unlocked = vaultRepository.observeUnlockedVaultIds().first()
        // 全库快照用于判断「是否有库仍锁定」（VaultSummary.unlocked 由仓储维护，比自查 unlocked 集合更权威）。
        val lockedCount = runCatching { vaultRepository.observeVaults().first().count { !it.unlocked } }
            .getOrDefault(0)
        log("GET unlocked=${unlocked.size} locked=$lockedCount")

        if (unlocked.isEmpty()) {
            // ⚠️ **全部库锁定：必须走 `authenticationActions`，而不是往 `credentialEntries` 里塞
            // 一条"解锁"条目。**（此前实现即错在此，导致锁定时点解锁毫无反应。）
            //
            // 对齐 Bitwarden `CredentialProviderProcessorImpl.processGetCredentialRequest`：
            // ```
            // if (!userState.activeAccount.isVaultUnlocked) {
            //     val authenticationAction = AuthenticationAction(
            //         title = context.getString(BitwardenString.unlock),
            //         pendingIntent = pendingIntentManager.createFido2UnlockPendingIntent(...),
            //     )
            //     callback.onResult(BeginGetCredentialResponse(
            //         authenticationActions = listOf(authenticationAction)))
            //     return
            // }
            // ```
            // `credentialEntries` 是**凭据**通道（系统会当作"可以填的东西"处理，要求回灌
            // `setGetCredentialResponse`）；`authenticationActions` 是**认证动作**通道
            // （系统渲染为独立的"解锁"操作，不期待凭据回灌）。把解锁项塞进凭据通道
            // → 系统按凭据语义处理 → 点击后既拿不到凭据、也不是认证动作 → 表现为"点不开"。
            log("GET locked → authenticationActions")
            return BeginGetCredentialResponse.Builder()
                .setAuthenticationActions(listOf(unlockAction()))
                .build()
        }

        val entries = mutableListOf<CredentialEntry>()
        for (option in pwOptions) {
            entries += passwordEntries(option, unlocked, callingOrigin, callingPackage)
        }
        for (option in pkOptions) {
            val matched = resolvePasskeys(option, unlocked)
            log("GET rpId matched pk=${matched.size}")
            for (m in matched) {
                entries += publicKeyEntry(option, m)
            }
        }
        // 部分库仍锁定时，把「解锁 Vaultix」作为认证动作一起返回（凭据通道与认证动作通道
        // 可并存）：用户可以就地解锁其余库，无需先清空候选。对齐 Bitwarden 的
        // 「未解锁账号也纳入解锁引导」语义（Bitwarden 单账号场景下即整库锁定分支）。
        val actions = if (lockedCount > 0) listOf(unlockAction()) else emptyList()
        log("GET entries=${entries.size} actions=${actions.size}")
        return BeginGetCredentialResponse.Builder()
            .setCredentialEntries(entries)
            .setAuthenticationActions(actions)
            .build()
    }

    /**
     * 库锁定时的解锁动作（`authenticationActions` 通道）。
     *
     * 与"把解锁项塞进 credentialEntries"的做法关键区别：本方式**不绑定具体的凭据选项**，
     * 因为认证动作是"先解锁、再重新发起请求"的两段式流程 —— 系统在用户完成动作后会
     * 重新调用 `onBeginGetCredentialRequest`，届时库已解锁，正常返回凭据。
     *
     * 对齐 Bitwarden `createFido2UnlockPendingIntent`：显式 action + 显式 Activity class，
     * `FLAG_MUTABLE`，**不加 NEW_TASK**（见其 KDoc 警告）。
     */
    private fun unlockAction(): AuthenticationAction {
        val intent = AutofillIntents.create(
            context = this,
            mode = AutofillIntents.MODE_UNLOCK,
            title = getString(R.string.credential_unlock_title),
            subtitle = getString(R.string.credential_unlock_subtitle),
        )
        val pendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_UNLOCK_CP,
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return AuthenticationAction.Builder(
            getString(R.string.credential_unlock_title),
            pendingIntent,
        ).build()
    }

    /**
     * 已解锁库的 Login 条目 → PasswordCredentialEntry（不预填明文，点击经 PasswordGetActivity 取密回灌）。
     *
     * 与通行密钥分支对齐：**按调用来源过滤**（对齐 Bitwarden
     * `filterCiphersForMatches(matchUri = ...)`）。此前不过滤 → 一打开密码框就列出
     * 全库几十条无关站点，且浏览器的「只显示相关凭据」预期被打破。
     *
     * 过滤器选用 Vaultix 既有的 [BitwardenLikeAutofillMatcher]（同一套 eTLD+1 / 等价域 /
     * androidapp:// 规则），保证 CP 通道与老 autofill 通道的匹配语义**完全一致**。
     * origin / 包名都取不到时**不过滤**（宁可多列，不可漏列 —— 用户至少能看到条目）。
     */
    private suspend fun passwordEntries(
        option: BeginGetPasswordOption,
        unlocked: Set<String>,
        callingOrigin: String?,
        callingPackage: String?,
    ): List<CredentialEntry> {
        val result = mutableListOf<CredentialEntry>()
        for (vaultId in unlocked) {
            val items = runCatching { itemRepository.observeItems(vaultId).first() }.getOrDefault(emptyList())
            val logins = items.filter { isUsablePasswordItem(it) }
            if (logins.isEmpty()) continue
            val filtered = filterByCaller(logins, callingOrigin, callingPackage)
            log("GET pw vault=$vaultId usable=${logins.size} matched=${filtered.size}")
            for (item in filtered) {
                result += passwordEntry(option, vaultId, item, filtered.size)
            }
        }
        return result
    }

    /**
     * 按调用来源过滤登录条目。
     *
     * [callingOrigin] 形如 `https://github.com`（浏览器）；[callingPackage] 形如
     * `com.microsoft.emmx`（浏览器自身包名，或普通 App 包名）。两者都为空白时返回原列表。
     *
     * ⚠️ 浏览器场景**只用 origin 不用包名**：包名是浏览器自己（Edge/Chrome），拿它去匹配
     * 条目 URI 会全部落空（条目存的是网站 URI，不是浏览器包名）。
     */
    private fun filterByCaller(
        logins: List<VaultItem>,
        callingOrigin: String?,
        callingPackage: String?,
    ): List<VaultItem> {
        val webDomain = callingOrigin?.let { UriMatcher.hostOf(it) }
        val packageForMatch = if (webDomain.isNullOrBlank()) callingPackage else null
        if (webDomain.isNullOrBlank() && packageForMatch.isNullOrBlank()) return logins

        val byId = logins.associateBy { it.id }
        val credentials = logins.map { AutofillCredentialMapper.toCredential(it.id, it) }
        val matched = BitwardenLikeAutofillMatcher.match(
            credentials = credentials,
            packageName = packageForMatch,
            webDomain = webDomain,
        )
        // 匹配器返回按相关度排序的结果，按其 itemId 还原为 VaultItem。
        return matched.mapNotNull { byId[it.itemId] }
    }

    private fun isUsablePasswordItem(item: VaultItem): Boolean {
        if (item.type != VaultItemType.Login) return false
        return item.username.isNotBlank() || item.password.isNotBlank()
    }

    private fun passwordEntry(
        option: BeginGetPasswordOption,
        vaultId: String,
        item: VaultItem,
        siblingCount: Int,
    ): CredentialEntry {
        val intent = PasskeyProviderIntents.passwordGetIntent(this, vaultId, item.id)
        val pendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_PASSWORD_CP_BASE + item.id.hashCode(),
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val username = item.username.ifBlank { item.title }
        val builder = PasswordCredentialEntry.Builder(this, username, pendingIntent, option)
            .setDisplayName(item.title.ifBlank { item.username })
            // 对齐 Bitwarden：仅当只有一条候选时允许系统自动选中，避免多条时误填。
            .setAutoSelectAllowed(siblingCount == 1)
            .setIcon(Icon.createWithResource(this, R.drawable.ic_passkey))
        applyBiometricPromptDataIfSupported(builder)
        return builder.build()
    }

    /** 跨已解锁库扁平化所有登录条目的 fido2，按 rpId（+ allowCredentials）匹配。 */
    private suspend fun resolvePasskeys(
        option: BeginGetPublicKeyCredentialOption,
        unlocked: Set<String>,
    ): List<PasskeyMatch> {
        val json = runCatching { JSONObject(option.requestJson) }.getOrNull() ?: return emptyList()
        val rpId = json.optString("rpId", "").lowercase()
        if (rpId.isBlank()) return emptyList()
        val allowed = parseAllowedCredentialIds(json)

        val result = mutableListOf<PasskeyMatch>()
        for (vaultId in unlocked) {
            val items = runCatching { itemRepository.observeItems(vaultId).first() }.getOrDefault(emptyList())
            for (item in items) {
                if (item.type != VaultItemType.Login) continue
                for (cred in item.fido2Credentials) {
                    // ⚠️ **必须过滤不可用记录**（对齐 Bastion PasskeyCredentialDiscoveryPolicy
                    // .isUsable：privateKeyAlias 非空才算可用；Vaultix 的等价条件即 keyValue 非空）。
                    // 真机实证（2026-09-10）：库中存在「只有公钥登记、keyValue 缺失」的残缺 passkey
                    // （Bitwarden 官方端同步下来的未完成注册残留）。此前不过滤 → 它被当成正常候选
                    // 列进凭据列表，用户点它必然走不通签名，浏览器的报错文案是
                    // 「Cannot parse passkey key」——与真正的解析失败**表象相同**，极易误诊为
                    // 解析器 bug（此前即误判在此）。列出不可用记录本身也是 UX 缺陷：
                    // 用户会看到一条永远点不通的通行密钥。
                    if (!isUsablePasskey(cred)) continue
                    if (cred.rpId.equals(rpId, ignoreCase = true) &&
                        (allowed.isEmpty() || allowed.any { idMatches(cred.credentialId, it) })
                    ) {
                        result += PasskeyMatch(vaultId, item.id, cred, item.title)
                    }
                }
            }
        }
        return result
    }

    /**
     * 通行密钥可用性判定（对齐 Bastion `PasskeyCredentialDiscoveryPolicy.isUsable`）。
     *
     * 只有 `keyValue`（私钥材料）非空才有签名能力。缺失 keyValue 的记录来自
     * Bitwarden 官方端的「未完成注册」残留，本身不可修复，**只能不展示**。
     */
    private fun isUsablePasskey(cred: VaultFido2Credential): Boolean =
        !cred.keyValue.isNullOrBlank() && cred.credentialId.isNotBlank()

    /** allowCredentials：[{type,id,transports}] 中的 id（base64url）。 */
    private fun parseAllowedCredentialIds(json: JSONObject): List<String> {
        val arr = json.optJSONArray("allowCredentials") ?: return emptyList()
        val ids = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }?.let { ids += it }
        }
        return ids
    }

    /** 容错比较 storedId 与允许列表 id（两种 base64 形态都试，转原始字节比较）。 */
    private fun idMatches(storedId: String, allowed: String): Boolean {
        val a = runCatching { WebAuthn.decodeBase64UrlOrStandard(storedId) }
            .getOrNull() ?: return false
        val b = runCatching { WebAuthn.decodeBase64UrlOrStandard(allowed) }
            .getOrNull() ?: return false
        return a.contentEquals(b)
    }

    private fun publicKeyEntry(option: BeginGetPublicKeyCredentialOption, m: PasskeyMatch): CredentialEntry {
        val intent = PasskeyProviderIntents.getIntent(
            context = this,
            requestJson = option.requestJson,
            vaultId = m.vaultId,
            itemId = m.itemId,
            credentialId = m.credential.credentialId,
            rpId = m.credential.rpId,
            clientDataHash = option.clientDataHash,
        )
        val pendingIntent = PendingIntent.getActivity(
            this,
            m.credential.credentialId.hashCode(),
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val username = m.credential.userName.ifBlank { m.loginTitle.ifBlank { m.credential.rpName } }
        val builder = PublicKeyCredentialEntry.Builder(this, username, pendingIntent, option)
            .setDisplayName(m.credential.rpName.ifBlank { m.credential.rpId })
            .setIcon(Icon.createWithResource(this, R.drawable.ic_passkey))
        applyBiometricPromptDataIfSupported(builder)
        return builder.build()
    }

    // ===================== entry 能力增强 =====================

    /**
     * 按需给凭据条目附加 `BiometricPromptData`（Android 15+ 系统层生物识别流程）。
     *
     * 对齐 Bitwarden `setBiometricPromptDataIfSupported`：**仅当 ROM 支持时才挂**——
     * 小米 HyperOS / 荣耀 MagicOS 等魔改 ROM 挂上后可能导致系统在渲染阶段丢弃整个 entry
     * （表现仍是「浏览器里什么都不弹」，比不挂更糟）。判定见 [RomCompat]。
     *
     * 另：Vaultix 的库密钥只在内存，没有可绑定到条目的 Keystore cipher（见
     * [credentialEntryCipher] 返回 null），因此这里实际落到「不挂」分支，设备验证仍由
     * 点击后的 Activity 完成 —— 与 Vaultix 既有行为一致，只是补齐了扩展点。
     */
    private fun applyBiometricPromptDataIfSupported(builder: PasswordCredentialEntry.Builder): PasswordCredentialEntry.Builder {
        val cipher = credentialEntryCipher()
        return if (RomCompat.biometricPromptDataSupported && cipher != null) {
            builder.setBiometricPromptData(buildPromptDataWithCipher(cipher))
        } else {
            log("GET entry: biometricPromptData skipped (rom=${Build.MANUFACTURER} sdk=${Build.VERSION.SDK_INT})")
            builder
        }
    }

    private fun applyBiometricPromptDataIfSupported(
        builder: PublicKeyCredentialEntry.Builder,
    ): PublicKeyCredentialEntry.Builder {
        val cipher = credentialEntryCipher()
        return if (RomCompat.biometricPromptDataSupported && cipher != null) {
            builder.setBiometricPromptData(buildPromptDataWithCipher(cipher))
        } else {
            builder
        }
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun buildPromptDataWithCipher(cipher: Cipher): BiometricPromptData =
        BiometricPromptData.Builder()
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setCryptoObject(BiometricPrompt.CryptoObject(cipher))
            .build()

    // ===================== CREATE =====================

    private suspend fun buildCreateResponse(request: BeginCreateCredentialRequest): BeginCreateCredentialResponse {
        if (request !is BeginCreatePublicKeyCredentialRequest) {
            throw UnsupportedOperationException("only public-key supported")
        }
        val json = JSONObject(request.requestJson)
        val rp = json.optJSONObject("rp")
        val rpId = rp?.optString("id") ?: json.optString("rpId", "")
        val rpName = rp?.optString("name") ?: rpId
        val user = json.optJSONObject("user")
        val userName = user?.optString("name") ?: ""
        val userDisplayName = user?.optString("displayName") ?: userName

        val intent = PasskeyProviderIntents.createIntent(
            context = this,
            requestJson = request.requestJson,
            rpId = rpId,
            rpName = rpName,
            userName = userName,
            userDisplayName = userDisplayName,
        )
        val pendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_CREATE_CP,
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val entry = CreateEntry.Builder(
            getString(R.string.passkey_create_for, rpName.ifBlank { rpId }),
            pendingIntent,
        )
            .setDescription(userName.ifBlank { rpName.ifBlank { getString(R.string.app_name) } })
            .setIcon(Icon.createWithResource(this, R.drawable.ic_passkey))
            .build()
        return BeginCreateCredentialResponse.Builder().addCreateEntry(entry).build()
    }

    /** 一次 GET 解析出的可匹配凭证（含定位信息，供 Activity 回取私钥）。 */
    private data class PasskeyMatch(
        val vaultId: String,
        val itemId: String,
        val credential: VaultFido2Credential,
        val loginTitle: String,
    )

    /**
     * 现场排障日志（仅 debug 构建；只记选项类型/数量等非敏感元数据，禁记条目内容）。
     *
     * ⚠️ 统一走 [AutofillLogger] 的 `VaultixAutofill` tag（15 字符）：Android 的 log tag
     * 上限为 23 字符，而本类名派生的 tag 长达 27 字符——真机实测该 tag 的日志**一条都进不了
     * logcat**（疑似 ROM 按长度丢弃），导致 CP 全链路"零日志"、排障只能靠猜。
     */
    private fun log(message: String) = AutofillLogger.d("CP $message")

    private companion object {
        const val REQUEST_UNLOCK_CP = 2101
        const val REQUEST_CREATE_CP = 2102
        const val REQUEST_PASSWORD_CP_BASE = 2200
    }
}
