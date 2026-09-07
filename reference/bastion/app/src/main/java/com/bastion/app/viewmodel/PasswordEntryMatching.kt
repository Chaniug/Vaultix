package com.bastion.app.viewmodel

import com.bastion.app.data.PasswordEntry
import com.bastion.app.logging.runCatchingObserved
import java.net.URI
import java.util.Locale

/**
 * Phase B.3 集群 5a：密码条目的文本规范化、去重键与匹配判定。
 *
 * 这些逻辑原先散落在 [PasswordViewModel] 中，是一组**完全无状态的纯函数**：
 * 不读写 repository、不触碰 StateFlow、不启动协程、不做加解密。把它们收敛到
 * 独立文件后，[PasswordViewModel] 只保留状态编排。
 *
 * 每个函数都与搬迁前**逐字符等价**（包括大小写处理、trim 时机、异常兜底分支），
 * 不改变任何去重、搜索或"仅本地"判定结果。
 *
 * ⚠️ 注意：`AutofillPickerActivityV2.kt` 中另有一个同名的
 * `PasswordEntry.matchesSearchQuery(query)` 私有扩展，其语义与本文件的
 * [matchesSearchQuery] **不同**（前者不 trim 查询串、且不比对 appPackageName）。
 * 二者是各自场景的独立实现，**刻意不合并**——合并会静默改变自动填充的
 * 搜索命中集合。若未来要统一，必须先补行为测试再动。
 */
internal object PasswordEntryMatching {

    /**
     * 按当前分类过滤器在内存中筛选条目。18 个分支与 [CategoryFilter] 一一对应，
     * 纯筛选、不排序、不去重，保持传入顺序。
     *
     * 两个分支刻意返回全集：
     * - [CategoryFilter.Archived]：归档视图的取舍在上游完成
     * - [CategoryFilter.LocalOnly]：需要与完整数据集比对，由
     *   [filterLocalOnlyComparedToBitwarden] 单独处理
     */
    fun applyCategoryFilterInMemory(
        entries: List<PasswordEntry>,
        filter: CategoryFilter
    ): List<PasswordEntry> {
        return when (filter) {
            is CategoryFilter.All -> entries
            is CategoryFilter.Archived -> entries
            is CategoryFilter.Local -> entries.filter { it.isLocalOnlyEntry() }
            is CategoryFilter.LocalOnly -> entries // handled separately because it needs full dataset comparison
            is CategoryFilter.Starred -> entries.filter { it.isFavorite }
            is CategoryFilter.Uncategorized -> entries.filter { it.categoryId == null }
            is CategoryFilter.LocalStarred -> entries.filter { it.isLocalOnlyEntry() && it.isFavorite }
            is CategoryFilter.LocalUncategorized -> entries.filter { it.isLocalOnlyEntry() && it.categoryId == null }
            is CategoryFilter.Custom -> entries.filter { it.categoryId == filter.categoryId && it.isLocalOnlyEntry() }
            is CategoryFilter.KeePassDatabase -> entries.filter { it.keepassDatabaseId == filter.databaseId }
            is CategoryFilter.KeePassGroupFilter -> entries.filter {
                it.keepassDatabaseId == filter.databaseId && it.keepassGroupPath == filter.groupPath
            }
            is CategoryFilter.KeePassDatabaseStarred -> entries.filter {
                it.keepassDatabaseId == filter.databaseId && it.isFavorite
            }
            is CategoryFilter.KeePassDatabaseUncategorized -> entries.filter {
                it.keepassDatabaseId == filter.databaseId && it.keepassGroupPath.isNullOrBlank()
            }
            is CategoryFilter.BitwardenVault -> entries.filter { it.bitwardenVaultId == filter.vaultId }
            is CategoryFilter.BitwardenFolderFilter -> entries.filter {
                it.bitwardenVaultId == filter.vaultId && it.bitwardenFolderId == filter.folderId
            }
            is CategoryFilter.BitwardenVaultStarred -> entries.filter {
                it.bitwardenVaultId == filter.vaultId && it.isFavorite
            }
            is CategoryFilter.BitwardenVaultUncategorized -> entries.filter {
                it.bitwardenVaultId == filter.vaultId && it.bitwardenFolderId == null
            }
        }
    }

    /** 比较用文本规范化：去空白 + 转小写。 */
    fun normalizeComparableText(value: String): String {
        return value.trim().lowercase(Locale.ROOT)
    }

    /** 去重用文本规范化（当前与 [normalizeComparableText] 同实现，语义域不同故独立保留）。 */
    fun normalizeDedupeText(value: String): String {
        return value.trim().lowercase(Locale.ROOT)
    }

    /** 幽灵条目分组用的站点规范化：剥离协议头、www. 前缀与尾部斜杠。 */
    fun normalizeWebsiteForGhostGrouping(value: String): String {
        val raw = value.trim()
        if (raw.isEmpty()) return ""
        return raw
            .lowercase(Locale.ROOT)
            .removePrefix("http://")
            .removePrefix("https://")
            .removePrefix("www.")
            .trimEnd('/')
    }

    /**
     * 从候选集中挑出"信息量最大"的条目：依次比较备注长度、站点长度、
     * 用户名长度、是否收藏、归属状态，最后比较更新时间。
     */
    fun pickBestEntry(candidates: List<PasswordEntry>): PasswordEntry? {
        return candidates.maxWithOrNull(
            compareByDescending<PasswordEntry> { if (it.authenticatorKey.isNotBlank()) 1 else 0 }
                .thenBy { it.notes.length }
                .thenBy { it.website.length }
                .thenBy { it.username.length }
                .thenBy { if (it.isFavorite) 1 else 0 }
                .thenBy { if (it.hasOwnershipConflict()) 2 else if (!it.isLocalOnlyEntry()) 1 else 0 }
                .thenBy { it.updatedAt.time }
        )
    }

