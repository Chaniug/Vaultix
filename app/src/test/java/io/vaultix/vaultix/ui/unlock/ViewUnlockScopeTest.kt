/*
 * Vaultix — app:ui.unlock（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.unlock

import androidx.lifecycle.SavedStateHandle
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.domain.RoomResealRepository
import io.vaultix.domain.UnlockRecoveryRepository
import io.vaultix.domain.VaultRepository
import io.vaultix.domain.VaultSessionRepository
import io.vaultix.model.VaultKind
import io.vaultix.model.VaultSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * 查看层锁认证成功后的**清除粒度**（2026-10-31，用户报「进入 App 要验两次指纹」）。
 *
 * ## 用户原话
 *
 * > 「进入 app 时候验证指纹怎么需要两次，两个密码库？两个密码库不应该是同一个指纹吗，
 * > 指纹检验一次就够了啊」
 *
 * ## 真因
 *
 * 熄屏时 `AutoViewGate` 会给**每个**已解锁的「从不」库各打一个 `automatic = true`
 * 的查看锁标记（按库存的）。认证成功后原来只调 `clearViewLock(vaultId)` ——
 * **只清了当前库**。而 `RootNavViewModel` 的判据是「**任一库**带查看锁 ⇒ 收回解锁页」，
 * 于是切到第二个库时又被要求验一次。
 *
 * ## 为什么这是 bug 而不是设计
 *
 * 指纹门锁在房子化之后是**全局**的（`HouseKeyStore`，只有一把）；
 * "验证过是本人"这件事与"哪个库"毫无关系。分区标记只是 UI 的存储方式，
 * 不该被升格成"每个库各验一次"的语义。Bitwarden 里锁定态本身就是账号级的，
 * 不存在这个错位。
 *
 * ## 本文件守的两条（方向相反，缺一不可）
 *
 * | 断言 | 守的是 |
 * |---|---|
 * | 自动门禁 → 调 `clearAutoViewLocks()` 且**不**调 `clearViewLock(单库)` | 一次认证覆盖全部库 |
 * | 手动查看锁不在 `clearAutoViewLocks` 的清除范围内 | 用户对单库的显式锁定不能被顺手撤销 |
 *
 * 第二条由 `VaultSessionManagerTest` 钉在数据层（那里能看到两个集合的真实关系），
 * 本文件钉的是"UI 到底调了哪个方法" —— 只测数据层的话，UI 改回 `clearViewLock`
 * 依然会全绿。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ViewUnlockScopeTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val vaultId = "vault-1"

    private val vault = VaultSummary(
        id = vaultId,
        kind = VaultKind.BITWARDEN,
        name = "示例库",
        account = null,
        origin = "https://vault.example.com",
        unlocked = true,
    )

    /** 造一个「已解锁但被查看锁挡住」的解锁页 VM（真锁字段一律不参与本文件）。 */
    private fun TestScope.viewLockedViewModel(
        sessions: VaultSessionRepository,
        /**
         * 查看锁**是不是自动门禁打上的**（决定 `viewLockAuto` 这一层分支）。
         *
         * ⚠️ 默认 `true`：本文件的主体（2026-10-31 那两条）针对的正是自动门禁场景。
         * #245 的"手动锁"用例显式传 `false`。
         */
        auto: Boolean = true,
    ): UnlockViewModel {
        val repository = mockk<VaultRepository>(relaxed = true)
        every { repository.observeVaults() } returns flowOf(listOf(vault))
        every { repository.fingerprintQuickUnlockAvailable(any()) } returns flowOf(true)
        every { repository.pinLockAvailable() } returns flowOf(false)
        coEvery { repository.prepareFingerprintUnlock() } returns
            mockk<javax.crypto.Cipher>(relaxed = true)
        coEvery { repository.completeFingerprintUnlock(any()) } returns true

        // 当前库处于查看锁：构造期会同步读到（`_state` 的初值就靠这一句）。
        every { sessions.observeViewLockedVaultIds() } returns flowOf(setOf(vaultId))
        every { sessions.isViewLocked(any()) } returns true
        every { sessions.isAutoViewLocked(any()) } returns auto
        coEvery { sessions.clearAutoViewLocks() } returns Unit

        val prefs = mockk<VaultixPreferences>(relaxed = true)
        every { prefs.quickUnlockScope() } returns flowOf(emptySet())

        // ★ #220：接缝必须作为**构造参数**传入（构造后赋值赶不上 `init`）。
        val seams = UnlockViewModelSeams().apply {
            scope = this@viewLockedViewModel
            io = StandardTestDispatcher(testScheduler)
            ioContextOverride = StandardTestDispatcher(testScheduler)
        }
        return UnlockViewModel(
            SavedStateHandle(mapOf(UnlockViewModel.ARG_VAULT_ID to vaultId)),
            repository,
            sessions,
            mockk<UnlockRecoveryRepository>(relaxed = true),
            mockk<RoomResealRepository>(relaxed = true),
            prefs,
            textContext(),
            seams,
        ).also { it.startObservers() }
    }

    /**
     * 只给 ViewModel 解析文案用的假 Context。
     *
     * 本文件的所有路径都**不该**走到文案解析（认证是成功的），所以一个返回占位串的
     * relaxed mock 就够。刻意**不**写 `getString(any(), *anyVararg())` 那条重载：
     * 它在 mockk 新版里已经不是顶层函数，为一条走不到的路径引一个不确定的符号
     * 只会换来一次编译失败（`StaleRoomResealTest` 那套写法依赖老版本行为）。
     */
    private fun textContext(): android.content.Context {
        val ctx = mockk<android.content.Context>(relaxed = true)
        every { ctx.getString(any()) } returns "stub"
        return ctx
    }

    @Test
    fun `查看锁认证成功_清所有库的自动门禁_而不是只清当前库`() = runTest {
        val sessions = mockk<VaultSessionRepository>(relaxed = true)
        val vm = viewLockedViewModel(sessions)

        vm.completeLocalUnlock(
            cipher = mockk<javax.crypto.Cipher>(relaxed = true),
            forViewLock = true,
        )
        advanceUntilIdle()

        // ★ 这条就是用户报的那个 bug 的回归门禁：改成只清当前库 ⇒ 切库再验一次。
        coVerify(exactly = 1) { sessions.clearAutoViewLocks() }
        // 反向：只清当前库的写法必须**不再出现** —— 它正是"验两次"的成因。
        coVerify(exactly = 0) { sessions.clearViewLock(any()) }
        // 也不许走"全清"：那会把手动查看锁一起抹掉（安全语义倒退）。
        coVerify(exactly = 0) { sessions.clearAllViewLocks() }
    }

    @Test
    fun `查看锁认证成功_不碰密钥`() = runTest {
        // 查看锁的语义是"只挡界面"。若这里顺手调了真锁或解封，就等于把"从不"档
        // 偷偷改成"锁库"—— 自动填充会跟着失效，违反用户「填充不受影响」。
        val sessions = mockk<VaultSessionRepository>(relaxed = true)
        val vm = viewLockedViewModel(sessions)

        vm.completeLocalUnlock(
            cipher = mockk<javax.crypto.Cipher>(relaxed = true),
            forViewLock = true,
        )
        advanceUntilIdle()

        coVerify(exactly = 0) { sessions.viewLock(any(), any()) }
    }

    // ------------------------------------------------------------ #245（2026-11-04）

    /**
     * 用户取消**自动**门禁的弹窗 ⇒ 标记消失、回到库列表（不再被钉在认证页）。
     *
     * ⚠️ 为什么这条不是"放松安全"：自动门禁只管**界面**，密钥从没被它保护过；
     * 用户取消是一次合法选择（去拿眼镜 / 手湿）。把他钉在"只有生物识别按钮"的
     * 页面上，等于逼他为一次"不想验证"付一次验证的代价 —— 而真锁（密钥已清）
     * 走的是另一条路径，完全不受影响。
     */
    @Test
    fun `自动门禁被用户取消_清标记并放行_而不是钉在认证页`() = runTest {
        val sessions = mockk<VaultSessionRepository>(relaxed = true)
        val vm = viewLockedViewModel(sessions)
        advanceUntilIdle()
        org.junit.Assert.assertTrue("前置：构造后应识别为自动门禁", vm.state.value.viewLockAuto)

        vm.onBiometricPromptError(message = "用户取消", cancelled = true, systemAbort = false)
        advanceUntilIdle()

        coVerify(exactly = 1) { sessions.clearAutoViewLocks() }
        // 不许顺手撤销"手动查看锁"（那是用户对单库的显式指令）。
        coVerify(exactly = 0) { sessions.clearViewLock(any()) }
    }

    /**
     * **手动**查看锁被取消 ⇒ 保持锁定（标记不许自己消失）。
     *
     * 与上一条方向相反：手动锁是用户刚按下的显式指令，系统弹窗被他取消
     * 不等于"我要解锁"，把它悄悄清掉就是**谎报状态**（页面显示已解锁、实际他刚锁上）。
     */
    @Test
    fun `手动查看锁被用户取消_保持锁定`() = runTest {
        val sessions = mockk<VaultSessionRepository>(relaxed = true)
        // 手动锁：标记在，但不属于自动门禁子集。
        val vm = viewLockedViewModel(sessions, auto = false)
        advanceUntilIdle()
        org.junit.Assert.assertFalse("前置：手动锁不该被当成自动门禁", vm.state.value.viewLockAuto)

        vm.onBiometricPromptError(message = "用户取消", cancelled = true, systemAbort = false)
        advanceUntilIdle()

        coVerify(exactly = 0) { sessions.clearAutoViewLocks() }
    }

    /**
     * 系统侧终止（压根没弹成）**不算**用户的选择 ⇒ 自动门禁标记必须留着。
     *
     * 否则冷启动首帧那次"弹窗被系统以 ERROR_CANCELED 结束"会顺手把门禁解除 ——
     * 用户什么都没做，却绕过了"回来确认一次身份"。
     */
    @Test
    fun `系统侧终止不算用户取消_自动门禁保持`() = runTest {
        val sessions = mockk<VaultSessionRepository>(relaxed = true)
        val vm = viewLockedViewModel(sessions)
        advanceUntilIdle()

        vm.onBiometricPromptError(message = "硬件不可用", cancelled = true, systemAbort = true)
        advanceUntilIdle()

        coVerify(exactly = 0) { sessions.clearAutoViewLocks() }
    }
}
