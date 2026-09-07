package com.bastion.app.ui

import com.bastion.app.logging.runCatchingObserved
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.bastion.app.R
import com.bastion.app.bitwarden.repository.BitwardenRepository
import com.bastion.app.bitwarden.viewmodel.BitwardenViewModel
import com.bastion.app.data.Category
import com.bastion.app.data.CategorySelectionUiMode
import com.bastion.app.data.PasswordCardDisplayMode
import com.bastion.app.data.PasswordOwnership
import com.bastion.app.data.PasswordPageContentType
import com.bastion.app.data.PasswordListQuickFilterItem
import com.bastion.app.data.model.StorageTarget
import com.bastion.app.data.resolveOwnership
import com.bastion.app.security.SecurityManager
import com.bastion.app.ui.components.CreateCategoryDialog
import com.bastion.app.ui.components.CreateDialogTarget
import com.bastion.app.ui.components.ExpressiveTopBar
import com.bastion.app.ui.components.M3IdentityVerifyDialog
import com.bastion.app.ui.components.UnifiedCategoryFilterChipMenuDropdown
import com.bastion.app.ui.components.UnifiedCategoryFilterChipMenuOffset
import com.bastion.app.ui.components.UnifiedCategoryFilterSelection
import com.bastion.app.utils.BiometricHelper
import com.bastion.app.utils.KeePassKdbxService
import com.bastion.app.utils.decodeKeePassPathForDisplay
import com.bastion.app.utils.planLocalCategoryMove
import com.bastion.app.viewmodel.CategoryFilter
import com.bastion.app.viewmodel.PasswordViewModel
import com.bastion.app.viewmodel.SettingsViewModel
import com.bastion.app.ui.password.StackCardMode
import com.bastion.app.ui.password.BitwardenClearCacheTopActionsMenuItem
import com.bastion.app.ui.password.BitwardenSyncTopActionsMenuItem
import com.bastion.app.ui.password.CommonPasswordTopActionsMenuItems
import com.bastion.app.ui.password.KeepassRefreshTopActionsMenuItem
import com.bastion.app.ui.password.PasswordTopActionsDropdownMenu

