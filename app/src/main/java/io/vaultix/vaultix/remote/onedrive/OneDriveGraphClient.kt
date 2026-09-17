/*
 * Vaultix — app / OneDrive
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * ★ 溯源声明（GPL-3.0）：请求形状（OkHttp 手写 REST + `/me/drive` 基址 + eTag 版本令牌 +
 *   412 视为「远端已变化」+ 2MiB/5MiB 分片参数）移植自 Bastion
 *   （`com.bastion.app.utils.OneDriveKeePassFileSource`，同项目作者的另一个应用，GPL-3.0）。
 *
 * 本文件 = **读 + 条件写**。写路径在 KDBX 阶段 B（写回）落地后补齐，
 * 理由见下方「为什么先前只做读」。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.remote.onedrive

import io.vaultix.common.logging.VaultixLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** 网盘上的一个条目（目录或文件）。 */
data class OneDriveEntry(
    val id: String,
    val name: String,
    val isDirectory: Boolean,
    /** 版本令牌（优先 `eTag`）：条件写的 `If-Match` 用它，也是冲突检测的判据。 */
    val versionToken: String?,
    val sizeBytes: Long?,
    val lastModified: String?,
) {
    /** 是否是 KeePass 数据库文件（大小写不敏感 —— OneDrive 上两种写法都常见）。 */
    val isKdbx: Boolean get() = !isDirectory && name.endsWith(".kdbx", ignoreCase = true)
}

/**
 * ★ **远端已被其他设备修改**（服务端回 412 Precondition Failed）。
 *
 * 单独成一个异常而不是普通 `IOException`，理由与 `KdbxFileConflictException` 相同：
 * 它是**唯一一种"失败了好事"**（说明条件写生效、没覆盖别人），
 * 上层要做的不是重试而是**让用户处理冲突**。
 */
class OneDrivePreconditionFailedException(
    message: String = "OneDrive 上的文件已被其他设备修改，请先处理冲突",
) : IOException(message)

/**
 * Microsoft Graph 的最小客户端（**读 + 条件写**）。
 *
 * ## 为什么手写 OkHttp 而不是引入 Graph SDK
 *
 * 与上游 Bastion 同取向：Graph 的官方 Android SDK 体积大、传递依赖多（还带 GMS），
 * 而本项目只用到十来个端点。手写 REST 的代价是"要自己处理分页与错误码"，
 * 但换来可控的体积与依赖面。
 *
 * ## 为什么先前只做读（保留这段说明，因为它解释了一个容易再犯的判断）
 *
 * KDBX 曾处在 **M2 阶段 A（只读）**：本地库都还不能写回，网盘侧更没有写入场景，
 * 所以写路径当时**没有任何理由先写** —— 写了也无处可用，只会白白扩大维护面。
 * 阶段 B（写回）落地后，写路径才有意义，于是补齐（见 [upload]）。
 *
 * ## ★ 条件写（冲突检测的地基）
 *
 * 覆盖上传时带 `If-Match: <eTag>`：
 * - 版本一致 ⇒ 服务端接受，返回新 `eTag`；
 * - 版本已变 ⇒ 服务端回 **412** ⇒ 转成 [OneDrivePreconditionFailedException]。
 *
 * ⚠️ **绝不能用 `conflictBehavior=replace` 做"更新"**：那是**无条件覆盖**，
 * 会把别的设备的改动直接抹掉（与用户的硬要求"同步不丢"直接冲突）。
 * 它只有在用户**明确选择"用本地覆盖远端"**时才合法（见 `forceReplace` 参数）。
 */
@Singleton
class OneDriveGraphClient @Inject constructor() {

