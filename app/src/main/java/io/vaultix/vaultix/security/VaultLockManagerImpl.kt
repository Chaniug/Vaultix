/*
 * Vaultix — app:security
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * [VaultLockManager] 的主实现。
 *
 * 逐句对齐 Bitwarden Android 官方客户端 `data/vault/manager/VaultLockManagerImpl.kt`
 * （GPL-3.0，Copyright Bitwarden Inc.）中的超时部分：
 *   - `observeAppForegroundChanges()`  → [onAppBackgrounded] / [onAppForegrounded]
 *   - `handleOnBackground()`           → 触发超时检查
 *   - `handleOnForeground()`           → **仅取消定时器 job**
 *   - `checkForVaultTimeout()`         → 按档位与「原因」分流
 *   - `handleTimeoutActionWithDelay()` → 启动 `delay()` job
 *   - `handleTimeoutAction()`          → 执行锁定
 *   - `CheckTimeoutReason`             → 三类原因（见文件末尾）
 *
 * ⚠️ 与 Bitwarden 的已知差异（刻意的）：
 *  1. Bitwarden 用 SDK 的 `WrappedAccountCryptographicState` 解锁；Vaultix 走自己的
 *     `BitwardenAuthRepository`（`VaultRepositoryImpl.doUnlock`），故 [unlockVault]
 *     委托给 [VaultRepository] 而不是 SDK；
 *  2. Bitwarden 有 `LOGOUT` 超时动作；Vaultix 只实现 `LOCK`；
 *  3. Bitwarden 的定时器在 `unconfinedScope` 上跑（它需要即时性）；Vaultix 用
 *     注入的 `@ApplicationScope` 语义的独立 scope，避免依赖 `Dispatchers.Unconfined`。
 */

package io.vaultix.vaultix.security

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.vaultix.datastore.VaultTimeout
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.domain.UnlockResult
import io.vaultix.domain.VaultRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** 运行中的超时定时器（对齐 Bitwarden `TimeoutJobData`）。 */
private data class TimeoutJobData(
    val job: Job,
    val startTimeMs: Long,
    val durationMs: Long,
)

