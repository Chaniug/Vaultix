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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.common.AddVaultTypeDialog
import io.vaultix.vaultix.ui.common.deviceCanAuthenticate
import io.vaultix.vaultix.ui.theme.Spacing

/**
 * 「密码库管理」二级页（设置首页「密码库管理」一行进入）。
 *
 * ## 为什么要单独一页
 *
 * 2026-09-15 用户反馈：「设置页面的密码库、添加密码库、快速解锁设置，这几个好像都属于
 * 密码库管理的内容，我在想这些能不能放到一起管理。」
 *
 * 这条观察是对的，且可以量化：改之前这三件事**散在两个组**——
 * - 「密码库」组：当前密码库 · 添加密码库；
 * - 「解锁与隐私」组：快速解锁（夹在自动锁定与防截屏之间）。
 *
 * 「快速解锁」在语义上属于**库**（它为每个库单独登记一条解锁凭据，`quickUnlockVaults`
 * 就是"每库一行"的形态），把它放在隐私组里，用户要改"某个库怎么解锁"得先想到去隐私组找。
 *
 * ## 与 `.ai/decisions/设置页信息架构-定稿.md` 的关系
 *
 * 那份定稿 §3 把「快速解锁」放在「解锁与隐私」，并写了「**别重新设计分组**」。
 * 本页是**对该定稿的一次修订**（用户明确要求），差异与理由记在定稿的 §10。
 * 定稿的其余结论（破坏性动作置底、消灭单行组、文案三规则）**继续有效**，本页照办。
 *
 * ## 边界
 *
 * - 本页承载的是**库相关**的设置：当前库 / 添加库 / 网盘账号 / 解锁方式。
 * - ⚠️ 「解锁方式」组内的**三个设置行是 2026-09-17 内联**进来的（旧形态是"组内一行入口 +
 *   点进去的对话框"）：组名与组内唯一一行语义重复，而且白多一次导航。
 *   随之行为也变了 —— 逐库勾选挪进「管理解锁方式」向导，并且**默认全勾**
 *   （理由与边界见 `QuickUnlockController` 类 KDoc）。
 * - 「退出数据库」**不**搬进来：它是清本地缓存的数据动作，不是库管理
 *   （定稿 §2① 已经把它纠正到「数据」组置底，那是正确的，别回退）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultManagementScreen(
    onBack: () -> Unit,
    /** 「添加 Bitwarden 云端库」（导航到登录 / 添加流程）。 */
    onAddBitwardenVault: () -> Unit = {},
    /** 「打开本地 KDBX 文件」（导航到文件选择流程）。 */
    onAddKdbxVault: () -> Unit = {},
    /** 「从网盘添加」（导航到 WebDAV / OneDrive 配置流程）。 */
    onAddCloudVault: () -> Unit = {},
    /** 「网盘账号」（导航到 OneDrive / WebDAV 的账号与凭据管理，定稿 §11.7）。 */
    onOpenCloudAccounts: () -> Unit = {},
    /** 点一个**未解锁**的库时，去它的解锁页输主密码。 */
    onOpenLockedVault: (String) -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val active by viewModel.activeVault.collectAsStateWithLifecycle()
    val default by viewModel.defaultVault.collectAsStateWithLifecycle()
    val switchable by viewModel.switchableVaults.collectAsStateWithLifecycle()
    val quickUnlockState by viewModel.quickUnlock.state.collectAsStateWithLifecycle()

    var showVaultPicker by rememberSaveable { mutableStateOf(false) }
    var showAddDialog by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.group_vaults)) },
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
            // ---- 当前库 ----
            SettingsGroupTitle(stringResource(R.string.vault_management_group_current))
            SettingsGroupCard {
                SettingsRow(
                    icon = { Icon(Icons.Filled.Storage, contentDescription = null) },
                    title = stringResource(R.string.settings_active_vault),
                    subtitle = active?.name
                        ?: stringResource(R.string.settings_active_vault_none),
                    onClick = { showVaultPicker = true },
                )
                SettingsDivider()
                SettingsRow(
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    title = stringResource(R.string.vault_add_fab),
                    subtitle = stringResource(
                        if (switchable.isEmpty()) {
                            R.string.settings_add_vault_desc
                        } else {
                            R.string.settings_add_vault_desc_another
                        },
                    ),
                    onClick = { showAddDialog = true },
                )
                // ★ 入口放在**这一组里**而不是设置首页新开一组：
                //   首页新开一个单行组会违反定稿 §2③「消灭单行组」。
                //   语义上也对 —— 它就是"库的来源怎么连"。
                SettingsDivider()
                SettingsRow(
                    icon = { Icon(Icons.Filled.Cloud, contentDescription = null) },
                    title = stringResource(R.string.cloud_accounts_title),
                    subtitle = stringResource(R.string.cloud_accounts_entry_desc),
                    onClick = onOpenCloudAccounts,
                )
            }

            // ---- 解锁方式 ----
            // ★ 2026-09-17：两个开关**内联到这一组**里，不再点进对话框。
            //   旧形态是「组名『解锁方式』+ 组内唯一一行也叫『快速解锁』」—— 语义重复，
            //   而且白多一次导航（定稿 §11.11 的目标形态本来就是开关直出）。
            //   逐库勾选挪进了「管理解锁方式」的向导，且**默认全勾**。
            SettingsGroupTitle(stringResource(R.string.vault_management_group_unlock))
            SettingsGroupCard {
                QuickUnlockSettingsRows(
                    state = quickUnlockState,
                    canAuthenticate = deviceCanAuthenticate(context),
                    onToggleBiometric = viewModel.quickUnlock::toggleBiometric,
                    onTogglePin = viewModel.quickUnlock::togglePin,
                    onManage = viewModel.quickUnlock::manageUnlock,
                )
            }
        }
    }

    // ---- 对话框（全部是从设置首页搬过来的同一批，行为不变）----
    if (showVaultPicker) {
        ActiveVaultDialog(
            vaults = switchable,
            activeId = active?.id,
            defaultId = default?.id,
            // ⚠️ 只有**已解锁**的库才能成为活跃库（2026-09-15 修的 bug）：
            // 未解锁的库切过去后条目流是空的（KDBX 会话不在内存 / Bitwarden 无密钥），
            // 且「切库即锁旧库」会顺带把原来能看的库也锁掉 ⇒ 条目页与验证码页**全白**。
            // 所以未解锁项的点击语义是「去解锁」，不是「切过去」。
            onSelect = { vault ->
                if (vault.unlocked) {
                    viewModel.selectVault(vault.id)
                } else {
                    onOpenLockedVault(vault.id)
                }
                showVaultPicker = false
            },
            // 「设为默认」= 改冷启动先开哪个，**不要求当下解锁**（这是它的正当用途：
            // 用户明知道某库要输密码，仍希望下次开 App 直奔它）。故这里也先切活跃库，
            // 但只对已解锁库切 —— 未解锁库交给解锁页去打开。
            onSetDefault = { vault ->
                if (vault.unlocked) viewModel.selectVault(vault.id)
                viewModel.setDefaultVault(vault.id)
                showVaultPicker = false
            },
            onDismiss = { showVaultPicker = false },
        )
    }

    if (showAddDialog) {
        AddVaultTypeDialog(
            onConnectBitwarden = {
                showAddDialog = false
                onAddBitwardenVault()
            },
            onOpenKdbx = {
                showAddDialog = false
                onAddKdbxVault()
            },
            onAddCloudKdbx = {
                showAddDialog = false
                onAddCloudVault()
            },
            onDismiss = { showAddDialog = false },
        )
    }

    // ---- 对话框 ----
    // ⚠️ 「解锁方式」的设置**不再是对话框**（2026-09-17 内联进上面的组里）；
    //    这里只剩"库选择 / 添加库"两个对话框，外加下面的流程宿主。
    // 指纹认证 + 流程对话框（配置向导 → 输 PIN → 逐库问主密码 → 认证 → 结果）。
    // ⚠️ 不传 Activity：它自己在内部用 `rememberFragmentActivity()` 解析 ——
    // BiometricPrompt 要的是 `FragmentActivity`，而 `ComponentActivity` 与
    // `FragmentActivity` 是**兄弟**（不是父子），从这里传会编译失败（2026-09-16 CI 实录）。
    QuickUnlockHost(viewModel.quickUnlock)
}
