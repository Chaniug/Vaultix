/*
 * Vaultix — data:repository
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * [io.vaultix.domain.RoomResealRepository] 的实现 —— 复用
 * `LocalUnlockEnrollment`（备料 + 校验）与 `HouseKeyStore.sealRoom`（软封装落盘），
 * 本类只做「单库」这一条的编排与结果归类。
 *
 * ⚠️ **不碰门锁**：全程无 Keystore、无 BiometricPrompt、无 PIN。门锁包的房钥匙
 * 没变，用户的指纹/PIN 配置原样保留（见接口 KDoc 的边界说明）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.repository

import io.vaultix.common.logging.VaultixLog
import io.vaultix.domain.LocalUnlockEnrollOutcome
import io.vaultix.domain.LocalUnlockPrepareOutcome
import io.vaultix.domain.LocalUnlockPreparedEnrollment
import io.vaultix.domain.RoomResealOutcome
import io.vaultix.domain.RoomResealRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomResealRepositoryImpl @Inject constructor(
    /**
     * 备料与校验的唯一实现（「先校验后包裹」纪律的所在地）。
     *
     * ⚠️ 复用而不是另写一份单库校验：那份逻辑里有两条真实教训 ——
     * KDBX 必须先 `Kdbx.verify` 真验一次（否则会「启用成功但躺的是错密码」），
     * 以及「读不到文件」与「密码错」必须分开报。重写一份就是把坑再埋一遍。
     */
    private val enrollment: LocalUnlockEnrollment,
    private val houseKeyStore: HouseKeyStore,
) : RoomResealRepository {

    override suspend fun resealRoom(
        vaultId: String,
        newMasterPassword: String,
    ): RoomResealOutcome {
        // 前置自查：没有房钥匙就没有封装密钥。如实报而不是让 `sealRoom` 抛
        // `IllegalStateException`（那是给编排 bug 的响亮信号，但不该由用户承受）。
        if (!houseKeyStore.isUnlocked) {
            VaultixLog.d(TAG) { "reseal $vaultId → 房钥匙不在内存，跳过" }
            return RoomResealOutcome.HouseKeyUnavailable
        }
        val outcome = when (
            val prepared = enrollment.prepareForVaults(listOf(vaultId)) { newMasterPassword }
                .values.firstOrNull()
        ) {
            // Bitwarden 库返回的也是 Ready（它不需要密码，密码参数被忽略）。
            is LocalUnlockPrepareOutcome.Ready -> seal(prepared.prepared, vaultId)
            LocalUnlockPrepareOutcome.InvalidCredentials -> RoomResealOutcome.InvalidCredentials
            LocalUnlockPrepareOutcome.Skipped -> RoomResealOutcome.InvalidCredentials
            is LocalUnlockPrepareOutcome.SourceUnavailable -> RoomResealOutcome.Failed(prepared.detail)
            is LocalUnlockPrepareOutcome.Failed -> RoomResealOutcome.Failed(prepared.detail)
            null -> RoomResealOutcome.Failed("本地不存在该库")
        }
        VaultixLog.d(TAG) { "reseal $vaultId → $outcome" }
        return outcome
    }

    /**
     * 落盘：把备料交给 `LocalUnlockEnrollment`（它保证明文用完即擦 +
     * 范围镜像同步），并把它的 [LocalUnlockEnrollOutcome] 归类成本接口的结果。
     */
    private suspend fun seal(
        prepared: LocalUnlockPreparedEnrollment,
        vaultId: String,
    ): RoomResealOutcome = try {
        val sealed = enrollment.sealRoomsForVaults(listOf(prepared)).values.firstOrNull()
        // Enrolled = 信封已覆盖；其余（Failed）带上 detail 如实上报。
        if (sealed is LocalUnlockEnrollOutcome.Failed) {
            RoomResealOutcome.Failed(sealed.detail)
        } else {
            RoomResealOutcome.Resealed
        }
    } catch (error: IllegalStateException) {
        // `sealRoom` 在无钥匙时抛这个 —— 前面已自查，走到这里说明自查与实际之间
        // 钥匙被并发清掉了（用户恰好锁了库）。如实报，不静默。
        VaultixLog.d(TAG) { "reseal $vaultId → 落盘时房钥匙已消失（${error.message}）" }
        RoomResealOutcome.HouseKeyUnavailable
    }

    private companion object {
        const val TAG = "VaultixRoomReseal"
    }
}
