/*
 * Vaultix — app:autofill · model
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.autofill.model

import android.view.autofill.AutofillId

/**
 * 表单字段语义（解析层归一后的结果；对齐 Bastion autofill_ng 的 FieldHint 思路，
 * GPL-3.0，Copyright 2025 JoyinJoester）。v1 填充只消费 [USERNAME] / [PASSWORD]，
 * 其余枚举保留以便后续扩展（卡片 / 邮箱 / 地址等）。
 */
enum class FieldHint {
    USERNAME,
    PASSWORD,
    NEW_PASSWORD,
    EMAIL_ADDRESS,
    PHONE_NUMBER,
    POSTAL_CODE,
    CARD_NUMBER,
    CARD_CVC,
    CARD_EXPIRY,
    NAME,
    SEARCH,
    OTP,
    UNKNOWN,
}

/**
 * 单个可填充字段（已归一语义）。
 *
 * @param id 框架回填时的目标 [AutofillId]（来自 [android.app.assist.AssistStructure]）。
 * @param hint 语义类型，见 [FieldHint]。
 * @param value 当前已填文本（可能为空，仅用于启发式与回填校验）。
 * @param isFocused 是否为当前聚焦字段。
 * @param isVisible 是否可见（不可见字段不参与填充）。
 */
data class ParsedField(
    val id: AutofillId,
    val hint: FieldHint,
    val value: String?,
    val isFocused: Boolean = true,
    val isVisible: Boolean = true,
)

/**
 * 一次 [android.service.autofill.FillRequest] 解析后的整体结果。
 *
 * @param packageName 触发填充的 App 包名（用于包名匹配）。
 * @param webScheme 网页方案（http/https），WebView / 浏览器场景。
 * @param webDomain 网页域名（来自 AssistStructure.webDomain 或浏览器地址栏）。
 * @param webUri 尽量完整的网页地址（scheme + host；Android AssistStructure 仅暴露 host，
 *   故路径/查询段无法获取，Exact/StartsWith/Regex 匹配退化为按 host 近似，见 BitwardenLikeAutofillMatcher）。
 * @param fallbackWebDomain 浏览器未上报 [webDomain] 时的兜底域名（地址栏文本 / 结构文本），
 *   **非权威**：只用于匹配，不用于「拒绝」判定（见 [io.vaultix.vaultix.autofill.parser.BrowserUrlBars]）。
 * @param webView 是否为 WebView / 浏览器表单（决定是否强制 inline 回填）。
 * @param usernameId 归一后的用户名字段 id（无则 null）。
 * @param passwordId 归一后的密码字段 id（无则 null）。
 * @param fields 全部已识别字段（供后续扩展与调试）。
 */
data class ParsedStructure(
    val packageName: String?,
    val webScheme: String?,
    val webDomain: String?,
    val fallbackWebDomain: String? = null,
    val webUri: String?,
    val webView: Boolean,
    val usernameId: AutofillId?,
    val passwordId: AutofillId?,
    val fields: List<ParsedField>,
)
