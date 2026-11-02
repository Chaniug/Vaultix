/*
 * Vaultix — app / OneDrive
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.remote.onedrive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * [readBackPlan] 的判定表（ISSUES #232）。
 *
 * ## 这一组守的是什么
 *
 * #232 把"写后读回"从**无条件整份下载**改成"元数据能判死才免下载"。
 * 这类优化的危险恰恰在于**省错地方**：一旦哪条判据写松了，
 * 「长度对、内容坏」的库就会被当成写成功记进基线，从此每次保存都建在一个坏库上。
 *
 * ⇒ 这里三个方向都要钉死：
 * - **必须省**：远端记录的**大小**与写入字节数不符 ⇒ 内容不可能相同，直接判冲突（省掉整份下载）；
 * - **不许省**：大小相同（哪怕时间戳看起来正常）⇒ **必须**下载逐字节比 ——
 *   `大小相同也必须下载` 那两例就是这颗钉子；
 * - **不许升级**：远端时间戳明显偏旧 ⇒ 只标"可疑"、**仍然下载** ——
 *   `远端时间戳明显偏旧也仍然要下载` 钉的就是"别把时钟差当成证据"。
 *
 * ## 变异验证（把实现改回危险写法，看哪些用例精确报红）
 *
 * | 变异 | 期望报红的用例 |
 * |---|---|
 * | 把 `recorded != writtenSize` 改成恒 false | 大小不符必须直接判不一致 |
 * | `NEEDS_BYTE_CHECK` 改为 `MISMATCH_PROVEN`（大小相同也判死） | 大小相同也必须下载 / 来源不给大小时也必须下载 |
 * | 把时间戳那条**重新**升成判死 | 远端时间戳明显偏旧也仍然要下载 / 时钟略慢不得被标成可疑 |
 * | 去掉容差（`modified + 0 < startedAt`） | 时钟略慢不得被标成可疑 |
 * | 时间戳解析失败时返回 0（而不是 null） | 时间戳为 null 也必须下载 / 刚好越过容差才标成可疑 |
 */
class OneDriveReadBackPolicyTest {

    private fun entry(size: Long?, modified: String? = null) = OneDriveEntry(
        id = "item-1",
        name = "vault.kdbx",
        isDirectory = false,
        versionToken = "\"etag-v2\"",
        sizeBytes = size,
        lastModified = modified,
    )

    private fun at(iso: String): Long = Instant.parse(iso).toEpochMilli()

    // ───────────────────────── 必须省：元数据已证不一致 ─────────────────────────

    @Test
    fun `大小不符必须直接判不一致`() {
        val plan = readBackPlan(
            writtenSize = 1024,
            entry = entry(size = 2048),
            writeStartedAtMs = at("2026-11-02T10:00:00Z"),
        )
        assertEquals(ReadBackVerdict.MISMATCH_PROVEN, plan.verdict)
        assertFalse("证明不一致时不该再下载", plan.requiresDownload)
        assertTrue("诊断文案应带上两侧字节数，实际：${plan.reason}", plan.reason.contains("1024"))
    }

    @Test
    fun `远端时间戳明显偏旧也仍然要下载`() {
        val plan = readBackPlan(
            writtenSize = 1024,
            entry = entry(size = 1024, modified = "2026-11-02T08:00:00Z"),
            // 本次写入在 10:00，远端却说自己 08:00 就被改过 —— 可疑，但**不是证据**。
            writeStartedAtMs = at("2026-11-02T10:00:00Z"),
        )
        assertEquals(
            "时间戳与本机时钟比较，隔着漂移/手改/秒级截断；判错就是凭空造一个冲突。" +
                "结论必须交给字节",
            ReadBackVerdict.NEEDS_BYTE_CHECK,
            plan.verdict,
        )
        assertTrue("但它要作为诊断线索带出来", plan.suspiciousTimestamp)
        assertTrue(plan.requiresDownload)
    }

    @Test
    fun `大小不符优先于时间戳诊断`() {
        val plan = readBackPlan(
            writtenSize = 1024,
            entry = entry(size = 999, modified = "2026-11-02T10:00:00Z"),
            writeStartedAtMs = at("2026-11-02T10:00:00Z"),
        )
        assertEquals(ReadBackVerdict.MISMATCH_PROVEN, plan.verdict)
        assertTrue("文案应说大小，实际：${plan.reason}", plan.reason.contains("字节"))
        assertFalse("已经判死了，不必再给诊断", plan.suspiciousTimestamp)
    }

