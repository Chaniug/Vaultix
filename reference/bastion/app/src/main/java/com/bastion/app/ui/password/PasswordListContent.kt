@file:Suppress("LocalContextGetResourceValueCall")
package com.bastion.app.ui

import com.bastion.app.logging.runCatchingObserved
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Base64
import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.fragment.app.FragmentActivity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import com.bastion.app.R
import com.bastion.app.bitwarden.sync.isUserVisibleSyncInProgress
import com.bastion.app.data.AppSettings
import com.bastion.app.data.BottomNavContentTab
import com.bastion.app.data.CategorySelectionUiMode
import com.bastion.app.data.ItemType
import com.bastion.app.data.KeePassSyncPhase
import com.bastion.app.data.KeePassSyncStatus
import com.bastion.app.data.PasskeyEntry
import com.bastion.app.data.PasswordListQuickFilterItem
import com.bastion.app.data.PasswordListTopModule
import com.bastion.app.data.PasswordPageAggregateStackEntry
import com.bastion.app.data.PasswordPageContentType
import com.bastion.app.data.PasswordEntry
import com.bastion.app.data.OperationLogItemType
import com.bastion.app.data.SecureItem
import com.bastion.app.data.isKeePassOwned
import com.bastion.app.data.isLocalOnlyItem
import com.bastion.app.data.isRemoteSource
import com.bastion.app.data.model.TotpData
import com.bastion.app.data.model.TimelinePasswordLocationState
import com.bastion.app.data.model.TimelineEvent
import com.bastion.app.data.model.isSshKeyEntry
import com.bastion.app.data.model.isBarcodeEntry
import com.bastion.app.notes.domain.NoteContentCodec
import com.bastion.app.utils.BiometricHelper
import com.bastion.app.utils.FieldChange
import com.bastion.app.utils.OperationLogger
import com.bastion.app.utils.decodeKeePassPathForDisplay
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
import com.bastion.app.ui.components.CreateCategoryDialog
import com.bastion.app.ui.components.BastionExpressiveFilterChip
import com.bastion.app.ui.components.UnifiedCategoryFilterChipMenuDropdown
import com.bastion.app.ui.components.UnifiedCategoryFilterChipMenuOffset
import com.bastion.app.ui.components.UnifiedCategoryFilterBottomSheet
import com.bastion.app.ui.components.UnifiedCategoryFilterSelection
import com.bastion.app.ui.components.UnifiedMoveAction
import com.bastion.app.ui.components.UnifiedMoveCategoryTarget
import com.bastion.app.ui.password.PasswordAggregateCardStyle
import com.bastion.app.ui.password.PasswordAggregateListItemUi
import com.bastion.app.ui.password.PasswordAggregateManualStackBuildResult
import com.bastion.app.ui.password.PasswordAggregateRetainedStateViewModel
import com.bastion.app.ui.password.PasswordAggregateWalletItemType
import com.bastion.app.ui.password.PasswordListCardBadge
import com.bastion.app.ui.password.PasswordGroupListItemUi
import com.bastion.app.ui.password.PasswordListAggregateConfig
import com.bastion.app.ui.password.PasswordPageListItemUi
import com.bastion.app.ui.password.PasswordListSingleCardItem
import com.bastion.app.ui.password.PasswordSupplementaryListItemUi
import com.bastion.app.ui.password.PasswordBatchDeleteProgressTracker
import com.bastion.app.ui.password.PasswordBatchTransferProgressTracker
import com.bastion.app.ui.password.appendAggregateContentQuickFilterItems
import com.bastion.app.ui.password.resolvedQuickFilterBaseItems
import com.bastion.app.ui.password.buildPasswordAggregateManualStackGroups
import com.bastion.app.ui.password.buildPasswordAggregateItems
import com.bastion.app.ui.password.buildPasswordPageListItems
import com.bastion.app.ui.password.filterPasswordAggregateItemsByQuickFilters
import com.bastion.app.ui.password.flattenPasswordPageCardItems
import com.bastion.app.ui.password.icon
import com.bastion.app.ui.password.labelRes
import com.bastion.app.ui.password.resolvePasswordPageDisplayedTypes
import com.bastion.app.ui.password.resolvePasswordPageQuickFilterTypes
import com.bastion.app.ui.password.resolveSelectedPasswordPageCardItems
import com.bastion.app.ui.password.toPasswordPageContentTypeOrNull
import com.bastion.app.ui.password.toSelectedSupplementaryItemOrNull
import com.bastion.app.ui.components.UnifiedMoveToCategoryBottomSheet
import com.bastion.app.ui.components.UNIFIED_MOVE_ARCHIVE_SENTINEL_CATEGORY_ID
import com.bastion.app.ui.components.rememberUnifiedCategoryFilterChipMenuWidth
import com.bastion.app.ui.common.dialog.DeleteConfirmDialog
import com.bastion.app.ui.common.layout.DetailPane
import com.bastion.app.ui.common.layout.InspectorRow
import com.bastion.app.ui.common.layout.ListPane
import com.bastion.app.ui.common.pull.PullActionVisualState
import com.bastion.app.ui.common.pull.PullGestureIndicator
import com.bastion.app.ui.common.pull.rememberPullActionState
import com.bastion.app.ui.common.selection.CategoryListItem
import com.bastion.app.ui.common.selection.SelectionActionBar
import com.bastion.app.ui.common.selection.SelectionModeTopBar
import com.bastion.app.ui.main.navigation.BottomNavItem
import com.bastion.app.ui.main.navigation.fullLabelRes
import com.bastion.app.ui.main.navigation.indexToDefaultTabKey
import com.bastion.app.ui.main.navigation.shortLabelRes
import com.bastion.app.ui.main.navigation.toBottomNavItem
import com.bastion.app.ui.main.layout.AdaptiveMainScaffold
import com.bastion.app.ui.icons.BastionIcons
import com.bastion.app.ui.password.buildAdditionalInfoPreview
import com.bastion.app.ui.password.MultiPasswordEntryCard
import com.bastion.app.ui.password.StackedPasswordGroup
import com.bastion.app.ui.password.PasswordEntryCard
import com.bastion.app.ui.password.StackCardMode
import com.bastion.app.ui.password.getGroupKeyForMode
import com.bastion.app.ui.password.getPasswordGroupTitle
import com.bastion.app.ui.password.getPasswordInfoKey
import com.bastion.app.ui.password.passwordIdFromSelectionKey
import com.bastion.app.ui.password.passwordSelectionKey
import com.bastion.app.data.bitwarden.BitwardenPendingOperation
import com.bastion.app.data.bitwarden.BitwardenSend
import com.bastion.app.bitwarden.repository.BitwardenRepository
import com.bastion.app.bitwarden.sync.SyncStatus
import com.bastion.app.security.SecurityManager
import com.bastion.app.sync.SyncKey
import com.bastion.app.sync.SyncPhase
import com.bastion.app.sync.SyncTarget
import com.bastion.app.sync.SyncTaskRunner
import sh.calvin.reorderable.rememberReorderableLazyListState
import com.bastion.app.ui.screens.AddEditPasswordScreen
import com.bastion.app.ui.screens.AddEditTotpScreen
import com.bastion.app.ui.screens.AddEditBankCardScreen
import com.bastion.app.ui.screens.AddEditDocumentScreen
import com.bastion.app.ui.screens.AddEditNoteScreen
import com.bastion.app.ui.screens.AddEditSendScreen
import com.bastion.app.ui.theme.BastionTheme
import java.util.concurrent.CancellationException
import java.security.KeyFactory
import java.security.KeyStore
import java.security.spec.PKCS8EncodedKeySpec
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import com.bastion.app.bitwarden.viewmodel.BitwardenViewModel

private val stringSetSaver = Saver<Set<String>, ArrayList<String>>(
    save = { value -> ArrayList(value) },
    restore = { saved -> saved.toSet() }
)

private const val FAST_SCROLL_LOG_TAG = "PasswordFastScroll"
private const val PASSWORD_SCROLL_LOG_TAG = "PasswordScrollDebug"
private const val PASSWORD_EMPTY_STATE_DEBOUNCE_MS = 220L

