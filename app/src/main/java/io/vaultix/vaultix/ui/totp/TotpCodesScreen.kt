package io.vaultix.vaultix.ui.totp

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.vaultix.common.OtpType
import io.vaultix.common.TotpConfig
import io.vaultix.common.TotpGenerator
import io.vaultix.model.VaultItem
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.common.CloudSyncIcon
import io.vaultix.vaultix.ui.common.EntryCard
import io.vaultix.vaultix.ui.common.EntryCardIconSpacing
import io.vaultix.vaultix.ui.common.EntryCardTextSpacing
import io.vaultix.vaultix.ui.common.PressAndSwipeToDelete
import io.vaultix.vaultix.ui.common.SelectionActionBar
import io.vaultix.vaultix.ui.common.SiteIconByHost
import io.vaultix.vaultix.ui.common.VaultixExpressiveTopBar
import io.vaultix.vaultix.ui.common.FullScreenDialogShell
import io.vaultix.vaultix.ui.common.VaultixSearchTopAppBar
import io.vaultix.vaultix.ui.common.rememberImmersiveBarPadding
import io.vaultix.vaultix.ui.common.rememberScrollCollapseFraction
import io.vaultix.vaultix.ui.common.toggleSelection
import io.vaultix.vaultix.ui.common.VaultixWavyProgress
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.vaultix.vaultix.ui.theme.Spacing

/**
 * 验证码统一界面（对齐 Bitwarden 的 TOTP 总览 + Bastion 独立验证器视图）。
 *
 * - 实时滚动验证码（逐秒刷新 + 进度条），点按复制当前码；
 * - 搜索发行方/账号；
 * - 条目分「已绑定 / 独立」两种徽标；独立项可「绑定到密码条目」；
 * - 编辑 / 删除；右下角新增独立验证码；
 * - 顶栏「通行密钥」按钮进入通行密钥列表（PasskeysRoute）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TotpCodesScreen(
    onBack: () -> Unit,
    onOpenPasskeys: () -> Unit,
    /** 主界面 Tab 内嵌模式：隐藏返回键与 FAB（「+」由底部导航条统一承载）。 */
    embedded: Boolean = false,
    /** 外部「+」请求计数：非零即打开新建 TOTP 表单。 */
    addRequest: Int = 0,
    onAddConsumed: () -> Unit = {},
    /** 底部叠层悬浮栏占用的高度（宿主给；非内嵌时为 0）——列表要留出它，否则末条被压住。 */
    bottomInset: Dp = 0.dp,
    viewModel: TotpCodesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 提到 Scaffold 外：底部批量操作条（全选 / 已选数）也要用它，
    // 与列表同源才不会出现「底栏说全选了、列表没勾上」。
    // ⚠️ 基于已收集的 `state`（Compose State）派生，而非 `viewModel.filteredEntries()`
    // 直读 `_state.value`——后者不感知快照，Tab 切换时偶发不刷新
    // （用户反馈「密码页新建的含验证码条目，在验证码页搜不到」）。
    val entries = rememberTotpEntries(state)
    val snackbarHostState = remember { SnackbarHostState() }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    // 搜索态自己消费返回手势：主界面是根路由（栈里没有上一层），不拦就会直接退回桌面。
    BackHandler(enabled = searchActive) {
        searchActive = false
        viewModel.setQuery("")
    }
    var editing by remember { mutableStateOf<TotpEntry?>(null) }
    var binding by remember { mutableStateOf<TotpEntry?>(null) }
    var importOpen by remember { mutableStateOf(false) }
    // 长按多选（对齐 Bastion：长按条目 → 选择框 + 底部批量条）。
    // 用「集合非空」当开关，省掉一个必须与它同步的布尔量（两者不一致是最容易出的错）。
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    val selectionMode = selectedIds.isNotEmpty()
    // 多选态优先吃掉返回手势：否则一按返回就整页退出，前面勾的条目全白勾了。
    BackHandler(enabled = selectionMode) { selectedIds = emptySet() }

    // 实时时钟：每秒推进，驱动所有验证码滚动刷新（见 [TotpTickerEffect]）。
    var nowSeconds by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    TotpTickerEffect { nowSeconds = it }

    // 底部导航条「+」→ 打开新建 TOTP 表单（Tab 内嵌时不展示自己的 FAB）
    LaunchedEffect(addRequest) {
        if (addRequest > 0) {
            editing = TotpEntry.empty()
            onAddConsumed()
        }
    }
    // 每次进入组合都刷新一次活跃库：锁屏恢复 / 同步完成后 `activeVaultId` 可能
    // 仍是 null，导致 vaultIdState 为 ""、验证码列表空白（见 ViewModel.refresh 注释）。
    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // 顶栏改为**浮在内容之上**的画法（沉浸式，见 [VaultixExpressiveTopBar]）：
        // Scaffold 不再为顶栏预留空间，状态栏内边距由顶栏自己处理。
        contentWindowInsets = if (searchActive) {
            ScaffoldDefaults.contentWindowInsets
        } else {
            WindowInsets(0, 0, 0, 0)
        },
        topBar = {
            // 搜索态仍需固定高度顶栏（输入框不能塞进会折叠的大标题里）。
            if (searchActive) {
                VaultixSearchTopAppBar(
                    searchTerm = state.query,
                    placeholder = stringResource(R.string.totp_search_hint),
                    onSearchTermChange = viewModel::setQuery,
                    onClose = {
                        searchActive = false
                        viewModel.setQuery("")
                    },
                    clearIconContentDescription = stringResource(R.string.items_search_clear),
                    scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
                )
            }
        },
        floatingActionButton = {
            if (!embedded) {
                FloatingActionButton(onClick = { editing = TotpEntry.empty() }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.totp_add_title))
                }
            }
        },
        // 多选态才出现：平时不占一寸屏幕（对齐 Bastion 底部批量操作条的出现时机）。
        bottomBar = {
            if (selectionMode) {
                TotpSelectionBar(
                    entries = entries,
                    selectedIds = selectedIds,
                    onSelectionChange = { selectedIds = it },
                    onDelete = { victims ->
                        victims.forEach(viewModel::deleteTotp)
                        selectedIds = emptySet()
                    },
                )
            }
        },
    ) { padding ->
        val listState = rememberLazyListState()
        val collapse = rememberScrollCollapseFraction(listState)
        val barPadding = rememberImmersiveBarPadding(collapse)
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            // ⚠️ 搜索态必须归零：此时 Scaffold 已按 `ScaffoldDefaults.contentWindowInsets`
            // 为搜索顶栏预留了高度，这里再叠一次就是「双重留白」= 纯黑大横幅。
            //
            // ⚠️ 让位**必须做在滚动内容里**（`contentPadding` / 列表首项），不能做在滚动容器外
            // 的 `Column.padding(top)`：做在外面，整页（含进度条）被永久下压，内容永远画不到
            // 顶栏区域 ⇒「收起后顶栏透明、内容从下方穿过」不成立 = 用户反馈的「验证码页不沉浸」。
            // 这与密码页 [ItemsList] 的写法是同一条约束（`.ai/ISSUES.md` #67）。
            val topInset = if (searchActive) 0.dp else barPadding + Spacing.sm
            TotpBody(
                state = state,
                entries = entries,
                listState = listState,
                nowSeconds = nowSeconds,
                collapse = collapse,
                selectionMode = selectionMode,
                selectedIds = selectedIds,
                topInset = topInset,
                bottomInset = bottomInset,
                onOpenPasskeys = onOpenPasskeys,
                onToggleSelect = { id -> selectedIds = toggleSelection(selectedIds, id) },
                onEdit = { entry -> editing = entry },
                onDeleteEntry = { entry ->
                    // 删完必须把 id 从选中集合里摘掉，否则底栏还会统计一条已不存在的条目。
                    selectedIds = selectedIds - entry.itemId
                    viewModel.deleteTotp(entry)
                },
                onBind = { entry -> if (!entry.bound) binding = entry },
                onCopy = viewModel::copyCode,
            )
            if (!searchActive) {
                TotpOverlayTopBar(
                    collapseFraction = collapse,
                    embedded = embedded,
                    onBack = onBack,
                    onSearch = { searchActive = true },
                    onImport = { importOpen = true },
                )
            }
        }
    }

    TotpDialogs(
        editing = editing,
        binding = binding,
        importOpen = importOpen,
        viewModel = viewModel,
        snackbarHostState = snackbarHostState,
        onEditingChange = { editing = it },
        onBindingChange = { binding = it },
        onImportOpenChange = { importOpen = it },
    )
}

