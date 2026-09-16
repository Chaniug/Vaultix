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
import androidx.compose.ui.unit.Dp
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
 * 选择模式下的底部批量操作条（密码条目页 / 验证码页 / 通行密钥页共用）。
 *
 * ## ⚠️ 为什么需要 [dockInset]（2026-09-16 修的一个真 bug）
 *
 * 悬浮胶囊底栏（`VaultixBottomDock`）是**叠层**画的 —— `AdaptiveMainScaffold` 刻意
 * 不走 Scaffold 的 `bottomBar`，内容一直铺到屏幕底。所以**页面自己的 `bottomBar`
 * 会落在 Dock 底下**：主 Tab 页（密码条目 / 验证码）里这条操作栏**看不见也点不到**，
 * 而二级页（通行密钥，没有 Dock）却正常 —— 用户反馈的正是这个差异。
 *
 * 本组件原先的注释写着「直接在页面 `Scaffold.bottomBar` 内渲染，省掉跨层回调」——
 * **省掉的那层跨层正是这个 bug 的来源**（上游 Bastion 是上抛给宿主统一渲染的）。
 * 这里用更小的改动达成同样的可用性：由调用方告知"我下面有多少是被 Dock 占掉的"，
 * 操作栏据此抬到 Dock 之上。⚠️ 主 Tab 页传 `BottomDockOccupiedHeight`，二级页传 0。
 *
 * ## 「全选」为什么去掉了（2026-09-16）
 *
 * 用户反馈「按住条目的时候，出现全选的设置，我感觉全选的没必要吧」。
 * 密码管理器里批量全选后能做的事只有「删除」，而全选再删是**最危险**的操作组合
 * （误触一次就是整库清空）；真要全选，逐条点的成本远低于误删的代价。
 * ⇒ 摘掉按钮，`selectedCount` 的展示保留（用户仍需知道选了几条）。
 */
@Composable
fun SelectionActionBar(
    selectedCount: Int,
    /** 底部需要让出的高度（见上）。主 Tab 页传 `BottomDockOccupiedHeight`，二级页传 0。 */
    dockInset: Dp = 0.dp,
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
                // 让位写在内容 padding 里（而不是外挂 Spacer）：操作栏的底色/浮起要
                // 一路铺到屏幕底，只是**内容**抬上去 —— 否则下方会露出画面，
                // 那正是本项目 #76「让位必须写在 contentPadding 里」记过的坑。
                .padding(
                    start = BAR_PADDING,
                    end = BAR_PADDING,
                    top = Spacing.xs,
                    bottom = Spacing.xs + dockInset,
                ),
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
