/*
 * Vaultix — app:passkey
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * **凭据候选列表的构建**（从 `VaultixCredentialProviderService` 抽出，2026-09-17）。
 *
 * ## 为什么必须抽出来（这是"解锁后还要手动关掉面板"那个 bug 的修复前置）
 *
 * 库锁定时，服务回给系统的是一个 `AuthenticationAction`（"解锁 Vaultix"）。用户解锁后，
 * **系统不会重新调用 `onBeginGetCredentialRequest`** —— 真机实证（荣耀 BKQ-AN00 / Edge）：
 *
 * ```
 * CP GET locked → authenticationActions     ← 面板弹出「解锁 Vaultix」
 * unlockAllAndFinish: 认证成功 → Success    ← 指纹过了，库真的解锁了
 * （之后再没有任何 CP GET 这一行）
 * ```
 *
 * 所以刷新后的候选列表**必须由我们塞进那次解锁动作的结果 Intent 里**：
 * `PendingIntentHandler.setBeginGetCredentialResponse(intent, response)` + `RESULT_OK`。
 * 上游 Bitwarden 正是这么做的（`CredentialProviderCompletionManagerImpl.
 * completeProviderGetCredentialsRequest` 由**解锁页 / 条目列表页**调用）。
 *
 * 而"构建候选列表"这段逻辑原本**私有在服务里**，解锁 Activity 拿不到 ⇒
 * 抽成这个类，服务与解锁 Activity **共用同一份**（绝不复制：两份实现必然漂移，
 * 而漂移的表现就是"面板里少几条候选"这种极难自查的现象）。
 *
 * ⚠️ 本类只做**纯构建**：不做任何 `setResult`/`finish`，也不碰 UI。收尾由调用方负责。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.passkey

import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Icon
import androidx.annotation.RequiresApi
import androidx.credentials.provider.AuthenticationAction
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.BeginGetPasswordOption
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.credentials.provider.CredentialEntry
import androidx.credentials.provider.PasswordCredentialEntry
import androidx.credentials.provider.PublicKeyCredentialEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import io.vaultix.domain.ItemRepository
import io.vaultix.domain.VaultRepository
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import io.vaultix.vaultix.autofill.engine.AutofillCredentialMapper
import io.vaultix.vaultix.R
import io.vaultix.vaultix.autofill.AutofillIntents
import io.vaultix.vaultix.autofill.AutofillLogger
import io.vaultix.vaultix.autofill.match.BitwardenLikeAutofillMatcher
import io.vaultix.vaultix.autofill.match.UriMatcher
import io.vaultix.vaultix.session.ActiveVaultStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/**
 * 把 `BeginGetCredentialRequest` 变成候选列表。
 *
 * @param context 只用来取字符串 / 建 PendingIntent / 建 `Icon` —— **不要**在长命对象里
 *   持有 Activity（本类是 `@Singleton`，注入的是 application context）。
 */
