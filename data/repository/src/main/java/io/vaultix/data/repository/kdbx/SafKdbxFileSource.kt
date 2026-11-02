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
 * ⇒ 宁可慢一点。
 *
 * ## ★ 2026-11-02 追加：真的不必**每次**都读（#234）
 *
 * 上面那句"读一遍可接受"漏算了一件事：`stat()` 不是一次，而是**每一次同步 + 每一次解锁的
 * 后台校验**都要一次。用户只是打开看一眼，2 MiB 的库也要被完整读两遍。
 *
 * 现在的做法是**先证明，再省**：
 * - 记住上一次整份读算出的摘要，**连同那次读的时间区间**（`ReadWindow`）；
 * - 下次 `stat()` 只查元数据：若"大小没变 + 修改时间早于那段区间的开始"，
 *   就说明那段区间里文件没被动过 ⇒ 摘要**确实**属于现在这一版 ⇒ 直接复用；
 * - 否则（或本进程写过一次，见 `generation`）⇒ 老老实实整份读。
 *
 * ⚠️ **判据是"修改时间早于读区间"，不是"修改时间没变"**：后者要求 provider 的 mtime 语义
 * 完全可靠，而它不可靠（部分 provider 保留 mtime、部分我们的写入会让 mtime 落在写区间中间）。
 * 前者只要"改动会让 mtime 推进"这一个较弱的前提，而且还额外有 `generation` 兜住本进程的写。
 * ⚠️ 判据全部收在 `SafCachePolicy.kt` 的纯函数里（本模块单测不跑 Android 运行时）。
 *
 * ## ⚠️ SAF 写的两个坑（方案 §7）
 *
 * 1. **不能假设可以就地随机写** ⇒ 一律 `"wt"`（truncate）整体覆盖；
 * 2. **写失败可能留下半截文件** ⇒ 平台侧没有 rename 可用（`DocumentsContract`
 *    的 rename 语义不跨 provider 可靠）。
 *
 * ## ⚠️ 2026-10-02 更正：这里原先写错了"不损坏"的保障来源
 *
 * 本文件的 KDoc 曾经写着「本地路径的保障**依赖上层 `Kdbx.save` 做的备份 + 往返自检**，
 * 所以 [write] 拿到的是已经过自检的字节」。**这个前提在代码里不成立**：
 *
 * - `Kdbx.save(File)` 在全项目**零生产调用点**（是一条没人走的死路径）；
 * - 真正的主路径 `KdbxItemRepository.persist` 此前**直接 `source.write(bytes)`**，
 *   一步自检都没做 —— 字节出 `Kdbx.mutate` 就奔这个文件而来；
 * - `.kdbx.bak` 备份同理：SAF 拿不到兄弟文件的写权限，这条路径**本来就做不了备份**。
 *
 * ⇒ 这两句注释让"这条链是通的"在代码审查时**看起来成立**（`.ai/ISSUES.md` #147
 *   记的就是同一类病：假装兜底的占位实现比没有兜底更危险）。
 *
 * **修后的真实保障**（如实写在这里，别再夸大）：
 * - ✅ **往返自检** —— 由 `KdbxItemRepository.persist` 在写之前调用
 *   `Kdbx.verifyWrite`（解码当前会话比对，见 `KdbxRoundTrip.verifyEncoded`）；
 * - ✅ **写后读回比对** —— 本文件 [write] 的第 ③ 步，能挡住"写进去的字节和预期不同"；
 * - ❌ **`.bak` 备份** —— SAF 不支持，**本路径做不到**（与其假装，不如不承诺）；
 * - ❌ **原子 rename** —— 同上；`"wt"` 截断写 + 读回校验是能给出的最好保证。
 *
 * ⚠️ 因此 [write] **仍然假定拿到的是已经过自检的字节** —— 现在这句话才第一次为真，
 *   并且由调用方 `KdbxItemRepository.persist` 显式兑现（它在调用前先 `Kdbx.verifyWrite`）。
 *   谁要是以后新增一条绕过 `persist` 的写路径，这条约定会**再次失守且无人报错**。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.repository.kdbx

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import io.vaultix.common.logging.VaultixLog
import io.vaultix.data.kdbx.KdbxFileConflictException
import io.vaultix.data.kdbx.KdbxFileEntry
import io.vaultix.data.kdbx.KdbxFileSource
import io.vaultix.data.kdbx.KdbxFileStat
import io.vaultix.data.kdbx.KdbxFileWriteResult
import io.vaultix.data.kdbx.sha256Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

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

    /**
     * ★ 本实例**每一次成功写**都 `++`（#234）。
     *
     * 为什么要它：光靠 `(mtime, size)` 判"文件没变"有一个我们自己造成的漏洞 ——
     * 我们刚写完的那一份，provider 可能把 mtime 记成**写区间中间**的时刻，
     * 那样下一次 `stat()` 会看到"mtime 早于上次读区间" ⇒ 误判成没变。
     * 有了序号，写之后缓存记录必然失配（记录里的序号是写之前的）⇒ 必定重读一次。
     *
     * ⚠️ 它只在**同一个实例**内有效 ⇒ 本类必须是**单例**（装配侧
     * `KdbxCloudSyncCoordinator` 按 origin 统一持有，见那里的 `safSources`）。
     * 每次 `fileSourceFor` 新建一个的话，序号恒为 0，这道保护会**静默失效**。
     */
    @Volatile
    private var generation: Long = 0

    /** 上一次整份读留下的摘要与区间（见 [SafReadCache]）。只在内存里，退出即丢。 */
    @Volatile
    private var readCache: SafReadCache? = null

    /** 串行化 `stat()`：并发两次都判"要读"会白读两遍（结果无害，纯浪费）。 */
    private val statLock = Mutex()

    override suspend fun stat(): KdbxFileStat = withContext(Dispatchers.IO) {
        statLock.withLock { statLocked() }
    }

    private fun statLocked(): KdbxFileStat {
        // ⚠️ 先确认可读：授权失效时应抛异常让上层报"请重新选文件"，
        //    而不是返回一个空 stat（那会被当成"文件是空的"）。
        val meta = queryMetadata() ?: throw FileNotFoundException(
            "无法访问该文件，授权可能已失效，请重新选择文件",
        )
        val sourceId = uri.toString()
        val record = readCache
        // ① 记录本身还属于这个文件、且这段时间没人写过？
        if (recordStillValid(record, generation, sourceId)) {
            val observed = SafFileObservation(meta.lastModified, meta.size)
            // ② 文件本身没被动过？
            when (val verdict = judgeObserved(observed, record?.token, record?.window, record?.sizeBytes)) {
                is SafObservationVerdict.Reusable -> {
                    VaultixLog.d(TAG) { "stat 快速路径：元数据证明内容未变，复用摘要（不读整份）" }
                    return KdbxFileStat(
                        versionToken = verdict.token,
                        lastModified = meta.lastModified,
                        sizeBytes = verdict.sizeBytes,
                        remoteId = sourceId,
                        displayName = meta.displayName,
                    )
                }
                is SafObservationVerdict.MustRead -> {
                    VaultixLog.d(TAG) { "stat 需要整份读：${verdict.reason}" }
                }
            }
        }
        // ③ 证明不了 ⇒ 整份读（这是唯一会读字节的路径）。
        val startedAt = System.currentTimeMillis()
        val bytes = readBytesOrThrow()
        // 区间取"读完之后"：读的过程中若有改动，会被下一次判据判成可疑（走缓慢路径）。
        val finishedAt = System.currentTimeMillis()
        val token = sha256Hex(bytes)
        val readGeneration = generation
        readCache = SafReadCache(
            token = token,
            window = ReadWindow(startedAt, finishedAt),
            sizeBytes = bytes.size.toLong(),
            generation = readGeneration,
            sourceId = sourceId,
        )
        return KdbxFileStat(
            versionToken = token,
            lastModified = meta.lastModified,
            sizeBytes = meta.size ?: bytes.size.toLong(),
            remoteId = sourceId,
            displayName = meta.displayName,
        )
    }

    override suspend fun read(): ByteArray = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        val bytes = readBytesOrThrow()
        val finishedAt = System.currentTimeMillis()
        // ★ 记住"这份摘要是在哪段区间里读出来的" —— 快速路径的全部依据（#234）。
        readCache = SafReadCache(
            token = sha256Hex(bytes),
            window = ReadWindow(startedAt, finishedAt),
            sizeBytes = bytes.size.toLong(),
            generation = generation,
            sourceId = uri.toString(),
        )
        bytes
    }

    /**
     * 覆盖写入 / 只创建写入。
     *
     * @param expectedVersion 上一次 [stat]/[read] 的内容 SHA-256。
     *   ⚠️ 与网盘不同：本地**没有服务端能原子地替我们判定**，
     *   所以这里只能做"写前预检"（缩小窗口），**无法彻底消除 TOCTOU**。
     *   这是 SAF 的固有限制 —— 在本地单机场景下影响极小（同一时刻只有一个 App 写），
     *   但要如实写清楚，**不能假装有和 WebDAV 一样的保障**。
     * @param failIfExists 只创建。⚠️ 本地同样"只能预检"：SAF 没有 `If-None-Match`，
     *   所以做法是"先问文件在不在，在就拒写"。
     */
    override suspend fun write(
        bytes: ByteArray,
        expectedVersion: String?,
        force: Boolean,
        failIfExists: Boolean,
    ): KdbxFileWriteResult =
        withContext(Dispatchers.IO) {
            // 记录写入开始时刻：写成功后要用它当"可疑区间"的起点（#234）。
            val writeStartedAt = System.currentTimeMillis()

            // ⓪ 只创建：目标已存在 ⇒ 直接拒写，**一个字节都不碰**。
            //
            //   ⚠️ 这一步必须排在 ② 的 `openOutputStream` **之前** ——
            //      `"wt"` 一打开就已经把文件截断了，那时再检查等于已经毁了别人的库。
            //   ⚠️ 判据用 `readBytesOrThrow()` 而不是 `DocumentFile.exists()`：
            //      同一份实现里只留一种"文件在不在"的判据（读了有字节就是在，抛了就是不在）。
            if (failIfExists) {
                val existing = runCatching { readBytesOrThrow() }.getOrNull()
                if (existing != null) {
                    throw KdbxFileConflictException(
                        currentVersion = sha256Hex(existing),
                        message = "该位置已存在同名文件，已取消创建以免覆盖",
                    )
                }
            }

            // ① 写前预检：本地只能做到这一步（见上面 KDoc 的说明）。
            //
            //   ⚠️ force = true 时**跳过预检** —— 用户已在冲突对话框里确认要用本地
            //      覆盖远端。保留预检的话，用户点了确认也永远写不进去
            //      （文件确实变了，条件必然不满足），只能反复点。
            //   ⚠️ failIfExists 时也跳过：只创建场景没有"基线"这回事，
            //      硬拿一个旧基线去比，只会把"新建失败"误报成"文件被别人改了"。
            //   ⚠️ expectedVersion = null 时也跳过（2026-11-02，#234）：调用方明确表示
            //      **没有基线**，拿 null 去和当前内容比毫无意义；而"读一遍算当前令牌"再丢掉，
            //      只是把一次白读塞进每一次保存里。**真正的冲突检测只能靠非空基线** ——
            //      这条路径上它本来就不存在，所以这里跳过不降低任何保障（见下方 KDoc）。
            if (!force && !failIfExists && expectedVersion != null) {
                val current = runCatching { currentTokenOrNull() }.getOrNull()
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

            // ★ 写成功 ⇒ 序号前进、记录换新（#234）。
            //
            //   ⚠️ 顺序不能反：先 `++` 再登记，登记进去的才是"写之后"的序号；
            //      反过来的话记录里的序号立刻过期，下一次 stat 必然重读（结果无害但白读）。
            //   ⚠️ 这里的区间**故意**记成"写入窗口"而不是"读回窗口"：读回那次是在
            //      写完之后、文件已经稳定时发生的，若只记那一小段，provider 把 mtime
            //      记在写入过程中的情况就会被误判成"没变"。记整个写入窗口更保守。
            val token = sha256Hex(written)
            generation += 1
            readCache = SafReadCache(
                token = token,
                window = ReadWindow(writeStartedAt, System.currentTimeMillis()),
                sizeBytes = written.size.toLong(),
                generation = generation,
                sourceId = uri.toString(),
            )
            KdbxFileWriteResult(
                versionToken = token,
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

    /**
     * 连通性自检。
     *
     * ⚠️ 只查元数据，**不读字节**（2026-11-02）：这里要回答的是"这个文件还打不打得开"，
     * 不是"内容是什么"。而 SAF 的"可读性"与"内容"是两件事 —— 授权失效在 `query` 这步
     * 就会抛。整份读一遍在 50 MiB 的库上足以让设置页卡到超时（被误报成"连不上"）。
     */
    override suspend fun testConnection(): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            if (queryMetadata() == null) {
                throw FileNotFoundException("无法访问该文件，授权可能已失效，请重新选择文件")
            }
        }
    }

    /**
     * 当前内容的令牌 —— **能复用就复用，不能才读**（[stat] 的同款判据，#234）。
     *
     * 写前预检只需要"现在是什么"，不需要 `KdbxFileStat` 的其余字段；
     * 但判据**必须**与 [stat] 是同一份，否则预检会比 `stat` 更宽松，那就是静默覆盖。
     */
    private fun currentTokenOrNull(): String? {
        val meta = queryMetadata() ?: return null
        val record = readCache
        if (recordStillValid(record, generation, uri.toString())) {
            val observed = SafFileObservation(meta.lastModified, meta.size)
            val verdict = judgeObserved(observed, record?.token, record?.window, record?.sizeBytes)
            if (verdict is SafObservationVerdict.Reusable) return verdict.token
        }
        return sha256Hex(readBytesOrThrow())
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
        const val TAG = "VaultixKdbxSaf"

        val PROJECTION = arrayOf(
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
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
