package io.vaultix.vaultix.ui.vaultlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.vaultix.domain.VaultSyncStatus
import io.vaultix.model.VaultKind
import io.vaultix.model.VaultSummary
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.AppFlavor
import io.vaultix.vaultix.ui.common.AddVaultTypeDialog
import io.vaultix.vaultix.ui.common.BiometricPrompter
import io.vaultix.vaultix.ui.common.deviceCanAuthenticate
import io.vaultix.vaultix.ui.common.rememberFragmentActivity

/**
 * 库列表（Docs/08 S3 最小版）。
 *
 * 交互基线（Docs/16 §4）：LargeTopAppBar 大标题随列表滚动缩放收起，
 * 由滚动位置驱动（nestedScroll），非定时器。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultListScreen(
    onAddVault: () -> Unit,
    onAddKdbx: () -> Unit,
    onOpenVault: (VaultSummary) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: VaultListViewModel = hiltViewModel(),
) {
    val vaults by viewModel.vaults.collectAsStateWithLifecycle()
    val syncStatuses by viewModel.syncStatuses.collectAsStateWithLifecycle()
    val quickUnlockSuggest by viewModel.quickUnlockSuggest.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val activity = rememberFragmentActivity()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var vaultToRemove by remember { mutableStateOf<VaultSummary?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    // 横幅启用 KDBX 时等待主密码的库 id（null = 不显示输入框）
    var pendingKdbxVault by remember { mutableStateOf<VaultSummary?>(null) }
    var kdbxPasswordError by remember { mutableStateOf<String?>(null) }
    // 认证对话框文案
    val enrollTitle = stringResource(R.string.quick_unlock_enroll_title)
    val cancelText = stringResource(R.string.action_cancel)

    // 启用引导：收到 cipher 即弹认证，成功后完成密钥包裹（横幅自动消失）
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is VaultListViewModel.Event.PromptForEnroll -> {
                    // 指纹框已弹 ⇒ KDBX 密码框使命完成，收起。
                    pendingKdbxVault = null
                    val host = activity ?: return@collect
                    BiometricPrompter(host).authenticate(
                        cipher = event.cipher,
                        title = enrollTitle,
                        cancelText = cancelText,
                        onSuccess = { cipher -> viewModel.enrollWithCipher(event.vaultId, cipher) },
                        onError = { _, _, _ -> /* 取消/失败：横幅保留，可再试 */ },
                    )
                }
                is VaultListViewModel.Event.PromptForKdbxPassword ->
                    pendingKdbxVault = vaults.firstOrNull { it.id == event.vaultId }
                is VaultListViewModel.Event.KdbxPasswordRejected ->
                    kdbxPasswordError = event.detail
                        ?: context.getString(R.string.kdbx_quick_unlock_wrong_password)
                VaultListViewModel.Event.Removed ->
                    snackbarHostState.showSnackbar(context.getString(R.string.vault_removed))
                is VaultListViewModel.Event.RemoveFailed ->
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.item_save_failed, event.message),
                    )
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            LargeTopAppBar(
                title = { Text(text = stringResource(R.string.vault_list_title)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.settings_title),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            // 「+」= 添加库：Bitwarden 云端 或 本地 KDBX 文件（两种库类型二选一，
            // 与产品定义「登录时二选一」一致）。offline 分发只保留 KDBX。
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.vault_add_fab))
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (vaults.isEmpty()) {
                EmptyVaultState(onConnectBitwarden = onAddVault, onOpenKdbx = onAddKdbx)
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    // 快速解锁启用引导（设备支持时显示；enroll 后自动消失）
                    val suggest = quickUnlockSuggest
                    if (suggest != null && deviceCanAuthenticate(LocalContext.current)) {
                        QuickUnlockBanner(
                            onEnable = { viewModel.startQuickUnlockEnroll(suggest.id) },
                            onDismiss = viewModel::dismissQuickUnlockPrompt,
                        )
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(vaults, key = { it.id }) { vault ->
                        VaultCard(
                            vault = vault,
                            syncStatus = syncStatuses[vault.id],
                            onClick = { onOpenVault(vault) },
                            onRemove = { vaultToRemove = vault },
                        )
                    }
                }
                }
            }
        }
    }

    VaultListDialogs(
        vaultToRemove = vaultToRemove,
        onDismissRemove = { vaultToRemove = null },
        onConfirmRemove = { vaultToRemove = null; viewModel.removeVault(it) },
        kdbxTarget = pendingKdbxVault,
        kdbxPasswordError = kdbxPasswordError,
        onSubmitKdbxPassword = { id, password ->
            kdbxPasswordError = null
            viewModel.confirmKdbxPassword(id, password)
        },
        onDismissKdbx = {
            pendingKdbxVault = null
            kdbxPasswordError = null
        },
        showAddDialog = showAddDialog,
        onDismissAdd = { showAddDialog = false },
        onAddVault = onAddVault,
        onAddKdbx = onAddKdbx,
    )
}