private fun QuickStatusKeePassSyncState.dialogSuppressionKey(): String {
    return "$databaseId:$status:$phase:$coordinatorPhase:$coordinatorErrorKind"
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PasswordListInitialLoadingIndicator() {
    androidx.compose.material3.LoadingIndicator(
        modifier = Modifier.size(64.dp),
        color = MaterialTheme.colorScheme.primary
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PasswordListContent(
    viewModel: PasswordViewModel,
    settingsViewModel: SettingsViewModel,
    securityManager: SecurityManager,
    keepassDatabases: List<com.bastion.app.data.LocalKeePassDatabase>,
    bitwardenVaults: List<com.bastion.app.data.bitwarden.BitwardenVault>,
    localKeePassViewModel: com.bastion.app.viewmodel.LocalKeePassViewModel,
    groupMode: String = "none",
    stackCardMode: StackCardMode,
    onRenameCategory: (Category) -> Unit,
    onDeleteCategory: (Category) -> Unit,
    onPasswordClick: (com.bastion.app.data.PasswordEntry) -> Unit,
    onSelectionModeChange: (
        isSelectionMode: Boolean,
        selectedCount: Int,
        onExit: () -> Unit,
        onSelectAll: () -> Unit,
        onFavorite: (() -> Unit)?,
        onMoveToCategory: (() -> Unit)?,
        onStack: (() -> Unit)?,
        onDelete: () -> Unit
    ) -> Unit,
    onBackToTopVisibilityChange: (Boolean) -> Unit = {},
    scrollToTopRequestKey: Int = 0,
    onOpenHistory: () -> Unit = {},
    onOpenTrash: () -> Unit = {},
    onOpenCommonAccountTemplates: () -> Unit = {},
    onScanFidoQr: () -> Unit = {},
    showStandaloneSettingsEntry: Boolean = false,
    onOpenStandaloneSettings: () -> Unit = {},
    aggregateConfig: PasswordListAggregateConfig? = null,
    // 通行秘钥 chip 的跳转目标：点击进入通行秘钥页。
    // 不做列表过滤，因为筛选只能得到「绑定了 passkey 的密码条目」，
    // 而 passkey 本身存在独立的 PasskeyEntry 表，需进通行秘钥页查看。
    onNavigateToPasskeys: (() -> Unit)? = null
) {
    val coroutineScope = rememberCoroutineScope()
    val passwordEntries by viewModel.passwordEntries.collectAsState()
    val passwordEntriesReady by viewModel.passwordEntriesReady.collectAsState()
    val allPasswords by viewModel.allPasswordsForUi.collectAsState()
    val allPasswordsReady by viewModel.allPasswordsForUiReady.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val categoriesReady by viewModel.categoriesReady.collectAsState()
    val currentFilter by viewModel.categoryFilter.collectAsState()
    val isAuthenticated by viewModel.isAuthenticated.collectAsState()
    val aggregateRetainedStateViewModel: PasswordAggregateRetainedStateViewModel = viewModel()
    LaunchedEffect(isAuthenticated) {
        if (!isAuthenticated) {
            aggregateRetainedStateViewModel.retainedState.clear()
        }
    }
    // settings
    val appSettings by settingsViewModel.settings.collectAsState()
    val aggregateUiState = rememberPasswordAggregateUiState(
        aggregateConfig = aggregateConfig,
        searchQuery = searchQuery,
        currentFilter = currentFilter,
        appSettings = appSettings,
        retainedState = aggregateRetainedStateViewModel.retainedState,
    )
    val fastScrollRequestKey by viewModel.fastScrollRequestKey.collectAsState()
    val fastScrollProgress by viewModel.fastScrollProgress.collectAsState()
    val quickStatusTransferState by PasswordBatchTransferProgressTracker.progress.collectAsState()
    val quickStatusDeleteState by PasswordBatchDeleteProgressTracker.progress.collectAsState()
    val dialogState = rememberPasswordListDialogState()

    // 收集 VM 暴露的"手动 sync 进行中"数据库 id 集合，用于区分自动 / 手动 sync 的弹窗策略。
    // 自动 sync（PAGE_VISIBLE / PENDING_UPLOAD 触发的）静默不弹"正在同步"对话框，
    // 手动 sync（用户主动点"立即同步"或在 KeePass 工具页点同步）才弹。
    // CONFLICT 状态除外（见下方 quickStatusKeePassSyncState 的 shouldShow 计算）。
    val activeManualSyncDatabaseIds by localKeePassViewModel.activeManualSyncDatabaseIds.collectAsState()

    // "仅本地" 的核心目标是给用户看待上传清单，不应该出现堆叠容器。
    // 因此这里强制扁平展示，仅在该筛选下生效，不影响其他页面。
    val isLocalOnlyView = currentFilter is CategoryFilter.LocalOnly
    val isAllView = currentFilter is CategoryFilter.All
    // Bitwarden pages use pull-to-search only; disable pull-to-sync behavior.
    val isBitwardenDatabaseView = false && when (currentFilter) {
        is CategoryFilter.BitwardenVault,
        is CategoryFilter.BitwardenFolderFilter,
        is CategoryFilter.BitwardenVaultStarred,
        is CategoryFilter.BitwardenVaultUncategorized -> true
        else -> false
    }
    val selectedBitwardenVaultId = when (val filter = currentFilter) {
        is CategoryFilter.BitwardenVault -> filter.vaultId
        is CategoryFilter.BitwardenFolderFilter -> filter.vaultId
        is CategoryFilter.BitwardenVaultStarred -> filter.vaultId
        is CategoryFilter.BitwardenVaultUncategorized -> filter.vaultId
        else -> null
    }
    val selectedKeePassDatabaseId = when (val filter = currentFilter) {
        is CategoryFilter.KeePassDatabase -> filter.databaseId
        is CategoryFilter.KeePassGroupFilter -> filter.databaseId
        is CategoryFilter.KeePassDatabaseStarred -> filter.databaseId
        is CategoryFilter.KeePassDatabaseUncategorized -> filter.databaseId
        else -> null
    }
    val keepassGroupsForSelectedDbFlow = remember(selectedKeePassDatabaseId, localKeePassViewModel) {
        selectedKeePassDatabaseId?.let { databaseId ->
            localKeePassViewModel.getGroups(databaseId)
        } ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }
    val keepassGroupsForSelectedDb by keepassGroupsForSelectedDbFlow.collectAsState(initial = emptyList())
    val isKeePassDatabaseView = selectedKeePassDatabaseId != null
    val selectedKeePassDatabase = remember(selectedKeePassDatabaseId, keepassDatabases) {
        selectedKeePassDatabaseId?.let { databaseId ->
            keepassDatabases.find { it.id == databaseId }
        }
    }
    // 只在选中数据库切换（id 变化）时触发一次自动同步；
    // 不再以 lastSyncStatus 作为 key，避免同步完成→状态变更→再次触发形成循环，
    // 也避免切换设置导致列表重建时误触发同步。
    LaunchedEffect(selectedKeePassDatabase?.id) {
        val database = selectedKeePassDatabase ?: return@LaunchedEffect
        if (database.isRemoteSource()) {
            localKeePassViewModel.autoSyncVisibleRemoteDatabase(database.id)
        }
    }
    val selectedKeePassSyncStateFlow = remember(selectedKeePassDatabaseId, localKeePassViewModel) {
        selectedKeePassDatabaseId?.let { databaseId ->
            localKeePassViewModel.getRemoteSyncState(databaseId)
        } ?: kotlinx.coroutines.flow.flowOf(null)
    }
    val selectedKeePassRemoteSyncState by selectedKeePassSyncStateFlow.collectAsState(initial = null)
    val selectedKeePassCoordinatorStatusFlow = remember(selectedKeePassDatabaseId) {
        if (selectedKeePassDatabaseId == null) {
            kotlinx.coroutines.flow.flowOf(null)
        } else {
            SyncTaskRunner.observe(SyncKey("keepass_visible_remote"))
        }
    }
    val visibleKeePassRemoteCoordinatorStatus by selectedKeePassCoordinatorStatusFlow.collectAsState(initial = null)
    val selectedKeePassCoordinatorStatus = remember(
        selectedKeePassDatabaseId,
        visibleKeePassRemoteCoordinatorStatus
    ) {
        val selectedId = selectedKeePassDatabaseId ?: return@remember null
        visibleKeePassRemoteCoordinatorStatus?.takeIf { status ->
            (status.target as? SyncTarget.KeePassDatabase)?.databaseId == selectedId
        }
    }
    // Activity 级共享实例：默认的 viewModel() 会按 NavBackStackEntry 各建一个，
    // 导致 BitwardenViewModel 出现多个副本（详见 activityViewModel 文档）。
    val bitwardenViewModel: com.bastion.app.bitwarden.viewmodel.BitwardenViewModel = activityViewModel()
    val bitwardenSyncStatusByVault by bitwardenViewModel.syncStatusByVault.collectAsState()
    val selectedBitwardenFoldersFlow = remember(selectedBitwardenVaultId, viewModel) {
        selectedBitwardenVaultId?.let(viewModel::getBitwardenFolders)
            ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }
    val selectedBitwardenFolders by selectedBitwardenFoldersFlow.collectAsState(initial = emptyList())
    val isTopBarSyncing = selectedBitwardenVaultId?.let { vaultId ->
        bitwardenSyncStatusByVault[vaultId].isUserVisibleSyncInProgress()
    } == true
    val quickStatusBitwardenSyncState = remember(
        selectedBitwardenVaultId,
        bitwardenSyncStatusByVault,
        bitwardenVaults
    ) {
        val vaultId = selectedBitwardenVaultId ?: return@remember null
        val status = bitwardenSyncStatusByVault[vaultId] ?: return@remember null
        if (!status.isUserVisibleSyncInProgress()) return@remember null
        val vaultName = bitwardenVaults
            .firstOrNull { it.id == vaultId }
            ?.displayName
            ?.takeIf { it.isNotBlank() }
            ?: bitwardenVaults.firstOrNull { it.id == vaultId }?.email
            ?: "Bitwarden"
        QuickStatusBitwardenSyncState(
            vaultName = vaultName,
            isRunning = status.isRunning
        )
    }
    val quickStatusKeePassSyncState = remember(
        selectedKeePassDatabase,
        selectedKeePassRemoteSyncState,
        selectedKeePassCoordinatorStatus,
        activeManualSyncDatabaseIds,
        localKeePassViewModel
    ) {
        val database = selectedKeePassDatabase
        if (database == null || !database.isRemoteSource()) {
            null
        } else {
            val phase = selectedKeePassRemoteSyncState?.syncPhase
            val coordinatorPhase = selectedKeePassCoordinatorStatus?.phase
            val coordinatorShouldShow = coordinatorPhase in setOf(
                SyncPhase.RUNNING,
                SyncPhase.BLOCKED,
                SyncPhase.CONFLICT
            )
            val isConflict = database.lastSyncStatus == KeePassSyncStatus.CONFLICT ||
                coordinatorPhase == SyncPhase.CONFLICT
            // 仅"手动 sync"才弹"正在同步"对话框；CONFLICT 状态无论自动 / 手动都必须弹（用户需要解决冲突）。
            val triggerIsManual = database.id in activeManualSyncDatabaseIds
            val shouldShow = isConflict || (triggerIsManual && (
                database.lastSyncStatus in setOf(
                    KeePassSyncStatus.PENDING_UPLOAD,
                    KeePassSyncStatus.SYNCING,
                    KeePassSyncStatus.REMOTE_CHANGED
                ) || phase in setOf(
                    KeePassSyncPhase.COMPARING,
                    KeePassSyncPhase.DOWNLOADING,
                    KeePassSyncPhase.UPLOADING
                ) || coordinatorShouldShow
            ))
            if (!shouldShow) {
                null
            } else {
                QuickStatusKeePassSyncState(
                    databaseId = database.id,
                    databaseName = database.name,
                    status = database.lastSyncStatus,
                    phase = phase,
                    coordinatorPhase = coordinatorPhase,
                    coordinatorErrorKind = selectedKeePassCoordinatorStatus?.lastError?.kind,
                    onSync = {
                        localKeePassViewModel.syncRemoteDatabase(database.id)
                    }
                )
            }
        }
    }

    // 切后台时自动抑制"正在同步"对话框（等价于用户点"后台继续"）。
    // CONFLICT 状态时仍可绕过该抑制重新弹起（见下方 LaunchedEffect mustShow 路径）。
    val suppressLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(suppressLifecycleOwner, quickStatusKeePassSyncState?.dialogSuppressionKey()) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                quickStatusKeePassSyncState?.let { state ->
                    dialogState.backgroundedKeePassSyncKey = state.dialogSuppressionKey()
                }
            }
        }
        suppressLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { suppressLifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isArchiveView = currentFilter is CategoryFilter.Archived
    val effectiveGroupMode = if (isLocalOnlyView) "none" else groupMode
    val effectiveStackCardMode = if (isLocalOnlyView) {
        StackCardMode.ALWAYS_EXPANDED
    } else {
        stackCardMode
    }
    val quickFoldersEnabledForCurrentFilter = false
    val quickFolderPathBannerEnabledForCurrentFilter =
        appSettings.passwordListQuickFolderPathBannerEnabled && !isAllView
    val quickStatusBannerEnabled = quickFolderPathBannerEnabledForCurrentFilter
    LaunchedEffect(quickStatusTransferState?.operationId, quickStatusBannerEnabled) {
        val state = quickStatusTransferState
        if (state == null) {
            dialogState.showQuickStatusTransferDialog = false
            dialogState.backgroundedTransferOperationId = null
            return@LaunchedEffect
        }
        if (!quickStatusBannerEnabled && state.operationId != dialogState.backgroundedTransferOperationId) {
            dialogState.showQuickStatusTransferDialog = true
        }
    }
    LaunchedEffect(quickStatusDeleteState?.operationId, quickStatusBannerEnabled) {
        val state = quickStatusDeleteState
        if (state == null) {
            dialogState.showQuickStatusDeleteDialog = false
            dialogState.backgroundedDeleteOperationId = null
            return@LaunchedEffect
        }
        if (!quickStatusBannerEnabled && state.operationId != dialogState.backgroundedDeleteOperationId) {
            dialogState.showQuickStatusDeleteDialog = true
        }
    }
    LaunchedEffect(
        quickStatusKeePassSyncState?.databaseId,
        quickStatusKeePassSyncState?.status,
        quickStatusKeePassSyncState?.phase,
        quickStatusKeePassSyncState?.coordinatorPhase,
        quickStatusKeePassSyncState?.coordinatorErrorKind,
        quickStatusBannerEnabled
    ) {
        val state = quickStatusKeePassSyncState
        if (state == null) {
            dialogState.showQuickStatusKeePassSyncDialog = false
            dialogState.backgroundedKeePassSyncKey = null
            return@LaunchedEffect
        }
        val stateKey = state.dialogSuppressionKey()
        // CONFLICT 状态必须弹（绕过 onPause 自动抑制与 backgroundedKey 检查），用户需要解决冲突。
        val mustShow = state.status == KeePassSyncStatus.CONFLICT ||
            state.coordinatorPhase == SyncPhase.CONFLICT
        if (mustShow || (!quickStatusBannerEnabled && stateKey != dialogState.backgroundedKeePassSyncKey)) {
            dialogState.showQuickStatusKeePassSyncDialog = true
        }
    }
    
    // 选择模式状态
    val selectionState = rememberPasswordListSelectionState()
    
    // 详情对话框状态
    
    
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? FragmentActivity
    val biometricHelper = remember { BiometricHelper(context) }
    // 必须缓存：isBiometricAvailable() 内部是 canAuthenticate()，每次调用都要跨进程
    // Binder 打 BiometricService。不缓存的话会在**每次重组**执行一次——实测密码页常驻
    // 前台时高达 81 次/秒（15 秒采样 1218 次），是实打实的耗电点。
    // 只在 activity / biometricEnabled 变化时才需要重算。
    val canUseBiometric = remember(activity, appSettings.biometricEnabled) {
        activity != null && appSettings.biometricEnabled && biometricHelper.isBiometricAvailable()
    }
    val database = remember { com.bastion.app.data.PasswordDatabase.getDatabase(context) }
    val attachmentParentIds by database.attachmentDao()
        .observeParentsWithActiveAttachments()
        .collectAsState(initial = emptyList())
    val activeAttachmentParentIds = remember(attachmentParentIds) {
        attachmentParentIds.toSet()
    }
    val bitwardenRepository = remember { com.bastion.app.bitwarden.repository.BitwardenRepository.getInstance(context) }
    val aggregateStackRepository = remember(database) {
        com.bastion.app.repository.PasswordPageAggregateStackRepository(
            database.passwordPageAggregateStackDao()
        )
    }
    val aggregateStackEntries by aggregateStackRepository.observeAll().collectAsState(initial = emptyList())

    // Top actions menu and display options sheet state
    var topActionsMenuExpanded by remember { mutableStateOf(false) }
    var showDisplayOptionsSheet by remember { mutableStateOf(false) }
    LaunchedEffect(isAuthenticated) {
        if (!isAuthenticated) {
            topActionsMenuExpanded = false
        }
    }
    // Search state hoisted for morphing animation
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }

    // 如果搜索框展开，按返回键关闭搜索框
    val focusManager = LocalFocusManager.current
    BackHandler(enabled = isSearchExpanded) {
        isSearchExpanded = false
        viewModel.updateSearchQuery("")
        focusManager.clearFocus()
    }

    // Handle back press for selection mode
    BackHandler(enabled = selectionState.isSelectionMode) {
        selectionState.isSelectionMode = false
        selectionState.selectedItemKeys = emptySet()
        selectionState.swipeSelectionAnchorKey = null
    }

    // 在归档页按返回键时，先退出归档回到密码主列表
    BackHandler(enabled = isArchiveView && !selectionState.isSelectionMode && !isSearchExpanded) {
        viewModel.closeArchiveView()
    }
    // Category sheet state
    var isCategorySheetVisible by rememberSaveable { mutableStateOf(false) }
    var categoryPillBoundsInWindow by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }

    LaunchedEffect(isArchiveView) {
        if (isArchiveView && isCategorySheetVisible) {
            isCategorySheetVisible = false
        }
    }

    LaunchedEffect(aggregateStackRepository) {
        aggregateStackRepository.pruneDegenerateGroups()
    }
    
    // 添加触觉反馈
    val haptic = rememberHapticFeedback()
    val density = androidx.compose.ui.platform.LocalDensity.current
    
    // Pull-to-search/sync state (shared implementation)
    val triggerDistance = remember(density) { with(density) { 40.dp.toPx() } }
    val syncTriggerDistance = remember(density) { with(density) { 72.dp.toPx() } }
    val maxDragDistance = remember(density) { with(density) { 100.dp.toPx() } }
    val pullAction = rememberPullActionState(
        isBitwardenDatabaseView = isBitwardenDatabaseView,
        isSearchExpanded = isSearchExpanded,
        searchTriggerDistance = triggerDistance,
        syncTriggerDistance = syncTriggerDistance,
        maxDragDistance = maxDragDistance,
        bitwardenRepository = bitwardenRepository,
        onSearchTriggered = { isSearchExpanded = true }
    )
    
    // 添加单项删除对话框状态
    
    // 添加已删除项ID集合（用于在验证前隐藏项）
    var deletedItemIds by remember { mutableStateOf(setOf<Long>()) }
    
    // 堆叠展开状态 - 记录哪些分组已展开（托管到 ViewModel，导航返回后保持）
    val expandedGroups by viewModel.expandedGroups.collectAsState()
    // 15 个快捷筛选开关 + WIFI/SSH/条码的存在性派生值，已下沉到独立组合函数。
    val quickFilterToggles = rememberPasswordListQuickFilterToggles(passwordEntries)
    val configuredQuickFilterItems = rememberPasswordListConfiguredQuickFilterItems(
        appSettings = appSettings,
        aggregateUiState = aggregateUiState,
        quickFilterToggles = quickFilterToggles
    )
    val quickFolderStyle = appSettings.passwordListQuickFolderStyle
    var quickFolderRootKey by rememberSaveable {
        mutableStateOf(currentFilter.toQuickFolderRootKeyOrNull() ?: QUICK_FOLDER_ROOT_ALL)
    }
    val outsideTapInteractionSource = remember { MutableInteractionSource() }
    val canCollapseExpandedGroups = effectiveStackCardMode == StackCardMode.AUTO && expandedGroups.isNotEmpty()

    // 当分组模式改变时,重置展开状态（初始值用 null 标记，重建时不误清空）
    val prevGroupMode = remember { mutableStateOf<String?>(null) }
    val prevStackCardMode = remember { mutableStateOf<StackCardMode?>(null) }
    LaunchedEffect(effectiveGroupMode, effectiveStackCardMode) {
        val prev1 = prevGroupMode.value
        val prev2 = prevStackCardMode.value
        if (prev1 != null && prev2 != null &&
            (effectiveGroupMode != prev1 || effectiveStackCardMode != prev2)) {
            viewModel.clearExpandedGroups()
        }
        prevGroupMode.value = effectiveGroupMode
        prevStackCardMode.value = effectiveStackCardMode
    }

    LaunchedEffect(currentFilter) {
        currentFilter.toQuickFolderRootKeyOrNull()?.let { key ->
            quickFolderRootKey = key
        }
    }

    val quickFolderUiState = rememberPasswordListQuickFolderUiState(
        context = context,
        appSettings = appSettings,
        currentFilter = currentFilter,
        categories = categories,
        searchQuery = searchQuery,
        passwordEntries = passwordEntries,
        allPasswords = allPasswords,
        keepassDatabases = keepassDatabases,
        keepassGroupsForSelectedDb = keepassGroupsForSelectedDb,
        bitwardenVaults = bitwardenVaults,
        selectedBitwardenFolders = selectedBitwardenFolders,
        quickFolderRootKey = quickFolderRootKey,
        quickFoldersEnabledForCurrentFilter = quickFoldersEnabledForCurrentFilter,
        quickFolderPathBannerEnabledForCurrentFilter = quickFolderPathBannerEnabledForCurrentFilter
    )
    val quickFolderSystemBackTarget = quickFolderUiState.systemBackTarget
    val quickFolderShortcuts = quickFolderUiState.shortcuts
    val categoryMenuQuickFolderShortcuts = quickFolderUiState.categoryMenuShortcuts
    val quickFolderBreadcrumbs = quickFolderUiState.breadcrumbs

    BackHandler(
        enabled = !isSearchExpanded &&
            !selectionState.isSelectionMode &&
            !isArchiveView &&
            quickFolderSystemBackTarget != null
    ) {
        viewModel.setCategoryFilter(requireNotNull(quickFolderSystemBackTarget))
    }

    val shouldLoadManualStackMetadata =
        effectiveStackCardMode != StackCardMode.ALWAYS_EXPANDED ||
            quickFilterToggles.manualStackOnly ||
            quickFilterToggles.neverStack ||
            quickFilterToggles.unstacked
    val manualStackMeta = rememberPasswordListManualStackMeta(
        passwordEntries = passwordEntries,
        deletedItemIds = deletedItemIds,
        shouldLoadManualStackMetadata = shouldLoadManualStackMetadata,
        viewModel = viewModel
    )
    val emptyStateMessage = remember(
        currentFilter,
        quickFoldersEnabledForCurrentFilter,
        quickFolderShortcuts,
        appSettings.passwordListCategoryQuickFiltersEnabled,
        categoryMenuQuickFolderShortcuts
    ) {
        resolvePasswordListEmptyStateMessage(
            currentFilter = currentFilter,
            quickFoldersEnabledForCurrentFilter = quickFoldersEnabledForCurrentFilter,
            hasQuickFolderShortcuts =
                quickFolderShortcuts.isNotEmpty() ||
                    (
                        appSettings.passwordListCategoryQuickFiltersEnabled &&
                            categoryMenuQuickFolderShortcuts.isNotEmpty()
                        )
        )
    }
    val derivedFilters = rememberPasswordListDerivedFilters(
        passwordEntries = passwordEntries,
        deletedItemIds = deletedItemIds,
        quickFoldersEnabledForCurrentFilter = quickFoldersEnabledForCurrentFilter,
        currentFilter = currentFilter,
        configuredQuickFilterItems = configuredQuickFilterItems,
        quickFilterToggles = quickFilterToggles,
        activeAttachmentParentIds = activeAttachmentParentIds,
        manualStackMeta = manualStackMeta,
        aggregateUiState = aggregateUiState,
        effectiveStackCardMode = effectiveStackCardMode,
        aggregateStackEntries = aggregateStackEntries,
        isLocalOnlyView = isLocalOnlyView,
        effectiveGroupMode = effectiveGroupMode,
        appSettings = appSettings,
        context = context
    )
    LaunchedEffect(selectionState.isSelectionMode, selectionState.selectedItemKeys) {
        if (selectionState.isSelectionMode && selectionState.selectedItemKeys.isEmpty()) {
            selectionState.isSelectionMode = false
        }
        if (selectionState.selectedItemKeys.isEmpty()) {
            selectionState.swipeSelectionAnchorKey = null
        }
    }

    
    // 根据分组模式对密码进行分组（后台线程计算，避免阻塞首滑）
    var groupedPasswords by remember {
        mutableStateOf<Map<String, List<com.bastion.app.data.PasswordEntry>>>(emptyMap())
    }
    var hasGroupedPasswordsReadyForCurrentInputs by remember {
        mutableStateOf(false)
    }
    val visiblePasswordsForAutoGrouping = remember(
        derivedFilters.visiblePasswordEntries,
        derivedFilters.manualAggregateStackBuildResult.stackedPasswordIds
    ) {
        derivedFilters.visiblePasswordEntries.filter { it.id !in derivedFilters.manualAggregateStackBuildResult.stackedPasswordIds }
    }
    LaunchedEffect(
        visiblePasswordsForAutoGrouping,
        effectiveGroupMode,
        appSettings.passwordWebsiteStackMatchMode,
        effectiveStackCardMode,
        manualStackMeta.effectiveManualStackGroupByEntryId,
        manualStackMeta.effectiveNoStackEntryIds
    ) {
        val sourceEntries = visiblePasswordsForAutoGrouping
        if (sourceEntries.isEmpty()) {
            groupedPasswords = emptyMap()
            hasGroupedPasswordsReadyForCurrentInputs = true
            return@LaunchedEffect
        }
        hasGroupedPasswordsReadyForCurrentInputs = false
        groupedPasswords = withContext(Dispatchers.Default) {
            buildGroupedPasswordsForEntries(
                sourceEntries = sourceEntries,
                config = derivedFilters.groupingConfig
            )
        }
        hasGroupedPasswordsReadyForCurrentInputs = true
    }

    val groupedPasswordsForRender = remember(
        groupedPasswords,
        hasGroupedPasswordsReadyForCurrentInputs,
        visiblePasswordsForAutoGrouping
    ) {
        if (
            groupedPasswords.isEmpty() &&
            !hasGroupedPasswordsReadyForCurrentInputs &&
            visiblePasswordsForAutoGrouping.isNotEmpty()
        ) {
            visiblePasswordsForAutoGrouping
                .sortedBy { entry -> entry.sortOrder }
                .associate { entry -> "entry_${entry.id}" to listOf(entry) }
        } else {
            groupedPasswords
        }
    }

    val shouldRenderPasswordGroups = remember(aggregateUiState.displayedContentTypes) {
        PasswordPageContentType.PASSWORD in aggregateUiState.displayedContentTypes ||
            PasswordPageContentType.AUTHENTICATOR in aggregateUiState.displayedContentTypes ||
            PasswordPageContentType.PASSKEY in aggregateUiState.displayedContentTypes
    }
    val visiblePasswordIds = remember(visiblePasswordsForAutoGrouping) {
        visiblePasswordsForAutoGrouping.map(PasswordEntry::id)
    }
    val groupedPasswordIds = remember(groupedPasswordsForRender) {
        groupedPasswordsForRender.values.flatten().map(PasswordEntry::id)
    }
    // 首次进入页面后保持稳定态，避免目录切换/返回父级时重复触发首帧门控
    var hasCompletedInitialPasswordListStabilization by rememberSaveable {
        mutableStateOf(false)
    }
    val initialRenderState = remember(
        hasCompletedInitialPasswordListStabilization,
        passwordEntriesReady,
        allPasswordsReady,
        categoriesReady,
        shouldRenderPasswordGroups,
        visiblePasswordIds,
        groupedPasswordIds,
        aggregateUiState.displayedContentTypes,
        searchQuery
    ) {
        resolvePasswordListInitialRenderState(
            hasCompletedInitialPasswordListStabilization = hasCompletedInitialPasswordListStabilization,
            passwordEntriesReady = passwordEntriesReady,
            allPasswordsForUiReady = allPasswordsReady,
            categoriesReady = categoriesReady,
            shouldRenderPasswordGroups = shouldRenderPasswordGroups,
            visiblePasswordIds = visiblePasswordIds,
            groupedPasswordIds = groupedPasswordIds,
            displayedContentTypes = aggregateUiState.displayedContentTypes,
            searchQuery = searchQuery
        )
    }
    val isPasswordPageListModelReady = initialRenderState.isPasswordPageListModelReady
    LaunchedEffect(initialRenderState.isPasswordListDataLoaded, isPasswordPageListModelReady) {
        if (initialRenderState.isPasswordListDataLoaded && isPasswordPageListModelReady) {
            hasCompletedInitialPasswordListStabilization = true
        }
    }
    val shouldGateInitialPasswordFirstFrame = initialRenderState.shouldGateInitialContent
    val effectiveVisibleAggregateItems = remember(
        shouldGateInitialPasswordFirstFrame,
        derivedFilters.visibleAggregateItems,
        derivedFilters.manualAggregateStackBuildResult.stackedAggregateKeys
    ) {
        if (shouldGateInitialPasswordFirstFrame) {
            emptyList()
        } else {
            derivedFilters.visibleAggregateItems.filter { item ->
                item.key !in derivedFilters.manualAggregateStackBuildResult.stackedAggregateKeys
            }
        }
    }
    val effectiveQuickFolderShortcuts = remember(
        shouldGateInitialPasswordFirstFrame,
        quickFolderShortcuts
    ) {
        if (shouldGateInitialPasswordFirstFrame) emptyList() else quickFolderShortcuts
    }
    val effectiveCategoryQuickFilterShortcuts = remember(
        shouldGateInitialPasswordFirstFrame,
        appSettings.passwordListCategoryQuickFiltersEnabled,
        currentFilter,
        categoryMenuQuickFolderShortcuts
    ) {
        if (shouldGateInitialPasswordFirstFrame || !appSettings.passwordListCategoryQuickFiltersEnabled) {
            emptyList()
        } else {
            when (currentFilter) {
                is CategoryFilter.BitwardenVault,
                is CategoryFilter.BitwardenFolderFilter ->
                    categoryMenuQuickFolderShortcuts.filterNot { it.isBack }
                else -> categoryMenuQuickFolderShortcuts
            }
        }
    }
    val effectiveQuickFolderCardShortcuts = remember(
        appSettings.passwordListCategoryQuickFiltersEnabled,
        effectiveQuickFolderShortcuts
    ) {
        if (appSettings.passwordListCategoryQuickFiltersEnabled) {
            emptyList()
        } else {
            effectiveQuickFolderShortcuts
        }
    }
    val effectiveQuickFolderBreadcrumbs = remember(
        shouldGateInitialPasswordFirstFrame,
        quickFolderBreadcrumbs
    ) {
        if (shouldGateInitialPasswordFirstFrame) emptyList() else quickFolderBreadcrumbs
    }
    val passwordPageListItems = remember(
        aggregateUiState.displayedContentTypes,
        groupedPasswordsForRender,
        effectiveVisibleAggregateItems,
        effectiveGroupMode
    ) {
        buildPasswordPageListItems(
            selectedContentTypes = aggregateUiState.displayedContentTypes,
            groupedPasswords = groupedPasswordsForRender,
            supplementaryItems = effectiveVisibleAggregateItems,
            groupMode = effectiveGroupMode,
            manualStackGroups = derivedFilters.manualAggregateStackBuildResult.groups
        )
    }
    val passwordPageListItemKeys = remember(passwordPageListItems) {
        passwordPageListItems.map { item -> item.key }
    }
    val passwordPageListItemKeySet = remember(passwordPageListItemKeys) {
        passwordPageListItemKeys.toSet()
    }
    val visiblePageCards = remember(passwordPageListItems) {
        flattenPasswordPageCardItems(passwordPageListItems)
    }
    val visibleSelectableKeys = remember(visiblePageCards) {
        visiblePageCards.mapTo(linkedSetOf<String>()) { card -> card.key }
    }
    val selectedPageCards = remember(passwordPageListItems, selectionState.selectedItemKeys) {
        resolveSelectedPasswordPageCardItems(
            items = passwordPageListItems,
            selectedKeys = selectionState.selectedItemKeys
        )
    }
    val selectedPasswords = remember(selectedPageCards) {
        selectedPageCards.mapNotNullTo(linkedSetOf<Long>()) { card -> card.passwordId }
    }
    val selectedSupplementaryItems = remember(selectedPageCards) {
        selectedPageCards.mapNotNull { card -> card.toSelectedSupplementaryItemOrNull() }
    }
    val hasSelectedSupplementaryItems = remember(selectedSupplementaryItems) {
        selectedSupplementaryItems.isNotEmpty()
    }

    LaunchedEffect(visibleSelectableKeys) {
        if (selectionState.selectedItemKeys.isEmpty()) return@LaunchedEffect
        selectionState.selectedItemKeys = selectionState.selectedItemKeys.intersect(visibleSelectableKeys)
    }
    val hasVisibleQuickFilters = remember(
        configuredQuickFilterItems,
        aggregateUiState.visibleContentTypes,
        shouldGateInitialPasswordFirstFrame,
        quickFilterToggles.hasAnyWifiEntry,
        quickFilterToggles.hasAnySshKeyEntry,
        quickFilterToggles.hasAnyBarcodeEntry
    ) {
        if (shouldGateInitialPasswordFirstFrame) return@remember false
        val hasConfiguredChips = configuredQuickFilterItems.any { item ->
            shouldShowQuickFilterItem(item, aggregateUiState.visibleContentTypes)
        }
        // WIFI / SSH chip 无需 quickFilters 设置开关——"有数据就冒出来"语义。
        hasConfiguredChips || quickFilterToggles.hasAnyWifiEntry || quickFilterToggles.hasAnySshKeyEntry || quickFilterToggles.hasAnyBarcodeEntry
    }

    // 收纳为内建行为：快捷筛选横排的展开/收起状态由这里（共同父级）持有，
    // 因为 chip 横排在 PasswordListScrollableContent、触发按钮在 PasswordListTopSection 的标题，两者是兄弟组件。
    // 默认收起；用 SharedPreferences 持久化（rememberSaveable 只在「系统重建 Activity」时恢复，
    // 用户主动退出 APP 后再打开会丢失展开状态，所以这里落盘）。
val collapseContext = LocalContext.current
val collapsePrefs = remember(collapseContext) {
    collapseContext.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)
}
var quickFiltersExpanded by remember(collapsePrefs) {
    mutableStateOf(collapsePrefs.getBoolean("password_quick_filters_expanded", false))
}
LaunchedEffect(quickFiltersExpanded) {
    collapsePrefs.edit()
        .putBoolean("password_quick_filters_expanded", quickFiltersExpanded)
        .apply()
}
    val hasVisibleCategoryQuickFilters = remember(
        effectiveCategoryQuickFilterShortcuts
    ) {
        effectiveCategoryQuickFilterShortcuts.isNotEmpty()
    }
    val hasQuickStatusProgress =
        quickStatusTransferState != null ||
            quickStatusDeleteState != null ||
            quickStatusBitwardenSyncState != null
    val showPinnedQuickFolderPathBanner =
        quickStatusBannerEnabled &&
            (effectiveQuickFolderBreadcrumbs.isNotEmpty() || hasQuickStatusProgress)
    val hasScrollableHeaderContent = remember(
        hasVisibleQuickFilters,
        hasVisibleCategoryQuickFilters,
        effectiveQuickFolderCardShortcuts,
        showPinnedQuickFolderPathBanner
    ) {
        hasVisibleQuickFilters ||
            hasVisibleCategoryQuickFilters ||
            effectiveQuickFolderCardShortcuts.isNotEmpty() ||
            showPinnedQuickFolderPathBanner
    }
    val hasVisibleListItems = passwordPageListItems.isNotEmpty()
    val usesLazyColumn = remember(
        isPasswordPageListModelReady,
        hasVisibleListItems,
        hasScrollableHeaderContent,
        searchQuery,
        derivedFilters.visiblePasswordEntries,
        effectiveVisibleAggregateItems
    ) {
        if (!isPasswordPageListModelReady) {
            derivedFilters.visiblePasswordEntries.isNotEmpty() ||
                effectiveVisibleAggregateItems.isNotEmpty() ||
                hasScrollableHeaderContent ||
                searchQuery.isNotEmpty()
        } else {
            hasVisibleListItems || hasScrollableHeaderContent || searchQuery.isNotEmpty()
        }
    }
    val shouldShowEmptyState = remember(
        isPasswordPageListModelReady,
        usesLazyColumn,
        hasVisibleListItems,
        searchQuery,
        shouldGateInitialPasswordFirstFrame
    ) {
        isPasswordPageListModelReady &&
            usesLazyColumn &&
            !hasVisibleListItems &&
            searchQuery.isEmpty() &&
            !shouldGateInitialPasswordFirstFrame
    }
    val scrollState = rememberPasswordListScrollState(
        viewModel = viewModel,
        currentListItemKeys = passwordPageListItemKeys,
        scrollToTopRequestKey = scrollToTopRequestKey,
        fastScrollRequestKey = fastScrollRequestKey,
        fastScrollProgress = fastScrollProgress,
        allowScrollPositionPersistence =
            isPasswordPageListModelReady &&
                hasVisibleListItems &&
                !shouldGateInitialPasswordFirstFrame,
        onBackToTopVisibilityChange = onBackToTopVisibilityChange,
        shouldShowEmptyState = shouldShowEmptyState,
        usesLazyColumn = usesLazyColumn,
        currentFilter = currentFilter
    )

    val selectionHandlers = rememberPasswordListSelectionHandlers(
        context = context,
        coroutineScope = coroutineScope,
        viewModel = viewModel,
        selectedItemKeys = selectionState.selectedItemKeys,
        visibleSelectableKeys = visibleSelectableKeys,
        selectedPasswords = selectedPasswords,
        passwordEntries = passwordEntries,
        selectedSupplementaryItems = selectedSupplementaryItems,
        aggregateUiState = aggregateUiState,
        onClearSelection = {
            selectionState.isSelectionMode = false
            selectionState.selectedItemKeys = emptySet()
            selectionState.swipeSelectionAnchorKey = null
        },
        onSelectedItemKeysChange = {
            selectionState.selectedItemKeys = it
            if (it.isEmpty()) selectionState.swipeSelectionAnchorKey = null
        },
        onShowMoveToCategoryDialog = { dialogState.showMoveToCategoryDialog = true },
        onShowManualStackConfirmDialog = {
            dialogState.selectedManualStackMode = ManualStackDialogMode.STACK
            dialogState.showManualStackConfirmDialog = true
        },
        onShowBatchDeleteDialog = { dialogState.showBatchDeleteDialog = true }
    )

    BindPasswordListSelectionModeChange(
        isSelectionMode = selectionState.isSelectionMode,
        selectedItemKeys = selectionState.selectedItemKeys,
        selectedPasswords = selectedPasswords,
        selectedSupplementaryItems = selectedSupplementaryItems,
        handlers = selectionHandlers,
        onSelectionModeChange = onSelectionModeChange
    )

    PasswordBatchMoveSheetHost(
        visible = dialogState.showMoveToCategoryDialog,
        categories = categories,
        keepassDatabases = keepassDatabases,
        bitwardenVaults = bitwardenVaults,
        database = database,
        localKeePassViewModel = localKeePassViewModel,
        securityManager = securityManager,
        selectedPasswords = selectedPasswords,
        selectedSupplementaryItems = selectedSupplementaryItems,
        passwordEntries = passwordEntries,
        aggregateUiState = aggregateUiState,
        viewModel = viewModel,
        bitwardenRepository = bitwardenRepository,
        context = context,
        coroutineScope = coroutineScope,
        onRenameCategory = onRenameCategory,
        onDeleteCategory = onDeleteCategory,
        onDismiss = { dialogState.showMoveToCategoryDialog = false },
        onSelectionCleared = {
            selectionState.isSelectionMode = false
            selectionState.selectedItemKeys = emptySet()
            selectionState.swipeSelectionAnchorKey = null
        }
    )


    val decryptAuthenticatorKeyForPreview: (String) -> String = remember(securityManager) {
        { value: String ->
            runCatchingObserved { securityManager.decryptDataIfBastionCiphertext(value) }
                .getOrDefault(value)
        }
    }


    // 顶部 Bar 透明覆盖在列表之上（不再是上下排列）：
    // 列表从 Bar 底下穿过，Bar 自身无背景、可透出内容（参考系统相册）。
    // 列表顶部留 contentPadding = 展开态 Bar 高度，保证首屏第一条完整可见。
    Box(modifier = Modifier.fillMaxSize()) {
        PasswordListMainPaneSection(
            canCollapseExpandedGroups = canCollapseExpandedGroups,
            outsideTapInteractionSource = outsideTapInteractionSource,
            isBitwardenDatabaseView = isBitwardenDatabaseView,
            pullAction = pullAction,
            triggerDistance = triggerDistance,
            syncTriggerDistance = syncTriggerDistance,
            density = density,
            showPinnedQuickFolderPathBanner = showPinnedQuickFolderPathBanner,
            quickStatusTransferState = quickStatusTransferState,
            onShowQuickStatusTransferDialog = {
                dialogState.backgroundedTransferOperationId = null
                dialogState.showQuickStatusTransferDialog = true
            },
            quickStatusDeleteState = quickStatusDeleteState,
            onShowQuickStatusDeleteDialog = {
                dialogState.backgroundedDeleteOperationId = null
                dialogState.showQuickStatusDeleteDialog = true
            },
            quickStatusBitwardenSyncState = quickStatusBitwardenSyncState,
            quickStatusKeePassSyncState = quickStatusKeePassSyncState,
            currentFilter = currentFilter,
            shouldGateInitialPasswordFirstFrame = shouldGateInitialPasswordFirstFrame,
            searchQuery = searchQuery,
            isPasswordPageListModelReady = isPasswordPageListModelReady,
            hasVisibleListItems = hasVisibleListItems,
            hasScrollableHeaderContent = hasScrollableHeaderContent,
            hasVisibleQuickFilters = hasVisibleQuickFilters,
            quickFiltersExpanded = quickFiltersExpanded,
            hasVisibleCategoryQuickFilters = hasVisibleCategoryQuickFilters,
            aggregateUiState = aggregateUiState,
            emptyStateMessage = emptyStateMessage,
            listState = scrollState.listState,
            appSettings = appSettings,
            configuredQuickFilterItems = configuredQuickFilterItems,
            quickFolderStyle = quickFolderStyle,
            passwordPageListItems = passwordPageListItems,
            effectiveStackCardMode = effectiveStackCardMode,
            expandedGroups = expandedGroups,
            itemToDelete = dialogState.itemToDelete,
            onItemToDeleteChange = { dialogState.itemToDelete = it },
            isSelectionMode = selectionState.isSelectionMode,
            onSelectionModeChange = { selectionState.isSelectionMode = it },
            selectedItemKeys = selectionState.selectedItemKeys,
            onSelectedItemKeysChange = { selectionState.selectedItemKeys = it },
            swipeSelectionAnchorKey = selectionState.swipeSelectionAnchorKey,
            onSwipeSelectionAnchorKeyChange = { selectionState.swipeSelectionAnchorKey = it },
            selectedPasswords = selectedPasswords,
            showBatchDeleteDialog = dialogState.showBatchDeleteDialog,
            onShowBatchDeleteDialogChange = { dialogState.showBatchDeleteDialog = it },
            viewModel = viewModel,
            haptic = haptic,
            onPasswordClick = onPasswordClick,
            passwordPageListItemKeySet = passwordPageListItemKeySet,
            coroutineScope = coroutineScope,
            context = context,
            passwordEntries = passwordEntries,
            aggregateConfig = aggregateConfig,
            onNavigateToPasskeys = onNavigateToPasskeys,
            listTopPadding = scrollState.listTopPadding,
            quickFilterToggles = quickFilterToggles,
            effectiveQuickFolderBreadcrumbs = effectiveQuickFolderBreadcrumbs,
            showEmptyStateWithHeaders = scrollState.showEmptyStateWithHeaders,
            effectiveCategoryQuickFilterShortcuts = effectiveCategoryQuickFilterShortcuts,
            effectiveQuickFolderCardShortcuts = effectiveQuickFolderCardShortcuts,
            decryptAuthenticatorKeyForPreview = decryptAuthenticatorKeyForPreview
        )
        Box(modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter)) {
            PasswordListTopSectionHost(
                currentFilter = currentFilter,
                categories = categories,
                keepassDatabases = keepassDatabases,
                bitwardenVaults = bitwardenVaults,
                viewModel = viewModel,
                localKeePassViewModel = localKeePassViewModel,
                bitwardenViewModel = bitwardenViewModel,
                selectedBitwardenVaultId = selectedBitwardenVaultId,
                selectedKeePassDatabaseId = selectedKeePassDatabaseId,
                isTopBarSyncing = isTopBarSyncing,
                isArchiveView = isArchiveView,
                isKeePassDatabaseView = isKeePassDatabaseView,
                searchQuery = searchQuery,
                isSearchExpanded = isSearchExpanded,
                onSearchExpandedChange = { isSearchExpanded = it },
                topActionsMenuExpanded = topActionsMenuExpanded,
                onTopActionsMenuExpandedChange = { topActionsMenuExpanded = it },
                showStandaloneSettingsEntry = showStandaloneSettingsEntry,
                onOpenStandaloneSettings = onOpenStandaloneSettings,
                isCategorySheetVisible = isCategorySheetVisible,
                onCategorySheetVisibleChange = { isCategorySheetVisible = it },
                categoryPillBoundsInWindow = categoryPillBoundsInWindow,
                onCategoryPillBoundsChange = { categoryPillBoundsInWindow = it },
                showDisplayOptionsSheet = showDisplayOptionsSheet,
                onShowDisplayOptionsSheetChange = { showDisplayOptionsSheet = it },
                configuredQuickFilterItems = configuredQuickFilterItems,
                categoryMenuQuickFolderShortcuts = categoryMenuQuickFolderShortcuts,
                stackCardMode = stackCardMode,
                groupMode = groupMode,
                settingsViewModel = settingsViewModel,
                context = context,
                activity = activity,
                biometricHelper = biometricHelper,
                canUseBiometric = canUseBiometric,
                coroutineScope = coroutineScope,
                bitwardenRepository = bitwardenRepository,
                securityManager = securityManager,
                onRenameCategory = onRenameCategory,
                onDeleteCategory = onDeleteCategory,
                onOpenCommonAccountTemplates = onOpenCommonAccountTemplates,
                onOpenHistory = onOpenHistory,
                onOpenTrash = onOpenTrash,
                onScanFidoQr = onScanFidoQr,
                onTitleClick = { quickFiltersExpanded = !quickFiltersExpanded },
                quickFiltersExpanded = quickFiltersExpanded,
                onNavigateToPasskeys = onNavigateToPasskeys,
                scrollCollapseFraction = scrollState.scrollCollapseFraction,
                quickFilterToggles = quickFilterToggles,
                appSettings = appSettings,
                aggregateUiState = aggregateUiState,
                aggregateConfig = aggregateConfig,
                isAuthenticated = isAuthenticated
            )
        }
    }

    PasswordListQuickStatusDialogsHost(
        showQuickStatusTransferDialog = dialogState.showQuickStatusTransferDialog,
        quickStatusTransferState = quickStatusTransferState,
        onMoveTransferToBackground = {
            dialogState.backgroundedTransferOperationId = quickStatusTransferState?.operationId
            dialogState.showQuickStatusTransferDialog = false
        },
        showQuickStatusDeleteDialog = dialogState.showQuickStatusDeleteDialog,
        quickStatusDeleteState = quickStatusDeleteState,
        onMoveDeleteToBackground = {
            dialogState.backgroundedDeleteOperationId = quickStatusDeleteState?.operationId
            dialogState.showQuickStatusDeleteDialog = false
        },
        showQuickStatusKeePassSyncDialog = dialogState.showQuickStatusKeePassSyncDialog,
        quickStatusKeePassSyncState = quickStatusKeePassSyncState,
        onMoveKeePassSyncToBackground = { state ->
            dialogState.backgroundedKeePassSyncKey = state.dialogSuppressionKey()
            dialogState.showQuickStatusKeePassSyncDialog = false
        },
        onRunKeePassSyncNow = { state ->
            dialogState.backgroundedKeePassSyncKey = null
            state.onSync()
            dialogState.showQuickStatusKeePassSyncDialog = false
        }
    )
    
    PasswordListDialogsHost(
        showManualStackConfirmDialog = dialogState.showManualStackConfirmDialog,
        onShowManualStackConfirmDialogChange = { dialogState.showManualStackConfirmDialog = it },
        selectedItemKeys = selectionState.selectedItemKeys,
        selectedPasswords = selectedPasswords,
        selectedManualStackMode = dialogState.selectedManualStackMode,
        onSelectedManualStackModeChange = { dialogState.selectedManualStackMode = it },
        onApplyManualStackMode = { dialogMode, itemKeys, passwordIds ->
            val validItemKeys = itemKeys.filterTo(linkedSetOf()) { it.isNotBlank() }
            if (
                validItemKeys.size < 2 ||
                validItemKeys.size != passwordIds.size
            ) {
                0
            } else {
                val mode = when (dialogMode) {
                    ManualStackDialogMode.STACK -> PasswordViewModel.ManualStackMode.STACK
                    ManualStackDialogMode.AUTO_STACK -> PasswordViewModel.ManualStackMode.AUTO_STACK
                    ManualStackDialogMode.NEVER_STACK -> PasswordViewModel.ManualStackMode.NEVER_STACK
                }
                viewModel.applyManualStackMode(passwordIds.toList(), mode)
                passwordIds.size
            }
        },
        viewModel = viewModel,
        context = context,
        coroutineScope = coroutineScope,
        onDeleteSelection = { onProgress ->
            val selectedPasswordIdsSnapshot = selectedPasswords.toSet()
            val selectedSupplementaryItemsSnapshot = selectedSupplementaryItems.toList()
            val selectedItemKeysSnapshot = selectionState.selectedItemKeys.toList()
            val selectedPasswordEntries = passwordEntries.filter { it.id in selectedPasswordIdsSnapshot }
            val totalToProcess = selectedPasswordEntries.size + selectedSupplementaryItemsSnapshot.size
            var processedCount = 0
            onProgress(processedCount, totalToProcess.coerceAtLeast(1))
            if (selectedItemKeysSnapshot.isNotEmpty()) {
                coroutineScope.launch {
                    aggregateStackRepository.clearManualStack(selectedItemKeysSnapshot)
                }
            }
            val deletedPasswordCount = viewModel.deletePasswordEntriesBatch(selectedPasswordEntries) { processed, _ ->
                processedCount = processed.coerceIn(0, selectedPasswordEntries.size)
                onProgress(processedCount, totalToProcess.coerceAtLeast(1))
            }

            selectedSupplementaryItemsSnapshot.forEach { item ->
                when (item.type) {
                    PasswordPageContentType.AUTHENTICATOR -> {
                        aggregateUiState.totpItems
                            .firstOrNull { it.id == item.secureItemId }
                            ?.let { aggregateUiState.totpViewModel?.deleteTotpItem(it) }
                    }

                    PasswordPageContentType.CARD_WALLET -> {
                        item.secureItemId?.let { id ->
                            when (item.walletItemType) {
                                PasswordAggregateWalletItemType.BANK_CARD ->
                                    aggregateUiState.bankCardViewModel?.deleteCard(id)
                                PasswordAggregateWalletItemType.DOCUMENT ->
                                    aggregateUiState.documentViewModel?.deleteDocument(id)
                                PasswordAggregateWalletItemType.BILLING_ADDRESS ->
                                    aggregateUiState.billingAddressViewModel?.deleteAddress(id)
                                null -> Unit
                            }
                        }
                    }

                    PasswordPageContentType.NOTE -> {
                        aggregateUiState.notes
                            .firstOrNull { it.id == item.secureItemId }
                            ?.let { aggregateUiState.noteViewModel?.deleteNote(it) }
                    }

                    PasswordPageContentType.PASSKEY -> {
                        item.passkeyRecordId?.let { recordId ->
                            aggregateUiState.passkeyViewModel?.deletePasskeyByRecordId(recordId)
                        }
                    }

                    PasswordPageContentType.PASSWORD -> Unit
                }
                processedCount = (processedCount + 1).coerceAtMost(totalToProcess.coerceAtLeast(1))
                onProgress(processedCount, totalToProcess.coerceAtLeast(1))
            }

            deletedPasswordCount + selectedSupplementaryItemsSnapshot.size
        },
        onBatchDeleteStarted = {
            selectionState.isSelectionMode = false
            selectionState.selectedItemKeys = emptySet()
            selectionState.swipeSelectionAnchorKey = null
        },
        onSelectionCleared = {
            selectionState.isSelectionMode = false
            selectionState.selectedItemKeys = emptySet()
            selectionState.swipeSelectionAnchorKey = null
        },
        showBatchDeleteDialog = dialogState.showBatchDeleteDialog,
        onShowBatchDeleteDialogChange = { dialogState.showBatchDeleteDialog = it },
        passwordInput = dialogState.passwordInput,
        onPasswordInputChange = {
            dialogState.passwordInput = it
            dialogState.passwordError = false
        },
        passwordError = dialogState.passwordError,
        onPasswordErrorChange = { dialogState.passwordError = it },
        canUseBiometric = canUseBiometric,
        activity = activity,
        biometricHelper = biometricHelper,
        itemToDelete = dialogState.itemToDelete,
        onItemToDeleteChange = { dialogState.itemToDelete = it },
        appSettings = appSettings,
        singleItemPasswordInput = dialogState.singleItemPasswordInput,
        onSingleItemPasswordInputChange = { dialogState.singleItemPasswordInput = it },
        showSingleItemPasswordVerify = dialogState.showSingleItemPasswordVerify,
        onShowSingleItemPasswordVerifyChange = { dialogState.showSingleItemPasswordVerify = it },
        passwordEntries = passwordEntries,
        selectedSupplementaryItems = selectedSupplementaryItems
    )
}

