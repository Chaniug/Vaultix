/*
 * Vaultix — app:ui · passkeys
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.passkeys

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.common.EntryCard
import io.vaultix.vaultix.ui.common.EntryCardIconSpacing
import io.vaultix.vaultix.ui.common.EntryCardTextSpacing
import io.vaultix.vaultix.ui.common.PressAndSwipeToDelete
import io.vaultix.vaultix.ui.common.SavePasskeyDialog
import io.vaultix.vaultix.ui.common.SelectionActionBar
import io.vaultix.vaultix.ui.common.SiteIconByHost
import io.vaultix.vaultix.ui.common.VaultixExpressiveTopBar
import io.vaultix.vaultix.ui.common.VaultixSearchTopAppBar
import io.vaultix.vaultix.ui.common.rememberImmersiveBarPadding
import io.vaultix.vaultix.ui.common.rememberScrollCollapseFraction
import io.vaultix.vaultix.ui.common.toggleSelection
import io.vaultix.vaultix.ui.theme.Spacing

/** 通行密钥列表的卡片间距（与密码条目 / 验证码 / 卡包三个列表一致）。 */
private val PASSKEY_CARD_GAP = Spacing.sm

/**
 * 通行密钥列表（从验证码界面「通行密钥」按钮进入）。
 * 对齐 Bitwarden / Keyguard：通行密钥只读（仅查看 / 删除），不可编辑——密钥由服务器 / 平台管理。
 *
 * 观感上补齐成**第四个列表**（2026-09-15 用户反馈「通行密钥的界面 ui 风格没有对齐
 * 密码条目和验证码界面」）：
 * - 卡片外框改用 [EntryCard]（与密码 / 验证码 / 卡包同一套规格）——此前这里是一个
 *   **裸 `Surface` + 裸 `Row`**，既不是 M3 默认卡片底色、也没有统一的圆角与内边距；
 * - 图标改用 [SiteIconByHost]（站点图标，取不到回退首字母头像）——此前恒为一枚
 *   套在圆底里的 `Key` 图标，整页看起来像另一个 App 的列表；
 * - 顶栏改用沉浸式 [VaultixExpressiveTopBar]——此前是 `LargeTopAppBar`，收起时
 *   顶栏变矮而内容不同步穿过去，正是 [ItemsScreen] / [TotpCodesScreen] 早先修掉的退化；
 * - 左右内边距 `Spacing.lg`（此前是 `Spacing.sm`）、间距走 `Arrangement.spacedBy`；
 * - 补 `bottomInset` 底部让位；
 * - 补左滑删除与长按多选（与另外三个列表同一套交互）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasskeysScreen(
    onBack: () -> Unit,
    /**
     * 底部叠层悬浮栏占用的高度（宿主给）。
     *
     * 通行密钥是**独立路由**（不在主界面 Tab 里，下面没有胶囊底栏）⇒ 宿主传 0；
     * 但参数与让位公式保留，与另外三个列表同构——将来若嵌进 Tab 不必再改一遍。
     */
    bottomInset: Dp = 0.dp,
    viewModel: PasskeysViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var detail by remember { mutableStateOf<PasskeyRow?>(null) }
    var saving by remember { mutableStateOf(false) }
    // 长按多选：用「集合非空」当开关，省掉一个必须与它同步的布尔量（同 ItemsScreen）。
    var selectedKeys by remember { mutableStateOf(emptySet<String>()) }

    val rows = rememberPasskeyRows(state)

    // 搜索态自己消费返回手势：本页是二级页，不拦会直接退回上一页而丢掉输入。
    BackHandler(enabled = searchActive) {
        searchActive = false
        viewModel.setQuery("")
    }
    // 多选态优先吃掉返回手势：否则一按返回就整页退出，前面勾的全白勾了。
    BackHandler(enabled = selectedKeys.isNotEmpty()) { selectedKeys = emptySet() }

    // 搜索关闭动作（点 × 与系统返回共用）。
    val closeSearch: () -> Unit = { searchActive = false; viewModel.setQuery("") }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // 顶栏浮在内容之上：Scaffold 不再为它预留高度（见 [PasskeysBody] 的让位说明）。
        contentWindowInsets =
            if (searchActive) ScaffoldDefaults.contentWindowInsets else WindowInsets(0, 0, 0, 0),
        topBar = {
            // 搜索态保留固定高度顶栏（输入框不能塞进会折叠的大标题里）。
            if (searchActive) {
                VaultixSearchTopAppBar(
                    searchTerm = state.query,
                    placeholder = stringResource(R.string.passkeys_search_hint),
                    onSearchTermChange = viewModel::setQuery,
                    onClose = closeSearch,
                    clearIconContentDescription = stringResource(R.string.items_search_clear),
                    scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { saving = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.passkeys_add_title))
            }
        },
        // 多选态才出现，平时不占一寸屏幕。
        bottomBar = {
            if (selectedKeys.isNotEmpty()) {
                PasskeySelectionBar(
                    rows = rows,
                    selectedKeys = selectedKeys,
                    onSelectionChange = { selectedKeys = it },
                    onDelete = { victims ->
                        victims.forEach { viewModel.deleteCredential(it) }
                        selectedKeys = emptySet()
                    },
                )
            }
        },
    ) { padding ->
        val listState = rememberLazyListState()
        val collapse = rememberScrollCollapseFraction(listState)
        val barPadding = rememberImmersiveBarPadding(collapse)

        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 让位**必须做在滚动内容里**（列表的 `contentPadding.top`），不能做在滚动容器外的
            // `Column.padding(top)`：做在外面整页被永久下压，内容永远画不到顶栏区域 ⇒
            // 「收起后顶栏透明、内容从下方穿过」不成立（`.ai/ISSUES.md` #67 / #76）。
            val topInset = if (searchActive) 0.dp else barPadding + Spacing.sm
            PasskeysBody(
                state = state,
                rows = rows,
                listState = listState,
                topInset = topInset,
                bottomInset = bottomInset,
                selectedKeys = selectedKeys,
                onToggleSelect = { key -> selectedKeys = toggleSelection(selectedKeys, key) },
                onOpenDetail = { row -> detail = row },
                onDelete = { row ->
                    // 删完必须把 key 从选中集合里摘掉，否则底栏还会统计一条已不存在的凭证。
                    selectedKeys = selectedKeys - row.key
                    viewModel.deleteCredential(row)
                },
            )
            if (!searchActive) {
                PasskeysOverlayTopBar(
                    collapseFraction = collapse,
                    onBack = onBack,
                    onSearch = { searchActive = true },
                )
            }
        }
    }

    detail?.let { row ->
        PasskeyDetailDialog(
            row = row,
            onDismiss = { detail = null },
            onDelete = { viewModel.deleteCredential(row); detail = null },
        )
    }

    if (saving) {
        SavePasskeyDialog(
            candidates = viewModel.loginCandidates(),
            onDismiss = { saving = false },
            onSave = { loginId, credential ->
                viewModel.savePasskey(loginId, credential)
                saving = false
            },
        )
    }
}

