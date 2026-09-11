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
 */

package io.vaultix.vaultix.security

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    @Suppress("InjectDispatcher")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _lockEvents = MutableStateFlow(0)

    /** 锁定代次：UI 收集到新值即强制回到根路由（防状态穿透）。 */
    val lockEvents: StateFlow<Int> = _lockEvents.asStateFlow()

    init {
        // 锁定事件 → 代次自增（驱动导航回根）。
        lockManager.vaultStateEventFlow
            .onEach { event ->
                if (event is VaultStateEvent.Locked) {
                    _lockEvents.update { it + 1 }
                }
            }
            .launchIn(scope)
    }

    override fun onStop(owner: LifecycleOwner) {
        lockManager.onAppBackgrounded()
    }

    override fun onStart(owner: LifecycleOwner) {
        lockManager.onAppForegrounded()
    }

    /** 供「立即锁定」等入口直接调用（幂等）。 */
    fun lockAllNow() {
        lockManager.lockVaultForCurrentUser(isUserInitiated = true)
    }
}
