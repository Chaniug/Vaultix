/*
 * Vaultix — app:ui · settings (test)
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.settings

import io.vaultix.model.VaultKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「能力状态推导」的单测。
 *
 * ## 为什么只测这一个函数
 *
 * [QuickUnlockController] 的其余部分都要碰 Repository / Keystore / 协程，
 * 而**开关到底显示成什么**这个判断是纯逻辑，也是最容易写错、错了又最难看出来的地方 ——
 * 它正是 2026-09-16 能力级重构里"防漂移"的核心（见类 KDoc 的真源说明）。
 *
 * ## 这些用例锁住的是**语义**，不是实现
 *
 * 尤其 `范围内一个都没建 ⇒ Off`：这一条若写成 `Partial`，用户**主动关闭**某能力后
 * 会被界面告知"有 N 个库未完成"，从而以为自己没操作成功。
 * 这就是旧实现「谎报状态」（issue #93）的同一种病，必须钉死。
 */
class QuickUnlockControllerTest {

    /** 造一行库。默认：在范围内、两个能力都没建。 */
    private fun row(
        id: String,
        inScope: Boolean = true,
        biometricReady: Boolean = false,
        pinReady: Boolean = false,
    ) = QuickUnlockController.VaultUi(
        vaultId = id,
        name = id,
        kind = VaultKind.BITWARDEN,
        inScope = inScope,
        biometricReady = biometricReady,
        pinReady = pinReady,
    )

    @Test
    fun `范围为空时是 Off`() {
        val result = deriveCapabilityState(emptyList()) { it.biometricReady }

        assertEquals(QuickUnlockController.CapabilityState.Off, result)
    }

    @Test
    fun `范围内全部建好时是 On`() {
        val rows = listOf(
            row("a", biometricReady = true),
            row("b", biometricReady = true),
        )

        val result = deriveCapabilityState(rows) { it.biometricReady }

        assertEquals(QuickUnlockController.CapabilityState.On, result)
    }

    @Test
    fun `范围内一个都没建时是 Off 而不是 Partial`() {
        val rows = listOf(row("a"), row("b"))

        val result = deriveCapabilityState(rows) { it.biometricReady }

        assertEquals(QuickUnlockController.CapabilityState.Off, result)
    }

    @Test
    fun `部分建好时是 Partial 且待办数正确`() {
        val rows = listOf(
            row("a", biometricReady = true),
            row("b"),
            row("c"),
        )

        val result = deriveCapabilityState(rows) { it.biometricReady }

        assertEquals(QuickUnlockController.CapabilityState.Partial(pending = 2), result)
    }

    @Test
    fun `未勾选的库不计入推导`() {
        // a 在范围内且建好；b 未勾选、没建 —— 不能因为 b 而把状态拉成 Partial/Off。
        val rows = listOf(
            row("a", biometricReady = true),
            row("b", inScope = false),
        )

        val result = deriveCapabilityState(rows) { it.biometricReady }

        assertEquals(QuickUnlockController.CapabilityState.On, result)
    }

    @Test
    fun `未勾选的库不会让范围非空`() {
        val rows = listOf(row("a", inScope = false, biometricReady = true))

        val result = deriveCapabilityState(rows) { it.biometricReady }

        assertEquals(QuickUnlockController.CapabilityState.Off, result)
    }

    @Test
    fun `指纹与 PIN 各自独立推导`() {
        // 同一个库：指纹建好了、PIN 没建 ⇒ 两个能力的结论必须不同。
        // 若某天把两者合并成一个状态，这条会立刻红。
        val rows = listOf(row("a", biometricReady = true, pinReady = false))

        val biometric = deriveCapabilityState(rows) { it.biometricReady }
        val pin = deriveCapabilityState(rows) { it.pinReady }

        assertEquals(QuickUnlockController.CapabilityState.On, biometric)
        assertEquals(QuickUnlockController.CapabilityState.Off, pin)
    }
}
