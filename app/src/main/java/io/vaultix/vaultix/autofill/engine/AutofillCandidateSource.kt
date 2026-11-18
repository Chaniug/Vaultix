/*
 * Vaultix — app:autofill · engine
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * 候选来源：把「活跃库 → 条目 → 匹配」整条链路收在一处，Service 与解锁后回灌共用。
 */
package io.vaultix.vaultix.autofill.engine

import io.vaultix.datastore.VaultixPreferences
import io.vaultix.domain.ItemRepository
import io.vaultix.domain.VaultRepository
import io.vaultix.model.VaultItem
import io.vaultix.vaultix.autofill.match.AutofillRequestContextPolicy
import io.vaultix.vaultix.autofill.match.BitwardenLikeAutofillMatcher
import io.vaultix.vaultix.autofill.match.MatchConfig
import io.vaultix.vaultix.autofill.model.AutofillCredential
import io.vaultix.vaultix.autofill.model.ParsedStructure
import io.vaultix.vaultix.session.ActiveVaultStore
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自动填充候选的**唯一来源**。
 *
 * 为什么不是塞进 `VaultixAutofillService` 的私有方法（原状）：解锁后回灌发生在
 * [io.vaultix.vaultix.autofill.AutofillActivity]，那里的 Service 实例早已结束 ——
 * 若各写一份「收集候选 → 匹配」，两条路径对同一条目就可能给出不同结论
 * （匹配宽严取自偏好，任何一处漏读偏好都会造成「解锁前匹配得上、解锁后填不进」）。
 */
