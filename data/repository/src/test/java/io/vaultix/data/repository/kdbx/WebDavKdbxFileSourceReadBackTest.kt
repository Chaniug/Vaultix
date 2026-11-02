/*
 * Vaultix — data:repository（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.data.repository.kdbx

import io.vaultix.data.kdbx.KdbxFileConflictException
import io.vaultix.data.kdbx.KdbxFileWriteResult
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * WebDAV 写入的**写后读回校验**：读不回来**不许**当成写成功（2026-11-02）。
 *
 * ## 修掉的是哪一行
 *
 * 旧实现：
 * ```kotlin
 * val readBack = runCatching { read() }
 * if (readBack.isSuccess && !readBack.getOrThrow().contentEquals(expected)) throw …
 * ```
 * `isSuccess` 为假时直接返回 —— "读不回来"被静默当成"写成功了"。
 * 这恰好是 `OneDriveKdbxFileSource` 的 KDoc 点名禁止的那一类：
 * **用户以为存好了，其实没有**，而且下次打开才发现，那时本地已改过几轮。
 *
 * ## 为什么必须用假服务器，不能只测那个私有函数
 *
 * 这个判定的价值全在"PUT 成功之后"的那一步。若只把 `verifyReadBack` 抽出来单独测，
 * 就测不到它的**调用点** —— 而这里最容易犯的错恰恰是调用点：
 * `writeConditionally` 和 `forceReplace` 两条路都必须过校验（历史上强写那条就是后补的）。
 * 所以用 [Interceptor] 搭一个假 WebDAV：PUT 返回 200，紧随其后的 GET 按需失败。
 *
 * ⚠️ 三种结局各一例，且第三例（内容不一致）是**回归护栏** ——
 * 防止将来有人为了"别动不动报冲突"把校验整段删掉。
 */
class WebDavKdbxFileSourceReadBackTest {

    /**
     * 一个只关心"PUT 之后那次 GET"的假 WebDAV。
     *
     * @param readBack 读回时返回什么：[ReadBack.Same] 内容一致、[ReadBack.Different]
     *   内容被改了、[ReadBack.Fail] 读请求本身失败。
     * @param etag HEAD 返回的 ETag（`null` = 服务器不给 ETag，这是坑 2 的场景）。
     */
    private class FakeWebDav(
        private val readBack: ReadBack,
        private val etag: String? = ETAG,
        /** 待写入的字节：由用例传入，这样"内容不一致"那条才有东西可比。 */
        private val uploaded: ByteArray,
    ) : Interceptor {

        val requests = mutableListOf<Request>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            requests += request
            val isReadBack = request.method == "GET" && requests.count { it.method == "PUT" } > 0
            return when {
                request.method == "HEAD" -> respond(request, 200, ByteArray(0), etag)
                request.method == "PUT" -> respond(request, 200, ByteArray(0), etag)
                isReadBack -> readBackResponse(request)
                request.method == "GET" -> respond(request, 200, uploaded, etag)
                else -> respond(request, 405, "{}".toByteArray(), etag)
            }
        }

        private fun readBackResponse(request: Request): Response = when (readBack) {
            // 内容一致：假服务器把我们 PUT 的字节原样还回来。
            ReadBack.Same -> respond(request, 200, uploaded, etag)
            // ★ 被别的设备覆盖了：长度相同、内容不同（最阴的一种，只看大小看不出来）。
            ReadBack.Different -> respond(request, 200, uploaded.reversedArray(), etag)
            // ★ 读不回来：服务器回了 503（"写的时候好好的，读的时候没了"）。
            ReadBack.Fail -> respond(request, 503, "unavailable".toByteArray(), etag)
        }

