package io.vaultix.vaultix.ui.items

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
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
import io.vaultix.vaultix.ui.common.CapabilityIcon
import io.vaultix.vaultix.ui.common.CloudSyncIcon
import io.vaultix.vaultix.ui.common.EntryCard
import io.vaultix.vaultix.ui.common.EntryCardIconSize
import io.vaultix.vaultix.ui.common.EntryCardIconSpacing
import io.vaultix.vaultix.ui.common.EntryCardTextSpacing
import io.vaultix.vaultix.ui.common.ItemFormDialog
import io.vaultix.vaultix.ui.common.PressAndSwipeToDelete
import io.vaultix.vaultix.ui.common.SelectionActionBar
import io.vaultix.vaultix.ui.common.SiteIcon
import io.vaultix.vaultix.ui.common.VaultixExpressiveTopBar
import io.vaultix.vaultix.ui.common.VaultixSearchTopAppBar
import io.vaultix.vaultix.ui.common.itemTypeLabelRes
import io.vaultix.vaultix.ui.common.rememberImmersiveBarPadding
import io.vaultix.vaultix.ui.common.rememberScrollCollapseFraction
import io.vaultix.vaultix.ui.common.toggleSelection
import io.vaultix.vaultix.ui.common.VaultixWavyProgressBar
import io.vaultix.vaultix.ui.theme.Spacing

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
private val ITEM_CARD_GAP = Spacing.sm

/** 分组折叠/展开的箭头动画时长（对齐 Bastion 的 200ms 补间）。 */
private const val GROUP_ANIM_MS = 200

/**
 * 展开后的快捷筛选 chip 行高度（8dp 上下内边距 ×2 + 32dp 高的 FilterChip）。
 *
 * 列表要按这个值**加高顶部留白**，否则 chip 行会直接压在第一条条目上
 * （`.ai/ISSUES.md` #62 记着「筛选条必须浮在内容之上」——浮着是对的，
 * 但**清单必须同步让位**，两件事不矛盾）。
 */
private val QUICK_FILTER_ROW_HEIGHT = 48.dp

/**
 * 搜索态的返回手势：主界面是**根路由**（导航栈里没有上一层），不拦系统返回就会
 * 直接结束 Activity 退回桌面 —— 用户期望的是「退出搜索、回到列表」。
 */
@Composable
private fun SearchBackHandler(enabled: Boolean, onClose: () -> Unit) {
    BackHandler(enabled = enabled) { onClose() }
}

/**
 * 展开后的快捷筛选 chip 行让位高（随展开/收起动画变化）。
 *
 * 抽成独立函数的原因有两个：`animateDpAsState` 的 label 得在这里写；主 composable
 * 已贴着 detekt `LongMethod ≤150` 的门禁线。
 */
@Composable
private fun rememberQuickFilterRowInset(expanded: Boolean): Dp =
    animateDpAsState(
        targetValue = if (expanded) QUICK_FILTER_ROW_HEIGHT else 0.dp,
        animationSpec = tween(GROUP_ANIM_MS),
        label = "items_filter_row_inset",
    ).value

/**
 * 列表顶部让位 = 沉浸式顶栏高 + 展开中的筛选行高。**这两个量都只能喂给
 * `LazyColumn.contentPadding`，绝不能做成外层容器的 padding。**
 *
 * ⚠️ 2026-09-13 用户反馈「密码条目往下滑动时上方还是不能透明，不够沉浸」。根因是
 * 状态栏让位被写成了 `Column` 里的**固定 `Spacer(Modifier.height(barPadding))`** ——
 * 列表视口被整体下压，内容永远画不到顶栏那一条带子里 ⇒ 顶栏再半透明也没有东西透出来。
 * 这与 `.ai/ISSUES.md` #67 是**同一个根因**，只是当时只修了验证码页 / 卡包页，密码页漏了。
 * 现在改成与 `TotpCodesScreen` / `CardWalletScreen` 完全一致的写法：让位只走
 * `contentPadding`，列表从 y=0 开始铺。
 *
 * ⚠️ 同步横幅（`SyncNoteBanner`）是列表的**兄弟节点**、不随列表滚动：它出现时会自己
 * 占掉「状态栏 + 横幅高」那一段。此时 `contentPadding` 若再加一份 `barPadding`，
 * 横幅与首条之间就会凭空多出一条 `barPadding` 的空白（旧 bug：下拉刷新区大块空白）。
 * 因此横幅可见时**只留筛选行高**。
 *
 * ⚠️ 搜索态是例外：`Scaffold` 已按 `ScaffoldDefaults.contentWindowInsets` 为搜索顶栏
 * 预留了高度（见调用点的 `contentWindowInsets`），这里必须归零。
 */
