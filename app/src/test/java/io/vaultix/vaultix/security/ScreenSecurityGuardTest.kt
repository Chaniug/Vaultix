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
 * ## 断言方向的选择（本文件的判据）
 *
 * 开启侧与关闭侧的**竞态后果不对称**，所以两类用例的写法也不同：
 *
 * | 用例 | 快照未就绪时的行为 | 风险 |
 * |---|---|---|
 * | 开启（`setFlags`） | 初值就是 `true` ⇒ 仍会 `setFlags` | **不会假绿** |
 * | 关闭（`clearFlags`） | 快照仍是 `true` ⇒ 会走 `setFlags` 而非 `clearFlags` | 只会**假红** |
 *
 * ⇒ 宁可偶发红（能看见、能重跑），也不要假绿（看不见、以为有保护）。
 * 这是 #130「区分『没等到』与『等到了不该发生的结果』」在测试设计上的应用。
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenSecurityGuardTest {

    private val secure = WindowManager.LayoutParams.FLAG_SECURE

    /**
     * 装好守卫并把注册进去的回调**抓出来**，让测试能像系统那样驱动生命周期。
     *
     * 抓回调而不是直接暴露内部方法：这里要验的正是
     * 「**系统通过生命周期回调驱动它**」这条链路本身。
     */
    private fun install(screenSecurity: Boolean): Harness {
        val prefs = mockk<VaultixPreferences>()
        every { prefs.screenSecurity } returns flowOf(screenSecurity)
        val app = mockk<Application>(relaxed = true)
        val captor = slot<Application.ActivityLifecycleCallbacks>()
        every { app.registerActivityLifecycleCallbacks(captor) } just runs

        ScreenSecurityGuard(prefs).install(app)

        val window = mockk<Window>(relaxed = true)
        val activity = mockk<Activity>(relaxed = true)
        every { activity.window } returns window
        return Harness(captor.captured, activity, window)
    }

    private class Harness(
        val callbacks: Application.ActivityLifecycleCallbacks,
        val activity: Activity,
        val window: Window,
    )

    // ---- 规则 1：开关开启 ⇒ 设 FLAG_SECURE ----

    @Test
    fun `开关开启_三个回调都设FLAG_SECURE`() = runTest {
        // ⚠️ 开启侧**不需要**等订阅：`enabledSnapshot` 的初值就是 true（安全默认），
        //   等不等订阅结果一样 —— 早等反而只是白花时间。
        val h = install(screenSecurity = true)

        // 三个时机都必须覆盖 —— 少一个就有一类 Activity 裸奔：
        //  · onActivityPreCreated  API 29+，最早的时机
        //  · onActivityCreated     全版本
        //  · onActivityResumed     覆盖"用户刚在系统层面清掉了 flag"
        h.callbacks.onActivityPreCreated(h.activity, null)
        h.callbacks.onActivityCreated(h.activity, null)
        h.callbacks.onActivityResumed(h.activity)

        verify(exactly = 3) { h.window.setFlags(secure, secure) }
        verify(exactly = 0) { h.window.clearFlags(secure) }
    }

    @Test
    fun `开关开启_不调用clearFlags`() = runTest {
        val h = install(screenSecurity = true)

        h.callbacks.onActivityCreated(h.activity, null)

        // 反向断言：`clearFlags` 一旦被调，说明两个分支的语义反了（保护被自己解除）
        verify(exactly = 0) { h.window.clearFlags(any()) }
    }

    // ---- 规则 2：开关关闭 ⇒ 清 FLAG_SECURE（用户显式关掉的，必须尊重） ----

    @Test
    fun `开关关闭_清掉FLAG_SECURE`() = runTest {
        val h = install(screenSecurity = false)

        h.callbacks.onActivityCreated(h.activity, null)

        // 用 `verify(timeout = …)` 而不是「先 sleep 再断言」：订阅跑在 Default 上，
        // 固定等待的长度取决于机器繁忙程度 ⇒ 那是把 flaky 换了个地方放。
        verify(timeout = 5_000) { h.window.clearFlags(secure) }
        // 必须在 clearFlags 发生**之后**再断言"没设过 flag"，
        // 否则这个 0 只是在说"此刻还没设"，而不是"这条路径不会设"。
        verify(exactly = 0) { h.window.setFlags(secure, secure) }
    }

    @Test
    fun `开关关闭_恢复时也保持清掉`() = runTest {
        val h = install(screenSecurity = false)

        h.callbacks.onActivityResumed(h.activity)

        verify(timeout = 5_000) { h.window.clearFlags(secure) }
    }

    // ---- 规则 3：其余回调不碰窗口 ----

    @Test
    fun `其余生命周期回调不碰窗口`() = runTest {
        // 初值 true ⇒ 无需等订阅
        val h = install(screenSecurity = true)

        // ⚠️ `outState: Bundle` 是**非空**类型，不能传 null（那样编译不过）。
        h.callbacks.onActivityStarted(h.activity)
        h.callbacks.onActivityPaused(h.activity)
        h.callbacks.onActivityStopped(h.activity)
        h.callbacks.onActivityDestroyed(h.activity)
        h.callbacks.onActivitySaveInstanceState(h.activity, mockk<Bundle>())

        // 少写一个空实现就得实现整个接口，纯噪音；但写错了会在这里暴露。
        verify(exactly = 0) { h.window.setFlags(any(), any()) }
        verify(exactly = 0) { h.window.clearFlags(any()) }
    }
}
