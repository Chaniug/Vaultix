/*
 * Vaultix — app:ui:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * 对话框外壳 + 动作按钮：设置页「当前密码库 / 添加密码库 / 快速解锁」等对话框共用的视觉骨架。
 *
 * ---------------------------------------------------------------------------
 * 为什么需要它（2026-09-15，用户真机反馈「这几个界面都好简陋啊」）
 *
 * 页面级做得很讲究（SettingsRow：20dp 圆角卡片 + 72dp 最小高 + 28dp 图标槽），
 * 但对话框内部全是裸 AlertDialog + 裸 Row + 裸 TextButton ⇒ 观感落差就是「简陋」。
 *
 * ## 2026-11-06 第三轮（用户反馈「弹窗很简陋，又大」「按钮没有边框包裹」）
 *
 * 1. **尺寸**：原先 `fillMaxHeight(0.8f)` 写死八成屏高，KDoc 还把「内容少也是大面板」
 *    当成一致性优点。真机上这就是「又大、空、不成比例」的来源。改为
 *    `wrapContentHeight` + `heightIn(max = 520.dp)`：内容少就收紧，内容多由内部
 *    滚动区自己限高（调用方给 `heightIn(max)` + `verticalScroll`，顺序不能反）。
 * 2. **按钮**：三个语义按钮全部从裸 `TextButton` 换成 `OutlinedButton`（有边框），
 *    并新增 `DialogPrimaryButton`（filled，唯一主操作）/ `DialogSecondaryButton` /
 *    `DialogDangerButton`。M3 的 dialog 默认动作是低强调的 TextButton，但本项目
 *    设置流程里「继续 / 保存 / 关闭 PIN」需要更明确的落点，故采用中强调的
 *    Outlined + 高强调的 Filled 组合 —— 仍是 M3 原生组件，不是自绘。
 * 3. **标题**：`titleLarge` → `headlineSmall`，对齐 M3 dialog 的标题层级。
 *
 * ⚠️ 放在 `ui/common` 而不是 `ui/settings`：`AddVaultTypeDialog` 也用了它。
 * ⚠️ 仍用 `BasicAlertDialog`，**不做成底部弹层**：全仓库弹窗形态一致，改弹层会
 *    立刻显得「不是同一个 App」。形态一致性优先于"更 M3"。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.theme.Spacing

/** 对话框面板圆角（对齐 `AppPickerDialog` 的 28dp）。 */
private val DIALOG_CORNER = 28.dp

/** 对话框宽度占屏比（留出左右呼吸，但不像 `AlertDialog` 那样压到 7 成）。 */
private const val DIALOG_WIDTH_RATIO = 0.92f

/** 面板高度上界；短内容由 [wrapContentHeight] 自然收缩，长内容由内部滚动区接管。 */
private val DIALOG_MAX_HEIGHT = 520.dp

/**
 * 对话框外壳：圆角面板 + **自适应高度**。
 *
 * 内部是一个 `Column`，调用方通常依次放：
 * [DialogHeader] → 正文（可滚动）→ [DialogFootnote] → [DialogActions]。
 *
 * ⚠️ 不再用 `fillMaxHeight(0.8f)`：内容少的确认框/结果框会按内容收紧，
 *    只有内容多的列表框才通过自身的 `heightIn(max)` + `verticalScroll` 撑到上界。
 * ⚠️ 因此调用方**不要**再用 `weight(1f, fill = false)` 撑正文区 —— Column 现在
 *    是 wrapContent 的，weight 没有剩余空间可分配。改成 `heightIn(max = …)`。
 */
@Composable
fun DialogSurface(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth(DIALOG_WIDTH_RATIO)
            .wrapContentHeight()
            .heightIn(max = DIALOG_MAX_HEIGHT),
        shape = RoundedCornerShape(DIALOG_CORNER),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .padding(vertical = Spacing.lg),
            content = content,
        )
    }
}

/** 对话框标题（M3 dialog 的 `headlineSmall`）。 */
@Composable
fun DialogHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.padding(
            start = Spacing.xl,
            end = Spacing.xl,
            top = Spacing.sm,
            bottom = Spacing.md,
        ),
    )
}

/** 对话框的说明小字（`bodySmall` + 次要色）。用于列表**下方**的补充说明。 */
@Composable
fun DialogFootnote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            start = Spacing.xl,
            end = Spacing.xl,
            top = Spacing.md,
        ),
    )
}

/** 对话框的空态正文（内容为空时替代列表区）。 */
@Composable
fun DialogEmptyBody(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.lg),
    )
}

