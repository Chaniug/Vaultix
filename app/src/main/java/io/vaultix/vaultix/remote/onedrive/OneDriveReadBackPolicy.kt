/*
 * Vaultix — app / OneDrive
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * **写后读回校验的判定规则** —— 纯逻辑，不碰 HTTP、不碰 KDBX。
 *
 * ## 为什么单独一个文件（#232）
 *
 * 原先是 `OneDriveKdbxFileSource` 里的一段私有逻辑：`upload()` 之后**无条件**整份下载
 * 回来逐字节比对。正确性没问题，但代价是**每次保存都要多下一次整库**——
 * 库 2 MiB 就是每次保存多走 2 MiB 上行。而这一层真正需要的只有三种结局，
 * 且其中两种根本不需要下载就能判出来。
 *
 * ⇒ 把"该不该下载 / 下载之后怎么判"抽成纯函数：可以脱离网络单测，
 * 也让「不下载」这件事有一个**需要满足条件**的明确入口（不是一个静默的 `if`）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.remote.onedrive

/**
 * 读回校验的三种结局。
 *
 * ⚠️ 刻意**不**提供"跳过校验"这一项：那是把防线整个关掉，不是一种结论。
 * 不下载只可能出现在「元数据已经证明不一致」这一支（见 [ReadBackPlan]）。
 */
enum class ReadBackVerdict {
    /** 远端记录的大小/时间戳之一与本次写入不符 ⇒ 不必下载就能断定不一致。 */
    MISMATCH_PROVEN,

    /** 元数据看起来一致（大小对、没被写后修改）⇒ 仍须下载并逐字节比对。 */
    NEEDS_BYTE_CHECK,
}

/**
 * 一次写回之后**该做什么**，以及（若不必下载）该给出的诊断。
 *
 * @property verdict 见 [ReadBackVerdict]。
 * @property reason 仅 [ReadBackVerdict.MISMATCH_PROVEN] 时有值，直接拼进冲突文案。
 * @property suspiciousTimestamp 远端最后修改时间**明显早于**本次写入（见
 *   [CLOCK_SKEW_TOLERANCE_MS]）。**不改变结论**，只作为诊断线索随冲突文案带出 ——
 *   "上传成功但远端时间戳停在一小时前"是排查"服务端回滚 / 写到了别的副本"的第一条线索。
 */
data class ReadBackPlan(
    val verdict: ReadBackVerdict,
    val reason: String = "",
    val suspiciousTimestamp: Boolean = false,
) {
    /** 是否必须发起一次下载。 */
    val requiresDownload: Boolean get() = verdict == ReadBackVerdict.NEEDS_BYTE_CHECK

    companion object {
        val NEEDS_BYTE_CHECK = ReadBackPlan(ReadBackVerdict.NEEDS_BYTE_CHECK)
    }
}

/**
 * 决定"写完之后要不要下载回来".
 *
 * ## 判据只有一条，是**远端自己报的**、能独立成立的事实
 *
 * **大小**：`size` 与写入字节数不等 ⇒ 内容不可能相同（内容的长度是确定的）
 * ⇒ [ReadBackVerdict.MISMATCH_PROVEN]，连下载都省了。
 *
 * ## ⚠️ 时间戳这一半为什么**不算**"证明不一致"（2026-11-02 补，写测试时踩到）
 *
 * 第一版把"远端 `lastModifiedDateTime` 明显早于本次写入开始"也当成证明 ——
 * 在真机上这纯属**透支证据**：
 *
 * - `lastModifiedDateTime` 的**语义**是"内容最后一次被修改"，而 Graph 在上传
 *   进行中就可能更新它（真机上常见：上传刚返回、服务端还在收尾，时间戳是几秒前）；
 * - 更要命的是它与**本机时钟**比较，而两者之间隔着 NTP 漂移、用户手改时间、
 *   以及"服务端秒级截断"。
 *
 * 一旦判错，用户看到的是一个**根本不存在的冲突**（然后开始怀疑数据），
 * 而正确的兜底（下载逐字节比）本来就一定会跑对。
 * ⇒ 时间戳只用来**降级**，不用来判死：它明显偏旧时**仍然下载**，但把
 * [ReadBackPlan.suspiciousTimestamp] 置真，让冲突文案能多说一句"远端时间戳偏旧"，
 * 排查时少走一圈弯路。**结论永远交给字节。**
 *
 * ## ⚠️ 尺寸相同时**不许**下结论
 *
 * "长度对、内容坏"是本项目点名过的最坏形态（分片重复 / 传输截断都能造出它）。
 * 大小一致只能说明"需要再看字节"，**不是**"校验通过"。
 * 所以本函数只有"证明不一致"与"证明不了、去下载"两种输出 —— **没有"证明一致"**。
 *
 * ## ⚠️ 为什么时间戳这一条要留那么大的容差
 *
 * Graph 的 `lastModifiedDateTime` 是服务端时钟（`UTC`），而我们比的是**本机时钟**。
 * 两者之间有 NTP 漂移，移动端还可能被用户随手改。
 * ⇒ 只有"远端时间 **明显早于** 我们开始写的时刻"才算证据（[CLOCK_SKEW_TOLERANCE_MS]），
 * 而不是"比我们记的时刻小就算"。宁可多下载一次，也不要因为时钟差几秒就**误报冲突**
 * —— 那会让用户在一个根本没冲突的地方被要求拍板，然后开始怀疑数据。
 *
 * @param writtenSize 本次写入的字节数。
 * @param entry 写入接口返回的远端条目（Graph 响应里的新元数据）。
 * @param writeStartedAtMs 本次写入**开始之前**取的本机时刻（毫秒）；`null` = 没取到
 *   ⇒ 时间戳那一半不参与判定。
 *   ⚠️ 必须是"开始之前"：拿写完之后取的时刻去比，秒级精度的服务端时间戳
 *   会恒定地"早于"它 ⇒ 每次都误判。
 */
fun readBackPlan(
    writtenSize: Int,
    entry: OneDriveEntry,
    writeStartedAtMs: Long?,
): ReadBackPlan {
    val recorded = entry.sizeBytes
    if (recorded != null && recorded != writtenSize.toLong()) {
        return ReadBackPlan(
            verdict = ReadBackVerdict.MISMATCH_PROVEN,
            reason = "写入 $writtenSize 字节，远端记录 $recorded 字节",
        )
    }
    val modified = entry.lastModifiedMillis
    // 两边只要有一个不知道就不能比（`null` 不等于"没改过"）。
    val suspicious = writeStartedAtMs != null && modified != null &&
        modified + CLOCK_SKEW_TOLERANCE_MS < writeStartedAtMs
    // ⚠️ 只带诊断、不改结论：见上面「时间戳这一半为什么不算证明不一致」。
    return ReadBackPlan(ReadBackVerdict.NEEDS_BYTE_CHECK, suspiciousTimestamp = suspicious)
}

/**
 * 时钟容差（5 分钟）。
 *
 * 取值理由：NTP 漂移典型在秒级，手机被用户手动改时间的情况更常见；
 * 而"服务端的时间戳真的早于我们开始写"这种证据，在正常路径上一定差着**分钟以上**
 * （上传本身就要几百毫秒到几十秒，加上响应回传）。
 * ⇒ 5 分钟既能挡住真实的时间倒流，又不会被正常的时钟差撞出误报。
 */
const val CLOCK_SKEW_TOLERANCE_MS: Long = 5 * 60 * 1000