        private fun respond(
            request: Request,
            code: Int,
            body: ByteArray,
            etag: String?,
        ): Response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code < 300) "OK" else "Failure")
            .apply { etag?.let { header("ETag", it) } }
            .body(body.toResponseBody(KDBX))
            .build()
    }

    private enum class ReadBack { Same, Different, Fail }

    private fun source(server: FakeWebDav, credentialsFail: Boolean = false) = WebDavKdbxFileSource(
        client = OkHttpClient.Builder().addInterceptor(server).build(),
        fileUrl = FILE_URL,
        credentialProvider = {
            if (credentialsFail) throw IllegalStateException("凭据读取失败（模拟 Keystore 被重置）")
            WebDavCredentials("alice", "s3cret")
        },
    )

    /** 走的是 `write(expectedVersion = TOKEN)` 那条常规覆盖路径（带 `If-Match`）。 */
    private suspend fun writeExisting(source: WebDavKdbxFileSource): KdbxFileWriteResult =
        source.write(SAMPLE_BYTES, expectedVersion = TOKEN, force = false, failIfExists = false)


    // ───────────────────────── 三种结局 ─────────────────────────

    @Test
    fun `读回内容一致时必须正常返回新的版本令牌`() {
        val server = FakeWebDav(ReadBack.Same, uploaded = SAMPLE_BYTES)
        // ⚠️ `kotlinx-coroutines-test` 1.11.0 起 `runTest` **返回 Unit**（块内结果不再传出），
        //    所以不能写 `val result: KdbxFileWriteResult = runTest { … }` ——
        //    那会报 `Initializer type mismatch: expected 'KdbxFileWriteResult', actual 'Unit'`，
        //    看着像"write 返回类型写错了"，其实是 runTest 的签名变了。
        var result: KdbxFileWriteResult? = null
        runTest { result = writeExisting(source(server)) }

        assertEquals("读回一致就该拿到服务器给的新令牌", TOKEN, result?.versionToken)
        assertEquals("大小必须是我们写下去的字节数", SAMPLE_BYTES.size.toLong(), result?.sizeBytes)
        // 时间线：HEAD(预检) → PUT → GET(读回)。少一次 GET 就说明校验被绕过了。
        assertEquals(
            "写路径必须依次是 HEAD / PUT / GET，实际：${server.requests.map { it.method }}",
            listOf("HEAD", "PUT", "GET"),
            server.requests.map { it.method },
        )
    }

    @Test
    fun `读回内容不一致时必须报冲突而不是当成功`() {
        val server = FakeWebDav(ReadBack.Different, uploaded = SAMPLE_BYTES)
        try {
            runTest { writeExisting(source(server)) }
            fail("读回内容与写下去的不一致，必须报冲突；实际当成写成功了")
        } catch (expected: KdbxFileConflictException) {
            assertTrue(
                "冲突文案要说明是读回校验不一致，实际：${expected.message}",
                expected.message.orEmpty().contains("读回校验不一致"),
            )
            // ★ 冲突里必须带上**当前**版本令牌：用户下一步大概率选"用远端覆盖本地"，
            //   那时手上必须有冲突之后的令牌才能写成功。
            assertEquals("冲突必须附带**归一化后**的当前版本令牌", TOKEN, expected.currentVersion)
        }
    }

    /**
     * ★ 本次修掉的那个 bug：读不回来曾经被静默当成成功。
     *
     * ⚠️ 这条是最重要的一条：它描述的不是"极端情况"，而是**服务器 503 / 网络断了**
     * 这种日常就会发生的事。旧实现下用户会看到"保存成功"，然后改动就那样消失了。
     */
    @Test
    fun `读回请求本身失败时必须报冲突而不是当成功`() {
        val server = FakeWebDav(ReadBack.Fail, uploaded = SAMPLE_BYTES)
        try {
            runTest { writeExisting(source(server)) }
            fail("读回失败（503）被当成了写成功 —— 这正是「以为存好了其实没有」")
        } catch (expected: KdbxFileConflictException) {
            assertTrue(
                "冲突文案要说明是**读不回来**（不是内容不一致），实际：${expected.message}",
                expected.message.orEmpty().contains("无法读回校验"),
            )
            // 原始异常必须挂在 cause 上：否则"到底哪一步坏了"就失去唯一线索。
            assertTrue("原始读取异常必须保留在 cause 上", expected.cause != null)
        }
    }

    // ───────────────────────── 强写那条路同样要校验 ─────────────────────────

    /**
     * `force = true`（用户点了「用本地覆盖远端」）走的是 [forceReplace]，**另一条**代码路径。
     *
     * ⚠️ 强写的语义是"不管远端是什么，用我的盖上"，**不是**"写完不检查"。
     * 历史上这条路的校验就是后补的，所以用一条独立的用例钉住它 ——
     * 否则将来重构把它们合并时，很容易只保住其中一条。
     */
    @Test
    fun `强制覆盖之后读回不一致同样必须报冲突`() {
        val server = FakeWebDav(ReadBack.Different, uploaded = SAMPLE_BYTES)
        try {
            runTest {
                source(server).write(SAMPLE_BYTES, expectedVersion = null, force = true, failIfExists = false)
            }
            fail("强制覆盖之后读回不一致，仍然必须报冲突")
        } catch (expected: KdbxFileConflictException) {
            assertTrue(
                "强写路径的文案同样要说清是读回不一致，实际：${expected.message}",
                expected.message.orEmpty().contains("读回校验不一致"),
            )
        }
        // 强写**开头**不能有预检 HEAD（带了必然 412，用户点了却没反应）。
        // ⚠️ 也不能断言整条时间线只有 PUT + GET：报冲突时 `conflictOf` 会**故意**再 stat 一次
        //    去补当前版本令牌，那是一次合法的 HEAD，出现在末尾。所以只看 PUT 之前的请求。
        val beforePut = server.requests.takeWhile { it.method != "PUT" }.map { it.method }
        assertEquals("强制覆盖在 PUT 之前不得做任何预检，实际：$beforePut", emptyList<String>(), beforePut)
    }

    /**
     * 服务器不给 ETag（坑 2）：校验就是这条路上**唯一**的保障，更不能漏。
     */
    @Test
    fun `服务器不给ETag时读回失败依然必须报冲突`() {
        val server = FakeWebDav(ReadBack.Fail, etag = null, uploaded = SAMPLE_BYTES)
        try {
            runTest { writeExisting(source(server)) }
            fail("没有 ETag 的服务器上，读回校验是唯一保障，失败不能当成功")
        } catch (expected: KdbxFileConflictException) {
            // 拿不到 ETag ⇒ currentVersion 只能是 null，上层据此再 stat 一次。
            assertEquals("没有 ETag 时冲突令牌应为 null", null, expected.currentVersion)
        }
    }

    // ───────────────────────── 反证：别把"正常成功"也拦下来 ─────────────────────────

    /**
     * 反证：校验不能矫枉过正 —— 内容一致时必须**不抛**。
     *
     * 用 `write` 的返回值直接比对字节，确保假服务器收到的确实是我们的内容
     * （否则上一条"内容不一致"的用例可能只是假服务器自己写错了）。
     */
    @Test
    fun `假服务器收到的字节必须与被写入的内容逐字节相同`() {
        val server = FakeWebDav(ReadBack.Same, uploaded = SAMPLE_BYTES)
        runTest { writeExisting(source(server)) }

        val put = server.requests.single { it.method == "PUT" }
        val buffer = okio.Buffer()
        put.body?.writeTo(buffer)
        assertArrayEquals("PUT 的请求体必须就是待写字节", SAMPLE_BYTES, buffer.readByteArray())
        // 条件头也要在：常规覆盖必须带 If-Match，否则就是无条件覆盖（会静默盖掉别人的改动）。
        assertEquals("覆盖必须带归一化后的 If-Match", TOKEN, put.header("If-Match"))
    }

    private companion object {
        const val FILE_URL = "https://nas.local/dav/Vaultix/db.kdbx"

        /**
         * 服务器给的**原始** ETag（带强引号）。
         *
         * ⚠️ 归一化会**剥掉引号**（见 `normalizeVersionToken`），所以：
         * 写请求头里发出去的是 `v1`、冲突里带回来的也是 `v1`；
         * 而 `If-Match` 在假服务器上看**收到的**值，同样是 `v1`。
         * 之前这里断言 `"\"v1\""` 是错的（把原始形态当成了归一化后的形态）。
         */
        const val ETAG = "\"v1\""

        /** 归一化后的版本令牌（剥掉 `W/` 与引号）。 */
        const val TOKEN = "v1"
        val KDBX = "application/x-keepass2".toMediaType()

        /**
         * 假内容（64 字节，够用来比"相同 / 不同 / 长度"）。
         *
         * ⚠️ 名字**不带** `UPLOADED`：假服务器里有个同名字段，两者一撞，
         * 服务器那边的 `UPLOADED` 就会解析到字段而不是常量，编译期报
         * `Initializer type mismatch`（看着完全不像遮蔽问题）。
         */
        val SAMPLE_BYTES = ByteArray(64) { index -> ((index * 7 + 3) % 256).toByte() }
    }
}
