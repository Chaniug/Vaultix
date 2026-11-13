/*
 * Vaultix — data:repository（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * #288（2026-11-13）：真机「坚果云 WebDAV 登录不上去」。
 *
 * 现场：填 `https://dav.jianguoyun.com/dav/` + 邮箱 + 密码 → 点「测试连接」→
 * 红字「WebDAV 账号或密码不正确（HTTP 403）」。
 *
 * 沙箱匿名差分实测（不触碰用户真实密码）：
 *   - 无凭据 / 假凭据，HEAD / GET / PROPFIND / OPTIONS、三种 UA ⇒ **一律 401**
 *     + `WWW-Authenticate: Basic realm="nutstore"`；
 *   - 用户带凭据却拿 **403** ⇒ 认证已通过，是服务端主动拒绝，
 *     官方 FAQ 给的原因是「微信二次验证 / 访问频率 / 应用密码被撤销 / 目录权限」。
 * ⇒ 旧实现的两个错：① 把 403 谎报成密码错（用户只会无限重输）；
 *   ② 自检用 HEAD 探**目录**，与 `listChildren()` 的 PROPFIND 不同源。
 *
 * ⚠️ 单测里假服务器用的是**干净 OkHttpClient**（见 `client()`），
 *    而生产用的全局单例挂了 `BitwardenAuthInterceptor` —— 那个坑靠
 *    `专用client必须清掉共享拦截器且保留Basic` 一条单独钉住，别指望这些用例能发现它。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.repository.kdbx

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavConnectionTest {

    /** 按请求决定回什么码，并把收到的请求原样记下来（含被拦截器改过的头）。 */
    private class FakeDav(private val statusFor: (Request) -> Int) : Interceptor {
        val requests = mutableListOf<Request>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            requests += request
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(statusFor(request))
                .message("probe")
                .header("ETag", "\"v1\"")
                .body(ByteArray(0).toResponseBody("application/xml".toMediaType()))
                .build()
        }
    }

    private fun client(fake: FakeDav): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(fake).build()

    private fun source(client: OkHttpClient): WebDavKdbxFileSource = WebDavKdbxFileSource(
        client = client,
        fileUrl = FILE_URL,
        credentialProvider = { WebDavCredentials("chaniug@vip.qq.com", "app-password") },
    )

    @Test
    fun `自检必须发PROPFIND_Depth0而不是HEAD`() = runBlocking {
        val fake = FakeDav { 207 }
        val outcome = source(client(fake)).testConnection()

        assertTrue("207 应判成功：${outcome.exceptionOrNull()}", outcome.isSuccess)
        val first = fake.requests.single()
        assertEquals("PROPFIND", first.method)
        assertEquals("0", first.header("Depth"))
        assertTrue(
            "必须带 Basic 认证头，实际=${first.header("Authorization")}",
            first.header("Authorization")?.startsWith("Basic ") == true,
        )
    }

    /** 反证：若只把 HEAD 换成 PROPFIND 却漏了 405 降级，老 NAS 会全线连不上。 */
    @Test
    fun `PROPFIND被明确拒绝时降级HEAD且判据同源`() = runBlocking {
        val fake = FakeDav { if (it.method == "PROPFIND") 405 else 200 }
        val outcome = source(client(fake)).testConnection()

        assertTrue("405 应降级 HEAD 并成功：${outcome.exceptionOrNull()}", outcome.isSuccess)
        assertEquals(listOf("PROPFIND", "HEAD"), fake.requests.map { it.method })
        // 降级那一发也必须带 Basic，不能因为换方法就漏认证。
        assertTrue(fake.requests[1].header("Authorization")?.startsWith("Basic ") == true)
    }

    @Test
    fun `坚果云403必须说清不是密码错并给出可操作原因`() = runBlocking {
        val fake = FakeDav { 403 }
        val outcome = source(client(fake)).testConnection()

        assertTrue(outcome.isFailure)
        val message = outcome.exceptionOrNull()?.message.orEmpty()
        assertTrue("应点明状态码：$message", message.contains("403"))
        assertTrue("必须明确否定「密码错」：$message", message.contains("不是密码错"))
        assertFalse("403 绝不能再谎报账号或密码不正确：$message", message.contains("账号或密码不正确"))
        listOf("二次验证", "应用密码", "频率", "目录").forEach { reason ->
            assertTrue("403 文案缺可操作原因「$reason」：$message", message.contains(reason))
        }
    }

    @Test
    fun `401仍然报账号或密码不正确但要顺带提示应用密码`() = runBlocking {
        val fake = FakeDav { 401 }
        val outcome = source(client(fake)).testConnection()

        assertTrue(outcome.isFailure)
        val message = outcome.exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("账号或密码不正确（HTTP 401）"))
        assertTrue("坚果云用登录密码是 401 的头号成因，必须点名：$message", message.contains("应用密码"))
    }

    /** 旧实现把 404 当「连接是通的」，可这里传的是目录 URL —— 404 只可能是地址填错。 */
    @Test
    fun `目录404不再假报连接成功`() = runBlocking {
        val fake = FakeDav { 404 }
        val outcome = source(client(fake)).testConnection()

        assertTrue(outcome.isFailure)
        assertTrue(
            "应提示检查服务器地址：${outcome.exceptionOrNull()?.message}",
            outcome.exceptionOrNull()?.message?.contains("找不到该目录") == true,
        )
    }

    /** `errorMessage()` 是 stat/read/listChildren/强写的共同出口，不能只在 writeFailureOf 分流。 */
    @Test
    fun `stat与listChildren的403也必须分流`() = runBlocking {
        val statMessage = runCatching {
            source(client(FakeDav { 403 })).stat()
        }.exceptionOrNull()?.message.orEmpty()
        assertTrue("stat 的 403 不该说密码错：$statMessage", statMessage.contains("不是密码错"))

        val listMessage = runCatching {
            source(client(FakeDav { 403 })).listChildren()
        }.exceptionOrNull()?.message.orEmpty()
        assertTrue("listChildren 的 403 不该说密码错：$listMessage", listMessage.contains("不是密码错"))
    }

    /**
     * ★ 生产链路的 P0 反证：全局 client 挂的 Bearer 拦截器会覆盖 Basic、并把
     * Bitwarden token 发到用户自己的 NAS。撤掉 `webDavProductionClient()` 里的
     * `interceptors().clear()`，本用例必须变红（hostileRan = true / auth = Bearer）。
     */
    @Test
    fun `专用client必须清掉共享拦截器且保留Basic`() = runBlocking {
        var hostileRan = false
        val hostile = Interceptor { chain ->
            hostileRan = true
            chain.proceed(
                chain.request().newBuilder()
                    .header("Authorization", "Bearer LEAKED-TOKEN")
                    .build(),
            )
        }
        val fake = FakeDav { 207 }
        val shared = OkHttpClient.Builder().addInterceptor(hostile).build()
        // 派生后再挂假服务器（否则 clear() 会把它一起清掉，测不到任何东西）。
        val dedicated = webDavProductionClient(shared).newBuilder()
            .addInterceptor(fake)
            .build()

        val outcome = source(dedicated).testConnection()

        assertFalse("共享的 Bearer 拦截器不该在 WebDAV 请求上执行", hostileRan)
        assertTrue("派生后应能正常自检：${outcome.exceptionOrNull()}", outcome.isSuccess)
        val request = fake.requests.single()
        assertTrue(
            "Authorization 必须仍是 Basic，实际=${request.header("Authorization")}",
            request.header("Authorization")?.startsWith("Basic ") == true,
        )
        assertFalse(
            "Bitwarden access token 不能出现在 WebDAV 请求头里",
            request.headers.toString().contains("LEAKED-TOKEN"),
        )
    }

    private companion object {
        const val FILE_URL = "https://dav.jianguoyun.com/dav/"
    }
}
