/*
 * Vaultix — app:security
 * Copyright (C) 2026 Vaultix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * `ScreenSecurityGuard` 的行为锁定（2026-10-02 补，双库健康度审计批次 A 的 #159）。
 *
 * ## 为什么这个类必须有测试
 *
 * #159 修的是「`FLAG_SECURE` 只挂 `MainActivity`，另外 9 个 Activity 全裸」——
 * 一个**纯安全性**缺陷：自动填充框、通行密钥选择页这些**用户最常在第三方 App 里看到**的
 * 界面，系统截图 / 录屏 / 最近任务缩略图能把条目名和密码整段录走。
 *
 * 而它的实现方式（进程级 `ActivityLifecycleCallbacks`）有个恼人的性质：
 * **"忘了写" 不会编译失败、也不会崩，只会静默少一层保护。**
 * ⇒ 唯一能钉住它的办法就是测试：把"哪些时机必须设 flag"写成断言。
 *
 * ## 怎么消除竞态：等「快照写进去了」这个信号，而不是等它自己发生
 *
 * `enabledSnapshot` 初值是 `true`（安全默认），而真正把它改成实际偏好值的
 * `collect` 跑在**进程级 `Dispatchers.Default`** 上。⇒ 若不等待就驱动生命周期回调，
 * 那一次读到的是初值 `true`，会走 `setFlags` 分支；而**回调不会再有第二次**来纠正。
 *
 * ⚠️ "驱动一次再 `verify(timeout = …)` 等 `clearFlags`"**救不了**：
 *   `verify` 的 timeout 只轮询**已经发生**的调用，不会重新驱动回调。
 *   那种写法的结果不是"偶尔红"，而是**稳定超时失败**——比flaky 更难查，因为它看起来
 *   像"环境慢"。
 *
 * ⇒ 正确做法是**让夹具自己报信**：flow 每 emit 一个值就往 `Channel` 发一个信号，
 *   测试 `receive()` 到信号 == 订阅确实把快照写完了。此后所有断言都能用 `exactly = n`，
 *   **完全确定性、零 flaky**（见 [Harness.awaitSettled]）。
 *
 * 这是 #130.1（分清"没等到"与"等到了不该发生的结果"）之后的下一步：
 * #130.1 让 flaky 变得**可诊断**，这里直接把竞态**从夹具里消除**，不再需要轮询。
 *
 * ## ⚠️ 为什么用 `runBlocking` 而不是 `runTest`（代价换来的，务必别改回去）
 *
 * `runTest` 用的是**虚拟时间**：它会把 `withTimeout(5000)` 直接跳到 5 秒**立刻超时**，
 * 根本不等真实线程。⇒ 拿它去等一个跑在`Dispatchers.Default` 上的信号，
 * **超时不是保护，是随机抛异常**。
 *
 *⚠️ **它的表现是"随机"的，不是稳定红**（这一点最容易误判成"偶发 flaky，算了"）：
 *   - 信号已就绪 ⇒ 不需要等待 ⇒ **绿**；
 *   - 信号还没到 ⇒ 虚拟时间立刻跳满5 秒 ⇒ `awaitSettled()` 抛
 *     `IllegalStateException` ⇒ **红**。
 *   本地连跑 5 次：前 2 次绿、后 3 次红；换`runBlocking` 后连跑 8 次全绿。
 *   CI run `37132804360` 上则是 6 条里**恰好 1 条红**（`开关运行中从开变关`，
 *   那条要等**第二个**信号 ⇒ 最容易撞上虚拟时间）。
 *
 * ⇒ 这个文件里的等待都是**真实线程**上的等待，`runTest` 的虚拟调度器**毫无价值**，
 * 换`runBlocking` 才是对的：超时恢复为真正的 5 秒。
 * ⚠️ 反过来，若哪天被测代码改成跑在 `TestDispatcher` 上，就得换回 `runTest`——
 * 判据是"**被等的那个协程在不在测试调度器里**"，不是哪个更常见。
 *
 * ⇒顺带一条**通用教训**：断言前的 `awaitSettled()` 只回答"快照写进去了吗"，
 *   不回答"流程走完了吗"。第 6 条用例证明流程能连续走两次，靠的是
 *   **第二次等待也真的等了**——若把它换成固定 `sleep`，那才是把随机性藏起来。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.security

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import io.vaultix.datastore.VaultixPreferences
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

class ScreenSecurityGuardTest {

    private val secure = WindowManager.LayoutParams.FLAG_SECURE

    /**
     * 装好守卫并把注册进去的回调**抓出来**，让测试能像系统那样驱动生命周期。
     *
     * 抓回调而不是直接暴露内部方法：这里要验的正是
     * 「**系统通过生命周期回调驱动它**」这条链路本身。
     *
     * @param source 喂给 `screenSecurity` 的偏好流；由测试持有引用以便运行中翻转。
     */
    private fun install(source: Flow<Boolean>): Harness {
        // 每个被 collect 到的值都发一个信号 —— 它同时证明「订阅在跑」与
        // 「`enabledSnapshot` 已经写成这个值了」，因为 emit 返回时
        // 下游 `collect { enabledSnapshot = value }` 已经执行完
        //（`distinctUntilChanged` 是转发型操作符，会等下游处理完才返回）。
        val settled = Channel<Unit>(Channel.UNLIMITED)
        val prefs = mockk<VaultixPreferences>()
        every { prefs.screenSecurity } returns flow {
            source.collect { value ->
                emit(value)
                settled.send(Unit)
            }
        }

        val app = mockk<Application>(relaxed = true)
        val captor = slot<Application.ActivityLifecycleCallbacks>()
        // ⚠️ slot 必须用 `capture(...)` 包裹，直接传 captor 编译不过
        //（`Argument type mismatch: actual type is 'CapturingSlot<…>'`）。
        // ⚠️ **不要 import io.mockk.capture** —— `capture` 是 `MockKMatcherScope`
        //   的成员函数，`every {}` 的 lambda 自带该 receiver，直接写就能解析。
        //   多写那行 import 会得到 `Unresolved reference 'capture'`（2026-10-03 实录，
        //   连续两次被同一处绊倒）。既有用法见 `ItemRepositoryImplTest:151`，
        //   它的 mockk import 只有 coEvery/coVerify/every/mockk/slot。
        every { app.registerActivityLifecycleCallbacks(capture(captor)) } just runs

        ScreenSecurityGuard(prefs).install(app)

        val window = mockk<Window>(relaxed = true)
        val activity = mockk<Activity>(relaxed = true)
        every { activity.window } returns window
        return Harness(captor.captured, activity, window, settled)
    }

    private class Harness(
        val callbacks: Application.ActivityLifecycleCallbacks,
        val activity: Activity,
        val window: Window,
        private val settled: Channel<Unit>,
    ) {
        /**
         * 等到偏好变更**已被订阅写进快照**。
         *
         * 超时而非无限等：万一 `install()` 里的 `launch` 被删了 / `collect` 没跑，
         * 测试要在 5 秒内**响亮地失败**，而不是挂到 `runTest` 的 60 秒上限。
         */
        suspend fun awaitSettled() {
            try {
                withTimeout(5_000) { settled.receive() }
            } catch (_: TimeoutCancellationException) {
                error(
                    "偏好变更 5 秒内没被订阅观察到 —— `install()` 里的 launch/collect 掉了？",
                )
            }
        }
    }

    // ---- 规则 1：开关开启 ⇒ 设 FLAG_SECURE ----

    @Test
    fun `开关开启_三个回调都设FLAG_SECURE`() = runBlocking {
        val h = install(flowOf(true))
        // 开启侧顺带钉住「订阅确实启动了」：初值虽也是 true（等不等结果一样），
        // 但这里等一下，就能抓住「launch 被删掉」这种退化 —— 否则本用例永远绿。
        h.awaitSettled()

        // 三个时机都必须覆盖 —— 少一个就有一类 Activity 裸奔：
        //  · onActivityPreCreated  API 29+，最早的时机
        //  · onActivityCreated     全版本
        //  · onActivityResumed     覆盖"用户刚在系统层面清掉了 flag"
        h.callbacks.onActivityPreCreated(h.activity, null)
        h.callbacks.onActivityCreated(h.activity, null)
        h.callbacks.onActivityResumed(h.activity)

        verify(exactly = 3) { h.window.setFlags(secure, secure) }
        verify(exactly = 0) { h.window.clearFlags(any()) }
    }

    @Test
    fun `开关开启_不调用clearFlags`() = runBlocking {
        val h = install(flowOf(true))
        h.awaitSettled()

        h.callbacks.onActivityCreated(h.activity, null)

        // 反向断言：`clearFlags` 一旦被调，说明两个分支的语义反了（保护被自己解除）
        verify(exactly = 0) { h.window.clearFlags(any()) }
    }

    // ---- 规则 2：开关关闭 ⇒ 清 FLAG_SECURE（用户显式关掉的，必须尊重） ----

    @Test
    fun `开关关闭_清掉FLAG_SECURE`() = runBlocking {
        val h = install(flowOf(false))
        // ⚠️ 这一步是本文件的关键：不等它，第一次 `onActivityCreated` 会读到初值
        //   `true` 而误设flag，**且不会有第二次回调纠正**。
        h.awaitSettled()

        h.callbacks.onActivityCreated(h.activity, null)

        // 快照已确定为 false ⇒ 这里可以用 `exactly`，不需要 timeout 轮询。
        verify(exactly = 1) { h.window.clearFlags(secure) }
        // 这个 0 现在是**真断言**（此前做不到）：快照已落地，走的必然是关闭分支。
        verify(exactly = 0) { h.window.setFlags(any(), any()) }
    }

    @Test
    fun `开关关闭_恢复时也保持清掉`() = runBlocking {
        val h = install(flowOf(false))
        h.awaitSettled()

        h.callbacks.onActivityResumed(h.activity)

        verify(exactly = 1) { h.window.clearFlags(secure) }
        verify(exactly = 0) { h.window.setFlags(any(), any()) }
    }

    // ---- 规则 3：运行中开关翻转 ⇒ 之后启动的 Activity 立刻按新值走 ----

    @Test
    fun `开关运行中从开变关_之后清掉FLAG_SECURE`() = runBlocking {
        // 热流而不是 `flowOf`：`flowOf` 发一次就结束，测不到"持续跟随变化"。
        // 而 `install()` 的实现注释明确声称「进程级订阅：开关变了要能立刻反映到
        // **之后启动**的每个 Activity」—— 这条声称必须有用例钉住，否则它是注释而已。
        val toggle = MutableStateFlow(true)
        val h = install(toggle)
        h.awaitSettled()

        h.callbacks.onActivityCreated(h.activity, null)
        verify(exactly = 1) { h.window.setFlags(secure, secure) }

        toggle.value = false
        h.awaitSettled()

        h.callbacks.onActivityCreated(h.activity, null)
        // 前一次的 setFlags 保留在记录里（=1），本次新增的 clearFlags 也是 1。
        verify(exactly = 1) { h.window.clearFlags(secure) }
        verify(exactly = 1) { h.window.setFlags(secure, secure) }
    }

    // ---- 规则 4：其余回调不碰窗口 ----

    @Test
    fun `其余生命周期回调不碰窗口`() = runBlocking {
        val h = install(flowOf(true))
        h.awaitSettled()

        // ⚠️ `outState` 传了 `mockk<Bundle>()` 而不是 `null`：
        //   `onActivitySaveInstanceState(activity, outState: Bundle)` 在**真实
        //   android.jar** 里带 `@NonNull`，传 null 编译不过；这里传 mock 是最保险的写法。
        //   ⚠️ 反过来，**本地 typecheck 抓不到这个错**——它用的是 robolectric 的
        //   `android-all.jar`，字节码里没有空注解 ⇒ Kotlin 视作平台类型 ⇒ null 也放行。
        //   （实测：把它改成 null，本地检查依然"通过"。）别拿本地绿灯当这件事的证据。
        h.callbacks.onActivityStarted(h.activity)
        h.callbacks.onActivityPaused(h.activity)
        h.callbacks.onActivityStopped(h.activity)
        h.callbacks.onActivityDestroyed(h.activity)
        h.callbacks.onActivitySaveInstanceState(h.activity, mockk<Bundle>(relaxed = true))

        // 少写一个空实现就得实现整个接口，纯噪音；但写错了会在这里暴露。
        verify(exactly = 0) { h.window.setFlags(any(), any()) }
        verify(exactly = 0) { h.window.clearFlags(any()) }
    }
}