@Composable
internal fun PasswordListTopSection(
    currentFilter: CategoryFilter,
    categories: List<Category>,
    keepassDatabases: List<com.bastion.app.data.LocalKeePassDatabase>,
    bitwardenVaults: List<com.bastion.app.data.bitwarden.BitwardenVault>,
    viewModel: PasswordViewModel,
    localKeePassViewModel: com.bastion.app.viewmodel.LocalKeePassViewModel,
    bitwardenViewModel: BitwardenViewModel,
    selectedBitwardenVaultId: Long?,
    selectedKeePassDatabaseId: Long?,
    isTopBarSyncing: Boolean,
    isArchiveView: Boolean,
    isKeePassDatabaseView: Boolean,
    searchQuery: String,
    isSearchExpanded: Boolean,
    onSearchExpandedChange: (Boolean) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    topActionsMenuExpanded: Boolean,
    onTopActionsMenuExpandedChange: (Boolean) -> Unit,
    showStandaloneSettingsEntry: Boolean,
    onOpenStandaloneSettings: () -> Unit,
    isCategorySheetVisible: Boolean,
    onCategorySheetVisibleChange: (Boolean) -> Unit,
    // 参数保留（调用方链路不动），但当前无消费方：见下方 onActionPillBoundsChanged = null 的说明
    @Suppress("UNUSED_PARAMETER") categoryPillBoundsInWindow: androidx.compose.ui.geometry.Rect?,
    @Suppress("UNUSED_PARAMETER") onCategoryPillBoundsChange: (androidx.compose.ui.geometry.Rect?) -> Unit,
    showDisplayOptionsSheet: Boolean,
    onShowDisplayOptionsSheetChange: (Boolean) -> Unit,
    configuredQuickFilterItems: List<PasswordListQuickFilterItem>,
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
    quickFilterWifi: Boolean = false,
    onQuickFilterWifiChange: (Boolean) -> Unit = {},
    wifiQuickFilterVisible: Boolean = false,
    quickFilterSshKey: Boolean = false,
    onQuickFilterSshKeyChange: (Boolean) -> Unit = {},
    sshKeyQuickFilterVisible: Boolean = false,
    quickFilterBarcode: Boolean = false,
    onQuickFilterBarcodeChange: (Boolean) -> Unit = {},
    barcodeQuickFilterVisible: Boolean = false,
    aggregateSelectedTypes: Set<PasswordPageContentType>,
    aggregateVisibleTypes: List<PasswordPageContentType>,
    onToggleAggregateType: (PasswordPageContentType) -> Unit,
    categoryMenuQuickFolderShortcuts: List<PasswordQuickFolderShortcut>,
    stackCardMode: StackCardMode,
    groupMode: String,
    passwordCardDisplayMode: PasswordCardDisplayMode,
    settingsViewModel: SettingsViewModel,
    context: Context,
    activity: FragmentActivity?,
    biometricHelper: BiometricHelper,
    canUseBiometric: Boolean,
    coroutineScope: CoroutineScope,
    bitwardenRepository: BitwardenRepository,
    securityManager: SecurityManager,
    onRenameCategory: (Category) -> Unit,
    onDeleteCategory: (Category) -> Unit,
    onOpenCommonAccountTemplates: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenTrash: () -> Unit,
    onScanFidoQr: () -> Unit,
    /**
     * 收纳为内建行为：点击标题切换「快捷筛选条」的展开/收起，收起后为密码列表腾出一行空间。
     * 状态由调用方持有（PasswordListContent），因为 chip 横排不在本组件内。
     */
    onTitleClick: (() -> Unit)? = null,
    quickFiltersExpanded: Boolean = true,
    // 通行秘钥 chip：收拢菜单内点击跳转通行秘钥页（不做列表过滤，passkey 存独立 PasskeyEntry 表）。
    onNavigateToPasskeys: (() -> Unit)? = null,
    // 滚动收起状态（0=展开/1=收起，快照式切换）
    scrollCollapseFraction: Float = 0f
) {
    val appSettings by settingsViewModel.settings.collectAsState()
    val isAuthenticated by viewModel.isAuthenticated.collectAsState()

    // 数据库筛选区块的条数统计：基于全量未删除条目按存储来源分组。
    // 口径统一走 resolveOwnership()（与列表归属判定一致）：只挂 vaultId 但尚无具体
    // Bitwarden 绑定（无 cipher/revision/folder 且未本地修改）的条目算 Bastion 本地，
    // 避免"列表里显示在本地、头部却计入 Bitwarden 库"的口径分裂（0 条 vs 1 条）。
    // Conflict（同时挂具体 KeePass + Bitwarden 绑定）不计入任何分项。
    val allPasswordsForStats by viewModel.allPasswordsForUi.collectAsState()
    val storageCounts = remember(allPasswordsForStats) {
        val active = allPasswordsForStats.filter { !it.isDeleted }
        val ownerships = active.map { it.resolveOwnership() }
        PasswordStorageCounts(
            total = active.size,
            bastionLocal = ownerships.count { it is PasswordOwnership.BastionLocal },
            perKeePassDatabase = ownerships
                .mapNotNull { (it as? PasswordOwnership.KeePass)?.databaseId }
                .groupingBy { it }
                .eachCount(),
            perBitwardenVault = ownerships
                .mapNotNull { (it as? PasswordOwnership.Bitwarden)?.vaultId }
                .groupingBy { it }
                .eachCount()
        )
    }
    var showCreateCategoryDialog by remember { mutableStateOf(false) }
    var showReunlockDialog by remember { mutableStateOf(false) }
    var reunlockPassword by remember { mutableStateOf("") }
    var reunlockPasswordError by remember { mutableStateOf(false) }
    var showClearBitwardenCacheDialog by remember { mutableStateOf(false) }
    var clearCacheRiskSummary by remember { mutableStateOf<BitwardenRepository.VaultCacheRiskSummary?>(null) }
    var isBitwardenMaintenanceActionRunning by remember { mutableStateOf(false) }

    val selectedBitwardenVault = selectedBitwardenVaultId?.let { vaultId ->
        bitwardenVaults.find { it.id == vaultId }
    }
    Column {
        val title = when (val filter = currentFilter) {
            is CategoryFilter.All -> "ALL"
            is CategoryFilter.Archived -> stringResource(R.string.archive_page_title)
            is CategoryFilter.Local -> stringResource(R.string.filter_bastion)
            is CategoryFilter.LocalOnly -> stringResource(R.string.filter_local_only)
            is CategoryFilter.Starred -> stringResource(R.string.filter_starred)
            is CategoryFilter.Uncategorized -> stringResource(R.string.filter_uncategorized)
            is CategoryFilter.LocalStarred -> "${stringResource(R.string.filter_bastion)} · ${stringResource(R.string.filter_starred)}"
            is CategoryFilter.LocalUncategorized -> "${stringResource(R.string.filter_bastion)} · ${stringResource(R.string.filter_uncategorized)}"
            is CategoryFilter.Custom -> categories.find { it.id == filter.categoryId }?.name ?: stringResource(R.string.filter_all)
            is CategoryFilter.KeePassDatabase -> keepassDatabases.find { it.id == filter.databaseId }?.name ?: "KeePass"
            is CategoryFilter.KeePassGroupFilter -> decodeKeePassPathForDisplay(filter.groupPath)
            is CategoryFilter.KeePassDatabaseStarred -> "${keepassDatabases.find { it.id == filter.databaseId }?.name ?: "KeePass"} · ${stringResource(R.string.filter_starred)}"
            is CategoryFilter.KeePassDatabaseUncategorized -> "${keepassDatabases.find { it.id == filter.databaseId }?.name ?: "KeePass"} · ${stringResource(R.string.filter_uncategorized)}"
            is CategoryFilter.BitwardenVault -> "Bitwarden"
            is CategoryFilter.BitwardenFolderFilter -> "Bitwarden"
            is CategoryFilter.BitwardenVaultStarred -> "${stringResource(R.string.filter_bitwarden)} · ${stringResource(R.string.filter_starred)}"
            is CategoryFilter.BitwardenVaultUncategorized -> "${stringResource(R.string.filter_bitwarden)} · ${stringResource(R.string.filter_uncategorized)}"
        }

        ExpressiveTopBar(
            title = title,
            searchQuery = searchQuery,
            onSearchQueryChange = onSearchQueryChange,
            isSearchExpanded = isSearchExpanded,
            onSearchExpandedChange = onSearchExpandedChange,
            searchHint = stringResource(R.string.search_passwords_hint),
            // 性能：胶囊 onGloballyPositioned 在顶栏收起/展开动画期间逐帧回调，
            // 写入的 bounds 状态在主函数被读取（订阅）→ 整个列表屏幕逐帧重组，滚动明显掉帧。
            // 该 bounds 目前无任何消费方，停用回调（状态保留备用，不再写入即不会触发重组）。
            onActionPillBoundsChanged = null,
            onTitleClick = onTitleClick,
            titleExpanded = quickFiltersExpanded,
            scrollCollapseFraction = scrollCollapseFraction,
            actions = {
                if (isArchiveView) {
                    IconButton(onClick = { viewModel.closeArchiveView() }) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = stringResource(R.string.nav_passwords_short),
                            tint = LocalContentColor.current
                        )
                    }
                }

                // 搜索：无高亮底色，避免滚动时圆形底遮挡下方条目
                IconButton(
                    onClick = { onSearchExpandedChange(true) },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = stringResource(R.string.search)
                    )
                }

                if (!isArchiveView) {
                    // 文件夹：降权（缩小尺寸）
                    IconButton(
                        onClick = { onCategorySheetVisibleChange(true) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = stringResource(R.string.category),
                            tint = LocalContentColor.current
                        )
                    }
                }

                Box {
                    IconButton(
                        onClick = { onTopActionsMenuExpandedChange(true) },
                        enabled = isAuthenticated
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.more_options),
                            tint = LocalContentColor.current
                        )
                    }
                    if (!isArchiveView && appSettings.categorySelectionUiMode == CategorySelectionUiMode.CHIP_MENU) {
                        UnifiedCategoryFilterChipMenuDropdown(
                            expanded = isCategorySheetVisible,
                            onDismissRequest = { onCategorySheetVisibleChange(false) },
                            offset = UnifiedCategoryFilterChipMenuOffset
                        ) {
                            PasswordListCategoryChipMenu(
                                currentFilter = currentFilter,
                                keepassDatabases = keepassDatabases,
                                bitwardenVaults = bitwardenVaults,
                                configuredQuickFilterItems = configuredQuickFilterItems,
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
                                onNavigateToPasskeys = onNavigateToPasskeys,
                                // 收纳已内建：快捷筛选横排收进标题，文件夹菜单内不再显示快捷筛选区块
                                showQuickFilterSection = false,
                                storageCounts = storageCounts,
                                quickFolderShortcuts = categoryMenuQuickFolderShortcuts,
                                topModulesOrder = appSettings.passwordListTopModulesOrder,
                                onTopModulesOrderChange = settingsViewModel::updatePasswordListTopModulesOrder,
                                onQuickFilterItemsOrderChange = settingsViewModel::updatePasswordListQuickFilterItems,
                                launchAnchorBounds = null,
                                onDismiss = { onCategorySheetVisibleChange(false) },
                                onSelectFilter = viewModel::setCategoryFilter,
                                categories = categories,
                                onCreateCategory = {
                                    onCategorySheetVisibleChange(false)
                                    showCreateCategoryDialog = true
                                },
                                onMoveCategory = { category, targetParentCategoryId ->
                                    runCatchingObserved {
                                        planLocalCategoryMove(
                                            categories = categories,
                                            sourceCategory = category,
                                            targetParentCategory = categories.find { it.id == targetParentCategoryId }
                                        )
                                    }.onSuccess { plan ->
                                        plan.updatedCategories.forEach(viewModel::updateCategory)
                                    }.onFailure { error ->
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.save_failed_with_error, error.message ?: ""),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                },
                                onMoveCategoryToStorageTarget = { category, target ->
                                    when (target) {
                                        is StorageTarget.BastionLocal -> {
                                            runCatchingObserved {
                                                planLocalCategoryMove(
                                                    categories = categories,
                                                    sourceCategory = category,
                                                    targetParentCategory = categories.find { it.id == target.categoryId }
                                                )
                                            }.onSuccess { plan ->
                                                plan.updatedCategories.forEach(viewModel::updateCategory)
                                            }.onFailure { error ->
                                                Toast.makeText(
                                                    context,
                                                    context.getString(R.string.save_failed_with_error, error.message ?: ""),
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }

                                        is StorageTarget.Bitwarden -> {
                                            viewModel.updateCategory(
                                                category.copy(
                                                    bitwardenVaultId = target.vaultId,
                                                    bitwardenFolderId = target.folderId.orEmpty()
                                                )
                                            )
                                        }

                                        is StorageTarget.KeePass -> {
                                            Toast.makeText(
                                                context,
                                                context.getString(
                                                    R.string.save_failed_with_error,
                                                    "当前暂不支持将分类移动到 KeePass 数据库"
                                                ),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                },
                                getBitwardenFolders = viewModel::getBitwardenFolders,
                                getKeePassGroups = localKeePassViewModel::getGroups,
                                onRenameCategory = onRenameCategory,
                                onDeleteCategory = onDeleteCategory
                            )
                        }
                    }
                    PasswordTopActionsDropdownMenu(
                        expanded = topActionsMenuExpanded,
                        onDismissRequest = { onTopActionsMenuExpandedChange(false) }
                    ) {
                            if (isKeePassDatabaseView) {
                                KeepassRefreshTopActionsMenuItem(
                                    onClick = {
                                        onTopActionsMenuExpandedChange(false)
                                        viewModel.refreshKeePassFromSourceForCurrentContext()
                                    }
                                )
                            }
                            if (selectedBitwardenVaultId != null) {
                                BitwardenSyncTopActionsMenuItem(
                                    isSyncing = isTopBarSyncing,
                                    enabled = !isTopBarSyncing && !isBitwardenMaintenanceActionRunning,
                                    onClick = {
                                        val vaultId = selectedBitwardenVaultId
                                        if (!isTopBarSyncing && !isBitwardenMaintenanceActionRunning && vaultId != null) {
                                            onTopActionsMenuExpandedChange(false)
                                            bitwardenViewModel.requestManualSync(vaultId)
                                        }
                                    },
                                )
                            }
                            if (selectedBitwardenVaultId != null) {
                                // A1：Bitwarden 锁/解锁入口已移除（统一依赖 Bastion app 锁）
                                BitwardenClearCacheTopActionsMenuItem(
                                    enabled = !isBitwardenMaintenanceActionRunning,
                                    onClick = {
                                        val vaultId = selectedBitwardenVaultId
                                        if (vaultId != null) {
                                            onTopActionsMenuExpandedChange(false)
                                            coroutineScope.launch {
                                                runCatchingObserved {
                                                    viewModel.getBitwardenVaultCacheRiskSummary(vaultId)
                                                }.onSuccess { summary ->
                                                    clearCacheRiskSummary = summary
                                                    showClearBitwardenCacheDialog = true
                                                }.onFailure { error ->
                                                    Toast.makeText(
                                                        context,
                                                        context.getString(
                                                            R.string.bitwarden_clear_cache_failed,
                                                            error.message ?: ""
                                                        ),
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }
                                        }
                                    },
                                )
                            }
                            CommonPasswordTopActionsMenuItems(
                                onDismissMenu = { onTopActionsMenuExpandedChange(false) },
                                onShowDisplayOptions = { onShowDisplayOptionsSheetChange(true) },
                                onOpenCommonAccountTemplates = onOpenCommonAccountTemplates,
                                onOpenHistory = onOpenHistory,
                                onOpenTrash = onOpenTrash,
                                onOpenArchive = viewModel::openArchiveView,
                                showSettingsEntry = showStandaloneSettingsEntry,
                                onOpenSettings = onOpenStandaloneSettings,
                                onScanFidoQr = onScanFidoQr
                            )
                        }
                }
            }
        )


        if (showClearBitwardenCacheDialog && selectedBitwardenVaultId != null && clearCacheRiskSummary != null) {
            val vaultId = selectedBitwardenVaultId
            val riskSummary = clearCacheRiskSummary!!
            val hasRisk = riskSummary.hasRisk
            val resetDialogState: () -> Unit = {
                showClearBitwardenCacheDialog = false
                clearCacheRiskSummary = null
            }

            AlertDialog(
                onDismissRequest = {
                    if (!isBitwardenMaintenanceActionRunning) {
                        resetDialogState()
                    }
                },
                title = { Text(stringResource(R.string.bitwarden_clear_cache_confirm_title)) },
                text = {
                    Text(
                        if (hasRisk) {
                            context.getString(
                                R.string.bitwarden_clear_cache_confirm_message_with_risk,
                                riskSummary.pendingOperationCount,
                                riskSummary.passwordLocalModifiedCount,
                                riskSummary.secureItemLocalModifiedCount,
                                riskSummary.unresolvedConflictCount
                            )
                        } else {
                            context.getString(R.string.bitwarden_clear_cache_confirm_message)
                        }
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = !isBitwardenMaintenanceActionRunning,
                        onClick = {
                            coroutineScope.launch {
                                isBitwardenMaintenanceActionRunning = true
                                runCatchingObserved {
                                    viewModel.clearBitwardenVaultLocalCache(
                                        vaultId = vaultId,
                                        mode = if (hasRisk) {
                                            BitwardenRepository.CacheClearMode.SAFE_ONLY_SYNCED
                                        } else {
                                            BitwardenRepository.CacheClearMode.FULL_FORCE
                                        }
                                    )
                                }.onSuccess { result ->
                                    val message = if (hasRisk) {
                                        context.getString(
                                            R.string.bitwarden_clear_cache_success_safe,
                                            result.totalClearedCount,
                                            result.protectedCipherCount
                                        )
                                    } else {
                                        context.getString(
                                            R.string.bitwarden_clear_cache_success,
                                            result.totalClearedCount
                                        )
                                    }
                                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                    resetDialogState()
                                }.onFailure { error ->
                                    Toast.makeText(
                                        context,
                                        context.getString(
                                            R.string.bitwarden_clear_cache_failed,
                                            error.message ?: ""
                                        ),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                isBitwardenMaintenanceActionRunning = false
                            }
                        }
                    ) {
                        Text(
                            stringResource(
                                if (hasRisk) R.string.bitwarden_clear_cache_action_safe
                                else R.string.bitwarden_clear_cache_action
                            )
                        )
                    }
                },
                dismissButton = {
                    Row {
                                if (hasRisk) {
                                    TextButton(
                                        enabled = !isBitwardenMaintenanceActionRunning,
                                        onClick = {
                                            coroutineScope.launch {
                                                isBitwardenMaintenanceActionRunning = true
                                                runCatchingObserved {
                                                    viewModel.clearBitwardenVaultLocalCache(
                                                        vaultId = vaultId,
                                                        mode = BitwardenRepository.CacheClearMode.FULL_FORCE
                                                    )
                                                }.onSuccess { result ->
                                                    Toast.makeText(
                                                context,
                                                context.getString(
                                                    R.string.bitwarden_clear_cache_force_success,
                                                    result.totalClearedCount
                                                ),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            resetDialogState()
                                        }.onFailure { error ->
                                            Toast.makeText(
                                                context,
                                                context.getString(
                                                    R.string.bitwarden_clear_cache_failed,
                                                    error.message ?: ""
                                                ),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                        isBitwardenMaintenanceActionRunning = false
                                    }
                                }
                            ) {
                                Text(stringResource(R.string.bitwarden_clear_cache_action_force))
                            }
                        }
                        TextButton(
                            enabled = !isBitwardenMaintenanceActionRunning,
                            onClick = { resetDialogState() }
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                }
            )
        }

        if (showReunlockDialog) {
            val dismissReunlockDialog: () -> Unit = {
                showReunlockDialog = false
                reunlockPassword = ""
                reunlockPasswordError = false
            }
            val biometricAction = if (activity != null && canUseBiometric) {
                {
                    biometricHelper.authenticate(
                        activity = activity,
                        title = context.getString(R.string.verify_identity),
                        subtitle = context.getString(R.string.reunlock_current_database_menu),
                        onSuccess = {
                            val unlocked = runCatchingObserved {
                                securityManager.unlockVaultWithBiometric()
                            }.getOrDefault(false)
                            if (unlocked) {
                                securityManager.markVaultAuthenticated()
                                viewModel.restoreAuthenticatedUiState()
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.current_database_unlocked),
                                    Toast.LENGTH_SHORT
                                ).show()
                                dismissReunlockDialog()
                            } else {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.reunlock_current_database_failed),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onError = { error ->
                            Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                        },
                        onFailed = {}
                    )
                }
            } else {
                null
            }

            M3IdentityVerifyDialog(
                title = stringResource(R.string.verify_identity),
                message = stringResource(R.string.reunlock_current_database_message),
                passwordValue = reunlockPassword,
                onPasswordChange = {
                    reunlockPassword = it
                    reunlockPasswordError = false
                },
                onDismiss = dismissReunlockDialog,
                onConfirm = {
                    val unlocked = securityManager.unlockVaultWithPassword(reunlockPassword)
                    if (unlocked) {
                        securityManager.markVaultAuthenticated()
                        viewModel.restoreAuthenticatedUiState()
                        Toast.makeText(
                            context,
                            context.getString(R.string.current_database_unlocked),
                            Toast.LENGTH_SHORT
                        ).show()
                        dismissReunlockDialog()
                    } else {
                        reunlockPasswordError = true
                    }
                },
                confirmText = stringResource(R.string.unlock),
                destructiveConfirm = false,
                isPasswordError = reunlockPasswordError,
                passwordErrorText = stringResource(R.string.current_password_incorrect),
                onBiometricClick = biometricAction,
                biometricHintText = if (biometricAction == null) {
                    context.getString(R.string.biometric_not_available)
                } else {
                    null
                }
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        val unifiedSelectedFilter = when (val filter = currentFilter) {
            is CategoryFilter.All -> UnifiedCategoryFilterSelection.All
            is CategoryFilter.Archived -> UnifiedCategoryFilterSelection.All
            is CategoryFilter.Local -> UnifiedCategoryFilterSelection.Local
            is CategoryFilter.LocalOnly -> UnifiedCategoryFilterSelection.Local
            is CategoryFilter.Starred -> UnifiedCategoryFilterSelection.Starred
            is CategoryFilter.Uncategorized -> UnifiedCategoryFilterSelection.Uncategorized
            is CategoryFilter.LocalStarred -> UnifiedCategoryFilterSelection.LocalStarred
            is CategoryFilter.LocalUncategorized -> UnifiedCategoryFilterSelection.LocalUncategorized
            is CategoryFilter.Custom -> UnifiedCategoryFilterSelection.Custom(filter.categoryId)
            is CategoryFilter.BitwardenVault -> UnifiedCategoryFilterSelection.BitwardenVaultFilter(filter.vaultId)
            is CategoryFilter.BitwardenFolderFilter -> UnifiedCategoryFilterSelection.BitwardenFolderFilter(filter.vaultId, filter.folderId)
            is CategoryFilter.BitwardenVaultStarred -> UnifiedCategoryFilterSelection.BitwardenVaultStarredFilter(filter.vaultId)
            is CategoryFilter.BitwardenVaultUncategorized -> UnifiedCategoryFilterSelection.BitwardenVaultUncategorizedFilter(filter.vaultId)
            is CategoryFilter.KeePassDatabase -> UnifiedCategoryFilterSelection.KeePassDatabaseFilter(filter.databaseId)
            is CategoryFilter.KeePassGroupFilter -> UnifiedCategoryFilterSelection.KeePassGroupFilter(filter.databaseId, filter.groupPath)
            is CategoryFilter.KeePassDatabaseStarred -> UnifiedCategoryFilterSelection.KeePassDatabaseStarredFilter(filter.databaseId)
            is CategoryFilter.KeePassDatabaseUncategorized -> UnifiedCategoryFilterSelection.KeePassDatabaseUncategorizedFilter(filter.databaseId)
        }
        if (showDisplayOptionsSheet) {
            PasswordDisplayOptionsSheet(
                stackCardMode = stackCardMode,
                groupMode = groupMode,
                passwordCardDisplayMode = passwordCardDisplayMode,
                onDismiss = { onShowDisplayOptionsSheetChange(false) },
                onStackCardModeSelected = { mode ->
                    settingsViewModel.updateStackCardMode(mode.name)
                },
                onGroupModeSelected = { modeKey ->
                    settingsViewModel.updatePasswordGroupMode(modeKey)
                },
                onPasswordCardDisplayModeSelected = { mode ->
                    settingsViewModel.updatePasswordCardDisplayMode(mode)
                }
            )
        }

        if (showCreateCategoryDialog) {
            val initialLocalParentPath = (currentFilter as? CategoryFilter.Custom)?.let { filter ->
                categories.firstOrNull { it.id == filter.categoryId }?.name
            }
            val (initialDialogTarget, initialDialogKeePassDbId, initialDialogBitwardenVaultId) = remember(currentFilter) {
                when (currentFilter) {
                    is CategoryFilter.KeePassDatabase,
                    is CategoryFilter.KeePassGroupFilter,
                    is CategoryFilter.KeePassDatabaseStarred,
                    is CategoryFilter.KeePassDatabaseUncategorized -> {
                        val dbId = when (currentFilter) {
                            is CategoryFilter.KeePassDatabase -> currentFilter.databaseId
                            is CategoryFilter.KeePassGroupFilter -> currentFilter.databaseId
                            is CategoryFilter.KeePassDatabaseStarred -> currentFilter.databaseId
                            is CategoryFilter.KeePassDatabaseUncategorized -> currentFilter.databaseId
                            else -> null
                        }
                        Triple(CreateDialogTarget.KeePass, dbId, null)
                    }
                    is CategoryFilter.BitwardenVault,
                    is CategoryFilter.BitwardenFolderFilter,
                    is CategoryFilter.BitwardenVaultStarred,
                    is CategoryFilter.BitwardenVaultUncategorized -> {
                        val vaultId = when (currentFilter) {
                            is CategoryFilter.BitwardenVault -> currentFilter.vaultId
                            is CategoryFilter.BitwardenFolderFilter -> currentFilter.vaultId
                            is CategoryFilter.BitwardenVaultStarred -> currentFilter.vaultId
                            is CategoryFilter.BitwardenVaultUncategorized -> currentFilter.vaultId
                            else -> null
                        }
                        Triple(CreateDialogTarget.Bitwarden, null, vaultId)
                    }
                    else -> Triple(null, null, null)
                }
            }
            CreateCategoryDialog(
                visible = true,
                onDismiss = { showCreateCategoryDialog = false },
                categories = categories,
                keepassDatabases = keepassDatabases,
                bitwardenVaults = bitwardenVaults,
                getKeePassGroups = localKeePassViewModel::getGroups,
                onCreateCategoryWithName = { name -> viewModel.addCategory(name) },
                initialLocalParentPath = initialLocalParentPath,
                initialTarget = initialDialogTarget,
                initialKeePassDbId = initialDialogKeePassDbId,
                initialBitwardenVaultId = initialDialogBitwardenVaultId,
                onCreateBitwardenFolder = { vaultId, name ->
                    coroutineScope.launch {
                        val result = bitwardenRepository.createFolder(vaultId, name)
                        result.exceptionOrNull()?.let { error ->
                            Toast.makeText(
                                context,
                                context.getString(R.string.webdav_operation_failed, error.message ?: ""),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                },
                onCreateKeePassGroup = { databaseId, parentPath, name ->
                    localKeePassViewModel.createGroup(
                        databaseId = databaseId,
                        groupName = name,
                        parentPath = parentPath
                    ) { result ->
                        result.exceptionOrNull()?.let { error ->
                            Toast.makeText(
                                context,
                                context.getString(R.string.webdav_operation_failed, error.message ?: ""),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            )
        }
    }
}
