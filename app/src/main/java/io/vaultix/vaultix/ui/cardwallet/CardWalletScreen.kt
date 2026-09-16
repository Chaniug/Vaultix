/*
 * Vaultix — app:ui:cardwallet
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 卡包 Tab 界面。视觉组件（品牌图标）来自 Bastion（见 [CardBrandIcon]），
 * 容器与列表按 Vaultix 条目模型重写（Bastion 的 pane 依赖其私有条目类型，不搬）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.cardwallet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.IconButton
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.runtime.saveable.rememberSaveable
import io.vaultix.vaultix.ui.common.VaultixSearchTopAppBar
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.vaultix.common.CardBrandDetector
import io.vaultix.common.formatCardNumberGrouped
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.common.EntryCard
import io.vaultix.vaultix.ui.common.EntryCardIconSpacing
import io.vaultix.vaultix.ui.common.EntryCardTextSpacing
import io.vaultix.vaultix.ui.common.ItemFormDialog
import io.vaultix.vaultix.ui.common.PressAndSwipeToDelete
import io.vaultix.vaultix.ui.common.VaultixExpressiveTopBar
import io.vaultix.vaultix.ui.common.rememberImmersiveBarPadding
import io.vaultix.vaultix.ui.common.rememberScrollCollapseFraction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import io.vaultix.vaultix.ui.theme.Spacing

/**
 * 卡包 Tab（Docs/08 S20 最小版 / main-shell-migration §6.1.3）。
 *
 * @param embedded 主界面 Tab 内嵌模式：隐藏返回键与 FAB（「+」由底部导航条统一承载）。
 * @param addRequest 「+」请求计数（非零即触发新建表单，避免重复弹窗）。
 * @param onAddConsumed 表单已弹出，通知宿主清零请求。
 * @param onOpenItem 点击某张卡 → 打开条目详情（与密码 Tab 同一条二级路由）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardWalletScreen(
    embedded: Boolean = false,
    addRequest: Int = 0,
    onAddConsumed: () -> Unit = {},
    onOpenItem: (VaultItem) -> Unit = {},
    /** 底部叠层悬浮栏占用的高度（宿主给；非内嵌时为 0）——列表要留出它，否则末条被压住。 */
    bottomInset: Dp = 0.dp,
    viewModel: CardWalletViewModel = hiltViewModel(),
) {
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf<VaultItem?>(null) }
    val listState = rememberLazyListState()
    val collapse = rememberScrollCollapseFraction(listState)
    val barPadding = rememberImmersiveBarPadding(collapse)

    LaunchedEffect(addRequest) {
        if (addRequest > 0) {
            creating = VaultItem(id = "", title = "", type = VaultItemType.Card)
            onAddConsumed()
        }
    }

    // 搜索（2026-09-13 用户反馈「卡包里面没有搜索按钮」）。
    // 卡片条目通常只有个位数，**在页面内过滤**就够，不必给 ViewModel 加 query 状态
    // （密码 / 验证码页的 query 还要参与「切库后重查」，那两页才必须放在 ViewModel）。
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val closeSearch: () -> Unit = { searchActive = false; query = "" }
    val visibleCards = remember(cards, query) { filterCards(cards, query) }
    // 搜索态自己消费返回：主界面是根路由（栈里没有上一层），不拦就会直接退回桌面。
    BackHandler(enabled = searchActive) { closeSearch() }

    val searchScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    // 轻量选中态（2026-09-16）：卡包页没有像密码页那样的批量操作，所以不需要一整套
    // 多选（复选框、批量工具条）。删除防误触只需要「先选中」这一个语义 ⇒ 用**单值**
    // 记录当前选中的那张卡，长按进入、删除或点击取消。
    // ⚠️ 用 `rememberSaveable`：旋转屏幕后选中态不该丢（否则用户会以为"我明明选中了"）。
    var selectedCardId by rememberSaveable { mutableStateOf<String?>(null) }
    // 切库 / 搜索词变化后旧 id 可能已不在列表里 ⇒ 用 key 重置，避免"幽灵选中"。
    LaunchedEffect(cards, query) {
        if (selectedCardId != null && visibleCards.none { it.id == selectedCardId }) {
            selectedCardId = null
        }
    }
    // 返回键优先取消选中（与搜索态同一优先级思路：先退一层"模式"，再退路由）。
    // ⚠️ 必须在搜索态之后声明：搜索态自己的 BackHandler 要先消费（`enabled` 互斥）。
    BackHandler(enabled = selectedCardId != null && !searchActive) { selectedCardId = null }

    Scaffold(
        // 沉浸式：顶栏浮在内容之上（状态栏内边距由顶栏自己处理，见 [VaultixExpressiveTopBar]）。
        // ⚠️ 搜索态是例外：那时换成一条**固定高度**的真实 `topBar`，必须让 Scaffold 帮它
        // 预留状态栏高度，否则搜索输入框会被状态栏压住。
        contentWindowInsets = if (searchActive) {
            ScaffoldDefaults.contentWindowInsets
        } else {
            WindowInsets(0, 0, 0, 0)
        },
        topBar = {
            if (searchActive) {
                VaultixSearchTopAppBar(
                    searchTerm = query,
                    placeholder = stringResource(R.string.card_wallet_search_hint),
                    onSearchTermChange = { query = it },
                    onClose = closeSearch,
                    clearIconContentDescription = stringResource(R.string.card_wallet_search_close),
                    scrollBehavior = searchScrollBehavior,
                )
            }
        },
        floatingActionButton = {
            // ⚠️ 选中态下隐藏 FAB：此时用户的意图是"对这张卡做点什么"（左滑删除），
            // 一个"新建"按钮悬在那里与当前语义冲突，也容易误点。
            if (!embedded && selectedCardId == null) {
                FloatingActionButton(onClick = {
                    creating = VaultItem(id = "", title = "", type = VaultItemType.Card)
                }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.nav_add))
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 搜索态归零：Scaffold 已按 `contentWindowInsets` 为搜索栏预留了高度。
                val topInset = if (searchActive) 0.dp else barPadding
                if (visibleCards.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(top = topInset),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(
                                if (cards.isEmpty()) {
                                    R.string.card_wallet_empty
                                } else {
                                    R.string.card_wallet_no_match
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        // 顶部留白走 contentPadding（**不是**外层容器 padding）：
                        // 外层 padding 会把视口整体下压，内容永远画不到顶栏区域，
                        // 「收起后顶栏透明、内容从下方穿过」就不成立（顶栏下留一条死区）。
                        // 与密码 / 验证码列表同一套留白结构（卡片不再自带外边距）。
                        contentPadding = PaddingValues(
                            start = Spacing.lg,
                            top = topInset,
                            end = Spacing.lg,
                            bottom = Spacing.sm + bottomInset,
                        ),
                        verticalArrangement = Arrangement.spacedBy(CARD_GAP),
                    ) {
                        items(visibleCards, key = { it.id }) { item ->
                            // 「长按选中 → 左滑 → 松手过半 → 二次确认」（软删除进回收站），
                            // 与密码 / 验证码 / 通行密钥列表**同一套纪律**。
                            // ⚠️ 2026-09-16：卡包页此前没有多选机制，删除是"直接左滑"。
                            // 用户反馈误触太多 ⇒ 补一个**轻量选中态**（见 [selectedCardId]）：
                            // 长按卡片选中它，选中后左滑才可删；点其他卡片或返回键取消。
                            PressAndSwipeToDelete(
                                onDelete = { viewModel.deleteCard(item) },
                                selectable = selectedCardId == item.id,
                            ) {
                                CardWalletRow(
                                    item = item,
                                    isSelected = selectedCardId == item.id,
                                    onClick = {
                                        // 已选中时点击 = 取消选中（给用户一个不退出的"取消"路径）；
                                        // 否则照旧打开详情。
                                        if (selectedCardId != null) {
                                            selectedCardId = null
                                        } else {
                                            onOpenItem(item)
                                        }
                                    },
                                    onLongClick = {
                                        selectedCardId =
                                            if (selectedCardId == item.id) null else item.id
                                    },
                                )
                            }
                        }
                    }
                }
            }
            if (!searchActive) {
                VaultixExpressiveTopBar(
                    title = stringResource(R.string.nav_card_wallet),
                    collapseFraction = collapse,
                    modifier = Modifier.align(Alignment.TopCenter),
                    actions = {
                        IconButton(onClick = { searchActive = true }) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = stringResource(R.string.card_wallet_search_hint),
                            )
                        }
                    },
                )
            }
        }
    }

    val draft = creating
    if (draft != null) {
        ItemFormDialog(
            title = stringResource(R.string.card_wallet_new),
            initial = draft,
            saving = saving,
            typeEditable = false,
            onDismiss = { creating = null },
            onSave = { item ->
                viewModel.createCard(item)
                creating = null
            },
        )
    }
}

