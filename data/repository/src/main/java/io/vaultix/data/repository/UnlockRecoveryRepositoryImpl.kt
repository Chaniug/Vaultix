/*
 * Vaultix — data:repository
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * [io.vaultix.domain.UnlockRecoveryRepository] 的实现 —— 房子钥匙层的**薄转发**
 * （失效矩阵的判定与动作全在 `HouseKeyStore`，本类只做编排与「影响上报」）。
 *
 * ## 为什么独立成类（而不是并进 VaultRepositoryImpl）
 *
 * 同 `AutoUnlockRepositoryImpl` 的理由：后者已正好 40 个函数（detekt 硬上限）。
 * 且「失效之后的善后」与「用户配置快速解锁」语义不同层。
 *
 * ## 与 Bitwarden 的对照（逐条，含**有意偏离**）
 *
 * | Bitwarden | 本类 / 本项目 |
 * |---|---|
 * | `VaultRepositoryImpl.kt:288-349` 生物识别解密失败 → `BiometricDecodingError`，**不重装** | [markRearmPending]：留下「下次认证补写」的约定 |
 * | `VaultLockManagerImpl.kt:340-352` 计数 + 5 次登出 | 无此机制：Vaultix 的失效**不是**暴力破解，登出惩罚错人 |
 * | 无「重装」概念（它不设门锁信封） | [rearmFingerprintLock]：`auth-per-use` 下必须再弹一次认证，故只能延迟补写 |
 *
 * **偏离理由**：Bitwarden 的模型里生物识别直接解「会话密钥」，失败即放弃、
 * 用户重走一遍登录；Vaultix 的房子化模型里**房钥匙可能仍在内存**（PIN 开过门、
 * 或 Never 档自动恢复过）—— 这时把用户的登记整个丢掉是把可用状态当废品扔掉。
 * 前提是「房钥匙在内存」这一 Bitwarden 没有的事实，故这条偏离是**有据**的。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.repository

import io.vaultix.common.logging.VaultixLog
import io.vaultix.domain.FingerprintDegradeReport
import io.vaultix.domain.UnlockRecoveryRepository
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UnlockRecoveryRepositoryImpl @Inject constructor(
    private val houseKeyStore: HouseKeyStore,
) : UnlockRecoveryRepository {

    override val houseKeyInMemory = houseKeyStore.isUnlockedFlow

    override suspend fun isFingerprintLockInvalidated(): Boolean =
        houseKeyStore.isFingerprintLockInvalidated()

    override suspend fun hasRearmPending(): Boolean = houseKeyStore.hasFingerprintRearmPending()

    override suspend fun markRearmPending() {
        houseKeyStore.markFingerprintRearmPending()
        VaultixLog.d(TAG) { "rearm → 标记待重装（房钥匙在内存，等下次认证补写新信封）" }
    }

    override suspend fun clearRearmPending() {
        houseKeyStore.clearFingerprintRearmPending()
    }

    override suspend fun rearmFingerprintLock(cipher: Cipher): Boolean {
        val ok = houseKeyStore.rearmFingerprintLock(cipher)
        VaultixLog.d(TAG) {
            if (ok) {
                "rearm → 指纹门锁信封已用本次认证重装"
            } else {
                "rearm → 跳过（房钥匙不在内存 / cipher 不可用），标记保留以便稍后重试"
            }
        }
        return ok
    }

    override suspend fun degradeFingerprintLock(): FingerprintDegradeReport {
        // 先记下是否有锁、有多少房间 —— 降级本身会把它们抹掉，事后无从统计。
        val hadLock = houseKeyStore.hasFingerprintEnvelope()
        if (!hadLock) {
            // 该锁本来就没开：无信封可降级，也别去动别人的房间
            //（`trimRoomsIfNoLocksRemain` 会因 PIN 锁在而原样保留）。
            VaultixLog.d(TAG) { "degrade → 无指纹信封，无事发生" }
            return FingerprintDegradeReport(
                degraded = false,
                roomsRemoved = 0,
                remainingLocks = houseKeyStore.hasAnyLock(),
            )
        }
        val roomsBefore = houseKeyStore.roomVaultIds().size
        houseKeyStore.disableFingerprintLock()
        val remaining = houseKeyStore.hasAnyLock()
        val roomsAfter = if (remaining) roomsBefore else 0
        val removed = roomsBefore - roomsAfter
        VaultixLog.d(TAG) {
            "degrade → 指纹门锁已禁用 roomsRemoved=$removed remainingLocks=$remaining"
        }
        return FingerprintDegradeReport(
            degraded = true,
            roomsRemoved = removed,
            remainingLocks = remaining,
        )
    }

    private companion object {
        const val TAG = "VaultixUnlockRecovery"
    }
}