/**
 * 库列表页的对话框集合（移除确认 / KDBX 启用主密码 / 添加库类型）。
 *
 * 抽出来是为了 [VaultListScreen] 不超 detekt `LongMethod`(150) 上限，
 * 同时让「页面布局」与「对话框编排」各自可读。
 */
@Composable
private fun VaultListDialogs(
    vaultToRemove: VaultSummary?,
    onDismissRemove: () -> Unit,
    onConfirmRemove: (String) -> Unit,
    kdbxTarget: VaultSummary?,
    kdbxPasswordError: String?,
    onSubmitKdbxPassword: (String, String) -> Unit,
    onDismissKdbx: () -> Unit,
    showAddDialog: Boolean,
    onDismissAdd: () -> Unit,
    onAddVault: () -> Unit,
    onAddKdbx: () -> Unit,
) {
    if (vaultToRemove != null) {
        AlertDialog(
            onDismissRequest = onDismissRemove,
            title = { Text(stringResource(R.string.vault_remove_title)) },
            text = { Text(stringResource(R.string.vault_remove_message, vaultToRemove.name)) },
            confirmButton = {
                TextButton(onClick = { onConfirmRemove(vaultToRemove.id) }) {
                    Text(
                        text = stringResource(R.string.vault_remove_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRemove) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    // KDBX 横幅启用前的主密码确认（定稿 §4.5：KDBX 没有可包裹的会话密钥）。
    if (kdbxTarget != null) {
        KdbxBannerPasswordDialog(
            vaultName = kdbxTarget.name,
            errorText = kdbxPasswordError,
            onSubmit = { password -> onSubmitKdbxPassword(kdbxTarget.id, password) },
            onDismiss = onDismissKdbx,
        )
    }

    // 添加库：两种类型二选一（Bitwarden 云端 / 本地 KDBX 文件）。
    if (showAddDialog) {
        AddVaultTypeDialog(
            onConnectBitwarden = {
                onDismissAdd()
                onAddVault()
            },
            onOpenKdbx = {
                onDismissAdd()
                onAddKdbx()
            },
            onDismiss = onDismissAdd,
        )
    }
}

/**
 * 添加库的类型选择对话框已提到 [io.vaultix.vaultix.ui.common.AddVaultTypeDialog]：
 * 设置页「密码库」也要同一个入口（库列表路由在已有库时不可达，
 * 否则用户永远加不了本地 KDBX 库）。
 */

@Composable
private fun QuickUnlockBanner(
    onEnable: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.quick_unlock_enroll_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.quick_unlock_banner_text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                TextButton(onClick = onEnable) {
                    Text(stringResource(R.string.action_enable))
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_later))
                }
            }
        }
    }
}

