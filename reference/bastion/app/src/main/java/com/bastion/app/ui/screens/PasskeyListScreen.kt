@file:Suppress("LocalContextGetResourceValueCall")
package com.bastion.app.ui.screens

import androidx.compose.ui.platform.LocalConfiguration

import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.bastion.app.R
import com.bastion.app.bitwarden.sync.isUserVisibleSyncInProgress
import com.bastion.app.bitwarden.repository.BitwardenRepository
import com.bastion.app.bitwarden.sync.syncForUserVisibleRequest
import com.bastion.app.data.AppSettings
import com.bastion.app.data.Category
import com.bastion.app.data.isKeePassOwned
import com.bastion.app.data.PasskeyEntry
import com.bastion.app.data.PasswordDatabase
import com.bastion.app.data.PasswordEntry
import com.bastion.app.data.UnmatchedIconHandlingStrategy
import com.bastion.app.data.bitwarden.BitwardenPendingOperation
import com.bastion.app.data.bitwarden.BitwardenVault
import com.bastion.app.data.model.PasskeyBinding
import com.bastion.app.data.model.PasskeyBindingCodec
import com.bastion.app.data.model.StorageTarget
import com.bastion.app.keepass.KeePassPasskeyCredentialConflictException
import com.bastion.app.repository.KeePassCompatibilityBridge
import com.bastion.app.repository.KeePassWorkspaceRepository
import com.bastion.app.ui.activityViewModel
import com.bastion.app.ui.PasswordListCategoryChipMenuBottomActions
import com.bastion.app.ui.components.CreateCategoryDialog
import com.bastion.app.ui.components.CreateDialogTarget
import com.bastion.app.ui.category.CategoryManagementTrailingContent
import com.bastion.app.ui.category.CategoryManagementCreateDialog
import com.bastion.app.ui.category.rememberCategoryManagementState
import com.bastion.app.ui.components.ExpressiveTopBar
import com.bastion.app.ui.components.M3IdentityVerifyDialog
import com.bastion.app.ui.components.PullActionVisualState
import com.bastion.app.ui.components.PasswordEntryPickerBottomSheet
import com.bastion.app.ui.components.SyncStatusBadge
import com.bastion.app.ui.components.SyncStatusIcon
import com.bastion.app.ui.components.UnifiedCategoryFilterChipMenu
import com.bastion.app.ui.components.UnifiedCategoryFilterChipMenuDropdown
import com.bastion.app.ui.components.UnifiedCategoryFilterChipMenuOffset
import com.bastion.app.ui.components.UnifiedCategoryFilterSelection
import com.bastion.app.ui.components.UnifiedMoveAction
import com.bastion.app.ui.components.UnifiedMoveCategoryTarget
import com.bastion.app.ui.components.UnifiedMoveToCategoryBottomSheet
import com.bastion.app.ui.gestures.SwipeActions
import com.bastion.app.ui.haptic.rememberHapticFeedback
import com.bastion.app.utils.BiometricHelper
import com.bastion.app.utils.SavedCategoryFilterState
import com.bastion.app.utils.SettingsManager
import com.bastion.app.utils.decodeKeePassPathForDisplay
import com.bastion.app.utils.planLocalCategoryMove
import com.bastion.app.viewmodel.PasskeyViewModel
import com.bastion.app.viewmodel.PasswordViewModel
import kotlinx.coroutines.flow.flowOf
import com.bastion.app.autofill_ng.ui.rememberAppIcon
import com.bastion.app.autofill_ng.ui.rememberFavicon
import com.bastion.app.ui.common.state.rememberSaveableLazyListState
import com.bastion.app.ui.common.pull.calculateDampedPullOffset
import com.bastion.app.ui.icons.UnmatchedIconFallback
import com.bastion.app.ui.icons.rememberAutoMatchedSimpleIcon
import com.bastion.app.ui.icons.shouldShowFallbackSlot
import com.bastion.app.ui.password.PasswordTopActionsDropdownMenu
import com.bastion.app.bitwarden.sync.SyncStatus
import com.bastion.app.passkey.PasskeyCredentialIdCodec
import com.bastion.app.passkey.PasskeyPrivateKeyStore
import com.bastion.app.passkey.managementKey
import com.bastion.app.passkey.managementRecordIdOrNull
import androidx.compose.material.icons.filled.Info