/**
 * 由已收集的 [PasskeysViewModel.UiState] 派生「当前要展示的通行密钥行」。
 *
 * ⚠️ **必须**吃 `state.items` / `state.query` 两个 Compose State，**不能**回 ViewModel 读
 * `_state.value`。函数式调用不订阅 Flow ⇒ **不感知快照**：首帧数据未到时算出空列表，
 * 而且不等下一次重组，界面就停在空态「还没有通行密钥」，**必须点一下搜索**
 * （改变 `searchActive` 强制重组）条目才出现。
 *
 * 2026-09-15 用户真机报的正是这一条：「点进通行密钥页面看不到条目，点击搜索后却又看得到」。
 * 这与验证码页 `rememberTotpEntries`（`TotpCodesScreen.kt`）是**同一个坑**，那边早已修过。
 *
 * `remember` 把派生结果绑定到这两个输入：任一变化即重算，也不会因多选等无关状态变化而白算。
 * 抽成 helper 同时也是给主 composable 瘦身、守 detekt `LongMethod ≤150`。
 */
@Composable
private fun rememberPasskeyRows(state: PasskeysViewModel.UiState): List<PasskeyRow> =
    remember(state.items, state.query) {
        state.items.toPasskeyRows().filter { it.matches(state.query) }
    }

/**
 * 通行密钥列表的正文（列表 / 空态 / 锁定态）。
 *
 * 抽成独立 composable 有两个原因：
 * 1. 让 [PasskeysScreen] 主函数守住 detekt `LongMethod`（≤150 行）——沉浸顶栏与
 *    多选状态加起来很容易顶线（同 [ItemsScreen] 的 `ItemsBody`）；
 * 2. 列表留白是一套完整约束（顶部 inset 随顶栏收起变短、底部要留出叠层底栏），
 *    自成一块更好核对，不会与顶栏代码互相穿插。
 */