@Composable
private fun EmptyVaultState(onConnectBitwarden: () -> Unit, onOpenKdbx: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.vault_list_empty_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(R.string.vault_list_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        if (AppFlavor.supportsBitwarden) {
            Button(onClick = onConnectBitwarden) {
                Text(stringResource(R.string.vault_connect_bitwarden))
            }
            Spacer(Modifier.height(12.dp))
        }
        // KDBX 入口对 full / offline **两种分发都开放**（本地库不需要网络，
        // offline 分发反而只有它可用 —— 此前这里写的是「即将支持」占位）。
        OutlinedButton(onClick = onOpenKdbx) {
            Text(stringResource(R.string.vault_open_kdbx))
        }
    }
}

@Composable
private fun VaultCard(
    vault: VaultSummary,
    syncStatus: VaultSyncStatus?,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = vault.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = vaultSubtitle(vault),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                SyncStatusLine(syncStatus = syncStatus)
            }
            if (!vault.unlocked) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = stringResource(R.string.vault_badge_locked),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            VaultCardMenu(onRemove = onRemove)
        }
    }
}

/**
 * 卡片副标题：**给用户看的字段必须是用户语义的**（`.ai/issues/05-KDBX本地库.md` #95）。
 *
 * - Bitwarden：`account`（邮箱）→ 退化到 `origin`（`https://...`，本身可读）；
 * - KDBX：`account` 恒为 null，而 `origin`/`id` **就是** SAF 的 `content://` URI
 *   （`addKdbxVault` 用 `vaultId = sourceUri`）⇒ 直接渲染会露出
 *   `content://com.android.externalstorage.documents/document/primary%3A...` 这种
 *   给系统看的、URL 编码过的字符串。
 *
 * ⇒ KDBX 一律取**文件名**（`name` 就是添加时从 SAF 取的显示名），并标注「本地文件」
 * 让用户一眼知道它不在云端。
 */
@Composable
private fun vaultSubtitle(vault: VaultSummary): String = when (vault.kind) {
    VaultKind.KDBX -> stringResource(R.string.vault_card_kdbx_subtitle, vault.name)
    VaultKind.BITWARDEN -> vault.account ?: vault.origin
}

/** 卡片更多菜单：移除库（破坏性动作入口；确认对话框在列表层）。 */
@Composable
private fun VaultCardMenu(onRemove: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menuOpen = true }) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.vault_more_actions),
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(R.string.vault_remove_menu),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    menuOpen = false
                    onRemove()
                },
            )
        }
    }
}

/**
 * 卡片行内同步状态轻提示（Bastion SyncStatusIndicator 语义的轻量裁剪）：
 * - 同步中 → 「正在同步…」（primary）；
 * - 最近一次结果是失败且尚无更新的成功 → 「同步失败：…」（error）；
 * - 其余（含静默成功）不显示，保持列表安静（Bastion 静默同步语义）。
 */
@Composable
private fun SyncStatusLine(syncStatus: VaultSyncStatus?) {
    val s = syncStatus ?: return
    val errorIsLatest = (s.lastErrorAt ?: Long.MIN_VALUE) >= (s.lastSuccessAt ?: Long.MIN_VALUE)
    val lastError = s.lastError
    val (text, color) = when {
        s.isRunning -> stringResource(R.string.vault_syncing) to MaterialTheme.colorScheme.primary
        errorIsLatest && lastError != null ->
            stringResource(R.string.vault_sync_failed, lastError) to MaterialTheme.colorScheme.error
        else -> return
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        modifier = Modifier.padding(top = 2.dp),
    )
}

/**
 * 横幅启用 KDBX 快速解锁前的主密码确认框。
 *
 * KDBX 与 Bitwarden 的**根本差异**（定稿 §4.5）：Bitwarden 会话里握着对称密钥，
 * 勾选即可直接包裹；KDBX 会话里**没有可包裹的东西**，唯一能重新开库的凭据就是
 * 「主密码 + keyfile」，而主密码只在用户脑子里 ⇒ 必须当场再问一次。
 *
 * 「宽松」取向（§4.4）：输错只显示错误、**输入框保留**，用户就地重输即可。
 */
@Composable
private fun KdbxBannerPasswordDialog(
    vaultName: String,
    errorText: String?,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by rememberSaveable { mutableStateOf("") }

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
                enabled = password.isNotEmpty(),
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
