/*
 * Vaultix — app:ui:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * [selectableItemTypes] / [supportsCardEntries] 的裁剪口径（A3）。
 *
 * 这两条判据是「表单摆哪几枚类型筹码」与「卡包 Tab 显不显示 +」的**唯一真相**，
 * 漂移的代价是自相矛盾的门（表单能选卡、卡包不让建 / 反过来亦然）⇒ 用纯 JVM
 * 单测钉死，不依赖 Compose 运行时。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.common

import io.vaultix.model.VaultItemType
import io.vaultix.model.VaultKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemTypePolicyTest {

    /**
     * KDBX 只给两种，且**顺序固定**（登录在前）——顺序即表单里的展示顺序，
     * 也是"当前类型不在表内"时的兜底顺序（见 [selectableItemTypes] 的 KDoc）。
     */
    @Test
    fun `KDBX 库只给登录与安全笔记_顺序固定`() {
        assertEquals(
            listOf(VaultItemType.Login, VaultItemType.SecureNote),
            selectableItemTypes(VaultKind.KDBX),
        )
    }

    /** Bitwarden 侧五类全给（云端五类都有对应载荷，裁剪没有任何收益）。 */
    @Test
    fun `Bitwarden 库五种全给`() {
        assertEquals(VaultItemType.entries.toList(), selectableItemTypes(VaultKind.BITWARDEN))
    }

    /**
     * 库类型还没解析出来（摘要流首发前）⇒ **不缩小**。
     * 宁可多给一个选项，也不在信息不足时替用户做缩小决定（与"未知类型不映射到
     * Login"同向，见 [selectableItemTypes] 的 KDoc）。
     */
    @Test
    fun `库类型未知时不缩小_五种全给`() {
        assertEquals(VaultItemType.entries.toList(), selectableItemTypes(null))
    }

    /**
     * 卡入口判定必须由同一个函数派生 —— 否则会出现"表单里能选卡、卡包却不让建"
     * 这种自相矛盾的门（两个判断各自漂移的产物）。
     */
    @Test
    fun `卡入口判定与可选集同源`() {
        assertTrue(supportsCardEntries(VaultKind.BITWARDEN))
        assertTrue(supportsCardEntries(null))
        assertFalse(supportsCardEntries(VaultKind.KDBX))
    }
}
