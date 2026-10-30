/*
 * Vaultix — app:security（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.security

import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.vaultix.datastore.VaultTimeout
import io.vaultix.datastore.VaultTimeoutPreferences
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.domain.VaultRepository
import io.vaultix.domain.VaultSessionRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * 「从不」档界面门禁的**判定表**（2026-10-30）。
 *
 * ## 守的是什么
 *
 * 用户诉求：「开启了从不锁定……熄屏或者清除后台后，还是会需要指纹解锁；
 * 但是填充并不会受影响」。也就是**只打 viewLock 标记、绝不动密钥**。
 *
 * ## 四条前置条件（任何一条不满足都不该打标记）
 *
 * | 条件 | 不满足时的后果（这就是为什么要测） |
 * |---|---|
 * | 档位 = `Never` | 给 `N 分钟` / `OnSystemLock` 叠加门禁 ⇒ 破坏那些档位"切后台不打扰"的承诺 |
 * | `autoViewGateOnNever` = `true` | 用户明确关掉了，还去挡 ⇒ 开关形同虚设（#93 同族） |
 * | 指纹门锁可用 | **没有指纹时门禁 = 每次回来输主密码** —— 比不做更糟，用户会把整个功能当 bug |
 * | 开发者「保持不锁」关闭 | 开发时反复弹指纹 ⇒ 那个开关等于没写 |
 *
 * ## ⚠️ 本文件不覆盖的
 *
 * 真正的"熄屏广播 → 调用本类"接线在 `VaultLockManagerImpl.onScreenOff`，
 * 那里没有单测（`ACTION_SCREEN_OFF` 需要真机 / Robolectric）。
 * 本文件只钉**判定本身**。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoViewGateTest {

    /**
     * 造一个门禁实例。
     *
     * @param timeout 该库的档位（只放一个库，判据不涉及多库交互）。
     * @param gateOn 产品开关 `autoViewGateOnNever`。
     * @param devRelax 开发者「保持不锁」。
     * @param fingerprintReady 指纹门锁是否可用。
     */
    private fun gate(
        timeout: VaultTimeout = VaultTimeout.Never,
        gateOn: Boolean = true,
        devRelax: Boolean = false,
        fingerprintReady: Boolean = true,
        unlocked: Set<String> = setOf(VAULT_ID),
    ): Triple<AutoViewGate, VaultSessionRepository, VaultRepository> {
        val vaultRepository = mockk<VaultRepository>(relaxed = true)
        every { vaultRepository.observeUnlockedVaultIds() } returns flowOf(unlocked)
        every { vaultRepository.fingerprintLockAvailable() } returns flowOf(fingerprintReady)

        val timeoutPrefs = mockk<VaultTimeoutPreferences>(relaxed = true)
        every { timeoutPrefs.vaultTimeout(any()) } returns flowOf(timeout)

        val preferences = mockk<VaultixPreferences>(relaxed = true)
        every { preferences.autoViewGateOnNever } returns flowOf(gateOn)
        every { preferences.devRelaxScreenLock } returns flowOf(devRelax)

        val sessions = mockk<VaultSessionRepository>(relaxed = true)

        return Triple(
            AutoViewGate(
                vaultRepository = vaultRepository,
                sessionRepository = sessions,
                vaultTimeoutPrefs = timeoutPrefs,
                preferences = preferences,
            ),
            sessions,
            vaultRepository,
        )
    }

    @Test
    fun `从不档_开关开_指纹可用_打自动门禁`() = runTest {
        val (gate, sessions) = gate()

        gate.gateUnlockedVaults()

        // `automatic = true` 是**语义的一部分**，不是实现细节：
        // 自动填充靠它区分"该不该先认证"（用户诉求「填充不受影响」）。
        coVerify(exactly = 1) { sessions.viewLock(VAULT_ID, automatic = true) }
    }

    @Test
    fun `开关关掉_不打门禁`() = runTest {
        val (gate, sessions) = gate(gateOn = false)

        gate.gateUnlockedVaults()

        coVerify(exactly = 0) { sessions.viewLock(any(), any()) }
    }

    @Test
    fun `指纹不可用_不打门禁`() = runTest {
        // ★ 这条最容易写反：直觉是"更安全 ⇒ 还是挡上"。但查看锁的唯一解锁手段是
        //   生物识别（UnlockScreen 的查看锁分支只给指纹按钮），没有指纹时页面会
        //   降级成主密码表单 ⇒ 用户每次回到 App 都要输主密码。
        //   那不是在保护「从不」档，那是把它变成一个更烦的「立即」档。
        val (gate, sessions) = gate(fingerprintReady = false)

        gate.gateUnlockedVaults()

        coVerify(exactly = 0) { sessions.viewLock(any(), any()) }
    }

    @Test
    fun `非从不档_不打门禁`() = runTest {
        // 五分钟档有自己的真锁；`OnSystemLock` / `OnAppRestart` 的卖点就是"切后台不打扰"。
        // 给它们叠这一层会直接破坏所选档位的语义。
        val (gate, sessions) = gate(timeout = VaultTimeout.FiveMinutes)

        gate.gateUnlockedVaults()

        coVerify(exactly = 0) { sessions.viewLock(any(), any()) }
    }

    @Test
    fun `开发者保持不锁_不打门禁`() = runTest {
        // 开发者开关的语义就是"完全不打扰"。只关真锁、不关门禁 ⇒ 开发时照样弹指纹。
        val (gate, sessions) = gate(devRelax = true)

        gate.gateUnlockedVaults()

        coVerify(exactly = 0) { sessions.viewLock(any(), any()) }
    }

    @Test
    fun `没有已解锁库_不打门禁`() = runTest {
        // 冷启动时 `AutoRestoreTrigger` 会在恢复**之后**才调用本类；这里覆盖
        // "恢复失败 / 本来就没库"的分支 —— 不该对一个没密钥的库打标记
        //（`VaultSessionManager.viewLock` 自己也会 no-op，但少发一次调用更省）。
        val (gate, sessions) = gate(unlocked = emptySet())

        gate.gateUnlockedVaults()

        coVerify(exactly = 0) { sessions.viewLock(any(), any()) }
    }

    private companion object {
        const val VAULT_ID = "vault-1"
    }
}
