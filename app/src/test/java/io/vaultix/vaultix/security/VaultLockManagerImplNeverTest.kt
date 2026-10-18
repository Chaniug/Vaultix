package io.vaultix.vaultix.security

import android.content.Context
import io.mockk.clearMocks
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.vaultix.datastore.VaultTimeout
import io.vaultix.datastore.VaultTimeoutPreferences
import io.vaultix.domain.VaultRepository
import io.vaultix.model.VaultKind
import io.vaultix.model.VaultSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定时机纪律（2026-09-29 二次定稿 → **2026-10-18 改成虚拟时钟**）。
 *
 * ## ⚠️ 本文件是**反转后的门禁**，读之前先看这段历史
 *
 * 本文件在 2026-09-29 早些时候的版本断言的是**相反**的判据
 * （「切后台**必须**软锁」）。那次判断已被推翻 —— 细节见
 * `VaultLockManagerImpl.checkForVaultTimeoutInternal` 的 KDoc「为什么推翻」。
 *
 * > 一句话：软锁换来的「后台期间密钥不在内存」是**有代价的伪安全**；
 * > 真正的边界是「进程是否活着」，而非「是否在前台」。
 *
 * ## 判据表
 *
 * | 触发 | Never 档 | 非 Never 档 |
 * |---|---|---|
 * | `onAppBackgrounded()` | **不锁** | **不立即锁**，起定时器，**到点才锁** |
 * | `onAppCreated()` | **不锁** | 首次创建立即锁 |
 * | `onAppForegrounded()` | **不锁** | **撤销**定时器（此后时间再走也不锁） |
 * | `onScreenOn()` | **不锁** | 按剩余时长重启定时器（不提前锁） |
 *
 * ## ★★ 2026-10-18：从「真实等待 500ms」改成**虚拟时钟**（#130 同族隐患收口）
 *
 * 改之前 `VaultLockManagerImpl.scope` 硬编码 `Dispatchers.Default`，于是：
 * - `runTest` 的虚拟时间**管不到**它 ⇒ 所有等待都得用
 *   `withContext(Dispatchers.Default) { delay(500) }` 走真实时间；
 * - 更贵的是**能力缺口**：「5 分钟档走满 5 分钟该锁」这条**核心语义根本测不了**
 *   （要真等 5 分钟），只能拿 0 分钟档（`Immediately`）绕；
 * - 而断言「没发生」的用例，其可信度只能来自**变异验证变红**，
 *   等待本身只能证明「这 500ms 内没发生」（#130 原话）。
 *
 * 现在实现里有 `testingIoDispatcher` 覆写口（生产恒 `null`），测试注入
 * `StandardTestDispatcher(testScheduler)` ⇒ 定时器的 `delay` 走**虚拟时间**，
 * 一步跳到到点瞬间。判定从「等到了/没等到」变成
 * 「时间推到 X，断言发生/不发生」—— **确定性，且能测到边界**。
 *
 * ⚠️ 别把 `settle()` / 真实 `delay` 写回来：那会让本文件重新变成"运气测试"，
 *    并把「到点才锁」这条重新变成不可测。
 *
 * ## ★★ 2026-10-18 同日复盘：虚拟时钟的三条规则（连踩四轮才定，别自己发明）
 *
 * ### 踩坑记录（都是真的，别再走一遍）
 *
 * | 尝试 | 结果 |
 * |---|---|
 * | 一律 `advanceTimeBy(N)` + `runCurrent()` | 单跑全绿，**全量跑偶发 1–2 条红**，红哪条还会变 |
 * | 一律 `advanceUntilIdle()` | **稳定 3 条红**：它会把定时器一路推到到点，"不该锁"的断言被迫变红 |
 * | 只用一次 `runCurrent()` 建定时器 | **时好时坏**：`runCurrent()` 只跑当前时刻已排队的任务，而这条路径是**三跳** |
 * | 自定义 `CoroutineDispatcher` 包装 | **`delay` 永远醒不过来**：
 *   `timer started` 出现 5 次、`timer fired` **0 次** ——
 *   虚拟时钟的恢复要求上下文里的 `CoroutineDispatcher` **就是** `TestDispatcher` 本身 |
 *
 * ### 规则（三条，缺一不可）
 *
 * | 目的 | 写法 |
 * |---|---|
 * | **建好定时器但不推时间** | `settle(m)` —— 反复 `runCurrent()` 直到**定时器计数稳定** |
 * | 断言"到点前**不**会锁" | `settle(m)` → `advanceTimeBy(N - 1)` → `runCurrent()`（**不能**再 `advanceUntilIdle()`，它会补齐剩下的 1ms） |
 * | 断言"**到点后会**锁" | `settle(m)` → `advanceTimeBy(N)` → `advanceUntilIdle()`（让锁定那一跳也跑完） |
 *
 * **不要手数"几跳"**：`onAppBackgrounded` 到定时器挂上有 2–3 跳（读档位 → 建定时器，
 * 补偿路径还多一次 cancel），跳数会随实现变动。`settle(m)` 用
 * [VaultLockManagerImpl.timerCountForTest] 当收敛信号 —— 判据是"计数稳定"，
 * 不是"跑了几轮"，所以实现加一跳它也不会坏。
 *
 * 唯一例外：`Never` 档"**再久也不锁**"这类用例可以直接 `advanceTimeBy(1 小时)`，
 * 因为它**没有**任何定时器会到点，推多远都等价。
 *
 * ⚠️ **`advanceUntilIdle()` 不是万能钥匙**：它把**所有** `delay` 跑到尽头，所以
 * 只能用来"把已经到点的动作跑完"，**不能**用来表达"时间过去了多少"。
 * 看到 `advanceUntilIdle()` 就停下来问一句：*"这里是不是想表达'还没到点'？"*
 *
 * ### 真正让前两轮"偶发红"的原因（修在实现里，不是修在测试里）
 *
 * `VaultLockManagerImpl` 原来在 **`init`** 里就 `scope.launch { observeVaults().collect { … } }`。两个后果：
 *
 * 1. **构造期派发**：`launch` 发生在构造期间，那时 `testingIoDispatcher` 还没赋值 ⇒
 *    这条收集协程永久跑在 `Dispatchers.Default` 上，虚拟时钟**推不动它**；
 * 2. **无界 `collect`**：它永不结束，`runTest` 判断"还有没有待办"时会被它搅乱 ⇒
 *    表现为**同一次构建里两条互斥断言同时红**、且每次红的不是同一条。
 *
 * ⇒ 修法：把观察/收集的**启动时机交给调用方**（`startObservingVaults()` /
 * `startCollecting()`），测试里"先注入调度器、再 start"。生产在
 * `VaultixApplication.onCreate` 显式调，行为不变。
 * **测试写法只是表象，根因在 `init` 自启** —— 只改测试永远修不掉。
 *
 * 判定证据：修完后 `security.*` 整包 `--rerun-tasks` 连跑 5 次全绿（19 例 × 5）；
 * 变异验证 7 组仍逐条精确命中（见下表）。
 *
 * ## 变异验证（改动本类实现后必做）
 *
 * | 变异 | 应变红的用例 |
 * |---|---|
 * | `Never` 分支塞回任意锁定调用 | `Never档_切后台_绝不锁定` / `Never档_进程创建_不锁定` |
 * | 切后台改成立即锁（不走定时器） | `非Never档_切后台_不立即锁` |
 * | `onAppForegrounded` 不撤定时器 | `非Never档_回前台后时间走满_仍不锁` |
 * | 定时器不再 `delay(duration)` | `非Never档_切后台满5分钟_锁定` |
 * | `onScreenOn` 取消后不重启 | `非Never档_亮屏补偿后_时间从零点走满仍会锁` |
 * | `lockAll` 退回"只锁活动库" | `lockAll_锁全部已解锁库` |
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultLockManagerImplNeverTest {

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
     * ⚠️ `runCurrent()` 在这里是**必须**的：`init` 里的收集协程要先跑一帧，
     * 把 `observeVaults()` 的结果写进 `_vaultUnlockDataStateFlow`。少了它，
     * `onAppBackgrounded()` 会因「没有已解锁的库」而早退 ⇒ 测试什么都没验证（假绿）。
     * 现在它是确定性的，不再需要「轮询 5 秒」。
     */
    private fun TestScope.managerWithRepo(
        timeout: VaultTimeout,
        vaultIds: List<String> = listOf("vault-1"),
    ): Pair<VaultLockManagerImpl, VaultRepository> {
        val prefs = mockk<VaultTimeoutPreferences>(relaxed = true)
        every { prefs.vaultTimeout(any()) } returns flowOf(timeout)

        val repository = mockk<VaultRepository>(relaxed = true)
        every { repository.observeVaults() } returns flowOf(vaultIds.map(::vault))
        every { repository.observeUnlockedVaultIds() } returns flowOf(vaultIds.toSet())

        val m = VaultLockManagerImpl(
            context = mockk<Context>(relaxed = true),
            vaultRepository = repository,
            vaultTimeoutPrefs = prefs,
        )
        // ★ 把后台调度器换成本测试的虚拟调度器 ⇒ 定时器与 init 收集全部走虚拟时间。
        //   ⚠️ 必须在 `runCurrent()` **之前**设：`scope` 是 `by lazy`-ish 的 getter，
        //      第一次 `launch` 才落到这个调度器上。
        m.testingIoDispatcher = StandardTestDispatcher(testScheduler)
        // ★ 顺序关键：**先**注入调度器，**再**启动观察（本类不再在 init 里自启）。
        m.startObservingVaults()
        // 让收集跑完一帧（解锁快照就位）。
        runCurrent()
        return m to repository
    }

    /**
     * **把待发协程跑到"没有新的同步进展"为止，但一个时钟刻度都不推。**
     *
     * ## 为什么需要它（2026-10-18 实测踩过，别改成固定次数的 `runCurrent()`）
     *
     * 被测路径的"跳数"并不固定：
     *
     * ```
     * onAppBackgrounded()
     *   └─ scope.launch { 读档位 }                      ← 第 1 跳
     *        └─ handleTimeoutActionWithDelay()
     *             └─ scope.launch { delay(N) }            ← 第 2 跳（定时器此刻才挂上）
     * ```
     *
     * `runCurrent()` 只跑**当前时刻**已排队的任务：跑一次只到第 1 跳，`delay` 还没注册；
     * 写两次又依赖"每次恰好推进一跳"，实测在"补偿"那条路径（先 cancel 再 launch）会翻车。
     * 手数"几跳"是不可靠的 —— 真正的判据是**定时器数量稳定了没有**。
     *
     * 所以这里用 [VaultLockManagerImpl.timerCountForTest] 当收敛信号：
     * 反复 `runCurrent()` 直到计数连续两次不变。确定性来自"计数"，不来自"跑几次"。
     *
     * ⚠️ 本函数**不推时钟**（只 `runCurrent`），所以它不会让定时器到点。
     *    这正是"到点前不该锁"那几条用例需要的"建好定时器但不推进"。
     */
    private fun TestScope.settle(m: VaultLockManagerImpl) {
        // ⚠️ "连续两次计数相同"是**不够**的收敛判据：补偿路径先从 1 → 0（cancel），
        //    再从 0 → 1（重启），若在 1 → 1 处提前退出，新定时器可能还没挂上。
        //    所以至少跑 [MIN_SETTLE_ROUNDS] 轮，再按计数稳定收敛。
        var previous = -2
        repeat(MAX_SETTLE_ROUNDS) { round ->
            runCurrent()
            val current = m.timerCountForTest()
            if (round + 1 >= MIN_SETTLE_ROUNDS && current == previous) return
            previous = current
        }
        error("定时器数量在 $MAX_SETTLE_ROUNDS 轮内没有稳定，疑似新增了无界 launch 链")
    }

    /**
     * 断言「锁定真的发生了」。
     *
     * ⚠️ **必须 `advanceUntilIdle()` 之后调用**，且**先**跑断言再 `runTest` 收尾 ——
     * `lockVault` 发生在 `scope.launch { … }` 里，虚拟时钟要把那个 launch 也跑掉。
     *
     * ⚠️ 别用 `coVerify(timeout = …)` 来"托底"：mockk 的 timeout 是**真实时间轮询**，
     * 在虚拟时钟下等于把确定性又换回等待。
     */
    private fun assertLockedOnce(repository: VaultRepository, vaultId: String) {
        coVerify(exactly = 1) { repository.lockVault(vaultId) }
    }

    /** 断言「任何形式的锁定都没发生」。 */
    private fun assertNoLock(repository: VaultRepository) {
        coVerify(exactly = 0) { repository.lockVault(any()) }
        coVerify(exactly = 0) { repository.lockAll() }
    }

    // ---- Never 档：任何触发都不锁 ----

    @Test
    fun `Never档_切后台_绝不锁定`() = runTest {
        val (m, repository) = managerWithRepo(VaultTimeout.Never)

        m.onAppBackgrounded()
        // ★ 推到 1 小时：**不是**等"一会儿"，而是断言"再久也不锁"。
        //   `Never` 分支如果建了定时器，这里必然到点。
        runCurrent()
        advanceTimeBy(60 * 60 * 1000L)
        advanceUntilIdle()

        // ★ 这条变红 = Never 档又开始在离场时锁库（回到被推翻的「软锁」实现）。
        assertNoLock(repository)
    }

    @Test
    fun `Never档_进程创建_不锁定`() = runTest {
        val (m, repository) = managerWithRepo(VaultTimeout.Never)

        m.onAppCreated(isFirstCreation = true, createdForAutofill = false)
        runCurrent()
        advanceTimeBy(60 * 60 * 1000L)
        advanceUntilIdle()

        assertNoLock(repository)
    }

    @Test
    fun `Never档_回前台_不锁定`() = runTest {
        val (m, repository) = managerWithRepo(VaultTimeout.Never)

        m.onAppForegrounded()
        runCurrent()
        advanceTimeBy(60 * 60 * 1000L)
        advanceUntilIdle()

        assertNoLock(repository)
    }

    // ---- 非 Never 档：延迟到点才锁 ----

    @Test
    fun `非Never档_切后台_不立即锁`() = runTest {
        // 5 分钟档由超时定时器负责锁定（本测试只推 4 分 59 秒 ⇒ 定时器还没到点）。
        // 重点：切后台**不是立即锁** —— 若这里变红，说明把「延迟 N 分钟」误实现成
        // 「离场即锁」，会与用户的档位预期打架。
        val (m, repository) = managerWithRepo(VaultTimeout.FiveMinutes)

        m.onAppBackgrounded()
        // ⚠️ 这里**绝不能**用 `advanceUntilIdle()`：它会把 5 分钟定时器一口气跑到到点，
        //   于是"不立即锁"这条永远红（2026-10-18 实测踩过）。
        //   连 `advanceTimeBy` 之后的收尾也**不能**用 `advanceUntilIdle()` ——
        //   它会把"剩下的 1ms"补上，等价于推到到点（这就是上一版红的原因）。
        //   收尾只用 `runCurrent()`：跑完"当前时刻"的任务，不再推进时钟。
        settle(m)
        advanceTimeBy(VaultTimeout.FiveMinutes.vaultTimeoutInMinutes * 60_000L - 1)
        runCurrent()

        assertNoLock(repository)
        assertTrue("非 Never 档切后台后不该立刻变成锁定态", m.isVaultUnlocked("vault-1"))
    }

    /**
     * ★★ **本条此前做不到**（硬编码 `Dispatchers.Default` 时，要真等 5 分钟）：
     * 5 分钟档切后台，时间走满 5 分钟 ⇒ **必须**锁定。
     *
     * 它是判据表里「到点才锁」的**正向**一半 —— 只有 [非Never档_切后台_不立即锁]
     * 那种"没到点不锁"的断言，`delay` 被误改小也照样绿；
     * 加了本条之后，"早了"和"晚了"两头都被钉住。
     */
    @Test
    fun `非Never档_切后台满5分钟_锁定`() = runTest {
        val (m, repository) = managerWithRepo(VaultTimeout.FiveMinutes)

        m.onAppBackgrounded()
        // ★★ 为什么必须是 `advanceUntilIdle()`，而不是 `advanceTimeBy(5 分钟)`：
        //   `checkForVaultTimeoutInternal` 自己是 `scope.launch { 读档位 → 建定时器 }`，
        //   而 `advanceTimeBy` 的执行顺序是「**先**把时钟推到位、**再**跑任务」。
        //   于是定时器是在"第 5 分钟"那一刻才 `delay(5 分钟)` 出去的 ⇒ 永远差一个任务周期。
        //   这正是本文件 2026-10-18 在全量跑时偶发 1–2 条红的根因（同一次构建里红哪条还会变，
        //   因为谁先被调度到不固定）。`advanceUntilIdle()` 是「跑到没事可做」，中间新建的
        //   `delay` 会被它自己跑掉 —— 不需要算术，也就没有算术可算错。
        advanceUntilIdle()

        assertLockedOnce(repository, "vault-1")
    }

    /**
     * ★ 回前台 = **撤销**定时器：此后时间再走多久都不该锁。
     *
     * ⚠️ 与 `Immediately`（0 分钟）档不同，这里用 5 分钟档 ⇒ 定时器**确定**还在跑，
     * 撤销与否是唯一变量。原来那条同义用例只能用 5 分钟档 + "立刻回前台"，
     * 靠的是"来不及到点"，其实什么都没证明。
     */
    @Test
    fun `非Never档_回前台后时间走满_仍不锁`() = runTest {
        val (m, repository) = managerWithRepo(VaultTimeout.FiveMinutes)

        m.onAppBackgrounded()
        runCurrent() // 先把定时器挂上（不推时间）
        m.onAppForegrounded()
        // 再推满 1 小时：撤销生效 ⇒ 到点也不该锁；漏撤某个库 ⇒ 它必锁。
        advanceTimeBy(60 * 60 * 1000L)
        advanceUntilIdle()

        assertNoLock(repository)
        assertTrue("回前台撤销定时器后不该锁", m.isVaultUnlocked("vault-1"))
    }

    // ===================== 亮屏补偿（2026-09-29 新增） =====================

    /**
     * ★ Never 档：亮屏补偿**绝不**造成锁定。
     *
     * 亮屏补偿会遍历 `timerJobMap` 并重启定时器；若实现不慎（例如无条件按档位重算），
     * Never 档就可能被"补偿"出一个定时器，从而在用户明确选择"从不"时锁库。
     *
     * 正确行为：Never 档**从未建过定时器** ⇒ `timerJobMap` 为空 ⇒ `onScreenOn()`
     * 在 `snapshot.isEmpty()` 处早退，不会有任何锁定动作。
     */
    @Test
    fun `Never档_亮屏_不锁定`() = runTest {
        val (m, repository) = managerWithRepo(VaultTimeout.Never)

        m.onAppBackgrounded() // Never 档下这不建定时器
        m.onScreenOn()
        runCurrent()
        advanceTimeBy(60 * 60 * 1000L)
        advanceUntilIdle()

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
     * - **已覆盖**：有活定时器时补偿**不提前锁**（下面这条）；
     * - **未覆盖**：`elapsedRealtime` 真实推进后「剩余 = 总量 - 已走过」的**算术**，
     *   以及"剩余钳到 0 即立即锁"。
     *
     * 后者只能靠**真机验证**：把档位设 1 分钟 → 熄屏 3 分钟 → 亮屏，
     * 库应**立即**处于锁定态（而非再等 1 分钟）。见 `Docs/progress/` 的验收清单。
     *
     * 之所以不为此引 Robolectric / 抽象 `RealtimeManager`：本项目当前没有
     * Robolectric 基建，为一个单点引入不成比例。
     *
     * ★ 2026-10-18 补：`elapsedRealtime ≡ 0` ⇒ 补偿算出的剩余 = 全量 ⇒ "刚重启"。
     *   所以本条断言的是「补偿后**没锁**」；补偿**是否真的重启了定时器**由下面
     *   `非Never档_亮屏补偿后_时间从零点走满仍会锁` 用虚拟时间正面验证。
     */
    @Test
    fun `非Never档_切后台后亮屏_不提前锁`() = runTest {
        val (m, repository) = managerWithRepo(VaultTimeout.FiveMinutes)

        m.onAppBackgrounded() // 建 5 分钟定时器
        runCurrent()
        m.onScreenOn() // 补偿：重算剩余并重启
        runCurrent()

        assertNoLock(repository)
        assertTrue("亮屏补偿不该把未到点的库锁掉", m.isVaultUnlocked("vault-1"))
    }

    /**
     * ★ 2026-10-18 新增（此前不可测）：补偿**重启**了定时器，而不是把它弄丢了。
     *
     * `elapsedRealtime ≡ 0` ⇒ 补偿把剩余算成"全量 5 分钟"并从**此刻**重新计时。
     *
     * ## ⚠️ 这条用例**不能**先让定时器跑到点
     *
     * 第一版写成「切后台 → 推满 5 分钟（锁一次）→ 补偿 → 推 4:59 → 推 1ms（再锁）」，
     * 结果最后一步永远不锁。原因在实现里：
     *
     * ```kotlin
     * override fun onScreenOn() {
     *     val snapshot = timerJobMap.toList()
     *     if (snapshot.isEmpty()) return      // ← 到点后 job 已被 remove，这里直接返回
     * ```
     *
     * 定时器到点时会先 `timerJobMap.remove(vaultId)` 再锁，所以**已经到点过**的库
     * 在补偿时 map 里没有条目 ⇒ 补偿对它什么也不做（这是**正确**行为：库已经锁了，
     * 没有"剩余时间"可补）。是我的用例设计错了，不是产品缺陷。
     *
     * ## 现在的断言（不依赖"已经锁过"）
     *
     * 1. 切后台 → 只推 **1 分钟**（未到点，`lockVault` 零调用）；
     * 2. 补偿 → 剩余按 `elapsedRealtime≡0` 算成全量，从**此刻**重启；
     * 3. 从此刻起推 4:59 ⇒ **仍不该锁** ——
     *    > 若补偿"只读不重启"（旧 job 不 cancel 也不新建），时间按**最初**那次切后台起算，
     *    > 此刻已走过 1 分钟 + 4:59 = 5:59 > 5 分钟 ⇒ **早就锁了** ⇒ 红；
     *    > 若补偿"取消后不重启"，则永远不锁 ⇒ 第 4 步不锁 ⇒ 也红；
     * 4. 再推 1ms ⇒ 必须锁（证明新定时器是真的、且时长是全量 5 分钟）。
     */
    @Test
    fun `非Never档_亮屏补偿后_时间从零点走满仍会锁`() = runTest {
        val (m, repository) = managerWithRepo(VaultTimeout.FiveMinutes)

        m.onAppBackgrounded()
        settle(m)
        // 只推 1 分钟：定时器**还活着**（这正是补偿能起作用的前提），库未锁。
        advanceTimeBy(60_000L)
        runCurrent()
        assertNoLock(repository)

        m.onScreenOn() // 补偿：剩余按 elapsedRealtime≡0 算成全量，从此刻重启
        settle(m)

        // 从此刻起推 4:59：不该锁（新定时器是全量 5 分钟）。
        advanceTimeBy(VaultTimeout.FiveMinutes.vaultTimeoutInMinutes * 60_000L - 1)
        runCurrent()
        assertNoLock(repository)

        // 再推 1 毫秒 —— 越过新定时器的到点边。
        advanceTimeBy(1)
        advanceUntilIdle()
        assertLockedOnce(repository, "vault-1")
    }

    // ===================== 多库：每库各自计时（2026-09-29 · D2） =====================

    /**
     * ★★ **D2**：切后台时**每个已解锁库**各起一份定时器（各自到点）。
     *
     * 旧实现只取 `activeVaultId`（= 首个已注册库）⇒ 另一个库**永远没人计时**，
     * 表现为「开 A 再开 B，只有 A 会被锁、B 一直开着」——
     * 用户报的「两个库加锁逻辑混乱」的主因之一（`.ai/issues/04-锁与解锁.md` #131）。
     *
     * 用 0 分钟档（`Immediately`）：`delay(0)` 在虚拟时钟下推 1ms 即到点。
     * ⚠️ 别的用例别学这个档位 —— 它测不出"延迟"这层语义。
     */
    @Test
    fun `多库_立即档_切后台_每个已解锁库各锁各的`() = runTest {
        val (m, repository) = managerWithRepo(
            VaultTimeout.Immediately,
            vaultIds = listOf("vault-1", "vault-2"),
        )

        m.onAppBackgrounded()
        advanceUntilIdle()

        assertLockedOnce(repository, "vault-1")
        assertLockedOnce(repository, "vault-2")
    }

    /**
     * ★ **D2 的另一半**：回前台撤销**全部**定时器（不只是活动库的）。
     *
     * 旧实现只撤 `activeVaultId` ⇒ 别的库的 job 继续跑到点，
     * 于是「用户明明在 App 里看着，另一个库却被锁了」。
     *
     * ★ 2026-10-18：原来这条用的是 5 分钟档 + "立刻回前台"，靠"来不及到点"取胜 ——
     * **它其实什么都没证明**（定时器在不在都没到点）。现在推虚拟时间走满：
     * 撤销生效 ⇒ 一个都不锁；撤销漏掉某个库 ⇒ 那个库必红。
     */
    @Test
    fun `多库_回前台_撤销全部定时器`() = runTest {
        val (m, repository) = managerWithRepo(
            VaultTimeout.FiveMinutes,
            vaultIds = listOf("vault-1", "vault-2"),
        )

        m.onAppBackgrounded()
        runCurrent() // 先让定时器挂上（不推时间）
        m.onAppForegrounded() // 撤销全部
        // ★ 推满 2 倍时长：撤销生效 ⇒ 一个都不锁；漏撤某个库 ⇒ 它必锁。
        //   别用 `advanceUntilIdle()`：那等价于"推到定时器到点"，撤销与否都锁一个，
        //   这条就变成了自我实现的预言。
        advanceTimeBy(VaultTimeout.FiveMinutes.vaultTimeoutInMinutes * 60_000L * 2)
        advanceUntilIdle()

        assertNoLock(repository)
    }

    // ===================== 「立即锁定」= 锁全部（2026-10-18 修） =====================

    /**
     * ★ `lockAll` 必须锁**每一个**已解锁库。
     *
     * 起因：`AutoLockController.lockAllNow()` 原来转发 `lockVaultForCurrentUser`，
     * 后者只锁 `activeVaultId`（首个库）⇒ 多库下第二个库**静默保持解锁**，
     * 而文案写着「清零**全部**库的解锁密钥」（`setting_lock_now_desc`）。
     *
     * 本条的变红条件很窄 —— 它专门盯"只锁一个"：
     * - 退回 `lockVault(activeVaultId)` ⇒ `lockAll()` 零调用 ⇒ 红；
     * - 遍历漏掉某个库 ⇒ 对应 `lockVault(id)` 零调用 ⇒ 红。
     */
    @Test
    fun `lockAll_锁全部已解锁库`() = runTest {
        val (m, repository) = managerWithRepo(
            VaultTimeout.FiveMinutes,
            vaultIds = listOf("vault-1", "vault-2"),
        )

        m.lockAll(isUserInitiated = true)
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.lockAll() }
        // 锁全 = 仓储的 `lockAll` 一次，**不是**逐个 `lockVault`（房钥匙只会清一次）。
        coVerify(exactly = 0) { repository.lockVault(any()) }
    }

    /**
     * ★ `lockAll` 也要把**定时器**清干净：锁定之后残留的 job 到点还会再锁一次
     *（幂等但多余），更糟的是它会留在 `timerJobMap` 里被 `onScreenOn` 拿去补偿。
     */
    @Test
    fun `lockAll_撤销定时器_时间再走也不重复锁`() = runTest {
        val (m, repository) = managerWithRepo(VaultTimeout.FiveMinutes)

        m.onAppBackgrounded()
        runCurrent()
        m.lockAll(isUserInitiated = true)
        // ⚠️ 必须 `advanceUntilIdle()`：`lockAll` 内部也是 `scope.launch`，
        //   只 `runCurrent()` 时它还排在队列里（实测：这时 `lockAll()` 零调用）。
        advanceUntilIdle()

        // ★ 再推满 2 倍时长：`lockAll` 应当已经把定时器全撤了。
        //   用 `advanceUntilIdle()` 在这里是错的 —— 它等价于"推到到点"，
        //   撤没撤都会锁，于是这条永远绿（假绿）。
        advanceTimeBy(VaultTimeout.FiveMinutes.vaultTimeoutInMinutes * 60_000L * 2)
        advanceUntilIdle()

        // 定时器没有额外触发一次 lockVault（锁全由 lockAll 触发）。
        coVerify(exactly = 0) { repository.lockVault(any()) }
        coVerify(exactly = 1) { repository.lockAll() }
    }

    private companion object {
        /** [settle] 的收敛下限（见其 KDoc：避免在 "1 → 1" 处误判收敛）。 */
        const val MIN_SETTLE_ROUNDS = 3

        /** [settle] 的收敛上限。 */
        const val MAX_SETTLE_ROUNDS = 8
    }
}
