/*
 * Vaultix — app:autofill · engine
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 为什么需要本文件（2026-09-13）
 * 用户反馈「填充时候的密码条目框……没有独立的 logo 个性展示」。此前面板里用的是
 * **按类别固定的单色矢量**（登录 = 地球 / 卡片 = 卡片 / 身份 = 人像）：十条 GitHub 条目
 * 长得一模一样，扫一眼分不出谁是谁。
 *
 * 对齐 Bitwarden：其填充面板展示的是**站点图标**（`icon.png`），取不到才回退首字母方块。
 * 但 RemoteViews 由**系统进程**渲染，既拿不到 Compose、也不能发起网络请求，
 * 因此这里走「本地可得优先」的次序：
 *   1. Coil **磁盘缓存**里已有该站点图标（用户在 Vaultix 里翻过列表就命中）→ 用它；
 *   2. 否则**本地绘制**字母头像（按标题哈希取底色 + 首字母）→ 每条都有自己的颜色与字。
 * 全程同步、零网络、零阻塞 —— 填充响应有系统超时，任何 await 都是在赌用户体验。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.autofill.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import coil.imageLoader
import io.vaultix.vaultix.autofill.model.FillCategory
import kotlin.math.abs
import kotlin.math.roundToInt

/** 面板里的图标直径（dp）——与 `autofill_dataset_item.xml` 的 ImageView 尺寸对齐。 */
private const val ICON_DP = 24f

/** 字母头像的字号占比（相对直径）。 */
private const val LETTER_SCALE = 0.46f

/** 站点图标多为透明 PNG：垫白底，避免深色面板下深色描边糊成一团（同 Bitwarden）。 */
private const val ICON_BACKDROP = Color.WHITE

/**
 * 字母头像的底色池（M3 tonal palette 的中低明度色，白字均能保证对比度）。
 * 按标题哈希取模 —— 同一条目在任何一次填充里颜色都稳定。
 */
private val AVATAR_COLORS = intArrayOf(
    0xFF6750A4.toInt(),
    0xFF00639B.toInt(),
    0xFF006D3B.toInt(),
    0xFF8F5000.toInt(),
    0xFFB3261E.toInt(),
    0xFF5B5B92.toInt(),
    0xFF00696E.toInt(),
    0xFF7A4A00.toInt(),
)

/** 卡片类别固定蓝绿（与「银行卡」语义一致，不参与哈希）。 */
private const val CARD_COLOR = 0xFF0E5A8A.toInt()

/** 身份类别固定砖红（与「人像」语义一致，不参与哈希）。 */
private const val IDENTITY_COLOR = 0xFF7D3C4A.toInt()

/**
 * 填充面板条目的图标位图（同步、本地）。
 *
 * @param iconUrl 站点图标 URL（`<服务器>/icons/<域名>/icon.png`）；为 null 直接走字母头像。
 * @param title 条目标题（取首字母）。
 * @param category 类别（卡片 / 身份用固定色，登录按标题哈希）。
 */
fun createAutofillItemIcon(
    context: Context,
    iconUrl: String?,
    title: String,
    category: FillCategory,
): Bitmap {
    val size = (ICON_DP * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
    val cached = iconUrl?.let { readCachedIcon(context, it) }
    return if (cached != null) {
        circular(cached, size)
    } else {
        letterAvatar(title, category, size)
    }
}

/**
 * 从 Coil **磁盘缓存**里同步取图标位图；未命中 / 任何异常都返回 null（走字母头像）。
 *
 * ⚠️ 只查缓存，**不发起网络请求**：填充响应卡在系统超时上，为一枚 24dp 图标去联网不值。
 */
private fun readCachedIcon(context: Context, url: String): Bitmap? = runCatching {
    val snapshot = context.imageLoader.diskCache?.openSnapshot(url) ?: return null
    snapshot.use { BitmapFactory.decodeFile(it.data.toFile().absolutePath) }
}.getOrNull()

/** 把任意尺寸图标裁成圆形（居中裁剪，垫 [ICON_BACKDROP] 底）。 */
private fun circular(src: Bitmap, size: Int): Bitmap {
    val out = createBitmap(size, size)
    val canvas = Canvas(out)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val radius = size / 2f
    paint.color = ICON_BACKDROP
    canvas.drawCircle(radius, radius, radius, paint)

    val path = Path().apply { addOval(RectF(0f, 0f, size.toFloat(), size.toFloat()), Path.Direction.CW) }
    canvas.save()
    canvas.clipPath(path)
    // 居中裁剪（CENTER_CROP）：按较大边等比缩放，避免图标被拉扁。
    val scale = maxOf(size.toFloat() / src.width, size.toFloat() / src.height)
    val w = src.width * scale
    val h = src.height * scale
    val dst = RectF((size - w) / 2, (size - h) / 2, (size + w) / 2, (size + h) / 2)
    canvas.drawBitmap(src, null, dst, paint)
    canvas.restore()
    return out
}

/** 本地绘制的字母头像：圆底 + 白色首字母（RemoteViews 里最可靠的「个性 logo」）。 */
private fun letterAvatar(title: String, category: FillCategory, size: Int): Bitmap {
    val out = createBitmap(size, size)
    val canvas = Canvas(out)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val radius = size / 2f
    paint.color = avatarColor(title, category)
    canvas.drawCircle(radius, radius, radius, paint)

    val letter = title.trim().firstOrNull()?.uppercaseChar()?.toString()
        ?: fallbackLetter(category)
    paint.color = Color.WHITE
    paint.textAlign = Paint.Align.CENTER
    paint.textSize = size * LETTER_SCALE
    paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val baseline = radius - (paint.descent() + paint.ascent()) / 2
    canvas.drawText(letter, radius, baseline, paint)
    return out
}

private fun avatarColor(title: String, category: FillCategory): Int = when (category) {
    FillCategory.CARD -> CARD_COLOR
    FillCategory.IDENTITY -> IDENTITY_COLOR
    FillCategory.LOGIN -> AVATAR_COLORS[abs(title.hashCode()) % AVATAR_COLORS.size]
}

private fun fallbackLetter(category: FillCategory): String = when (category) {
    FillCategory.CARD -> "C"
    FillCategory.IDENTITY -> "I"
    FillCategory.LOGIN -> "?"
}
