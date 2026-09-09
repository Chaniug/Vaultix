/*
 * Vaultix — app:autofill · engine
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * 填充规划（纯逻辑，无 Android 依赖，便于 JVM 单测）。输入已解析的表单 [FillContext]、
 * 已按 Bitwarden 口径匹配排序的登录凭据、以及已解锁的卡片/身份条目，产出 [FillPlan]。
 * 是否解锁（needsUnlock）由调用方（Service / Engine）根据已解锁库状态填充。
 */
package io.vaultix.vaultix.autofill.engine

import io.vaultix.model.VaultItem
import io.vaultix.vaultix.autofill.model.AutofillCredential
import io.vaultix.vaultix.autofill.model.FieldHint
import io.vaultix.vaultix.autofill.model.FillCategory
import io.vaultix.vaultix.autofill.model.FillContext
import io.vaultix.vaultix.autofill.model.FillPlan
import io.vaultix.vaultix.autofill.model.FillSuggestion

/** 把已解析表单 + 候选凭据规划为填充建议（对齐 Bitwarden 按表单上下文出候选）。 */
object FillPlanner {

    /** 有效期月份合法范围（1–12）。 */
    private const val MIN_MONTH = 1
    private const val MAX_MONTH = 12
    /** 有效期年份取末两位（MM/YY）。 */
    private const val EXPIRY_YEAR_TAIL = 2
    /** 月份补零后的位宽（MM）。 */
    private const val MONTH_PAD_WIDTH = 2
    private const val ZERO_PAD_CHAR = '0'
    /** 掩码展示的卡片尾号位数。 */
    private const val CARD_LAST_DIGITS = 4

    /**
     * @param context 页面上下文（含字段语义与账号/密码框是否存在）。
     * @param matchedLogins 已按域名/包名匹配并排序的登录凭据。
     * @param cards 已解锁的卡片条目。
     * @param identities 已解锁的身份条目。
     * @param totpProvider 由 TOTP 密钥计算当前验证码（secret → code；不可用时返回 null）。
     */
    fun plan(
        context: FillContext,
        matchedLogins: List<AutofillCredential>,
        cards: List<VaultItem>,
        identities: List<VaultItem>,
        totpProvider: (String) -> String?,
    ): FillPlan {
        val present = context.presentHints
        val suggestions = mutableListOf<FillSuggestion>()
        if (hasLoginContext(present, context.hasCredibleUsernameField)) {
            suggestions += buildLoginSuggestions(context, matchedLogins, present, totpProvider)
        } else if (hasOtpOnlyContext(present)) {
            // 纯 2FA 第二步页面（只有验证码框、没有账号密码框）：单独出「只填验证码」的建议，
            // 否则这类页面一条建议都没有，用户只能切出去手动复制。
            suggestions += buildOtpOnlySuggestions(matchedLogins, totpProvider)
        }
        if (hasCardContext(present)) {
            suggestions += buildCardSuggestions(cards, present)
        }
        if (hasIdentityContext(present)) {
            suggestions += buildIdentitySuggestions(identities, present)
        }
        return FillPlan(suggestions = suggestions, needsUnlock = false)
    }

    /**
     * 是否出登录候选。
     *
     * 有密码框 → 必出（密码框是登录的强信号，搜索框等孤立输入框不可能带密码框）。
     * 无密码框（多步登录第一步）→ 仅当用户名字段来自**强信号**（标准 autofillHints /
     * inputType 变体）才出——纯文本启发式命中（placeholder / id / htmlInfo 含
     * "login/账号/用户名"）的孤立文本框（搜索栏 / 昵称 / 订阅框）不得单独触发，
     * 否则会在非登录页面乱弹密码条目（对齐 Bastion
     * `AutofillDetectionPolicy.shouldKeepLoginField` 的 MEDIUM+ 门槛）。
     */
    private fun hasLoginContext(present: Set<FieldHint>, credibleUsername: Boolean): Boolean =
        FieldHint.PASSWORD in present ||
            (FieldHint.USERNAME in present && credibleUsername)

    /** 只有验证码框（2FA 第二步页面）。 */
    private fun hasOtpOnlyContext(present: Set<FieldHint>): Boolean = FieldHint.OTP in present

    /** 纯验证码页面：每个带 TOTP 的条目出一条「只填验证码」的建议。 */
    private fun buildOtpOnlySuggestions(
        logins: List<AutofillCredential>,
        totpProvider: (String) -> String?,
    ): List<FillSuggestion> = logins.mapNotNull { login ->
        if (login.totp.isBlank()) return@mapNotNull null
        val code = totpProvider(login.totp) ?: return@mapNotNull null
        FillSuggestion(
            id = "otp:${login.vaultId}:${login.itemId}",
            title = login.name.ifBlank { login.username },
            subtitle = login.username,
            fields = mapOf(FieldHint.OTP to code),
            category = FillCategory.LOGIN,
        )
    }

    private fun hasCardContext(present: Set<FieldHint>): Boolean =
        FieldHint.CARD_NUMBER in present || FieldHint.CARD_CVC in present || FieldHint.CARD_EXPIRY in present

    private fun hasIdentityContext(present: Set<FieldHint>): Boolean =
        FieldHint.NAME in present || FieldHint.EMAIL_ADDRESS in present ||
            FieldHint.PHONE_NUMBER in present || FieldHint.POSTAL_CODE in present

