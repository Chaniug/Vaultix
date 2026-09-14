/*
 * Vaultix — app:ui:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 「长按条目 → 进入选择模式 → 底部批量操作条」的交互规格移植自 Bastion
 * （GPL-3.0，Copyright 2025 JoyinJoester）：
 *   - 选择模式由「长按任意条目」激活，激活后条目右侧出现 `Checkbox` 取代菜单按钮；
 *   - 底部浮出一条操作条，承载「已选 N 项 / 全选 / 批量删除 / 退出」；
 *   - **选择模式下条目点击 = 勾选**，不再执行「打开 / 复制」（上游
 *     `TotpCodeCard.cardInteractionModifier` 的同款分支）。
 * 上游把选择态经 `onSelectionModeChange(...)` 上抛给宿主 Activity 统一渲染底栏；
 * 本文件为独立实现：直接在页面 `Scaffold.bottomBar` 内渲染，省掉跨层回调。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.theme.Spacing

/** 操作条的水平内边距（与列表卡片的 16dp 对齐）。 */
private val BAR_PADDING = Spacing.lg

/**
 * 选中集合的翻转开关：已选就摘掉、没选就加进来。
 *
 * 分组折叠与条目多选都是同一套「点一下翻转」的语义。抽成一行是为了不让页面级
 * composable 里堆两组各占 6 行的三元表达式（detekt `LongMethod ≤150` 门禁）。
 */
fun toggleSelection(set: Set<String>, key: String): Set<String> =
    if (key in set) set - key else set + key

/**
 * 选择模式下的底部批量操作条（密码条目页 / 验证码页共用）。
 *
 * @param selectedCount 当前已选条目数。
 * @param allSelected 是否已全选（决定「全选」按钮的文案与行为交给 [onToggleSelectAll]）。
 * @param onToggleSelectAll 全选 / 取消全选。
 * @param onClear 退出选择模式。
 * @param onDelete 删除已选条目（真正删除由调用方执行，通常还要二次确认或生物验证）。
 */
@Composable
fun SelectionActionBar(
    selectedCount: Int,
    allSelected: Boolean,
    onToggleSelectAll: () -> Unit,
    onClear: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BAR_PADDING, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClear) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.action_clear_selection),
                )
            }
            Text(
                text = stringResource(R.string.selection_count, selectedCount),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onToggleSelectAll) {
                Text(
                    text = stringResource(
                        if (allSelected) R.string.action_clear_selection else R.string.action_select_all,
                    ),
                )
            }
            Spacer(Modifier.width(Spacing.xs))
            TextButton(
                onClick = onDelete,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Text(stringResource(R.string.action_delete_selected))
            }
        }
    }
}
