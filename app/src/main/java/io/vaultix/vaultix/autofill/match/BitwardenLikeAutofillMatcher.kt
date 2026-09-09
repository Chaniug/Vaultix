/*
 * Vaultix — app:autofill · match
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * 匹配器移植自 Bastion 的 BitwardenLikeAutofillMatcherNg（GPL-3.0，Copyright 2025 JoyinJoester），
 * 该实现本身对齐 Bitwarden filterCiphersForMatches。仅消费解耦后的 AutofillCredential，
 * 与 Vaultix 仓储 / KeePass 完全解耦，便于纯 JVM 单测。已改写 KeePass 特有耦合
 * （keepassDatabaseId / bitwardenVaultId 等），按 Vaultix 候选列表匹配。
 *
 * 与 Bitwarden 一致的关键点：
 *  - 每条 URI 携带自身 [io.vaultix.model.UriMatch] 规则；缺省为基域匹配（Domain）。
 *  - 基域匹配（Domain）即 eTLD+1 相等，天然覆盖所有子域（含反向：凭据存子域、页面是根域）。
 *  - 等价域组（google/youtube/...）视为同一组织可互填。
 *  - androidapp:// 包名 Uri 走包名精确匹配。
 *  - Never 规则显式排除该 URI。
 */
package io.vaultix.vaultix.autofill.match

import io.vaultix.model.UriMatch
import io.vaultix.vaultix.autofill.model.AutofillCredential
import io.vaultix.vaultix.autofill.model.AutofillUri

/** 匹配评分信号（对齐 Bitwarden 数值：精确域 > 等价域 > 包名 > 基域 > 前缀/精确/正则）。 */
private const val EXACT_DOMAIN = 140
private const val EQUIVALENT_DOMAIN = 139
private const val EXACT_PACKAGE = 120
private const val BASE_DOMAIN = 100
private const val STARTS_WITH_MATCH = 110

/**
 * 匹配配置（对应 Bastion 的 strictOnly / allowBaseDomainMatch / exactDomainOnly /
 * allowPackageMatch；Bitwarden 默认全开）。
 */
data class MatchConfig(
    val allowBaseDomainMatch: Boolean = true,
    val exactDomainOnly: Boolean = false,
    val allowPackageMatch: Boolean = true,
)

/**
 * Bitwarden 风格凭据匹配器：给定目标（包名 / 网页域名）与候选登录列表，
 * 返回按相关度排序的候选项（评分降序 → 收藏优先 → 名称升序）。
 */
object BitwardenLikeAutofillMatcher {

    private val CREDENTIAL_ORDER =
        compareByDescending<Pair<AutofillCredential, Int>> { it.second }
            .thenByDescending { it.first.isFavorite }
            .thenBy { it.first.name.lowercase() }

    fun match(
        credentials: List<AutofillCredential>,
        packageName: String?,
        webDomain: String?,
        config: MatchConfig = MatchConfig(),
    ): List<AutofillCredential> = credentials.asSequence()
        .map { it to scoreCredential(it, packageName, webDomain, config) }
        .filter { it.second > 0 }
        .sortedWith(CREDENTIAL_ORDER)
        .map { it.first }
        .toList()

    private fun scoreCredential(
        cred: AutofillCredential,
        packageName: String?,
        webDomain: String?,
        config: MatchConfig,
    ): Int {
        var best = 0
        for (entry in cred.uris) {
            val s = scoreUri(entry.uri, entry.match, packageName, webDomain, config)
            if (s > best) best = s
        }
        // 无 uri 时靠 appPackageName 命中包名（v1 暂未反查，预留接口）。
        if (best == 0 && cred.appPackageName.isNotEmpty() && cred.appPackageName == packageName) {
            best = EXACT_PACKAGE
        }
        return best
    }

    private fun scoreUri(
        uri: String,
        match: UriMatch,
        packageName: String?,
        webDomain: String?,
        config: MatchConfig,
    ): Int {
        if (match == UriMatch.Never) return 0
        if (uri.startsWith("androidapp://", ignoreCase = true)) {
            return scoreAndroidAppUri(uri, packageName, config)
        }
        return scoreWebUri(uri, match, webDomain, config)
    }

    private fun scoreAndroidAppUri(uri: String, packageName: String?, config: MatchConfig): Int {
        if (!config.allowPackageMatch) return 0
        val pkg = uri.substringAfter("androidapp://").substringBefore('/').substringBefore('?')
        return if (pkg == packageName) EXACT_PACKAGE else 0
    }

    private fun scoreWebUri(
        uri: String,
        match: UriMatch,
        webDomain: String?,
        config: MatchConfig,
    ): Int {
        val web = webDomain ?: return 0
        return when (match) {
            UriMatch.Domain -> scoreDomainUri(uri, web, config)
            UriMatch.Host -> scoreHostUri(uri, web)
            UriMatch.StartsWith -> scoreStartsWithUri(uri, web)
            UriMatch.Exact -> scoreExactUri(uri, web)
            UriMatch.RegularExpression -> scoreRegexUri(uri, web)
            UriMatch.Never -> 0
        }
    }

    private fun scoreDomainUri(uri: String, web: String, config: MatchConfig): Int {
        val credHost = UriMatcher.hostOf(uri) ?: return 0
        val targetHost = UriMatcher.hostOf(web) ?: web.lowercase()
        val level = domainLevel(credHost, targetHost)
        if (config.exactDomainOnly && level != EXACT_DOMAIN) return 0
        if (level == BASE_DOMAIN && !config.allowBaseDomainMatch) return 0
        return level
    }

    private fun scoreHostUri(uri: String, web: String): Int {
        val credHost = UriMatcher.hostOf(uri) ?: return 0
        val targetHost = UriMatcher.hostOf(web) ?: web.lowercase()
        return if (UriMatcher.hostMatch(credHost, targetHost)) EXACT_DOMAIN else 0
    }

    private fun scoreStartsWithUri(uri: String, web: String): Int =
        if (UriMatcher.startsWithMatch(uri, "https://$web")) STARTS_WITH_MATCH else 0

    private fun scoreExactUri(uri: String, web: String): Int =
        if (UriMatcher.exactMatch(uri, "https://$web")) STARTS_WITH_MATCH else 0

    private fun scoreRegexUri(uri: String, web: String): Int =
        if (UriMatcher.regexMatch(uri, web)) STARTS_WITH_MATCH else 0

    /**
     * 基域匹配层级（无严格开关）：
     * 精确主机 > 等价域 > 基域（eTLD+1）。返回 0 表示不匹配。
     */
    private fun domainLevel(credHost: String, targetHost: String): Int {
        if (UriMatcher.hostMatch(credHost, targetHost)) return EXACT_DOMAIN
        if (EquivalentDomains.isEquivalent(credHost, targetHost)) return EQUIVALENT_DOMAIN
        if (UriMatcher.baseDomainMatch(credHost, targetHost)) return BASE_DOMAIN
        return 0
    }
}
