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
import android.graphics.Bitmap
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

/**
 * Dataset 图标来源（#308）：把互斥的「URL 现算」与「预生成位图」合并为单一参数，
 * 既让 `build()` 参数数守住 detekt ≤8 硬上限，也表达二者的互斥语义。
 */
sealed interface IconSource {
    /** 主线程现算：查 Coil 磁盘缓存，未命中画字母头像（旧行为，兼容后台调用方）。 */
    data class FromUrl(val url: String?) : IconSource

    /** 后台已算好位图：主线程组装时直接用，不再碰磁盘（#308 推荐路径）。 */
    data class Ready(val bitmap: Bitmap) : IconSource

    /** 无图标：按标题画字母头像。 */
    data object None : IconSource
}

/** 自动填充面板条目的构造与展示（RemoteViews，由系统渲染）。 */
object AutofillDatasets {

    /**
     * 下拉 / 内联面板里的单条建议视图。
     *
     * 观感规格对齐 Bitwarden `autofill_remote_view.xml`（2026-09-12 第二轮修正）：
     * **左侧 20dp 单色小图标** + 标题 / 副标题两行。此前的 40dp 彩色启动图标在系统
     * 填充面板里又大又花（用户反馈「图标太丑」），现已换成语义化单色矢量：
     * 登录 = 地球、银行卡 = 卡片、身份 = 人像、整表认证行 = [R.drawable.ic_autofill_vaultix]
     * （Vaultix 自身标识，折纸 V 单色剪影，与桌面启动图标同源）。
     *
     * @param iconRes 条目标图标；默认登录（见 [iconFor]）。
     * @param tintIcon 是否用前景色上色。品牌标识同样传 true：折纸 V 是纯色剪影，
     *   染上面板前景色才能同时适配浅/深面板（此前传 false 并用固定黑，深色面板会糊）。
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
        // 无障碍：整行是一块可点区域，读屏需要一句完整描述（对齐上游
        // `BitwardenRemoteViews` 给 container 设 contentDescription 的做法）。
        // ⚠️ 挂在 **wrapper** 上而不是圆角卡片上：2026-11-09 起卡片外面还有一层
        //    透明 padding（行距），系统把整行当一个可聚焦项，描述必须覆盖整行。
        setContentDescription(
            R.id.autofill_item_wrapper,
            listOf(title, subtitle).filter { it.isNotBlank() }.joinToString("，"),
        )
    }

    /**
     * 同上，但图标是**位图**（站点图标 / 字母头像，见 [createAutofillItemIcon]）。
     *
     * 条目建议一律走这条：十条 GitHub 条目若都显示同一个「地球」图标，
     * 用户扫一眼根本分不出谁是谁（用户反馈「没有独立的 logo 个性展示」）。
     */
    fun presentation(
        context: Context,
        title: String,
        subtitle: String,
        icon: Bitmap,
    ): RemoteViews = RemoteViews(context.packageName, R.layout.autofill_dataset_item).apply {
        setImageViewBitmap(R.id.autofill_item_icon, icon)
        setTextViewText(R.id.autofill_item_title, title)
        setTextViewText(R.id.autofill_item_subtitle, subtitle)
        // 无障碍：整行是一块可点区域，读屏需要一句完整描述（对齐上游
        // `BitwardenRemoteViews` 给 container 设 contentDescription 的做法）。
        // ⚠️ 挂在 **wrapper** 上而不是圆角卡片上：2026-11-09 起卡片外面还有一层
        //    透明 padding（行距），系统把整行当一个可聚焦项，描述必须覆盖整行。
        setContentDescription(
            R.id.autofill_item_wrapper,
            listOf(title, subtitle).filter { it.isNotBlank() }.joinToString("，"),
        )
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
     * 填充面板顶部的**品牌头**（`FillResponse.Builder.setHeader`）。
     *
     * 只做一件事：让用户确认"这些条目是 Vaultix 给的"。面板里每一行都是
     * 「图标 + 标题 + 副标题」的通用造型，十几家密码管理器长得一模一样，
     * 没有品牌头时用户无从分辨来源（尤其在国产 ROM 的纯黑面板上，外人看着
     * 就像输入法自带的东西）。
     *
     * ⚠️ **不可点击**（`setHeader` 的 RemoteViews 文档明确写 dead）：
     * 所以这里没有「管理 / 打开 App」入口。想给入口只能另加一个 dataset，
     * 而 dataset 一旦被点就会尝试填充 —— 会在密码框里填进脏值，不做。
     *
     * @param count 本次面板里的建议条数；<= 0 时返回 null（不画头）。
     */
    fun header(context: Context, count: Int): RemoteViews? {
        if (count <= 0) return null
        return RemoteViews(context.packageName, R.layout.autofill_header).apply {
            setTextViewText(R.id.autofill_header_count, context.getString(R.string.autofill_header_count, count))
            // 品牌矢量是纯色剪影，必须按面板明暗上色；用与副标题同档的前景色。
            setInt(R.id.autofill_header_icon, "setColorFilter", iconTint(context))
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
        category: FillCategory = FillCategory.LOGIN,
        /**
         * 图标来源（#308）：[IconSource.Ready] 用后台预生成的位图、主线程零 I/O；
         * [IconSource.FromUrl] 维持旧的现算行为（兼容仍在后台调用的路径）。
         */
        icon: IconSource = IconSource.None,
    ): Dataset? {
        if (entries.isEmpty()) return null
        val iconBitmap = when (icon) {
            is IconSource.Ready -> icon.bitmap
            is IconSource.FromUrl -> createAutofillItemIcon(context, icon.url, title, category)
            IconSource.None -> createAutofillItemIcon(context, null, title, category)
        }
        val builder = datasetBuilder(presentation(context, title, subtitle, iconBitmap))
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
        // 整表认证行 = Vaultix 自身入口（解锁 / 搜索）→ 用品牌折纸 V 剪影，
        // 并接上面板明暗 tint（浅 #44474E / 深 #C4C6CF），与 header 同一套着色。
        val presentation = presentation(
            context = context,
            title = title,
            subtitle = subtitle,
            iconRes = R.drawable.ic_autofill_vaultix,
            tintIcon = true,
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
