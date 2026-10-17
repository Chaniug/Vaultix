/*
 * Vaultix — app / OneDrive
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * OneDrive **写路径的条件头**（ISSUES #172 的修复是否真的生效）。
 *
 * ## 为什么必须有这一组测试
 *
 * #172 的修复全部押在两个"看不见的行为"上：
 * 1. `failIfExists = true` 时**真的**发出了 `If-None-Match: *`；
 * 2. 服务端回 412 时**真的**翻成了 [OneDrivePreconditionFailedException]。
 *
 * 这两条在真机上要注册 OneDrive 账号、造一个同名文件、再抓包才看得见 ——
 * 一次回归就得重来一遍，实际上等于**永远不会被验证**。
 * 它们本来只是"拼一个请求头"和"映射一个状态码"，是纯逻辑。
 *
 * ## 假服务端为什么手写而不用 MockWebServer
 *
 * MockWebServer 要真端口、真线程、真 socket，app 模块也没引这个依赖。
 * 我们只关心 **请求长什么样**、**响应怎么被解释**，用 OkHttp 自带的
 * [Interceptor] 直接短路、返回构造好的 [Response] 就够了 —— 零新依赖、零端口、毫秒级。
 * 这也正是给 [OneDriveGraphClient] 加 `client` / `baseUrl` 两个构造参数的理由。
 *
 * ## ⚠️ 为什么用 `runBlocking` 而不是 `runTest`
 *
 * 走了两次弯路，都记下来（下次别再踩）：
 * 1. **裸 `runTest`**：它跑在虚拟时间调度器上，而 `upload` 内部是
 *    `withContext(Dispatchers.IO)` —— 真正的线程切换。测试会在拦截器执行**之前**
 *    就断言"没收到请求"，6 例全红。
 * 2. **`runTest(Dispatchers.Default)`**：直接抛
 *    `Dispatcher must implement TestDispatcher: Dispatchers.Default` ——
 *    `runTest` 的 context 只接受 `TestDispatcher`，"挂到真实调度器"这个想法它不支持。
 *
 * ⇒ 用 `runBlocking`。这里要验的是**真实请求长什么样**（真线程、真调度），
 * 不是协程调度顺序，本来就不需要虚拟时间。
 *
 * ## 变异验证记录（还原成出 bug 的版本，看测试是否精确报红）
 *
 * | 变异 | 期望报红的用例 |
 * |---|---|
 * | 删掉 `simpleUpload` 的 `else if (failIfExists) header("If-None-Match","*")` | 只创建时简单上传必须发 If-None-Match 星号 |
 * | 删掉 `simpleUpload` 的 412 分支（落到 else 的 IOException） | 条件写 412 必须映射成前置条件失败异常 |
 * | 去掉 `createUploadSession` 里的 `!failIfExists` | 只创建时分片上传会话请求体不得带 ifMatch |
 * | 删掉 `forceReplace` 分支（让它继续走 If-Match） | 强制覆盖不得携带任何条件头 |
 */
class OneDriveGraphClientWriteTest {

    // ───────────────────────── 夹具 ─────────────────────────

    /**
     * 记录收到的请求，并按预设状态码作答。
     *
     * ⚠️ 默认响应体必须是一段**合法** JSON：`simpleUpload` 的成功分支会解析它，
     * 回空体会抛反序列化异常 —— 那测的就不是条件头了。
     */
    private class FakeGraphServer(
        private val respondCode: Int = 200,
        private val respondBody: String = DRIVE_ITEM_JSON,
    ) : Interceptor {
        /**
         * 收到的**所有**请求（按顺序）。
         *
         * ⚠️ 不能只留"最后一个"：分片路径一次上传会发 N 个请求，
         * 要断言的 createUploadSession 是**第一个**，最后一个一定是片 ——
         * 只留最后一个会让那条测试永远断言在错误对象上（而且可能"恰好通过"）。
         */
        val requests = mutableListOf<Request>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            requests += request
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(respondCode)
                .message(if (respondCode == 200) "OK" else "Failure")
                .body(respondBody.toResponseBody(JSON_MEDIA_TYPE.toMediaType()))
                .build()
        }

        fun last(): Request = requireNotNull(requests.lastOrNull()) { "假服务端没有收到任何请求" }

