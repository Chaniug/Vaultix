/*
 * Vaultix — app:ui:addvault
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 从网盘添加 KDBX 库：配置来源 → 选文件 → 主密码 → 入库。
 *
 * 两段式而不是一页塞满：**配置**（服务器/账号，或登录 OneDrive）与**浏览**
 * （在目录树里选库）是完全不同的两件事，混在一页会让"我现在该填哪个框"变成猜谜。
 * 连接成功后才翻到第二段，此时第一段的输入已经用不上、也不该再显示
 * （改一个已生效的服务器地址只会让人以为改了就会生效）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.addvault

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.vaultix.data.kdbx.KdbxFileEntry
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.common.VaultixWavyProgressBar
import io.vaultix.vaultix.ui.common.rememberFragmentActivity
import io.vaultix.vaultix.ui.error.UnlockUiError
import io.vaultix.vaultix.ui.error.unlockErrorText
import io.vaultix.vaultix.ui.theme.Spacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val BUTTON_HEIGHT = 48.dp
private val INLINE_PROGRESS = 20.dp

/**
 * 从网盘添加 KDBX 库。
 *
 * ⚠️ OneDrive 的登录**必须传真实 Activity**：MSAL 要拉起系统授权页，
 * 传 `applicationContext` 拿不到 Activity 就直接失败。这里用
 * [rememberFragmentActivity] 解析当前宿主（MainActivity 本身是 `FragmentActivity`）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddCloudVaultScreen(
    onBack: () -> Unit,
    onAdded: () -> Unit,
    viewModel: AddCloudVaultViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val activity = rememberFragmentActivity()
    // 文案先取好：LaunchedEffect 里不能直接 stringResource。
    val addedText = stringResource(R.string.add_cloud_added)
    val updatedText = stringResource(R.string.add_cloud_updated)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is AddCloudVaultViewModel.Event.VaultAdded -> {
                    snackbarHostState.showSnackbar(if (event.isUpdate) updatedText else addedText)
                    onAdded()
                }
            }
        }
    }

    // ★ 进页面时向 MSAL 要一次缓存账户（2026-09-17）。
    //   本页是导航路由 ⇒ 一返回这个 ViewModel 就销毁，再进来是全新的、登录态为 null。
    //   不主动恢复的话，界面会把"MSAL 里登录着"显示成"没登录"，逼用户重走一遍授权页。
    LaunchedEffect(Unit) { viewModel.restoreOneDriveSessionIfAny() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_cloud_title)) },
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
            if (state.browsing) {
                BrowserSection(state = state, viewModel = viewModel)
            } else {
                ConfigSection(state = state, viewModel = viewModel, activity = activity)
            }
            Spacer(Modifier.height(Spacing.xxl))
        }
    }
}

// ---------------------------------------------------------------- 配置阶段

/** 连接阶段：选来源 → 填该来源要的东西 → 连接并浏览。 */
@Composable
private fun ConfigSection(
    state: AddCloudVaultViewModel.UiState,
    viewModel: AddCloudVaultViewModel,
    activity: FragmentActivity?,
) {
    Text(
        text = stringResource(R.string.add_cloud_subtitle),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(Spacing.lg))

    ProviderRow(selected = state.provider, onSelect = viewModel::onProviderChange)
    Spacer(Modifier.height(Spacing.lg))

    when (state.provider) {
        CloudProvider.WEBDAV -> WebDavFields(state = state, viewModel = viewModel)
        CloudProvider.ONEDRIVE -> OneDriveFields(
            state = state,
            viewModel = viewModel,
            activity = activity,
        )
    }

    ErrorLine(state.error)
    BusyLine(visible = state.busy, label = stringResource(R.string.add_cloud_connecting))

    Spacer(Modifier.height(Spacing.lg))
    ConnectButton(state = state, viewModel = viewModel, activity = activity)
}

/** 来源二选一（两个 chip 而不是下拉菜单：只有两个选项时下拉是多余的一次点击）。 */
@Composable
private fun ProviderRow(selected: CloudProvider, onSelect: (CloudProvider) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        CloudProvider.entries.forEach { provider ->
            FilterChip(
                selected = provider == selected,
                onClick = { onSelect(provider) },
                label = { Text(stringResource(provider.labelRes())) },
            )
        }
    }
}

