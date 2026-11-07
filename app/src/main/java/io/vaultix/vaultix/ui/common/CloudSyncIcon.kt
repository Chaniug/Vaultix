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

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.vaultix.model.KdbxCloudSyncStatus
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.theme.VaultixCorner
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

/**
 * 详情页顶栏的**库级**云同步状态（11-07）。
 *
 * ## 与 [CloudSyncIcon] 的区别（别混用）
 *
 * | | CloudSyncIcon | 本组件 |
 * |---|---|---|
 * | 粒度 | 条目级（Bitwarden `pending_ops`） | 库级（`vaults.syncStatus`） |
 * | 适用 | 仅 Bitwarden | **仅 KDBX 网盘库** |
 * | 状态 | 已推 / 没推 | 待上传→同步中→已同步 / 失败 / 冲突 |
 *
 * ⚠️ KDBX 是**整文件**同步，没有「某一条上云了」的概念；
 *    `observeSyncStates` 对 KDBX 恒返回空 map，条目级云图标在这里画不出来。
 *
 * 形态（用户指定）：上传中 = M3 内置 [CircularProgressIndicator] 转动；
 * 成功 = [Icons.Filled.CloudDone] 小云弱化色；失败/冲突 = `CloudOff` + error。
 * 本地 SAF 库（`null` / `LOCAL_ONLY` / `REMOTE_CHANGED`）不画 —— 不编造不存在的云端状态。
 *
 * 文案**全部复用已有资源**，不新增字符串：读屏描述与视觉状态成对。
 */
/** 是否处于「本地改动正在推上云」的阶段（图标与进度条共用同一判据）。 */
fun isSyncUploading(status: KdbxCloudSyncStatus?): Boolean =
    status == KdbxCloudSyncStatus.PENDING_UPLOAD ||
        status == KdbxCloudSyncStatus.PENDING_UPLOAD_WITH_LOCAL_CHANGES ||
        status == KdbxCloudSyncStatus.SYNCING

/**
 * 上传中的**不确定进度条**（M3 内置 [LinearProgressIndicator]）。
 *
 * 一次 KDBX 上传是「整文件条件写」，拿不到可信的百分比 ⇒ 用不确定进度而不是虚构数字。
 * 与 [DetailSyncIndicator] 成对：上传中 = 转动 + 细进度条；完成 = 只剩小云。
 */
@Composable
fun DetailSyncProgress(modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        modifier = modifier
            .fillMaxWidth()
            .height(PROGRESS_HEIGHT)
            .clip(VaultixCorner.Pill),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
    )
}

@Composable
fun DetailSyncIndicator(status: KdbxCloudSyncStatus?, modifier: Modifier = Modifier) {
    val uploading = isSyncUploading(status)
    if (uploading) {
        val label = stringResource(R.string.sync_dot_uploading)
        CircularProgressIndicator(
            modifier = modifier
                .size(CLOUD_ICON_SIZE)
                .padding(2.dp)
                .semantics { contentDescription = label },
            strokeWidth = 2.dp,
        )
        return
    }
    val icon: ImageVector
    val tint: Color
    val descriptionRes: Int
    when (status) {
        KdbxCloudSyncStatus.IN_SYNC -> {
            icon = Icons.Filled.CloudDone
            tint = MaterialTheme.colorScheme.onSurfaceVariant
            descriptionRes = R.string.sync_state_synced
        }
        KdbxCloudSyncStatus.FAILED -> {
            icon = Icons.Filled.CloudOff
            tint = MaterialTheme.colorScheme.error
            descriptionRes = R.string.sync_dot_failed
        }
        KdbxCloudSyncStatus.CONFLICT -> {
            icon = Icons.Filled.CloudOff
            tint = MaterialTheme.colorScheme.error
            descriptionRes = R.string.sync_dot_conflict
        }
        else -> return
    }
    Icon(
        imageVector = icon,
        contentDescription = stringResource(descriptionRes),
        tint = tint,
        modifier = modifier.size(CLOUD_ICON_SIZE),
    )
}

/** 上传进度条的厚度：细到不抢标题，又能被一眼看见。 */
private val PROGRESS_HEIGHT = 3.dp
