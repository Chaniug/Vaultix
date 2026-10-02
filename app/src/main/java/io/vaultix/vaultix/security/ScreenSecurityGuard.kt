/*
 * Vaultix — app:security
 * Copyright (C) 2026 Vaultix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * **防截屏的全局兜底**（2026-10-02 双库健康度审计 · 批次 A）。
 *
 * ## 修的是什么
 *
 * 在此之前 `FLAG_SECURE` **只挂在 `MainActivity`**（经 `ScreenSecurityEffect` 这一个
 * Composable）。而本进程里还有 **9 个 Activity 显示敏感内容**：
 *
 * | Activity | 屏上有什么 |
 * |---|---|
 * | `AutofillActivity` | 解锁引导 + 候选条目（用户名/掩码密码） |
 * | `ManualFillActivity` | 同上（快捷方式入口） |
 * | `AutofillSaveActivity` | 待保存的用户名与密码 |
 * | `AutofillInlinePlaceholderActivity` | 内联填充宿主 |
 * | `CredentialProviderActivity` | 通行密钥选择的整份候选列表 |
 * | `PasskeyGetActivity` / `PasswordGetActivity` | **候选项 + 解锁后的条目详情** |
 * | `PasskeyCreateActivity` | 新建通行密钥的确认页 |
 * | `CredentialProviderSettingsActivity` | 凭据提供商设置 |
 *
 * 这些页面的共同点：**它们都不走 Compose**（所以 `ScreenSecurityEffect` 挂不上去），
 * 而恰恰是**用户在第三方 App 里最常看到的那几张**——自动填充框一弹出，
 * 系统截图、录屏、"最近任务"卡片缩略图就能把条目名和密码整段录走。
 *
 * ⇒ 防截屏必须是**进程级属性**（跟着 Activity 走），不能是"某个页面记得写一行"。
 *
 * ## 为什么用 ActivityLifecycleCallbacks 而不是抽基类
 *
 * 抽 `SecureActivity` 基类要改 10 个文件的继承关系（`FragmentActivity` / `Activity`
 * 两种基类、还有 Hilt 的 `@AndroidEntryPoint` 叠加），改动面大且每加一个 Activity
 * 仍需**记得继承**。生命周期回调反过来：**新 Activity 默认被保护**，忘了也不会漏。
 * 这正是本类存在的唯一理由——把"记得写"变成"不可能忘"。
 *
 * ## ⚠️ 为什么 6 个不用 hook：`onActivityCreated` 在 `Activity.onCreate()` **之后**才回调
 *
 * `Activity.performCreate()` 的顺序是：`onCreate()` → `dispatchActivityCreated`。
 * 所以此时 `setContent` 已经跑过。
 *
 * 但**这对 `FLAG_SECURE` 不构成问题**：该 flag 由 WindowManager 在**合成 surface
 * 时**读取，而窗口要到 `onResume` 之后才被 WMS 加进去——`onActivityCreated` 仍远在
 * **第一帧呈现之前**，行为等价于文档中「`setContent` 之前」那条要求。
 *
 * 为兼顾 API 29+ 上的更早时机，额外覆盖 `onActivityPreCreated`（只在 29+ 被回调，
 * 且它确实跑在 `onCreate` 之前）；两个回调设同样的 flag，幂等。
 *
 * ## 为什么还要缓存一份开关快照
 *
 * 偏好是 DataStore 的 `Flow`（异步），而生命周期回调必须**同步**给出结论——
 * 阻塞等 IO 会拖慢每个 Activity 的启动。⇒ 用协程订阅维护 `@Volatile` 快照。
 *
 * ⚠️ 初值取 `true`（与 `VaultixPreferences.screenSecurity` 的默认一致、且与安全侧
 *   的默认值取向一致：**宁可多保护一瞬，不可漏保护一瞬**）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.security

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.WindowManager
import io.vaultix.common.logging.VaultixLog
import io.vaultix.datastore.VaultixPreferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 全 Activity 防截屏守卫。
 *
 * 用法：在 `VaultixApplication.onCreate()` 里调一次 [install]。
 *
 * 构造时机照 `AutoRestoreTrigger` / `KdbxCloudSyncInitializer` 的既有模式：
 * 由 `VaultixApplication` 用 `@Inject` 持有字段来保证进程早期实例化。
 */
@Singleton
class ScreenSecurityGuard @Inject constructor(
    private val preferences: VaultixPreferences,
) {

    /**
     * 开关的内存快照。
     *
     * ⚠️ 必须是 `@Volatile`：写它的协程跑在 Default 调度器、读它的回调跑在**主线程**，
     *    没有可见性保证的话用户关掉开关之后可能要等到下一次重新启动才生效。
     */
    @Volatile
    private var enabledSnapshot: Boolean = true

    /**
     * 注册生命周期回调并开始跟随偏好变化。幂等语义由调用方保证（只调一次）。
     *
     * @param application 应用实例；回调是进程级的，只需注册一次。
     */
    fun install(application: Application) {
        application.registerActivityLifecycleCallbacks(callbacks)
        // 进程级订阅：开关变了要能立刻反映到**之后启动**的每个 Activity。
        // 已经在屏上的 Activity 由 MainActivity 的 ScreenSecurityEffect 负责实时切换，
        // 其余 9 个都是短命的一次性流程（autofill / 凭据选择），下次进入已是新实例。
        preferencesScope.launch {
            preferences.screenSecurity
                .distinctUntilChanged()
                .collect { value ->
                    enabledSnapshot = value
                    VaultixLog.d(TAG) { "防截屏偏好变更 → enabled=$value" }
                }
        }
    }

    // 进程级 scope（与本包 AutoRestoreTrigger 同款取向；不混用 @CryptoDispatcher，
    // 那一个是 KDF/加解密的语义限定符）。
    @Suppress("InjectDispatcher")
    private val preferencesScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val callbacks = object : Application.ActivityLifecycleCallbacks {

        /**
         * API 29+ 才回调：跑在 `Activity.onCreate()` **之前**，是最早的时机。
         * 低版本静默不回调（由 [onActivityCreated] 覆盖），不影响正确性。
         */
        override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
            applyFlag(activity)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            applyFlag(activity)
        }

        /**
         * 回到前台也重设一次：覆盖"用户刚在系统层面清掉了 flag"或"开关刚变化时
         * 这个 Activity 恰好已在栈里"的情形。幂等且廉价（只是按位设置一个 flag）。
         */
        override fun onActivityResumed(activity: Activity) {
            applyFlag(activity)
        }

        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    }

    /** 按当前开关设置/清除 `FLAG_SECURE`（幂等）。 */
    private fun applyFlag(activity: Activity) {
        if (enabledSnapshot) {
            activity.window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private companion object {
        const val TAG = "VaultixScreenSecurity"
    }
}