/**
 * 由已收集的 [TotpCodesViewModel.UiState] 派生「当前要展示的验证码条目」。
 *
 * 必须吃 `state.items` / `state.query` 两个 Compose State（而非 `viewModel.filteredEntries()`
 * 直读 `_state.value`）——后者不感知快照，Tab 切换 / `SaveableStateProvider` 恢复时偶发不刷新
 * （用户反馈「密码页新建的含验证码条目，在验证码页搜不到」）。`remember` 把派生结果绑定到这两个
 * 输入：任一变化即重算，且不会因选区等无关状态变化而白算。抽成 helper 也是为了给主 composable
 * 瘦身、守 detekt `LongMethod ≤150`。
 */
@Composable
private fun rememberTotpEntries(state: TotpCodesViewModel.UiState): List<TotpEntry> =
    remember(state.items, state.query) {
        state.items.toTotpEntries().filter { it.matches(state.query) }
    }

/**
 * 每秒推进一次的时钟（驱动所有验证码滚动刷新）。
 *
 * 抽出来是为了把「重读一次时间」这件事从主 composable 里挪走 —— 主函数要守
 * detekt `LongMethod ≤150`。
 */
@Composable
private fun TotpTickerEffect(onTick: (Long) -> Unit) {
    LaunchedEffect(Unit) {
        while (true) {
            delay(TOTP_TICK_MS)
            onTick(System.currentTimeMillis() / MILLIS_PER_SECOND)
        }
    }
}

/**
 * 空态正文（整页为空 / 筛选后为空两个面孔）。
 *
 * 两个分支的差别只在 [title] 传不传，但重复两遍 `Column + padding(topInset)` 会
 * 让主 composable 越 detekt `LongMethod` 门禁；且「空态要让开顶栏高度」这条约束
 * 本就该只写一次。
 */
/**
 * 加载中占位（冷启动 / 解锁后条目流尚未发首帧）。
 *
 * ⚠️ 它存在的**唯一**理由：不要把"还没加载完"显示成"还没有验证码"（见 `UiState.loading`）。
 * 刻意只放一个转圈、不做骨架屏 —— 这一屏通常只有几百毫秒，骨架屏反而容易被读成"内容错位"。
 */
