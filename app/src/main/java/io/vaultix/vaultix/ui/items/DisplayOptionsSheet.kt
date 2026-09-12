/*
 * Vaultix — app:ui:items
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 「显示选项底部弹层」的结构移植自 Bastion（GPL-3.0，Copyright 2025 JoyinJoester）的
 * `ui/password/PasswordDisplayOptionsSheet.kt`：
 *   - `ModalBottomSheet`（`skipPartiallyExpanded = true`、容器色 surface）+ 大标题；
 *   - 每个维度一个小标题（`labelLarge` + primary）+ 若干**选中态选项行**
 *     （图标 + 标题 + 说明 + 选中标记），维度之间用 `HorizontalDivider`（outlineVariant 40%）分隔；
 *   - 选项点击后先收起弹层再落盘（避免「选了没反应」的错觉）。
 * 维度按 Vaultix 的数据模型裁剪：**分组方式 / 卡片显示 / 显示图标**。
 * 未搬 Bastion 的 StackCardMode（依赖它的「堆叠卡片组」实现，Vaultix 无对应物）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.items

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.vaultix.vaultix.R
import kotlinx.coroutines.launch

/**
 * 显示选项弹层（密码列表顶栏「显示选项」按钮打开）。
 *
 * @param groupMode 当前分组方式。
 * @param cardDisplayMode 当前卡片信息密度。
 * @param showIcon 是否显示左侧图标。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplayOptionsSheet(
    groupMode: ItemsGroupMode,
    cardDisplayMode: ItemsCardDisplayMode,
    showIcon: Boolean,
    onDismiss: () -> Unit,
    onGroupMode: (ItemsGroupMode) -> Unit,
    onCardDisplayMode: (ItemsCardDisplayMode) -> Unit,
    onShowIcon: (Boolean) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // 先收起弹层再落盘：否则选中态会在关闭动画里跳一下（上游同款处理）。
    fun pick(apply: () -> Unit) {
        scope.launch {
            if (sheetState.isVisible) sheetState.hide()
            onDismiss()
            apply()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
        ) {
            Text(
                text = stringResource(R.string.items_display_options),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )

            OptionGroupTitle(stringResource(R.string.items_group_mode))
            ItemsGroupMode.entries.forEach { mode ->
                OptionRow(
                    icon = iconOf(mode),
                    title = stringResource(groupModeLabelRes(mode)),
                    selected = mode == groupMode,
                    onClick = { pick { onGroupMode(mode) } },
                )
            }

            OptionDivider()
            OptionGroupTitle(stringResource(R.string.items_card_display))
            ItemsCardDisplayMode.entries.forEach { mode ->
                OptionRow(
                    icon = iconOf(mode),
                    title = stringResource(cardDisplayLabelRes(mode)),
                    selected = mode == cardDisplayMode,
                    onClick = { pick { onCardDisplayMode(mode) } },
                )
            }

            OptionDivider()
            OptionGroupTitle(stringResource(R.string.items_show_icon))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = stringResource(R.string.items_show_icon_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(start = 16.dp),
                )
                Switch(checked = showIcon, onCheckedChange = onShowIcon)
            }
        }
    }
}

/** 维度小标题（上游：labelLarge + primary + 24/8 内边距）。 */
@Composable
private fun OptionGroupTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
}

/** 维度分隔线（上游：outlineVariant 40% + 24dp 水平内边距）。 */
@Composable
private fun OptionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 12.dp, horizontal = 24.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    )
}

/** 单个选项行：图标 + 标题（+ 选中标记）。 */
@Composable
private fun OptionRow(
    icon: ImageVector,
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f).padding(start = 16.dp),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private fun iconOf(mode: ItemsGroupMode): ImageVector = when (mode) {
    ItemsGroupMode.None -> Icons.Filled.Dashboard
    ItemsGroupMode.Type -> Icons.Filled.Dashboard
    ItemsGroupMode.Folder -> Icons.Filled.Language
    ItemsGroupMode.Initial -> Icons.Filled.Title
}

private fun iconOf(mode: ItemsCardDisplayMode): ImageVector = when (mode) {
    ItemsCardDisplayMode.All -> Icons.Filled.Dashboard
    ItemsCardDisplayMode.TitleUsername -> Icons.Filled.Person
    ItemsCardDisplayMode.TitleOnly -> Icons.Filled.Title
}

private fun cardDisplayLabelRes(mode: ItemsCardDisplayMode): Int = when (mode) {
    ItemsCardDisplayMode.All -> R.string.items_display_all
    ItemsCardDisplayMode.TitleUsername -> R.string.items_display_title_username
    ItemsCardDisplayMode.TitleOnly -> R.string.items_display_title_only
}
