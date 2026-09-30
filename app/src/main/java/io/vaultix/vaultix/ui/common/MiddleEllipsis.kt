/*
 * Vaultix — app:ui:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * **中间省略**（保头保尾）—— 路径 / URL / 服务器地址一类的单行文本专用。
 *
 * ## 为什么不缩字号（2026-09-30 用户问「需要分开或者缩小字号吗」）
 *
 * 缩小字号的代价是**每一眼都在付**：它是持续显示的环境信息，不是"偶尔看一眼的长文"。
 * 而且越长的串往往越出现在**小屏**上（屏幕越窄越容易溢出）⇒ 缩字号正好在小屏上缩得最狠，
 * 把可读性牺牲在最需要它的地方。**截断 + 智能省略**不改变字号，只决定"哪几个字先不显示"。
 *
 * ## 为什么不尾部省略（`TextOverflow.Ellipsis` 的默认行为）
 *
 * 因为路径的**头尾最关键、中间最不重要**：
 *
 * ```
 *   WebDAV · nas.local:5006/vaultix-dav/backup/我的密码库.kdbx
 *            └── 哪台服务器 ──┘└── 中间目录（最可省）──┘└─ 哪个文件 ─┘
 * ```
 *
 * 尾部省略会把「哪个文件」先吃掉 —— 而用户看这一行最想确认的往往正是"打开的是哪个库"。
 * ⇒ 保留**前若干字符 + 后若干字符**，中间换成 `…`。
 *
 * ## 为什么分两步（纯函数 + 测量）
 *
 * 「保留几个字符」要靠**真实测量**才知道（字符宽度不等：中文 ≈ 2× 数字）。
 * 但"怎么折"是纯逻辑 ⇒ [foldMiddle] 可单独 JVM 单测，测量那半留在 Compose 里。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.common

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow

/** 省略号（单独一个常量：折叠逻辑与单测都用它，免得两边写成不同字符）。 */
internal const val ELLIPSIS = "…"

/**
 * 把 [text] 折成"保留 [keep] 个字符（头尾各半）+ 中间一个省略号"。
 *
 * 纯函数 ⇒ 可 JVM 单测。`keep >= text.length` 时原样返回（不必绕一圈）。
 *
 * ⚠️ 头多分一个字符（`(keep + 1) / 2`）而不是尾多分：路径的**主机名/前缀**比尾段
 * 更需要完整可读（尾段通常就是文件名，少一个字符仍认得出）。
 */
internal fun foldMiddle(text: String, keep: Int): String {
    if (keep >= text.length) return text
    if (keep <= 0) return ELLIPSIS
    val headLength = (keep + 1) / 2
    val tailLength = keep - headLength
    if (tailLength == 0) return text.take(headLength) + ELLIPSIS
    return text.take(headLength) + ELLIPSIS + text.takeLast(tailLength)
}

/**
 * 单行文本，溢出时**中间**省略（保头保尾）。
 *
 * ⚠️ `overflow = TextOverflow.Clip` 是刻意的：省略已经由本函数算好了，
 * 再叠一层平台省略会在极窄宽度下变成"两个省略号"。
 */
@Composable
fun MiddleEllipsizedText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        // 用 `constraints.maxWidth`（px，Int）而不是 `maxWidth`（Dp）：后者在无界约束下是
        // `Dp.Infinity`，换算会溢出；而 px 上限本身就是 Int.MAX_VALUE，天然安全。
        val availablePx = constraints.maxWidth
        val display = remember(text, availablePx, style, measurer) {
            fitMiddle(measurer, text, availablePx.toFloat(), style)
        }
        Text(text = display, style = style, maxLines = 1, overflow = TextOverflow.Clip)
    }
}

/**
 * 二分出"最多能保留几个字符"。
 *
 * 为什么二分而不是逐字符递减：一次测量约几十微秒，长路径上百字符时逐字符会做上百次，
 * 而二分只要 ~7 次。这个函数在**滚动时会被反复调用**（重组），常数不能大。
 */
private fun fitMiddle(
    measurer: TextMeasurer,
    text: String,
    maxPx: Float,
    style: TextStyle,
): String {
    fun widthOf(candidate: String): Float =
        measurer.measure(text = AnnotatedString(candidate), style = style).size.width.toFloat()

    if (maxPx <= 0f || text.isEmpty()) return text
    if (widthOf(text) <= maxPx) return text

    var low = 0
    var high = text.length
    while (low < high) {
        val keep = (low + high + 1) / 2
        if (widthOf(foldMiddle(text, keep)) <= maxPx) low = keep else high = keep - 1
    }
    return foldMiddle(text, low)
}