/**
 * 验证码页主体：**四个空态 + 列表**的完整分流（从 [TotpCodesScreen] 抽出）。
 *
 * 四个 `when` 分支的顺序**有语义**，不能重排：
 * 1. `loading` —— 流还没发首帧（冷启动 1~2 秒的假空态）；
 * 2. `unlocked == false` —— 库锁着（条目都在，只是读不出来）；
 * 3. `items.isEmpty()` —— 真的没有验证码；
 * 4. `entries.isEmpty()` —— 有验证码但被搜索词筛没了。
 *
 * 抽出来的直接原因：内联这几处会把 [TotpCodesScreen] 顶破 detekt 的
 * LongMethod（实测 153 > 150）与 CyclomaticComplexMethod（15 > 14）。
 */
@Composable
private fun TotpBody(
    state: TotpCodesViewModel.UiState,
    entries: List<TotpEntry>,
    listState: LazyListState,
    nowSeconds: Long,
    collapse: Float,
    selectionMode: Boolean,
    selectedIds: Set<String>,
    topInset: Dp,
    bottomInset: Dp,
    onOpenPasskeys: () -> Unit,
    onToggleSelect: (String) -> Unit,
    onEdit: (TotpEntry) -> Unit,
    onDeleteEntry: (TotpEntry) -> Unit,
    onBind: (TotpEntry) -> Unit,
    onCopy: (String) -> Unit,
) {
    when {
        // ⚠️ **必须排在空态前面**：冷启动 / 解锁后条目流还没发首帧时，
        // `state.items` 同样是空的 —— 若直接落进 `TotpEmptyBody`，用户看到的就是
        // 「还没有验证码」这个**假状态**（实测 1~2 秒才变真）。
        // 这就是用户报的「冷启动瞬间点验证码页有 1~2 秒空白」。
        state.loading -> TotpLoadingBody()

        // ⚠️ 与密码页同一类坑（2026-09-15 用户报的「切到 KDBX 后验证码页也全白」）：
        // 活跃库锁定时条目流必然是空的，但**不能说「还没有验证码」**——
        // 那同样是假状态。锁定时要如实说"锁着"。
        state.unlocked == false -> TotpEmptyBody(
            topInset = topInset,
            title = stringResource(R.string.items_locked_title),
            message = stringResource(R.string.items_locked_body),
        )

        state.items.isEmpty() -> TotpEmptyBody(
            topInset = topInset,
            title = stringResource(R.string.totp_empty_title),
            message = stringResource(R.string.totp_empty_body),
        )

        entries.isEmpty() -> TotpEmptyBody(
            topInset = topInset,
            message = stringResource(R.string.totp_empty_body),
        )

        else -> LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // 与密码列表同一套留白结构（卡片不再自带外边距，见 [EntryCard]）。
            // 顶部 = 状态栏 + 顶栏高（随收起动画变短）；
            // 底部留出叠层悬浮底栏的高度，否则最后一条被胶囊压住。
            contentPadding = PaddingValues(
                start = Spacing.lg,
                top = topInset,
                end = Spacing.lg,
                bottom = Spacing.sm + bottomInset,
            ),
            verticalArrangement = Arrangement.spacedBy(TOTP_CARD_GAP),
        ) {
            // 整页一条倒计时进度条，「通行密钥」入口挂在它右侧。
            // 作为**列表首项**：随滚动一起滑走，于是收起后内容能穿过透明顶栏 ——
            // 沉浸感与进度条收起是同一个动作（见 [TotpPageProgress] 的折叠包装）。
            item(key = "page_progress") {
                TotpPageProgress(
                    entries = entries,
                    nowSeconds = nowSeconds,
                    collapseFraction = collapse,
                    onOpenPasskeys = onOpenPasskeys,
                )
            }
            items(entries, key = { it.itemId }) { entry ->
                TotpRow(
                    entry = entry,
                    nowSeconds = nowSeconds,
                    serverOrigin = state.serverOrigin,
                    actions = TotpRowActions(
                        isSelectionMode = selectionMode,
                        isSelected = entry.itemId in selectedIds,
                        // 缺省 true：新建刚可见、还没入队的那一瞬间不该闪一下「未同步」。
                        synced = state.syncStates[entry.itemId] ?: true,
                        onToggleSelect = { onToggleSelect(entry.itemId) },
                        onEdit = { onEdit(entry) },
                        onDelete = { onDeleteEntry(entry) },
                        onBind = { onBind(entry) },
                        onCopy = onCopy,
                    ),
                )
            }
        }
    }
}

@Composable
private fun TotpLoadingBody() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        VaultixWavyProgress()
    }
}

@Composable
private fun TotpEmptyBody(topInset: Dp, message: String, title: String? = null) {
    Column(modifier = Modifier.fillMaxSize().padding(top = topInset)) {
        if (title == null) {
            EmptyTotpState(message = message)
        } else {
            EmptyTotpState(title = title, message = message)
        }
    }
}

/**
 * 多选态的底部批量操作条（仅此时出现）。
 *
 * 抽出来是为了把 [TotpCodesScreen] 压回 detekt `LongMethod ≤150` 门禁内 ——
 * 「全选 / 清空 / 删除」三组动作在这里闭环，主函数只留一个状态出口。
 */