    /**
     * 列出一个目录下的子项。
     *
     * @param directoryPath 相对 OneDrive 根目录的路径（null / 空 = 根目录）。
     * @param accessToken [OneDriveAuthManager.acquireAccessToken] 取到的 token。
     */
    suspend fun listChildren(
        accessToken: String,
        directoryPath: String? = null,
    ): List<OneDriveEntry> = withContext(Dispatchers.IO) {
        val firstUrl = buildChildrenRelativeUrl(directoryPath)
        val collected = mutableListOf<OneDriveDriveItemDto>()
        var nextUrl: String? = firstUrl
        var page = 0

        // Graph 用 `@odata.nextLink` 分页；不跟到底会静默漏掉后半段（库文件常排在后面）。
        while (nextUrl != null) {
            page++
            val payload = executeJsonRequest(nextUrl, accessToken)
            val response = json.decodeFromString<OneDriveChildrenResponseDto>(payload)
            collected += response.value
            nextUrl = response.nextLink
        }
        VaultixLog.d(TAG) {
            "OneDrive 列目录完成：path=${directoryPath ?: "<root>"} pages=$page items=${collected.size}"
        }

        collected.map { item ->
            OneDriveEntry(
                id = item.id,
                name = item.name,
                isDirectory = item.folder != null,
                versionToken = item.eTag ?: item.cTag,
                sizeBytes = item.size,
                lastModified = item.lastModifiedDateTime,
            )
        }.sortedWith(
            // 目录在前，其余按名称不区分大小写 —— 用户的库文件因此不会被随机顺序埋掉。
            compareBy<OneDriveEntry> { !it.isDirectory }
                .thenBy { it.name.lowercase() },
        )
    }

    /** 连通性自检：能不能拿到根目录的第一页？（失败原因原样抛出，由调用方翻译给用户） */
    suspend fun testConnection(accessToken: String): Result<Unit> = runCatching {
        executeJsonRequest("/me/drive/root?${'$'}select=id", accessToken)
        Unit
    }

    /**
     * 取某个文件的元数据（**不下载内容**）—— 条件写在写之前用它拿 `eTag`。
     *
     * @param path 相对根目录的路径（如 `Vaultix/vault.kdbx`）。
     */
    suspend fun stat(accessToken: String, path: String): OneDriveEntry = withContext(Dispatchers.IO) {
        val payload = executeJsonRequest(buildItemRelativeUrl(path), accessToken)
        json.decodeFromString<OneDriveDriveItemDto>(payload).toEntry().also { entry ->
            if (entry.isDirectory) throw IOException("OneDrive 上该路径是一个文件夹，不是文件：$path")
        }
    }

    /**
     * 按路径下载全部内容。
     *
     * ⚠️ 走 `/content`（Graph 会 302 到带短时效签名的 CDN 地址）。
     * 见 [client] 的拦截器：跳到非 graph 域时**必须摘掉 `Authorization`**
     * （CDN 不认 Bearer，部分情况下直接 401）。
     */
    suspend fun download(accessToken: String, path: String): ByteArray = withContext(Dispatchers.IO) {
        downloadBytes(buildContentRelativeUrl(path), accessToken, "下载 $path")
    }

    /** 按 `itemId` 下载（列表里已经拿到 id 时用这个，省掉一次路径解析）。 */
    suspend fun downloadById(accessToken: String, itemId: String): ByteArray = withContext(Dispatchers.IO) {
        downloadBytes("/me/drive/items/$itemId/content", accessToken, "下载 item=$itemId")
    }

    /**
     * 覆盖写入一个文件。
     *
     * ## 两条路径
     *
     * - **小文件（≤ [SIMPLE_UPLOAD_LIMIT_BYTES]）**：`PUT …/content` 一次传完，带
     *   `If-Match: <expectedETag>` 做条件写。
     * - **大文件**：建 `uploadSession`（`…/createUploadSession`）后按
     *   [CHUNK_SIZE_BYTES] 分片 `PUT`，每片带 `Content-Range`；条件写通过
     *   uploadSession 请求体里的 `ifMatch` 字段表达（Graph 支持，**不是** HTTP 头）。
     *
     * @param expectedETag ★ 上次 [stat] 拿到的 `eTag`；null = 调用方不对并发做保证。
     * @param forceReplace ★ 用户明确选了"用本地覆盖远端"时的**唯一**合法入口 ——
     *   true 时走 `conflictBehavior=replace` **且不带条件**。
     *   ⚠️ 默认 false：**不要**在普通保存路径上传 true。
     * @return 写入后的新状态（含**新的** `eTag`，下一次条件写要用它）。
     */
    suspend fun upload(
        accessToken: String,
        path: String,
        bytes: ByteArray,
        expectedETag: String?,
        forceReplace: Boolean = false,
    ): OneDriveEntry = withContext(Dispatchers.IO) {
        if (bytes.size <= SIMPLE_UPLOAD_LIMIT_BYTES) {
            simpleUpload(accessToken, path, bytes, expectedETag, forceReplace)
        } else {
            chunkedUpload(accessToken, path, bytes, expectedETag, forceReplace)
        }
    }