/**
 * 密码页「快捷筛选」的 15 个开关，外加 WIFI / SSH / 条码三个"库里是否存在对应条目"的派生值。
 *
 * 这几个值原先全部内联在 [PasswordListContent] 中，是该函数膨胀的组成部分之一。
 * 详见 [rememberPasswordListQuickFilterToggles] 的说明。
 */
private class PasswordListQuickFilterToggles {
    var favorite: Boolean = false
    var onFavoriteChange: (Boolean) -> Unit = {}
    var twoFa: Boolean = false
    var onTwoFaChange: (Boolean) -> Unit = {}
    var notes: Boolean = false
    var onNotesChange: (Boolean) -> Unit = {}
    var passkey: Boolean = false
    var onPasskeyChange: (Boolean) -> Unit = {}
    var boundNote: Boolean = false
    var onBoundNoteChange: (Boolean) -> Unit = {}
    var attachments: Boolean = false
    var onAttachmentsChange: (Boolean) -> Unit = {}
    var uncategorized: Boolean = false
    var onUncategorizedChange: (Boolean) -> Unit = {}
    var localOnly: Boolean = false
    var onLocalOnlyChange: (Boolean) -> Unit = {}
    var manualStackOnly: Boolean = false
    var onManualStackOnlyChange: (Boolean) -> Unit = {}
    var neverStack: Boolean = false
    var onNeverStackChange: (Boolean) -> Unit = {}
    var unstacked: Boolean = false
    var onUnstackedChange: (Boolean) -> Unit = {}
    var wifi: Boolean = false
    var onWifiChange: (Boolean) -> Unit = {}
    var sshKey: Boolean = false
    var onSshKeyChange: (Boolean) -> Unit = {}
    var barcode: Boolean = false
    var onBarcodeChange: (Boolean) -> Unit = {}
    var hasAnyWifiEntry: Boolean = false
    var hasAnySshKeyEntry: Boolean = false
    var hasAnyBarcodeEntry: Boolean = false
}

