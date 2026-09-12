/*
 * Vaultix — app:autofill · fillassist
 * Copyright (C) 2026 Vaultix contributors
 *
 * 「填充辅助（Fill Assist）」规则模型。
 *
 * 移植自 Bitwarden `map-the-web` 规则表（GPL-3.0，© Bitwarden Inc.）——
 * 用站点级 CSS 选择器精确指出「账号 / 密码 / 卡号」等字段，**命中后不再走启发式**。
 * 规则由服务端下发（`/api/config` 的 `environment.fillAssistRules`），客户端定期刷新。
 */
package io.vaultix.vaultix.autofill.fillassist

import io.vaultix.vaultix.autofill.model.FieldHint

/** 用于「登录」分区的规则类别（对齐 Bitwarden `LOGIN_FILL_ASSIST_CATEGORIES`）。 */
val LOGIN_FILL_ASSIST_CATEGORIES: Set<String> =
    setOf("account-login", "account-creation", "account-update")

/** 用于「卡片」分区的规则类别（对齐 Bitwarden `CARD_FILL_ASSIST_CATEGORIES`）。 */
val CARD_FILL_ASSIST_CATEGORIES: Set<String> = setOf("payment-card")

/**
 * 单条选择器子句：一条 CSS 选择器里**被支持**的那部分约束。
 *
 * 不支持的部分（`.class` 限定符、`[placeholder='…']` 之类的属性）在解析阶段就整条丢弃，
 * 否则会退化成「只按 tag 匹配」从而命中一切同类元素。
 */
data class SelectorClause(
    val tag: String? = null,
    val id: String? = null,
    val name: String? = null,
    val type: String? = null,
    val role: String? = null,
) {
    /** 是否至少有一项约束：无约束子句会匹配一切，必须拒绝。 */
    val hasConstraint: Boolean
        get() = tag != null || id != null || name != null || type != null ||
            role != null
}

/** 单个主机下的一类表单规则。 */
data class HostRule(
    val category: String,
    /** 字段语义键（`username` / `password` / …）→ 候选选择器子句。 */
    val fields: Map<String, List<SelectorClause>>,
)

/** 全部站点的规则表（键为主机名，**已去掉 `www.` 前缀**，与上游一致）。 */
data class FillAssistRules(
    val hostRules: Map<String, List<HostRule>>,
) {
    /** 取某主机的规则；无则返回空表，调用方据此退回启发式。 */
    fun forHost(host: String?): List<HostRule> =
        host?.trim()?.removePrefix("www.")?.let { hostRules[it] }.orEmpty()

    /** 规则表是否为空（未拉到 / 未启用）。 */
    val isEmpty: Boolean get() = hostRules.isEmpty()

    companion object {
        /** 空实现：用于「未启用 / 拉取失败」，行为等同没有填充辅助。 */
        val EMPTY: FillAssistRules = FillAssistRules(emptyMap())
    }
}

/**
 * 规则里的字段键 → 本项目语义；不支持的键返回 null。
 *
 * `username` 与 `phone` 都归 [FieldHint.USERNAME]：上游 `Login.Username` 不限制值形态，
 * 而 `Login.Email` 会用 `isValidEmail()` 拒掉非邮箱值——用手机号当账号的条目不该被拒。
 */
fun fieldKeyToHint(fieldKey: String): FieldHint? = when (fieldKey) {
    "username", "phone" -> FieldHint.USERNAME
    "email" -> FieldHint.EMAIL_ADDRESS
    "password", "newPassword" -> FieldHint.PASSWORD
    "cardNumber" -> FieldHint.CARD_NUMBER
    "cardCvv" -> FieldHint.CARD_CVC
    "cardExpirationDate", "cardExpirationMonth", "cardExpirationYear" -> FieldHint.CARD_EXPIRY
    "cardholderName" -> FieldHint.NAME
    else -> null
}
