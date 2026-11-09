/*
 * Vaultix — app:ui:theme
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 全项目**唯一**的字阶来源。
 *
 * ## 为什么必须有这个文件（2026-11-09）
 *
 * `Theme.kt` 此前只传了 `colorScheme` 与 `shapes`，**没有 `typography`** ——
 * 全 App 吃的是 M3 的默认值，而那套值是**为拉丁文设计的**。中文在三个维度上都被它坑：
 *
 * | 维度 | M3 默认 | 中文的实际后果 |
 * |---|---|---|
 * | **字距** | `bodyLarge` 0.5sp、`bodyMedium` 0.25sp、`labelMedium` 0.5sp | 拉丁文靠字距提升可读性；中文加字距会「漏风」，整段松散 |
 * | **行高** | `bodyMedium` 14/20（1.43）、`bodySmall` 12/16（1.33） | 中文没有 x-height，字面占满 1em ⇒ 1.43 偏挤 |
 * | **字重** | 标题 500 | 与本文无关，但说明"默认值不是没有设计" |
 *
 * 代价是**每处都要就地手调**：`EntryCard.kt` 里 `titleMedium` 的行高被单独收到 21sp，
 * 而表单字段名在另一处收到 14sp —— 同一件事两种补法，且下一个人不知道还有哪些地方该补。
 * 有了这份定义，"中文行高多少"只在这里回答一次。
 *
 * ## ⚠️ 本文件**只做中文适配**，不做"重新设计"
 *
 * 三条纪律，改动前必读：
 *
 * 1. **不改任何现有元素的字号**。项目已按 `8.8 §5`「不采纳强调排印」定过调
 *    （顶栏标题刚从 32sp 收到 26sp），这里再放大字号就是往回走。
 * 2. **只调三项**：body 系列行高 +2sp、全部字距归零、其余照抄 M3 默认值。
 *    "顺手把标题加粗一点"会让全 App 的层级同时变化，无法归因。
 * 3. **局部覆盖仍然合法**：像 `EntryCardTitleLineHeight`（21sp）这种"因为图标 40dp 要垂直居中"
 *    的调整，是**版式需要**，不是字阶问题 —— 它继续留在组件里，不要搬进来。
 *
 * ## 为什么字距写成 `0.sp` 而不是删掉这一行
 *
 * `TextStyle` 的 `letterSpacing` 是 `TextUnit.Unspecified` 时继承默认值（0）。
 * 显式写 0 是为了让"这里刻意归零"在 diff 里可见 —— 否则下一个人以为漏写了。
 */
internal val VaultixTypography = Typography(
    // ---- Display：仅解锁页 / 空态大标题，字距沿用 M3（负值是为拉丁文视觉平衡，中文几乎用不到）----
    displayLarge = TextStyle(
        fontSize = 57.sp,
        lineHeight = 64.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = (-0.25).sp,
    ),
    displayMedium = TextStyle(
        fontSize = 45.sp,
        lineHeight = 52.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
    ),
    displaySmall = TextStyle(
        fontSize = 36.sp,
        lineHeight = 44.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
    ),

    // ---- Headline：页面级标题 ----
    headlineLarge = TextStyle(
        fontSize = 32.sp,
        lineHeight = 40.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
    ),
    headlineMedium = TextStyle(
        fontSize = 28.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
    ),
    headlineSmall = TextStyle(
        fontSize = 24.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
    ),

    // ---- Title：条目名 / 卡片标题 / 分区标题。字重保持 M3 的 500（titleLarge 是 400）----
    titleLarge = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
    ),

    // ---- Body：正文。**行高 +2sp 是这份文件的主要目的** ----
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        // 24 保持（1.5 倍对中文已够），只把字距 0.5 → 0
        lineHeight = 24.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        // 20 → 22：1.43 倍在中文里偏挤（没有 x-height 的视觉补偿）
        lineHeight = 22.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        // 16 → 18：1.33 倍是这套字阶里最挤的一档
        lineHeight = 18.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
    ),

    // ---- Label：按钮 / 徽章 / 字段名。字距全部归零（M3 给的是 0.1~0.5）----
    labelLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
    ),
)
