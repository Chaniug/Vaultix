/*
 * Vaultix — app:ui · settings (test)
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.settings

import io.vaultix.model.VaultKind
import io.vaultix.model.VaultSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「快速解锁」**开关语义**的单测（批次 3 改写，2026-09-29）。
 *
 * ## 这批用例钉死的是**房子化后的开关语义**，不是旧的三态推导
 *
 * 旧模型下开关由「范围内 N 个库配好了几个」推导 ⇒ `On` / `Partial(n)` / `Off` 三态，
 * 用户**主动关掉**某能力后会被显示成"还差 n 个没配完"，从而以为自己没操作成功 ——
 * 那正是 issue #93「谎报状态的开关」的成因。
 *
 * 房子化后开关只对应**一把全局门锁**：开门锁 = 一次 wrap，要么成功要么不变
 * ⇒ **「部分完成」在结构上不存在**，`Partial` 随之删除（定稿 §5.1）。
 *
 * ⇒ 本文件现在钉两件事：
 * 1. **开关 = 门锁的存在性**（[lockState]，二值，与"库配好了几个"无关）；
 * 2. **范围语义**（[assemble]）：默认全勾、房间信封存在性单维度、每库 `ready` 是复合判定。
 *
 * ## 为什么测这两个**纯函数**而不是整个控制器
 *
 * 控制器内部硬编码 `Dispatchers.IO`（`withContext`），纯 JVM 下 `runTest`
 * **无法确定性推进**那次线程跳转 ⇒ 强写会得到间歇性红的测试（批次 2 遗留 #1）。
 * 把判定逻辑放到文件级纯函数上，就既能被测试直接钉住，又不必 mock
 * Repository / Preferences / Cleanup 一整套依赖。
 *
 * ⚠️ 这是**有代价的取舍**：本文件证明的是"判定逻辑正确"，
 * **不是**"整条编排流程正确"。后者仍要靠批次 5 的真机验收。
 */
class QuickUnlockControllerTest {

    private fun summary(id: String, kind: VaultKind = VaultKind.BITWARDEN) =
        VaultSummary(id = id, kind = kind, name = id, origin = "")

    private fun state(
        vaultIds: List<String> = listOf("a"),
        scopeIds: Set<String> = emptySet(),
        confirmed: Boolean = true,
        biometricLock: Boolean = false,
        pinLock: Boolean = false,
    ) = assemble(
        vaults = vaultIds.map(::summary),
        scopeIds = scopeIds,
        confirmed = confirmed,
        biometricLock = biometricLock,
        pinLock = pinLock,
    )

    // ---- 开关 = 门锁的存在性（二值）----

    @Test
    fun `门锁装着就是 On`() {
        assertEquals(QuickUnlockController.CapabilityState.On, lockState(lockExists = true))
    }

    @Test
    fun `门锁没装就是 Off`() {
        assertEquals(QuickUnlockController.CapabilityState.Off, lockState(lockExists = false))
    }

    @Test
    fun `开关与库配好了几个无关`() {
        // 门锁装着、但没有任何一个库建了房间信封 —— 旧模型会把它推导成 Off（或 Partial），
        // 新模型如实显示 On：门锁**确实**装上了（房子化后那是唯一的开关事实）。
        // 若哪天这里变回 Off，说明有人又把按库进度接了回来（#93 复发）。
        val ui = state(vaultIds = listOf("a", "b"), biometricLock = true)

        assertEquals(QuickUnlockController.CapabilityState.On, ui.biometric)
        assertTrue(ui.rows.none { it.roomReady })
    }

    // ---- 范围语义 ----

    @Test
    fun `从未确认过范围时所有库都在范围内`() {
        // 「默认全勾」的落点：confirmed = false ⇒ 一个存储值都没读，也全在范围内。
        val ui = state(vaultIds = listOf("a", "b"), confirmed = false)

        assertTrue(ui.rows.all { it.inScope })
    }