    // ───────────────────────── 写路径内部实现 ─────────────────────────

    private fun simpleUpload(
        accessToken: String,
        path: String,
        bytes: ByteArray,
        expectedETag: String?,
        forceReplace: Boolean,
    ): OneDriveEntry {
        val url = buildString {
            append(GRAPH_BASE_URL).append(buildContentRelativeUrl(path))
            // ★ 只有"用户明确选择覆盖"才带 replace。
            //   普通路径靠 `If-Match` 兜并发 —— 两者**不能同时**出现：
            //   replace 会让服务端跳过前置条件检查，那就等于无条件覆盖。
            if (forceReplace) append("?@microsoft.graph.conflictBehavior=replace")
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .put(bytes.toRequestBody(KDBX_MIME_TYPE.toMediaType()))
            .apply {
                if (!forceReplace && !expectedETag.isNullOrBlank()) {
                    header("If-Match", expectedETag)
                }
            }
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            when {
                response.isSuccessful -> Unit
                // ★ 412 = 条件写失败 = 别人改了 ⇒ 这是我们要**明确区分**的结果
                response.code == HTTP_PRECONDITION_FAILED -> throw OneDrivePreconditionFailedException(
                    "OneDrive 上的文件已被其他设备修改（HTTP 412），为避免覆盖已取消写入",
                )
                else -> throw IOException(
                    translateUploadError(response.code)
                        ?: body.ifBlank { "OneDrive 上传失败：HTTP ${response.code}" },
                )
            }
            return json.decodeFromString<OneDriveDriveItemDto>(body).toEntry()
        }
    }

    /**
     * 分片上传。
     *
     * ⚠️ 阈值 **2MiB**、片大小 **5MiB** 是 Bastion 实测参数，**别自己猜**：
     * Graph 的简单上传有约 4MB 上限，且片必须是 **320KiB 的整数倍**
     * （[CHUNK_SIZE_BYTES] = 320KiB × 16），否则部分服务端会 400。
     * ⚠️ **必须是 `suspend`**：末尾那次兜底 `stat(...)` 是 suspend 函数
     * （它自己 `withContext(Dispatchers.IO)`）。写成普通函数会在编译期报
     * `Suspend function 'stat' can only be called from a coroutine`，
     * 且 `?:` 的结果会被推成 `Any`（触发 `Return type mismatch`）。
     * 调用方 [upload] 本身在 `withContext(Dispatchers.IO)` 里，
     * 再叠一层 suspend 不产生额外调度开销。
     */
    private suspend fun chunkedUpload(
        accessToken: String,
        path: String,
        bytes: ByteArray,
        expectedETag: String?,
        forceReplace: Boolean,
    ): OneDriveEntry {
        val uploadUrl = createUploadSession(accessToken, path, expectedETag, forceReplace)

        var offset = 0
        var lastDto = OneDriveDriveItemDto()
        while (offset < bytes.size) {
            val end = minOf(offset + CHUNK_SIZE_BYTES, bytes.size)
            val chunk = bytes.copyOfRange(offset, end)
            val dto = putChunkWithRetry(uploadUrl, chunk, offset, bytes.size)
            if (dto.id.isNotBlank()) lastDto = dto
            offset = end
        }
        VaultixLog.d(TAG) { "OneDrive 分片上传完成：path=$path bytes=${bytes.size}" }
        // 最后一次片的响应体就是最终的 driveItem（带新 eTag）；
        // 若服务端只回了 202 的中间响应，则只有 uploadSession 本身能反映结果 ——
        // 这时补一次 stat 拿到真实 eTag，**不要**返回空（会让上层把令牌丢了）。
        return lastDto.takeIf { it.id.isNotBlank() }
            ?: stat(accessToken, path)
    }