/** WebDAV 的三个输入框 + 明文 HTTP 提示。 */
@Composable
private fun WebDavFields(
    state: AddCloudVaultViewModel.UiState,
    viewModel: AddCloudVaultViewModel,
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
            SecretVisibilityToggle(
                visible = state.passwordVisible,
                onToggle = viewModel::onPasswordVisibleChange,
            )
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** OneDrive：登录说明 + 已登录态（含换号与注销）。 */
@Composable
private fun OneDriveFields(
    state: AddCloudVaultViewModel.UiState,
    viewModel: AddCloudVaultViewModel,
    activity: FragmentActivity?,
) {
    Text(
        text = stringResource(R.string.add_cloud_onedrive_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val account = state.accountName ?: return
    Spacer(Modifier.height(Spacing.md))
    Text(
        text = stringResource(R.string.add_cloud_onedrive_signed_in, account),
        style = MaterialTheme.typography.bodyMedium,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        // ⚠️ 「切换账号」= signOut + signIn(forceAccountChooser)，两件都要做 ——
        //    只做一件 MSAL 都可能静默复用旧账户（见 ViewModel 里那段的说明）。
        TextButton(
            onClick = {
                if (activity == null) {
                    viewModel.reportMissingActivity()
                } else {
                    viewModel.switchOneDriveAccount(activity)
                }
            },
            enabled = !state.busy,
        ) {
            Text(stringResource(R.string.add_cloud_onedrive_switch))
        }
        TextButton(onClick = viewModel::signOutOneDrive) {
            Text(stringResource(R.string.add_cloud_onedrive_signout))
        }
    }
}

/** 「连接并浏览」/「登录」——两种来源的按钮文案与动作不同。 */
@Composable
private fun ConnectButton(
    state: AddCloudVaultViewModel.UiState,
    viewModel: AddCloudVaultViewModel,
    activity: FragmentActivity?,
) {
    val focusManager = LocalFocusManager.current
    FilledTonalButton(
        onClick = {
            focusManager.clearFocus()
            when (state.provider) {
                CloudProvider.WEBDAV -> viewModel.connectWebDav()
                // ⚠️ 传真实 Activity（MSAL 要拉起授权页）。拿不到就**如实报错**，
                //    不静默禁按钮 —— 那样用户只看到"点了没反应"。
                CloudProvider.ONEDRIVE -> if (activity == null) {
                    viewModel.reportMissingActivity()
                } else {
                    viewModel.connectOneDrive(activity)
                }
            }
        },
        enabled = state.canConnect,
        modifier = Modifier
            .fillMaxWidth()
            .height(BUTTON_HEIGHT),
    ) {
        Text(
            stringResource(
                if (state.provider == CloudProvider.ONEDRIVE) {
                    R.string.add_cloud_onedrive_signin
                } else {
                    R.string.add_cloud_connect
                },
            ),
        )
    }
}

// ---------------------------------------------------------------- 浏览阶段

/** 浏览阶段：目录条 + 条目列表 + 主密码 + 提交。 */
@Composable
private fun BrowserSection(
    state: AddCloudVaultViewModel.UiState,
    viewModel: AddCloudVaultViewModel,
) {
    DirectoryBar(state = state, viewModel = viewModel)
    Spacer(Modifier.height(Spacing.sm))

    if (state.entries.isEmpty()) {
        Text(
            text = stringResource(R.string.add_cloud_dir_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        state.entries.forEach { entry ->
            EntryRow(
                entry = entry,
                selected = entry.name == state.selectedName,
                onSelect = { viewModel.onSelectFile(entry) },
                onOpen = { viewModel.openDirectory(entry) },
            )
        }
    }

    Spacer(Modifier.height(Spacing.lg))
    MasterPasswordField(state = state, viewModel = viewModel)
    ErrorLine(state.error)
    BusyLine(visible = state.busy, label = stringResource(R.string.add_cloud_working))
    Spacer(Modifier.height(Spacing.lg))
    SubmitButton(state = state, onSubmit = viewModel::submit)
}

/** 当前目录 + 上一级 + 重新配置。 */
@Composable
private fun DirectoryBar(
    state: AddCloudVaultViewModel.UiState,
    viewModel: AddCloudVaultViewModel,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = viewModel::goUp, enabled = state.canGoUp && !state.busy) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.add_cloud_up),
            )
        }
        Text(
            text = state.directoryLabel.ifBlank { stringResource(R.string.add_cloud_dir_root) },
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = viewModel::resetConnection) {
            Text(stringResource(R.string.add_cloud_reconfigure))
        }
    }
}

/**
 * 一行条目。
 *
 * 目录与库文件是**两种不同的动作**（进目录 vs 选中它），所以视觉也要分开：
 * 目录用箭头（"还有下一层"），文件用单选圈（"选中它"）。
 * 混成同一种样子，用户会以为点文件也能进去。
 */
@Composable
private fun EntryRow(
    entry: KdbxFileEntry,
    selected: Boolean,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
) {
    ListItem(
        modifier = if (entry.isDirectory) {
            Modifier.clickable(onClick = onOpen)
        } else {
            Modifier.clickable(onClick = onSelect, role = Role.RadioButton)
        },
        leadingContent = {
            Icon(
                imageVector = if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.Key,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        headlineContent = { Text(entry.name) },
        supportingContent = { EntryMeta(entry) },
        trailingContent = {
            if (entry.isDirectory) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.add_cloud_entry_folder),
                )
            } else {
                RadioButton(selected = selected, onClick = onSelect)
            }
        },
    )
}

