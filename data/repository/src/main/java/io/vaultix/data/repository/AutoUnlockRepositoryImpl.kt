/*
 * Vaultix — data:repository
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * [io.vaultix.domain.AutoUnlockRepository] 的实现 —— 房子钥匙层的**薄转发** +
 * 恢复编排（解信封 → 逐库开房间）。
 *
 * ## 为什么独立成类（而不是并进 VaultRepositoryImpl）
 *
 * 同 `KdbxSyncRepositoryImpl` 的理由：后者已正好 40 个函数（detekt 硬上限）。
 * 且「进程死亡后的会话恢复」与「用户配置快速解锁」语义不同层。
 *
 * ## 与 Bitwarden 的对照（逐条）
 *
 * | Bitwarden（`VaultLockManagerImpl`） | 本类 / 本项目 |
 * |---|---|
 * | `storeUserAutoUnlockKeyIfNecessary`（解锁成功钩子，timeout=Never 时存 key） | [enrollEnvelope]（幂等） |
 * | `handleUserAutoUnlockChanges` else 分支（key 在、库锁着 → 自动解锁） | [restore] |
 * | `setVaultToLocked` 清 autoUnlockKey | `VaultRepositoryImpl.lockVault/lockAll` 调 `HouseKeyStore.removeAutoEnvelope` |
 * | `InitUserCryptoMethod.DecryptedKey`（SDK 恢复会话） | `unlockVaultFromRoom`（房间信封，纯软件） |
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.repository

import io.vaultix.common.logging.VaultixLog
import io.vaultix.domain.AutoUnlockRepository
import io.vaultix.domain.AutoRestoreReport
import io.vaultix.domain.RoomUnlockOutcome
import io.vaultix.domain.VaultRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class AutoUnlockRepositoryImpl @Inject constructor(
    private val houseKeyStore: HouseKeyStore,
    /** 恢复编排复用「房间信封 → 会话」的唯一实现（openBitwarden/openKdbx 分流在这里面）。 */
    private val vaultRepository: VaultRepository,
) : AutoUnlockRepository {

    override val houseKeyInMemory = houseKeyStore.isUnlockedFlow

    override suspend fun hasEnvelope(): Boolean = houseKeyStore.hasAutoEnvelope()

    override suspend fun enrollEnvelope(): Boolean =
        houseKeyStore.enrollAutoEnvelope()

    override suspend fun restore(): AutoRestoreReport = withContext(Dispatchers.IO) {
        // 钥匙已在内存（可能刚被别的入口解开）就不再碰信封 —— 信封只在
        // 「内存无钥匙」时才是恢复的唯一途径。
        val opened = houseKeyStore.isUnlocked || houseKeyStore.openAutoEnvelope()
        if (!opened) {
            VaultixLog.d(TAG) { "autoRestore → 信封不可解，跳过（用户走正常解锁）" }
            return@withContext AutoRestoreReport(
                envelopeOpened = false,
                roomCount = 0,
                opened = 0,
                failedVaultIds = emptyList(),
            )
        }
        val rooms = houseKeyStore.roomVaultIds()
        var count = 0
        val failed = mutableListOf<String>()
        for (vaultId in rooms) {
            // 逐库独立成败：某库凭据过期（StaleCredentials）/ 文件移走不影响其它库。
            when (runCatching { vaultRepository.unlockVaultFromRoom(vaultId) }.getOrNull()) {
                RoomUnlockOutcome.Opened -> count++
                else -> failed += vaultId
            }
        }
        VaultixLog.d(TAG) {
            "autoRestore → rooms=${rooms.size} opened=$count failed=${failed.size}"
        }
        AutoRestoreReport(
            envelopeOpened = true,
            roomCount = rooms.size,
            opened = count,
            failedVaultIds = failed,
        )
    }

    override suspend fun removeEnvelope() {
        houseKeyStore.removeAutoEnvelope()
    }

    private companion object {
        const val TAG = "VaultixAutoRestore"
    }
}
