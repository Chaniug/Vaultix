/*
 * Vaultix — data:repository
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * ★ 溯源声明（GPL-3.0）：条件写（`If-Match` / `If-None-Match: *`）、
 *   ETag 归一化、写后读回校验三项机制对照 Bastion 的
 *   `com.bastion.app.utils.WebDavKeePassFileSource` 与
 *   `com.bastion.app.webdav.WebDavConditionalWriter`（同项目作者的另一个应用，GPL-3.0）实现。
 *
 * ## ★★ 为什么必须手写 OkHttp（而不是用现成 WebDAV 库）
 *
 * 现成的 Android WebDAV 库（sardine-android 0.8 等）**不支持条件请求头**
 * （它的 lock token 走 `If` 头，而我们要的是 `If-Match` / `If-None-Match`）。
 * 用它们只能"先 PROPFIND 拿 ETag，再 PUT" —— 而这两步之间的窗口
 * 正是 **TOCTOU（检查与使用之间的竞态）**：另一个设备恰好在这时推了新版，
 * 我们就会**静默覆盖它**。这直接违反用户的硬要求「同步不丢」。
 *
 * ⇒ 条件必须在 **HTTP 边界**上交由服务端判定。只有手写 PUT + 条件头能做到。
 *
 * ## ★★ 四个坑（每一个都踩过，注释里写清楚为什么）
 *
 * | # | 坑 | 本文件的做法 |
 * |---|---|---|
 * | 1 | sardine 不支持条件头 | 写路径**完全绕开它**，用 [write] 里的手写 PUT |
 * | 2 | 部分服务器**不给 ETag** | 退化为「写前预检」+**「写后读回校验」**，并**如实标注**能力有限 |
 * | 3 | ★ ETag 形态参差（`W/"x"` vs `"x"`） | 一律过 [normalizeVersionToken]，**比对与发头都用归一化后的值** |
 * | 4 | ★ 新建要用 `If-None-Match: *` | 否则两个设备并发新建同名文件会**互相静默覆盖** |
 *
 * ## 认证
 *
 * **Basic**，放在 `Authorization` 头里。
 * ⚠️ **绝不**把凭据拼进 URL（`https://user:pass@host/...`）—— 那会进日志、
 * 进崩溃报告、进 OkHttp 的 `request.url` 输出。凭据由 [credentialProvider] 现取。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.repository.kdbx

import io.vaultix.data.kdbx.KdbxFileConflictException
import io.vaultix.data.kdbx.KdbxFileEntry
import io.vaultix.data.kdbx.KdbxFileSource
import io.vaultix.data.kdbx.KdbxFileStat
import io.vaultix.data.kdbx.KdbxFileWriteResult
import io.vaultix.data.kdbx.normalizeVersionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64

/** WebDAV 的账号密码（**只在这个对象里存在**，不落 Room、不进日志、不进 origin）。 */
data class WebDavCredentials(val username: String, val password: String) {
    /**
     * Basic 认证头。
     *
     * ⚠️ 用 `Base64.getEncoder()` 而不是 Android 的 `android.util.Base64`：
     * 后者在 JVM 单测里是**空实现**（返回 null / 抛异常），
     * 会让"认证头拼得对不对"这件事无法用单测覆盖。
     */
    fun basicAuthHeader(): String =
        "Basic " + Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
}

/**
 * WebDAV 上的一个 `.kdbx` 文件。
 *
 * @param fileUrl 该文件的**完整** URL（如 `https://nas.local/dav/vault.kdbx`）。
 * @param credentialProvider 现取凭据（**不要**在构造时取一次存起来：用户可能中途改密码，
 *   而缓存下来的旧凭据只会让之后所有请求 401）。
 */
