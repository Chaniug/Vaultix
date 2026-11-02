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
 * 对 `nextExpectedRanges` 的判断结果。
 *
 * ⚠️ 三选一，没有"就这样吧"。
 */
sealed interface ChunkResumeVerdict {

    /**
     * 从 [offset] 继续（[offset] 一定 **≤** 传进来的 `confirmedOffset`）。
     *
     * @param progressed 服务端位置是否比 `confirmedOffset` **更靠前**（= 这一片确实被收了）。
     *   用来判断"有没有进展" —— 没有进展时重试是有意义的，但**必须计次**。
     */
    data class Continue(
        val offset: Long,
        val progressed: Boolean,
        /** 只用于日志（"为什么这么续"）。⚠️ 不要拿它做分支。 */
        val reason: String = "",
    ) : ChunkResumeVerdict

    /**
     * 服务端要求**从头来**（或者给的区间不可信）。
     *
     * ⚠️ 与 [GiveUp] 的区别：这里"重来"是**有意义的**（服务端确实清空了会话），
     * 而 [GiveUp] 是"再试也没用，别浪费时间"。
     */
    data class Restart(val reason: String) : ChunkResumeVerdict

    /** 连续无进展次数超限 ⇒ 让上层整次重来（或者直接报失败，由它决定）。 */
    data class GiveUp(val reason: String) : ChunkResumeVerdict
}
