/*
 * Vaultix — app / OneDrive
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.remote.onedrive

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分片上传**失败重试**时，重发的片必须与"服务端实际收到的进度"对齐（2026-11-02）。
 *
 * ## 守的是什么（这是"文件长度对、内容坏了"的唯一入口）
 *
 * `chunkedUpload` 的循环是 `offset = end` 无条件推进。于是只要 [putChunkWithRetry]
 * 在某一片上"最终返回成功"，上层就会当作这一片已经完整落进服务端，继续传下一片。
 * 而一片 PUT 的失败有三种完全不同的后果（客户端**看不出来**是哪一种）：
 *
 * | 失败形态 | 服务端实际收到 | 原样重发的后果 |
 * |---|---|---|
 * | 请求没到 | 0 字节 | 正常（这是唯一安全的那种） |
 * | 请求到了、响应丢了 | 整片 | 416，或**同一片写两遍** ⇒ 长度对、内容坏 |
 * | 服务端中途断开 | 部分 | 416 / 400 |
 *
 * 修法：重试前 `GET <uploadUrl>` 读 `nextExpectedRanges`，把要发的字节裁成
 * "服务端还缺的那一段"。本文件就钉这三件事：
 * 1. 服务端**已收全整片**（只差响应）⇒ 不再发第二个 PUT，且整次上传视为成功；
 * 2. 服务端**只收了半片** ⇒ 第二个 PUT 只发后半段，`Content-Range` 跟着挪；
 * 3. 服务端**一字节没收到** ⇒ 原样重发（不能把"不知道"当成"已经收到"）。
 *
 * ## 为什么不用 MockWebServer
 *
 * 同 `OneDriveGraphClientWriteTest`：用 OkHttp 的 [Interceptor] 直接短路，
 * 零新依赖、零端口，毫秒级。这里的假服务端按"URL + 方法"分流，
 * 并对 uploadSession 维护一个**真实推进的偏移**（这才是能抓住"重复写"的假服务端）。
 */
class OneDriveGraphClientChunkRetryTest {

    /**
     * 一个会**记住进度**的假 uploadSession。
     *
     * @param failFirstPut true = 第一次 PUT 先"收下 [receivedBeforeFailure] 字节再断开"，
     *   返回 500，让客户端走重试。
     * @param receivedBeforeFailure 服务端在断开前已经收下的字节数（整片长度 = 收到全部）。
     */
    private class FakeSessionServer(
        private val failFirstPut: Boolean = true,
        private val receivedBeforeFailure: Int = 0,
        /**
         * **指定的分片**连续失败几次（之后正常接收）—— 用来构造"前 N 片成功、这一片怎么都上不去"。
         *
         * key 是这一片的起始偏移。这才是**真正有东西可续**的场景：
         * 第一片就失败的会话没有断点可续（本地确认位还是 0），那种情况走的是"原地重发"。
         */
        private val failPlan: Map<Int, Int> = emptyMap(),
        /** 指定起点的那一片直接 410（会话不存在）并清空进度 —— 模拟"会话被服务端清掉"。 */
        private val clearSessionAtChunkStart: Int? = null,
        /**
         * true = 每片都回 202，但 `nextExpectedRanges` 仍指向本片起点（"收下了但没推进"）。
         *
         * 这是最恶毒的一种服务端行为：**不报错、永远不成功**。没有上限的话客户端会
         * 无限重发同一片，内存与流量一路涨到 OOM（2026-11-03 实测 `Java heap space`）。
         */
        private val stallProgressEveryPut: Boolean = false,
        /** 指定起点的那一片回 412（远端已变）—— 用来钉"412 绝不能被当成可续传"。 */
        private val preconditionFailedAtChunkStart: Int? = null,
    ) : Interceptor {

        /** 建过几次上传会话（续传期间不该 > 1）。 */
        var sessionCreates = 0
            private set

        val requests = mutableListOf<Request>()

        /** 服务端**真的收下**的字节（按顺序），用来验证"没有重复 / 没有空洞"。 */
        val received = StringBuilder()

        private var nextExpected = 0
        private var putCount = 0
        private val failedByStart = mutableMapOf<Int, Int>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            requests += request
            val url = request.url.toString()
            return when {
                url.contains("createUploadSession") -> {
                    sessionCreates++
                    respond(request, 200, """{"uploadUrl":"$SESSION_URL"}""")
                }

                // 查进度：Graph 的 GET session 回的是 `nextExpectedRanges`。
                request.method == "GET" && url.startsWith(SESSION_URL) ->
                    respond(request, 200, """{"nextExpectedRanges":["$nextExpected-"]}""")

                request.method == "PUT" && url.startsWith(SESSION_URL) -> putChunk(request)

                else -> respond(request, 404, "{}")
            }
        }

