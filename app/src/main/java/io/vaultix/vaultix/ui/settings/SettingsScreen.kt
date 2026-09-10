package io.vaultix.vaultix.ui.settings

import android.app.StatusBarManager
import android.content.ComponentName
import android.graphics.drawable.Icon
import android.os.Build
import androidx.core.content.ContextCompat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Save
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.vaultix.vaultix.BuildConfig
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.common.TrashAutoDeleteDialog
import io.vaultix.vaultix.ui.common.trashAutoDeleteLabel
import io.vaultix.vaultix.autofill.shortcut.AutofillTileService
import io.vaultix.vaultix.ui.theme.ThemeMode
import io.vaultix.vaultix.util.CredentialProviderStatus

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

            // ---- 外观 / 数据（批次④：Bastion SettingsScreen 对照补缺） ----
            AppearanceSection(viewModel, dynamicColor = state.dynamicColor)
            DataSection(viewModel)

            // ---- 自动填充（M2-a：系统 AutofillService 入口） ----
            AutofillSection(viewModel)

            // ---- 验证器（对齐 Bastion「验证器」分组：通知 / 时长 / 自动复制） ----
            OtpSection(viewModel)

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

/**
 * 外观组（批次④，对齐 Bastion 主题能力）：主题模式三态 + OLED 纯黑 + 动态取色。
 * 状态在 ViewModel 单独流上（不进 [SettingsViewModel.UiState]，避免六流 combine 的
 * Array 转型噪音）；切换主题立即生效（MainActivity 收集）。
 */
@Composable
private fun AppearanceSection(
    viewModel: SettingsViewModel,
    dynamicColor: Boolean,
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val oledPureBlack by viewModel.oledPureBlack.collectAsStateWithLifecycle()
    var showThemeDialog by rememberSaveable { mutableStateOf(false) }

    SettingsGroupTitle(stringResource(R.string.group_appearance))
    SettingsRow(
        icon = { Icon(Icons.Filled.DarkMode, contentDescription = null) },
        title = stringResource(R.string.setting_theme_mode),
        subtitle = themeModeLabel(ThemeMode.from(themeMode)),
        onClick = { showThemeDialog = true },
    )
    SettingsRow(
        icon = { Icon(Icons.Filled.Contrast, contentDescription = null) },
        title = stringResource(R.string.setting_oled_pure_black),
        subtitle = stringResource(R.string.setting_oled_pure_black_desc),
        trailing = {
            Switch(
                checked = oledPureBlack,
                onCheckedChange = viewModel::setOledPureBlack,
            )
        },
    )
    SettingsRow(
        icon = { Icon(Icons.Filled.Palette, contentDescription = null) },
        title = stringResource(R.string.setting_dynamic_color),
        subtitle = stringResource(R.string.setting_dynamic_color_desc),
        trailing = {
            Switch(
                checked = dynamicColor,
                onCheckedChange = viewModel::setDynamicColor,
            )
        },
    )

    if (showThemeDialog) {
        ThemeModeDialog(
            current = ThemeMode.from(themeMode),
            onSelect = {
                viewModel.setThemeMode(it.name.lowercase())
                showThemeDialog = false
            },
            onDismiss = { showThemeDialog = false },
        )
    }
}

/**
 * 数据组（批次④）：回收站自动清理档位——与回收站页顶栏入口共用
 * [TrashAutoDeleteDialog] 与同一偏好键，改哪边都实时生效。
 */
@Composable
private fun DataSection(viewModel: SettingsViewModel) {
    val trashDays by viewModel.trashAutoDeleteDays.collectAsStateWithLifecycle()
    var showTrashDialog by rememberSaveable { mutableStateOf(false) }

    SettingsGroupTitle(stringResource(R.string.group_data))
    SettingsRow(
        icon = { Icon(Icons.Filled.DeleteSweep, contentDescription = null) },
        title = stringResource(R.string.setting_trash_auto_delete),
        subtitle = trashAutoDeleteLabel(trashDays),
        onClick = { showTrashDialog = true },
    )

    if (showTrashDialog) {
        TrashAutoDeleteDialog(
            currentDays = trashDays,
            onSelect = viewModel::setTrashAutoDeleteDays,
            onDismiss = { showTrashDialog = false },
        )
    }
}

/**
 * 验证器分组（对齐 Bastion「验证器」`autofill_otp_settings_title`）：填充后验证码的三条
 * 交付选项——通知栏实时显示 / 通知展示时长 / 自动复制到剪贴板。
 *
 * 拆成两个独立开关的取舍（Bastion 同款）：第一步登录时页面通常**没有**验证码框，此时
 * 「盲复制」纯属多此一举（剪贴板被占，还会被自动清除机制清掉）；通知承载既不抢剪贴板、
 * 又能随时点取。两者互不排斥，可同时开启。
 */
