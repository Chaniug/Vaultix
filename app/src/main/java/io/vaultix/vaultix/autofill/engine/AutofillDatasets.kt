/*
 * Vaultix — app:autofill · engine
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * Dataset / FillResponse 组装（Android 框架层）。Service 与认证回灌的 AutofillActivity
 * 共用本文件的 Dataset 构造，保证两条路径产出的条目外观与字段完全一致。
 */
package io.vaultix.vaultix.autofill.engine

import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import android.service.autofill.Presentations
import android.service.autofill.SaveInfo
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import io.vaultix.vaultix.R
import io.vaultix.vaultix.autofill.model.FieldHint
import io.vaultix.vaultix.autofill.model.FillPlan
import io.vaultix.vaultix.autofill.model.FillSuggestion
import io.vaultix.vaultix.autofill.model.ParsedStructure
import io.vaultix.vaultix.autofill.match.AutofillFillTargetPolicy

/** 自动填充面板条目的构造与展示（RemoteViews，由系统渲染）。 */
object AutofillDatasets {

    /** 下拉 / 内联面板里的单条建议视图。 */
    fun presentation(context: Context, title: String, subtitle: String): RemoteViews =
        RemoteViews(context.packageName, R.layout.autofill_dataset_item).apply {
            setTextViewText(R.id.autofill_item_title, title)
            setTextViewText(R.id.autofill_item_subtitle, subtitle)
        }

    /**
     * 组装一个 Dataset：[entries] 为「目标字段 → 待填值」，为空表示无可填字段（返回 null）。
     * [authIntent] 非空时该条目需先认证（主密码二次验证）再回填。
     */
    fun build(
        context: Context,
        entries: List<Pair<AutofillId, String>>,
        title: String,
        subtitle: String,
        datasetId: String? = null,
        authIntent: PendingIntent? = null,
    ): Dataset? {
        if (entries.isEmpty()) return null
        val builder = datasetBuilder(presentation(context, title, subtitle))
        entries.forEach { (id, value) -> builder.setValue(id, AutofillValue.forText(value)) }
        datasetId?.let { builder.setId(it) }
        // 框架要求 IntentSender（Dataset 级认证）
        authIntent?.let { builder.setAuthentication(it.intentSender) }
        return builder.build()
    }

    /**
     * Android 13（API 33）起菜单呈现要走 [Presentations]；低版本沿用单参构造。
     * 两者都是「下拉菜单」形态，键盘内联建议（InlinePresentation）见后续里程碑。
     */
    private fun datasetBuilder(presentation: RemoteViews): Dataset.Builder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Dataset.Builder(Presentations.Builder().setMenuPresentation(presentation).build())
        } else {
            @Suppress("DEPRECATION")
            Dataset.Builder(presentation)
        }

    /** 把建议映射到页面真实存在的字段上（无对应字段的语义直接丢弃）。 */
    fun entriesFor(parsed: ParsedStructure, suggestion: FillSuggestion): List<Pair<AutofillId, String>> {
        val result = mutableListOf<Pair<AutofillId, String>>()
        for ((hint, value) in suggestion.fields) {
            if (value.isEmpty()) continue
            targetIdsFor(parsed, hint).forEach { result += it to value }
        }
        return result
    }

    /** 该语义在当前页面可回填的目标字段（不可见字段不填）。 */
    fun targetIdsFor(parsed: ParsedStructure, hint: FieldHint): List<AutofillId> =
        parsed.fields.filter { it.isVisible && it.hint == hint }.map { it.id }

    /**
     * 页面上**值得填充**的字段 id（用于 FillResponse 的整体认证回灌）。
     *
     * ⚠️ 必须走 [AutofillFillTargetPolicy]，**不能**退回「全部可见字段」——
     * 后者会让搜索框这类非凭据字段也拿到认证入口，正是「填充 UI 到处误弹」的根因；
     * 且返回空数组会让上层直接不响应（对齐 Bitwarden 的 `Unfillable → null`）。
     */
    fun allFillableIds(parsed: ParsedStructure): Array<AutofillId> =
        AutofillFillTargetPolicy.fillTargets(parsed).map { it.id }.toTypedArray()

    /**
     * 无建议时的占位：整表认证，点击后交给 Activity 引导解锁 / 搜索。
     *
     * ⚠️ [saveInfo] 必须照样挂上：**没有匹配项恰恰是最需要保存的场景**
     * （用户第一次在某站点注册/登录，库里没有条目）——漏了就永远收不到
     * `onSaveRequest`，保存流程形同虚设。
     */
    fun buildFallback(
        context: Context,
        ids: Array<AutofillId>,
        authIntent: PendingIntent,
        title: String,
        subtitle: String,
        saveInfo: SaveInfo? = null,
    ): FillResponse? {
        if (ids.isEmpty()) return null
        val presentation = presentation(context, title, subtitle)
        val builder = FillResponse.Builder()
        saveInfo?.let { builder.setSaveInfo(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            builder.setAuthentication(
                ids,
                authIntent.intentSender,
                Presentations.Builder().setMenuPresentation(presentation).build(),
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setAuthentication(ids, authIntent.intentSender, presentation)
        }
        return builder.build()
    }
}