/**
 * 持有 [PasswordListQuickFilterToggles] 的组合函数。
 *
 * 为什么要拆：`PasswordListContent` 编译后达 18895 条指令，超过 ART 的编译上限，
 * 于是**永远无法被 JIT 编译、只能解释执行**，且每次尝试编译都会打出一条
 * `Method exceeds compiler instruction limit`（日志里刷了 428 次）。这是拆分该巨型
 * Composable 的第一步，优先搬走只依赖 `passwordEntries` 的这块内聚状态。
 *
 * 各开关仍用 rememberSaveable，屏幕旋转等重建场景的恢复行为与拆分前一致。
 */
@Composable
private fun rememberPasswordListQuickFilterToggles(
    passwordEntries: List<com.bastion.app.data.PasswordEntry>
): PasswordListQuickFilterToggles {
    var favorite by rememberSaveable { mutableStateOf(false) }
    var twoFa by rememberSaveable { mutableStateOf(false) }
    var notes by rememberSaveable { mutableStateOf(false) }
    var passkey by rememberSaveable { mutableStateOf(false) }
    var boundNote by rememberSaveable { mutableStateOf(false) }
    var attachments by rememberSaveable { mutableStateOf(false) }
    var uncategorized by rememberSaveable { mutableStateOf(false) }
    var localOnly by rememberSaveable { mutableStateOf(false) }
    var manualStackOnly by rememberSaveable { mutableStateOf(false) }
    var neverStack by rememberSaveable { mutableStateOf(false) }
    var unstacked by rememberSaveable { mutableStateOf(false) }
    // WIFI 筛选走"按需出现"分支：只有数据库里存在 WIFI 条目才渲染 chip，
    // 也不写进用户的 [com.bastion.app.data.PasswordListQuickFilterItem] 清单，避免污染备份结构。
    var wifi by rememberSaveable { mutableStateOf(false) }
    val hasAnyWifiEntry = remember(passwordEntries) {
        passwordEntries.any { it.isWifiEntry() }
    }
    LaunchedEffect(hasAnyWifiEntry) {
        if (!hasAnyWifiEntry) wifi = false
    }
    // SSH 密钥筛选：与 WIFI 同样按需出现，不进入 [com.bastion.app.data.PasswordListQuickFilterItem] 清单。
    var sshKey by rememberSaveable { mutableStateOf(false) }
    val hasAnySshKeyEntry = remember(passwordEntries) {
        passwordEntries.any { it.isSshKeyEntry() }
    }
    LaunchedEffect(hasAnySshKeyEntry) {
        if (!hasAnySshKeyEntry) sshKey = false
    }
    var barcode by rememberSaveable { mutableStateOf(false) }
    val hasAnyBarcodeEntry = remember(passwordEntries) {
        passwordEntries.any { it.isBarcodeEntry() }
    }
    LaunchedEffect(hasAnyBarcodeEntry) {
        if (!hasAnyBarcodeEntry) barcode = false
    }

    val toggles = remember { PasswordListQuickFilterToggles() }
    toggles.favorite = favorite
    toggles.onFavoriteChange = { favorite = it }
    toggles.twoFa = twoFa
    toggles.onTwoFaChange = { twoFa = it }
    toggles.notes = notes
    toggles.onNotesChange = { notes = it }
    toggles.passkey = passkey
    toggles.onPasskeyChange = { passkey = it }
    toggles.boundNote = boundNote
    toggles.onBoundNoteChange = { boundNote = it }
    toggles.attachments = attachments
    toggles.onAttachmentsChange = { attachments = it }
    toggles.uncategorized = uncategorized
    toggles.onUncategorizedChange = { uncategorized = it }
    toggles.localOnly = localOnly
    toggles.onLocalOnlyChange = { localOnly = it }
    toggles.manualStackOnly = manualStackOnly
    toggles.onManualStackOnlyChange = { manualStackOnly = it }
    toggles.neverStack = neverStack
    toggles.onNeverStackChange = { neverStack = it }
    toggles.unstacked = unstacked
    toggles.onUnstackedChange = { unstacked = it }
    toggles.wifi = wifi
    toggles.onWifiChange = { wifi = it }
    toggles.sshKey = sshKey
    toggles.onSshKeyChange = { sshKey = it }
    toggles.barcode = barcode
    toggles.onBarcodeChange = { barcode = it }
    toggles.hasAnyWifiEntry = hasAnyWifiEntry
    toggles.hasAnySshKeyEntry = hasAnySshKeyEntry
    toggles.hasAnyBarcodeEntry = hasAnyBarcodeEntry
    return toggles
}

