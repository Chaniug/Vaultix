/*
 * Vaultix — app:ui · settings
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * **「网盘账号」二级页** —— 网盘账号的**全生命周期**都在这里（2026-09-18 补齐"可操作"）。
 *
 * ## 为什么必须独立成页（这是病因，不是排版偏好）
 *
 * 用户实测：「**返回到添加密码库界面，登录状态就没了**」—— 而且「根本不用划掉后台」。
 *
 * | | 生命周期 |
 * |---|---|
 * | OneDrive 登录态 / WebDAV 凭据 | **长**（跨进程跨页面，活在 MSAL 缓存 / `SecureCredentialStore`） |
 * | 「添加密码库」那一页 | **短** —— 导航路由，**一返回 ViewModel 即销毁** |
 *
 * ⇒ 把长寿命的状态存在短寿命页面里，**必然"返回就没了"**。
 * 本页就是给它一个**与状态寿命匹配的宿主**：设置里的持久页面。
 *
 * ## 2026-09-18：从"只读清单"升级为"可操作"
 *
 * 上一版只能列出来。用户看到账号"未连接"却在本页什么都做不了，只能去别处碰运气。
 * 现在补齐：
 *
 * | 动作 | WebDAV | OneDrive |
 * |---|---|---|
 * | 新增账号 | ✅ 填地址/账号/密码 + **连接自检** | ✅ 登录 |
 * | 自检 | ✅ 独立【测试连接】按钮 + 4 态状态卡 | —（登录态即连接态） |
 * | 换号 | 重填即可（同一地址同一账号会覆盖） | ✅ 切换账号 |
 * | 注销 | ✅ 删凭据 | ✅ 清 MSAL 缓存 |
 *
 * ⚠️ **注销会连带影响同账号的所有库**（凭据是账号级、被多个库共用）⇒
 * 确认框里必须写出**影响面**，不能点了就删。
 *
 * ## 数据从哪来
 *
 * [CloudAccountInventory] —— **已保存的凭据/登录会话** ∪ **库的 origin**（见该文件头
 * 2026-09-18 的修正）。第二个来源提供"被哪些库在用"与"从哪个目录开始浏览"。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.vaultix.vaultix.R
import io.vaultix.vaultix.remote.CloudAccount
import io.vaultix.vaultix.remote.CloudAccountKind
import io.vaultix.vaultix.ui.common.rememberFragmentActivity
import io.vaultix.vaultix.ui.theme.Spacing

private val BUTTON_HEIGHT = 48.dp
private val INLINE_ICON = 18.dp
private val INLINE_PROGRESS = 18.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudAccountsScreen(
    onBack: () -> Unit,
    viewModel: CloudAccountsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    // ⚠️ OneDrive 登录**必须传真实 Activity**：MSAL 要拉起系统授权页，
    //   传 applicationContext 拿不到 Activity 就直接失败。
    val activity = rememberFragmentActivity()
    val focusManager = LocalFocusManager.current

    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeNotice()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cloud_accounts_title)) },
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
                .imePadding()
                .padding(horizontal = Spacing.xl),
        ) {
            Text(
                text = stringResource(R.string.cloud_accounts_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.sm),
            )
            Spacer(Modifier.height(Spacing.lg))

            // ⚠️ 「空」有两态必须分开（本项目的老坑）：还在读 vs 真的一个都没有。
            //    塌进一个 isEmpty() 就是**假空态** —— 用户会以为"我配的账号丢了"。
            when {
                state.loading -> Text(
                    text = stringResource(R.string.cloud_accounts_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                state.accounts.isEmpty() && !state.formVisible -> EmptyHint()

                else -> state.accounts.forEach { account ->
                    CloudAccountCard(
                        account = account,
                        busy = state.busy,
                        onRemove = { viewModel.requestRemoval(account) },
                        onReconfigure = { viewModel.startReconfigure(account) },
                        onSignIn = { force ->
                            if (activity == null) {
                                viewModel.reportMissingActivity()
                            } else {
                                viewModel.signInOneDrive(activity, forceAccountChooser = force)
                            }
                        },
                    )
                    Spacer(Modifier.height(Spacing.md))
                }
            }

            Spacer(Modifier.height(Spacing.md))
            if (state.formVisible) {
                AddAccountForm(
                    state = state,
                    viewModel = viewModel,
                    focusClear = { focusManager.clearFocus() },
                    onSignIn = { force ->
                        // ⚠️ 拿不到 Activity 就**如实报错**，不静默禁按钮
                        //    （那样用户只看到"点了没反应"，既不知原因也不知能做什么）。
                        if (activity == null) {
                            viewModel.reportMissingActivity()
                        } else {
                            viewModel.signInOneDrive(activity, forceAccountChooser = force)
                        }
                    },
                )
            } else {
                OutlinedButton(
                    onClick = viewModel::showAddForm,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(INLINE_ICON),
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text(stringResource(R.string.cloud_accounts_add))
                }
            }

            state.error?.let { error ->
                Spacer(Modifier.height(Spacing.sm))
                ErrorText(error)
            }
            Spacer(Modifier.height(Spacing.xxl))
        }
    }

    // 注销确认：★ 必须写出**影响面** —— 凭据是账号级的，删了同账号所有库都打不开。
    state.pendingRemoval?.let { account ->
        AlertDialog(
            onDismissRequest = viewModel::dismissRemoval,
            title = { Text(stringResource(R.string.cloud_accounts_remove_title)) },
            text = {
                Text(
                    if (account.vaultCount > 0) {
                        stringResource(R.string.cloud_accounts_remove_impact, account.vaultCount)
                    } else {
                        stringResource(R.string.cloud_accounts_remove_no_impact)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRemoval) {
                    Text(
                        stringResource(R.string.cloud_accounts_remove_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissRemoval) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

// ---------------------------------------------------------------- 账号卡

@Composable
private fun CloudAccountCard(
    account: CloudAccount,
    busy: Boolean,
    onRemove: () -> Unit,
    onReconfigure: () -> Unit,
    onSignIn: (forceAccountChooser: Boolean) -> Unit,
) {
    SettingsGroupCard {
        SettingsRow(
            icon = {
                Icon(
                    imageVector = if (account.kind == CloudAccountKind.ONEDRIVE) {
                        Icons.Filled.Cloud
                    } else {
                        Icons.Filled.Storage
                    },
                    contentDescription = null,
                )
            },
            title = account.label,
            subtitle = stringResource(account.kind.subtitleRes()) + " · " +
                stringResource(R.string.cloud_accounts_used_by, account.vaultCount),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.xl, end = Spacing.lg, bottom = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            // OneDrive：未连接时给"登录"，已连接时给"换号"。
            if (account.kind == CloudAccountKind.ONEDRIVE) {
                TextButton(
                    onClick = { onSignIn(account.connected) },
                    enabled = !busy,
                ) {
                    Text(
                        stringResource(
                            if (account.connected) {
                                R.string.cloud_accounts_onedrive_switch
                            } else {
                                R.string.cloud_accounts_onedrive_signin
                            },
                        ),
                    )
                }
            }
            // WebDAV：改密码是最常见的故障 ⇒ 给一条"重填密码"的路，
            // 而不是逼用户注销再重加（那会连账号名一起丢掉）。
            if (account.kind == CloudAccountKind.WEBDAV && !account.connected) {
                TextButton(onClick = onReconfigure, enabled = !busy) {
                    Text(stringResource(R.string.cloud_accounts_reconfigure))
                }
            }
            TextButton(onClick = onRemove, enabled = !busy) {
                Text(
                    stringResource(R.string.cloud_accounts_remove),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
    // 连接状态**单独一行**：它**会变**（token 过期 / 凭据被清 / 库被移除），
    // 混进副标题会让人以为它是账号的固有属性。
    Text(
        text = stringResource(
            if (account.connected) {
                R.string.cloud_accounts_connected
            } else {
                R.string.cloud_accounts_disconnected
            },
        ),
        style = MaterialTheme.typography.bodySmall,
        color = if (account.connected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.error
        },
        modifier = Modifier.padding(start = Spacing.xl, top = Spacing.xs, bottom = Spacing.sm),
    )
}

@Composable
private fun EmptyHint() {
    Text(
        text = stringResource(R.string.cloud_accounts_empty),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ---------------------------------------------------------------- 新增账号

/**
 * 新增账号：**选来源 → 填该来源要的东西 → 连接**。
 *
 * WebDAV 有独立的【测试连接】按钮（而不是"填完直接连"）——
 * 用户填错地址是常态，先让他确认一次"这台机器能连上"，比连上之后再报错清楚得多。
 */
