package io.vaultix.vaultix.ui.common

import io.vaultix.model.VaultItem

/**
 * 条目搜索过滤（纯函数，便于单测）。
 *
 * 匹配范围：**标题 / 用户名 / 任一网址**，忽略大小写；空词或纯空白 = 全部命中。
 * 抽出来的原因：条目列表、验证码列表、通行密钥列表三处都需要搜索，
 * 与其各写一份 `filteredEntries()`，不如共用同一套匹配语义（避免行为漂移）。
 */
object ItemFilter {

    /** 单个条目是否命中搜索词。 */
    fun matches(item: VaultItem, query: String): Boolean {
        val q = query.trim()
        if (q.isBlank()) return true
        return item.title.contains(q, ignoreCase = true) ||
            item.username.contains(q, ignoreCase = true) ||
            item.uris.any { it.uri.contains(q, ignoreCase = true) }
    }

    /** 按搜索词过滤；空词直接返回原列表（不复制）。 */
    fun filter(items: List<VaultItem>, query: String): List<VaultItem> {
        val q = query.trim()
        if (q.isBlank()) return items
        return items.filter { matches(it, q) }
    }
}
