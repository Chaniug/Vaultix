/*
 * Vaultix — core:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.common

import kotlinx.datetime.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回收站自动清理策略（对齐 Bastion TrashViewModel 语义）：
 * cutoff / 到期判断 / 剩余天数倒计时 / 0 天与脏数据守卫。
 */
@Suppress("MagicNumber")
class TrashCleanupPolicyTest {

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
        const val NOW = 1_760_000_000_000L
    }

    private fun isoDaysAgo(days: Long): String =
        Instant.fromEpochMilliseconds(NOW - days * DAY).toString()

    @Test
    fun shouldAutoCleanup_onlyForPositiveDays() {
        assertFalse(TrashCleanupPolicy.shouldAutoCleanup(0))
        assertFalse(TrashCleanupPolicy.shouldAutoCleanup(-1))
        assertTrue(TrashCleanupPolicy.shouldAutoCleanup(1))
        assertTrue(TrashCleanupPolicy.shouldAutoCleanup(30))
    }

    @Test
    fun deletedAtMillis_parsesLocalAndServerFormats() {
        // 本地 Instant.toString()（毫秒精度）
        assertEquals(123L, TrashCleanupPolicy.deletedAtMillis("1970-01-01T00:00:00.123Z"))
        // 服务端 Bitwarden（微秒精度）
        assertEquals(1_000L, TrashCleanupPolicy.deletedAtMillis("1970-01-01T00:00:01.000000Z"))
        // 整秒（无小数）
        assertEquals(40_000L, TrashCleanupPolicy.deletedAtMillis("1970-01-01T00:00:40Z"))
    }

    @Test
    fun deletedAtMillis_rejectsGarbage() {
        assertNull(TrashCleanupPolicy.deletedAtMillis(""))
        assertNull(TrashCleanupPolicy.deletedAtMillis("not-a-date"))
        assertNull(TrashCleanupPolicy.deletedAtMillis("2026-13-99T99:99:99Z"))
    }

    @Test
    fun cutoffMillis_subtractsWholeDays() {
        assertEquals(NOW - 30 * DAY, TrashCleanupPolicy.cutoffMillis(NOW, 30))
        assertEquals(NOW - 7 * DAY, TrashCleanupPolicy.cutoffMillis(NOW, 7))
    }

    @Test
    fun isExpired_strictlyBeforeCutoffOnly() {
        // 恰好压线（= cutoff）不算过期：严格小于
        val onCutoff = TrashCleanupPolicy.cutoffMillis(NOW, 30)
        val onCutoffIso = Instant.fromEpochMilliseconds(onCutoff).toString()
        assertFalse(TrashCleanupPolicy.isExpired(onCutoffIso, NOW, 30))
        // 比 cutoff 早 1ms → 过期
        val beforeCutoff = Instant.fromEpochMilliseconds(onCutoff - 1).toString()
        assertTrue(TrashCleanupPolicy.isExpired(beforeCutoff, NOW, 30))
        // 删除 90 天前（保留 30 天）→ 过期
        assertTrue(TrashCleanupPolicy.isExpired(isoDaysAgo(90), NOW, 30))
    }

    @Test
    fun isExpired_neverWhenDisabledOrUnparseable() {
        val deletedAt = isoDaysAgo(90)
        assertFalse(TrashCleanupPolicy.isExpired(deletedAt, NOW, 0))
        assertFalse(TrashCleanupPolicy.isExpired(deletedAt, NOW, -1))
        assertFalse(TrashCleanupPolicy.isExpired("garbage", NOW, 30))
    }

    @Test
    fun remainingDays_countsWholeDaysSinceDelete() {
        // 删除 10 天前，保留 30 天 → 剩 20
        assertEquals(20, TrashCleanupPolicy.remainingDays(isoDaysAgo(10), NOW, 30))
        // 删除 1 天前，保留 7 天 → 剩 6
        assertEquals(6, TrashCleanupPolicy.remainingDays(isoDaysAgo(1), NOW, 7))
        // 同一毫秒内删除 → 剩满额
        assertEquals(30, TrashCleanupPolicy.remainingDays(isoDaysAgo(0), NOW, 30))
    }

    @Test
    fun remainingDays_clampsAtZero() {
        // 删除 45 天前（超过 30 天保留期）→ 0，不出现负数
        assertEquals(0, TrashCleanupPolicy.remainingDays(isoDaysAgo(45), NOW, 30))
        assertEquals(0, TrashCleanupPolicy.remainingDays(isoDaysAgo(365), NOW, 30))
    }

    @Test
    fun remainingDays_nullWhenDisabledOrUnparseable() {
        assertNull(TrashCleanupPolicy.remainingDays(isoDaysAgo(1), NOW, 0))
        assertNull(TrashCleanupPolicy.remainingDays("garbage", NOW, 30))
    }
}