    private fun buildLoginSuggestions(
        context: FillContext,
        logins: List<AutofillCredential>,
        present: Set<FieldHint>,
        totpProvider: (String) -> String?,
    ): List<FillSuggestion> {
        val result = mutableListOf<FillSuggestion>()
        for (login in logins) {
            val fields = mutableMapOf<FieldHint, String>()
            if (context.hasUsernameField && login.username.isNotBlank()) {
                fields[FieldHint.USERNAME] = login.username
            }
            if (context.hasPasswordField && login.password.isNotBlank()) {
                fields[FieldHint.PASSWORD] = login.password
            }
            if (FieldHint.OTP in present && login.totp.isNotBlank()) {
                totpProvider(login.totp)?.let { fields[FieldHint.OTP] = it }
            }
            if (fields.isEmpty()) continue
            result += FillSuggestion(
                id = "login:${login.vaultId}:${login.itemId}",
                title = login.name.ifBlank { login.username },
                subtitle = login.username,
                fields = fields,
                requiresReprompt = login.requiresReprompt,
                totpSecret = login.totp.takeIf { it.isNotBlank() },
                category = FillCategory.LOGIN,
            )
        }
        return result
    }

    private fun buildCardSuggestions(cards: List<VaultItem>, present: Set<FieldHint>): List<FillSuggestion> {
        val result = mutableListOf<FillSuggestion>()
        for (item in cards) {
            buildCardSuggestion(item, present)?.let { result += it }
        }
        return result
    }

    private fun buildCardSuggestion(item: VaultItem, present: Set<FieldHint>): FillSuggestion? {
        val card = item.card ?: return null
        if (card.number.isBlank()) return null
        val fields = mutableMapOf<FieldHint, String>()
        if (FieldHint.CARD_NUMBER in present) fields[FieldHint.CARD_NUMBER] = card.number
        if (FieldHint.CARD_CVC in present && card.code.isNotBlank()) fields[FieldHint.CARD_CVC] = card.code
        if (FieldHint.CARD_EXPIRY in present) {
            formatExpiry(card.expMonth, card.expYear)?.let { fields[FieldHint.CARD_EXPIRY] = it }
        }
        if (FieldHint.NAME in present && card.cardholderName.isNotBlank()) {
            fields[FieldHint.NAME] = card.cardholderName
        }
        if (fields.isEmpty()) return null
        return FillSuggestion(
            id = "card:${item.id}",
            title = card.brand.ifBlank { item.title },
            subtitle = card.cardholderName.ifBlank { maskCardNumber(card.number) },
            fields = fields,
            category = FillCategory.CARD,
        )
    }

    private fun buildIdentitySuggestions(identities: List<VaultItem>, present: Set<FieldHint>): List<FillSuggestion> {
        val result = mutableListOf<FillSuggestion>()
        for (item in identities) {
            buildIdentitySuggestion(item, present)?.let { result += it }
        }
        return result
    }

    private fun buildIdentitySuggestion(item: VaultItem, present: Set<FieldHint>): FillSuggestion? {
        val id = item.identity ?: return null
        val fields = mutableMapOf<FieldHint, String>()
        if (FieldHint.NAME in present) {
            buildFullName(id.title, id.firstName, id.middleName, id.lastName)?.let {
                fields[FieldHint.NAME] = it
            }
        }
        if (FieldHint.EMAIL_ADDRESS in present && id.email.isNotBlank()) {
            fields[FieldHint.EMAIL_ADDRESS] = id.email
        }
        if (FieldHint.PHONE_NUMBER in present && id.phone.isNotBlank()) {
            fields[FieldHint.PHONE_NUMBER] = id.phone
        }
        if (FieldHint.POSTAL_CODE in present && id.postalCode.isNotBlank()) {
            fields[FieldHint.POSTAL_CODE] = id.postalCode
        }
        if (fields.isEmpty()) return null
        return FillSuggestion(
            id = "identity:${item.id}",
            title = item.title.ifBlank { id.firstName.ifBlank { id.company } },
            subtitle = id.company.ifBlank { id.email },
            fields = fields,
            category = FillCategory.IDENTITY,
        )
    }

    private fun buildFullName(title: String, first: String, middle: String, last: String): String? {
        val parts = listOf(title, first, middle, last).filter { it.isNotBlank() }
        return parts.joinToString(" ").takeIf { it.isNotBlank() }
    }

    /** 有效期格式化为 MM/YY（与主流表单一致）；缺月份或年份时返回 null。 */
    private fun formatExpiry(expMonth: String, expYear: String): String? {
        if (expMonth.isBlank() || expYear.isBlank()) return null
        val month = expMonth.toIntOrNull() ?: return null
        if (month < MIN_MONTH || month > MAX_MONTH) return null
        val monthText = month.toString().padStart(MONTH_PAD_WIDTH, ZERO_PAD_CHAR)
        val yearText = expYear.takeLast(EXPIRY_YEAR_TAIL)
        return "$monthText/$yearText"
    }

    private fun maskCardNumber(number: String): String {
        val digits = number.filter { it.isDigit() }
        return if (digits.length >= CARD_LAST_DIGITS) "•••• ${digits.takeLast(CARD_LAST_DIGITS)}" else number
    }
}
