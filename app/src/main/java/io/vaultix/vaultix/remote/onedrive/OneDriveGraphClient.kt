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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 一片 PUT 的成果。
 *
 * 把"响应体里的 driveItem"与"响应体里的 `nextExpectedRanges`"一起带出来：
 * 后者是**这一片到底有没有被收下**的唯一一手证据（202 也可能带）。
 * 用命名类而不是 `Pair`，避免调用点读反两个字段 —— 读反的后果是把字节发到错误的偏移上。
 */
private class ChunkAck(
    val dto: OneDriveDriveItemDto,
    val nextExpectedRanges: List<String>?,
)

/**
 * **这一片没有传上去**（重试入环已用尽）—— 专供 [OneDriveGraphClient.chunkedUpload] 的续传层识别。
 *
 * ## 为什么不能继续用 `IOException`
 *
 * 续传层要区分两类失败，而它们对 `IOException` 来说**长得一样**：
 *
 * | 失败 | 该怎么做 |
 * |---|---|
 * | 这一片没上去（网络抖动 / 服务端 5xx） | 问服务端缺哪段，**续传** |
 * | 条件写失败（412）/ 会话失效 | **停下**，让用户拍板或整次重来 |
 *
 * 把前者也当 `IOException` 抛出去，上层只能"整次重来"；把后者吞进续传，
 * 又会绕过"远端已变 ⇒ 让用户拍板"这条最重要的语义。
 */
/**
 * 单次分片 PUT 的失败。带 HTTP 状态码，因为"值不值得重试"完全由它决定
 * （4xx 里只有 408/429 值得；5xx 值得；412 连重试都不允许）。
 */
private enum class ChunkFailureKind { Retry, Resume, Fatal }

private class ChunkPutException(
    val code: Int,
    message: String,
) : IOException(message)

