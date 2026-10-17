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
 *
 * ## 为什么这里**没有** `@Inject constructor`（2026-10-17）
 *
 * 两个构造参数都是**可替换接缝**（`client` 给测试短路、`baseUrl` 给假服务端），
 * 而 Hilt 对带默认值的构造参数会**同时**把它当成"可注入"与"有默认值"两回事：
 * 实测报 `Type … may only contain one injected constructor. Found: [(OkHttpClient, String), ()]`
 * —— Kotlin 为默认值生成的合成构造与 `@Inject` 那个撞在了一起。
 *
 * ⇒ 改由 `di/KdbxCloudSyncAppModule.provideOneDriveGraphClient()` 用 `@Provides` 提供。
 * 好处是**接缝在生产/测试两侧都是显式的**，而不是靠"注入器能不能凑齐依赖"这种
 * 编译期魔法决定。
 *
 * ## 为什么是 `open class`（2026-10-17）
 *
 * Kotlin 类默认 `final`，mockk 没法给 `final` 类打桩（除非挂 inline agent）。
 * 而 [OneDriveKdbxFileSource] 的**写后读回校验**（#172 的第二道闸）要验的正是
 * "upload 返回之后，本类会不会再 download 一次" —— 这是两个方法之间的**调用关系**，
 * 用假服务端写不出来（假服务端只能验单个 HTTP 请求），只能靠 mock。
 * 开放继承面是这里最小、最诚实的代价；⚠️ 它的子类只应存在于测试里。
 */
