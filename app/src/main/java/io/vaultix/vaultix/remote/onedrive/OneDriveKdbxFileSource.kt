/*
 * Vaultix — app / OneDrive
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * ★ 溯源声明（GPL-3.0）：把「Graph 端点 → KdbxFileSource 契约」的映射
 *   （eTag 当版本令牌、412 当冲突、path 形如 `<dir>/<name>.kdbx`）
 *   对照 Bastion 的 `com.bastion.app.utils.OneDriveKeePassFileSource`（GPL-3.0）实现。
 *
 * ## 为什么落在 `app` 而不是 `data:repository`
 *
 * 依赖决定位置：本实现需要 **MSAL**（`OneDriveAuthManager`）与 `OneDriveGraphClient`，
 * 而两者都在 `app`。把 MSAL 下沉到 data 层是一次不小的工程（要动依赖与 Hilt 装配），
 * 而本实现**只有几十行**（真正的 HTTP 细节都在 [OneDriveGraphClient] 里）。
 * ⇒ 现阶段放在 `app`（依赖齐备、改动最小）；将来若要收敛到 `data:remote`，
 *   搬走这个文件即可，`KdbxFileSource` 契约本身已经在 `data:kdbx`（纯逻辑）里了。
 *
 * ## 与 `OneDriveGraphClient` 的分工
 *
 * - `OneDriveGraphClient`：**纯 HTTP**（端点、分页、状态码、分片、重试）。
 *   它对"这是不是密码库"一无所知。
 * - 本文件：**契约适配**（把 eTag 映射成 versionToken、把 412 映射成
 *   `KdbxFileConflictException`、把路径拆成"目录 + 文件名"）。
 *
 * ⚠️ 不把两者合并：HTTP 客户端的正确性可以用假响应测（不需要 KDBX），
 * 而适配层的正确性用假客户端测。分开才都能测。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.remote.onedrive

import io.vaultix.data.kdbx.KdbxFileConflictException
import io.vaultix.data.kdbx.KdbxFileEntry
import io.vaultix.data.kdbx.KdbxFileSource
import io.vaultix.data.kdbx.KdbxFileStat
import io.vaultix.data.kdbx.KdbxFileWriteResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OneDrive 上的一个 `.kdbx` 文件（[KdbxFileSource] 的 OneDrive 实现）。
 *
 * @param accountId MSAL 账户 id（拿 access token 用）。
 * @param path 相对 OneDrive 根目录的路径，如 `Vaultix/vault.kdbx`。
 *   ⚠️ 不是 `onedrive:` 那种 origin 字符串 —— origin 解析在
 *   [OneDriveVaultOrigin] 里做，两者职责不同（origin 要能持久化进 Room，
 *   path 只在运行时用）。
 */
