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
 * 窄屏底部导航条（悬浮胶囊 + 中央「+」按钮）。
 * 抽取自 Bastion 项目（GPL-3.0，Copyright 2025 JoyinJoester）的
 * ui/SimpleMainScreen.kt 内联实现（原为 bottomBar lambda 内约 110 行）：
 *   - 悬浮胶囊规格：RoundedCornerShape(50) + surfaceContainerHigh + tonal3/shadow6，
 *     height(60dp)，外层留白 start/end=12dp, top=6dp, bottom=20dp（总高 82dp）
 *   - 布局：左 2 Tab + 中间「+」+ 右 2 Tab（各 weight(1f) 均分）
 *   - 中央「+」：圆角方块（**非 FAB**）RoundedCornerShape(16dp) + primary，
 *     52×48dp，图标 26dp；行为随当前 Tab 变化（对齐 Bastion `when(currentTab)`）
 *
 * 同时**原样保留** Bastion 在该处沉淀的两条踩坑注释（见 [DockTabItem] 与
 * [VaultixBottomDock] 的 KDoc）——这两条是「点一个 tab 旁边跟着闪」的真实根因，
 * 简单化改写会重新引入该缺陷。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.vaultix.vaultix.R

/** 悬浮胶囊底栏固定高度（对齐 Bastion：胶囊 60dp + 留白 6/20 = 86dp 视觉占位）。 */
private val DockBarHeight = 60.dp

/**
 * 窄屏底部导航条：悬浮胶囊 + 中央「+」。
 *
 * ## ⚠️ 为什么「+」是圆角方块而不是 FAB
 * 对齐 Bastion 实测样式（酷安规格）：胶囊内中央一块接近撑满高度的主色圆角方形，
 * 视觉上与两侧 tab 同层，而非悬浮于其上的 FAB。**勿改成 FloatingActionButton。**
 *
 * ## ⚠️ 为什么 tab 项必须用 [key] 包裹
 * 见 [DockTabItem] KDoc —— 嵌套函数 + 无 key 会导致组合身份不稳定，
 * 表现为「点一个 tab、旁边那个 pill 也跟着闪」。
 *
 * @param tabs 数据 tab（**不含设置**；设置由调用方决定是否固定在末尾）。
 * @param selected 当前选中项。
 * @param onSelect 切换 tab。
 * @param onAdd 中央「+」点击；入参为当前 Tab，由宿主按 [VaultixNavItem.addTarget] 分发。
 */
@Composable
fun VaultixBottomDock(
    tabs: List<VaultixNavItem>,
    selected: VaultixNavItem,
    onSelect: (VaultixNavItem) -> Unit,
    onAdd: (VaultixNavItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 外层留白：bottom 20dp 让胶囊明显抬离系统手势条，底部透出内容（酷安观感）。
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 20.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(DockBarHeight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 布局：左 2 个 + 中间「+」+ 右 2 个（不足则用 Spacer 占位保持「+」居中）。
                val leftTabs = tabs.take(2)
                val rightTabs = tabs.drop(2).take(2)

                leftTabs.forEach { item ->
                    key(item) {
                        DockTabItem(
                            item = item,
                            isSelected = item == selected,
                            onClick = { onSelect(item) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                repeat(2 - leftTabs.size) { Spacer(modifier = Modifier.weight(1f)) }

                // 中央「+」：独立函数无法访问 RowScope.weight，故此处显式传 weight 修饰符。
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center,
                ) {
                    AddButton(currentTab = selected, onAdd = onAdd)
                }

                rightTabs.forEach { item ->
                    key(item) {
                        DockTabItem(
                            item = item,
                            isSelected = item == selected,
                            onClick = { onSelect(item) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                repeat(2 - rightTabs.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

/** 中央「+」：主色圆角方形（52×48dp，圆角 16dp，图标 26dp）。 */
@Composable
private fun AddButton(
    currentTab: VaultixNavItem,
    onAdd: (VaultixNavItem) -> Unit,
) {
    val addLabel = stringResource(R.string.nav_add)
    Surface(
        onClick = { onAdd(currentTab) },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier
            .width(52.dp)
            .height(48.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = addLabel,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

/**
 * 底栏单个 tab 项（悬浮胶囊内的图标 + 文字）。
 *
 * ## ⚠️ 必须是【顶层】composable
 * 不能定义在 `bottomBar` / 其他 lambda 内部再经 forEach 调用 —— 嵌套函数在每次
 * 重组时会被重新定义，导致内部 `remember` / 动画的组合身份不稳定，点击一个 tab
 * 触发整行重组时动画状态会重启，表现为「**点一个、旁边那个 pill 也跟着闪**」。
 * 同时调用处必须用 `key(item.key)` 包裹，保证每个 tab 有稳定的组合槽。
 *
 * ## ⚠️ 选中色必须【瞬切】，不可用 animateColorAsState
 * 此前 Bastion 用 `animateColorAsState(tween(200))`，点击新 tab 时「新 pill 渐现」
 * 与「旧 pill 渐隐」两个动画并行 200ms，期间两个 pill 同时处于中间色，用户感知
 * 就是「**点一个、旁边那个也跟着闪**」。且下方 contentTint 本就是瞬间切换，
 * 背景若走渐变会与文字色不同步、更显抖动。改为瞬切后选中态与文字色严格同步。
 */
@Composable
private fun DockTabItem(
    item: VaultixNavItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(item.labelRes)
    // 选中色块包住「图标+文字」整体（酷安样式，圆角矩形非细长条）。瞬切，无动画 —— 见 KDoc。
    val pillColor = if (isSelected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        Color.Transparent
    }
    val contentTint = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 56.dp)
                .height(48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(pillColor),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = label,
                tint = contentTint,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = contentTint,
            )
        }
    }
}
