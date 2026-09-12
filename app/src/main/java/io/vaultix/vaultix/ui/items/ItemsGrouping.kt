/*
 * Vaultix — app:ui:items
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 条目分组（纯逻辑，便于 JVM 单测）。
 *
 * 分组维度取 Vaultix（Bitwarden canonical）模型里**真实存在**的三种，外加「不分组」：
 *   - 按类型：登录 / 银行卡 / 身份 / 安全笔记 / SSH 密钥（`VaultItemType`）；
 *   - 按文件夹：Bitwarden 原生 folder（`VaultItem.folderId` + `FolderRepository`）；
 *   - 按首字母：标题首字符（中文按原字符、数字/符号归「#」）。
 * 未搬运 Bastion 的 `group by app / note / smart` —— 那三种依赖它的私有条目类型与
 * 预设字段系统，Vaultix 没有对应物（搬过来只会是永远空的分组）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.items

import io.vaultix.model.VaultFolder
import io.vaultix.model.VaultItem

/** 分组方式（`storageKey` 落偏好，改动需保持向后兼容）。 */
enum class ItemsGroupMode(val storageKey: String) {
    /** 不分组（默认，与历史行为一致）。 */
    None("none"),
    Type("type"),
    Folder("folder"),
    Initial("initial"),
    ;

    companion object {
        fun from(storageKey: String?): ItemsGroupMode =
            entries.firstOrNull { it.storageKey == storageKey } ?: None
    }
}

/** 一个分组：标题 + 归属条目（顺序与列表一致）。 */
data class ItemsGroup(
    val key: String,
    val title: String,
    val items: List<VaultItem>,
)

/**
 * 按 [mode] 把 [items] 分组。
 *
 * - [mode] 为 [ItemsGroupMode.None] 时返回**单个**分组（调用方据此不渲染分组标题）；
 * - 组内保持传入顺序（调用方已按收藏/标题排好）；
 * - 分组顺序：按类型固定枚举顺序；按文件夹按名称；按首字母按字母序、「#」置末。
 *
 * @param folders 库内文件夹（用于把 folderId 翻成名字）。
 * @param unnamedLabel 标题为空时的兜底名（用于首字母分组的「#」判定）。
 * @param noFolderLabel 无文件夹条目的分组名。
 */
fun groupItems(
    items: List<VaultItem>,
    folders: List<VaultFolder>,
    mode: ItemsGroupMode,
    typeLabel: (VaultItem) -> String,
    unnamedLabel: String,
    noFolderLabel: String,
): List<ItemsGroup> = when (mode) {
    ItemsGroupMode.None -> listOf(ItemsGroup(key = NONE_KEY, title = "", items = items))
    ItemsGroupMode.Type -> groupByType(items, typeLabel)
    ItemsGroupMode.Folder -> groupByFolder(items, folders, noFolderLabel)
    ItemsGroupMode.Initial -> groupByInitial(items, unnamedLabel)
}

private const val NONE_KEY = "__all__"

/** 数字 / 符号 / 空白首字符归到该分组。 */
private const val OTHER_INITIAL = "#"

private fun groupByType(items: List<VaultItem>, typeLabel: (VaultItem) -> String): List<ItemsGroup> =
    items
        .groupBy { it.type }
        .toList()
        .sortedBy { (type, _) -> type.ordinal }
        .map { (type, grouped) ->
            ItemsGroup(key = type.name, title = typeLabel(grouped.first()), items = grouped)
        }

private fun groupByFolder(
    items: List<VaultItem>,
    folders: List<VaultFolder>,
    noFolderLabel: String,
): List<ItemsGroup> {
    val nameById = folders.associate { it.id to it.name }
    val (withoutFolder, withFolder) = items.partition { it.folderId.isNullOrBlank() }
    val groups = withFolder
        .groupBy { it.folderId.orEmpty() }
        .map { (folderId, grouped) ->
            ItemsGroup(
                key = folderId,
                // 文件夹被删/未同步时用「未命名文件夹」兜底，避免出现空标题分组。
                title = nameById[folderId]?.takeIf { it.isNotBlank() } ?: noFolderLabel,
                items = grouped,
            )
        }
        .sortedBy { it.title }
    if (withoutFolder.isEmpty()) return groups
    // 「无文件夹」永远排在最后（与 Bitwarden 网页端一致）。
    return groups + ItemsGroup(key = NO_FOLDER_KEY, title = noFolderLabel, items = withoutFolder)
}

private const val NO_FOLDER_KEY = "__no_folder__"

private fun groupByInitial(items: List<VaultItem>, unnamedLabel: String): List<ItemsGroup> {
    val groups = items.groupBy { initialOf(it.title.ifBlank { unnamedLabel }) }
    val letters = groups.filterKeys { it != OTHER_INITIAL }.toSortedMap()
    val others = groups[OTHER_INITIAL].orEmpty()
    return letters.map { (letter, grouped) -> ItemsGroup(letter, letter, grouped) } +
        if (others.isEmpty()) emptyList() else listOf(ItemsGroup(OTHER_INITIAL, OTHER_INITIAL, others))
}

/** 标题首字符：ASCII 字母统一大写；数字 / 符号 / 中文以外一律归 [OTHER_INITIAL]。 */
private fun initialOf(title: String): String {
    val first = title.trim().firstOrNull() ?: return OTHER_INITIAL
    if (first.isDigit()) return OTHER_INITIAL
    val upper = first.uppercaseChar()
    return if (upper in 'A'..'Z') upper.toString() else OTHER_INITIAL
}

/**
 * 分组方式的中文名（显示选项弹层与设置页共用）。
 *
 * 放在本文件而不是 `ItemsScreen.kt`：后者是 private-in-file，弹层与设置页都要用，
 * 放这里定义一次即可（两个界面文案不会漂移）。
 */
@androidx.annotation.StringRes
internal fun groupModeLabelRes(mode: ItemsGroupMode): Int = when (mode) {
    ItemsGroupMode.None -> io.vaultix.vaultix.R.string.items_group_none
    ItemsGroupMode.Type -> io.vaultix.vaultix.R.string.items_group_type
    ItemsGroupMode.Folder -> io.vaultix.vaultix.R.string.items_group_folder
    ItemsGroupMode.Initial -> io.vaultix.vaultix.R.string.items_group_initial
}