        private fun putChunk(request: Request): Response {
            putCount++
            val range = requireNotNull(request.header("Content-Range")) { "分片必须带 Content-Range" }
            val start = range.substringAfter("bytes ").substringBefore('-').toInt()
            val body = bodyBytes(request)
            // 三条"异常服务端行为"与两条"按计划失败"各走一个辅助函数 ——
            // 全塞在这里会让圈复杂度冲到 20+（detekt 上限 14）。
            specialFailure(request, start)?.let { return it }
            plannedFailure(request, start, body)?.let { return it }
            // 正常接收：必须**正好**接在当前进度上，否则就是"重复写 / 写错位置"。
            check(start == nextExpected) {
                "分片起点 $start 与服务端进度 $nextExpected 不符（重复写或空洞）"
            }
            received.append(String(body, Charsets.ISO_8859_1))
            nextExpected += body.size
            // 中间片回 202（空体）；最后一片回 200 + driveItem。
            return if (range.endsWith("/$TOTAL_SIZE") && nextExpected == TOTAL_SIZE) {
                respond(request, 200, FINAL_ITEM_JSON)
            } else {
                respond(request, 202, "")
            }
        }

        /**
         * 三条"服务端可能干出来的怪事"，都返回 null 表示"没发生，走正常路径"。
         *
         * ⚠️ 这个函数必须排在正常接收**之前**：`stallProgressEveryPut` 若放在
         * `nextExpected += body.size` 之后，假服务端就会一边推进进度、一边声称没推进，
         * 自相矛盾（2026-11-03 第一版就是这样，测出来的是 404 而不是"无进展"）。
         */
        private fun specialFailure(request: Request, start: Int): Response? {
            if (preconditionFailedAtChunkStart == start) {
                return respond(request, 412, """{"error":{"code":"preconditionFailed"}}""")
            }
            if (clearSessionAtChunkStart == start) {
                nextExpected = 0
                received.setLength(0)
                return respond(request, 410, """{"error":{"code":"itemNotFound"}}""")
            }
            if (stallProgressEveryPut) {
                return respond(request, 202, """{"nextExpectedRanges":["$start-"]}""")
            }
            return null
        }

        /** 按计划让某片失败（**一个字节都不收**，进度原地不动）；返回 null 表示这片该成功。 */
        private fun plannedFailure(request: Request, start: Int, body: ByteArray): Response? {
            val planned = failPlan[start] ?: 0
            val already = failedByStart[start] ?: 0
            if (already < planned) {
                failedByStart[start] = already + 1
                return respond(request, 500, """{"error":{"code":"serviceNotAvailable"}}""")
            }
            if (failFirstPut && putCount == 1) {
                // 服务端只收下前 N 字节就断开（"部分接收"与"整片收下但响应丢了"
                // 都靠 receivedBeforeFailure 表达：写 CHUNK 就是整片）。
                val accepted = minOf(receivedBeforeFailure, body.size)
                received.append(String(body, 0, accepted, Charsets.ISO_8859_1))
                nextExpected = start + accepted
                return respond(request, 500, """{"error":{"code":"serviceNotAvailable"}}""")
            }
            return null
        }

