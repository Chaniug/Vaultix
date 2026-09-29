/*
 * Vaultix — data:repository
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 */

package io.vaultix.data.repository.kdbx

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [KdbxFileCache] 的文件实现（纯 JVM，只依赖 `java.io`）。
 *
 * 布局（每个 key 两个文件，文件名 = `sha256(key)` 十六进制）：
 * ```
 * <root>/<hash>.kdbx    缓存的文件字节（密文）
 * <root>/<hash>.meta    侧车：第一行 = 字节数，第二行 = versionToken（可空）
 * ```
 *
 * ## 为什么侧车要带**字节数**（不是可有可无）
 *
 * 崩溃 / 被杀进程可能留下**半截文件**（本项目的 KDBX 侧格外常见：Honor 会在锁屏后
 * 杀进程）。没有尺寸校验的话，下次读到的就是一段截断字节，KDBX 会报
 * 「文件损坏 / 密码错」——用户完全无从判断，而真因只是缓存写了一半。
 * ⇒ 尺寸对不上就**当作没有缓存**（并删掉它），退回正常下载：慢一次，但不会说谎。
 *
 * ## 写入是「先写临时文件再改名」
 *
 * 改名在同一目录内是原子的（POSIX 语义，Android 同样成立）⇒ 读侧永远看不到半截文件。
 * 改名失败（跨卷等理论情形）退化为直接覆盖拷贝，并在 `finally` 里清掉临时文件。
 */
class FileKdbxFileCache(private val root: File) : KdbxFileCache {

    override suspend fun load(key: String): CachedKdbxFile? = withContext(Dispatchers.IO) {
        val data = dataFile(key)
        if (!data.isFile) return@withContext null
        val meta = readMeta(key) ?: return@withContext null.also { clear(data, key) }
        val bytes = runCatching { data.readBytes() }.getOrNull() ?: return@withContext null
        if (bytes.size.toLong() != meta.size) {
            // 半截文件 / 被外部改动 ⇒ 当作未命中，并清掉这份不可信的缓存。
            clear(data, key)
            return@withContext null
        }
        CachedKdbxFile(bytes, meta.token)
    }

    override suspend fun save(key: String, file: CachedKdbxFile): Unit = withContext(Dispatchers.IO) {
        root.mkdirs()
        val tmp = File(root, "${hash(key)}.tmp")
        runCatching {
            tmp.writeBytes(file.bytes)
            val target = dataFile(key)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
            }
            metaFile(key).writeText(
                buildString {
                    append(file.bytes.size)
                    append('\n')
                    file.versionToken?.let { append(it) }
                },
            )
        }.onFailure { runCatching { tmp.delete() } }
        Unit
    }

    override suspend fun remove(key: String): Unit = withContext(Dispatchers.IO) {
        clear(dataFile(key), key)
    }

    private fun clear(data: File, key: String) {
        runCatching { data.delete() }
        runCatching { metaFile(key).delete() }
    }

    private fun dataFile(key: String) = File(root, "${hash(key)}.kdbx")

    private fun metaFile(key: String) = File(root, "${hash(key)}.meta")

    private fun readMeta(key: String): Meta? {
        val text = runCatching { metaFile(key).readText() }.getOrNull() ?: return null
        val lines = text.split('\n')
        val size = lines.firstOrNull()?.trim()?.toLongOrNull() ?: return null
        // 第二行可能是空字符串（= 无令牌），也可能整行不存在 ⇒ 一律视作 null。
        val token = lines.getOrNull(1)?.takeIf { it.isNotEmpty() }
        return Meta(size, token)
    }

    private fun hash(key: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private class Meta(val size: Long, val token: String?)
}
