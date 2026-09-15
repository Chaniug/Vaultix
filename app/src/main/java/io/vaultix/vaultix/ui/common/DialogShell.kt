/*
 * Vaultix — app:ui:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * 对话框外壳：设置页「当前密码库 / 添加密码库 / 快速解锁」等对话框共用的视觉骨架。
 *
 * ---------------------------------------------------------------------------
 * 为什么需要它（2026-09-15，用户真机反馈「这几个界面都好简陋啊」）
 *
 * 页面级做得很讲究（[io.vaultix.vaultix.ui.settings.SettingsRow]：20dp 圆角卡片 +
 * 72dp 最小高 + 28dp 图标槽），**但对话框内部全是裸 `AlertDialog` + 裸 `Row` +
 * 裸 `TextButton`**，长段说明还堆在底部 —— 观感落差就是「简陋」的来源。
 *
 * 这里给出一套共用外壳，让对话框与页面共享同一套视觉语言：
 *   - 28dp 圆角大面板（对齐 `AppPickerDialog` 的既有规格）+ `tonalElevation`；
 *   - 标题 `titleLarge`，与页面一级标题同级；
 *   - 正文区默认自带滚动位（由调用方决定是否 `verticalScroll`）；
 *   - 底部动作右对齐。
 *
 * ⚠️ 放在 `ui/common` 而不是 `ui/settings`：`AddVaultTypeDialog` 也用了它，
 * 而后者位于 `ui/common` 且被设置页与库列表页**两处共用**（其文件头 KDoc 明确要求
 * 共用一份实现避免漂移）。放 settings 会造成 ui/common → ui/settings 的反向依赖。
 *
 * ⚠️ 用 `BasicAlertDialog`（`AlertDialog` 的自定义容器版），**不做成底部弹层**：
 * 全仓库 25 处弹窗都是弹窗形态、只有 1 处底部弹层；把设置页这几处改成弹层会立刻
 * 显得「这不是同一个 App」。**形态一致性优先于"更 M3"。**
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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

/** 对话框高度占屏比（上限；内容少时由内部 `weight(1f, fill = false)` 自然收缩）。 */
private const val DIALOG_HEIGHT_RATIO = 0.8f

/**
 * 对话框外壳：圆角面板 + 宽高约束。
 *
 * 内部是一个 `Column`，调用方通常依次放：
 * [DialogHeader] → 正文（可滚动）→ [DialogFootnote] → [DialogActions]。
 *
 * ⚠️ 面板高度**写死 0.8 屏**是刻意的：`BasicAlertDialog` 给的是全屏约束，
 * 若不限高，内容一多就会顶到屏幕边缘（状态栏/手势条底下）。
 * 内容少的对话框看起来仍是「大面板」，与内容多的保持一致的外形语言。
 */
@Composable
fun DialogSurface(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth(DIALOG_WIDTH_RATIO)
            .fillMaxHeight(DIALOG_HEIGHT_RATIO),
        shape = RoundedCornerShape(DIALOG_CORNER),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(vertical = Spacing.lg),
            content = content,
        )
    }
}

/** 对话框标题（`titleLarge`，与页面一级标题同级）。 */
@Composable
fun DialogHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
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

/** 底部动作区（右对齐）。 */
@Composable
fun DialogActions(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.xl, end = Spacing.xl, top = Spacing.md),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** 次级动作按钮（「取消」）。 */
@Composable
fun DialogDismissButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(stringResource(R.string.action_cancel))
    }
}

/** 次级动作按钮（「返回」）——语义上比「取消」更贴合二级流程。 */
@Composable
fun DialogBackButton(onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(stringResource(R.string.action_back))
    }
}

/** 对话框内的分组小标题（`labelLarge` + primary，对齐 `DisplayOptionsSheet`）。 */
@Composable
fun DialogSectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(
            start = Spacing.xl,
            end = Spacing.xl,
            top = Spacing.sm,
            bottom = Spacing.xs,
        ),
    )
}

/** 对话框内的分组说明（`bodySmall` + 次要色），紧跟 [DialogSectionTitle]。 */
@Composable
fun DialogSectionHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Spacing.xl, end = Spacing.xl, bottom = Spacing.sm),
    )
}
