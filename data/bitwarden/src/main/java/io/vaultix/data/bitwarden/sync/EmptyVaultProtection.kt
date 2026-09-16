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
            // ⚠️ 必须真的插值：此前写成 "本地有  条记录"（占位处为空），
            // 用户看到的是没有数字的残句，无法判断到底有多少条面临风险。
            reason = "服务器返回空数据，但本地有 $localCipherCount 条记录。" +
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

    /**
     * 删除本地行之前，是否**需要用户再确认一次**（2026-09-16 新增）。
     *
     * ## 它补的是哪条缝
     *
     * [hasSignificantDataLoss] 比的是「服务端总数 vs 本地总数」，阈值 50%。
     * 于是存在一个空档：**服务端因故障只返回 60%，不触发阻断（0.4 不 > 0.5），
     * 而那 40% 的本地行会被 `pruneRemovedRows` 直接删掉**。
     * `pendingIds` 在这里帮不上忙 —— 它只保护「有本地未推送改动」的条目，
     * 保护不了「已经同步过、用户从未删过」的。
     *
     * 现实触发场景：共享库/组织权限被改（条目从 `/sync` 消失）、代理截断响应、
     * 服务端部分故障。
     *
     * ## 判据为什么用「数量/比例」而不是「和上次比」
     *
     * 用户**确实**会在官方网页端删条目 —— 那种删除本地没有 pending op，
     * 表现与「服务端故障」**完全相同**，服务端侧无法区分。
     * ⇒ 这里不去猜「哪种」，而是只回答一个更弱、但可从数据判断的问题：
     *   **「这批删除的量，像不像顺手删几条？」** 不像就要求确认。
     *
     * ⚠️ 门槛刻意设得**偏高**：日常删几条不该被打扰（否则每次网页端操作都要多同步一次，
     * 用户会烦），只在「批量且占比大」时才拦。
     *
     * @param toDeleteCount 本次将要删除的本地行数（已排除有待推送改动的）
     * @param localCount 删除前本地总行数
     */
    fun requiresDeleteConfirmation(toDeleteCount: Int, localCount: Int): Boolean {
        if (toDeleteCount <= 0) return false
        // ① 绝对量：一次删 20 条以上，不太像"顺手删几条"
        if (toDeleteCount >= DELETE_CONFIRM_ABSOLUTE) return true
        // ② 比例：删掉本地一半以上 —— 这一条同时保护小库
        //    （本地只剩 4 条而删 3 条，绝对量达不到 20，但显然可疑）
        return localCount > 0 && toDeleteCount * 2 >= localCount
    }

    /** 触发"需要确认"的绝对删除条数。 */
    private const val DELETE_CONFIRM_ABSOLUTE = 20

    private const val DEFAULT_LOSS_THRESHOLD = 0.5f
}