        /** 片请求的 URL 是会话返回的一次性地址，不含这个字样，正好用来区分。 */
        fun sessionRequest(): Request = requireNotNull(
            requests.firstOrNull { it.url.toString().contains("createUploadSession") },
        ) { "没有收到 createUploadSession 请求，实际收到：${requests.map { it.url }}" }
    }

    private fun clientWith(server: FakeGraphServer) = OneDriveGraphClient(
        client = OkHttpClient.Builder().addInterceptor(server).build(),
        baseUrl = FAKE_BASE_URL,
    )

    // ───────────────────────── 只创建 ─────────────────────────

    @Test
    fun `只创建时简单上传必须发 If-None-Match 星号`() = runBlocking {
        val server = FakeGraphServer()
        val graph = clientWith(server)

        runCatching {
            graph.upload(
                accessToken = TOKEN,
                path = DB_PATH,
                bytes = SMALL_BYTES,
                expectedETag = null,
                forceReplace = false,
                failIfExists = true,
            )
        }

        val request = server.last()
        assertEquals("PUT", request.method)
        assertEquals(
            "failIfExists 必须发 If-None-Match: *，否则同名文件会被直接覆盖（#172）",
            "*",
            request.header("If-None-Match"),
        )
        // 两者同时出现会让服务端收到互相矛盾的前置条件（一个要求"还是那版"，
        // 一个要求"不能存在"），实际表现是莫名其妙的 412。
        assertNull("只创建时不得再带 If-Match", request.header("If-Match"))
    }

    @Test
    fun `只创建时不得附加 conflictBehavior=replace`() = runBlocking {
        val server = FakeGraphServer()
        val graph = clientWith(server)

        runCatching {
            graph.upload(
                accessToken = TOKEN,
                path = DB_PATH,
                bytes = SMALL_BYTES,
                expectedETag = null,
                forceReplace = false,
                failIfExists = true,
            )
        }

        val url = server.last().url.toString()
        // `conflictBehavior=replace` 会让服务端**跳过**前置条件检查，
        // 那 If-None-Match 就白发了 —— 这是个"看起来对、实际全开"的组合。
        assertTrue(
            "只创建时 URL 里不能出现 conflictBehavior=replace：$url",
            !url.contains("conflictBehavior=replace"),
        )
    }

    @Test
    fun `条件覆盖时必须发 If-Match 且不得发 If-None-Match`() = runBlocking {
        val server = FakeGraphServer()
        val graph = clientWith(server)

        runCatching {
            graph.upload(
                accessToken = TOKEN,
                path = DB_PATH,
                bytes = SMALL_BYTES,
                expectedETag = ETAG_V1,
                forceReplace = false,
                failIfExists = false,
            )
        }

        val request = server.last()
        assertEquals(ETAG_V1, request.header("If-Match"))
        assertNull(request.header("If-None-Match"))
    }

    @Test
    fun `强制覆盖不得携带任何条件头`() = runBlocking {
        val server = FakeGraphServer()
        val graph = clientWith(server)

        runCatching {
            graph.upload(
                accessToken = TOKEN,
                path = DB_PATH,
                bytes = SMALL_BYTES,
                expectedETag = ETAG_V1,
                forceReplace = true,
                failIfExists = false,
            )
        }

        val request = server.last()
        // 用户已明确选择"用本地覆盖远端" ⇒ 再带 If-Match 必然 412，
        // 表现就是"点了覆盖什么也没发生，只能反复点"。
        assertNull("forceReplace 时不得带 If-Match", request.header("If-Match"))
        assertNull("forceReplace 时不得带 If-None-Match", request.header("If-None-Match"))
        assertTrue(
            "forceReplace 必须用 conflictBehavior=replace：${request.url}",
            request.url.toString().contains("conflictBehavior=replace"),
        )
    }

    // ───────────────────────── 分片路径 ─────────────────────────

    @Test
    fun `只创建时分片上传会话请求体不得带 ifMatch`() = runBlocking {
        // 会话响应里必须带 uploadUrl，否则 createUploadSession 抛"未返回上传会话地址"。
        val server = FakeGraphServer(respondBody = """{"uploadUrl":"$FAKE_BASE_URL/upload-session-1"}""")
        val graph = clientWith(server)

        // 超过 2MiB 才走分片：先 createUploadSession，再逐片 PUT。
        runCatching {
            graph.upload(
                accessToken = TOKEN,
                path = DB_PATH,
                bytes = ByteArray(SIMPLE_UPLOAD_LIMIT_PLUS_ONE),
                expectedETag = ETAG_V1,
                forceReplace = false,
                failIfExists = true,
            )
        }

        val body = server.sessionRequest().bodyText()
        assertTrue(
            "只创建时分片会话必须 conflictBehavior=fail，实际：$body",
            body.contains("\"@microsoft.graph.conflictBehavior\":\"fail\""),
        )
        // ★ 关键：即便调用方**同时**传了 eTag，"只创建"也必须忽略它。
        //   带了 ifMatch 会放过"远端恰好还是那一版"的覆盖 —— 那等于把新建变成了覆盖。
        assertTrue(
            "只创建时分片会话不得带 ifMatch（会把新建变成覆盖），实际：$body",
            !body.contains("ifMatch"),
        )
    }

    // ───────────────────────── 路径编码 ─────────────────────────

    @Test
    fun `中文与空格的路径必须编码成百分号形式而不是加号`() = runBlocking {
        val server = FakeGraphServer()
        val graph = clientWith(server)

        runCatching {
            graph.upload(
                accessToken = TOKEN,
                path = "我的 密码库/王 五.kdbx",
                bytes = SMALL_BYTES,
                expectedETag = null,
                forceReplace = false,
                failIfExists = true,
            )
        }

        val url = server.last().url.toString()
        // ⚠️ 空格必须是 %20：路径里的 "+" 是**字面加号**，不是空格（RFC 3986）。
        //    用 URLEncoder 那套表单编码会把它编成 "+"，Graph 当成加号去找文件 ⇒ 404，
        //    而用户看到的只是"找不到该文件"（2026-09-17 真实踩过）。
        assertTrue("空格必须编成 %20，实际 URL：$url", url.contains("%20"))
        assertTrue("路径里不得出现裸的 +，实际 URL：$url", !url.contains('+'))
        assertTrue("中文必须被百分号编码，实际 URL：$url", !url.contains("我的"))
        // 校验一下具体字节对，防止"编了但编错"
        assertTrue("%E7%8E%8B%20%E4%BA%94 应出现在 URL 里：$url", url.contains("%E7%8E%8B%20%E4%BA%94"))
    }

    // ───────────────────────── 412 映射 ─────────────────────────

    @Test
    fun `条件写 412 必须映射成前置条件失败异常`() = runBlocking {
        val server = FakeGraphServer(respondCode = 412, respondBody = ERROR_JSON)
        val graph = clientWith(server)

        try {
            graph.upload(
                accessToken = TOKEN,
                path = DB_PATH,
                bytes = SMALL_BYTES,
                expectedETag = ETAG_V1,
                forceReplace = false,
                failIfExists = false,
            )
            fail("412 必须抛异常，实际正常返回了")
        } catch (expected: OneDrivePreconditionFailedException) {
            // 这是"失败了好事"：条件写生效、没有覆盖别人。上层据此转成冲突让用户处理。
            assertTrue(
                "异常消息应说明是 412 冲突，实际：${expected.message}",
                expected.message.orEmpty().contains("412"),
            )
        } catch (wrong: Throwable) {
            // 精确报红：类型错了就是错了，不能说"抛了异常就算过"。
            fail("412 应翻成 OneDrivePreconditionFailedException，实际是 ${wrong::class.java.name}")
        }
    }

    // ───────────────────────── 小工具 ─────────────────────────

    private fun Request.bodyText(): String {
        val buffer = okio.Buffer()
        body?.writeTo(buffer)
        return buffer.readUtf8()
    }

    private companion object {
        const val FAKE_BASE_URL = "https://graph.test/v1.0"
        const val TOKEN = "test-token"
        const val DB_PATH = "Vaultix/vault.kdbx"
        const val ETAG_V1 = "\"etag-v1\""

        const val JSON_MEDIA_TYPE = "application/json"
        const val ERROR_JSON = """{"error":{"code":"preconditionFailed"}}"""
        const val DRIVE_ITEM_JSON =
            """{"id":"item-1","name":"vault.kdbx","size":10,""" +
                """"eTag":"\"etag-v2\"","lastModifiedDateTime":"2026-10-17T10:00:00Z"}"""

        /** 刚好越过 2MiB 简单上传阈值。 */
        const val SIMPLE_UPLOAD_LIMIT_PLUS_ONE = 2 * 1024 * 1024 + 1

        val SMALL_BYTES = ByteArray(64) { it.toByte() }
    }
}