@Composable
private fun PasskeysBody(
    state: PasskeysViewModel.UiState,
    rows: List<PasskeyRow>,
    listState: LazyListState,
    topInset: Dp,
    bottomInset: Dp,
    selectedKeys: Set<String>,
    onToggleSelect: (String) -> Unit,
    onOpenDetail: (PasskeyRow) -> Unit,
    onDelete: (PasskeyRow) -> Unit,
) {
    val selectionMode = selectedKeys.isNotEmpty()
    // 库里「确实没有通行密钥」——用它而不是 `rows.isEmpty()` 来判定空态，
    // 否则搜索筛空时会错报「还没有通行密钥」（验证码页是同样拆开的，见其 `items.isEmpty()`
    // 与 `entries.isEmpty()` 两级）。
    val noPasskeysAtAll = state.items.none { it.fido2Credentials.isNotEmpty() }
    when {
        // ⚠️ **必须排在空态前面**：冷启动 / 解锁后条目流还没发首帧时 `state.items`
        // 同样是空的 —— 若直接落进空态，用户看到的就是「还没有通行密钥」这个**假状态**。
        // 标题也**不能**沿用 `passkeys_empty_title`：那等于一边说"还没有"一边说"正在读"。
        state.loading -> PasskeysEmptyState(
            topInset = topInset,
            title = null,
            message = stringResource(R.string.passkeys_loading_body),
        )

        // 与密码页 / 验证码页同一类坑（2026-09-15 用户报的「切到未解锁的库后两页全白」）：
        // 活跃库锁定时条目流必然是空的，但**不能说「还没有通行密钥」**。
        state.unlocked == false -> PasskeysEmptyState(
            topInset = topInset,
            title = stringResource(R.string.items_locked_title),
            message = stringResource(R.string.items_locked_body),
        )

        // 库里确实一条通行密钥都没有（与"搜索筛没了"区分）。
        noPasskeysAtAll -> PasskeysEmptyState(
            topInset = topInset,
            title = stringResource(R.string.passkeys_empty_title),
            message = stringResource(
                R.string.passkeys_empty_body,
                state.items.size,
                state.items.count { it.fido2Credentials.isNotEmpty() },
            ),
        )

        // 有通行密钥、但被搜索词筛没了 ⇒ 用「没有匹配的条目」，不能说「还没有通行密钥」。
        // 复用密码列表的同名字符串，避免为同一件事造第三份文案。
        rows.isEmpty() -> PasskeysEmptyState(
            topInset = topInset,
            title = stringResource(R.string.items_search_empty),
            message = stringResource(R.string.passkeys_search_hint),
        )

        else -> LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // 与密码 / 验证码列表同一套留白结构（卡片不再自带外边距，见 [EntryCard]）。
            contentPadding = PaddingValues(
                start = Spacing.lg,
                top = topInset,
                end = Spacing.lg,
                bottom = Spacing.sm + bottomInset,
            ),
            verticalArrangement = Arrangement.spacedBy(PASSKEY_CARD_GAP),
        ) {
            items(rows, key = { it.key }) { row ->
                PasskeyRowItem(
                    row = row,
                    serverOrigin = state.serverOrigin,
                    isSelectionMode = selectionMode,
                    isSelected = row.key in selectedKeys,
                    onToggleSelect = { onToggleSelect(row.key) },
                    onClick = { onOpenDetail(row) },
                    onDelete = { onDelete(row) },
                )
            }
        }
    }
}

/**
 * 空态 / 锁定态（共用）。
 *
 * ⚠️ `topInset` **必须**计入：沉浸顶栏是叠层，不占 Scaffold 高度 ⇒ 若空态从 0 开始
 * 居中，标题会正好落在半透明顶栏底下（观感像被"吃掉"）。
 *
 * @param message 详情文案；[title] 为空时只显示详情。
 */
@Composable
private fun PasskeysEmptyState(topInset: Dp, message: String, title: String? = null) {
    Column(
        modifier = Modifier.fillMaxSize().padding(top = topInset, start = Spacing.xl, end = Spacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        title?.let {
            Text(text = it, style = MaterialTheme.typography.titleLarge)
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.sm),
        )
    }
}

/**
 * 一行通行密钥。
 *
 * 外框改用 [EntryCard]（与密码 / 验证码 / 卡包列表同一套卡片规格）——此前这里是一个
 * 裸 `Surface` + 裸 `Row`，没有统一的 M3 卡片底色、圆角与内边距，是四个列表里
 * 观感最不一致的一处（同 [CardWalletScreen] 早先的迁移）。
 *
 * 左滑删除包在外层：长按**选中**由 [EntryCard] 的 `onLongClick` **独占**，
 * 本容器只负责"长按成立后进入拖拽删除"的信号，不再回调选中 ——
 * 否则一次长按会同时触发两次 toggle（净无操作），正是用户反馈「长按进不了多选」的根因。
 */