private val CARD_GAP = Spacing.sm

/**
 * 卡包搜索过滤：标题 / 备注 / 用户名任一命中即可（不区分大小写）。
 *
 * 抽成**顶层私有函数**（而不是页面内的 lambda）有两个原因：纯函数好单测；
 * 且 detekt `CyclomaticComplexMethod` 会把它算进调用方的复杂度，独立后互不影响。
 */
private fun filterCards(cards: List<VaultItem>, query: String): List<VaultItem> {
    val keyword = query.trim()
    if (keyword.isEmpty()) return cards
    return cards.filter { item ->
        item.title.contains(keyword, ignoreCase = true) ||
            item.username.contains(keyword, ignoreCase = true) ||
            item.notes.contains(keyword, ignoreCase = true)
    }
}

/**
 * 单张卡：品牌图标 + 名称 + 分组卡号（末四位外打码）。
 *
 * ⚠️ 必须**可点击**：此前漏了 `clickable`，导致卡包里的条目点不进详情（用户反馈）。
 * 外框改用 [EntryCard]（与密码 / 验证码列表同一套卡片规格）——此前这里是一个**裸 Row**，
 * 连外框都没有，是三个列表里观感最不一致的一处。
 *
 * @param isSelected 是否处于「已选中」态（选中后才允许左滑删除，见 [PressAndSwipeToDelete]）。
 *   选中态由 [EntryCard] 的 `selected` 参数高亮（与密码列表同一套选中视觉）。
 * @param onLongClick 长按 = 选中 / 取消选中（卡包页的删除前置门槛）。
 */
