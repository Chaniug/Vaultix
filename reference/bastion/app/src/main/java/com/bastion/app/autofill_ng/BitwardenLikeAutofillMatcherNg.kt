package com.bastion.app.autofill_ng

import com.bastion.app.logging.runCatchingObserved
import com.bastion.app.data.PasswordEntry
import java.net.URI
import java.util.Locale

/**
 * Bitwarden-style matcher:
 * - Prefer exact package/domain matches.
 * - Keep matching deterministic and conservative to reduce false positives.
 */
class BitwardenLikeAutofillMatcherNg {
    data class Config(
        val strictOnly: Boolean = true,
        val allowSubdomainMatch: Boolean = true,
        val allowBaseDomainMatch: Boolean = true,
        val exactDomainOnly: Boolean = false,
        val allowPackageMatch: Boolean = true,
        val maxSuggestions: Int = 20,
    )

    private enum class Reason {
        EXACT_PACKAGE,
        EXACT_DOMAIN,
        /** 等价域名命中（同账号体系），对齐 bitwarden 的 `MatchResult.EXACT`。 */
        EQUIVALENT_DOMAIN,
        SUBDOMAIN,
        BASE_DOMAIN,
        PACKAGE_DOMAIN_COMBO,
        EXACT_APP_TITLE,
        PACKAGE_TOKEN_TITLE,
        HEURISTIC_FALLBACK,
    }

    private data class ScoredMatch(
        val entry: PasswordEntry,
        val score: Int,
        val reasons: Set<Reason>,
    )

    companion object {
        private val PACKAGE_TOKEN_STOPWORDS = setOf(
            "com", "android", "app", "net", "org", "io",
            "mobile", "client", "common", "ui", "view", "service",
            "lib", "utils", "core", "main", "v2", "prod", "dev",
            "test", "debug",
        )
    }

    fun match(
        entries: List<PasswordEntry>,
        packageName: String,
        webDomain: String?,
        appDisplayName: String? = null,
        config: Config = Config(),
    ): List<PasswordEntry> {
        if (entries.isEmpty()) return emptyList()

        val targetPackage = normalizePackageName(packageName)
        val targetPackageTokens = targetPackage
            ?.split('.')
            ?.filter { it.length >= 3 }
            ?.filter { it !in PACKAGE_TOKEN_STOPWORDS }
            ?.distinct()
            ?: emptyList()
        val targetHost = normalizeHost(webDomain)
        val preferDomainSignals = !targetHost.isNullOrBlank()
        val targetRoot = targetHost?.let(::extractBaseDomain)
        val targetAppDisplayName = normalizeLabel(appDisplayName)

        val candidates = entries.mapNotNull { entry ->
            scoreEntry(
                entry = entry,
                targetPackage = targetPackage,
                targetPackageTokens = targetPackageTokens,
                targetHost = targetHost,
                preferDomainSignals = preferDomainSignals,
                targetRoot = targetRoot,
                targetAppDisplayName = targetAppDisplayName,
                config = config,
            )
        }

        if (candidates.isEmpty()) return emptyList()

        // Deduplicate by entry id and keep higher score.
        val bestByEntry = linkedMapOf<Long, ScoredMatch>()
        candidates.forEach { candidate ->
            val existing = bestByEntry[candidate.entry.id]
            if (existing == null || candidate.score > existing.score) {
                bestByEntry[candidate.entry.id] = candidate
            }
        }

        return bestByEntry.values
            .sortedWith(
                // 对齐 bitwarden `filterCiphersForMatches` 的 `exactMatches + fuzzyMatches`：
                // **先按 EXACT / FUZZY 硬分层，再在层内按分数排序**。
                // 纯分数排序会让「模糊匹配 + 组合加分」反超精确匹配
                // （例：SUBDOMAIN 115 + PACKAGE_DOMAIN_COMBO 30 = 145 > EXACT_DOMAIN 140），
                // 导致派生关系的条目排在精确命中之前。
                compareBy<ScoredMatch> { matchTier(it.reasons) }
                    .thenByDescending { it.score }
                    .thenByDescending { it.entry.isFavorite }
                    .thenByDescending { it.entry.updatedAt.time },
            )
            .take(config.maxSuggestions.coerceAtLeast(1))
            .map { it.entry }
    }