    /**
     * 创建分片上传会话，返回会话的上传 URL。
     *
     * ## 条件写在这里怎么表达
     *
     * Graph 的 uploadSession **不用 `If-Match` 头**，而是把条件写成请求体里的字段：
     * - `conflictBehavior = "fail"` + `ifMatch = <eTag>` ⇒ 远端变了就拒（412）；
     * - `conflictBehavior = "replace"` ⇒ 无条件覆盖（用户拍板后的强写）。
     *
     * ⚠️ 这个差异很关键：如果照 `If-Match` 那样写，条件**根本不会生效**
     * （Graph 会忽略未知字段），表现为"远端被人改过，我们还是盖了上去" ——
     * 静默数据丢失，而且没有任何报错。
     *
     * ⚠️ 抽成独立函数是为了让 [chunkedUpload] 的 throw 数落在 detekt
     * `ThrowsCount`（上限 2）之内。这里的三条各自对应一类完全不同的失败
     * （冲突 / 可翻译的 Graph 错误 / 缺上传地址），本来就不该和分片循环混在一起。
     */
    private fun createUploadSession(
        accessToken: String,
        path: String,
        expectedETag: String?,
        forceReplace: Boolean,
    ): String {
        val sessionUrl = "$GRAPH_BASE_URL${buildCreateUploadSessionRelativeUrl(path)}"
        val sessionBody = buildString {
            append("""{"item":{"@microsoft.graph.conflictBehavior":""")
            append(if (forceReplace) "replace" else "fail")
            append("""}""")
            if (!forceReplace && !expectedETag.isNullOrBlank()) {
                // Graph 在 uploadSession 上用请求体里的 `ifMatch` 表达条件写
                append(""","ifMatch":"$expectedETag"""")
            }
            append("}")
        }
        val sessionRequest = Request.Builder()
            .url(sessionUrl)
            .header("Authorization", "Bearer $accessToken")
            .post(sessionBody.toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
            .build()

        return client.newCall(sessionRequest).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw sessionFailureOf(response, body)
            json.decodeFromString<OneDriveUploadSessionDto>(body).uploadUrl
                ?: throw IOException("OneDrive 未返回上传会话地址")
        }
    }

    /**
     * 创建上传会话失败的响应 ⇒ 该抛的异常（**纯映射，自己不抛**）。
     *
     * ⚠️ 形状（先分类、再只抛一次）是为了 detekt `ThrowsCount`（上限 2）——
     * 与 `WebDavKdbxFileSource.writeFailureOf` 同一套做法：把分类抽成返回值的
     * 纯函数，顺带也让"哪个码算什么失败"可以脱离 HTTP 单测。放宽阈值不是选项，
     * 那等于把这扇门关掉。
     */
    private fun sessionFailureOf(response: okhttp3.Response, body: String): IOException =
        when (response.code) {
            // ★ 412 = 条件写失败 = 远端确实变了。这是**唯一**要单独成类的失败：
            //   它不是"出错"，是"我们成功地阻止了一次覆盖"（见类的 KDoc）。
            HTTP_PRECONDITION_FAILED -> OneDrivePreconditionFailedException(
                "OneDrive 上的文件已被其他设备修改（HTTP 412），为避免覆盖已取消写入",
            )
            else -> IOException(
                translateUploadError(response.code)
                    ?: body.ifBlank { "创建上传会话失败：HTTP ${response.code}" },
            )
        }

    /** 单片上传 + 重试 [CHUNK_RETRY_COUNT] 次 —— 移动网络下单片失败很常见，不重试等于白传。 */
    private fun putChunkWithRetry(
        uploadUrl: String,
        chunk: ByteArray,
        offset: Int,
        totalSize: Int,
    ): OneDriveDriveItemDto {
        var lastError: Throwable? = null
        repeat(CHUNK_RETRY_COUNT) { attempt ->
            val request = Request.Builder()
                .url(uploadUrl)
                .header("Content-Length", chunk.size.toString())
                .header("Content-Range", "bytes $offset-${offset + chunk.size - 1}/$totalSize")
                .put(chunk.toRequestBody(OCTET_STREAM_MEDIA_TYPE.toMediaType()))
                .build()
            val outcome = runCatching {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        throw IOException(body.ifBlank { "分片上传失败：HTTP ${response.code}" })
                    }
                    // 200/201 = 完成（响应体是 driveItem 且带 id）；202 = 中间片（多为空体）
                    if (response.code in setOf(200, 201) && body.isNotBlank()) {
                        json.decodeFromString<OneDriveDriveItemDto>(body)
                    } else {
                        OneDriveDriveItemDto()
                    }
                }
            }
            outcome.getOrNull()?.let { return it }
            outcome.exceptionOrNull()?.let { error ->
                lastError = error
                VaultixLog.w(TAG) { "分片重试 ${attempt + 1}/$CHUNK_RETRY_COUNT：${error.message}" }
                Thread.sleep(CHUNK_RETRY_DELAY_MS)
            }
        }
        throw IOException(lastError?.message ?: "分片上传失败", lastError)
    }

    private fun downloadBytes(relativeUrl: String, accessToken: String, context: String): ByteArray {
        val request = Request.Builder()
            .url("$GRAPH_BASE_URL$relativeUrl")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                VaultixLog.w(TAG) { "Graph $context 失败：HTTP ${response.code}" }
                throw IOException(
                    when (response.code) {
                        HTTP_UNAUTHORIZED -> "OneDrive 登录已失效，请重新登录"
                        HTTP_NOT_FOUND -> "OneDrive 上找不到该文件"
                        else -> "OneDrive $context 失败：HTTP ${response.code}"
                    },
                )
            }
            return response.body?.bytes() ?: throw IOException("OneDrive 返回了空响应")
        }
    }

    private fun translateUploadError(code: Int): String? = when (code) {
        HTTP_UNAUTHORIZED -> "OneDrive 登录已失效，请重新登录"
        HTTP_FORBIDDEN -> "OneDrive 拒绝了写入（权限不足，可能需要重新授权）"
        HTTP_INSUFFICIENT_STORAGE -> "OneDrive 空间不足"
        else -> null
    }

    private fun OneDriveDriveItemDto.toEntry(): OneDriveEntry = OneDriveEntry(
        id = id,
        name = name,
        isDirectory = folder != null,
        versionToken = eTag ?: cTag,
        sizeBytes = size,
        lastModified = lastModifiedDateTime,
    )

    private fun buildChildrenRelativeUrl(directoryPath: String?): String {
        val normalized = directoryPath?.trim()?.trim('/')?.takeIf { it.isNotEmpty() }
        return if (normalized == null) {
            "/me/drive/root/children"
        } else {
            "/me/drive/root:/${encodePath(normalized)}:/children"
        }
    }

    private fun buildItemRelativeUrl(path: String): String =
        "/me/drive/root:/${encodePath(path.trim().trim('/'))}"

    private fun buildContentRelativeUrl(path: String): String =
        "${buildItemRelativeUrl(path)}:/content"

    private fun buildCreateUploadSessionRelativeUrl(path: String): String =
        "${buildItemRelativeUrl(path)}:/createUploadSession"

    /** 逐段编码：路径里的中文 / 空格 / `#` 不编码会让 Graph 返回 400 或找错文件。 */
    private fun encodePath(path: String): String = path
        .split('/')
        .filter { it.isNotBlank() }
        .joinToString("/") { segment -> java.net.URLEncoder.encode(segment, "UTF-8") }

    private fun executeJsonRequest(relativeOrAbsoluteUrl: String, accessToken: String): String {
        val url = if (relativeOrAbsoluteUrl.startsWith("https://", ignoreCase = true)) {
            relativeOrAbsoluteUrl
        } else {
            "$GRAPH_BASE_URL$relativeOrAbsoluteUrl"
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                VaultixLog.w(TAG) { "Graph 请求失败：HTTP ${response.code} ${request.url.encodedPath}" }
                // 401/403 单独说清楚：它是"要重新登录"而不是"网络不好"，
                // 两者的用户动作完全不同（前者去重新登录，后者去检查网络）。
                val hint = when (response.code) {
                    401 -> "OneDrive 登录已失效，请重新登录"
                    403 -> "OneDrive 拒绝了该请求（权限不足，可能需要重新授权）"
                    404 -> "OneDrive 上找不到该路径"
                    else -> null
                }
                throw IOException(hint ?: body.ifBlank { "OneDrive 请求失败：HTTP ${response.code}" })
            }
            return body
        }
    }

    private companion object {
        const val TAG = "OneDriveGraph"
        const val GRAPH_BASE_URL = "https://graph.microsoft.com/v1.0"
        const val KDBX_MIME_TYPE = "application/x-keepass2"
        const val JSON_MEDIA_TYPE = "application/json"
        const val OCTET_STREAM_MEDIA_TYPE = "application/octet-stream"

        /** 小于等于这个值走简单上传（一次 PUT）。Bastion 实测参数。 */
        const val SIMPLE_UPLOAD_LIMIT_BYTES = 2 * 1024 * 1024

        /** 片大小 = 320KiB × 16 = 5MiB（Graph 要求片是 320KiB 的整数倍）。 */
        const val CHUNK_SIZE_BYTES = 320 * 1024 * 16

        const val CHUNK_RETRY_COUNT = 3
        const val CHUNK_RETRY_DELAY_MS = 500L

        /**
         * 用到的 HTTP 状态码。
         *
         * ⚠️ 提成常量不是为了"消灭魔法数字"，而是因为它们在**两个地方**出现
         * （抛异常的判据 + 翻成人话的 switch），两处写错一个就会变成
         * "412 走了 else 分支"或"权限不足报成了登录失效" —— 那类 bug 又静默又难查。
         */
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_NOT_FOUND = 404
        /** 条件写失败（`If-Match` 不匹配 / `If-None-Match: *` 命中已存在的文件）。 */
        const val HTTP_PRECONDITION_FAILED = 412
        /** OneDrive 配额耗尽。 */
        const val HTTP_INSUFFICIENT_STORAGE = 507

        /**
         * ★ 重定向时要摘掉 `Authorization`。
         *
         * `/content` 会 302 到带一次性签名的 CDN 地址，而 **OkHttp 默认把请求头
         * 原样带到重定向目标** —— CDN 不认 Bearer token，部分情况下直接回 401
         * （表现成"文件明明在，却读不到"）。
         * Graph 的签名 URL 自带凭据 ⇒ 跳到外部域名时把头摘掉。
         *
         * ⚠️ 判据用**目标 host** 而不是"是不是 302"：Graph 内部也可能有同域跳转，
         * 那种情况下摘头反而会 401。
         */
        val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val targetHost = response.request.url.host
                if (targetHost.endsWith(GRAPH_HOST_SUFFIX)) {
                    response
                } else {
                    response.newBuilder()
                        .request(response.request.newBuilder().removeHeader("Authorization").build())
                        .build()
                }
            }
            .build()

        const val GRAPH_HOST_SUFFIX = "graph.microsoft.com"

        val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class OneDriveChildrenResponseDto(
    /** 注意字段名带 `@`，故必须显式注解，不能靠命名推导。 */
    @SerialName("@odata.nextLink")
    val nextLink: String? = null,
    val value: List<OneDriveDriveItemDto> = emptyList(),
)

@Serializable
private data class OneDriveUploadSessionDto(
    /**
     * 分片 PUT 的目标地址（已带一次性签名）。
     *
     * ⚠️ 对它 **不要再加 `Authorization`** —— 会话 URL 自带凭据，
     * 多带一个 Bearer 反而可能被拒。这也正是 [client] 拦截器存在的理由。
     */
    val uploadUrl: String? = null,
    val expirationDateTime: String? = null,
)

@Serializable
private data class OneDriveDriveItemDto(
    val id: String = "",
    val name: String = "",
    val size: Long? = null,
    /** `eTag`/`cTag` 的 JSON 名首字母大写，与 Kotlin 属性名不同，需显式注解。 */
    @SerialName("eTag")
    val eTag: String? = null,
    @SerialName("cTag")
    val cTag: String? = null,
    val lastModifiedDateTime: String? = null,
    /** 非 null 表示这是目录（Graph 的约定：靠字段有无区分类型）。 */
    val folder: OneDriveFolderFacetDto? = null,
)

@Serializable
private data class OneDriveFolderFacetDto(
    val childCount: Int = 0,
)
