package io.vaultix.vaultix.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.vaultix.vaultix.BuildConfig
import io.vaultix.vaultix.R

/**
 * 设置页（最小版）：安全（自动锁定 / 剪贴板清除 / 防截屏 / 立即锁定）、
 * 外观（动态取色）、关于。行与选择器交互范式参考 Bastion 设置页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showAutoLockDialog by rememberSaveable { mutableStateOf(false) }
    var showClipboardDialog by rememberSaveable { mutableStateOf(false) }
    var showAboutDialog by rememberSaveable { mutableStateOf(false) }
    var showQuickUnlockDialog by rememberSaveable { mutableStateOf(false) }
    val quickUnlockVaults by viewModel.quickUnlockVaults.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
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
                .verticalScroll(rememberScrollState()),
        ) {
            // ---- 安全 ----
            SettingsGroupTitle(stringResource(R.string.group_security))
            SettingsRow(
                icon = { Icon(Icons.Filled.Timer, contentDescription = null) },
                title = stringResource(R.string.setting_auto_lock),
                subtitle = autoLockMinutesLabel(state.autoLockMinutes),
                onClick = { showAutoLockDialog = true },
            )
            SettingsRow(
                icon = { Icon(Icons.Filled.VisibilityOff, contentDescription = null) },
                title = stringResource(R.string.setting_clipboard_clear),
                subtitle = clipboardClearLabel(state.clipboardClearMs),
                onClick = { showClipboardDialog = true },
            )
            SettingsRow(
                icon = { Icon(Icons.Filled.Shield, contentDescription = null) },
                title = stringResource(R.string.setting_screen_security),
                subtitle = stringResource(R.string.setting_screen_security_desc),
                trailing = {
                    Switch(
                        checked = state.screenSecurity,
                        onCheckedChange = viewModel::setScreenSecurity,
                    )
                },
            )
            SettingsRow(
                icon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                title = stringResource(R.string.setting_lock_now),
                subtitle = stringResource(R.string.setting_lock_now_desc),
                titleColor = MaterialTheme.colorScheme.error,
                onClick = viewModel::lockAllNow,
            )
            SettingsRow(
                icon = { Icon(Icons.Filled.Fingerprint, contentDescription = null) },
                title = stringResource(R.string.settings_quick_unlock),
                subtitle = stringResource(R.string.settings_quick_unlock_desc),
                onClick = { showQuickUnlockDialog = true },
            )

            // ---- 外观 ----
            SettingsGroupTitle(stringResource(R.string.group_appearance))
            SettingsRow(
                icon = { Icon(Icons.Filled.Palette, contentDescription = null) },
                title = stringResource(R.string.setting_dynamic_color),
                subtitle = stringResource(R.string.setting_dynamic_color_desc),
                trailing = {
                    Switch(
                        checked = state.dynamicColor,
                        onCheckedChange = viewModel::setDynamicColor,
                    )
                },
            )

            // ---- 关于 ----
            SettingsGroupTitle(stringResource(R.string.group_about))
            SettingsRow(
                icon = { Icon(Icons.Filled.Info, contentDescription = null) },
                title = stringResource(R.string.about_version),
                subtitle = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            )
            SettingsRow(
                icon = { Icon(Icons.Filled.Security, contentDescription = null) },
                title = stringResource(R.string.about_source),
                subtitle = stringResource(R.string.about_github_url),
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(context.getString(R.string.about_github_url))),
                    )
                },
            )
            SettingsRow(
                icon = { Icon(Icons.Filled.Info, contentDescription = null) },
                title = stringResource(R.string.about_license),
                onClick = { showAboutDialog = true },
            )
            Spacer(Modifier.height(32.dp))
        }
    }

    if (showAutoLockDialog) {
        AutoLockDialog(
            current = state.autoLockMinutes,
            onSelect = {
                viewModel.setAutoLockMinutes(it)
                showAutoLockDialog = false
            },
            onDismiss = { showAutoLockDialog = false },
        )
    }
    if (showClipboardDialog) {
        ClipboardClearDialog(
            currentMs = state.clipboardClearMs,
            onSelect = {
                viewModel.setClipboardClearMs(it)
                showClipboardDialog = false
            },
            onDismiss = { showClipboardDialog = false },
        )
    }
    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text(stringResource(R.string.about_license)) },
            text = { Text(stringResource(R.string.about_license_body)) },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text(stringResource(R.string.action_back))
                }
            },
        )
    }
    if (showQuickUnlockDialog) {
        QuickUnlockManageDialog(
            vaults = quickUnlockVaults,
            onDisable = viewModel::disableQuickUnlock,
            onDismiss = { showQuickUnlockDialog = false },
        )
    }
}

/** 快速解锁管理：列出各库启用状态，可逐个关闭（启用入口 = 登录后列表横幅）。 */
@Composable
private fun QuickUnlockManageDialog(
    vaults: List<SettingsViewModel.QuickUnlockVaultUi>,
    onDisable: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_quick_unlock)) },
        text = {
            if (vaults.isEmpty()) {
                Text(stringResource(R.string.quick_unlock_manage_none))
            } else {
                Column {
                    vaults.forEach { vault ->
                        ListItem(
                            headlineContent = { Text(vault.name) },
                            supportingContent = {
                                Text(
                                    if (vault.enabled) {
                                        stringResource(R.string.quick_unlock_enabled)
                                    } else {
                                        stringResource(R.string.quick_unlock_disabled)
                                    },
                                )
                            },
                            trailingContent = {
                                if (vault.enabled) {
                                    TextButton(onClick = { onDisable(vault.vaultId) }) {
                                        Text(stringResource(R.string.quick_unlock_disable))
                                    }
                                }
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_back))
            }
        },
    )
}

