/*
 * Vaultix — app:ui:rootnav
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * 根导航状态（决定"应用启动后第一屏是什么"）。
 *
 * 逐句对齐 Bitwarden Android 官方客户端
 * `ui/platform/feature/rootnav/RootNavViewModel.kt`（GPL-3.0，Copyright Bitwarden Inc.）：
 *
 * ```kotlin
 * val state = combine(...) { ... }
 * when {
 *     userState == null || !isLoggedIn -> RootNavState.VaultLocked
 *     isVaultUnlocked && specialCircumstance is SpecialCircumstance.Fido2Assertion ->
 *         RootNavState.VaultUnlockedForFido2Assertion(...)
 *     isVaultUnlocked -> RootNavState.VaultUnlockedGraph
 *     else -> RootNavState.VaultLocked
 * }
 * ```
 * 且 `RootNavScreen` 把 `RootNavState.VaultLocked` 映射到 **`VaultUnlockRoute.Standard`**
 * （一个解锁界面，**绝不是错误页**）。
 *
 * 为什么必须集中在这里：旧实现把"库是否锁定"的判定散落在三处
 * （CP Service 的 lockedCount、PasskeyGetActivity 的 sessions.isUnlocked、
 * AutoLockController 的前台判定）。任一处不一致就会出现「候选列出了但点进去说找不到」
 * 这类自相矛盾的竞态。集中到一个状态源之后，三者读的是同一个结论。
 */

package io.vaultix.vaultix.ui.rootnav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.vaultix.domain.VaultRepository
import io.vaultix.domain.VaultSessionRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * 根导航状态（对齐 Bitwarden `RootNavState`）。
 */
sealed class RootNavState {

    /**
     * 首帧占位：库列表的首个结果尚未到达（数据库查询未完成）。
     *
     * ⚠️ 必须存在：没有它，首帧就只能"猜"一个状态，而首帧猜错会被
     * `NavHost.startDestination` 固化（见 [io.vaultix.vaultix.ui.VaultixApp]）——
     * 全新安装（一个库都没有）时会被猜成"锁定"，直接落到解锁页，
     * 而解锁页没有库可解锁 → 永久转圈（2026-09-12 修复的启动死锁）。
     */
    data object Splash : RootNavState()

    /**
     * 一个库都没有 → 首次使用，去库列表引导添加。
     *
     * ⚠️ 与 [VaultLocked] 必须分开：此时没有任何库可解锁，把用户扔在解锁页
     * 会卡死（`UnlockViewModel` 解析不出 vaultId → 无限 loading）。
     * Bitwarden 用 `isLoggedIn` 区分，Vaultix 用「有没有库」区分。
     */
    data object Onboarding : RootNavState()

    /**
     * 库处于锁定态（**有库**且全部锁定） → 显示**解锁界面**。
     *
     * ⚠️ 注意这不是"错误状态"：Bitwarden 的 `RootNavScreen` 把它映射到
     * `VaultUnlockRoute.Standard`，用户看到的是主密码输入页。
     *
     * [vaultId] 非空时表示这是**查看层锁**（ViewLocked，密钥仍在内存）：
     * 解锁页只需一次生物识别即可回来，[io.vaultix.vaultix.ui.unlock.UnlockScreen]
     * 据此走「仅认证」分支。
     */
    data class VaultLocked(val vaultId: String? = null) : RootNavState()

    /** 库已解锁 → 进入主功能图（库列表 / 条目 / 设置…）。 */
    data object VaultUnlockedGraph : RootNavState()
}

@HiltViewModel
class RootNavViewModel @Inject constructor(
    vaultRepository: VaultRepository,
    sessionRepository: VaultSessionRepository,
) : ViewModel() {

    /**
     * 根导航状态。
     *
     * 判定顺序（照抄 Bitwarden，并按 Vaultix 的「有没有库」语义补两层）：
     * **首帧未到 → Splash；有库但查看层被锁 → 锁定态（携带库 id）；任一已解锁 → 解锁图；
     * 无库 → 首次使用；其余（有库但全锁）→ 锁定态。**
     *
     * ⚠️ 查看层锁必须排在「已解锁」**之前**：查看锁的库在会话层面仍是解锁的
     * （密钥在内存、autofill 与凭据提供商照常可用），若按解锁图处理，
     * 用户按了主页锁按钮却什么都没发生 —— 那正是这一步要修的行为。
     *
     * 与 Bitwarden 的差异：Bitwarden 多一层 `isLoggedIn`（账号是否已登录）与
     * `SpecialCircumstance.Fido2Assertion` 专项路由；Vaultix 目前是单账号模型，
     * 「有没有库」即等价于「是否已登录」，故收敛为一条判定。
     */
    val rootNavState: StateFlow<RootNavState> = combine(
        vaultRepository.observeVaults(),
        vaultRepository.observeUnlockedVaultIds(),
        sessionRepository.observeViewLockedVaultIds(),
    ) { vaults, unlockedIds, viewLockedIds ->
        val viewLocked = viewLockedIds.firstOrNull { id -> vaults.any { it.id == id } }
        when {
            // 查看层锁：界面收回解锁页，但密钥仍在 → 解锁页只做一次认证。
            viewLocked != null -> RootNavState.VaultLocked(viewLocked)
            // 双源交叉校验：`VaultSummary.unlocked` 与 `unlockedIds` 任一为真即视为已解锁。
            // 二者同源（都来自会话表），差异只可能是极短的时间窗；取"或"可避免
            // 把内存中确有密钥的会话误判成锁定（从而误弹解锁页）。
            unlockedIds.isNotEmpty() || vaults.any { it.unlocked } -> RootNavState.VaultUnlockedGraph
            // 一个库都没有：首次使用，不是"锁定"（锁定态必须有可解锁的库）
            vaults.isEmpty() -> RootNavState.Onboarding
            else -> RootNavState.VaultLocked()
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = RootNavState.Splash,
    )
}
