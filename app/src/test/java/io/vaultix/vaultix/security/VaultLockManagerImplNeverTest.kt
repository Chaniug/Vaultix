package io.vaultix.vaultix.security

import android.content.Context
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.vaultix.datastore.VaultTimeout
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.domain.VaultRepository
import io.vaultix.model.VaultKind
import io.vaultix.model.VaultSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 「从不」档**不锁**的触发纪律（2026-09-29 二次定稿，用户三次拍板「对齐 Bitwarden」）。
 *
 * ## ⚠️ 本文件是**反转后的门禁**，读之前先看这段历史
 *
 * 本文件在 2026-09-29 早些时候的版本断言的是**相反**的判据
 * （「切后台**必须**软锁」）。那次判断已被推翻 —— 细节见
 * `VaultLockManagerImpl.checkForVaultTimeoutInternal` 的 KDoc「为什么推翻」。
 *
 * > 一句话：软锁换来的「后台期间密钥不在内存」是**有代价的伪安全**；
 * > 真正的边界是「进程是否活着」，而非「是否在前台」。Bitwarden 靠的正是
 * > Android 的进程内存回收，而不是任何「离场清密钥」的动作。
 *
 * ## 当前判据（本文件逐条钉死）
 *
 * | 触发 | Never 档应做什么 |
 * |---|---|
 * | `onAppBackgrounded()` | **不锁**（密钥常驻内存，对齐 Bitwarden `Never -> return`） |
 * | `onAppCreated()` | **不锁** |
 * | `onAppForegrounded()` | **不锁** |
 * | 非 Never 档切后台 | **不立即锁**（由超时定时器负责） |
 *
 * ## 变异验证（本文件建好后必做）
 *
 * 临时在 `checkForVaultTimeoutInternal` 的 `Never` 分支塞回任意锁定调用
 * （例如 `setVaultToLocked(vaultId)`），确认 `Never档_切后台_绝不锁定` **变红**。
 * 若不变红，说明测试**什么都没验证**。
 *
 * ⚠️ 由于 `Never` 分支现在是**纯 `return@launch`**，本测试的"假绿"风险主要来自
 * **早退**（`activeVaultId` 未就位时 `onAppBackgrounded` 直接返回 ⇒ 无论实现怎么写
 * 都不会锁）。所以 [managerWithRepo] 里「轮询到 `activeVaultId` 就位」是这条门禁
 * 成立的**前提**，不可省。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultLockManagerImplNeverTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vault(id: String) = VaultSummary(
        id = id,
        kind = VaultKind.BITWARDEN,
        name = "库 $id",
        account = null,
        origin = "https://vault.example.com",
        unlocked = true,
    )

    /**
     * 造一个**已定位到活动库**的管理器，连同其 `VaultRepository` mock 一起返回
     * （后者用于 `coVerify` 断言"没有发生锁定动作"）。
     *
     * 返回前轮询到 `activeVaultId` 就位 —— 否则 `onAppBackgrounded()` 会在
     * `activeVaultId ?: return` 处早退，测试变成"什么都没验证"。
     */
    private suspend fun managerWithRepo(
        timeout: VaultTimeout,
    ): Pair<VaultLockManagerImpl, VaultRepository> {
        val prefs = mockk<VaultixPreferences>(relaxed = true)
        every { prefs.vaultTimeout } returns flowOf(timeout)

        val repository = mockk<VaultRepository>(relaxed = true)
        every { repository.observeVaults() } returns flowOf(listOf(vault("vault-1")))
        every { repository.observeUnlockedVaultIds() } returns flowOf(emptySet())

        val m = VaultLockManagerImpl(
            context = mockk<Context>(relaxed = true),
            vaultRepository = repository,
            preferences = prefs,
        )
        // 等 `activeVaultId` 被 init 里的收集赋值。
        withTimeoutOrNull(5_000L) { while (!m.isVaultUnlocked("vault-1")) delay(1) }
            ?: error("activeVaultId 未就位 —— 后续断言会假绿")
        return m to repository
    }

    /** 断言「任何形式的锁定都没发生」。 */
    private fun assertNoLock(repository: VaultRepository) {
        coVerify(exactly = 0) { repository.lockVault(any()) }
        coVerify(exactly = 0) { repository.lockAll() }
    }

    @Test
    fun `Never档_切后台_绝不锁定`() = runTest {
        val (m, repository) = managerWithRepo(VaultTimeout.Never)

        m.onAppBackgrounded()
        delay(50)

        // ★ 这条变红 = Never 档又开始在离场时锁库（回到被推翻的「软锁」实现）。
        //   对齐 Bitwarden：切后台不锁，密钥常驻内存直到进程死亡。
        assertNoLock(repository)
    }

    @Test
    fun `Never档_进程创建_不锁定`() = runTest {
        val (m, repository) = managerWithRepo(VaultTimeout.Never)

        m.onAppCreated(isFirstCreation = true, createdForAutofill = false)
        delay(50)

        assertNoLock(repository)
    }

    @Test
    fun `Never档_回前台_不锁定`() = runTest {
        val (m, repository) = managerWithRepo(VaultTimeout.Never)

        m.onAppForegrounded()
        delay(50)

        assertNoLock(repository)
    }

    @Test
    fun `非Never档_切后台_不立即锁`() = runTest {
        // 5 分钟档由超时定时器负责锁定（本测试不推进时间 ⇒ 定时器不触发）。
        // 重点：切后台**不是立即锁** —— 若这里变红，说明把「延迟 N 分钟」误实现成
        // 「离场即锁」，会与用户的档位预期打架。
        val (m, repository) = managerWithRepo(VaultTimeout.FiveMinutes)

        m.onAppBackgrounded()
        delay(50)

        assertNoLock(repository)
        assertTrue("非 Never 档切后台后不该立刻变成锁定态", m.isVaultUnlocked("vault-1"))
    }

    // ===================== 亮屏补偿（2026-09-29 新增） =====================

    /**
     * ★ Never 档：亮屏补偿**绝不**造成锁定。
     *
     * 这是新增 `onScreenOn()` 后**最需要守住**的一条 —— 亮屏补偿会遍历
     * `timerJobMap` 并重启定时器；若实现不慎（例如无条件按档位重算），
     * Never 档就可能被"补偿"出一个定时器，从而在用户明确选择"从不"时锁库。
     *
     * 正确行为：Never 档**从未建过定时器** ⇒ `timerJobMap` 为空 ⇒ `onScreenOn()`
     * 在 `snapshot.isEmpty()` 处早退，不会有任何锁定动作。
     *
     * ⚠️ 本条变红 = 亮屏补偿破坏了 Never 档「绝不锁」的语义。
     */
    @Test
    fun `Never档_亮屏_不锁定`() = runTest {
        val (m, repository) = managerWithRepo(VaultTimeout.Never)

        // 先切后台（Never 档下这不建定时器），再模拟亮屏。
        m.onAppBackgrounded()
        m.onScreenOn()
        delay(50)

        assertNoLock(repository)
        assertTrue("Never 档亮屏后必须仍然解锁", m.isVaultUnlocked("vault-1"))
    }

    /**
     * ⚠️ 亮屏补偿的**已测边界与未测边界**（诚实记录，别把这里当全测）。
     *
     * 单测跑在 JVM 上，`unitTests.isReturnDefaultValues = true` ⇒
     * `SystemClock.elapsedRealtime()` **恒返回 0**。因此：
     *
     * - **已覆盖**：`timerJobMap` 为空时的早退（见上面「Never档_亮屏_不锁定」）；
     * - **未覆盖**：`elapsedRealtime` 真实推进后「剩余 = 总量 - 已走过」的**算术**，
     *   以及"剩余钳到 0 即立即锁"。
     *
     * 这两条只能靠**真机验证**：把档位设 1 分钟 → 熄屏 3 分钟 → 亮屏，
     * 库应**立即**处于锁定态（而非再等 1 分钟）。见 `Docs/progress/` 的验收清单。
     *
     * 之所以不为此引 Robolectric / 抽象 `RealtimeManager`：本项目当前没有
     * Robolectric 基建，为一个单点引入不成比例；而刻度语义（单调时钟）已在
     * [VaultLockManagerImpl.onScreenOn] 的 KDoc 里逐句对照 Bitwarden。
     */
    /**
     * ★ 非 Never 档：切后台建了定时器后，亮屏补偿**重启而非误触发**。
     *
     * 这是 `onScreenOn()` 的**核心路径**（不是早退路径）：`timerJobMap` 里有活定时器，
     * 补偿会读出它、算剩余、再重启。要守住的是：
     * - 补偿**不得**把"还剩 5 分钟"的定时器变成"立刻锁"；
     * - 也不得产生"新旧两个 job 同时到点 ⇒ 锁两次"的重复（靠 `cancelTimer` 保证）。
     *
     * ⚠️ 本测试在本机 JVM 下 `elapsedRealtime() ≡ 0`，所以算出的剩余 = 全量
     * （等价于"刚启动"），锁不会发生 —— 断言"没锁"成立。真机上的"已走过一半"
     * 算术见上面那条测试的 KDoc「未覆盖边界」。
     *
     * 本条变红 = 亮屏补偿把还没到点的库给锁了（用户会感觉"一锁屏再亮就要求重解锁"）。
     */
    @Test
    fun `非Never档_切后台后亮屏_不提前锁`() = runTest {
        val (m, repository) = managerWithRepo(VaultTimeout.FiveMinutes)

        m.onAppBackgrounded()   // 建 5 分钟定时器
        delay(50)
        m.onScreenOn()          // 补偿：重算剩余并重启
        delay(50)

        assertNoLock(repository)
        assertTrue("亮屏补偿不该把未到点的库锁掉", m.isVaultUnlocked("vault-1"))
    }
}
