/*
 * Vaultix — data:repository
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 编排语义（触发分类/静默、节流窗口、运行中合并优先级、指数退避重试、per-vault
 * 状态流）移植自 Bastion 项目（GPL-3.0，Copyright 2025 JoyinJoester）的
 * bitwarden/sync/BitwardenSyncOrchestrator.kt；本文件按 Vaultix 的 domain 接口
 * （VaultRepository.syncVault）与单进程模型裁剪重写，不包含其代码实体。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.repository

import io.vaultix.domain.SyncTrigger
import io.vaultix.domain.VaultRepository
import io.vaultix.domain.VaultSyncReport
import io.vaultix.domain.VaultSyncStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * Bitwarden 同步编排（Bastion SyncOrchestrator 语义的 Vaultix 裁剪版）。
 *
 * - 触发：[SyncTrigger]（手动/进页/回前台/周期/重试），非手动 = 静默同步；
 * - 门卫：库未解锁（自动触发时）→ 静默跳过，不产生噪音状态；
 * - 节流：PAGE_ENTER 90s / APP_RESUME 180s（静默自动触发不反复打 revision 预检）；
 * - 运行中合并：同库并发请求按优先级合并，跑完自动回放；
 * - 失败退避：指数 5s→15min 上限 5 次（RETRY 强制）；
 * - 状态：per-vault [VaultSyncStatus] StateFlow，UI 全量订阅；
 * - 执行：复用 [VaultRepository.syncVault]（revision 预检/空库保护在 data 层已保证）。
 *
 * 与「本地编辑即时 flush」不冲突：flushPending 推送脏队列是保存路径的一部分，
 * 编排器只管「拉取同步」的节流/重试/状态。
 */
@Singleton
class BitwardenSyncOrchestrator {
    private val vaultRepository: VaultRepository
    private val sessions: VaultSessionManager
    private val scope: CoroutineScope
    private val config: Config
    private val now: () -> Long

    /**
     * 完整构造：scope/config/now 仅供测试注入（虚拟时间/虚拟调度器），
     * internal 防止生产误用（同模块测试可直接调用）。
     */
    internal constructor(
        vaultRepository: VaultRepository,
        sessions: VaultSessionManager,
        scope: CoroutineScope,
        config: Config,
        now: () -> Long,
    ) {
        this.vaultRepository = vaultRepository
        this.sessions = sessions
        this.scope = scope
        this.config = config
        this.now = now
    }

    /** Hilt 生产构造：scope 取进程级 SupervisorJob，策略用默认 Config。 */
    @Inject
    constructor(
        vaultRepository: VaultRepository,
        sessions: VaultSessionManager,
    ) : this(
        vaultRepository,
        sessions,
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        Config(),
        { System.currentTimeMillis() },
    )

    data class Config(
        val pageEnterThrottleMs: Long = PAGE_ENTER_THROTTLE_MS,
        val appResumeThrottleMs: Long = APP_RESUME_THROTTLE_MS,
        val retryBaseDelayMs: Long = RETRY_BASE_DELAY_MS,
        val retryMaxDelayMs: Long = RETRY_MAX_DELAY_MS,
        val retryMaxAttempts: Int = RETRY_MAX_ATTEMPTS,
    )

    private val mutex = Mutex()
    private val runtimes = mutableMapOf<String, Runtime>()
    private val status = mutableMapOf<String, VaultSyncStatus>()

    private val _statusByVault = MutableStateFlow<Map<String, VaultSyncStatus>>(emptyMap())
    val statusByVault: StateFlow<Map<String, VaultSyncStatus>> = _statusByVault.asStateFlow()

    fun statusOf(vaultId: String): VaultSyncStatus =
        _statusByVault.value[vaultId] ?: VaultSyncStatus()

    /** 请求一次同步；force = 手动语义（跳过节流/门卫中的静默约束）。 */
    fun requestSync(vaultId: String, trigger: SyncTrigger, force: Boolean = false) {
        scope.launch { process(vaultId, trigger, force) }
    }