    /** 搜索命中判定：查询串去空白后，对标题/站点/用户名/应用名/包名做子串匹配。 */
    fun matchesSearchQuery(entry: PasswordEntry, query: String): Boolean {
        val normalizedQuery = query.trim().lowercase(Locale.ROOT)
        if (normalizedQuery.isBlank()) return true
        return entry.title.lowercase(Locale.ROOT).contains(normalizedQuery) ||
            entry.website.lowercase(Locale.ROOT).contains(normalizedQuery) ||
            entry.username.lowercase(Locale.ROOT).contains(normalizedQuery) ||
            entry.appName.lowercase(Locale.ROOT).contains(normalizedQuery) ||
            entry.appPackageName.lowercase(Locale.ROOT).contains(normalizedQuery)
    }

    /**
     * 提取可比较的域名。优先按 URI 解析取 host，解析失败或 host 为空时
     * 回退到「剥协议头 + 剥 www. + 截首个斜杠前」的字符串处理。
     */
    fun extractComparableDomain(value: String): String {
        val raw = value.trim()
        if (raw.isEmpty()) return ""

        return runCatchingObserved {
            val withScheme = if (raw.contains("://")) raw else "https://$raw"
            val host = URI(withScheme).host?.lowercase(Locale.ROOT)?.removePrefix("www.") ?: ""
            if (host.isNotBlank()) host else raw
                .lowercase(Locale.ROOT)
                .removePrefix("http://")
                .removePrefix("https://")
                .removePrefix("www.")
                .substringBefore('/')
        }.getOrElse {
            raw.lowercase(Locale.ROOT)
                .removePrefix("http://")
                .removePrefix("https://")
                .removePrefix("www.")
                .substringBefore('/')
        }.trim()
    }

    /** 去重键：标题|用户名|站点。 */
    fun buildDedupeKey(entry: PasswordEntry): String {
        val title = normalizeDedupeText(entry.title)
        val username = normalizeDedupeText(entry.username)
        val website = normalizeWebsiteForDedupe(entry.website)
        return "$title|$username|$website"
    }

    /**
     * 去重用的站点规范化：保留有意义的端口与路径（默认端口 80/443 略去），
     * URI 解析失败时回退到纯字符串剥离。
     */
    fun normalizeWebsiteForDedupe(value: String): String {
        val raw = value.trim()
        if (raw.isEmpty()) return ""

        return runCatchingObserved {
            val withScheme = if (raw.contains("://")) raw else "https://$raw"
            val uri = URI(withScheme)
            val host = (uri.host ?: "").lowercase(Locale.ROOT).removePrefix("www.")
            if (host.isEmpty()) return@runCatchingObserved raw.lowercase(Locale.ROOT).trimEnd('/')

            val port = uri.port
            val hostWithPort = if (port == -1 || port == 80 || port == 443) host else "$host:$port"
            val path = (uri.path ?: "").trim().trimEnd('/').lowercase(Locale.ROOT)
            if (path.isBlank()) hostWithPort else "$hostWithPort$path"
        }.getOrElse {
            raw.lowercase(Locale.ROOT)
                .removePrefix("http://")
                .removePrefix("https://")
                .removePrefix("www.")
                .trimEnd('/')
        }
    }

    /**
     * "仅本地"的判定依据：
     * 1) 不是 KeePass 条目
     * 2) 不是已同步的 Bitwarden cipher
     * 3) 任何 Bitwarden 保险库中都不存在可匹配项
     */
    fun filterLocalOnlyComparedToBitwarden(entries: List<PasswordEntry>): List<PasswordEntry> {
        if (entries.isEmpty()) return emptyList()

        val bitwardenIndexByUsername = entries
            .asSequence()
            .filter { it.keepassDatabaseId == null && it.bitwardenVaultId != null && it.bitwardenCipherId != null }
            .map {
                BitwardenComparableSignature(
                    username = normalizeComparableText(it.username),
                    title = normalizeComparableText(it.title),
                    domain = extractComparableDomain(it.website)
                )
            }
            .filter { it.username.isNotBlank() && (it.title.isNotBlank() || it.domain.isNotBlank()) }
            .groupBy { it.username }

        return entries.filter { entry ->
            isLocalOnlyComparedToBitwarden(entry, bitwardenIndexByUsername)
        }
    }

    private fun isLocalOnlyComparedToBitwarden(
        entry: PasswordEntry,
        bitwardenIndexByUsername: Map<String, List<BitwardenComparableSignature>>
    ): Boolean {
        if (!entry.isLocalOnlyEntry()) return false
        if (entry.bitwardenCipherId != null) return false

        val username = normalizeComparableText(entry.username)
        if (username.isBlank()) return true

        val domain = extractComparableDomain(entry.website)
        val title = normalizeComparableText(entry.title)
        if (domain.isBlank() && title.isBlank()) return true

        val candidates = bitwardenIndexByUsername[username] ?: return true
        val matched = candidates.any { candidate ->
            (domain.isNotBlank() && domain == candidate.domain) ||
                (title.isNotBlank() && title == candidate.title)
        }
        return !matched
    }

    private data class BitwardenComparableSignature(
        val username: String,
        val title: String,
        val domain: String
    )
}
