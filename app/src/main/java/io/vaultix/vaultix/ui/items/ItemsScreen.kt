package io.vaultix.vaultix.ui.items

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.vaultix.model.VaultFolder
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.common.EntryCard
import io.vaultix.vaultix.ui.common.EntryCardIconSize
import io.vaultix.vaultix.ui.common.EntryCardIconSpacing
import io.vaultix.vaultix.ui.common.EntryCardTextSpacing
import io.vaultix.vaultix.ui.common.ItemFormDialog
import io.vaultix.vaultix.ui.common.PressAndSwipeToDelete
import io.vaultix.vaultix.ui.common.VaultixExpressiveTopBar
import io.vaultix.vaultix.ui.common.VaultixSearchTopAppBar
import io.vaultix.vaultix.ui.common.itemTypeLabelRes
import io.vaultix.vaultix.ui.common.rememberImmersiveBarPadding
import io.vaultix.vaultix.ui.common.rememberScrollCollapseFraction

/**
 * 条目列表（Docs/08 S7 最小版）+ 新建条目对话框（S10 最小版）。
 *
 * - 大标题随滚动缩放（Docs/16 §4 交互基线）；
 * - 同步状态提示条 + 手动同步 / 立即锁定动作；
 * - 行点击 → 详情页；空态居中引导；新建走 dirty 队列 + 轻量推送。
 */

/** 同步成功/跳过提示的自动消失延迟（ms）。 */
private const val NOTE_DISMISS_DELAY_MS = 2_000L

/** 卡片之间的纵向间距（8dp；水平留白由 LazyColumn 的 contentPadding 统一给）。 */
private val ITEM_CARD_GAP = 8.dp

/** 分组折叠/展开的箭头动画时长（对齐 Bastion 的 200ms 补间）。 */
private const val GROUP_ANIM_MS = 200

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemsScreen(
    onBack: () -> Unit,
    onLocked: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenItem: (VaultItem) -> Unit,
    onOpenTotp: () -> Unit,
    /**
     * 主界面 Tab 内嵌模式（main-shell-migration 阶段 2）：
     * 隐藏返回键（无上层可返回）与 FAB（「+」由底部导航条统一承载）。
     */
    embedded: Boolean = false,
    /** 外部「+」请求计数：非零即打开新建表单（宿主在消费后清零，避免重复弹出）。 */
    addRequest: Int = 0,
    onAddConsumed: () -> Unit = {},
    viewModel: ItemsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val visibleItems by viewModel.visibleItems.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val groupMode by viewModel.groupMode.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val collapse = rememberScrollCollapseFraction(listState)
    val barPadding = rememberImmersiveBarPadding(collapse)
    // 折叠起来的分组 key（默认全部展开；存 saveable，切 Tab 回来不丢）。
    var collapsedGroups by rememberSaveable { mutableStateOf(emptySet<String>()) }

    // 分组（纯逻辑在 ItemsGrouping.kt；不分组时只有一项、标题为空 → UI 不画分组头）。
    val groups = remember(visibleItems, folders, groupMode) {
        groupItems(
            items = visibleItems,
            folders = folders,
            mode = groupMode,
            typeLabel = { context.getString(itemTypeLabelRes(it.type)) },
            unnamedLabel = context.getString(R.string.items_item_unnamed),
            noFolderLabel = context.getString(R.string.items_group_no_folder),
        )
    }

    // 底部导航条「+」→ 打开新建表单（Tab 内嵌时不展示自己的 FAB）
    LaunchedEffect(addRequest) {
        if (addRequest > 0) {
            showCreateDialog = true
            onAddConsumed()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.saveEvents.collect { event ->
            val message = when (event) {
                ItemsViewModel.SaveEvent.SavedSynced ->
                    context.getString(R.string.item_saved_synced)
                ItemsViewModel.SaveEvent.SavedQueued ->
                    context.getString(R.string.item_saved_queued)
                is ItemsViewModel.SaveEvent.Failed ->
                    context.getString(R.string.item_save_failed, event.message)
                is ItemsViewModel.SaveEvent.Deleted ->
                    context.getString(R.string.items_deleted_to_trash, event.title)
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // 顶栏改为**浮在内容之上**（沉浸式，见 [VaultixExpressiveTopBar]）：
        // Scaffold 不再为顶栏预留高度，状态栏内边距由顶栏自己处理。
        contentWindowInsets = if (searchActive) {
            ScaffoldDefaults.contentWindowInsets
        } else {
            WindowInsets(0, 0, 0, 0)
        },
        topBar = {
            // 搜索态保留固定高度顶栏（输入框不能塞进会折叠的大标题里）。
            if (searchActive) {
                ItemsSearchBar(
                    query = state.query,
                    onQueryChange = viewModel::setQuery,
                    onClose = {
                        searchActive = false
                        viewModel.setQuery("")
                    },
                )
            }
        },
        floatingActionButton = {
            if (!embedded) {
                FloatingActionButton(onClick = { showCreateDialog = true }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.items_new_item))
                }
            }
        },
    ) { padding ->
        val syncing by viewModel.isSyncing.collectAsStateWithLifecycle()
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize().padding(top = barPadding)) {
                SyncNoteBanner(
                    note = state.syncNote,
                    onDismiss = viewModel::dismissSyncNote,
                    onRetry = viewModel::retrySync,
                )
                PullToRefreshBox(
                    isRefreshing = syncing,
                    onRefresh = viewModel::retrySync,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (visibleItems.isEmpty()) {
                        if (state.query.isBlank()) {
                            EmptyItemsState()
                        } else {
                            NoSearchResultState()
                        }
                    } else {
                        ItemsList(
                            groups = groups,
                            listState = listState,
                            grouped = groupMode != ItemsGroupMode.None,
                            collapsedGroups = collapsedGroups,
                            onToggleGroup = { key ->
                                collapsedGroups = if (key in collapsedGroups) {
                                    collapsedGroups - key
                                } else {
                                    collapsedGroups + key
                                }
                            },
                            onOpenItem = onOpenItem,
                            onDelete = viewModel::deleteItem,
                        )
                    }
                }
            }
            if (!searchActive) {
                ItemsTopBar(
                    title = state.vault?.name.orEmpty(),
                    collapseFraction = collapse,
                    embedded = embedded,
                    groupMode = groupMode,
                    onBack = onBack,
                    onGroupMode = viewModel::setGroupMode,
                    onToggleSearch = { searchActive = true },
                    onOpenTotp = onOpenTotp,
                    onOpenTrash = onOpenTrash,
                    onRetrySync = viewModel::retrySync,
                    onLock = { viewModel.lockNow(onLocked) },
                )
            }
        }
    }

    if (showCreateDialog) {
        CreateItemDialog(
            folders = folders,
            saving = state.saving,
            onDismiss = { showCreateDialog = false },
            onSave = { item ->
                viewModel.createItem(item)
                showCreateDialog = false
            },
        )
    }
}