    fun clearVault(vaultId: String) {
        scope.launch {
            mutex.withLock {
                runtimes.remove(vaultId)?.cancel()
                status.remove(vaultId)
                publish()
            }
        }
    }

    companion object {
        // 节流/退避参数与触发优先级（@suppress MagicNumber：数值为策略常量）
        @Suppress("MagicNumber")
        private const val PAGE_ENTER_THROTTLE_MS = 90_000L
        private const val APP_RESUME_THROTTLE_MS = 180_000L
        private const val RETRY_BASE_DELAY_MS = 5_000L
        private const val RETRY_MAX_DELAY_MS = 15 * 60_000L
        private const val RETRY_MAX_ATTEMPTS = 5
        private const val PRIORITY_MANUAL = 6
        private const val PRIORITY_RETRY = 4
        private const val PRIORITY_APP_RESUME = 3
        private const val PRIORITY_PAGE_ENTER = 2
        private const val PRIORITY_PERIODIC = 1
    }

    /**
     * @suppress TooGenericExceptionCaught：执行侧未知异常统一按可重试处理
     * （与 Bastion 编排一致）；CancellationException/ThreadDeath 已显式透传。
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun process(vaultId: String, trigger: SyncTrigger, force: Boolean) {
        mutex.withLock {
            val rt = runtimeOf(vaultId)

            if (force) {
                rt.delayedJob?.cancel()
                rt.delayedJob = null
                rt.pendingReason = null
            }

            if (rt.isRunning) {
                // 合并：高优先级覆盖低优先级，跑完回放
                rt.pendingReason = higherPriority(rt.pendingReason, trigger)
                return
            }

            if (!force && trigger != SyncTrigger.MANUAL) {
                // UI 自动触发（进页/回前台）：库未解锁 → 静默跳过（不产生状态噪音）；
                // PERIODIC/RETRY 由后台调用方以 force=true 发起，不受此限
                if (trigger == SyncTrigger.PAGE_ENTER || trigger == SyncTrigger.APP_RESUME) {
                    if (!sessions.isUnlocked(vaultId)) return
                }
                if (!passesThrottle(rt, trigger)) return
            }

            rt.isRunning = true
            updateStatus(vaultId) {
                it.copy(
                    isRunning = true,
                    trigger = trigger,
                    isSilent = trigger != SyncTrigger.MANUAL,
                    lastError = null,
                )
            }
        }

        val outcome = try {
            // 不额外切线程：syncVault 内部经 Room/Retrofit 挂起点自行调度，
            // 直接调用使测试可用虚拟时间推进（生产运行在注入 scope 上）
            vaultRepository.syncVault(vaultId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            VaultSyncReport.Retryable(error.message ?: "同步失败")
        }

        val replay = mutex.withLock {
            val rt = runtimeOf(vaultId)
            rt.isRunning = false
            settleOutcomeLocked(vaultId, rt, outcome)
        }

        if (replay != null) {
            requestSync(vaultId, replay, force = true)
        }
    }

    /**
     * 结算一次执行结果并返回需要回放的合并请求（mutex 内调用）。
     * 独立成函数以控制 process 的圈复杂度（Docs/16）。
     */
    private fun settleOutcomeLocked(
        vaultId: String,
        rt: Runtime,
        outcome: VaultSyncReport,
    ): SyncTrigger? {
        when (outcome) {
            is VaultSyncReport.Success -> {
                rt.retryAttempt = 0
                updateStatus(vaultId) {
                    it.copy(
                        isRunning = false,
                        lastSuccessAt = now(),
                        lastSuccessCipherCount = outcome.cipherCount,
                        lastErrorAt = null,
                        lastError = null,
                        retryAttempt = 0,
                        nextRetryAt = null,
                    )
                }
            }
            VaultSyncReport.Skipped -> {
                rt.retryAttempt = 0
                updateStatus(vaultId) {
                    it.copy(
                        isRunning = false,
                        lastSuccessAt = now(),
                        lastErrorAt = null,
                        lastError = null,
                        retryAttempt = 0,
                        nextRetryAt = null,
                    )
                }
            }
            is VaultSyncReport.Retryable -> {
                scheduleRetryLocked(vaultId, rt, outcome.reason)
            }
            is VaultSyncReport.Blocked -> {
                updateStatus(vaultId) { it.copy(isRunning = false, lastError = outcome.reason) }
            }
            is VaultSyncReport.Fatal -> {
                updateStatus(vaultId) { it.copy(isRunning = false, lastError = outcome.reason) }
            }
            VaultSyncReport.Unsupported -> {
                updateStatus(vaultId) { it.copy(isRunning = false) }
            }
        }
        val replay = rt.pendingReason
        rt.pendingReason = null
        return replay
    }