private fun itemsTopInset(
    searchActive: Boolean,
    barPadding: Dp,
    filterRowInset: Dp,
    bannerVisible: Boolean,
): Dp = when {
    searchActive -> 0.dp
    bannerVisible -> filterRowInset
    else -> barPadding + filterRowInset
}

/**
 * 顶栏收起度 —— 把两条**与"筛选"耦合**的规则收在这里（主函数才能守住 detekt `LongMethod`）。
 *
 * **规则一：切换快筛 = 换了一份数据视图 ⇒ 列表回到顶部。**
 * 不重置的话，旧滚动位会被新数据集继承（而顶部内边距会把它"藏"起来，肉眼看不出已位移）：
 * 既让顶栏误判收起，也让每次切筛选白多一次重新布局。
 *
 * **规则二：筛选面板展开期间，收起度冻结为「展开」。**
 * 2026-09-15 用户实测：点开面板后连续切筛选（全部→验证码→通行密钥→SSH→**再点回全部**），
 * 标题会从 26sp 缩成 16sp、像"被滑下去了"，而且**那个状态下再点 chip 更卡**。
 * 根因是一圈**互相喂的状态**：收起度 ← 列表滚动位；而 `contentPadding.top` 里含着
 * `filterRowInset`（面板展开多出约 56dp）与 `barPadding`（又由收起度驱动）
 * ⇒ 面板一展开，顶部内边距变大，LazyColumn 把这份位移记进
 * `firstVisibleItemScrollOffset`：**视觉上列表还在顶部，判据 `offset > 8dp` 却已经成立**。
 * 面板展开时顶栏本来就该是展开的（标题刚被点过）⇒ 直接冻结，把回路切断。
 */