    @Test
    fun `已确认过范围时范围就是存储值`() {
        val ui = state(vaultIds = listOf("a", "b"), scopeIds = setOf("a"), confirmed = true)

        assertTrue(ui.rows.first { it.vaultId == "a" }.inScope)
        assertFalse(ui.rows.first { it.vaultId == "b" }.inScope)
    }

    @Test
    fun `空范围在已确认后如实表示一个都不要`() {
        // ⚠️ 与「从未确认」必须分开：空集已被"一个都不要"占用，
        // 借它表示"还没配过"会让用户主动全不勾之后被显示成全勾（「空有三态」那条纪律）。
        val ui = state(vaultIds = listOf("a", "b"), scopeIds = emptySet(), confirmed = true)

        assertTrue(ui.rows.none { it.inScope })
    }

    @Test
    fun `房间信封的存在性是单维度且与方式无关`() {
        // 房间信封是**共享**的（两把门锁包同一把房钥匙），所以它只跟 vaultId 有关，
        // 不跟"选了指纹还是 PIN"有关 —— 这是动作表重排的核心（批次 2）。
        val ui = state(
            vaultIds = listOf("a", "b"),
            scopeIds = setOf("a"),
            confirmed = true,
            biometricLock = true,
            pinLock = true,
        )

        assertTrue(ui.rows.first { it.vaultId == "a" }.roomReady)
        assertFalse(ui.rows.first { it.vaultId == "b" }.roomReady)
    }

    @Test
    fun `每库的 ready 是门锁在且房间在的复合判定`() {
        // 门锁装着、房间没建 ⇒ 这个库**现在打不开**，但开关本身是 On。
        // 两者回答的不是同一个问题，别混（混了就会用 ready 去推开关）。
        val withRoom = state(vaultIds = listOf("a"), scopeIds = setOf("a"), biometricLock = true)
        val withoutRoom = state(vaultIds = listOf("a"), scopeIds = emptySet(), biometricLock = true)

        assertTrue(withRoom.rows.single().biometricReady)
        assertTrue(withRoom.rows.single().pinReady == false) // PIN 门锁没装
        assertFalse(withoutRoom.rows.single().biometricReady)
        assertEquals(QuickUnlockController.CapabilityState.On, withoutRoom.biometric)
    }

    @Test
    fun `两种门锁各自独立推导`() {
        // 只装了指纹锁 ⇒ 指纹 On、PIN Off。若某天两者被合并成一个状态，这条会立刻红。
        val ui = state(vaultIds = listOf("a"), scopeIds = setOf("a"), biometricLock = true)

        assertEquals(QuickUnlockController.CapabilityState.On, ui.biometric)
        assertEquals(QuickUnlockController.CapabilityState.Off, ui.pin)
        assertTrue(ui.rows.single().biometricReady)
        assertFalse(ui.rows.single().pinReady)
    }

    // ---- 动作表：只开还没装的门锁（批次 2 动作表第 2 条）----

    @Test
    fun `两把锁都没装时两种方式都要开`() {
        val ui = state(biometricLock = false, pinLock = false)

        val locks = locksToOpen(
            setOf(
                QuickUnlockController.UnlockMethod.BIOMETRIC,
                QuickUnlockController.UnlockMethod.PIN,
            ),
            ui,
        )

        assertEquals(
            setOf(
                QuickUnlockController.UnlockMethod.BIOMETRIC,
                QuickUnlockController.UnlockMethod.PIN,
            ),
            locks,
        )
    }

    @Test
    fun `已装着的门锁不重开`() {
        // ★ 动作表第 2 条：用户再勾一次"指纹"不该再按一次指纹。
        // 若这里返回含 BIOMETRIC 的集合，用户每次进向导都会被要求重按一次指纹，
        // 第一反应是"锁坏了"。
        val ui = state(biometricLock = true, pinLock = false)

        val locks = locksToOpen(
            setOf(
                QuickUnlockController.UnlockMethod.BIOMETRIC,
                QuickUnlockController.UnlockMethod.PIN,
            ),
            ui,
        )

        assertEquals(setOf(QuickUnlockController.UnlockMethod.PIN), locks)
    }

