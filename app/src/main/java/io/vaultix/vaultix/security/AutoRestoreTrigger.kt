/*
 * Vaultix — app:security
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 「从不锁定」档自动恢复的**触发编排**（2026-09-29，对齐 Bitwarden）。
 *
 * ## 与 Bitwarden 的对照（2026-09-29 二次定稿）
 *
 * | 上游 `VaultLockManagerImpl` | 本类 |
 * |---|---|
 * | `observeVaultTimeoutChanges` → `handleUserAutoUnlockChanges` | [reconcile]（combine 档位 × 房钥匙内存态） |
 * | 非 Never ⇒ 清 autoUnlockKey | [reconcile] 第一分支 |
 * | Never 且已解锁 ⇒ 存 key | [reconcile] 第二分支（幂等 enroll） |
 * | Never 且锁定且有 key ⇒ `InitUserCryptoMethod.DecryptedKey` 自动解锁 | [reconcile] 第三分支（restore → 逐库开房间） |
 * | `setVaultToUnlocked` ⇒ `storeUserAutoUnlockKeyIfNecessary` | [onUnlockSuccess] 钩子（unlockedIds 新增 → 幂等 enroll） |
 *
 * ⚠️ **上游 `handleUserAutoUnlockChanges` 不判前台** —— 本类同理。
 *   上一轮曾加过 `lifecycle.isForeground` 前台门禁（为保护「离场软锁」），
 *   但 **`Never` 档现已对齐 Bitwarden 不再软锁**，门禁失去保护对象，
 *   反而会挡住 autofill 冷启动进程的恢复（→ 填充卡 5.6 秒）。已删。
 *
 * ## 为什么信封重建挂在「解锁成功」而不是「档位/钥匙状态」
 *
 * 主动锁库（lockVault/lockAll）会删信封，但 lockVault **不清房钥匙**
 * （多库语义：锁一个 ≠ 全锁）—— 若靠 (档位, 钥匙在内存) 的状态组合重建，
 * 钥匙没动过、组合没变化、信封就永远回不来，表现为「锁过一次库，
 * 『从不』的自动恢复就悄悄永久失效」。挂「库解锁成功」事件（对齐 Bitwarden
 * `setVaultToUnlocked` 的钩子位置）则没有这个死角。
 *
 * ## 构造时机（照 `KdbxCloudSyncInitializer` 模式）
 *
 * 必须在进程启动早期被实例化（否则 autofill 拉起的进程永远等不到恢复）。
 * 做法是 [io.vaultix.vaultix.VaultixApplication] 里 `@Inject` 一个字段。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.security

import io.vaultix.common.logging.VaultixLog
import io.vaultix.datastore.VaultTimeout
import io.vaultix.datastore.VaultTimeoutPreferences
import io.vaultix.domain.AutoUnlockRepository
import io.vaultix.domain.VaultRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 「从不」档自动恢复触发器（纯编排，动作在 [AutoUnlockRepository]）。
 *
 * 三条规则（见类 KDoc 对照表）：
 * 1. 档位 ≠ Never 且信封在 → 删信封；
 * 2. 档位 = Never 且房钥匙在内存 → 幂等写信封；
 * 3. 档位 = Never 且房钥匙不在内存且信封在 → **自动恢复**（进程死亡后的冷启动）。
 */
