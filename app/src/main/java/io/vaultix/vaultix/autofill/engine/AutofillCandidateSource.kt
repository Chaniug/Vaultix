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
        return VaultCandidates(credentials, cards, identities)
    }

    /**
     * 匹配配置：域匹配宽严由用户在「设置 → 自动填充 → 填充行为」控制，
     * 默认与 Bitwarden 一致（允许基域匹配、不强制精确域）。
     */
    suspend fun matchConfigFor(parsed: ParsedStructure, webDomain: String?): MatchConfig = MatchConfig(
        allowBaseDomainMatch = preferences.autofillBaseDomainMatch.first(),
        exactDomainOnly = preferences.autofillExactDomainOnly.first(),
        allowPackageMatch = AutofillRequestContextPolicy.allowPackageMatching(
            packageName = parsed.packageName,
            webDomain = webDomain,
            isWebView = parsed.webView,
        ),
    )

    /** 登录候选匹配（等价于 Service 里的 `BitwardenLikeAutofillMatcher.match` 调用）。 */
    suspend fun matchLogins(
        credentials: List<AutofillCredential>,
        parsed: ParsedStructure,
        webDomain: String?,
    ): List<AutofillCredential> = BitwardenLikeAutofillMatcher.match(
        credentials = credentials,
        packageName = parsed.packageName,
        webDomain = webDomain,
        config = matchConfigFor(parsed, webDomain),
    )

    /** 页面域名（浏览器不上报 webDomain 时用地址栏 / 结构文本兜底）。 */
    fun webDomainOf(parsed: ParsedStructure): String? = parsed.webDomain ?: parsed.fallbackWebDomain
}

/** 一次填充请求内汇总的候选集合。 */
data class VaultCandidates(
    val credentials: List<AutofillCredential>,
    val cards: List<VaultItem>,
    val identities: List<VaultItem>,
)