    /**
     * 匹配档位，对齐 bitwarden `MatchResult`：
     * - `0` = EXACT —— 权威轴上的「直接相等」：域名完全相等、包名完全相等。
     * - `1` = FUZZY —— 派生关系或启发式：子域、基域、应用标题、包名 token、兜底。
     *
     * 精确档永远排在模糊档之前，与 bitwarden 一致（它甚至只在 androidapp:// 场景
     * 才产生 FUZZY，网页侧零模糊）。
     */
    private fun matchTier(reasons: Set<Reason>): Int =
        if (reasons.any {
                it == Reason.EXACT_DOMAIN ||
                    it == Reason.EQUIVALENT_DOMAIN ||
                    it == Reason.EXACT_PACKAGE
            }
        ) 0 else 1

    private fun scoreEntry(
        entry: PasswordEntry,
        targetPackage: String?,
        targetPackageTokens: List<String>,
        targetHost: String?,
        preferDomainSignals: Boolean,
        targetRoot: String?,
        targetAppDisplayName: String?,
        config: Config,
    ): ScoredMatch? {
        val reasons = linkedSetOf<Reason>()
        var score = 0

        val entryPackages = linkedSetOf<String>().apply {
            extractNormalizedPackages(entry.appPackageName).forEach(::add)
            extractWebsiteTokens(entry.website)
                .mapNotNull(::extractAndroidAppPackage)
                .forEach(::add)
        }
        val entryHosts = extractNormalizedHosts(entry.website)
        val entryRoots = entryHosts.map(::extractBaseDomain).toSet()

        if (!preferDomainSignals &&
            config.allowPackageMatch &&
            !targetPackage.isNullOrBlank() &&
            entryPackages.contains(targetPackage)
        ) {
            score += 120
            reasons += Reason.EXACT_PACKAGE
        }

        val entryTitle = normalizeLabel(entry.title)
        val entryAppName = normalizeLabel(entry.appName)
        if (!preferDomainSignals &&
            config.allowPackageMatch &&
            !targetAppDisplayName.isNullOrBlank() &&
            (
                entryTitle == targetAppDisplayName ||
                    entryAppName == targetAppDisplayName
                )
        ) {
            score += 95
            reasons += Reason.EXACT_APP_TITLE
        }

        if (!preferDomainSignals && config.allowPackageMatch && targetPackageTokens.isNotEmpty()) {
            val tokenMatched = targetPackageTokens.any { token ->
                entryTitle.contains(token) || entryAppName.contains(token)
            }
            if (tokenMatched) {
                score += 70
                reasons += Reason.PACKAGE_TOKEN_TITLE
            }
        }

        if (!targetHost.isNullOrBlank() && entryHosts.isNotEmpty()) {
            val hasExactDomain = entryHosts.any { it == targetHost }
            // 等价域名（对齐 bitwarden equivalentDomains）：同属一个账号体系的不同域名
            // 视为精确匹配 —— bitwarden 对等价域名返回 MatchResult.EXACT。
            // 比较用**基域**（已归约），避免 www./子域前缀导致比较不出结果。
            val hasEquivalentDomain = !targetRoot.isNullOrBlank() &&
                entryRoots.any { EquivalentDomains.isEquivalent(it, targetRoot) }
            val hasSubdomainRelation = entryHosts.any { isSubdomainRelation(it, targetHost) }
            when {
                hasExactDomain -> {
                    score += 140
                    reasons += Reason.EXACT_DOMAIN
                }

                hasEquivalentDomain -> {
                    // 略低于"域名完全相等"，保证同等条件下真·精确仍排在前面
                    score += 139
                    reasons += Reason.EQUIVALENT_DOMAIN
                }

                hasSubdomainRelation && !config.exactDomainOnly && config.allowSubdomainMatch -> {
                    score += 115
                    reasons += Reason.SUBDOMAIN
                }

                hasSubdomainRelation && !config.allowSubdomainMatch -> {
                    // Respect explicit subdomain toggle: do not fall through
                    // to base-domain scoring for strict parent/child host pairs.
                }

                entryRoots.isNotEmpty() &&
                    !targetRoot.isNullOrBlank() &&
                    !config.exactDomainOnly &&
                    config.allowBaseDomainMatch &&
                    targetRoot in entryRoots -> {
                    score += 100
                    reasons += Reason.BASE_DOMAIN
                }
            }
        }

        if (Reason.EXACT_PACKAGE in reasons &&
            (
                Reason.EXACT_DOMAIN in reasons ||
                    Reason.SUBDOMAIN in reasons ||
                    Reason.BASE_DOMAIN in reasons
                )
        ) {
            score += 30
            reasons += Reason.PACKAGE_DOMAIN_COMBO
        }

        if (!config.strictOnly && score == 0) {
            score = heuristicFallbackScore(
                entry = entry,
                targetPackage = if (preferDomainSignals || !config.allowPackageMatch) null else targetPackage,
                targetHost = targetHost,
            )
            if (score > 0) {
                reasons += Reason.HEURISTIC_FALLBACK
            }
        }

        if (score <= 0) return null

        if (config.strictOnly) {
            val hasStrongReason = reasons.any {
                it == Reason.EXACT_PACKAGE ||
                    it == Reason.EXACT_DOMAIN ||
                    it == Reason.EQUIVALENT_DOMAIN ||
                    it == Reason.SUBDOMAIN ||
                    it == Reason.BASE_DOMAIN ||
                    it == Reason.EXACT_APP_TITLE ||
                    it == Reason.PACKAGE_TOKEN_TITLE
            }
            if (!hasStrongReason) return null
        }

        return ScoredMatch(entry = entry, score = score, reasons = reasons)
    }

