/*
 * Vaultix — data:bitwarden (test)
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.data.bitwarden.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「删除前确认」判据的测试（2026-09-16）。
 *
 * ## 这些用例在钉什么
 *
 * 判据有**两条腿**（绝对量 / 占比），任一条成立就要确认。测试必须同时覆盖：
 * - 日常少量删除**不该**被拦（否则每次在官方网页端删几条都要多同步一次，用户会烦）；
 * - 批量删除**必须**被拦（这是防"服务端故障导致本地被清"的闸）；
 * - **小库**只能靠占比那条腿保护（绝对量永远达不到）——单独测它，
 *   是为了证明"绝对量门槛"一条腿不够用。
 */
class EmptyVaultProtectionTest {

    @Test
    fun `没有要删的行时不需要确认`() {
        assertFalse(
            EmptyVaultProtection.requiresDeleteConfirmation(toDeleteCount = 0, localCount = 100),
        )
    }

    @Test
    fun `日常少量删除不该被打扰`() {
        // 本地 100 条删 5 条 —— 用户在网页端顺手删几条的正常情形。
        assertFalse(
            EmptyVaultProtection.requiresDeleteConfirmation(toDeleteCount = 5, localCount = 100),
        )
    }

    @Test
    fun `一次删二十条以上需要确认`() {
        // 绝对量门槛（本地基数很大时占比那条腿不会响）。
        assertTrue(
            EmptyVaultProtection.requiresDeleteConfirmation(toDeleteCount = 20, localCount = 1000),
        )
    }

    @Test
    fun `删掉本地一半以上需要确认`() {
        assertTrue(
            EmptyVaultProtection.requiresDeleteConfirmation(toDeleteCount = 50, localCount = 100),
        )
    }

    @Test
    fun `小库也受保护 —— 绝对量达不到但占比很高`() {
        // 本地只剩 4 条而删 3 条：绝对量远不到 20，但显然可疑。
        // 这条用例的存在就是为了说明「只设绝对量门槛」是不够的。
        assertTrue(
            EmptyVaultProtection.requiresDeleteConfirmation(toDeleteCount = 3, localCount = 4),
        )
    }

    @Test
    fun `刚好一半即触发`() {
        assertTrue(
            EmptyVaultProtection.requiresDeleteConfirmation(toDeleteCount = 15, localCount = 30),
        )
    }

    @Test
    fun `略低于一半且未达绝对量则不触发`() {
        assertFalse(
            EmptyVaultProtection.requiresDeleteConfirmation(toDeleteCount = 14, localCount = 30),
        )
    }

    @Test
    fun `本地为空时不会误触发`() {
        assertFalse(
            EmptyVaultProtection.requiresDeleteConfirmation(toDeleteCount = 0, localCount = 0),
        )
    }
}
