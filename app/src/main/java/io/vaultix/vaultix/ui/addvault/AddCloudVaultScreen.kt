/*
 * Vaultix — app:ui:addvault
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 从网盘添加 KDBX 库：**选账号 → 选文件 → 主密码 → 入库**。
 *
 * ## ★ 本页**没有**账号表单（2026-09-18，定稿 §11.7 第 2 步落地）
 *
 * 旧的形态是"在这里填服务器/账号/密码（或点登录）→ 连接 → 浏览"。
 * 它有两条错：
 *
 * 1. **生命周期错**：本页是导航路由，**一返回 ViewModel 即销毁**，而 OneDrive 登录态
 *    活在 MSAL 缓存里、WebDAV 凭据活在 `SecureCredentialStore` 里 —— 都是长寿命状态。
 *    把长寿命状态的操作放在短寿命宿主里，必然"返回就没了"（用户实测过的那个 bug）。
 * 2. **职责错**：账号是**被多个库共用**的资源，它的增删改查该有唯一归宿，
 *    而不是散在每个"用到网盘"的入口各配一遍。
 *
 * ⇒ 现在账号**只在设置 →「网盘账号」**里配。本页：
 * - 有账号 ⇒ 列出来让用户选；
 * - 一个都没有 ⇒ 给"去设置里配置"的引导，**不摊开表单**。
 *
 * ## 为什么"没账号时不给表单"不是倒退
 *
 * 关键在于 `CloudAccountInventory` 现在能列出**已保存凭据但还没建库**的账号
 * （`WebDavCredentialStore` 加了幂等索引）。所以"去设置里配完再回来"这条路
 * **是通的** —— 配完它就在列表里。此前不能摘表单，正是因为配完列不出来会造死路。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.addvault

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.vaultix.data.kdbx.KdbxFileEntry
import io.vaultix.vaultix.R
import io.vaultix.vaultix.remote.CloudAccount
import io.vaultix.vaultix.ui.common.VaultixWavyProgressBar
import io.vaultix.vaultix.ui.error.UnlockUiError
import io.vaultix.vaultix.ui.error.unlockErrorText
import io.vaultix.vaultix.ui.theme.Spacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val BUTTON_HEIGHT = 48.dp
private val INLINE_PROGRESS = 20.dp
private val INLINE_ICON = 18.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddCloudVaultScreen(
    onBack: () -> Unit,
    onAdded: () -> Unit,
    onOpenCloudAccounts: () -> Unit,
    viewModel: AddCloudVaultViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
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

    // 账号可能刚在设置里配好 ⇒ 每次进页面重读一次（不缓存）。
    LaunchedEffect(Unit) { viewModel.refreshConfiguredAccounts() }

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
            when {
                state.browsing -> BrowserSection(state = state, viewModel = viewModel)
                // ⚠️ 「空」有两态必须分开（本项目的老坑）：还在读 vs 真的一个都没有。
                //    塌进一个 isEmpty() 就是**假空态** —— 用户会以为"我配的账号丢了"。
                state.loadingAccounts -> LoadingAccounts()
                state.configuredAccounts.isEmpty() -> NoAccountsYet(onOpenCloudAccounts)
                else -> AccountPicker(
                    state = state,
                    onPick = viewModel::pickAccount,
                    onOpenCloudAccounts = onOpenCloudAccounts,
                )
            }
            Spacer(Modifier.height(Spacing.xxl))
        }
    }
}

// ---------------------------------------------------------------- 选账号

/**
 * 账号选择：**本页唯一的起点**。
 *
 * 每行显示连接状态 —— **未连接**的账号点了也会失败，得让用户先看见。
 */
@Composable
private fun AccountPicker(
    state: AddCloudVaultViewModel.UiState,
    onPick: (CloudAccount) -> Unit,
    onOpenCloudAccounts: () -> Unit,
) {
    Text(
        text = stringResource(R.string.add_cloud_subtitle),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(Spacing.lg))
    Text(
        text = stringResource(R.string.add_cloud_pick_account),
        style = MaterialTheme.typography.titleSmall,
    )
    Spacer(Modifier.height(Spacing.sm))

    state.configuredAccounts.forEach { account ->
        ListItem(
            modifier = Modifier.clickable(
                enabled = !state.busy,
                onClick = { onPick(account) },
            ),
            leadingContent = {
                Icon(
                    imageVector = Icons.Filled.Cloud,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            headlineContent = { Text(account.label) },
            supportingContent = { AccountSubtitle(account) },
            trailingContent = {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                )
            },
        )
    }

    Spacer(Modifier.height(Spacing.md))
    // 管理入口：账号的**全生命周期**都在那个持久页里，这里只给一条路。
    OutlinedIconButton(
        icon = Icons.Filled.Settings,
        text = stringResource(R.string.add_cloud_manage_accounts),
        onClick = onOpenCloudAccounts,
    )
    ErrorLine(state.error)
    BusyLine(visible = state.busy, label = stringResource(R.string.add_cloud_working))
}

@Composable
private fun AccountSubtitle(account: CloudAccount) {
    val kind = stringResource(
        if (account.kind == io.vaultix.vaultix.remote.CloudAccountKind.ONEDRIVE) {
            R.string.cloud_accounts_kind_onedrive
        } else {
            R.string.cloud_accounts_kind_webdav
        },
    )
    val usage = if (account.vaultCount > 0) {
        stringResource(R.string.cloud_accounts_used_by, account.vaultCount)
    } else {
        stringResource(R.string.cloud_accounts_no_vault_yet)
    }
    val connection = stringResource(
        if (account.connected) R.string.cloud_accounts_connected else R.string.cloud_accounts_disconnected,
    )
    Text(
        text = "$kind · $usage · $connection",
        style = MaterialTheme.typography.bodySmall,
        color = if (account.connected) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.error
        },
    )
}

/**
 * 一个都没有 —— **给引导，不给表单**。
 *
 * ⚠️ 这里刻意不提供"就地填凭据"：配账号是设置页的职责，而且配完**这里能立刻看到**
 * （`CloudAccountInventory` 现在列得出"已存凭据但没建库"的账号）。
 * 摊开一个表单只会让"账号该在哪配"有两个答案。
 */
@Composable
private fun NoAccountsYet(onOpenCloudAccounts: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.Cloud,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(ICON_LARGE),
        )
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = stringResource(R.string.add_cloud_no_accounts_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = stringResource(R.string.add_cloud_no_accounts_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.lg))
        FilledTonalButton(
            onClick = onOpenCloudAccounts,
            modifier = Modifier.fillMaxWidth().height(BUTTON_HEIGHT),
        ) {
            Icon(
                Icons.Filled.Settings,
                contentDescription = null,
                modifier = Modifier.size(INLINE_ICON),
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(stringResource(R.string.add_cloud_go_to_accounts))
        }
    }
}

@Composable
private fun LoadingAccounts() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        VaultixWavyProgressBar(modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = stringResource(R.string.cloud_accounts_loading),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

/** 当前目录 + 上一级 + 重新选账号。 */
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

/** 带图标的描边按钮（"去设置"这类"跳走去别处"的动作）。 */
@Composable
private fun OutlinedIconButton(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(INLINE_ICON))
        Spacer(Modifier.width(Spacing.sm))
        Text(text)
    }
}

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
private val ICON_LARGE = 48.dp
