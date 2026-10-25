package io.vaultix.vaultix.ui.common

import io.vaultix.model.VaultItem

/**
 * 条目搜索过滤（纯函数，便于单测）。
 *
 * 匹配范围：**标题 / 用户名 / 任一网址 / 备注 / 自定义字段名与值**，忽略大小写；
 * 空词或纯空白 = 全部命中。
 * 抽出来的原因：条目列表、验证码列表、通行密钥列表三处都需要搜索，
 * 与其各写一份 `filteredEntries()`，不如共用同一套匹配语义（避免行为漂移）。
 *
 * ## 为什么把「备注」与「自定义字段」加进来（#191，2026-10-26）
 *
 * 原来只搜标题/用户名/网址。而用户的真实用法是**把识别信息记在备注里**：
 * 「建行 工资卡」「XX 小区的门禁」「公司 VPN，账号找 IT 要」…… 于是
 *
 * > 明明备注里写着"工资卡"，搜"工资"却搜不到 —— 用户只会认为"搜索坏了"，
 * > 然后退化成一条条往下翻。
 *
 * 对齐 Bitwarden / KeePassXC：它们的搜索都覆盖 notes 与自定义字段
 * （KeePassXC 甚至按字段可配置）。而**不搜密码**是各家一致的取舍 ——
 * 搜密码会让"输错一个字母就多出一条结果"，也会让搜索结果的可分享截图泄露密码。
 *
 * ⚠️ 仍**不搜密码**，也不要加：见上。
 */
object ItemFilter {

    /** 单个条目是否命中搜索词。 */
    fun matches(item: VaultItem, query: String): Boolean {
        val q = query.trim()
        if (q.isBlank()) return true
        return item.title.contains(q, ignoreCase = true) ||
            item.username.contains(q, ignoreCase = true) ||
            item.uris.any { it.uri.contains(q, ignoreCase = true) } ||
            item.notes.contains(q, ignoreCase = true) ||
            item.customFields.any { field ->
                field.name.contains(q, ignoreCase = true) ||
                    field.value.contains(q, ignoreCase = true)
            }
    }

    /** 按搜索词过滤；空词直接返回原列表（不复制）。 */
    fun filter(items: List<VaultItem>, query: String): List<VaultItem> {
        val q = query.trim()
        if (q.isBlank()) return items
        return items.filter { matches(it, q) }
    }
}
