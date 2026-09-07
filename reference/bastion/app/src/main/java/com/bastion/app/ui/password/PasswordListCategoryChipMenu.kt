package com.bastion.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import com.bastion.app.data.Category
import com.bastion.app.data.PasswordListTopModule
import com.bastion.app.data.PasswordPageContentType
import com.bastion.app.data.bitwarden.BitwardenFolder
import com.bastion.app.data.model.StorageTarget
import com.bastion.app.ui.components.rememberUnifiedCategoryFilterChipMenuWidth
import com.bastion.app.utils.KeePassGroupInfo
import com.bastion.app.viewmodel.CategoryFilter
import com.bastion.app.R

@Composable
internal fun PasswordListCategoryChipMenu(
    currentFilter: CategoryFilter,
    keepassDatabases: List<com.bastion.app.data.LocalKeePassDatabase>,
    bitwardenVaults: List<com.bastion.app.data.bitwarden.BitwardenVault>,
    configuredQuickFilterItems: List<com.bastion.app.data.PasswordListQuickFilterItem>,
    quickFilterFavorite: Boolean,
    onQuickFilterFavoriteChange: (Boolean) -> Unit,
    quickFilter2fa: Boolean,
    onQuickFilter2faChange: (Boolean) -> Unit,
    quickFilterNotes: Boolean,
    onQuickFilterNotesChange: (Boolean) -> Unit,
    quickFilterPasskey: Boolean,
    onQuickFilterPasskeyChange: (Boolean) -> Unit,
    quickFilterBoundNote: Boolean,
    onQuickFilterBoundNoteChange: (Boolean) -> Unit,
    quickFilterAttachments: Boolean,
    onQuickFilterAttachmentsChange: (Boolean) -> Unit,
    quickFilterUncategorized: Boolean,
    onQuickFilterUncategorizedChange: (Boolean) -> Unit,
    quickFilterLocalOnly: Boolean,
    onQuickFilterLocalOnlyChange: (Boolean) -> Unit,
    quickFilterManualStackOnly: Boolean,
    onQuickFilterManualStackOnlyChange: (Boolean) -> Unit,
    quickFilterNeverStack: Boolean,
    onQuickFilterNeverStackChange: (Boolean) -> Unit,
    quickFilterUnstacked: Boolean,
    onQuickFilterUnstackedChange: (Boolean) -> Unit,
    aggregateSelectedTypes: Set<PasswordPageContentType>,
    aggregateVisibleTypes: List<PasswordPageContentType>,
    onToggleAggregateType: (PasswordPageContentType) -> Unit,
    quickFolderShortcuts: List<PasswordQuickFolderShortcut>,
    topModulesOrder: List<PasswordListTopModule>,
    onTopModulesOrderChange: (List<PasswordListTopModule>) -> Unit,
    onQuickFilterItemsOrderChange: (List<com.bastion.app.data.PasswordListQuickFilterItem>) -> Unit,
    launchAnchorBounds: Rect?,
    onDismiss: () -> Unit,
    onSelectFilter: (CategoryFilter) -> Unit,
    categories: List<Category> = emptyList(),
    onCreateCategory: (() -> Unit)? = null,
    onMoveCategory: ((Category, Long?) -> Unit)? = null,
    onMoveCategoryToStorageTarget: ((Category, StorageTarget) -> Unit)? = null,
    getBitwardenFolders: (Long) -> Flow<List<BitwardenFolder>> = { flowOf(emptyList()) },
    getKeePassGroups: (Long) -> Flow<List<KeePassGroupInfo>> = { flowOf(emptyList()) },
    onRenameCategory: ((Category) -> Unit)? = null,
    onDeleteCategory: ((Category) -> Unit)? = null,
    // 通行秘钥 chip：收拢菜单内点击跳转通行秘钥页（不做列表过滤）。
    onNavigateToPasskeys: (() -> Unit)? = null,
    // 非收拢模式下横排筛选条常驻，菜单里的快捷筛选区块与其重复，传 false 隐藏
    // （同时隐藏顶部「快捷筛选」Tab）。收拢模式下菜单是唯一筛选入口，保持 true。
    showQuickFilterSection: Boolean = true,
    // 各存储来源的密码条数统计，null 时数据库区块不显示徽标
    storageCounts: PasswordStorageCounts? = null
) {
    val coroutineScope = rememberCoroutineScope()
    val menuWidth = rememberUnifiedCategoryFilterChipMenuWidth()
    val uiState = rememberCategoryMenuUiState()

    // 方案 A：用顶部 Tab 串联"数据库 / 快捷筛选 / 分类"三个区块，
    // 选中 Tab 即展开对应区块、收起其余，避免三个折叠区纵向堆叠过长。
    var selectedTabRaw by rememberSaveable { mutableStateOf(0) }
    // 隐藏快捷筛选 Tab 后索引上限变化，对历史保存的索引做收敛，避免越界空选中。
    val selectedTab = selectedTabRaw.coerceAtMost(if (showQuickFilterSection) 2 else 1)
    // 快捷筛选 / 分类文件夹的展开以 uiState 为单一事实来源；Tab 切换时通过
    // 下方 LaunchedEffect 同步，避免 Tab 驱动的只读布尔值与折叠 header 点击写入的
    // uiState 状态互相覆盖（两套状态打架会导致 AnimatedVisibility 重组异常）。
    // 切 Tab 即展开对应区块、收起其余，形成完整闭环（含 Tab 0）。
    // 注意：仅在“切 Tab”这一刻同步，用户在该 Tab 内手动折叠后不会被迫重新展开。
    LaunchedEffect(selectedTab, showQuickFilterSection) {
        if (!showQuickFilterSection) {
            // 两 Tab 布局：0=数据库，1=分类
            when (selectedTab) {
                0 -> { uiState.onQuickFiltersExpandedChange(false); uiState.onFoldersExpandedChange(false) }
                1 -> { uiState.onFoldersExpandedChange(true); uiState.onQuickFiltersExpandedChange(false) }
            }
        } else {
            when (selectedTab) {
                0 -> { uiState.onQuickFiltersExpandedChange(false); uiState.onFoldersExpandedChange(false) }
                1 -> { uiState.onQuickFiltersExpandedChange(true); uiState.onFoldersExpandedChange(false) }
                2 -> { uiState.onFoldersExpandedChange(true); uiState.onQuickFiltersExpandedChange(false) }
            }
        }
    }

    val quickFilterState = rememberCategoryMenuQuickFilterState(configuredQuickFilterItems)
    val moduleDragState = rememberCategoryMenuModuleDragState(topModulesOrder)
    BindCategoryMenuModuleDragState(
        topModulesOrder = topModulesOrder,
        categoryEditMode = uiState.categoryEditMode,
        moduleDragState = moduleDragState,
        coroutineScope = coroutineScope
    )

    val availableModules = remember(
        uiState.showDeferredFolderSection,
        quickFolderShortcuts,
        quickFilterState.order,
        showQuickFilterSection
    ) {
        buildCategoryMenuAvailableModules(
            showDeferredFolderSection = uiState.showDeferredFolderSection,
            quickFolderShortcuts = quickFolderShortcuts,
            quickFilterOrder = quickFilterState.order,
            includeQuickFilters = showQuickFilterSection
        )
    }
    val orderedModules = remember(moduleDragState.moduleOrder, availableModules) {
        resolveCategoryMenuOrderedModules(
            moduleOrder = moduleDragState.moduleOrder,
            availableModules = availableModules
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        PasswordListCategoryChipMenuTabBar(
            selectedTab = selectedTab,
            onSelectTab = { selectedTabRaw = it },
            showQuickFilters = showQuickFilterSection
        )

        PasswordDatabaseFiltersSection(
            params = PasswordDatabaseFiltersSectionParams(
                currentFilter = currentFilter,
                keepassDatabases = keepassDatabases,
                bitwardenVaults = bitwardenVaults,
                onSelectFilter = onSelectFilter,
                entryCounts = storageCounts
            ),
            expanded = selectedTab == 0
        )

        PasswordListCategoryChipMenuModulesSection(
            orderedModules = orderedModules,
            quickFiltersExpanded = uiState.quickFiltersExpanded,
            onQuickFiltersExpandedChange = uiState.onQuickFiltersExpandedChange,
            foldersExpanded = uiState.foldersExpanded,
            onFoldersExpandedChange = uiState.onFoldersExpandedChange,
            categoryEditMode = uiState.categoryEditMode,
            menuWidth = menuWidth,
            quickFilterOrder = quickFilterState.order,
            quickFilterMeasuredSizes = quickFilterState.measuredSizes,
            quickFilterFavorite = quickFilterFavorite,
            onQuickFilterFavoriteChange = onQuickFilterFavoriteChange,
            quickFilter2fa = quickFilter2fa,
            onQuickFilter2faChange = onQuickFilter2faChange,
            quickFilterNotes = quickFilterNotes,
            onQuickFilterNotesChange = onQuickFilterNotesChange,
            quickFilterPasskey = quickFilterPasskey,
            onQuickFilterPasskeyChange = onQuickFilterPasskeyChange,
            quickFilterBoundNote = quickFilterBoundNote,
            onQuickFilterBoundNoteChange = onQuickFilterBoundNoteChange,
            quickFilterAttachments = quickFilterAttachments,
            onQuickFilterAttachmentsChange = onQuickFilterAttachmentsChange,
            quickFilterUncategorized = quickFilterUncategorized,
            onQuickFilterUncategorizedChange = onQuickFilterUncategorizedChange,
            quickFilterLocalOnly = quickFilterLocalOnly,
            onQuickFilterLocalOnlyChange = onQuickFilterLocalOnlyChange,
            quickFilterManualStackOnly = quickFilterManualStackOnly,
            onQuickFilterManualStackOnlyChange = onQuickFilterManualStackOnlyChange,
            quickFilterNeverStack = quickFilterNeverStack,
            onQuickFilterNeverStackChange = onQuickFilterNeverStackChange,
            quickFilterUnstacked = quickFilterUnstacked,
            onQuickFilterUnstackedChange = onQuickFilterUnstackedChange,
            aggregateSelectedTypes = aggregateSelectedTypes,
            aggregateVisibleTypes = aggregateVisibleTypes,
            onToggleAggregateType = onToggleAggregateType,
            // 跳转前先收起下拉菜单，避免导航后菜单仍悬浮在新页面上。
            onNavigateToPasskeys = {
                onNavigateToPasskeys?.invoke()
                onDismiss()
            },
            onQuickFilterItemsOrderChange = { reordered ->
                quickFilterState.onOrderChange(reordered)
                onQuickFilterItemsOrderChange(reordered)
            },
            currentFilter = currentFilter,
            quickFolderShortcuts = quickFolderShortcuts,
            categories = categories,
            onCategoryActionTargetChange = uiState.onCategoryActionTargetChange,
            onSelectFilter = onSelectFilter,
            modulePlacementOffsets = moduleDragState.modulePlacementOffsets,
            draggingModule = moduleDragState.draggingModule,
            settlingModule = moduleDragState.settlingModule,
            moduleDragOffset = moduleDragState.moduleDragOffset,
            moduleSettleOffset = moduleDragState.moduleSettleOffset,
            moduleBounds = moduleDragState.moduleBounds,
            previousModuleBounds = moduleDragState.previousModuleBounds,
            moduleReorderEpoch = moduleDragState.moduleReorderEpoch,
            lastModuleAnimatedEpoch = moduleDragState.lastModuleAnimatedEpoch,
            moduleOrder = moduleDragState.moduleOrder,
            onModuleOrderChange = moduleDragState.onModuleOrderChange,
            onModuleDragOffsetChange = moduleDragState.onModuleDragOffsetChange,
            onModuleReorderEpochChange = moduleDragState.onModuleReorderEpochChange,
            onDraggingModuleChange = moduleDragState.onDraggingModuleChange,
            onSettlingModuleChange = moduleDragState.onSettlingModuleChange,
            coroutineScope = coroutineScope,
            onTopModulesOrderChange = onTopModulesOrderChange,
            isExpandedStateLoaded = uiState.isExpandedStateLoaded,
            activeTab = selectedTab,
            showHeaders = uiState.categoryEditMode,
        )

        AnimatedVisibility(
            visible = selectedTab == 2,
            enter = fadeIn(animationSpec = tween(160)) + expandVertically(animationSpec = tween(180)),
            exit = fadeOut(animationSpec = tween(120)) + shrinkVertically(animationSpec = tween(140))
        ) {
            PasswordListCategoryChipMenuBottomActions(
                categories = categories,
                keepassDatabases = keepassDatabases,
                bitwardenVaults = bitwardenVaults,
                getBitwardenFolders = getBitwardenFolders,
                getKeePassGroups = getKeePassGroups,
                categoryEditMode = uiState.categoryEditMode,
                onCategoryEditModeChange = uiState.onCategoryEditModeChange,
                onDismiss = onDismiss,
                onCreateCategory = onCreateCategory,
                onMoveCategory = onMoveCategory,
                onMoveCategoryToStorageTarget = onMoveCategoryToStorageTarget,
                onRenameCategory = onRenameCategory,
                onDeleteCategory = onDeleteCategory,
                categoryActionTarget = uiState.categoryActionTarget,
                onCategoryActionTargetChange = uiState.onCategoryActionTargetChange,
                renameCategoryTarget = uiState.renameCategoryTarget,
                onRenameCategoryTargetChange = uiState.onRenameCategoryTargetChange,
                renameCategoryInput = uiState.renameCategoryInput,
                onRenameCategoryInputChange = uiState.onRenameCategoryInputChange
            )
        }
    }
}

@Composable
private fun PasswordListCategoryChipMenuTabBar(
    selectedTab: Int,
    onSelectTab: (Int) -> Unit,
    modifier: Modifier = Modifier,
    // 非收拢模式下隐藏「快捷筛选」Tab（与横排筛选条重复）
    showQuickFilters: Boolean = true
) {
    val tabs = buildList {
        add(R.string.category_selection_menu_databases)
        if (showQuickFilters) add(R.string.category_selection_menu_quick_filters)
        add(R.string.category_selection_menu_folders)
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        tabs.forEachIndexed { index, labelRes ->
            // 使用 Material3 FilterChip 统一 ripple 与选中态样式，与 app 其他 chip 一致
            FilterChip(
                selected = selectedTab == index,
                onClick = { onSelectTab(index) },
                modifier = Modifier.weight(1f),
                label = {
                    Text(
                        text = stringResource(labelRes),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            )
        }
    }
}
