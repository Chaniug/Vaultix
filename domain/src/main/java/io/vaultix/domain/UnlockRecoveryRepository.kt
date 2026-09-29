/*
 * Vaultix — domain
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * **快速解锁失效矩阵**的领域入口（2026-09-29，批次 4，定稿 §6）。
 *
 * ## 为什么不并进 `VaultRepository`
 *
 * 与 `KdbxSyncRepository` / `AutoUnlockRepository` 同一条理由：`VaultRepositoryImpl`
 * 已**正好 40 个函数**（detekt `TooManyFunctions` 硬上限），加任何方法都爆门禁。
 * 语义上也该分开 —— `VaultRepository` 管「用户配置快速解锁」（开关、登记、解锁），
 * 本接口管的是**失效之后的善后**（重装 / 降级），触发者是解锁失败的现场，不是用户手势。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.domain

import javax.crypto.Cipher
import kotlinx.coroutines.flow.Flow

/**
 * 快速解锁门锁/房间失效之后的善后（实现见 `data:repository` 的 `UnlockRecoveryRepositoryImpl`）。
 *
 * ## 要解决的问题
 *
 * 指纹门锁的 KEK 是 `auth-per-use` 的（硬约束 #2），平台在下列情形会让它**永久失效**：
 * 用户重录指纹、删除全部指纹、清除锁屏凭据。此时门锁信封还在盘上，但已解不开。
 *
 * 传统的两种处理都错：
 * - **静默删掉登记** → 用户观感是「指纹解锁莫名消失」（#93 谎报状态的翻版）；
 * - **静默重包** → `auth-per-use` 下**物理上不可能**：重包要一次 `doFinal`，
 *   而那个 cipher 必须经过一次新的 BiometricPrompt 授权。
 *
 * ## 本接口的取向：延迟重装（rearm-on-next-auth）
 *
 * 分两种局面，判据**唯一** = [houseKeyInMemory]：
 *
 * | 局面 | 判据 | 动作 |
 * |---|---|---|
 * | **开门态**（房钥匙还在手上） | `houseKeyInMemory == true` | [markRearmPending]：信封解不开但钥匙在，等下次认证补写 |
 * | **关门态**（钥匙也丢了） | `houseKeyInMemory == false` | [degradeFingerprintLock]：禁用该锁 + 回主密码（**绝不静默**） |
 *
 * ⚠️ **绝不能拿「信封是否存在」当判据**：那是「用户开没开锁」的答案，不是
 * 「钥匙在不在手上」。混淆会把开门态误判成关门态，[degradeFingerprintLock]
 * 一跑就**丢掉全部房间信封**（`trimRoomsIfNoLocksRemain`），用户的每个库都得
 * 重新走一遍登记向导。唯一的判据是 `HouseKeyStore.isUnlocked`。
 *
 * ## 触发编排在哪
 *
 * 本接口只承载**可复用与可测的动作**；「什么时候调」在 app 层 ——
 * 解锁失败现场在 `LocalUnlockFanout`，补写时机在登记向导指纹段
 * （那时天然有一个已授权的 cipher）。
 */
interface UnlockRecoveryRepository {

    /**
     * 房钥匙是否在内存 —— **rearm 与降级的唯一判据**（见接口 KDoc 的表）。
     *
     * 与 `AutoUnlockRepository.houseKeyInMemory` 同一个源；两处都暴露是因为
     * 两个接口各有各的消费者，共用一个常量级 Flow 不构成重复状态。
     */
    val houseKeyInMemory: Flow<Boolean>

    /**
     * 指纹门锁**在盘上但已失效**（信封在 + KEK 被平台永久失效）。
     *
     * 设置页据此显示「需重新启用」而不是「已启用」（#93：不谎报 On 态），
     * 也不是「已关闭」（那会让用户以为可以点一下重新打开，实则要走完整认证）。
     */
    suspend fun isFingerprintLockInvalidated(): Boolean

    /** 是否有「指纹门锁待重装」标记（UI 呈现 + 补写时机的判断）。 */
    suspend fun hasRearmPending(): Boolean

    /**
     * 打「待重装」标记（开门态检测到失效时写；幂等）。
     *
     * 只写标记、**不重包** —— 当场没有已授权的 cipher（见接口 KDoc）。
     */
    suspend fun markRearmPending()

    /** 清「待重装」标记（用户主动关锁 / 已降级时；幂等）。 */
    suspend fun clearRearmPending()

    /**
     * 用**本次认证的** cipher 重装指纹门锁信封（延迟重装的落地动作）。
     *
     * 前置：[houseKeyInMemory] == true（重包要包一把已知的房钥匙）。
     *
     * @return false = 房钥匙不在内存 / cipher 不可用（此时标记**不**清除 ——
     *   钥匙回来后仍应能重装）。
     */
    suspend fun rearmFingerprintLock(cipher: Cipher): Boolean

    /**
     * 降级：禁用指纹门锁（删信封 + 清计数 + 清待重装标记）并如实上报。
     *
     * ⚠️ 只在**关门态**调用（见接口 KDoc 的判据表）。若这是最后一把门锁，
     * `HouseKeyStore` 会连带清掉全部房间信封 —— 那是**正确**的行为
     * （房钥匙再无恢复途径），但用户会看到「库都不在快速解锁范围内」，
     * 故上报结果里带上 [FingerprintDegradeReport.roomsRemoved]，让 UI 有话可说。
     *
     * @return 降级后的真实影响（**绝不静默**：硬约束 #5）。
     */
    suspend fun degradeFingerprintLock(): FingerprintDegradeReport
}

/** 指纹门锁降级的真实影响（用户可见动作的据实描述）。 */
data class FingerprintDegradeReport(
    /** 是否确实执行了降级（false = 该锁本来就没开，无事发生）。 */
    val degraded: Boolean,
    /** 因「再无门锁」而连带清掉的房间信封数（0 = 还有别的门锁在，用户的库没受影响）。 */
    val roomsRemoved: Int,
    /** 降级后是否还剩至少一把门锁（决定提示语该说「请用主密码」还是「请用 PIN」）。 */
    val remainingLocks: Boolean,
)