/**
 * Passkey 列表屏幕
 * 
 * 与密码列表页面保持完全一致的设计风格：
 * - ExpressiveTopBar 顶栏（大标题 + 胶囊形搜索按钮）
 * - 下拉触发搜索
 * - Android 14+ 完整功能支持
 * - Android 14 以下版本仅查看模式
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PasskeyListScreen(
    viewModel: PasskeyViewModel,
    modifier: Modifier = Modifier,
    onPasskeyClick: (PasskeyEntry) -> Unit = {},
    passwordViewModel: PasswordViewModel? = null,
    onNavigateToPasswordDetail: (Long) -> Unit = {},
    hideTopBar: Boolean = false,
    showStandaloneSettingsEntry: Boolean = false,
    onOpenStandaloneSettings: () -> Unit = {},
    onNavigateToAuthenticator: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val database = remember(context) { PasswordDatabase.getDatabase(context) }
    
    // 收集状态
    val passkeys by viewModel.filteredPasskeys.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    // 「数据库」Tab 各条目的条数统计：基于全量通行密钥按存储来源分组。
    // 注意：PasskeyEntry 存独立表且无软删除字段，故不做 isDeleted 过滤。
    val allPasskeysForStats by viewModel.allPasskeys.collectAsState()
    val passkeyStorageCounts = remember(allPasskeysForStats) {
        com.bastion.app.ui.PasswordStorageCounts(
            total = allPasskeysForStats.size,
            bastionLocal = allPasskeysForStats.count {
                it.keepassDatabaseId == null && it.bitwardenVaultId == null
            },
            perKeePassDatabase = allPasskeysForStats
                .filter { it.keepassDatabaseId != null }
                .groupingBy { it.keepassDatabaseId!! }
                .eachCount(),
            perBitwardenVault = allPasskeysForStats
                .filter { it.bitwardenVaultId != null }
                .groupingBy { it.bitwardenVaultId!! }
                .eachCount()
        )
    }

    val passwords by (passwordViewModel?.allPasswords ?: flowOf(emptyList())).collectAsState(initial = emptyList())
    val passwordMap = remember(passwords) { passwords.associateBy { it.id } }
    val categories by database.categoryDao().getAllCategories().collectAsState(initial = emptyList())
    val keepassDatabases by database.localKeePassDatabaseDao().getAllDatabases().collectAsState(initial = emptyList())
    val bitwardenRepository = remember { BitwardenRepository.getInstance(context) }
    val bitwardenVaults by database.bitwardenVaultDao().getAllVaultsFlow().collectAsState(initial = emptyList())
    val securityManager = remember { com.bastion.app.security.SecurityManager(context) }
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
    val categoryMap = remember(categories) { categories.associateBy { it.id } }
    LaunchedEffect(keepassDatabases.map { it.id }) {
        delay(1_200L)
        viewModel.refreshKeePassPasskeys(trigger = "PASSKEY_PAGE_ENTER")
    }

    val bindingPasskeys = remember(passwords, searchQuery) {
        val rawList = passwords.flatMap { password ->
            val bindings = PasskeyBindingCodec.decodeList(password.passkeyBindings)
            bindings.map { binding ->
                val createdAt = password.createdAt.time
                val lastUsedAt = maxOf(createdAt, password.updatedAt.time)
                PasskeyEntry(
                    credentialId = binding.credentialId.ifBlank { "ref_${password.id}_${binding.rpId}" },
                    rpId = binding.rpId,
                    rpName = binding.rpName.ifBlank { binding.rpId },
                    userId = "",
                    userName = binding.userName,
                    userDisplayName = binding.userDisplayName.ifBlank { binding.userName },
                    publicKeyAlgorithm = PasskeyEntry.ALGORITHM_ES256,
                    publicKey = "",
                    privateKeyAlias = "",
                    createdAt = createdAt,
                    lastUsedAt = lastUsedAt,
                    useCount = 0,
                    iconUrl = null,
                    isDiscoverable = false,
                    isUserVerificationRequired = true,
                    transports = PasskeyEntry.TRANSPORT_INTERNAL,
                    aaguid = "",
                    signCount = 0,
                    isBackedUp = true,
                    notes = "",
                    boundPasswordId = password.id,
                    categoryId = password.categoryId,
                    bitwardenVaultId = null,
                    bitwardenCipherId = null,
                    syncStatus = "REFERENCE",
                    passkeyMode = PasskeyEntry.MODE_LEGACY
                )
            }
        }

        if (searchQuery.isBlank()) {
            rawList
        } else {
            rawList.filter { passkey ->
                passkey.rpId.contains(searchQuery, ignoreCase = true) ||
                    passkey.rpName.contains(searchQuery, ignoreCase = true) ||
                    passkey.userName.contains(searchQuery, ignoreCase = true) ||
                    passkey.userDisplayName.contains(searchQuery, ignoreCase = true) ||
                    passkey.notes.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    val combinedPasskeys = remember(passkeys, bindingPasskeys) {
        if (bindingPasskeys.isEmpty()) return@remember passkeys
        val existingKeys = passkeys.mapTo(mutableSetOf()) { it.passkeyReferenceDedupKey() }
        passkeys + bindingPasskeys.filter { binding ->
            binding.passkeyReferenceDedupKey() !in existingKeys
        }
    }
    var passkeyMigratableById by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    LaunchedEffect(combinedPasskeys) {
        passkeyMigratableById = withContext(Dispatchers.IO) {
            combinedPasskeys.associate { passkey ->
                passkey.managementKey() to isPasskeyMigratableToBitwarden(context, passkey)
            }
        }
    }
    // 是否完全支持 Passkey
    val isFullySupported = viewModel.isPasskeyFullySupported
    
    // 列表状态
    val listState = rememberSaveableLazyListState()
    val scrollCollapseThresholdPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        8.dp.toPx()
    }
    val scrollCollapseFraction by remember(listState) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0 ||
                listState.firstVisibleItemScrollOffset > scrollCollapseThresholdPx
            ) 1f else 0f
        }
    }
    
    // 搜索栏展开状态
    var isSearchExpanded by remember { mutableStateOf(false) }

    var passkeyToBind by remember { mutableStateOf<PasskeyEntry?>(null) }
    var passkeyToMoveCategory by remember { mutableStateOf<PasskeyEntry?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedPasskeys by remember { mutableStateOf(setOf<String>()) }
    var pendingDeletePasskey by remember { mutableStateOf<PasskeyEntry?>(null) }
    var selectedCategoryFilter by remember { mutableStateOf<UnifiedCategoryFilterSelection>(UnifiedCategoryFilterSelection.All) }
    var showCategoryFilterDialog by remember { mutableStateOf(false) }
    val categoryMgmt = rememberCategoryManagementState()
    var showTopActionsMenu by remember { mutableStateOf(false) }
    var showBatchMoveCategoryDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var deletePasswordInput by remember { mutableStateOf("") }
    var deletePasswordError by remember { mutableStateOf(false) }
    val haptic = rememberHapticFeedback()
    // 【方案 A】防误触滑动门控：滑动默认锁定，长按条目激活后 3 秒内可滑
    val armState = com.bastion.app.ui.gestures.rememberSwipeArmState()
    val settingsManager = remember { SettingsManager(context) }
    val savedCategoryFilterState by settingsManager
        .categoryFilterStateFlow(SettingsManager.CategoryFilterScope.PASSKEY)
        .collectAsState(initial = SavedCategoryFilterState())
    var hasRestoredCategoryFilter by remember { mutableStateOf(false) }
    val appSettings by settingsManager.settingsFlow.collectAsState(
        initial = AppSettings(biometricEnabled = false)
    )
    val biometricHelper = remember { BiometricHelper(context) }
    val canUseBiometric = remember(appSettings.biometricEnabled) {
        appSettings.biometricEnabled && biometricHelper.isBiometricAvailable()
    }
    val activity = context as? FragmentActivity
    val boundPasswordForPasskey: (PasskeyEntry) -> PasswordEntry? = remember(passwordMap) {
        { passkey -> passkey.boundPasswordId?.let { passwordId -> passwordMap[passwordId] } }
    }
    val effectiveCategoryId: (PasskeyEntry) -> Long? = remember(passwordMap) {
        { passkey -> boundPasswordForPasskey(passkey)?.categoryId ?: passkey.categoryId }
    }
    val effectiveBitwardenVaultId: (PasskeyEntry) -> Long? = remember(passwordMap) {
        { passkey ->
            val boundPassword = boundPasswordForPasskey(passkey)
            val boundVaultId = boundPassword?.bitwardenVaultId
            val canSyncToBitwarden = passkeyMigratableById[passkey.managementKey()] ?: false
            if (boundVaultId != null && !canSyncToBitwarden) {
                passkey.bitwardenVaultId
            } else {
                boundVaultId ?: passkey.bitwardenVaultId
            }
        }
    }
    val effectiveBitwardenFolderId: (PasskeyEntry) -> String? = remember(passwordMap) {
        { passkey ->
            val boundPassword = boundPasswordForPasskey(passkey)
            val boundVaultId = boundPassword?.bitwardenVaultId
            val canSyncToBitwarden = passkeyMigratableById[passkey.managementKey()] ?: false
            if (boundVaultId != null && !canSyncToBitwarden) {
                passkey.bitwardenFolderId
            } else {
                boundPassword?.bitwardenFolderId ?: passkey.bitwardenFolderId
            }
        }
    }
    val effectiveKeePassDatabaseId: (PasskeyEntry) -> Long? = remember(passwordMap) {
        { passkey -> boundPasswordForPasskey(passkey)?.keepassDatabaseId ?: passkey.keepassDatabaseId }
    }
    val effectiveKeePassGroupPath: (PasskeyEntry) -> String? = remember(passwordMap) {
        { passkey -> boundPasswordForPasskey(passkey)?.keepassGroupPath ?: passkey.keepassGroupPath }
    }
    val effectiveIsFavorite: (PasskeyEntry) -> Boolean = remember(passwordMap) {
        { passkey -> boundPasswordForPasskey(passkey)?.isFavorite == true }
    }
    val categoryFilteredPasskeys = remember(combinedPasskeys, selectedCategoryFilter, passwordMap) {
        combinedPasskeys.filter { passkey ->
            val effectiveVaultId = effectiveBitwardenVaultId(passkey)
            val effectiveFolderId = effectiveBitwardenFolderId(passkey)
            val effectiveKeePassId = effectiveKeePassDatabaseId(passkey)
            val effectiveGroupPath = effectiveKeePassGroupPath(passkey)
            val isLocal = effectiveVaultId == null && effectiveKeePassId == null
            when (val filter = selectedCategoryFilter) {
                UnifiedCategoryFilterSelection.All -> true
                UnifiedCategoryFilterSelection.Local -> isLocal
                UnifiedCategoryFilterSelection.Uncategorized -> effectiveCategoryId(passkey) == null
                UnifiedCategoryFilterSelection.LocalStarred -> isLocal && effectiveIsFavorite(passkey)
                UnifiedCategoryFilterSelection.LocalUncategorized -> isLocal && effectiveCategoryId(passkey) == null
                is UnifiedCategoryFilterSelection.Custom -> effectiveCategoryId(passkey) == filter.categoryId
                UnifiedCategoryFilterSelection.Starred -> effectiveIsFavorite(passkey)
                is UnifiedCategoryFilterSelection.BitwardenVaultFilter -> effectiveVaultId == filter.vaultId
                is UnifiedCategoryFilterSelection.BitwardenFolderFilter ->
                    effectiveVaultId == filter.vaultId && effectiveFolderId == filter.folderId
                is UnifiedCategoryFilterSelection.BitwardenVaultStarredFilter ->
                    effectiveVaultId == filter.vaultId && effectiveIsFavorite(passkey)
                is UnifiedCategoryFilterSelection.BitwardenVaultUncategorizedFilter ->
                    effectiveVaultId == filter.vaultId && effectiveFolderId == null
                is UnifiedCategoryFilterSelection.KeePassDatabaseFilter -> effectiveKeePassId == filter.databaseId
                is UnifiedCategoryFilterSelection.KeePassGroupFilter ->
                    effectiveKeePassId == filter.databaseId && effectiveGroupPath == filter.groupPath
                is UnifiedCategoryFilterSelection.KeePassDatabaseStarredFilter ->
                    effectiveKeePassId == filter.databaseId && effectiveIsFavorite(passkey)
                is UnifiedCategoryFilterSelection.KeePassDatabaseUncategorizedFilter ->
                    effectiveKeePassId == filter.databaseId && effectiveGroupPath.isNullOrBlank()
            }
        }
    }
    LaunchedEffect(savedCategoryFilterState, hasRestoredCategoryFilter) {
        if (hasRestoredCategoryFilter) return@LaunchedEffect
        selectedCategoryFilter = decodePasskeyCategoryFilter(savedCategoryFilterState)
        hasRestoredCategoryFilter = true
    }
    LaunchedEffect(selectedCategoryFilter, hasRestoredCategoryFilter) {
        if (!hasRestoredCategoryFilter) return@LaunchedEffect
        settingsManager.updateCategoryFilterState(
            scope = SettingsManager.CategoryFilterScope.PASSKEY,
            state = encodePasskeyCategoryFilter(selectedCategoryFilter)
        )
    }
    val visiblePasskeys = remember(categoryFilteredPasskeys, pendingDeletePasskey) {
        val deletingId = pendingDeletePasskey?.managementKey()
        val baseList = if (deletingId == null) {
            categoryFilteredPasskeys
        } else {
            categoryFilteredPasskeys.filterNot { it.managementKey() == deletingId }
        }
        baseList
    }
    val failedPasskeyCount = remember(visiblePasskeys) {
        visiblePasskeys.count { it.syncStatus == "FAILED" }
    }
    var lastShownFailedPasskeyCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(failedPasskeyCount) {
        if (failedPasskeyCount > 0 && failedPasskeyCount != lastShownFailedPasskeyCount) {
            val message = context.getString(R.string.sync_status_failed_short) + " ($failedPasskeyCount)"
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
        lastShownFailedPasskeyCount = failedPasskeyCount
    }
    
    // Passkeys keep pull-to-search; disable pull-to-sync on Bitwarden filters.
    val isBitwardenDatabaseView = false && when (selectedCategoryFilter) {
        is UnifiedCategoryFilterSelection.BitwardenVaultFilter,
        is UnifiedCategoryFilterSelection.BitwardenFolderFilter,
        is UnifiedCategoryFilterSelection.BitwardenVaultStarredFilter,
        is UnifiedCategoryFilterSelection.BitwardenVaultUncategorizedFilter -> true
        else -> false
    }
    val selectedBitwardenVaultId = when (val filter = selectedCategoryFilter) {
        is UnifiedCategoryFilterSelection.BitwardenVaultFilter -> filter.vaultId
        is UnifiedCategoryFilterSelection.BitwardenFolderFilter -> filter.vaultId
        is UnifiedCategoryFilterSelection.BitwardenVaultStarredFilter -> filter.vaultId
        is UnifiedCategoryFilterSelection.BitwardenVaultUncategorizedFilter -> filter.vaultId
        else -> null
    }
    // Activity 级共享实例：默认的 viewModel() 会按 NavBackStackEntry 各建一个，
    // 导致 BitwardenViewModel 出现多个副本（详见 activityViewModel 文档）。
    val bitwardenViewModel: com.bastion.app.bitwarden.viewmodel.BitwardenViewModel = activityViewModel()
    val bitwardenSyncStatusByVault by bitwardenViewModel.syncStatusByVault.collectAsState()
    val isTopBarSyncing = selectedBitwardenVaultId?.let { vaultId ->
        bitwardenSyncStatusByVault[vaultId].isUserVisibleSyncInProgress()
    } == true

    // 下拉搜索相关
    var currentOffset by remember { mutableFloatStateOf(0f) }
    val searchTriggerDistance = remember(density, isBitwardenDatabaseView) {
        with(density) { (if (isBitwardenDatabaseView) 40.dp else 72.dp).toPx() }
    }
    val syncTriggerDistance = remember(density) { with(density) { 72.dp.toPx() } }
    val maxDragDistance = remember(density) { with(density) { 100.dp.toPx() } }
    val syncHoldMillis = 500L
    var isSettlingBack by remember { mutableStateOf(false) }
    var hasVibrated by remember { mutableStateOf(false) }
    var hasSyncStageVibrated by remember { mutableStateOf(false) }
    var syncHintArmed by remember { mutableStateOf(false) }
    var isBitwardenSyncing by remember { mutableStateOf(false) }
    var lockPullUntilSyncFinished by remember { mutableStateOf(false) }
    var canRunBitwardenSync by remember { mutableStateOf(false) }
    var showSyncFeedback by remember { mutableStateOf(false) }
    var syncFeedbackMessage by remember { mutableStateOf("") }
    var syncFeedbackIsSuccess by remember { mutableStateOf(false) }
    val collapseAnimatable = remember { androidx.compose.animation.core.Animatable(0f) }
    
    // 震动服务
    val vibrator = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
        }
    }

    suspend fun resolveSyncableVaultId(): Long? {
        val activeVault = bitwardenRepository.getActiveVault() ?: run {
            canRunBitwardenSync = false
            return null
        }
        val unlocked = bitwardenRepository.isVaultUnlocked(activeVault.id)
        canRunBitwardenSync = unlocked
        return if (unlocked) activeVault.id else null
    }

    fun vibratePullThreshold(isSyncStage: Boolean) {
        if (isSyncStage && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            vibrator?.vibrate(
                android.os.VibrationEffect.createPredefined(android.os.VibrationEffect.EFFECT_DOUBLE_CLICK)
            )
        } else {
            vibrator?.vibrate(android.os.VibrationEffect.createWaveform(com.bastion.app.util.VibrationPatterns.TICK, -1))
        }
    }

    fun updatePullThresholdHaptics(oldOffset: Float, newOffset: Float) {
        if (oldOffset < searchTriggerDistance && newOffset >= searchTriggerDistance && !hasVibrated) {
            hasVibrated = true
            vibratePullThreshold(isSyncStage = false)
        } else if (newOffset < searchTriggerDistance) {
            hasVibrated = false
        }

        if (!isBitwardenDatabaseView) {
            hasSyncStageVibrated = false
            return
        }

        if (oldOffset < syncTriggerDistance && newOffset >= syncTriggerDistance && !hasSyncStageVibrated) {
            hasSyncStageVibrated = true
            vibratePullThreshold(isSyncStage = true)
        } else if (newOffset < syncTriggerDistance) {
            hasSyncStageVibrated = false
        }
    }

    fun interruptCollapseAnimation() {
        if (!collapseAnimatable.isRunning && !isSettlingBack) return
        isSettlingBack = false
        scope.launch {
            collapseAnimatable.stop()
            collapseAnimatable.snapTo(currentOffset)
        }
    }

    suspend fun collapsePullOffsetSmoothly() {
        if (currentOffset <= 0.5f) {
            currentOffset = 0f
            isSettlingBack = false
            return
        }
        if (collapseAnimatable.isRunning) return
        isSettlingBack = true
        collapseAnimatable.snapTo(currentOffset)
        try {
            collapseAnimatable.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = 140,
                    easing = androidx.compose.animation.core.FastOutLinearInEasing
                )
            ) {
                currentOffset = value
            }
        } finally {
            currentOffset = 0f
            collapseAnimatable.snapTo(0f)
            isSettlingBack = false
        }
    }

    fun onPullRelease(): Boolean {
        if (isBitwardenDatabaseView && syncHintArmed && !isBitwardenSyncing) {
            syncHintArmed = false
            isBitwardenSyncing = true
            lockPullUntilSyncFinished = true
            currentOffset = syncTriggerDistance
            scope.launch {
                val vaultId = resolveSyncableVaultId()
                if (vaultId == null) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.pull_sync_requires_bitwarden_login),
                        Toast.LENGTH_SHORT
                    ).show()
                    isBitwardenSyncing = false
                    lockPullUntilSyncFinished = false
                    hasVibrated = false
                    hasSyncStageVibrated = false
                    collapsePullOffsetSmoothly()
                    return@launch
                }

                val syncResult = bitwardenRepository.syncForUserVisibleRequest(
                    vaultId = vaultId,
                    requestIdPrefix = "bw-passkey-list-vault"
                )
                when (syncResult) {
                    is BitwardenRepository.SyncResult.Success -> {
                        syncFeedbackIsSuccess = true
                        syncFeedbackMessage = context.getString(R.string.pull_sync_success)
                        showSyncFeedback = true
                        Toast.makeText(
                            context,
                            context.getString(R.string.pull_sync_success),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    is BitwardenRepository.SyncResult.Error -> {
                        syncFeedbackIsSuccess = false
                        syncFeedbackMessage = context.getString(R.string.sync_status_failed_full)
                        showSyncFeedback = true
                        Toast.makeText(
                            context,
                            context.getString(R.string.sync_status_failed_full) + ": " + syncResult.message,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    is BitwardenRepository.SyncResult.EmptyVaultBlocked -> {
                        syncFeedbackIsSuccess = false
                        syncFeedbackMessage = context.getString(R.string.sync_status_failed_full)
                        showSyncFeedback = true
                        Toast.makeText(
                            context,
                            context.getString(R.string.sync_status_failed_full) + ": " + syncResult.reason,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                isBitwardenSyncing = false
                lockPullUntilSyncFinished = false
                hasVibrated = false
                hasSyncStageVibrated = false
                collapsePullOffsetSmoothly()
                kotlinx.coroutines.delay(1400)
                showSyncFeedback = false
            }
            return true
        }

        if (currentOffset >= searchTriggerDistance) {
            isSearchExpanded = true
            hasVibrated = false
        }
        return false
    }

    LaunchedEffect(isBitwardenDatabaseView) {
        if (isBitwardenDatabaseView) {
            resolveSyncableVaultId()
        } else {
            interruptCollapseAnimation()
            canRunBitwardenSync = false
            syncHintArmed = false
            isBitwardenSyncing = false
            lockPullUntilSyncFinished = false
            showSyncFeedback = false
            currentOffset = 0f
            hasVibrated = false
            hasSyncStageVibrated = false
        }
    }

    LaunchedEffect(currentOffset >= syncTriggerDistance, isBitwardenDatabaseView, isBitwardenSyncing) {
        if (isBitwardenDatabaseView && currentOffset >= syncTriggerDistance && !isBitwardenSyncing) {
            resolveSyncableVaultId()
        }
    }

    LaunchedEffect(currentOffset, isBitwardenDatabaseView, canRunBitwardenSync, isBitwardenSyncing) {
        if (isBitwardenDatabaseView && currentOffset >= syncTriggerDistance && canRunBitwardenSync && !isBitwardenSyncing) {
            kotlinx.coroutines.delay(syncHoldMillis)
            if (isBitwardenDatabaseView && currentOffset >= syncTriggerDistance && canRunBitwardenSync && !isBitwardenSyncing) {
                syncHintArmed = true
            }
        } else {
            syncHintArmed = false
        }
    }

    LaunchedEffect(isSearchExpanded) {
        if (isSearchExpanded) {
            if (!lockPullUntilSyncFinished && currentOffset > 0.5f) {
                collapsePullOffsetSmoothly()
            } else {
                interruptCollapseAnimation()
                currentOffset = 0f
                isSettlingBack = false
            }
            hasVibrated = false
            hasSyncStageVibrated = false
            syncHintArmed = false
        }
    }
    
    // 嵌套滚动连接（下拉触发搜索）
    val nestedScrollConnection = remember(isBitwardenDatabaseView) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (lockPullUntilSyncFinished) {
                    return available
                }
                if (currentOffset > 0 && available.y < 0) {
                    interruptCollapseAnimation()
                    val newOffset = (currentOffset + available.y).coerceAtLeast(0f)
                    val consumed = currentOffset - newOffset
                    currentOffset = newOffset
                    return Offset(0f, -consumed)
                }
                return Offset.Zero
            }
            
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (lockPullUntilSyncFinished) {
                    return available
                }
                if (available.y > 0 && source == NestedScrollSource.UserInput) {
                    interruptCollapseAnimation()
                    val newOffset = calculateDampedPullOffset(
                        currentOffset = currentOffset,
                        dragDelta = available.y,
                        maxDragDistance = maxDragDistance
                    )
                    val oldOffset = currentOffset
                    currentOffset = newOffset
                    updatePullThresholdHaptics(oldOffset = oldOffset, newOffset = newOffset)
                    return available
                }
                return Offset.Zero
            }
            
            override suspend fun onPreFling(available: Velocity): Velocity {
                val syncStarted = onPullRelease()
                if (!syncStarted && !lockPullUntilSyncFinished) {
                    collapsePullOffsetSmoothly()
                }
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (!lockPullUntilSyncFinished && currentOffset > 0f) {
                    val syncStarted = onPullRelease()
                    if (!syncStarted && !lockPullUntilSyncFinished) {
                        collapsePullOffsetSmoothly()
                    }
                }
                return Velocity.Zero
            }
        }
    }
    
    // 监听搜索查询变化
    LaunchedEffect(searchQuery) {
        if (searchQuery.isNotBlank()) {
            isSearchExpanded = true
        }
    }

    BackHandler(enabled = isSearchExpanded) {
        isSearchExpanded = false
        viewModel.updateSearchQuery("")
    }

    BackHandler(enabled = selectionMode) {
        selectionMode = false
        selectedPasskeys = emptySet()
    }
    
    // 显示版本警告
    var showVersionWarning by remember { mutableStateOf(!isFullySupported) }

    suspend fun applyStorageTarget(passkey: PasskeyEntry, target: UnifiedMoveCategoryTarget): Result<PasskeyEntry> {
        val currentVaultId = passkey.bitwardenVaultId
        val currentCipherId = passkey.bitwardenCipherId
        val targetVaultId = when (target) {
            is UnifiedMoveCategoryTarget.BitwardenVaultTarget -> target.vaultId
            is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> target.vaultId
            else -> null
        }

        if (targetVaultId != null) {
            val canMoveToBitwarden = withContext(Dispatchers.IO) {
                isPasskeyMigratableToBitwarden(context, passkey)
            }
            if (!canMoveToBitwarden) {
                return Result.failure(PasskeyBitwardenMoveBlockedException())
            }
        }

        val moved = when (target) {
            UnifiedMoveCategoryTarget.Uncategorized -> passkey.copy(
                categoryId = null,
                keepassDatabaseId = null,
                keepassGroupPath = null,
                bitwardenFolderId = null,
                bitwardenVaultId = null
            )
            is UnifiedMoveCategoryTarget.BastionCategory -> passkey.copy(
                categoryId = target.categoryId,
                keepassDatabaseId = null,
                keepassGroupPath = null,
                bitwardenFolderId = null,
                bitwardenVaultId = null
            )
            is UnifiedMoveCategoryTarget.BitwardenVaultTarget -> passkey.copy(
                bitwardenVaultId = target.vaultId,
                bitwardenFolderId = null,
                keepassGroupPath = null,
                keepassDatabaseId = null,
            )
            is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> passkey.copy(
                bitwardenVaultId = target.vaultId,
                bitwardenFolderId = target.folderId,
                keepassGroupPath = null,
                keepassDatabaseId = null,
            )
            is UnifiedMoveCategoryTarget.KeePassDatabaseTarget -> passkey.copy(
                keepassDatabaseId = target.databaseId,
                keepassGroupPath = null,
                bitwardenFolderId = null,
                bitwardenVaultId = null
            )
            is UnifiedMoveCategoryTarget.KeePassGroupTarget -> passkey.copy(
                keepassDatabaseId = target.databaseId,
                keepassGroupPath = target.groupPath,
                bitwardenFolderId = null,
                bitwardenVaultId = null
            )
        }

        val keepExistingCipher =
            !currentCipherId.isNullOrBlank() &&
                currentVaultId != null &&
                currentVaultId == targetVaultId

        val resolvedSyncStatus = when {
            moved.syncStatus == "REFERENCE" -> "REFERENCE"
            targetVaultId == null -> "NONE"
            keepExistingCipher -> if (passkey.syncStatus == "SYNCED") "SYNCED" else "PENDING"
            else -> "PENDING"
        }
        val resolvedMode = when (target) {
            is UnifiedMoveCategoryTarget.BitwardenVaultTarget,
            is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> PasskeyEntry.MODE_BW_COMPAT
            is UnifiedMoveCategoryTarget.KeePassDatabaseTarget,
            is UnifiedMoveCategoryTarget.KeePassGroupTarget -> PasskeyEntry.MODE_KEEPASS_COMPAT
            else -> if (passkey.isKeePassCompatible()) {
                PasskeyEntry.MODE_KEEPASS_COMPAT
            } else {
                passkey.passkeyMode
            }
        }

        return Result.success(
            moved.copy(
                passkeyMode = resolvedMode,
                bitwardenCipherId = if (keepExistingCipher) currentCipherId else null,
                syncStatus = resolvedSyncStatus
            )
        )
    }

    suspend fun queueSourceBitwardenDeleteAfterPasskeyMove(
        passkey: PasskeyEntry,
        target: UnifiedMoveCategoryTarget
    ): Result<Unit> {
        val currentVaultId = passkey.bitwardenVaultId
        val currentCipherId = passkey.bitwardenCipherId
        val targetVaultId = when (target) {
            is UnifiedMoveCategoryTarget.BitwardenVaultTarget -> target.vaultId
            is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> target.vaultId
            else -> null
        }
        val isLeavingCurrentCipher =
            currentVaultId != null &&
                !currentCipherId.isNullOrBlank() &&
                currentVaultId != targetVaultId
        if (!isLeavingCurrentCipher) return Result.success(Unit)

        return bitwardenRepository.queueCipherDelete(
            vaultId = currentVaultId!!,
            cipherId = currentCipherId!!,
            itemType = BitwardenPendingOperation.ITEM_TYPE_PASSKEY
        ).map { Unit }
    }

    suspend fun persistStorageTarget(passkey: PasskeyEntry, target: UnifiedMoveCategoryTarget): Result<Unit> {
        val updateResult = applyStorageTarget(passkey, target)
        if (updateResult.isFailure) {
            return Result.failure(
                updateResult.exceptionOrNull()
                    ?: IllegalStateException("Passkey storage target update failed")
            )
        }
        val moved = updateResult.getOrThrow()
        val persisted = viewModel.updatePasskey(moved)
        if (persisted.isFailure) {
            return Result.failure(
                persisted.exceptionOrNull()
                    ?: IllegalStateException("Passkey update failed")
            )
        }

        val queueDelete = queueSourceBitwardenDeleteAfterPasskeyMove(passkey, target)
        if (queueDelete.isFailure) {
            return Result.failure(
                queueDelete.exceptionOrNull()
                    ?: IllegalStateException("Queue Bitwarden delete failed")
            )
        }

        return Result.success(Unit)
    }

    suspend fun deletePasskeyWithBinding(passkey: PasskeyEntry): Boolean {
        val boundPassword = passkey.boundPasswordId?.let { passwordMap[it] }
        if (boundPassword != null && passwordViewModel != null) {
            val updatedBindings = PasskeyBindingCodec.removeBinding(boundPassword.passkeyBindings, passkey.credentialId)
            passwordViewModel.updatePasskeyBindings(boundPassword.id, updatedBindings)
        }
        val isReferenceOnly =
            passkey.syncStatus == "REFERENCE" &&
                passkey.privateKeyAlias.isBlank() &&
                passkey.publicKey.isBlank()
        if (!isReferenceOnly) {
            val vaultId = passkey.bitwardenVaultId
            val cipherId = passkey.bitwardenCipherId
            val isBoundToSyncedPassword = boundPassword != null &&
                !boundPassword.bitwardenCipherId.isNullOrBlank()
            if (isBoundToSyncedPassword) {
                // 绑定型 passkey：passkey 的 cipherId 指向密码 cipher，绝不能 queueCipherDelete（会误删整个密码 cipher）。
                // 标记 DELETE_PENDING，由 uploadModifiedPasskeys 从密码 cipher 的 fido2Credentials 移除该 credential 后再删本地。
                val updated = passkey.copy(
                    syncStatus = PasskeyEntry.SYNC_STATUS_DELETE_PENDING
                )
                if (viewModel.updatePasskey(updated).isFailure) {
                    return false
                }
                return true
            }
            if (vaultId != null && !cipherId.isNullOrBlank()) {
                // 未绑定/独立 passkey cipher：软删独立 cipher（现状）
                val queueResult = bitwardenRepository.queueCipherDelete(
                    vaultId = vaultId,
                    cipherId = cipherId,
                    itemType = BitwardenPendingOperation.ITEM_TYPE_PASSKEY
                )
                if (queueResult.isFailure) {
                    return false
                }
            }
            if (viewModel.deletePasskey(passkey).isFailure) {
                return false
            }
        }
        return true
    }

    val performDeleteTargets: (List<PasskeyEntry>) -> Unit = { targets ->
        scope.launch {
            if (targets.isNotEmpty()) {
                var deletedCount = 0
                var failedCount = 0
                targets.forEach { passkey ->
                    if (deletePasskeyWithBinding(passkey)) {
                        deletedCount++
                    } else {
                        failedCount++
                    }
                }
                if (selectionMode) {
                    selectionMode = false
                    selectedPasskeys = emptySet()
                }
                pendingDeletePasskey = null
                showDeleteConfirmDialog = false
                deletePasswordInput = ""
                deletePasswordError = false
                val msg = if (failedCount == 0) {
                    context.getString(R.string.deleted_items, deletedCount)
                } else {
                    "${context.getString(R.string.deleted_items, deletedCount)}，失败$failedCount"
                }
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            } else {
                showDeleteConfirmDialog = false
                pendingDeletePasskey = null
            }
        }
    }

    val topBarTitle = when (val filter = selectedCategoryFilter) {
        UnifiedCategoryFilterSelection.All -> stringResource(R.string.passkey_title)
        UnifiedCategoryFilterSelection.Local -> stringResource(R.string.passkey_title)
        UnifiedCategoryFilterSelection.Uncategorized -> stringResource(R.string.category_none)
        UnifiedCategoryFilterSelection.LocalStarred -> "${stringResource(R.string.filter_bastion)} · ${stringResource(R.string.filter_starred)}"
        UnifiedCategoryFilterSelection.LocalUncategorized -> "${stringResource(R.string.filter_bastion)} · ${stringResource(R.string.filter_uncategorized)}"
        is UnifiedCategoryFilterSelection.Custom -> categoryMap[filter.categoryId]?.name ?: stringResource(R.string.passkey_title)
        UnifiedCategoryFilterSelection.Starred -> stringResource(R.string.filter_starred)
        is UnifiedCategoryFilterSelection.BitwardenVaultFilter -> stringResource(R.string.filter_bitwarden)
        is UnifiedCategoryFilterSelection.BitwardenFolderFilter -> stringResource(R.string.filter_bitwarden)
        is UnifiedCategoryFilterSelection.BitwardenVaultStarredFilter -> "${stringResource(R.string.filter_bitwarden)} · ${stringResource(R.string.filter_starred)}"
        is UnifiedCategoryFilterSelection.BitwardenVaultUncategorizedFilter -> "${stringResource(R.string.filter_bitwarden)} · ${stringResource(R.string.filter_uncategorized)}"
        is UnifiedCategoryFilterSelection.KeePassDatabaseFilter -> stringResource(R.string.filter_keepass)
        is UnifiedCategoryFilterSelection.KeePassGroupFilter -> stringResource(R.string.filter_keepass)
        is UnifiedCategoryFilterSelection.KeePassDatabaseStarredFilter -> "${stringResource(R.string.filter_keepass)} · ${stringResource(R.string.filter_starred)}"
        is UnifiedCategoryFilterSelection.KeePassDatabaseUncategorizedFilter -> "${stringResource(R.string.filter_keepass)} · ${stringResource(R.string.filter_uncategorized)}"
    }
    
    // 悬浮叠加：顶栏浮在列表之上，列表内容可从其下方穿过（对齐密码页/验证器页观感）
    var topOverlayHeightPx by remember { mutableStateOf(0) }
    val topOverlayHeightDp = with(LocalDensity.current) { topOverlayHeightPx.toDp() }

    Box(
        modifier = modifier
            .fillMaxSize()
    ) {
        // 悬浮层：置顶 + 提升层级（压在列表之上），高度实测供列表避让
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .zIndex(1f)
                .onSizeChanged { size -> topOverlayHeightPx = size.height.toInt() }
        ) {
        // ExpressiveTopBar（与密码列表完全一致）
        if (!hideTopBar) {
            ExpressiveTopBar(
                title = topBarTitle,
                searchQuery = searchQuery,
                onSearchQueryChange = viewModel::updateSearchQuery,
                isSearchExpanded = isSearchExpanded,
                onSearchExpandedChange = { isSearchExpanded = it },
                searchHint = stringResource(R.string.passkey_search_placeholder),
                // 性能：胶囊 bounds 逐帧回调且无消费方，会触发整屏重组；停用
                onActionPillBoundsChanged = null,
                scrollCollapseFraction = scrollCollapseFraction,
                actions = {
                    onNavigateToAuthenticator?.let { navigateToAuthenticator ->
                        IconButton(onClick = navigateToAuthenticator) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = stringResource(R.string.authenticator),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (appSettings.categorySelectionUiMode == com.bastion.app.data.CategorySelectionUiMode.CHIP_MENU) {
                        IconButton(onClick = { showCategoryFilterDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = stringResource(R.string.category),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        IconButton(onClick = { showCategoryFilterDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = stringResource(R.string.category),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (selectedBitwardenVaultId != null) {
                        IconButton(
                            onClick = {
                                if (isTopBarSyncing) return@IconButton
                                val vaultId = selectedBitwardenVaultId ?: return@IconButton
                                bitwardenViewModel.requestManualSync(vaultId)
                            },
                            enabled = !isTopBarSyncing
                        ) {
                            if (isTopBarSyncing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Sync,
                                    contentDescription = stringResource(R.string.refresh),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { isSearchExpanded = true }) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = stringResource(R.string.search),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (showStandaloneSettingsEntry) {
                            IconButton(onClick = { showTopActionsMenu = true }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.more_options),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (appSettings.categorySelectionUiMode == com.bastion.app.data.CategorySelectionUiMode.CHIP_MENU) {
                            UnifiedCategoryFilterChipMenuDropdown(
                                expanded = showCategoryFilterDialog,
                                onDismissRequest = { showCategoryFilterDialog = false },
                                offset = UnifiedCategoryFilterChipMenuOffset
                            ) {
                                UnifiedCategoryFilterChipMenu(
                                    visible = true,
                                    onDismiss = { showCategoryFilterDialog = false },
                                    selected = selectedCategoryFilter,
                                    onSelect = { selection -> selectedCategoryFilter = selection },
                                    categories = categories,
                                    keepassDatabases = keepassDatabases,
                                    bitwardenVaults = bitwardenVaults,
                                    getBitwardenFolders = { vaultId -> database.bitwardenFolderDao().getFoldersByVaultFlow(vaultId) },
                                    getKeePassGroups = getKeePassGroups,
                                    categoryEditMode = categoryMgmt.categoryEditMode,
                                    onRequestCategoryAction = { categoryMgmt.categoryActionTarget = it },
                                    quickFilterContent = {},
                                    trailingContent = {
                                        CategoryManagementTrailingContent(
                                            state = categoryMgmt,
                                            categories = categories,
                                            keepassDatabases = keepassDatabases,
                                            bitwardenVaults = bitwardenVaults,
                                            getBitwardenFolders = { vaultId -> database.bitwardenFolderDao().getFoldersByVaultFlow(vaultId) },
                                            getKeePassGroups = getKeePassGroups,
                                            passwordViewModel = passwordViewModel!!,
                                            onDismissFilterSheet = { showCategoryFilterDialog = false }
                                        )
                                    },
                                    presentationMode = com.bastion.app.ui.components.UnifiedCategoryFilterChipMenuPresentationMode.ThreeChipsTabs,
                                    // 通行密钥页没有横排快捷筛选条，弹层是「标星 / 未分类 / 仅本地」
                                    // 的唯一入口，必须开启，否则这三个筛选在 UI 上完全不可达。
                                    showQuickFilters = true,
                                    entryCounts = passkeyStorageCounts
                                )
                            }
                        }
                        PasswordTopActionsDropdownMenu(
                            expanded = showTopActionsMenu,
                            onDismissRequest = { showTopActionsMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.nav_settings)) },
                                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                onClick = {
                                    showTopActionsMenu = false
                                    onOpenStandaloneSettings()
                                }
                            )
                        }
                    }
                }
            )
        }
        
        // 版本兼容性警告（跟随悬浮层，位于顶栏下方）
        SafeAnimatedVisibility(
            visible = showVersionWarning && !isFullySupported,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            VersionWarningBanner(
                androidVersion = viewModel.androidVersion,
                onDismiss = { showVersionWarning = false }
            )
        }
        } // 悬浮层结束（顶栏 + 版本警告条）
        
        // 主内容 + 左下角胶囊多选栏
        Box(modifier = Modifier.fillMaxSize()) {
            val contentPullOffset = if (isBitwardenDatabaseView) {
                (currentOffset * 0.28f).toInt()
            } else {
                currentOffset.toInt()
            }

            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (isLoading) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    } else if (visiblePasskeys.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(isSearchExpanded) {
                                    detectVerticalDragGestures(
                                        onVerticalDrag = { _, dragAmount ->
                                            if (dragAmount > 0f) {
                                                val newOffset = calculateDampedPullOffset(
                                                    currentOffset = currentOffset,
                                                    dragDelta = dragAmount,
                                                    maxDragDistance = maxDragDistance
                                                )
                                                val oldOffset = currentOffset
                                                currentOffset = newOffset
                                                updatePullThresholdHaptics(oldOffset = oldOffset, newOffset = newOffset)
                                            }
                                        },
                                        onDragEnd = {
                                            val syncStarted = onPullRelease()
                                            if (!syncStarted && !lockPullUntilSyncFinished) {
                                                scope.launch { collapsePullOffsetSmoothly() }
                                            }
                                        },
                                        onDragCancel = {
                                            if (!lockPullUntilSyncFinished) {
                                                scope.launch { collapsePullOffsetSmoothly() }
                                            }
                                        }
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.offset { IntOffset(0, contentPullOffset) }
                            ) {
                                Icon(
                                    Icons.Default.Key,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = if (searchQuery.isEmpty())
                                        stringResource(R.string.passkey_empty_title)
                                    else
                                        stringResource(R.string.passkey_no_search_results),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 16.dp)
                                )
                                if (searchQuery.isEmpty() && isFullySupported) {
                                    Text(
                                        text = stringResource(R.string.passkey_empty_message),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(top = 8.dp, start = 32.dp, end = 32.dp)
                                    )
                                }
                            }
                        }
                    } else {
                        // 滚动掉帧采样：只在确实掉帧时输出一行摘要，正常滚动静默（见 ScrollJankMonitor）
                        com.bastion.app.ui.perf.ScrollJankReporter(listState = listState, label = "passkeys")
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .offset { IntOffset(0, contentPullOffset) }
                                .nestedScroll(nestedScrollConnection),
                            state = listState,
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                // top = 状态栏 + 顶部 Bar 高度(88dp)：沉浸式布局避让
                                // 悬浮叠加：按悬浮层实测高度避让，随收起动画自动联动；额外 12dp 呼吸空间
                                top = topOverlayHeightDp + 12.dp,
                                bottom = if (selectionMode) 140.dp else 100.dp
                            ),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(
                                items = visiblePasskeys,
                                key = { it.managementKey() }
                            ) { passkey ->
                                val passkeyKey = passkey.managementKey()
                                val boundPassword = passkey.boundPasswordId?.let { passwordMap[it] }
                                val currentCategoryId = effectiveCategoryId(passkey)
                                val categoryName = currentCategoryId
                                    ?.let { categoryMap[it]?.name }
                                    ?: stringResource(R.string.category_none)
                                val isSelected = selectedPasskeys.contains(passkeyKey)
                                val isPendingDelete = pendingDeletePasskey?.managementKey() == passkeyKey

                                key(passkeyKey, isSelected, isPendingDelete, selectionMode) {
                                    SwipeActions(
                                        onSwipeLeft = {
                                            haptic.performWarning()
                                            pendingDeletePasskey = passkey
                                            showDeleteConfirmDialog = true
                                        },
                                        onSwipeRight = {
                                            haptic.performSuccess()
                                            val updatedSelection = if (selectedPasskeys.contains(passkeyKey)) {
                                                selectedPasskeys - passkeyKey
                                            } else {
                                                selectedPasskeys + passkeyKey
                                            }
                                            selectedPasskeys = updatedSelection
                                            selectionMode = updatedSelection.isNotEmpty()
                                        },
                                        isSwiped = isPendingDelete,
                                        // 【方案 A】默认锁定滑动；长按激活后 3 秒内可滑；多选模式保留滑动可用
                                        enabled = selectionMode || armState.armed,
                                        armed = armState.armed && !selectionMode,
                                        modifier = Modifier
                                    ) {
                                        PasskeyListItem(
                                            passkey = passkey,
                                            boundPassword = boundPassword,
                                            currentCategoryName = categoryName,
                                            isCategoryLocked = boundPassword != null || passkey.syncStatus == "REFERENCE",
                                            iconCardsEnabled = appSettings.iconCardsEnabled && appSettings.passkeyPageIconEnabled,
                                            unmatchedIconHandlingStrategy = appSettings.unmatchedIconHandlingStrategy,
                                            isSelected = isSelected,
                                            selectionMode = selectionMode,
                                            onClick = {
                                                passkey.managementRecordIdOrNull()?.let { recordId ->
                                                    if (recordId > 0L) {
                                                        onPasskeyClick(passkey)
                                                    }
                                                }
                                            },
                                            onToggleSelect = {
                                                val updatedSelection = if (selectedPasskeys.contains(passkeyKey)) {
                                                    selectedPasskeys - passkeyKey
                                                } else {
                                                    selectedPasskeys + passkeyKey
                                                }
                                                selectedPasskeys = updatedSelection
                                                selectionMode = updatedSelection.isNotEmpty()
                                            },
                                            onLongPress = {
                                                // 【方案 A】长按同时激活滑动删除（3 秒窗口）+ 保留进多选逻辑
                                                armState.arm()
                                                haptic.performLongPress()
                                                if (!selectionMode) {
                                                    selectionMode = true
                                                    selectedPasskeys = setOf(passkeyKey)
                                                }
                                            },
                                            onDeleteRequest = {
                                                pendingDeletePasskey = passkey
                                                showDeleteConfirmDialog = true
                                            },
                                            onBindPassword = { passkeyToBind = passkey },
                                            onUnbindPassword = {
                                                if (boundPassword != null && passwordViewModel != null) {
                                                    val updatedBindings = PasskeyBindingCodec.removeBinding(boundPassword.passkeyBindings, passkey.credentialId)
                                                    passwordViewModel.updatePasskeyBindings(boundPassword.id, updatedBindings)
                                                }
                                                passkey.managementRecordIdOrNull()?.let { recordId ->
                                                    viewModel.updateBoundPassword(recordId, null)
                                                }
                                            },
                                            onOpenBoundPassword = { passwordId -> onNavigateToPasswordDetail(passwordId) },
                                            onChangeCategory = if (boundPassword != null || passkey.syncStatus == "REFERENCE") {
                                                null
                                            } else {
                                                { passkeyToMoveCategory = passkey }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (selectionMode) {
                PasskeySelectionActionBar(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 16.dp, bottom = 20.dp),
                    selectedCount = selectedPasskeys.size,
                    onExit = {
                        selectionMode = false
                        selectedPasskeys = emptySet()
                    },
                    onSelectAll = {
                        val allKeys = categoryFilteredPasskeys.mapTo(linkedSetOf()) { it.managementKey() }
                        val updatedSelection = if (selectedPasskeys.size == allKeys.size) {
                            emptySet()
                        } else {
                            allKeys
                        }
                        selectedPasskeys = updatedSelection
                        selectionMode = updatedSelection.isNotEmpty()
                    },
                    onMoveToCategory = {
                        showBatchMoveCategoryDialog = true
                    },
                    onDelete = {
                        pendingDeletePasskey = null
                        showDeleteConfirmDialog = true
                    }
                )
            }
        }
    }

    if (showDeleteConfirmDialog) {
        val deleteTargets = if (pendingDeletePasskey != null) {
            listOf(pendingDeletePasskey!!)
        } else {
            combinedPasskeys.filter { selectedPasskeys.contains(it.managementKey()) }
        }

        val biometricAction = if (activity != null && canUseBiometric) {
            {
                biometricHelper.authenticate(
                    activity = activity,
                    title = context.getString(R.string.verify_identity),
                    subtitle = context.getString(R.string.verify_to_delete),
                    onSuccess = { performDeleteTargets(deleteTargets) },
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
            title = if (pendingDeletePasskey != null) {
                stringResource(R.string.passkey_delete_title)
            } else {
                stringResource(R.string.delete_item_title, stringResource(R.string.passkey_title))
            },
            message = if (pendingDeletePasskey != null) {
                val passkey = pendingDeletePasskey!!
                stringResource(
                    R.string.passkey_delete_message,
                    passkey.rpName.ifBlank { passkey.rpId },
                    passkey.userDisplayName.ifBlank { passkey.userName }
                )
            } else {
                stringResource(
                    R.string.delete_item_message,
                    stringResource(R.string.passkey_title),
                    stringResource(R.string.selected_items, deleteTargets.size)
                )
            },
            passwordValue = deletePasswordInput,
            onPasswordChange = {
                deletePasswordInput = it
                deletePasswordError = false
            },
            onDismiss = {
                showDeleteConfirmDialog = false
                pendingDeletePasskey = null
                deletePasswordInput = ""
                deletePasswordError = false
            },
            onConfirm = {
                if (com.bastion.app.security.SecurityManager(context).verifyMasterPassword(deletePasswordInput)) {
                    performDeleteTargets(deleteTargets)
                } else {
                    deletePasswordError = true
                }
            },
            confirmText = stringResource(R.string.delete),
            destructiveConfirm = true,
            isPasswordError = deletePasswordError,
            passwordErrorText = stringResource(R.string.current_password_incorrect),
            onBiometricClick = biometricAction,
            biometricHintText = if (biometricAction == null) {
                stringResource(R.string.biometric_not_available)
            } else {
                null
            }
        )
    }

    PasswordEntryPickerBottomSheet(
        visible = passkeyToBind != null,
        title = stringResource(R.string.select_password_to_bind),
        passwords = passwords.filter { !it.isDeleted && !it.isArchived },
        selectedEntryId = passkeyToBind?.boundPasswordId,
        onDismiss = { passkeyToBind = null },
        onSelect = { password ->
            val passkey = passkeyToBind ?: return@PasswordEntryPickerBottomSheet
            val previousPasswordId = passkey.boundPasswordId
            scope.launch {
                val nonMigratableForBitwarden = password.bitwardenVaultId != null &&
                    !withContext(Dispatchers.IO) { isPasskeyMigratableToBitwarden(context, passkey) }

                if (passwordViewModel != null) {
                    val newBinding = PasskeyBinding(
                        credentialId = passkey.credentialId,
                        rpId = passkey.rpId,
                        rpName = passkey.rpName,
                        userName = passkey.userName,
                        userDisplayName = passkey.userDisplayName
                    )

                    val targetEntry = passwordMap[password.id]
                    if (targetEntry != null) {
                        val updatedBindings = PasskeyBindingCodec.addBinding(targetEntry.passkeyBindings, newBinding)
                        passwordViewModel.updatePasskeyBindings(password.id, updatedBindings)
                    }

                    if (previousPasswordId != null && previousPasswordId != password.id) {
                        val previousEntry = passwordMap[previousPasswordId]
                        if (previousEntry != null) {
                            val updatedBindings = PasskeyBindingCodec.removeBinding(previousEntry.passkeyBindings, passkey.credentialId)
                            passwordViewModel.updatePasskeyBindings(previousPasswordId, updatedBindings)
                        }
                    }
                }

                val passkeyRecordId = passkey.managementRecordIdOrNull()
                if (passkey.syncStatus == "REFERENCE") {
                    passkeyRecordId?.let { recordId ->
                        viewModel.updateBoundPassword(recordId, password.id)
                    }
                } else {
                    val targetVaultId = when {
                        password.bitwardenVaultId != null && !nonMigratableForBitwarden -> password.bitwardenVaultId
                        password.bitwardenVaultId != null && nonMigratableForBitwarden -> passkey.bitwardenVaultId
                        else -> null
                    }
                    val targetFolderId = when {
                        password.bitwardenVaultId != null && !nonMigratableForBitwarden -> password.bitwardenFolderId
                        password.bitwardenVaultId != null && nonMigratableForBitwarden -> passkey.bitwardenFolderId
                        else -> null
                    }
                    val currentVaultId = passkey.bitwardenVaultId
                    val currentCipherId = passkey.bitwardenCipherId
                    val isLeavingCurrentCipher =
                        currentVaultId != null &&
                            !currentCipherId.isNullOrBlank() &&
                            currentVaultId != targetVaultId

                    if (isLeavingCurrentCipher) {
                        val queueResult = bitwardenRepository.queueCipherDelete(
                            vaultId = currentVaultId!!,
                            cipherId = currentCipherId!!,
                            itemType = BitwardenPendingOperation.ITEM_TYPE_PASSKEY
                        )
                        if (queueResult.isFailure) {
                            Toast.makeText(context, "操作失败", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                    }

                    val keepExistingCipher =
                        currentVaultId != null &&
                            !currentCipherId.isNullOrBlank() &&
                            currentVaultId == targetVaultId

                    val resolvedSyncStatus = when {
                        targetVaultId == null -> "NONE"
                        keepExistingCipher -> if (passkey.syncStatus == "SYNCED") "SYNCED" else "PENDING"
                        else -> "PENDING"
                    }
                    val resolvedMode = when {
                        targetVaultId != null -> PasskeyEntry.MODE_BW_COMPAT
                        password.keepassDatabaseId != null -> PasskeyEntry.MODE_KEEPASS_COMPAT
                        passkey.isKeePassCompatible() -> PasskeyEntry.MODE_KEEPASS_COMPAT
                        else -> passkey.passkeyMode
                    }

                    val updateResult = viewModel.updatePasskey(
                        passkey.copy(
                            boundPasswordId = password.id,
                            categoryId = password.categoryId,
                            keepassDatabaseId = password.keepassDatabaseId,
                            keepassGroupPath = password.keepassGroupPath,
                            bitwardenVaultId = targetVaultId,
                            bitwardenFolderId = targetFolderId,
                            bitwardenCipherId = if (keepExistingCipher) currentCipherId else null,
                            syncStatus = resolvedSyncStatus,
                            passkeyMode = resolvedMode
                        )
                    )
                    if (updateResult.isFailure) {
                        Toast.makeText(context, context.getString(R.string.passkey_update_failed), Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                }
                if (nonMigratableForBitwarden) {
                    Toast.makeText(context, context.getString(R.string.passkey_bitwarden_legacy_hint), Toast.LENGTH_SHORT).show()
                }
                passkeyToBind = null
            }
        }
    )

    UnifiedMoveToCategoryBottomSheet(
        visible = passkeyToMoveCategory != null,
        onDismiss = { passkeyToMoveCategory = null },
        categories = categories,
        keepassDatabases = keepassDatabases,
        bitwardenVaults = bitwardenVaults,
        getBitwardenFolders = { vaultId -> database.bitwardenFolderDao().getFoldersByVaultFlow(vaultId) },
        getKeePassGroups = getKeePassGroups,
        allowCopy = true,
        allowMove = true,
        onTargetSelected = { target, action ->
            val passkey = passkeyToMoveCategory ?: return@UnifiedMoveToCategoryBottomSheet
            scope.launch {
                val effectiveAction = action
                if (effectiveAction == UnifiedMoveAction.COPY && action == UnifiedMoveAction.COPY) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.passkey_copy_uses_move_hint),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                val persistResult = persistStorageTarget(passkey, target)
                if (persistResult.isFailure) {
                    Toast.makeText(
                        context,
                        context.getString(passkeyMoveFailureMessageRes(persistResult.exceptionOrNull())),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }

                val targetLabel = when (target) {
                    UnifiedMoveCategoryTarget.Uncategorized -> context.getString(R.string.category_none)
                    is UnifiedMoveCategoryTarget.BastionCategory -> categoryMap[target.categoryId]?.name ?: context.getString(R.string.category_none)
                    is UnifiedMoveCategoryTarget.BitwardenVaultTarget -> context.getString(R.string.filter_bitwarden)
                    is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> context.getString(R.string.filter_bitwarden)
                    is UnifiedMoveCategoryTarget.KeePassDatabaseTarget -> keepassDatabases.find { it.id == target.databaseId }?.name ?: context.getString(R.string.filter_keepass)
                    is UnifiedMoveCategoryTarget.KeePassGroupTarget -> decodeKeePassPathForDisplay(target.groupPath)
                }
                Toast.makeText(context, context.getString(R.string.passkey_category_updated, targetLabel), Toast.LENGTH_SHORT).show()
                passkeyToMoveCategory = null
            }
        }
    )

    UnifiedMoveToCategoryBottomSheet(
        visible = showBatchMoveCategoryDialog,
        onDismiss = { showBatchMoveCategoryDialog = false },
        categories = categories,
        keepassDatabases = keepassDatabases,
        bitwardenVaults = bitwardenVaults,
        getBitwardenFolders = { vaultId -> database.bitwardenFolderDao().getFoldersByVaultFlow(vaultId) },
        getKeePassGroups = getKeePassGroups,
        allowCopy = true,
        allowMove = true,
        onTargetSelected = { target, action ->
            scope.launch {
                val selectedItems = combinedPasskeys.filter { selectedPasskeys.contains(it.managementKey()) }
                val movable = selectedItems.filter { it.boundPasswordId == null && it.syncStatus != "REFERENCE" }
                val lockedCount = selectedItems.size - movable.size
                var movedCount = 0
                var failedCount = 0
                var blockedCount = 0
                var keepassConflictCount = 0

                val effectiveAction = action

                if (effectiveAction == UnifiedMoveAction.COPY && action == UnifiedMoveAction.COPY) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.passkey_copy_uses_move_hint),
                        Toast.LENGTH_SHORT
                    ).show()
                }

                movable.forEach { passkey ->
                    val persistResult = persistStorageTarget(passkey, target)
                    if (persistResult.isSuccess) {
                        movedCount++
                    } else {
                        when (persistResult.exceptionOrNull()) {
                            is PasskeyBitwardenMoveBlockedException -> blockedCount++
                            is KeePassPasskeyCredentialConflictException -> keepassConflictCount++
                            else -> failedCount++
                        }
                    }
                }

                val baseMessage = context.getString(R.string.selected_items, movedCount)
                val skippedTotal = lockedCount + failedCount + blockedCount + keepassConflictCount
                val toastMessage = if (skippedTotal > 0) "$baseMessage，跳过$skippedTotal" else baseMessage
                Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
                if (blockedCount > 0) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.passkey_bitwarden_move_blocked_count, blockedCount),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                if (keepassConflictCount > 0) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.passkey_keepass_credential_conflict_count, keepassConflictCount),
                        Toast.LENGTH_SHORT
                    ).show()
                }

                showBatchMoveCategoryDialog = false
                selectionMode = false
                selectedPasskeys = emptySet()
            }
        }
    )

    CategoryManagementCreateDialog(
        state = categoryMgmt,
        currentFilter = selectedCategoryFilter,
        categories = categories,
        keepassDatabases = keepassDatabases,
        bitwardenVaults = bitwardenVaults,
        getKeePassGroups = getKeePassGroups,
        passwordViewModel = passwordViewModel!!,
        bitwardenRepository = bitwardenRepository,
        keepassBridge = keepassBridge,
        scope = scope
    )
}

private fun encodePasskeyCategoryFilter(filter: UnifiedCategoryFilterSelection): SavedCategoryFilterState = when (filter) {
    UnifiedCategoryFilterSelection.All -> SavedCategoryFilterState(type = "all")
    UnifiedCategoryFilterSelection.Local -> SavedCategoryFilterState(type = "local")
    UnifiedCategoryFilterSelection.Starred -> SavedCategoryFilterState(type = "starred")
    UnifiedCategoryFilterSelection.Uncategorized -> SavedCategoryFilterState(type = "uncategorized")
    UnifiedCategoryFilterSelection.LocalStarred -> SavedCategoryFilterState(type = "local_starred")
    UnifiedCategoryFilterSelection.LocalUncategorized -> SavedCategoryFilterState(type = "local_uncategorized")
    is UnifiedCategoryFilterSelection.Custom -> SavedCategoryFilterState(type = "custom", primaryId = filter.categoryId)
    is UnifiedCategoryFilterSelection.BitwardenVaultFilter -> SavedCategoryFilterState(type = "bitwarden_vault", primaryId = filter.vaultId)
    is UnifiedCategoryFilterSelection.BitwardenFolderFilter -> SavedCategoryFilterState(type = "bitwarden_folder", primaryId = filter.vaultId, text = filter.folderId)
    is UnifiedCategoryFilterSelection.BitwardenVaultStarredFilter -> SavedCategoryFilterState(type = "bitwarden_vault_starred", primaryId = filter.vaultId)
    is UnifiedCategoryFilterSelection.BitwardenVaultUncategorizedFilter -> SavedCategoryFilterState(type = "bitwarden_vault_uncategorized", primaryId = filter.vaultId)
    is UnifiedCategoryFilterSelection.KeePassDatabaseFilter -> SavedCategoryFilterState(type = "keepass_database", primaryId = filter.databaseId)
    is UnifiedCategoryFilterSelection.KeePassGroupFilter -> SavedCategoryFilterState(type = "keepass_group", primaryId = filter.databaseId, text = filter.groupPath)
    is UnifiedCategoryFilterSelection.KeePassDatabaseStarredFilter -> SavedCategoryFilterState(type = "keepass_database_starred", primaryId = filter.databaseId)
    is UnifiedCategoryFilterSelection.KeePassDatabaseUncategorizedFilter -> SavedCategoryFilterState(type = "keepass_database_uncategorized", primaryId = filter.databaseId)
}

private fun decodePasskeyCategoryFilter(state: SavedCategoryFilterState): UnifiedCategoryFilterSelection {
    return when (state.type) {
        "all" -> UnifiedCategoryFilterSelection.All
        "local" -> UnifiedCategoryFilterSelection.Local
        "starred" -> UnifiedCategoryFilterSelection.Starred
        "uncategorized" -> UnifiedCategoryFilterSelection.Uncategorized
        "local_starred" -> UnifiedCategoryFilterSelection.LocalStarred
        "local_uncategorized" -> UnifiedCategoryFilterSelection.LocalUncategorized
        "custom" -> state.primaryId?.let { UnifiedCategoryFilterSelection.Custom(it) } ?: UnifiedCategoryFilterSelection.All
        "bitwarden_vault" -> state.primaryId?.let { UnifiedCategoryFilterSelection.BitwardenVaultFilter(it) } ?: UnifiedCategoryFilterSelection.All
        "bitwarden_folder" -> {
            val vaultId = state.primaryId
            val folderId = state.text
            if (vaultId != null && !folderId.isNullOrBlank()) {
                UnifiedCategoryFilterSelection.BitwardenFolderFilter(vaultId, folderId)
            } else {
                UnifiedCategoryFilterSelection.All
            }
        }
        "bitwarden_vault_starred" -> state.primaryId?.let { UnifiedCategoryFilterSelection.BitwardenVaultStarredFilter(it) } ?: UnifiedCategoryFilterSelection.All
        "bitwarden_vault_uncategorized" -> state.primaryId?.let { UnifiedCategoryFilterSelection.BitwardenVaultUncategorizedFilter(it) } ?: UnifiedCategoryFilterSelection.All
        "keepass_database" -> state.primaryId?.let { UnifiedCategoryFilterSelection.KeePassDatabaseFilter(it) } ?: UnifiedCategoryFilterSelection.All
        "keepass_group" -> {
            val databaseId = state.primaryId
            val groupPath = state.text
            if (databaseId != null && !groupPath.isNullOrBlank()) {
                UnifiedCategoryFilterSelection.KeePassGroupFilter(databaseId, groupPath)
            } else {
                UnifiedCategoryFilterSelection.All
            }
        }
        "keepass_database_starred" -> state.primaryId?.let { UnifiedCategoryFilterSelection.KeePassDatabaseStarredFilter(it) } ?: UnifiedCategoryFilterSelection.All
        "keepass_database_uncategorized" -> state.primaryId?.let { UnifiedCategoryFilterSelection.KeePassDatabaseUncategorizedFilter(it) } ?: UnifiedCategoryFilterSelection.All
        else -> UnifiedCategoryFilterSelection.All
    }
}

private fun formatPasswordSummary(entry: PasswordEntry): String {
    val parts = listOf(entry.title, entry.username, entry.website).filter { it.isNotBlank() }
    return if (parts.isEmpty()) entry.title else parts.joinToString(" · ")
}

/**
 * 版本警告横幅
 */
