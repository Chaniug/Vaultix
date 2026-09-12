/*
 * Vaultix — app:autofill · engine
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 「解锁后回填」的整体时序对齐 Bitwarden Android 官方客户端
 * `data/autofill/util/AutofillIntentUtils.kt:109-119`
 * （`createAutofillSelectionResultIntent`：把 Dataset 经
 * `AutofillManager.EXTRA_AUTHENTICATION_RESULT` 回传）与其
 * `AutofillCipherProviderImpl` 的 `takeUnless { isVaultLocked() }` 取舍：
 *   - 库锁定时**不**给候选，只给一条认证入口；
 *   - 用户完成解锁 → 用**同一次**填充请求的目标字段构造 Dataset 回传，
 *     而不是让用户回到浏览器再点一次。
 * 本文件为独立编写。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.autofill.engine

import android.app.PendingIntent
import android.content.Context
import android.service.autofill.Dataset
import android.view.autofill.AutofillId
import io.vaultix.common.OtpUriParser
import io.vaultix.common.TotpGenerator
import io.vaultix.vaultix.R
import io.vaultix.vaultix.autofill.AutofillIntents
import io.vaultix.vaultix.autofill.model.FillCategory
import io.vaultix.vaultix.autofill.model.FillSuggestion
import io.vaultix.vaultix.autofill.model.ParsedStructure

/**
 * 「建议 → Dataset」的**唯一**实现（Service 与解锁后回灌的 [AutofillActivity] 共用）。
 *
 * 为什么必须共用：Dataset 上挂的认证意图（二次验证 / 填充后复制验证码）是**语义**，
 * 不是外观。若解锁回灌另写一份，就会出现「解锁后填进去的密码不触发验证码复制」
 * 这种只在特定路径复现的偏差 —— 而两条路径的产物本该逐字段一致
 * （与 [AutofillDatasets] 的注释同一理由）。
 */
object AutofillDatasetFactory {

    /**
     * 单条建议 → Dataset。
     *
     * 需要「先认证再回填」的两种情况：
     * - 主密码二次验证（[FillSuggestion.requiresReprompt]）→ 挂认证意图，认证后回灌；
     * - 条目带验证码且用户开了自动复制 → 走回调路径，回填后把验证码复制到剪贴板。
     */
    fun datasetFor(
        context: Context,
        parsed: ParsedStructure,
        suggestion: FillSuggestion,
        copyTotpEnabled: Boolean,
    ): Dataset? {
        val entries = AutofillDatasets.entriesFor(parsed, suggestion)
        if (entries.isEmpty()) return null
        val authIntent = when {
            suggestion.requiresReprompt -> repromptIntent(context, suggestion, entries)
            copyTotpEnabled && !suggestion.totpSecret.isNullOrBlank() ->
                copyTotpIntent(context, suggestion, entries)
            else -> null
        }
        return AutofillDatasets.build(
            context = context,
            entries = entries,
            title = suggestion.title,
            subtitle = suggestion.subtitle,
            datasetId = suggestion.id,
            authIntent = authIntent,
            // 图标随条目类别（登录 = 地球 / 银行卡 = 卡片 / 身份 = 人像），
            // 对齐 Bitwarden `AutofillCipher.iconRes` —— 面板里一行一图标才分得清类型。
            iconRes = AutofillDatasets.iconFor(suggestion.category),
        )
    }

    private fun repromptIntent(
        context: Context,
        suggestion: FillSuggestion,
        entries: List<Pair<AutofillId, String>>,
    ): PendingIntent = AutofillIntents.pending(
        context = context,
        intent = AutofillIntents.create(
            context = context,
            mode = AutofillIntents.MODE_REPROMPT,
            title = suggestion.title,
            subtitle = suggestion.subtitle,
            datasetId = suggestion.id,
            entries = entries,
            category = suggestion.category,
        ),
        requestCode = suggestion.id.hashCode(),
    )

    private fun copyTotpIntent(
        context: Context,
        suggestion: FillSuggestion,
        entries: List<Pair<AutofillId, String>>,
    ): PendingIntent = AutofillIntents.pending(
        context = context,
        intent = AutofillIntents.create(
            context = context,
            mode = AutofillIntents.MODE_COPY_TOTP,
            title = suggestion.title,
            subtitle = suggestion.subtitle,
            datasetId = suggestion.id,
            entries = entries,
            totpSecret = suggestion.totpSecret,
            category = suggestion.category,
        ),
        requestCode = suggestion.id.hashCode(),
    )

    /**
     * 卡片 / 身份建议 → Dataset。
     *
     * 这两类**不做**自动复制验证码 / 二次验证（与 Service 侧同款取舍）：它们的字段语义
     * 与登录不同，认证意图挂上去只会多一次弹窗而没有对应副作用。
     */
    fun plainDatasetFor(
        context: Context,
        parsed: ParsedStructure,
        suggestion: FillSuggestion,
    ): Dataset? = datasetFor(context, parsed, suggestion, copyTotpEnabled = false)

    /** TOTP 密钥 → 当前验证码；解析或计算失败返回 null（不阻塞账号密码填充）。 */
    fun totpCode(raw: String): String? = runCatching {
        val config = OtpUriParser.parse(raw) ?: return null
        TotpGenerator.generate(config)
    }.getOrNull()

    /** 类别 → 面板图标（转发 [AutofillDatasets.iconFor]；调用方少一个 import）。 */
    fun iconFor(category: FillCategory): Int = AutofillDatasets.iconFor(category)

    /** 兜底图标：类别缺失时统一按登录（与 [AutofillIntents.create] 的默认一致）。 */
    val defaultIconRes: Int = R.drawable.ic_autofill_login
}