@Composable
private fun TotpSelectionBar(
    entries: List<TotpEntry>,
    selectedIds: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    onDelete: (List<TotpEntry>) -> Unit,
) {
    val allSelected = selectedIds.size >= entries.size
    SelectionActionBar(
        selectedCount = selectedIds.size,
        allSelected = allSelected,
        onToggleSelectAll = {
            onSelectionChange(if (allSelected) emptySet() else entries.map { it.itemId }.toSet())
        },
        onClear = { onSelectionChange(emptySet()) },
        onDelete = { onDelete(entries.filter { it.itemId in selectedIds }) },
    )
}

/**
 * 浮在内容之上的大标题顶栏（沉浸式，见 [VaultixExpressiveTopBar]）。
 *
 * 与 [TotpSelectionBar] 同理：主 composable 的行数与圈复杂度都要留给门禁，
 * 顶栏这种自成一体的区块抽出去最省事，也不损失可读性。
 */
@Composable
private fun BoxScope.TotpOverlayTopBar(
    collapseFraction: Float,
    embedded: Boolean,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onImport: () -> Unit,
) {
    VaultixExpressiveTopBar(
        title = stringResource(R.string.totp_screen_title),
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
            IconButton(onClick = onSearch) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = stringResource(R.string.totp_search_hint),
                )
            }
            IconButton(onClick = onImport) {
                Icon(
                    Icons.Filled.FileDownload,
                    contentDescription = stringResource(R.string.totp_import_button),
                )
            }
        },
    )
}

/**
 * 三个对话框（编辑 / 绑定密码条目 / 导入）。
 *
 * 抽出来是为让 [TotpCodesScreen] 守住 detekt 门禁（2026-09-12：主函数曾达 151 行 /
 * 圈复杂度 15，均超阈值 —— 上一轮加 `embedded` / `addRequest` 参数时被推过线）。
 * 对话框本身是独立交互单元，与页面骨架无耦合，拆出后主函数只剩「骨架 + 状态分发」。
 */
@Composable
private fun TotpDialogs(
    editing: TotpEntry?,
    binding: TotpEntry?,
    importOpen: Boolean,
    viewModel: TotpCodesViewModel,
    snackbarHostState: SnackbarHostState,
    onEditingChange: (TotpEntry?) -> Unit,
    onBindingChange: (TotpEntry?) -> Unit,
    onImportOpenChange: (Boolean) -> Unit,
) {
    editing?.let { entry ->
        TotpEditDialog(
            entry = entry,
            onDismiss = { onEditingChange(null) },
            onSave = { issuer, account, config ->
                viewModel.saveTotp(
                    entryId = entry.itemId.takeIf { it.isNotEmpty() && entry.totpRaw.isNotEmpty() },
                    issuer = issuer,
                    account = account,
                    config = config,
                )
                onEditingChange(null)
            },
            onDelete = if (entry.totpRaw.isNotEmpty()) {
                {
                    viewModel.deleteTotp(entry)
                    onEditingChange(null)
                }
            } else {
                null
            },
        )
    }

    binding?.let { entry ->
        LoginPickerDialog(
            candidates = viewModel.loginCandidates(entry.itemId),
            onDismiss = { onBindingChange(null) },
            onPick = { login ->
                viewModel.bindStandaloneToLogin(entry, login.id)
                onBindingChange(null)
            },
        )
    }

    if (importOpen) {
        ImportDialogWithOutcome(
            viewModel = viewModel,
            snackbarHostState = snackbarHostState,
            onSingle = { onEditingChange(it) },
            onDismiss = { onImportOpenChange(false) },
        )
    }
}

@Composable
private fun EmptyTotpState(title: String? = null, message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (title != null) {
            Text(text = title, style = MaterialTheme.typography.titleLarge)
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.sm, start = Spacing.xl, end = Spacing.xl),
        )
    }
}

/**
 * 单个验证码行：实时码 + 整页进度条 + 长按多选。
 *
 * ## 按钮精简（2026-09-13，对齐 Bastion `TotpCodeCard`）
 * 此前卡片底部挂着「复制 / 编辑 / 移除 / 绑定」四个按钮，其中：
 * - **复制图标**多余 —— 整行点击就是复制（上游同款），再画一个图标是同一个动作的第二个入口；
 * - **编辑 / 移除 / 绑定**属于低频动作，直接铺在卡片上会把「一眼要读的验证码」挤下去。
 * 上游把它们收进右上角 `MoreVert` 下拉菜单，本行照此办理；选择态下同一个位置换成
 * `Checkbox`（点击整行即勾选）。
 *
 * @param isSelectionMode 是否处于多选态（决定点击语义与右上角控件）。
 * @param isSelected 本行是否被勾选。
 * @param onToggleSelect 切换本行勾选（长按 = 勾选本行并进入多选态）。
 */
/**
 * 单条验证码行的交互与状态打包（见 [TotpRow] 的参数说明）。
 *
 * 只是参数聚合，不含行为 —— 定义为 `private data class` 而非 interface，
 * 是为了让它能继续留在同一个文件里、不改动任何可见性。
 */
private data class TotpRowActions(
    val isSelectionMode: Boolean,
    val isSelected: Boolean,
    val synced: Boolean,
    val onToggleSelect: () -> Unit,
    val onDelete: () -> Unit,
    val onEdit: () -> Unit,
    val onBind: () -> Unit,
    val onCopy: (String) -> Unit,
)

