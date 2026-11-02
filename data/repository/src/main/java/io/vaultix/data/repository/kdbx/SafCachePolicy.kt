/*
 * Vaultix — data:repository
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * **本机文件的"变没变"快速判据** —— `SafKdbxFileSource.stat()` 为什么不必每次整份读。
 *
 * ## 问题
 *
 * SAF 来源的版本令牌是**内容 SHA-256**（理由见 `SafKdbxFileSource` 文件头：`lastModified`
 * 只到秒，同秒内两次修改看不出差异）。最直白的实现是"每次 `stat()` 都把整份文件读一遍算哈希"，
 * 而这个 `stat()` 在**每一次**同步、每一次解锁的后台校验里都会被调 ⇒
 * 一个 2 MiB 的本地库，用户只是打开看一眼，也要被完整读好几遍。
 *
 * ## 为什么不能简单地"记住上一次的 stat 结果"
 *
 * 因为 `write(expectedVersion=…)` 的预检是**正确性判据**：拿一个"记得的"值当令牌，
 * 文件在别的 App 里被改过也照样通过 ⇒ **静默覆盖别人的改动**。
 * 所以"不读"必须先**被证明**成立，而不是默认成立。
 *
 * ## 证据分两级（[judgeObserved]）
 *
 * | 证据 | 来源 | 能不能单独判"一致" |
 * |---|---|---|
 * | 活跃区间 `[startedAt, finishedAt]` + 摘要 | **本进程自己完成的**一次读 | ✅ 可以（见下） |
 * | `(修改时间, 大小)` | 查询得到的元数据 | ❌ **永远不行**，只能判"不一致" |
 *
 * **活跃区间**：摘要不是在某一瞬间算出来的，而是在一段**区间**里读出来的。
 * 只要文件的当前修改时间落在这段区间内，就说明"读的过程中（或之后）它被动过" ⇒
 * 那一刻算出的摘要不可能是它现在的状态 ⇒ 缓存作废、重新读。
 * 反之，修改时间**早于**区间开始 ⇒ 那段区间里文件没被动过 ⇒ 摘要**确实**属于现在这一版。
 *
 * ⚠️ 这与 OneDrive 读回校验（#232）的取舍**正好相反**，而且两边都对：
 * - 那边比的是**服务端时钟**与本机时钟 ⇒ 只能当诊断，不能当结论；
 * - 这边比的是**同一个时钟源**（本机文件系统）的两次读数 ⇒ 相对先后可信。
 *   ⇒ 结论不是"时间戳不能用"，而是"**谁的时钟**决定它能不能用"。
 *
 * ⚠️ 反向仍然不成立：真实改动**同样**会让区间判据失效（被读之后文件被改，修改时间随之推进），
 * 所以"文件被改过却判成没变"这条路是堵死的 —— **前提是修改时间真的会推进**。
 * 有些 provider 会保留 mtime（云盘占位文件、从备份恢复的文件），且**我们自己的写入**
 * 也不保证 provider 会把 mtime 推到"写完之后"（它可能落在写区间正中间）
 * ⇒ 在它之外再加一道**对缓存自身的怀疑**（[CacheLine] 的库变更序号 / 来源标识）。
 *
 * ## 另外两根支柱（缺一不可）
 *
 * 1. **库变更序号**：`SafKdbxFileSource` 是单例（由 `KdbxCloudSyncCoordinator` 统一持有），
 *    本进程**每一次成功写**都 `++`。序号与缓存记录的对不上 ⇒ 中间有人写过 ⇒ 重读。
 *    这是"本进程内的写入"的**精确**证据（比 mtime 可靠）。
 * 2. **写侧的内容指纹**（`Kdbx.kt` 的 `baselineAligned`，ISSUES #185）：即使令牌判错了，
 *    写回时内容来源对不上仍然会被拒写。它才是最后一道防线 —— 本文件只负责**少读几次**，
 *    **不负责**正确性兜底。
 * ---------------------------------------------------------------------------
 */

package io.vaultix.data.repository.kdbx

/**
 * 一段"摘要是在什么时候读出来的"区间，单位毫秒（`System.currentTimeMillis()`）。
 *
 * ⚠️ `finishedAt` 取在**读完之后**、而不是读到第一个字节之前：
 * 宁可把区间判得更严（多读一次），也不能让区间短到漏掉"读的过程中被改"。
 */
data class ReadWindow(val startedAt: Long, val finishedAt: Long) {

    /** 某个修改时间是否落在这段"可疑"区间里（含端点 —— 边界上宁可当作可疑）。 */
    fun covers(modifiedAtMillis: Long?): Boolean =
        modifiedAtMillis != null && modifiedAtMillis >= startedAt && modifiedAtMillis <= finishedAt
}

/** 一次元数据查询的结果（**不含文件内容**）。 */
data class SafFileObservation(
    /** 修改时间（毫秒）。**null = 该 provider 不给** —— 此时一切快速路径作废。 */
    val modifiedAtMillis: Long?,
    /** 大小（字节）。null = 未知（同样让快速路径作废）。 */
    val sizeBytes: Long?,
)

