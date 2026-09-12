/*
 * Vaultix — app:ui:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * 添加库的**类型选择**对话框：Bitwarden 云端 / 本地 KDBX 文件。
 *
 * 为什么提到 `ui/common`：库列表（`+`）与设置页「密码库」两处都要这个入口。
 * 设置页过去**没有**任何添加入口 —— 而库列表路由在「已有一个库」时不可达
 * （根导航落在解锁页 / 主界面），于是用户**永远加不了 KDBX 库**
 * （`.ai/ISSUES.md` 记录的「KDBX 集成已交付却用不到」）。两处共用一份实现避免漂移。
 */
package io.vaultix.vaultix.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.AppFlavor

/**
 * 添加库的类型选择（Bitwarden 云端 / 本地 KDBX 文件）。
 *
 * @param onConnectBitwarden 选择云端同步 → 进登录流程。
 * @param onOpenKdbx 选择打开本地 `.kdbx` 文件 → 进选文件流程。
 * @param onDismiss 取消。
 */
@Composable
fun AddVaultTypeDialog(
    onConnectBitwarden: () -> Unit,
    onOpenKdbx: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vault_add_fab)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.vault_add_type_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                if (AppFlavor.supportsBitwarden) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.vault_connect_bitwarden)) },
                        supportingContent = { Text(stringResource(R.string.vault_add_bitwarden_desc)) },
                        leadingContent = { Icon(Icons.Filled.Cloud, contentDescription = null) },
                        modifier = Modifier.clickable(onClick = onConnectBitwarden),
                    )
                }
                ListItem(
                    headlineContent = { Text(stringResource(R.string.vault_open_kdbx)) },
                    supportingContent = { Text(stringResource(R.string.vault_add_kdbx_desc)) },
                    leadingContent = { Icon(Icons.Filled.Description, contentDescription = null) },
                    modifier = Modifier.clickable(onClick = onOpenKdbx),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
