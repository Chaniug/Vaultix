/*
 * Vaultix — domain
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.domain

import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType

/**
 * 导入时的**重复判定**（2026-10-28）。
 *
 * ## 为什么需要它
 *
 * `applyImportedVault` 原本是"逐条新建、绝不合并"。这在**首次**迁移时正确，
 * 在**重复导入**时是灾难：用户从旧手机导出、导进新手机、过两天又导一次 → 库里
 * 每个条目都变成两份。而密码管理器里"两份同名条目"最坏的后果不是难看，是
 * **无法判断哪一份是新的**，用户改了 A 那一份、自动填充却可能填 B。
 *
 * ## 判什么重：类型 + 标题 + 用户名（**不用密码**）
 *
 * | 候选键 | 为什么不用 |
 * |---|---|
 * | 条目 id | 换库 / 换账号后同一份数据本来就会换 id；用 id 判重等于"永远不重" |
 * | 密码 | 改密码后标题用户名不变、密码变了 —— 那正是**同一个条目**，按密码判重会漏 |
 * | 网址 | 一条常挂多个网址且可空；空网址的笔记（"Wi-Fi 密码"之类）会全撞在一起 |
 *
 * ⇒ 取 **type + title + username** 三元组。它保守：**只合并看起来完全同一条的**，
 * 宁可留下两份可疑条目，也不要把两条不同账号（`a@a.com` / `a@b.com`）静默抹成一条。
 * 用户名参与判定正是为了区分同一站点的两个账号。
 *
 * ## 为什么要归一化
 *
 * 官方导出会保留用户输入里的首尾空格，甚至大小写差异（`GitHub` vs `github`）。
 * 不归一化的话，"看起来一样"的两条会被判成不同 ⇒ 用户以为去重生效了、其实没有。
 * 归一化只做**大小写 + 首尾空白**：这是人眼认定的"一样"，且不会把 `Foo` 与 `FOO`
 * 两条确实想分开的条目合并成一条 —— 那种歧义本来就无解，保守一侧是合并。
 *
 * 纯函数、无依赖：不碰库、不读配置，便于单测直接钉住语义。
 */
object ImportDuplicatePolicy {

    /** 判定用的键（已归一化）。 */
    data class Key(val type: VaultItemType, val title: String, val username: String)

    /**
     * 一条导入条目的判定信息：键 + 与之冲突时需要比较的原文（用于提示文案）。
     *
     * 只带 [existing] 的**标题**就够：用户在提示里看到"3 条已存在（如 GitHub）"，
     * 能对上号即可，不必把整条目搬进 UI 状态（也可能不该显示用户名）。
     */
    data class Conflict(val imported: VaultItem, val existingTitle: String)

    /**
     * 计算重复情况。
     *
     * @param imported 本次要导入的条目（已映射为领域模型）。
     * @param existing 目标库**当前**的条目（未删除的，回收站里的不算 —— 回收站条目
     *   按设计就是"用户不要了"，拿它来挡导入会让用户怎么导都导不进去）。
     * @return 被判为"已存在"的条目（顺序与 [imported] 一致，便于提示时取前几条）。
     */
    fun conflicts(imported: List<VaultItem>, existing: List<VaultItem>): List<Conflict> {
        if (imported.isEmpty()) return emptyList()
        val byKey = HashMap<Key, String>(existing.size)
        existing.forEach { item ->
            // 同键多条（库里本来就有两份）：留最早一条的标题即可 —— 提示只需一个代表。
            byKey.putIfAbsent(keyOf(item), item.title)
        }
        return imported.mapNotNull { item ->
            byKey[keyOf(item)]?.let { title -> Conflict(imported = item, existingTitle = title) }
        }
    }

    /** 归一化键：类型 + 去首尾空白并转小写的标题 + 同口径的用户名。 */
    fun keyOf(item: VaultItem): Key = Key(
        type = item.type,
        title = normalize(item.title),
        username = normalize(item.username),
    )

    private fun normalize(raw: String): String = raw.trim().lowercase()
}