@Singleton
class AutoRestoreTrigger @Inject constructor(
    // 只依赖**档位**这一件事（2026-09-30 抽类后收窄；此前拿的是整个 `VaultixPreferences` 门面）。
    private val vaultTimeoutPrefs: VaultTimeoutPreferences,
    private val autoUnlock: AutoUnlockRepository,
    private val vaultRepository: VaultRepository,
    // ⚠️ 2026-09-29 二次定稿：`AutoLockController`（前台状态）注入**已移除**。
    //
    //  上一轮方案 A 里，前台状态是「恢复的闸门」—— 因为那时 `Never` 档会
    //  离场软锁，若不设闸，软锁翻掉 `houseKeyInMemory` 后本类会立刻把钥匙
    //  解回内存，等于没锁。
    //
    //  **现在没有软锁了**（`Never` 对齐 Bitwarden 直接 return，见
    //  `VaultLockManagerImpl.checkForVaultTimeoutInternal`），所以：
    //  - `houseKeyInMemory` 只会在**进程死亡**时变 false（进程内钥匙只增不减）；
    //  - 触发第三分支（恢复）的唯一现实场景 = **新进程冷启动**（autofill 拉起）；
    //  - 冷启动时前台状态本来就是 false（还没走完 `onStart`），设闸反而会
    //    **挡住 autofill 进程的恢复** —— 那正是上一轮「QQ 填充卡 5.6 秒」的真因。
    //
    //  ⇒ 闸门连同其依赖一并删除。恢复时机由 **进程启动** 隐式决定，不再需要显式判据。
) {

    // 进程级 scope（订阅生命周期 = 进程）。与 VaultLockManagerImpl 同款取向：
    // 项目唯一的调度器限定符 @CryptoDispatcher 是 KDF/加解密语义，不混用。
    @Suppress("InjectDispatcher")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            combine(
                // ★ **D3**（2026-09-29）：档位改成**每库一份**后，信封存在性的判据变成
                //   「**有没有任何一个库**是 Never 档」—— 信封是**全局**的（包的是一把房钥匙），
                //   而「该不该恢复某个库」由 `restore()` 逐库判定。
                anyVaultNever(),
                autoUnlock.houseKeyInMemory,
            ) { anyNever, keyInMemory -> anyNever to keyInMemory }
                .distinctUntilChanged()
                .collect { (anyNever, keyInMemory) ->
                    reconcile(anyNever, keyInMemory)
                }
        }
        scope.launch { observeUnlockSuccesses() }
    }

    /**
     * 「**是否还有 Never 档的库**」—— auto 信封存在性的唯一判据（D3 起按库聚合）。
     *
     * 库表来自仓储（偏好层拿不到库表），逐库读 `vaultTimeoutPrefs.vaultTimeout(id)`；
     * 库集合变化时用 `flatMapLatest` 重建订阅（不为动态集合手工维护挂/摘）。
     *
     * ⚠️ **迁移期结论不变**：还没写过显式档位的库，`vaultTimeout(id)` 回退旧全局键
     * ⇒ 与 D3 之前「读全局档位」完全一致。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun anyVaultNever(): Flow<Boolean> =
        vaultRepository.observeVaults()
            .map { vaults -> vaults.map { it.id }.sorted() }
            .distinctUntilChanged()
            .flatMapLatest { ids ->
                if (ids.isEmpty()) {
                    flowOf(false)
                } else {
                    combine(ids.map { vaultTimeoutPrefs.vaultTimeout(it) }) { perVault ->
                        perVault.any { it == VaultTimeout.Never }
                    }.distinctUntilChanged()
                }
            }

    /**
     * 档位 / 钥匙内存态变化时的收敛动作（核心规则表）。
     *
     * ⚠️ 三条分支的**语义边界**（2026-09-29 二次定稿后）：
     * - 分支 2（写信封）在进程内是**幂等**的，`keyInMemory=true` 会反复触发也不怕；
     * - 分支 3（恢复）现实里只在**新进程**首次求值时命中 ——
     *   进程存活期间钥匙一旦进内存就不会再出去（没有软锁了），
     *   所以 `keyInMemory=false` ⇔ 本进程从未解锁过 ⇔ 需要恢复。
     */
    private suspend fun reconcile(
        anyVaultNever: Boolean,
        keyInMemory: Boolean,
    ) {
        if (!anyVaultNever) {
            if (autoUnlock.hasEnvelope()) {
                autoUnlock.removeEnvelope()
                VaultixLog.d(TAG) { "autoRestore → 档位离开 Never，删除自动恢复信封" }
            }
            return
        }
        if (keyInMemory) {
            autoUnlock.enrollEnvelope()
            return
        }
        if (autoUnlock.hasEnvelope()) {
            val report = autoUnlock.restore()
            VaultixLog.d(TAG) {
                "autoRestore → 恢复完成 envelope=${report.envelopeOpened} " +
                    "rooms=${report.roomCount} opened=${report.opened} failed=${report.failedVaultIds.size}"
            }
        }
    }

    /**
     * 「解锁成功后确保信封在」（对齐 Bitwarden `setVaultToUnlocked` →
     * `storeUserAutoUnlockKeyIfNecessary`）。
     *
     * 只对 unlockedIds **新增**触发：主动锁库（集合变小）后信封刚被删，
     * 不能立刻又写回去 —— 那等于把用户的「锁」撤销掉。
     */
    private suspend fun observeUnlockSuccesses() {
        var previous: Set<String> = emptySet()
        vaultRepository.observeUnlockedVaultIds().collect { current ->
            val newlyUnlocked = current - previous
            previous = current
            if (newlyUnlocked.isEmpty()) return@collect
            // ★★ 多库锁模型定稿 **D1**（2026-09-29）：解锁成功 = 用户撤回了「锁」的意图
            //   ⇒ 先清该库的「用户主动锁」标记（下一次进程死亡后它重新可被自动恢复）。
            //   放在这里的理由：本类**就是**「解锁成功后维护锁态元数据」的归属
            //   （见类 KDoc 对照表 `setVaultToUnlocked` 那一行），且解锁成功有 5 条路径 ——
            //   散着写过一轮必然漏一条。
            newlyUnlocked.forEach { autoUnlock.clearUserLock(it) }
            // D3：信封是全局的 ⇒「**任一**新解锁库是 Never 档」即可写信封。
            val anyNever = newlyUnlocked.any {
                vaultTimeoutPrefs.vaultTimeout(it).first() == VaultTimeout.Never
            }
            if (anyNever && autoUnlock.houseKeyInMemory.first()) {
                autoUnlock.enrollEnvelope()
            }
        }
    }

    private companion object {
        const val TAG = "VaultixAutoRestore"
    }
}
