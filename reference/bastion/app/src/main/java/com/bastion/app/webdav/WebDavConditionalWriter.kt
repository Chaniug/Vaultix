package com.bastion.app.webdav

import com.bastion.app.utils.KEEPASS_KDBX_MIME_TYPE
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * WebDAV 条件写入模式。
 */
enum class WebDavWriteMode {
    /** 仅当远端不存在时创建（`If-None-Match: *`） */
    CREATE_ONLY,

    /** 仅当远端 ETag 匹配时覆盖（`If-Match: <etag>`） */
    IF_MATCH,

    /** 无条件覆盖 */
    REPLACE
}

/**
 * WebDAV 前置条件失败（HTTP 409 / 412）。
 *
 * 表示服务端拒绝了条件写入（目标已被其它客户端修改或已存在），
 * 调用方应提示用户先重新同步。
 */
class WebDavPreconditionException(
    val statusCode: Int,
    responseBody: String
) : IOException(
    "HTTP $statusCode: " + responseBody.ifBlank { "WebDAV 前置条件校验失败" }
)

/**
 * 基于 OkHttp 的 WebDAV 条件写入器。
 *
 * 在 HTTP/WebDAV 边界上由服务端强制前置条件，使"检查-写入"原子化，
 * 消除 stat→PUT 窗口内的并发覆盖（TOCTOU）。参考上游 Monica 的
 * `WebDavConditionalWriter`：sardine-android 0.8 不支持 `If-Match` 条件头
 * （lockToken 走 `If` header），因此直接使用 OkHttp 发出带条件头的 PUT。
 *
 * 使用方式：
 * - 首次创建：`WebDavWriteMode.CREATE_ONLY`（`If-None-Match: *`）
 * - 覆盖更新：先 `stat()` 拿到远端 ETag，再 `WebDavWriteMode.IF_MATCH` 传入该 ETag
 * - 强制覆盖：`WebDavWriteMode.REPLACE`
 */
class WebDavConditionalWriter(
    private val httpClient: OkHttpClient
) {
    fun write(
        targetUrl: String,
        bytes: ByteArray,
        mode: WebDavWriteMode,
        expectedVersion: String?
    ) {
        val request = Request.Builder()
            .url(targetUrl)
            .put(bytes.toRequestBody(KEEPASS_KDBX_MIME_TYPE.toMediaType()))
            .apply {
                when (mode) {
                    WebDavWriteMode.CREATE_ONLY -> header(HEADER_IF_NONE_MATCH, "*")
                    WebDavWriteMode.IF_MATCH -> header(
                        HEADER_IF_MATCH,
                        expectedVersion?.takeIf { it.isNotBlank() }
                            ?: throw IllegalArgumentException("WebDAV 条件覆盖必须提供 ETag")
                    )
                    WebDavWriteMode.REPLACE -> Unit
                }
            }
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (response.code in SUCCESS_CODES) return
            val body = response.body?.string().orEmpty()
            when (response.code) {
                409, 412 -> throw WebDavPreconditionException(response.code, body)
                else -> throw IOException(
                    body.ifBlank { "WebDAV 写入失败: HTTP ${response.code}" }
                )
            }
        }
    }

    private companion object {
        const val HEADER_IF_MATCH = "If-Match"
        const val HEADER_IF_NONE_MATCH = "If-None-Match"
        val SUCCESS_CODES = setOf(200, 201, 204)
    }
}