@Composable
private fun TotpRow(
    entry: TotpEntry,
    nowSeconds: Long,
    serverOrigin: String?,
    /**
     * 本行的交互与状态（勾选态 / 云同步 / 四个回调）。
     *
     * 打成一个包传入是为了守住 detekt `LongParameterList ≤8` —— 逐项摊开是 12 个参数。
     * 这些量本就是「这一行怎么表现、被点了做什么」的一个整体，收拢语义上也更顺。
     */
    actions: TotpRowActions,
) {
    val isSelectionMode = actions.isSelectionMode
    val isSelected = actions.isSelected
    val synced = actions.synced
    val onToggleSelect = actions.onToggleSelect
    val onDelete = actions.onDelete
    val onEdit = actions.onEdit
    val onBind = actions.onBind
    val onCopy = actions.onCopy
    val code = TotpGenerator.generate(entry.toConfig(), nowSeconds)
    val isHotp = entry.type == OtpType.HOTP
    // HOTP 没有时间衰减，不做过期警示。
    val remaining = TotpGenerator.remainingSeconds(entry.period, nowSeconds)
    // 点一下即复制（对齐 Bastion 验证器页：整行可点 → 复制）。
    // ⚠️ 2026-09-13 用户反馈「点击复制大家都知道的操作，不需要提示」—— 复制后的
    // `SnackbarHost` 提示已删除。它除了啰嗦，还会在悬浮胶囊底栏上方压出一块自带
    // surface 底板的深色方块（用户看到的「底栏外一圈黑色」），观感很脏。
    // 页面级 SnackbarHost **保留**：批量导入的结果提示仍然要用它。
    val copyNow: () -> Unit = { onCopy(code) }

    // 卡片外框与密码 / 卡包列表完全一致（见 [EntryCard]）；内边距由卡片统一给 16dp。
    // 「左滑 → 松手过半 → 二次确认」包在外层：长按**选中**由 [EntryCard] 的 `onLongClick`
    // 独占，本容器只负责滑动删除信号（见 [PressAndSwipeToDelete]）。
    // ⚠️ 2026-09-14：**不再要求先长按进多选**，任意条目直接左滑即可
    // （原 `enabled = isSelectionMode` 已去掉，与密码页保持一致）。
    PressAndSwipeToDelete(
        onDelete = onDelete,
    ) {
        EntryCard(
            // 多选态下点击 = 勾选（上游 `cardInteractionModifier` 同款分支）。
            // ⚠️ 长按选中由 [EntryCard] 自己的 `onLongClick` 独占；外层
            // [PressAndSwipeToDelete] 只负责「长按成立后进入拖拽删除」的信号，
            // 不再回调选中，避免一次长按触发两次 toggle（净无操作）导致无法进入多选。
            onClick = if (isSelectionMode) onToggleSelect else copyNow,
            onLongClick = if (isSelectionMode) null else onToggleSelect,
            selected = isSelected,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // 站点图标（库内服务器地址 + 条目域名），取不到回退首字母 ——
                // 与密码列表同一套观感（见 [SiteIconByHost]）。
                SiteIconByHost(
                    domain = entry.domain,
                    fallbackText = entry.title,
                    serverOrigin = serverOrigin,
                )
                Spacer(Modifier.width(EntryCardIconSpacing))
                Text(
                    text = entry.title.ifBlank { stringResource(R.string.totp_screen_title) },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Badge(entry.bound)
                // 云端同步状态（待推送 = 云加斜杠，error 色跳出来）。见 `.ai/ISSUES.md` #76。
                if (!synced) CloudSyncIcon(synced = false)
                if (isSelectionMode) {
                    Checkbox(checked = isSelected, onCheckedChange = { onToggleSelect() })
                } else {
                    TotpRowMenu(
                        onEdit = onEdit,
                        onDelete = onDelete,
                        onBind = if (entry.bound) null else onBind,
                    )
                }
            }
            if (entry.account.isNotBlank()) {
                Text(
                    text = entry.account,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = EntryCardTextSpacing),
                )
            }
            Spacer(Modifier.height(Spacing.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SelectionContainer(modifier = Modifier.weight(1f)) {
                    Text(
                        text = groupCode(code),
                        // 对齐 Bastion `TotpCodeCard`（40sp / 普通模式 32–36sp）：
                        // 验证码是「一眼读出来照着敲」的数字，24sp 的 `headlineSmall` 在小屏上
                        // 得凑近看；**等宽**保证每秒刷新时数字宽度不抖，分组空格（[groupCode]）
                        // 比 letterSpacing 更利于口头念读。
                        fontSize = TOTP_CODE_FONT_SP,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold,
                        // 剩余 ≤5 秒转警示色：不必盯着顶部进度条也知道「快过期了，先别念」。
                        color = if (isHotp || remaining > TOTP_HOT_WARNING_SECONDS) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
                if (isHotp) {
                    // HOTP 基于计数器，无时间衰减：展示当前 counter 而非倒计时
                    Text(
                        text = stringResource(R.string.totp_hotp_counter, entry.counter),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // 倒计时不再逐行画进度条：整页共用顶部的统一进度条（见 [UnifiedTotpProgressBar]），
            // 既统一观感，也省掉每行每秒一次的绘制/动画开销（用户要求「降低功耗」）。
        }
    }
}

/**
 * 验证码行右上角菜单（对齐 Bastion：低频操作收进 `MoreVert`，不铺在卡片上）。
 *
 * @param onBind 「绑定到密码条目」；已绑定时传 `null`（菜单里不出现该行）。
 */
@Composable
private fun TotpRowMenu(
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onBind: (() -> Unit)?,
) {
    // 图标**贴右**：用户反馈「三个点太靠中间，需要往右边移一点」。
    // `IconButton` 默认 48dp 触控区、图标居中 ⇒ 图标右缘到卡片内缘还留着 12dp，
    // 叠上卡片 16dp 内边距 ≈ 28dp 的视觉空隙，读起来就像「缩在中间」。
    // 触控区压到 36dp、图标缩到 20dp ⇒ 视觉空隙收到 ~16dp；行内 36dp 仍够点。
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.size(TOTP_MENU_BUTTON_SIZE),
        ) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.content_desc_more_options),
                modifier = Modifier.size(TOTP_MENU_ICON_SIZE),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (onBind != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.totp_action_bind)) },
                    onClick = {
                        expanded = false
                        onBind()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.totp_action_edit)) },
                onClick = {
                    expanded = false
                    onEdit()
                },
                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.totp_remove_action)) },
                onClick = {
                    expanded = false
                    onDelete()
                },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
            )
        }
    }
}