/** 单选列表对话框：自动锁定档位（0/1/5/10/15/30/60/300/1440/-1 + 自定义）。 */
@Composable
private fun AutoLockDialog(
    current: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var customOpen by rememberSaveable { mutableStateOf(false) }
    var customText by rememberSaveable { mutableStateOf("") }
    var customInvalid by rememberSaveable { mutableStateOf(false) }

    if (customOpen) {
        AlertDialog(
            onDismissRequest = {
                customOpen = false
                customInvalid = false
            },
            title = { Text(stringResource(R.string.auto_lock_custom_title)) },
            text = {
                OutlinedTextField(
                    value = customText,
                    onValueChange = {
                        customText = it.filter { c -> c.isDigit() }.take(AutoLockPresets.CUSTOM_MINUTES_DIGITS)
                        customInvalid = false
                    },
                    isError = customInvalid,
                    supportingText = if (customInvalid) {
                        { Text(stringResource(R.string.auto_lock_custom_invalid)) }
                    } else {
                        null
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val value = customText.toIntOrNull()
                    if (value == null || value < 1 || value > AutoLockPresets.MAX_CUSTOM_MINUTES) {
                        customInvalid = true
                    } else {
                        onSelect(value)
                    }
                }) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { customOpen = false; customInvalid = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_auto_lock)) },
        text = {
            Column {
                AutoLockPresets.VALUES.forEach { minutes ->
                    SingleChoiceRow(
                        label = autoLockMinutesLabel(minutes),
                        selected = minutes == current,
                        onClick = { onSelect(minutes) },
                    )
                }
                SingleChoiceRow(
                    label = stringResource(R.string.auto_lock_custom),
                    selected = current !in AutoLockPresets.VALUES,
                    onClick = { customOpen = true },
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

/** 单选列表对话框：复制后自动清除剪贴板时长。 */
@Composable
private fun ClipboardClearDialog(
    currentMs: Long,
    onSelect: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_clipboard_clear)) },
        text = {
            Column {
                SettingsViewModel.CLIPBOARD_PRESETS_MS.forEach { ms ->
                    SingleChoiceRow(
                        label = clipboardClearLabel(ms),
                        selected = ms == currentMs,
                        onClick = { onSelect(ms) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun SingleChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun SettingsGroupTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingsRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    titleColor: androidx.compose.ui.graphics.Color? = null,
    onClick: (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = {
            Text(
                text = title,
                color = titleColor ?: MaterialTheme.colorScheme.onSurface,
            )
        },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = icon,
        trailingContent = trailing,
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}

// ---- 档位文案（Bastion getAutoLockDisplayName 的 Vaultix 版，无 -2 档）----

/** 自动锁定分钟数 → 文案资源 id。 */
@Composable
private fun autoLockMinutesLabel(minutes: Int): String = when (minutes) {
    -1 -> stringResource(R.string.auto_lock_never)
    0 -> stringResource(R.string.auto_lock_immediately)
    AutoLockPresets.HOUR_MINUTES -> stringResource(R.string.auto_lock_hour_fmt, 1)
    AutoLockPresets.FIVE_HOURS -> stringResource(
        R.string.auto_lock_hour_fmt,
        AutoLockPresets.FIVE_HOURS_COUNT,
    )
    AutoLockPresets.DAY_MINUTES -> stringResource(R.string.auto_lock_day_fmt, 1)
    else -> stringResource(R.string.auto_lock_minutes_fmt, minutes)
}

/** 剪贴板清除毫秒 → 文案。 */
@Composable
private fun clipboardClearLabel(ms: Long): String = when (ms) {
    0L -> stringResource(R.string.clipboard_clear_off)
    else -> stringResource(
        R.string.clipboard_clear_seconds_fmt,
        ms / AutoLockPresets.MS_PER_SECOND,
    )
}

/** 自动锁定档位与文案换算常量（0=立即 / N=空闲分钟 / -1=从不）。 */
@Suppress("MagicNumber")
private object AutoLockPresets {
    /** 单选候选：从不 / 立即 / 常用分钟档 / 1 天。 */
    val VALUES = listOf(-1, 0, 1, 5, 10, 15, 30, 60, 300, 1440)

    const val HOUR_MINUTES = 60
    const val FIVE_HOURS = 300
    const val FIVE_HOURS_COUNT = 5
    const val DAY_MINUTES = 1440
    const val MS_PER_SECOND = 1000

    /** 自定义分钟数输入上限。 */
    const val MAX_CUSTOM_MINUTES = 100_000

    /** 自定义输入框位数上限。 */
    const val CUSTOM_MINUTES_DIGITS = 6
}
