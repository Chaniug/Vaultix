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

    /** 是否来自「因锁定而跳转解锁」的流程（供导航回退判定）。 */
    var isFromLockFlow: Boolean

    /** 指定库当前是否已解锁（同步快照）。 */
    fun isVaultUnlocked(vaultId: String): Boolean

    /** 指定库是否正在解锁中。 */
    fun isVaultUnlocking(vaultId: String): Boolean

    /** 锁定指定库。[isUserInitiated] 表示是否由用户主动触发（区别于超时自动锁定）。 */
    fun lockVault(vaultId: String, isUserInitiated: Boolean)

    /** 锁定当前活动库（若有）。 */
    fun lockVaultForCurrentUser(isUserInitiated: Boolean)

    /** 解锁指定库（主密码 / 2FA / 本地快速解锁均走此处或其专用方法）。 */
    suspend fun unlockVault(vaultId: String, masterPassword: String): UnlockResult

    /** 挂起直到指定库解锁（供凭据流程等待解锁完成）。 */
    suspend fun waitUntilUnlocked(vaultId: String)

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

    /** 主动触发一次超时检查（如手动锁定前的统一入口）。 */
    fun checkForVaultTimeout(vaultId: String)
}