/**
 * 一次 `stat()` 的结论：**要么**能复用旧摘要，**要么**必须整份读一遍。
 *
 * 刻意做成"二选一"而不是返回一个布尔：布尔会退化成 `true` 的默认值，
 * 而这里 `true` 的代价是**用旧摘要当条件写基线**。
 */
sealed interface SafObservationVerdict {

    /** 能证明：这份摘要就是文件**现在**这一版的内容指纹。 */
    data class Reusable(val token: String, val sizeBytes: Long) : SafObservationVerdict

    /**
     * 证明不了 ⇒ 必须整份读。
     *
     * @param reason 只用于日志。⚠️ 不要拿它做分支 —— 所有"证明不了"的结局**动作相同**。
     */
    data class MustRead(val reason: String) : SafObservationVerdict
}

/**
 * 判定"这次能不能不读"。
 *
 * 唯一给 [SafObservationVerdict.Reusable] 的组合是：**有旧摘要、有区间、大小与那次读数一致、
 * 且修改时间早于区间开始**。其余一律 [SafObservationVerdict.MustRead]。
 *
 * @param cachedToken 上一次整份读算出的摘要；`null` = 从未读过。
 * @param cachedWindow 那次读的区间；`null` = 那次读没留下区间（写后读回等）⇒ 不可复用。
 * @param cachedSize 那次读到的字节数。
 */
fun judgeObserved(
    observed: SafFileObservation,
    cachedToken: String?,
    cachedWindow: ReadWindow?,
    cachedSize: Long?,
): SafObservationVerdict {
    if (cachedToken.isNullOrBlank()) return SafObservationVerdict.MustRead("从未完整读过")
    val window = cachedWindow ?: return SafObservationVerdict.MustRead("上一次读没有留下区间")
    val size = observed.sizeBytes ?: return SafObservationVerdict.MustRead("provider 未提供大小")
    val modified = observed.modifiedAtMillis
        ?: return SafObservationVerdict.MustRead("provider 未提供修改时间")

    // 大小先比：它**不依赖** mtime 的可靠性，是一道独立证据。
    // （大小变了 mtime 理应也变，但"理应"在 provider 之间并不一致。）
    if (cachedSize != null && size != cachedSize) {
        return SafObservationVerdict.MustRead("大小从 $cachedSize 变成 $size")
    }
    // 修改时间必须**早于**读区间开始才算"那段时间没被动过"。
    // ⚠️ 判据是 `>=`，不是 `covers()`：区间**之后**的改动（modified > finishedAt）
    //   同样让摘要过期。曾经写成 `covers()` ⇒ 「读完之后被改」被放行，
    //   而那不是理论漏洞：`stat()` 之后到 `write()` 之间，别的 App 完全可能改一笔。
    if (modified >= window.startedAt) {
        return SafObservationVerdict.MustRead("修改时间($modified)不早于上次读区间起点(${window.startedAt})")
    }
    return SafObservationVerdict.Reusable(cachedToken, size)
}

/**
 * SAF 的一份**读缓存记录**（只有摘要与元信息，**不留字节**）。
 *
 * ## 为什么本地只记摘要、不学远端那样存整份字节
 *
 * 远端的令牌要一次网络往返，缓存字节能省下整次下载；本地的令牌**就是内容哈希**，
 * 缓存字节既换不来一次 IO，又平白多出一份"哪一份才算数"的问题。
 * 所以 `KdbxFileCache` 的 KDoc 写着「只缓存远端来源」，这里遵守它。
 *
 * @property generation 读到这份摘要时，来源实例的库变更序号（见 `SafKdbxFileSource`）。
 *   写入后序号会 `++` ⇒ 记录自然失效，**不需要**去猜 mtime 推没推进。
 * @property sourceId 这份摘要属于哪个文件（URI 字符串）。不同 ⇒ 不能复用。
 */
data class SafReadCache(
    val token: String,
    val window: ReadWindow,
    val sizeBytes: Long,
    val generation: Long,
    val sourceId: String,
)

/**
 * 在 [SafReadCache] 之上再判一次"它是不是还属于这个文件、这段时间有没有人写过"。
 *
 * 与 [judgeObserved] 分开的理由：那个函数只关心"文件本身变没变"（纯文件系统证据），
 * 这个函数关心"我们这份记录还可不可信"（进程内证据）。混在一起会让两个判据互相掩护 ——
 * 一旦某天有人只改了一处，另一处的用例看不出来。
 *
 * @return `true` = 记录仍然可信，可以交给 [judgeObserved]；`false` = 直接重读。
 */
fun recordStillValid(record: SafReadCache?, currentGeneration: Long, sourceId: String): Boolean {
    if (record == null) return false
    if (record.sourceId != sourceId) return false
    return record.generation == currentGeneration
}