    // ───────────────────────── 不许省：必须下载 ─────────────────────────

    @Test
    fun `大小相同也必须下载`() {
        val plan = readBackPlan(
            writtenSize = 1024,
            entry = entry(size = 1024, modified = "2026-11-02T10:00:05Z"),
            writeStartedAtMs = at("2026-11-02T10:00:00Z"),
        )
        assertEquals(
            "长度对、内容坏是这条路上最坏的形态 —— 元数据看不出它，必须下载逐字节比",
            ReadBackVerdict.NEEDS_BYTE_CHECK,
            plan.verdict,
        )
        assertTrue(plan.requiresDownload)
        assertEquals("", plan.reason)
    }

    @Test
    fun `来源不给大小时也必须下载`() {
        val plan = readBackPlan(
            writtenSize = 1024,
            entry = entry(size = null, modified = null),
            writeStartedAtMs = at("2026-11-02T10:00:00Z"),
        )
        assertEquals(ReadBackVerdict.NEEDS_BYTE_CHECK, plan.verdict)
    }

    @Test
    fun `时间戳为 null 也必须下载`() {
        val plan = readBackPlan(
            writtenSize = 1024,
            entry = entry(size = 1024, modified = null),
            writeStartedAtMs = at("2026-11-02T10:00:00Z"),
        )
        assertEquals(
            "解析不出来只等于'不知道'，不能退化成'没改过'",
            ReadBackVerdict.NEEDS_BYTE_CHECK,
            plan.verdict,
        )
    }

    @Test
    fun `本机没取到时刻时也必须下载`() {
        val plan = readBackPlan(
            writtenSize = 1024,
            entry = entry(size = 1024, modified = "2026-11-02T10:00:00Z"),
            writeStartedAtMs = null,
        )
        assertEquals(ReadBackVerdict.NEEDS_BYTE_CHECK, plan.verdict)
    }

    // ───────────────────────── 时钟容差（宁可多下载，不可误报冲突） ─────────────────────────

    @Test
    fun `时钟略慢不得被标成可疑`() {
        val startedAt = at("2026-11-02T10:00:00Z")
        val plan = readBackPlan(
            writtenSize = 1024,
            // 远端时间比我们"开始写"早 2 分钟 —— 典型的本机时钟偏快。
            entry = entry(size = 1024, modified = "2026-11-02T09:58:00Z"),
            writeStartedAtMs = startedAt,
        )
        assertEquals(ReadBackVerdict.NEEDS_BYTE_CHECK, plan.verdict)
        assertFalse(
            "2 分钟的偏差必须落在容差内，否则排查时会一路被这条假线索带偏",
            plan.suspiciousTimestamp,
        )
    }

    @Test
    fun `刚好越过容差才标成可疑`() {
        val startedAt = at("2026-11-02T10:00:00Z")
        val justInside = startedAt - CLOCK_SKEW_TOLERANCE_MS + 1_000
        val justOutside = startedAt - CLOCK_SKEW_TOLERANCE_MS - 1_000
        val inside = readBackPlan(
            writtenSize = 1024,
            entry = entry(size = 1024, modified = Instant.ofEpochMilli(justInside).toString()),
            writeStartedAtMs = startedAt,
        )
        val outside = readBackPlan(
            writtenSize = 1024,
            entry = entry(size = 1024, modified = Instant.ofEpochMilli(justOutside).toString()),
            writeStartedAtMs = startedAt,
        )
        assertFalse(inside.suspiciousTimestamp)
        assertTrue(outside.suspiciousTimestamp)
    }

    // ───────────────────────── 时间戳解析 ─────────────────────────

    @Test
    fun `时间戳解析失败必须给 null 而不是 0`() {
        val parsed = OneDriveEntry(
            id = "x",
            name = "v.kdbx",
            isDirectory = false,
            versionToken = null,
            sizeBytes = 1,
            lastModified = "这不是时间",
        ).lastModifiedMillis
        assertEquals(
            "给 0 会让'0 早于任何时刻'恒成立，凭空造出'远端是旧版本'",
            null,
            parsed,
        )
    }

    @Test
    fun `合法时间戳按毫秒解析`() {
        val parsed = OneDriveEntry(
            id = "x",
            name = "v.kdbx",
            isDirectory = false,
            versionToken = null,
            sizeBytes = 1,
            lastModified = "2026-11-02T10:00:00Z",
        ).lastModifiedMillis
        assertEquals(at("2026-11-02T10:00:00Z"), parsed)
    }
}