class OneDriveKdbxFileSource(
    /**
     * ⚠️ 类型是 [OneDriveTokenProvider]（接口），**不是** [OneDriveAuthManager]。
     *    生产侧传的仍然是真管理器（它实现了该接口），但测试可以传假实现 ——
     *    否则本类在 JVM 单测里根本实例化不出来（`@ApplicationContext` + MSAL）。
     *    这是个纯粹的**类型收窄**，行为零变化。
     */
    private val authManager: OneDriveTokenProvider,
    private val graph: OneDriveGraphClient,
    private val accountId: String,
    private val path: String,
) : KdbxFileSource {

    override suspend fun stat(): KdbxFileStat {
        val entry = graph.stat(token(), path)
        return KdbxFileStat(
            // ★ 直接就是 eTag —— Graph 的 eTag 不带 W/ 前缀，**不要**再归一化，
            //   否则拿它当 If-Match 的值会被服务端拒（两边说的不是同一句话）。
            versionToken = entry.versionToken,
            sizeBytes = entry.sizeBytes,
            remoteId = entry.id,
            displayName = entry.name,
            lastModified = parseGraphTimestamp(entry.lastModified),
        )
    }

    override suspend fun read(): ByteArray = graph.download(token(), path)

    /**
     * 写入 OneDrive 上的库文件。
     *
     * ## ★ 为什么这里必须有「写后读回校验」（2026-10-17，ISSUES #172）
     *
     * `KdbxFileSource.write` 的契约要求：`expectedVersion = null`（无基线）时，
     * 实现方**不得**当成"随便覆盖"的许可，而要退化为最严保障（写前预检 + 写后读回）。
     * OneDrive 这条路径以前**两样都没有**：
     * `simpleUpload` 在 `expectedETag` 为空时一个条件头都不带 ⇒ Graph 直接覆盖同名文件。
     * 之所以一直没炸，是因为唯一的无基线调用方（[Kdbx.createVault]）恰好先 `stat` 过。
     * 那是**调用方的自觉**，不是来源层的保证 —— 一旦新增一条无基线写入路径，
     * 就会变成「新建同名库 = 静默覆盖旧库」。
     *
     * 现在两道闸：
     * 1. `failIfExists` 由调用方显式声明时发 `If-None-Match: *`（服务端拒绝）；
     * 2. **无论哪种情况**，写完都把内容读回来比对 —— 这是唯一能覆盖
     *    "服务端忽略了我们的头 / 传输中被截断"的手段。
     *    ⚠️ 代价是每次保存多一次下载；KDBX 库通常几百 KB，可接受。
     *    与 `SafKdbxFileSource` 的做法一致（那边是"SAF 没有 rename，写坏没有退路"，
     *    这边是"云端没有事务，写坏不可回滚"，理由同构）。
     */
    override suspend fun write(
        bytes: ByteArray,
        expectedVersion: String?,
        force: Boolean,
        failIfExists: Boolean,
    ): KdbxFileWriteResult {
        val token = token()
        val entry = try {
            graph.upload(
                accessToken = token,
                path = path,
                bytes = bytes,
                // ⚠️ force = true ⇒ 显式**不带** eTag 条件。带了必然 412
                //   （远端确实变了 —— 这正是用户要覆盖的那次改动），
                //   于是用户点了「用本地覆盖远端」却什么也没发生，只能反复点。
                expectedETag = expectedVersion.takeUnless { force },
                // ⚠️ force 会覆盖一切条件，所以只在非 force 时才声明"只创建"。
                failIfExists = failIfExists && !force,
            )
        } catch (error: OneDrivePreconditionFailedException) {
            // ★ 把"远端变了"翻译成本项目统一的冲突类型 —— 上层只认这一个，
            //   不必（也不该）知道 OneDrive 用 412、WebDAV 也用 412、而语义相同。
            //
            // ⚠️ 这里**顺带拉一次当前版本**：用户接下来很可能选"用远端覆盖本地"，
            //   而那时我们手上必须有**冲突之后**的版本令牌才能写成功。
            //   拉失败也不影响主流程（currentVersion 传 null，上层会再 stat 一次）。
            val current = runCatching { graph.stat(token, path).versionToken }.getOrNull()
            // ⚠️ 把原异常挂到 cause 上：只为让用户看到一句人话而丢掉原始异常，
            //   会让"到底是哪个请求 412 的"这类排查失去唯一线索。
            throw KdbxFileConflictException(
                current,
                error.message ?: "OneDrive 上的文件已被其他设备修改",
                error,
            )
        }

        // ★ 写后读回校验：**抛而不是返回失败**，因为上层只处理异常
        //   （`Kdbx.verifyWrite` / 同步状态机都是靠 catch 决定要不要记成功）。
        verifyReadBack(bytes, entry, token)

        return KdbxFileWriteResult(
            versionToken = entry.versionToken,
            sizeBytes = entry.sizeBytes,
            remoteId = entry.id,
            lastModified = parseGraphTimestamp(entry.lastModified),
        )
    }

    /**
     * 读回刚才写下去的内容并逐字节比对。
     *
     * ⚠️ 失败时抛 [KdbxFileConflictException]（而不是 `IllegalStateException`）：
     * 语义上它确实是"远端状态与我们的预期不一致"，上层对它的处理（转冲突、
     * 让用户决定）也是对的。用另一种异常会让这条路径悄悄掉进"未知错误"分支。
     *
     * ⚠️ 复用 write 里已经取到的 token，**不要**在这里再 `token()` 一次：
     * 那是又一次异步刷新（多一个网络往返），而且两次拿到的可能不是同一个
     * token（刚好过期刷新过），排查时会对不上。
     */
    private suspend fun verifyReadBack(
        bytes: ByteArray,
        entry: OneDriveEntry,
        accessToken: String,
    ) {
        // 先用大小做一次廉价预筛：大小都不对就别浪费一次完整下载。
        // ⚠️ 预筛**不能**直接写在这个函数里：detekt `ThrowsCount`(excludeGuardClauses=true)
        //   只把"第一条非声明语句之前的 throw"当守卫子句放行，排在一次 suspend 调用之后
        //   就不再算守卫 —— 实测预筛放这儿必报 3（上限 2）。挪进 [verifySize] 后，
        //   本函数正好只剩两条 throw，而且两条各管一类失败（读不回 / 读回不一致），
        //   语义上本来也不该和"大小预筛"挤在一起。
        verifySize(bytes, entry)
        val downloaded = runCatching { graph.download(accessToken, path) }
        val actual: ByteArray? = downloaded.getOrNull()
        if (actual == null) {
            // 读不回来**不能**当成"写成功了"：那正是"以为存好了其实没有"的场景。
            val failure = downloaded.exceptionOrNull() ?: IllegalStateException("下载返回了空结果")
            throw readBackFailureOf(entry, failure)
        }
        if (!actual.contentEquals(bytes)) {
            throw mismatchOf(entry, bytes.size, actual.size)
        }
    }

    /** 远端记录的大小与刚写下去的对不上 ⇒ 立刻冲突，省掉一次完整下载。 */
    private fun verifySize(bytes: ByteArray, entry: OneDriveEntry) {
        val recorded = entry.sizeBytes ?: return
        if (recorded != bytes.size.toLong()) {
            throw sizeMismatchOf(entry, bytes.size)
        }
    }

    /**
     * 大小对不上 ⇒ 该抛的冲突（纯构造，自己不抛；理由同 [readBackFailureOf]）。
     */
    private fun sizeMismatchOf(entry: OneDriveEntry, writtenSize: Int): KdbxFileConflictException =
        KdbxFileConflictException(
            currentVersion = entry.versionToken,
            message = "写入后校验不一致（写入 $writtenSize 字节，远端记录 ${entry.sizeBytes} 字节），请重新同步",
        )

    /**
     * "读不回来" ⇒ 该抛的冲突（**纯构造，自己不抛**）。
     *
     * ⚠️ 形状理由同 `WebDavKdbxFileSource.writeFailureOf`：detekt `ThrowsCount` 上限 2，
     * 三个失败分支各写一个 `throw` 必然超标。把"该抛什么"变成返回异常的纯函数，
     * `verifyReadBack` 里就只剩两次 throw，而且分类逻辑本身变得可脱离 HTTP 单测。
     */
    private fun readBackFailureOf(entry: OneDriveEntry, error: Throwable): KdbxFileConflictException =
        KdbxFileConflictException(
            currentVersion = entry.versionToken,
            message = "写入后无法读回校验：${error.message.orEmpty()}",
            cause = error,
        )

    /** 读回来内容不一致 ⇒ 该抛的冲突（同上，纯构造）。 */
    private fun mismatchOf(
        entry: OneDriveEntry,
        writtenSize: Int,
        actualSize: Int,
    ): KdbxFileConflictException = KdbxFileConflictException(
        currentVersion = entry.versionToken,
        message = "写入后读回校验不一致（写入 $writtenSize 字节，读回 $actualSize 字节），请重新同步",
    )

    override suspend fun listChildren(): List<KdbxFileEntry> {
        val directory = path.substringBeforeLast('/', missingDelimiterValue = "")
        return graph.listChildren(token(), directory.takeIf { it.isNotBlank() }).map { entry ->
            KdbxFileEntry(
                name = entry.name,
                id = entry.id,
                isDirectory = entry.isDirectory,
                versionToken = entry.versionToken,
                lastModified = parseGraphTimestamp(entry.lastModified),
                sizeBytes = entry.sizeBytes,
            )
        }
    }

    override suspend fun testConnection(): Result<Unit> = runCatching {
        // 用 stat 而不是 `graph.testConnection`：后者只证明"登录有效"，
        // 前者还证明"这个具体文件在、而且我能读到它" —— 这才是用户要的答案。
        graph.stat(token(), path)
    }

    /**
     * 取 access token。
     *
     * ⚠️ **每次都现取**（[OneDriveAuthManager.acquireAccessToken] 内部会静默刷新），
     * **不要缓存进字段**：token 有有效期（通常 1 小时），缓存下来 = 一个小时后
     * 所有网络调用一起失败，而用户看到的是"突然全都读不了了"。
     * MSAL 自己会缓存，这里再缓存一层纯属添乱。
     */
    private suspend fun token(): String {
        val session = authManager.acquireAccessToken(accountId)
        return session.accessToken
            ?: throw IllegalStateException("OneDrive 登录已失效，请重新登录")
    }

    @Singleton
    class Factory @Inject constructor(
        /**
         * 这里仍是**具体类型** `OneDriveAuthManager`：Factory 是从 origin 建
         * `KdbxFileSource` 的装配点，拿真管理器天经地义；它实现了
         * [OneDriveTokenProvider]，赋给上面那个 `authManager` 参数自动成立。
         */
        private val authManager: OneDriveAuthManager,
        private val graph: OneDriveGraphClient,
    ) {
        /** 从持久化的 origin 字符串构造。格式见 [OneDriveVaultOrigin]。 */
        fun create(origin: String): KdbxFileSource? {
            val parsed = OneDriveVaultOrigin.parse(origin) ?: return null
            return OneDriveKdbxFileSource(
                authManager = authManager,
                graph = graph,
                accountId = parsed.accountId,
                path = parsed.path,
            )
        }
    }

    private companion object {
        /**
         * Graph 的时间是 ISO-8601 字符串（如 `2026-09-17T12:34:56Z`）。
         *
         * ⚠️ 用 `runCatching` 兜住：时间戳**只用于展示**，解析失败不该让
         * "读取文件"这个主要目的失败。返回 null 时 UI 就不显示时间。
         */
        fun parseGraphTimestamp(raw: String?): Long? {
            val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return runCatching {
                // API 26+ 才有 java.time；本项目 minSdk = 26，可直接用。
                java.time.Instant.parse(text).toEpochMilli()
            }.getOrNull()
        }
    }
}