    private fun scheduleRetryLocked(vaultId: String, rt: Runtime, message: String) {
        if (rt.retryAttempt >= config.retryMaxAttempts) {
            updateStatus(vaultId) { it.copy(isRunning = false, lastError = message) }
            return
        }
        rt.retryAttempt += 1
        val delayMs = min(
            config.retryMaxDelayMs,
            config.retryBaseDelayMs * (1L shl (rt.retryAttempt - 1)),
        )
        rt.delayedJob?.cancel()
        rt.delayedJob = scope.launch {
            delay(delayMs)
            requestSync(vaultId, SyncTrigger.RETRY, force = true)
        }
        updateStatus(vaultId) {
            it.copy(
                isRunning = false,
                lastError = message,
                retryAttempt = rt.retryAttempt,
                nextRetryAt = now() + delayMs,
            )
        }
    }

    private fun passesThrottle(rt: Runtime, trigger: SyncTrigger): Boolean {
        val now = now()
        return when (trigger) {
            SyncTrigger.PAGE_ENTER -> {
                if (now - rt.lastPageEnterAt < config.pageEnterThrottleMs) false
                else {
                    rt.lastPageEnterAt = now
                    true
                }
            }
            SyncTrigger.APP_RESUME -> {
                if (now - rt.lastAppResumeAt < config.appResumeThrottleMs) false
                else {
                    rt.lastAppResumeAt = now
                    true
                }
            }
            else -> true
        }
    }

    private fun higherPriority(current: SyncTrigger?, incoming: SyncTrigger): SyncTrigger {
        if (current == null) return incoming
        return if (priorityOf(incoming) >= priorityOf(current)) incoming else current
    }

    private fun priorityOf(trigger: SyncTrigger): Int = when (trigger) {
        SyncTrigger.MANUAL -> PRIORITY_MANUAL
        SyncTrigger.RETRY -> PRIORITY_RETRY
        SyncTrigger.APP_RESUME -> PRIORITY_APP_RESUME
        SyncTrigger.PAGE_ENTER -> PRIORITY_PAGE_ENTER
        SyncTrigger.PERIODIC -> PRIORITY_PERIODIC
    }

    private fun runtimeOf(vaultId: String): Runtime =
        runtimes.getOrPut(vaultId) { Runtime() }

    private fun updateStatus(vaultId: String, transform: (VaultSyncStatus) -> VaultSyncStatus) {
        status[vaultId] = transform(status[vaultId] ?: VaultSyncStatus())
        publish()
    }

    private fun publish() {
        _statusByVault.value = status.toMap()
    }

    private class Runtime {
        var isRunning: Boolean = false
        var pendingReason: SyncTrigger? = null
        var lastPageEnterAt: Long = 0L
        var lastAppResumeAt: Long = 0L
        var retryAttempt: Int = 0
        var delayedJob: Job? = null

        fun cancel() {
            delayedJob?.cancel()
        }
    }
}
