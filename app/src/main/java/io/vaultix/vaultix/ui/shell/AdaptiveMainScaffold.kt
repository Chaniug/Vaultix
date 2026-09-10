/*
 * Vaultix — app:ui:shell
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 宽屏自适应主脚手架（NavigationRail + 内容区）。
 * 移植自 Bastion 项目（GPL-3.0，Copyright 2025 JoyinJoester）的
 * ui/main/layout/AdaptiveMainScaffold.kt（103 行，纯 UI 无业务耦合，近乎原样）。
 *
 * Vaultix 扩展点：
 *   - 原版**无**中央「+」按钮。此处宽屏分支补一个 rail 内的「+」项，
 *     使宽窄屏行为一致（窄屏「+」在悬浮胶囊中央，见 [VaultixBottomDock]）。
 *   - 窄屏分支在本文件仅作为**降级**（若未使用悬浮胶囊时），
 *     实际窄屏走 [VaultixBottomDock]（对齐 Bastion 真实主界面路径）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.vaultix.vaultix.R

/**
 * 宽屏主脚手架：左侧 NavigationRail（可滚动）+ 右侧内容区。
 *
 * @param tabs 数据 tab（不含设置）。
 * @param current 当前选中项。
 * @param onSelect 切换 tab。
 * @param onAdd 中央「+」点击（宽屏为 rail 内的独立项，位于 tabs 之间）。
 * @param content 内容区；接收 PaddingValues。
 */
@Composable
fun AdaptiveMainScaffold(
    isCompactWidth: Boolean,
    tabs: List<VaultixNavItem>,
    current: VaultixNavItem,
    onSelect: (VaultixNavItem) -> Unit,
    onAdd: (VaultixNavItem) -> Unit,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    if (isCompactWidth) {
        // 窄屏：由调用方提供悬浮胶囊底栏（VaultixBottomDock），此处不再自绘
        // NavigationBar —— 对齐 Bastion 真实主界面路径（见溯源声明）。
        androidx.compose.material3.Scaffold(bottomBar = bottomBar) { padding ->
            content(padding)
        }
        return
    }

    Row(modifier = Modifier.fillMaxSize()) {
        NavigationRail(
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // tabs 前半 → 「+」→ tabs 后半，使「+」视觉居中。
                val splitAt = (tabs.size + 1) / 2
                tabs.take(splitAt).forEach { item ->
                    RailItem(item = item, current = current, onSelect = onSelect)
                }

                val addLabel = stringResource(R.string.nav_add)
                NavigationRailItem(
                    selected = false,
                    onClick = { onAdd(current) },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = addLabel,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    label = { Text(text = addLabel, maxLines = 1) },
                    alwaysShowLabel = true,
                    colors = NavigationRailItemDefaults.colors(indicatorColor = Color.Transparent),
                )

                tabs.drop(splitAt).forEach { item ->
                    RailItem(item = item, current = current, onSelect = onSelect)
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            content(PaddingValues())
        }
    }
}

@Composable
private fun RailItem(
    item: VaultixNavItem,
    current: VaultixNavItem,
    onSelect: (VaultixNavItem) -> Unit,
) {
    val label = stringResource(item.labelRes)
    NavigationRailItem(
        selected = item == current,
        onClick = { onSelect(item) },
        icon = { Icon(item.icon, contentDescription = label) },
        label = {
            Text(text = label, maxLines = 2, overflow = TextOverflow.Clip)
        },
        alwaysShowLabel = true,
        // 与悬浮胶囊的选中样式保持一致：关闭漂浮放大 indicator。
        colors = NavigationRailItemDefaults.colors(indicatorColor = Color.Transparent),
    )
}
