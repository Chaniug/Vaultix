/*
 * Vaultix — data:bitwarden
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规，**不可删除**）
 * 本文件的「空库保护」设计衍生自 Bastion 项目（GPL-3.0，Copyright 2025 JoyinJoester）
 * 的 sync/EmptyVaultProtection.kt；Bastion 注明其参考 Keyguard 的安全同步策略。
 * 本文件按 Vaultix 的 String 型 vaultId 与日志规范改写，同样以 GPL-3.0 发布。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.bitwarden.sync

/**
 * 空库 / 数据骤减保护。
 *
 * **为什么需要**：若服务端因故障返回空数据，直接落库会**清空用户本地所有条目**。
 * 这类事故不可逆，因此宁可暂停同步，也不要盲目覆盖（源自 Keyguard 的安全策略，
 * 经 Bastion 实现验证）。
 *
 * 规则：
 * 1. 首次同步允许空库（新账号本来就没数据）；
 * 2. 服务端有数据，或本地也是空的 → 正常放行；
 * 3. 本地有数据但服务端返回 0 条 → **阻止同步**，交由用户确认；
 * 4. 数据量骤减超过阈值（默认 50%）→ 同样警告。
 */
object EmptyVaultProtection {

    sealed interface CheckResult {
        /** 允许同步 */
        data object Allowed : CheckResult

        /** 首次同步，允许空库 */
        data object FirstSyncAllowed : CheckResult

        /** 阻止同步，需用户确认 */
        data class Blocked(val localCount: Int, val serverCount: Int, val reason: String) : CheckResult
    }

    fun checkSyncAllowed(
        localCipherCount: Int,
        serverCipherCount: Int,
        isFirstSync: Boolean,
    ): CheckResult = when {
        isFirstSync -> CheckResult.FirstSyncAllowed
        serverCipherCount > 0 -> CheckResult.Allowed
        localCipherCount == 0 -> CheckResult.Allowed
        else -> CheckResult.Blocked(
            localCount = localCipherCount,
            serverCount = serverCipherCount,
            reason = "服务器返回空数据，但本地有  条记录。" +
                "这可能是服务器故障或账号异常，为保护你的数据已暂停同步。",
        )
    }

    /**
     * 是否发生显著数据丢失（服务端条目远少于本地）。
     *
     * @param threshold 丢失比例阈值，默认 0.5（减少超过 50% 即视为异常）
     */
    fun hasSignificantDataLoss(
        localCount: Int,
        serverCount: Int,
        threshold: Float = DEFAULT_LOSS_THRESHOLD,
    ): Boolean {
        if (localCount == 0) return false
        if (serverCount >= localCount) return false
        return (localCount - serverCount).toFloat() / localCount > threshold
    }

    private const val DEFAULT_LOSS_THRESHOLD = 0.5f
}
