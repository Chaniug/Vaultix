/*
 * Vaultix — core:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 回收站自动清理语义（TrashSettings.autoDeleteDays：0 = 不自动清空、
 * cutoff = now - days、剩余天数 = max(0, days - daysSinceDelete)）参考
 * Bastion 项目（GPL-3.0，Copyright 2025 JoyinJoester）的
 * viewmodel/TrashViewModel.kt，按 Vaultix 架构重写为独立纯函数
 * （Vaultix 不提供 Bastion 的 -1「禁用回收站」档位——回收站是固定能力）。
 */
package io.vaultix.common

import kotlinx.datetime.Instant

/**
 * 回收站自动清理策略（批次③，对齐 Bastion TrashViewModel）。
 *
 * [autoDeleteDays] 语义（单位天）：
 * - `0` = 不自动清空（仅手动永久删除）；
 * - `N > 0` = 软删除后保留 N 天，到期在进入回收站时自动永久删除。
 *
 * 时间基准：deletedDate 为 ISO-8601 字符串（本地写入用 `Instant.now().toString()`，
 * 服务端 Bitwarden 下行同样为 ISO 格式）——解析对两者统一容错，非法值不触发清理。
 */
object TrashCleanupPolicy {

    /** 是否启用自动清理（Bastion shouldAutoCleanup 去掉 enabled 开关后的简化形态）。 */
    fun shouldAutoCleanup(autoDeleteDays: Int): Boolean = autoDeleteDays > 0

    /**
     * 解析删除时间（epoch millis）；非法 / 空串返回 null。
     *
     * 兼容本地 `Instant.toString()`（毫秒精度）与服务端微秒 / 纳秒精度格式
     * （ISO-8601 小数秒 0-9 位均可）。
     */
    fun deletedAtMillis(deletedDate: String): Long? =
        runCatching { Instant.parse(deletedDate).toEpochMilliseconds() }.getOrNull()

    /** 清理截止线（epoch millis）：删除时间早于该值即过期（严格小于）。 */
    fun cutoffMillis(nowMillis: Long, autoDeleteDays: Int): Long =
        nowMillis - autoDeleteDays * MILLIS_PER_DAY

    /**
     * 是否已到期（应被自动清理）。策略未启用或删除时间不可解析时恒为 false
     * （宁可保留也不误删）。
     */
    fun isExpired(deletedDate: String, nowMillis: Long, autoDeleteDays: Int): Boolean {
        if (!shouldAutoCleanup(autoDeleteDays)) return false
        val deletedAt = deletedAtMillis(deletedDate) ?: return false
        return deletedAt < cutoffMillis(nowMillis, autoDeleteDays)
    }

    /**
     * 距自动清理剩余的天数（UI 倒计时）：
     * `max(0, autoDeleteDays - daysSinceDelete)`，同 Bastion 的整除天口径。
     * 策略未启用 / 删除时间不可解析时返回 null（UI 不显示倒计时）。
     */
    fun remainingDays(deletedDate: String, nowMillis: Long, autoDeleteDays: Int): Int? {
        if (!shouldAutoCleanup(autoDeleteDays)) return null
        val deletedAt = deletedAtMillis(deletedDate) ?: return null
        val daysSince = ((nowMillis - deletedAt) / MILLIS_PER_DAY).toInt()
        return maxOf(0, autoDeleteDays - daysSince)
    }

    private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
}
