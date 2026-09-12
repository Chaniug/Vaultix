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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.vaultix.domain.VaultSyncStatus
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
    // 认证对话框文案
    val enrollTitle = stringResource(R.string.quick_unlock_enroll_title)
    val cancelText = stringResource(R.string.action_cancel)

    // 启用引导：收到 cipher 即弹认证，成功后完成密钥包裹（横幅自动消失）
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is VaultListViewModel.Event.PromptForEnroll -> {
                    val host = activity ?: return@collect
                    BiometricPrompter(host).authenticate(
                        cipher = event.cipher,
                        title = enrollTitle,
                        cancelText = cancelText,
                        onSuccess = { cipher -> viewModel.enrollWithCipher(event.vaultId, cipher) },
                        onError = { _, _ -> /* 取消/失败：横幅保留，可再试 */ },
                    )
                }
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

    val removing = vaultToRemove
    if (removing != null) {
        AlertDialog(
            onDismissRequest = { vaultToRemove = null },
            title = { Text(stringResource(R.string.vault_remove_title)) },
            text = { Text(stringResource(R.string.vault_remove_message, removing.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        vaultToRemove = null
                        viewModel.removeVault(removing.id)
                    },
                ) {
                    Text(
                        text = stringResource(R.string.vault_remove_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { vaultToRemove = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    // 添加库：两种类型二选一（Bitwarden 云端 / 本地 KDBX 文件）。
    if (showAddDialog) {
        AddVaultTypeDialog(
            onConnectBitwarden = {
                showAddDialog = false
                onAddVault()
            },
            onOpenKdbx = {
                showAddDialog = false
                onAddKdbx()
            },
            onDismiss = { showAddDialog = false },
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
                    text = vault.account ?: vault.origin,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
