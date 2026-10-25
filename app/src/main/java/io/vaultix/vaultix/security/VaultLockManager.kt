/*
 * Vaultix — app:security
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * 库锁定/解锁管理器。
 *
 * 逐句对齐 Bitwarden Android 官方客户端 `data/vault/manager/VaultLockManager.kt`
 * （GPL-3.0，Copyright Bitwarden Inc.），按 Vaultix 的单账号单库模型收敛 API。
 *
 * 设计要点（为什么这样才「标准」）：**锁定的时机决策集中在本管理器内**，
 * 由「后台时启动定时器 job、前台时取消它」这一对动作驱动；会话层
 * （[io.vaultix.data.repository.VaultSessionManager]）只负责执行「清密钥」，
 * 不感知生命周期。旧实现把时机判定散在 UI 层与 `AutoLockController` 里，
 * 任一处判定不一致就会产生「候选列出了但点进去说找不到」这类竞态。
 */

package io.vaultix.vaultix.security

import io.vaultix.datastore.VaultTimeout
import io.vaultix.domain.UnlockResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** 单个库的锁态。 */
data class VaultUnlockData(
    val vaultId: String,
    val status: Status,
) {
    /** 锁态取值（对齐 Bitwarden `VaultUnlockData.Status`）。 */
    enum class Status {
        /** 已解锁（密钥在内存，可读明文）。 */
        UNLOCKED,

        /** 正在解锁中（KDF / 解包进行中，此时不应判定为「找不到」）。 */
        UNLOCKING,
    }
}

/** 锁态变更事件（供 UI / 同步编排订阅）。 */
sealed class VaultStateEvent {
    /** 库已被锁定。 */
    data class Locked(val vaultId: String) : VaultStateEvent()

    /** 库已被解锁。 */
    data class Unlocked(val vaultId: String) : VaultStateEvent()
}

/**
 * 库锁定 / 解锁的唯一入口。
 *
 * 与 Bitwarden 的差异（刻意的精简，已在计划中确认）：
 * - Bitwarden 是多账号（`userId` 维度）；Vaultix 当前是「一服务器一账号」，故
 *   `userId` 即 `vaultId`，保留参数名以便将来扩展时不必改签名；
 * - Bitwarden 的 `VaultTimeoutAction` 含 `LOGOUT`（超时后登出）；Vaultix 只实现
 *   `LOCK`（本地密钥清零），因为 Vaultix 的「登出」需要联网撤销设备，不适合作为
 *   超时副作用。
 */
interface VaultLockManager {

    /** 各库当前锁态。 */
    val vaultUnlockDataStateFlow: StateFlow<List<VaultUnlockData>>

    /** 锁态变更事件流。 */
    val vaultStateEventFlow: Flow<VaultStateEvent>

    /** 是否正处于「解锁中」。 */
    val isActiveUserUnlockingFlow: StateFlow<Boolean>

    // ⚠️ 2026-09-29：`isFromLockFlow` 已**删除**（原「是否来自锁定流程」布尔）。
    //
    // ## 为什么删（不是遗漏，是清理）
    //
    // 它是抄 Bitwarden 时留下的**残骸**：
    // - **无消费方**：全项目（含测试）没有任何地方读它；
    // - **赋值写反**：原实现 `if (isUserInitiated) isFromLockFlow = false`，
    //   而 Bitwarden 原版是 `isFromLockFlow = isUserInitiated`；
    // - **功能已被取代**：Bitwarden 用它表达「主动锁 ⇒ 不弹指纹」，
    //   而 Vaultix 用**更细的** [io.vaultix.data.repository.VaultSessionRepository.isViewLocked]
    //   （会话层、**逐库**粒度）表达同一语义，且已完整落地（见 `UnlockViewModel` 的
    //   「查看层锁」分支 / `UnlockScreen` 的 `viewLocked` 渲染）。
    //
    // ⚠️ 别因为它"名字像 Bitwarden"就把它加回来 —— 加回来会与 `viewLocked` 形成
    //    **双源真值**，两者对「主动锁」的粒度与生命周期都不同。

    /** 指定库当前是否已解锁（同步快照）。 */
    fun isVaultUnlocked(vaultId: String): Boolean

    /** 指定库是否正在解锁中。 */
    fun isVaultUnlocking(vaultId: String): Boolean

    /** 锁定指定库。[isUserInitiated] 表示是否由用户主动触发（区别于超时自动锁定）。 */
    fun lockVault(vaultId: String, isUserInitiated: Boolean)

    /**
     * 锁定**全部**已解锁库（用户主动）。
     *
     * ⚠️ **2026-10-18 取代 `lockVaultForCurrentUser`**：那个方法只锁
     * `activeVaultId`（= 首个已注册库），而它唯一的调用方是
     * `AutoLockController.lockAllNow()`，对外承诺的是「**全部**库」。
     * 多库下第二、三个库会**静默保持解锁** —— 用户点了"锁定"，界面上却还能进去。
     * 这与 [VaultRepository.lockAll] 的语义不一致，也违背 D1「锁全=真锁」。
     *
     * ⇒ 直接转发到仓储的 [VaultRepository.lockAll]（房钥匙清零 + 删自动恢复信封），
     * 语义只有一处定义，本类不再自己实现一遍。
     */
    fun lockAll(isUserInitiated: Boolean)