@Composable
private fun OtpSection(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val notificationEnabled by viewModel.otpNotificationEnabled.collectAsStateWithLifecycle()
    val durationSeconds by viewModel.otpNotificationDuration.collectAsStateWithLifecycle()
    val autoCopyTotp by viewModel.autoCopyTotp.collectAsStateWithLifecycle()
    var showDurationDialog by rememberSaveable { mutableStateOf(false) }

    SettingsGroupTitle(stringResource(R.string.group_otp))
    SettingsRow(
        icon = { Icon(Icons.Filled.Notifications, contentDescription = null) },
        title = stringResource(R.string.setting_otp_notification),
        subtitle = stringResource(R.string.setting_otp_notification_desc),
        trailing = {
            Switch(
                checked = notificationEnabled,
                onCheckedChange = { enabled ->
                    viewModel.setOtpNotificationEnabled(enabled)
                    // 开启时顺带送到系统通知设置：Android 13+ 未授权则通知不可见，
                    // 前台服务会照跑但用户什么都看不到（对齐 Bastion 同款引导）。
                    if (enabled) openAppNotificationSettings(context)
                },
            )
        },
    )
    SettingsRow(
        icon = { Icon(Icons.Filled.Timer, contentDescription = null) },
        title = stringResource(R.string.setting_otp_notification_duration),
        subtitle = stringResource(R.string.setting_otp_notification_duration_value, durationSeconds),
        onClick = { showDurationDialog = true },
    )
    SettingsRow(
        icon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
        title = stringResource(R.string.setting_auto_copy_totp),
        subtitle = stringResource(R.string.setting_auto_copy_totp_desc),
        trailing = {
            Switch(
                checked = autoCopyTotp,
                onCheckedChange = viewModel::setAutoCopyTotp,
            )
        },
    )

    if (showDurationDialog) {
        OtpDurationDialog(
            currentSeconds = durationSeconds,
            onSelect = viewModel::setOtpNotificationDuration,
            onDismiss = { showDurationDialog = false },
        )
    }
}

/** 验证码通知展示时长档位（秒）。 */
private val OTP_DURATION_OPTIONS = intArrayOf(10, 30, 60, 120)

