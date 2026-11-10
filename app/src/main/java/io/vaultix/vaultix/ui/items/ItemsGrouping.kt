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
 * 分组维度取 Vaultix（Bitwarden canonical）模型里**真实存在**的来源，外加「不分组」：
 *   - 按类型：登录 / 银行卡 / 身份 / 安全笔记 / SSH 密钥（`VaultItemType`）；
 *   - 按文件夹：Bitwarden 原生 folder（`VaultItem.folderId` + `FolderRepository`）；
 *   - 按首字母：标题首字符（中文按原字符、数字/符号归「#」）；
 *   - 按站点：从 `uris` 归一化出「站点 / 应用」键（#278，见 [groupBySite]）。
 * 未搬运 Bastion 的 `group by app / note / smart` 的「预设字段系统」部分 —— 那依赖它的
 * 私有条目类型与预设字段，Vaultix 没有对应物；「按站点」只用真实存在的 `uris`。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.items

import io.vaultix.common.UriFormat
import io.vaultix.common.UriKind
import io.vaultix.model.VaultFolder
import io.vaultix.model.VaultItem
import io.vaultix.vaultix.autofill.match.PublicSuffixList

/** 分组方式（`storageKey` 落偏好，改动需保持向后兼容）。 */
enum class ItemsGroupMode(val storageKey: String) {
    /** 不分组（默认，与历史行为一致）。 */
    None("none"),
    Type("type"),
    Folder("folder"),
    Initial("initial"),

    /**
     * 按站点 / 应用（#278）：纯展示层归纳，不改数据、不改自动填充。
     * 同一基域（eTLD+1）或同一应用包名下 **≥2 条**条目才成组；
     * 单条站点与无 URI 条目归入尾部「未分组」区，避免站点分散时满屏组头。
     */
    Site("site"),
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
 * @param ungroupedLabel 按站点分组时，单条站点与无 URI 条目所在尾部分组的名字。
 * @param siteLabel 按站点分组时把站点 / 应用键翻成标题：参数一是键值（基域或包名），
 *   参数二为该键是否应用包名（应用标题要用 [io.vaultix.vaultix.ui.common.AppLabel] 翻中文，
 *   这需要 `Context`，所以由调用方注入而不是在这里读）。
 */
fun groupItems(
    items: List<VaultItem>,
    folders: List<VaultFolder>,
    mode: ItemsGroupMode,
    typeLabel: (VaultItem) -> String,
    unnamedLabel: String,
    noFolderLabel: String,
    ungroupedLabel: String = noFolderLabel,
    siteLabel: (String, Boolean) -> String = { key, _ -> key },
): List<ItemsGroup> = when (mode) {
    ItemsGroupMode.None -> listOf(ItemsGroup(key = NONE_KEY, title = "", items = items))
    ItemsGroupMode.Type -> groupByType(items, typeLabel)
    ItemsGroupMode.Folder -> groupByFolder(items, folders, noFolderLabel)
    ItemsGroupMode.Initial -> groupByInitial(items, unnamedLabel)
    ItemsGroupMode.Site -> groupBySite(items, ungroupedLabel, siteLabel)
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
    // ⚠️ 悬空 folderId（文件夹被删 / 尚未同步）也归「无文件夹」：
    //    否则会以旧 folderId 为 key 再生成一个同标题的「无文件夹」分组 ——
    //    界面上出现两个同名组，且条目不在真正的 NO_FOLDER_KEY 组里。
    //    Bitwarden 的做法是删文件夹后主动清空 entries 的 folderId；这里在展示侧
    //    再兜一层，历史遗留的悬空值也能正确归组。
    val (withoutFolder, withFolder) = items.partition {
        it.folderId.isNullOrBlank() || it.folderId !in nameById
    }
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
    ItemsGroupMode.Site -> io.vaultix.vaultix.R.string.items_group_site
}

/** 「未分组」尾部分组键（避免与站点键冲突的前缀命名）。 */
private const val UNGROUPED_KEY = "__ungrouped__"

/** 一个站点 / 应用的归属键：`web:<基域>` 或 `app:<包名>`。 */
private data class SiteKey(val key: String, val value: String, val isApp: Boolean)

/**
 * 条目的「主站点」键：一条条目只归一组（列表 `key = it.id` 不允许重复出现）。
 *
 * 主 URI 取第一条应用 URI，没有再取第一条网站 URI —— 与自动填充里「同站点多端」
 * 的直觉一致（同一 App 的几个账号是用户点名要归组的场景）。其余 URI 不参与归属；
 * 只有 `Other` 或没有 URI 的条目返回 `null`（归入未分组）。
 *
 * 网站键用基域（eTLD+1，见 [PublicSuffixList.baseDomain]）而不是完整 host：
 * `a.github.com` 与 `b.github.com` 归一组，但 `a.github.io` 与 `b.github.io` 不归
 * （`github.io` 在公共后缀列表里，各自基域独立）——对齐 Bitwarden 的 PSL 口径。
 */
private fun siteKeyOf(item: VaultItem): SiteKey? {
    val kinds = item.uris.map { UriFormat.classify(it.uri) }
    (kinds.filterIsInstance<UriKind.AndroidApp>().firstOrNull())?.let { app ->
        return SiteKey("app:${app.packageName}", app.packageName, isApp = true)
    }
    (kinds.filterIsInstance<UriKind.Website>().firstOrNull())?.let { site ->
        val base = PublicSuffixList.baseDomain(site.host)
        return SiteKey("web:$base", base, isApp = false)
    }
    return null
}

/**
 * 按站点 / 应用分组（#278）。
 *
 * - 同一键 **≥2 条**才成组：站点分散的库里按 host 一组一组切，组头比条目还多，
 *   反而更乱（8.4「实体数量 ≤ 属性数量时分组收益最明显」的镜像判据）；
 * - 组标题排序用标题本身（站点与应用混排时按字母序可预期），组内保持传入顺序；
 * - 单条站点 + 无 URI 条目合入尾部「未分组」组（只这一组，不逐站点开组头），
 *   组内仍按调用方排好的顺序原样排 —— 展示层归纳不改变条目本身的传达。
 */
private fun groupBySite(
    items: List<VaultItem>,
    ungroupedLabel: String,
    siteLabel: (String, Boolean) -> String,
): List<ItemsGroup> {
    val titles = HashMap<String, String>()
    val buckets = LinkedHashMap<String, MutableList<VaultItem>>()
    val noSite = mutableListOf<VaultItem>()
    items.forEach { item ->
        val key = siteKeyOf(item)
        if (key == null) {
            // ⚠️ 没有站点键的条目必须仍出现在列表里（曾因直接 return 丢条目）。
            noSite.add(item)
            return@forEach
        }
        buckets.getOrPut(key.key) { mutableListOf() }.add(item)
        titles[key.key] = siteLabel(key.value, key.isApp)
    }
    val grouped = buckets.entries
        .filter { it.value.size >= MIN_SITE_GROUP_SIZE }
        .sortedBy { (titles[it.key] ?: it.key).lowercase() }
        .map { (key, grouped) -> ItemsGroup(key = key, title = titles[key].orEmpty(), items = grouped) }
    val ungrouped = buckets.entries.filter { it.value.size < MIN_SITE_GROUP_SIZE }.flatMap { it.value } + noSite
    if (ungrouped.isEmpty()) return grouped
    return grouped + ItemsGroup(key = UNGROUPED_KEY, title = ungroupedLabel, items = ungrouped)
}

/** 同一站点 / 应用至少这么多条条目才值得开一个组头。 */
private const val MIN_SITE_GROUP_SIZE = 2