/**
 * 持有"快捷筛选可见项清单"的组合函数，并把"清单里不存在的项目要归零"的
 * 重置副作用一并搬进来（清单变化与开关归零天然内聚）。
 *
 * 为什么要拆：同 [rememberPasswordListQuickFilterToggles] —— `PasswordListContent`
 * 编译后指令数超过 ART 上限，永远无法被 JIT 编译、只能解释执行。这是拆分该巨型
 * Composable 的第二步，搬走 11 行声明 + 35 行重置 effect。
 *
 * 行为与拆分前严格等价：`remember` 的 key、effect 的依赖与判定顺序均未改动。
 */
@Composable
private fun rememberPasswordListConfiguredQuickFilterItems(
    appSettings: AppSettings,
    aggregateUiState: PasswordListAggregateUiState,
    quickFilterToggles: PasswordListQuickFilterToggles
): List<PasswordListQuickFilterItem> {
    val configuredQuickFilterItems = remember(
        appSettings.passwordPageAggregateEnabled,
        aggregateUiState.visibleContentTypes
    ) {
        appendAggregateContentQuickFilterItems(
            configuredItems = resolvedQuickFilterBaseItems(appSettings.passwordListQuickFilterItems),
            visibleTypes = aggregateUiState.visibleContentTypes,
            aggregateEnabled = appSettings.passwordPageAggregateEnabled,
            includePasskeyChip = false
        )
    }
    LaunchedEffect(configuredQuickFilterItems) {
        if (PasswordListQuickFilterItem.FAVORITE !in configuredQuickFilterItems) {
            quickFilterToggles.favorite = false
        }
        if (PasswordListQuickFilterItem.TWO_FA !in configuredQuickFilterItems) {
            quickFilterToggles.twoFa = false
        }
        if (PasswordListQuickFilterItem.NOTES !in configuredQuickFilterItems) {
            quickFilterToggles.notes = false
        }
        if (PasswordListQuickFilterItem.PASSKEY !in configuredQuickFilterItems) {
            quickFilterToggles.passkey = false
        }
        if (PasswordListQuickFilterItem.NOTE !in configuredQuickFilterItems) {
            quickFilterToggles.boundNote = false
        }
        if (PasswordListQuickFilterItem.ATTACHMENTS !in configuredQuickFilterItems) {
            quickFilterToggles.attachments = false
        }
        if (PasswordListQuickFilterItem.UNCATEGORIZED !in configuredQuickFilterItems) {
            quickFilterToggles.uncategorized = false
        }
        if (PasswordListQuickFilterItem.LOCAL_ONLY !in configuredQuickFilterItems) {
            quickFilterToggles.localOnly = false
        }
        if (PasswordListQuickFilterItem.MANUAL_STACK_ONLY !in configuredQuickFilterItems) {
            quickFilterToggles.manualStackOnly = false
        }
        if (PasswordListQuickFilterItem.NEVER_STACK !in configuredQuickFilterItems) {
            quickFilterToggles.neverStack = false
        }
        if (PasswordListQuickFilterItem.UNSTACKED !in configuredQuickFilterItems) {
            quickFilterToggles.unstacked = false
        }
    }
    return configuredQuickFilterItems
}

