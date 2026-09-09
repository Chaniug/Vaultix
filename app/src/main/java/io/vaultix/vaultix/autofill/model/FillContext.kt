/*
 * Vaultix — app:autofill · model
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.autofill.model

/**
 * 填充所需的纯上下文（不含任何 Android 框架类型，便于 JVM 单测）。
 * 由 Service 从 [ParsedStructure] 派生：页面上是否真的存在账号/密码输入框，
 * 以及出现了哪些字段语义（决定登录 / 卡片 / 身份上下文）。
 */
data class FillContext(
    /** 触发填充的 App 包名（包名匹配用）。 */
    val packageName: String?,
    /** 网页域名（基域匹配用）。 */
    val webDomain: String?,
    /** 尽量完整的网页地址（scheme + host）。 */
    val webUri: String?,
    /** 页面是否含账号输入框。 */
    val hasUsernameField: Boolean,
    /** 页面是否含密码输入框。 */
    val hasPasswordField: Boolean,
    /** 页面出现的所有字段语义集合。 */
    val presentHints: Set<FieldHint>,
    /**
     * 是否存在**强信号**（标准 autofillHints / inputType 变体）判定的用户名字段。
     *
     * 无密码框时仅当该标志为 true 才出密码候选（对齐 Bastion
     * `AutofillDetectionPolicy.shouldKeepLoginField`：孤立文本框 / 搜索栏只靠
     * 文本启发式命中 USERNAME，属弱信号，不得单独触发登录填充）。
     */
    val hasCredibleUsernameField: Boolean = false,
)
