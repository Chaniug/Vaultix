/*
 * Vaultix — app:ui:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * 添加库的**类型选择**对话框：Bitwarden 云端 / 本地 KDBX 文件 / 网盘上的 KDBX。
 *
 * 为什么提到 `ui/common`：库列表（`+`）与设置页「密码库」两处都要这个入口。
 * 设置页过去**没有**任何添加入口 —— 而库列表路由在「已有一个库」时不可达
 * （根导航落在解锁页 / 主界面），于是用户**永远加不了 KDBX 库**
 * （`.ai/ISSUES.md` 记录的「KDBX 集成已交付却用不到」）。两处共用一份实现避免漂移。
 */
package io.vaultix.vaultix.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.AppFlavor
import io.vaultix.vaultix.ui.theme.Spacing

/** 类型选择卡片的圆角（与设置页 `SettingsRow` 同族）。 */
private val VAULT_TYPE_CARD_CORNER = 20.dp

/** 类型选择卡片最小高度。 */
private val VAULT_TYPE_CARD_MIN_HEIGHT = 76.dp

/** 图标槽（28dp，与设置页 `SettingsRow` 一致）。 */
private val VAULT_TYPE_ICON_BOX = 28.dp

/**
 * 添加库的类型选择（Bitwarden 云端 / 本地 KDBX 文件 / 网盘上的 KDBX）。
 *
 * ## 2026-09-15 重做
 *
 * 用户真机反馈「添加密码库这个页面好简陋」。旧实现是裸 `AlertDialog` +
 * 两个 `ListItem`：`ListItem` 默认**无卡片底**，两个选项糊成一片白底文字，
 * 且没有"这是一个可点的选项"的视觉暗示（只能靠文字猜）。
 *
 * 现改为**可选卡片**（与设置页 `SettingsRow` 同一套规格：20dp 圆角 +
 * `surfaceContainerHigh` 底 + 28dp 图标槽 + primary 图标 + 右侧箭头），
 * 并套用 `ui/common/DialogShell.kt` 的统一面板。
 *
 * ## 2026-09-17 加第三个入口：从网盘添加
 *
 * 「本地 KDBX 文件」与「网盘上的 KDBX」是**同一个引擎的两种文件来源**
 * （见 `data:kdbx` 的 `KdbxFileSource`），但对用户是两件不同的事：
 * 一个要经 SAF 选文件，一个要配服务器与账号。合成一个入口会让两套输入混在一页。
 *
 * ⚠️ 新入口**加在这里**（共用对话框）而不是散到设置首页 ——
 * 设置页信息架构是拍过板的（`decisions/设置页信息架构-定稿.md`），
 * 往首页加行等于把它推翻。这里加则两处调用方一起变，不会漂移。
 *
 * ⚠️ **行为零变化**：原有两个选项的接线、`AppFlavor.supportsBitwarden` 条件判断
 * 完全保留。本对话框被设置页与库列表页**两处共用**（见文件头说明）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddVaultTypeDialog(
    onConnectBitwarden: () -> Unit,
    onOpenKdbx: () -> Unit,
    onAddCloudKdbx: () -> Unit,
    onDismiss: () -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        DialogSurface {
            DialogHeader(title = stringResource(R.string.vault_add_fab))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.lg),
            ) {
                DialogSectionHint(stringResource(R.string.vault_add_type_hint))
                if (AppFlavor.supportsBitwarden) {
                    VaultTypeCard(
                        icon = Icons.Filled.Cloud,
                        title = stringResource(R.string.vault_connect_bitwarden),
                        subtitle = stringResource(R.string.vault_add_bitwarden_desc),
                        onClick = onConnectBitwarden,
                    )
                }
                VaultTypeCard(
                    icon = Icons.Filled.Description,
                    title = stringResource(R.string.vault_open_kdbx),
                    subtitle = stringResource(R.string.vault_add_kdbx_desc),
                    onClick = onOpenKdbx,
                )
                VaultTypeCard(
                    icon = Icons.Filled.CloudSync,
                    title = stringResource(R.string.vault_add_cloud),
                    subtitle = stringResource(R.string.vault_add_cloud_desc),
                    onClick = onAddCloudKdbx,
                )
            }

            DialogActions {
                DialogDismissButton(onDismiss)
            }
        }
    }
}

/**
 * 一个库类型的可选卡片（图标 + 标题 + 副标题 + 右侧箭头）。
 *
 * 抽出来是因为两个选项的规格必须**逐字一致** —— 内联两遍很容易在一处调了间距、
 * 另一处忘了，正是「两个选项看起来不一样高」这类问题的来源。
 */
@Composable
private fun VaultTypeCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onClick, role = Role.Button),
        shape = RoundedCornerShape(VAULT_TYPE_CARD_CORNER),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = VAULT_TYPE_CARD_MIN_HEIGHT)
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(VAULT_TYPE_ICON_BOX),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(Spacing.lg))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