@Composable
private fun rememberFilterBarCollapse(
    listState: LazyListState,
    panelExpanded: Boolean,
    filter: ItemsQuickFilter,
): Float {
    LaunchedEffect(filter) { listState.scrollToItem(0) }
    val scrollCollapse = rememberScrollCollapseFraction(listState)
    return if (panelExpanded) 0f else scrollCollapse
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemsScreen(
    onBack: () -> Unit,
    onLocked: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenItem: (VaultItem) -> Unit,
    /**
     * 验证码页出口；**null = 宿主不需要该入口**。
     *
     * 主界面（[io.vaultix.vaultix.ui.shell.MainShellScreen]）底部已有「验证码」页签，
     * 传 null 就不会在 ⋮ 菜单里重复出现；autofill 的条目路由（`ItemsRoute`）没有底部
     * 导航，必须传真值，否则那一侧将**没有任何**通往验证码页的路。
     */
    onOpenTotp: (() -> Unit)? = null,
    /**
     * 主界面 Tab 内嵌模式（main-shell-migration 阶段 2）：
     * 隐藏返回键（无上层可返回）与 FAB（「+」由底部导航条统一承载）。
     */
    embedded: Boolean = false,
    /**
     * 「切换密码库」出口（null = 只有一个库，菜单项隐藏）。
     *
     * issue #96：多库并存时此前**没有**任何语义正确的切库入口 —— 用户能碰到的
     * 只有「锁定」（语义相反），于是「添加了 KDBX 却找不到怎么进去」。
     */
    onSwitchVault: (() -> Unit)? = null,
    /** 外部「+」请求计数：非零即打开新建表单（宿主在消费后清零，避免重复弹出）。 */
    addRequest: Int = 0,
    onAddConsumed: () -> Unit = {},
    /** 底部叠层悬浮栏占用的高度（宿主给；非内嵌时为 0）——列表要留出它，否则末条被压住。 */
    bottomInset: Dp = 0.dp,
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
    val collapse = rememberFilterBarCollapse(listState, quickFiltersExpanded, state.quickFilter)
    val barPadding = rememberImmersiveBarPadding(collapse)
    // ⚠️ 挤在一行是有意的：主 composable 贴着 detekt `LongMethod ≤150` 的门禁线
    // （2026-09-13 这一批加了「让位走 contentPadding」后曾到 152 行，靠压这行回到 147）。
    val filterRowInset = rememberQuickFilterRowInset(quickFiltersExpanded)
    val listTopInset = itemsTopInset(searchActive, barPadding, filterRowInset, state.syncNote != null)
    // 搜索关闭动作（点 × 与系统返回共用）：退出搜索态 + 清空输入。
    val closeSearch: () -> Unit = { searchActive = false; viewModel.setQuery("") }
    SearchBackHandler(enabled = searchActive, onClose = closeSearch)
    // 折叠起来的分组 key（默认全部展开；存 saveable，切 Tab 回来不丢）。
    var collapsedGroups by rememberSaveable { mutableStateOf(emptySet<String>()) }
    // 长按多选（对齐 Bastion：长按条目 → 选择框 + 底部批量操作条）。
    // 用「集合非空」当开关，省掉一个必须与它同步的布尔量。
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    // 多选态优先吃掉返回手势：否则一按返回就整页退出，前面勾的全白勾了。
    BackHandler(enabled = selectedIds.isNotEmpty()) { selectedIds = emptySet() }

    // 分组（纯逻辑在 ItemsGrouping.kt；不分组时只有一项、标题为空 → UI 不画分组头）。
    val groups = rememberGroupedItems(visibleItems, folders, groupMode)

    // 底部导航条「+」→ 打开新建表单（Tab 内嵌时不展示自己的 FAB）
    AddRequestEffect(addRequest, onAddConsumed) { showCreateDialog = true }

    SaveEventSnackbar(viewModel = viewModel, hostState = snackbarHostState)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // 顶栏改为**浮在内容之上**（沉浸式，见 [VaultixExpressiveTopBar]）：
        // Scaffold 不再为顶栏预留高度，状态栏内边距由顶栏自己处理。
        contentWindowInsets =
            if (searchActive) ScaffoldDefaults.contentWindowInsets else WindowInsets(0, 0, 0, 0),
        topBar = {
            // 搜索态保留固定高度顶栏（输入框不能塞进会折叠的大标题里）。
            if (searchActive) {
                ItemsSearchBar(
                    query = state.query,
                    onQueryChange = viewModel::setQuery,
                    onClose = closeSearch,
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
        // 多选态才出现，平时不占一寸屏幕（对齐 Bastion 底部批量操作条的出现时机）。
        bottomBar = {
            if (selectedIds.isNotEmpty()) {
                ItemsSelectionBar(
                    visibleItems = visibleItems,
                    selectedIds = selectedIds,
                    onSelectionChange = { selectedIds = it },
                    onDelete = { victims -> victims.forEach(viewModel::deleteItem) },
                )
            }
        },
    ) { padding ->
        val syncing by viewModel.isSyncing.collectAsStateWithLifecycle()
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 沉浸式状态栏让位**只走列表的 contentPadding**（见 [itemsTopInset]），
                // 这里**不再**放固定 Spacer —— 放了列表就永远滑不到顶栏之下，顶栏再半透明
                // 也透不出内容（用户反馈的「上方不能透明、不够沉浸」）。
                // 同步横幅是列表的**兄弟节点**（不随列表滚动），必须自己让开状态栏高度，
                // 否则会被顶栏半透明地压住；对应地，它可见时列表只留筛选行高，免得叠出空白。
                SyncNoteBanner(
                    note = state.syncNote,
                    topPadding = if (searchActive) 0.dp else barPadding,
                    onDismiss = viewModel::dismissSyncNote,
                    onRetry = viewModel::retrySync,
                )
                // 下拉伸手区（指示器让位与状态都在这里闭环，见 [ItemsPullToRefresh]）。
                ItemsPullToRefresh(
                    syncing = syncing,
                    onRefresh = viewModel::retrySync,
                ) {
                    if (visibleItems.isEmpty()) {
                        // 空态 / 搜不到：同样要让位，否则文案与插画被半透明顶栏压住。
                        ItemsEmptyState(query = state.query, topInset = listTopInset)
                    } else {
                        ItemsList(
                            groups = groups,
                            listState = listState,
                            grouped = groupMode != ItemsGroupMode.None,
                            collapsedGroups = collapsedGroups,
                            displayMode = cardDisplayMode,
                            showIcon = showIcon,
                            serverOrigin = state.vault?.origin,
                            topInset = listTopInset,
                            bottomInset = bottomInset,
                            onToggleGroup = { key -> collapsedGroups = toggleSelection(collapsedGroups, key) },
                            selectedIds = selectedIds,
                            onToggleSelect = { item -> selectedIds = toggleSelection(selectedIds, item.id) },
                            onOpenItem = onOpenItem,
                            syncStates = state.syncStates,
                            onDelete = { item ->
                                // 删完必须把 id 摘掉，否则底栏还会统计一条已不存在的条目。
                                selectedIds = selectedIds - item.id
                                viewModel.deleteItem(item)
                            },
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
                    onSwitchVault = onSwitchVault,
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

/** 宿主「+」请求 → 打开新建表单；消费后由 [onConsumed] 清零，避免重复弹出。 */
@Composable
private fun AddRequestEffect(
    addRequest: Int,
    onConsumed: () -> Unit,
    onShow: () -> Unit,
) {
    LaunchedEffect(addRequest) {
        if (addRequest > 0) {
            onShow()
            onConsumed()
        }
    }
}

/** 空列表的两个面孔：整库为空 → 引导插画；搜索无果 → 「换个词试试」。 */
@Composable
private fun ItemsEmptyState(query: String, topInset: Dp) {
    Box(modifier = Modifier.fillMaxSize().padding(top = topInset)) {
        if (query.isBlank()) EmptyItemsState() else NoSearchResultState()
    }
}

/**
 * 下拉刷新的伸手区（含指示器让位）。
 *
 * ⚠️ 指示器默认贴在**容器顶部**（= 整页最上面），而列表首条要到 [topInset]
 * （状态栏 + 72dp 顶栏）之下才开始横向铺开。于是下拉时「指示器上方 + 指示器到首条之间」
 * 会露出两段纯背景 —— 正是用户反馈的「下拉处有一块很大的空隙」。
 * 把指示器整体下移到顶栏之下：空隙消失，它正好出现在「内容开始的地方」，
 * 与首条视觉上连成一体。
 */
@Composable
private fun ItemsPullToRefresh(
    syncing: Boolean,
    onRefresh: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val pullState = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = syncing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
        state = pullState,
        // ⚠️ 2026-09-13 第二轮用户反馈：「下拉刷新的时候，密码条目左边有一个刷新按钮但是
        // 没显示出来是白色的，可以移除了吧，不好看。」
        // 那枚指示器的底色是 `surfaceContainerHigh`，浅色主题下几乎与页面同色 —— 看起来
        // 就是"一块白"；而且它落在首条卡片左侧，正压着站点图标。
        // 这里**不再画指示器**：下拉手势与同步逻辑原样保留（静默触发），手动同步入口仍在
        // 顶栏 ⋮ 菜单里 —— 不靠一枚看不清的圆点来提示。因此 `topInset` 参数也随之取消。
        indicator = {},
        content = content,
    )
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
    /** null = 宿主没有验证码页可去（主界面底部已有该页签）⇒ 菜单里不出现这一项。 */
    onOpenTotp: (() -> Unit)?,
    onOpenTrash: () -> Unit,
    onRetrySync: () -> Unit,
    onLock: () -> Unit,
    onSwitchVault: (() -> Unit)?,
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
            // 「显示选项」= **看的方式**（分组 / 密度 / 图标），不是对数据的动作。
            // 2026-09-14 用户要求把它从 ⋮ 里挪到胶囊上：⋮ 菜单留给「对库做的动作」
            // （回收站 / 同步 / 锁定），而显示方式是随时会调的视图状态，多一次展开
            // 实在没必要。代价是胶囊从 2 个图标变 3 个 —— 用户明确要的取舍。
            IconButton(onClick = onDisplayOptions) {
                Icon(
                    Icons.Filled.ViewAgenda,
                    contentDescription = stringResource(R.string.items_display_options),
                )
            }
            ItemsMoreMenu(
                onSwitchVault = onSwitchVault,
                onOpenTotp = onOpenTotp,
                onOpenTrash = onOpenTrash,
                onRetrySync = onRetrySync,
                onLock = onLock,
            )
        },
    )
}

/**
 * 顶栏「更多」菜单（⋮）：**切换密码库** / （验证码） / 回收站 / 同步 / 锁定查看层。
 *
 * ⚠️ 菜单项顺序 = 使用频率（2026-09-14 用户指定的重排）：
 * 回收站 → 同步 → 锁定；「锁定」是破坏性动作，用 error 色并单独用分隔线隔开
 * （对齐 M3「破坏性动作不挨着常用动作」的建议）。
 *
 * ⚠️ 「切换密码库」放在**最上面**（2026-09-14，issue #96）：多库并存时这是
 * 「我要换个库看」的**唯一正确语义入口**。此前用户能碰到的只有「锁定」
 * （语义恰好相反），导致 KDBX 库「添加了却找不到」，只能靠摸到设置页。
 *
 * ⚠️ 「验证码」只在**调用方给了入口且底部导航没有该页签**时显示（`onOpenTotp == null`
 * 即整项隐藏）：主界面底部已经有「验证码」页签，菜单里再来一个是重复入口；
 * 而 autofill 的条目路由（`ItemsRoute`）**没有**底部导航，那里必须留着它。
 * 「显示选项」已于同日移出本菜单（见 [ItemsTopBar] 的胶囊）。
 */
@Composable
private fun ItemsMoreMenu(
    onSwitchVault: (() -> Unit)?,
    onOpenTotp: (() -> Unit)?,
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
            // 只有一个库时不必显示（没有可切换的对象），故回调为 null 即整项隐藏。
            if (onSwitchVault != null) {
                MenuAction(Icons.Filled.SwapHoriz, R.string.items_switch_vault) {
                    expanded = false
                    onSwitchVault()
                }
                HorizontalDivider()
            }
            if (onOpenTotp != null) {
                MenuAction(Icons.Filled.QrCode2, R.string.totp_screen_title) {
                    expanded = false
                    onOpenTotp()
                }
            }
            MenuAction(Icons.Filled.Delete, R.string.trash_title) {
                expanded = false
                onOpenTrash()
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
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
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
    serverOrigin: String?,
    topInset: Dp,
    bottomInset: Dp,
    onToggleGroup: (String) -> Unit,
    /** 多选：当前勾选的条目 id 集合（非空即处于多选态）。 */
    selectedIds: Set<String>,
    /** 多选：切换某条的勾选（长按卡片也走它，见 [PressAndSwipeToDelete]）。 */
    onToggleSelect: (VaultItem) -> Unit,
    onOpenItem: (VaultItem) -> Unit,
    onDelete: (VaultItem) -> Unit,
    /** 条目 id → 是否已同步上云（行尾云图标，缺省视为已同步）。 */
    syncStates: Map<String, Boolean> = emptyMap(),
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        // 顶部留白 = 状态栏 + 顶栏高 + 展开中的筛选行，全部走 contentPadding
        // （**不是**外层容器 padding）。这样收起顶栏后内容能滑到半透明顶栏之下 ——
        // 沉浸感的来源；同时筛选行展开时首条条目会自动被推下去，不再被 chip 压住。
        // 底部同理：悬浮胶囊底栏是叠层浮在内容之上的，末条要留出它的高度。
        // 水平 16dp 也由列表统一留白（卡片自身不再带外边距）——
        // 对齐 Bastion `PasswordListScrollableContent` 的 contentPadding 结构。
        contentPadding = PaddingValues(
            start = Spacing.lg,
            top = topInset,
            end = Spacing.lg,
            bottom = Spacing.sm + bottomInset,
        ),
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
                    // 「左滑 → 松手过半 → 二次确认」删除（软删除进回收站，见 ItemsViewModel.deleteItem）。
                    // 长按**选中**由内部 [ItemRow]/[EntryCard] 的 `onLongClick` 独占；本容器
                    // 只负责滑动删除信号（见 [PressAndSwipeToDelete]），不再回调选中，
                    // 避免一次长按触发两次 toggle（进不了多选）。
                    // ⚠️ 2026-09-14：**不再要求先长按进多选**，任意条目直接左滑即可
                    // （原 `enabled = selectedIds.isNotEmpty()` 已去掉，详见组件头注释）。
                    PressAndSwipeToDelete(
                        onDelete = { onDelete(item) },
                    ) {
                        ItemRow(
                            item = item,
                            serverOrigin = serverOrigin,
                            displayMode = displayMode,
                            showIcon = showIcon,
                            isSelectionMode = selectedIds.isNotEmpty(),
                            isSelected = item.id in selectedIds,
                            // 缺省 true：新建刚可见、还没入队的那一瞬间不该闪一下「未同步」。
                            synced = syncStates[item.id] ?: true,
                            onToggleSelect = { onToggleSelect(item) },
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
    topPadding: Dp,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    LaunchedEffect(note) {
        if (note is ItemsViewModel.SyncNote.Success || note is ItemsViewModel.SyncNote.Skipped) {
            kotlinx.coroutines.delay(NOTE_DISMISS_DELAY_MS)
            onDismiss()
        }
    }
    // ⚠️ note 为空时必须**直接 return**（一个节点都不渲染）：调用方不再用 `if` 包着它，
    // 若这里返回一个只剩 padding 的空容器，就会凭空留出一条空气。
    if (note == null) return

    Surface(
        color = when (note) {
            is ItemsViewModel.SyncNote.Warning -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        // padding 放在 Surface **外面**：让横幅整体落在顶栏之下，而不是色块被顶栏压住。
        modifier = Modifier.fillMaxWidth().padding(top = topPadding),
    ) {
        when (note) {
            ItemsViewModel.SyncNote.InProgress -> {
                VaultixWavyProgressBar(modifier = Modifier.fillMaxWidth())
            }
            is ItemsViewModel.SyncNote.Warning -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 6.dp),
                ) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(Spacing.lg),
                    )
                    Text(
                        text = note.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = Spacing.sm),
                    )
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
            }
            is ItemsViewModel.SyncNote.Success -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 6.dp),
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(Spacing.lg),
                    )
                    Text(
                        text = stringResource(R.string.items_sync_done, note.cipherCount),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = Spacing.sm),
                    )
                }
            }
            ItemsViewModel.SyncNote.Skipped -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 6.dp),
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(Spacing.lg),
                    )
                    Text(
                        text = stringResource(R.string.items_sync_skipped),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = Spacing.sm),
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
            modifier = Modifier.padding(top = Spacing.sm),
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
            .clip(RoundedCornerShape(Spacing.sm))
            .clickable(onClick = onToggle)
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
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
    serverOrigin: String?,
    displayMode: ItemsCardDisplayMode,
    showIcon: Boolean,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    /** 该条目是否已同步上云（true = 云端最新，false = 本地待推送）。 */
    synced: Boolean,
    onToggleSelect: () -> Unit,
    onClick: () -> Unit,
) {
    // 卡片外框规格见 [EntryCard]（对齐 Bastion PasswordEntryCard：M3 默认 Card 底色/高度 +
    // 12dp 圆角 + 16dp 内边距 + 标题 SemiBold + 6dp 行距）。
    // 多选态：整行点击 = 勾选（上游 `cardInteractionModifier` 同款分支）。
    // ⚠️ 长按选中由本卡片自己的 `onLongClick` **独占**（见 [EntryCard] 文档）。外层
    // [PressAndSwipeToDelete] 只负责「长按成立后进入拖拽删除」的信号，不再回调选中，
    // 否则一次长按会同时触发两次 toggle（净无操作）—— 正是用户反馈「长按进不了多选」的根因。
    EntryCard(
        onClick = if (isSelectionMode) onToggleSelect else onClick,
        onLongClick = if (isSelectionMode) null else onToggleSelect,
        selected = isSelected,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (showIcon) {
                // 站点图标（`<服务器>/icons/<域名>/icon.png`），取不到回退首字母头像。
                // 此前这里恒为首字母 —— 整库都是字母块，正是用户反馈的「图标是 bug」。
                SiteIcon(item = item, serverOrigin = serverOrigin)
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
                // 副标题（信息密度见 [ItemRowSubtitle]）。
                ItemRowSubtitle(item = item, displayMode = displayMode)
            }
            ItemRowTrailing(
                item = item,
                isSelectionMode = isSelectionMode,
                isSelected = isSelected,
                synced = synced,
                onToggleSelect = onToggleSelect,
            )
        }
    }
}