class WebDavKdbxFileSource(
    private val client: OkHttpClient,
    private val fileUrl: String,
    private val credentialProvider: suspend () -> WebDavCredentials,
) : KdbxFileSource {

    override suspend fun stat(): KdbxFileStat = withContext(Dispatchers.IO) {
        val response = execute(
            Request.Builder().url(fileUrl).head().build(),
        )
        response.use {
            if (it.code == HTTP_NOT_FOUND) {
                throw IOException("WebDAV 上找不到该文件：${displayPath(fileUrl)}")
            }
            if (!it.isSuccessful) throw IOException(it.errorMessage(ACTION_STAT))
            KdbxFileStat(
                // ★ 坑 3：**必须归一化**。直接拿原始 ETag 会出现
                //   "同一版却比不相等" ⇒ 误报冲突（用户看到一个不存在的冲突）。
                versionToken = normalizeVersionToken(it.header(HEADER_ETAG)),
                sizeBytes = it.header(HEADER_CONTENT_LENGTH)?.toLongOrNull(),
                lastModified = it.header(HEADER_LAST_MODIFIED)?.let(::parseHttpDate),
                remoteId = fileUrl,
                displayName = displayPath(fileUrl),
            )
        }
    }

    override suspend fun read(): ByteArray = withContext(Dispatchers.IO) {
        val response = execute(
            Request.Builder().url(fileUrl).get().build(),
        )
        response.use {
            if (it.code == HTTP_NOT_FOUND) {
                throw IOException("WebDAV 上找不到该文件：${displayPath(fileUrl)}")
            }
            if (!it.isSuccessful) throw IOException(it.errorMessage(ACTION_READ))
            it.body?.bytes() ?: throw IOException("WebDAV 返回了空响应")
        }
    }

    /**
     * 条件写入 —— 本类的核心。
     *
     * ## 判定逻辑
     *
     * | `expectedVersion` | 用的条件头 | 含义 |
     * |---|---|---|
     * | 为空 | `If-None-Match: *` | ★ 坑 4：**只创建**。文件已存在则服务端拒绝 |
     * | 非空 | `If-Match: <归一化 ETag>` | 覆盖，且**必须还是那一版** |
     *
     * ⚠️ `expectedVersion == null` 时用 `If-None-Match: *` 是**刻意的保守选择**：
     * `null` 的语义是"我不知道远端现在是什么"，而这时**最安全的解释是"我不该覆盖别人"**。
     * 如果调用方确实是想无条件覆盖，应该显式走 [forceReplace] 那条路。
     * 反之若把 null 当成"随便写"，两个设备并发新建同名文件就会互相静默覆盖
     * —— 那是**数据丢失**，不是"小概率"。
     *
     * ## 坑 2：不支持 ETag 的服务器怎么办
     *
     * 拿不到 ETag 时没法做服务端条件判定，于是退化成两道本地保障：
     * ① **写前预检**（再 stat 一次，版本不符就拒）——缩小窗口但不消除；
     * ② ★ **写后读回校验**（`PUT` 成功后立刻 `GET` 一次，比对字节）——
     *    这能**发现**"我们的写被别人的写覆盖了"，从而报错而不是假装成功。
     * ⚠️ 这两道都**不如**服务端条件写，所以 [KdbxFileStat.supportsConditionalWrite]
     *    会返回 false，UI 据此提示用户"该服务器保障较弱"。
     */
    override suspend fun write(
        bytes: ByteArray,
        expectedVersion: String?,
        force: Boolean,
    ): KdbxFileWriteResult =
        // ★ force：用户已在冲突对话框里明确选了「用本地覆盖远端」。
        //   那一刻再做条件检查**必然失败**（远端确实变了 —— 就是这个条件挡住的），
        //   于是用户点了确认却什么也没发生。所以这里走 [forceReplace]。
        if (force) forceReplace(bytes) else writeConditionally(bytes, expectedVersion)

    private suspend fun writeConditionally(
        bytes: ByteArray,
        expectedVersion: String?,
    ): KdbxFileWriteResult =
        withContext(Dispatchers.IO) {
            val normalizedExpected = normalizeVersionToken(expectedVersion)

            // ① 写前预检（仅在"我们有版本令牌"时才有意义；null 走 CREATE_ONLY 语义）
            precheckVersion(normalizedExpected)

            // ② 条件 PUT
            val request = Request.Builder()
                .url(fileUrl)
                .put(bytes.toRequestBody(KDBX_MIME_TYPE.toMediaType()))
                .conditionalHeader(normalizedExpected)
                .build()

            var newVersion: String?
            var lastModified: Long?
            var sizeBytes: Long?
            execute(request).use { response ->
                classifyWriteResponse(response, normalizedExpected)
                newVersion = normalizeVersionToken(response.header(HEADER_ETAG))
                lastModified = response.header(HEADER_LAST_MODIFIED)?.let(::parseHttpDate)
                // PUT 的响应体通常是空的 ⇒ 用本地字节数（我们刚写下去的就是它）；
                // 不要用 Content-Length（它可能是 0，会让上层以为写了个空文件）。
                sizeBytes = bytes.size.toLong()
            }

            // ③ ★ 写后读回校验（坑 2 的第二道保障）。
            //
            //   为什么值得多花一次 GET：**WebDAV 服务器没有事务**。
            //   我们的 PUT 可能"成功返回"却被一个并发的 PUT 覆盖 ——
            //   没有这一步，用户会以为改动已同步，其实早就没了（而且不知道）。
            //   ⚠️ 这一步**不阻断**写成功（数据已经上去了），但一旦发现不一致就报冲突，
            //      让用户知道"刚才那次同步不可信，请重试"。
            verifyReadBack(bytes)

            KdbxFileWriteResult(
                versionToken = newVersion,
                sizeBytes = sizeBytes,
                remoteId = fileUrl,
                lastModified = lastModified,
            )
        }

    /**
     * 写前预检：远端版本与我们的基线不符就拒写。
     *
     * ⚠️ 这一步**只缩小窗口、不消除 TOCTOU**（判定权仍在服务端，见类的 KDoc）。
     * 抽成独立函数是为了让 [writeConditionally] 的主线（预检 → PUT → 校验）一眼可读，
     * 而不是让版本比较的细节把主线埋掉。
     *
     * @param normalizedExpected 归一化后的基线；null = 走 CREATE_ONLY，跳过预检。
     */
    private suspend fun precheckVersion(normalizedExpected: String?) {
        if (normalizedExpected == null) return
        val current = runCatching { stat() }.getOrNull() ?: return
        val currentToken = current.versionToken ?: return
        if (currentToken != normalizedExpected) {
            throw KdbxFileConflictException(
                currentToken,
                "WebDAV 上的文件已被其他设备修改，为避免覆盖已取消写入",
            )
        }
    }

    /**
     * 给请求加上条件头。
     *
     * | [normalizedExpected] | 加的头 | 含义 |
     * |---|---|---|
     * | null | `If-None-Match: *` | ★ 坑 4：只创建。文件已存在则服务端拒绝 |
     * | 非空 | `If-Match: <值>` | 覆盖，且必须还是那一版 |
     */
    private fun Request.Builder.conditionalHeader(normalizedExpected: String?): Request.Builder =
        if (normalizedExpected == null) {
            // ★ 坑 4：CREATE_ONLY。没有它，并发新建同名文件会互相覆盖。
            header("If-None-Match", "*")
        } else {
            // ★ 坑 3：发出去的也必须是归一化后的值，否则部分服务器判 412。
            header("If-Match", normalizedExpected)
        }

    /**
     * 把条件 PUT 的响应翻成"成功"或一个明确的异常。
     *
     * ## 形状：先分类、再**只抛一次**
     *
     * 不是 `if (…) throw …; if (…) throw …`，而是让 [writeFailureOf] 返回一个
     * "该抛什么"，这里只负责抛。理由有两条，第二条是硬约束：
     *
     * 1. 分类逻辑（哪个状态码算冲突 / 算认证失败）**本身就是被测的那部分**，
     *    让它成为**返回值的纯函数**就能直接单测，不必去构造一个 Response。
     * 2. detekt `ThrowsCount` 上限 2 —— 四个状态码分支写四个 `throw` 必然超标，
     *    而"放宽阈值"等于把这扇门关掉。
     *
     * @param normalizedExpected 只用于失败措辞（新建 / 覆盖 提示不同）。
     */
    private suspend fun classifyWriteResponse(
        response: okhttp3.Response,
        normalizedExpected: String?,
    ) {
        if (response.isSuccessful) return
        throw writeFailureOf(response, normalizedExpected)
    }

    /**
     * 状态码 ⇒ 该抛的异常（**纯映射，自己不抛**）。
     *
     * ⚠️ 唯一带 IO 的一支是冲突：它要顺带把**当前**版本拉回来 ——
     * 用户接下来很可能选"用远端覆盖本地"，那时手上必须有冲突**之后**的版本令牌。
     * 拉失败不影响主流程（传 null，上层会自己再 stat 一次）。
     */
    private suspend fun writeFailureOf(
        response: okhttp3.Response,
        normalizedExpected: String?,
    ): Exception = when (response.code) {
        // ★ 409 与 412 都算冲突：不同服务器对 CREATE_ONLY 失败用哪个码并不一致
        //   （RFC 说 412，但实测有服务器回 409）。
        HTTP_PRECONDITION_FAILED, HTTP_CONFLICT -> KdbxFileConflictException(
            runCatching { stat().versionToken }.getOrNull(),
            "WebDAV 上的文件已被其他设备修改（HTTP ${response.code}），为避免覆盖已取消写入",
        )

        HTTP_UNAUTHORIZED, HTTP_FORBIDDEN ->
            IOException("WebDAV 账号或密码不正确（HTTP ${response.code}）")

        HTTP_INSUFFICIENT_STORAGE ->
            IOException("WebDAV 服务器空间不足（HTTP $HTTP_INSUFFICIENT_STORAGE）")

        // ⚠️ 不用 `else`：`when` 在**表达式**位置必须穷尽，所以留一个显式分支。
        //    措辞区分新建 / 覆盖：同一个状态码在两条路径上的成因往往不同，
        //    统一文案会把"文件已存在"说成"写入失败"，反而更难排查。
        else -> IOException(response.errorMessage(writeActionLabel(normalizedExpected)))
    }

    /** 措辞用：新建与覆盖的失败原因不同，提示也该不同。 */
    private fun writeActionLabel(normalizedExpected: String?): String =
        if (normalizedExpected == null) "新建文件" else "写入文件"

    /**
     * 用户**明确选择"用本地覆盖远端"**时的写入 —— 唯一允许绕开条件写的入口。
     *
     * 与 [writeConditionally] 的区别：不带任何条件头。
     * ⚠️ 只在冲突处理里由用户的显式选择触发（[write] 的 `force = true`），
     * **绝不要**在普通保存路径上调用（那就是方案 §8 方案 C「本地优先覆盖」，
     * 与"同步不丢"直接冲突）。
     *
     * ⚠️ 即使强写**也保留写后读回校验**：强写的语义是"不管远端是什么，用我的盖上"，
     * 不是"写完不检查"。读回不一致说明这次 PUT 根本没落地（网络中断 / 中间设备
     * 改写了内容）—— 那时报冲突比让用户以为同步成功要好。
     */
    private suspend fun forceReplace(bytes: ByteArray): KdbxFileWriteResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(fileUrl)
            .put(bytes.toRequestBody(KDBX_MIME_TYPE.toMediaType()))
            .build()
        val result = execute(request).use { response ->
            if (!response.isSuccessful) throw IOException(response.errorMessage(ACTION_FORCE_WRITE))
            KdbxFileWriteResult(
                versionToken = normalizeVersionToken(response.header(HEADER_ETAG)),
                sizeBytes = bytes.size.toLong(),
                remoteId = fileUrl,
                lastModified = response.header(HEADER_LAST_MODIFIED)?.let(::parseHttpDate),
            )
        }
        verifyReadBack(bytes)
        result
    }

    /** 写后读回校验（见 [writeConditionally] 的说明）。不一致就抛冲突。 */
    private suspend fun verifyReadBack(expected: ByteArray) {
        val readBack = runCatching { read() }
        if (readBack.isSuccess && !readBack.getOrThrow().contentEquals(expected)) {
            val current = runCatching { stat().versionToken }.getOrNull()
            throw KdbxFileConflictException(
                current,
                "写入后读回校验不一致：文件可能已被其他设备覆盖，请重新同步",
            )
        }
    }

    /**
     * 列所在目录的子项（`PROPFIND`，`Depth: 1`）。
     *
     * @return 目录里的文件/子目录。⚠️ **排除自身**（`PROPFIND` 的结果第一项是目录本身）。
     */
    override suspend fun listChildren(): List<KdbxFileEntry> = withContext(Dispatchers.IO) {
        val base = directoryUrl(fileUrl)
        val body = PROPFIND_BODY.toRequestBody(PROPFIND_MEDIA_TYPE.toMediaType())
        val request = Request.Builder()
            .url(base)
            .method("PROPFIND", body)
            .header("Depth", "1")
            .build()

        execute(request).use { response ->
            if (response.code == HTTP_NOT_FOUND) throw IOException("WebDAV 上找不到该目录")
            if (response.code !in MULTISTATUS_CODES) throw IOException(response.errorMessage(ACTION_LIST))
            val xml = response.body?.string().orEmpty()
            parsePropfind(xml, base)
        }
    }

    override suspend fun testConnection(): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            // 先 HEAD 目标文件：成功 ⇒ 文件在、凭据对。404 ⇒ 文件不在但**连接是通的**，
            // 这仍然是"配置可用"（用户可以据此新建）。401/403 才算真失败。
            execute(Request.Builder().url(fileUrl).head().build()).use { response ->
                if (response.code == HTTP_UNAUTHORIZED || response.code == HTTP_FORBIDDEN) {
                    throw IOException("WebDAV 账号或密码不正确（HTTP ${response.code}）")
                }
                if (response.code >= HTTP_SERVER_ERROR_FLOOR) {
                    throw IOException("WebDAV 服务器错误（HTTP ${response.code}）")
                }
            }
            Unit
        }
    }

    /**
     * 发一个带认证头的请求。
     *
     * ⚠️ 没有 `action` 参数（曾经有）：调用方各自的失败措辞不同，与其把文案穿进来
     * 再原样带回，不如让调用方在拿到非 2xx 时自己调 [errorMessage]。少一个参数、
     * 少一处"传进来的文案和调用点对不上"的可能。
     */
    private suspend fun execute(request: Request): okhttp3.Response {
        // ⚠️ 每次现取凭据：用户可能刚改过密码，缓存旧值只会让之后所有请求 401。
        val creds = credentialProvider()
        val authorized = request.newBuilder()
            .header("Authorization", creds.basicAuthHeader())
            .header("User-Agent", USER_AGENT)
            .build()
        return runCatching { client.newCall(authorized).execute() }
            .getOrElse { error ->
                // 网络类失败统一成可展示的文案（"连接超时"比 SocketTimeoutException 有用得多）
                throw IOException("无法连接 WebDAV 服务器：${error.message ?: error.javaClass.simpleName}", error)
            }
    }

    /** 把 HTTP 错误响应拼成一句能直接展示的话（响应体作为补充细节，截断到 200 字）。 */
    private fun okhttp3.Response.errorMessage(action: String): String {
        val detail = runCatching { body?.string() }.getOrNull().orEmpty()
        return "$action 失败：HTTP $code" +
            detail.take(ERROR_BODY_PREVIEW_CHARS).takeIf { it.isNotBlank() }?.let { "（$it）" }.orEmpty()
    }

    private companion object {
        const val KDBX_MIME_TYPE = "application/x-keepass2"
        const val PROPFIND_MEDIA_TYPE = "application/xml; charset=utf-8"
        const val USER_AGENT = "Vaultix-WebDAV"

        // ---- HTTP 状态码（提成常量：它们在多处出现，写错一处会静默错报）----
        const val HTTP_OK = 200
        const val HTTP_MULTI_STATUS = 207
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
        /** 前置条件不满足（`If-Match` / `If-None-Match` 未通过）。 */
        const val HTTP_PRECONDITION_FAILED = 412
        /** 部分服务器对 CREATE_ONLY 失败回 409 而不是 412。 */
        const val HTTP_CONFLICT = 409
        const val HTTP_INSUFFICIENT_STORAGE = 507
        /** 5xx 起点。 */
        const val HTTP_SERVER_ERROR_FLOOR = 500

        /** `PROPFIND` 成功只有这两种码（200 是某些实现的非标准简化）。 */
        val MULTISTATUS_CODES = setOf(HTTP_OK, HTTP_MULTI_STATUS)

        const val HEADER_ETAG = "ETag"
        const val HEADER_LAST_MODIFIED = "Last-Modified"
        const val HEADER_CONTENT_LENGTH = "Content-Length"

        // ---- 可展示文案里的动作名 ----
        const val ACTION_STAT = "读取文件信息"
        const val ACTION_READ = "下载文件"
        const val ACTION_FORCE_WRITE = "强制覆盖文件"
        const val ACTION_LIST = "列出目录"

        /** 错误响应体只展示这么多字（够定位问题，又不至于把整页 HTML 塞进对话框）。 */
        const val ERROR_BODY_PREVIEW_CHARS = 200

        /**
         * `PROPFIND` 的请求体：只要我们关心的四个属性。
         *
         * ⚠️ 别用 `<allprop/>`：部分服务器（尤其老版群晖）对 `allprop` 会返回**巨大**的
         * XML（含所有自定义属性），解析慢且容易在名称空间上翻车。指名要属性更稳。
         */
        const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<D:propfind xmlns:D="DAV:">
  <D:prop>
    <D:displayname/>
    <D:getcontentlength/>
    <D:getlastmodified/>
    <D:getetag/>
    <D:resourcetype/>
  </D:prop>
</D:propfind>"""

        /**
         * 解析 `PROPFIND` 的 207 Multi-Status。
         *
         * ## 为什么可见性不是 private
         *
         * 它是本文件里**唯一没有网络、却最容易出错**的一段：XML 名称空间、自身排除、
         * 中文名解码、弱 ETag 归一化。这些都必须能**在 JVM 单测里直接喂 XML 验证**
         * —— 靠一台真 WebDAV 服务器去覆盖这些分支既不现实也覆盖不全。
         * ⇒ 标 `internal` 而不是 `private`，让同模块的测试能直接调。
         */
        internal fun parsePropfind(xml: String, baseUrl: String): List<KdbxFileEntry> =
            WebDavPropfindParser.parse(xml, baseUrl)

        /**
         * HTTP 日期（RFC 1123）→ 毫秒。
         *
         * ⚠️ `Last-Modified` 只用于**展示**，不参与判重（精度到秒、且部分服务器返回本地时区
         * 的古怪格式）⇒ 解析失败返回 null，**不让读取因此失败**。
         */
        internal fun parseHttpDate(raw: String): Long? = WebDavPropfindParser.parseHttpDate(raw)

        /** 目录 URL（去掉最后一段并保留尾部 `/`）。 */
        fun directoryUrl(fileUrl: String): String {
            val withoutQuery = fileUrl.substringBefore('?')
            return withoutQuery.substringBeforeLast('/', missingDelimiterValue = withoutQuery) + "/"
        }

        /** 展示用的文件名（**脱敏后的**：只留最后一段）。 */
        fun displayPath(url: String): String = url.substringAfterLast('/')
    }
}

/**
 * `PROPFIND` 的 207 Multi-Status 解析器（`internal`、**顶层**）。
 *
 * ## 为什么单独抽成一个顶层对象
 *
 * 它是整个 WebDAV 实现里**唯一没有网络、却最容易出错**的一段：
 * XML 名称空间、自身排除、中文名 percent-decoding、弱 ETag 归一化、
 * 属性分散在多个 `propstat`。
 *
 * 这些分支必须能**在 JVM 单测里直接喂 XML 验证** —— 靠一台真 WebDAV 服务器
 * 去覆盖它们既不现实（要装 Nextcloud / 群晖 / Alist 三套）也覆盖不全
 * （比如"服务器不给 ETag"这种要特意构造）。
 * ⇒ 抽成顶层 `internal` 对象，测试可以直接调；不必为了测试去起 HTTP。
 *
 * ⚠️ 放 `internal` 而不是 `public`：它依赖 WebDAV 的 XML 方言（`DAV:` 名称空间），
 * 不是本项目通用的东西，暴露出去只会让外部误用。
 */
internal object WebDavPropfindParser {

    fun parse(xml: String, baseUrl: String): List<KdbxFileEntry> {
        if (xml.isBlank()) return emptyList()
        val document = runCatching {
            javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
                // ★★ 必须显式打开——**JDK 的 DOM 解析器默认不做名称空间处理**。
                //
                // 不开的话 `xmlns:d="DAV:"` 会被当成一个普通属性，元素的
                // `namespaceURI` 是 **null**、`tagName` 是字面量 `"d:multistatus"`。
                // 于是所有 `getElementsByTagNameNS("DAV:", …)` 全部返回 0 个元素，
                // **整个选库列表永远是空的** —— 而且不报任何错
                //（这正是本文件单测抓到的：Nextcloud / 群晖 / Alist 三种 XML 全部解析出 0 条）。
                //
                // ⚠️ 这个坑很隐蔽：`parse` 返回 `true`、文档对象看着"正常"、
                //    只有按名称空间取元素时才悄悄得到空。
                isNamespaceAware = true
                // ★ XXE 防护：恶意服务器可以用 `<!ENTITY x SYSTEM "file:///etc/passwd">`
                //   让解析器去读本机文件。我们解析的是**别人（服务器）给的** XML，
                //   这是必须关掉的能力。
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                isExpandEntityReferences = false
            }.newDocumentBuilder().parse(xml.byteInputStream())
        }.getOrNull() ?: return emptyList()

        val responses = document.getElementsByTagNameNS(DAV_NS, RESPONSE_TAG)
        val entries = mutableListOf<KdbxFileEntry>()
        for (i in 0 until responses.length) {
            val node = responses.item(i) as? org.w3c.dom.Element ?: continue
            parseResponseElement(node, baseUrl)?.let(entries::add)
        }
        return entries.sortedWith(
            compareBy<KdbxFileEntry> { !it.isDirectory }.thenBy { it.name.lowercase() },
        )
    }

    /**
     * 把一条 `<D:response>` 翻成 [KdbxFileEntry]。
     *
     * @return null = 这条要**跳过**（缺 `href`，或它就是被请求的那个目录自身）。
     *
     * ⚠️ 抽成独立函数的原因是 **detekt `LoopWithTooManyJumpStatements`**：
     * 原先循环体里有两个 `continue`（一个跳过非元素节点、一个跳过目录自身），
     * 加起来超过"单循环一个跳转"的上限。而这两个跳过的**语义其实不同** ——
     * 前者是"这条 XML 畸形"，后者是"这条合法但不是我们要的" ——
     * 混在一个循环里读起来也更容易看错。分开后循环体只剩一行。
     */
    private fun parseResponseElement(node: org.w3c.dom.Element, baseUrl: String): KdbxFileEntry? {
        val href = node.getElementsByTagNameNS(DAV_NS, HREF_TAG).item(0)?.textContent
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        // ⚠️ PROPFIND 的结果**第一项是请求的那个目录自身**，必须排除，
        //    否则"选库"列表里会多出一行指向目录的条目。
        //
        // ⚠️⚠️ 这里**不能拿 href 与完整 baseUrl 直接字符串比较**：
        //    RFC 4918 说 href 是**绝对路径**（`/remote.php/dav/files/alice/Vaultix/`），
        //    而 baseUrl 是完整 URL（`https://cloud.example.com/remote.php/dav/...`）
        //    —— 两者永远不相等，目录自身就永远排除不掉。
        //    ⇒ 只比**路径部分**（并且末尾的 `/` 不算差异）。
        if (normalizedPathOf(href) == normalizedPathOf(baseUrl)) return null

        val isDirectory = node.getElementsByTagNameNS(DAV_NS, COLLECTION_TAG).length > 0
        val rawName = node.firstText(DISPLAYNAME_TAG)
            ?: href.trimEnd('/').substringAfterLast('/')

        return KdbxFileEntry(
            // 服务器可能返回 percent-encoded 的名字（群晖对中文就这么干）
            name = runCatching { java.net.URLDecoder.decode(rawName, "UTF-8") }.getOrDefault(rawName),
            id = href,
            isDirectory = isDirectory,
            // ★ 坑 3：弱 ETag（`W/"x"`）必须归一化，否则与自己后来看到的
            //   强形态（`"x"`）比不相等 ⇒ 误报冲突。
            versionToken = node.firstText(GETETAG_TAG)?.let(::normalizeVersionToken),
            sizeBytes = node.firstText(GETCONTENTLENGTH_TAG)?.toLongOrNull(),
            lastModified = node.firstText(GETLASTMODIFIED_TAG)?.let(::parseHttpDate),
        )
    }

    /**
     * HTTP 日期（RFC 1123）→ 毫秒。
     *
     * ⚠️ 解析失败返回 null **而不是抛异常**：时间戳只用于展示与排序，
     * 不该因为某个服务器回了古怪格式就让"读取文件"这个主要目的失败。
     */
    /**
     * HTTP 日期（RFC 1123）→ 毫秒。
     *
     * ## 两个刻意的宽松处理
     *
     * 1. **用 `DateTimeFormatterBuilder` 指定 Locale.US**：`RFC_1123_DATE_TIME` 的月份/星期
     *    缩写解析依赖 locale —— 如果设备语言不是英语，默认 formatter 会解不出 `Wed`。
     * 2. ★ **`parseDefaulting(DAY_OF_WEEK)` 覆盖掉 `DayOfWeek` 字段**：Java 的严格解析
     *    会**校验星期几与日期是否自洽**（例如传 "Wed, 17 Sep 2026" 而 2026-09-17 是周四
     *    ⇒ 直接抛 `Conflict found: Field DayOfWeek 4 differs from DayOfWeek 3`）。
     *    而**现实中的服务器真的会写错星期几**（见过群晖与某些 nginx dav 模块），
     *    我们不能因为一个只用于展示的时间戳，就把整个"读取文件"搞失败。
     *
     * ⚠️ 解析失败返回 null **而不是抛异常**：时间戳只用于展示与排序。
     */
    fun parseHttpDate(raw: String): Long? = runCatching {
        // ★ 先**把星期几整段去掉**（含逗号）再解析。
        //
        //   "把 DayOfWeek 字段默认掉"是行不通的 —— 实测 `parseDefaulting(DAY_OF_WEEK, 1)`
        //   并不能阻止解析器在 resolution 阶段做自洽校验，照样抛
        //   `Conflict found: Field DayOfWeek 4 differs from DayOfWeek 3 derived from 2026-09-17`。
        //   而我们**根本不需要星期几**（日期由 dd MMM yyyy 唯一决定）⇒
        //   最省事也最稳的做法就是**不解析它**。
        val withoutWeekday = raw.trim().replace(WEEKDAY_PREFIX, "")
        java.time.LocalDateTime.from(LENIENT_HTTP_DATE.parse(withoutWeekday))
            .toInstant(java.time.ZoneOffset.UTC)
            .toEpochMilli()
    }.getOrNull()

    /** 匹配开头的 "Www, " （星期几缩写 + 逗号 + 空格）。不区分大小写。 */
    private val WEEKDAY_PREFIX = Regex("^[A-Za-z]{3},\\s*")

    /**
     * 宽容的 RFC 1123 解析器（时间部分 + 时区后缀）。
     *
     * ## ★ 为什么不能直接用 `DateTimeFormatter.RFC_1123_DATE_TIME`
     *
     * 1. **严格校验星期几**：`"Wed, 17 Sep 2026 …"` 若 2026-09-17 是周四，会抛
     *    `Conflict found: Field DayOfWeek ...`。**现实中服务器真的会写错星期几**
     *    （群晖、某些 nginx dav 模块都见过）⇒ 星期几已在 [parseHttpDate] 里被剥掉。
     * 2. **依赖默认 Locale**：月份/星期缩写按 locale 解析，设备语言非英语时可能解不出
     *    ⇒ 这里显式用 `Locale.US`。
     */
    private val LENIENT_HTTP_DATE: java.time.format.DateTimeFormatter =
        java.time.format.DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("dd MMM yyyy HH:mm:ss")
            // 时区后缀可选：RFC 1123 要求 GMT，但少数实现省略
            .optionalStart()
            .appendLiteral(' ')
            .appendZoneId()
            .optionalEnd()
            .toFormatter(java.util.Locale.US)

    /**
     * 把 href 或 URL 统一成**可比较的路径**：去掉 scheme+host 与查询串，并去掉末尾 `/`。
     *
     * 这样 `/remote.php/dav/files/alice/Vaultix/`（href，绝对路径）与
     * `https://cloud.example.com/remote.php/dav/files/alice/Vaultix/`（baseUrl，完整 URL）
     * 会得到同一个值，从而能正确判定"这一条就是目录自身"。
     *
     * ⚠️ 用 `java.net.URI` 而不是正则：手写正则处理 `?query`、端口、转义很容易出错。
     */
    private fun normalizedPathOf(hrefOrUrl: String): String {
        val withoutQuery = hrefOrUrl.trim().substringBefore('#').substringBefore('?')
        val path = runCatching { java.net.URI(withoutQuery).path }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: withoutQuery
        // 未编码的路径与 percent-encoded 的路径可能混着来（服务器不一定统一），
        // 解码一次再比，避免同一路径因编码差异被当成两条。
        val decoded = runCatching { java.net.URLDecoder.decode(path, "UTF-8") }.getOrDefault(path)
        return decoded.trimEnd('/')
    }

    private const val DAV_NS = "DAV:"

    // ---- DAV: 名称空间下的元素名（PROPFIND 用到的全部）----
    private const val RESPONSE_TAG = "response"
    private const val HREF_TAG = "href"
    private const val COLLECTION_TAG = "collection"
    private const val DISPLAYNAME_TAG = "displayname"
    private const val GETETAG_TAG = "getetag"
    private const val GETCONTENTLENGTH_TAG = "getcontentlength"
    private const val GETLASTMODIFIED_TAG = "getlastmodified"

    private fun org.w3c.dom.Element.firstText(localName: String): String? =
        getElementsByTagNameNS(DAV_NS, localName).item(0)?.textContent?.trim()
            ?.takeIf { it.isNotEmpty() }
}
