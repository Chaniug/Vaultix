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
 * 「条目行尾用小云图标表达云端同步状态」的交互规格参考自 Bastion
 * （GPL-3.0，Copyright 2025 JoyinJoester）的列表行徽标做法；图标语义与配色为独立实现。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.common

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.theme.Spacing

/** 云同步图标的尺寸（与收藏星标、能力徽标同一视觉重量）。 */
private val CLOUD_ICON_SIZE = Spacing.lg

/**
 * 条目行尾的云端同步状态图标（见 `.ai/ISSUES.md` #76）。
 *
 * 两种形态，语义**明确区分**而不是「有/无」：
 * - [synced] = true → 实心云（`CloudDone`），`onSurfaceVariant` 弱化处理 ——
 *   「一切正常」不该抢眼；
 * - [synced] = false → 云加斜杠（`CloudOff`），`error` 色 —— 这是**需要用户注意**的状态
 *   （本地改动还没推上云），必须跳出来。
 *
 * ⚠️ KDBX 库调用方**不要**渲染本组件：KDBX 的文件即存储，没有「云端」这一层，
 * 画出来是在编造一个不存在的状态。
 */
@Composable
fun CloudSyncIcon(synced: Boolean, modifier: Modifier = Modifier) {
    val icon: ImageVector
    val tint: Color
    val descriptionRes: Int
    if (synced) {
        icon = Icons.Filled.CloudDone
        tint = MaterialTheme.colorScheme.onSurfaceVariant
        descriptionRes = R.string.sync_state_synced
    } else {
        icon = Icons.Filled.CloudOff
        tint = MaterialTheme.colorScheme.error
        descriptionRes = R.string.sync_state_pending
    }
    Icon(
        imageVector = icon,
        contentDescription = stringResource(descriptionRes),
        tint = tint,
        modifier = modifier.size(CLOUD_ICON_SIZE),
    )
}