@Composable
private fun VersionWarningBanner(
    androidVersion: String,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(24.dp)
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.passkey_version_warning_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = stringResource(R.string.passkey_version_warning_message, androidVersion),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
            
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.dismiss),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

/**
 * Passkey 分组标题
 */
@Composable
private fun PasskeyGroupHeader(
    rpId: String,
    rpName: String,
    count: Int
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = rpName.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rpName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = rpId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    text = count.toString(),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

/**
 * Passkey 列表项（与密码列表风格完全一致 - M3 Expressive 设计）
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun PasskeyListItem(
    passkey: PasskeyEntry,
    boundPassword: PasswordEntry?,
    currentCategoryName: String,
    modifier: Modifier = Modifier,
    isCategoryLocked: Boolean = false,
    iconCardsEnabled: Boolean = false,
    unmatchedIconHandlingStrategy: UnmatchedIconHandlingStrategy = UnmatchedIconHandlingStrategy.DEFAULT_ICON,
    isSelected: Boolean = false,
    selectionMode: Boolean = false,
    onClick: () -> Unit,
    onToggleSelect: () -> Unit = {},
    onLongPress: () -> Unit = {},
    onDeleteRequest: () -> Unit = {},
    onBindPassword: () -> Unit = {},
    onUnbindPassword: () -> Unit = {},
    onOpenBoundPassword: (Long) -> Unit = {},
    onChangeCategory: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val syncStatus = remember(passkey.syncStatus) { SyncStatus.fromDbValue(passkey.syncStatus) }
    var canMoveToBitwarden by remember(
        passkey.credentialId,
        passkey.privateKeyAlias,
        passkey.syncStatus,
        passkey.passkeyMode
    ) {
        mutableStateOf<Boolean?>(null)
    }
    LaunchedEffect(
        expanded,
        passkey.credentialId,
        passkey.privateKeyAlias,
        passkey.syncStatus,
        passkey.passkeyMode
    ) {
        if (expanded) {
            canMoveToBitwarden = withContext(Dispatchers.IO) {
                isPasskeyMigratableToBitwarden(context, passkey)
            }
        } else {
            canMoveToBitwarden = null
        }
    }
    LaunchedEffect(selectionMode) {
        if (selectionMode && expanded) {
            expanded = false
        }
    }
    val rpWebsite = remember(passkey.rpId) {
        val rpId = passkey.rpId.trim()
        when {
            rpId.isBlank() -> ""
            "://" in rpId -> rpId
            else -> "https://$rpId"
        }
    }
    val useBoundPasswordIconSource = boundPassword != null
    val passkeyIconWebsite = remember(passkey.iconUrl, rpWebsite) {
        when {
            rpWebsite.isNotBlank() -> rpWebsite
            !passkey.iconUrl.isNullOrBlank() -> passkey.iconUrl.trim()
            else -> ""
        }
    }
    val passkeyIconTitle = remember(passkey.rpName, passkey.userDisplayName, passkey.userName) {
        passkey.rpName.ifBlank { passkey.userDisplayName.ifBlank { passkey.userName } }
    }
    val boundWebsite = boundPassword?.website?.trim().orEmpty()
    val boundTitle = boundPassword?.title.orEmpty()
    val iconWebsite = if (useBoundPasswordIconSource) boundWebsite else passkeyIconWebsite
    val iconTitle = if (useBoundPasswordIconSource) boundTitle else passkeyIconTitle
    val iconAppPackage = if (useBoundPasswordIconSource) {
        boundPassword?.appPackageName?.takeIf { it.isNotBlank() }
    } else {
        null
    }
    val boundCustomIconType = boundPassword?.customIconType ?: com.bastion.app.ui.icons.PASSWORD_ICON_TYPE_NONE
    val boundSimpleIcon = if (iconCardsEnabled && boundCustomIconType == com.bastion.app.ui.icons.PASSWORD_ICON_TYPE_SIMPLE) {
        com.bastion.app.ui.icons.rememberSimpleIconBitmap(
            slug = boundPassword?.customIconValue,
            tintColor = MaterialTheme.colorScheme.primary,
            enabled = true
        )
    } else {
        null
    }
    val boundUploadedIcon = if (iconCardsEnabled && boundCustomIconType == com.bastion.app.ui.icons.PASSWORD_ICON_TYPE_UPLOADED) {
        com.bastion.app.ui.icons.rememberUploadedPasswordIcon(boundPassword?.customIconValue)
    } else {
        null
    }
    val autoMatchedSimpleIcon = rememberAutoMatchedSimpleIcon(
        website = iconWebsite,
        title = iconTitle,
        appPackageName = iconAppPackage,
        tintColor = MaterialTheme.colorScheme.primary,
        enabled = iconCardsEnabled && boundCustomIconType == com.bastion.app.ui.icons.PASSWORD_ICON_TYPE_NONE
    )
    val favicon = if (iconWebsite.isNotBlank()) {
        rememberFavicon(
            url = iconWebsite,
            enabled = iconCardsEnabled && autoMatchedSimpleIcon.resolved && autoMatchedSimpleIcon.slug == null
        )
    } else {
        null
    }
    val appIcon = if (iconCardsEnabled && !iconAppPackage.isNullOrBlank()) {
        rememberAppIcon(iconAppPackage)
    } else {
        null
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (selectionMode && isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        }
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            if (selectionMode) {
                                onToggleSelect()
                            } else {
                                onClick()
                            }
                        },
                        onLongClick = {
                            if (!selectionMode) {
                                onLongPress()
                            }
                        }
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧：头像 + 标题区域
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Passkey 图标（无背景直出）
                    var iconSlotVisible = true
                    when {
                        iconCardsEnabled && boundSimpleIcon != null -> {
                            Image(
                                bitmap = boundSimpleIcon,
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .size(40.dp)
                            )
                        }
                        iconCardsEnabled && boundUploadedIcon != null -> {
                            Image(
                                bitmap = boundUploadedIcon,
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .size(40.dp)
                            )
                        }
                        iconCardsEnabled && autoMatchedSimpleIcon.bitmap != null -> {
                            Image(
                                bitmap = autoMatchedSimpleIcon.bitmap,
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .size(40.dp)
                            )
                        }
                        iconCardsEnabled && favicon != null -> {
                            Image(
                                bitmap = favicon,
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .size(40.dp)
                            )
                        }
                        iconCardsEnabled && appIcon != null -> {
                            Image(
                                bitmap = appIcon,
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .size(40.dp)
                            )
                        }
                        else -> {
                            if (shouldShowFallbackSlot(unmatchedIconHandlingStrategy)) {
                                UnmatchedIconFallback(
                                    strategy = unmatchedIconHandlingStrategy,
                                    primaryText = iconWebsite,
                                    secondaryText = iconTitle,
                                    defaultIcon = Icons.Default.Key,
                                    iconSize = 40.dp
                                )
                            } else {
                                iconSlotVisible = false
                            }
                        }
                    }

                    if (iconSlotVisible) {
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = passkey.displayTitle(),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = passkey.rpName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (passkey.isKeePassCompatible()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            PasskeyFormatBadge(text = stringResource(R.string.passkey_format_keepass))
                        }
                    }
                }

                if (selectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelect() }
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SyncStatusIcon(
                            status = syncStatus.takeIf { passkey.bitwardenVaultId != null || it == SyncStatus.FAILED },
                            size = 16.dp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.ArrowForward,
                            contentDescription = stringResource(R.string.passkey_view_details),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            // 展开内容
            SafeAnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                    )
                    
                    // 域名
                    DetailRow(
                        label = stringResource(R.string.passkey_rp_id),
                        value = passkey.rpId,
                        icon = Icons.Default.Language
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // 用户名
                    DetailRow(
                        label = stringResource(R.string.passkey_username),
                        value = passkey.userName,
                        icon = Icons.Default.Person
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // 创建时间
                    DetailRow(
                        label = stringResource(R.string.passkey_created_at),
                        value = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", LocalConfiguration.current.locales[0])
                            .format(java.util.Date(passkey.createdAt)),
                        icon = Icons.Default.Schedule
                    )
                    
                    if (passkey.lastUsedAt > passkey.createdAt) {
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // 最后使用时间
                        DetailRow(
                            label = stringResource(R.string.passkey_last_used),
                            value = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", LocalConfiguration.current.locales[0])
                                .format(java.util.Date(passkey.lastUsedAt)),
                            icon = Icons.Default.History
                        )
                    }
                    
                    if (passkey.useCount > 0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // 使用次数
                        DetailRow(
                            label = stringResource(R.string.passkey_use_count),
                            value = passkey.useCount.toString(),
                            icon = Icons.Default.Numbers
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    SyncStatusBadge(
                        status = syncStatus.takeIf { passkey.bitwardenVaultId != null || it == SyncStatus.FAILED },
                        showLabel = true
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    DetailRow(
                        label = stringResource(R.string.category),
                        value = currentCategoryName,
                        icon = Icons.Default.Folder
                    )

                    if (passkey.isKeePassCompatible()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        PasskeyFormatBadge(text = stringResource(R.string.passkey_format_keepass))
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.passkey_format_keepass_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (isCategoryLocked) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.passkey_category_follow_binding),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (expanded && passkey.syncStatus != "REFERENCE" && canMoveToBitwarden == false) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.passkey_bitwarden_legacy_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    if (onChangeCategory != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onChangeCategory,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.move_to_category))
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 绑定密码
                    if (boundPassword != null) {
                        DetailRow(
                            label = stringResource(R.string.bind_password),
                            value = formatPasswordSummary(boundPassword),
                            icon = Icons.Default.Lock
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { onOpenBoundPassword(boundPassword.id) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.details))
                            }
                            OutlinedButton(
                                onClick = onBindPassword,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.bound_password_change))
                            }
                            OutlinedButton(
                                onClick = onUnbindPassword,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Text(stringResource(R.string.unbind))
                            }
                        }
                    } else {
                        OutlinedButton(
                            onClick = onBindPassword,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.bind_password))
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // 删除按钮
                    OutlinedButton(
                        onClick = onDeleteRequest,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                        )
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.passkey_delete_button))
                    }
                }
            }
        }
    }
}

/**
 * Passkey 多选胶囊操作栏（左下角）
 */
@Composable
private fun PasskeySelectionActionBar(
    modifier: Modifier = Modifier,
    selectedCount: Int,
    onExit: () -> Unit,
    onSelectAll: () -> Unit,
    onMoveToCategory: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Text(
                    text = selectedCount.toString(),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            PasskeyActionIcon(
                icon = Icons.Default.CheckCircle,
                contentDescription = stringResource(id = R.string.select_all),
                onClick = onSelectAll
            )

            PasskeyActionIcon(
                icon = Icons.Default.Folder,
                contentDescription = stringResource(id = R.string.move_to_category),
                onClick = onMoveToCategory
            )

            PasskeyActionIcon(
                icon = Icons.Default.Delete,
                contentDescription = stringResource(id = R.string.delete),
                onClick = onDelete
            )

            Spacer(modifier = Modifier.width(4.dp))

            PasskeyActionIcon(
                icon = Icons.Default.Close,
                contentDescription = stringResource(id = R.string.close),
                onClick = onExit
            )
        }
    }
}

