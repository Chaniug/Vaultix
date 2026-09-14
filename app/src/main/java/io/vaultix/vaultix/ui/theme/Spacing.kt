/*
 * Vaultix — app:ui · theme
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 本文件是**原创实现**，不搬运任何上游代码。设计依据为公开的 Material Design 3
 * 规范（m3.material.io）中「Expressive 表达力布局系统」提出的 **8dp 间距标尺**
 * （2026-05-19 Google I/O 发布），此处只采纳其**标尺数值**，未复用任何源码。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * **8dp 间距标尺**（M3 Expressive 的「理性的一半」）。
 *
 * ## 为什么要有这个文件
 *
 * 之前全 App 有 **539 处**手写 `.dp` 字面量（`app/src/main`），没有任何标尺约束
 * ⇒ 数值全靠手感 ⇒ 反复出现「两个元素隔太近」「这里多一条缝」这类
 * **改了又改、每次都要真机看**的问题。可量化的一条佐证：
 * 2026-09-14 的「顶栏标题与右侧三个图标隔太近」，根因是 `ACTIONS_RESERVE = 144.dp`
 * 恰好等于胶囊宽度、**余量为 0** —— 典型的"数值没有标尺"。
 *
 * ## 用法
 *
 * ```kotlin
 * Spacer(Modifier.height(Spacing.md))          // 而不是 12.dp
 * Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) { ... }
 * ```
 *
 * ## 纪律（**改这个文件前必读**）
 *
 * 1. **只对齐，不顺手改版式**：把 `12.dp` 换成 `Spacing.md` 必须是**等价替换**。
 *    想改间距是"设计变更"，应单独一轮做并单独说明，不要混在"建标尺"里 ——
 *    否则一旦观感变了，无法区分是标尺的锅还是改版的锅。
 * 2. **不在标尺上的值保持字面量**：`18dp / 6dp / 48dp / 72dp` 这类不强行对齐，
 *    对齐它们会改变既有版式（违反第 1 条）。等这些值各自被设计时再决定。
 * 3. **`0.dp` 不进标尺**：它是"没有间距"这个语义本身，写成 `Spacing.zero` 反而绕。
 * 4. **新增档位前先想能否用现有档位** —— 标尺的价值在于档位**少**，
 *    每加一档就削弱一次"不用做选择"的收益。
 *
 * 采纳范围的来龙去脉见 `.ai/conventions/8.8-M3Expressive-采纳范围与顺序.md` §4 P0-1。
 */
@Immutable
object Spacing {
    /** 4dp —— 同一元素内部的极小间隙（如文字与它上方的标签）。 */
    val xs: Dp = 4.dp

    /** 8dp —— 相关元素之间的标准间隙（同组内的两行、图标与文字的最小呼吸）。 */
    val sm: Dp = 8.dp

    /** 12dp —— 中等间隙（卡片内边距、相关分组之间）。 */
    val md: Dp = 12.dp

    /** 16dp —— 屏幕边缘留白（**最常用**，内容距屏幕左右两侧）。 */
    val lg: Dp = 16.dp

    /** 24dp —— 大间隙（分区之间、对话框内边距）。 */
    val xl: Dp = 24.dp

    /** 32dp —— 特大间隙（页面底部的收尾留白）。 */
    val xxl: Dp = 32.dp
}
