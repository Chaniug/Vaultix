/*
 * Vaultix — app:security（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.security

import android.content.Context
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.vaultix.datastore.VaultTimeout
import io.vaultix.datastore.VaultTimeoutPreferences
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.domain.VaultRepository
import io.vaultix.model.VaultKind
import io.vaultix.model.VaultSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `VaultTimeout.OnSystemLock` 的锁定时机（2026-10-30）。
 *
 * ## 为什么单独一个文件，而不是塞进 `VaultLockManagerImplNeverTest`
 *
 * 那个类名与 KDoc 都在讲「`Never` 档任何触发都不锁」，是那一轮的**门禁**。
 * 新档位的判据与它**方向相反**（有一件事必须锁），混进去会让"这条用例在守什么"
 * 变得要靠读实现才知道。同一套虚拟时钟写法照抄，理由见那个文件顶部。
 *
 * ## 判据表（本文件守的就是这张表）
 *
 * | 触发 | `OnSystemLock` 档 | 其它档 |
 * |---|---|---|
 * | `onAppBackgrounded()` | **不锁**（本档位不是"立即"） | 起定时器 |
 * | `notifySystemLocked()` | **锁** | **不锁**（只有本档位认这个原因） |
 * | `onAppCreated()` | **不锁** | 首次创建立即锁 |
 *
 * ## ★ 第一条用例是**回归门禁**，不是凑数
 *
 * `OnSystemLock.vaultTimeoutInMinutes = -3`（`-1`/`-2` 已被 `OnAppRestart`/`Never` 占住）。
 * 而 `checkForVaultTimeoutInternal` 的 `else` 分支会算
 * `minutes.coerceAtLeast(0) * MS_PER_MINUTE` —— 负数被夹成 **0 ⇒ 立即锁**。
 * 也就是说：**只要有人删掉那个显式分支**，`OnSystemLock` 就会退化成
 * "切一次后台就锁"，而且编译、detekt、其它单测**全都不会红**。
 * `系统锁屏档_切后台_不锁` 是唯一能挡住它的东西。
 *
 * ## ⚠️ 本文件**不覆盖**的部分（如实记录，别以为测过了）
 *
 * 「设备是否真的上锁」那一层（`isDeviceLocked()` 读 `KeyguardManager`）**没有单测**：
 * JVM 里拿不到真的 `KeyguardManager`，`mockk` 一个 `Context` 只会得到 `null`
 * ⇒ 只能验证"拿到 `false` 时不锁"。真正的判定必须真机验：
 * 该档位下锁屏 → 回来要指纹；只熄屏不锁（无密码设备）→ 不锁。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultLockManagerImplSystemLockTest {

    private fun vault(id: String) = VaultSummary(
        id = id,
        kind = VaultKind.BITWARDEN,
        name = "库 $id",
        account = null,
        origin = "https://vault.example.com",
        unlocked = true,
    )

    private fun relaxedPreferences(): VaultixPreferences {
        val prefs = mockk<VaultixPreferences>(relaxed = true)
        // 显式 stub：`OnAppRestart` 那条路径会读它，不依赖 relaxed mock 的巧合默认值。
        every { prefs.devRelaxScreenLock } returns flowOf(false)
        return prefs
    }

    /**
     * 造一个已解锁库就位的管理器。
     *
     * @param timeouts 库 id → 档位；用于验证「每库档位独立」在锁屏这条路径下同样成立。
     */
    private fun TestScope.manager(
        timeouts: Map<String, VaultTimeout>,
    ): Pair<VaultLockManagerImpl, VaultRepository> {
        val prefs = mockk<VaultTimeoutPreferences>(relaxed = true)
        // ⚠️ 注册顺序**不能反**：mockk 的多条 stub 是"后注册者优先匹配"，
        //    若先写具体值、再写 `any()`，那条 `any()` 会把所有调用都抢走 ——
        //    表现为"每库档位独立"的用例里两个库读到同一个档位（而且不会报错）。
        every { prefs.vaultTimeout(any()) } returns flowOf(VaultTimeout.FiveMinutes)
        timeouts.forEach { (vaultId, timeout) ->
            every { prefs.vaultTimeout(vaultId) } returns flowOf(timeout)
        }

        val repository = mockk<VaultRepository>(relaxed = true)
        val ids = timeouts.keys.toList()
        every { repository.observeVaults() } returns flowOf(ids.map(::vault))
        every { repository.observeUnlockedVaultIds() } returns flowOf(ids.toSet())

        val m = VaultLockManagerImpl(
            context = mockk<Context>(relaxed = true),
            vaultRepository = repository,
            vaultTimeoutPrefs = prefs,
            preferences = relaxedPreferences(),
            clipboard = mockk(relaxed = true),
            sensitiveCache = mockk(relaxed = true),
            // 2026-10-30：「从不」档界面门禁（熄屏 / 冷启动后打 viewLock）。
            // 本文件断言的是"有没有真锁"，门禁是另一层，relaxed mock 足够。
            autoViewGate = mockk(relaxed = true),
        )
        // 先注入虚拟调度器，再启动观察 —— 顺序颠倒的话收集协程会永久跑在
        // Dispatchers.Default 上，虚拟时钟推不动它（详见 NeverTest 的踩坑记录）。
        m.testingIoDispatcher = StandardTestDispatcher(testScheduler)
        m.startObservingVaults()
        runCurrent()
        return m to repository
    }

    /** 单库 + 单档位的简写（绝大多数用例只需要这个）。 */
    private fun TestScope.manager(timeout: VaultTimeout): Pair<VaultLockManagerImpl, VaultRepository> =
        manager(mapOf("vault-1" to timeout))

    /**
     * 反复 `runCurrent()` 直到定时器数量稳定。
     *
     * 与 `NeverTest` 同款：不能手数"几跳"，跳数会随实现变动。
     */
    private fun TestScope.settle(m: VaultLockManagerImpl) {
        var previous = -2
        repeat(MAX_SETTLE_ROUNDS) { round ->
            runCurrent()
            val current = m.timerCountForTest()
            if (round + 1 >= MIN_SETTLE_ROUNDS && current == previous) return
            previous = current
        }
        error("定时器数量在 $MAX_SETTLE_ROUNDS 轮内没有稳定")
    }

    private fun assertLockedOnce(repository: VaultRepository, vaultId: String) {
        coVerify(exactly = 1) { repository.lockVault(vaultId) }
    }

    private fun assertNoLock(repository: VaultRepository) {
        coVerify(exactly = 0) { repository.lockVault(any()) }
        coVerify(exactly = 0) { repository.lockAll() }
    }

    // ---- ★ 回归门禁：负数分钟数不能被当成"0 分钟 = 立即锁" ----

    @Test
    fun `系统锁屏档_切后台_不锁`() = runTest {
        val (m, repository) = manager(VaultTimeout.OnSystemLock)

        m.onAppBackgrounded()
        settle(m)
        // 推满 1 小时：本档位没有"时长"概念，任何时刻都不该因为离开 App 而锁。
        advanceTimeBy(60 * 60 * 1000L)
        advanceUntilIdle()

        // ★ 这条红 = 有人删了 `when (timeout)` 里的 `OnSystemLock ->` 分支，
        //   于是 -3 落进 else 被 coerceAtLeast(0) 夹成 0 分钟 ⇒ 切后台立即锁。
        assertNoLock(repository)
    }

    @Test
    fun `系统锁屏档_收到锁屏通知_锁`() = runTest {
        val (m, repository) = manager(VaultTimeout.OnSystemLock)

        m.notifySystemLocked()
        runCurrent()
        advanceUntilIdle()

        assertLockedOnce(repository, "vault-1")
    }

    @Test
    fun `系统锁屏档_进程创建_不锁`() = runTest {
        // 进程重建时库本来就是锁的（密钥只在内存里），再"锁"一次只是噪音；
        // 更重要的是别在冷启动时多做一次无意义的清理。
        val (m, repository) = manager(VaultTimeout.OnSystemLock)

        m.onAppCreated(isFirstCreation = true, createdForAutofill = false)
        runCurrent()
        advanceUntilIdle()

        assertNoLock(repository)
    }

    @Test
    fun `系统锁屏档_回前台_不锁且不残留定时器`() = runTest {
        val (m, repository) = manager(VaultTimeout.OnSystemLock)

        m.onAppForegrounded()
        settle(m)
        advanceTimeBy(60 * 60 * 1000L)
        advanceUntilIdle()

        assertNoLock(repository)
        // 本档位从不建定时器；若这里非 0，说明它误进了 else 的按分钟分支。
        assertTrue("OnSystemLock 不该挂任何定时器", m.timerCountForTest() == 0)
    }

    // ---- 新原因不得影响其它档位 ----

    @Test
    fun `五分钟档_收到锁屏通知_不锁`() = runTest {
        // `SystemLocked` 是新增的 CheckTimeoutReason；若它被写进 else 的
        // "AppBackgrounded/UserChanged 起定时器"那一组，这里就会挂上定时器。
        val (m, repository) = manager(VaultTimeout.FiveMinutes)

        m.notifySystemLocked()
        settle(m)
        advanceUntilIdle()

        assertNoLock(repository)
        assertTrue("五分钟档收到锁屏通知不该起定时器", m.timerCountForTest() == 0)
    }

    @Test
    fun `从不档_收到锁屏通知_不锁`() = runTest {
        val (m, repository) = manager(VaultTimeout.Never)

        m.notifySystemLocked()
        runCurrent()
        advanceTimeBy(60 * 60 * 1000L)
        advanceUntilIdle()

        assertNoLock(repository)
    }

    @Test
    fun `重启档_收到锁屏通知_不锁`() = runTest {
        // 两个档位相邻、语义不同：锁屏通知不该让「重启 App 时」提前触发。
        val (m, repository) = manager(VaultTimeout.OnAppRestart)

        m.notifySystemLocked()
        runCurrent()
        advanceUntilIdle()

        assertNoLock(repository)
    }

    // ---- 每库档位独立：锁屏也只锁该锁的那个 ----

    @Test
    fun `锁屏通知_只锁系统锁屏档的库_分钟档不锁`() = runTest {
        val (m, repository) = manager(
            mapOf(
                "vault-a" to VaultTimeout.OnSystemLock,
                "vault-b" to VaultTimeout.FiveMinutes,
            ),
        )

        m.notifySystemLocked()
        settle(m)
        advanceUntilIdle()

        assertLockedOnce(repository, "vault-a")
        coVerify(exactly = 0) { repository.lockVault("vault-b") }
    }

    private companion object {
        /** 与 `NeverTest` 同款：至少跑够几轮再按"计数稳定"收敛（补偿路径会 1→0→1）。 */
        const val MIN_SETTLE_ROUNDS = 3

        /** 收敛轮数上限：超过就是实现里新增了无界 launch 链，应当直接失败。 */
        const val MAX_SETTLE_ROUNDS = 12
    }
}