    @Test
    fun `两把锁都已装时什么都不开`() {
        // 此时本次只是换了个勾选项 ⇒ 后面只做纯软件封装（不碰 Keystore、不弹认证）。
        val ui = state(biometricLock = true, pinLock = true)

        val locks = locksToOpen(
            setOf(
                QuickUnlockController.UnlockMethod.BIOMETRIC,
                QuickUnlockController.UnlockMethod.PIN,
            ),
            ui,
        )

        assertTrue(locks.isEmpty())
    }

    // ---- 顺序约束：封房间前的两道闸（定稿 §5）----

    @Test
    fun `一把门锁都没有时拒绝封房间并说明原因`() {
        val ui = state(biometricLock = false, pinLock = false)

        val blocker = roomSealingBlocker(
            pending = listOf("a"),
            locksToOpen = emptySet(),
            ui = ui,
            houseKeyReady = true,
        )

        // 非 null = 拦下。文案要点是"先开一种解锁方式"，不是含糊的"失败"。
        assertEquals(1, blocker?.failed?.size)
        assertTrue(blocker!!.failed.single().reason.contains("指纹解锁"))
    }

    @Test
    fun `门锁在但房钥匙不在内存时也要拦下`() {
        // ★ 顺序约束的**第二层**：房钥匙明文仅存内存（硬约束 #1）⇒ 进程重启即失
        // （Never 档的 auto 信封恢复发生在进程启动时，到向导这一步钥匙算已回来）；
        // 此时门锁信封还在也解不出钥匙，封了也是白封。
        val ui = state(biometricLock = true, pinLock = false)

        val blocked = roomSealingBlocker(
            pending = listOf("a"),
            locksToOpen = emptySet(),
            ui = ui,
            houseKeyReady = false,
        )
        val allowed = roomSealingBlocker(
            pending = listOf("a"),
            locksToOpen = emptySet(),
            ui = ui,
            houseKeyReady = true,
        )

        assertEquals(1, blocked?.failed?.size)
        assertTrue(blocked!!.failed.single().reason.contains("解锁一次"))
        assertEquals(null, allowed)
    }

    @Test
    fun `本次会开门锁时不拦（开门锁会把房钥匙带进内存）`() {
        // 首启流程 = 先包门锁、再建房间 ⇒ 两道闸随开门锁自动满足。
        val ui = state(biometricLock = false, pinLock = false)

        val blocker = roomSealingBlocker(
            pending = listOf("a"),
            locksToOpen = setOf(QuickUnlockController.UnlockMethod.BIOMETRIC),
            ui = ui,
            houseKeyReady = false,
        )

        assertEquals(null, blocker)
    }

    @Test
    fun `没有房间要封时不拦`() {
        // 用户可能只是想开一把锁，房间早就封好了 ⇒ 开门锁本身就是目的。
        val ui = state(biometricLock = false, pinLock = false)

        val blocker = roomSealingBlocker(
            pending = emptyList(),
            locksToOpen = emptySet(),
            ui = ui,
            houseKeyReady = false,
        )

        assertEquals(null, blocker)
    }

    @Test
    fun `库类型直接取自入参`() {
        // ⚠️ 走旁路缓存会退化成"所有库都被当成 Bitwarden"，
        // 表现为 KDBX 库登记时**不问主密码** ⇒ 静默地建不出信封。
        val ui = assemble(
            vaults = listOf(summary("k", VaultKind.KDBX), summary("b", VaultKind.BITWARDEN)),
            scopeIds = emptySet(),
            confirmed = false,
            biometricLock = false,
            pinLock = false,
        )

        assertTrue(ui.rows.first { it.vaultId == "k" }.needsMasterPassword)
        assertFalse(ui.rows.first { it.vaultId == "b" }.needsMasterPassword)
    }
}
