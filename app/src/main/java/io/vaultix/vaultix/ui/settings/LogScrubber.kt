/*
 * Vaultix — app:ui:settings
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 开发者日志的**脱敏**与**保留判定**（`.ai/ISSUES.md` #206，2026-10-29）。
 *
 * ## 为什么要有这个对象（此前两个洞）
 *
 * 1. **屏幕显示没有脱敏**：脱敏原先只发生在导出（`buildExportText` 的 `sanitize`），
 *    而对话框里**逐行显示的是原文**。日志源是 logcat —— 谁往里打了什么不受我们控制
 *    （第三方 SDK 常常把请求体整段打出来）。"导出时脱敏"保护的是**分享出去的那份**，
 *    没保护**正看着屏幕的人**。
 * 2. **`E/F` 兜底把白名单架空**：过滤条件原先是
 *    `KEEP_PATTERN.containsMatchIn(text) || level in "EF"` —— 第二条让白名单对
 *    `E`/`F` 级**完全失效**，而白名单存在的唯一理由就是挡噪声（Honor 机型的
 *    `afehal` 等实测几十秒就能把有效记录淹没）。**"兜底"比"主判据"更宽，等于没有判据。**
 *
 * ⇒ 修法：① **采集即脱敏**（一处生效，显示与导出都干净，且只脱敏保留下来的行）；
 *        ② `E/F` 兜底收窄为「**且**文本属于本应用」（见 [shouldKeep]）。
 *
 * ## 纪律
 *
 * 项目要求日志只记非敏感元数据（`AutofillLogger` KDoc），但**纪律靠人守、脱敏靠机器** ——
 * 这里就是那台机器，所以替换一律**保守**：宁可多遮，不可漏遮。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.settings

internal object LogScrubber {

    /**
     * 是否保留这一行。
     *
     * 两条通路：
     * - **白名单命中**（主判据）：与 adb 侧采集脚本同一套口径，避免"应用内看到的"和
     *   "adb 看到的"两套过滤互相打架；
     * - **`E/F` 且属于本应用**（兜底）：自家崩溃/断言不该因为 TAG 不在白名单里被丢掉。
     *
     * ⚠️ 兜底**必须**带 [SELF_HINT] 限定：不带限定的 `level in "EF"` 会让白名单在
     * 错误级别上失效 —— 那正是本条要修的洞。
     */
    fun shouldKeep(text: String, level: Char): Boolean {
        if (KEEP_PATTERN.containsMatchIn(text)) return true
        return level in ERROR_LEVELS && SELF_HINT.containsMatchIn(text)
    }

    /**
     * 脱敏：**只做保守替换**。
     *
     * 覆盖四类：`键=值` 形态的敏感键、邮箱、手机号、超长 base64/hex 串。
     * 最后一条兜住"某个地方不小心把密钥 / token 打进了日志"。
     */
    fun scrub(text: String): String {
        var out = text
        out = out.replace(SENSITIVE_KV) { m -> "${m.groupValues[1]}=<redacted>" }
        out = out.replace(EMAIL, "<email>")
        out = out.replace(PHONE, "<phone>")
        out = out.replace(LONG_BLOB, "<redacted>")
        return out
    }

    /**
     * 关键字白名单：与本项目 adb 侧采集脚本保持**同一套口径**。
     *
     * ⚠️ 不要退回"保留所有 `E/F` 级"：那正是 #206 修掉的洞（噪声淹没有效记录）。
     */
    private val KEEP_PATTERN = Regex(
        "Vaultix|Credential|passkey|Passkey|WebAuthn|Autofill|autofill|CredMan|FIDO|" +
            "KeyStore|keystore|Biometric|AndroidRuntime|FATAL",
    )

    /** 兜底的"自家"标识：包名与进程名都含它（`io.vaultix.vaultix`）。 */
    private val SELF_HINT = Regex("(?i)vaultix")

    private const val ERROR_LEVELS = "EF"

    private val SENSITIVE_KV = Regex(
        "(?i)(password|passwd|pwd|secret|token|api_?key|private_?key|recovery)\\s*[=:]\\s*\\S+",
    )
    private val EMAIL = Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+")
    private val PHONE = Regex("1[3-9]\\d{9}")
    private val LONG_BLOB = Regex("[A-Za-z0-9+/=]{40,}")
}