@Composable
private fun PasskeyActionIcon(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(40.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 详情行组件
 */
@Composable
private fun DetailRow(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun PasskeyFormatBadge(
    text: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun SafeAnimatedVisibility(
    visible: Boolean,
    enter: EnterTransition,
    exit: ExitTransition,
    content: @Composable () -> Unit
) {
    if (Build.VERSION.SDK_INT >= 36) {
        if (visible) content()
        return
    }
    AnimatedVisibility(
        visible = visible,
        enter = enter,
        exit = exit
    ) { content() }
}

private class PasskeyBitwardenMoveBlockedException :
    IllegalStateException("Passkey cannot be migrated to Bitwarden")

private fun passkeyMoveFailureMessageRes(error: Throwable?): Int {
    return when (error) {
        is PasskeyBitwardenMoveBlockedException -> R.string.passkey_bitwarden_move_blocked
        is KeePassPasskeyCredentialConflictException -> R.string.passkey_keepass_credential_conflict
        else -> R.string.passkey_bitwarden_move_failed
    }
}

private fun isPasskeyMigratableToBitwarden(context: Context, passkey: PasskeyEntry): Boolean {
    if (passkey.passkeyMode != PasskeyEntry.MODE_BW_COMPAT) return false
    if (passkey.syncStatus == "REFERENCE") return false
    return PasskeyPrivateKeyStore.hasBitwardenCompatiblePrivateKey(context, passkey.privateKeyAlias)
}

private fun PasskeyEntry.passkeyReferenceDedupKey(): String {
    val normalizedCredentialId = PasskeyCredentialIdCodec.normalize(credentialId)
        ?: credentialId.trim()
    return buildString {
        append(normalizedCredentialId)
        append('|')
        append(rpId.trim().lowercase())
        append('|')
        append(userName.trim().lowercase())
        append('|')
        append(userDisplayName.trim().lowercase())
    }
}