@Composable
private fun PasswordListMainPaneHost(
    canCollapseExpandedGroups: Boolean,
    outsideTapInteractionSource: MutableInteractionSource,
    onCollapseExpandedGroups: () -> Unit,
    isBitwardenDatabaseView: Boolean,
    pullAction: com.bastion.app.ui.common.pull.PullActionStateHandle,
    triggerDistance: Float,
    syncTriggerDistance: Float,
    density: androidx.compose.ui.unit.Density,
    showPinnedQuickFolderPathBanner: Boolean,
    quickFolderBreadcrumbs: List<PasswordQuickFolderBreadcrumb>,
    quickStatusTransferState: com.bastion.app.ui.password.PasswordBatchTransferGlobalProgressState?,
    onShowQuickStatusTransferDialog: () -> Unit,
    quickStatusDeleteState: com.bastion.app.ui.password.PasswordBatchDeleteGlobalProgressState?,
    onShowQuickStatusDeleteDialog: () -> Unit,
    quickStatusBitwardenSyncState: QuickStatusBitwardenSyncState?,
    quickStatusKeePassSyncState: QuickStatusKeePassSyncState?,
    currentFilter: CategoryFilter,
    onNavigateFilter: (CategoryFilter) -> Unit,
    shouldGateInitialPasswordFirstFrame: Boolean,
    searchQuery: String,
    isPasswordPageListModelReady: Boolean,
    hasVisibleListItems: Boolean,
    showEmptyState: Boolean,
    hasScrollableHeaderContent: Boolean,
    hasVisibleQuickFilters: Boolean,
    quickFiltersExpanded: Boolean = true,
    hasVisibleCategoryQuickFilters: Boolean,
    aggregateUiState: PasswordListAggregateUiState,
    emptyStateMessage: PasswordListEmptyStateMessage,
    listState: LazyListState,
    appSettings: AppSettings,
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
    quickFilterWifi: Boolean,
    onQuickFilterWifiChange: (Boolean) -> Unit,
    wifiQuickFilterVisible: Boolean,
    quickFilterSshKey: Boolean,
    onQuickFilterSshKeyChange: (Boolean) -> Unit,
    sshKeyQuickFilterVisible: Boolean,
    quickFilterBarcode: Boolean,
    onQuickFilterBarcodeChange: (Boolean) -> Unit,
    barcodeQuickFilterVisible: Boolean,
    onToggleAggregateType: ((PasswordPageContentType) -> Unit)?,
    categoryQuickFilterShortcuts: List<PasswordQuickFolderShortcut>,
    quickFolderShortcuts: List<PasswordQuickFolderShortcut>,
    quickFolderStyle: com.bastion.app.data.PasswordListQuickFolderStyle,
    passwordPageListItems: List<PasswordPageListItemUi>,
    effectiveStackCardMode: StackCardMode,
    expandedGroups: Set<String>,
    itemToDelete: PasswordEntry?,
    onItemToDeleteChange: (PasswordEntry?) -> Unit,
    isSelectionMode: Boolean,
    onSelectionModeChange: (Boolean) -> Unit,
    selectedItemKeys: Set<String>,
    onSelectedItemKeysChange: (Set<String>) -> Unit,
    swipeSelectionAnchorKey: String?,
    onSwipeSelectionAnchorKeyChange: (String?) -> Unit,
    selectedPasswords: Set<Long>,
    showBatchDeleteDialog: Boolean,
    onShowBatchDeleteDialogChange: (Boolean) -> Unit,
    viewModel: PasswordViewModel,
    haptic: com.bastion.app.ui.haptic.HapticFeedbackHelper,
    onPasswordClick: (PasswordEntry) -> Unit,
    passwordPageListItemKeySet: Set<String>,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    context: Context,
    passwordEntries: List<PasswordEntry>,
    aggregateConfig: PasswordListAggregateConfig?,
    decryptAuthenticatorKey: ((String) -> String)?,
    onNavigateToPasskeys: (() -> Unit)? = null,
    // 顶部留白：跟随顶部 Bar 高度联动（展开 88dp / 收起 48dp），保证首条内容不被 Bar 遮挡
    listTopPadding: Dp = 0.dp
) {
    // 滚动期间把 TOTP 验证码行从 50ms 平滑刷新降为秒级刷新（方案 A，见 docs §7.8），
    // 避免可见验证码卡片每帧重组导致滚动掉帧。isScrollInProgress 只在滚动开始/结束时翻转，
    // derivedStateOf 保证不会每帧触发重组。
    val isListScrolling by remember {
        derivedStateOf { listState.isScrollInProgress }
    }
    PasswordListMainPane(
        canCollapseExpandedGroups = canCollapseExpandedGroups,
        outsideTapInteractionSource = outsideTapInteractionSource,
        onCollapseExpandedGroups = onCollapseExpandedGroups,
        isBitwardenDatabaseView = isBitwardenDatabaseView,
        pullAction = pullAction,
        triggerDistance = triggerDistance,
        syncTriggerDistance = syncTriggerDistance,
        density = density,
        showPinnedQuickFolderPathBanner = showPinnedQuickFolderPathBanner,
        quickFolderBreadcrumbs = quickFolderBreadcrumbs,
        quickStatusTransferState = quickStatusTransferState,
        onQuickStatusTransferClick = {
            if (quickStatusTransferState != null) {
                onShowQuickStatusTransferDialog()
            }
        },
        quickStatusDeleteState = quickStatusDeleteState,
        onQuickStatusDeleteClick = {
            if (quickStatusDeleteState != null) {
                onShowQuickStatusDeleteDialog()
            }
        },
        quickStatusBitwardenSyncState = quickStatusBitwardenSyncState,
        quickStatusKeePassSyncState = quickStatusKeePassSyncState,
        currentFilter = currentFilter,
        onNavigateFilter = onNavigateFilter,
        shouldGateInitialPasswordFirstFrame = shouldGateInitialPasswordFirstFrame,
        searchQuery = searchQuery,
        isPasswordPageListModelReady = isPasswordPageListModelReady,
        hasVisibleListItems = hasVisibleListItems,
        showEmptyState = showEmptyState,
        hasScrollableHeaderContent = hasScrollableHeaderContent,
        hasVisibleQuickFilters = hasVisibleQuickFilters,
        quickFiltersExpanded = quickFiltersExpanded,
        hasVisibleCategoryQuickFilters = hasVisibleCategoryQuickFilters,
        aggregateUiState = aggregateUiState,
        emptyStateMessage = emptyStateMessage,
        listState = listState,
        appSettings = appSettings,
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
        quickFilterWifi = quickFilterWifi,
        onQuickFilterWifiChange = onQuickFilterWifiChange,
        wifiQuickFilterVisible = wifiQuickFilterVisible,
        quickFilterSshKey = quickFilterSshKey,
        onQuickFilterSshKeyChange = onQuickFilterSshKeyChange,
        sshKeyQuickFilterVisible = sshKeyQuickFilterVisible,
        quickFilterBarcode = quickFilterBarcode,
        onQuickFilterBarcodeChange = onQuickFilterBarcodeChange,
        barcodeQuickFilterVisible = barcodeQuickFilterVisible,
        onToggleAggregateType = onToggleAggregateType,
        onNavigateToPasskeys = onNavigateToPasskeys,
        categoryQuickFilterShortcuts = categoryQuickFilterShortcuts,
        quickFolderShortcuts = quickFolderShortcuts,
        quickFolderStyle = quickFolderStyle,
        listTopPadding = listTopPadding,
        renderPasswordRows = {
            passwordPageListRows(
                isListScrolling = isListScrolling,
                passwordPageListItems = passwordPageListItems,
                effectiveStackCardMode = effectiveStackCardMode,
                expandedGroups = expandedGroups,
                itemToDelete = itemToDelete,
                onItemToDeleteChange = onItemToDeleteChange,
                isSelectionMode = isSelectionMode,
                onSelectionModeChange = onSelectionModeChange,
                selectedItemKeys = selectedItemKeys,
                onSelectedItemKeysChange = onSelectedItemKeysChange,
                swipeSelectionAnchorKey = swipeSelectionAnchorKey,
                onSwipeSelectionAnchorKeyChange = onSwipeSelectionAnchorKeyChange,
                selectedPasswords = selectedPasswords,
                showBatchDeleteDialog = showBatchDeleteDialog,
                onShowBatchDeleteDialogChange = onShowBatchDeleteDialogChange,
                viewModel = viewModel,
                haptic = haptic,
                onPasswordClick = { password ->
                    val topVisibleKey = listState.layoutInfo.visibleItemsInfo
                        .firstOrNull { item -> item.key.toString() in passwordPageListItemKeySet }
                        ?.key
                        ?.toString()
                    Log.d(
                        PASSWORD_SCROLL_LOG_TAG,
                        "source=v1_click_open_detail persist=${listState.firstVisibleItemIndex}/${listState.firstVisibleItemScrollOffset} anchor=$topVisibleKey"
                    )
                    viewModel.updatePasswordListScrollPosition(
                        listState.firstVisibleItemIndex,
                        listState.firstVisibleItemScrollOffset,
                        topVisibleKey,
                        source = "v1_click_open_detail"
                    )
                    onPasswordClick(password)
                },
                appSettings = appSettings,
                coroutineScope = coroutineScope,
                context = context,
                passwordEntries = passwordEntries,
                aggregateConfig = aggregateConfig,
                aggregateUiState = aggregateUiState,
                decryptAuthenticatorKey = decryptAuthenticatorKey
            )
        }
    )
}

private const val MONICA_MANUAL_STACK_GROUP_FIELD_TITLE = "__bastion_manual_stack_group"
private const val MONICA_NO_STACK_FIELD_TITLE = "__bastion_no_stack"

@Composable
private fun PasswordListMainPaneSection(
    canCollapseExpandedGroups: Boolean,
    outsideTapInteractionSource: MutableInteractionSource,
    isBitwardenDatabaseView: Boolean,
    pullAction: com.bastion.app.ui.common.pull.PullActionStateHandle,
    triggerDistance: Float,
    syncTriggerDistance: Float,
    density: androidx.compose.ui.unit.Density,
    showPinnedQuickFolderPathBanner: Boolean,
    quickStatusTransferState: com.bastion.app.ui.password.PasswordBatchTransferGlobalProgressState?,
    onShowQuickStatusTransferDialog: () -> Unit,
    quickStatusDeleteState: com.bastion.app.ui.password.PasswordBatchDeleteGlobalProgressState?,
    onShowQuickStatusDeleteDialog: () -> Unit,
    quickStatusBitwardenSyncState: QuickStatusBitwardenSyncState?,
    quickStatusKeePassSyncState: QuickStatusKeePassSyncState?,
    currentFilter: CategoryFilter,
    shouldGateInitialPasswordFirstFrame: Boolean,
    searchQuery: String,
    isPasswordPageListModelReady: Boolean,
    hasVisibleListItems: Boolean,
    hasScrollableHeaderContent: Boolean,
    hasVisibleQuickFilters: Boolean,
    quickFiltersExpanded: Boolean,
    hasVisibleCategoryQuickFilters: Boolean,
    aggregateUiState: PasswordListAggregateUiState,
    emptyStateMessage: PasswordListEmptyStateMessage,
    listState: LazyListState,
    appSettings: AppSettings,
    configuredQuickFilterItems: List<PasswordListQuickFilterItem>,
    quickFolderStyle: com.bastion.app.data.PasswordListQuickFolderStyle,
    passwordPageListItems: List<PasswordPageListItemUi>,
    effectiveStackCardMode: StackCardMode,
    expandedGroups: Set<String>,
    itemToDelete: PasswordEntry?,
    onItemToDeleteChange: (PasswordEntry?) -> Unit,
    isSelectionMode: Boolean,
    onSelectionModeChange: (Boolean) -> Unit,
    selectedItemKeys: Set<String>,
    onSelectedItemKeysChange: (Set<String>) -> Unit,
    swipeSelectionAnchorKey: String?,
    onSwipeSelectionAnchorKeyChange: (String?) -> Unit,
    selectedPasswords: Set<Long>,
    showBatchDeleteDialog: Boolean,
    onShowBatchDeleteDialogChange: (Boolean) -> Unit,
    viewModel: PasswordViewModel,
    haptic: com.bastion.app.ui.haptic.HapticFeedbackHelper,
    onPasswordClick: (PasswordEntry) -> Unit,
    passwordPageListItemKeySet: Set<String>,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    context: Context,
    passwordEntries: List<PasswordEntry>,
    aggregateConfig: PasswordListAggregateConfig?,
    onNavigateToPasskeys: (() -> Unit)?,
    listTopPadding: Dp,
    quickFilterToggles: PasswordListQuickFilterToggles,
    effectiveQuickFolderBreadcrumbs: List<PasswordQuickFolderBreadcrumb>,
    showEmptyStateWithHeaders: Boolean,
    effectiveCategoryQuickFilterShortcuts: List<PasswordQuickFolderShortcut>,
    effectiveQuickFolderCardShortcuts: List<PasswordQuickFolderShortcut>,
    decryptAuthenticatorKeyForPreview: ((String) -> String)?
) {
    PasswordListMainPaneHost(
        canCollapseExpandedGroups = canCollapseExpandedGroups,
        outsideTapInteractionSource = outsideTapInteractionSource,
        onCollapseExpandedGroups = viewModel::clearExpandedGroups,
        isBitwardenDatabaseView = isBitwardenDatabaseView,
        pullAction = pullAction,
        triggerDistance = triggerDistance,
        syncTriggerDistance = syncTriggerDistance,
        density = density,
        showPinnedQuickFolderPathBanner = showPinnedQuickFolderPathBanner,
        quickFolderBreadcrumbs = effectiveQuickFolderBreadcrumbs,
        quickStatusTransferState = quickStatusTransferState,
        onShowQuickStatusTransferDialog = onShowQuickStatusTransferDialog,
        quickStatusDeleteState = quickStatusDeleteState,
        onShowQuickStatusDeleteDialog = onShowQuickStatusDeleteDialog,
        quickStatusBitwardenSyncState = quickStatusBitwardenSyncState,
        quickStatusKeePassSyncState = quickStatusKeePassSyncState,
        currentFilter = currentFilter,
        onNavigateFilter = viewModel::setCategoryFilter,
        shouldGateInitialPasswordFirstFrame = shouldGateInitialPasswordFirstFrame,
        searchQuery = searchQuery,
        isPasswordPageListModelReady = isPasswordPageListModelReady,
        hasVisibleListItems = hasVisibleListItems,
        showEmptyState = showEmptyStateWithHeaders,
        hasScrollableHeaderContent = hasScrollableHeaderContent,
        hasVisibleQuickFilters = hasVisibleQuickFilters,
        quickFiltersExpanded = quickFiltersExpanded,
        hasVisibleCategoryQuickFilters = hasVisibleCategoryQuickFilters,
        aggregateUiState = aggregateUiState,
        emptyStateMessage = emptyStateMessage,
        listState = listState,
        appSettings = appSettings,
        configuredQuickFilterItems = configuredQuickFilterItems,
        quickFilterFavorite = quickFilterToggles.favorite,
        onQuickFilterFavoriteChange = quickFilterToggles.onFavoriteChange,
        quickFilter2fa = quickFilterToggles.twoFa,
        onQuickFilter2faChange = quickFilterToggles.onTwoFaChange,
        quickFilterNotes = quickFilterToggles.notes,
        onQuickFilterNotesChange = quickFilterToggles.onNotesChange,
        quickFilterPasskey = quickFilterToggles.passkey,
        onQuickFilterPasskeyChange = quickFilterToggles.onPasskeyChange,
        quickFilterBoundNote = quickFilterToggles.boundNote,
        onQuickFilterBoundNoteChange = quickFilterToggles.onBoundNoteChange,
        quickFilterAttachments = quickFilterToggles.attachments,
        onQuickFilterAttachmentsChange = quickFilterToggles.onAttachmentsChange,
        quickFilterUncategorized = quickFilterToggles.uncategorized,
        onQuickFilterUncategorizedChange = quickFilterToggles.onUncategorizedChange,
        quickFilterLocalOnly = quickFilterToggles.localOnly,
        onQuickFilterLocalOnlyChange = quickFilterToggles.onLocalOnlyChange,
        quickFilterManualStackOnly = quickFilterToggles.manualStackOnly,
        onQuickFilterManualStackOnlyChange = quickFilterToggles.onManualStackOnlyChange,
        quickFilterNeverStack = quickFilterToggles.neverStack,
        onQuickFilterNeverStackChange = quickFilterToggles.onNeverStackChange,
        quickFilterUnstacked = quickFilterToggles.unstacked,
        onQuickFilterUnstackedChange = quickFilterToggles.onUnstackedChange,
        quickFilterWifi = quickFilterToggles.wifi,
        onQuickFilterWifiChange = quickFilterToggles.onWifiChange,
        wifiQuickFilterVisible = quickFilterToggles.hasAnyWifiEntry,
        quickFilterSshKey = quickFilterToggles.sshKey,
        onQuickFilterSshKeyChange = quickFilterToggles.onSshKeyChange,
        sshKeyQuickFilterVisible = quickFilterToggles.hasAnySshKeyEntry,
        quickFilterBarcode = quickFilterToggles.barcode,
        onQuickFilterBarcodeChange = quickFilterToggles.onBarcodeChange,
        barcodeQuickFilterVisible = quickFilterToggles.hasAnyBarcodeEntry,
        onToggleAggregateType = aggregateConfig?.onToggleContentType,
        categoryQuickFilterShortcuts = effectiveCategoryQuickFilterShortcuts,
        quickFolderShortcuts = effectiveQuickFolderCardShortcuts,
        quickFolderStyle = quickFolderStyle,
        passwordPageListItems = passwordPageListItems,
        effectiveStackCardMode = effectiveStackCardMode,
        expandedGroups = expandedGroups,
        itemToDelete = itemToDelete,
        onItemToDeleteChange = onItemToDeleteChange,
        isSelectionMode = isSelectionMode,
        onSelectionModeChange = onSelectionModeChange,
        selectedItemKeys = selectedItemKeys,
        onSelectedItemKeysChange = onSelectedItemKeysChange,
        swipeSelectionAnchorKey = swipeSelectionAnchorKey,
        onSwipeSelectionAnchorKeyChange = onSwipeSelectionAnchorKeyChange,
        selectedPasswords = selectedPasswords,
        showBatchDeleteDialog = showBatchDeleteDialog,
        onShowBatchDeleteDialogChange = onShowBatchDeleteDialogChange,
        viewModel = viewModel,
        haptic = haptic,
        onPasswordClick = onPasswordClick,
        passwordPageListItemKeySet = passwordPageListItemKeySet,
        coroutineScope = coroutineScope,
        context = context,
        passwordEntries = passwordEntries,
        aggregateConfig = aggregateConfig,
        onNavigateToPasskeys = onNavigateToPasskeys,
        decryptAuthenticatorKey = decryptAuthenticatorKeyForPreview,
        listTopPadding = listTopPadding
    )
}

