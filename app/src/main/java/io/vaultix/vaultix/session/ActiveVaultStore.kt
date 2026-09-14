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
 *
 * ⚠️ **两个键的分工**（2026-09-14 拆分，见 `.ai/decisions/库选择与快速解锁-逻辑定稿.md` §3）：
 * - `active_vault_id` —— **本次会话看谁**，[select] 写；
 * - `default_vault_id` —— **冷启动先开谁**，**只在设置页**写。
 *
 * 此前 [select] 直接写 `default_vault_id`，导致用户「临时切去看一眼另一个库」会
 * **静默永久改掉默认库**（新老用户都会踩）。
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
                preferences.activeVaultId,
                preferences.defaultVaultId,
                vaultRepository.observeUnlockedVaultIds(),
            ) { selected, saved, unlocked -> pick(selected, saved, unlocked) }
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
        val resolved = pick(
            selected = preferences.activeVaultId.first(),
            saved = preferences.defaultVaultId.first(),
            unlocked = vaultRepository.observeUnlockedVaultIds().first(),
        )
        _activeVaultId.value = resolved
        return resolved
    }

    /**
     * 取值规则单一实现（`init` 的流与 [resolve] 共用，避免两处口径漂移）。
     *
     * 优先级：
     * 1. **本次会话选过且仍解锁** → 用它（用户刚点的，最高优先级）；
     * 2. **默认库且仍解锁** → 用它（冷启动先开用户明确设过的那个）；
     * 3. 否则退化为「当前唯一已解锁的库」（按 id 字典序取最小，保证结论稳定可复现）。
     *
     * ⚠️ 第 1 步为什么不直接覆盖成默认库：`active` 与 `default` 相同时结论不变；
     * 不同时说明用户本次显式切过库 —— 会话内就该听用户的。
     */
    private fun pick(selected: String?, saved: String?, unlocked: Set<String>): String? = when {
        selected != null && selected in unlocked -> selected
        saved != null && saved in unlocked -> saved
        unlocked.isEmpty() -> null
        // Set 无序：按字典序取最小 id 保证「唯一已解锁库」结论稳定可复现
        else -> unlocked.minOrNull()
    }

    /**
     * 切换**当前活跃库**（互斥语义：直接覆盖，不同时存在第二个）。
     *
     * ⚠️ **不写 `default_vault_id`**：默认库的唯一写入点是设置页
     * （[io.vaultix.vaultix.ui.settings.SettingsViewModel.setDefaultVault]）。
     * 切库只表达「这次会话我要看它」，不该改掉用户设的冷启动默认库。
     */
    fun select(vaultId: String) {
        if (vaultId.isBlank()) return
        _activeVaultId.value = vaultId
        scope.launch { preferences.setActiveVaultId(vaultId) }
    }

    /**
     * 设置**默认库**（冷启动先开哪个）—— 透传到偏好层。
     *
     * 与 [select] 分开：设置页改默认库时应**同时**把当前活跃库切过去（用户的意图
     * 就是"以后开这个"），故由调用方先 [select] 再调本方法。
     */
    fun setDefault(vaultId: String?) {
        scope.launch { preferences.setDefaultVaultId(vaultId) }
    }

    /**
     * 新库**首次接入成功**时顺手设为默认库 —— **仅当默认库当前为空**。
     *
     * 语义边界（用户 2026-09-14 拍板）：
     * - ✅ 默认库为空（首个库 / 用户从没设过）⇒ 写入，省掉用户「还得去设置页点一下」；
     * - ❌ 默认库非空 ⇒ **绝不覆盖**。用户设过的选择不该被「又加了一个库」这件事
     *   悄悄改掉 —— 那正是第一批修掉的 bug（`select()` 每次切库都覆盖默认库）的
     *   **同一种病**，只是换了个触发点。
     *
     * ⚠️ 与 [select] 的分工：本方法的落点是「新库接入」这条业务路径，
     * **不是**通用的切库动作。放在 [select] 里就会退化成原来的 bug。
     *
     * 「检查 + 写入」在**同一个 `dataStore.edit` 事务**内完成（[VaultixPreferences.trySetDefaultVaultIfAbsent]），
     * 避免「两个库并发接入时都读到空、都写入」的竞态。
     *
     * @return true = 本次真的写入了（调用方可据此提示「已设为默认库」）。
     */
    suspend fun setDefaultIfAbsent(vaultId: String): Boolean {
        if (vaultId.isBlank()) return false
        return preferences.trySetDefaultVaultIfAbsent(vaultId)
    }

    /** 当前活跃库（同步读取，供 ViewModel 构造期解析 vaultId 用）。 */
    fun current(): String? = _activeVaultId.value
}