@Composable
private fun Badge(bound: Boolean) {
    val text = if (bound) R.string.totp_badge_bound else R.string.totp_badge_standalone
    val color = if (bound) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(6.dp)) {
        Text(
            text = stringResource(text),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 2.dp),
        )
    }
}

/**
 * 整页统一倒计时进度条 + 「通行密钥」入口，**随列表滚动收起**。
 *
 * 抽成独立 composable 的原因：主函数已贴着 detekt `LongMethod ≤150` 的门禁线，
 * 而这段逻辑（挑「最近过期」的条目决定周期、空列表时用占位条）与主流程无耦合。
 *
 * 「通行密钥」入口挂在进度条右侧，**空列表时也必须渲染**
 * （[UnifiedTotpProgressPlaceholder]）—— 否则用户在一条验证码都没有时，
 * 会彻底失去进入通行密钥页的路径（对齐 Bastion）。
 *
 * ## 滚动收起（2026-09-13）
 *
 * 对齐 Bastion `TotpListContent.kt`：列表一往下滚，整条进度条**高度 44dp → 0 + 淡出**。
 * 用户反馈「从上往下滑动的时候，倒计时条不变小」—— 此前本行是**静态**占位的，
 * 不随滚动变化，既挡视物也和顶栏的收起动作对不齐。
 *
 * ⚠️ 收起判据复用顶栏那个 [rememberScrollCollapseFraction]（同一条 [listState]），
 * **不要另起一套阈值** —— 否则进度条与顶栏会在不同时刻收起，看起来像两个动画打架。
 *
 * @param collapseFraction 0 = 完全展开，1 = 完全收起（与顶栏同源）。
 */
