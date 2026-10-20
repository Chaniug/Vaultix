/*
 * Vaultix — app:ui:theme
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 本文件是**原创实现**。数值来自 Material Design 3 的 icon size 与 touch target 规格
 * （公开的 m3.material.io 文档），未复用任何上游源码。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * **尺寸语义常量**：`Spacing` 管"间距"，[VaultixCorner] 管"圆角"，本文件管"**元件有多大**"。
 *
 * ## 为什么要有第四张表（2026-10-19 审计 §2.2 / §3.7 的整改）
 *
 * `Spacing` 的纪律第 2 条写的是「不在标尺上的值保持字面量」—— 那条纪律**本身没错**，
 * 但它只在"每个字面量都是独立的版式决定"时成立。实际不是：审计扫出来 36 个不同的
 * `.dp` 值里有 5 个**已经事实上成档**了：
 *
 * | 值 | 改前出现 | 实际含义 |
 * |---|---|---|
 * | `18.dp` | 26 次 / 12 文件 | 行内小图标（文本按钮的图标、字段尾部的复制/眼睛） |
 * | `20.dp` | 18 次 / 11 文件 | 比 18 大一档的小图标 + 小号的忙碌转圈 + 一处行内内边距 |
 * | `6.dp` | 19 次 | 细缝：徽章内边距、行内上下留白、底栏上边距 |
 * | `2.dp` | 19 次 | 极细缝：两行字之间、转圈描边 |
 * | `10.dp` | 6 次 | 列表行上下内边距（介于 8 与 12 之间） |
 *
 * 这些值散落成字面量时，**没人知道它们是"同一件事"** —— 于是
 * §3.7 描述的现象出现了：「图标尺寸 `18.dp` 散落 9 个文件，无一处收敛」，
 * 而 `AddCloudVaultScreen` / `CloudAccountsScreen` 各自偷偷建了一个
 * `INLINE_ICON = 18.dp`（同一个值，两个名字，第三个人还得再建一个）。
 *
 * ## 与 `Spacing` 的分工（**别混用**）
 *
 * ```
 * Spacing.lg      = 16.dp   两个元素「之间」留多少
 * UiSize.InlineIcon = 18.dp 元素自己「有多大」
 * ```
 *
 * 尺寸值恰好等于某个 `Spacing` 档位时（如 `CapabilityIconSize = Spacing.lg`），
 * **优先用 `Spacing`** —— 那种情况下"它多大"与"它占多宽的格子"是同一件事，不必分家。
 *
 * ## 纪律
 *
 * 1. **只收"已经成档"的值**。本文件不是随手放常量的大筐：一个值必须先在上表里
 *    证明"同一个含义反复出现"，才配进来。加新档位前先问：它是不是现有某一档的
 *    另一种说法？
 * 2. **18 与 20 并存是历史事实，本轮不合并**。它们不是"同一个值写错了两遍"：
 *    18dp 用在**文本按钮内**（`TextButton` 的默认图标槽就是 18dp），
 *    20dp 用在**标题旁 / 独立行 / 勾选标记**（视觉上需要比正文粗一档）。
 *    强行合并成 18 会让后者显小、合并成 20 会让前者把按钮顶高 —— 都是版式变更。
 *    ⇒ 判据是**它和谁并排**：并排的是 `bodyMedium`/`labelLarge` 正文 ⇒ 18；
 *      并排的是 `titleMedium` 或作为独立图形 ⇒ 20。
 * 3. **触摸目标不是尺寸**。[TouchTarget] 只用于 `minimumInteractiveComponentSize()`
 *    或自绘 `clickable` 容器，**不要**拿它去 `size(...)` 一个图标 —— 图标会被撑大。
 */
object UiSize {
    /**
     * **行内小图标：18dp**。文本按钮里的图标、字段行尾的复制 / 眼睛 / 打开。
     *
     * 与 `TextButton` 默认图标槽同值，所以放进按钮里**不会改变按钮高度**。
     * 并排对象是正文（`bodyMedium` / `labelLarge`）。
     */
    val InlineIcon: Dp = 18.dp

    /**
     * **次级小图标：20dp**。标题旁的箭头、选中勾、拖动手柄、独立的小图形。
     *
     * 并排对象是 `titleMedium` 或作为独立图形出现。见 KDoc 纪律 2 的判据。
     */
    val SecondaryIcon: Dp = 20.dp

    /**
     * **极小图标：16dp**。目前只有条目卡上的「只读」角标。
     *
     * ⚠️ 这一档**不要扩散**：16dp 在 1x 屏上已经接近"看不出形状"，只适合
     * 与文字紧贴、且语义由文字兜底的场景。新场景请优先用 [InlineIcon]。
     */
    val MicroIcon: Dp = 16.dp

    /**
     * **行内小转圈：18dp**。按钮里 / 表单字段尾部的 `CircularProgressIndicator`。
     *
     * 与 [InlineIcon] 同值，但语义不同：转圈要能塞进一行文字的高度且**不推动版面**，
     * 所以它必须比图标更保守 —— 三处原来写 18/18/20 的转圈这一轮统一到 18。
     */
    val InlineProgress: Dp = 18.dp

    /** **小转圈描边：2dp**。配合 [InlineProgress]。 */
    val InlineProgressStroke: Dp = 2.dp

    /**
     * **平台触摸目标下限：48dp**（M3 / Android 无障碍底线）。
     *
     * ⚠️ 只用于 `Modifier.minimumInteractiveComponentSize()`（`IconButton` 这类自带
     * 水波纹的）或自绘 `clickable` 容器的 `heightIn(min = ...)`。
     * **不要** `Modifier.size(UiSize.TouchTarget)` 去放大一个图标。
     */
    val TouchTarget: Dp = 48.dp

    /**
     * **极细缝：2dp**。两行文字之间、图标与它的展开箭头之间。
     *
     * 比 `Spacing.xs`(4dp) 更紧一档：4dp 用在"两个可区分的元素之间"，
     * 2dp 用在"同一块信息内部的两行"。
     */
    val HairGap: Dp = 2.dp

    /**
     * **细缝：6dp**。徽章的横向内边距、行内的上下留白、底栏胶囊上边距。
     *
     * 介于 `Spacing.xs`(4dp) 与 `Spacing.sm`(8dp) 之间。这一档存在的原因是
     * **4dp 太挤、8dp 太松**的那批位置 —— 例如徽章横内边距：4dp 时文字贴边，
     * 8dp 时徽章显得空。
     */
    val TightGap: Dp = 6.dp

    /**
     * **列表行上下内边距：10dp**。库列表卡、详情页字段行。
     *
     * 8dp 时行显得扁、12dp 时一屏少一行 —— M3 的 list item 垂直内边距正是这个区间。
     * ⚠️ 这一档**只用于"可点的整行"**，不要拿去做普通间距。
     */
    val RowVerticalPadding: Dp = 10.dp
}