/** 搜索态顶栏（固定高度，见 [VaultixSearchTopAppBar]）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemsSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    VaultixSearchTopAppBar(
        searchTerm = query,
        placeholder = stringResource(R.string.items_search_hint),
        onSearchTermChange = onQueryChange,
        onClose = onClose,
        clearIconContentDescription = stringResource(R.string.items_search_clear),
        scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
    )
}

/**
 * 沉浸式顶栏 + 动作组（分组方式 / 搜索 / 验证码 / 回收站 / 同步 / 锁定）。
 *
 * 抽成独立 composable 是为了把主函数的行数与圈复杂度压回门禁线内
 * （detekt `LongMethod` ≤150 / `CyclomaticComplexMethod` ≤14）。
 */
@Composable
private fun BoxScope.ItemsTopBar(
    title: String,
    collapseFraction: Float,
    embedded: Boolean,
    groupMode: ItemsGroupMode,
    onBack: () -> Unit,
    onGroupMode: (ItemsGroupMode) -> Unit,
    onToggleSearch: () -> Unit,
    onOpenTotp: () -> Unit,
    onOpenTrash: () -> Unit,
    onRetrySync: () -> Unit,
    onLock: () -> Unit,
) {
    VaultixExpressiveTopBar(
        title = title,
        collapseFraction = collapseFraction,
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
        actions = {
            GroupModeButton(current = groupMode, onSelect = onGroupMode)
            ItemsActions(
                onToggleSearch = onToggleSearch,
                onOpenTotp = onOpenTotp,
                onOpenTrash = onOpenTrash,
                onRetrySync = onRetrySync,
                onLock = onLock,
            )
        },
    )
}

