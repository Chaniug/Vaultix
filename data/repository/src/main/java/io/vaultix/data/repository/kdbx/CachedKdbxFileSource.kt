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

import io.vaultix.common.logging.VaultixLog
import io.vaultix.data.kdbx.KdbxFileEntry
import io.vaultix.data.kdbx.KdbxFileSource
import io.vaultix.data.kdbx.KdbxFileStat
import io.vaultix.data.kdbx.KdbxFileWriteResult
import kotlin.coroutines.cancellation.CancellationException

/**
 * 给**远端** [KdbxFileSource] 加一层本地缓存（多库锁模型定稿 **批次 B1**，2026-09-30）。
 *
 * ## 为什么（实测账）
 *
 * KDBX 解锁 3.58 s 里 ≈**3.4 s 是「读文件」**（`Kdbx.unlock` 先 `source.read()`
 * 再 KDF+解析；库在 OneDrive、设备无本地副本 ⇒ 每次解锁重下整份）。本层把这件事
 * 压成**一次元数据往返**（`stat()`，不下载内容）。
 *
 * ## 新鲜度判据：`versionToken`
 *
 * | 局面 | 行为 |
 * |---|---|
 * | 有缓存 且 `stat().versionToken` == 缓存令牌 | **命中，不下载** |
 * | 令牌变了 / 没有缓存 / 来源不给令牌（`null`） | 老实 `delegate.read()` 并更新缓存 |
 * | `stat()` 失败（离线 / 账号失效 / 服务端抽风） | **有缓存就回退缓存**（顺带获得离线解锁）；没有则**如实抛** |
 *
 * ⚠️ **`null` 令牌必须走「下载」这一支**：令牌是唯一能判定"缓存还是不是最新"的依据，
 * 拿不到它却声称命中，就会把一个**可能过期的库**静默交给用户（进而覆盖远端）。
 * 宁可慢一次，不可说谎一次 —— 与本项目「空有三态」是同一条纪律。
 *
 * ⚠️ **回退缓存只影响"能不能解锁"，不影响"能不能安全写回"**：推送侧仍走条件写
 * （`expectedVersion`），远端真的变了会抛 `KdbxFileConflictException` 让用户决定。
 *
 * ⚠️ **只应该包远端来源**（`content://` 本地的令牌是内容 SHA-256，算它就要读整份文件，
 * 缓存反而多做一次读）—— 挂载点见 `KdbxCloudSyncCoordinator.fileSourceFor`。
 */
internal class CachedKdbxFileSource(
    private val delegate: KdbxFileSource,
    private val cache: KdbxFileCache,
    private val cacheKey: String,
) : KdbxFileSource {

    /** 元数据请求照旧直连（它本来就不下载内容）；也保证 `write` 前拿到的令牌是真的。 */
    override suspend fun stat(): KdbxFileStat = delegate.stat()

    // 来源可能是网络 / HTTP / IO / 解析，异常族无法穷举；这里要的是"**任何**读不到"
    // 都退化为读缓存，而不是分类处理 ⇒ 与 `Kdbx.unlock` 同款压制（那里也这么写）。
    @Suppress("TooGenericExceptionCaught")
    override suspend fun read(): ByteArray {
        val cached = runCatching { cache.load(cacheKey) }.getOrNull()

        val remote = try {
            delegate.stat()
        } catch (cancelled: CancellationException) {
            // 取消不是"读不到"：上层要能把页面关闭与网络失败分开（同 `Kdbx.unlock` 的取向）。
            throw cancelled
        } catch (error: Exception) {
            return cached?.bytes?.also {
                VaultixLog.d(TAG) {
                    "读文件 → 元数据不可用（${error.message}），回退本地缓存（${it.size} 字节）"
                }
            } ?: throw error
        }

        val token = remote.versionToken
        val hit = cached != null && token != null && token == cached.versionToken
        // ★ 2026-09-30 加：**缓存没命中时要能说出"为什么"**。
        //   背景：真机实测两次解锁都是 `read≈4–5s（下载 30189B）`，而缓存文件的 mtime
        //   停在更早的时刻 ⇒ 命中判定从没通过，但**代码里看不到任何痕迹**（save 的失败
        //   被 runCatching 吞了）。三类原因是完全不同的结论，必须分开：
        //   - 没有缓存（load 失败 / 被清）⇒ 缓存机制没生效；
        //   - token 为 null（来源不给令牌）⇒ 设计上就不该命中；
        //   - token 变了（远端确实变了）⇒ **正常行为**（条件回源），别去"修"它。
        //   ⚠️ 只记**长度与短哈希**，不记令牌原文（VaultixLog 铁律：不记 token）。
        VaultixLog.d(TAG) {
            buildString {
                append("缓存判定：cached=").append(cached != null)
                cached?.let {
                    append("(").append(it.bytes.size).append("B,token=")
                    append(it.versionToken.tag()).append(")")
                }
                append(" 远端token=").append(token.tag())
                append(if (hit) " ⇒ 命中，不下载" else " ⇒ 未命中，下载")
            }
        }
        if (hit && cached != null) {
            return cached.bytes
        }

        val bytes = delegate.read()
        // 缓存写失败不能影响解锁本身（缓存是**优化**，不是正确性前提）。
        // ⚠️ 但**失败必须留痕**：静默吞掉会让"缓存永远不生效"这类问题查不出来（本次即如此）。
        runCatching { cache.save(cacheKey, CachedKdbxFile(bytes, token)) }
            .onSuccess {
                VaultixLog.d(TAG) {
                    "已写缓存（${bytes.size}B, token=${token.tag()}）"
                }
            }
            .onFailure { error ->
                VaultixLog.w(TAG) { "写缓存失败（不影响本次解锁）：${error.message}" }
            }
        return bytes
    }

    /**
     * 令牌的诊断标记：**长度 + 短哈希**（够判定"两次是不是同一个"，又不落原文）。
     * `null` 单独标记 —— 它代表"来源不给令牌"，是三类未命中原因里最需要区分的一类。
     */
    private fun String?.tag(): String = when {
        this == null -> "null"
        isEmpty() -> "空串"
        else -> "#${"%08x".format(hashCode())}(len=$length)"
    }

    override suspend fun write(
        bytes: ByteArray,
        expectedVersion: String?,
        force: Boolean,
    ): KdbxFileWriteResult {
        // 冲突时 `delegate.write` 抛 `KdbxFileConflictException` ⇒ 根本走不到下面，
        // 缓存不会被污染成"远端其实是旧的"。
        val result = delegate.write(bytes, expectedVersion, force)
        runCatching { cache.save(cacheKey, CachedKdbxFile(bytes, result.versionToken)) }
        return result
    }

    override suspend fun listChildren(): List<KdbxFileEntry> = delegate.listChildren()

    override suspend fun testConnection(): Result<Unit> = delegate.testConnection()

    private companion object {
        const val TAG = "VaultixKdbxCache"
    }
}