/**
 * 条目卡片第二行（对齐 Bastion `PasswordCardDisplayMode`）：
 * TitleOnly 不显示；All 显示用户名、没用户名则显示类型徽标；TitleUsername 只显示用户名。
 */
@Composable
private fun ItemRowSubtitle(item: VaultItem, displayMode: ItemsCardDisplayMode) {
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
    } else if (
        displayMode == ItemsCardDisplayMode.TitleUsername &&
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

/**
 * 条目卡片行尾：多选态是勾选框，常态是能力徽标。
 *
 * 对齐 Bastion：选择态 `Checkbox` 顶掉菜单/徽标位；非选择态才展示条目自身的标签。
 */
@Composable
private fun ItemRowTrailing(
    item: VaultItem,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    synced: Boolean,
    onToggleSelect: () -> Unit,
) {
    if (isSelectionMode) {
        Checkbox(checked = isSelected, onCheckedChange = { onToggleSelect() })
        return
    }
    // 能力徽标：一眼看出这条有没有 2FA 验证码、有没有绑通行密钥。
    // 两者可**同时**存在（一条登录条目既带 TOTP 又绑了 passkey），故不是 if/else。
    // 文案复用筛选维度的短词，避免另造一串近义词。
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 云端同步状态（待推送 = 云加斜杠，error 色跳出来）。
        CloudSyncIcon(synced = synced)
        // 收藏星标（对齐 Bitwarden：收藏条目在行尾点一颗星，
        // 不必进详情也能一眼把常用项从整库里挑出来）。
        if (item.favorite) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = stringResource(R.string.item_favorite),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(Spacing.lg),
            )
        }
        if (!item.totp.isNullOrBlank()) {
            CapabilityIcon(
                icon = Icons.Filled.Timer,
                tint = MaterialTheme.colorScheme.primary,
                contentDescription = stringResource(R.string.items_filter_totp),
            )
        }
        if (item.fido2Credentials.isNotEmpty()) {
            CapabilityIcon(
                icon = Icons.Filled.Key,
                tint = MaterialTheme.colorScheme.tertiary,
                contentDescription = stringResource(R.string.items_filter_passkey),
            )
        }
    }
}

/**
 * 密码条目页的底部批量操作条（仅多选态出现）。
 *
 * 抽成独立函数是为了不让主 composable 越过 detekt `LongMethod ≤150` 门禁 ——
 * 「全选 / 清空 / 删除」三组动作在这里闭环，主函数只留一个状态流转的出口。
 */
@Composable
private fun ItemsSelectionBar(
    visibleItems: List<VaultItem>,
    selectedIds: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    onDelete: (List<VaultItem>) -> Unit,
) {
    val allSelected = selectedIds.size >= visibleItems.size
    SelectionActionBar(
        selectedCount = selectedIds.size,
        allSelected = allSelected,
        onToggleSelectAll = {
            onSelectionChange(
                if (allSelected) emptySet() else visibleItems.map { it.id }.toSet(),
            )
        },
        onClear = { onSelectionChange(emptySet()) },
        onDelete = {
            onDelete(visibleItems.filter { it.id in selectedIds })
            onSelectionChange(emptySet())
        },
    )
}
