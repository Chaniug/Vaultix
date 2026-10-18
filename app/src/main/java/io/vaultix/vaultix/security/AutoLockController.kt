/*
 * Vaultix — app:security
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * 应用生命周期 → 锁定管理器的**唯一接线点**（进程前后台事件）。
 *
 * 2026-09-11 重写：本类原先自己承担「回前台算时间差」的判定逻辑，现降级为**极薄的适配器**——
 * 所有锁定时机决策已下沉到 [VaultLockManager]（对齐 Bitwarden 的做法：
 * `AppStateManager.appForegroundStateFlow` → `VaultLockManagerImpl.handleOnBackground/OnForeground`）。
 *
 * 保留 [lockEvents] 是为了向后兼容既有的导航回根机制
 * （`VaultShellViewModel.lockEpoch` ← 本流；UI 收到新值即把导航栈弹回根）。
 * 该计数器改由锁定事件驱动，语义与重写前一致。
 *
 * ## ⚠️ [lockAllNow] 目前是**生产零调用**的入口（如实记录，别当活代码用）
 *
 * 它唯一的调用方是 `SettingsViewModel.lockAllNow()`，而设置页**没有**再调后者
 * （`setting_lock_now` 文案零引用；`.ai/issues/04` #60 步 3 已把这一行换成「退出数据库」）。
 * ⇒ 这是一条**带单测的死链**。
 *
 * 留着而不是删：2026-10-18 修的是它**自己**的正确性（原来只锁活动库），
 * 而"要不要把它接回某个 UI"是产品决策、不是代码缺陷。
 * **也别因为"没人调"就删掉那条测试** —— 那会让这个缺陷在它被接回时无人知晓。
 */

package io.vaultix.vaultix.security

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 进程级生命周期观察者。
 *
 * 注册：`VaultixApplication.onCreate` 里
 * `ProcessLifecycleOwner.get().lifecycle.addObserver(autoLockController)`。
 *
 * 与 Bitwarden 的对应关系：
 * - `onStop`  → `VaultLockManager.onAppBackgrounded()`（→ 启动超时定时器）
 * - `onStart` → `VaultLockManager.onAppForegrounded()`（→ **仅取消**定时器）
 *
 * ⚠️ 不再有 `backgroundedAtMs` / `anyUnlocked` / `credentialFlowActive()` 这些字段：
 * 前者是「回前台算差值」模型的残留，后两者分别被 [VaultLockManager] 的
 * `CheckTimeoutReason` 与 `createdForAutofill` 结构性豁免取代。
 */