@Composable
private fun TotpPageProgress(
    entries: List<TotpEntry>,
    nowSeconds: Long,
    collapseFraction: Float,
    onOpenPasskeys: () -> Unit,
) {
    val passkeyEntry: @Composable () -> Unit = {
        IconButton(onClick = onOpenPasskeys) {
            Icon(
                imageVector = Icons.Filled.Fingerprint,
                contentDescription = stringResource(R.string.totp_passkey_button),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
    val soonest = entries.minByOrNull { TotpGenerator.remainingSeconds(it.period, nowSeconds) }
    // 高度随收起动画收缩到 0；clipToBounds 防止内容在压扁过程中溢出到下一行。
    val barHeight by animateDpAsState(
        targetValue = lerp(TOTP_PROGRESS_BAR_HEIGHT, 0.dp, collapseFraction),
        animationSpec = tween(durationMillis = TOTP_PROGRESS_COLLAPSE_MS),
        label = "totp_progress_bar_height",
    )
    Box(
        modifier = Modifier
            .height(barHeight)
            .clipToBounds()
            .graphicsLayer { alpha = 1f - collapseFraction },
    ) {
        if (soonest == null) {
            UnifiedTotpProgressPlaceholder(trailingContent = passkeyEntry)
        } else {
            UnifiedTotpProgressBar(
                periodSeconds = soonest.period,
                nowSeconds = nowSeconds,
                trailingContent = passkeyEntry,
            )
        }
    }
}

/** 每 3 位分组显示，便于人工录入（123456 → 123 456）。 */
private fun groupCode(code: String): String {
    if (code.length <= TOTP_CODE_GROUP) {
        return code
    }
    return buildString {
        code.forEachIndexed { index, c ->
            if (index > 0 && index % TOTP_CODE_GROUP == 0) append(' ')
            append(c)
        }
    }
}

private const val TOTP_TICK_MS = 1000L
private const val TOTP_CODE_GROUP = 3
private const val MILLIS_PER_SECOND = 1000

/**
 * 统一倒计时进度条**展开**时的高度（对齐 Bastion `lerp(44.dp, 0.dp, ...)` 的起点）。
 * 收起时压到 0，与顶栏大标题的收起同步，滚动时不再有「一条横杠赖在屏幕上」的割裂感。
 */
private val TOTP_PROGRESS_BAR_HEIGHT = 44.dp

/** 进度条随滚动收起 / 展开的动画时长（对齐 Bastion `tween(200)`）。 */
private const val TOTP_PROGRESS_COLLAPSE_MS = 200

/**
 * 验证码字号（对齐 Bastion `TotpCodeCard`：统一进度条模式 40sp / 普通 32–36sp）。
 * 取 36sp：小屏一行放得下 6 位分组码 + 复制按钮，又明显大于正文。
 */
private val TOTP_CODE_FONT_SP = 36.sp

/** 剩余秒数 ≤ 它时验证码转 `error` 警示色（对齐 Bastion 的 5 秒阈值）。 */
private const val TOTP_HOT_WARNING_SECONDS = 5

/** 行尾 `MoreVert` 的触控区尺寸（默认 48dp 会把图标推得离右缘太远）。 */
private val TOTP_MENU_BUTTON_SIZE = 36.dp

/** 行尾 `MoreVert` 的图标尺寸（随之收紧，视觉重心贴右）。 */
private val TOTP_MENU_ICON_SIZE = 20.dp

// 类型固定参数（对齐 Bastion TotpData 的固定口径）
private const val MOTP_FIXED_PERIOD = 10
private const val MOTP_FIXED_DIGITS = 6
private const val DEFAULT_EDIT_PERIOD = 30
private const val DEFAULT_EDIT_DIGITS = 6

// ===== 编辑 / 新增对话框（类型对齐 Bastion：TOTP/HOTP/Steam/Yandex/mOTP）=====

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TotpEditDialog(
    entry: TotpEntry,
    onDismiss: () -> Unit,
    onSave: (issuer: String, account: String, config: TotpConfig) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var issuer by remember { mutableStateOf(entry.issuer) }
    var account by remember { mutableStateOf(entry.account) }
    var secret by remember { mutableStateOf(entry.secret) }
    var type by remember { mutableStateOf(entry.type) }
    var period by remember { mutableStateOf(entry.period.toString()) }
    var digits by remember { mutableStateOf(entry.digits.toString()) }
    var algorithm by remember { mutableStateOf(entry.algorithm) }
    var counter by remember { mutableStateOf(entry.counter.toString()) }
    var pin by remember { mutableStateOf(entry.pin) }
    var showError by remember { mutableStateOf(false) }

    // ⚠️ 2026-09-13 第二轮用户反馈：「编辑条目，验证码条目不是全屏显示，看起来不舒服。」
    // ⇒ 与条目编辑一致，改成整页（见 [FullScreenDialogShell]）：
    // 顶部「添加/编辑验证码」标题行 + 可滚动字段区 + 底部固定的「移除 / 取消 / 保存」。
    FullScreenDialogShell(
        title = stringResource(
            if (entry.totpRaw.isEmpty()) R.string.totp_add_title else R.string.totp_edit_title,
        ),
        onDismiss = onDismiss,
        confirmEnabled = true,
        confirmLabel = stringResource(R.string.action_save),
        destructive = if (onDelete == null) {
            null
        } else {
            {
                TextButton(onClick = onDelete) {
                    Text(
                        stringResource(R.string.totp_remove_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        onConfirm = {
            if (secret.isBlank()) {
                showError = true
            } else {
                onSave(
                    issuer,
                    account,
                    buildTotpConfig(type, secret, period, digits, algorithm, counter, pin),
                )
            }
        },
    ) {
        TypeDropdown(type) { type = it }
        Spacer(Modifier.height(Spacing.sm))
        OutlinedTextField(
            value = issuer,
            onValueChange = { issuer = it },
            label = { Text(stringResource(R.string.totp_field_issuer)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.sm))
        OutlinedTextField(
            value = account,
            onValueChange = { account = it },
            label = { Text(stringResource(R.string.totp_field_account)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.sm))
        OutlinedTextField(
            value = secret,
            onValueChange = { secret = it },
            label = { Text(secretLabel(type)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        when (type) {
            OtpType.MOTP -> TotpMotpFields(pin, onPinChange = { pin = it })
            OtpType.STEAM -> {
                // Steam 固定 5 位 / 30s / SHA1，无可调参数
            }
            OtpType.HOTP -> TotpHotpFields(
                counter = counter,
                onCounterChange = { counter = it },
                digits = digits,
                onDigitsChange = { digits = it },
                algorithm = algorithm,
                onAlgorithmChange = { algorithm = it },
            )
            OtpType.TOTP, OtpType.YANDEX -> TotpTimedFields(
                period = period,
                onPeriodChange = { period = it },
                digits = digits,
                onDigitsChange = { digits = it },
                algorithm = algorithm,
                onAlgorithmChange = { algorithm = it },
            )
        }
        if (showError) {
            Text(
                stringResource(R.string.totp_invalid_secret),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** mOTP 专属字段:PIN 码(密钥为原始字符串,固定 10s / 6 位)。 */
@Composable
private fun TotpMotpFields(pin: String, onPinChange: (String) -> Unit) {
    Spacer(Modifier.height(Spacing.sm))
    OutlinedTextField(
        value = pin,
        onValueChange = onPinChange,
        label = { Text(stringResource(R.string.totp_field_pin)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        text = stringResource(R.string.totp_motp_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.xs),
    )
}

/** HOTP 专属字段:计数器 + 位数 + 算法(步长由 counter 代替,无刷新周期)。 */
@Composable
private fun TotpHotpFields(
    counter: String,
    onCounterChange: (String) -> Unit,
    digits: String,
    onDigitsChange: (String) -> Unit,
    algorithm: String,
    onAlgorithmChange: (String) -> Unit,
) {
    Spacer(Modifier.height(Spacing.sm))
    Row {
        OutlinedTextField(
            value = counter,
            onValueChange = onCounterChange,
            label = { Text(stringResource(R.string.totp_field_counter)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(Spacing.sm))
        OutlinedTextField(
            value = digits,
            onValueChange = onDigitsChange,
            label = { Text(stringResource(R.string.totp_field_digits)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(Spacing.sm))
    AlgorithmDropdown(algorithm, onSelected = onAlgorithmChange)
}

/** TOTP / Yandex 通用字段:刷新周期 + 位数 + 算法。 */
@Composable
private fun TotpTimedFields(
    period: String,
    onPeriodChange: (String) -> Unit,
    digits: String,
    onDigitsChange: (String) -> Unit,
    algorithm: String,
    onAlgorithmChange: (String) -> Unit,
) {
    Spacer(Modifier.height(Spacing.sm))
    Row {
        OutlinedTextField(
            value = period,
            onValueChange = onPeriodChange,
            label = { Text(stringResource(R.string.totp_field_period)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(Spacing.sm))
        OutlinedTextField(
            value = digits,
            onValueChange = onDigitsChange,
            label = { Text(stringResource(R.string.totp_field_digits)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(Spacing.sm))
    AlgorithmDropdown(algorithm, onSelected = onAlgorithmChange)
}

/** 按类型归一化参数并构造 [TotpConfig](mOTP/Steam 的固定口径在此收敛)。 */
private fun buildTotpConfig(
    type: OtpType,
    secret: String,
    period: String,
    digits: String,
    algorithm: String,
    counter: String,
    pin: String,
): TotpConfig {
    val p = period.toIntOrNull()?.coerceAtLeast(1) ?: DEFAULT_EDIT_PERIOD
    val d = digits.toIntOrNull()?.coerceIn(1, 10) ?: DEFAULT_EDIT_DIGITS
    return TotpConfig(
        secret = secret.trim(),
        period = if (type == OtpType.MOTP) MOTP_FIXED_PERIOD else p,
        digits = if (type == OtpType.MOTP) MOTP_FIXED_DIGITS else d,
        algorithm = if (type == OtpType.STEAM || type == OtpType.MOTP) "SHA1" else algorithm,
        type = type,
        counter = counter.toLongOrNull() ?: 0L,
        pin = pin.trim(),
    )
}

@Composable
private fun secretLabel(type: OtpType): String = stringResource(
    when (type) {
        OtpType.MOTP -> R.string.totp_field_motp_secret
        else -> R.string.totp_field_secret
    },
)

/** 导入对话框 + 结果处理：单条预填编辑、批量提示计数、失败提示原因。 */
@Composable
private fun ImportDialogWithOutcome(
    viewModel: TotpCodesViewModel,
    snackbarHostState: SnackbarHostState,
    onSingle: (TotpEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // 事件回调（非 composition）里无法调 stringResource，提前解析固定文案
    val invalidMessage = stringResource(R.string.totp_import_invalid)
    val unsupportedMessage = stringResource(R.string.totp_import_unsupported)
    ImportDialog(
        onDismiss = onDismiss,
        onImport = { raw ->
            when (val outcome = viewModel.importTotp(raw)) {
                is ImportOutcome.Single -> onSingle(outcome.entry)
                is ImportOutcome.Multiple -> scope.launch {
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.totp_import_imported, outcome.count),
                    )
                }
                ImportOutcome.Unsupported -> scope.launch {
                    snackbarHostState.showSnackbar(unsupportedMessage)
                }
                ImportOutcome.Invalid -> scope.launch {
                    snackbarHostState.showSnackbar(invalidMessage)
                }
            }
            onDismiss()
        },
    )
}

/** 粘贴导入对话框：otpauth / motp / otpauth-migration / 裸密钥。 */
@Composable
private fun ImportDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.totp_import_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.totp_import_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.sm))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.totp_import_label)) },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onImport(text) },
                enabled = text.isNotBlank(),
            ) { Text(stringResource(R.string.totp_import_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypeDropdown(value: OtpType, onSelected: (OtpType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = typeLabel(value),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.totp_field_type)) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            OtpType.entries.forEach { candidate ->
                DropdownMenuItem(
                    text = { Text(typeLabel(candidate)) },
                    onClick = { onSelected(candidate); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun typeLabel(type: OtpType): String = stringResource(
    when (type) {
        OtpType.TOTP -> R.string.totp_type_totp
        OtpType.HOTP -> R.string.totp_type_hotp
        OtpType.STEAM -> R.string.totp_type_steam
        OtpType.YANDEX -> R.string.totp_type_yandex
        OtpType.MOTP -> R.string.totp_type_motp
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlgorithmDropdown(value: String, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.totp_field_algorithm)) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ALGORITHMS.forEach { alg ->
                DropdownMenuItem(text = { Text(alg) }, onClick = { onSelected(alg); expanded = false })
            }
        }
    }
}

@Composable
private fun LoginPickerDialog(
    candidates: List<VaultItem>,
    onDismiss: () -> Unit,
    onPick: (VaultItem) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.totp_bind_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.totp_bind_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.sm))
                if (candidates.isEmpty()) {
                    Text(stringResource(R.string.passkey_select_login), style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().height(240.dp)) {
                        items(candidates, key = { it.id }) { login ->
                            TextButton(onClick = { onPick(login) }, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    login.title.ifBlank { login.username },
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Start,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
        dismissButton = { },
    )
}

private val ALGORITHMS = listOf("SHA1", "SHA256", "SHA512")

/** 卡片之间的纵向间距（与密码列表一致）。 */
private val TOTP_CARD_GAP = Spacing.sm