        private fun respond(request: Request, code: Int, body: String): Response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code < 300) "OK" else "Failure")
            .body(body.toResponseBody(JSON.toMediaType()))
            .build()

        private fun bodyBytes(request: Request): ByteArray {
            val buffer = okio.Buffer()
            request.body?.writeTo(buffer)
            return buffer.readByteArray()
        }

        fun putRequests(): List<Request> = requests.filter { it.method == "PUT" }
    }

    private fun clientWith(server: FakeSessionServer) = OneDriveGraphClient(
        client = OkHttpClient.Builder().addInterceptor(server).build(),
        baseUrl = BASE_URL,
    )

    /** 刚好两片多一点，保证第一片、第二片、第三片（尾片）都会走到。 */
    private val payload = ByteArray(TOTAL_SIZE) { index -> (index % 251).toByte() }

    private fun upload(graph: OneDriveGraphClient) = runBlocking {
        graph.upload(
            accessToken = TOKEN,
            path = DB_PATH,
            bytes = payload,
            expectedETag = null,
            forceReplace = false,
            failIfExists = false,
        )
    }

    // ───────────────────────── 场景 1：整片已收全，只是响应丢了 ─────────────────────────

    @Test
    fun `服务端已收全整片时重试不得再发第二个 PUT 且整次上传算成功`() {
        // 第一片正好 5MiB，服务端"全部收下"后才断开 ⇒ 客户端重发任何字节都会造成重复。
        val server = FakeSessionServer(failFirstPut = true, receivedBeforeFailure = CHUNK)
        val entry = upload(clientWith(server))

        val puts = server.putRequests()
        val firstChunkEnd = CHUNK
        val retriedForFirstChunk = puts.drop(1).filter { request ->
            val range = request.header("Content-Range").orEmpty()
            range.substringAfter("bytes ").substringBefore('-').toInt() < firstChunkEnd
        }
        assertTrue(
            "服务端已收全第一片，不得再对它发 PUT（否则同一段数据会写两遍）。" +
                "实际重发：${retriedForFirstChunk.map { it.header("Content-Range") }}",
            retriedForFirstChunk.isEmpty(),
        )
        // 最终文件必须是**逐字节正确**的：重复/空洞都会在这里露馅。
        assertTrue("服务端收下的内容必须与原文件逐字节相同", receivedMatches(server))
        // ★ #244：令牌现在带**来源前缀**（`etag:` / `ctag:`），因为上层要拿它判断
        //   "远端**内容**变了没有"—— 而 eTag 覆盖元数据，不能与 cTag 混为一谈。
        //   这里造的服务端只回 eTag ⇒ 期望 `etag:` 前缀。
        assertEquals("上传成功后必须带回新的版本令牌", "etag:$FINAL_ETAG", entry.versionToken)
    }

    // ───────────────────────── 场景 2：只收了半片 ─────────────────────────

    @Test
    fun `服务端只收了半片时重试必须只发缺的那半段`() {
        val half = CHUNK / 2
        val server = FakeSessionServer(failFirstPut = true, receivedBeforeFailure = half)
        upload(clientWith(server))

        val retry = server.putRequests()[1]
        val range = retry.header("Content-Range").orEmpty()
        assertEquals(
            "重试的第一片必须从服务端已收下的位置开始，实际：$range",
            "bytes $half-${CHUNK - 1}/$TOTAL_SIZE",
            range,
        )
        // 假服务端的 `check(start == nextExpected)` 已经会拦住"重复写"；
        // 这里再验一次最终内容，防止假服务端自己写错。
        assertTrue("重发之后服务端内容必须与原文件逐字节相同", receivedMatches(server))
    }

    // ───────────────────────── 场景 3：一字节没收到 ─────────────────────────

    @Test
    fun `服务端一字节未收到时必须原样重发`() {
        val server = FakeSessionServer(failFirstPut = true, receivedBeforeFailure = 0)
        upload(clientWith(server))

        val retry = server.putRequests()[1]
        assertEquals(
            "服务端进度是 0 ⇒ 必须从 0 重发整片",
            "bytes 0-${CHUNK - 1}/$TOTAL_SIZE",
            retry.header("Content-Range").orEmpty(),
        )
        assertTrue("原样重发之后内容必须逐字节相同", receivedMatches(server))
    }

    // ───────────────────────── 场景 4：整次上传的断点续传（#236） ─────────────────────────

    /**
     * ★ 断点续传的本体：**第二片怎么都传不上去，必须从第二片续上，不重建会话**。
     *
     * 真机对应：库 15 MiB，传到 5 MiB（第一片已落盘）时进电梯断网。
     * 旧行为 ⇒ `putChunkWithRetry` 三次都失败 ⇒ 整次上传抛错 ⇒ 上层从 0 重传，
     * 前 5 MiB 白传；网络再抖一次又来一遍，用户看到的就是"同步老是失败"。
     *
     * 现在：确认位停在第一片末尾（**已确认**写进服务端），服务端缺口也在那里
     * ⇒ 原地重发第二片即可，`sessionCreates` 保持 1。
     *
     * ⚠️ 为什么不用"第一片就失败"当用例：那时本地确认位是 0，**没有断点可续**，
     * 走的是"原地重发"，测不出续传。这一点是 2026-11-03 写完第一版用例才想清的。
     */
    @Test
    fun `第二片反复失败时必须从第二片续上 且不重建会话`() {
        // 第二片连续失败 3 次 = 正好耗尽入环重试；第 4 次是续传重发，成功。
        val server = FakeSessionServer(failPlan = mapOf(CHUNK to CHUNK_RETRY_ROUND))
        upload(clientWith(server))

        assertTrue("续传之后服务端内容必须逐字节相同", receivedMatches(server))
        assertEquals("一条上传会话都不该重建", 1, server.sessionCreates)
        // 第二片至少被发了 4 次（3 次入环 + 1 次续传），但**第三片必须照常传**。
        val thirdChunkPuts = server.putRequests().filter { request ->
            request.header("Content-Range").orEmpty().substringAfter("bytes ").substringBefore('-')
                .toIntOrNull() == CHUNK * 2
        }
        assertTrue("第三片必须照常上传，实际 ${server.putRequests().size} 次 PUT", thirdChunkPuts.isNotEmpty())
    }

    /**
     * 服务端把进度重置成 0（会话被清）⇒ 判 Restart ⇒ **抛错**，交给上层决定重来。
     *
     * ⚠️ 这里**不许**在客户端里自己重建会话：那会把 412（条件写失败）也吞进重来逻辑，
     * 绕过"远端已变 ⇒ 让用户拍板"这条最重要的语义。
     *
     * 装置：第一片收下后服务端把进度清回 0 并丢弃已收字节 —— 本地确认位仍是第一片末尾，
     * 两边差 > 0，这才是"会话被清"的真实形态。
     */
    @Test
    fun `服务端进度被清零时必须抛错且不自己重建会话`() {
        // 第一片正常收下（确认位推进到第一片末尾），第二片开始前会话被清。
        val server = FakeSessionServer(clearSessionAtChunkStart = CHUNK)
        val failure = runCatching { upload(clientWith(server)) }.exceptionOrNull()

        assertTrue("会话失效必须抛出异常，实际正常返回了", failure != null)
        assertTrue(
            "错误信息要能指向原因（续传/会话），实际：${failure?.message}",
            failure?.message.orEmpty().contains("续传"),
        )
        assertEquals("客户端不得自己重建上传会话", 1, server.sessionCreates)
    }

    /**
     * ★ **412（远端已变）必须原样抛穿，绝不能当成"这一片没传上去"去续传**。
     *
     * 这是"续传"最容易带出来的新风险：一旦把所有 `IOException` 都当可续传，
     * 412 就会在续传循环里被一遍遍重发 —— 而服务端的条件写语义是"我这一版不是你以为的那版"，
     * 重发多少次都不会变，除非远端被别的设备又改了一次；一旦那一刻条件重新满足，
     * 上传就会**静默覆盖别人的改动**，界面还显示"已同步"。
     *
     * 正确语义：412 ⇒ 一路抛到 `OneDriveKdbxFileSource.write` ⇒ 转成冲突 ⇒ 让用户拍板。
     */
    @Test
    fun `412 必须直接抛穿 既不重试也不续传`() {
        val server = FakeSessionServer(preconditionFailedAtChunkStart = CHUNK)
        val failure = runCatching { upload(clientWith(server)) }.exceptionOrNull()

        assertTrue(
            "412 必须保持自己的类型，实际：${failure?.let { it::class.simpleName }}",
            failure is OneDrivePreconditionFailedException,
        )
        val retried = server.putRequests().filter { request ->
            request.header("Content-Range").orEmpty().substringAfter("bytes ")
                .substringBefore('-').toIntOrNull() == CHUNK
        }
        assertEquals("412 那一片不许重试", 1, retried.size)
        assertEquals("412 不许重建上传会话", 1, server.sessionCreates)
    }

    /**
     * ★ **服务端永远 202、却永远不推进进度** —— 必须有上限，不许无限重发。
     *
     * 2026-11-03 实测：这条用例的第一版装置让客户端把同一片重发到 `Java heap space`。
     * 根因是成功分支里 `stalls = 0` **无条件重置**，而 `confirmed` 根本没动
     * ⇒ `while (confirmed < bytes.size)` 永远成立 ⇒ 同一片无限 PUT。
     *
     * 真机表现：进度条一直转、手机发热、电量狂掉，最后要么 OOM 要么用户手动杀进程，
     * 而日志里只有一串"分片上传成功"。
     */
    @Test
    fun `服务端 202 却从不推进进度 必须在有限次后报错`() {
        val server = FakeSessionServer(failFirstPut = false, stallProgressEveryPut = true)
        val failure = runCatching { upload(clientWith(server)) }.exceptionOrNull()

        assertTrue("无进展必须抛错，实际正常返回了", failure != null)
        assertTrue(
            "错误信息要能指向「没有进展」，实际：${failure?.message}",
            failure?.message.orEmpty().contains("无进展"),
        )
        // 上限是 MAX_STALLS(5) + 首次，PUT 次数必须远小于"无限"。
        assertTrue("PUT 次数必须有上限，实际 ${server.putRequests().size}", server.putRequests().size <= 10)
    }

    private fun receivedMatches(server: FakeSessionServer): Boolean {
        val actual = server.received.toString().toByteArray(Charsets.ISO_8859_1)
        return actual.size == payload.size && actual.contentEquals(payload)
    }

    private companion object {
        const val BASE_URL = "https://graph.test/v1.0"
        const val SESSION_URL = "$BASE_URL/upload-session-1"
        const val TOKEN = "test-token"
        const val DB_PATH = "Vaultix/vault.kdbx"
        const val FINAL_ETAG = "\"etag-final\""
        const val JSON = "application/json"

        /** `putChunkWithRetry` 的入环次数（= `CHUNK_RETRY_COUNT`，3）。 */
        const val CHUNK_RETRY_ROUND = 3

        /** 与 `OneDriveGraphClient.CHUNK_SIZE_BYTES` 同值（320KiB × 16）。 */
        const val CHUNK = 320 * 1024 * 16

        /** 两片多一点：保证第一片、第二片、尾片都会走到。 */
        const val TOTAL_SIZE = CHUNK * 2 + 1024

        const val FINAL_ITEM_JSON =
            """{"id":"item-1","name":"vault.kdbx","size":$TOTAL_SIZE,""" +
                """"eTag":"\"etag-final\"","lastModifiedDateTime":"2026-11-02T10:00:00Z"}"""
    }
}
