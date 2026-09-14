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
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import io.vaultix.datastore.VaultTimeout
import io.vaultix.domain.PIN_MIN_LENGTH
import io.vaultix.model.VaultKind
import io.vaultix.model.VaultSummary
import io.vaultix.vaultix.BuildConfig
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.common.BiometricPrompter
import io.vaultix.vaultix.ui.items.DisplayOptionsSheet
import io.vaultix.vaultix.ui.common.VaultixExpressiveTopBar
import io.vaultix.vaultix.ui.common.AddVaultTypeDialog
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
    /** 「数据管理」分区：导入 / 导出加密备份（导航到二级页）。 */
    onOpenImportExport: () -> Unit = {},
    /** 主界面 Tab 内嵌模式：隐藏返回键（无上层可返回）。 */
    embedded: Boolean = false,
    /** 底部叠层悬浮栏占用的高度（宿主给；非内嵌时为 0）——内容要留出它，否则末项被压住。 */
    bottomInset: Dp = 0.dp,
    /** 「密码库」分区：添加 Bitwarden 云端库 / 打开本地 KDBX 文件（导航到对应流程）。 */
    onAddBitwardenVault: () -> Unit = {},
    onAddKdbxVault: () -> Unit = {},
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
    val kdbxEnrollState by viewModel.kdbxEnrollState.collectAsStateWithLifecycle()
    // 正在等待主密码的 KDBX 库（null = 不显示输入框）。由事件驱动，不 saveable：
    // 进程重建后事件已消费，重新弹一个空输入框反而困惑。
    var pendingKdbxVaultId by remember { mutableStateOf<String?>(null) }

    QuickUnlockEnrollEffect(
        viewModel = viewModel,
        onPromptKdbxPassword = { vaultId -> pendingKdbxVaultId = vaultId },
        onEnrollReady = { pendingKdbxVaultId = null },
    )

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
                .verticalScroll(scrollState),
        ) {
            // 顶部让位用**可滚动的 Spacer**，不用外层 `padding(top = barPadding)`：
            // 后者会把滚动视口整体下压 ⇒ 内容永远画不到顶栏区域，收起顶栏后
            // 顶栏下方留一条死区（「不沉浸」）；Spacer 会随内容一起滚走。
            Spacer(modifier = Modifier.height(barPadding))
            // ---- 库（活跃库 = 全局单一真源；Bastion 里它是筛选维度，Vaultix 收成一个） ----
            VaultSection(
                viewModel = viewModel,
                onAddBitwardenVault = onAddBitwardenVault,
                onAddKdbxVault = onAddKdbxVault,
            )

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
            DataSection(viewModel, onOpenImportExport = onOpenImportExport)

            // ---- 自动填充（M2-a：系统 AutofillService 入口 → 二级设置页） ----
            AutofillSection(onOpenAutofillSettings = onOpenAutofillSettings)

            // ---- 其他 / 关于（同上：拆出去守住函数长度门禁） ----
            OthersSection(
                context = context,
                versionName = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                onShowLicense = { showAboutDialog = true },
            )
            Spacer(Modifier.height(32.dp))
            // 底部留出叠层悬浮底栏的高度（顶部的让位见上方 Spacer(barPadding)）：
            // 底栏改成叠层后内容铺到屏幕底，最后一项不再被胶囊压住。
            Spacer(Modifier.height(bottomInset))
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
            // ★ 按库类型分流：KDBX 没有可包裹的会话密钥，**必须先要主密码**
            //   （定稿 §4.5）；Bitwarden 直接弹指纹。
            onEnable = { vaultId ->
                val target = quickUnlockVaults.firstOrNull { it.vaultId == vaultId }
                if (target?.kind == VaultKind.KDBX) {
                    viewModel.startKdbxQuickUnlock(vaultId)
                } else {
                    viewModel.startQuickUnlockEnroll(vaultId)
                }
            },
            onDisable = viewModel::disableQuickUnlock,
            onPinSet = viewModel::openPinDialog,
            onPinDisable = viewModel::disablePin,
            onDismiss = { showQuickUnlockDialog = false },
        )
    }
    PinDialogHost(viewModel = viewModel)
    // KDBX 主密码输入框：仅当用户勾选了 KDBX 库、且尚未校验通过时出现。
    val kdbxTarget = pendingKdbxVaultId
    if (kdbxTarget != null) {
        KdbxQuickUnlockPasswordDialog(
            vaultName = quickUnlockVaults.firstOrNull { it.vaultId == kdbxTarget }?.name.orEmpty(),
            state = kdbxEnrollState,
            onSubmit = { password -> viewModel.confirmKdbxPassword(kdbxTarget, password) },
            onDismiss = {
                pendingKdbxVaultId = null
                viewModel.dismissKdbxPassword()
            },
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
private fun VaultSection(
    viewModel: SettingsViewModel,
    onAddBitwardenVault: () -> Unit,
    onAddKdbxVault: () -> Unit,
) {
    val active by viewModel.activeVault.collectAsStateWithLifecycle()
    val default by viewModel.defaultVault.collectAsStateWithLifecycle()
    val switchable by viewModel.switchableVaults.collectAsStateWithLifecycle()
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var showAddDialog by rememberSaveable { mutableStateOf(false) }

    SettingsGroupTitle(stringResource(R.string.group_vaults))
    SettingsRow(
        icon = { Icon(Icons.Filled.Storage, contentDescription = null) },
        title = stringResource(R.string.settings_active_vault),
        subtitle = active?.name ?: stringResource(R.string.settings_active_vault_none),
        onClick = { showDialog = true },
    )
    // ⚠️ 这一行是**必需**的：添加库的入口原本只在库列表页的「+」，而库列表路由在
    // 「已经有一个库」时不可达（根导航落在解锁页 / 主界面）⇒ 用户永远加不了本地
    // KDBX 库，表现为「KDBX 集成已交付但设置里只有 Bitwarden」。
    SettingsRow(
        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
        title = stringResource(R.string.vault_add_fab),
        subtitle = stringResource(R.string.settings_add_vault_desc),
        onClick = { showAddDialog = true },
    )

    if (showDialog) {
        ActiveVaultDialog(
            vaults = switchable,
            activeId = active?.id,
            defaultId = default?.id,
            onSelect = { vaultId ->
                viewModel.selectVault(vaultId)
                showDialog = false
            },
            onSetDefault = { vaultId ->
                viewModel.setDefaultVault(vaultId)
                showDialog = false
            },
            onDismiss = { showDialog = false },
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
            onDismiss = { showAddDialog = false },
        )
    }
}

/**
 * 活跃库选择器。**列出全部库**（含未解锁）。
 *
 * ⚠️ 2026-09-14（issue #96）：原先只列已解锁库 ⇒ 未解锁的 KDBX 不在列表里，
 * 用户「找不到我的库」而以为库丢了。现在全部列出、未解锁项如实标注
 * 「未解锁 · 需先输入主密码」——**「找得到」优先于「点得动」**：
 * 点一个未解锁库时 [onSelect] 会把用户带去解锁页（由宿主接线）。
 *
 * 两项动作分开（这是「活跃库 / 默认库」两键拆分的 UI 形态）：
 * - 点行 = 切换**本次会话**看哪个；
 * - 「设为默认」= 改**冷启动先开哪个**（唯一写入点）。
 */
@Composable
private fun ActiveVaultDialog(
    vaults: List<VaultSummary>,
    activeId: String?,
    defaultId: String?,
    onSelect: (String) -> Unit,
    onSetDefault: (String) -> Unit,
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
                        VaultChoiceRow(
                            name = vault.name,
                            locked = !vault.unlocked,
                            selected = vault.id == activeId,
                            isDefault = vault.id == defaultId,
                            onClick = { onSelect(vault.id) },
                            onSetDefault = { onSetDefault(vault.id) },
                        )
                    }
                    Text(
                        text = stringResource(R.string.settings_default_vault_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
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

/**
 * 库选择器里的一行：名称（未解锁时带后缀）+ 选中态 + 「设为默认」动作 + 默认标记。
 *
 * 拆成独立 composable 是为了让 [ActiveVaultDialog] 不越 detekt 的复杂度门禁，
 * 也让「未解锁标注」「默认标记」两件事各自可读。
 */
@Composable
private fun VaultChoiceRow(
    name: String,
    locked: Boolean,
    selected: Boolean,
    isDefault: Boolean,
    onClick: () -> Unit,
    onSetDefault: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (locked) {
                Text(
                    text = stringResource(R.string.settings_vault_locked_suffix),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (isDefault) {
            Text(
                text = stringResource(R.string.settings_vault_default_badge),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            TextButton(onClick = onSetDefault) {
                Text(stringResource(R.string.settings_vault_set_default))
            }
        }
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
 *
 * 另含「导入 / 导出」入口（对齐 Bitwarden 官方「设置 → 导出密码库 / 导入数据」）：
 * 导出 = 加密 JSON 备份，导入 = 从备份增量恢复。放在数据组而非单独分组，
 * 因为三者都属「库数据的迁移 / 维护」。
 */
@Composable
private fun DataSection(
    viewModel: SettingsViewModel,
    onOpenImportExport: () -> Unit,
) {
    val trashDays by viewModel.trashAutoDeleteDays.collectAsStateWithLifecycle()
    var showTrashDialog by rememberSaveable { mutableStateOf(false) }

    SettingsGroupTitle(stringResource(R.string.group_data))
    SettingsRow(
        icon = { Icon(Icons.Filled.DeleteSweep, contentDescription = null) },
        title = stringResource(R.string.setting_trash_auto_delete),
        subtitle = trashAutoDeleteLabel(trashDays),
        onClick = { showTrashDialog = true },
    )
    SettingsRow(
        icon = { Icon(Icons.Filled.SwapVert, contentDescription = null) },
        title = stringResource(R.string.import_export_entry),
        subtitle = stringResource(R.string.import_export_entry_desc),
        onClick = onOpenImportExport,
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
 * BiometricPrompt；认证通过后用本次 cipher 包裹密钥并落盘，对话框内状态随之翻为
 * 「已启用」。取消/失败什么都不做 —— 维持「未启用」，用户可再试。
 *
 * 另一条事件 [SettingsViewModel.Event.PromptForKdbxPassword] 只把「哪个库在等密码」
 * 交给调用方（KDBX 必须先要主密码，定稿 §4.5）。
 *
 * 独立成 composable 而非内联在 [SettingsScreen]，一是避免后者超长（detekt LongMethod），
 * 二是把「认证副作用」与「页面布局」解耦。
 */
@Composable
private fun QuickUnlockEnrollEffect(
    viewModel: SettingsViewModel,
    onPromptKdbxPassword: (String) -> Unit,
    onEnrollReady: () -> Unit,
) {
    val activity = rememberFragmentActivity()
    val enrollTitle = stringResource(R.string.quick_unlock_enroll_title)
    val cancelText = stringResource(R.string.action_cancel)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsViewModel.Event.PromptForEnroll -> {
                    // 指纹框已弹出 ⇒ KDBX 密码框使命完成，收起（避免叠两层对话框）。
                    onEnrollReady()
                    val host = activity ?: return@collect
                    BiometricPrompter(host).authenticate(
                        cipher = event.cipher,
                        title = enrollTitle,
                        cancelText = cancelText,
                        onSuccess = { cipher -> viewModel.enrollWithCipher(event.vaultId, cipher) },
                        onError = { _, _, _ -> /* 取消/失败：维持「未启用」，可再试 */ },
                    )
                }
                is SettingsViewModel.Event.PromptForKdbxPassword -> {
                    onPromptKdbxPassword(event.vaultId)
                }
            }
        }
    }
}

/**
 * 快速解锁「**生效范围**」：列出全部库，逐个决定这把指纹钥匙管不管它们。
 *
 * 心智模型（用户提出、定稿 §4.7 采纳）：**用户只有一把 Keystore 密钥**，
 * 所以「快速解锁是一个能力，作用于哪些库由用户勾选」——勾选即决定
 * 「这把钥匙串上挂几把钥匙」。
 *
 * ⚠️ 两条登记流程**不同**，故 UI 也要区分（[QuickUnlockVaultUi.kind]）：
 * - Bitwarden：勾选 → 直接弹指纹（密钥在会话里）；
 * - KDBX：勾选 → **先弹主密码输入框** → 校验通过 → 再弹指纹（§4.5）。
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
    onPinSet: (SettingsViewModel.QuickUnlockVaultUi) -> Unit,
    onPinDisable: (String) -> Unit,
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
                    Text(
                        text = stringResource(R.string.quick_unlock_scope_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    vaults.forEach { vault ->
                        QuickUnlockRow(
                            vault = vault,
                            canAuthenticate = canAuthenticate,
                            onEnable = onEnable,
                            onDisable = onDisable,
                        )
                    }
                    // 「应用内 PIN」单列一段：与指纹是**两条独立**的解锁路径，
                    // 挤在同一行里会让人以为它们是一个开关的两个档位。
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.pin_section_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.pin_section_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    vaults.forEach { vault ->
                        PinRow(vault = vault, onSet = onPinSet, onPinDisable = onPinDisable)
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
 * 单个库的「快速解锁」行（从 [QuickUnlockManageDialog] 抽出）。
 *
 * 抽出来的直接原因是不让对话框越过 detekt `LongMethod`；但更重要的理由是
 * 一个库的两种解锁手段本来就该各占一行、各自可读。
 */
@Composable
private fun QuickUnlockRow(
    vault: SettingsViewModel.QuickUnlockVaultUi,
    canAuthenticate: Boolean,
    onEnable: (String) -> Unit,
    onDisable: (String) -> Unit,
) {
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
                        stringResource(
                            if (vault.kind == VaultKind.KDBX) {
                                R.string.quick_unlock_disabled_kdbx
                            } else {
                                R.string.quick_unlock_disabled
                            },
                        )
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

/**
 * 单个库的「应用内 PIN」行。
 *
 * ⚠️ PIN **不需要**设备支持生物识别 ⇒ 这里**没有** [QuickUnlockRow] 那种
 * `canAuthenticate` 门槛：一台没有锁屏的设备也能用 PIN（安全性来自
 * `SecureCredentialStore` 那层硬件密钥，不来自系统认证）。
 */
@Composable
private fun PinRow(
    vault: SettingsViewModel.QuickUnlockVaultUi,
    onSet: (SettingsViewModel.QuickUnlockVaultUi) -> Unit,
    onPinDisable: (String) -> Unit,
) {
    ListItem(
        headlineContent = { Text(vault.name) },
        supportingContent = {
            Text(
                if (vault.pinEnabled) {
                    stringResource(R.string.pin_enabled_summary, PIN_MIN_LENGTH)
                } else {
                    stringResource(R.string.pin_disabled_summary)
                },
            )
        },
        trailingContent = {
            if (vault.pinEnabled) {
                TextButton(onClick = { onPinDisable(vault.vaultId) }) {
                    Text(stringResource(R.string.pin_disable))
                }
            }
            TextButton(onClick = { onSet(vault) }) {
                Text(
                    stringResource(
                        if (vault.pinEnabled) R.string.pin_change else R.string.pin_enable,
                    ),
                )
            }
        },
    )
}

/**
 * PIN 设置对话框的宿主：按 [SettingsViewModel.PinDialogState] 选一帧渲染。
 *
 * 单独成宿主的原因：PIN 的两步（输 PIN → KDBX 输主密码）是**同一个流程的两个阶段**，
 * 让它们在同一个宿主里切换，才能保证「上一步收下的 PIN」不会因为 UI 重组而丢失。
 */
@Composable
private fun PinDialogHost(viewModel: SettingsViewModel) {
    // ⚠️ 必须先用局部 `val` 接住：委托属性（`by`）**无法智能转换**，
    // 直接在 `when` 里用 `state is ...` 会编译不过。
    val state = viewModel.pinDialog.collectAsStateWithLifecycle().value
    when (state) {
        SettingsViewModel.PinDialogState.Idle -> Unit
        is SettingsViewModel.PinDialogState.Entering ->
            PinSetDialog(state = state, viewModel = viewModel)
        is SettingsViewModel.PinDialogState.AskingKdbxPassword ->
            PinKdbxPasswordDialog(state = state, viewModel = viewModel)
    }
}

/**
 * 设置 PIN（第一步）：输入两次。
 *
 * 用 `NumberPassword` 键盘：PIN 是纯数字，弹全键盘只会让用户多找一次数字行。
 * 两次输入一致性与位数校验都在 ViewModel（见 `confirmPinEntry`），这里只负责画错误。
 */
@Composable
private fun PinSetDialog(
    state: SettingsViewModel.PinDialogState.Entering,
    viewModel: SettingsViewModel,
) {
    AlertDialog(
        onDismissRequest = viewModel::dismissPinDialog,
        title = { Text(stringResource(R.string.pin_set_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.pin_set_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                PinField(
                    value = state.pin,
                    labelRes = R.string.pin_label,
                    onValueChange = viewModel::onPinChange,
                )
                Spacer(Modifier.height(8.dp))
                PinField(
                    value = state.confirm,
                    labelRes = R.string.pin_confirm_label,
                    onValueChange = viewModel::onPinConfirmChange,
                )
                DialogErrorText(state.error)
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::confirmPinEntry) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissPinDialog) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/** KDBX 第二步：再输一次主密码（会话里没有它，PIN 无从包裹）。 */
@Composable
private fun PinKdbxPasswordDialog(
    state: SettingsViewModel.PinDialogState.AskingKdbxPassword,
    viewModel: SettingsViewModel,
) {
    AlertDialog(
        onDismissRequest = viewModel::dismissPinDialog,
        title = { Text(stringResource(R.string.pin_kdbx_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.pin_kdbx_message, state.vaultName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.password,
                    onValueChange = viewModel::onPinKdbxPasswordChange,
                    label = { Text(stringResource(R.string.kdbx_master_password_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                DialogErrorText(state.error)
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::confirmKdbxPasswordForPin) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissPinDialog) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/** 纯数字、遮蔽输入的字段（PIN 两格共用：样式只有一处，改就一起改）。 */
@Composable
private fun PinField(value: String, labelRes: Int, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(labelRes)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 对话框内的错误行；无错误时不占位（不要留一行空高，会让对话框忽高忽低）。 */
@Composable
private fun DialogErrorText(message: String?) {
    if (message == null) return
    Spacer(Modifier.height(8.dp))
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

/**
 * KDBX 启用快速解锁前的主密码输入框（定稿 §4.5 —— 这一步**无法省略**）。
 *
 * 「宽松」取向（§4.4）的 UI 落地：
 * - 输错 ⇒ 只显示错误、**输入框保留**、按钮可再点（不清空、不关框）；
 * - 校验期间按钮禁用，避免连点产生多个 cipher。
 */
@Composable
private fun KdbxQuickUnlockPasswordDialog(
    vaultName: String,
    state: SettingsViewModel.KdbxEnrollState,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by rememberSaveable { mutableStateOf("") }
    val busy = state is SettingsViewModel.KdbxEnrollState.Ready
    val errorText = when (state) {
        is SettingsViewModel.KdbxEnrollState.WrongPassword -> stringResource(R.string.kdbx_quick_unlock_wrong_password)
        is SettingsViewModel.KdbxEnrollState.Failed -> state.detail
        is SettingsViewModel.KdbxEnrollState.Unavailable -> stringResource(R.string.quick_unlock_device_unsupported)
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.kdbx_quick_unlock_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.kdbx_quick_unlock_message, vaultName),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.kdbx_master_password_label)) },
                    singleLine = true,
                    isError = errorText != null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (errorText != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = errorText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(password) },
                enabled = password.isNotEmpty() && !busy,
            ) {
                Text(stringResource(R.string.action_enable))
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