@Composable
private fun CardWalletRow(
    item: VaultItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val card = item.card
    val brand = remember(card?.number, card?.brand) {
        CardBrandDetector.detect(card?.number.orEmpty(), card?.brand.orEmpty())
    }
    EntryCard(onClick = onClick, onLongClick = onLongClick, selected = isSelected) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(width = 44.dp, height = 30.dp)) {
                CardBrandIcon(brand = brand, modifier = Modifier.fillMaxSize())
            }
            Column(
                modifier = Modifier.weight(1f).padding(start = EntryCardIconSpacing),
                verticalArrangement = Arrangement.spacedBy(EntryCardTextSpacing),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val number = card?.number.orEmpty()
                if (number.isNotBlank()) {
                    Text(
                        text = maskCardNumber(formatCardNumberGrouped(number)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                } else {
                    card?.cardholderName?.takeIf { it.isNotBlank() }?.let { holder ->
                        Text(
                            text = holder,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            val expiry = listOf(card?.expMonth, card?.expYear)
                .map { it.orEmpty() }
                .filter { it.isNotBlank() }
            if (expiry.size == 2) {
                Text(
                    text = expiry.joinToString("/"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 卡号打码：保留末 4 位，其余替换为「•」（对齐 Bastion 卡包列表观感）。
 *
 * ⚠️ 分组格式化后的分隔符保留，避免把「1234 5678」压成一串难以辨认的圆点。
 */
private fun maskCardNumber(grouped: String): String {
    if (grouped.isBlank()) return ""
    val totalDigits = grouped.count { it.isDigit() }
    var index = 0
    return grouped.map { char ->
        if (!char.isDigit()) {
            char
        } else {
            val keep = index >= totalDigits - 4
            index++
            if (keep) char else '•'
        }
    }.joinToString("")
}