@Composable
private fun AddAccountForm(
    state: CloudAccountsViewModel.UiState,
    viewModel: CloudAccountsViewModel,
    focusClear: () -> Unit,
    onSignIn: (forceAccountChooser: Boolean) -> Unit,
) {
    Text(
        text = stringResource(R.string.cloud_accounts_add_title),
        style = MaterialTheme.typography.titleSmall,
    )
    Spacer(Modifier.height(Spacing.sm))
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        CloudAccountKind.entries.forEach { kind ->
            FilterChip(
                selected = kind == state.kind,
                onClick = { viewModel.onKindChange(kind) },
                label = { Text(stringResource(kind.subtitleRes())) },
            )
        }
    }
    Spacer(Modifier.height(Spacing.md))

    if (state.kind == CloudAccountKind.WEBDAV) {
        WebDavFields(state = state, viewModel = viewModel)
        Spacer(Modifier.height(Spacing.md))
        ProbeButton(state = state, onClick = {
            focusClear()
            viewModel.probeWebDav()
        })
        Spacer(Modifier.height(Spacing.sm))
        ProbeStatusCard(state = state)
    } else {
        Text(
            text = stringResource(R.string.cloud_accounts_onedrive_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.md))
        FilledTonalButton(
            onClick = { onSignIn(false) },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().height(BUTTON_HEIGHT),
        ) {
            Text(stringResource(R.string.cloud_accounts_onedrive_signin))
        }
    }

    Spacer(Modifier.height(Spacing.sm))
    TextButton(onClick = viewModel::hideAddForm, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.action_cancel))
    }
}

