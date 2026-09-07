package com.bastion.app.webdav

import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * WebDAV 客户端工厂。
 *
 * 统一构造带预置式 Basic Auth、速率限制拦截、User-Agent 注入的
 * [OkHttpSardine] 客户端。调用方（主要是 [com.bastion.app.utils.WebDavHelper]）
 * 只需通过该入口构造 sardine 客户端即可获得与 Kazumi (`webdav_client`) 等价的
 * 请求行为。
 */
object WebDavGateway {

    private const val CONNECT_TIMEOUT_SECONDS: Long = 10L
    private const val READ_TIMEOUT_SECONDS: Long = 12L
    private const val WRITE_TIMEOUT_SECONDS: Long = 12L
    private const val CALL_TIMEOUT_SECONDS: Long = 15L

    /**
     * 大文件（kdbx 密码库）传输用的超时配置。
     *
     * [CALL_TIMEOUT_SECONDS] 覆盖的是「整通调用」，包含请求体上传。
     * 一个几 MB 的 kdbx 在弱网下上传远超 15 秒，会被无条件掐断并报超时，
     * 表现为「同步总是失败但小文件备份正常」。
     * 故单独放宽 write/call，read 仅指响应首字节间隔，保持原值即可。
     */
    private const val BULK_CONNECT_TIMEOUT_SECONDS: Long = 15L
    private const val BULK_WRITE_TIMEOUT_SECONDS: Long = 120L
    private const val BULK_CALL_TIMEOUT_SECONDS: Long = 300L

    /** 全局共享连接池，避免各组件各自建池导致僵尸连接堆积。 */
    private val sharedConnectionPool = ConnectionPool(8, 2, TimeUnit.MINUTES)

    /**
     * 构造已配置好 OkHttp 拦截器链的裸 [OkHttpClient]。
     *
     * 供 [buildClient] 与需要直连 OkHttp 的组件（如 [WebDavConditionalWriter]）复用，
     * 保证认证/限流/UA 行为一致。
     */
    fun buildOkHttpClient(credentials: WebDavCredentials): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // 共享连接池 + 连接失败自动重试：
            // 缺了这两项，OkHttp 默认池可能长期复用已失效的 socket，
            // 导致请求永久挂起（Bitwarden 同步侧曾出现同类问题）。
            .connectionPool(sharedConnectionPool)
            .retryOnConnectionFailure(true)
            .addInterceptor(PreemptiveBasicAuthInterceptor(credentials))
            .addInterceptor(RateLimitInterceptor())
            .addInterceptor(UserAgentInterceptor())
            .build()
    }

    /**
     * 构造一个已配置好 OkHttp 拦截器链的 sardine 客户端。
     *
     * 重要：不再调用 `sardine.setCredentials(...)`，因为凭据已由
     * [PreemptiveBasicAuthInterceptor] 预置到每个请求中；双重设置反而会让
     * sardine 走 challenge-response 逻辑，与 OpenList 的速率策略冲突。
     */
    fun buildClient(credentials: WebDavCredentials): OkHttpSardine {
        return OkHttpSardine(buildOkHttpClient(credentials))
    }

    /**
     * 构造适合大文件传输的 sardine 客户端（kdbx 密码库同步 / 大备份上传）。
     *
     * 与 [buildClient] 的差异仅在超时：放宽 write/call，避免多 MB 的
     * kdbx 在弱网下被 15 秒的 callTimeout 掐断。认证/限流/UA 行为完全一致。
     */
    fun buildBulkClient(credentials: WebDavCredentials): OkHttpSardine {
        val client = OkHttpClient.Builder()
            .connectTimeout(BULK_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(BULK_WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(BULK_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .connectionPool(sharedConnectionPool)
            .retryOnConnectionFailure(true)
            .addInterceptor(PreemptiveBasicAuthInterceptor(credentials))
            .addInterceptor(RateLimitInterceptor())
            .addInterceptor(UserAgentInterceptor())
            .build()
        return OkHttpSardine(client)
    }

    /** 从任意 URL 字符串中提取 host；若无法解析返回空串。 */
    fun hostOf(url: String): String = WebDavUrlBuilder.hostOf(url)
}
