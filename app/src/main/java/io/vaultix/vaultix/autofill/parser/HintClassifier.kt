/*
 * Vaultix — app:autofill · parser
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * 单节点字段语义分类算法移植并改写自 Bastion autofill_ng 的「四类优先级判定」
 * （GPL-3.0，Copyright 2025 JoyinJoester）：标准 autofillHints > inputType > 文本/标签启发式。
 * 仅做纯映射，无 Android 运行时调用，便于 JVM 单测。
 */
package io.vaultix.vaultix.autofill.parser

import android.text.InputType
import io.vaultix.vaultix.autofill.model.FieldHint

/**
 * 把单个输入控件的可用信号（标准 hints / inputType / 文本）归一为 [FieldHint]。
 *
 * 优先级（对齐 Bastion）：
 * 1. 标准 `autofillHints`（最权威，如 `username` / `password` / `emailAddress`）；
 * 2. `inputType` 变体（密码 / 邮箱 / 电话 / 人名）；
 * 3. 文本/标签启发式（兜底，仅在无其它信号时启用）。
 *
 * WebView 的 className 由调用方另行标记（见 [AssistStructureParser]），不在此函数内处理，
 * 避免把缺乏信号的 WebView 字段误判而遮蔽文本启发式。
 *
 * 信号强度（[SignalStrength]，对齐 Bastion `EnhancedAutofillStructureParserV2.Accuracy`
 * 的分级精神，GPL-3.0，Copyright 2025 JoyinJoester）：标准 hint 最强、inputType 次之、
 * 文本启发式最弱。**弱信号只作兜底展示，不得单独触发密码候选**——孤立文本框
 * （搜索栏 / 昵称 / 订阅框，placeholder 或 id 里含 "login / 账号 / 用户名" 的输入框）
 * 若仅凭文本启发式命中 USERNAME，会在没有密码框的页面误弹密码条目（Bastion
 * 「京东搜索栏误弹」同根因，其 P1 修复即按 MEDIUM+ 过滤）。
 */
object HintClassifier {

    /** 字段信号来源的可信度（用于「无密码框时不弹弱信号账号字段」判定）。 */
    enum class SignalStrength { HIGH, MEDIUM, LOW }

    /** 分类结果：语义 + 来源强度。 */
    data class Classified(val hint: FieldHint, val strength: SignalStrength)

    // ---- InputType 常量（引用 Android 编译期常量，运行时即为真实值）----
    private const val MASK_CLASS = InputType.TYPE_MASK_CLASS
    private const val MASK_VARIATION = InputType.TYPE_MASK_VARIATION
    private const val CLASS_TEXT = InputType.TYPE_CLASS_TEXT
    private const val CLASS_PHONE = InputType.TYPE_CLASS_PHONE
    private const val VARIATION_PASSWORD = InputType.TYPE_TEXT_VARIATION_PASSWORD
    private const val VARIATION_VISIBLE_PASSWORD = InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
    private const val VARIATION_WEB_PASSWORD = InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
    private const val VARIATION_EMAIL = InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
    private const val VARIATION_WEB_EMAIL = InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
    private const val VARIATION_PERSON_NAME = InputType.TYPE_TEXT_VARIATION_PERSON_NAME

    fun classify(
        hints: List<String>?,
        inputType: Int,
        text: String?,
    ): Classified {
        // 一个节点可能带多个 hint（Chromium 常同时给 web* 与标准 hint）：全部试一遍
        hints?.firstNotNullOfOrNull { hint ->
            mapAutofillHint(hint)?.let { Classified(it, SignalStrength.HIGH) }
        }?.let { return it }
        mapInputType(inputType)?.let { return Classified(it, SignalStrength.MEDIUM) }
        text?.let { mapTextHeuristic(it)?.let { hint -> return Classified(hint, SignalStrength.LOW) } }
        return Classified(FieldHint.UNKNOWN, SignalStrength.LOW)
    }

    /**
     * 标准 autofill hint 字符串 → [FieldHint]（null 表示未识别）。
     *
     * `webUsername` / `webPassword` 是 **Chromium 内核浏览器（Chrome / Edge / Brave …）
     * 在 WebView 表单里实际下发的 hint**（对齐 Bitwarden `AutofillParserImpl` 的别名处理，
     * KeePassDX 亦有同款常量）——缺了它们，浏览器里的账号密码框只能靠 inputType 兜底，
     * 表现就是「浏览器里填充不认字段」。
     */
    private fun mapAutofillHint(hint: String): FieldHint? = when (hint) {
        "username", "webUsername" -> FieldHint.USERNAME
        "password", "webPassword" -> FieldHint.PASSWORD
        "newPassword", "newUsername" -> FieldHint.NEW_PASSWORD
        "emailAddress", "webEmail" -> FieldHint.EMAIL_ADDRESS
        "phone", "phoneNumber", "webTel" -> FieldHint.PHONE_NUMBER
        "postalCode", "postalAddress" -> FieldHint.POSTAL_CODE
        "creditCardNumber" -> FieldHint.CARD_NUMBER
        "creditCardSecurityCode" -> FieldHint.CARD_CVC
        "creditCardExpirationDate", "creditCardExpirationMonth", "creditCardExpirationYear" ->
            FieldHint.CARD_EXPIRY
        "personName", "name", "givenName", "familyName" -> FieldHint.NAME
        "search" -> FieldHint.SEARCH
        "otp", "oneTimeCode", "smsOTPCode" -> FieldHint.OTP
        else -> null
    }