/** 单选对话框：验证码通知在通知栏保留多久。 */
@Composable
private fun OtpDurationDialog(
    currentSeconds: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.otp_duration_dialog_title)) },
        text = {
            Column {
                OTP_DURATION_OPTIONS.forEach { seconds ->
                    SingleChoiceRow(
                        label = stringResource(R.string.setting_otp_notification_duration_value, seconds),
                        selected = seconds == currentSeconds,
                        onClick = {
                            onSelect(seconds)
                            onDismiss()
                        },
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

/** 打开本应用的系统通知设置页（引导用户授权通知，否则验证码通知不可见）。 */
private fun openAppNotificationSettings(context: Context) {
    val direct = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    try {
        context.startActivity(direct)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", context.packageName, null)),
        )
    }
}

/**
 * 自动填充分组（M2-a）：入口仅做一件事——跳到系统「自动填充」设置，
 * 让用户把 Vaultix 选为默认自动填充服务（OS 级开关，App 内无法自行启用）。
 * 具体的填充行为（解析/匹配/回填）由 [io.vaultix.vaultix.autofill.VaultixAutofillService] 承担。
 */
@Composable
private fun AutofillSection(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val savePrompt by viewModel.autofillSavePrompt.collectAsStateWithLifecycle()
    var tileUnsupported by rememberSaveable { mutableStateOf(false) }
    // 凭据提供商启用状态：只读检测 + 每次回前台刷新（跳系统设置开启后返回要能看到变化）
    var credentialProviderEnabled by remember { mutableStateOf(CredentialProviderStatus.isEnabled(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                credentialProviderEnabled = CredentialProviderStatus.isEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    SettingsGroupTitle(stringResource(R.string.group_autofill))
    SettingsRow(
        icon = { Icon(Icons.Filled.Password, contentDescription = null) },
        title = stringResource(R.string.setting_autofill),
        subtitle = stringResource(R.string.setting_autofill_desc),
        onClick = { openSystemAutofillSettings(context) },
    )
    // Credential Provider（Android 14+）：Chromium（Chrome/Edge）取密码/通行密钥只问
    // 系统已启用的 Provider——与「系统自动填充」同页管理，App 无法自行启用（安全设置）。
    SettingsRow(
        icon = { Icon(Icons.Filled.Key, contentDescription = null) },
        title = stringResource(R.string.setting_credential_provider),
        subtitle = stringResource(
            if (credentialProviderEnabled) {
                R.string.setting_credential_provider_enabled_desc
            } else {
                R.string.setting_credential_provider_disabled_desc
            }
        ),
        // 直达「启用本 Provider」的系统界面（Android 14+ createSettingsPendingIntent）；
        // 低版本退化到自动填充设置页（老路径服务选择）
        onClick = { openCredentialProviderSettings(context) },
    )
    SettingsRow(
        icon = { Icon(Icons.Filled.Save, contentDescription = null) },
        title = stringResource(R.string.setting_autofill_save_prompt),
        subtitle = stringResource(R.string.setting_autofill_save_prompt_desc),
        trailing = {
            Switch(
                checked = savePrompt,
                onCheckedChange = viewModel::setAutofillSavePrompt,
            )
        },
    )
    // 快捷磁贴：国产输入法大多不支持键盘内联建议、部分国产 ROM 会吞掉系统填充弹窗，
    // 这条「复制 + 粘贴」路径不依赖输入法和无障碍，是最稳的兜底入口（仅说明如何添加）。
    SettingsRow(
        icon = { Icon(Icons.Filled.Lock, contentDescription = null) },
        title = stringResource(R.string.setting_manual_fill_tile),
        subtitle = stringResource(R.string.setting_manual_fill_tile_desc),
        onClick = { requestAddTile(context) { tileUnsupported = true } },
    )
    if (tileUnsupported) {
        AlertDialog(
            onDismissRequest = { tileUnsupported = false },
            title = { Text(stringResource(R.string.setting_manual_fill_tile)) },
            text = { Text(stringResource(R.string.setting_manual_fill_tile_hint)) },
            confirmButton = {
                TextButton(onClick = { tileUnsupported = false }) {
                    Text(stringResource(R.string.action_done))
                }
            },
        )
    }
}

/**
 * 把「快速填充」磁贴加到快捷设置。
 *
 * Android 13+ 用系统 API 弹确认框（`StatusBarManager.requestAddTileService`）；
 * 更低版本没有公开 API，只能引导用户手动拖动（系统不允许应用替用户改快捷设置布局，
 * 所以这里**没有也不该有**「开关」——磁贴的增删权限在系统手里）。
 */
private fun requestAddTile(context: Context, onUnsupported: () -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        onUnsupported()
        return
    }
    val manager = context.getSystemService(StatusBarManager::class.java)
    val added = runCatching {
        manager.requestAddTileService(
            ComponentName(context, AutofillTileService::class.java),
            context.getString(R.string.tile_manual_fill),
            Icon.createWithResource(context, R.drawable.ic_stat_lock),
            ContextCompat.getMainExecutor(context),
        ) { }
    }.isSuccess
    if (!added) onUnsupported()
}

/** 打开系统自动填充设置：优先请求直接把 Vaultix 设为服务，失败回退到服务列表。 */
private fun openSystemAutofillSettings(context: Context) {
    // 直接请求把 Vaultix 设为自动填充服务（Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, API 26）。
    val direct = Intent("android.settings.REQUEST_SET_AUTOFILL_SERVICE").apply {
        data = Uri.parse("package:${context.packageName}")
    }
    try {
        context.startActivity(direct)
    } catch (_: ActivityNotFoundException) {
        // 部分 OEM 不支持直接请求，退到自动填充服务选择列表
        //（Settings.ACTION_AUTOFILL_SERVICE_SETTINGS, API 28）。
        context.startActivity(Intent("android.settings.AUTOFILL_SERVICE_SETTINGS"))
    }
}

/**
 * 打开「启用本应用为 Credential Provider」的系统界面。
 *
 * Android 14+ 用 [androidx.credentials.CredentialManager.createSettingsPendingIntent]
 * ——系统据此展示自家 provider 的启用开关（此前的 REQUEST_SET_AUTOFILL_SERVICE 在
 * 部分设备上无反应，且老自动填充与凭据提供商是两个独立设置项，互不替代）。
 * 低版本没有 Credential Provider，退化到老自动填充设置页。
 */
private fun openCredentialProviderSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        val pendingIntent = runCatching {
            androidx.credentials.CredentialManager.create(context).createSettingsPendingIntent()
        }.getOrNull()
        if (pendingIntent != null) {
            val sent = runCatching { pendingIntent.send(context, 0, null) }.isSuccess
            if (sent) return
        }
    }
    openSystemAutofillSettings(context)
}

/** 单选对话框：主题模式三态（跟随系统 / 浅色 / 深色）。 */
@Composable
private fun ThemeModeDialog(
    current: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_theme_mode)) },
        text = {
            Column {
                ThemeMode.entries.forEach { mode ->
                    SingleChoiceRow(
                        label = themeModeLabel(mode),
                        selected = mode == current,
                        onClick = { onSelect(mode) },
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
private fun themeModeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> stringResource(R.string.theme_mode_system)
    ThemeMode.LIGHT -> stringResource(R.string.theme_mode_light)
    ThemeMode.DARK -> stringResource(R.string.theme_mode_dark)
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
