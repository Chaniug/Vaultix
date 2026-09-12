package io.vaultix.vaultix.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import io.vaultix.datastore.VaultTimeout
import io.vaultix.model.VaultSummary
import io.vaultix.vaultix.BuildConfig
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.common.BiometricPrompter
import io.vaultix.vaultix.ui.items.DisplayOptionsSheet
import io.vaultix.vaultix.ui.common.VaultixExpressiveTopBar
import io.vaultix.vaultix.ui.common.rememberImmersiveBarPadding
import io.vaultix.vaultix.ui.common.rememberScrollCollapseFraction
import io.vaultix.vaultix.ui.common.TrashAutoDeleteDialog
import io.vaultix.vaultix.ui.common.deviceCanAuthenticate
import io.vaultix.vaultix.ui.common.rememberFragmentActivity
import io.vaultix.vaultix.ui.common.trashAutoDeleteLabel
import io.vaultix.vaultix.ui.theme.ThemeMode

/**
 * 设置页（最小版）：安全（自动锁定 / 剪贴板清除 / 防截屏 / 立即锁定）、
 * 外观（动态取色）、关于。行与选择器交互范式参考 Bastion 设置页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAutofillSettings: () -> Unit,
    /** 主界面 Tab 内嵌模式：隐藏返回键（无上层可返回）。 */
    embedded: Boolean = false,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showAutoLockDialog by rememberSaveable { mutableStateOf(false) }
    var showClipboardDialog by rememberSaveable { mutableStateOf(false) }
    var showAboutDialog by rememberSaveable { mutableStateOf(false) }
    var showQuickUnlockDialog by rememberSaveable { mutableStateOf(false) }
    var showExitDatabaseDialog by rememberSaveable { mutableStateOf(false) }
    val quickUnlockVaults by viewModel.quickUnlockVaults.collectAsStateWithLifecycle()

    QuickUnlockEnrollEffect(viewModel)

    // 沉浸式顶栏：大标题随滚动缩小、状态栏区域由顶栏背景覆盖（对齐 Bastion）。
    val scrollState = rememberScrollState()
    val collapse = rememberScrollCollapseFraction(scrollState)
    val barPadding = rememberImmersiveBarPadding(collapse)

    Scaffold(
        // 顶栏浮在内容之上：Scaffold 不再为它预留高度。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = barPadding)
                .verticalScroll(scrollState),
        ) {
            // ---- 库（活跃库 = 全局单一真源；Bastion 里它是筛选维度，Vaultix 收成一个） ----
            VaultSection(viewModel)

            // ---- 安全（拆分为独立 composable：主函数要守住 detekt LongMethod ≤150） ----
            SecuritySection(
                viewModel = viewModel,
                state = state,
                onAutoLock = { showAutoLockDialog = true },
                onClipboardClear = { showClipboardDialog = true },
                onQuickUnlock = { showQuickUnlockDialog = true },
                onExitDatabase = { showExitDatabaseDialog = true },
            )

            // ---- 外观 / 数据（批次④：Bastion SettingsScreen 对照补缺） ----
            AppearanceSection(viewModel, dynamicColor = state.dynamicColor)
            DataSection(viewModel)

            // ---- 自动填充（M2-a：系统 AutofillService 入口 → 二级设置页） ----
            AutofillSection(onOpenAutofillSettings = onOpenAutofillSettings)

            // ---- 其他 / 关于（同上：拆出去守住函数长度门禁） ----
            OthersSection(
                context = context,
                versionName = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                onShowLicense = { showAboutDialog = true },
            )
            Spacer(Modifier.height(32.dp))
        }
            VaultixExpressiveTopBar(
                title = stringResource(R.string.settings_title),
                collapseFraction = collapse,
                modifier = Modifier.align(Alignment.TopCenter),
                navigationIcon = if (embedded) {
                    null
                } else {
                    {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    }
                },
            )
        }
    }

    if (showAutoLockDialog) {
        AutoLockDialog(
            current = state.vaultTimeout,
            onSelect = {
                viewModel.setVaultTimeout(it)
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
            canAuthenticate = deviceCanAuthenticate(context),
            onEnable = viewModel::startQuickUnlockEnroll,
            onDisable = viewModel::disableQuickUnlock,
            onDismiss = { showQuickUnlockDialog = false },
        )
    }
    if (showExitDatabaseDialog) {
        ExitDatabaseDialog(
            onConfirm = {
                showExitDatabaseDialog = false
                viewModel.exitDatabase()
            },
            onDismiss = { showExitDatabaseDialog = false },
        )
    }
}