@Composable
private fun PasskeyRowItem(
    row: PasskeyRow,
    serverOrigin: String?,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    // ⚠️ 2026-09-16：**必须已选中才允许左滑**（见 [PressAndSwipeToDelete] 头注释）。
    PressAndSwipeToDelete(onDelete = onDelete, selectable = isSelected) {
        EntryCard(
            onClick = if (isSelectionMode) onToggleSelect else onClick,
            onLongClick = if (isSelectionMode) null else onToggleSelect,
            selected = isSelected,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // 站点图标（库内服务器地址 + 凭证 rpId 反查域名），取不到回退首字母 ——
                // 与密码 / 验证码列表同一套观感（见 [SiteIconByHost]）。
                SiteIconByHost(
                    domain = row.credential.rpId,
                    fallbackText = row.credential.rpName.ifBlank { row.credential.rpId },
                    serverOrigin = serverOrigin,
                )
                Spacer(Modifier.width(EntryCardIconSpacing))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(EntryCardTextSpacing),
                ) {
                    Text(
                        text = row.credential.rpName.ifBlank { row.credential.rpId },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (row.credential.userName.isNotBlank()) {
                        Text(
                            text = row.credential.userName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = stringResource(R.string.passkey_bound_to_login, row.loginTitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * 通行密钥的批量操作条。
 *
 * 直接复用密码 / 验证码页共用的 [SelectionActionBar]（「全选 / 清空 / 删除」三键的
 * 行为与文案完全一致），这里只把"选中集合"翻译成"待删凭证列表"。
 */
@Composable
private fun PasskeySelectionBar(
    rows: List<PasskeyRow>,
    selectedKeys: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    onDelete: (List<PasskeyRow>) -> Unit,
) {
    SelectionActionBar(
        selectedCount = selectedKeys.size,
        // 本页是二级页（从设置进入），底部**没有**悬浮 Dock ⇒ 不需要让位。
        // 主 Tab 页（密码条目 / 验证码）则必须传 `BottomDockOccupiedHeight`。
        onClear = { onSelectionChange(emptySet()) },
        onDelete = { onDelete(rows.filter { it.key in selectedKeys }) },
    )
}

/**
 * 浮在内容之上的大标题顶栏（沉浸式，见 [VaultixExpressiveTopBar]）。
 *
 * 与 [PasskeySelectionBar] 同理：主 composable 的行数与圈复杂度都要留给门禁，
 * 顶栏这种自成一体的区块抽出去最省事，也不损失可读性。
 */
@Composable
private fun BoxScope.PasskeysOverlayTopBar(
    collapseFraction: Float,
    onBack: () -> Unit,
    onSearch: () -> Unit,
) {
    VaultixExpressiveTopBar(
        title = stringResource(R.string.passkeys_screen_title),
        collapseFraction = collapseFraction,
        modifier = Modifier.align(Alignment.TopCenter),
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                )
            }
        },
        actions = {
            IconButton(onClick = onSearch) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = stringResource(R.string.passkeys_search_hint),
                )
            }
        },
    )
}

@Composable
private fun PasskeyDetailDialog(row: PasskeyRow, onDismiss: () -> Unit, onDelete: () -> Unit) {
    val c = row.credential
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.passkeys_detail_title),
                    modifier = Modifier.weight(1f),
                )
                if (c.credentialId.isNotBlank()) {
                    IconButton(onClick = { clipboard.setText(AnnotatedString(c.credentialId)) }) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = stringResource(R.string.action_copy),
                        )
                    }
                }
            }
        },
        text = {
            Column {
                Text(
                    stringResource(R.string.passkeys_readonly_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.sm))
                DetailLine(
                    stringResource(R.string.passkey_field_rp_name),
                    c.rpName.ifBlank { "—" },
                )
                DetailLine(stringResource(R.string.passkey_field_rp_id), c.rpId.ifBlank { "—" })
                DetailLine(stringResource(R.string.passkey_field_user), c.userName.ifBlank { "—" })
                DetailLine(stringResource(R.string.passkey_user), c.userDisplayName.ifBlank { "—" })
                DetailLine(stringResource(R.string.passkey_field_credential_id), c.credentialId.ifBlank { "—" })
                DetailLine(stringResource(R.string.passkey_field_key_algorithm), c.keyAlgorithm ?: "—")
                DetailLine(stringResource(R.string.passkey_field_counter), c.counter.toString())
                DetailLine(stringResource(R.string.passkey_field_discoverable), if (c.discoverable) "true" else "false")
                DetailLine(stringResource(R.string.passkey_field_created), c.creationDate ?: "—")
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = stringResource(R.string.passkey_bound_to_login, row.loginTitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDelete) { Text(stringResource(R.string.totp_remove_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    if (value.isBlank()) return
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
