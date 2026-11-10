/*
 * Vaultix — app:ui · settings
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.theme.Spacing

/**
 * 「把本地 KDBX 放到网盘」引导页（2026-11-10）。
 *
 * ## 为什么是"引导页"而不是"一键迁移"
 *
 * 用户问的是「本地的 kdbx 如何接入到 onedrive」。技术上 Vaultix **能**上传
 * （`OneDriveGraphClient.upload` 已有原子上传 + 条件写 + 写后读回），但**不能**顺手把库"搬"过去：
 *
 * 1. 库的身份（`vaults.origin` / 主键）绑在文件位置上。搬完要让新库可用，得把
 *    origin、库 id、加密信封、已登记的指纹 / PIN、文件夹归属一起迁移；
 * 2. 原 SAF 文件删不删都是坏选择：删了用户以为丢了，不删就是两份会分叉的库；
 * 3. 用户自己在 OneDrive 里上传，再回来「从网盘添加」，代价只是**重输一次主密码**
 *    —— 而这条路不迁移任何东西，失败也不会毁掉已配好的解锁方式。
 *
 * ⇒ 本页只回答「怎么做」，不做任何写操作。措辞刻意**不劝、不催**：
 * 这一页从库的 ⋮ 菜单进来，用户不看也完全不影响任何功能（"不打扰"）。
 *
 * ## 为什么把"不自动搬"的理由写在页面上
 *
 * 一个密码管理器**有能力却不动手**，用户第一反应是"没做完"。与其让他在别处猜，
 * 不如在这里把代价摊开：省两步 vs 丢解锁凭据 —— 这笔账用户自己能算。
 *
 * ## 与「库的来源」的关系
 *
 * 本页的两个出口（网盘账号 / 从网盘添加）就是那一组里的入口；本页只是把"顺序"
 * 讲清楚。不复用 `VaultManagementScreen` 的导航回调，是因为本页**不该**依赖那一页的
 * 存在 —— 它俩是兄弟，不是父子。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KdbxCloudGuideScreen(
    onBack: () -> Unit,
    /** 「网盘账号」：配置 OneDrive / WebDAV 的登录与凭据（[CloudAccountsScreen]）。 */
    onOpenCloudAccounts: () -> Unit,
    /** 「从网盘添加」：走现有的添加流程（[io.vaultix.vaultix.ui.addvault.AddCloudVaultScreen]）。 */
    onAddCloudVault: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.kdbx_cloud_guide_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = Spacing.xxl),
        ) {
            // ⚠️ 2026-11-10：这一行 `Spacing.sm` 是**必需**的，不是凑数。
            //   二级页的 Scaffold 已消费掉顶栏让出的 inset，内容是贴顶栏下沿开始画的；
            //   而本页首元素要么是不带外边距的 SettingsGroupCard，要么是裸 Text
            //   ⇒ 少这一行就有 0dp 上留白，与「自动填充」「权限管理」两页的 8dp 对不齐，
            //   同一层级的页面换一页就"顶上去一截"。自动填充 / 权限两页靠
            //   AutofillStatusCard / PermissionStatusCard 自带的 vertical = md 撑出边距，
            //   本页没有那张卡，只能自己补。
            Spacer(Modifier.height(Spacing.sm))
            // 开场先把结论说完：本地文件 → 上传 → 从网盘添加。
            // ⚠️ 用 bodyMedium 而不是标题：页面上真正要读的是三步，开场只是"为什么点进来"。
            Text(
                text = stringResource(R.string.kdbx_cloud_guide_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg),
            )

            // ---- 三步 ----
            SettingsGroupTitle(stringResource(R.string.kdbx_cloud_guide_steps))
            SettingsGroupCard {
                // ⚠️ 三行都**没有 onClick**：它们不是设置项，是步骤说明。
                //    给它们点击态会让用户以为点一下就能自动完成（正是本页否定的做法）。
                SettingsRow(
                    icon = { Icon(Icons.Filled.CloudUpload, contentDescription = null) },
                    title = stringResource(R.string.kdbx_cloud_guide_step_upload_title),
                    subtitle = stringResource(R.string.kdbx_cloud_guide_step_upload_desc),
                )
                SettingsDivider()
                SettingsRow(
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    title = stringResource(R.string.kdbx_cloud_guide_step_add_title),
                    subtitle = stringResource(R.string.kdbx_cloud_guide_step_add_desc),
                )
                SettingsDivider()
                SettingsRow(
                    icon = { Icon(Icons.Filled.Key, contentDescription = null) },
                    title = stringResource(R.string.kdbx_cloud_guide_step_unlock_title),
                    subtitle = stringResource(R.string.kdbx_cloud_guide_step_unlock_desc),
                )
            }

            // ---- 为什么不自动搬（纯文字：这是解释，不是可操作的项）----
            SettingsGroupTitle(stringResource(R.string.kdbx_cloud_guide_why_title))
            Text(
                text = stringResource(R.string.kdbx_cloud_guide_why_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg),
            )

            // ---- 两个出口 ----
            SettingsGroupTitle(stringResource(R.string.kdbx_cloud_guide_actions))
            SettingsGroupCard {
                SettingsRow(
                    icon = { Icon(Icons.Filled.Cloud, contentDescription = null) },
                    title = stringResource(R.string.cloud_accounts_title),
                    subtitle = stringResource(R.string.cloud_accounts_entry_desc),
                    onClick = onOpenCloudAccounts,
                )
                SettingsDivider()
                SettingsRow(
                    icon = { Icon(Icons.Filled.CloudUpload, contentDescription = null) },
                    title = stringResource(R.string.vault_add_cloud),
                    subtitle = stringResource(R.string.vault_add_cloud_desc),
                    onClick = onAddCloudVault,
                )
            }

            // 页脚：把"旧文件怎么办"说清楚 —— 不说，用户会以为 App 已经替他删了。
            Text(
                text = stringResource(R.string.kdbx_cloud_guide_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
            )
        }
    }
}
