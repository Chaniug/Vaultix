/*
 * Vaultix — data:kdbx
 * Copyright (C) 2026 Vaultix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * KDBX 字段键区的**单一真源**（W1 · JSON⇄KDBX 无损互转 · 立单 2026-10-05）。
 *
 * 引入动机：标准键清单此前在 [KdbxItemMapper] 写死一份（"URL"），写侧却用 kotpass 的
 * `BasicField.Url.key`（"Url"）⇒ 大小写不一致，且与 OTP / 通行密钥判区各抄一份，
 * 必然漂移（实测 `STANDARD_FIELD_KEYS` 的 "URL" 漏判标准 URL 键，导致 URL 被覆盖）。
 *
 * 三个键区永不交叉（见 Docs/progress/json-kdbx-lossless-conversion.md §2.1）：
 *   ① 标准区（5 个，KDBX 语义固定）     Title / UserName / Password / Url / Notes
 *   ② 工具区（前缀 VPX_，本应用专用）   转换器生成，可逆还原
 *   ③ 通行密钥区（前缀 KPEX_，KeePassDX） KPEX_PASSKEY_*
 * OTP 区由 [KdbxTotpCodec] 维护（已含 OTP_FIELD_NAMES）。
 *
 * 铁律 R1：写入自定义字段前，若键名（大小写折叠后）命中保留区 ⇒ 跳过，绝不写入
 * —— 否则自定义字段 `Title` 会直接覆盖库名（现网 bug，数据毁；探针 PROBE 实测真覆盖）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.kdbx

import java.util.Locale

/** KDBX 字段键区的单一真源（标准键 + 保留判区），读 / 写两侧共用，杜绝抄一份必然漂移。 */
internal object KdbxFieldKeys {

    /**
     * 标准五键。键名必须与 kotpass 的 [app.keemobile.kotpass.models.EntryFields] 一致：
     * 写 `"Url"` 而非 `"URL"`（kotpass 大小写敏感，写错就读不出 `url`）。
     */
    val STANDARD: Set<String> = setOf("Title", "UserName", "Password", "Url", "Notes")

    /** 工具字段前缀（转换器产出，反向转换可识别「哪些是工具生成的」）。 */
    const val TOOL_PREFIX = "VPX_"

    private val STANDARD_LOWER: Set<String> = STANDARD.map { it.lowercase(Locale.ROOT) }.toSet()

    /**
     * 该键名是否属于「保留区」（标准 / OTP / 通行密钥），大小写折叠后判定。
     * 命中即**不可作为自定义字段写回**（R1）—— 见文件头说明。
     */
    fun isReserved(name: String): Boolean {
        if (name.isBlank()) return false
        val lower = name.lowercase(Locale.ROOT)
        return lower in STANDARD_LOWER ||
            KdbxTotpCodec.isOtpFieldName(name) ||
            KdbxPasskeyCodec.isPasskeyFieldName(name)
    }
}