@Composable
private fun PasswordListTopSectionHost(
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
    topActionsMenuExpanded: Boolean,
    onTopActionsMenuExpandedChange: (Boolean) -> Unit,
    showStandaloneSettingsEntry: Boolean,
    onOpenStandaloneSettings: () -> Unit,
    isCategorySheetVisible: Boolean,
    onCategorySheetVisibleChange: (Boolean) -> Unit,
    categoryPillBoundsInWindow: androidx.compose.ui.geometry.Rect?,
    onCategoryPillBoundsChange: (androidx.compose.ui.geometry.Rect?) -> Unit,
    showDisplayOptionsSheet: Boolean,
    onShowDisplayOptionsSheetChange: (Boolean) -> Unit,
    configuredQuickFilterItems: List<PasswordListQuickFilterItem>,
    categoryMenuQuickFolderShortcuts: List<PasswordQuickFolderShortcut>,
    stackCardMode: StackCardMode,
    groupMode: String,
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
    onTitleClick: (() -> Unit)?,
    quickFiltersExpanded: Boolean,
    onNavigateToPasskeys: (() -> Unit)?,
    scrollCollapseFraction: Float,
    quickFilterToggles: PasswordListQuickFilterToggles,
    appSettings: AppSettings,
    aggregateUiState: PasswordListAggregateUiState,
    aggregateConfig: PasswordListAggregateConfig?,
    isAuthenticated: Boolean
) {
    PasswordListTopSection(
        currentFilter = currentFilter,
        onNavigateToPasskeys = onNavigateToPasskeys,
        scrollCollapseFraction = scrollCollapseFraction,
        categories = categories,
        keepassDatabases = keepassDatabases,
        bitwardenVaults = bitwardenVaults,
        viewModel = viewModel,
        localKeePassViewModel = localKeePassViewModel,
        bitwardenViewModel = bitwardenViewModel,
        selectedBitwardenVaultId = selectedBitwardenVaultId,
        selectedKeePassDatabaseId = selectedKeePassDatabaseId,
        isTopBarSyncing = isTopBarSyncing,
        isArchiveView = isArchiveView,
        isKeePassDatabaseView = isKeePassDatabaseView,
        searchQuery = searchQuery,
        isSearchExpanded = isSearchExpanded,
        onSearchExpandedChange = onSearchExpandedChange,
        onSearchQueryChange = viewModel::updateSearchQuery,
        topActionsMenuExpanded = isAuthenticated && topActionsMenuExpanded,
        onTopActionsMenuExpandedChange = onTopActionsMenuExpandedChange,
        showStandaloneSettingsEntry = showStandaloneSettingsEntry,
        onOpenStandaloneSettings = onOpenStandaloneSettings,
        isCategorySheetVisible = isCategorySheetVisible,
        onCategorySheetVisibleChange = onCategorySheetVisibleChange,
        categoryPillBoundsInWindow = categoryPillBoundsInWindow,
        onCategoryPillBoundsChange = onCategoryPillBoundsChange,
        showDisplayOptionsSheet = showDisplayOptionsSheet,
        onShowDisplayOptionsSheetChange = onShowDisplayOptionsSheetChange,
        configuredQuickFilterItems = configuredQuickFilterItems,
        quickFilterFavorite = quickFilterToggles.favorite,
        onQuickFilterFavoriteChange = quickFilterToggles.onFavoriteChange,
        quickFilter2fa = quickFilterToggles.twoFa,
        onQuickFilter2faChange = quickFilterToggles.onTwoFaChange,
        quickFilterNotes = quickFilterToggles.notes,
        onQuickFilterNotesChange = quickFilterToggles.onNotesChange,
        quickFilterPasskey = quickFilterToggles.passkey,
        onQuickFilterPasskeyChange = quickFilterToggles.onPasskeyChange,
        quickFilterBoundNote = quickFilterToggles.boundNote,
        onQuickFilterBoundNoteChange = quickFilterToggles.onBoundNoteChange,
        quickFilterAttachments = quickFilterToggles.attachments,
        onQuickFilterAttachmentsChange = quickFilterToggles.onAttachmentsChange,
        quickFilterUncategorized = quickFilterToggles.uncategorized,
        onQuickFilterUncategorizedChange = quickFilterToggles.onUncategorizedChange,
        quickFilterLocalOnly = quickFilterToggles.localOnly,
        onQuickFilterLocalOnlyChange = quickFilterToggles.onLocalOnlyChange,
        quickFilterManualStackOnly = quickFilterToggles.manualStackOnly,
        onQuickFilterManualStackOnlyChange = quickFilterToggles.onManualStackOnlyChange,
        quickFilterNeverStack = quickFilterToggles.neverStack,
        onQuickFilterNeverStackChange = quickFilterToggles.onNeverStackChange,
        quickFilterUnstacked = quickFilterToggles.unstacked,
        onQuickFilterUnstackedChange = quickFilterToggles.onUnstackedChange,
        quickFilterWifi = quickFilterToggles.wifi,
        onQuickFilterWifiChange = quickFilterToggles.onWifiChange,
        wifiQuickFilterVisible = quickFilterToggles.hasAnyWifiEntry,
        quickFilterSshKey = quickFilterToggles.sshKey,
        onQuickFilterSshKeyChange = quickFilterToggles.onSshKeyChange,
        sshKeyQuickFilterVisible = quickFilterToggles.hasAnySshKeyEntry,
        quickFilterBarcode = quickFilterToggles.barcode,
        onQuickFilterBarcodeChange = quickFilterToggles.onBarcodeChange,
        barcodeQuickFilterVisible = quickFilterToggles.hasAnyBarcodeEntry,
        aggregateSelectedTypes = aggregateUiState.selectedContentTypes,
        aggregateVisibleTypes = aggregateUiState.visibleContentTypes,
        onToggleAggregateType = { type -> aggregateConfig?.onToggleContentType?.invoke(type) },
        categoryMenuQuickFolderShortcuts = categoryMenuQuickFolderShortcuts,
        stackCardMode = stackCardMode,
        groupMode = groupMode,
        passwordCardDisplayMode = appSettings.passwordCardDisplayMode,
        settingsViewModel = settingsViewModel,
        context = context,
        activity = activity,
        biometricHelper = biometricHelper,
        canUseBiometric = canUseBiometric,
        coroutineScope = coroutineScope,
        bitwardenRepository = bitwardenRepository,
        securityManager = securityManager,
        onRenameCategory = onRenameCategory,
        onDeleteCategory = onDeleteCategory,
        onOpenCommonAccountTemplates = onOpenCommonAccountTemplates,
        onOpenHistory = onOpenHistory,
        onOpenTrash = onOpenTrash,
        onScanFidoQr = onScanFidoQr,
        onTitleClick = onTitleClick,
        quickFiltersExpanded = quickFiltersExpanded
    )
}

@Composable
private fun PasswordListQuickStatusDialogsHost(
    showQuickStatusTransferDialog: Boolean,
    quickStatusTransferState: com.bastion.app.ui.password.PasswordBatchTransferGlobalProgressState?,
    onMoveTransferToBackground: () -> Unit,
    showQuickStatusDeleteDialog: Boolean,
    quickStatusDeleteState: com.bastion.app.ui.password.PasswordBatchDeleteGlobalProgressState?,
    onMoveDeleteToBackground: () -> Unit,
    showQuickStatusKeePassSyncDialog: Boolean,
    quickStatusKeePassSyncState: QuickStatusKeePassSyncState?,
    onMoveKeePassSyncToBackground: (QuickStatusKeePassSyncState) -> Unit,
    onRunKeePassSyncNow: (QuickStatusKeePassSyncState) -> Unit
) {
    PasswordListQuickStatusDialogs(
        showQuickStatusTransferDialog = showQuickStatusTransferDialog,
        quickStatusTransferState = quickStatusTransferState,
        onMoveTransferToBackground = onMoveTransferToBackground,
        showQuickStatusDeleteDialog = showQuickStatusDeleteDialog,
        quickStatusDeleteState = quickStatusDeleteState,
        onMoveDeleteToBackground = onMoveDeleteToBackground,
        showQuickStatusKeePassSyncDialog = showQuickStatusKeePassSyncDialog,
        quickStatusKeePassSyncState = quickStatusKeePassSyncState,
        onMoveKeePassSyncToBackground = onMoveKeePassSyncToBackground,
        onRunKeePassSyncNow = onRunKeePassSyncNow,
    )
}

@Composable
private fun PasswordListDialogsHost(
    showManualStackConfirmDialog: Boolean,
    onShowManualStackConfirmDialogChange: (Boolean) -> Unit,
    selectedItemKeys: Set<String>,
    selectedPasswords: Set<Long>,
    selectedManualStackMode: ManualStackDialogMode,
    onSelectedManualStackModeChange: (ManualStackDialogMode) -> Unit,
    onApplyManualStackMode: suspend (ManualStackDialogMode, Set<String>, Set<Long>) -> Int,
    viewModel: PasswordViewModel,
    context: Context,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    onDeleteSelection: suspend (onProgress: (processed: Int, total: Int) -> Unit) -> Int,
    onBatchDeleteStarted: () -> Unit,
    onSelectionCleared: () -> Unit,
    showBatchDeleteDialog: Boolean,
    onShowBatchDeleteDialogChange: (Boolean) -> Unit,
    passwordInput: String,
    onPasswordInputChange: (String) -> Unit,
    passwordError: Boolean,
    onPasswordErrorChange: (Boolean) -> Unit,
    canUseBiometric: Boolean,
    activity: FragmentActivity?,
    biometricHelper: BiometricHelper,
    itemToDelete: PasswordEntry?,
    onItemToDeleteChange: (PasswordEntry?) -> Unit,
    appSettings: AppSettings,
    singleItemPasswordInput: String,
    onSingleItemPasswordInputChange: (String) -> Unit,
    showSingleItemPasswordVerify: Boolean,
    onShowSingleItemPasswordVerifyChange: (Boolean) -> Unit,
    passwordEntries: List<PasswordEntry>,
    selectedSupplementaryItems: List<PasswordAggregateListItemUi>
) {
    PasswordListDialogs(
        showManualStackConfirmDialog = showManualStackConfirmDialog,
        onShowManualStackConfirmDialogChange = onShowManualStackConfirmDialogChange,
        selectedItemKeys = selectedItemKeys,
        selectedPasswords = selectedPasswords,
        selectedCount = selectedItemKeys.size,
        selectedManualStackMode = selectedManualStackMode,
        onSelectedManualStackModeChange = onSelectedManualStackModeChange,
        onApplyManualStackMode = onApplyManualStackMode,
        viewModel = viewModel,
        context = context,
        coroutineScope = coroutineScope,
        enableBatchDeleteProgress = selectedPasswords.any { id ->
            passwordEntries.any { it.id == id && it.keepassDatabaseId != null }
        } || selectedSupplementaryItems.any { it.entry.keepassDatabaseId != null },
        onDeleteSelection = onDeleteSelection,
        onBatchDeleteStarted = onBatchDeleteStarted,
        onSelectionCleared = onSelectionCleared,
        showBatchDeleteDialog = showBatchDeleteDialog,
        onShowBatchDeleteDialogChange = onShowBatchDeleteDialogChange,
        passwordInput = passwordInput,
        onPasswordInputChange = onPasswordInputChange,
        passwordError = passwordError,
        onPasswordErrorChange = onPasswordErrorChange,
        canUseBiometric = canUseBiometric,
        activity = activity,
        biometricHelper = biometricHelper,
        itemToDelete = itemToDelete,
        onItemToDeleteChange = onItemToDeleteChange,
        appSettings = appSettings,
        singleItemPasswordInput = singleItemPasswordInput,
        onSingleItemPasswordInputChange = onSingleItemPasswordInputChange,
        showSingleItemPasswordVerify = showSingleItemPasswordVerify,
        onShowSingleItemPasswordVerifyChange = onShowSingleItemPasswordVerifyChange,
    )
}

