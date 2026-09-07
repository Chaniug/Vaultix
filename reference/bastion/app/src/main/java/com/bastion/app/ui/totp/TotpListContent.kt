@file:Suppress("LocalContextGetResourceValueCall")
package com.bastion.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.fragment.app.FragmentActivity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.bastion.app.R
import com.bastion.app.bitwarden.sync.isUserVisibleSyncInProgress
import com.bastion.app.repository.KeePassCompatibilityBridge
import com.bastion.app.repository.KeePassWorkspaceRepository
import com.bastion.app.data.BottomNavContentTab
import com.bastion.app.data.PasskeyEntry
import com.bastion.app.data.SecureItem
import com.bastion.app.data.isKeePassOwned
import com.bastion.app.data.isLocalOnlyItem
import com.bastion.app.data.model.PasskeyBindingCodec
import com.bastion.app.data.model.TimelineEvent
import com.bastion.app.data.model.TotpData
import com.bastion.app.utils.BiometricHelper
import com.bastion.app.utils.decodeKeePassPathForDisplay
import com.bastion.app.utils.planLocalCategoryMove
import com.bastion.app.ui.category.CategoryManagementTrailingContent
import com.bastion.app.ui.category.CategoryManagementCreateDialog
import com.bastion.app.ui.category.rememberCategoryManagementState
import com.bastion.app.viewmodel.PasswordViewModel
import com.bastion.app.viewmodel.SettingsViewModel
import com.bastion.app.viewmodel.TotpViewModel
import com.bastion.app.viewmodel.CategoryFilter
import com.bastion.app.data.Category
import com.bastion.app.viewmodel.BankCardViewModel
import com.bastion.app.viewmodel.DocumentViewModel
import com.bastion.app.viewmodel.GeneratorViewModel
import com.bastion.app.viewmodel.GeneratorType
import com.bastion.app.viewmodel.NoteViewModel
import com.bastion.app.viewmodel.PasskeyViewModel
import com.bastion.app.viewmodel.TimelineViewModel
import com.bastion.app.ui.screens.SettingsScreen
import com.bastion.app.ui.screens.GeneratorScreen  // 添加生成器页面导入
import com.bastion.app.ui.screens.NoteListScreen
import com.bastion.app.ui.screens.NoteListContent
import com.bastion.app.ui.screens.PasswordDetailScreen
import com.bastion.app.ui.screens.SendScreen
import com.bastion.app.ui.screens.CardWalletScreen
import com.bastion.app.ui.screens.CardWalletTab
import com.bastion.app.ui.screens.BankCardDetailScreen
import com.bastion.app.ui.screens.DocumentDetailScreen
import com.bastion.app.ui.screens.TimelineScreen
import com.bastion.app.ui.screens.PasskeyListScreen
import com.bastion.app.ui.gestures.SwipeActions
import com.bastion.app.ui.haptic.rememberHapticFeedback
import kotlin.math.absoluteValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.bastion.app.ui.components.QrCodeDialog
import com.bastion.app.ui.components.ExpressiveTopBar
import com.bastion.app.ui.components.DraggableBottomNavScaffold
import com.bastion.app.ui.components.SwipeableAddFab
import com.bastion.app.ui.components.DraggableNavItem
import com.bastion.app.ui.components.QuickActionItem
import com.bastion.app.ui.components.QuickAddCallback
import com.bastion.app.ui.components.SyncStatusIcon
import com.bastion.app.ui.components.M3IdentityVerifyDialog
import com.bastion.app.ui.components.UnifiedCategoryFilterChipMenu
import com.bastion.app.ui.components.UnifiedCategoryFilterChipMenuDropdown
import com.bastion.app.ui.components.UnifiedCategoryFilterChipMenuOffset
import com.bastion.app.ui.components.UnifiedCategoryFilterSelection
import com.bastion.app.ui.components.UnifiedMoveAction
import com.bastion.app.ui.components.UnifiedMoveCategoryTarget
import com.bastion.app.ui.components.UnifiedMoveToCategoryBottomSheet
import com.bastion.app.ui.common.dialog.DeleteConfirmDialog
import com.bastion.app.ui.common.layout.DetailPane
import com.bastion.app.ui.common.layout.InspectorRow
import com.bastion.app.ui.common.layout.ListPane
import com.bastion.app.ui.common.pull.rememberPullActionState
import com.bastion.app.ui.common.state.rememberSaveableLazyListState
import com.bastion.app.ui.common.selection.CategoryListItem
import com.bastion.app.ui.common.selection.SelectionActionBar
import com.bastion.app.ui.common.selection.SelectionModeTopBar
import com.bastion.app.ui.main.navigation.BottomNavItem
import com.bastion.app.ui.main.navigation.fullLabelRes
import com.bastion.app.ui.main.navigation.indexToDefaultTabKey
import com.bastion.app.ui.main.navigation.shortLabelRes
import com.bastion.app.ui.main.navigation.toBottomNavItem
import com.bastion.app.ui.main.layout.AdaptiveMainScaffold
import com.bastion.app.ui.password.buildAdditionalInfoPreview
import com.bastion.app.ui.password.MultiPasswordEntryCard
import com.bastion.app.ui.password.PasswordTopActionsDropdownMenu
import com.bastion.app.ui.password.StackedPasswordGroup
import com.bastion.app.ui.password.PasswordEntryCard
import com.bastion.app.ui.password.StackCardMode
import com.bastion.app.ui.password.getGroupKeyForMode
import com.bastion.app.ui.password.getPasswordGroupTitle
import com.bastion.app.ui.password.getPasswordInfoKey
import com.bastion.app.data.bitwarden.BitwardenPendingOperation
import com.bastion.app.data.bitwarden.BitwardenSend
import com.bastion.app.bitwarden.sync.SyncStatus
import com.bastion.app.security.SecurityManager
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import com.bastion.app.ui.screens.AddEditPasswordScreen
import com.bastion.app.ui.screens.AddEditTotpScreen
import com.bastion.app.ui.screens.AddEditBankCardScreen
import com.bastion.app.ui.screens.AddEditDocumentScreen
import com.bastion.app.ui.screens.AddEditNoteScreen
import com.bastion.app.ui.screens.AddEditSendScreen
import com.bastion.app.ui.theme.BastionTheme
import com.bastion.app.util.TotpDataResolver
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TotpListContent(
    viewModel: com.bastion.app.viewmodel.TotpViewModel,
    passwordViewModel: PasswordViewModel,
    onTotpClick: (Long) -> Unit,
    onDeleteTotp: (com.bastion.app.data.SecureItem) -> Unit,
    onQuickScanTotp: () -> Unit,
    onSelectionModeChange: (
        isSelectionMode: Boolean,
        selectedCount: Int,
        onExit: () -> Unit,
        onSelectAll: () -> Unit,
        onMoveToCategory: () -> Unit,
        onDelete: () -> Unit
    ) -> Unit,
    showStandaloneSettingsEntry: Boolean = false,
    onOpenStandaloneSettings: () -> Unit = {},
    // 验证器 → 通行秘钥 的快捷入口。通行秘钥是托管在验证器 tab 下的子页，
    // 与 PasskeyListScreen 的 onNavigateToAuthenticator 构成对称互跳。
    onNavigateToPasskeys: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val securityManager = remember(context) { SecurityManager(context.applicationContext) }
    val bitwardenRepository = remember { com.bastion.app.bitwarden.repository.BitwardenRepository.getInstance(context) }
    val database = remember { com.bastion.app.data.PasswordDatabase.getDatabase(context) }
    val scope = rememberCoroutineScope()
    val keepassDatabases by database.localKeePassDatabaseDao().getAllDatabases().collectAsState(initial = emptyList())
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
    val parsedTotpItems by viewModel.parsedTotpItems.collectAsState()
    val totpItems = remember(parsedTotpItems) { parsedTotpItems.map { it.item } }
    val totpDataById = remember(parsedTotpItems) { parsedTotpItems.associate { it.item.id to it.totpData } }
    val allTotpItemsForStats by viewModel.allTotpItems.collectAsState()
    val duplicateTotpIds by viewModel.duplicateTotpItemIds.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val passwords by passwordViewModel.allPasswords.collectAsState(initial = emptyList())
    val passwordMap = remember(passwords) { passwords.associateBy { it.id } }
    val haptic = rememberHapticFeedback()
    val focusManager = LocalFocusManager.current
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }
    var showTopActionsMenu by remember { mutableStateOf(false) }
    
    // 分类选择状态
    var isCategorySheetVisible by rememberSaveable { mutableStateOf(false) }
    val categories by viewModel.categories.collectAsState()
    val categoryMgmt = rememberCategoryManagementState()
    val currentFilter by viewModel.categoryFilter.collectAsState()
    val totpSelectedFilter = when (val filter = currentFilter) {
        is com.bastion.app.viewmodel.TotpCategoryFilter.All -> UnifiedCategoryFilterSelection.All
        is com.bastion.app.viewmodel.TotpCategoryFilter.Local -> UnifiedCategoryFilterSelection.Local
        is com.bastion.app.viewmodel.TotpCategoryFilter.Starred -> UnifiedCategoryFilterSelection.Starred
        is com.bastion.app.viewmodel.TotpCategoryFilter.Uncategorized -> UnifiedCategoryFilterSelection.Uncategorized
        is com.bastion.app.viewmodel.TotpCategoryFilter.LocalStarred -> UnifiedCategoryFilterSelection.LocalStarred
        is com.bastion.app.viewmodel.TotpCategoryFilter.LocalUncategorized -> UnifiedCategoryFilterSelection.LocalUncategorized
        is com.bastion.app.viewmodel.TotpCategoryFilter.Custom -> UnifiedCategoryFilterSelection.Custom(filter.categoryId)
        is com.bastion.app.viewmodel.TotpCategoryFilter.KeePassDatabase -> UnifiedCategoryFilterSelection.KeePassDatabaseFilter(filter.databaseId)
        is com.bastion.app.viewmodel.TotpCategoryFilter.KeePassGroupFilter -> UnifiedCategoryFilterSelection.KeePassGroupFilter(filter.databaseId, filter.groupPath)
        is com.bastion.app.viewmodel.TotpCategoryFilter.KeePassDatabaseStarred -> UnifiedCategoryFilterSelection.KeePassDatabaseStarredFilter(filter.databaseId)
        is com.bastion.app.viewmodel.TotpCategoryFilter.KeePassDatabaseUncategorized -> UnifiedCategoryFilterSelection.KeePassDatabaseUncategorizedFilter(filter.databaseId)
        is com.bastion.app.viewmodel.TotpCategoryFilter.BitwardenVault -> UnifiedCategoryFilterSelection.BitwardenVaultFilter(filter.vaultId)
        is com.bastion.app.viewmodel.TotpCategoryFilter.BitwardenFolderFilter -> UnifiedCategoryFilterSelection.BitwardenFolderFilter(filter.vaultId, filter.folderId)
        is com.bastion.app.viewmodel.TotpCategoryFilter.BitwardenVaultStarred -> UnifiedCategoryFilterSelection.BitwardenVaultStarredFilter(filter.vaultId)
        is com.bastion.app.viewmodel.TotpCategoryFilter.BitwardenVaultUncategorized -> UnifiedCategoryFilterSelection.BitwardenVaultUncategorizedFilter(filter.vaultId)
    }
    val handleCategorySelection: (UnifiedCategoryFilterSelection) -> Unit = { selection ->
        when (selection) {
            is UnifiedCategoryFilterSelection.All -> viewModel.setCategoryFilter(com.bastion.app.viewmodel.TotpCategoryFilter.All)
            is UnifiedCategoryFilterSelection.Local -> viewModel.setCategoryFilter(com.bastion.app.viewmodel.TotpCategoryFilter.Local)
            is UnifiedCategoryFilterSelection.Starred -> viewModel.setCategoryFilter(com.bastion.app.viewmodel.TotpCategoryFilter.Starred)
            is UnifiedCategoryFilterSelection.Uncategorized -> viewModel.setCategoryFilter(com.bastion.app.viewmodel.TotpCategoryFilter.Uncategorized)
            is UnifiedCategoryFilterSelection.LocalStarred -> viewModel.setCategoryFilter(com.bastion.app.viewmodel.TotpCategoryFilter.LocalStarred)
            is UnifiedCategoryFilterSelection.LocalUncategorized -> viewModel.setCategoryFilter(com.bastion.app.viewmodel.TotpCategoryFilter.LocalUncategorized)
            is UnifiedCategoryFilterSelection.Custom -> viewModel.setCategoryFilter(com.bastion.app.viewmodel.TotpCategoryFilter.Custom(selection.categoryId))
            is UnifiedCategoryFilterSelection.KeePassDatabaseFilter -> viewModel.setCategoryFilter(com.bastion.app.viewmodel.TotpCategoryFilter.KeePassDatabase(selection.databaseId))
            is UnifiedCategoryFilterSelection.KeePassGroupFilter -> viewModel.setCategoryFilter(com.bastion.app.viewmodel.TotpCategoryFilter.KeePassGroupFilter(selection.databaseId, selection.groupPath))
            is UnifiedCategoryFilterSelection.KeePassDatabaseStarredFilter -> viewModel.setCategoryFilter(com.bastion.app.viewmodel.TotpCategoryFilter.KeePassDatabaseStarred(selection.databaseId))
            is UnifiedCategoryFilterSelection.KeePassDatabaseUncategorizedFilter -> viewModel.setCategoryFilter(com.bastion.app.viewmodel.TotpCategoryFilter.KeePassDatabaseUncategorized(selection.databaseId))
            is UnifiedCategoryFilterSelection.BitwardenVaultFilter -> viewModel.setCategoryFilter(com.bastion.app.viewmodel.TotpCategoryFilter.BitwardenVault(selection.vaultId))
            is UnifiedCategoryFilterSelection.BitwardenFolderFilter -> viewModel.setCategoryFilter(com.bastion.app.viewmodel.TotpCategoryFilter.BitwardenFolderFilter(selection.folderId, selection.vaultId))
            is UnifiedCategoryFilterSelection.BitwardenVaultStarredFilter -> viewModel.setCategoryFilter(com.bastion.app.viewmodel.TotpCategoryFilter.BitwardenVaultStarred(selection.vaultId))
            is UnifiedCategoryFilterSelection.BitwardenVaultUncategorizedFilter -> viewModel.setCategoryFilter(com.bastion.app.viewmodel.TotpCategoryFilter.BitwardenVaultUncategorized(selection.vaultId))
        }
    }

    // 如果搜索框展开，按返回键关闭搜索框
    BackHandler(enabled = isSearchExpanded) {
        isSearchExpanded = false
        viewModel.updateSearchQuery("")
        focusManager.clearFocus()
    }


    // Pull-to-search state
    val density = androidx.compose.ui.platform.LocalDensity.current
    val isBitwardenDatabaseView = when (currentFilter) {
        is com.bastion.app.viewmodel.TotpCategoryFilter.BitwardenVault,
        is com.bastion.app.viewmodel.TotpCategoryFilter.BitwardenFolderFilter,
        is com.bastion.app.viewmodel.TotpCategoryFilter.BitwardenVaultStarred,
        is com.bastion.app.viewmodel.TotpCategoryFilter.BitwardenVaultUncategorized -> true
        else -> false
    }
    val selectedBitwardenVaultId = when (val filter = currentFilter) {
        is com.bastion.app.viewmodel.TotpCategoryFilter.BitwardenVault -> filter.vaultId
        is com.bastion.app.viewmodel.TotpCategoryFilter.BitwardenFolderFilter -> filter.vaultId
        is com.bastion.app.viewmodel.TotpCategoryFilter.BitwardenVaultStarred -> filter.vaultId
        is com.bastion.app.viewmodel.TotpCategoryFilter.BitwardenVaultUncategorized -> filter.vaultId
        else -> null
    }
    val selectedKeePassDatabaseId = when (val filter = currentFilter) {
        is com.bastion.app.viewmodel.TotpCategoryFilter.KeePassDatabase -> filter.databaseId
        is com.bastion.app.viewmodel.TotpCategoryFilter.KeePassGroupFilter -> filter.databaseId
        is com.bastion.app.viewmodel.TotpCategoryFilter.KeePassDatabaseStarred -> filter.databaseId
        is com.bastion.app.viewmodel.TotpCategoryFilter.KeePassDatabaseUncategorized -> filter.databaseId
        else -> null
    }
    // Activity 级共享实例：默认的 viewModel() 会按 NavBackStackEntry 各建一个，
    // 导致 BitwardenViewModel 出现多个副本（详见 activityViewModel 文档）。
    val bitwardenViewModel: com.bastion.app.bitwarden.viewmodel.BitwardenViewModel = activityViewModel()
    val bitwardenSyncStatusByVault by bitwardenViewModel.syncStatusByVault.collectAsState()
    val isTopBarSyncing = selectedBitwardenVaultId?.let { vaultId ->
        bitwardenSyncStatusByVault[vaultId].isUserVisibleSyncInProgress()
    } == true
    var isBitwardenTotpRepairing by remember { mutableStateOf(false) }
    // Verifier page uses plain pull-to-search only; disable pull-to-sync UX here.
    val enableBitwardenPullSync = false
    val searchTriggerDistance = remember(density) {
        with(density) { 72.dp.toPx() }
    }
    val syncTriggerDistance = remember(density) { with(density) { 72.dp.toPx() } }
    val maxDragDistance = remember(density) { with(density) { 100.dp.toPx() } }
    val lazyListState = rememberSaveableLazyListState()
    val scrollCollapseThresholdPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        8.dp.toPx()
    }
    val scrollCollapseFraction by remember(lazyListState) {
        derivedStateOf {
            if (lazyListState.firstVisibleItemIndex > 0 ||
                lazyListState.firstVisibleItemScrollOffset > scrollCollapseThresholdPx
            ) 1f else 0f
        }
    }
    val pullAction = rememberPullActionState(
        isBitwardenDatabaseView = enableBitwardenPullSync,
        isSearchExpanded = isSearchExpanded,
        searchTriggerDistance = searchTriggerDistance,
        syncTriggerDistance = syncTriggerDistance,
        maxDragDistance = maxDragDistance,
        bitwardenRepository = bitwardenRepository,
        onSearchTriggered = { isSearchExpanded = true }
    )
    
    // 选择模式状态
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedItems by remember { mutableStateOf(setOf<Long>()) }

    // 多选模式下按返回键：先取消选择（否则手势返回会直接触发"再按一次退出"）
    BackHandler(enabled = isSelectionMode) {
        isSelectionMode = false
        selectedItems = emptySet()
    }

    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var showMoveToCategoryDialog by remember { mutableStateOf(false) }
    var passwordInput by remember { mutableStateOf("") }
    var passwordError by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val settingsManager = remember { com.bastion.app.utils.SettingsManager(context) }
    val appSettings by settingsManager.settingsFlow.collectAsState(initial = com.bastion.app.data.AppSettings())
    val activity = context as? FragmentActivity
    val biometricHelper = remember { BiometricHelper(context) }
    // 必须缓存：isBiometricAvailable() 内部是 canAuthenticate()，每次调用都要跨进程
    // Binder 打 BiometricService。本页有 sharedTickSeconds（TOTP 倒计时，**每秒**更新一次），
    // 不缓存就等于每秒打一次指纹服务，是实打实的耗电点。
    // 只在 activity / biometricEnabled 变化时才重算。
    val canUseBiometric = remember(activity, appSettings.biometricEnabled) {
        activity != null && appSettings.biometricEnabled && biometricHelper.isBiometricAvailable()
    }
    val sharedTickSeconds by produceState(initialValue = System.currentTimeMillis() / 1000) {
        while (true) {
            value = System.currentTimeMillis() / 1000
            delay(1000)
        }
    }
    val sharedProgressTimeMillis = rememberTotpTickerMillis(appSettings.validatorSmoothProgress)
    
    // 添加单项删除对话框状态
    var itemToDelete by remember { mutableStateOf<com.bastion.app.data.SecureItem?>(null) }
    var singleItemPasswordInput by remember { mutableStateOf("") }
    var showSingleItemPasswordVerify by remember { mutableStateOf(false) }
    var pendingBoundSingleDelete by remember { mutableStateOf<SecureItem?>(null) }
    var pendingBoundBatchDelete by remember { mutableStateOf<List<SecureItem>>(emptyList()) }
    
    // 待删除项ID集合（用于隐藏即将删除的项）
    var deletedItemIds by remember { mutableStateOf(setOf<Long>()) }
    
    // QR码显示状态
    var itemToShowQr by remember { mutableStateOf<com.bastion.app.data.SecureItem?>(null) }
    
    // 过滤掉待删除的项
    val filteredTotpItems = remember(totpItems, deletedItemIds) {
        totpItems.filter { it.id !in deletedItemIds }
    }

    fun boundPasswordIdFor(item: SecureItem): Long? {
        return (totpDataById[item.id] ?: viewModel.parseTotpDataForDisplay(item))?.boundPasswordId
    }

    /**
     * 删除一条验证器是否会连带清空「被绑定密码条目」的验证器密钥。
     *
     * 只有「绑定型且自己没有独立 Bitwarden cipher」的条目才会连带影响——此时它是密码条目
     * 验证器的唯一载体，删除它就等于移除该密码条目的验证器（与 TotpViewModel.deleteTotpItem
     * 的判断保持一致）。
     *
     * 自带独立 cipher 的条目（例如历史 bug 产生的 website=otpauth://... 重复条目）虽也带
     * boundPasswordId，但它在服务器上是另一个独立条目，删除只影响它自己，不会动到被绑定
     * 密码条目的验证码，因此不该弹出「会影响密码条目」的确认。
     */
    // 注意：这是 Composable 内的局部函数，不能加 private 修饰符（Kotlin 不允许）。
    fun willClearBoundPasswordTotp(item: SecureItem): Boolean {
        val boundId = boundPasswordIdFor(item) ?: return false
        // 自带独立 cipher 的条目在服务器上是另一个独立条目，删除只影响它自己。
        if (!item.bitwardenCipherId.isNullOrBlank()) return false
        // 被绑定的密码条目若已不存在，就没有东西可连带清空，不该再用这条警告吓用户。
        return passwordMap[boundId] != null
    }

    // 「数据库」Tab 条数统计：按存储来源分组（与密码页同口径）。
    // 绑定型验证码依附于某条密码条目，自身不占存储位（bitwardenVaultId / keepassDatabaseId 皆空），
    // 但它的数据实际存在于被绑定密码条目所在的库中。统计时须继承该密码条目的存储归属，
    // 否则会被错误地算成「Bastion 本地」，用户就只能在本地分类里找到它、在 Bitwarden 下找不到。
    val totpStorageCounts = remember(allTotpItemsForStats, passwordMap) {
        val active = allTotpItemsForStats.filter { !it.isDeleted }
        val storages = active.map { item ->
            val boundPassword = boundPasswordIdFor(item)?.let { passwordMap[it] }
            if (boundPassword != null) {
                boundPassword.keepassDatabaseId to boundPassword.bitwardenVaultId
            } else {
                item.keepassDatabaseId to item.bitwardenVaultId
            }
        }
        com.bastion.app.ui.PasswordStorageCounts(
            total = active.size,
            bastionLocal = storages.count { it.first == null && it.second == null },
            perKeePassDatabase = storages
                .mapNotNull { it.first }
                .groupingBy { it }
                .eachCount(),
            perBitwardenVault = storages
                .mapNotNull { it.second }
                .groupingBy { it }
                .eachCount()
        )
    }

    fun requestDeleteItem(item: SecureItem) {
        if (willClearBoundPasswordTotp(item)) {
            pendingBoundSingleDelete = item
            return
        }

        itemToDelete = item
        deletedItemIds = deletedItemIds + item.id
    }

    fun requestBatchDelete() {
        val toDelete = totpItems.filter { selectedItems.contains(it.id) }
        if (toDelete.isEmpty()) return

        val boundItems = toDelete.filter { willClearBoundPasswordTotp(it) }
        if (boundItems.isNotEmpty()) {
            pendingBoundBatchDelete = boundItems
        } else {
            showBatchDeleteDialog = true
        }
    }

    fun buildBoundDeleteImpacts(items: List<SecureItem>): List<BoundTotpDeleteImpact> {
        return items.mapNotNull { item ->
            val passwordId = boundPasswordIdFor(item) ?: return@mapNotNull null
            val passwordTitle = passwordMap[passwordId]?.title
                ?: context.getString(R.string.bound_totp_delete_missing_password, passwordId)
            BoundTotpDeleteImpact(
                authenticatorTitle = item.title,
                passwordTitle = passwordTitle
            )
        }
    }
    
    // 定义回调函数
    val exitSelection = {
        isSelectionMode = false
        selectedItems = setOf()
    }
    
    val selectAll = {
        selectedItems = if (selectedItems.size == filteredTotpItems.size) {
            setOf()
        } else {
            filteredTotpItems.map { it.id }.toSet()
        }
    }
    
    val deleteSelected = {
        requestBatchDelete()
    }

    val moveToCategory = {
        showMoveToCategoryDialog = true
    }
    
    // 通知父组件选择模式状态变化
    LaunchedEffect(isSelectionMode, selectedItems.size) {
        onSelectionModeChange(
            isSelectionMode,
            selectedItems.size,
            exitSelection,
            selectAll,
            moveToCategory,
            deleteSelected
        )
    }

    UnifiedMoveToCategoryBottomSheet(
        visible = showMoveToCategoryDialog,
        onDismiss = { showMoveToCategoryDialog = false },
        categories = categories,
        keepassDatabases = keepassDatabases,
        bitwardenVaults = bitwardenVaults,
        getBitwardenFolders = { vaultId -> database.bitwardenFolderDao().getFoldersByVaultFlow(vaultId) },
        getKeePassGroups = getKeePassGroups,
        allowCopy = true,
        allowMove = totpItems.filter { it.id in selectedItems.filter { selected -> selected > 0L } }.none { it.isKeePassOwned() },
        onTargetSelected = { target, action ->
            val movableIds = selectedItems.filter { it > 0L }
            val targetCategoryId = when (target) {
                UnifiedMoveCategoryTarget.Uncategorized -> null
                is UnifiedMoveCategoryTarget.BastionCategory -> target.categoryId
                else -> null
            }
            val isBastionLocalTarget = target == UnifiedMoveCategoryTarget.Uncategorized ||
                target is UnifiedMoveCategoryTarget.BastionCategory
            val selectedTotpItems = totpItems.filter { it.id in movableIds }
            val effectiveAction = if (action == UnifiedMoveAction.MOVE && selectedTotpItems.any { it.isKeePassOwned() }) {
                Toast.makeText(
                    context,
                    context.getString(R.string.keepass_copy_only_hint),
                    Toast.LENGTH_SHORT
                ).show()
                UnifiedMoveAction.COPY
            } else {
                action
            }
            if (effectiveAction == UnifiedMoveAction.COPY) {
                coroutineScope.launch {
                    var copiedCount = 0
                    selectedTotpItems.forEach { item ->
                        if (isBastionLocalTarget) {
                            if (viewModel.copyTotpToBastionLocal(item, targetCategoryId) != null) {
                                copiedCount++
                            }
                            return@forEach
                        }
                        val totpData = TotpDataResolver.parseStoredItemData(
                            itemData = item.itemData,
                            fallbackIssuer = item.title,
                            decryptIfNeeded = securityManager::decryptDataIfBastionCiphertext
                        ) ?: return@forEach
                        val detachedTotpData = totpData.copy(
                            boundPasswordId = null,
                            categoryId = null,
                            keepassDatabaseId = null
                        )
                        val targetKeepassDatabaseId = when (target) {
                            is UnifiedMoveCategoryTarget.KeePassDatabaseTarget -> target.databaseId
                            is UnifiedMoveCategoryTarget.KeePassGroupTarget -> target.databaseId
                            else -> null
                        }
                        val targetBitwardenVaultId = when (target) {
                            is UnifiedMoveCategoryTarget.BitwardenVaultTarget -> target.vaultId
                            is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> target.vaultId
                            else -> null
                        }
                        val targetBitwardenFolderId = when (target) {
                            is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> target.folderId
                            is UnifiedMoveCategoryTarget.BitwardenVaultTarget -> ""
                            else -> null
                        }
                        viewModel.saveTotpItem(
                            id = null,
                            title = item.title,
                            notes = item.notes,
                            totpData = detachedTotpData,
                            isFavorite = item.isFavorite,
                            categoryId = targetCategoryId,
                            keepassDatabaseId = targetKeepassDatabaseId,
                            bitwardenVaultId = targetBitwardenVaultId,
                            bitwardenFolderId = targetBitwardenFolderId
                        )
                        copiedCount++
                    }
                    Toast.makeText(
                        context,
                        context.getString(R.string.selected_items, copiedCount),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                if (isBastionLocalTarget) {
                    coroutineScope.launch {
                        var movedCount = 0
                        selectedTotpItems.forEach { item ->
                            if (item.isLocalOnlyItem()) {
                                viewModel.moveToCategory(listOf(item.id), targetCategoryId)
                                movedCount++
                            } else if (viewModel.moveTotpToBastionLocal(item, targetCategoryId).isSuccess) {
                                movedCount++
                            }
                        }
                        Toast.makeText(
                            context,
                            context.getString(R.string.selected_items, movedCount),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    when (target) {
                        UnifiedMoveCategoryTarget.Uncategorized -> {
                            viewModel.moveToCategory(movableIds, null)
                            Toast.makeText(context, context.getString(R.string.category_none), Toast.LENGTH_SHORT).show()
                        }
                        is UnifiedMoveCategoryTarget.BastionCategory -> {
                            viewModel.moveToCategory(movableIds, target.categoryId)
                            val name = categories.find { it.id == target.categoryId }?.name ?: ""
                            Toast.makeText(context, "${context.getString(R.string.move_to_category)} $name", Toast.LENGTH_SHORT).show()
                        }
                        is UnifiedMoveCategoryTarget.BitwardenVaultTarget -> {
                            viewModel.moveToBitwardenFolder(movableIds, target.vaultId, "")
                            Toast.makeText(context, context.getString(R.string.filter_bitwarden), Toast.LENGTH_SHORT).show()
                        }
                        is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> {
                            viewModel.moveToBitwardenFolder(movableIds, target.vaultId, target.folderId)
                            Toast.makeText(context, context.getString(R.string.filter_bitwarden), Toast.LENGTH_SHORT).show()
                        }
                        is UnifiedMoveCategoryTarget.KeePassDatabaseTarget -> {
                            viewModel.moveToKeePassDatabase(movableIds, target.databaseId)
                            val name = keepassDatabases.find { it.id == target.databaseId }?.name ?: "KeePass"
                            Toast.makeText(context, "${context.getString(R.string.move_to_category)} $name", Toast.LENGTH_SHORT).show()
                        }
                        is UnifiedMoveCategoryTarget.KeePassGroupTarget -> {
                            viewModel.moveToKeePassGroup(movableIds, target.databaseId, target.groupPath)
                            val groupName = decodeKeePassPathForDisplay(target.groupPath)
                            Toast.makeText(context, "${context.getString(R.string.move_to_category)} $groupName", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            showMoveToCategoryDialog = false
            isSelectionMode = false
            selectedItems = emptySet()
        }
    )

    // 悬浮叠加：顶栏+统一进度条浮在列表之上，列表内容可从其下方穿过（对齐密码页观感）
    var topOverlayHeightPx by remember { mutableStateOf(0) }
    val topOverlayHeightDp = with(LocalDensity.current) { topOverlayHeightPx.toDp() }

    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        // M3E Top Bar with integrated search - 根据当前分类过滤器动态显示标题
        val title = when (val filter = currentFilter) {
            is com.bastion.app.viewmodel.TotpCategoryFilter.All -> stringResource(R.string.nav_authenticator)
            is com.bastion.app.viewmodel.TotpCategoryFilter.Local -> stringResource(R.string.filter_bastion)
            is com.bastion.app.viewmodel.TotpCategoryFilter.Starred -> stringResource(R.string.filter_starred)
            is com.bastion.app.viewmodel.TotpCategoryFilter.Uncategorized -> stringResource(R.string.filter_uncategorized)
            is com.bastion.app.viewmodel.TotpCategoryFilter.LocalStarred -> "${stringResource(R.string.filter_bastion)} · ${stringResource(R.string.filter_starred)}"
            is com.bastion.app.viewmodel.TotpCategoryFilter.LocalUncategorized -> "${stringResource(R.string.filter_bastion)} · ${stringResource(R.string.filter_uncategorized)}"
            is com.bastion.app.viewmodel.TotpCategoryFilter.Custom -> categories.find { it.id == filter.categoryId }?.name ?: stringResource(R.string.unknown_category)
            is com.bastion.app.viewmodel.TotpCategoryFilter.KeePassDatabase -> keepassDatabases.find { it.id == filter.databaseId }?.name ?: "KeePass"
            is com.bastion.app.viewmodel.TotpCategoryFilter.KeePassGroupFilter -> decodeKeePassPathForDisplay(filter.groupPath)
            is com.bastion.app.viewmodel.TotpCategoryFilter.KeePassDatabaseStarred -> "${keepassDatabases.find { it.id == filter.databaseId }?.name ?: "KeePass"} · ${stringResource(R.string.filter_starred)}"
            is com.bastion.app.viewmodel.TotpCategoryFilter.KeePassDatabaseUncategorized -> "${keepassDatabases.find { it.id == filter.databaseId }?.name ?: "KeePass"} · ${stringResource(R.string.filter_uncategorized)}"
            is com.bastion.app.viewmodel.TotpCategoryFilter.BitwardenVault -> stringResource(R.string.filter_bitwarden)
            is com.bastion.app.viewmodel.TotpCategoryFilter.BitwardenFolderFilter -> stringResource(R.string.filter_bitwarden)
            is com.bastion.app.viewmodel.TotpCategoryFilter.BitwardenVaultStarred -> "${stringResource(R.string.filter_bitwarden)} · ${stringResource(R.string.filter_starred)}"
            is com.bastion.app.viewmodel.TotpCategoryFilter.BitwardenVaultUncategorized -> "${stringResource(R.string.filter_bitwarden)} · ${stringResource(R.string.filter_uncategorized)}"
        }

        // 悬浮层：置顶 + 提升层级（压在列表之上），高度实测供列表避让
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .zIndex(1f)
                .onSizeChanged { size -> topOverlayHeightPx = size.height.toInt() }
        ) {
        ExpressiveTopBar(
            title = title,
            searchQuery = searchQuery,
            onSearchQueryChange = viewModel::updateSearchQuery,
            isSearchExpanded = isSearchExpanded,
            onSearchExpandedChange = { isSearchExpanded = it },
            searchHint = stringResource(R.string.search_authenticator),
            // 性能：胶囊 bounds 逐帧回调且无消费方，会触发整屏重组；停用
            onActionPillBoundsChanged = null,
            scrollCollapseFraction = scrollCollapseFraction,
            actions = {
                // 搜索按钮（无高亮底色，与密码页一致，固定首位）
                IconButton(
                    onClick = { isSearchExpanded = true },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = stringResource(R.string.search)
                    )
                }
                // 分类文件夹按钮
                IconButton(onClick = { isCategorySheetVisible = true }) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = stringResource(R.string.category),
                        tint = LocalContentColor.current
                    )
                }
                Box {
                    IconButton(onClick = { showTopActionsMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.more_options),
                            tint = LocalContentColor.current
                        )
                    }
                    if (appSettings.categorySelectionUiMode == com.bastion.app.data.CategorySelectionUiMode.CHIP_MENU) {
                        UnifiedCategoryFilterChipMenuDropdown(
                            expanded = isCategorySheetVisible,
                            onDismissRequest = { isCategorySheetVisible = false },
                            offset = UnifiedCategoryFilterChipMenuOffset
                        ) {
                            UnifiedCategoryFilterChipMenu(
                                visible = true,
                                onDismiss = { isCategorySheetVisible = false },
                                selected = totpSelectedFilter,
                                onSelect = handleCategorySelection,
                                categories = categories,
                                keepassDatabases = keepassDatabases,
                                bitwardenVaults = bitwardenVaults,
                                getBitwardenFolders = { vaultId -> database.bitwardenFolderDao().getFoldersByVaultFlow(vaultId) },
                                getKeePassGroups = getKeePassGroups,
                                categoryEditMode = categoryMgmt.categoryEditMode,
                                onRequestCategoryAction = { categoryMgmt.categoryActionTarget = it },
                                trailingContent = {
                                    CategoryManagementTrailingContent(
                                        state = categoryMgmt,
                                        categories = categories,
                                        keepassDatabases = keepassDatabases,
                                        bitwardenVaults = bitwardenVaults,
                                        getBitwardenFolders = { vaultId -> database.bitwardenFolderDao().getFoldersByVaultFlow(vaultId) },
                                        getKeePassGroups = getKeePassGroups,
                                        passwordViewModel = passwordViewModel,
                                        onDismissFilterSheet = { isCategorySheetVisible = false }
                                    )
                                },
                                presentationMode = com.bastion.app.ui.components.UnifiedCategoryFilterChipMenuPresentationMode.ThreeChipsTabs,
                                // 验证器页没有密码页那种横排快捷筛选条，弹层是「标星 / 未分类 /
                                // 仅本地」的唯一入口，必须开启，否则这三个筛选在 UI 上完全不可达。
                                showQuickFilters = true,
                                entryCounts = totpStorageCounts
                            )
                        }
                    }
                    PasswordTopActionsDropdownMenu(
                        expanded = showTopActionsMenu,
                        onDismissRequest = { showTopActionsMenu = false }
                    ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.quick_action_scan_qr)) },
                                leadingIcon = { Icon(Icons.Default.QrCodeScanner, contentDescription = null) },
                                onClick = {
                                    showTopActionsMenu = false
                                    onQuickScanTotp()
                                }
                            )
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
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp
                                            )
                                        } else {
                                            Icon(Icons.Default.Sync, contentDescription = null)
                                        }
                                    },
                                    enabled = !isTopBarSyncing && !isBitwardenTotpRepairing,
                                    onClick = {
                                        if (isTopBarSyncing || isBitwardenTotpRepairing) return@DropdownMenuItem
                                        val vaultId = selectedBitwardenVaultId ?: return@DropdownMenuItem
                                        showTopActionsMenu = false
                                        bitwardenViewModel.requestManualSync(vaultId)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.repair_bitwarden_totp_menu)) },
                                    leadingIcon = { Icon(Icons.Default.Build, contentDescription = null) },
                                    enabled = !isTopBarSyncing && !isBitwardenTotpRepairing,
                                    onClick = {
                                        if (isTopBarSyncing || isBitwardenTotpRepairing) return@DropdownMenuItem
                                        val vaultId = selectedBitwardenVaultId ?: return@DropdownMenuItem
                                        showTopActionsMenu = false
                                        scope.launch {
                                            isBitwardenTotpRepairing = true
                                            try {
                                                val result = viewModel.repairHistoricalBitwardenTotp(vaultId)
                                                if (result.queuedForSyncCount > 0) {
                                                    bitwardenViewModel.requestManualSync(vaultId)
                                                    Toast.makeText(
                                                        context,
                                                        context.getString(
                                                            R.string.repair_bitwarden_totp_result_sync,
                                                            result.normalizedCount,
                                                            result.queuedForSyncCount,
                                                            result.skippedItems
                                                        ),
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                } else if (result.normalizedCount > 0) {
                                                    Toast.makeText(
                                                        context,
                                                        context.getString(
                                                            R.string.repair_bitwarden_totp_result_local,
                                                            result.normalizedCount,
                                                            result.skippedItems
                                                        ),
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                } else {
                                                    Toast.makeText(
                                                        context,
                                                        context.getString(R.string.repair_bitwarden_totp_nothing_to_fix),
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            } catch (e: Exception) {
                                                Toast.makeText(
                                                    context,
                                                    context.getString(
                                                        R.string.repair_bitwarden_totp_failed,
                                                        e.message ?: context.getString(R.string.repair_bitwarden_totp_failed_unknown)
                                                    ),
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            } finally {
                                                isBitwardenTotpRepairing = false
                                            }
                                        }
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
                                        viewModel.syncKeePassByDatabaseId(selectedKeePassDatabaseId)
                                    }
                                )
                            }
                        }
                    }
            }
        )
        
        // 验证器 → 通行秘钥 的快捷入口。默认挂在倒计时进度条右侧；
        // 进度条被关闭或当前无 TOTP 条目时改为独立成行兜底显示，
        // 否则用户会在空列表等场景下彻底失去进入通行秘钥页的路径。
        val passkeyEntryButton: @Composable () -> Unit = {
            IconButton(
                onClick = onNavigateToPasskeys,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Fingerprint,
                    contentDescription = stringResource(R.string.nav_passkey),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        // 统一进度条 - 在顶栏下方显示；滚动收起时高度收起 + 淡出（列表避让随悬浮层实测自动跟随）
        val progressBarHeight by animateDpAsState(
            targetValue = androidx.compose.ui.unit.lerp(44.dp, 0.dp, scrollCollapseFraction),
            animationSpec = tween(200),
            label = "totp_progress_bar_height"
        )
        Box(
            modifier = Modifier
                .height(progressBarHeight)
                .clipToBounds()
                .graphicsLayer { alpha = 1f - scrollCollapseFraction }
        ) {
            if (appSettings.validatorUnifiedProgressBar == com.bastion.app.data.UnifiedProgressBarMode.ENABLED &&
                filteredTotpItems.isNotEmpty()) {
                com.bastion.app.ui.components.UnifiedProgressBar(
                    style = appSettings.validatorProgressBarStyle,
                    currentSeconds = sharedTickSeconds,
                    period = 30,
                    smoothProgress = appSettings.validatorSmoothProgress,
                    timeOffset = (appSettings.totpTimeOffset * 1000).toLong(), // 传递时间偏移(毫秒)
                    trailingContent = passkeyEntryButton
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    passkeyEntryButton()
                }
            }
        }
        } // 悬浮层结束（顶栏 + 统一进度条）

        val contentPullOffset = if (enableBitwardenPullSync) 0 else pullAction.currentOffset.toInt()

        // TOTP列表
        if (filteredTotpItems.isEmpty()) {
            // 空状态
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset { androidx.compose.ui.unit.IntOffset(0, contentPullOffset) }
                    .nestedScroll(pullAction.nestedScrollConnection)
                    .pointerInput(isSearchExpanded) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { _, dragAmount ->
                                if (!isSearchExpanded) pullAction.onVerticalDrag(dragAmount)
                            },
                            onDragEnd = {
                                pullAction.onDragEnd()
                            },
                            onDragCancel = {
                                pullAction.onDragCancel()
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(
                        Icons.Default.Security,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.no_authenticators_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.no_authenticators_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            // 可拖动排序的列表状态
            // 用于拖动排序的本地列表状态
            var localTotpItems by remember(filteredTotpItems) { 
                mutableStateOf(filteredTotpItems) 
            }
            
            // 当筛选后的列表变化时同步
            LaunchedEffect(filteredTotpItems) {
                localTotpItems = filteredTotpItems
            }
            
            // 滚动掉帧采样：只在确实掉帧时输出一行摘要，正常滚动静默（见 ScrollJankMonitor）
            com.bastion.app.ui.perf.ScrollJankReporter(listState = lazyListState, label = "totp")

            val reorderableLazyListState = rememberReorderableLazyListState(lazyListState) { from, to ->
                // 只在多选模式下允许排序
                if (isSelectionMode) {
                    localTotpItems = localTotpItems.toMutableList().apply {
                        add(to.index, removeAt(from.index))
                    }
                }
            }
            
            // 当拖动结束时保存新顺序
            LaunchedEffect(reorderableLazyListState.isAnyItemDragging) {
                if (!reorderableLazyListState.isAnyItemDragging && isSelectionMode) {
                    // 拖动结束，保存新顺序到数据库
                    val newOrders = localTotpItems.mapIndexed { index, item ->
                        item.id to index
                    }
                    if (newOrders.isNotEmpty()) {
                        viewModel.updateSortOrders(newOrders)
                    }
                }
            }
            
            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxSize()
                    .offset { androidx.compose.ui.unit.IntOffset(0, contentPullOffset) }
                    .nestedScroll(pullAction.nestedScrollConnection),
                // top = 状态栏 + 顶部 Bar 高度(88dp)：沉浸式布局下内容从屏幕顶开始，须避让状态栏和 Bar
contentPadding = PaddingValues(
    start = 16.dp,
    end = 16.dp,
    // 悬浮叠加：按悬浮层（顶栏+进度条）实测高度避让，随收起动画自动联动；额外 12dp 呼吸空间
    top = topOverlayHeightDp + 12.dp,
    bottom = 96.dp
),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    items = localTotpItems,
                    key = { it.id }
                ) { item ->
                    ReorderableItem(
                        reorderableLazyListState,
                        key = item.id,
                        enabled = isSelectionMode
                    ) { isDragging ->
                        // 【方案 A】滑动门控是**每条目独立**的。此前它被放在列表顶层，导致长按任一条目
                        // 会让全部条目同时解锁（误触面放大 N 倍），且 3 秒超时后所有条目的删除滑动
                        // 一起失效。放进 items(key = { it.id }) 内部即可按条目隔离。
                        val armState = com.bastion.app.ui.gestures.rememberSwipeArmState()
                        val elevation by animateDpAsState(
                            if (isDragging) 8.dp else 0.dp,
                            label = "drag_elevation"
                        )
                        
                        // 在多选模式下使用拖动手柄
                        val dragModifier = if (isSelectionMode) {
                            Modifier.longPressDraggableHandle(
                                onDragStarted = {
                                    haptic.performLongPress()
                                },
                                onDragStopped = {
                                    haptic.performSuccess()
                                }
                            )
                        } else {
                            Modifier
                        }
                        
                        // Keep right-swipe selection available in selection mode; only disable delete swipe there.
                        com.bastion.app.ui.gestures.SwipeActions(
                            onSwipeLeft = {
                                // 左滑删除
                                armState.disarm()
                                haptic.performWarning()
                                requestDeleteItem(item)
                            },
                            onSwipeRight = {
                                // 右滑选择
                                haptic.performSuccess()
                                if (!isSelectionMode) {
                                    isSelectionMode = true
                                }
                                selectedItems = if (selectedItems.contains(item.id)) {
                                    selectedItems - item.id
                                } else {
                                    selectedItems + item.id
                                }
                            },
                            isSwiped = itemToDelete?.id == item.id,
                            // 【方案 A】默认锁定滑动（拖动排序时也锁）；长按激活**本条目**后 3 秒内可滑。
                            // 多选模式下仍允许滑动（右滑选条目）。左滑删除在多选下仅对**已选中**条目放行：
                            // 未选中的仍禁删（防误触语义由 TotpSwipeSelectionRegressionGuardTest 守卫，改前先读那个测试）。
                            // 修复（2026-09-05 实机复现）：此前多选下左滑完全依赖 armed 的 3 秒窗口，
                            // 超时后连已选中的条目也删不掉——多选操作栏又被悬浮胶囊遮挡，滑动是唯一删除出口。
                            enabled = !isDragging && (isSelectionMode || armState.armed),
                            armed = armState.armed,
                            allowSwipeLeft = !isSelectionMode || armState.armed || selectedItems.contains(item.id),
                            allowSwipeRight = true
                        ) {
                            // 包装卡片以支持拖动
                            Box(
                                modifier = Modifier
                                    .graphicsLayer {
                                        shadowElevation = elevation.toPx()
                                    }
                                    .then(dragModifier)
                            ) {
                                TotpItemCard(
                                    item = item,
                                    onEdit = { onTotpClick(item.id) },
                                    onToggleSelect = {
                                        selectedItems = if (selectedItems.contains(item.id)) {
                                            selectedItems - item.id
                                        } else {
                                            selectedItems + item.id
                                        }
                                    },
                                    onDelete = {
                                        haptic.performWarning()
                                        requestDeleteItem(item)
                                    },
                                    onToggleFavorite = { id, isFavorite ->
                                        viewModel.toggleFavorite(id, isFavorite)
                                    },
                                    onGenerateNext = { id ->
                                        viewModel.incrementHotpCounter(id)
                                    },
                                    onMoveUp = null, // 使用拖动排序替代
                                    onMoveDown = null, // 使用拖动排序替代
                                    onShowQrCode = {
                                        itemToShowQr = item
                                    },
                                    onLongClick = {
                                        // 【方案 A】长按同时激活滑动删除（3 秒窗口）+ 保留进多选逻辑
                                        armState.arm()
                                        haptic.performLongPress()
                                        if (!isSelectionMode) {
                                            isSelectionMode = true
                                            selectedItems = setOf(item.id)
                                        }
                                    },
                                    isSelectionMode = isSelectionMode,
                                    isSelected = selectedItems.contains(item.id),
                                    sharedTickSeconds = sharedTickSeconds,
                                    sharedProgressTimeMillis = sharedProgressTimeMillis,
                                    appSettings = appSettings.copy(
                                        iconCardsEnabled = appSettings.iconCardsEnabled && appSettings.authenticatorPageIconEnabled
                                    ),
                                    parsedTotpData = totpDataById[item.id]
                                )

                                // 同一密钥被保存了多份（如既存独立验证器、又绑进密码条目）→ 打标记提示。
                                // 仅提示不自动删除：两种存在形式可能代表不同使用意图。
                                if (item.id in duplicateTotpIds) {
                                    DuplicateTotpBadge()
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                item {
                    Spacer(modifier = Modifier.height(80.dp))
                }
            }
        }
    }
    
    // QR码对话框
    itemToShowQr?.let { item ->
        QrCodeDialog(
            item = item,
            onDismiss = { itemToShowQr = null }
        )
    }

    pendingBoundSingleDelete?.let { item ->
        BoundTotpDeleteWarningDialog(
            impacts = buildBoundDeleteImpacts(listOf(item)),
            isBatch = false,
            onDismiss = {
                pendingBoundSingleDelete = null
            },
            onConfirm = {
                pendingBoundSingleDelete = null
                itemToDelete = item
                deletedItemIds = deletedItemIds + item.id
            }
        )
    }

    if (pendingBoundBatchDelete.isNotEmpty()) {
        val pendingItems = pendingBoundBatchDelete
        BoundTotpDeleteWarningDialog(
            impacts = buildBoundDeleteImpacts(pendingItems),
            isBatch = true,
            onDismiss = {
                pendingBoundBatchDelete = emptyList()
            },
            onConfirm = {
                pendingBoundBatchDelete = emptyList()
                showBatchDeleteDialog = true
            }
        )
    }
    
    // 单项删除确认对话框(支持指纹和密码验证)
    itemToDelete?.let { item ->
        DeleteConfirmDialog(
            itemTitle = item.title,
            itemType = stringResource(R.string.item_type_authenticator),
            biometricEnabled = appSettings.biometricEnabled,
            onDismiss = {
                // 取消删除，恢复卡片显示
                deletedItemIds = deletedItemIds - item.id
                itemToDelete = null
            },
            onConfirmWithPassword = { password ->
                singleItemPasswordInput = password
                showSingleItemPasswordVerify = true
            },
            onConfirmWithBiometric = {
                // 指纹验证成功，直接删除
                onDeleteTotp(item)
                Toast.makeText(
                    context,
                    context.getString(R.string.deleted),
                    Toast.LENGTH_SHORT
                ).show()
                itemToDelete = null
            }
        )
    }
    
    // 单项删除密码验证
    if (showSingleItemPasswordVerify && itemToDelete != null) {
        LaunchedEffect(Unit) {
            val securityManager = com.bastion.app.security.SecurityManager(context)
            if (securityManager.verifyMasterPassword(singleItemPasswordInput)) {
                // 密码正确，删除 TOTP
                onDeleteTotp(itemToDelete!!)
                
                Toast.makeText(
                    context,
                    context.getString(R.string.deleted),
                    Toast.LENGTH_SHORT
                ).show()
                
                // 清理状态（保持在 deletedItemIds 中，因为已真实删除）
                itemToDelete = null
                singleItemPasswordInput = ""
                showSingleItemPasswordVerify = false
            } else {
                // 密码错误，恢复卡片显示
                deletedItemIds = deletedItemIds - itemToDelete!!.id
                
                Toast.makeText(
                    context,
                    context.getString(R.string.current_password_incorrect),
                    Toast.LENGTH_SHORT
                ).show()
                
                // 重置状态
                itemToDelete = null
                singleItemPasswordInput = ""
                showSingleItemPasswordVerify = false
            }
        }
    }
    
    // 批量删除验证对话框（统一 M3 身份验证弹窗）
    if (showBatchDeleteDialog) {
        val biometricAction = if (canUseBiometric) {
            {
                biometricHelper.authenticate(
                    activity = activity!!,
                    title = context.getString(R.string.verify_identity),
                    subtitle = context.getString(R.string.verify_to_delete),
                    onSuccess = {
                        coroutineScope.launch {
                            val toDelete = totpItems.filter { selectedItems.contains(it.id) }
                            viewModel.deleteTotpItems(toDelete)
                            android.widget.Toast.makeText(
                                context,
                                context.getString(R.string.deleted_items, toDelete.size),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            isSelectionMode = false
                            selectedItems = setOf()
                            passwordInput = ""
                            passwordError = false
                            showBatchDeleteDialog = false
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
            message = stringResource(R.string.batch_delete_totp_message, selectedItems.size),
            passwordValue = passwordInput,
            onPasswordChange = {
                passwordInput = it
                passwordError = false
            },
            onDismiss = {
                showBatchDeleteDialog = false
                passwordInput = ""
                passwordError = false
            },
            onConfirm = {
                if (SecurityManager(context).verifyMasterPassword(passwordInput)) {
                    coroutineScope.launch {
                        val toDelete = totpItems.filter { selectedItems.contains(it.id) }
                        viewModel.deleteTotpItems(toDelete)
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.deleted_items, toDelete.size),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        isSelectionMode = false
                        selectedItems = setOf()
                        passwordInput = ""
                        passwordError = false
                        showBatchDeleteDialog = false
                    }
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

    CategoryManagementCreateDialog(
        state = categoryMgmt,
        currentFilter = totpSelectedFilter,
        categories = categories,
        keepassDatabases = keepassDatabases,
        bitwardenVaults = bitwardenVaults,
        getKeePassGroups = getKeePassGroups,
        passwordViewModel = passwordViewModel,
        bitwardenRepository = bitwardenRepository,
        keepassBridge = keepassBridge,
        scope = scope
    )
}

private data class BoundTotpDeleteImpact(
    val authenticatorTitle: String,
    val passwordTitle: String
)

@Composable
private fun BoundTotpDeleteWarningDialog(
    impacts: List<BoundTotpDeleteImpact>,
    isBatch: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.LinkOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text(
                text = stringResource(
                    if (isBatch) {
                        R.string.bound_totp_delete_warning_title_multi
                    } else {
                        R.string.bound_totp_delete_warning_title_single
                    }
                )
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(
                        if (isBatch) {
                            R.string.bound_totp_delete_warning_message_multi
                        } else {
                            R.string.bound_totp_delete_warning_message_single
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                impacts.forEach { impact ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.28f)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = impact.authenticatorTitle,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = impact.passwordTitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(stringResource(R.string.bound_totp_delete_warning_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/**
 * TOTP项卡片
 */


/**
 * 同一 TOTP 密钥被保存多份时的提示角标（文件末尾独立定义，避免插进 TotpListContent 体内）。
 *
 * 出现场景：同一个密钥既存为独立验证器、又绑定进了某个密码条目。二者在列表里是
 * 不同的 SecureItem（绑定型按 boundPasswordId|identityKey 去重、独立型按 identityKey
 * 去重，互不合并），内容却完全相同，改任一边另一边都不会跟着变化。
 *
 * 只做提示，不做任何自动清理——保留哪一份由用户决定，避免误删数据。
 */
@Composable
private fun BoxScope.DuplicateTotpBadge() {
    Box(
        modifier = Modifier
            .padding(top = 8.dp, end = 8.dp)
            .background(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .align(Alignment.TopEnd)
    ) {
        Text(
            text = stringResource(R.string.totp_duplicate_badge),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer
        )
    }
}