/**
 * OneDrive 库的 `origin` 编解码。
 *
 * ## 格式
 *
 * ```
 * onedrive:<accountId>:<percentEncodedPath>
 * ```
 *
 * ## 为什么这么设计（而不是直接存 https URL）
 *
 * | 需求 | 做法 |
 * |---|---|
 * | 要能判别来源 | 前缀 `onedrive:`（方案 §1.3 的"origin 前缀即来源判别"） |
 * | 要能重新登录后找到**同一份文件** | 存 `accountId` + 路径，**不存 itemId** |
 * | 路径可能含 `:`（Windows 风格目录名真的有人用） | 路径**整体**做百分号编码 ⇒ `:` 不再是分隔符 |
 * | **绝不能存凭据** | 这里只有 accountId 与路径；token 在 MSAL 自己的缓存里 |
 *
 * ⚠️ 不存 `itemId` 的理由：itemId 在"删除后重新上传""移动到别的目录"后会变，
 * 而**路径**是用户认知里的稳定标识。代价是每次要按路径解析一次 —— 可接受。
 */
object OneDriveVaultOrigin {

    const val PREFIX: String = "onedrive:"

    /** 是否是 OneDrive 来源的 origin（**只看前缀**，不解析）。 */
    fun matches(origin: String): Boolean = origin.startsWith(PREFIX)

    fun build(accountId: String, path: String): String =
        "$PREFIX$accountId:${encode(path.trim().trim('/'))}"

    fun parse(origin: String): Parsed? {
        if (!matches(origin)) return null
        val rest = origin.removePrefix(PREFIX)
        val separator = rest.indexOf(':')
        if (separator <= 0) return null
        val accountId = rest.substring(0, separator)
        val path = runCatching { decode(rest.substring(separator + 1)) }.getOrNull()
        if (accountId.isBlank() || path.isNullOrBlank()) return null
        return Parsed(accountId = accountId, path = path)
    }

    data class Parsed(val accountId: String, val path: String)

    private fun encode(path: String): String =
        java.net.URLEncoder.encode(path, "UTF-8")

    private fun decode(path: String): String =
        java.net.URLDecoder.decode(path, "UTF-8")
}