@Composable
private fun PasswordBatchMoveSheetHost(
    visible: Boolean,
    categories: List<Category>,
    keepassDatabases: List<com.bastion.app.data.LocalKeePassDatabase>,
    bitwardenVaults: List<com.bastion.app.data.bitwarden.BitwardenVault>,
    database: com.bastion.app.data.PasswordDatabase,
    localKeePassViewModel: com.bastion.app.viewmodel.LocalKeePassViewModel,
    securityManager: SecurityManager,
    selectedPasswords: Set<Long>,
    selectedSupplementaryItems: List<PasswordAggregateListItemUi>,
    passwordEntries: List<PasswordEntry>,
    aggregateUiState: PasswordListAggregateUiState,
    viewModel: PasswordViewModel,
    bitwardenRepository: com.bastion.app.bitwarden.repository.BitwardenRepository,
    context: Context,
    coroutineScope: CoroutineScope,
    onRenameCategory: (Category) -> Unit,
    onDeleteCategory: (Category) -> Unit,
    onDismiss: () -> Unit,
    onSelectionCleared: () -> Unit
) {
    PasswordBatchMoveSheet(
        visible = visible,
        categories = categories,
        keepassDatabases = keepassDatabases,
        bitwardenVaults = bitwardenVaults,
        database = database,
        localKeePassViewModel = localKeePassViewModel,
        securityManager = securityManager,
        selectedPasswords = selectedPasswords,
        selectedSupplementaryItems = selectedSupplementaryItems,
        passwordEntries = passwordEntries,
        aggregateUiState = aggregateUiState,
        viewModel = viewModel,
        bitwardenRepository = bitwardenRepository,
        context = context,
        coroutineScope = coroutineScope,
        onRenameCategory = onRenameCategory,
        onDeleteCategory = onDeleteCategory,
        onDismiss = onDismiss,
        onSelectionCleared = onSelectionCleared
    )
}


internal class PasswordListManualStackMeta(
    val effectiveManualStackGroupByEntryId: Map<Long, String>,
    val effectiveNoStackEntryIds: Set<Long>
)

@Composable
private fun rememberPasswordListManualStackMeta(
    passwordEntries: List<PasswordEntry>,
    deletedItemIds: Set<Long>,
    shouldLoadManualStackMetadata: Boolean,
    viewModel: PasswordViewModel
): PasswordListManualStackMeta {
    var manualStackGroupByEntryId by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var noStackEntryIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var lastCustomFieldEntryIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    LaunchedEffect(passwordEntries, deletedItemIds, shouldLoadManualStackMetadata) {
        if (!shouldLoadManualStackMetadata) {
            manualStackGroupByEntryId = emptyMap()
            noStackEntryIds = emptySet()
            lastCustomFieldEntryIds = emptyList()
            return@LaunchedEffect
        }
        val entriesSnapshot = passwordEntries
        val deletedIdsSnapshot = deletedItemIds
        val allIds = withContext(Dispatchers.Default) {
            entriesSnapshot
                .asSequence()
                .map { it.id }
                .filter { id -> id !in deletedIdsSnapshot }
                .toList()
        }
        if (allIds.isEmpty()) {
            manualStackGroupByEntryId = emptyMap()
            noStackEntryIds = emptySet()
            lastCustomFieldEntryIds = emptyList()
            return@LaunchedEffect
        }
        if (allIds == lastCustomFieldEntryIds) {
            return@LaunchedEffect
        }
        lastCustomFieldEntryIds = allIds
        val fieldMap = withContext(Dispatchers.IO) {
            viewModel.getCustomFieldsByEntryIds(allIds)
        }
        val (manualStackMap, noStackIds) = withContext(Dispatchers.Default) {
            val manualStack = fieldMap.mapNotNull { (entryId, fields) ->
                val groupId = fields.firstOrNull {
                    it.title == MONICA_MANUAL_STACK_GROUP_FIELD_TITLE
                }?.value?.takeIf { value -> value.isNotBlank() }
                groupId?.let { entryId to it }
            }.toMap()
            val noStack = fieldMap.mapNotNull { (entryId, fields) ->
                val hasNoStack = fields.any {
                    it.title == MONICA_NO_STACK_FIELD_TITLE && it.value != "0"
                }
                if (hasNoStack) entryId else null
            }.toSet()
            manualStack to noStack
        }
        manualStackGroupByEntryId = manualStackMap
        noStackEntryIds = noStackIds
    }
    val effectiveManualStackGroupByEntryId =
        if (shouldLoadManualStackMetadata) manualStackGroupByEntryId else emptyMap()
    val effectiveNoStackEntryIds =
        if (shouldLoadManualStackMetadata) noStackEntryIds else emptySet()
    return PasswordListManualStackMeta(
        effectiveManualStackGroupByEntryId = effectiveManualStackGroupByEntryId,
        effectiveNoStackEntryIds = effectiveNoStackEntryIds
    )
}

private class PasswordListDerivedFilters(
    val groupingConfig: PasswordGroupingConfig,
    val preStackFilteredPasswordEntries: List<PasswordEntry>,
    val preStackFilteredAggregateItems: List<PasswordAggregateListItemUi>,
    val manualAggregateStackBuildResult: PasswordAggregateManualStackBuildResult,
    val validAggregateStackedItemKeys: Set<String>,
    val visiblePasswordEntries: List<PasswordEntry>,
    val visibleAggregateItems: List<PasswordAggregateListItemUi>
)

@Composable
private fun rememberPasswordListDerivedFilters(
    passwordEntries: List<PasswordEntry>,
    deletedItemIds: Set<Long>,
    quickFoldersEnabledForCurrentFilter: Boolean,
    currentFilter: CategoryFilter,
    configuredQuickFilterItems: List<PasswordListQuickFilterItem>,
    quickFilterToggles: PasswordListQuickFilterToggles,
    activeAttachmentParentIds: Set<Long>,
    manualStackMeta: PasswordListManualStackMeta,
    aggregateUiState: PasswordListAggregateUiState,
    effectiveStackCardMode: StackCardMode,
    aggregateStackEntries: List<PasswordPageAggregateStackEntry>,
    isLocalOnlyView: Boolean,
    effectiveGroupMode: String,
    appSettings: AppSettings,
    context: Context
): PasswordListDerivedFilters {
    val effectiveManualStackGroupByEntryId = manualStackMeta.effectiveManualStackGroupByEntryId
    val effectiveNoStackEntryIds = manualStackMeta.effectiveNoStackEntryIds
    val groupingConfig = remember(
        isLocalOnlyView,
        effectiveStackCardMode,
        effectiveGroupMode,
        appSettings.passwordWebsiteStackMatchMode,
        effectiveNoStackEntryIds,
        effectiveManualStackGroupByEntryId,
        context
    ) {
        PasswordGroupingConfig(
            isLocalOnlyView = isLocalOnlyView,
            effectiveStackCardMode = effectiveStackCardMode,
            effectiveGroupMode = effectiveGroupMode,
            websiteStackMatchMode = appSettings.passwordWebsiteStackMatchMode,
            effectiveNoStackEntryIds = effectiveNoStackEntryIds,
            effectiveManualStackGroupByEntryId = effectiveManualStackGroupByEntryId,
            untitledLabel = context.getString(R.string.untitled)
        )
    }
    
    val preStackFilteredPasswordEntries = remember(
        passwordEntries,
        deletedItemIds,
        quickFoldersEnabledForCurrentFilter,
        currentFilter,
        configuredQuickFilterItems,
        quickFilterToggles.favorite,
        quickFilterToggles.twoFa,
        quickFilterToggles.notes,
        quickFilterToggles.passkey,
        quickFilterToggles.boundNote,
        quickFilterToggles.attachments,
        activeAttachmentParentIds,
        quickFilterToggles.uncategorized,
        quickFilterToggles.localOnly,
        quickFilterToggles.neverStack,
        quickFilterToggles.wifi,
        quickFilterToggles.sshKey,
        quickFilterToggles.barcode,
        effectiveNoStackEntryIds,
        aggregateUiState.hasActiveContentTypeFilter,
        aggregateUiState.contentTypeFilterTypes
    ) {
        filterPreStackPasswordEntries(
            passwordEntries = passwordEntries,
            deletedItemIds = deletedItemIds,
            quickFoldersEnabledForCurrentFilter = quickFoldersEnabledForCurrentFilter,
            currentFilter = currentFilter,
            configuredQuickFilterItems = configuredQuickFilterItems,
            quickFilterFavorite = quickFilterToggles.favorite,
            quickFilter2fa = quickFilterToggles.twoFa,
            quickFilterNotes = quickFilterToggles.notes,
            quickFilterPasskey = quickFilterToggles.passkey,
            quickFilterBoundNote = quickFilterToggles.boundNote,
            quickFilterAttachments = quickFilterToggles.attachments,
            activeAttachmentParentIds = activeAttachmentParentIds,
            quickFilterUncategorized = quickFilterToggles.uncategorized,
            quickFilterLocalOnly = quickFilterToggles.localOnly,
            quickFilterNeverStack = quickFilterToggles.neverStack,
            quickFilterWifi = quickFilterToggles.wifi,
            quickFilterSshKey = quickFilterToggles.sshKey,
            quickFilterBarcode = quickFilterToggles.barcode,
            effectiveNoStackEntryIds = effectiveNoStackEntryIds,
            hasActiveContentTypeFilter = aggregateUiState.hasActiveContentTypeFilter,
            contentTypeFilterTypes = aggregateUiState.contentTypeFilterTypes
        )
    }

    val preStackFilteredAggregateItems = remember(
        aggregateUiState.visibleItems,
        configuredQuickFilterItems,
        quickFilterToggles.favorite,
        quickFilterToggles.twoFa,
        quickFilterToggles.notes,
        quickFilterToggles.uncategorized,
        quickFilterToggles.localOnly,
        quickFilterToggles.wifi,
        quickFilterToggles.sshKey,
        quickFilterToggles.barcode,
        quickFilterToggles.neverStack,
        currentFilter,
        effectiveStackCardMode
    ) {
        filterPasswordAggregateItemsByQuickFilters(
            items = aggregateUiState.visibleItems,
            currentFilter = currentFilter,
            configuredQuickFilterItems = configuredQuickFilterItems,
            quickFilterFavorite = quickFilterToggles.favorite,
            quickFilter2fa = quickFilterToggles.twoFa,
            quickFilterNotes = quickFilterToggles.notes,
            quickFilterUncategorized = quickFilterToggles.uncategorized,
            quickFilterLocalOnly = quickFilterToggles.localOnly,
            quickFilterWifi = quickFilterToggles.wifi,
            quickFilterSshKey = quickFilterToggles.sshKey,
            quickFilterBarcode = quickFilterToggles.barcode,
            quickFilterManualStackOnly = false,
            quickFilterNeverStack = quickFilterToggles.neverStack,
            quickFilterUnstacked = false,
            effectiveStackCardMode = effectiveStackCardMode,
            manualStackedKeys = emptySet()
        )
    }
    val manualAggregateStackBuildResult = remember(
        aggregateStackEntries,
        preStackFilteredPasswordEntries,
        preStackFilteredAggregateItems
    ) {
        buildPasswordAggregateManualStackGroups(
            stackEntries = aggregateStackEntries,
            passwords = preStackFilteredPasswordEntries,
            aggregateItems = preStackFilteredAggregateItems
        )
    }
    val validAggregateStackedItemKeys = remember(manualAggregateStackBuildResult.stackedItemKeys) {
        manualAggregateStackBuildResult.stackedItemKeys
    }
    val visiblePasswordEntries = remember(
        preStackFilteredPasswordEntries,
        configuredQuickFilterItems,
        quickFilterToggles.manualStackOnly,
        quickFilterToggles.unstacked,
        effectiveStackCardMode,
        effectiveManualStackGroupByEntryId,
        validAggregateStackedItemKeys,
        manualAggregateStackBuildResult.stackedPasswordIds,
        groupingConfig
    ) {
        filterPasswordEntriesByStackQuickFilters(
            items = preStackFilteredPasswordEntries,
            configuredQuickFilterItems = configuredQuickFilterItems,
            quickFilterManualStackOnly = quickFilterToggles.manualStackOnly,
            quickFilterUnstacked = quickFilterToggles.unstacked,
            effectiveStackCardMode = effectiveStackCardMode,
            effectiveManualStackGroupByEntryId = effectiveManualStackGroupByEntryId,
            aggregateManualStackedItemKeys = validAggregateStackedItemKeys,
            aggregateManualStackedPasswordIds = manualAggregateStackBuildResult.stackedPasswordIds,
            groupingConfig = groupingConfig
        )
    }
    val visibleAggregateItems = remember(
        preStackFilteredAggregateItems,
        configuredQuickFilterItems,
        quickFilterToggles.manualStackOnly,
        quickFilterToggles.unstacked,
        quickFilterToggles.wifi,
        quickFilterToggles.sshKey,
        quickFilterToggles.barcode,
        effectiveStackCardMode,
        validAggregateStackedItemKeys
    ) {
        filterPasswordAggregateItemsByQuickFilters(
            items = preStackFilteredAggregateItems,
            currentFilter = currentFilter,
            configuredQuickFilterItems = configuredQuickFilterItems,
            quickFilterFavorite = false,
            quickFilter2fa = false,
            quickFilterNotes = false,
            quickFilterUncategorized = false,
            quickFilterLocalOnly = false,
            quickFilterWifi = quickFilterToggles.wifi,
            quickFilterSshKey = quickFilterToggles.sshKey,
            quickFilterBarcode = quickFilterToggles.barcode,
            quickFilterManualStackOnly = quickFilterToggles.manualStackOnly,
            quickFilterNeverStack = false,
            quickFilterUnstacked = quickFilterToggles.unstacked,
            effectiveStackCardMode = effectiveStackCardMode,
            manualStackedKeys = validAggregateStackedItemKeys
        )
    }
    return PasswordListDerivedFilters(
        groupingConfig = groupingConfig,
        preStackFilteredPasswordEntries = preStackFilteredPasswordEntries,
        preStackFilteredAggregateItems = preStackFilteredAggregateItems,
        manualAggregateStackBuildResult = manualAggregateStackBuildResult,
        validAggregateStackedItemKeys = validAggregateStackedItemKeys,
        visiblePasswordEntries = visiblePasswordEntries,
        visibleAggregateItems = visibleAggregateItems
    )
}