    /** 解锁指定库（主密码 / 2FA / 本地快速解锁均走此处或其专用方法）。 */
    suspend fun unlockVault(vaultId: String, masterPassword: String): UnlockResult

    /** 挂起直到指定库解锁（供凭据流程等待解锁完成）。 */
    suspend fun waitUntilUnlocked(vaultId: String)

    /**
     * 开始观察仓储的库列表（活动库 + 锁态快照）。**幂等**，由 `VaultixApplication.onCreate` 调用。
     *
     * ## ⚠️ 为什么启动时机要交出来，而不是在构造时就自启（2026-10-18）
     *
     * 原实现在 `init { scope.launch { observeVaults().collect { … } } }` 里自启。那条协程：
     *
     * 1. 在**构造期**就派发，那时测试的调度器覆写口还没赋值 ⇒ 它落在
     *    `Dispatchers.Default` 上，虚拟时钟永远推不动；
     * 2. 是**无界 `collect`**（永不结束），`runTest` 判断"是否还有待办"会被它搅乱 ⇒
     *    单测出现"同一次构建里两条互斥断言同时红、且每次红的不是同一条"的随机结果。
     *
     * 把启动变成一个显式步骤后，测试可以「先注入调度器 → 再 start」，
     * 生产行为完全不变（`VaultixApplication.onCreate` 里紧跟 Hilt 注入调用）。
     *
     * 默认实现为空，是为了不逼着测试替身去实现它。
     */
    fun startObservingVaults() {
        // 默认无操作。
    }

    /**
     * 应用进入后台。
     *
     * 对齐 Bitwarden `handleOnBackground()`：触发一次超时检查，
     * 档位非 [VaultTimeout.Never] 时**启动定时器 job**。
     */
    fun onAppBackgrounded()

    /**
     * 应用回到前台。
     *
     * 对齐 Bitwarden `handleOnForeground()`：**只做一件事——取消定时器 job**。
     * 特别注意：这里**不**做任何「是否该锁」的判定。锁定与否完全由那个 job 自己
     * 在到点时执行；前台只负责把它撤销。这是本模型与旧实现最本质的区别
     * （旧实现是「前台时回头算时间差」，基准时刻会被各种事件干扰）。
     */
    fun onAppForegrounded()

    /**
     * 应用（进程）创建完成。
     *
     * 对齐 Bitwarden `handleOnCreated(createdForAutofill, isFirstCreated)`：
     * - [isFirstCreation] 为真（冷启动进程）→ 立即按档位执行超时动作；
     * - [createdForAutofill] 为真且**非**首次创建 → 视为「为 autofill / 凭据提供商
     *   而拉起进程」，**豁免** [VaultTimeout.OnAppRestart] 档位的锁定。
     *
     * 这条豁免就是「通行密钥流程不该把库锁掉」的**结构性**保障。
     */
    fun onAppCreated(isFirstCreation: Boolean, createdForAutofill: Boolean)

    /**
     * 屏幕点亮。
     *
     * 对齐 Bitwarden `ScreenStateBroadcastReceiver`（`ACTION_SCREEN_ON`）：熄屏期间
     * 协程 `delay()` 的计时会因 CPU 挂起而**欠账**，亮屏时按单调时钟
     * (`SystemClock.elapsedRealtime`) 重算每个定时器的剩余时长并重启它。
     *
     * 不是「可选优化」——没有它，长熄屏后的锁会**迟到**（详见实现类 KDoc 的举例）。
     */
    fun onScreenOn()

    /**
     * 屏幕熄灭（`ACTION_SCREEN_OFF`）。
     *
     * ## 为什么必须单独一条（#189，2026-10-26）
     *
     * 之前只有「亮屏补偿」，而锁定的**定时器是在切后台时启动的**。熄屏时 App 未必
     * 切到后台（前台 Activity 在熄屏时通常仍是 resumed，「后台」要等
     * `ProcessLifecycleOwner` 的 `onStop`），而 Doze / 应用待机下那个 `delay`
     * 根本不会准时唤醒 ⇒
     *
     * > 用户设「离开 1 分钟锁定」，熄屏 10 分钟，亮屏后仍有近 1 分钟窗口。
     *
     * 这正是用户报的「锁得不及时」。`ACTION_SCREEN_OFF` 是**熄屏那一刻**唯一可靠的信号：
     * 它一定送达（系统直接广播给已注册的接收器），且不依赖进程是否被冻结多久。
     * 所以在熄屏时按「已离开 App」处理一次 —— 与切后台同一套判定（`Never` / 
     * `OnAppRestart` 档照旧不锁，见实现类 `checkForVaultTimeoutInternal`）。
     *
     * ⚠️ 不要把它做成「熄屏立即锁」：那会把用户选择的档位彻底作废
     * （设 30 分钟的用户熄一次屏就要求指纹）。这里只做「**开始计时**」。
     */
    fun onScreenOff()

    /** 主动触发一次超时检查（如手动锁定前的统一入口）。 */
    fun checkForVaultTimeout(vaultId: String)
}
