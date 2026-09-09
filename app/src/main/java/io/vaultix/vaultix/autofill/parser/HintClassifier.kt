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
 */
object HintClassifier {

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
    ): FieldHint {
        // 一个节点可能带多个 hint（Chromium 常同时给 web* 与标准 hint）：全部试一遍
        hints?.firstNotNullOfOrNull { mapAutofillHint(it) }?.let { return it }
        mapInputType(inputType)?.let { return it }
        text?.let { mapTextHeuristic(it)?.let { hint -> return hint } }
        return FieldHint.UNKNOWN
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

    /** 文本/标签启发式（兜底）：命中关键词才返回，避免误判。 */
    private fun mapTextHeuristic(text: String): FieldHint? {
        val t = text.lowercase()
        if (PASSWORD_KEYWORDS.any { t.contains(it) }) return FieldHint.PASSWORD
        if (USERNAME_KEYWORDS.any { t.contains(it) }) return FieldHint.USERNAME
        if (EMAIL_KEYWORDS.any { t.contains(it) }) return FieldHint.EMAIL_ADDRESS
        return null
    }

    private val PASSWORD_KEYWORDS = listOf("password", "passwort", "密码", "口令")
    private val USERNAME_KEYWORDS = listOf("username", "user name", "login", "用户名", "账号", "登录")
    private val EMAIL_KEYWORDS = listOf("email", "e-mail", "邮箱")
}