@Singleton
class AutofillCandidateSource @Inject constructor(
    private val vaultRepository: VaultRepository,
    private val itemRepository: ItemRepository,
    private val activeVaultStore: ActiveVaultStore,
    private val preferences: VaultixPreferences,
) {

    /**
     * 候选来源 = **唯一活跃库**（Docs/progress/main-shell-migration.md 阶段 2
     * 「★ 全局活跃库真源」）。
     *
     * 历史行为是遍历全部已解锁库聚合：云端库与 KDBX 库同时解锁时，同一站点会冒出两条
     * 来源不同的候选（用户不知点哪条），保存时也不知写回哪个库。
     * 解析结果不在已解锁集合里时退化成「字典序最小的已解锁库」——**仍然只取一个**。
     */
    suspend fun singleActiveVault(unlocked: Set<String>): Set<String> {
        val active = activeVaultStore.resolve()
        if (active != null && active in unlocked) return setOf(active)
        return setOfNotNull(unlocked.minOrNull())
    }

    /** 汇总指定库的候选（登录 / 卡片 / 身份）。 */
    suspend fun collectCandidates(vaultIds: Set<String>): VaultCandidates {
        val credentials = mutableListOf<AutofillCredential>()
        val cards = mutableListOf<VaultItem>()
        val identities = mutableListOf<VaultItem>()
        for (vaultId in vaultIds) {
            val items = runCatching { itemRepository.observeItems(vaultId).first() }
                .getOrElse { emptyList() }
            for (item in items) {
                when {
                    AutofillCredentialMapper.isLoginCandidate(item) ->
                        credentials += AutofillCredentialMapper.toCredential(vaultId, item)
                    AutofillCredentialMapper.isCardCandidate(item) -> cards += item
                    AutofillCredentialMapper.isIdentityCandidate(item) -> identities += item
                    else -> Unit
                }
            }
        }
        // 库 origin：填充面板要按它拼站点图标地址（Bitwarden 库 = 服务器地址；
        // KDBX 的 `content://` 会被 SiteIconUrl 判为不可用 → 自动走字母头像）。
        val serverOrigin = runCatching { vaultRepository.observeVaults().first() }
            .getOrNull()
            ?.firstOrNull { it.id in vaultIds }
            ?.origin
        return VaultCandidates(credentials, cards, identities, serverOrigin)
    }

    /**
     * 匹配配置：域匹配宽严由用户在「设置 → 自动填充 → 填充行为」控制，
     * 默认与 Bitwarden 一致（允许基域匹配、不强制精确域）。
     *
     * 包名匹配是**两道闸的并集**：
     * - [AutofillRequestContextPolicy.allowPackageMatching]：宽松档，原生 App 放行、
     *   浏览器缺域名时禁止；
     * - [AutofillRequestContextPolicy.allowExactPackageMatching]：`file://` 本地 H5 的
     *   精确包名例外（App 内嵌登录页，2026-11-18 对照 Bastion 补齐）。
     */
    suspend fun matchConfigFor(parsed: ParsedStructure, webDomain: String?): MatchConfig = MatchConfig(
        allowBaseDomainMatch = preferences.autofillBaseDomainMatch.first(),
        exactDomainOnly = preferences.autofillExactDomainOnly.first(),
        allowPackageMatch = AutofillRequestContextPolicy.allowPackageMatching(
            packageName = parsed.packageName,
            webDomain = webDomain,
            isWebView = parsed.webView,
        ) || AutofillRequestContextPolicy.allowExactPackageMatching(
            packageName = parsed.packageName,
            webDomain = webDomain,
            isWebView = parsed.webView,
            webScheme = parsed.webScheme,
        ),
    )

    /**
     * 登录候选匹配（等价于 Service 里的 `BitwardenLikeAutofillMatcher.match` 调用）。
     *
     * 🔴 **无域名降级**（2026-10-04，Firefox Android 真机日志实证）：`webDomain` 为 null 时
     * 不能就此返回 0 条 —— 那会让所有候选静默变成 0 分，用户体感是
     *「输入框能看到 Vaultix 的提示，但密码条目一条都匹配不出来」（原话）。
     *
     * ## 为什么必须降级而不是只修空白防御
     *
     * Firefox 用 **GeckoView**，它不给第三方 AutofillService 上报可用的
     * `ViewNode.webDomain`：登录页上报的是**空字符串**（已修，见 [WebDomainResolver]），
     * 而地址栏 / 结构文本兜底实测也取不到（日志里 13 次 `fillRequest` 的 `fallback=` 全为 null）。
     * ⇒ **Firefox 上「域名匹配」这条路在平台层就走不通**，不是我们某处判据写错了。
     *
     * 外部佐证：Bitwarden Android 在 Firefox Android 上**同样**做不了域名匹配
     * （其 issue #5720「Autofill does not propose correct vault entries (URI match detection)」
     *  与社区帖 #19444），是已知的 GeckoView 限制。而 Bitwarden 的用户体感是
     *「菜单会弹、但要自己在搜索框里翻」—— 它没有把用户挡在门外。
     *
     * ## 降级策略（对齐 Bitwarden 的搜索兜底语义）
     *
     * **浏览器**拿不到域名时，返回**全部登录候选**，按「收藏优先 → 名称升序」排（与
     * [BitwardenLikeAutofillMatcher] 的 `CREDENTIAL_ORDER` 同序，保证有域名时降级不改变体验）。
     * 再由 [FillPlanner] / `MAX_DATASETS` 截断成有限几条 ⇒ 用户在 Firefox 上仍能看到
     * 自己的条目并一键填入，而不是面对一个空面板。
     *
     * ⚠️ **安全边界（2026-11-18 收紧）**：降级**只对浏览器成立** —— 判据是
     * [AutofillRequestContextPolicy.allowDomainlessFallback]（= 已知浏览器包名）。
     *
     * 原判据只有「没有域名」，而原生 App **本来就没有域名** ⇒ 每个没存
     * `androidapp://` 条目的 App 里都会按「收藏优先 + 名称」列出整库密码，
     * 自己那条反而不在列表里。这不是「多弹一条」，是把别的站点的密码摊在无关 App 里。
     * 对齐 Bitwarden：原生 App 匹配不到就**一条都不给**，只留「在 Vaultix 中搜索」。
     *
     * 降级前提仍由 Service 侧的 `noFillTarget` 闸门保证（确实是登录表单才会走到这里），
     * 且**不关闭** [AutofillRequestContextPolicy] 的包名匹配闸门 —— 浏览器里缺域名时
     * 包名是浏览器自己的，拿它匹配会弹出「浏览器身份」的无关条目。
     */
    suspend fun matchLogins(
        credentials: List<AutofillCredential>,
        parsed: ParsedStructure,
        webDomain: String?,
    ): List<AutofillCredential> {
        val matched = BitwardenLikeAutofillMatcher.match(
            credentials = credentials,
            packageName = parsed.packageName,
            webDomain = webDomain,
            config = matchConfigFor(parsed, webDomain),
            // http 站点必须按 http 比对：`Exact` / `StartsWith` 拿的串由它拼出。
            webScheme = parsed.webScheme,
        )
        if (matched.isNotEmpty() || !webDomain.isNullOrBlank()) return matched
        // 走到这里 = 本次请求没有任何域名，却一个候选都没匹配上。
        // 只有「域名该有却拿不到」的浏览器允许列全库；原生 App 返回空（上层转搜索入口）。
        if (!AutofillRequestContextPolicy.allowDomainlessFallback(parsed.packageName)) return emptyList()
        return BitwardenLikeAutofillMatcher.orderWithoutDomain(credentials)
    }

    /**
     * 页面域名（浏览器不上报 webDomain 时用地址栏 / 结构文本兜底）。
     *
     * ⚠️ 判据是 `takeIf { it.isNotBlank() }` 而非 `?:`：**空串不是 null**，
     * 直接 `?:` 会让它赢掉真正的兜底域名（GeckoView / Firefox 的实测形态，见 [WebDomainResolver]）。
     */
    fun webDomainOf(parsed: ParsedStructure): String? =
        parsed.webDomain?.takeIf { it.isNotBlank() } ?: parsed.fallbackWebDomain
}

/** 一次填充请求内汇总的候选集合。 */
data class VaultCandidates(
    val credentials: List<AutofillCredential>,
    val cards: List<VaultItem>,
    val identities: List<VaultItem>,
    /** 活跃库的 origin（拼站点图标地址用；取不到为 null）。 */
    val serverOrigin: String? = null,
)
