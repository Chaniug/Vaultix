/*
 * Vaultix — app:session
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 语义参考 Bastion 项目（GPL-3.0，Copyright 2025 JoyinJoester）的
 * `KEY_ACTIVE_VAULT_ID`（单一活跃库 id，落 Settings 持久化），此处按 Vaultix 架构重写：
 *   - 载体从 Bastion 的「SharedPreferences + SettingsManager 强耦合」改为
 *     **Hilt 单例 + StateFlow**（模块化可注入，autofill / CP 层同样可消费）；
 *   - 持久化复用 Vaultix 已存在的 `VaultixPreferences.defaultVaultId`；
 *   - **不搬** Bastion 的 `UnifiedCategoryFilterSelection`：那是「多后端聚合」的产物，
 *     Vaultix 按「登录时只能进一样」的设计只保留**单个活跃库**（见 Docs/progress/
 *     main-shell-migration.md §0）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.session

import io.vaultix.datastore.VaultixPreferences
import io.vaultix.domain.VaultRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * **全局唯一活跃库**（Docs/progress/main-shell-migration.md 阶段 2「★ 活跃库状态载体」）。
 *
 * 语义：**同一时刻只有一个活跃库**（互斥），因为 Vaultix 的产品定义就是
 * 「Bitwarden 能力 + KDBX 能力，登录时二选一」——多库同时活跃会导致
 * 自动填充候选重复、保存回写目标不确定（用户原话「条目就不会错乱和保存重复」）。
 *
 * 取值优先级：
 * 1. 持久化的活跃库 id **且该库当前仍解锁** → 沿用（对齐 Bastion `KEY_ACTIVE_VAULT_ID`）；
 * 2. 否则退化为「当前唯一已解锁的库」（单活跃库语义下的自然结论）；
 * 3. 全部锁定 → `null`（此时 UI 不该停留在主界面，由 `RootNavState` 收回到解锁页）。
 *
 * ⚠️ 本类是**真源**：主界面各 Tab、以及后续 autofill / Credential Provider 的
 * 候选来源与保存回写目标，都应从这里取，而不是各自遍历「所有已解锁库」。
 */
@Singleton
class ActiveVaultStore @Inject constructor(
    private val preferences: VaultixPreferences,
    private val vaultRepository: VaultRepository,
) {

    // 进程级 scope（持久化写入 + 会话流订阅）。与 VaultLockManagerImpl 同款写法：
    // 项目唯一的调度器限定符是 @CryptoDispatcher（KDF/加解密语义），用在此处会混淆语义。
    @Suppress("InjectDispatcher")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _activeVaultId = MutableStateFlow<String?>(null)

    /** 当前活跃库 id；全锁 / 尚未解锁时为 `null`。 */
    val activeVaultId: StateFlow<String?> = _activeVaultId.asStateFlow()

    init {
        scope.launch {
            combine(
                preferences.defaultVaultId,
                vaultRepository.observeUnlockedVaultIds(),
            ) { saved, unlocked -> pick(saved, unlocked) }
                .distinctUntilChanged()
                .collect { _activeVaultId.value = it }
        }
    }

    /**
     * **权威解析**当前活跃库 id（挂起、每次真实重算，并回写 [activeVaultId]）。
     *
     * ⚠️ 为什么 autofill / Credential Provider 必须用它、而不是直接读 [activeVaultId]：
     * [activeVaultId] 由 `init` 中的协程**异步**填充。冷启动时系统可能只拉起了
     * `VaultixAutofillService` / `VaultixCredentialProviderService`（主界面从未打开过），
     * 此时首帧可能还没到 → 读到 `null`。若「读到 null 就退化成遍历所有已解锁库」，
     * 就会重新引入本类存在的意义所要消灭的问题（候选重复 / 回写目标不确定）。
     * 所以这些场景必须**等一次真实计算**。
     */
    suspend fun resolve(): String? {
        val resolved = pick(preferences.defaultVaultId.first(), vaultRepository.observeUnlockedVaultIds().first())
        _activeVaultId.value = resolved
        return resolved
    }

    /** 取值规则单一实现（`init` 的流与 [resolve] 共用，避免两处口径漂移）。 */
    private fun pick(saved: String?, unlocked: Set<String>): String? = when {
        saved != null && saved in unlocked -> saved
        unlocked.isEmpty() -> null
        // Set 无序：按字典序取最小 id 保证「唯一已解锁库」结论稳定可复现
        else -> unlocked.minOrNull()
    }

    /** 切换 / 设定活跃库（互斥语义：直接覆盖，不同时存在第二个）。 */
    fun select(vaultId: String) {
        if (vaultId.isBlank()) return
        _activeVaultId.value = vaultId
        scope.launch { preferences.setDefaultVaultId(vaultId) }
    }

    /** 当前活跃库（同步读取，供 ViewModel 构造期解析 vaultId 用）。 */
    fun current(): String? = _activeVaultId.value
}
