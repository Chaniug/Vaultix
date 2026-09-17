/*
 * Vaultix — data:repository
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * **本地 SAF 来源** —— [KdbxFileSource] 在 `content://` 上的实现。
 *
 * ## 为什么本地也要实现"网盘那套"接口
 *
 * 方案 §3.3 的取向：**先在本地把写路径打稳，网盘只是"换一个字节去处"**。
 * 如果本地走一条单独的只读路径，那么"冲突检测""写回""条件写"这套逻辑
 * 只能等网盘落地才有地方验证 —— 风险全部堆到联网那一刻。
 * 让本地也实现同一个接口，冲突检测在**本地就能跑通测试**（离线、可重复）。
 *
 * ## ★ 版本令牌用「内容 SHA-256」，不用 `lastModified`
 *
 * 这是本地路径**唯一真正需要设计**的点：
 *
 * | 候选 | 问题 |
 * |---|---|
 * | `DocumentFile.lastModified()` | 精度到**秒**。同一秒内改两次看不出差异 ⇒ 漏报冲突 |
 * | `ContentResolver` 的 `SIZE` | 换内容不改大小（KDBX 换个密码长度就变了，但同长度改名不会） |
 * | **内容 SHA-256** | ✅ 只要内容变了就变。代价是**每次 stat 都要把整个文件读一遍** |
 *
 * 取舍：KDBX 库通常是几百 KB ~ 几 MB（不是几十 GB），**读一遍是可接受的**；
 * 而"漏报冲突"的代价是**静默覆盖用户的改动**，与用户的硬要求直接冲突。
 * ⇒ 宁可慢一点。⚠️ 也因此 [stat] 与 [read] 在本地是同一个 IO 成本，
 *   调用方拿不定主意时优先 `read()` 再自己算（少一次重复读）。
 *
 * ## ⚠️ SAF 写的两个坑（方案 §7）
 *
 * 1. **不能假设可以就地随机写** ⇒ 一律 `"wt"`（truncate）整体覆盖；
 * 2. **写失败可能留下半截文件** ⇒ 平台侧没有 rename 可用（`DocumentsContract`
 *    的 rename 语义不跨 provider 可靠），所以本地路径的"不损坏"保障
 *    **依赖 `Kdbx.save` 在上层做的备份 + 往返自检**，而不是这里。
 *    这也是为什么 [write] 拿到的是**已经过自检的字节**。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.repository.kdbx

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import io.vaultix.data.kdbx.KdbxFileConflictException
import io.vaultix.data.kdbx.KdbxFileEntry
import io.vaultix.data.kdbx.KdbxFileSource
import io.vaultix.data.kdbx.KdbxFileStat
import io.vaultix.data.kdbx.KdbxFileWriteResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.security.MessageDigest

/**
 * 把 SAF `content://` URI 当作 KDBX 文件来源。
 *
 * @param resolver 走 `Context.contentResolver`；直接依赖它（而不是 `Context`）
 *   是为了单测能给一个 mock，不必起 Android 环境。
 * @param uri 用户选中的那个文件（持久化授权后长期有效）。
 */
class SafKdbxFileSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
) : KdbxFileSource {

    override suspend fun stat(): KdbxFileStat = withContext(Dispatchers.IO) {
        // ⚠️ 先确认可读：授权失效时应抛异常让上层报"请重新选文件"，
        //    而不是返回一个空 stat（那会被当成"文件是空的"）。
        val meta = queryMetadata() ?: throw FileNotFoundException(
            "无法访问该文件，授权可能已失效，请重新选择文件",
        )
        val bytes = readBytesOrThrow()
        KdbxFileStat(
            versionToken = sha256Hex(bytes),
            lastModified = meta.lastModified,
            sizeBytes = meta.size ?: bytes.size.toLong(),
            remoteId = uri.toString(),
            displayName = meta.displayName,
        )
    }

    override suspend fun read(): ByteArray = withContext(Dispatchers.IO) { readBytesOrThrow() }

    /**
     * 覆盖写入。
     *
     * @param expectedVersion 上一次 [stat]/[read] 的内容 SHA-256。
     *   ⚠️ 与网盘不同：本地**没有服务端能原子地替我们判定**，
     *   所以这里只能做"写前预检"（缩小窗口），**无法彻底消除 TOCTOU**。
     *   这是 SAF 的固有限制 —— 在本地单机场景下影响极小（同一时刻只有一个 App 写），
     *   但要如实写清楚，**不能假装有和 WebDAV 一样的保障**。
     */
    override suspend fun write(
        bytes: ByteArray,
        expectedVersion: String?,
        force: Boolean,
    ): KdbxFileWriteResult =
        withContext(Dispatchers.IO) {
            // ① 写前预检：本地只能做到这一步（见上面 KDoc 的说明）。
            //
            //   ⚠️ force = true 时**跳过预检** —— 用户已在冲突对话框里确认要用本地
            //      覆盖远端。保留预检的话，用户点了确认也永远写不进去
            //      （文件确实变了，条件必然不满足），只能反复点。
            if (!force && expectedVersion != null) {
                val current = runCatching { sha256Hex(readBytesOrThrow()) }.getOrNull()
                if (current != null && current != expectedVersion) {
                    throw KdbxFileConflictException(current, "文件已被其他程序修改，已取消写入")
                }
            }

            // ② 整体覆盖：`"wt"` = write + truncate。
            //    不加 "t" 的话，新内容比旧内容短时会留下尾巴 —— 那就是一个损坏的 KDBX。
            resolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(bytes)
                out.flush()
            } ?: throw FileNotFoundException("无法写入该文件，授权可能已失效，请重新选择文件")

            // ③ 写后读回校验：SAF 没有 rename，写坏没有本地退路。
            //    读回来比对是最直接的"确实写对了"证明；不一致就抛，让上层别记成功。
            val written = readBytesOrThrow()
            if (!written.contentEquals(bytes)) {
                throw IllegalStateException(
                    "写入后读回校验不一致（写入 ${bytes.size} 字节，读回 ${written.size} 字节）",
                )
            }

            KdbxFileWriteResult(
                versionToken = sha256Hex(written),
                lastModified = System.currentTimeMillis(),
                sizeBytes = written.size.toLong(),
                remoteId = uri.toString(),
            )
        }

    override suspend fun listChildren(): List<KdbxFileEntry> = withContext(Dispatchers.IO) {
        // 本实现绑定的就是"一个具体文件"，没有"所在目录"的概念
        // （SAF 的树授权是另一套 Uri，见 `SafKdbxDirectorySource` 的说明）。
        // 返回自己不是好主意（会让"选库"界面把当前库当成候选）。
        emptyList()
    }

    override suspend fun testConnection(): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            if (queryMetadata() == null) {
                throw FileNotFoundException("无法访问该文件，授权可能已失效，请重新选择文件")
            }
            Unit
        }
    }

    private fun readBytesOrThrow(): ByteArray =
        resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw FileNotFoundException("无法读取该文件，授权可能已失效，请重新选择文件")

    private fun queryMetadata(): SafFileMeta? = runCatching {
        resolver.query(uri, PROJECTION, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            SafFileMeta(
                displayName = nameIndex.takeIf { it >= 0 }?.let { cursor.getString(it) },
                size = sizeIndex.takeIf { it >= 0 && !cursor.isNull(it) }?.let { cursor.getLong(it) },
                lastModified = modifiedIndex.takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let { cursor.getLong(it) },
            )
        }
    }.getOrNull()

    private data class SafFileMeta(
        val displayName: String?,
        val size: Long?,
        val lastModified: Long?,
    )

    private companion object {
        val PROJECTION = arrayOf(
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        /**
         * 内容哈希 —— 本地路径的版本令牌（为什么不给 mtime 用，见文件头说明）。
         *
         * 用 SHA-256 而不是 `hashCode()`：后者是 32 位且碰撞容易构造，
         * 拿它当"内容有没有变"的判据会出现**假阴性**（不同内容算出同一个值 ⇒ 漏报冲突）。
         */
        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}

/**
 * 从一个 `content://` URI 构造本地来源。
 *
 * 存在的意义：调用点不必自己 `Uri.parse` + 取 `contentResolver`
 * （两处都容易写错，尤其是忘了 `applicationContext`）。
 *
 * ⚠️ 是**顶层函数**而不是 `KdbxFileSource.Companion.saf`：
 * `KdbxFileSource` 是 interface，没有 companion 可以扩展
 * （给它加 companion 会往纯逻辑模块里塞 Android 相关的东西）。
 */
fun safKdbxFileSource(context: Context, sourceUri: String): KdbxFileSource =
    SafKdbxFileSource(
        resolver = context.applicationContext.contentResolver,
        uri = Uri.parse(sourceUri),
    )