@Singleton
class VaultLockManagerImpl @Inject constructor(
    @ApplicationContext context: Context,
    private val vaultRepository: VaultRepository,
    private val preferences: VaultixPreferences,
) : VaultLockManager {

    // 进程级短任务 scope（启动/取消定时器）。项目当前唯一的调度器限定符是
    // @CryptoDispatcher（语义 = KDF/加解密 CPU 密集），用在这里会混淆语义。
    @Suppress("InjectDispatcher")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 当前活动库 id（Vaultix 一服务器一账号，取首个已注册的库）。 */
    @Volatile
    private var activeVaultId: String? = null

    /** 每个库的运行中定时器（对齐 Bitwarden `userIdTimerJobMap`）。 */
    private val timerJobMap = mutableMapOf<String, TimeoutJobData>()

    private val _vaultUnlockDataStateFlow = MutableStateFlow<List<VaultUnlockData>>(emptyList())
    override val vaultUnlockDataStateFlow: StateFlow<List<VaultUnlockData>> =
        _vaultUnlockDataStateFlow.asStateFlow()

    private val _vaultStateEventFlow = MutableSharedFlow<VaultStateEvent>(extraBufferCapacity = 16)
    override val vaultStateEventFlow: Flow<VaultStateEvent> = _vaultStateEventFlow.asSharedFlow()

    private val _isActiveUserUnlockingFlow = MutableStateFlow(false)
    override val isActiveUserUnlockingFlow: StateFlow<Boolean> = _isActiveUserUnlockingFlow.asStateFlow()

    @Volatile
    override var isFromLockFlow: Boolean = false

    init {
        scope.launch {
            vaultRepository.observeVaults().collect { vaults ->
                activeVaultId = vaults.firstOrNull()?.id
                // 锁态快照：把仓储的 unlocked 布尔映射为本管理器的三态模型。
                // 注意 UNLOCKING 由 _isActiveUserUnlockingFlow 单独表达，这里只发已知的
                // UNLOCKED 库；未解锁的库**不出现在列表中**（对齐 Bitwarden
                // `vaultUnlockDataStateFlow` 只含已解锁/解锁中的用户）。
                _vaultUnlockDataStateFlow.value = vaults
                    .filter { it.unlocked }
                    .map { VaultUnlockData(it.id, VaultUnlockData.Status.UNLOCKED) }
            }
        }
    }

    override fun isVaultUnlocked(vaultId: String): Boolean =
        _vaultUnlockDataStateFlow.value.any {
            it.vaultId == vaultId && it.status == VaultUnlockData.Status.UNLOCKED
        }

    override fun isVaultUnlocking(vaultId: String): Boolean = _isActiveUserUnlockingFlow.value

    override fun lockVault(vaultId: String, isUserInitiated: Boolean) {
        // 用户主动锁定：立即取消任何在跑的定时器（对齐 Bitwarden `lockVault` 先清 job）。
        cancelTimer(vaultId)
        if (isUserInitiated) isFromLockFlow = false
        setVaultToLocked(vaultId)
    }

    override fun lockVaultForCurrentUser(isUserInitiated: Boolean) {
        val vaultId = activeVaultId ?: return
        lockVault(vaultId, isUserInitiated)
    }

    override suspend fun unlockVault(vaultId: String, masterPassword: String): UnlockResult {
        _isActiveUserUnlockingFlow.value = true
        return try {
            val result = vaultRepository.unlockVault(vaultId, masterPassword)
            if (result is UnlockResult.Success) {
                isFromLockFlow = false
                _vaultStateEventFlow.tryEmit(VaultStateEvent.Unlocked(vaultId))
            }
            result
        } finally {
            _isActiveUserUnlockingFlow.value = false
        }
    }

    override suspend fun waitUntilUnlocked(vaultId: String) {
        if (isVaultUnlocked(vaultId)) return
        vaultUnlockDataStateFlow.first { list ->
            list.any { it.vaultId == vaultId && it.status == VaultUnlockData.Status.UNLOCKED }
        }
    }

    // ===================== 生命周期驱动（本文件的核心） =====================

    override fun onAppBackgrounded() {
        val vaultId = activeVaultId ?: return
        checkForVaultTimeoutInternal(vaultId, CheckTimeoutReason.AppBackgrounded)
    }

    override fun onAppForegrounded() {
        val vaultId = activeVaultId ?: return
        // ⚠️ **只做这一件事**（对齐 Bitwarden `handleOnForeground`）：
        //     val userId = activeUserId ?: return
        //     userIdTimerJobMap.remove(key = userId)?.job?.cancel()
        // 不判断「是否超时」——超时由 job 自己在到点后执行；前台只是撤销它。
        cancelTimer(vaultId)
    }

    override fun onAppCreated(isFirstCreation: Boolean, createdForAutofill: Boolean) {
        val vaultId = activeVaultId ?: return
        checkForVaultTimeoutInternal(
            vaultId,
            CheckTimeoutReason.AppCreated(
                firstTimeCreation = isFirstCreation,
                createdForAutofill = createdForAutofill,
            ),
        )
    }

    override fun checkForVaultTimeout(vaultId: String) {
        checkForVaultTimeoutInternal(vaultId, CheckTimeoutReason.UserChanged)
    }

    /**
     * 超时检查（对齐 Bitwarden `checkForVaultTimeout`）。
     *
     * 分流规则**逐条照抄**：
     * - `Never` → 直接返回，任何原因都不锁；
     * - `OnAppRestart` → **只在** `AppCreated` 时触发；且 `createdForAutofill == true`
     *   且**非**首次创建时**豁免**（为 autofill/凭据流程拉起进程不该锁库）；
     * - 其它档位 → `AppCreated(firstTimeCreation = true)` 立即执行；
     *   `AppBackgrounded` / `UserChanged` 走「延迟 N 分钟后执行」。
     */
    private fun checkForVaultTimeoutInternal(vaultId: String, reason: CheckTimeoutReason) {
        scope.launch {
            val timeout = preferences.vaultTimeout.first()

            when (timeout) {
                VaultTimeout.Never -> return@launch

                VaultTimeout.OnAppRestart -> {
                    // 仅「进程创建」这一原因会触发；切后台不锁。
                    if (reason is CheckTimeoutReason.AppCreated) {
                        // 首次创建必查；非首次创建时，若本次是为 autofill 拉起 → 跳过。
                        if (reason.firstTimeCreation || !reason.createdForAutofill) {
                            handleTimeoutAction(vaultId)
                        }
                    }
                }

                else -> when (reason) {
                    is CheckTimeoutReason.AppCreated -> {
                        // 冷启动（首次创建）一律按档位执行，保证用户看到的是正确状态；
                        // 非首次创建（如 autofill 拉起）不在此路径处理，避免误锁。
                        if (reason.firstTimeCreation) {
                            handleTimeoutAction(vaultId)
                        }
                    }

                    CheckTimeoutReason.AppBackgrounded,
                    CheckTimeoutReason.UserChanged,
                    -> {
                        val minutes = timeout.vaultTimeoutInMinutes ?: 0
                        handleTimeoutActionWithDelay(
                            vaultId = vaultId,
                            delayMs = minutes.coerceAtLeast(0).toLong() * MS_PER_MINUTE,
                        )
                    }
                }
            }
        }
    }

    /**
     * 延迟执行超时动作（对齐 Bitwarden `handleTimeoutActionWithDelay`）。
     *
     * 先取消同库旧 job（避免同时刻存在多个定时器），再启动新的。
     */
    private fun handleTimeoutActionWithDelay(vaultId: String, delayMs: Long) {
        cancelTimer(vaultId)
        val job = scope.launch {
            delay(timeMillis = delayMs)
            timerJobMap.remove(vaultId)
            handleTimeoutAction(vaultId)
        }
        timerJobMap[vaultId] = TimeoutJobData(
            job = job,
            startTimeMs = System.currentTimeMillis(),
            durationMs = delayMs,
        )
    }

    /** 执行锁定（对齐 Bitwarden `handleTimeoutAction` 的 `LOCK` 分支）。 */
    private fun handleTimeoutAction(vaultId: String) {
        setVaultToLocked(vaultId)
    }

    /** 取消并移除指定库的定时器。 */
    private fun cancelTimer(vaultId: String) {
        timerJobMap.remove(vaultId)?.job?.cancel()
    }

    /**
     * 立即锁定（对齐 Bitwarden `setVaultToLocked`）。
     *
     * 会话密钥清零由仓储层执行；本管理器只负责发事件。
     */
    private fun setVaultToLocked(vaultId: String) {
        scope.launch {
            vaultRepository.lockVault(vaultId)
            _vaultStateEventFlow.tryEmit(VaultStateEvent.Locked(vaultId))
        }
    }

    /**
     * 超时触发原因（对齐 Bitwarden `CheckTimeoutReason`）。
     *
     * 三个原因对应三类不同的处置：
     * - [AppBackgrounded]：用户离开 App → 启动定时器；
     * - [AppCreated]：进程创建 → 冷启动立即执行；为 autofill 创建时可豁免；
     * - [UserChanged]：账号切换 → 按离开处理（启动定时器）。
     */
    private sealed class CheckTimeoutReason {
        /** 应用进入后台但仍存活。 */
        data object AppBackgrounded : CheckTimeoutReason()

        /**
         * 应用进程创建完成。
         *
         * @param firstTimeCreation 是否为进程的**首次**创建（冷启动）；
         * @param createdForAutofill 本次创建是否由 autofill / 凭据提供商拉起。
         */
        data class AppCreated(
            val firstTimeCreation: Boolean,
            val createdForAutofill: Boolean,
        ) : CheckTimeoutReason()

        /** 活动账号发生变更。 */
        data object UserChanged : CheckTimeoutReason()
    }

    private companion object {
        const val MS_PER_MINUTE = 60_000L
    }
}
