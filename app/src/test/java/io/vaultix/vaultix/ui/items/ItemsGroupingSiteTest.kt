/*
 * Vaultix — app:ui:items
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.items

import com.google.common.truth.Truth.assertThat
import io.vaultix.common.UriFormat
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import io.vaultix.model.VaultUri
import org.junit.Test

/**
 * 「按站点」分组（#278）规则钉子：
 * 同基域 / 同包名 ≥2 条才成组；一条只归一组；单条站点与无 URI 归尾部「未分组」。
 */
class ItemsGroupingSiteTest {

    private val labels = mapOf(
        "com.google.android.gms" to "Google Play 服务",
        "github.com" to "github.com",
    )

    private fun item(
        id: String,
        title: String = id,
        uris: List<String> = emptyList(),
    ): VaultItem = VaultItem(
        id = id,
        title = title,
        type = VaultItemType.Login,
        uris = uris.map { VaultUri(it) },
    )

    private fun group(items: List<VaultItem>): List<ItemsGroup> = groupItems(
        items = items,
        folders = emptyList(),
        mode = ItemsGroupMode.Site,
        typeLabel = { it.type.name },
        unnamedLabel = "未命名",
        noFolderLabel = "无文件夹",
        ungroupedLabel = "未分组",
        siteLabel = { key, _ -> labels[key] ?: key },
    )

    @Test
    fun `同基域的多条条目归为一组`() {
        val groups = group(
            listOf(
                item("1", uris = listOf("https://github.com/a")),
                item("2", uris = listOf("https://gist.github.com/b")),
                item("3", uris = listOf("https://gitlab.com/c")),
                item("4", uris = listOf("https://gitlab.com/d")),
            ),
        )
        assertThat(groups.map { it.title }).containsExactly("github.com", "gitlab.com").inOrder()
        // 子域按基域合并，但组内保持传入顺序。
        assertThat(groups[0].items.map { it.id }).containsExactly("1", "2").inOrder()
    }

    @Test
    fun `公共后缀下的站点不合组`() {
        // github.io 在 PSL 里：两个不同子域基域独立，各只有一条 → 全进未分组。
        val groups = group(
            listOf(
                item("1", uris = listOf("https://a.github.io/x")),
                item("2", uris = listOf("https://b.github.io/y")),
            ),
        )
        assertThat(groups).hasSize(1)
        assertThat(groups[0].key).isEqualTo("__ungrouped__")
        assertThat(groups[0].items.map { it.id }).containsExactly("1", "2")
    }

    @Test
    fun `同一应用的账号按包名归组且标题走注入翻译`() {
        val groups = group(
            listOf(
                item("1", uris = listOf(UriFormat.androidAppUri("com.google.android.gms"))),
                item("2", uris = listOf("androidapp://com.google.android.gms")),
            ),
        )
        assertThat(groups).hasSize(1)
        assertThat(groups[0].title).isEqualTo("Google Play 服务")
        assertThat(groups[0].items).hasSize(2)
    }

    @Test
    fun `一条条目只归一组_应用优先于网站`() {
        val groups = group(
            listOf(
                item("1", uris = listOf("https://example.com", "androidapp://com.example.app")),
                item("2", uris = listOf("androidapp://com.example.app")),
            ),
        )
        assertThat(groups).hasSize(1)
        assertThat(groups[0].key).isEqualTo("app:com.example.app")
        // 主 URI 是应用，条目 1 不再出现在 web:example.com 组（它也没成组）。
        assertThat(groups[0].items.map { it.id }).containsExactly("1", "2")
    }

    @Test
    fun `单条站点与无URI条目归未分组且排在成组区之后`() {
        val groups = group(
            listOf(
                item("1", uris = listOf("https://solo.example.com")),
                item("2", uris = listOf("https://pair.com/a")),
                item("3", uris = listOf("https://pair.com/b")),
                item("4"),
                item("5", uris = listOf("ftp://weird")),
            ),
        )
        assertThat(groups.map { it.title }).containsExactly("pair.com", "未分组").inOrder()
        // 未分组里保持传入顺序：单条站点在前（"ftp://weird" 能解析出 host，也是单条站点）、
        // 完全无 URI 在后。
        assertThat(groups[1].items.map { it.id }).containsExactly("1", "5", "4").inOrder()
    }

    @Test
    fun `全部条目都不成组时只剩未分组`() {
        val groups = group(listOf(item("1"), item("2")))
        assertThat(groups).hasSize(1)
        assertThat(groups[0].title).isEqualTo("未分组")
        assertThat(groups[0].items.map { it.id }).containsExactly("1", "2")
    }

    @Test
    fun `storageKey落偏好向后兼容`() {
        assertThat(ItemsGroupMode.from("site")).isEqualTo(ItemsGroupMode.Site)
        assertThat(ItemsGroupMode.from("none")).isEqualTo(ItemsGroupMode.None)
        assertThat(ItemsGroupMode.from("bogus")).isEqualTo(ItemsGroupMode.None)
        assertThat(ItemsGroupMode.Site.storageKey).isEqualTo("site")
    }
}
