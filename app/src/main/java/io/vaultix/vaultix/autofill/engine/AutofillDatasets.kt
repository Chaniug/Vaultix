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
import android.content.res.Configuration
import android.os.Build
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import android.service.autofill.Presentations
import android.service.autofill.SaveInfo
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import androidx.annotation.DrawableRes
import io.vaultix.vaultix.R
import io.vaultix.vaultix.autofill.model.FieldHint
import io.vaultix.vaultix.autofill.model.FillCategory
import io.vaultix.vaultix.autofill.model.FillPlan
import io.vaultix.vaultix.autofill.model.FillSuggestion
import io.vaultix.vaultix.autofill.match.AutofillFillTargetPolicy
import io.vaultix.vaultix.autofill.match.UriMatcher
import io.vaultix.vaultix.autofill.model.ParsedStructure

/**
 * 图标前景色（亮 / 暗），对齐 Bitwarden `Context.iconTint` ——
 * 上游给自己的图标**显式上色**（取自身色板的 icon.primary），而不是把颜色留给系统面板。
 * 这里取 Material3 基线色板的 `onSurfaceVariant`：亮色 #44474E / 暗色 #C4C6CF，
 * 与系统面板正文同族，既不会在浅色面板里糊掉，也不会在深色面板里刺眼。
 */
private const val LIGHT_ICON_TINT: Long = 0xFF44474EL
private const val DARK_ICON_TINT: Long = 0xFFC4C6CFL

/** 自动填充面板条目的构造与展示（RemoteViews，由系统渲染）。 */
object AutofillDatasets {

    /**
     * 下拉 / 内联面板里的单条建议视图。
     *
     * 观感规格对齐 Bitwarden `autofill_remote_view.xml`（2026-09-12 第二轮修正）：
     * **左侧 20dp 单色小图标** + 标题 / 副标题两行。此前的 40dp 彩色启动图标在系统
     * 填充面板里又大又花（用户反馈「图标太丑」），现已换成语义化单色矢量：
     * 登录 = 地球、银行卡 = 卡片、身份 = 人像、整表认证行 = [R.drawable.ic_autofill_vaultix]。
     *
     * @param iconRes 条目标图标；默认登录（见 [iconFor]）。
     * @param tintIcon 是否用前景色上色。品牌标识（[R.drawable.ic_autofill_vaultix]）传 false，
     *   否则会被系统面板前景色冲成一块纯色，失去识别度（对齐上游 `shouldTintIcon`）。
     */
    fun presentation(
        context: Context,
        title: String,
        subtitle: String,
        @DrawableRes iconRes: Int = R.drawable.ic_autofill_login,
        tintIcon: Boolean = true,
    ): RemoteViews = RemoteViews(context.packageName, R.layout.autofill_dataset_item).apply {
        setImageViewResource(R.id.autofill_item_icon, iconRes)
        setTextViewText(R.id.autofill_item_title, title)
        setTextViewText(R.id.autofill_item_subtitle, subtitle)
        if (tintIcon) {
            setInt(R.id.autofill_item_icon, "setColorFilter", iconTint(context))
        }
    }

    /** 建议类别 → 条目标图标（对齐 Bitwarden `AutofillCipher.iconRes`）。 */
    @DrawableRes
    fun iconFor(category: FillCategory): Int = when (category) {
        FillCategory.LOGIN -> R.drawable.ic_autofill_login
        FillCategory.CARD -> R.drawable.ic_autofill_card
        FillCategory.IDENTITY -> R.drawable.ic_autofill_identity
    }

    /** 亮 / 暗模式下的图标前景色（系统面板的明暗由系统配置决定，不是 App 主题）。 */
    private fun iconTint(context: Context): Int {
        val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return if (night == Configuration.UI_MODE_NIGHT_YES) {
            DARK_ICON_TINT.toInt()
        } else {
            LIGHT_ICON_TINT.toInt()
        }
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
        @DrawableRes iconRes: Int = R.drawable.ic_autofill_login,
    ): Dataset? {
        if (entries.isEmpty()) return null
        val builder = datasetBuilder(presentation(context, title, subtitle, iconRes))
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

    /**
     * 把建议映射到页面真实存在的字段上（无对应字段的语义直接丢弃）。
     *
     * 邮箱框额外要求值**本身像邮箱** —— 对齐 Bitwarden `fillLoginPartition` 的
     * `if (!autofillCipher.username.trim().isValidEmail()) return null`：
     * 用手机号 / 昵称当账号的条目，不该把那个值塞进邮箱框。
     */
    fun entriesFor(parsed: ParsedStructure, suggestion: FillSuggestion): List<Pair<AutofillId, String>> =
        suggestion.fields
            .filter { (hint, value) -> value.isNotEmpty() && isFillableValue(hint, value) }
            .flatMap { (hint, value) -> targetIdsFor(parsed, hint).map { it to value } }

    /** 值的形态是否适合填进该语义的字段（见 [entriesFor] 关于邮箱的说明）。 */
    private fun isFillableValue(hint: FieldHint, value: String): Boolean =
        hint != FieldHint.EMAIL_ADDRESS || isEmailLike(value)

    /**
     * 该语义在当前页面可回填的目标字段。
     *
     * 三条过滤（对齐 Bitwarden 填充侧的精确性要求）：
     * - 不可见字段不填；
     * - **逐字段站点校验**：字段自身所属域名与本次填充站点不同源时不填 ——
     *   页面里嵌入第三方 iframe（外挂登录 / 支付组件）时，避免把本站账号填进别家的框
     *   （对齐 Bitwarden `autofillView.data.website == autofillCipher.website`）；
     * - 原生 App 字段没有 webDomain，[UriMatcher.sameSite] 会放行，不影响 App 内填充。
     */
    fun targetIdsFor(parsed: ParsedStructure, hint: FieldHint): List<AutofillId> {
        val pageDomain = parsed.webDomain ?: parsed.fallbackWebDomain
        return parsed.fields
            .filter { it.isVisible && it.hint == hint && UriMatcher.sameSite(it.webDomain, pageDomain) }
            .map { it.id }
    }

    /**
     * 值是否像邮箱（对齐 Bitwarden 用 `isValidEmail()` 守住 Email 字段的意图）。
     *
     * 刻意保持宽松：只挡住明显不是邮箱的值（无 `@`、`@` 在首尾、有空白、多个 `@`），
     * 不做完整 RFC 校验 —— 宁可放过个别畸形地址，也不要漏填合法的罕见形态。
     */
    internal fun isEmailLike(value: String): Boolean {
        val v = value.trim()
        val at = v.indexOf('@')
        return at > 0 && at < v.length - 1 && v.lastIndexOf('@') == at && v.none { it.isWhitespace() }
    }

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
        // 整表认证行 = Vaultix 自身入口（解锁 / 搜索）→ 用品牌盾牌，且**不上色**
        //（对齐 Bitwarden 该处用 logo_bitwarden_icon + shouldTintIcon = false）。
        val presentation = presentation(
            context = context,
            title = title,
            subtitle = subtitle,
            iconRes = R.drawable.ic_autofill_vaultix,
            tintIcon = false,
        )
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
