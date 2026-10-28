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
     * 一次导入的**分类计划**（2026-10-28 新增）。
     *
     * 把"新建 / 库里已存在 / 文件内重复"三类一次分完，预览与写入**共用这一份**。
     * 此前的 `conflicts()` 只比现有库，文件内部的两条一样的会全部进"新建"，
     * 于是同一次导入就能把重复原样复制一遍。
     */
    data class Plan(
        /** 会被新建的条目（顺序与输入一致）。 */
        val newItems: List<VaultItem>,
        /** 与目标库已有条目冲突的（按输入顺序）。 */
        val existingDuplicates: List<Conflict>,
        /** 与**本批次前面某条**冲突的（按输入顺序；代表标题取先出现的那条）。 */
        val inFileDuplicates: List<Conflict>,
    )

    /**
     * 计算导入分类。
     *
     * 判定顺序（也是唯一口径）：
     * 1. 与 [existing] 同键 ⇒ [Plan.existingDuplicates]；
     * 2. 与**本批次已接受**的某条同键 ⇒ [Plan.inFileDuplicates]；
     * 3. 其余 ⇒ [Plan.newItems]。
     *
     * ⚠️ 只有被接受的条目才进入 `seen`。被现有库挡下的条目不参与"批次内重复"判定，
     * 否则同一份文件里重复三次的条目会报成"1 条已存在 + 2 条文件内重复"，而用户
     * 真正需要知道的是"这 3 条一条都不会写"。
     */
    fun plan(imported: List<VaultItem>, existing: List<VaultItem>): Plan {
        if (imported.isEmpty()) return Plan(emptyList(), emptyList(), emptyList())
        val existingTitles = HashMap<Key, String>(existing.size)
        existing.forEach { item ->
            // 同键多条（库里本来就有两份）：留最早一条的标题即可 —— 提示只需一个代表。
            existingTitles.putIfAbsent(keyOf(item), item.title)
        }
        val acceptedKeys = HashSet<Key>(imported.size)
        val newItems = ArrayList<VaultItem>(imported.size)
        val existingDuplicates = ArrayList<Conflict>()
        val inFileDuplicates = ArrayList<Conflict>()
        imported.forEach { item ->
            val key = keyOf(item)
            val existingTitle = existingTitles[key]
            when {
                existingTitle != null -> existingDuplicates += Conflict(item, existingTitle)
                !acceptedKeys.add(key) -> inFileDuplicates += Conflict(item, item.title)
                else -> newItems += item
            }
        }
        return Plan(
            newItems = newItems,
            existingDuplicates = existingDuplicates,
            inFileDuplicates = inFileDuplicates,
        )
    }

    /**
     * 只取"与现有库冲突"的部分。
     *
     * 保留这个入口是为了不破坏既有调用/单测；新代码请直接用 [plan] ——
     * 只调这个函数就会漏掉"文件内重复"这一维（正是 2026-10-28 修掉的 bug）。
     */
    fun conflicts(imported: List<VaultItem>, existing: List<VaultItem>): List<Conflict> =
        plan(imported, existing).existingDuplicates

    /** 归一化键：类型 + 去首尾空白并转小写的标题 + 同口径的用户名。 */
    fun keyOf(item: VaultItem): Key = Key(
        type = item.type,
        title = normalize(item.title),
        username = normalize(item.username),
    )

    private fun normalize(raw: String): String = raw.trim().lowercase()
}