/** 大小 + 修改时间（两者都可能拿不到 —— 拿不到就不显示，而不是显示 "null"）。 */
@Composable
private fun EntryMeta(entry: KdbxFileEntry) {
    val meta = listOfNotNull(formatSize(entry.sizeBytes), formatTime(entry.lastModified))
    if (meta.isEmpty()) return
    Text(
        text = meta.joinToString(separator = " · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun MasterPasswordField(
    state: AddCloudVaultViewModel.UiState,
    viewModel: AddCloudVaultViewModel,
) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = state.masterPassword,
        onValueChange = viewModel::onMasterPasswordChange,
        label = { Text(stringResource(R.string.add_cloud_master_password)) },
        singleLine = true,
        visualTransformation = state.masterPassword.visualTransformation(
            state.masterPasswordVisible,
        ),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = {
            focusManager.clearFocus()
            viewModel.submit()
        }),
        trailingIcon = {
            SecretVisibilityToggle(
                visible = state.masterPasswordVisible,
                onToggle = viewModel::onMasterPasswordVisibleChange,
            )
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SubmitButton(state: AddCloudVaultViewModel.UiState, onSubmit: () -> Unit) {
    val focusManager = LocalFocusManager.current
    FilledTonalButton(
        onClick = {
            focusManager.clearFocus()
            onSubmit()
        },
        enabled = state.canSubmit,
        modifier = Modifier
            .fillMaxWidth()
            .height(BUTTON_HEIGHT),
    ) {
        if (state.busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(INLINE_PROGRESS),
                strokeWidth = 2.dp,
            )
        } else {
            Text(stringResource(R.string.add_cloud_submit))
        }
    }
}

// ---------------------------------------------------------------- 小组件

/** 密码可见性开关（两处密码框共用，避免一处的图标行为与另一处不一致）。 */
@Composable
private fun SecretVisibilityToggle(visible: Boolean, onToggle: (Boolean) -> Unit) {
    IconButton(onClick = { onToggle(!visible) }) {
        Icon(
            imageVector = if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
            contentDescription = stringResource(
                if (visible) {
                    R.string.add_vault_password_hidden
                } else {
                    R.string.add_vault_password_visible
                },
            ),
        )
    }
}

@Composable
private fun ErrorLine(error: UnlockUiError?) {
    // ⚠️ `forKdbx = true`：这一页只会出现 KDBX 库，凭据错要说"主密码不正确"
    //    而不是"邮箱或主密码不正确"（KDBX 没有邮箱）。
    val message = unlockErrorText(error, forKdbx = true) ?: return
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = Spacing.sm),
    )
}

@Composable
private fun BusyLine(visible: Boolean, label: String) {
    if (!visible) return
    Spacer(Modifier.height(Spacing.md))
    VaultixWavyProgressBar(modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(Spacing.sm))
    Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun CloudProvider.labelRes(): Int = when (this) {
    CloudProvider.WEBDAV -> R.string.add_cloud_provider_webdav
    CloudProvider.ONEDRIVE -> R.string.add_cloud_provider_onedrive
}

private fun String.visualTransformation(visible: Boolean): VisualTransformation =
    if (visible) VisualTransformation.None else PasswordVisualTransformation()

/**
 * 字节数 → 人能读的大小（拿不到就不显示，见 [EntryMeta]）。
 *
 * ⚠️ 显式给 `Locale.US`：`String.format` 不带 locale 会被 detekt
 * `ImplicitDefaultLocale` 拦下，而在某些 locale 下小数点还会变成逗号
 * （`1,5 MB`），与旁边的数字挤在一起更难读。
 */
private fun formatSize(bytes: Long?): String? {
    if (bytes == null || bytes <= 0) return null
    val kb = bytes.toDouble() / BYTES_PER_KB
    return if (kb < BYTES_PER_KB) {
        String.format(Locale.US, "%.0f KB", kb)
    } else {
        String.format(Locale.US, "%.1f MB", kb / BYTES_PER_KB)
    }
}

/**
 * 毫秒 → 本地时间（只用于展示与辨认"哪个是最近改的"）。
 *
 * 用 `java.time` 而不是 `SimpleDateFormat`：后者是可变且有线程局部陷阱的老 API，
 * 而这里的格式化器是**每次调用新建**的 —— 那就更没理由用它。
 */
private fun formatTime(millis: Long?): String? = millis
    ?.takeIf { it > 0 }
    ?.let {
        DateTimeFormatter.ofPattern(TIME_PATTERN, Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(it))
    }

private const val BYTES_PER_KB = 1024.0
private const val TIME_PATTERN = "yyyy-MM-dd HH:mm"
