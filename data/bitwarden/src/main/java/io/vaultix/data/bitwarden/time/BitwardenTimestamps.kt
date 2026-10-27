/*
 * Vaultix — data:bitwarden
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.data.bitwarden.time

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Bitwarden 时间串 ⇄ epoch 毫秒的**唯一**编解码点。
 *
 * ## 为什么抽出来（2026-10-28 · 导出往返保真度）
 *
 * 此前只有 `CipherMapper` 里一个**私有**的 `parseBitwardenInstant`（服务端 DTO → 领域模型），
 * 导出侧（领域模型 → 官方 JSON）**反向**没有对应实现 ⇒ `creationDate` / `revisionDate` /
 * `passwordHistory[].lastUsedDate` 三个字段恒为 null ⇒ 用户导出的备份里**没有时间戳、
 * 没有密码历史**，而导出界面报"成功"。
 * 两侧各写一份解析/格式化必然漂移（本项目已因"同一规则写两处"栽过多次）⇒ 收敛到这里。
 *
 * ## 两个方向都必须"不知道就说不知道"
 *
 * - 解析失败 → `null`，**绝不**兜"现在"或 1970：兜默认值的症状是
 *   「详情页显示一个看起来合理但完全错误的时间」，用户会据此判断"这条是不是我昨天改的"
 *   —— 那是**假信息**，比不显示更坏（UI 侧遇到 null 是**不显示这一行**）。
 * - 格式化时 `null` → `null`（字段省略，官方导入器按可选处理）。
 *
 * ## 格式：RFC 3339 **毫秒**精度
 *
 * 官方 `json.rs` 用 `rfc3339_millis_serialize`，即 `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`
 * （**不带**时区偏移，恒 `Z`）。
 *
 * ⚠️ 用 [DateTimeFormatter] 显式指定毫秒，而**不是** `Instant.toString()`：
 * 后者在整秒时会省略 `.SSS`（`…T12:34:56Z`），与官方导出的形状不一致 ——
 * 差异虽能被解析，但"我们自己导出的文件长得和官方不一样"会让互操作问题难以定位。
 */
internal object BitwardenTimestamps {

    /** 官方导出的时间形状。**恒 UTC、恒三位毫秒**；`Locale.US` 防止默认语言环境的数字形状干扰。 */
    private val RFC3339_MILLIS: DateTimeFormatter = DateTimeFormatter
        .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .withZone(ZoneOffset.UTC)

    /**
     * Bitwarden 时间串 → epoch 毫秒；无法解析一律 `null`。
     *
     * ⚠️ 用 `java.time.Instant.parse` 而不是手写正则：它能同时吃 `...Z`、
     * 带毫秒 / 不带毫秒、带 `+08:00` 偏移这几种服务端常见写法；
     * 自建 Vaultwarden 上偶尔会有非标准串，`runCatching` 兜住即可。
     */
    fun parse(raw: String?): Long? =
        raw?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    /** epoch 毫秒 → 官方时间串；`null` 或格式化失败 → `null`（字段省略）。 */
    fun format(epochMs: Long?): String? =
        epochMs?.let { runCatching { RFC3339_MILLIS.format(Instant.ofEpochMilli(it)) }.getOrNull() }
}