    private fun normalizeLabel(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return value.trim().lowercase(Locale.ROOT)
    }

    private fun heuristicFallbackScore(
        entry: PasswordEntry,
        targetPackage: String?,
        targetHost: String?,
    ): Int {
        val title = entry.title.lowercase(Locale.ROOT)
        val username = entry.username.lowercase(Locale.ROOT)
        val website = entry.website.lowercase(Locale.ROOT)
        val packageName = entry.appPackageName.lowercase(Locale.ROOT)

        val hostToken = targetHost
            ?.substringBefore('.')
            ?.takeIf { it.length >= 3 }
        val packageToken = targetPackage
            ?.substringAfterLast('.')
            ?.takeIf { it.length >= 3 }

        val token = hostToken ?: packageToken ?: return 0

        val haystack = "$title $username $website $packageName"
        return if (haystack.contains(token)) 55 else 0
    }

    private fun normalizePackageName(value: String?): String? {
        val normalized = value
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.removePrefix("androidapp://")
            ?.removePrefix("android-app://")
            ?.substringBefore(':')
            ?.substringBefore('/')
            ?.substringBefore('?')
            ?.substringBefore('#')
            ?.takeIf { it.isNotBlank() }
        return normalized
    }

    private fun extractNormalizedPackages(value: String?): Set<String> {
        if (value.isNullOrBlank()) return emptySet()
        return value
            .split(',', ';', '|', ' ')
            .asSequence()
            .mapNotNull { normalizePackageName(it) }
            .filter { it.isNotBlank() }
            .toCollection(linkedSetOf())
    }

    private fun extractWebsiteTokens(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        return value
            .split(',', ';', '|', '\n', '\r', '\t')
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun extractAndroidAppPackage(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val raw = value.trim().lowercase(Locale.ROOT)
        if (!raw.startsWith("androidapp://") && !raw.startsWith("android-app://")) {
            return null
        }
        return normalizePackageName(raw)
    }

    private fun extractNormalizedHosts(value: String?): Set<String> =
        extractWebsiteTokens(value)
            .mapNotNull(::normalizeHost)
            .toCollection(linkedSetOf())

    private fun normalizeHost(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val raw = value.trim().lowercase(Locale.ROOT)
        if (raw.startsWith("androidapp://")) return null

        val fullValue = if (raw.contains("://")) raw else "https://$raw"
        // URI 比 URL 轻：不做 URLStreamHandler 查找与网络相关校验，纯语法解析，热路径更省。
        val parsedHost = runCatchingObserved { URI(fullValue).host }
            .getOrNull()
            ?.trim()
            ?.lowercase(Locale.ROOT)
        val fallbackHost = raw
            .substringBefore('/')
            .substringBefore(':')
            .trim()
            .lowercase(Locale.ROOT)
        val host = (parsedHost ?: fallbackHost)
            .removePrefix("www.")
            .trim('.')
            .takeIf { it.isNotBlank() }
        return host
    }

    /**
     * 归约 [host] 到基域（public suffix + 1 段）。
     *
     * 原实现只硬编码了 12 条双段后缀（co.uk / com.cn / ...），**连 `org.uk` 都没覆盖**，
     * 遇到 com.br / co.kr / com.hk / github.io 之类会把基域算错，从而产生跨站误匹配
     * （`a.example.org.uk` 与 `b.example.org.uk` 被当成同一基域而互相匹配）。
     *
     * 现改为查公共后缀表 [PublicSuffixList]（由 `image/gen_public_suffix_list.py` 生成，
     * 数据取自 publicsuffix.org，收录 2~3 段规则 7747 条），与 Bitwarden 用 PSL 做
     * `getDomainOrNull(resourceCacheManager)` 的做法对齐。
     */
    private fun extractBaseDomain(host: String): String = PublicSuffixList.baseDomain(host)

    private fun isSubdomainRelation(left: String, right: String): Boolean {
        if (left == right) return false
        return left.endsWith(".$right") || right.endsWith(".$left")
    }
}
