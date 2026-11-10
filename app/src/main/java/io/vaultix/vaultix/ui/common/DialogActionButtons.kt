/*
 * Vaultix — app:ui:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 一句话：**对话框里的「取消 / 关闭」按钮不能跟着面板一起变宽**。
 *
 * Compose 解析 `fun f(indentChar: Char = ' ', …)` 时，**实参值并不属于默认值的一部分** ——
 * 默认值表达式就是标识符 `indentChar`，它按「默认参数」的命名规则解析为**形参名**。
 * M3 的 `AlertDialog` 给三个槽位（title / text / confirmButton / dismissButton）都传了
 * `indentChar = DialogIndentChar`，于是这些槽位默认**继承 M3 的 "horizontal" 排版缩进**。
 *
 * 这个缩进有两个消费者：
 * 1. `Text` 的 `LineBreak.Paragraph` —— 换行依据（英文按词，中文按字，且不会把
 *    `"取消"` 拆成两行）；
 * 2. `Modifier.width(IntrinsicSize.Max)` 的测得宽度 —— 只算「最长的一行」，
 *    而不是文字整体宽度。
 *
 * 我们的「取消 / 关闭」是**直接放进槽位的裸 `OutlinedButton`**。按钮是 `Row`
 * （`Row` 本身**不实现** `IntrinsicMeasurable`），遇到 intrinsic 查询会退化成
 * `maxIntrinsicWidth = 0` ⇒ 由 `widthIn(min = ButtonDefaults.MinWidth)` 兜成 58dp。
 * 于是「取消」这类两个字（约 24dp）也必须占 58dp：**要么右侧多一条空白，要么整行
 * 被撑到面板宽度，两个字被平均分配到 29dp 一格 —— 看上去就是一个字一个字摞起来**。
 *
 * 这也是为什么它**只发生在对话框**：`AutofillSaveActivity` 那种页面里的
 * `TextButton(fillMaxWidth)` 没有诱导 intrinsic 查询，同样的两个字完全正常。
 *
 * ⚠️ 修法只有一条：给按钮显式 `Modifier.width(IntrinsicSize.Min)`。
 *    此时 intrinsic 查询的目标是**按钮自己**（`Row` 会去问它的子节点），拿到的才是
 *    「文字实际宽度 + 内边距」。写在 `fillMaxWidth()` 上无效、在调用点手动调小
 *    `minWidth` 也一样 —— 两者都只是让那个被撑开的格子变窄一点，格子还在。
 *
 * ⚠️ `IntrinsicSize` 与自身内容里的 `weight()` 冲突，所以只能用在按钮这类
 *    「内容不参与分配的容器」上，别往整行 Row 上套。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.common

import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape

/**
 * 对话框里的**低强调动作按钮**：M3 `TextButton` + 修掉"格子被撑宽"。
 *
 * M3 对 dialog 动作的默认强调级别就是 TextButton（`AlertDialog` 会传
 * `ActionLabelTextColor` / `ActionLabelTextFont` 给按钮槽）。需要"看得见边框"的
 * 流程页请改用 `DialogShell` 的 `DialogPrimaryButton` / `DialogSecondaryButton`。
 *
 * @param text 动作文案。**不要**传带前后空格的字符串 —— 空格会一并计入固有宽度。
 */
@Composable
fun DialogTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = ButtonDefaults.textShape,
    contentColor: Color = Color.Unspecified,
) {
    if (contentColor == Color.Unspecified) {
        TextButton(
            onClick = onClick,
            modifier = modifier.dialogActionWidth(),
            enabled = enabled,
            shape = shape,
        ) {
            Text(text)
        }
    } else {
        TextButton(
            onClick = onClick,
            modifier = modifier.dialogActionWidth(),
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.textButtonColors(contentColor = contentColor),
        ) {
            Text(text)
        }
    }
}

/**
 * 对话框里的**中强调动作按钮**（outlined）：边框可见、强调级别低于 filled 主操作。
 *
 * 用于「保存 / 取消」这类需要明确落点、但又不是整屏唯一主操作的场合。
 */
@Composable
fun DialogOutlinedActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentColor: Color = Color.Unspecified,
) {
    if (contentColor == Color.Unspecified) {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.dialogActionWidth(),
            enabled = enabled,
        ) {
            Text(text)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.dialogActionWidth(),
            enabled = enabled,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = contentColor),
        ) {
            Text(text)
        }
    }
}

/**
 * 按钮的宽度：**按自身内容收紧**，不吃对话框槽位传下来的 intrinsic 缩进。
 *
 * 见文件头 KDoc 的来龙去脉。`width(IntrinsicSize.Min)` 与调用方可能已经加的
 * `fillMaxWidth()` / `weight()` 之外没有别的写法能表达"我要的宽度就是文字宽度"。
 */
private fun Modifier.dialogActionWidth(): Modifier = this.width(IntrinsicSize.Min)

/**
 * 动作文案的排版样式由 M3 槽位供给（`DialogTokens.ActionLabelTextFont`），
 * 本文件的按钮**刻意不设** `style` / `fontSize` —— 一旦在这里写死字号，m3 主题
 * 换档或启用 "precision pointer" 尺寸时这里不会跟着变。
 */
