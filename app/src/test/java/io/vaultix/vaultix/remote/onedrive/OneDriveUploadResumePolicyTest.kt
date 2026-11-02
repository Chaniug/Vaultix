/*
 * Vaultix — app / OneDrive（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.remote.onedrive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分片上传的**断点续传判定**（`.ai/ISSUES.md` #236）。
 *
 * ## 这份用例几乎每一条都在防"文件被拼错"
 *
 * "续传"最坏的失败不是"没省下流量"，而是**从服务端报的缺口直接跳过去** ——
 * 中间那段永远缺，KDBX 解不开，而错误只会显示"密码错误或文件损坏"。
 * ⇒ 所有"服务端说的位置与我们确认的位置对不上"的情形，期望一律是
 * **不许跳过**（[ChunkResumeVerdict.Continue] 且 `offset <= confirmedOffset`）。
 */
class OneDriveUploadResumePolicyTest {

    private val confirmed = 327_680L

    private fun continueOffset(verdict: ChunkResumeVerdict): Long =
        (verdict as ChunkResumeVerdict.Continue).offset

    // ------------------------------------------------------------ 正常区间

    @Test
    fun `缺口正好在确认位 从确认位继续`() {
        val verdict = judgeResume(listOf("327680-"), confirmed, stallCount = 0)
        assertEquals(confirmed, continueOffset(verdict))
    }

    @Test
    fun `缺口在确认位之内 从缺口处重发`() {
        // 服务端说从 163840 开始 —— 它把我们已确认的前半片又算成缺失了。
        // 从缺口重发是对的（幂等覆盖），但**绝不能**从 327680 开始。
        val verdict = judgeResume(listOf("163840-"), confirmed, stallCount = 0)
        assertEquals(163_840L, continueOffset(verdict))
    }

    @Test
    fun `闭合区间取右端加一`() {
        // "0-655360" 表示已收到 655360（含），下一个字节是 655361。
        val verdict = judgeResume(listOf("655361-"), confirmed, stallCount = 0)
        assertTrue(verdict is ChunkResumeVerdict.Continue || verdict is ChunkResumeVerdict.Restart)
    }

    // ------------------------------------------------------------ 危险：服务端超前

    @Test
    fun `服务端超前于确认位 必须夹回确认位而不是跳过`() {
        // 最危险的一格：服务端说"我收到 1MiB 了"，但我们本地只确认到 320KiB。
        // 若真从 1MiB 开始，320KiB~1MiB 那段永远是洞。
        val verdict = judgeResume(listOf("1048576-"), confirmed, stallCount = 0)
        assertEquals("必须从确认位重发，不许跳过", confirmed, continueOffset(verdict))
    }

    @Test
    fun `服务端超前且已达到无进展上限 必须放弃`() {
        val verdict = judgeResume(listOf("1048576-"), confirmed, stallCount = MAX_STALLS)
        assertTrue("连续无进展超限应 GiveUp，实际 $verdict", verdict is ChunkResumeVerdict.GiveUp)
    }

    // ------------------------------------------------------------ 从头来

    @Test
    fun `服务端要求从 0 开始 必须重启`() {
        assertTrue(judgeResume(listOf("0-"), confirmed, 0) is ChunkResumeVerdict.Restart)
    }

    /**
     * ★ 反过来的一半：**本地一个字节都没确认**时，`0-` 就是"从头发"，必须原地重发。
     *
     * 曾经把 `0-` 一律判成 Restart，于是**第一片就失败的库永远传不上去**：
     * 每次都被告知"会话失效"，而会话其实是好的、只是这一片没上去。
     * 判据要看**两边的差**，不能只看服务端那一个数字（2026-11-03 实测）。
     */
    @Test
    fun `本地未确认任何字节时 服务端 0- 必须原地重发`() {
        val verdict = judgeResume(listOf("0-"), confirmedOffset = 0, stallCount = 0)
        assertEquals("本地也是 0 时不许判重启", 0L, continueOffset(verdict))
    }

    /**
     * ★ `0-` 不能当成"就地重发"（2026-11-03 实测踩到）。
     *
     * 直觉上"服务端缺口在我确认位之内 ⇒ 从缺口重发"是对的，但缺口是 **0** 时不成立：
     * 那意味着**之前传的全没了**（会话被清）。当成"重发当前片"会让循环原地打转 ——
     * 每次重发都成功、确认位又前进到本片末尾，然后下一片再被拒，永远传不完；
     * 而用户看到的是"上传中"一直转。
     */
    @Test
    fun `缺口为 0 且确认位大于 0 时必须重启 而不是原地重发`() {
        val verdict = judgeResume(listOf("0-"), confirmedOffset = 655_360, stallCount = 0)
        assertTrue("缺口为 0 时必须是 Restart，实际 $verdict", verdict is ChunkResumeVerdict.Restart)
    }

    @Test
    fun `区间非法必须重启 不许猜`() {
        listOf("abc", "-5-", "", "   ", "327680", "999999999999999999999-").forEach { raw ->
            assertTrue(
                "非法区间 '$raw' 必须判 Restart",
                judgeResume(listOf(raw), confirmed, 0) is ChunkResumeVerdict.Restart,
            )
        }
    }

    // ------------------------------------------------------------ 缺字段

    @Test
    fun `缺 nextExpectedRanges 必须原地重发而不是当成完成`() {
        // 有代理会整个吞掉这个字段。当成"完成"会让整份文件只传了前几片就宣布成功。
        listOf<List<String>?>(null, emptyList()).forEach { ranges ->
            val verdict = judgeResume(ranges, confirmed, stallCount = 0)
            assertEquals("缺字段时应原地重发：$ranges", confirmed, continueOffset(verdict))
        }
        // ⚠️ 反过来：**明确**回了一个空值不是"没给"，是"给了个解析不了的" ⇒ 必须重启，
        //    否则会对着同一条坏路径无限原地重发（用例见「区间非法必须重启」）。
    }

    @Test
    fun `多次区间只取第一段`() {
        // 多段意味着"中间有洞"，只补第一段是安全的（其余交给下一轮）。
        val verdict = judgeResume(listOf("327680-", "655360-"), confirmed, 0)
        assertEquals(confirmed, continueOffset(verdict))
    }

    // ------------------------------------------------------------ 成功片的确认位

    @Test
    fun `2xx 且缺口正好在片尾 这片算收下`() {
        val next = confirmedAfterAccepted(listOf("655360-"), chunkStart = 327_680, chunkEndExclusive = 655_360)
        assertEquals(655_360L, next)
    }

    @Test
    fun `2xx 但缺口仍在片起点 不算收下`() {
        // 服务端回了 2xx 却说"我还缺这一片" ⇒ 收下就是自欺欺人。
        val next = confirmedAfterAccepted(listOf("327680-"), chunkStart = 327_680, chunkEndExclusive = 655_360)
        assertEquals(327_680L, next)
    }

    @Test
    fun `2xx 且缺口超出片尾 必须夹回片尾`() {
        // 服务端说缺 2MB，但我们刚发的片只到 640KiB ⇒ 中间那段谁都没发过。
        val next = confirmedAfterAccepted(listOf("2097152-"), chunkStart = 327_680, chunkEndExclusive = 655_360)
        assertEquals("不许把还没发的字节记成已确认", 655_360L, next)
    }

    @Test
    fun `2xx 但缺区间字段 按整片收下`() {
        val next = confirmedAfterAccepted(null, chunkStart = 0, chunkEndExclusive = 655_360)
        assertEquals(655_360L, next)
    }
}