@RequiresApi(android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
@Singleton
class CredentialProviderEntryBuilder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vaultRepository: VaultRepository,
    private val itemRepository: ItemRepository,
    private val activeVaultStore: ActiveVaultStore,
) {

    /**
     * ★ 最近一次"因库锁定而要求解锁"的原始请求。
     *
     * 解锁完成后要用它**重建候选列表**回给面板（见文件头）。不存它，解锁动作就只能回一个
     * 空结果 —— 那正是用户看到的「面板里 Vaultix 没有任何登录信息」。
     *
     * ⚠️ 只存**请求**（不含任何已解密的密钥材料）；解锁动作收尾后会清掉。
     */
    @Volatile
    var pendingUnlockRequest: BeginGetCredentialRequest? = null
        private set

    /** 解锁动作已收尾：清掉暂存（幂等）。 */
    fun clearPendingUnlockRequest() {
        pendingUnlockRequest = null
    }

    private fun log(message: String) = AutofillLogger.d("CP $message")

    private companion object {
        /** 解锁动作 PendingIntent 的 requestCode（与其它 PendingIntent 区分开）。 */
        const val REQUEST_UNLOCK_CP = 2101

        /** 密码条目 PendingIntent 的 requestCode 基数（每条目 +index，避免互相覆盖）。 */
        const val REQUEST_PASSWORD_CP_BASE = 2200
    }
    suspend fun buildGetResponse(request: BeginGetCredentialRequest): BeginGetCredentialResponse {
        val pkOptions = request.beginGetCredentialOptions.filterIsInstance<BeginGetPublicKeyCredentialOption>()
        @Suppress("unused") val pwOptions = request.beginGetCredentialOptions.filterIsInstance<BeginGetPasswordOption>()
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

        // ⚠️ 锁态判据**只看「已解锁的库集合」**，不看「全库快照里有没有锁着的库」。
        //
        // 旧实现（2026-09-11 收紧）额外算了 `observeVaults().count { !it.unlocked }`，
        // 并在它 >0 时丢弃全部候选、只回解锁动作。那把「全库快照」当成了「当前库」：
        // 只要用户存在**第二个库**（Bitwarden + KDBX 并存，或 KDBX 因「切库即锁旧库」
        // 被策略性锁掉），该计数恒 >0 ⇒ 候选被永久清空 ⇒ 浏览器里永远只剩
        // 「解锁 Vaultix」一行，且**解锁完还是这一行**（判据没变），形成无限解锁环。
        //
        // 对齐 Bitwarden `CredentialProviderProcessorImpl`：`!activeAccount.isVaultUnlocked`
        // —— 只看**活跃账号/活跃库**，不存在「lockedCount」这个概念。
        val unlocked = vaultRepository.observeUnlockedVaultIds().first()
        log("GET unlocked=${unlocked.size}")

        if (unlocked.isEmpty()) {
            // ⚠️ **全部库锁定：必须走 `authenticationActions`，而不是往 `credentialEntries` 里塞
            // 一条"解锁"条目。**（此前实现即错在此，导致锁定时点解锁毫无反应。）
            //
            // 对齐 Bitwarden `CredentialProviderProcessorImpl.processGetCredentialRequest`：
            // ```
            // if (!userState.activeAccount.isVaultUnlocked) {
            //     val authenticationAction = AuthenticationAction(
            //         title = context.context.getString(BitwardenString.unlock),
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
            // ★ 存下这份请求：解锁完成后要用它**重建候选列表**回给面板（见类 KDoc）。
            //   没有这一步，解锁动作只能回一个空结果 ⇒ 用户看到「Vaultix 没有任何登录信息」。
            pendingUnlockRequest = request
            return BeginGetCredentialResponse.Builder()
                .setAuthenticationActions(listOf(unlockAction()))
                .build()
        }

        // ★ 候选来源收敛到**单个活跃库**（与 `VaultixAutofillService` 同款，见迁移文档
        // 阶段 2「★ 全局活跃库真源」）。历史行为是遍历全部已解锁库：两个库同时解锁时，
        // 同一站点会出现两条来源不同的候选，用户无法分辨该点哪一条。
        val sources = singleActiveVault(unlocked)
        log("GET active=${sources.firstOrNull() ?: "-"}")

        val entries = mutableListOf<CredentialEntry>()
        // pwOptions 循环保留但当前不会执行（credential_provider.xml 不声明 PASSWORD 能力）。
        for (option in pwOptions) {
            entries += passwordEntries(option, sources, callingOrigin, callingPackage)
        }
        for (option in pkOptions) {
            val matched = resolvePasskeys(option, sources)
            log("GET rpId matched pk=${matched.size}")
            for (m in matched) {
                entries += publicKeyEntry(option, m)
            }
        }
        // ⚠️ 候选一律返回（除非「活跃库锁定」已在上方提前返回）：
        // 对齐 Bitwarden `CredentialProviderProcessorImpl` —— 官方只在
        // `!activeAccount.isVaultUnlocked` 时给认证动作，**没有**「任一库锁定」这一层。
        // 曾有一版实现在「存在锁定的库」时丢弃全部候选（2026-09-11，已于 09-13 撤销）：
        // 它把「当前库」偷换成「存在锁定的库」⇒ 多库用户候选全灭，且解锁后判据不变
        // ⇒ 浏览器里永远只剩「解锁 Vaultix」一行，形成**无限解锁环**。
        log("GET entries=${entries.size} actions=0")
        return BeginGetCredentialResponse.Builder()
            .setCredentialEntries(entries)
            .setAuthenticationActions(emptyList())
            .build()
    }

    /**
     * 候选来源 = **唯一活跃库**（语义与 `VaultixAutofillService.singleActiveVault` 一致）。
     *
     * [unlocked] 非空已由调用方保证；解析结果不在其中时退化成「字典序最小的已解锁库」，
     * **仍然只取一个**，绝不回退成遍历全部。
     */
    private suspend fun singleActiveVault(unlocked: Set<String>): Set<String> {
        val active = activeVaultStore.resolve()
        if (active != null && active in unlocked) return setOf(active)
        return setOfNotNull(unlocked.minOrNull())
    }

    /**
     * 库锁定时的解锁动作（`authenticationActions` 通道）。
     *
     * 与"把解锁项塞进 credentialEntries"的做法关键区别：本方式**不绑定具体的凭据选项**，
     * 因为认证动作是"先解锁、再重新发起请求"的两段式流程 —— 系统在用户完成动作后会
     * **重新调用** `onBeginGetCredentialRequest`，届时库已解锁，正常返回凭据。
     *
     * 对齐 Bitwarden `createFido2UnlockPendingIntent`：显式 action + 显式 Activity class，
     * `FLAG_MUTABLE`，**不加 NEW_TASK**（见其 KDoc 警告）。
     *
     * ⚠️ 解锁走的是 `AutofillActivity`（MODE_UNLOCK）：它已实现"原地生物识别解锁，
     * 无本地快速解锁则亮卡片引导打开 Vaultix"，是 Vaultix 既有的成熟解锁链。
     */
    private fun unlockAction(): AuthenticationAction {
        val intent = AutofillIntents.create(
            context = context,
            mode = AutofillIntents.MODE_UNLOCK,
            title = context.getString(R.string.credential_unlock_title),
            subtitle = context.getString(R.string.credential_unlock_subtitle),
        ).putExtra(AutofillIntents.EXTRA_CREDENTIAL_FLOW, true)
        val pendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_UNLOCK_CP,
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return AuthenticationAction.Builder(
            context.getString(R.string.credential_unlock_title),
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
        val intent = PasskeyProviderIntents.passwordGetIntent(context, vaultId, item.id)
        val pendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_PASSWORD_CP_BASE + item.id.hashCode(),
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val username = item.username.ifBlank { item.title }
        val builder = PasswordCredentialEntry.Builder(context, username, pendingIntent, option)
            .setDisplayName(item.title.ifBlank { item.username })
            // 对齐 Bitwarden：仅当只有一条候选时允许系统自动选中，避免多条时误填。
            .setAutoSelectAllowed(siblingCount == 1)
            .setIcon(Icon.createWithResource(context, R.drawable.ic_passkey))
        return builder.build()
    }

    /**
     * 跨已解锁库扁平化所有登录条目的 fido2，按 rpId（+ allowCredentials）匹配。
     *
     * 解析细节拆分到同包 `PasskeyResolution.kt`：detekt 2.0.0-alpha.6 的
     * CyclomaticComplexMethod 会把**同文件**被调用私有函数的复杂度累加进调用方
     * （实测：同文件拆 helper 反而把本函数从 19 推高到 41）。拆到独立文件后
     * detekt 逐文件分析、无法跨文件累加，本函数与其 helper 各自 < 14。
     */
    private suspend fun resolvePasskeys(
        option: BeginGetPublicKeyCredentialOption,
        unlocked: Set<String>,
    ): List<PasskeyMatch> {
        val json = runCatching { JSONObject(option.requestJson) }.getOrNull() ?: return emptyList()
        val rpId = normalizeRpId(json.optString("rpId", ""))
        if (rpId.isBlank()) {
            log("GET resolve aborted: requestJson has no rpId")
            return emptyList()
        }
        val allowed = parseAllowedCredentialIds(json)
        val (rpMatched, counts) = collectPasskeyMatches(itemRepository, unlocked, rpId)
        val result = applyAllowedFilter(rpMatched, allowed) { log(it) }
        log(
            "GET resolve rpId=$rpId allowed=${allowed.size} " +
                "total=${counts.total} unusable=${counts.unusable} " +
                "rpIdMiss=${counts.rpIdMiss} matched=${result.size}",
        )
        if (result.isEmpty() && counts.total > 0) {
            // 有记录却筛不出候选：把库里实际存的 rpId 打出来（只打域名，非敏感），
            // 常见于「库里存了 www.github.com 而请求是 github.com」这类子域口径差。
            val stored = collectStoredRpIds(itemRepository, unlocked)
            log("GET resolve EMPTY: storedRpIds=${stored.joinToString(",")}")
        }
        return result
    }

    private fun publicKeyEntry(option: BeginGetPublicKeyCredentialOption, m: PasskeyMatch): CredentialEntry {
        val intent = PasskeyProviderIntents.getIntent(
            context = context,
            requestJson = option.requestJson,
            vaultId = m.vaultId,
            itemId = m.itemId,
            credentialId = m.credential.credentialId,
            rpId = m.credential.rpId,
            clientDataHash = option.clientDataHash,
        )
        val pendingIntent = PendingIntent.getActivity(
            context,
            m.credential.credentialId.hashCode(),
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val username = m.credential.userName.ifBlank { m.loginTitle.ifBlank { m.credential.rpName } }
        val builder = PublicKeyCredentialEntry.Builder(context, username, pendingIntent, option)
            .setDisplayName(m.credential.rpName.ifBlank { m.credential.rpId })
            .setIcon(Icon.createWithResource(context, R.drawable.ic_passkey))
        return builder.build()
    }

    // ===================== 关于 entry 的 BiometricPromptData =====================
    //
    // Android 15+（API 35）的 CredentialEntry 支持 setBiometricPromptData：官方 Bitwarden
    // 会在 entry 上挂（用 Keystore cipher 作 CryptoObject，让系统在候选列表内直接完成
    // 生物识别）。Vaultix **不挂**，两条理由：
    //  1. **魔改 ROM 风险**：Bitwarden 源码原文标注「Xiaomi HyperOS is known to be
    //     incompatible」；荣耀 MagicOS 同族。挂上可能导致系统在渲染阶段丢弃整个 entry，
    //     表现仍是「浏览器里什么都不弹」——比不挂更糟。（曾短暂引入 RomCompat 判定对象，
    //     因逻辑最终无需启用而删除；若日后要挂，务必先恢复该 ROM 判定，勿直接挂。）
    //  2. **无可用 cipher**：Vaultix 的库密钥只在内存（VaultSessionManager），没有绑定到
    //     单个条目、且经 setUserAuthenticationRequired 的 Keystore 密钥可作 CryptoObject。
    // 因此设备验证统一由条目点击后的 Activity（PasskeyGetActivity / PasswordGetActivity）
    // 承担 —— 与 Vaultix 既有行为一致，且不受 ROM 差异影响。
    //
    // ⚠️ 由此带来的**用户可见后果**（2026-09-18 记录，勿当成 bug 重修）：
    // 库锁定时的通行密钥登录会看到**两次**生物识别 ——
    //  ① `AutofillActivity.maybeBiometricUnlock()`（文案「解锁 Vaultix」，CP 认证动作）；
    //  ② `PasskeyGetActivity.verifyUser()`（文案「使用通行密钥登录」，WebAuthn UV）。
    // ② 是协议要求（`sign()` 断言 `isUserVerified`，且签名用的私钥取自已解锁的仓储），
    // **去不掉**；① 是否可以省掉取决于自动锁定档位是否命中 `VaultTimeout.OnAppRestart`
    // 的 `createdForAutofill` 豁免（见 VaultLockManagerImpl.checkForVaultTimeoutInternal）。
    // 这两条各自都写在别处，但**根因是本节的「不挂 BiometricPromptData」**，
    // 所以在这里留一句指路，避免下一位接力者从两个 Activity 分头查起。

}
