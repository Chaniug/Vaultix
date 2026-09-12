package io.vaultix.vaultix.ui.items

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
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
    val cardDisplayMode by viewModel.cardDisplayMode.collectAsStateWithLifecycle()
    val showIcon by viewModel.showIcon.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var displayOptionsOpen by rememberSaveable { mutableStateOf(false) }
    // 顶栏点库名 → 展开快捷筛选条（对齐 Bastion `titleExpanded`）。
    // **默认收起**（用户要求就是「点库名展开」这个动作本身；一直摊开会让列表少一行）。
    // 走 rememberSaveable：切 Tab 回来仍保持展开（用户刚点开就切走再回来，不该又收起来）。
    var quickFiltersExpanded by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val collapse = rememberScrollCollapseFraction(listState)
    val barPadding = rememberImmersiveBarPadding(collapse)
    // 折叠起来的分组 key（默认全部展开；存 saveable，切 Tab 回来不丢）。
    var collapsedGroups by rememberSaveable { mutableStateOf(emptySet<String>()) }

    // 分组（纯逻辑在 ItemsGrouping.kt；不分组时只有一项、标题为空 → UI 不画分组头）。
    val groups = rememberGroupedItems(visibleItems, folders, groupMode)

    // 底部导航条「+」→ 打开新建表单（Tab 内嵌时不展示自己的 FAB）
    LaunchedEffect(addRequest) {
        if (addRequest > 0) {
            showCreateDialog = true
            onAddConsumed()
        }
    }

    SaveEventSnackbar(viewModel = viewModel, hostState = snackbarHostState)

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
                            displayMode = cardDisplayMode,
                            showIcon = showIcon,
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
                QuickFilterPanel(
                    visible = quickFiltersExpanded,
                    selected = state.quickFilter,
                    topPadding = barPadding,
                    onSelect = viewModel::setQuickFilter,
                    onDismiss = { quickFiltersExpanded = false },
                )
                ItemsTopBar(
                    title = itemsTitle(
                        vaultName = state.vault?.name.orEmpty(),
                        filter = state.quickFilter,
                        filterLabel = stringResource(quickFilterLabelRes(state.quickFilter)),
                    ),
                    collapseFraction = collapse,
                    embedded = embedded,
                    titleExpanded = quickFiltersExpanded,
                    onTitleClick = { quickFiltersExpanded = !quickFiltersExpanded },
                    onBack = onBack,
                    onDisplayOptions = { displayOptionsOpen = true },
                    onToggleSearch = { searchActive = true },
                    onOpenTotp = onOpenTotp,
                    onOpenTrash = onOpenTrash,
                    onRetrySync = viewModel::retrySync,
                    onLock = { viewModel.lockNow(onLocked) },
                )
            }
        }
    }

    DisplayOptionsHost(
        visible = displayOptionsOpen,
        groupMode = groupMode,
        cardDisplayMode = cardDisplayMode,
        showIcon = showIcon,
        onDismiss = { displayOptionsOpen = false },
        onGroupMode = viewModel::setGroupMode,
        onCardDisplayMode = viewModel::setCardDisplayMode,
        onShowIcon = viewModel::setShowIcon,
    )

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

/** 分组（纯逻辑在 [groupItems]；不分组时只有一项、标题为空 → UI 不画分组头）。 */
@Composable
private fun rememberGroupedItems(
    visibleItems: List<VaultItem>,
    folders: List<VaultFolder>,
    groupMode: ItemsGroupMode,
): List<ItemsGroup> {
    val context = LocalContext.current
    return remember(visibleItems, folders, groupMode) {
        groupItems(
            items = visibleItems,
            folders = folders,
            mode = groupMode,
            typeLabel = { context.getString(itemTypeLabelRes(it.type)) },
            unnamedLabel = context.getString(R.string.items_item_unnamed),
            noFolderLabel = context.getString(R.string.items_group_no_folder),
        )
    }
}

/** 保存 / 删除事件的 Snackbar 提示（抽出来压主函数的行数）。 */
@Composable
private fun SaveEventSnackbar(
    viewModel: ItemsViewModel,
    hostState: SnackbarHostState,
) {
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
                is ItemsViewModel.SaveEvent.Deleted ->
                    context.getString(R.string.items_deleted_to_trash, event.title)
            }
            hostState.showSnackbar(message)
        }
    }
}

