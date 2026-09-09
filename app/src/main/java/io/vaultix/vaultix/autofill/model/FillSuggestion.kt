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
 * 一次填充建议的类别（决定回填时取哪些字段）。
 */
enum class FillCategory {
    /** 登录（账号 + 密码 + 可选 OTP）。 */
    LOGIN,

    /** 银行卡（卡号 / 安全码 / 有效期 / 持卡人）。 */
    CARD,

    /** 身份信息（姓名 / 邮箱 / 电话 / 邮编等）。 */
    IDENTITY,
}

/**
 * 一个具体的填充建议（来自某条凭据），由 [io.vaultix.vaultix.autofill.engine.FillPlanner] 产出。
 *
 * [fields] 为「语义 → 待填值」映射，Service 据此把值回填到 [ParsedStructure] 中对应 hint 的
 * [android.view.autofill.AutofillId]。本类型不依赖任何 Android 框架类，可纯 JVM 单测。
 */
data class FillSuggestion(
    /** 稳定 id（vaultId:itemId:category），便于 Dataset.setId 去重。 */
    val id: String,
    /** 展示标题（凭据名 / 卡组织 / 身份名）。 */
    val title: String,
    /** 副标题（账号 / 持卡人等）。 */
    val subtitle: String,
    /** 待填充的「字段语义 → 值」映射。 */
    val fields: Map<FieldHint, String>,
    /** 主密码二次验证：回填前需再验主密码（走认证回灌路径）。 */
    val requiresReprompt: Boolean = false,
    /**
     * 条目自带的 TOTP 密钥原文（非空 = 该条目有验证码）。
     *
     * 用途：页面**没有**验证码框时，填充完成后自动把当前验证码复制进剪贴板
     * （对齐 Bitwarden `isAutoCopyTotpDisabled=false`）。只在回调 Activity 里现算码，
     * 不在 Intent 里传明文验证码。
     */
    val totpSecret: String? = null,
    /** 类别（影响展示与回填字段集）。 */
    val category: FillCategory,
)

/** 一次 [android.service.autofill.FillRequest] 规划结果（纯数据，无 Android 依赖）。 */
data class FillPlan(
    /** 按相关度排序的填充建议（登录在前，其次卡片/身份）。 */
    val suggestions: List<FillSuggestion>,
    /** 是否需要先解锁（无已解锁库时由 Service 改为 AUTH 回灌）。 */
    val needsUnlock: Boolean,
)
