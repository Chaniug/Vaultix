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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.vaultix.model.KdbxCloudSyncStatus
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
 * ⚠️ KDBX 云端库**不要**用本组件回答「库在哪」：那是 [VaultSourceCloudIcon] 的职责，
 *    判据是 `VaultSummary.origin`。本组件只服务 Bitwarden 的条目级 `pending_ops`。
 *    #282 起两者形状一致（都是 `CloudDone`），但调用方与数据源仍严格分开。
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
 * 条目行尾的**库来源**云图标：这个库存在云端（OneDrive / WebDAV），不是本地文件。
 *
 * ## 前两轮（2026-11-10）：先多画、后少画
 *
 * #263 修掉「**本地** KDBX 挂上已同步的云」时，改成了「KDBX 一律不画」——
 * 把用户真正需要的那一朵也删了（过度修正）。#266 据用户反馈拆成三态，
 * 让 `VaultSummary.origin` 回答「这个库在不在云上」、Bitwarden `pending_ops`
 * 回答「这一条推上去了吗」，本地 KDBX 仍不画。这两轮的**判据本轮一个不动**。
 *
 * ## 2026-11-12 第八轮（#282）：形状统一，但语义不合并
 *
 * 用户反馈：「kdbx 的小云图标和 bitwarden 的云同步的图标不一样，不是带对勾的云图标。」
 * 前两轮刻意用**裸云**（地点）与**云+对勾**（状态）区分语义，但真机上两朵不同的云
 * 并排出现时，用户读到的是「这个不一样 / 是不是没同步」，而不是设计者想表达的
 * 「一个答地点、一个答状态」。⇒ 用户最新反馈优先：本组件改用 [Icons.Filled.CloudDone]，
 * 与 [CloudSyncIcon] 的「已同步」**同形同色**（`onSurfaceVariant` 弱化）。
 *
 * ⚠️ **形状统一 ≠ 语义合并**，下面三条底线本轮一个没动，由 `CloudSyncBadgeTest` 钉住：
 *    1. **本地 KDBX 仍然不画云** —— `cloudSyncBadgeOrNull` 对 LocalFile 返回 null，
 *       「有没有云」的判据始终是 `VaultSummary.origin`，不因形状改了就给本地库补一朵；
 *    2. KDBX **不产出条目级同步态** —— 整文件同步没有「这一条上云了」，
 *       `CLOUD_VAULT` 与 `SYNCED` 仍是两个不同的枚举值，读屏文案也不同
 *       （`sync_state_cloud_vault` = 「来自云端密码库」 vs `sync_state_synced` = 「已同步到云端」）；
 *    3. 「待上传 / 失败」仍由库级 `DetailSyncIndicator` / `SyncBadge` 用 `CloudOff` + error 承担。
 *
 * ⚠️ 弱化色（`onSurfaceVariant`）：它是**常态**（库一直在云上），不该抢眼。
 * ⚠️ 原 KDoc 的「裸云 = 地点，云+对勾 = 状态」「刻意不画对勾」即日起**作废**，
 *    历史理由留存在 `.ai/issues/06-界面与交互-part14.md`，不要在别处再引用。
 */
@Composable
fun VaultSourceCloudIcon(modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Filled.CloudDone,
        contentDescription = stringResource(R.string.sync_state_cloud_vault),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
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
 * 形态（2026-11-08 更新）：上传中 = [VaultixMorphingProgress] 变形圆点；
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
 * 上传中的**不确定进度条**：委托给 [VaultixMorphingProgressBar]（胶囊扫光）。
 *
 * 一次 KDBX 上传是「整文件条件写」，拿不到可信的百分比 ⇒ 用不确定进度而不是虚构数字。
 * 2026-11-08 换掉 3dp 老式直线（用户原话「很老实，不够新颖」），尺寸规格收进
 * `ExpressiveProgress.kt` 唯一封装点；本函数保留是让详情页调用处一行不用改。
 */
@Composable
fun DetailSyncProgress(modifier: Modifier = Modifier) {
    VaultixMorphingProgressBar(modifier = modifier.fillMaxWidth())
}

@Composable
fun DetailSyncIndicator(status: KdbxCloudSyncStatus?, modifier: Modifier = Modifier) {
    val uploading = isSyncUploading(status)
    if (uploading) {
        val label = stringResource(R.string.sync_dot_uploading)
        // 官方变形组件内部写死 48dp；封装里靠 wrapContentSize + graphicsLayer 缩到 24dp，
        // 才不会把「类型徽标 / 能力图标 / 云状态」这一行挤开。
        VaultixMorphingProgress(
            indicatorSize = CLOUD_ICON_SIZE,
            modifier = modifier.semantics { contentDescription = label },
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
