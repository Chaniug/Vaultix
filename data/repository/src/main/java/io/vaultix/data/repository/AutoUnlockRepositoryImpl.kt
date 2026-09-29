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
 * | **离开 App 时的锁定（Never 档也锁）** | **[softLock]**（清密钥、**留**信封 —— 回来自动开） |
 * | `InitUserCryptoMethod.DecryptedKey`（SDK 恢复会话） | `unlockVaultFromRoom`（房间信封，纯软件） |
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.repository

import io.vaultix.common.logging.VaultixLog
import io.vaultix.data.kdbx.Kdbx
import io.vaultix.domain.AutoUnlockRepository
import io.vaultix.domain.AutoRestoreReport
import io.vaultix.domain.RoomUnlockOutcome
import io.vaultix.domain.VaultRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@Singleton
class AutoUnlockRepositoryImpl @Inject constructor(
    private val houseKeyStore: HouseKeyStore,
    /** 恢复编排复用「房间信封 → 会话」的唯一实现（openBitwarden/openKdbx 分流在这里面）。 */
    private val vaultRepository: VaultRepository,
    /**
     * 离场软锁需要同时清掉两条会话模型（Bitwarden 密钥 / KDBX 整库明文）。
     *
     * ⚠️ 走仓储的 [sessions] 会引入循环依赖（`VaultRepositoryImpl` 依赖本类吗？
     * 不依赖 —— 但 `VaultRepositoryImpl` 与 `AutoUnlockRepositoryImpl` 平级，
     * 这里注入具体会话管理器是安全的）。二者都是进程级单例。
     */
    private val sessions: VaultSessionManager,
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

    /**
     * 离场软锁：清会话 + 清房钥匙，**刻意不删信封**。
     *
     * ⚠️ 三个不可省的要点：
     * 1. **先清房钥匙**（硬约束 #4：锁 = 密钥清零）。顺序反了的话，中间那一刻
     *    会话已死而房钥匙还在，`AutoRestoreTrigger` 的 combine 可能观察到
     *    「钥匙在内存」而重写信封 —— 无害但白跑。
     * 2. **两条会话模型必须同时收**：Bitwarden 侧是密钥（[VaultSessionManager]），
     *    KDBX 侧是内存里的整库明文。漏一个就漏一把密钥。
     * 3. **绝不调 [removeEnvelope]**：这是与硬锁的**唯一**差别。删了信封
     *    用户回来就得重新过门锁 —— 那是「方案 B」，不是用户选的「方案 A」。
     *
     * ⚠️ 恢复时机由 `AutoRestoreTrigger` 的**前台门禁**把关：软锁后
     * `houseKeyInMemory` 变 false，若没有前台门禁，那个 combine 会立刻把钥匙
     * 读回内存 —— 等于没锁。见该类的 KDoc。
     *
     * @return 软锁前仍处于解锁态的库 id（诊断 / 测试断言用）。
     */
    override suspend fun softLock(): List<String> = withContext(Dispatchers.IO) {
        val unlockedBefore = vaultRepository.observeVaults().first()
            .filter { it.unlocked }
            .map { it.id }
        // 房钥匙先清零（软锁的核心目的：后台期间密钥不在内存）。
        houseKeyStore.lock()
        // 两条会话模型同时收（Bitwarden 密钥 + KDBX 整库明文）。
        sessions.lockAll()
        Kdbx.lockAll()
        VaultixLog.d(TAG) {
            "autoRestore → 离场软锁 locked=${unlockedBefore.size}（信封保留，回前台免交互恢复）"
        }
        unlockedBefore
    }

    private companion object {
        const val TAG = "VaultixAutoRestore"
    }
}
