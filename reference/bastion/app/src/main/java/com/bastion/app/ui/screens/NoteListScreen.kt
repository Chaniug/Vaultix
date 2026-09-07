@file:Suppress("LocalContextGetResourceValueCall")
package com.bastion.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Note
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import com.bastion.app.data.SecureItem
import com.bastion.app.data.SecureItemOwnership
import com.bastion.app.data.isKeePassOwned
import com.bastion.app.data.PasswordDatabase
import com.bastion.app.data.Category
import com.bastion.app.data.ItemType
import com.bastion.app.data.isLocalOnlyItem
import com.bastion.app.data.resolveOwnership
import com.bastion.app.bitwarden.sync.isUserVisibleSyncInProgress
import com.bastion.app.bitwarden.repository.BitwardenRepository
import com.bastion.app.data.KeePassStorageLocation
import com.bastion.app.data.bitwarden.BitwardenVault
import com.bastion.app.repository.KeePassCompatibilityBridge
import com.bastion.app.repository.KeePassWorkspaceRepository
import com.bastion.app.viewmodel.NoteViewModel
import com.bastion.app.viewmodel.NoteDraftStorageTarget
import com.bastion.app.viewmodel.SettingsViewModel
import java.text.SimpleDateFormat
import com.bastion.app.security.SecurityManager
import com.bastion.app.utils.BiometricHelper
import com.bastion.app.utils.SettingsManager
import com.bastion.app.utils.decodeKeePassPathForDisplay
import com.bastion.app.utils.planLocalCategoryMove
import com.bastion.app.ui.activityViewModel
import com.bastion.app.ui.category.CategoryManagementTrailingContent
import com.bastion.app.ui.category.CategoryManagementCreateDialog
import com.bastion.app.ui.category.rememberCategoryManagementState
import androidx.fragment.app.FragmentActivity
import androidx.compose.ui.res.stringResource
import com.bastion.app.R
import com.bastion.app.ui.components.M3IdentityVerifyDialog
import com.bastion.app.ui.components.ExpressiveTopBar
import com.bastion.app.ui.components.BastionExpressiveFilterChip
import com.bastion.app.ui.components.SyncStatusIcon
import com.bastion.app.ui.components.UnifiedCategoryFilterChipMenu
import com.bastion.app.ui.components.UnifiedCategoryFilterChipMenuDropdown
import com.bastion.app.ui.components.UnifiedCategoryFilterChipMenuOffset
import com.bastion.app.ui.components.UnifiedCategoryFilterSelection
import com.bastion.app.ui.components.UnifiedMoveAction
import com.bastion.app.ui.components.UnifiedMoveCategoryTarget
import com.bastion.app.ui.components.UnifiedMoveToCategoryBottomSheet
import com.bastion.app.ui.components.PullActionVisualState
import com.bastion.app.ui.components.PullGestureIndicator
import com.bastion.app.bitwarden.sync.SyncStatus
import com.bastion.app.notes.domain.NoteContentCodec
import com.bastion.app.notes.ui.model.NoteListItemUiModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.bastion.app.util.VibrationPatterns
import com.bastion.app.utils.SavedCategoryFilterState
import androidx.lifecycle.viewmodel.compose.viewModel
import java.util.Locale
import com.bastion.app.ui.password.PasswordTopActionsDropdownMenu

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun NoteListScreen(
    viewModel: NoteViewModel,
    settingsViewModel: SettingsViewModel,
    onNavigateToAddNote: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    onNavigateToSearchedNote: (Long, String) -> Unit = { noteId, _ -> onNavigateToAddNote(noteId) },
    securityManager: SecurityManager,
    passwordViewModel: com.bastion.app.viewmodel.PasswordViewModel,
    onSelectionModeChange: (Boolean) -> Unit = {},
    showStandaloneSettingsEntry: Boolean = false,
    onOpenStandaloneSettings: () -> Unit = {}
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedTag by remember { mutableStateOf<String?>(null) }
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }
    val settings by settingsViewModel.settings.collectAsState()
    val isGridLayout = settings.noteGridLayout
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedNoteIds by remember { mutableStateOf(setOf<Long>()) }
    var isCategorySheetVisible by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showBatchMoveCategoryDialog by remember { mutableStateOf(false) }
    var showBitwardenImageWarningDialog by remember { mutableStateOf(false) }
    var pendingBitwardenMoveTarget by remember { mutableStateOf<UnifiedMoveCategoryTarget?>(null) }
    var pendingBitwardenMoveAction by remember { mutableStateOf<UnifiedMoveAction?>(null) }
    var bitwardenImageWarningCount by remember { mutableStateOf(0) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var showTopActionsMenu by remember { mutableStateOf(false) }
    var masterPassword by remember { mutableStateOf("") }
    var passwordError by remember { mutableStateOf(false) }
    
    // 防止重复点击
    var isNavigating by remember { mutableStateOf(false) }

    fun collapseSearch() {
        isSearchExpanded = false
        searchQuery = ""
    }

    BackHandler(enabled = isSearchExpanded) {
        collapseSearch()
    }

    LaunchedEffect(isSelectionMode) {
        onSelectionModeChange(isSelectionMode)
    }

    DisposableEffect(Unit) {
        onDispose {
            onSelectionModeChange(false)
        }
    }
    
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val database = remember { PasswordDatabase.getDatabase(context) }
    val settingsManager = remember { SettingsManager(context) }
    val scope = rememberCoroutineScope()
    val categories by database.categoryDao().getAllCategories().collectAsState(initial = emptyList())
    val keepassDatabases by database.localKeePassDatabaseDao().getAllDatabases().collectAsState(initial = emptyList())
    val bitwardenRepository = remember { BitwardenRepository.getInstance(context) }
    val bitwardenVaults by database.bitwardenVaultDao().getAllVaultsFlow().collectAsState(initial = emptyList())
    val keepassBridge = remember {
        KeePassCompatibilityBridge(
            KeePassWorkspaceRepository(
                context,
                database.localKeePassDatabaseDao(),
                securityManager
            )
        )
    }
    val keepassGroupFlows = remember {
        mutableMapOf<Long, kotlinx.coroutines.flow.MutableStateFlow<List<com.bastion.app.utils.KeePassGroupInfo>>>()
    }
    val getKeePassGroups: (Long) -> kotlinx.coroutines.flow.Flow<List<com.bastion.app.utils.KeePassGroupInfo>> = remember {
        { databaseId ->
            val flow = keepassGroupFlows.getOrPut(databaseId) {
                kotlinx.coroutines.flow.MutableStateFlow(emptyList())
            }
            if (flow.value.isEmpty()) {
                scope.launch {
                    flow.value = keepassBridge.listLegacyGroups(databaseId).getOrDefault(emptyList())
                }
            }
            flow
        }
    }
    val biometricHelper = remember { BiometricHelper(context) }
    // 必须缓存：isBiometricAvailable() 内部是 canAuthenticate()，每次调用都要跨进程
    // Binder 打 BiometricService。不缓存的话会在**每次重组**执行一次（实测密码页同款
    // 写法达 81 次/秒），是实打实的耗电点。只在 activity / biometricEnabled 变化时重算。
    val canUseBiometric = remember(activity, settings.biometricEnabled) {
        activity != null && settings.biometricEnabled && biometricHelper.isBiometricAvailable()
    }
    val notes by viewModel.allNotes.collectAsState(initial = emptyList())
    var selectedCategoryFilter by remember { mutableStateOf<NoteCategoryFilter>(NoteCategoryFilter.All) }
    val savedCategoryFilterState by settingsManager
        .categoryFilterStateFlow(SettingsManager.CategoryFilterScope.NOTE)
        .collectAsState(initial = SavedCategoryFilterState())
    var hasRestoredCategoryFilter by remember { mutableStateOf(false) }

    LaunchedEffect(savedCategoryFilterState, hasRestoredCategoryFilter) {
        if (hasRestoredCategoryFilter) return@LaunchedEffect
        selectedCategoryFilter = decodeNoteCategoryFilter(savedCategoryFilterState)
        hasRestoredCategoryFilter = true
    }

    LaunchedEffect(selectedCategoryFilter) {
        viewModel.setDraftStorageTarget(selectedCategoryFilter.toDraftStorageTarget())
        if (hasRestoredCategoryFilter) {
            settingsManager.updateCategoryFilterState(
                scope = SettingsManager.CategoryFilterScope.NOTE,
                state = encodeNoteCategoryFilter(selectedCategoryFilter)
            )
        }
    }

    val resolvedPasswordViewModel = passwordViewModel
    val categoryMgmt = rememberCategoryManagementState()

    val selectedUnifiedFilter = when (val filter = selectedCategoryFilter) {
        NoteCategoryFilter.All -> UnifiedCategoryFilterSelection.All
        NoteCategoryFilter.Local -> UnifiedCategoryFilterSelection.Local
        NoteCategoryFilter.Starred -> UnifiedCategoryFilterSelection.Starred
        NoteCategoryFilter.Uncategorized -> UnifiedCategoryFilterSelection.Uncategorized
        NoteCategoryFilter.LocalStarred -> UnifiedCategoryFilterSelection.LocalStarred
        NoteCategoryFilter.LocalUncategorized -> UnifiedCategoryFilterSelection.LocalUncategorized
        is NoteCategoryFilter.Custom -> UnifiedCategoryFilterSelection.Custom(filter.categoryId)
        is NoteCategoryFilter.BitwardenVault -> UnifiedCategoryFilterSelection.BitwardenVaultFilter(filter.vaultId)
        is NoteCategoryFilter.BitwardenFolderFilter -> UnifiedCategoryFilterSelection.BitwardenFolderFilter(filter.vaultId, filter.folderId)
        is NoteCategoryFilter.BitwardenVaultStarred -> UnifiedCategoryFilterSelection.BitwardenVaultStarredFilter(filter.vaultId)
        is NoteCategoryFilter.BitwardenVaultUncategorized -> UnifiedCategoryFilterSelection.BitwardenVaultUncategorizedFilter(filter.vaultId)
        is NoteCategoryFilter.KeePassDatabase -> UnifiedCategoryFilterSelection.KeePassDatabaseFilter(filter.databaseId)
        is NoteCategoryFilter.KeePassGroupFilter -> UnifiedCategoryFilterSelection.KeePassGroupFilter(filter.databaseId, filter.groupPath)
        is NoteCategoryFilter.KeePassDatabaseStarred -> UnifiedCategoryFilterSelection.KeePassDatabaseStarredFilter(filter.databaseId)
        is NoteCategoryFilter.KeePassDatabaseUncategorized -> UnifiedCategoryFilterSelection.KeePassDatabaseUncategorizedFilter(filter.databaseId)
    }
    val handleCategorySelection: (UnifiedCategoryFilterSelection) -> Unit = { selection ->
        selectedCategoryFilter = when (selection) {
            is UnifiedCategoryFilterSelection.All -> NoteCategoryFilter.All
            is UnifiedCategoryFilterSelection.Local -> NoteCategoryFilter.Local
            is UnifiedCategoryFilterSelection.Starred -> NoteCategoryFilter.Starred
            is UnifiedCategoryFilterSelection.Uncategorized -> NoteCategoryFilter.Uncategorized
            is UnifiedCategoryFilterSelection.LocalStarred -> NoteCategoryFilter.LocalStarred
            is UnifiedCategoryFilterSelection.LocalUncategorized -> NoteCategoryFilter.LocalUncategorized
            is UnifiedCategoryFilterSelection.Custom -> NoteCategoryFilter.Custom(selection.categoryId)
            is UnifiedCategoryFilterSelection.BitwardenVaultFilter -> NoteCategoryFilter.BitwardenVault(selection.vaultId)
            is UnifiedCategoryFilterSelection.BitwardenFolderFilter -> NoteCategoryFilter.BitwardenFolderFilter(selection.folderId, selection.vaultId)
            is UnifiedCategoryFilterSelection.BitwardenVaultStarredFilter -> NoteCategoryFilter.BitwardenVaultStarred(selection.vaultId)
            is UnifiedCategoryFilterSelection.BitwardenVaultUncategorizedFilter -> NoteCategoryFilter.BitwardenVaultUncategorized(selection.vaultId)
            is UnifiedCategoryFilterSelection.KeePassDatabaseFilter -> NoteCategoryFilter.KeePassDatabase(selection.databaseId)
            is UnifiedCategoryFilterSelection.KeePassGroupFilter -> NoteCategoryFilter.KeePassGroupFilter(selection.databaseId, selection.groupPath)
        is UnifiedCategoryFilterSelection.KeePassDatabaseStarredFilter -> NoteCategoryFilter.KeePassDatabaseStarred(selection.databaseId)
        is UnifiedCategoryFilterSelection.KeePassDatabaseUncategorizedFilter -> NoteCategoryFilter.KeePassDatabaseUncategorized(selection.databaseId)
    }
        when (selection) {
            is UnifiedCategoryFilterSelection.KeePassDatabaseFilter -> viewModel.syncKeePassNotes(selection.databaseId)
            is UnifiedCategoryFilterSelection.KeePassGroupFilter -> viewModel.syncKeePassNotes(selection.databaseId)
            else -> Unit
        }
    }

    val title = when (val filter = selectedCategoryFilter) {
        NoteCategoryFilter.All -> stringResource(R.string.filter_all)
        NoteCategoryFilter.Local -> stringResource(R.string.filter_bastion)
        NoteCategoryFilter.Starred -> stringResource(R.string.filter_starred)
        NoteCategoryFilter.Uncategorized -> stringResource(R.string.filter_uncategorized)
        NoteCategoryFilter.LocalStarred -> "${stringResource(R.string.filter_bastion)} · ${stringResource(R.string.filter_starred)}"
        NoteCategoryFilter.LocalUncategorized -> "${stringResource(R.string.filter_bastion)} · ${stringResource(R.string.filter_uncategorized)}"
        is NoteCategoryFilter.Custom -> categories.find { it.id == filter.categoryId }?.name
            ?: stringResource(R.string.unknown_category)
        is NoteCategoryFilter.BitwardenVault -> "Bitwarden"
        is NoteCategoryFilter.BitwardenFolderFilter -> "Bitwarden"
        is NoteCategoryFilter.BitwardenVaultStarred -> "${stringResource(R.string.filter_bitwarden)} · ${stringResource(R.string.filter_starred)}"
        is NoteCategoryFilter.BitwardenVaultUncategorized -> "${stringResource(R.string.filter_bitwarden)} · ${stringResource(R.string.filter_uncategorized)}"
        is NoteCategoryFilter.KeePassDatabase -> keepassDatabases.find { it.id == filter.databaseId }?.name ?: "KeePass"
        is NoteCategoryFilter.KeePassGroupFilter -> decodeKeePassPathForDisplay(filter.groupPath)
        is NoteCategoryFilter.KeePassDatabaseStarred -> "${keepassDatabases.find { it.id == filter.databaseId }?.name ?: "KeePass"} · ${stringResource(R.string.filter_starred)}"
        is NoteCategoryFilter.KeePassDatabaseUncategorized -> "${keepassDatabases.find { it.id == filter.databaseId }?.name ?: "KeePass"} · ${stringResource(R.string.filter_uncategorized)}"
    }
    // Notes keep pull-to-search; disable pull-to-sync on Bitwarden filters.
    val isBitwardenDatabaseView = false && when (selectedCategoryFilter) {
        is NoteCategoryFilter.BitwardenVault,
        is NoteCategoryFilter.BitwardenFolderFilter,
        is NoteCategoryFilter.BitwardenVaultStarred,
        is NoteCategoryFilter.BitwardenVaultUncategorized -> true
        else -> false
    }
    val selectedBitwardenVaultId = when (val filter = selectedCategoryFilter) {
        is NoteCategoryFilter.BitwardenVault -> filter.vaultId
        is NoteCategoryFilter.BitwardenFolderFilter -> filter.vaultId
        is NoteCategoryFilter.BitwardenVaultStarred -> filter.vaultId
        is NoteCategoryFilter.BitwardenVaultUncategorized -> filter.vaultId
        else -> null
    }
    val selectedKeePassDatabaseId = when (val filter = selectedCategoryFilter) {
        is NoteCategoryFilter.KeePassDatabase -> filter.databaseId
        is NoteCategoryFilter.KeePassGroupFilter -> filter.databaseId
        is NoteCategoryFilter.KeePassDatabaseStarred -> filter.databaseId
        is NoteCategoryFilter.KeePassDatabaseUncategorized -> filter.databaseId
        else -> null
    }
    // Activity 级共享实例：默认的 viewModel() 会按 NavBackStackEntry 各建一个，
    // 导致 BitwardenViewModel 出现多个副本（详见 activityViewModel 文档）。
    val bitwardenViewModel: com.bastion.app.bitwarden.viewmodel.BitwardenViewModel = activityViewModel()
    val bitwardenSyncStatusByVault by bitwardenViewModel.syncStatusByVault.collectAsState()
    val isTopBarSyncing = selectedBitwardenVaultId?.let { vaultId ->
        bitwardenSyncStatusByVault[vaultId].isUserVisibleSyncInProgress()
    } == true
    
    // 过滤笔记
    val filteredNotes = remember(notes, searchQuery, selectedCategoryFilter, selectedTag) {
        val categoryFiltered = filterNotesByCategory(notes, selectedCategoryFilter)
        val searchFiltered = if (searchQuery.isBlank()) {
            categoryFiltered
        } else {
            categoryFiltered.filter { item ->
                val decoded = NoteContentCodec.decodeFromItem(item)
                item.title.contains(searchQuery, ignoreCase = true) ||
                    decoded.content.contains(searchQuery, ignoreCase = true) ||
                    decoded.tags.any { tag -> tag.contains(searchQuery, ignoreCase = true) }
            }
        }
        if (selectedTag.isNullOrBlank()) {
            searchFiltered
        } else {
            searchFiltered.filter { item ->
                val decoded = NoteContentCodec.decodeFromItem(item)
                decoded.tags.any { tag -> tag.equals(selectedTag, ignoreCase = true) }
            }
        }
    }
    val availableTags = remember(notes, selectedCategoryFilter) {
        val categoryFiltered = filterNotesByCategory(notes, selectedCategoryFilter)
        categoryFiltered
            .flatMap { NoteContentCodec.decodeFromItem(it).tags }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedBy { it.lowercase(Locale.getDefault()) }
    }
    LaunchedEffect(availableTags, selectedTag) {
        if (selectedTag != null && selectedTag !in availableTags) {
            selectedTag = null
        }
    }
    val filteredNoteUiItems = remember(filteredNotes) {
        filteredNotes.map { it.toNoteListItemUiModel() }
    }

    // 删除实际执行
    fun performDelete() {
        val notesToDelete = notes.filter { it.id in selectedNoteIds }
        viewModel.deleteNotes(notesToDelete)
        isSelectionMode = false
        selectedNoteIds = emptySet()
        showDeleteDialog = false
        showPasswordDialog = false
        masterPassword = ""
        passwordError = false
    }

    fun performBatchMove(target: UnifiedMoveCategoryTarget, action: UnifiedMoveAction) {
        scope.launch {
            val selectedItems = notes.filter { selectedNoteIds.contains(it.id) }
            var movedCount = 0
            var failedCount = 0

            selectedItems.forEach { item ->
                val targetCategoryId = when (target) {
                    UnifiedMoveCategoryTarget.Uncategorized -> null
                    is UnifiedMoveCategoryTarget.BastionCategory -> target.categoryId
                    else -> item.categoryId
                }
                val targetKeepassDatabaseId = when (target) {
                    UnifiedMoveCategoryTarget.Uncategorized -> null
                    is UnifiedMoveCategoryTarget.BastionCategory -> null
                    is UnifiedMoveCategoryTarget.BitwardenVaultTarget -> null
                    is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> null
                    is UnifiedMoveCategoryTarget.KeePassDatabaseTarget -> target.databaseId
                    is UnifiedMoveCategoryTarget.KeePassGroupTarget -> target.databaseId
                }
                val targetKeepassGroupPath = when (target) {
                    is UnifiedMoveCategoryTarget.KeePassGroupTarget -> target.groupPath
                    is UnifiedMoveCategoryTarget.KeePassDatabaseTarget -> null
                    else -> null
                }
                val targetBitwardenVaultId = when (target) {
                    is UnifiedMoveCategoryTarget.BitwardenVaultTarget -> target.vaultId
                    is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> target.vaultId
                    else -> null
                }
                val targetBitwardenFolderId = when (target) {
                    is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> target.folderId
                    else -> null
                }

                if (action == UnifiedMoveAction.COPY) {
                    if (target is UnifiedMoveCategoryTarget.BastionCategory || target == UnifiedMoveCategoryTarget.Uncategorized) {
                        if (viewModel.copyNoteToBastionLocal(item, targetCategoryId) != null) {
                            movedCount++
                        } else {
                            failedCount++
                        }
                    } else {
                        val decodedNote = NoteContentCodec.decodeFromItem(item)
                        viewModel.addNote(
                            content = decodedNote.content,
                            title = item.title,
                            tags = decodedNote.tags,
                            isMarkdown = decodedNote.isMarkdown,
                            isFavorite = item.isFavorite,
                            categoryId = targetCategoryId,
                            imagePaths = item.imagePaths,
                            keepassDatabaseId = targetKeepassDatabaseId,
                            keepassGroupPath = targetKeepassGroupPath,
                            bitwardenVaultId = targetBitwardenVaultId,
                            bitwardenFolderId = targetBitwardenFolderId
                        )
                        movedCount++
                    }
                } else {
                    val moved = if (target is UnifiedMoveCategoryTarget.BastionCategory || target == UnifiedMoveCategoryTarget.Uncategorized) {
                        if (item.isLocalOnlyItem()) {
                            viewModel.moveNoteToStorage(
                                item = item,
                                categoryId = targetCategoryId,
                                keepassDatabaseId = null,
                                keepassGroupPath = null,
                                bitwardenVaultId = null,
                                bitwardenFolderId = null
                            )
                        } else {
                            viewModel.moveNoteToBastionLocal(item, targetCategoryId).isSuccess
                        }
                    } else {
                        viewModel.moveNoteToStorage(
                            item = item,
                            categoryId = targetCategoryId,
                            keepassDatabaseId = targetKeepassDatabaseId,
                            keepassGroupPath = targetKeepassGroupPath,
                            bitwardenVaultId = targetBitwardenVaultId,
                            bitwardenFolderId = targetBitwardenFolderId
                        )
                    }
                    if (moved) movedCount++ else failedCount++
                }
            }

            val baseMessage = context.getString(R.string.selected_items, movedCount)
            val toastMessage = if (failedCount > 0) "$baseMessage，失败$failedCount" else baseMessage
            android.widget.Toast.makeText(context, toastMessage, android.widget.Toast.LENGTH_SHORT).show()

            showBatchMoveCategoryDialog = false
            isSelectionMode = false
            selectedNoteIds = emptySet()
        }
    }

    fun shouldWarnBitwardenImageLoss(
        target: UnifiedMoveCategoryTarget,
        selectedItems: List<SecureItem>
    ): Pair<Boolean, Int> {
        val isBitwardenTarget = when (target) {
            is UnifiedMoveCategoryTarget.BitwardenVaultTarget,
            is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> true
            else -> false
        }
        if (!isBitwardenTarget) return false to 0

        val count = selectedItems.count { item ->
            if (item.itemType != ItemType.NOTE) return@count false
            val hasLegacyImagePaths = NoteContentCodec.decodeImagePaths(item.imagePaths).isNotEmpty()
            val hasInlineImages = NoteContentCodec
                .extractInlineImageIds(NoteContentCodec.decodeFromItem(item).content)
                .isNotEmpty()
            hasLegacyImagePaths || hasInlineImages
        }
        return (count > 0) to count
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            // M3E 风格顶栏（保持与其他页面一致）
            ExpressiveTopBar(
                title = title,
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                isSearchExpanded = isSearchExpanded,
                onSearchExpandedChange = { expanded ->
                    if (expanded) {
                        isSearchExpanded = true
                    } else {
                        collapseSearch()
                    }
                },
                searchHint = stringResource(R.string.search),
                // 性能：胶囊 bounds 逐帧回调且无消费方，会触发整屏重组；停用
                onActionPillBoundsChanged = null,
                actions = {
                    if (settings.categorySelectionUiMode == com.bastion.app.data.CategorySelectionUiMode.CHIP_MENU) {
                        IconButton(onClick = { isCategorySheetVisible = true }) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = stringResource(R.string.category),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    } else {
                        IconButton(onClick = { isCategorySheetVisible = true }) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = stringResource(R.string.category),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    // 搜索按钮
                    IconButton(onClick = { isSearchExpanded = true }) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = stringResource(R.string.search),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Box {
                        IconButton(onClick = { showTopActionsMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.more_options),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        if (settings.categorySelectionUiMode == com.bastion.app.data.CategorySelectionUiMode.CHIP_MENU) {
                            UnifiedCategoryFilterChipMenuDropdown(
                                expanded = isCategorySheetVisible,
                                onDismissRequest = { isCategorySheetVisible = false },
                                offset = UnifiedCategoryFilterChipMenuOffset
                            ) {
                                UnifiedCategoryFilterChipMenu(
                                    visible = true,
                                    onDismiss = { isCategorySheetVisible = false },
                                    selected = selectedUnifiedFilter,
                                    onSelect = handleCategorySelection,
                                    categories = categories,
                                    keepassDatabases = keepassDatabases,
                                    bitwardenVaults = bitwardenVaults,
                                    getBitwardenFolders = { vaultId -> database.bitwardenFolderDao().getFoldersByVaultFlow(vaultId) },
                                    getKeePassGroups = getKeePassGroups,
                                    categoryEditMode = categoryMgmt.categoryEditMode,
                                    onRequestCategoryAction = { categoryMgmt.categoryActionTarget = it },
                                    quickFilterContent = {
                                        if (availableTags.isNotEmpty()) {
                                            Text(
                                                text = stringResource(R.string.note_tags),
                                                style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .horizontalScroll(rememberScrollState()),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                BastionExpressiveFilterChip(
                                                    selected = selectedTag == null,
                                                    onClick = { selectedTag = null },
                                                    label = stringResource(R.string.note_all_tags)
                                                )
                                                availableTags.forEach { tag ->
                                                    BastionExpressiveFilterChip(
                                                        selected = selectedTag == tag,
                                                        onClick = {
                                                            selectedTag = if (selectedTag == tag) null else tag
                                                        },
                                                        label = "#$tag"
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    trailingContent = {
                                        CategoryManagementTrailingContent(
                                            state = categoryMgmt,
                                            categories = categories,
                                            keepassDatabases = keepassDatabases,
                                            bitwardenVaults = bitwardenVaults,
                                            getBitwardenFolders = { vaultId -> database.bitwardenFolderDao().getFoldersByVaultFlow(vaultId) },
                                            getKeePassGroups = getKeePassGroups,
                                            passwordViewModel = resolvedPasswordViewModel,
                                            onDismissFilterSheet = { isCategorySheetVisible = false }
                                        )
                                    }
                                )
                            }
                        }
                        PasswordTopActionsDropdownMenu(
                            expanded = showTopActionsMenu,
                            onDismissRequest = { showTopActionsMenu = false }
                        ) {
                            if (showStandaloneSettingsEntry) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.nav_settings)) },
                                    leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                    onClick = {
                                        showTopActionsMenu = false
                                        onOpenStandaloneSettings()
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (isGridLayout) {
                                            stringResource(R.string.switch_to_list)
                                        } else {
                                            stringResource(R.string.switch_to_grid)
                                        }
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (isGridLayout) Icons.Default.ViewList else Icons.Default.GridView,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    showTopActionsMenu = false
                                    settingsViewModel.updateNoteGridLayout(!isGridLayout)
                                }
                            )
                            if (selectedBitwardenVaultId != null) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (isTopBarSyncing) {
                                                "${stringResource(R.string.sync_status_syncing_short)}..."
                                            } else {
                                                stringResource(R.string.sync_bitwarden_database_menu)
                                            }
                                        )
                                    },
                                    leadingIcon = {
                                        if (isTopBarSyncing) {
                                            androidx.compose.material3.CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp
                                            )
                                        } else {
                                            Icon(Icons.Default.Sync, contentDescription = null)
                                        }
                                    },
                                    enabled = !isTopBarSyncing,
                                    onClick = {
                                        if (isTopBarSyncing) return@DropdownMenuItem
                                        val vaultId = selectedBitwardenVaultId
                                        showTopActionsMenu = false
                                        bitwardenViewModel.requestManualSync(vaultId)
                                    }
                                )
                            }
                            if (selectedKeePassDatabaseId != null) {
                                DropdownMenuItem(
                                    text = {
                                        Text("${stringResource(R.string.refresh)} ${stringResource(R.string.filter_keepass)}")
                                    },
                                    leadingIcon = { Icon(Icons.Default.Sync, contentDescription = null) },
                                    onClick = {
                                        showTopActionsMenu = false
                                        viewModel.syncKeePassNotes(selectedKeePassDatabaseId)
                                    }
                                )
                            }
                        }
                    }
                }
            )

        },
        bottomBar = {
            if (isSelectionMode) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    NoteSelectionActionBar(
                        modifier = Modifier.wrapContentWidth(),
                        selectedCount = selectedNoteIds.size,
                        onExit = {
                            isSelectionMode = false
                            selectedNoteIds = emptySet()
                        },
                        onSelectAll = {
                            selectedNoteIds = if (selectedNoteIds.size == filteredNotes.size) {
                                emptySet()
                            } else {
                                filteredNotes.map { it.id }.toSet()
                            }
                        },
                        onMoveToCategory = { showBatchMoveCategoryDialog = true },
                        onDelete = { showDeleteDialog = true }
                    )
                }
            }
        },
        floatingActionButton = {} // FAB moved to SwipeableAddFab in SimpleMainScreen
    ) { paddingValues ->
        // 重置导航状态
        LaunchedEffect(Unit) {
            isNavigating = false
        }

        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text(stringResource(R.string.delete)) },
                text = { Text(stringResource(R.string.notes_delete_selected_confirm, selectedNoteIds.size)) },
                confirmButton = {
                    TextButton(onClick = { 
                        showDeleteDialog = false
                        showPasswordDialog = true
                    }) {
                        Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        if (showPasswordDialog) {
            val biometricAction = if (activity != null && canUseBiometric) {
                {
                    biometricHelper.authenticate(
                        activity = activity,
                        title = context.getString(R.string.verify_identity),
                        subtitle = context.getString(R.string.verify_to_delete),
                        onSuccess = { performDelete() },
                        onError = { error ->
                            android.widget.Toast.makeText(
                                context,
                                error,
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        },
                        onFailed = {}
                    )
                }
            } else {
                null
            }
            M3IdentityVerifyDialog(
                title = stringResource(R.string.verify_identity),
                message = stringResource(R.string.notes_delete_selected_confirm, selectedNoteIds.size),
                passwordValue = masterPassword,
                onPasswordChange = {
                    masterPassword = it
                    passwordError = false
                },
                onDismiss = {
                    showPasswordDialog = false
                    masterPassword = ""
                    passwordError = false
                },
                onConfirm = {
                    if (securityManager.verifyMasterPassword(masterPassword)) {
                        performDelete()
                    } else {
                        passwordError = true
                    }
                },
                confirmText = stringResource(R.string.delete),
                destructiveConfirm = true,
                isPasswordError = passwordError,
                passwordErrorText = stringResource(R.string.current_password_incorrect),
                onBiometricClick = biometricAction,
                biometricHintText = if (biometricAction == null) {
                    context.getString(R.string.biometric_not_available)
                } else {
                    null
                }
            )
        }

        UnifiedMoveToCategoryBottomSheet(
            visible = showBatchMoveCategoryDialog,
            onDismiss = { showBatchMoveCategoryDialog = false },
            categories = categories,
            keepassDatabases = keepassDatabases,
            bitwardenVaults = bitwardenVaults,
            getBitwardenFolders = { vaultId -> database.bitwardenFolderDao().getFoldersByVaultFlow(vaultId) },
            getKeePassGroups = getKeePassGroups,
            allowCopy = true,
            allowMove = notes.filter { selectedNoteIds.contains(it.id) }.none { it.isKeePassOwned() },
            onTargetSelected = { target, action ->
                val selectedItems = notes.filter { selectedNoteIds.contains(it.id) }
                val effectiveAction = if (action == UnifiedMoveAction.MOVE && selectedItems.any { it.isKeePassOwned() }) {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.keepass_copy_only_hint),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    UnifiedMoveAction.COPY
                } else {
                    action
                }
                val (shouldWarn, count) = shouldWarnBitwardenImageLoss(target, selectedItems)
                if (shouldWarn) {
                    showBatchMoveCategoryDialog = false
                    pendingBitwardenMoveTarget = target
                    pendingBitwardenMoveAction = effectiveAction
                    bitwardenImageWarningCount = count
                    showBitwardenImageWarningDialog = true
                } else {
                    performBatchMove(target, effectiveAction)
                }
            }
        )

        if (showBitwardenImageWarningDialog) {
            AlertDialog(
                onDismissRequest = {
                    showBitwardenImageWarningDialog = false
                    pendingBitwardenMoveTarget = null
                    pendingBitwardenMoveAction = null
                    bitwardenImageWarningCount = 0
                },
                title = { Text(stringResource(R.string.note_bitwarden_move_warning_title)) },
                text = {
                    Text(
                        stringResource(
                            R.string.note_bitwarden_move_warning_message,
                            bitwardenImageWarningCount
                        )
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val target = pendingBitwardenMoveTarget
                            val action = pendingBitwardenMoveAction
                            showBitwardenImageWarningDialog = false
                            pendingBitwardenMoveTarget = null
                            pendingBitwardenMoveAction = null
                            bitwardenImageWarningCount = 0
                            if (target != null && action != null) {
                                performBatchMove(target, action)
                            }
                        }
                    ) {
                        Text(stringResource(R.string.continue_action))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showBitwardenImageWarningDialog = false
                            pendingBitwardenMoveTarget = null
                            pendingBitwardenMoveAction = null
                            bitwardenImageWarningCount = 0
                        }
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }

        NoteListContent(
            notes = filteredNoteUiItems,
            isGridLayout = isGridLayout,
            isSearchExpanded = isSearchExpanded,
            onRequestExpandSearch = { isSearchExpanded = true },
            isBitwardenDatabaseView = isBitwardenDatabaseView,
            bitwardenRepository = bitwardenRepository,
            selectedNoteIds = selectedNoteIds,
            onNoteClick = { noteId ->
                if (isSelectionMode) {
                    selectedNoteIds = if (selectedNoteIds.contains(noteId)) {
                        selectedNoteIds - noteId
                    } else {
                        selectedNoteIds + noteId
                    }
                    if (selectedNoteIds.isEmpty()) {
                        isSelectionMode = false
                    }
                } else {
                    if (!isNavigating) {
                        isNavigating = true
                        val activeSearchQuery = searchQuery.trim()
                        if (activeSearchQuery.isNotBlank()) {
                            onNavigateToSearchedNote(noteId, activeSearchQuery)
                        } else {
                            onNavigateToAddNote(noteId)
                        }
                        scope.launch {
                            delay(600)
                            isNavigating = false
                        }
                    }
                }
            },
            onNoteLongClick = { noteId ->
                if (!isSelectionMode) {
                    isSelectionMode = true
                    selectedNoteIds = setOf(noteId)
                }
            },
            modifier = Modifier.padding(paddingValues)
        )
    }

    CategoryManagementCreateDialog(
        state = categoryMgmt,
        currentFilter = selectedUnifiedFilter,
        categories = categories,
        keepassDatabases = keepassDatabases,
        bitwardenVaults = bitwardenVaults,
        getKeePassGroups = getKeePassGroups,
        passwordViewModel = resolvedPasswordViewModel,
        bitwardenRepository = bitwardenRepository,
        keepassBridge = keepassBridge,
        scope = scope
    )
}

private fun filterNotesByCategory(
    notes: List<SecureItem>,
    filter: NoteCategoryFilter
): List<SecureItem> {
    return when (filter) {
        NoteCategoryFilter.All -> notes
        NoteCategoryFilter.Local -> notes.filter { it.isLocalOnlyItem() }
        NoteCategoryFilter.Starred -> notes.filter { it.isFavorite }
        NoteCategoryFilter.Uncategorized -> notes.filter { it.categoryId == null }
        NoteCategoryFilter.LocalStarred -> notes.filter { it.isLocalOnlyItem() && it.isFavorite }
        NoteCategoryFilter.LocalUncategorized -> notes.filter { it.isLocalOnlyItem() && it.categoryId == null }
        is NoteCategoryFilter.Custom -> notes.filter { it.categoryId == filter.categoryId && it.isLocalOnlyItem() }
        is NoteCategoryFilter.BitwardenVault -> notes.filter {
            (it.resolveOwnership() as? SecureItemOwnership.Bitwarden)?.vaultId == filter.vaultId
        }
        is NoteCategoryFilter.BitwardenFolderFilter -> notes.filter {
            val ownership = it.resolveOwnership() as? SecureItemOwnership.Bitwarden
            ownership?.vaultId == filter.vaultId && it.bitwardenFolderId == filter.folderId
        }
        is NoteCategoryFilter.BitwardenVaultStarred -> notes.filter {
            (it.resolveOwnership() as? SecureItemOwnership.Bitwarden)?.vaultId == filter.vaultId && it.isFavorite
        }
        is NoteCategoryFilter.BitwardenVaultUncategorized -> notes.filter {
            (it.resolveOwnership() as? SecureItemOwnership.Bitwarden)?.vaultId == filter.vaultId &&
                it.bitwardenFolderId == null
        }
        is NoteCategoryFilter.KeePassDatabase -> notes.filter {
            (it.resolveOwnership() as? SecureItemOwnership.KeePass)?.databaseId == filter.databaseId
        }
        is NoteCategoryFilter.KeePassGroupFilter -> notes.filter {
            (it.resolveOwnership() as? SecureItemOwnership.KeePass)?.databaseId == filter.databaseId &&
                it.keepassGroupPath == filter.groupPath
        }
        is NoteCategoryFilter.KeePassDatabaseStarred -> notes.filter {
            (it.resolveOwnership() as? SecureItemOwnership.KeePass)?.databaseId == filter.databaseId &&
                it.isFavorite
        }
        is NoteCategoryFilter.KeePassDatabaseUncategorized -> notes.filter {
            (it.resolveOwnership() as? SecureItemOwnership.KeePass)?.databaseId == filter.databaseId &&
                it.keepassGroupPath.isNullOrBlank()
        }
    }
}
