/*
 * Vaultix — app / OneDrive
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * **分片上传的"从哪继续"判定** —— 断点续传的策略层（`.ai/ISSUES.md` #236）。
 *
 * ## 我们原先怎么做，别的项目怎么做
 *
 * | 实现 | 失败后 |
 * |---|---|
 * | 我们（#236 之前） | 同一片重试 3 次；仍失败就**整次上传作废**，下次从头再来 |
 * | KeePassDX / KeePass2Android | 同款（简单 PUT / 小分片，失败即整体失败） |
 * | rclone / OneDrive 官方 SDK | 读 `nextExpectedRanges` **从断点继续** |
 *
 * 差别在**代价的量级**：解密的库常在 2–80 MiB，蜂窝网下传一次要几十秒到几分钟。
 * 在 90% 处断一次就白传全部，是用户能直接感知的"同步好慢 / 老是失败"。
 *
 * ## 关键：`nextExpectedRanges` 说的是「**从哪开始没收到**」
 *
 * Graph 在每次分片响应里回 `nextExpectedRanges: ["327680-"]`。它不是"进度条"，
 * 而是**服务端的缺口**，而且**不保证只增**：同一片被重发、或服务端合并了两片时，
 * 它可能**回退**。⇒ 拿它直接当"已上传多少"会写出**错位的文件**（后一片盖掉前一片），
 * 而 KDBX 错一个字节就整库打不开 —— 比"上传失败"糟得多。
 *
 * ⇒ 本文件的结论只有三种，且**永远不允许"跳跃"**：
 * - [Continue]：起点 **≤** 我们本地已确认写完的位置（可以原地重发或继续）；
 * - [Restart]：服务端说从 0 开始 / 给的区间非法（越界、倒序、解析不了）；
 * - [GiveUp]：连续没有进展次数超限 ⇒ 交给上层整次重来（只在**这一种**情况下放弃）。
 * ---------------------------------------------------------------------------
 */

package io.vaultix.vaultix.remote.onedrive

/**
 * 把 Graph 回的 `nextExpectedRanges` 判成 [ChunkResumeVerdict]。
 *
 * ## 规则（每条都对应一种真实响应）
 *
 * | 响应 | 判定 |
 * |---|---|
 * | `null` / 空列表 | **Continue（原地）** —— 有的代理会吞掉这个字段，不能当成"完成" |
 * | `[""]` / `["  "]` | [ChunkResumeVerdict.Restart] —— 服务端**明确**回了个空值，重发也解析不了 |
 * | `"0-"` 且本地已确认 > 0 | [ChunkResumeVerdict.Restart] —— 服务端把已确认的也算没了 ⇒ 会话被清 |
 * | `"0-"` 且本地确认 == 0 | **Continue(0)** —— 服务端一个字节没收到，而我们本来就从 0 发 |
 * | `"327680-"` | Continue(327680)，**超出**当前确认位 ⇒ **夹回**确认位 |
 * | `"0-655360"`（闭合区间） | 取左端作为起点；仍按上面的规则夹回 |
 * | `"abc"` / `"-5-"` / 负数 | Restart（区间非法，宁可重来也不猜） |
 *
 * ⚠️ **"夹回"不是保守，是必须**：服务端说"从 327680 开始"，而我们只确认传到 163840、
 * 且中间那一片恰好失败 ⇒ 若真从 327680 开始，文件中间会**永久缺 163840 字节**。
 * 那种库解不开，而错误信息只会说"密码错误或文件损坏"。
 *
 * @param ranges Graph 响应里的 `nextExpectedRanges`。
 * @param confirmedOffset 本地**已经确认写完**的位置（一片 202 且落在界内才能推进）。
 * @param stallCount 目前为止"连续没有进展"的次数（第一次传 0）。
 * @param maxStalls 允许的连续无进展次数。
 */