/** 显示选项弹层的宿主（`visible=false` 时什么都不渲染）。 */
@Composable
private fun DisplayOptionsHost(
    visible: Boolean,
    groupMode: ItemsGroupMode,
    cardDisplayMode: ItemsCardDisplayMode,
    showIcon: Boolean,
    onDismiss: () -> Unit,
    onGroupMode: (ItemsGroupMode) -> Unit,
    onCardDisplayMode: (ItemsCardDisplayMode) -> Unit,
    onShowIcon: (Boolean) -> Unit,
) {
    if (!visible) return
    DisplayOptionsSheet(
        groupMode = groupMode,
        cardDisplayMode = cardDisplayMode,
        showIcon = showIcon,
        onDismiss = onDismiss,
        onGroupMode = onGroupMode,
        onCardDisplayMode = onCardDisplayMode,
        onShowIcon = onShowIcon,
    )
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
 * 沉浸式顶栏 + **胶囊动作组**（🔍 搜索 + ⋮ 更多）。
 *
 * 用户要求（`.ai/ISSUES.md` #60 后续批次第 2 批）：「顶栏胶囊化（🔍 搜索 + ⋮ 更多＝
 * 同步 / 锁定 / 回收站）+ 点左上角库名展开/收起分类筛选」。
 *
 * 为什么把 6 个图标收成 2 个：一排 6 个 IconButton 在小屏上把标题挤成省略号，
 * 且「验证码 / 回收站 / 同步 / 锁定」都不是高频动作（低频动作进 overflow 是 M3 的
 * 既定做法，Bastion 的 `PasswordListTopSection` 同样只留搜索 + ⋮）。
 *
 * 抽成独立 composable 是为了把主函数的行数与圈复杂度压回门禁线内
 * （detekt `LongMethod` ≤150 / `CyclomaticComplexMethod` ≤14）。
 */
@Composable
private fun BoxScope.ItemsTopBar(
    title: String,
    collapseFraction: Float,
    embedded: Boolean,
    titleExpanded: Boolean,
    onTitleClick: () -> Unit,
    onBack: () -> Unit,
    onDisplayOptions: () -> Unit,
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
        onTitleClick = onTitleClick,
        titleExpanded = titleExpanded,
        titleClickHint = stringResource(R.string.items_quick_filter_hint),
        actions = {
            IconButton(onClick = onToggleSearch) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = stringResource(R.string.items_search),
                )
            }
            ItemsMoreMenu(
                onDisplayOptions = onDisplayOptions,
                onOpenTotp = onOpenTotp,
                onOpenTrash = onOpenTrash,
                onRetrySync = onRetrySync,
                onLock = onLock,
            )
        },
    )
}

/**
 * 顶栏「更多」菜单（⋮）：验证码 / 通行密钥回收站 / 显示选项 / 同步 / 锁定查看层。
 *
 * ⚠️ 菜单项顺序 = 使用频率：查看类（验证码 / 回收站 / 显示选项）在上，
 * 维护类（同步）居中，破坏性动作（锁定）在下并用 error 色分隔
 * （对齐 M3「破坏性动作不挨着常用动作」的建议）。
 */
@Composable
private fun ItemsMoreMenu(
    onDisplayOptions: () -> Unit,
    onOpenTotp: () -> Unit,
    onOpenTrash: () -> Unit,
    onRetrySync: () -> Unit,
    onLock: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.items_more_actions),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MenuAction(Icons.Filled.QrCode2, R.string.totp_screen_title) {
                expanded = false
                onOpenTotp()
            }
            MenuAction(Icons.Filled.Delete, R.string.trash_title) {
                expanded = false
                onOpenTrash()
            }
            MenuAction(Icons.Filled.ViewAgenda, R.string.items_display_options) {
                expanded = false
                onDisplayOptions()
            }
            MenuAction(Icons.Filled.Refresh, R.string.items_sync) {
                expanded = false
                onRetrySync()
            }
            HorizontalDivider()
            // 「锁定」= 锁查看层（不清密钥，一次生物识别即回来），见 ISSUES #60 1b
            MenuAction(Icons.Filled.Lock, R.string.items_lock, destructive = true) {
                expanded = false
                onLock()
            }
        }
    }
}

/** 菜单项（图标 + 文案；[destructive] 用 error 色标出不可逆 / 中断性动作）。 */
@Composable
private fun MenuAction(
    icon: ImageVector,
    @StringRes labelRes: Int,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Text(
                text = stringResource(labelRes),
                color = if (destructive) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (destructive) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
        onClick = onClick,
    )
}

