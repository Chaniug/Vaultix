package io.vaultix.vaultix.ui.items

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.common.ItemFormDialog
import io.vaultix.vaultix.ui.common.itemTypeLabelRes

/**
 * 条目列表（Docs/08 S7 最小版）+ 新建条目对话框（S10 最小版）。
 *
 * - 大标题随滚动缩放（Docs/16 §4 交互基线）；
 * - 同步状态提示条 + 手动同步 / 立即锁定动作；
 * - 行点击 → 详情页；空态居中引导；新建走 dirty 队列 + 轻量推送。
 */

/** 同步成功/跳过提示的自动消失延迟（ms）。 */
private const val NOTE_DISMISS_DELAY_MS = 2_000L
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemsScreen(
    onBack: () -> Unit,
    onLocked: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenItem: (VaultItem) -> Unit,
    onOpenTotp: () -> Unit,
    viewModel: ItemsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val visibleItems by viewModel.visibleItems.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.saveEvents.collect { event ->
            val message = when (event) {
                ItemsViewModel.SaveEvent.SavedSynced ->
                    context.getString(R.string.item_saved_synced)
                ItemsViewModel.SaveEvent.SavedQueued ->
                    context.getString(R.string.item_saved_queued)
                is ItemsViewModel.SaveEvent.Failed ->
                    context.getString(R.string.item_save_failed, event.message)
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                LargeTopAppBar(
                    title = {
                        VaultTitle(
                            name = state.vault?.name.orEmpty(),
                            account = state.vault?.account,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    },
                    actions = {
                        ItemsActions(
                            onToggleSearch = {
                                searchActive = !searchActive
                                if (!searchActive) viewModel.setQuery("")
                            },
                            onOpenTotp = onOpenTotp,
                            onOpenTrash = onOpenTrash,
                            onRetrySync = viewModel::retrySync,
                            onLock = { viewModel.lockNow(onLocked) },
                        )
                    },
                    scrollBehavior = scrollBehavior,
                )
                if (searchActive) {
                    SearchField(query = state.query, onQueryChange = viewModel::setQuery)
                }
                SyncNoteBanner(
                    note = state.syncNote,
                    onDismiss = viewModel::dismissSyncNote,
                    onRetry = viewModel::retrySync,
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.items_new_item))
            }
        },
    ) { padding ->
        val syncing by viewModel.isSyncing.collectAsStateWithLifecycle()
        PullToRefreshBox(
            isRefreshing = syncing,
            onRefresh = viewModel::retrySync,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (visibleItems.isEmpty()) {
                if (state.query.isBlank()) {
                    EmptyItemsState()
                } else {
                    NoSearchResultState()
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    items(visibleItems, key = { it.id }) { item ->
                        ItemRow(item = item, onClick = { onOpenItem(item) })
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        val blankItem = remember { VaultItem(id = "", title = "") }
        ItemFormDialog(
            title = stringResource(R.string.items_new_item),
            initial = blankItem,
            folders = folders,
            saving = state.saving,
            // 新建可选类型（登录 / 银行卡 / 身份 / 安全笔记 / SSH）；
            // 编辑态不开，避免改类型让原类型载荷失去意义。
            typeEditable = true,
            onDismiss = { showCreateDialog = false },
            onSave = { item ->
                viewModel.createItem(item)
                showCreateDialog = false
            },
        )
    }
}

/** 顶栏标题：库名 + 账号（账号为空时只显示库名）。 */
@Composable
private fun VaultTitle(name: String, account: String?) {
    Column {
        Text(text = name)
        if (account != null) {
            Text(
                text = account,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 顶栏动作（搜索在最前，其后为验证码 / 回收站 / 同步 / 锁定）。 */
@Composable
private fun ItemsActions(
    onToggleSearch: () -> Unit,
    onOpenTotp: () -> Unit,
    onOpenTrash: () -> Unit,
    onRetrySync: () -> Unit,
    onLock: () -> Unit,
) {
    IconButton(onClick = onToggleSearch) {
        Icon(
            Icons.Filled.Search,
            contentDescription = stringResource(R.string.items_search),
        )
    }
    IconButton(onClick = onOpenTotp) {
        Icon(
            Icons.Filled.QrCode2,
            contentDescription = stringResource(R.string.totp_screen_title),
        )
    }
    IconButton(onClick = onOpenTrash) {
        Icon(
            Icons.Filled.Delete,
            contentDescription = stringResource(R.string.trash_title),
        )
    }
    IconButton(onClick = onRetrySync) {
        Icon(
            Icons.Filled.Refresh,
            contentDescription = stringResource(R.string.items_sync),
        )
    }
    IconButton(onClick = onLock) {
        Icon(
            Icons.Filled.Lock,
            contentDescription = stringResource(R.string.items_lock),
        )
    }
}

/** 搜索框（顶栏展开态）：有输入时右侧出现清除按钮。 */
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(stringResource(R.string.items_search_hint)) },
        singleLine = true,
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.items_search_clear),
                    )
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** 同步状态提示条：进行中 = 细进度条；成功/跳过 = 短暂提示后自动消失；警告 = 常驻到下次同步。 */
@Composable
private fun SyncNoteBanner(
    note: ItemsViewModel.SyncNote?,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    LaunchedEffect(note) {
        if (note is ItemsViewModel.SyncNote.Success || note is ItemsViewModel.SyncNote.Skipped) {
            kotlinx.coroutines.delay(NOTE_DISMISS_DELAY_MS)
            onDismiss()
        }
    }
    if (note == null) return

    Surface(
        color = when (note) {
            is ItemsViewModel.SyncNote.Warning -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        when (note) {
            ItemsViewModel.SyncNote.InProgress -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            is ItemsViewModel.SyncNote.Warning -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = note.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                    )
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
            }
            is ItemsViewModel.SyncNote.Success -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.items_sync_done, note.cipherCount),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            ItemsViewModel.SyncNote.Skipped -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.items_sync_skipped),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyItemsState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.items_empty_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(R.string.items_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** 搜索无命中时的空态（区别于「库里还没有条目」）。 */
@Composable
private fun NoSearchResultState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.items_search_empty),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemRow(item: VaultItem, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            ) {
                Text(
                    text = item.title.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title.ifBlank { stringResource(R.string.items_item_unnamed) },
                    style = MaterialTheme.typography.titleMedium,
                )
                if (item.username.isNotBlank()) {
                    Text(
                        text = item.username,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (item.type != VaultItemType.Login) {
                    Text(
                        text = stringResource(itemTypeLabelRes(item.type)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