/** 统一的按钮内边距：比 M3 默认 24dp 收一档，避免三按钮弹窗被挤出可见区。 */
private val DIALOG_BUTTON_PADDING = PaddingValues(horizontal = 16.dp, vertical = 8.dp)

/**
 * 底部动作区：右对齐、自动换行、上下各留一口气。
 *
 * ⚠️ 2026-11-06 真机截图：原为单 `Row`，「修改 PIN」里「关闭 PIN 解锁 / 返回 / 继续」
 * 三个按钮加 `weight` 空隙超出可用宽度 ⇒ 主按钮被裁成屏幕边一条竖条。
 * 改 `FlowRow` 后放不下就换行，任何字号 / 任意按钮数都不会再裁内容。
 * ⚠️ 代价：`DialogActions` 调用点里的 `Spacer(Modifier.weight(1f))` 已去掉（FlowRow 无 weight），
 *    破坏性动作仍靠「排在最前 + danger 色」与主操作区分。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DialogActions(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = Spacing.xl,
                end = Spacing.xl,
                top = Spacing.md,
                bottom = Spacing.sm,
            ),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        content = content,
    )
}

/** 主操作（「继续 / 保存」）：filled，一个对话框只应有一个。 */
@Composable
fun DialogPrimaryButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        contentPadding = DIALOG_BUTTON_PADDING,
    ) {
        Text(text)
    }
}

/** 次操作（「跳过 / 返回 / 取消」）：outlined，有边框但强调级别低于主操作。 */
@Composable
fun DialogSecondaryButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = DIALOG_BUTTON_PADDING,
    ) {
        Text(text)
    }
}

/** 破坏性动作（「关闭 PIN 解锁 / 清除配置」）：outlined + error 文字色。 */
@Composable
fun DialogDangerButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = DIALOG_BUTTON_PADDING,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.error,
        ),
    ) {
        Text(text)
    }
}

/** 次级动作按钮（「取消」）。 */
@Composable
fun DialogDismissButton(onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, contentPadding = DIALOG_BUTTON_PADDING) {
        Text(stringResource(R.string.action_cancel))
    }
}

/** 次级动作按钮（「返回」）——语义上比「取消」更贴合二级流程。 */
@Composable
fun DialogBackButton(onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, contentPadding = DIALOG_BUTTON_PADDING) {
        Text(stringResource(R.string.action_back))
    }
}

/**
 * 次级动作按钮（「关闭」）。
 *
 * ## 为什么它必须与 [DialogDismissButton]（「取消」）分开
 *
 * - 「取消」= 打算做的事不做了（有未完成的动作会被放弃）；
 * - 「关闭」= 只是把看过的内容收起来（事情已经做完，没有东西可取消）。
 *
 * Material 3 要求动作文案如实描述后果：对一个只**报告结果**的对话框写「取消」是错的。
 */
@Composable
fun DialogCloseButton(onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, contentPadding = DIALOG_BUTTON_PADDING) {
        Text(stringResource(R.string.action_close))
    }
}

/**
 * 对话框内的分组小标题（`labelLarge` + primary，对齐 `DisplayOptionsSheet`）。
 *
 * @param icon 可选的图标锚点；传 `null` 则退化为纯文字标题。
 * @param hint 可选的紧随其后的说明。合并进本函数是为了让「标题 + 说明」
 *   作为一个整体参与间距计算，避免两处分别写 padding 导致间距不等。
 */
@Composable
fun DialogSectionTitle(
    title: String,
    icon: (@Composable () -> Unit)? = null,
    hint: String? = null,
) {
    Column(
        modifier = Modifier.padding(
            start = Spacing.xl,
            end = Spacing.xl,
            top = Spacing.sm,
            bottom = Spacing.sm,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
                    icon()
                }
                Spacer(Modifier.width(Spacing.sm))
            }
            Text(
                text = title,
                // ⚠️ 原 labelLarge(14sp) 比输入框 label 的 bodyMedium(16sp) 还小，
                //    一屏里「小标题 < 字段名」层级倒挂 ⇒ 提到 titleSmall(16sp)。
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
    }
}

/** 对话框内的分组说明（`bodySmall` + 次要色）。
 *
 * ⚠️ 新代码优先用 [DialogSectionTitle] 的 `hint` 参数 —— 标题与说明一起排版，
 * 间距才一致。本函数保留给「说明与任何标题都不相邻」的场景。 */
@Composable
fun DialogSectionHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Spacing.xl, end = Spacing.xl, bottom = Spacing.sm),
    )
}
