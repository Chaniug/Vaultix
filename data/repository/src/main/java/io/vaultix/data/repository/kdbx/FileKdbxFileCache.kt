/*
 * Vaultix — data:repository
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 */

package io.vaultix.data.repository.kdbx

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [KdbxFileCache] 的文件实现（纯 JVM，只依赖 `java.io`）。
 *
 * ## 布局：**一个 key 一个文件**，令牌写在文件头
 *
 * ```
 * <root>/<sha256(key)>.kdbx
 *   [int  magic     ]  固定 [MAGIC]（"VKC1"）
 *   [int  tagLen    ]  versionToken 的 UTF-8 字节数；-1 = 无令牌
 *   [int  payloadLen]  KDBX 文件字节数
 *   [byte ...       ]  versionToken 字节（tagLen >= 0 时）
 *   [byte ...       ]  KDBX 文件字节（密文）
 * ```
 *
 * ⚠️ **[payloadLen] 不是冗余**：单文件格式里"剩余字节就是内容"这句话让**尾部截断
 * 无法被发现** —— 砍掉 3 个字节后照样解析出一段（更短的）payload，交给 KDBX 就是
 * 「文件损坏 / 密码错」。写一个显式长度并与实际剩余字节**严格相等**地校验，
 * 才保住了旧实现靠侧车尺寸做到的那道防线。这条是 2026-11-04 的用例
 * `文件被截断_当作未命中并清掉` 当场抓出来的（第一版没有它，测试直接报红）。
 *
 * ## ★ 为什么改成单文件（2026-11-04，#246）
 *
 * 旧实现是**两个文件**：`<hash>.kdbx` 放内容、`<hash>.meta` 放「字节数 + 令牌」。
 * 写入顺序是 `rename(数据) → writeText(meta)` —— 中间那段窗口里，磁盘上同时存在
 * **新的数据**与**旧的 meta**。一旦此时进程被杀（Honor 锁屏后杀进程是常态，本项目
 * 已经为它写过不止一条注释），下一次 [load] 会读到「尺寸不匹配」：
 *
 * | 窗口内发生了什么 | 旧实现的判定 | 真实情况 |
 * |---|---|---|
 * | 数据已换新、meta 还是旧的 | 「半截文件」⇒ **删掉** | **数据是完好的新版本** |
 * | 数据已换新、meta 还没写 | `readMeta` 返回 null ⇒ **删掉** | 同上 |
 *
 * ⇒ 用户什么都没做错，缓存却没了；下一次解锁老老实实 `stat` + **下载整库**。
 * 表现就是「锁屏回来特别慢」，而且**时好时坏**（取决于有没有被杀在那个窗口里）。
 *
 * 令牌与内容挤进**同一个文件**之后，写一次 `rename` 就是原子的：要么看到旧的完整
 * 版本，要么看到新的完整版本，不存在「新的内容配旧的元数据」这种混合态。
 *
 * ## 截断仍然要防（单文件也有半截的可能）
 *
 * 改名在同一目录内是原子的（POSIX 语义，Android 同样成立），所以正常路径不会产生
 * 半截文件。但磁盘损坏、外部写入、`renameTo` 退化成 `copyTo` 之后被杀，仍可能留下
 * 截断字节 —— 那时 [load] 靠「header 读不出 / 声明长度超过剩余字节」判出来，
 * 当作未命中并删掉。**宁慢一次，不说谎一次。**
 *
 * ## 旧格式怎么办
 *
 * 旧的 `.kdbx` 文件开头是 KDBX 密文的签名，不会是 [MAGIC] ⇒ 自然被判为未命中并删一次。
 * 缓存是**优化**，丢一次只等于多下一次库，不需要迁移逻辑。
 */
class FileKdbxFileCache(private val root: File) : KdbxFileCache {

