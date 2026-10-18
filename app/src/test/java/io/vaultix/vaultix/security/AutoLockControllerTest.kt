package io.vaultix.vaultix.security

import androidx.lifecycle.LifecycleOwner
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `AutoLockController` 的接线纪律（2026-10-18 补，同日改成**虚拟时钟**）。
 *
 * ## 为什么现在才补
 *
 * 本类被 3 个地方依赖（`VaultShellViewModel.lockEpoch` / `SettingsViewModel.lockAllNow` /
 * `ProcessLifecycleOwner` 观察者），却**一条测试都没有**。它只有六十行，
 * 但有一处**语义越界**的地方：`lockAllNow()` 原来转发的是
 * `lockVaultForCurrentUser` —— 只锁**活动库**（= 首个已注册库），
 * 而方法名与 `setting_lock_now_desc` 都写着"**全部**库"。
 *
 * 多库下第二个库会**静默保持解锁**：用户点了"锁定"，退出再进，界面还能直接看到条目。
 * 这类缺陷不崩、不报错、不写日志，**除了测试没有别的办法抓**。
 *
 * ## 判据
 *
 * | 动作 | 必须发生 |
 * |---|---|
 * | `lockAllNow()` | `VaultLockManager.lockAll(isUserInitiated = true)` **一次** |
 * | `lockAllNow()` | **不得**退化成 `lockVault(…)` |
 * | `onStart` / `onStop` | `onAppForegrounded` / `onAppBackgrounded` 各一次 + `isForeground` 翻转 |
 * | `VaultStateEvent.Locked` | `lockEvents` 自增（导航回根的唯一信号） |
 * | `VaultStateEvent.Unlocked` | `lockEvents` **不**动 |
 *
 * ## ★★ 2026-10-18：从「真实时间轮询」改成**虚拟时钟**
 *
 * 第一版跑的是真实时间：本类 `scope` 当时硬编码 `Dispatchers.Default`，
 * `init` 里那条收集协程跑在真实线程池上 ⇒ 只能靠
 * ① 轮询 `subscriptionCount > 0` 确认收集器挂上了、② `Thread.sleep(200)` 等它处理完。
 * 那版之所以能过，全靠"握手 + 大方等"，本质仍是**运气测试**，而且整类耗时 1.2s 起。
 *
 * 现在 `AutoLockController` 有 `testingIoDispatcher` 覆写口（生产恒 `null`），
 * 注入 `StandardTestDispatcher(testScheduler)` 后：
 * - `managerWithRepo` 里的 `runCurrent()` 一步把 `init` 的收集挂好
 *   （`subscriptionCount == 1` 可以**直接断言**，不用轮询）；
 * - 事件处理用 `advanceUntilIdle()`（同一次 `runTest` 里，两个类共享 `testScheduler`，
 *   所以推进一次就把 `onEach` 跑完）；
 * - 断言「没发生」时**不需要**任何等待 —— 时钟没推，任务就没跑，
 *   这个"没跑"是虚构时钟的定义，不是运气。
 *
 * ⚠️ 别把 `Thread.sleep` / 轮询写回来：那会重新引入"单独跑绿、全量跑红"的时序竞态。
 *
 * ## 变异验证
 *
 * | 变异 | 应变红的用例 |
 * |---|---|
 * | `lockAllNow` 退回 `lockVaultForCurrentUser` | `lockAllNow_锁全部库_不退化成锁活动库` |
 * | `onStop` 不调 `onAppBackgrounded` | `onStop_切后台_翻前台标记并通知管理器` |
 * | `Locked` 分支写成 `_lockEvents.value = 1`（覆盖而非累加） | `多条Locked事件_代次累加而不是覆盖` |
 * | `if (event is Locked)` 的判据去掉（Unlocked 也自增） | `收到Unlocked事件_锁定代次不动` |
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoLockControllerTest {

    private class Fixture {
        val lockManager = mockk<VaultLockManager>(relaxed = true)
        val events = MutableSharedFlow<VaultStateEvent>(extraBufferCapacity = 16)
    }

    /**
     * 造控制器，并把它的 `init` 收集协程**跑起来**。
     *
     * ⚠️ `runCurrent()` 不可省：`launchIn(scope)` 只是把协程排进队列，
     * 不跑它 `onEach` 就没挂上 ⇒ `MutableSharedFlow(replay = 0)` 会把事件丢掉 ⇒ 假红
     * （第一版就是这么翻的车）。跑完之后 `subscriptionCount == 1` 是**确定**的。
     */
    private fun TestScope.controller(): Pair<AutoLockController, Fixture> {
        val f = Fixture()
        every { f.lockManager.vaultStateEventFlow } returns f.events
        val c = AutoLockController(f.lockManager)
        // ★ 必须在 `runCurrent()` 之前设：`scope` 是 getter，第一次 `launch` 才落到这个调度器上。
        // ★ 顺序是关键：**先**注入调度器，**再**启动收集。
        //   （本类不再在 `init` 里自启收集 —— 那样会在注入之前就派发到 `Dispatchers.Default`。）
        c.testingIoDispatcher = StandardTestDispatcher(testScheduler)
        c.startCollecting()
        // `launchIn` 只是把协程排进队列；要真正 `collect` 上（`subscriptionCount` +1）
        // 得让它跑一帧。这里还没有任何定时器，`advanceUntilIdle()` 等价于"跑光队列"。
        advanceUntilIdle()
        assertEquals("init 的收集协程应当已挂上（否则后面的事件会被丢弃）", 1, f.events.subscriptionCount.value)
        return c to f
    }

    // ---- 锁定入口 ----

    @Test
    fun `lockAllNow_锁全部库_不退化成锁活动库`() = runTest {
        val (c, f) = controller()

        c.lockAllNow()

        verify(exactly = 1) { f.lockManager.lockAll(isUserInitiated = true) }
        // ★ 门禁的另一半：老实现走的是这条，一旦有人"图省事"改回来，这里立刻红。
        verify(exactly = 0) { f.lockManager.lockVault(any(), any()) }
    }

    // ---- 前后台接线 ----

    @Test
    fun `onStart_回前台_翻前台标记并通知管理器`() = runTest {
        val (c, f) = controller()
        assertFalse("初值必须是 false：进程创建时还没走完 onStart", c.isForeground.value)

        c.onStart(mockk<LifecycleOwner>(relaxed = true))

        assertTrue(c.isForeground.value)
        verify(exactly = 1) { f.lockManager.onAppForegrounded() }
    }

    @Test
    fun `onStop_切后台_翻前台标记并通知管理器`() = runTest {
        val (c, f) = controller()
        c.onStart(mockk<LifecycleOwner>(relaxed = true))

        c.onStop(mockk<LifecycleOwner>(relaxed = true))

        assertFalse(c.isForeground.value)
        verify(exactly = 1) { f.lockManager.onAppBackgrounded() }
        // 回前台那次**不该**被连带重复调用（防止有人把两个回调抄混）。
        verify(exactly = 1) { f.lockManager.onAppForegrounded() }
    }

    // ---- 锁定代次（导航回根的唯一信号） ----

    @Test
    fun `收到Locked事件_锁定代次自增`() = runTest {
        val (c, f) = controller()
        val before = c.lockEvents.value

        f.events.tryEmit(VaultStateEvent.Locked("v1"))
        advanceUntilIdle()

        assertEquals(before + 1, c.lockEvents.value)
    }

    @Test
    fun `收到Unlocked事件_锁定代次不动`() = runTest {
        // 反向断言：Unlocked 也自增的话，每次解锁都会把用户"弹回库列表"，
        // 表现为"解锁成功却被踢出去了"。
        //
        // ⚠️ 先把渠道"炒热"：先发一个 Locked 并确认它生效 ⇒ 证明"发射 → 收集 → update"
        //    这条链**此刻是通的**；此后 Unlocked 没让它变，才是"方向不对"而不是"链路没起来"。
        val (c, f) = controller()
        f.events.tryEmit(VaultStateEvent.Locked("v1"))
        advanceUntilIdle()
        assertEquals("预热事件必须生效，否则下面的断言证明不了任何事", 1, c.lockEvents.value)

        f.events.tryEmit(VaultStateEvent.Unlocked("v1"))
        advanceUntilIdle()

        assertEquals("Unlocked 不该改变锁定代次", 1, c.lockEvents.value)
    }

    @Test
    fun `多条Locked事件_代次累加而不是覆盖`() = runTest {
        // 代次是"回根"的触发信号：若写成 `= 1`（覆盖），第二次锁定就不会再弹回去
        //（用户停在条目详情，锁定后仍能看到旧界面）。
        val (c, f) = controller()

        f.events.tryEmit(VaultStateEvent.Locked("v1"))
        advanceUntilIdle()
        f.events.tryEmit(VaultStateEvent.Locked("v2"))
        advanceUntilIdle()

        assertEquals(2, c.lockEvents.value)
    }
}