fun judgeResume(
    ranges: List<String>?,
    confirmedOffset: Long,
    stallCount: Int,
    maxStalls: Int = MAX_STALLS,
): ChunkResumeVerdict {
    // ★ "没给"与"给了个空的"是两件事：
    //   - `null` / 空列表 ⇒ 有的代理会整个吞掉这个字段 ⇒ 按"不知道"处理（原地重发）；
    //   - `[""]` / `["  "]` ⇒ 服务端**明确**回了一个空值 ⇒ 这是解析不了的响应，
    //     原地重发只会无限重试同一条坏路径 ⇒ 判 Restart。
    if (ranges == null) {
        return noProgressVerdict("服务端未返回 nextExpectedRanges", confirmedOffset, stallCount, maxStalls)
    }
    if (ranges.isEmpty()) {
        return noProgressVerdict("nextExpectedRanges 为空列表", confirmedOffset, stallCount, maxStalls)
    }
    // 取**第一个**区间：Graph 正常只回一个；多段是"有洞"，我们无法安全地只补一段。
    val first = ranges.firstOrNull()?.trim().orEmpty()
    val start = parseRangeStart(first)
        ?: return ChunkResumeVerdict.Restart("nextExpectedRanges 无法解析：$first")
    // ★ `0-` 有两种完全不同的含义，**取决于我们本地确认到哪了**（2026-11-03 实测踩到）：
    //
    // | confirmedOffset | `0-` 的含义 | 正确处置 |
    // |---|---|---|
    // | 0 | 服务端一个字节都没收 —— 而我们本来就要从 0 发 | Continue（原地重发） |
    // | > 0 | 我们确认传过，服务端却说全没了 ⇒ 会话被清 | Restart |
    //
    // 曾经一刀切成 Restart，于是**第一片就失败的库**永远传不上去：每次重发都被判
    // "会话失效"，而会话其实是好的、只是这一片没上去。判据要看**两边的差**，
    // 不能只看服务端那一个数字。
    if (start <= 0) {
        if (confirmedOffset <= 0) {
            return ChunkResumeVerdict.Continue(
                offset = 0L,
                progressed = false,
                reason = "服务端从 0 开始，本地也未确认任何字节（原地重发）",
            )
        }
        return ChunkResumeVerdict.Restart(
            "服务端要求从头开始（$first），但本地已确认 $confirmedOffset 字节 ⇒ 会话可能被清",
        )
    }
    if (start <= confirmedOffset) {
        // 服务端缺口在我们已确认的位置之内（含"原地"）⇒ 原地重发这一片。
        // started 严格小于确认位时，说明服务端把我们**已确认**的那片又算成缺失了 ——
        // 那是"片上被丢弃"的信号，照样从缺口处重发即可（幂等覆盖）。
        val clamped = minOf(start, confirmedOffset)
        val note = if (start > confirmedOffset) "（服务端位置 $start 超前，已夹回 $clamped）" else "（缺口在 $start）"
        return ChunkResumeVerdict.Continue(offset = clamped, progressed = false, reason = note)
    }
    // ★ 服务端比我们"超前"：说明它其实已经收了这一片，但我们没拿到 2xx（如网络断开）。
    //   这一片按"已成功"记，**但不能跳过它** —— 起点夹回确认位，让调用方再发一次同样的字节
    //   （Graph 对重复区间是幂等的）。真正的进度以下一次成功的响应为准。
    return noProgressVerdict(
        "服务端位置 $start 超前于本地确认位 $confirmedOffset，夹回重发",
        confirmedOffset,
        stallCount,
        maxStalls,
    )
}

/** 一次分片**成功**（2xx）后的推进判定：响应里的区间是否与"这一片已被完整接收"一致。 */
fun confirmedAfterAccepted(
    ranges: List<String>?,
    chunkStart: Long,
    chunkEndExclusive: Long,
): Long {
    val raw = ranges?.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.orEmpty()
    if (raw.isEmpty()) return chunkEndExclusive
    val start = parseRangeStart(raw.first()) ?: return chunkEndExclusive
    // 2xx 但服务端说缺口就在这一片的起点 ⇒ 它其实没收下这片。
    // ⚠️ 不能返回 chunkStart 当作"成功"：那会让上层以为有进展、无限重试。
    if (start <= chunkStart) return chunkStart
    // 正常：缺口正好是片的末尾（或更靠后）⇒ 这片确实进去了。
    // 夹回片尾：服务端说"从我后面的某处开始"，但中间缺的正是这一片 —— 不夹会留洞。
    return minOf(start, chunkEndExclusive)
}

/** 区间串（`"327680-"` 或 `"0-655360"`）的**起点**；非法返回 null。 */
internal fun parseRangeStart(range: String): Long? {
    val head = range.substringBefore('-', missingDelimiterValue = "")
    if (head.isEmpty()) return null
    val value = head.toLongOrNull() ?: return null
    return if (value < 0) null else value
}

/** 没有进展时的统一出口：先计次，超限才放弃。 */
private fun noProgressVerdict(
    reason: String,
    confirmedOffset: Long,
    stallCount: Int,
    maxStalls: Int,
): ChunkResumeVerdict = if (stallCount >= maxStalls) {
    ChunkResumeVerdict.GiveUp("$reason；连续 $stallCount 次没有进展")
} else {
    ChunkResumeVerdict.Continue(offset = confirmedOffset, progressed = false, reason = reason)
}

/**
 * 允许的"连续无进展"次数。
 *
 * 5 次 × 每片 5 MiB 的重发上限，代价远小于"整个库白传一遍"；
 * 而如果服务端真的坏了，5 次之内必然能看出来（每次都是同一片）。
 */
const val MAX_STALLS: Int = 5