/**
 * 「退出数据库」确认对话框。
 *
 * ⚠️ **必须有**：这一步会丢掉本地未上传的改动（待推送队列属本地缓存），
 * 一个没有确认的破坏性动作是不可接受的（用户可能刚离线编辑了十条条目）。
 * 文案把三件事说清：清什么（本地缓存）、不碰什么（远程 / 库本身）、丢什么（未上传改动）。
 */
@Composable
private fun ExitDatabaseDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Logout, contentDescription = null) },
        title = { Text(stringResource(R.string.setting_exit_database)) },
        text = { Text(stringResource(R.string.setting_exit_database_confirm)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.setting_exit_database_action),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/**
 * 安全组：自动锁定 / 剪贴板清除 / 防截屏 / 退出数据库 / 快速解锁。
 *
 * 拆成独立 composable 纯粹是为了让 [SettingsScreen] 主函数守住 detekt `LongMethod`
 * （≤150 行）——三个对话框的显示状态仍留在宿主里，本函数只收回调。
 */
@Composable
private fun SecuritySection(
    viewModel: SettingsViewModel,
    state: SettingsViewModel.UiState,
    onAutoLock: () -> Unit,
    onClipboardClear: () -> Unit,
    onQuickUnlock: () -> Unit,
    onExitDatabase: () -> Unit,
) {
    SettingsGroupTitle(stringResource(R.string.group_security))
    SettingsRow(
        icon = { Icon(Icons.Filled.Timer, contentDescription = null) },
        title = stringResource(R.string.setting_auto_lock),
        subtitle = vaultTimeoutLabel(state.vaultTimeout),
        onClick = onAutoLock,
    )
    SettingsRow(
        icon = { Icon(Icons.Filled.VisibilityOff, contentDescription = null) },
        title = stringResource(R.string.setting_clipboard_clear),
        subtitle = clipboardClearLabel(state.clipboardClearMs),
        onClick = onClipboardClear,
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
        icon = { Icon(Icons.Filled.Logout, contentDescription = null) },
        title = stringResource(R.string.setting_exit_database),
        subtitle = stringResource(R.string.setting_exit_database_desc),
        titleColor = MaterialTheme.colorScheme.error,
        onClick = onExitDatabase,
    )
    SettingsRow(
        icon = { Icon(Icons.Filled.Fingerprint, contentDescription = null) },
        title = stringResource(R.string.settings_quick_unlock),
        subtitle = stringResource(R.string.settings_quick_unlock_desc),
        onClick = onQuickUnlock,
    )
}

/** 其他组 + 关于组（同上：拆出去是为了满足函数长度门禁）。 */
@Composable
private fun OthersSection(
    context: Context,
    versionName: String,
    onShowLicense: () -> Unit,
) {
    // ---- 其他（对齐 Bastion SettingsScreen：权限管理放在设置首页） ----
    SettingsGroupTitle(stringResource(R.string.group_others))
    SettingsRow(
        icon = { Icon(Icons.Filled.Policy, contentDescription = null) },
        title = stringResource(R.string.permission_management_title),
        subtitle = stringResource(R.string.permission_management_subtitle),
        onClick = { openAppPermissionSettings(context) },
    )

    // ---- 关于 ----
    SettingsGroupTitle(stringResource(R.string.group_about))
    SettingsRow(
        icon = { Icon(Icons.Filled.Info, contentDescription = null) },
        title = stringResource(R.string.about_version),
        subtitle = versionName,
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
        onClick = onShowLicense,
    )
}

/**
 * 「库」分组：显示并切换**活跃库**。
 *
 * 为什么入口在设置页：Bastion 的多库是主界面里的一个**筛选维度**（`UnifiedCategoryFilterSelection`
 * 把库与文件夹 / 分类平级），而 Vaultix 是「登录时二选一」的**单活跃库**语义 —— 主界面
 * 不该出现库的概念，于是把多库入口整体下沉到设置页
 * （Docs/progress/main-shell-migration.md §0 与 A4）。
 *
 * 切换的影响面：主界面各 Tab、autofill 候选、Credential Provider 候选、保存回写目标
 * **同时**切到新库 —— 它们都只读 `ActiveVaultStore`，没有第二份状态。
 */
@Composable
private fun VaultSection(viewModel: SettingsViewModel) {
    val active by viewModel.activeVault.collectAsStateWithLifecycle()
    val switchable by viewModel.switchableVaults.collectAsStateWithLifecycle()
    var showDialog by rememberSaveable { mutableStateOf(false) }

    SettingsGroupTitle(stringResource(R.string.group_vaults))
    SettingsRow(
        icon = { Icon(Icons.Filled.Storage, contentDescription = null) },
        title = stringResource(R.string.settings_active_vault),
        subtitle = active?.name ?: stringResource(R.string.settings_active_vault_none),
        onClick = { showDialog = true },
    )

    if (showDialog) {
        ActiveVaultDialog(
            vaults = switchable,
            activeId = active?.id,
            onSelect = { vaultId ->
                viewModel.selectVault(vaultId)
                showDialog = false
            },
            onDismiss = { showDialog = false },
        )
    }
}

/**
 * 活跃库选择器。**只列已解锁的库**：锁定库没有内存密钥，切过去也读不出任何条目
 * （先解锁再切，与「多库并存时只能进一样」的产品定义一致）。
 */
@Composable
private fun ActiveVaultDialog(
    vaults: List<VaultSummary>,
    activeId: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_active_vault)) },
        text = {
            if (vaults.isEmpty()) {
                Text(stringResource(R.string.settings_active_vault_locked_hint))
            } else {
                Column {
                    vaults.forEach { vault ->
                        SingleChoiceRow(
                            label = vault.name,
                            selected = vault.id == activeId,
                            onClick = { onSelect(vault.id) },
                        )
                    }
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
    var showDisplayOptions by rememberSaveable { mutableStateOf(false) }

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
    // 条目列表的显示选项（分组方式 / 卡片信息密度 / 是否显示图标）——
    // 与密码 Tab 顶栏那个按钮共用同一个弹层与同一份偏好，改哪边都实时生效。
    SettingsRow(
        icon = { Icon(Icons.Filled.ViewAgenda, contentDescription = null) },
        title = stringResource(R.string.items_display_options),
        subtitle = stringResource(R.string.setting_display_options_desc),
        onClick = { showDisplayOptions = true },
    )

    if (showDisplayOptions) {
        SettingsDisplayOptionsHost(
            viewModel = viewModel,
            onDismiss = { showDisplayOptions = false },
        )
    }

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
 * 设置页里的「显示选项」宿主：把 `SettingsViewModel` 的偏好接进
 * [DisplayOptionsSheet]（与密码 Tab 顶栏按钮同一个弹层，改哪边都一致）。
 */
@Composable
private fun SettingsDisplayOptionsHost(
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit,
) {
    val groupMode by viewModel.itemsGroupMode.collectAsStateWithLifecycle()
    val cardDisplayMode by viewModel.itemsCardDisplayMode.collectAsStateWithLifecycle()
    val showIcon by viewModel.itemsShowIcon.collectAsStateWithLifecycle()
    DisplayOptionsSheet(
        groupMode = groupMode,
        cardDisplayMode = cardDisplayMode,
        showIcon = showIcon,
        onDismiss = onDismiss,
        onGroupMode = viewModel::setItemsGroupMode,
        onCardDisplayMode = viewModel::setItemsCardDisplayMode,
        onShowIcon = viewModel::setItemsShowIcon,
    )
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
 * 自动填充分组（M2-a）：设置首页只放一个入口，点进去是二级页
 * [AutofillSettingsScreen]——对齐 Bastion「设置 → 自动填充」的嵌套结构
 * （系统设置 / 验证器 / 保存行为）。
 *
 * 首页不再平铺具体开关的理由：自动填充相关项已有 5+ 条，平铺会把安全 / 外观 / 数据组
 * 挤到很下面，而这些项通常只在首次配置时改一次（对齐 Bastion 的信息架构）。
 */
@Composable
private fun AutofillSection(onOpenAutofillSettings: () -> Unit) {
    SettingsGroupTitle(stringResource(R.string.group_autofill))
    SettingsRow(
        icon = { Icon(Icons.Filled.Password, contentDescription = null) },
        title = stringResource(R.string.autofill_settings_entry),
        subtitle = stringResource(R.string.autofill_settings_entry_desc),
        trailing = {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        onClick = onOpenAutofillSettings,
    )
}

/** 系统应用信息页（权限管理）：唯一能改运行时权限的入口，系统不提供应用内开关。 */
private fun openAppPermissionSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            ),
        )
    }
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

/**
 * 快速解锁「启用」的认证副作用收集器。
 *
 * 收到 [SettingsViewModel.Event.PromptForEnroll]（已 init 的 ENCRYPT cipher）即弹
 * BiometricPrompt；认证通过后用本次 cipher 包裹当前会话密钥并落盘，对话框内状态随之
 * 翻为「已启用」。取消/失败什么都不做 —— 维持「未启用」，用户可再试。
 *
 * 独立成 composable 而非内联在 [SettingsScreen]，一是避免后者超长（detekt LongMethod），
 * 二是把「认证副作用」与「页面布局」解耦。
 */
@Composable
private fun QuickUnlockEnrollEffect(viewModel: SettingsViewModel) {
    val activity = rememberFragmentActivity()
    val enrollTitle = stringResource(R.string.quick_unlock_enroll_title)
    val cancelText = stringResource(R.string.action_cancel)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsViewModel.Event.PromptForEnroll -> {
                    val host = activity ?: return@collect
                    BiometricPrompter(host).authenticate(
                        cipher = event.cipher,
                        title = enrollTitle,
                        cancelText = cancelText,
                        onSuccess = { cipher -> viewModel.enrollWithCipher(event.vaultId, cipher) },
                        onError = { _, _ -> /* 取消/失败：维持「未启用」，可再试 */ },
                    )
                }
            }
        }
    }
}