/** 分组方式按钮（自带菜单展开态，调用方不必再持有一个 `expanded` 状态）。 */
@Composable
private fun GroupModeButton(
    current: ItemsGroupMode,
    onSelect: (ItemsGroupMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(
            Icons.Filled.ViewAgenda,
            contentDescription = stringResource(R.string.items_group_mode),
        )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        ItemsGroupMode.entries.forEach { mode ->
            DropdownMenuItem(
                text = { Text(stringResource(groupModeLabelRes(mode))) },
                onClick = {
                    onSelect(mode)
                    expanded = false
                },
                trailingIcon = {
                    if (mode == current) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
            )
        }
    }
}

/**
 * 条目列表（分组 + 「按住后滑动删除」）。
 *
 * 抽成独立 composable 的原因：主函数加了顶栏/分组/沉浸式留白后行数越界
 * （detekt `LongMethod` ≤150 / `CyclomaticComplexMethod` ≤14，本项目门禁在 CI 之前跑）。
 */
@Composable
private fun ItemsList(
    groups: List<ItemsGroup>,
    listState: LazyListState,
    grouped: Boolean,
    collapsedGroups: Set<String>,
    onToggleGroup: (String) -> Unit,
    onOpenItem: (VaultItem) -> Unit,
    onDelete: (VaultItem) -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        // 水平 16dp 由列表统一留白（卡片自身不再带外边距）——
        // 对齐 Bastion `PasswordListScrollableContent` 的 contentPadding 结构。
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(ITEM_CARD_GAP),
    ) {
        groups.forEach { group ->
            if (grouped) {
                item(key = "header:${group.key}") {
                    GroupHeader(
                        title = group.title,
                        count = group.items.size,
                        expanded = group.key !in collapsedGroups,
                        onToggle = { onToggleGroup(group.key) },
                    )
                }
            }
            if (group.key !in collapsedGroups) {
                items(group.items, key = { it.id }) { item ->
                    // 「按住 → 向左滑 → 松手」删除（软删除进回收站，见 ItemsViewModel.deleteItem）。
                    PressAndSwipeToDelete(onDelete = { onDelete(item) }) {
                        ItemRow(item = item, onClick = { onOpenItem(item) })
                    }
                }
            }
        }
    }
}

/** 新建条目对话框（独立函数：把主 composable 的行数压回门禁线内）。 */
@Composable
private fun CreateItemDialog(
    folders: List<VaultFolder>,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (VaultItem) -> Unit,
) {
    val blankItem = remember { VaultItem(id = "", title = "") }
    ItemFormDialog(
        title = stringResource(R.string.items_new_item),
        initial = blankItem,
        folders = folders,
        saving = saving,
        // 新建可选类型（登录 / 银行卡 / 身份 / 安全笔记 / SSH）；
        // 编辑态不开，避免改类型让原类型载荷失去意义。
        typeEditable = true,
        onDismiss = onDismiss,
        onSave = onSave,
    )
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

/**
 * 分组标题（可点折叠/展开）。
 *
 * 观感对齐 Bastion `PasswordMenuSection`：小标题 + 数量 + 箭头，箭头随展开态旋转 0°↔180°
 * （200ms 补间）。`stickyHeader` 不用：本列表是 Collapsible section 语义（可整个收起），
 * 粘性头会与「收起后整组消失」的预期冲突。
 */
@Composable
private fun GroupHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        animationSpec = tween(GROUP_ANIM_MS),
        label = "group_arrow",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = rotation },
        )
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 6.dp),
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

private fun groupModeLabelRes(mode: ItemsGroupMode): Int = when (mode) {
    ItemsGroupMode.None -> R.string.items_group_none
    ItemsGroupMode.Type -> R.string.items_group_type
    ItemsGroupMode.Folder -> R.string.items_group_folder
    ItemsGroupMode.Initial -> R.string.items_group_initial
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemRow(item: VaultItem, onClick: () -> Unit) {
    // 卡片外框规格见 [EntryCard]（对齐 Bastion PasswordEntryCard：M3 默认 Card 底色/高度 +
    // 12dp 圆角 + 16dp 内边距 + 标题 SemiBold + 6dp 行距）。
    EntryCard(onClick = onClick) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(EntryCardIconSize)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            ) {
                Text(
                    text = item.title.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(EntryCardIconSpacing))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(EntryCardTextSpacing),
            ) {
                Text(
                    text = item.title.ifBlank { stringResource(R.string.items_item_unnamed) },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
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
