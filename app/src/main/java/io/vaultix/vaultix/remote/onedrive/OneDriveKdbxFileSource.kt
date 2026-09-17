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
    private val authManager: OneDriveAuthManager,
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

    override suspend fun write(
        bytes: ByteArray,
        expectedVersion: String?,
        force: Boolean,
    ): KdbxFileWriteResult {
        val entry = try {
            graph.upload(
                accessToken = token(),
                path = path,
                bytes = bytes,
                // ⚠️ force = true ⇒ 显式**不带** eTag 条件。带了必然 412
                //   （远端确实变了 —— 这正是用户要覆盖的那次改动），
                //   于是用户点了「用本地覆盖远端」却什么也没发生，只能反复点。
                expectedETag = expectedVersion.takeUnless { force },
            )
        } catch (error: OneDrivePreconditionFailedException) {
            // ★ 把"远端变了"翻译成本项目统一的冲突类型 —— 上层只认这一个，
            //   不必（也不该）知道 OneDrive 用 412、WebDAV 也用 412、而语义相同。
            //
            // ⚠️ 这里**顺带拉一次当前版本**：用户接下来很可能选"用远端覆盖本地"，
            //   而那时我们手上必须有**冲突之后**的版本令牌才能写成功。
            //   拉失败也不影响主流程（currentVersion 传 null，上层会再 stat 一次）。
            val current = runCatching { graph.stat(token(), path).versionToken }.getOrNull()
            throw KdbxFileConflictException(current, error.message ?: "OneDrive 上的文件已被其他设备修改")
        }
        return KdbxFileWriteResult(
            versionToken = entry.versionToken,
            sizeBytes = entry.sizeBytes,
            remoteId = entry.id,
            lastModified = parseGraphTimestamp(entry.lastModified),
        )
    }

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
        Unit
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