open class OneDriveGraphClient(
    /**
     * ★ 可测接缝（2026-10-17 引入）。
     *
     * 原先这里是个**无参**构造，`client` 是 companion 里写死的 `OkHttpClient`
     * ⇒ `If-None-Match: *` 到底有没有发出去、412 有没有被映射成
     * [OneDrivePreconditionFailedException]，这两件本可以纯逻辑验证的事
     * **一行测试都写不了**，只能靠真机 + 抓包。
     * 而 ISSUES #172 的修复恰恰全押在这两个行为上 —— 不测等于没修。
     *
     * ⚠️ **没有引入新依赖**：假服务端用 OkHttp 自带的
     * `Interceptor` 直接短路请求（见测试里的 `FakeGraphServer`），
     * 比 MockWebServer 更轻，也不需要真实端口。
     */
    private val client: OkHttpClient = defaultClient,
    /** Graph 基址；测试时指向假服务端即可。 */
    private val baseUrl: String = GRAPH_BASE_URL,
) {

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

        // ⚠️ 这里曾经把 `toEntry()` 的六个字段**手抄一遍**。那正是本轮 bug 的温床：
        // 手抄版与 `toEntry()` 之间没有任何机制保证同步，下次改字段只会改一处。
        // 统一走 `toEntry()`。
        collected.map { it.toEntry() }.sortedWith(
            // 目录在前，其余按名称不区分大小写 —— 用户的库文件因此不会被随机顺序埋掉。
            compareBy<OneDriveEntry> { !it.isDirectory }
                .thenBy { it.name.lowercase() },
        )
    }

    /** 连通性自检：能不能拿到根目录的第一页？（失败原因原样抛出，由调用方翻译给用户） */
    suspend fun testConnection(accessToken: String): Result<Unit> = runCatching {
        executeJsonRequest("/me/drive/root?${'$'}select=id", accessToken)
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
     * 覆盖写入 / 只创建写入。
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
     *   ⚠️ 但 null **不等于"可以随便覆盖"**：见 [failIfExists] 与调用方
     *   `OneDriveKdbxFileSource.write` 的写后读回校验（ISSUES #172）。
     * @param forceReplace ★ 用户明确选了"用本地覆盖远端"时的**唯一**合法入口 ——
     *   true 时走 `conflictBehavior=replace` **且不带条件**。
     *   ⚠️ 默认 false：**不要**在普通保存路径上传 true。
     * @param failIfExists ★ **只创建**：目标已存在则失败，绝不覆盖。
     *   实现走 HTTP 标准的 `If-None-Match: *`（RFC 9110），已存在时服务端回 412，
     *   由 [OneDrivePreconditionFailedException] 承接。
     *   ⚠️ 优先于 [expectedETag]：置 true 时**不再**发 `If-Match`（两者语义互斥）。
     * @return 写入后的新状态（含**新的** `eTag`，下一次条件写要用它）。
     */
    suspend fun upload(
        accessToken: String,
        path: String,
        bytes: ByteArray,
        expectedETag: String?,
        forceReplace: Boolean = false,
        failIfExists: Boolean = false,
    ): OneDriveEntry = withContext(Dispatchers.IO) {
        if (bytes.size <= SIMPLE_UPLOAD_LIMIT_BYTES) {
            simpleUpload(accessToken, path, bytes, expectedETag, forceReplace, failIfExists)
        } else {
            chunkedUpload(accessToken, path, bytes, expectedETag, forceReplace, failIfExists)
        }
    }

    // ───────────────────────── 写路径内部实现 ─────────────────────────

    private fun simpleUpload(
        accessToken: String,
        path: String,
        bytes: ByteArray,
        expectedETag: String?,
        forceReplace: Boolean,
        failIfExists: Boolean,
    ): OneDriveEntry {
        val url = buildString {
            append(baseUrl).append(buildContentRelativeUrl(path))
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
                // ★ 条件头三选一，顺序即优先级：
                //   ① 只创建（If-None-Match: *）—— 标准语义，存在则 412
                //   ② 条件覆盖（If-Match: etag）—— 必须还是那一版
                //   ③ 什么都不带 —— ⚠️ **这是 2026-10-17 修掉的缺陷**：
                //      原先只写了 ② ③，于是"无基线"直接变成"无条件覆盖同名文件"。
                //      现在②③之间仍有可能走到③，但调用方（OneDriveKdbxFileSource）
                //      会补写后读回校验兜底，见该类的 write。
                if (forceReplace) {
                    Unit
                } else if (failIfExists) {
                    header("If-None-Match", "*")
                } else if (!expectedETag.isNullOrBlank()) {
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
        failIfExists: Boolean,
    ): OneDriveEntry {
        val uploadUrl = createUploadSession(
            accessToken,
            path,
            expectedETag,
            forceReplace,
            failIfExists,
        )

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
        //
        // ⚠️ ★ 两处细节都踩过（CI #171）：
        // 1. `toEntry()` **不能漏**。`lastDto` 是私有的 `OneDriveDriveItemDto`（数据层 DTO），
        //    本函数返回的是 `OneDriveEntry`（对外模型）—— 是**两个不同的类型**。
        //    漏掉转换时 `?:` 两侧没有公共超类型，编译器报的是
        //    `Return type mismatch: expected 'OneDriveEntry', actual 'Any'`，
        //    而**不是**更好懂的"类型不匹配"。
        // 2. 本函数必须是 `suspend`（下面调了 suspend 的 `stat`）。
        return lastDto.takeIf { it.id.isNotBlank() }?.toEntry()
            ?: stat(accessToken, path)
    }

    /**
     * 创建分片上传会话，返回会话的上传 URL。
     *
     * ## 条件写在这里怎么表达
     *
     * Graph 的 uploadSession **不用 `If-Match` 头**，而是把条件写成请求体里的字段：
     * - `conflictBehavior = "fail"` + `ifMatch = <eTag>` ⇒ 远端变了就拒（412）；
     * - `conflictBehavior = "fail"`（无 `ifMatch`）= **只创建**，已存在就拒；
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
        failIfExists: Boolean,
    ): String {
        val sessionUrl = "$baseUrl${buildCreateUploadSessionRelativeUrl(path)}"
        // ★ 请求体**交给序列化器生成**，不再手拼字符串。
        //
        //   ⚠️ 2026-10-17 修掉一个一直存在的**生产缺陷**（写单测时才发现）：
        //   原先是 `append(if (forceReplace) "replace" else "fail")`，**值没加引号**，
        //   实际发出的是 `{"item":{"@microsoft.graph.conflictBehavior":fail}}` ——
        //   非法 JSON。Graph 会回 400，于是**任何超过 2MiB 的库文件在 OneDrive 上
        //   都存不进去**（走的就是分片这条路），而界面只会显示"OneDrive 上传失败"。
        //   这个 bug 活了很久没被发现，因为小库走简单上传、根本不经过这里。
        //   手拼 JSON 的失败模式就是这样：少一对引号，编译器一声不吭。
        val sessionBody = json.encodeToString(
            OneDriveUploadSessionRequestDto(
                item = OneDriveUploadSessionItemDto(
                    conflictBehavior = if (forceReplace) "replace" else "fail",
                    // ★ failIfExists 时**不带** ifMatch：两者的意图不同 ——
                    //   "只创建"不关心远端是哪一版（存在就是错），带 ifMatch 反而会放过
                    //   "恰好还是那一版"的覆盖（那等于把新建变成了覆盖）。
                    ifMatch = expectedETag
                        ?.takeIf { !forceReplace && !failIfExists && it.isNotBlank() },
                ),
            ),
        )
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
            .url("$baseUrl$relativeUrl")
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

    /**
     * 逐段编码：路径里的中文 / 空格 / `#` 不编码会让 Graph 返回 400 或找错文件。
     *
     * ⚠️ ★ **必须用 `Uri.encode`，不能用 `URLEncoder.encode`**（2026-09-17 修）：
     * `URLEncoder` 是 **`application/x-www-form-urlencoded`（表单）** 编码，
     * 它把**空格编成 `+`**。而在 URL 的**路径**里 `+` 是**字面加号**、不代表空格
     * （RFC 3986；只有 query 里才按 form 规则解释）。
     * ⇒ 一个叫 `我的 密码库.kdbx` 的文件会被拿去查 `我的+密码库.kdbx`
     *   ⇒ Graph 回 **404**，而界面只会说"OneDrive 上找不到该文件"，
     *   **文件明明就在那儿、名字也没打错** —— 这类"看着像权限/路径错"的 404，
     *   根因往往就是这一处编码器选错。
     *
     * `Uri.encode` 编的是 URI 路径语义：空格 → `%20`、`+` → `%2B`，两者都不再混淆。
     */
    private fun encodePath(path: String): String = path
        .split('/')
        .filter { it.isNotBlank() }
        .joinToString("/") { segment -> percentEncodePathSegment(segment) }

    /**
     * 按 **RFC 3986 的路径段规则**做百分号编码（纯 Kotlin，不依赖 Android）。
     *
     * ## 为什么不再用 `Uri.encode`
     *
     * 2026-10-17 写条件头单测时被它挡住了：`Uri.encode` 是 `android.net` 的
     * **桩方法**，JVM 单测里 `unitTests.isReturnDefaultValues = true` 会让它
     * 直接返回 `null` ⇒ `encode(...) must not be null` 的 NPE，
     * 于是**任何**走 Graph 的用例都跑不起来（实测 6 例全红，全是这个 NPE）。
     *
     * 当时有两条路：
     * 1. 测试里绕开 —— 做不到，路径编码就在构造 URL 的主线上；
     * 2. 换成纯 Kotlin 实现 —— **顺带消掉了一个隐藏的平台依赖**：
     *    这函数本来就是纯字符串处理，没有任何理由绑 Android。
     *
     * 取第 2 条。编码集与 `Uri.encode` 对齐（`unreserved` + 允许的子分隔符），
     * 因此**线上行为不变**：中文 → `%E4%B8%AD`、空格 → `%20`、`+` → `%2B`。
     *
     * ⚠️ **空格必须是 `%20` 而不是 `+`**（见上面 KDoc 里 2026-09-17 那条）：
     * 路径里的 `+` 是字面加号，`URLEncoder` 那套表单规则在这里是错的。
     */
    private fun percentEncodePathSegment(segment: String): String {
        val out = StringBuilder(segment.length)
        for (byte in segment.toByteArray(Charsets.UTF_8)) {
            val value = byte.toInt() and PATH_SEGMENT_MASK
            val ch = value.toChar()
            if (isPathSegmentSafe(ch)) out.append(ch) else out.appendPercentEncoded(value)
        }
        return out.toString()
    }

    /**
     * 追加 `%XX`（大写十六进制，与 `Uri.encode` 一致）。
     *
     * ⚠️ 抽出来是为了把 [percentEncodePathSegment] 的复杂度拉回 detekt 门禁内：
     * 一个 20 个 `||` 的大条件（`ComplexCondition` 上限 3）+ 一个循环（`CyclomaticComplexMethod`
     * 上限 14）叠在一起必然撞红。**放宽阈值不是选项**（等于把这扇门关掉），
     * 拆成"判一个字符 + 追加几个字符"两件独立的小事才是正解。
     * 附带好处：`isPathSegmentSafe` 是个纯函数，可以脱离 HTTP 直接单测。
     */
    private fun StringBuilder.appendPercentEncoded(value: Int) {
        append('%').append(HEX_UPPER[value shr HEX_SHIFT_BITS]).append(HEX_UPPER[value and HEX_LOW_NIBBLE])
    }

    /**
     * 这个字符在 **URL 路径段**里是否可以原样出现（RFC 3986 §3.3）。
     *
     * 允许两类：
     * - `unreserved`：ALPHA / DIGIT / `-` / `.` / `_` / `~`
     * - `sub-delims` 里的 `!` `$` `&` `'` `(` `)` `*` `+` `,` `;` `=`
     *   （保留下来只为减少输出噪音；Graph 对编不编都接受）
     *
     * ⚠️ 判断按 **Byte→Char** 做：调用方已经按 UTF-8 拆成字节，所以这里
     * 只看 0–255 的单字节字符，中文必然落入"不安全"分支被编码 —— 这正是我们要的。
     */
    private fun isPathSegmentSafe(ch: Char): Boolean = when {
        ch in 'A'..'Z' -> true
        ch in 'a'..'z' -> true
        ch in '0'..'9' -> true
        else -> ch in PATH_SEGMENT_EXTRA_SAFE
    }

    private fun executeJsonRequest(relativeOrAbsoluteUrl: String, accessToken: String): String {
        val url = if (relativeOrAbsoluteUrl.startsWith("https://", ignoreCase = true)) {
            relativeOrAbsoluteUrl
        } else {
            "$baseUrl$relativeOrAbsoluteUrl"
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

    companion object {
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
        /**
         * 生产用的默认客户端。
         *
         * ⚠️ 名字从 `client` 改成 `defaultClient`：原名叫 `client`，
         * 与实例字段 `client` **同名**会让人误以为"companion 里那个就是实例用的"，
         * 实际上改了构造参数后两者已经是两回事。改名是为了让这种误读不可能发生。
         */
        val defaultClient: OkHttpClient = OkHttpClient.Builder()
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

        /** 百分号编码用的十六进制字符表（大写，与 `Uri.encode` 一致）。 */
        const val HEX_UPPER = "0123456789ABCDEF"

        /** `Byte` 转无符号整数用（`and 0xFF`），去掉魔法数字。 */
        const val PATH_SEGMENT_MASK = 0xFF

        /** 一个字节 8 位 ⇒ 取高 4 位要右移 4 位（`%XX` 的高位半字节）。 */
        const val HEX_SHIFT_BITS = 4

        /** 取低 4 位（`%XX` 的低位半字节）。 */
        const val HEX_LOW_NIBBLE = 0x0F

        /**
         * 路径段里除字母数字外**可以原样保留**的字符（RFC 3986 `sub-delims` 与 `-._~`）。
         * 写成字符串常量而不是一串 `||`：既躲开 `ComplexCondition`，也让"哪些能留"一眼可见。
         */
        const val PATH_SEGMENT_EXTRA_SAFE = "-._~!$&'()*+,;="

        /**
         * ⚠️ `explicitNulls = false` **必须显式打开**（默认是 true）：
         * 否则 `ifMatch = null` 会序列化成 `"ifMatch":null` 一起发出去，
         * 而 Graph 对 uploadSession 的 `ifMatch` 只接受字符串 —— 收到 null 可能
         * 回 400，也可能被当成"要匹配 null"直接拒。两种情况都比"不传"糟。
         * `encodeDefaults = false`（默认值）保证带默认值的字段不会被顺手带上。
         */
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}

@Serializable
private data class OneDriveChildrenResponseDto(
    /** 注意字段名带 `@`，故必须显式注解，不能靠命名推导。 */
    @SerialName("@odata.nextLink")
    val nextLink: String? = null,
    val value: List<OneDriveDriveItemDto> = emptyList(),
)

/**
 * `createUploadSession` 的**请求体**。
 *
 * ⚠️ 用 DTO 而不是手拼字符串：字段名带 `@` 前缀、值需要加引号、条件字段时有时无 ——
 * 三处手写都能出错，而且错了编译器不报（2026-10-17 就是因为漏引号，
 * 让 >2MiB 的库在 OneDrive 上一直存不进去）。`encodeDefaults = false` 交给
 * kotlinx-serialization 处理，`null` 字段自动省略。
 */
@Serializable
private data class OneDriveUploadSessionRequestDto(
    val item: OneDriveUploadSessionItemDto,
)

@Serializable
private data class OneDriveUploadSessionItemDto(
    @SerialName("@microsoft.graph.conflictBehavior")
    val conflictBehavior: String,
    /** 只有"覆盖且必须还是那一版"时才带上；`null` 时不会出现在 JSON 里。 */
    val ifMatch: String? = null,
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