    override suspend fun load(key: String): CachedKdbxFile? = withContext(Dispatchers.IO) {
        val file = dataFile(key)
        if (!file.isFile) return@withContext null
        val raw = runCatching { file.readBytes() }.getOrNull() ?: return@withContext null
        val parsed = parse(raw)
        if (parsed == null) {
            // 无法解释这份文件（旧格式 / 截断 / 被外部改动）⇒ 清掉，别每次白读一遍。
            clear(file)
            return@withContext null
        }
        parsed
    }

    override suspend fun save(key: String, file: CachedKdbxFile): Unit = withContext(Dispatchers.IO) {
        root.mkdirs()
        val tmp = File(root, "${hash(key)}.tmp")
        runCatching {
            // 组装成**一整块**字节再落盘：rename 之前磁盘上看不到任何中间态。
            tmp.writeBytes(encode(file))
            val target = dataFile(key)
            if (!tmp.renameTo(target)) {
                // 跨卷等理论情形退化为拷贝（原子性由"tmp 仍在 + 下次覆盖"兜底）。
                tmp.copyTo(target, overwrite = true)
                runCatching { tmp.delete() }
            }
        }.onFailure { runCatching { tmp.delete() } }
        Unit
    }

    override suspend fun remove(key: String): Unit = withContext(Dispatchers.IO) {
        clear(dataFile(key))
    }

    /** 组装落盘字节（见类 KDoc 的线格式）。 */
    private fun encode(file: CachedKdbxFile): ByteArray {
        val tagBytes = file.versionToken?.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream(file.bytes.size + HEADER_BYTES + (tagBytes?.size ?: 0))
        DataOutputStream(out).use { stream ->
            stream.writeInt(MAGIC)
            stream.writeInt(tagBytes?.size ?: NO_TOKEN)
            stream.writeInt(file.bytes.size)
            tagBytes?.let { stream.write(it) }
            stream.write(file.bytes)
        }
        return out.toByteArray()
    }

    /**
     * 解析落盘字节。
     *
     * @return null = 这份文件不可信（旧格式 / 截断 / 被改过），调用方应删掉它。
     */
    private fun parse(raw: ByteArray): CachedKdbxFile? {
        if (raw.size < HEADER_BYTES) return null
        val buffer = ByteBuffer.wrap(raw)
        if (buffer.int != MAGIC) return null
        val tagLength = buffer.int
        val payloadLength = buffer.int
        // 令牌段长度非法（比剩余字节还多 / 负数且不是哨兵）⇒ 截断或损坏。
        if (tagLength < NO_TOKEN || tagLength > buffer.remaining()) return null
        val versionToken = if (tagLength == NO_TOKEN) {
            null
        } else {
            val bytes = ByteArray(tagLength)
            buffer.get(bytes)
            // 空串令牌等于"来源给了个空值"：与 null 语义一致（上层不区分）。
            String(bytes, Charsets.UTF_8).takeIf { it.isNotEmpty() }
        }
        // ★ 声明长度必须**恰好等于**剩余字节：多一个字节说明尾部被砍/被追加，
        //   此时宁可当作未命中（多下一次库），也不要把一段不可信的字节交给 KDBX。
        if (payloadLength <= 0 || payloadLength != buffer.remaining()) return null
        val payload = ByteArray(payloadLength)
        buffer.get(payload)
        return CachedKdbxFile(payload, versionToken)
    }

    private fun clear(file: File) {
        runCatching { file.delete() }
    }

    private fun dataFile(key: String) = File(root, "${hash(key)}.kdbx")

    private fun hash(key: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        /** "VKC1"：缓存格式标识。KDBX 密文的头四字节是签名，不会撞上这个值。 */
        const val MAGIC = 0x564B4331

        /** 无令牌的哨兵（用 -1 而不是 0：0 长度令牌与"没有令牌"语义不同）。 */
        const val NO_TOKEN = -1

        /** magic + tagLen + payloadLen 三个 int。 */
        const val HEADER_BYTES = Int.SIZE_BYTES * 3
    }
}