    /** inputType 变体 → [FieldHint]（null 表示非文本/电话类或未知变体）。 */
    private fun mapInputType(inputType: Int): FieldHint? {
        val cls = inputType and MASK_CLASS
        val variation = inputType and MASK_VARIATION
        return when {
            cls == CLASS_TEXT && variation in setOf(
                VARIATION_PASSWORD,
                VARIATION_VISIBLE_PASSWORD,
                VARIATION_WEB_PASSWORD,
            ) -> FieldHint.PASSWORD
            cls == CLASS_TEXT && variation in setOf(VARIATION_EMAIL, VARIATION_WEB_EMAIL) ->
                FieldHint.EMAIL_ADDRESS
            cls == CLASS_TEXT && variation == VARIATION_PERSON_NAME -> FieldHint.NAME
            cls == CLASS_PHONE -> FieldHint.PHONE_NUMBER
            else -> null
        }
    }

    /**
     * 文本/标签启发式（兜底）：命中关键词才返回，避免误判。
     *
     * ⚠️ **2026-09-12 对齐 Bitwarden（`ViewStructureUtils` / `ViewNodeExtensions`）三处**，
     * 这是「不误弹 + 精准填充」的关键：
     * 1. **否定词优先**：命中 `search` / `find` / `recipient` / `edit`（及中文「搜索 / 查找 /
     *    收件人 / 编辑」）时**直接放弃**——对齐上游 `IGNORED_RAW_HINTS`。没有这道闸时，
     *    `id="login-search"` 这类搜索框会被 `login` 命中而误判成账号框。
     * 2. **关键词收窄**：用户名只用 `username` 这类**明确**词，对齐上游的
     *    `SUPPORTED_RAW_USERNAME_HINTS`（`email`/`phone`/`username`）——上游**没有 `login`**。
     *    我们保留中文「用户名 / 账号」以覆盖中文站点（英文站点靠「紧邻密码框升格」兜底）。
     * 3. **归一化**：先转小写、去掉 ASCII 分隔符（`_` `-` `.` 空格），让 `user_name` /
     *    `user-name` 都能命中 `username`；**中文原样保留**。
     */
    private fun mapTextHeuristic(text: String): FieldHint? {
        val t = normalize(text)
        if (IGNORED_TERMS.any { t.contains(it) }) return null
        if (PASSWORD_TERMS.any { t.contains(it) }) return FieldHint.PASSWORD
        if (USERNAME_TERMS.any { t.contains(it) }) return FieldHint.USERNAME
        // 验证码框常常既没有 autofillHints 也没有特殊 inputType，只靠 id/placeholder 文本识别
        if (OTP_TERMS.any { t.contains(it) }) return FieldHint.OTP
        if (EMAIL_TERMS.any { t.contains(it) }) return FieldHint.EMAIL_ADDRESS
        return null
    }

    /** 转小写并去掉 ASCII 分隔符（保留字母 / 数字与非 ASCII 字符，中文不受影响）。 */
    private fun normalize(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() || it.code > ASCII_MAX }

    /** ASCII 上界；大于它的字符（中文等）在归一化时原样保留。 */
    private const val ASCII_MAX = 0x7F

    /** 否定词（对齐 Bitwarden `IGNORED_RAW_HINTS`，另加中文对照）。 */
    private val IGNORED_TERMS = listOf(
        "search", "find", "recipient", "edit",
        "搜索", "查找", "收件人", "编辑",
    )

    private val PASSWORD_TERMS = listOf("password", "pswd", "密码", "口令")
    private val USERNAME_TERMS = listOf("username", "用户名", "账号")
    private val EMAIL_TERMS = listOf("email", "邮箱")

    /** 验证码关键词（移植 Bastion `OtpAutofillSideEffects.isOtpHint`，GPL-3.0，Copyright 2025 JoyinJoester）。 */
    private val OTP_TERMS = listOf(
        "otp", "onetime", "totp", "2fa", "twofactor", "mfa",
        "verification", "verify", "验证码", "驗證碼", "一次性", "动态码",
    )
}
