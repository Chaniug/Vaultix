/*
 * Vaultix — app:ui:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * **KDBX 网盘冲突对话框** —— 方案 §8 方案 B 的「三选项 UI」。
 *
 * ## 为什么必须是三个选项、且措辞必须写明后果
 *
 * 冲突（本地改了 + 远端也改了）时，**没有任何自动选择是安全的**：
 * 任选一边都意味着另一边那份改动**永久消失**。所以这里不做"默认推荐"，
 * 而是把三个选项的后果**逐一写在按钮旁边**，让用户带着代价去做选择。
 *
 * 三个选项的语义：
 * 1. [KdbxConflictChoice.KeepLocalUpload] —— 用本地覆盖远端（远端那份改动会丢）
 * 2. [KdbxConflictChoice.KeepRemoteDownload] —— 用远端覆盖本地（本地那份改动会丢）
 * 3. [KdbxConflictChoice.DecideLater] —— 先放着（**什么都不做**，状态停在冲突）
 *
 * ## 为什么没有「合并」选项
 *
 * 三方合并（方案 §8 方案 A）是**第二期**的事：它需要在条目级别做 diff，
 * 而 KDBX 没有稳定的"条目级版本"概念（kotpass 每次 encode 都会重生成向量）。
 * 在合并真正可信之前放一个按钮进去，用户点完会以为"两边都保住了" ——
 * 那是**比不给按钮更糟**的结果（用户会据此不再备份）。
 *
 * ⚠️ 所以第三项是「稍后再决定」而不是「自动合并」：宁可让用户手动两边各留一份，
 * 也不给一个可能悄悄丢数据的承诺。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.theme.Spacing

/**
 * 网盘冲突对话框。
 *
 * @param vaultName 库名（让用户确认自己正在处理哪一个库 —— 多库用户会同时遇到多个）。
 * @param onChoose 用户三选一。⚠️ 选中 [KdbxConflictChoice.DecideLater] 时**也必须回调**：
 *   关对话框这个动作本身要落成"状态停在冲突"，否则状态会卡在 `SYNCING`
 *   （UI 上是一个永远转不完的圈），见 `KdbxSyncOrchestrator.markDeferred`。
 * @param onDismiss 用户按返回键 / 点外面关掉 —— **等同于** [KdbxConflictChoice.DecideLater]。
 */
@Composable
fun KdbxConflictDialog(
    vaultName: String,
    onChoose: (KdbxConflictChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
        title = { Text(stringResource(R.string.kdbx_conflict_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.kdbx_conflict_body, vaultName),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(Spacing.md))
                Text(
                    text = stringResource(R.string.kdbx_conflict_no_auto),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(Spacing.md))

                ConflictOption(
                    icon = Icons.Filled.CloudUpload,
                    titleRes = R.string.kdbx_conflict_keep_local,
                    consequenceRes = R.string.kdbx_conflict_keep_local_hint,
                    onClick = { onChoose(KdbxConflictChoice.KeepLocalUpload) },
                )
                Spacer(Modifier.height(Spacing.sm))
                ConflictOption(
                    icon = Icons.Filled.CloudDownload,
                    titleRes = R.string.kdbx_conflict_keep_remote,
                    consequenceRes = R.string.kdbx_conflict_keep_remote_hint,
                    onClick = { onChoose(KdbxConflictChoice.KeepRemoteDownload) },
                )
                Spacer(Modifier.height(Spacing.sm))
                ConflictOption(
                    icon = Icons.Filled.Schedule,
                    titleRes = R.string.kdbx_conflict_later,
                    consequenceRes = R.string.kdbx_conflict_later_hint,
                    onClick = { onChoose(KdbxConflictChoice.DecideLater) },
                )
            }
        },
        // ⚠️ 不设 confirmButton：三个选项本身就是动作，再放一个"确定"会让用户
        //    以为要先选再确定（多一步、且容易选完忘了点）。
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * 一个冲突选项（图标 + 标题 + **后果说明**）。
 *
 * ⚠️ 用 [OutlinedButton] 而不是 `TextButton`：三选项是**并列的决策**，
 * 视觉重量必须相当；用 `TextButton` 会让第一个看起来像"推荐项"。
 * 颜色一律用默认（**不要**给「用本地覆盖」上红色）：两个覆盖选项的代价是对称的，
 * 特意标红一个会把用户往另一个推 —— 而那个同样会丢数据。
 */
@Composable
private fun ConflictOption(
    icon: ImageVector,
    titleRes: Int,
    consequenceRes: Int,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = stringResource(consequenceRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
