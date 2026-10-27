/*
 * Vaultix — domain（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.domain

import com.google.common.truth.Truth.assertThat
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import org.junit.Test

/**
 * [ImportDuplicatePolicy] 的表驱动单测。
 *
 * 重点不是"同名能判出来"，而是**不该判重的绝不能判重**：
 * 静默把两条不同账号合成一条，用户不会发现，直到某天密码怎么都不对。
 */
class ImportDuplicatePolicyTest {

    private fun item(
        title: String,
        username: String = "",
        type: VaultItemType = VaultItemType.Login,
    ): VaultItem = VaultItem(id = "id-$title-$username", title = title, username = username, type = type)

    private fun conflictsOf(imported: List<VaultItem>, existing: List<VaultItem>): List<String> =
        ImportDuplicatePolicy.conflicts(imported, existing).map { it.imported.title }

    @Test
    fun `同标题同用户名同类型 判为重复`() {
        assertThat(
            conflictsOf(
                imported = listOf(item("GitHub", "alice")),
                existing = listOf(item("GitHub", "alice")),
            ),
        ).containsExactly("GitHub")
    }

    @Test
    fun `同一站点两个账号 不判重`() {
        assertThat(
            conflictsOf(
                imported = listOf(item("GitHub", "alice@a.com"), item("GitHub", "alice@b.com")),
                existing = listOf(item("GitHub", "alice@a.com")),
            ),
        ).containsExactly("GitHub")
    }

    @Test
    fun `标题或用户名只差大小写与首尾空白 仍判重`() {
        assertThat(
            conflictsOf(
                imported = listOf(item(" GitHub ", "ALICE")),
                existing = listOf(item("github", "alice")),
            ),
        ).containsExactly(" GitHub ")
    }

    @Test
    fun `类型不同不判重`() {
        assertThat(
            conflictsOf(
                imported = listOf(item("Visa", type = VaultItemType.Card)),
                existing = listOf(item("Visa", type = VaultItemType.SecureNote)),
            ),
        ).isEmpty()
    }

    @Test
    fun `库里本来就有两份 也只报一条`() {
        val conflicts = ImportDuplicatePolicy.conflicts(
            imported = listOf(item("GitHub", "alice")),
            existing = listOf(item("GitHub", "alice"), item("GitHub", "alice")),
        )
        assertThat(conflicts).hasSize(1)
    }

    @Test
    fun `完全没有交集时不报`() {
        assertThat(
            conflictsOf(
                imported = listOf(item("A"), item("B")),
                existing = listOf(item("C")),
            ),
        ).isEmpty()
    }

    @Test
    fun `导入为空 或库为空 都不炸`() {
        assertThat(ImportDuplicatePolicy.conflicts(emptyList(), listOf(item("A")))).isEmpty()
        assertThat(ImportDuplicatePolicy.conflicts(listOf(item("A")), emptyList())).isEmpty()
    }

    @Test
    fun `冲突里带回库里那一条的标题 供提示用`() {
        val conflicts = ImportDuplicatePolicy.conflicts(
            imported = listOf(item(" github ", "alice")),
            existing = listOf(item("GitHub", "alice")),
        )
        // 提示里显示的是**库里那条**的原始标题（用户能在列表里对上号）。
        assertThat(conflicts.single().existingTitle).isEqualTo("GitHub")
    }
}