/**
 * 快捷筛选面板：顶栏下方的一排 chip（验证码 / 通行密钥 / SSH / 笔记 / 收藏 …）。
 *
 * **浮在内容之上**（与顶栏同层，`topPadding = rememberImmersiveBarPadding(...)`）：
 * 若把它塞进可滚动的 `Column`，列表一滚它就跟着滚走，而顶栏的箭头还指着「已展开」——
 * 用户会以为筛选条坏了（`.ai/ISSUES.md` #57 的同一类问题）。
 *
 * [visible] 为 true 时同时铺一层全屏透明遮罩：点面板以外任何地方即收起，免去找关闭按钮。
 */
@Composable
private fun BoxScope.QuickFilterPanel(
    visible: Boolean,
    selected: ItemsQuickFilter,
    topPadding: Dp,
    onSelect: (ItemsQuickFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top),
        modifier = Modifier.align(Alignment.TopCenter),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 透明遮罩：点面板以外任何地方即收起，免去找关闭按钮。
            // 用 `matchParentSize` 而不是 `fillMaxSize`：它不参与父 Box 的尺寸测量，
            // 因此不会把「只剩 chip 行高度」的父布局撑成满屏。
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
            QuickFilterChips(
                selected = selected,
                topPadding = topPadding,
                onSelect = onSelect,
            )
        }
    }
}

/** 横向可滚的 chip 行（chip 数会随维度增加，小屏必须能横滑）。 */
@Composable
private fun QuickFilterChips(
    selected: ItemsQuickFilter,
    topPadding: Dp,
    onSelect: (ItemsQuickFilter) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = topPadding)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ItemsQuickFilter.entries.forEach { filter ->
                FilterChip(
                    selected = filter == selected,
                    onClick = { onSelect(filter) },
                    label = { Text(stringResource(quickFilterLabelRes(filter))) },
                    leadingIcon = if (filter == selected) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/** 筛选维度 → 文案（[ItemsQuickFilter.All] 为「全部」）。 */
@StringRes
private fun quickFilterLabelRes(filter: ItemsQuickFilter): Int = when (filter) {
    ItemsQuickFilter.All -> R.string.items_filter_all
    ItemsQuickFilter.Totp -> R.string.items_filter_totp
    ItemsQuickFilter.Passkey -> R.string.items_filter_passkey
    ItemsQuickFilter.Ssh -> R.string.items_filter_ssh
    ItemsQuickFilter.Note -> R.string.items_filter_note
    ItemsQuickFilter.Favorite -> R.string.items_filter_favorite
}

/**
 * 顶栏标题：无筛选时就是库名；有筛选时拼成「库名 · 筛选名」。
 *
 * 为什么必须拼：筛选生效后列表条数会明显变少，标题不写清「现在在看什么」，
 * 用户第一反应是「我的条目丢了」（`.ai/ISSUES.md` 里同类误报的常见来源）。
 */
private fun itemsTitle(vaultName: String, filter: ItemsQuickFilter, filterLabel: String): String =
    if (filter == ItemsQuickFilter.All) vaultName else "$vaultName · $filterLabel"
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
    displayMode: ItemsCardDisplayMode,
    showIcon: Boolean,
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
                        ItemRow(
                            item = item,
                            displayMode = displayMode,
                            showIcon = showIcon,
                            onClick = { onOpenItem(item) },
                        )
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

/** 顶栏动作已收敛为「胶囊里的 🔍 + ⋮」（见 [ItemsMoreMenu]）。 */

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


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemRow(
    item: VaultItem,
    displayMode: ItemsCardDisplayMode,
    showIcon: Boolean,
    onClick: () -> Unit,
) {
    // 卡片外框规格见 [EntryCard]（对齐 Bastion PasswordEntryCard：M3 默认 Card 底色/高度 +
    // 12dp 圆角 + 16dp 内边距 + 标题 SemiBold + 6dp 行距）。
    EntryCard(onClick = onClick) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (showIcon) {
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
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(EntryCardTextSpacing),
            ) {
                Text(
                    text = item.title.ifBlank { stringResource(R.string.items_item_unnamed) },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                // 信息密度（对齐 Bastion PasswordCardDisplayMode）：
                // TitleOnly 只留标题；TitleUsername 只显示用户名；All 显示用户名或类型徽标。
                if (displayMode == ItemsCardDisplayMode.All && item.username.isNotBlank()) {
                    Text(
                        text = item.username,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (displayMode == ItemsCardDisplayMode.All && item.type != VaultItemType.Login) {
                    Text(
                        text = stringResource(itemTypeLabelRes(item.type)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else if (displayMode == ItemsCardDisplayMode.TitleUsername &&
                    item.type == VaultItemType.Login &&
                    item.username.isNotBlank()
                ) {
                    Text(
                        text = item.username,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