internal class ChunkNotAcceptedException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

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

    /**
     * [lastModified] 解析成毫秒时间戳（解析不了 ⇒ `null`）。
     *
     * ⚠️ 放在这里而不是让调用方各自 `Instant.parse`：**只有一种解析口径**
     * 才谈得上"两处比得起来"。`null` 必须保住"不知道"这个语义 ——
     * 解析失败绝不能返回 0（那会让 `0 < 任何时刻` 恒成立，凭空造出"远端是旧版本"）。
     *
     * 解析实现放在 companion 里（`parseGraphTimestamp`），与 `OneDriveKdbxFileSource`
     * 共用同一个函数 —— 那条**只用于展示**的路径与这条**参与判定**的路径
     * 若各写一份，迟早一份改了一份没改。
     */
    val lastModifiedMillis: Long?
        get() = OneDriveGraphClient.parseGraphTimestamp(lastModified)
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

        // ★★ 整次上传的**断点续传**（#236）
        //
        // 旧行为：某一片重试 [CHUNK_RETRY_COUNT] 次仍失败 ⇒ 抛 IOException ⇒
        // 上层只好整次重来 —— 80 MiB 的库在 90% 处断一次，前面全白传。
        // （rclone / OneDrive 官方 SDK 都是读 `nextExpectedRanges` 从断点继续。）
        //
        // 现在的循环只在两种情况下离开：
        // ① `confirmed` 走到文件尾；
        // ② [judgeResume] 判 GiveUp / Restart。
        // `confirmed` 是**我们已确认写进服务端**的位置，按判据推进或**回退到缺口**；
        // 服务端报的缺口一律夹回它，**绝不越过**（见 `OneDriveUploadResumePolicy` 的 KDoc）。
        var confirmed = 0L
        var lastDto = OneDriveDriveItemDto()
        var stalls = 0
        while (confirmed < bytes.size) {
            val start = confirmed.toInt()
            val end = minOf(start + CHUNK_SIZE_BYTES, bytes.size)
            val chunk = bytes.copyOfRange(start, end)
            val outcome = runCatching { putChunkWithRetry(uploadUrl, accessToken, chunk, start, bytes.size) }
            val ack = outcome.getOrNull()
            // ★ 只有"这一片没传上去"才谈得上续传（#236）。
            //   其余失败必须**原样抛穿**，尤其 412（远端已变）——
            //   把它也当成"这一片丢了"会让续传循环一路重试到成功，
            //   那等于**绕过"远端已变 ⇒ 让用户拍板"**，静默覆盖别人的改动。
            val failure = outcome.exceptionOrNull()
            if (ack == null && failure !is ChunkNotAcceptedException) {
                throw failure ?: IllegalStateException("分片上传失败但没有异常")
            }
            if (ack != null) {
                if (ack.dto.id.isNotBlank()) lastDto = ack.dto
                // ★ 用**这次 PUT 的响应体**里的区间，不再额外发一次 GET（#236）。
                //
                // 旧写法在这里补了一次 `GET <uploadUrl>` 来问进度 —— 每传一片就多一个
                // round trip，80 MiB 的库要多打十几次请求，弱网下每次都要等一个 RTT。
                // 而 Graph 的 PUT 响应体本来就带 `nextExpectedRanges`（202 带、200 也带）。
                // 响应体没带时**信任 2xx 的语义**（这一片收下了）`confirmedAfterAccepted`
                // 在 ranges 为 null 时返回片尾，正是这个意思。
                val next = confirmedAfterAccepted(
                    ranges = ack.nextExpectedRanges,
                    chunkStart = start.toLong(),
                    chunkEndExclusive = end.toLong(),
                ).coerceIn(confirmed, end.toLong())
                val progressed = next > confirmed
                confirmed = next
                stalls = if (progressed) 0 else stalls + 1
                if (!progressed) {
                    // ★ 2xx 却说"这一片还没收下"（`nextExpectedRanges` 仍指向本片起点）。
                    //   不设上限的话这里是**死循环**：同样的 offset 无限重发，
                    //   内存与流量一路涨到 OOM（2026-11-03 实测：测试里直接 `Java heap space`）。
                    //   真机上用户看到的是"同步一直转、手机发热、电量狂掉"。
                    VaultixLog.w(TAG) { "服务端收下响应但未推进进度 $stalls/$MAX_STALLS（起止 $start-$end）" }
                    if (stalls > MAX_STALLS) {
                        throw IOException("OneDrive 上传无进展：服务端连续 $stalls 次没有推进进度（停在 $confirmed）")
                    }
                    Thread.sleep(CHUNK_RETRY_DELAY_MS)
                }
                continue
            }
            // 这一片彻底失败（重试也没救）⇒ 问服务端缺哪一段，再决定下一步。
            val ranges = runCatching { nextExpectedRanges(uploadUrl, accessToken) }.getOrNull()
            val next = resumeStep(ranges, confirmed, stalls, outcome.exceptionOrNull())
            confirmed = next.confirmed
            stalls = next.stalls
        }
        val chunkCount = (bytes.size + CHUNK_SIZE_BYTES - 1) / CHUNK_SIZE_BYTES
        VaultixLog.d(TAG) { "OneDrive 分片上传完成：path=$path bytes=${bytes.size} chunks=$chunkCount" }
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

    /**
     * 单片上传 + 重试 [CHUNK_RETRY_COUNT] 次 —— 移动网络下单片失败很常见，不重试等于白传。
     *
     * ## ★★ 重试之前必须先把会话的进度**对齐到本片之前**（2026-11-02 修）
     *
     * Graph 的 uploadSession 记住"已经收到多少字节"。一次 PUT 的三种结局对会话进度的影响
     * **完全不同**，而**客户端看不到**是哪一种：
     *
     * | 结局 | 服务端收到的字节 | 直接重发同一片会怎样 |
     * |---|---|---|
     * | 请求没到（DNS / 连接失败） | 0 | `416`（与预期偏移不符）或重复数据 |
     * | 请求到了、响应丢了 | 整片 | `416` 或**同一片写两遍** ⇒ 文件长度对得上、内容是坏的 |
     * | 服务端中途断开 | 部分 | `416` / `400` |
     *
     * 而 **[chunkedUpload] 的循环是把 `offset = end` 无条件推进的**：只要这里最终"返回成功"，
     * 上层就会带着缺失 / 重复的片继续往后传，最后拿到 200 和新的 eTag ——
     * 一个**长度正确、内容损坏**的 kdbx 被当作"上传成功"记进基线，下一次保存开始就写在一个坏库上。
     *
     * ⇒ 重试前先 `GET <uploadUrl>` 查会话真实的 `nextExpectedRanges`，把本片**裁**成
     * 「服务端还缺的那一段」再发：
     * - 缺的部分为空（服务端已收全）⇒ 不再发，直接算这片成功；
     * - 服务端进度落后于本片起点（说明整片没到）⇒ 原样重发；
     * - 只缺后半段 ⇒ 只发后半段（`Content-Range` 与内容都跟着裁）。
     *
     * 查询本身失败（网络断得彻底）时**不猜**：原样重发（与旧行为等价），
     * 真正的兜底是 [OneDriveKdbxFileSource.write] 的写后读回校验 —— 但那要整份下载
     * （#232 之后只在这条"元数据证明不了"的路径上才下载），
     * 所以这里先尽力把"不损坏"做到发送侧。
     */
    private fun putChunkWithRetry(
        uploadUrl: String,
        accessToken: String,
        chunk: ByteArray,
        offset: Int,
        totalSize: Int,
    ): ChunkAck {
        var lastError: Throwable? = null
        var pending = chunk
        var pendingOffset = offset
        var alreadyComplete = false
        repeat(CHUNK_RETRY_COUNT) { attempt ->
            // 第一遍按原样发；后续每一遍都先问服务端"还缺哪一段"再裁。
            if (attempt > 0) {
                pending = alignToServerProgress(uploadUrl, accessToken, pending, pendingOffset, lastError)
                if (pending.isNotEmpty()) {
                    pendingOffset = currentExpectedOffset
                } else {
                    // 服务端已收全这一片（上一次的响应只是没回来）⇒ 不必再发。
                    // ⚠️ 用标志位跳出，而不是在 `repeat` 的 lambda 里 `return`：
                    //    后者会让整个函数返回 `Nothing`，编译错误说"expected OneDriveDriveItemDto"，
                    //    看着像类型问题，其实是控制流写错。
                    alreadyComplete = true
                }
            }
            if (!alreadyComplete) {
                val outcome = runCatching {
                    executeChunk(uploadUrl, pending, pendingOffset, totalSize)
                }
                outcome.getOrNull()?.let { return it }
                outcome.exceptionOrNull()?.let { error ->
                    lastError = error
                    // ★ 有的失败**再试也不会变**：412（远端已变）、401/403（要重新登录）。
                    //   在这里重试三次只是把用户多等三秒，然后仍然失败；而 412 被吞进
                    //   续传逻辑的后果前面已经写了 —— 会静默覆盖别人的改动。
                    handleChunkFailure(error)
                    VaultixLog.w(TAG) { "分片重试 ${attempt + 1}/$CHUNK_RETRY_COUNT：${error.message}" }
                    Thread.sleep(CHUNK_RETRY_DELAY_MS)
                }
            }
        }
        // 服务端已把这一片收全（只是上一次的响应没回来）⇒ 视为成功，
        // 让循环往下一片走。**不能**在这里报错退出：那样会让一次网络抖动
        // 变成"整份文件永远传不上去"。
        if (alreadyComplete) return ChunkAck(OneDriveDriveItemDto(), null)
        // ★ 入环用尽（#236）：**不在这里决定整次上传作废**。
        //
        // 旧行为是这里 `throw IOException` ⇒ [chunkedUpload] 整次失败 ⇒ 上层从 0 重传，
        // 前面传过的片全白费。现在抛一个**专供续传层识别**的类型，
        // 让 `chunkedUpload` 去问服务端缺哪一段、从确认位继续 ——
        // 这正是"断点续传"与"整次重来"的分界：前者只丢一片的进度，后者丢一整个库。
        throw ChunkNotAcceptedException(lastError?.message ?: "分片上传失败", lastError)
    }

    /**
     * 发一片，并把响应翻译成 [ChunkAck]。
     *
     * ⚠️ 失败分两类，**类型必须不同**（否则上层无法判断能不能续传）：
     * - [OneDrivePreconditionFailedException]：远端已变，**绝对不能**重试也**不能**续传；
     * - [ChunkPutException]：这一片没上去，按 [ChunkPutException.code] 判断值不值得重试。
     */
    private fun executeChunk(
        uploadUrl: String,
        pending: ByteArray,
        pendingOffset: Int,
        totalSize: Int,
    ): ChunkAck {
        val request = Request.Builder()
            .url(uploadUrl)
            .header("Content-Length", pending.size.toString())
            .header(
                "Content-Range",
                "bytes $pendingOffset-${pendingOffset + pending.size - 1}/$totalSize",
            )
            .put(pending.toRequestBody(OCTET_STREAM_MEDIA_TYPE.toMediaType()))
            .build()
        return client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == HTTP_PRECONDITION_FAILED) {
                throw OneDrivePreconditionFailedException(
                    "OneDrive 上的文件已被其他设备修改（HTTP 412），为避免覆盖已取消写入",
                )
            }
            if (!response.isSuccessful) {
                throw ChunkPutException(response.code, body.ifBlank { "分片上传失败：HTTP ${response.code}" })
            }
            // 200/201 = 完成（响应体是 driveItem 且带 id）；202 = 中间片。
            // 两者都可能带 `nextExpectedRanges`（Graph 用同一个 schema），
            // 能读就读 —— 它是"这一片到底收下没有"的唯一一手证据。
            ChunkAck(
                dto = if (response.code in setOf(HTTP_OK, HTTP_CREATED) && body.isNotBlank()) {
                    runCatching { json.decodeFromString<OneDriveDriveItemDto>(body) }
                        .getOrDefault(OneDriveDriveItemDto())
                } else {
                    OneDriveDriveItemDto()
                },
                nextExpectedRanges = parseNextExpectedRanges(body),
            )
        }
    }

    /**
     * 按 [classifyChunkFailure] 的结论**当场**处置一次分片失败；正常返回即表示"可以再试"。
     *
     * 抽成独立函数有两个原因：① `putChunkWithRetry` 的 `ThrowsCount` 会超（这里两条
     * `throw` 各自对应完全不同的结局）；② "哪一种失败该上抛"这条判断集中在一处，
     * 比散在重试循环里更难写错。
     */
    private fun handleChunkFailure(error: Throwable) {
        when (classifyChunkFailure(error)) {
            ChunkFailureKind.Fatal -> throw error
            // 会话级失败（404/410）：重试同一个地址没有意义，但**续传层**能读懂它
            // （它会去查进度，看到缺口 0 就判 Restart）。所以包成"这一片没上去"，
            // 让 `chunkedUpload` 走 [resumeStep] 而不是把裸 body 甩给用户。
            ChunkFailureKind.Resume ->
                throw ChunkNotAcceptedException(error.message ?: "分片上传失败", error)
            ChunkFailureKind.Retry -> Unit
        }
    }

    /**
     * 一次分片失败该怎么处置 —— 三态，缺一档就会把某种失败塞进错误的桶里。
     *
     * | 值 | 含义 | 例子 |
     * |---|---|---|
     * | [ChunkFailureKind.Retry] | 再试一次可能成功 | 5xx、408、429、连接断开 |
     * | [ChunkFailureKind.Resume] | 重试同一个地址没用，但**续传层能读懂** | 404 / 410（会话没了） |
     * | [ChunkFailureKind.Fatal] | 必须原样上抛，让上层做业务决定 | **412（远端已变）**、401/403 |
     *
     * ⚠️ 412 必须在 [ChunkFailureKind.Fatal]：它意味着"远端不是你以为的那一版"，
     * 重试多少次都不会变；一旦因为别处又改动而条件重新成立，续传就会**静默覆盖**别人的改动。
     */
    private fun classifyChunkFailure(error: Throwable): ChunkFailureKind = when (error) {
        is OneDrivePreconditionFailedException -> ChunkFailureKind.Fatal
        is ChunkPutException -> when {
            error.code == HTTP_REQUEST_TIMEOUT || error.code == HTTP_TOO_MANY_REQUESTS ->
                ChunkFailureKind.Retry
            error.code >= HTTP_SERVER_ERROR -> ChunkFailureKind.Retry
            error.code == HTTP_NOT_FOUND || error.code == HTTP_GONE -> ChunkFailureKind.Resume
            else -> ChunkFailureKind.Fatal
        }
        else -> ChunkFailureKind.Retry // 网络层异常（连接断开 / 超时 / DNS）
    }

    /**
     * 上一次 [alignToServerProgress] 问出来的"服务端还缺的起点"。
     *
     * ⚠️ 单独一个字段而不是把 `Pair` 从函数里透出来：`Pair<ByteArray, Int>` 在调用点上
     * 极容易被读反（先取 offset 还是先取字节），而读反的后果是把字节发到错误的偏移上 ——
     * 又是一个"不报错但文件坏了"的形状。这里让"字节"作返回值、"偏移"作状态，只有一种读法。
     */
    private var currentExpectedOffset: Int = 0

    /**
     * 把 [pending]（本片剩余内容，起点 [offset]）裁成"服务端**还缺**的那一段"。
     *
     * @return 要重发的内容（空 = 服务端已收全，不用再发）。同时把
     *   [currentExpectedOffset] 置成这一段的起点。
     *
     * 查询失败时**原样返回**（起点仍是 [offset]）：那是"不知道"，而不是"不用发"。
     */
    private fun alignToServerProgress(
        uploadUrl: String,
        accessToken: String,
        pending: ByteArray,
        offset: Int,
        cause: Throwable?,
    ): ByteArray {
        currentExpectedOffset = offset
        val expected = runCatching { nextExpectedRangeStart(uploadUrl, accessToken) }.getOrElse { error ->
            VaultixLog.w(TAG) { "查询上传进度失败（按原样重发）：${error.message}" }
            return pending
        }
        // 拿不到进度（会话已过期 / 非 2xx）⇒ 原样重发，让下一次 HTTP 给出真实错误。
        if (expected == null) return pending
        val end = offset + pending.size
        val (start, resume) = when {
            expected >= end -> return ByteArray(0)
            expected <= offset -> offset to pending
            else -> expected to pending.copyOfRange(expected - offset, pending.size)
        }
        currentExpectedOffset = start
        VaultixLog.d(TAG) {
            "上传进度对齐：服务端期望 $expected，本片 [$offset,$end) ⇒ 重发 ${resume.size}B" +
                cause?.message?.let { msg -> "（上次失败：$msg）" }.orEmpty()
        }
        return resume
    }

    /**
     * 问 uploadSession：下一片应该从哪个字节开始（`nextExpectedRanges` 的第一段起点）。
     *
     * @return null = 服务端没给可用信息（此时调用方按"不知道"处理）。
     */
    private fun nextExpectedRangeStart(uploadUrl: String, accessToken: String): Int? {
        val ranges = nextExpectedRanges(uploadUrl, accessToken) ?: return null
        return ranges.firstOrNull()?.substringBefore('-')?.trim()?.toIntOrNull()
    }

    /** [resumeStep] 的产物：新的确认位 + 新的连续无进展计数。 */
    private class ResumeStep(val confirmed: Long, val stalls: Int)

    /**
     * 一片彻底失败之后该做什么 —— 把 [judgeResume] 的结论翻译成"下一步的循环变量"。
     *
     * ## 为什么单独抽出来（而不是写在循环里）
     *
     * 抽出来才满足 detekt `ThrowsCount`（上限 2）：循环里只剩 `while` + 一次赋值，
     * 而这里的两条 `throw` 各自对应一种**完全不同的结局**（会话失效让她重新上传 /
     * 反复失败要告诉用户），本来就不该混在控制流里。
     *
     * ## `offset < confirmed` 时把确认位**回退**
     *
     * 服务端把我们已确认的部分又算成缺失 ⇒ 那几片确实丢了（不是"我们记多了"）。
     * 回退后重发是幂等的；不回退就会在那段留一个永久缺口。
     */
    private fun resumeStep(
        ranges: List<String>?,
        confirmed: Long,
        stalls: Int,
        cause: Throwable?,
    ): ResumeStep {
        return when (val verdict = judgeResume(ranges, confirmed, stalls)) {
            is ChunkResumeVerdict.Continue -> {
                val noProgress = if (verdict.progressed) 0 else stalls + 1
                if (!verdict.progressed) {
                    VaultixLog.w(TAG) { "续传无进展 $noProgress/$MAX_STALLS：${verdict.reason}" }
                    if (noProgress > 0) Thread.sleep(CHUNK_RETRY_DELAY_MS)
                }
                ResumeStep(confirmed = verdict.offset, stalls = noProgress)
            }
            // **重建会话**（= 从头来）是上层的事：如果在这里自己重建，会把
            // "条件写失败（412）"也吞进重来逻辑里 —— 那会绕过
            // "远端已变 ⇒ 让用户拍板"这条最重要的语义。
            is ChunkResumeVerdict.Restart ->
                throw IOException("OneDrive 上传会话已失效，续传无法继续（${verdict.reason}）", cause)
            is ChunkResumeVerdict.GiveUp ->
                throw IOException("OneDrive 上传反复失败：${verdict.reason}", cause)
        }
    }

    /**
     * 问 uploadSession 的**原始** `nextExpectedRanges`（#236）。
     *
     * 与 [nextExpectedRangeStart] 的区别：那个把"区间"折算成一个 `Int`，
     * 丢掉了"字段缺失"与"给了个空值"的区别 —— 而那正是 [judgeResume] 需要的输入。
     * ⚠️ 返回 `null` = 这次查询本身失败（HTTP 非 2xx / 解析不了）**或**字段缺失；
     *    `emptyList()` = 服务端明确回了空数组。两者在续传判定里的处理**不同**。
     *
     * ## DTO 里为什么要把"缺失"和"空数组"分开
     *
     * `OneDriveUploadSessionStatusDto.nextExpectedRanges` 的默认值是 `emptyList()`，
     * 于是"字段不存在"和"字段是空数组"在解码后**长得一模一样**。
     * ⇒ 这里用 `JsonElement` 看原始 JSON 有没有这个键，才能把它们分开。
     */
    private fun nextExpectedRanges(uploadUrl: String, accessToken: String): List<String>? {
        // ⚠️ 必须带 token —— Graph 的 uploadSession URL 是**预签名的完整地址**（cdn 域），
        //    正常情况下不带也行；但测试/代理会把会话挂在 graph 域下，那时 401 会被
        //    [nextExpectedRangeStart] 当成"没有进度" ⇒ 整条续传逻辑静默失效（不报错，只是白做）。
        //    带上 Authorization 对预签名地址无害（CDN 忽略它，见 client 拦截器里的摘头规则）。
        val request = Request.Builder()
            .url(uploadUrl)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            parseNextExpectedRanges(response.body?.string().orEmpty())
        }
    }

    /**
     * 从任意一段 Graph 响应体里读 `nextExpectedRanges`（#236）。
     *
     * ⚠️ 返回 `null` = **字段缺失**（或整段不可解析）；`emptyList()` = 服务端明确回了空数组。
     * 两者在 [judgeResume] 里的处置**不同**（前者按"不知道"原地重发，后者判重启），
     * 所以这里不能把"缺失"折算成空列表 —— 那正是
     * `OneDriveUploadSessionStatusDto` 的默认值会把两件事混成一件的地方。
     */
    private fun parseNextExpectedRanges(body: String): List<String>? {
        if (body.isBlank()) return null
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return null
        val array = root[NEXT_EXPECTED_RANGES] as? JsonArray ?: return null
        return array.mapNotNull { element -> (element as? JsonPrimitive)?.contentOrNull }
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
        versionToken = versionTokenOf(),
        sizeBytes = size,
        lastModified = lastModifiedDateTime,
    )

    /**
     * ★ 版本令牌的选择：**优先 cTag，绝不拿 eTag 顶替**（#244，2026-11-04）。
     *
     * ## 为什么不是 `eTag ?: cTag`（旧写法）
     *
     * 两者的语义在 Graph 里被明确定义过，且**完全不同**：
     *
     * | 字段 | 覆盖范围 | 什么时候变 |
     * |---|---|---|
     * | `eTag` | 整个 item（**元数据 + 内容**） | 任何属性变化（改权限、服务端处理…） |
     * | `cTag` | **只有内容** | 内容变了（文档是这么说的） |
     *
     * 上层拿这个令牌回答的是「**远端内容**变了没有」，所以正确答案是 cTag。
     * 拿 eTag 顶替，等于把"元数据动了一下"读成"别人改了密码库" ⇒ 用户什么都没做
     * 就看到「冲突」，随后还要在两个都对的选项里赌一把（真机报障的原文）。
     *
     * ## 为什么不是 `cTag ?: eTag`（简单的顺序调换也不行）
     *
     * 条件写（`If-Match`）里放的是**服务端认的那个值**，而它来自我们上次拿到的令牌；
     * 只要"取令牌"与"用令牌"用的是同一个字段，服务端就自洽。真正的问题只有一个：
     * **同一个字段必须在所有路径上一致**。所以这里定死 cTag 优先、eTag 只在
     * cTag 缺失时兜底，并且把"这次用的是哪个字段"编码进前缀 ——
     * 否则一次 `stat` 拿到 cTag、一次拿到 eTag，两者永不相等 ⇒ 每次都判"远端变了"。
     *
     * ## ⚠️ cTag 并不严格（已知局限，但不改变本决策）
     *
     * 实测反馈显示 SharePoint / OneDrive for Business 上重命名、移动也会让 cTag 变。
     * 那会让 `remoteChanged` 出现**假阳性**（说"远端变了"而内容没变），
     * 属于"多拉一次"的代价；而 eTag 的假阳性会让用户被拦下来做**有损选择**。
     * 两者代价不对称 ⇒ 仍取 cTag。真正的收敛靠 `KdbxSyncOrchestrator` 里的
     * **内容对账**（字节相同就不算冲突），本方法只负责别再主动制造假阳性。
     *
     * @return 形如 `ctag:{值}` / `etag:{值}`；两者都没有时返回 null（上层按"远端
     *   版本未知"处理 —— 那比编一个值出来诚实）。
     */
    private fun OneDriveDriveItemDto.versionTokenOf(): String? {
        cTag?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { return io.vaultix.data.kdbx.TAG_PREFIX_CTAG + it }
        eTag?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { return io.vaultix.data.kdbx.TAG_PREFIX_ETAG + it }
        return null
    }

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

        /**
         * Graph 的时间是 ISO-8601 字符串（如 `2026-09-17T12:34:56Z`）。
         *
         * ## 为什么放在 companion 而不是实例方法（2026-11-02，#232）
         *
         * 它有两个使用者：[OneDriveKdbxFileSource] 填 `KdbxFileStat.lastModified`（仅展示），
         * 以及 [OneDriveEntry.lastModifiedMillis] → `readBackPlan`（**参与判定**）。
         * 而后者是纯数据类，拿不到客户端实例。放在这里两种调用都成立，
         * 解析口径因此只有一份 —— 展示与判定用两套解析，迟早一套改了一套没改。
         *
         * ⚠️ 用 `runCatching` 兜住：展示场景下解析失败不该让"读取文件"这个主要目的失败。
         * 参与判定时 `null` 的语义是**"不知道"**，调用方据此跳过时间戳那条判据，
         * 绝不退化成"没改过"。
         */
        internal fun parseGraphTimestamp(raw: String?): Long? {
            val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return runCatching {
                // API 26+ 才有 java.time；本项目 minSdk = 26，可直接用。
                java.time.Instant.parse(text).toEpochMilli()
            }.getOrNull()
        }
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

        /** Graph 进度响应里的字段名（只在 [nextExpectedRanges] 读原始 JSON 时用）。 */
        const val NEXT_EXPECTED_RANGES = "nextExpectedRanges"

        /**
         * 用到的 HTTP 状态码。
         *
         * ⚠️ 提成常量不是为了"消灭魔法数字"，而是因为它们在**两个地方**出现
         * （抛异常的判据 + 翻成人话的 switch），两处写错一个就会变成
         * "412 走了 else 分支"或"权限不足报成了登录失效" —— 那类 bug 又静默又难查。
         */
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_OK = 200
        const val HTTP_CREATED = 201
        const val HTTP_NOT_FOUND = 404
        const val HTTP_REQUEST_TIMEOUT = 408
        const val HTTP_GONE = 410
        /** 条件写失败（`If-Match` 不匹配 / `If-None-Match: *` 命中已存在的文件）。 */
        const val HTTP_PRECONDITION_FAILED = 412
        const val HTTP_TOO_MANY_REQUESTS = 429
        /** OneDrive 配额耗尽。 */
        const val HTTP_SERVER_ERROR = 500
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