/**
 * 快速解锁管理：列出各库启用状态，可逐个**启用 / 关闭**。
 *
 * ⚠️ 历史坑：此前本对话框**只能关不能开**（启用入口仅库列表页横幅），
 * 而横幅「以后再说」会永久置位 `isQuickUnlockPromptDismissed` → 用户彻底
 * 失去启用路径。现已补上对称的「启用」动作，消除该入口死角。
 */
@Composable
private fun QuickUnlockManageDialog(
    vaults: List<SettingsViewModel.QuickUnlockVaultUi>,
    canAuthenticate: Boolean,
    onEnable: (String) -> Unit,
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
                                        // 仅当设备确实支持认证时才提示「可启用」，
                                        // 否则维持原「未启用」说明，避免给出无法完成的指引
                                        if (canAuthenticate) {
                                            stringResource(R.string.quick_unlock_disabled)
                                        } else {
                                            stringResource(R.string.quick_unlock_device_unsupported)
                                        }
                                    },
                                )
                            },
                            trailingContent = {
                                if (vault.enabled) {
                                    TextButton(onClick = { onDisable(vault.vaultId) }) {
                                        Text(stringResource(R.string.quick_unlock_disable))
                                    }
                                } else if (canAuthenticate) {
                                    TextButton(onClick = { onEnable(vault.vaultId) }) {
                                        Text(stringResource(R.string.quick_unlock_enable))
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

/**
 * 单选列表对话框：自动锁定档位。
 *
 * 档位集合对齐 Bitwarden `VaultTimeout`（立即 / 1 / 5 / 15 / 30 / 60 / 240 分钟 /
 * 重启时 / 从不 / 自定义），取代旧的裸 Int 档位表。
 */
@Composable
private fun AutoLockDialog(
    current: VaultTimeout,
    onSelect: (VaultTimeout) -> Unit,
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
                        onSelect(VaultTimeout.Custom(value))
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
                AutoLockPresets.VALUES.forEach { timeout ->
                    SingleChoiceRow(
                        label = vaultTimeoutLabel(timeout),
                        selected = timeout == current,
                        onClick = { onSelect(timeout) },
                    )
                }
                SingleChoiceRow(
                    label = stringResource(R.string.auto_lock_custom),
                    selected = current is VaultTimeout.Custom,
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


// ---- 档位文案（对齐 Bitwarden VaultTimeout 的档位命名）----

/**
 * 自动锁定档位 → 文案。
 *
 * `when` 作用于 sealed class（穷尽分支，漏档编译器会报错），取代旧的裸 Int `when`
 * （后者漏一个档位只会在运行时静默落到 else）。
 */
@Composable
private fun vaultTimeoutLabel(timeout: VaultTimeout): String = when (timeout) {
    VaultTimeout.Never -> stringResource(R.string.auto_lock_never)
    VaultTimeout.Immediately -> stringResource(R.string.auto_lock_immediately)
    VaultTimeout.OnAppRestart -> stringResource(R.string.auto_lock_on_restart)
    VaultTimeout.OneMinute,
    VaultTimeout.FiveMinutes,
    VaultTimeout.FifteenMinutes,
    VaultTimeout.ThirtyMinutes ->
        // 这几个预设档位的 minutes 恒非空（各自 override 为非空 Int）；
        // 组合分支不做智能转换，故显式 requireNotNull 断言该不变量。
        stringResource(
            R.string.auto_lock_minutes_fmt,
            requireNotNull(timeout.vaultTimeoutInMinutes),
        )
    VaultTimeout.OneHour,
    VaultTimeout.FourHours ->
        stringResource(
            R.string.auto_lock_hour_fmt,
            requireNotNull(timeout.vaultTimeoutInMinutes) / AutoLockPresets.MINUTES_PER_HOUR,
        )
    is VaultTimeout.Custom -> when {
        timeout.vaultTimeoutInMinutes % AutoLockPresets.MINUTES_PER_HOUR == 0 ->
            stringResource(
                R.string.auto_lock_hour_fmt,
                timeout.vaultTimeoutInMinutes / AutoLockPresets.MINUTES_PER_HOUR,
            )
        timeout.vaultTimeoutInMinutes % AutoLockPresets.MINUTES_PER_DAY == 0 ->
            stringResource(
                R.string.auto_lock_day_fmt,
                timeout.vaultTimeoutInMinutes / AutoLockPresets.MINUTES_PER_DAY,
            )
        else -> stringResource(R.string.auto_lock_minutes_fmt, timeout.vaultTimeoutInMinutes)
    }
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

/**
 * 自动锁定档位表与文案换算常量。
 *
 * 档位对齐 Bitwarden `VaultTimeout`：立即 / 1 / 5 / 15 / 30 / 60 / 240 分钟 /
 * 重启时 / 从不（+ 自定义）。旧的 10 / 300 / 1440 三个非标准档位已从候选中移除——
 * 存量用户设置若落在这些值上，迁移时保留为 [VaultTimeout.Custom]，**不会丢失**
 * （见 `VaultTimeout.fromLegacyMinutes`）。
 */
@Suppress("MagicNumber")
private object AutoLockPresets {
    /** 单选候选（顺序即 UI 顺序）。 */
    val VALUES: List<VaultTimeout> = listOf(
        VaultTimeout.Immediately,
        VaultTimeout.OneMinute,
        VaultTimeout.FiveMinutes,
        VaultTimeout.FifteenMinutes,
        VaultTimeout.ThirtyMinutes,
        VaultTimeout.OneHour,
        VaultTimeout.FourHours,
        VaultTimeout.OnAppRestart,
        VaultTimeout.Never,
    )

    const val MINUTES_PER_HOUR = 60
    const val MINUTES_PER_DAY = 1440
    const val MS_PER_SECOND = 1000

    /** 自定义分钟数输入上限。 */
    const val MAX_CUSTOM_MINUTES = 100_000

    /** 自定义输入框位数上限。 */
    const val CUSTOM_MINUTES_DIGITS = 6
}
