/*
 * Vaultix — app:ui:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 一句话：**对话框动作按钮的宽度交给 M3 自己算，别用 intrinsic 去"修"它。**
 *
 * ## 2026-11-12 #284（本轮）：移除 `width(IntrinsicSize.Min)`
 *
 * 用户第三次反馈「设置里好几个地方的取消按钮大小和形态和其他正常按钮都不一样」
 * （点名主题模式 / 锁与安全 / 自动锁定）。前两轮都没修到点上：
 *
 * - #273 加 `width(IntrinsicSize.Min)`，理由是「M3 `AlertDialog` 动作槽传
 *   `indentChar = "horizontal"`，参与 `width(IntrinsicSize.Max)` 的测得宽度；
 *   裸按钮是 `Row`、不实现 intrinsic ⇒ 测得 0 ⇒ 被 `widthIn(58dp)` 兜住 ⇒
 *   两个字各占 29dp 竖排」；
 * - #283 在它前面补 `widthIn(min = ButtonDefaults.MinWidth)`，理由是「按钮被
 *   压成贴字小胶囊」，并显式声明 M3 `contentPadding`。
 *
 * ⚠️ **#273 那条根因是错的，而且从一开始就错**（本轮反编译实证，见
 *    `.ai/issues/06-界面与交互-part22.md`）：解包本仓实际依赖的
 *    `material3-android:1.5.0-alpha16`，`AlertDialogKt.AlertDialogFlowRow` 直接调
 *    `FlowLayoutKt.FlowRow`，**全部 Dialog 相关类里没有任何 `IntrinsicSize` 引用**；
 *    `git show d8559668:gradle/libs.versions.toml` 证明 #273 提交时的 `composeBom` /
 *    `material3` 与现在**完全相同**（`2026.06.01` / `1.5.0-alpha16`），不是"版本
 *    变了所以旧结论失效"。`FlowRow` 的子项是常规 wrap-content 测量，动作槽
 *    根本不会诱导 intrinsic 查询。
 *
 * ⚠️ 让按钮「大小 / 形态不对」的正是 `width(IntrinsicSize.Min)` **自己**：
 *    `IntrinsicSizeModifier` 把宽度**固定**成 `minIntrinsicWidth` —— 中文按单字断行，
 *    「取消」的最小固有宽度只够一个字 + 内边距；#283 的 `widthIn(64dp)` 只是把它
 *    托到**恰好 64dp 的固定宽度**。于是全项目 59 个动作槽按钮全是同一个 64dp
 *    小方块：不随文案（「保存」/「取消」/「删除并退出」）伸缩，也不随字号
 *    （2.0x）伸缩。而项目里已确认正常的 `DialogShell` 家族是 `FlowRow` + 原生
 *    M3 按钮（wrap-content、`ButtonDefaults` 自带 `MinWidth`），宽得多也饱满得多
 *    ⇒ 用户看到的「和其他正常按钮都不一样」就是**这两套规格并排出现**。
 *
 * ⇒ 修法：删掉 `width(IntrinsicSize.Min)`，只留 `widthIn(min = ButtonDefaults.MinWidth)`
 *    （纯下限、不固定宽度，等于把 M3 自带的规格显式写一遍）。按钮回到原生
 *    wrap-content：短文案自然收窄、长文案 / 大字号自然变宽。
 *    #282 的「单选项对话框退出按钮必须有边框」判据**不动**（用户明确要过边框）。
 *
 * ⚠️ 这是**共用组件**：全项目 59 个 `AlertDialog` 动作槽都走它（设置 / 全屏编辑壳 /
 *    丢弃确认框 / 库管理 / 导入导出 …），改这一处即全部生效，别去各调用点打补丁。
 *    `DialogShell` 家族走 `FlowRow`、用原生 M3 按钮，规格独立、不动。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.common

import androidx.compose.foundation.layout.widthIn
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
 * 对话框里的**低强调动作按钮**：M3 `TextButton` + 原生 wrap-content 宽度。
 *
 * M3 对 dialog 动作的默认强调级别就是 TextButton（`AlertDialog` 会传
 * `ActionLabelTextColor` / `ActionLabelTextFont` 给按钮槽）。需要"看得见边框"的
 * 流程页请改用 `DialogShell` 的 `DialogPrimaryButton` / `DialogSecondaryButton`。
 *
 * ⚠️ **#282 例外（2026-11-12）**：「即选即存的单选项对话框」的退出按钮
 *    （主题模式 / 剪贴板清除 / 自动锁定）按用户反馈改用 [DialogOutlinedActionButton]，
 *    不再走本组件 —— 单选项列表需要一个看得见的落点，`DialogStyleRulesTest` 规则 1
 *    已随本次反馈反转。本组件仍是**纯展示 / 双动作次要动作**的默认选择。
 *
 * ⚠️ **#284 起宽度不再固定**：见文件头。别把 `width(IntrinsicSize.Min)` 加回来。
 *
 * @param text 动作文案。**不要**传带前后空格的字符串 —— 空格会一并计入宽度。
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
            contentPadding = ButtonDefaults.TextButtonContentPadding,
        ) {
            Text(text)
        }
    } else {
        TextButton(
            onClick = onClick,
            modifier = modifier.dialogActionWidth(),
            enabled = enabled,
            shape = shape,
            contentPadding = ButtonDefaults.TextButtonContentPadding,
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
 *
 * ⚠️ **#282 起也是「单选项对话框退出按钮」的正规用法**：主题模式 / 剪贴板清除 /
 *    自动锁定三个「点档位即生效」的对话框，唯一的「取消」用它 —— 用户反馈无边框时
 *    看不出是可点按钮。旧规则「退出动作一律 TextButton」同步作废。**#284 不动这条。**
 * ⚠️ **#284 起宽度不再固定**：#283 补的 `widthIn(min = ButtonDefaults.MinWidth)`
 *    保留（纯下限），但删掉了压扁按钮的 `width(IntrinsicSize.Min)`；内边距仍是
 *    M3 默认 `ButtonDefaults.ContentPadding`（显式声明，避免换档时静默漂移）。
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
            contentPadding = ButtonDefaults.ContentPadding,
        ) {
            Text(text)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.dialogActionWidth(),
            enabled = enabled,
            contentPadding = ButtonDefaults.ContentPadding,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = contentColor),
        ) {
            Text(text)
        }
    }
}

/**
 * 按钮宽度规格：**只抬下限，不固定宽度**。
 *
 * `ButtonDefaults.MinWidth`（64dp）本来就是 M3 `Button` / `OutlinedButton` /
 * `TextButton` 自带的 `defaultMinSize`，这里显式写一遍是为了让"最短的动作
 * 按钮也不会窄于 M3 标准"成为可见的规格，而不是散落在库实现里的隐含行为。
 *
 * ⚠️ **绝对不要再加 `width(IntrinsicSize.Min)` / `width(...)` / `height(...)`**：
 *    `IntrinsicSizeModifier` 会把测得宽度变成 `fixedWidth` 约束，按钮从此不再
 *    随文案与字号伸缩 —— 正是 #284 修掉的「64dp 小方块」。详见文件头反编译取证。
 *    `DialogStyleRulesTest` 规则 4 与 `SettingsStyleRulesTest` 共同钉住这条。
 */
private fun Modifier.dialogActionWidth(): Modifier =
    this.widthIn(min = ButtonDefaults.MinWidth)

/**
 * 动作文案的排版样式由 M3 槽位供给（`DialogTokens.ActionLabelTextFont`），
 * 本文件的按钮**刻意不设** `style` / `fontSize` —— 一旦在这里写死字号，m3 主题
 * 换档或启用 "precision pointer" 尺寸时这里不会跟着变。
 */
