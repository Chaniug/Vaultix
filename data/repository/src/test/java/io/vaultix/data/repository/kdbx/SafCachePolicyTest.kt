/*
 * Vaultix — data:repository
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */

package io.vaultix.data.repository.kdbx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `SafKdbxFileSource` 快速判据的**纯函数**部分（`.ai/ISSUES.md` #234）。
 *
 * ## 这份用例的重点不是"能省"，而是"不能误省"
 *
 * 快速路径判错的方向只有一个有价值的失败模式：**该读的时候没读** ——
 * 于是 `write(expectedVersion=…)` 的预检拿一个过期摘要去比，比过了 ⇒ 静默覆盖别人的改动。
 * 所以每条 [judgeObserved] 用例都在问同一个问题：「这个装置下，**允许**复用吗？」
 * 而所有"拿不准"的装置，期望值一律是 `MustRead`。
 */
class SafCachePolicyTest {

    private val window = ReadWindow(startedAt = 1_000, finishedAt = 1_500)

    private fun reusable(verdict: SafObservationVerdict): Boolean =
        verdict is SafObservationVerdict.Reusable

    // ---------------------------------------------------------------- judgeObserved

    @Test
    fun `修改时间早于读区间且大小一致 必须允许复用`() {
        val verdict = judgeObserved(
            observed = SafFileObservation(modifiedAtMillis = 900, sizeBytes = 3072),
            cachedToken = "sha-abc",
            cachedWindow = window,
            cachedSize = 3072,
        )
        assertEquals("sha-abc", (verdict as SafObservationVerdict.Reusable).token)
        assertEquals(3072L, verdict.sizeBytes)
    }

    @Test
    fun `修改时间落在读区间内 必须重读`() {
        // 读的过程中被改：摘要可能是"改动前半段"的字节，绝不能当成现在的版本。
        val verdict = judgeObserved(
            observed = SafFileObservation(modifiedAtMillis = 1_200, sizeBytes = 3072),
            cachedToken = "sha-abc",
            cachedWindow = window,
            cachedSize = 3072,
        )
        assertFalse(reusable(verdict))
    }

    @Test
    fun `修改时间晚于读区间 必须重读`() {
        // 读完之后被改：摘要停在旧内容上。
        val verdict = judgeObserved(
            observed = SafFileObservation(modifiedAtMillis = 2_000, sizeBytes = 3072),
            cachedToken = "sha-abc",
            cachedWindow = window,
            cachedSize = 3072,
        )
        assertFalse(reusable(verdict))
    }

    @Test
    fun `大小变了 必须重读`() {
        val verdict = judgeObserved(
            observed = SafFileObservation(modifiedAtMillis = 900, sizeBytes = 4096),
            cachedToken = "sha-abc",
            cachedWindow = window,
            cachedSize = 3072,
        )
        assertFalse(reusable(verdict))
    }

    @Test
    fun `provider 不给大小 必须重读`() {
        val verdict = judgeObserved(
            observed = SafFileObservation(modifiedAtMillis = 900, sizeBytes = null),
            cachedToken = "sha-abc",
            cachedWindow = window,
            cachedSize = 3072,
        )
        assertFalse(reusable(verdict))
    }

    @Test
    fun `provider 不给修改时间 必须重读`() {
        // ⚠️ 这条是"仅凭 (mtime,size) 判一致"能成立的**前提**：没有 mtime 就什么也证明不了。
        val verdict = judgeObserved(
            observed = SafFileObservation(modifiedAtMillis = null, sizeBytes = 3072),
            cachedToken = "sha-abc",
            cachedWindow = window,
            cachedSize = 3072,
        )
        assertFalse(reusable(verdict))
    }

    @Test
    fun `从未读过 必须重读`() {
        val verdict = judgeObserved(
            observed = SafFileObservation(modifiedAtMillis = 900, sizeBytes = 3072),
            cachedToken = null,
            cachedWindow = null,
            cachedSize = null,
        )
        assertFalse(reusable(verdict))
    }

    @Test
    fun `有摘要但没有读区间 必须重读`() {
        // 写后读回那种摘要：知道内容，但不知道"这段区间里文件没被动过"。
        val verdict = judgeObserved(
            observed = SafFileObservation(modifiedAtMillis = 900, sizeBytes = 3072),
            cachedToken = "sha-abc",
            cachedWindow = null,
            cachedSize = 3072,
        )
        assertFalse(reusable(verdict))
    }

    @Test
    fun `刚好落在区间端点 必须当作可疑`() {
        // 两端都按"覆盖"处理：边界上宁可多读一次。
        assertFalse(
            reusable(
                judgeObserved(
                    SafFileObservation(window.startedAt, 10), "sha-abc", window, 10,
                ),
            ),
        )
        assertFalse(
            reusable(
                judgeObserved(
                    SafFileObservation(window.finishedAt, 10), "sha-abc", window, 10,
                ),
            ),
        )
    }

    // ---------------------------------------------------------------- recordStillValid

    private fun record(generation: Long, sourceId: String = "content://vault.kdbx") = SafReadCache(
        token = "sha-abc",
        window = window,
        sizeBytes = 3072,
        generation = generation,
        sourceId = sourceId,
    )

    @Test
    fun `记录序号与当前一致 且来源相同 才算有效`() {
        assertTrue(recordStillValid(record(3), currentGeneration = 3, sourceId = "content://vault.kdbx"))
    }

    @Test
    fun `本进程写过一次 记录必须作废`() {
        // 这是 #234 最关键的一根支柱：光靠 mtime 挡不住"provider 把 mtime 记在写入过程中"。
        assertFalse(recordStillValid(record(3), currentGeneration = 4, sourceId = "content://vault.kdbx"))
    }

    @Test
    fun `来源换了 记录必须作废`() {
        assertFalse(recordStillValid(record(3), currentGeneration = 3, sourceId = "content://other.kdbx"))
    }

    @Test
    fun `没有记录 必须作废`() {
        assertFalse(recordStillValid(null, currentGeneration = 0, sourceId = "content://vault.kdbx"))
    }

    // ---------------------------------------------------------------- ReadWindow

    @Test
    fun `null 修改时间不算被区间覆盖`() {
        assertFalse(window.covers(null))
    }
}