@Singleton
class AutoLockController @Inject constructor(
    private val lockManager: VaultLockManager,
) : DefaultLifecycleObserver {

    private val supervisorJob = SupervisorJob()

    /**
     * 本类后台协程的 scope。
     *
     * ⚠️ 必须是 **getter**：属性初始化按声明顺序求值，写成
     * `private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)` 时，
     * 它在构造那一刻就固化，测试后赋值的 [testingIoDispatcher] 追不上。
     * 每次读新建一个 `CoroutineScope` 包装，但 Job 只有一个（[supervisorJob]）。
     *
     * ⚠️⚠️ **别用自定义 `CoroutineDispatcher` 来做"延迟解析"**（2026-10-18 实测踩过）：
     * 试过 `dispatch` 时现查 `ioDispatcher` 的包装调度器，`delay` **永远醒不过来** ——
     * 虚拟时钟的恢复要求上下文里的 `CoroutineDispatcher` **就是** `TestDispatcher` 本身，
     * 中间套一层它就认不出来了。
     *
     * ⇒ 收集协程"构造期就派发到真线程池"这个真问题，是靠 [startCollecting] **显式启动**
     *   解决的（见其 KDoc），不是靠调度器包装。
     */
    @Suppress("InjectDispatcher")
    private val scope: CoroutineScope
        get() = CoroutineScope(supervisorJob + ioDispatcher)

    private val ioDispatcher: CoroutineDispatcher
        get() = testingIoDispatcher ?: Dispatchers.Default

    /**
     * 测试替身调度器（**生产恒 `null`**）。
     *
     * 为什么需要它：本类会把一个收集协程挂到 `vaultStateEventFlow` 上。若 scope 硬编码
     * `Dispatchers.Default`，测试就只能**真实时间轮询**「收集协程挂上了没有」
     * （`subscriptionCount > 0`）—— 纯等待，慢且带运气成分（第一版就是在 `emit`
     * 早于订阅时丢事件而假红）。注入 `StandardTestDispatcher(testScheduler)` 后，
     * 收集点直接落在虚拟时钟上，判定变成**确定性顺序**而非计时。
     *
     * ⚠️ 别改成构造参数/`@Inject`：`@HiltViewModel` 的校验插件会拒（`prohibited`），
     *    Dagger 会报 `MissingBinding`。**构造后赋值的 `internal var` 是实测唯一能过的路。**
     */
    @Volatile
    internal var testingIoDispatcher: CoroutineDispatcher? = null

    private val _lockEvents = MutableStateFlow(0)

    /** 锁定代次：UI 收集到新值即强制回到根路由（防状态穿透）。 */
    val lockEvents: StateFlow<Int> = _lockEvents.asStateFlow()

    private val _isForeground = MutableStateFlow(false)

    /**
     * 进程是否处于**前台**（`onStart` 之后到 `onStop` 之前）。
     *
     * ⚠️ **2026-09-29 二次定稿：当前没有生产消费者。**
     *
     * 上一轮方案 A 里它是「`AutoRestoreTrigger` 恢复的前台门禁」（保护离场软锁）。
     * 但 `Never` 档现已对齐 Bitwarden **取消离场软锁**，门禁失去保护对象，
     * 且会挡住 autofill 冷启动进程的恢复 ⇒ 已从 `AutoRestoreTrigger` 移除。
     *
     * ## 为什么留着字段而不是删掉
     *
     * 1. [onStop] 仍要以它配套地驱动 `lockManager.onAppBackgrounded()`
     *    （非 Never 档的「延迟 N 分钟锁定」全靠这个回调），前台态是它的天然伴生值，
     *    留着成本为零；
     * 2. 它是「进程前后台」这一事实的**唯一**单一来源；将来若要加
     *    「锁屏即锁」档或做前后台统计，不必重新推导。
     *
     * 初值刻意是 `false`：进程创建那一刻还没走完 `onStart`。首个真实 `onStart` 翻正。
     */
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    /**
     * 启动「锁定事件 → 代次」的收集（**幂等**）。
     *
     * ## ⚠️ 为什么不在 `init` 里做（2026-10-18 实测踩过，别搬回去）
     *
     * 原来是 `init { …launchIn(scope) }`。`launchIn` 会**立即** `dispatch` 一次，而那次
     * dispatch 发生在**构造期间** —— 那时 [testingIoDispatcher] 还没被赋值（它只能在
     * 构造后赋），于是这一发落到 `Dispatchers.Default` 上。此后 `runTest` 的虚拟时钟
     * **永远推不动它**：实测 `subscriptionCount` 恒为 0，6 条用例全红。
     *
     * ⇒ 启动时机交给调用方：
     * - **生产**：`VaultixApplication.onCreate` 里显式调一次（紧跟 Hilt 注入之后）；
     * - **测试**：先给 [testingIoDispatcher] 赋值，再调本方法 ⇒ 收集协程直接落在测试调度器上。
     *
     * 幂等：重复调用不会挂两个收集器（`collecting` 标记）。
     *
     * @return 收集用的 `Job`；重复调用返回同一个
     */
    fun startCollecting(): Job {
        collectingJob?.let { return it }
        val job = lockManager.vaultStateEventFlow
            .onEach { event ->
                if (event is VaultStateEvent.Locked) {
                    _lockEvents.update { it + 1 }
                }
            }
            .launchIn(scope)
        collectingJob = job
        return job
    }

    /** [startCollecting] 的幂等守卫（见其 KDoc）。 */
    @Volatile
    private var collectingJob: Job? = null

    override fun onStop(owner: LifecycleOwner) {
        _isForeground.value = false
        lockManager.onAppBackgrounded()
    }

    override fun onStart(owner: LifecycleOwner) {
        _isForeground.value = true
        lockManager.onAppForegrounded()
    }

    /**
     * 供「立即锁定」等入口直接调用（幂等）。
     *
     * ⚠️ 2026-10-18：原来转发 `lockManager.lockVaultForCurrentUser`，**只锁活动库**，
     * 而本方法名叫 `lockAllNow`、`setting_lock_now_desc` 写的也是"全部库" ——
     * 多库下第二个库会静默保持解锁。现转发 [VaultLockManager.lockAll]。
     */
    fun lockAllNow() {
        lockManager.lockAll(isUserInitiated = true)
    }
}