@Composable
private fun WebDavFields(
    state: CloudAccountsViewModel.UiState,
    viewModel: CloudAccountsViewModel,
) {
    OutlinedTextField(
        value = state.serverUrl,
        onValueChange = viewModel::onServerUrlChange,
        label = { Text(stringResource(R.string.add_cloud_server)) },
        supportingText = { Text(stringResource(R.string.add_cloud_server_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    if (state.insecureHttp) {
        // 只提示、不阻拦：局域网 NAS 用 http 是常态，拦下来等于让这批人用不了。
        Text(
            text = stringResource(R.string.add_cloud_insecure_http),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = Spacing.xs),
        )
    }
    Spacer(Modifier.height(Spacing.sm))
    OutlinedTextField(
        value = state.username,
        onValueChange = viewModel::onUsernameChange,
        label = { Text(stringResource(R.string.add_cloud_username)) },
        singleLine = true,
        leadingIcon = {
            Icon(Icons.Filled.Person, contentDescription = null)
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(Spacing.sm))
    OutlinedTextField(
        value = state.password,
        onValueChange = viewModel::onPasswordChange,
        label = { Text(stringResource(R.string.add_cloud_password)) },
        singleLine = true,
        visualTransformation = state.password.visualTransformation(state.passwordVisible),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { viewModel.onPasswordVisibleChange(!state.passwordVisible) }) {
                Icon(
                    imageVector = if (state.passwordVisible) {
                        Icons.Filled.VisibilityOff
                    } else {
                        Icons.Filled.Visibility
                    },
                    contentDescription = null,
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ProbeButton(state: CloudAccountsViewModel.UiState, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = state.canProbe,
        modifier = Modifier.fillMaxWidth().height(BUTTON_HEIGHT),
    ) {
        if (state.probe == ProbeState.PROBING) {
            CircularProgressIndicator(
                modifier = Modifier.size(INLINE_PROGRESS),
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(Spacing.sm))
        }
        Text(stringResource(R.string.cloud_accounts_test_connection))
    }
}

/**
 * 连接状态卡（4 态）。
 *
 * ⚠️ 刻意不做成 `Boolean`：**"没测过"** 和 **"测过但失败"** 是两件不同的事 ——
 * 前者用户该去测，后者用户该去改地址。塌成一个"连接失败"红字，
 * 会把"你还没测"说成"你配错了"。
 */
@Composable
private fun ProbeStatusCard(state: CloudAccountsViewModel.UiState) {
    if (state.probe == ProbeState.IDLE && state.probeMessage == null) return
    val (text, color) = when (state.probe) {
        ProbeState.IDLE -> stringResource(R.string.cloud_accounts_probe_idle) to
            MaterialTheme.colorScheme.onSurfaceVariant
        ProbeState.PROBING -> stringResource(R.string.cloud_accounts_probe_running) to
            MaterialTheme.colorScheme.onSurfaceVariant
        ProbeState.OK -> stringResource(R.string.cloud_accounts_probe_ok) to
            MaterialTheme.colorScheme.primary
        ProbeState.FAILED -> (state.probeMessage ?: stringResource(R.string.cloud_accounts_probe_failed)) to
            MaterialTheme.colorScheme.error
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            modifier = Modifier.padding(Spacing.md),
        )
    }
}

// ---------------------------------------------------------------- 小组件

@Composable
private fun ErrorText(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

private fun CloudAccountKind.subtitleRes(): Int = when (this) {
    CloudAccountKind.ONEDRIVE -> R.string.cloud_accounts_kind_onedrive
    CloudAccountKind.WEBDAV -> R.string.cloud_accounts_kind_webdav
}

private fun String.visualTransformation(visible: Boolean): VisualTransformation =
    if (visible) VisualTransformation.None else PasswordVisualTransformation()
