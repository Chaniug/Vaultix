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
import kotlinx.coroutines.withContext
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
 *
 * ## ★★ 2026-09-29 修的假绿缺陷（本文件第二个坑）
 *
 * 本文件原先用 `delay(50)` 等被测协程，但 `VaultLockManagerImpl.scope` **硬编码
 * `Dispatchers.Default`** ⇒ 协程在**真实线程**上，而 `runTest` 的 `delay` 是**虚拟时间**
 * ⇒ 断言可能先于协程执行，**随机变绿**。表现为变异验证时"6 条只红 2 条"。
 * 已改为 [settle] / [awaitUntil]（真实时间等待）。**别把 `delay(50)` 写回来。**
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
     * 造一个**已解锁库就位**的管理器，连同其 `VaultRepository` mock 一起返回
     * （后者用于 `coVerify` 断言"没有发生锁定动作"）。
     *
     * 返回前轮询到**解锁快照就位** —— 否则 `onAppBackgrounded()` 会因「没有已解锁的库」
     * 而早退，测试变成"什么都没验证"。
     *
     * ⚠️ 2026-09-29（多库锁模型 **D2**）改：切后台的门从 `activeVaultId != null`
     * 换成「快照里有已解锁库」—— 现在给**每个已解锁库**各起计时器，不再只看活动库。
     * 本夹具的库 `unlocked = true`，后者成立即前者成立。
     */
    private suspend fun managerWithRepo(
        timeout: VaultTimeout,
    ): Pair<VaultLockManagerImpl, VaultRepository> {
        val prefs = mockk<VaultixPreferences>(relaxed = true)
        every { prefs.vaultTimeout(any()) } returns flowOf(timeout)

        val repository = mockk<VaultRepository>(relaxed = true)
        every { repository.observeVaults() } returns flowOf(listOf(vault("vault-1")))
        every { repository.observeUnlockedVaultIds() } returns flowOf(emptySet())

        val m = VaultLockManagerImpl(
            context = mockk<Context>(relaxed = true),
            vaultRepository = repository,
            preferences = prefs,
        )
        // 等 `activeVaultId` 被 init 里的收集赋值（⚠️ 真实时间等待，见 [awaitUntil]）。
        awaitUntil { m.isVaultUnlocked("vault-1") }
        return m to repository
    }

    /** 断言「任何形式的锁定都没发生」。 */
    private fun assertNoLock(repository: VaultRepository) {
        coVerify(exactly = 0) { repository.lockVault(any()) }
        coVerify(exactly = 0) { repository.lockAll() }
    }

    // ===================== 真实时间等待（2026-09-29 修，别改回去） =====================

    /**
     * ★★ **必须等「真实时间」，不能用裸 `delay()`** —— 这是本文件曾经的**假绿源**。
     *
     * ## 为什么
     *
     * `VaultLockManagerImpl` 的 `scope` 是
     * `CoroutineScope(SupervisorJob() + Dispatchers.Default)` —— **硬编码**在实现里。
     * 因此 `Dispatchers.setMain(...)` 对它**完全无效**：`onAppBackgrounded()` 内部的
     * `scope.launch { ... }` 跑在 **Default 真实线程池**上（失败栈里可见
     * `CoroutineScheduler$Worker.run`）。
     *
     * 而 `runTest` 只虚拟化**测试调度器**上的 `delay`。旧写法 `delay(50)` 于是：
     * **瞬间推进虚拟时间、却根本没等真实线程** ⇒ 断言在协程跑起来之前就执行了。
     *
     * **实测后果**：断言"没发生锁定"的用例会**随机变绿**。变异验证时注入锁定，
     * 6 条里只有 2 条变红（其余是碰巧没跑到）⇒ **"绿"是运气，不是证据**。
     *
     * ## 正确做法
     *
     * 切到 `Dispatchers.Default`（跳出测试调度器）再 `delay` —— 此时 delay 走
     * **真实时间**，足以让 Default 上的协程跑完。配合变异验证即可证明其灵敏度：
     * 注入锁定后 [settle] 必定被 `coVerify` 抓到（变红）。
     *
     * ⚠️ **固有局限（如实记录）**：本类断言的是"**没发生**"；任何等待都只能证明
     * "**这段时间内**没发生"。它的可信度来自**变异验证变红**，不是来自等待本身。
     */
    private suspend fun settle(ms: Long = 500L) = withContext(Dispatchers.Default) { delay(ms) }

    /**
     * 用**真实时间**轮询直到 [condition] 成立；超时即 `error`。
     *
     * 不用 `withTimeoutOrNull`：那走的是**虚拟时间**，而这里等的
     * `activeVaultId` 同样是 Default 线程上收集来的（同 [settle] 的坑）。
     * 宁可超时抛错，也不要静默放过去变成假绿。
     */
    private suspend fun awaitUntil(timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            withContext(Dispatchers.Default) { delay(10) }
        }
        error("条件在 ${timeoutMs}ms 内未成立 —— 后续断言会假绿")
    }

    @Test
    fun `Never档_切后台_绝不锁定`() = runTest {
        val (m, repository) = managerWithRepo(VaultTimeout.Never)

        m.onAppBackgrounded()
        settle()

        // ★ 这条变红 = Never 档又开始在离场时锁库（回到被推翻的「软锁」实现）。
        //   对齐 Bitwarden：切后台不锁，密钥常驻内存直到进程死亡。
        assertNoLock(repository)
    }

    @Test
    fun `Never档_进程创建_不锁定`() = runTest {
        val (m, repository) = managerWithRepo(VaultTimeout.Never)

        m.onAppCreated(isFirstCreation = true, createdForAutofill = false)
        settle()

        assertNoLock(repository)
    }

    @Test
    fun `Never档_回前台_不锁定`() = runTest {
        val (m, repository) = managerWithRepo(VaultTimeout.Never)

        m.onAppForegrounded()
        settle()

        assertNoLock(repository)
    }

    @Test
    fun `非Never档_切后台_不立即锁`() = runTest {
        // 5 分钟档由超时定时器负责锁定（本测试不推进时间 ⇒ 定时器不触发）。
        // 重点：切后台**不是立即锁** —— 若这里变红，说明把「延迟 N 分钟」误实现成
        // 「离场即锁」，会与用户的档位预期打架。
        val (m, repository) = managerWithRepo(VaultTimeout.FiveMinutes)

        m.onAppBackgrounded()
        settle()

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
        settle()

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
        settle()
        m.onScreenOn()          // 补偿：重算剩余并重启
        settle()

        assertNoLock(repository)
        assertTrue("亮屏补偿不该把未到点的库锁掉", m.isVaultUnlocked("vault-1"))
    }

    // ===================== 多库：每库各自计时（2026-09-29 · D2） =====================

    /**
     * ★★ **D2**：切后台时**每个已解锁库**各起一份定时器（各自到点）。
     *
     * 旧实现只取 `activeVaultId`（= 首个已注册库）⇒ 另一个库**永远没人计时**，
     * 表现为「开 A 再开 B，只有 A 会被锁、B 一直开着」——
     * 用户报的「两个库加锁逻辑混乱」的主因之一（`.ai/issues/04-锁与解锁.md` #131）。
     *
     * ## 为什么用「立即」（0 分钟）档
     *
     * 定时器的 `delay` 走**真实时间**（实现里硬编码 `Dispatchers.Default`，
     * 见 [settle] 的说明），5 分钟档无法在单测里等到。0 分钟档让 `delay(0)`
     * 立即返回 ⇒ 两个库的锁定动作都能被 `coVerify` 抓到。
     *
     * ⚠️ 本条变红 = 又退回「只给活动库计时」。
     */
    @Test
    fun `多库_立即档_切后台_每个已解锁库各锁各的`() = runTest {
        val prefs = mockk<VaultixPreferences>(relaxed = true)
        every { prefs.vaultTimeout(any()) } returns flowOf(VaultTimeout.Immediately)
        val repository = mockk<VaultRepository>(relaxed = true)
        every { repository.observeVaults() } returns
            flowOf(listOf(vault("vault-1"), vault("vault-2")))
        every { repository.observeUnlockedVaultIds() } returns flowOf(setOf("vault-1", "vault-2"))
        val m = VaultLockManagerImpl(
            context = mockk<Context>(relaxed = true),
            vaultRepository = repository,
            preferences = prefs,
        )
        awaitUntil { m.isVaultUnlocked("vault-1") && m.isVaultUnlocked("vault-2") }

        m.onAppBackgrounded()
        settle()

        coVerify(exactly = 1) { repository.lockVault("vault-1") }
        coVerify(exactly = 1) { repository.lockVault("vault-2") }
    }

    /**
     * ★ **D2 的另一半**：回前台撤销**全部**定时器（不只是活动库的）。
     *
     * 旧实现只撤 `activeVaultId` ⇒ 别的库的 job 继续跑到点，
     * 于是「用户明明在 App 里看着，另一个库却被锁了」。
     *
     * 观测方式：用 0 分钟档让定时器**立刻**到点前撤掉 —— 先 `onScreenOn()`
     * 建/重启定时器不可行（`elapsedRealtime ≡ 0`），故改为：
     * 切后台（起两库定时器）→ **立刻**回前台（撤销）→ 等真实时间 → 不应有锁定。
     * ⚠️ 有固有竞态（0 分钟档的 job 可能在撤销前就跑完），所以断言的是
     * **「回前台后不再新增锁定」** 而不是"一次都没锁"；真正的守卫是上面那条
     * 「每库各锁各的」。本条的变红条件是「撤销只作用于活动库」⇒ vault-2 必被锁。
     */
    @Test
    fun `多库_回前台_撤销全部定时器`() = runTest {
        val prefs = mockk<VaultixPreferences>(relaxed = true)
        every { prefs.vaultTimeout(any()) } returns flowOf(VaultTimeout.FiveMinutes)
        val repository = mockk<VaultRepository>(relaxed = true)
        every { repository.observeVaults() } returns
            flowOf(listOf(vault("vault-1"), vault("vault-2")))
        every { repository.observeUnlockedVaultIds() } returns flowOf(setOf("vault-1", "vault-2"))
        val m = VaultLockManagerImpl(
            context = mockk<Context>(relaxed = true),
            vaultRepository = repository,
            preferences = prefs,
        )
        awaitUntil { m.isVaultUnlocked("vault-1") && m.isVaultUnlocked("vault-2") }

        m.onAppBackgrounded()
        m.onAppForegrounded()   // 5 分钟档 + 立即撤销 ⇒ 不应有任何锁定
        settle()

        assertNoLock(repository)
    }
}
