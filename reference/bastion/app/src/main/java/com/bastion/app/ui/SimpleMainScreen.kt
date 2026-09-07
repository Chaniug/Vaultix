@file:Suppress("LocalContextGetResourceValueCall")
package com.bastion.app.ui

import com.bastion.app.logging.runCatchingObserved
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
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.fragment.app.FragmentActivity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.bastion.app.R
import com.bastion.app.data.AddButtonBehaviorMode
import com.bastion.app.data.AppSettings
import com.bastion.app.data.AddButtonMenuAction
import com.bastion.app.data.BottomNavContentTab
import com.bastion.app.data.PasskeyEntry
import com.bastion.app.data.PasswordPageContentType
import com.bastion.app.data.PasswordQuickAccessManager
import com.bastion.app.data.model.PasskeyBindingCodec
import com.bastion.app.data.model.TimelineEvent
import com.bastion.app.passkey.managementKey
import com.bastion.app.utils.BiometricHelper
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
import com.bastion.app.viewmodel.BillingAddressViewModel
import com.bastion.app.ui.screens.SettingsScreen
import com.bastion.app.ui.screens.GeneratorScreen  // 添加生成器页面导入
import com.bastion.app.ui.screens.NoteListScreen
import com.bastion.app.ui.screens.NoteListContent
import com.bastion.app.ui.screens.PasswordDetailScreen
import com.bastion.app.ui.screens.SendScreen
import com.bastion.app.ui.screens.CardWalletScreen
import com.bastion.app.ui.screens.CardWalletTab
import com.bastion.app.ui.screens.BankCardDetailScreen
import com.bastion.app.ui.screens.BillingAddressDetailScreen
import com.bastion.app.ui.screens.DocumentDetailScreen
import com.bastion.app.ui.screens.HistoryTab
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
import com.bastion.app.ui.components.PasswordQuickAccessItem
import com.bastion.app.ui.components.rankFrequentPasswordQuickAccessItems
import com.bastion.app.ui.components.rankRecentPasswordQuickAccessItems
import com.bastion.app.ui.components.CardWalletAddTypeChip
import com.bastion.app.ui.components.UnifiedCategoryFilterBottomSheet
import com.bastion.app.ui.components.UnifiedCategoryFilterSelection
import com.bastion.app.ui.components.UnifiedMoveCategoryTarget
import com.bastion.app.ui.components.UnifiedMoveToCategoryBottomSheet
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
import com.bastion.app.ui.password.buildAdditionalInfoPreview
import com.bastion.app.ui.password.MultiPasswordEntryCard
import com.bastion.app.ui.password.StackedPasswordGroup
import com.bastion.app.ui.password.PasswordEntryCard
import com.bastion.app.ui.password.StackCardMode
import com.bastion.app.ui.password.resolvePasswordPageVisibleTypes
import com.bastion.app.ui.password.sanitizeSelectedPasswordPageTypes
import com.bastion.app.ui.password.PasswordListAggregateConfig
import com.bastion.app.ui.password.getGroupKeyForMode
import com.bastion.app.ui.password.getPasswordGroupTitle
import com.bastion.app.ui.password.getPasswordInfoKey
import com.bastion.app.ui.vaultv2.VaultV2Pane
import com.bastion.app.ui.vaultv2.VaultV2PaneState
import com.bastion.app.ui.vaultv2.VaultV2RetainedStateViewModel
import com.bastion.app.ui.vaultv2.rememberVaultV2PaneState
import com.bastion.app.data.bitwarden.BitwardenPendingOperation
import com.bastion.app.data.bitwarden.BitwardenSend
import com.bastion.app.bitwarden.sync.SyncBlockReason
import com.bastion.app.bitwarden.sync.buildMiniHintDetail
import com.bastion.app.bitwarden.sync.buildMiniHintTitle
import com.bastion.app.bitwarden.sync.buildDetailLine
import com.bastion.app.bitwarden.sync.buildHeadline
import com.bastion.app.bitwarden.sync.SyncStatus
import com.bastion.app.bitwarden.sync.VaultSyncStatus
import com.bastion.app.security.SecurityManager
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import com.bastion.app.ui.screens.AddEditPasswordScreen
import com.bastion.app.ui.screens.AddEditTotpScreen
import com.bastion.app.ui.screens.AddEditBankCardScreen
import com.bastion.app.ui.screens.AddEditBillingAddressScreen
import com.bastion.app.ui.screens.AddEditDocumentScreen
import com.bastion.app.ui.screens.AddEditNoteScreen
import com.bastion.app.ui.screens.AddEditSendScreen
import com.bastion.app.ui.theme.BastionTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Keep this file as orchestration layer: state wiring + tab routing + pane transitions.
// Feature-specific rendering should stay in dedicated composables/files.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnifiedWalletAddScreen(
    selectedType: CardWalletTab,
    onTypeSelected: (CardWalletTab) -> Unit,
    onNavigateBack: () -> Unit,
    bankCardViewModel: BankCardViewModel,
    documentViewModel: DocumentViewModel,
    billingAddressViewModel: BillingAddressViewModel,
    stateHolder: androidx.compose.runtime.saveable.SaveableStateHolder,
    modifier: Modifier = Modifier,
    initialCategoryId: Long? = null,
    initialKeePassDatabaseId: Long? = null,
    initialKeePassGroupPath: String? = null,
    initialBitwardenVaultId: Long? = null,
    initialBitwardenFolderId: String? = null
) {
    val context = LocalContext.current
    var isFavorite by remember { mutableStateOf(false) }
    var canSave by remember { mutableStateOf(false) }
    var onSaveAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var onToggleFavoriteAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val titleRes = when (selectedType) {
        CardWalletTab.DOCUMENTS -> R.string.item_type_document
        CardWalletTab.BILLING_ADDRESSES -> R.string.billing_address
        else -> R.string.item_type_bank_card
    }
    val topBarTitle = stringResource(titleRes)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                    title = { Text(topBarTitle) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = stringResource(R.string.back)
                            )
                        }
                    },
                    actions = {
                        CardWalletAddTypeChip(
                            current = selectedType,
                            onSelect = onTypeSelected
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(
                            onClick = {
                                onToggleFavoriteAction?.invoke()
                            },
                            enabled = onToggleFavoriteAction != null
                        ) {
                            Icon(
                                imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = stringResource(R.string.favorite),
                                tint = if (isFavorite) MaterialTheme.colorScheme.primary else LocalContentColor.current
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { onSaveAction?.invoke() },
                containerColor = if (canSave) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (canSave) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            ) {
                Icon(Icons.Default.Check, contentDescription = stringResource(R.string.save))
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                if (selectedType == CardWalletTab.BILLING_ADDRESSES) {
                    stateHolder.SaveableStateProvider("wallet_add_billing_address") {
                        AddEditBillingAddressScreen(
                            viewModel = billingAddressViewModel,
                            addressId = null,
                            onNavigateBack = onNavigateBack,
                            initialCategoryId = initialCategoryId,
                            showTopBar = false,
                            showFab = false,
                            onFavoriteStateChanged = { isFavorite = it },
                            onCanSaveChanged = { canSave = it },
                            onSaveActionChanged = { onSaveAction = it },
                            onToggleFavoriteActionChanged = { onToggleFavoriteAction = it },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else if (selectedType == CardWalletTab.DOCUMENTS) {
                    stateHolder.SaveableStateProvider("wallet_add_document") {
                        AddEditDocumentScreen(
                            viewModel = documentViewModel,
                            documentId = null,
                            onNavigateBack = onNavigateBack,
                            initialCategoryId = initialCategoryId,
                            initialKeePassDatabaseId = initialKeePassDatabaseId,
                            initialKeePassGroupPath = initialKeePassGroupPath,
                            initialBitwardenVaultId = initialBitwardenVaultId,
                            initialBitwardenFolderId = initialBitwardenFolderId,
                            showTopBar = false,
                            showFab = false,
                            onFavoriteStateChanged = { isFavorite = it },
                            onCanSaveChanged = { canSave = it },
                            onSaveActionChanged = { onSaveAction = it },
                            onToggleFavoriteActionChanged = { onToggleFavoriteAction = it },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    stateHolder.SaveableStateProvider("wallet_add_bank") {
                        AddEditBankCardScreen(
                            viewModel = bankCardViewModel,
                            cardId = null,
                            onNavigateBack = onNavigateBack,
                            initialCategoryId = initialCategoryId,
                            initialKeePassDatabaseId = initialKeePassDatabaseId,
                            initialKeePassGroupPath = initialKeePassGroupPath,
                            initialBitwardenVaultId = initialBitwardenVaultId,
                            initialBitwardenFolderId = initialBitwardenFolderId,
                            showTopBar = false,
                            showFab = false,
                            onFavoriteStateChanged = { isFavorite = it },
                            onCanSaveChanged = { canSave = it },
                            onSaveActionChanged = { onSaveAction = it },
                            onToggleFavoriteActionChanged = { onToggleFavoriteAction = it },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

private data class BitwardenBottomStatusUiState(
    val messageRes: Int? = null,
    val showProgress: Boolean = false
)

private data class BottomMiniHintMessage(
    val id: Long,
    val title: String,
    val supportingText: String? = null
)

private data class MainScreenHandlers(
    val passwordAddOpen: () -> Unit,
    val passwordEditOpen: (Long) -> Unit,
    val inlinePasswordEditorBack: () -> Unit,
    val totpAddOpen: () -> Unit,
    val inlineTotpEditorBack: () -> Unit,
    val bankCardAddOpen: () -> Unit,
    val bankCardEditOpen: (Long) -> Unit,
    val inlineBankCardEditorBack: () -> Unit,
    val documentAddOpen: () -> Unit,
    val documentEditOpen: (Long) -> Unit,
    val inlineDocumentEditorBack: () -> Unit,
    val billingAddressOpen: (Long) -> Unit,
    val billingAddressAddOpen: () -> Unit,
    val billingAddressEditOpen: (Long) -> Unit,
    val inlineBillingAddressEditorBack: () -> Unit,
    val walletAddOpen: () -> Unit,
    val noteOpen: (Long?) -> Unit,
    val inlineNoteEditorBack: () -> Unit,
    val passwordDetailOpen: (Long) -> Unit,
    val totpOpen: (Long) -> Unit,
    val bankCardOpen: (Long) -> Unit,
    val documentOpen: (Long) -> Unit,
    val passkeyOpen: (PasskeyEntry) -> Unit,
    val passkeyUnbind: (PasskeyEntry) -> Unit,
    val confirmPasskeyDelete: () -> Unit,
    val sendOpen: (BitwardenSend) -> Unit,
    val sendAddOpen: () -> Unit,
    val inlineSendEditorBack: () -> Unit,
    val timelineLogOpen: (TimelineEvent.StandardLog) -> Unit
)

// Keep this file as orchestration layer: state wiring + tab routing + pane transitions.
// Feature-specific rendering should stay in dedicated composables/files.
private const val MAX_BOTTOM_MINI_HINTS = 2

@Composable
private fun BottomMiniHintBubble(
    visible: Boolean,
    title: String,
    supportingText: String? = null,
    containerColor: Color,
    textColor: Color
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            initialOffsetY = { it / 3 },
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        ) + fadeIn(animationSpec = tween(180)) + scaleIn(
            initialScale = 0.92f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium
            )
        ),
        exit = slideOutVertically(
            targetOffsetY = { it / 3 },
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium
            )
        ) + fadeOut(animationSpec = tween(160)) + scaleOut(
            targetScale = 0.96f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium
            )
        )
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 260.dp),
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 2.dp,
            color = containerColor
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(if (supportingText != null) 2.dp else 0.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!supportingText.isNullOrBlank()) {
                    Text(
                        text = supportingText,
                        style = MaterialTheme.typography.bodySmall,
                        color = textColor.copy(alpha = 0.9f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private fun buildBitwardenSyncMiniHint(
    context: Context,
    summary: com.bastion.app.bitwarden.sync.BitwardenSyncSummary
): BottomMiniHintMessage {
    return BottomMiniHintMessage(
        id = System.currentTimeMillis(),
        title = summary.buildMiniHintTitle(context),
        supportingText = summary.buildMiniHintDetail(context)
    )
}

@Composable
private fun SyncMiniHintBubble(
    visible: Boolean,
    text: String,
    containerColor: Color,
    textColor: Color
) {
    BottomMiniHintBubble(
        visible = visible,
        title = text,
        containerColor = containerColor,
        textColor = textColor
    )
}

@Composable
private fun CustomMiniHintBubble(
    visible: Boolean,
    hint: BottomMiniHintMessage,
    containerColor: Color,
    textColor: Color
) {
    BottomMiniHintBubble(
        visible = visible,
        title = hint.title,
        supportingText = hint.supportingText,
        containerColor = containerColor,
        textColor = textColor
    )
}

private fun isBitwardenPasswordFilter(filter: CategoryFilter): Boolean = when (filter) {
    is CategoryFilter.BitwardenVault,
    is CategoryFilter.BitwardenFolderFilter,
    is CategoryFilter.BitwardenVaultStarred,
    is CategoryFilter.BitwardenVaultUncategorized -> true
    else -> false
}

private fun isBitwardenTotpFilter(filter: com.bastion.app.viewmodel.TotpCategoryFilter): Boolean = when (filter) {
    is com.bastion.app.viewmodel.TotpCategoryFilter.BitwardenVault,
    is com.bastion.app.viewmodel.TotpCategoryFilter.BitwardenFolderFilter,
    is com.bastion.app.viewmodel.TotpCategoryFilter.BitwardenVaultStarred,
    is com.bastion.app.viewmodel.TotpCategoryFilter.BitwardenVaultUncategorized -> true
    else -> false
}

private fun resolveTrashScopeKeyFromPasswordFilter(filter: CategoryFilter): String {
    return when (filter) {
        is CategoryFilter.BitwardenVault -> "bitwarden_${filter.vaultId}"
        is CategoryFilter.BitwardenFolderFilter -> "bitwarden_${filter.vaultId}"
        is CategoryFilter.BitwardenVaultStarred -> "bitwarden_${filter.vaultId}"
        is CategoryFilter.BitwardenVaultUncategorized -> "bitwarden_${filter.vaultId}"
        is CategoryFilter.KeePassDatabase -> "keepass_${filter.databaseId}"
        is CategoryFilter.KeePassGroupFilter -> "keepass_${filter.databaseId}"
        is CategoryFilter.KeePassDatabaseStarred -> "keepass_${filter.databaseId}"
        is CategoryFilter.KeePassDatabaseUncategorized -> "keepass_${filter.databaseId}"
        else -> "local"
    }
}

private fun resolveBitwardenBottomStatusUiState(
    status: VaultSyncStatus?,
    nowMs: Long
): BitwardenBottomStatusUiState? {
    if (status == null) return null

    if (status.blockedReason == SyncBlockReason.AUTH_REQUIRED) {
        return BitwardenBottomStatusUiState(
            messageRes = R.string.bitwarden_status_auth_required
        )
    }
    if (status.blockedReason == SyncBlockReason.VAULT_LOCKED) {
        return BitwardenBottomStatusUiState(
            messageRes = R.string.bitwarden_status_wait_unlock
        )
    }
    if (status.nextRetryAt != null) {
        return BitwardenBottomStatusUiState(
            messageRes = R.string.bitwarden_status_retrying
        )
    }
    if (status.lastError != null) {
        return BitwardenBottomStatusUiState(
            messageRes = R.string.sync_status_failed_short
        )
    }
    if (status.isRunning) {
        return BitwardenBottomStatusUiState(
            messageRes = R.string.sync_status_syncing_short,
            showProgress = true
        )
    }
    if (status.queuedReason != null) {
        return BitwardenBottomStatusUiState(
            messageRes = R.string.bitwarden_status_queued,
            showProgress = true
        )
    }
    val lastSuccess = status.lastSuccessAt
    if (lastSuccess != null && nowMs - lastSuccess in 0..3000L) {
        return BitwardenBottomStatusUiState(
            messageRes = R.string.bitwarden_status_synced
        )
    }

    return null
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun TimelineDetailPane(
    selectedLog: TimelineEvent.StandardLog,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.history),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = selectedLog.summary,
            style = MaterialTheme.typography.titleMedium
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("类型：${selectedLog.itemType.ifBlank { "-" }}")
                Text("操作：${selectedLog.operationType.ifBlank { "-" }}")
                Text(
                    text = "时间戳：${selectedLog.timestamp}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 底栏单个 tab 项（悬浮胶囊内的图标 + 文字）。
 *
 * 注意：本函数必须是【顶层】 composable，不能像此前那样定义在 bottomBar
 * lambda 内部再经 forEach 调用——嵌套函数在每次重组时会被重新定义，导致
 * 内部 remember / animateColorAsState 的组合身份不稳定，点击一个 tab 触发
 * 整行重组时动画状态会重启，表现为「点一个、旁边那个 pill 也跟着闪」。
 * 同时调用处必须用 key(item.key) 包裹，保证每个 tab 有稳定的组合槽。
 */
@Composable
private fun BottomNavTabItem(
    item: BottomNavItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val label = stringResource(item.shortLabelRes())
    // 选中色块包住「图标+文字」整体（酷安样式），圆角矩形非细长条
    // 选中色块颜色：直接取值，不使用颜色渐变动画。
    // 原因：此前用 animateColorAsState(tween(200))，点击新 tab 时「新 pill 渐现」与
    // 「旧 pill 渐隐」两个动画并行 200ms，期间两个 pill 同时处于中间色，
    // 用户感知就是「点一个、旁边那个也跟着闪」。
    // 且下方 contentTint 本来就是瞬间切换，背景若走渐变会与文字色不同步、更显抖动。
    // 改为瞬切后，选中态与文字色严格同步，切换干净利落。
    val pillColor = if (isSelected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        Color.Transparent
    }
    val contentTint = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 56.dp)
                .height(48.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .background(pillColor),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = label,
                tint = contentTint,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = contentTint
            )
        }
    }
}

/**
 * 带有底部导航的主屏幕
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3WindowSizeClassApi::class,
    androidx.compose.animation.ExperimentalSharedTransitionApi::class
)
@Composable
fun SimpleMainScreen(
    passwordViewModel: PasswordViewModel,
    settingsViewModel: SettingsViewModel,
    totpViewModel: com.bastion.app.viewmodel.TotpViewModel,
    bankCardViewModel: com.bastion.app.viewmodel.BankCardViewModel,
    documentViewModel: com.bastion.app.viewmodel.DocumentViewModel,
    billingAddressViewModel: BillingAddressViewModel,
    generatorViewModel: GeneratorViewModel = viewModel(), // 添加GeneratorViewModel
    noteViewModel: NoteViewModel = viewModel(),
    // Activity 级共享实例：默认的 viewModel() 会按 NavBackStackEntry 各建一个，
    // 导致 BitwardenViewModel 出现多个副本（详见 activityViewModel 文档）。
    bitwardenViewModel: com.bastion.app.bitwarden.viewmodel.BitwardenViewModel = activityViewModel(),
    passkeyViewModel: PasskeyViewModel,  // Passkey ViewModel
    localKeePassViewModel: com.bastion.app.viewmodel.LocalKeePassViewModel,
    securityManager: SecurityManager,
    onNavigateToAddPassword: (Long?) -> Unit,
    onNavigateToAddWifi: (Long?) -> Unit = {},
    onNavigateToAddSshKey: (Long?) -> Unit = {},
    onNavigateToAddTotp: (Long?) -> Unit,
    onNavigateToQuickTotpScan: () -> Unit,
    pendingPasswordAuthenticatorQrResult: String? = null,
    onConsumePendingPasswordAuthenticatorQrResult: () -> Unit = {},
    onScanPasswordAuthenticatorQrCode: () -> Unit = {},
    onNavigateToFidoQrScan: () -> Unit,
    onNavigateToAddBankCard: (Long?) -> Unit,
    onNavigateToAddDocument: (Long?) -> Unit,
    onNavigateToAddBillingAddress: (Long?) -> Unit,
    onNavigateToWalletAdd: (CardWalletTab) -> Unit,
    onPreparePasswordAddStorageDefaults: (Long?, Long?, String?,  Long?, String?) -> Unit = { _, _, _, _, _ -> },
    onPrepareTotpAddStorageDefaults: (Long?, Long?, String?,  Long?, String?) -> Unit = { _, _, _, _, _ -> },
    onPrepareNoteAddStorageDefaults: (Long?, Long?, String?,  Long?, String?) -> Unit = { _, _, _, _, _ -> },
    onPrepareWalletAddStorageDefaults: (Long?, Long?, String?,  Long?, String?) -> Unit = { _, _, _, _, _ -> },
    onNavigateToAddNote: (Long?) -> Unit,
    onNavigateToSearchedNote: (Long, String) -> Unit = { noteId, _ -> onNavigateToAddNote(noteId) },
    onNavigateToNoteDetail: (Long) -> Unit = {},
    onNavigateToPasswordDetail: (Long) -> Unit = {},
    onNavigateToPasskeyDetail: (Long) -> Unit,
    onNavigateToBankCardDetail: (Long) -> Unit, // Add this
    onNavigateToDocumentDetail: (Long) -> Unit, // Keep this
    onNavigateToBillingAddressDetail: (Long) -> Unit,
    onNavigateToChangePassword: () -> Unit = {},
    onNavigateToSecurityQuestion: () -> Unit = {},
    onNavigateToMasterPasswordLocking: () -> Unit = {},
    onNavigateToSyncBackup: () -> Unit = {},
    onNavigateToAutofill: () -> Unit = {},
    onNavigateToPasskeySettings: () -> Unit = {},
    onSecurityAnalysis: () -> Unit = {},
    onNavigateToDeveloperSettings: () -> Unit = {},
    onNavigateToPermissionManagement: () -> Unit = {},
    onNavigateToExtensions: () -> Unit = {},
    onNavigateToCommonAccountTemplates: () -> Unit = {},
    onNavigateToPageCustomization: () -> Unit = {},
    onNavigateToThemeAndColorScheme: () -> Unit = {},
    onNavigateToStandaloneSettings: () -> Unit = {},
    onNavigateToBitwardenLogin: () -> Unit = {},
    onNavigateToAddSend: () -> Unit = {},
    onManageKeePassDatabase: (Long) -> Unit = {},
    onClearAllData: (Boolean, Boolean, Boolean, Boolean, Boolean, Boolean) -> Unit,
    initialTab: Int = 0
) {

    // --- ViewModel wiring and global app-level state ---
    val timelineViewModel: TimelineViewModel = viewModel()
    
    // 双击返回退出相关状态
    var backPressedOnce by remember { mutableStateOf(false) }
    var passwordHistoryPageMode by rememberSaveable { mutableStateOf(PasswordHistoryPageMode.NONE) }
    var passwordHistoryInitialTrashScopeKey by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val bitwardenRepository = remember { com.bastion.app.bitwarden.repository.BitwardenRepository.getInstance(context) }
    val appSettings by settingsViewModel.settings.collectAsState()
    val passwordPageVisibleContentTypes = remember(
        appSettings.passwordPageAggregateEnabled,
        appSettings.passwordPageVisibleContentTypes
    ) {
        resolvePasswordPageVisibleTypes(
            aggregateEnabled = appSettings.passwordPageAggregateEnabled,
            configuredTypes = appSettings.passwordPageVisibleContentTypes
        )
    }
    var passwordPageSelectedContentTypes by rememberSaveable(
        stateSaver = passwordPageContentTypeSetSaver
    ) {
        mutableStateOf(emptySet())
    }
    
    // --- Global back behavior ---
    // 处理返回键 - 需要按两次才能退出
    // 只有在没有子页面（如添加页面）打开时才启用
    // FAB 展开状态由内部 SwipeableAddFab 管理，这里不需要干预，除非我们需要在 FAB 展开时拦截返回键
    // 目前 SwipeableAddFab 应该自己处理了返回键（如果有 BackHandler）
    // 为了安全起见，我们只在最外层处理
    BackHandler(enabled = true) {
        if (passwordHistoryPageMode.isVisible) {
            passwordHistoryPageMode = PasswordHistoryPageMode.NONE
            passwordHistoryInitialTrashScopeKey = null
            return@BackHandler
        }
        if (backPressedOnce) {
            // 第二次按返回键,退出应用
            (context as? android.app.Activity)?.finish()
        } else {
            // 第一次按返回键,显示提示
            backPressedOnce = true
            Toast.makeText(
                context,
                context.getString(R.string.press_back_again_to_exit),
                Toast.LENGTH_SHORT
            ).show()
            
            // 2秒后重置状态
            scope.launch {
                delay(2000)
                backPressedOnce = false
            }
        }
    }
    
    // --- Cross-tab selection/action-bar state ---
    // 密码列表的选择模式状态
    var isPasswordSelectionMode by remember { mutableStateOf(false) }
    var selectedPasswordCount by remember { mutableIntStateOf(0) }
    var onExitPasswordSelection by remember { mutableStateOf({}) }
    var onSelectAllPasswords by remember { mutableStateOf({}) }
    var onFavoriteSelectedPasswords by remember { mutableStateOf<(() -> Unit)?>(null) }
    var onMoveToCategoryPasswords by remember { mutableStateOf<(() -> Unit)?>(null) }
    var onManualStackPasswords by remember { mutableStateOf<(() -> Unit)?>(null) }
    var onDeleteSelectedPasswords by remember { mutableStateOf({}) }
    var passwordListShowBackToTop by remember { mutableStateOf(false) }
    var passwordScrollToTopRequestKey by remember { mutableIntStateOf(0) }
    var showPasswordQuickAccessSheet by rememberSaveable { mutableStateOf(false) }
    val retainedStateViewModel: VaultV2RetainedStateViewModel = viewModel()
    val vaultV2PaneState = rememberVaultV2PaneState(retainedStateViewModel.retainedState)
    val isPasswordVaultAuthenticated by passwordViewModel.isAuthenticated.collectAsState()

    LaunchedEffect(isPasswordVaultAuthenticated) {
        if (!isPasswordVaultAuthenticated) {
            vaultV2PaneState.clearRetainedListSnapshots()
        }
    }
    
    LaunchedEffect(passwordPageVisibleContentTypes) {
        passwordPageSelectedContentTypes = sanitizeSelectedPasswordPageTypes(
            visibleTypes = passwordPageVisibleContentTypes,
            selectedTypes = passwordPageSelectedContentTypes
        )
    }
    
    // 密码分组模式: smart(备注>网站>应用>标题), note, website, app, title
    // 从设置中读取，如果设置中没有则默认为 "smart"
    val passwordGroupMode = appSettings.passwordGroupMode


    // 堆叠卡片显示模式: 自动/始终展开（始终展开指逐条显示，不堆叠）
    // 从设置中读取，如果设置中没有则默认为 AUTO
    val stackCardModeKey = appSettings.stackCardMode
    val stackCardMode = remember(stackCardModeKey) {
        runCatchingObserved { StackCardMode.valueOf(stackCardModeKey) }.getOrDefault(StackCardMode.AUTO)
    }
    
    // TOTP/证件/银行卡三组跨 tab 选择模式桥接状态：原 19 个 var 注册下沉为
    // CrossTabSelectionState 容器（拆分计划批 5，减少主函数内联 Compose 指令）
    val totpSelectionState = rememberCrossTabSelectionState()
    val documentSelectionState = rememberCrossTabSelectionState()
    val bankCardSelectionState = rememberCrossTabSelectionState()

    // CardWallet state
    var cardWalletSubTab by rememberSaveable { mutableStateOf(CardWalletTab.ALL) }
    var cardWalletBitwardenVaultId by rememberSaveable { mutableStateOf<Long?>(null) }
    var walletUnifiedAddType by rememberSaveable { mutableStateOf(CardWalletTab.BANK_CARDS) }
    val walletAddSaveableStateHolder = rememberSaveableStateHolder()
    val cardWalletSaveableStateHolder = rememberSaveableStateHolder()

    val bottomNavVisibility = appSettings.bottomNavVisibility

    // 用 remember 缓存派生结果，避免每次重组都产生新的 List 实例。
    // 此前 dataTabItems / tabs 都是无 remember 的链式调用，每次重组都是新对象，
    // 导致下游 LaunchedEffect(tabs) 反复取消并重启协程，加重重组负担、
    // 底栏动画掉帧显得抖动。这里以真正影响结果的稳定输入作为 key。
    val dataTabItems = remember(appSettings.bottomNavOrder, bottomNavVisibility) {
        val combinedAuthenticatorVisible =
            bottomNavVisibility.authenticator || bottomNavVisibility.passkey
        appSettings.bottomNavOrder
            .map { tab ->
                if (tab == BottomNavContentTab.PASSKEY) BottomNavContentTab.AUTHENTICATOR else tab
            }
            .distinct()
            .filter { tab ->
                if (tab == BottomNavContentTab.AUTHENTICATOR) {
                    combinedAuthenticatorVisible
                } else {
                    bottomNavVisibility.isVisible(tab)
                }
            }
            .map { it.toBottomNavItem() }
    }
    val shouldHideBottomNavigation = appSettings.autoHideBottomNavWhenSingleTab && dataTabItems.size == 1

    // 底栏自定义槽位固定 3 个（左2 + 右1），最右侧固定「设置」。
    // 否则自定义 tab 过多时 Settings 排在末尾会被挤出 5 槽布局，完全不可见。
    val tabs = remember(dataTabItems, shouldHideBottomNavigation) {
        buildList {
            addAll(dataTabItems.take(3))
            if (!shouldHideBottomNavigation) {
                add(BottomNavItem.Settings)
            }
        }
    }

    val defaultTabKey = remember(initialTab, tabs) { 
        if (initialTab == 0 && tabs.isNotEmpty()) {
            tabs.first().key
        } else {
            indexToDefaultTabKey(initialTab) 
        }
    }
    var selectedTabKey by rememberSaveable { mutableStateOf(defaultTabKey) }
    var startupAutoTabApplied by rememberSaveable { mutableStateOf(false) }
    val hasCustomBottomNavConfig = remember(appSettings.bottomNavOrder, bottomNavVisibility) {
        appSettings.bottomNavOrder != BottomNavContentTab.DEFAULT_ORDER ||
            bottomNavVisibility != com.bastion.app.data.BottomNavVisibility()
    }

    LaunchedEffect(tabs) {
        val passkeyHostedByAuthenticator =
            selectedTabKey == BottomNavItem.Passkey.key &&
                tabs.any { it == BottomNavItem.Authenticator }
        if (!passkeyHostedByAuthenticator && tabs.none { it.key == selectedTabKey }) {
            selectedTabKey = tabs.first().key
        }
    }
    LaunchedEffect(initialTab, tabs, hasCustomBottomNavConfig, startupAutoTabApplied) {
        if (
            initialTab == 0 &&
            !startupAutoTabApplied &&
            hasCustomBottomNavConfig &&
            tabs.isNotEmpty()
        ) {
            selectedTabKey = tabs.first().key
            startupAutoTabApplied = true
        }
    }

    val currentTab = if (
        selectedTabKey == BottomNavItem.Passkey.key &&
        tabs.any { it == BottomNavItem.Authenticator }
    ) {
        BottomNavItem.Passkey
    } else {
        tabs.firstOrNull { it.key == selectedTabKey } ?: tabs.first()
    }
    val selectedDockTab = if (currentTab == BottomNavItem.Passkey) {
        BottomNavItem.Authenticator
    } else {
        currentTab
    }

    BackHandler(enabled = currentTab == BottomNavItem.Passkey) {
        selectedTabKey = BottomNavItem.Authenticator.key
    }

    LaunchedEffect(currentTab.key) {
        if (currentTab != BottomNavItem.CardWallet) {
            cardWalletBitwardenVaultId = null
        }
    }

    var passwordPaneState by rememberSaveable(stateSaver = PasswordPaneUiStateSaver) {
        mutableStateOf(PasswordPaneUiState())
    }
    var totpPaneState by rememberSaveable(stateSaver = TotpPaneUiStateSaver) {
        mutableStateOf(TotpPaneUiState())
    }
    var cardWalletPaneState by rememberSaveable(stateSaver = CardWalletPaneUiStateSaver) {
        mutableStateOf(CardWalletPaneUiState())
    }
    var notePaneState by rememberSaveable(stateSaver = NotePaneUiStateSaver) {
        mutableStateOf(NotePaneUiState())
    }
    var sendPaneState by remember { mutableStateOf(SendPaneUiState()) }
    var selectedPasskey by remember { mutableStateOf<PasskeyEntry?>(null) }
    var pendingPasskeyDelete by remember { mutableStateOf<PasskeyEntry?>(null) }
    var selectedTimelineLog by remember { mutableStateOf<TimelineEvent.StandardLog?>(null) }
    val sendState by bitwardenViewModel.sendState.collectAsState()
    val activeBitwardenVault by bitwardenViewModel.activeVault.collectAsState()
    val bitwardenSyncStatusByVault by bitwardenViewModel.syncStatusByVault.collectAsState()
    val totpFilter by totpViewModel.categoryFilter.collectAsState()
    val totpNewItemDefaults = remember(totpFilter) { defaultsFromTotpFilter(totpFilter) }
    var pendingInlinePasswordAddStorageDefaults by remember { mutableStateOf<NewItemStorageDefaults?>(null) }
    var pendingInlineTotpAddStorageDefaults by remember { mutableStateOf<NewItemStorageDefaults?>(null) }
    var pendingInlineNoteAddStorageDefaults by remember { mutableStateOf<NewItemStorageDefaults?>(null) }
    var pendingInlineWalletAddStorageDefaults by remember { mutableStateOf<NewItemStorageDefaults?>(null) }
    val selectedGeneratorType by generatorViewModel.selectedGenerator.collectAsState()
    val symbolGeneratorResult by generatorViewModel.symbolResult.collectAsState()
    val passwordGeneratorResult by generatorViewModel.passwordResult.collectAsState()
    val passphraseGeneratorResult by generatorViewModel.passphraseResult.collectAsState()
    val pinGeneratorResult by generatorViewModel.pinResult.collectAsState()
    val sshKeyGeneratorResult by generatorViewModel.sshKeyResult.collectAsState()
    val currentGeneratorResult = when (selectedGeneratorType) {
        GeneratorType.SYMBOL -> symbolGeneratorResult
        GeneratorType.PASSWORD -> passwordGeneratorResult
        GeneratorType.PASSPHRASE -> passphraseGeneratorResult
        GeneratorType.PIN -> pinGeneratorResult
        GeneratorType.SSH_KEY -> sshKeyGeneratorResult?.fingerprintSha256.orEmpty()
    }

    val selectedPasswordId = passwordPaneState.selectedPasswordId
    val inlinePasswordEditorId = passwordPaneState.inlinePasswordEditorId
    val isAddingPasswordInline = passwordPaneState.isAddingPasswordInline
    val selectedTotpId = totpPaneState.selectedTotpId
    val isAddingTotpInline = totpPaneState.isAddingInline
    val selectedBankCardId = cardWalletPaneState.selectedBankCardId
    val inlineBankCardEditorId = cardWalletPaneState.inlineBankCardEditorId
    val isAddingBankCardInline = cardWalletPaneState.isAddingBankCardInline
    val selectedDocumentId = cardWalletPaneState.selectedDocumentId
    val inlineDocumentEditorId = cardWalletPaneState.inlineDocumentEditorId
    val isAddingDocumentInline = cardWalletPaneState.isAddingDocumentInline
    val selectedBillingAddressId = cardWalletPaneState.selectedBillingAddressId
    val inlineBillingAddressEditorId = cardWalletPaneState.inlineBillingAddressEditorId
    val isAddingBillingAddressInline = cardWalletPaneState.isAddingBillingAddressInline
    val inlineNoteEditorId = notePaneState.inlineNoteEditorId
    val isAddingNoteInline = notePaneState.isAddingInline
    val selectedSend = sendPaneState.selectedSend
    val isAddingSendInline = sendPaneState.isAddingInline
    val resetPasswordPaneState: () -> Unit = {
        pendingInlinePasswordAddStorageDefaults = null
        passwordPaneState = PasswordPaneUiStateTransitions.reset()
    }
    val openInlinePasswordAdd: () -> Unit = {
        passwordPaneState = PasswordPaneUiStateTransitions.openInlineAdd()
    }
    val openInlinePasswordEditor: (Long) -> Unit = { passwordId ->
        passwordPaneState = PasswordPaneUiStateTransitions.openInlineEditor(passwordId)
    }
    val openInlinePasswordDetail: (Long) -> Unit = { passwordId ->
        passwordPaneState = PasswordPaneUiStateTransitions.openDetail(passwordId)
    }
    val closeInlinePasswordEditor: () -> Unit = {
        pendingInlinePasswordAddStorageDefaults = null
        passwordPaneState = PasswordPaneUiStateTransitions.closeInlineEditor(passwordPaneState)
    }
    val clearSelectedPasswordPaneItem: () -> Unit = {
        passwordPaneState = PasswordPaneUiStateTransitions.clearSelected(passwordPaneState)
    }
    val resetTotpPaneState: () -> Unit = {
        pendingInlineTotpAddStorageDefaults = null
        totpPaneState = TotpPaneUiStateTransitions.reset()
    }
    val openInlineTotpAdd: () -> Unit = {
        totpPaneState = TotpPaneUiStateTransitions.openInlineAdd()
    }
    val openInlineTotpDetail: (Long) -> Unit = { totpId ->
        totpPaneState = TotpPaneUiStateTransitions.openDetail(totpId)
    }
    val resetCardWalletPaneState: () -> Unit = {
        pendingInlineWalletAddStorageDefaults = null
        cardWalletPaneState = CardWalletPaneUiStateTransitions.resetAll()
    }
    val openInlineBankCardAdd: () -> Unit = {
        cardWalletPaneState = CardWalletPaneUiStateTransitions.openBankCardAddInline()
    }
    val openInlineBankCardEditor: (Long) -> Unit = { cardId ->
        cardWalletPaneState = CardWalletPaneUiStateTransitions.openBankCardEditInline(cardId)
    }
    val openInlineBankCardDetail: (Long) -> Unit = { cardId ->
        cardWalletPaneState = CardWalletPaneUiStateTransitions.openBankCardDetail(cardId)
    }
    val closeInlineBankCardEditor: () -> Unit = {
        pendingInlineWalletAddStorageDefaults = null
        cardWalletPaneState = CardWalletPaneUiStateTransitions.closeBankCardEditor(cardWalletPaneState)
    }
    val clearSelectedBankCardPaneItem: () -> Unit = {
        cardWalletPaneState = CardWalletPaneUiStateTransitions.clearSelectedBankCard(cardWalletPaneState)
    }
    val openInlineDocumentAdd: () -> Unit = {
        cardWalletPaneState = CardWalletPaneUiStateTransitions.openDocumentAddInline()
    }
    val openInlineDocumentEditor: (Long) -> Unit = { documentId ->
        cardWalletPaneState = CardWalletPaneUiStateTransitions.openDocumentEditInline(documentId)
    }
    val openInlineDocumentDetail: (Long) -> Unit = { documentId ->
        cardWalletPaneState = CardWalletPaneUiStateTransitions.openDocumentDetail(documentId)
    }
    val closeInlineDocumentEditor: () -> Unit = {
        pendingInlineWalletAddStorageDefaults = null
        cardWalletPaneState = CardWalletPaneUiStateTransitions.closeDocumentEditor(cardWalletPaneState)
    }
    val clearSelectedDocumentPaneItem: () -> Unit = {
        cardWalletPaneState = CardWalletPaneUiStateTransitions.clearSelectedDocument(cardWalletPaneState)
    }
    val openInlineBillingAddressAdd: () -> Unit = {
        cardWalletPaneState = CardWalletPaneUiStateTransitions.openBillingAddressAddInline()
    }
    val openInlineBillingAddressEditor: (Long) -> Unit = { addressId ->
        cardWalletPaneState = CardWalletPaneUiStateTransitions.openBillingAddressEditInline(addressId)
    }
    val openInlineBillingAddressDetail: (Long) -> Unit = { addressId ->
        cardWalletPaneState = CardWalletPaneUiStateTransitions.openBillingAddressDetail(addressId)
    }
    val closeInlineBillingAddressEditor: () -> Unit = {
        pendingInlineWalletAddStorageDefaults = null
        cardWalletPaneState = CardWalletPaneUiStateTransitions.closeBillingAddressEditor(cardWalletPaneState)
    }
    val clearSelectedBillingAddressPaneItem: () -> Unit = {
        cardWalletPaneState = CardWalletPaneUiStateTransitions.clearSelectedBillingAddress(cardWalletPaneState)
    }
    val resetDocumentPaneState: () -> Unit = {
        pendingInlineWalletAddStorageDefaults = null
        cardWalletPaneState = CardWalletPaneUiStateTransitions.resetDocumentPane(cardWalletPaneState)
    }
    val resetBankCardPaneState: () -> Unit = {
        pendingInlineWalletAddStorageDefaults = null
        cardWalletPaneState = CardWalletPaneUiStateTransitions.resetBankCardPane(cardWalletPaneState)
    }
    val resetBillingAddressPaneState: () -> Unit = {
        pendingInlineWalletAddStorageDefaults = null
        cardWalletPaneState = CardWalletPaneUiStateTransitions.resetBillingAddressPane(cardWalletPaneState)
    }
    val resetNotePaneState: () -> Unit = {
        pendingInlineNoteAddStorageDefaults = null
        notePaneState = NotePaneUiStateTransitions.reset()
    }
    val openInlineNoteEditor: (Long?) -> Unit = { noteId ->
        notePaneState = NotePaneUiStateTransitions.openInlineEditor(noteId)
    }
    val resetSendPaneState: () -> Unit = {
        sendPaneState = SendPaneUiStateTransitions.reset()
    }
    val openInlineSendDetail: (BitwardenSend) -> Unit = { send ->
        sendPaneState = SendPaneUiStateTransitions.openDetail(send)
    }
    val openInlineSendAdd: () -> Unit = {
        sendPaneState = SendPaneUiStateTransitions.openInlineAdd()
    }
    val closeInlineSendEditor: () -> Unit = {
        sendPaneState = SendPaneUiStateTransitions.closeInlineEditor(sendPaneState)
    }

    // 监听 FAB 展开状态，展开时禁用隐藏逻辑
    var isFabExpanded by remember { mutableStateOf(false) }
    var isFastScrollStripVisible by rememberSaveable(currentTab) { mutableStateOf(false) }
    

    // 检测是否有任何选择模式处于激活状态
    var isNoteSelectionMode by remember { mutableStateOf(false) }
    val isAnySelectionMode =
        isPasswordSelectionMode ||
            totpSelectionState.isSelectionMode ||
            documentSelectionState.isSelectionMode ||
            bankCardSelectionState.isSelectionMode ||
            isNoteSelectionMode ||
            vaultV2PaneState.selectionCount > 0
    var generatorRefreshRequestKey by remember { mutableIntStateOf(0) }
    
    

    val currentFilter by passwordViewModel.categoryFilter.collectAsState()
    val passwordNewItemDefaults = remember(currentFilter) { defaultsFromPasswordFilter(currentFilter) }
    val openHistoryPage: () -> Unit = {
        passwordHistoryInitialTrashScopeKey = null
        passwordHistoryPageMode = PasswordHistoryPageMode.TIMELINE
    }
    val openTrashPage: () -> Unit = {
        passwordHistoryInitialTrashScopeKey = null
        passwordHistoryPageMode = PasswordHistoryPageMode.TRASH
    }
    val closeHistoryPage: () -> Unit = {
        passwordHistoryPageMode = PasswordHistoryPageMode.NONE
        passwordHistoryInitialTrashScopeKey = null
    }
    val isPasskeyDataNeeded = currentTab == BottomNavItem.Passkey ||
        selectedPasskey != null ||
        pendingPasskeyDelete != null
    val isQuickAccessDataNeeded = appSettings.passwordListQuickAccessEnabled || showPasswordQuickAccessSheet
    val shouldCollectAllPasswords = isQuickAccessDataNeeded || isPasskeyDataNeeded
    val allPasswords = if (shouldCollectAllPasswords) {
        passwordViewModel.allPasswordsForUi.collectAsState(initial = emptyList()).value
    } else {
        emptyList()
    }
    val passwordQuickAccessManager = remember(context) { PasswordQuickAccessManager(context) }
    val passwordQuickAccessStats = if (isQuickAccessDataNeeded) {
        passwordQuickAccessManager.statsFlow.collectAsState(initial = emptyMap()).value
    } else {
        emptyMap()
    }
    val passwordQuickAccessItems = remember(
        allPasswords,
        passwordQuickAccessStats,
        isQuickAccessDataNeeded
    ) {
        if (!isQuickAccessDataNeeded) {
            emptyList()
        } else {
            allPasswords.mapNotNull { entry ->
                val stat = passwordQuickAccessStats[entry.id] ?: return@mapNotNull null
                PasswordQuickAccessItem(
                    entry = entry,
                    openCount = stat.openCount,
                    lastOpenedAt = stat.lastOpenedAt
                )
            }
        }
    }
    val recentOpenedPasswords = remember(passwordQuickAccessItems) {
        rankRecentPasswordQuickAccessItems(passwordQuickAccessItems)
    }
    val frequentOpenedPasswords = remember(passwordQuickAccessItems) {
        rankFrequentPasswordQuickAccessItems(passwordQuickAccessItems)
    }
    val localPasskeys = if (isPasskeyDataNeeded) {
        passkeyViewModel.allPasskeys.collectAsState(initial = emptyList()).value
    } else {
        emptyList()
    }
    val passkeyTotalCount = localPasskeys.size
    val passkeyBoundCount = localPasskeys.count { it.boundPasswordId != null }
    val passwordById = remember(allPasswords, isPasskeyDataNeeded) {
        if (isPasskeyDataNeeded) {
            allPasswords.associateBy { it.id }
        } else {
            emptyMap()
        }
    }
    val keepassDatabases by localKeePassViewModel.allDatabases.collectAsState()
    val bitwardenVaults by bitwardenViewModel.vaults.collectAsState()
    // 可拖拽导航栏已弃用（+ 号合并进底栏中间，两者冲突）。
    // 强制走悬浮胶囊底栏，避免此前开启过的用户在设置项移除后无法关回。
    val useDraggableNav = false
    
    // 构建导航项列表 (用于可拖拽导航栏)
    val draggableNavItems = remember(tabs, selectedDockTab) {
        tabs.map { item ->
            DraggableNavItem(
                key = item.key,
                icon = item.icon,
                labelRes = item.shortLabelRes(),
                selected = item.key == selectedDockTab.key,
                onClick = { selectedTabKey = item.key }
            )
        }
    }
    
    val activity = LocalContext.current.findActivity()
    val widthSizeClass = activity?.let { calculateWindowSizeClass(it).widthSizeClass }
    val isCompactWidth = widthSizeClass == null || widthSizeClass == WindowWidthSizeClass.Compact
    val wideListPaneWidth = 400.dp
    val wideNavigationRailWidth = 80.dp
    val wideFabHostWidth = wideNavigationRailWidth + wideListPaneWidth

    // --- Navigation/interaction handlers hub ---
    // This function centralizes open/edit/back intents so tab/pane switching stays consistent.
    fun buildMainScreenHandlers(): MainScreenHandlers {
        val handlePasswordAddOpen: () -> Unit = {
            val resolvedDefaults = passwordNewItemDefaults.takeIf { it.hasAnyValue() }
            if (isCompactWidth) {
                pendingInlinePasswordAddStorageDefaults = null
                onPreparePasswordAddStorageDefaults(
                    resolvedDefaults?.categoryId,
                    resolvedDefaults?.keepassDatabaseId,
                    resolvedDefaults?.keepassGroupPath,

                    resolvedDefaults?.bitwardenVaultId,
                    resolvedDefaults?.bitwardenFolderId
                )
                onNavigateToAddPassword(null)
            } else {
                pendingInlinePasswordAddStorageDefaults = resolvedDefaults
                openInlinePasswordAdd()
            }
        }
        val handlePasswordEditOpen: (Long) -> Unit = { passwordId ->
            if (isCompactWidth) {
                onNavigateToAddPassword(passwordId)
            } else {
                openInlinePasswordEditor(passwordId)
            }
        }
        val handleInlinePasswordEditorBack: () -> Unit = {
            closeInlinePasswordEditor()
        }
        val handleTotpAddOpen: () -> Unit = {
            if (isCompactWidth) {
                onNavigateToAddTotp(null)
            } else {
                openInlineTotpAdd()
            }
        }
        val handleInlineTotpEditorBack: () -> Unit = {
            pendingInlineTotpAddStorageDefaults = null
            resetTotpPaneState()
        }
        val handleBankCardAddOpen: () -> Unit = {
            if (isCompactWidth) {
                onNavigateToAddBankCard(null)
            } else {
                openInlineBankCardAdd()
            }
        }
        val handleBankCardEditOpen: (Long) -> Unit = { cardId ->
            if (isCompactWidth) {
                onNavigateToAddBankCard(cardId)
            } else {
                openInlineBankCardEditor(cardId)
            }
        }
        val handleInlineBankCardEditorBack: () -> Unit = {
            pendingInlineWalletAddStorageDefaults = null
            closeInlineBankCardEditor()
        }
        val handleDocumentAddOpen: () -> Unit = {
            if (isCompactWidth) {
                onNavigateToAddDocument(null)
            } else {
                openInlineDocumentAdd()
            }
        }
        val handleDocumentEditOpen: (Long) -> Unit = { documentId ->
            if (isCompactWidth) {
                onNavigateToAddDocument(documentId)
            } else {
                openInlineDocumentEditor(documentId)
            }
        }
        val handleInlineDocumentEditorBack: () -> Unit = {
            pendingInlineWalletAddStorageDefaults = null
            closeInlineDocumentEditor()
        }
        val handleBillingAddressAddOpen: () -> Unit = {
            if (isCompactWidth) {
                onNavigateToAddBillingAddress(null)
            } else {
                openInlineBillingAddressAdd()
            }
        }
        val handleBillingAddressEditOpen: (Long) -> Unit = { addressId ->
            if (isCompactWidth) {
                onNavigateToAddBillingAddress(addressId)
            } else {
                openInlineBillingAddressEditor(addressId)
            }
        }
        val handleInlineBillingAddressEditorBack: () -> Unit = {
            pendingInlineWalletAddStorageDefaults = null
            closeInlineBillingAddressEditor()
        }
        val handleWalletAddOpen: () -> Unit = {
            when (cardWalletSubTab) {
                CardWalletTab.BANK_CARDS -> handleBankCardAddOpen()
                CardWalletTab.DOCUMENTS -> handleDocumentAddOpen()
                CardWalletTab.BILLING_ADDRESSES -> handleBillingAddressAddOpen()
                CardWalletTab.ALL -> {
                    if (isCompactWidth) {
                        onNavigateToWalletAdd(walletUnifiedAddType)
                    } else {
                        openInlineBankCardAdd()
                    }
                }
            }
        }
        val handleNoteOpen: (Long?) -> Unit = { noteId ->
            if (isCompactWidth) {
                onNavigateToAddNote(noteId)
            } else {
                openInlineNoteEditor(noteId)
            }
        }
        val handleInlineNoteEditorBack: () -> Unit = {
            pendingInlineNoteAddStorageDefaults = null
            resetNotePaneState()
        }
        val handlePasswordDetailOpen: (Long) -> Unit = { passwordId ->
            if (appSettings.passwordListQuickAccessEnabled) {
                scope.launch {
                    passwordQuickAccessManager.recordOpen(passwordId)
                }
            }
            if (isCompactWidth) {
                onNavigateToPasswordDetail(passwordId)
            } else {
                openInlinePasswordDetail(passwordId)
            }
        }
        val handleTotpOpen: (Long) -> Unit = { totpId ->
            if (isCompactWidth) {
                onNavigateToAddTotp(totpId)
            } else {
                openInlineTotpDetail(totpId)
            }
        }
        val handleBankCardOpen: (Long) -> Unit = { cardId ->
            if (isCompactWidth) {
                onNavigateToBankCardDetail(cardId)
            } else {
                openInlineBankCardDetail(cardId)
            }
        }
        val handleDocumentOpen: (Long) -> Unit = { documentId ->
            if (isCompactWidth) {
                onNavigateToDocumentDetail(documentId)
            } else {
                openInlineDocumentDetail(documentId)
            }
        }
        val handleBillingAddressOpen: (Long) -> Unit = { addressId ->
            if (isCompactWidth) {
                onNavigateToBillingAddressDetail(addressId)
            } else {
                openInlineBillingAddressDetail(addressId)
            }
        }
        val handlePasskeyOpen: (PasskeyEntry) -> Unit = { passkey ->
            if (isCompactWidth) {
                selectedTabKey = BottomNavItem.Passkey.key
            } else {
                selectedPasskey = passkey
            }
        }
        val handlePasskeyUnbind: (PasskeyEntry) -> Unit = { passkey ->
            val boundId = passkey.boundPasswordId
            if (boundId != null) {
                passwordById[boundId]?.let { entry ->
                    val updatedBindings = PasskeyBindingCodec.removeBinding(
                        entry.passkeyBindings,
                        passkey.credentialId
                    )
                    passwordViewModel.updatePasskeyBindings(boundId, updatedBindings)
                }
            }
            if (passkey.id > 0L) {
                passkeyViewModel.updateBoundPassword(passkey.id, null)
            }
            if (selectedPasskey?.let { selected ->
                    if (selected.id > 0L && passkey.id > 0L) {
                        selected.id == passkey.id
                    } else {
                        selected.managementKey() == passkey.managementKey()
                    }
                } == true
            ) {
                selectedPasskey = selectedPasskey?.copy(boundPasswordId = null)
            }
        }
        val confirmPasskeyDelete: () -> Unit = {
            val passkey = pendingPasskeyDelete
            if (passkey != null) {
                scope.launch {
                    val boundId = passkey.boundPasswordId
                    if (boundId != null) {
                        passwordById[boundId]?.let { entry ->
                            val updatedBindings = PasskeyBindingCodec.removeBinding(
                                entry.passkeyBindings,
                                passkey.credentialId
                            )
                            passwordViewModel.updatePasskeyBindings(boundId, updatedBindings)
                        }
                    }

                    val isReferenceOnly = passkey.syncStatus == "REFERENCE" &&
                        passkey.privateKeyAlias.isBlank() &&
                        passkey.publicKey.isBlank()
                    if (!isReferenceOnly) {
                        val vaultId = passkey.bitwardenVaultId
                        val cipherId = passkey.bitwardenCipherId
                        if (vaultId != null && !cipherId.isNullOrBlank()) {
                            val queueResult = bitwardenRepository.queueCipherDelete(
                                vaultId = vaultId,
                                cipherId = cipherId,
                                itemType = BitwardenPendingOperation.ITEM_TYPE_PASSKEY
                            )
                            if (queueResult.isFailure) {
                                Toast.makeText(
                                    context,
                                    "Bitwarden 删除入队失败",
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@launch
                            }
                        }
                        passkeyViewModel.deletePasskey(passkey)
                    }
                    if (selectedPasskey?.let { selected ->
                            if (selected.id > 0L && passkey.id > 0L) {
                                selected.id == passkey.id
                            } else {
                                selected.managementKey() == passkey.managementKey()
                            }
                        } == true
                    ) {
                        selectedPasskey = null
                    }
                    pendingPasskeyDelete = null
                }
            }
        }
        val handleSendOpen: (BitwardenSend) -> Unit = { send ->
            if (!isCompactWidth) {
                openInlineSendDetail(send)
            }
        }
        val handleSendAddOpen: () -> Unit = {
            if (isCompactWidth) {
                onNavigateToAddSend()
            } else {
                openInlineSendAdd()
            }
        }
        val handleInlineSendEditorBack: () -> Unit = {
            closeInlineSendEditor()
        }
        val handleTimelineLogOpen: (TimelineEvent.StandardLog) -> Unit = { log ->
            if (!isCompactWidth) {
                selectedTimelineLog = log
            }
        }

        return MainScreenHandlers(
            passwordAddOpen = handlePasswordAddOpen,
            passwordEditOpen = handlePasswordEditOpen,
            inlinePasswordEditorBack = handleInlinePasswordEditorBack,
            totpAddOpen = handleTotpAddOpen,
            inlineTotpEditorBack = handleInlineTotpEditorBack,
            bankCardAddOpen = handleBankCardAddOpen,
            bankCardEditOpen = handleBankCardEditOpen,
            inlineBankCardEditorBack = handleInlineBankCardEditorBack,
            documentAddOpen = handleDocumentAddOpen,
            documentEditOpen = handleDocumentEditOpen,
            inlineDocumentEditorBack = handleInlineDocumentEditorBack,
            billingAddressOpen = handleBillingAddressOpen,
            billingAddressAddOpen = handleBillingAddressAddOpen,
            billingAddressEditOpen = handleBillingAddressEditOpen,
            inlineBillingAddressEditorBack = handleInlineBillingAddressEditorBack,
            walletAddOpen = handleWalletAddOpen,
            noteOpen = handleNoteOpen,
            inlineNoteEditorBack = handleInlineNoteEditorBack,
            passwordDetailOpen = handlePasswordDetailOpen,
            totpOpen = handleTotpOpen,
            bankCardOpen = handleBankCardOpen,
            documentOpen = handleDocumentOpen,
            passkeyOpen = handlePasskeyOpen,
            passkeyUnbind = handlePasskeyUnbind,
            confirmPasskeyDelete = confirmPasskeyDelete,
            sendOpen = handleSendOpen,
            sendAddOpen = handleSendAddOpen,
            inlineSendEditorBack = handleInlineSendEditorBack,
            timelineLogOpen = handleTimelineLogOpen
        )
    }

    val handlers = buildMainScreenHandlers()
    val handlePasswordAddOpen = handlers.passwordAddOpen
    val handlePasswordEditOpen = handlers.passwordEditOpen
    val handleInlinePasswordEditorBack = handlers.inlinePasswordEditorBack
    val handleTotpAddOpen = handlers.totpAddOpen
    val handleInlineTotpEditorBack = handlers.inlineTotpEditorBack
    val handleBankCardAddOpen = handlers.bankCardAddOpen
    val handleBankCardEditOpen = handlers.bankCardEditOpen
    val handleInlineBankCardEditorBack = handlers.inlineBankCardEditorBack
    val handleDocumentAddOpen = handlers.documentAddOpen
    val handleDocumentEditOpen = handlers.documentEditOpen
    val handleInlineDocumentEditorBack = handlers.inlineDocumentEditorBack
    val handleBillingAddressOpen = handlers.billingAddressOpen
    val handleBillingAddressAddOpen = handlers.billingAddressAddOpen
    val handleBillingAddressEditOpen = handlers.billingAddressEditOpen
    val handleInlineBillingAddressEditorBack = handlers.inlineBillingAddressEditorBack
    val handleWalletAddOpen = handlers.walletAddOpen
    val handleNoteOpen = handlers.noteOpen
    val handleInlineNoteEditorBack = handlers.inlineNoteEditorBack
    val handlePasswordDetailOpen = handlers.passwordDetailOpen
    val handleTotpOpen = handlers.totpOpen
    val handleBankCardOpen = handlers.bankCardOpen
    val handleDocumentOpen = handlers.documentOpen
    val handlePasskeyOpen = handlers.passkeyOpen
    val handlePasskeyUnbind = handlers.passkeyUnbind
    val confirmPasskeyDelete = handlers.confirmPasskeyDelete
    val handleSendOpen = handlers.sendOpen
    val handleSendAddOpen = handlers.sendAddOpen
    val handleInlineSendEditorBack = handlers.inlineSendEditorBack
    val handleTimelineLogOpen = handlers.timelineLogOpen

    // --- Tab switch cleanup effects ---
    // Reset detail/editor panes on tab changes to avoid stale selection or mixed mode state.
    MainScreenTabResetEffects(
        currentTab = currentTab,
        isCompactWidth = isCompactWidth,
        cardWalletSubTab = cardWalletSubTab,
        passwordHistoryPageMode = passwordHistoryPageMode,
        onResetPasswordPane = {
            resetPasswordPaneState()
            passwordHistoryPageMode = PasswordHistoryPageMode.NONE
        },
        onHideBackToTop = {
            passwordListShowBackToTop = false
            vaultV2PaneState.clearTransientUi()
        },
        onResetTotpPane = {
            resetTotpPaneState()
        },
        onResetCardWalletPaneAll = {
            resetCardWalletPaneState()
        },
        onResetCardWalletDocumentPane = {
            resetDocumentPaneState()
        },
        onResetCardWalletBankCardPane = {
            resetBankCardPaneState()
        },
        onResetCardWalletBillingAddressPane = {
            resetBillingAddressPaneState()
        },
        onSyncWalletUnifiedAddType = { walletUnifiedAddType = it },
        onResetNotePane = {
            resetNotePaneState()
        },
        onResetPasskeyPane = { selectedPasskey = null },
        onResetSendPane = {
            resetSendPaneState()
        },
        onResetTimelineSelection = { selectedTimelineLog = null }
    )

    val onCardWalletDocumentSelectionModeChange:
        (Boolean, Int, () -> Unit, () -> Unit, () -> Unit, () -> Unit) -> Unit =
        { isSelectionMode, count, onExit, onSelectAll, onMoveToCategory, onDelete ->
            documentSelectionState.isSelectionMode = isSelectionMode
            documentSelectionState.selectedCount = count
            documentSelectionState.onExit = onExit
            documentSelectionState.onSelectAll = onSelectAll
            documentSelectionState.onMoveToCategory = onMoveToCategory
            documentSelectionState.onDelete = onDelete
        }

    val onCardWalletBankCardSelectionModeChange:
        (Boolean, Int, () -> Unit, () -> Unit, () -> Unit, () -> Unit, () -> Unit) -> Unit =
        { isSelectionMode, count, onExit, onSelectAll, onDelete, onFavorite, onMoveToCategory ->
            bankCardSelectionState.isSelectionMode = isSelectionMode
            bankCardSelectionState.selectedCount = count
            bankCardSelectionState.onExit = onExit
            bankCardSelectionState.onSelectAll = onSelectAll
            bankCardSelectionState.onMoveToCategory = onMoveToCategory
            bankCardSelectionState.onDelete = onDelete
            bankCardSelectionState.onFavorite = onFavorite
        }

    val cardWalletContentState = CardWalletContentState(
        currentTab = cardWalletSubTab,
        onTabSelected = { cardWalletSubTab = it },
        onCardClick = { cardId ->
            handleBankCardOpen(cardId)
        },
        onDocumentClick = { documentId ->
            handleDocumentOpen(documentId)
        },
        onBillingAddressClick = { addressId ->
            handleBillingAddressOpen(addressId)
        },
        onDocumentSelectionModeChange = onCardWalletDocumentSelectionModeChange,
        onBankCardSelectionModeChange = onCardWalletBankCardSelectionModeChange,
        onBitwardenScopeChanged = { vaultId ->
            cardWalletBitwardenVaultId = vaultId
        }
    )
    
    val isBitwardenPageContext = when (currentTab) {
        BottomNavItem.VaultV2,
        BottomNavItem.Passwords -> isBitwardenPasswordFilter(currentFilter)
        BottomNavItem.Authenticator -> isBitwardenTotpFilter(totpFilter)
        BottomNavItem.CardWallet -> cardWalletBitwardenVaultId != null
        BottomNavItem.Notes,
        BottomNavItem.Passkey,
        BottomNavItem.Send -> activeBitwardenVault != null
        else -> false
    }
    val bitwardenStatusVaultId = when (currentTab) {
        BottomNavItem.CardWallet -> cardWalletBitwardenVaultId
        else -> activeBitwardenVault?.id
    }
    val activeVaultSyncState = bitwardenStatusVaultId?.let(bitwardenSyncStatusByVault::get)
    val nowMs by produceState(
        initialValue = System.currentTimeMillis(),
        key1 = activeVaultSyncState?.lastSuccessAt
    ) {
        val lastSuccessAt = activeVaultSyncState?.lastSuccessAt ?: run {
            value = System.currentTimeMillis()
            return@produceState
        }
        while (isActive) {
            val current = System.currentTimeMillis()
            value = current
            if (current - lastSuccessAt > 3000L) break
            delay(250)
        }
    }
    val bottomStatusUiState = resolveBitwardenBottomStatusUiState(
        status = activeVaultSyncState,
        nowMs = nowMs
    )
    val shouldHandleBitwardenStatusVisual =
        appSettings.bitwardenBottomStatusBarEnabled &&
            isBitwardenPageContext &&
            !isAnySelectionMode &&
            !isFabExpanded &&
            bottomStatusUiState != null
    val shouldShowBitwardenSyncIndicator =
        shouldHandleBitwardenStatusVisual && (bottomStatusUiState?.showProgress == true)
    var statusHintVisible by remember { mutableStateOf(false) }
    val activeMiniHints = remember { mutableStateListOf<BottomMiniHintMessage>() }
    val queuedMiniHints = remember { mutableStateListOf<BottomMiniHintMessage>() }
    val dismissingHintIds = remember { mutableStateListOf<Long>() }
    var sendHintSeed by remember { mutableLongStateOf(0L) }

    val syncHintVisible = statusHintVisible && shouldHandleBitwardenStatusVisual && bottomStatusUiState?.messageRes != null
    fun tryActivateQueuedMiniHints() {
        val syncOccupiesSlot = syncHintVisible
        val maxCustomHints = (MAX_BOTTOM_MINI_HINTS - if (syncOccupiesSlot) 1 else 0).coerceAtLeast(0)
        while (activeMiniHints.size < maxCustomHints && queuedMiniHints.isNotEmpty()) {
            val nextHint = queuedMiniHints.removeAt(0)
            activeMiniHints += nextHint
            scope.launch {
                delay(2800L)
                dismissingHintIds += nextHint.id
                delay(280L)
                activeMiniHints.removeAll { it.id == nextHint.id }
                dismissingHintIds.removeAll { it == nextHint.id }
                tryActivateQueuedMiniHints()
            }
        }
    }

    val enqueueMiniHint: (String, String?) -> Unit = { title, supportingText ->
        val hintId = ++sendHintSeed
        queuedMiniHints += BottomMiniHintMessage(
            id = hintId,
            title = title,
            supportingText = supportingText
        )
        tryActivateQueuedMiniHints()
    }
    val handleSendBitwardenEvent: (com.bastion.app.bitwarden.viewmodel.BitwardenViewModel.BitwardenEvent) -> Boolean = { event ->
        when (event) {
            is com.bastion.app.bitwarden.viewmodel.BitwardenViewModel.BitwardenEvent.SendCreated -> {
                enqueueMiniHint(event.message, null)
                true
            }

            is com.bastion.app.bitwarden.viewmodel.BitwardenViewModel.BitwardenEvent.SendDeleted -> {
                enqueueMiniHint(event.message, null)
                true
            }

            is com.bastion.app.bitwarden.viewmodel.BitwardenViewModel.BitwardenEvent.SyncFinished -> {
                true
            }

            else -> false
        }
    }
    LaunchedEffect(bitwardenViewModel, context) {
        bitwardenViewModel.events.collect { event ->
            when (event) {
                is com.bastion.app.bitwarden.viewmodel.BitwardenViewModel.BitwardenEvent.SyncFinished -> {
                    val hint = buildBitwardenSyncMiniHint(context, event.summary)
                    enqueueMiniHint(hint.title, hint.supportingText)
                }
                // 主 Vault 界面此前未消费 ShowError：同步失败/超时在这里以 Toast 明确提示，
                // 避免"感觉没同步"的静默失败（用户点同步后至少能看到失败原因）
                is com.bastion.app.bitwarden.viewmodel.BitwardenViewModel.BitwardenEvent.ShowError -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
                }
                else -> {}
            }
        }
    }
    LaunchedEffect(shouldHandleBitwardenStatusVisual, bottomStatusUiState?.messageRes) {
        if (!shouldHandleBitwardenStatusVisual || bottomStatusUiState?.messageRes == null) {
            statusHintVisible = false
            return@LaunchedEffect
        }
        statusHintVisible = true
        delay(if (bottomStatusUiState.showProgress) 2600L else 3600L)
        statusHintVisible = false
    }
    LaunchedEffect(syncHintVisible, queuedMiniHints.size, activeMiniHints.size) {
        tryActivateQueuedMiniHints()
    }

    // --- Main surface composition ---
    // Decides draggable nav vs classic scaffold and dispatches per-tab content.
    @Composable
    fun RenderMainSurface() {
    Box(modifier = Modifier.fillMaxSize()) {
    // 根据设置选择导航模式
    Box(
        modifier = Modifier
            .matchParentSize()
    ) {
        if (useDraggableNav && isCompactWidth && !shouldHideBottomNavigation) {
        // 使用可拖拽底部导航栏
        DraggableBottomNavScaffold(
            navItems = draggableNavItems,
            statusIndicatorVisible = shouldShowBitwardenSyncIndicator,

            quickAddCallback = QuickAddCallback(
                onAddPassword = { title, username, password ->
                    passwordViewModel.quickAddPassword(title, username, password)
                },
                onAddTotp = { name, secret ->
                    totpViewModel.quickAddTotp(name, secret)
                },
                onAddBankCard = { name, number ->
                    bankCardViewModel.quickAddBankCard(name, number)
                },
                onAddNote = { title, content ->
                    noteViewModel.quickAddNote(title, content)
                }
            ),
            floatingActionButton = {}, // FAB 移至外层 Overlay
            content = { paddingValues ->
                CompactDraggableTabContent(
                    paddingValues = paddingValues,
                    currentTab = currentTab,
                    showStandaloneSettingsEntry = shouldHideBottomNavigation,
                    onOpenStandaloneSettings = onNavigateToStandaloneSettings,
                    passwordViewModel = passwordViewModel,
                    settingsViewModel = settingsViewModel,
                    securityManager = securityManager,
                    keepassDatabases = keepassDatabases,
                    bitwardenVaults = bitwardenVaults,
                    localKeePassViewModel = localKeePassViewModel,
                    passwordGroupMode = passwordGroupMode,
                    stackCardMode = stackCardMode,
                    onPasswordOpen = handlePasswordDetailOpen,
                    onBankCardOpen = handleBankCardOpen,
                    onDocumentOpen = handleDocumentOpen,
                    onNoteOpen = { handleNoteOpen(it) },
                    onPasskeyOpen = handlePasskeyOpen,
                    onPasswordSelectionModeChange = { isSelectionMode, count, onExit, onSelectAll, onFavorite, onMoveToCategory, onStack, onDelete ->
                        isPasswordSelectionMode = isSelectionMode
                        selectedPasswordCount = count
                        onExitPasswordSelection = onExit
                        onSelectAllPasswords = onSelectAll
                        onFavoriteSelectedPasswords = onFavorite
                        onMoveToCategoryPasswords = onMoveToCategory
                        onManualStackPasswords = onStack
                        onDeleteSelectedPasswords = onDelete
                    },
                    onBackToTopVisibilityChange = { visible ->
                        passwordListShowBackToTop = visible
                    },
                    passwordScrollToTopRequestKey = passwordScrollToTopRequestKey,
                    totpViewModel = totpViewModel,
                    onTotpOpen = handleTotpOpen,
                    onNavigateToAddTotp = onNavigateToAddTotp,
                    onNavigateToQuickTotpScan = onNavigateToQuickTotpScan,
                    onNavigateToFidoQrScan = onNavigateToFidoQrScan,
                    onTotpSelectionModeChange = { isSelectionMode, count, onExit, onSelectAll, onMoveToCategory, onDelete ->
                        totpSelectionState.isSelectionMode = isSelectionMode
                        totpSelectionState.selectedCount = count
                        totpSelectionState.onExit = onExit
                        totpSelectionState.onSelectAll = onSelectAll
                        totpSelectionState.onMoveToCategory = onMoveToCategory
                        totpSelectionState.onDelete = onDelete
                    },
                    cardWalletSaveableStateHolder = cardWalletSaveableStateHolder,
                    bankCardViewModel = bankCardViewModel,
                    documentViewModel = documentViewModel,
                    billingAddressViewModel = billingAddressViewModel,
                    cardWalletContentState = cardWalletContentState,
                    generatorViewModel = generatorViewModel,
                    generatorRefreshRequestKey = generatorRefreshRequestKey,
                    onGeneratorRefreshRequestConsumed = { generatorRefreshRequestKey = 0 },
                    noteViewModel = noteViewModel,
                    onNavigateToAddNote = handleNoteOpen,
                    onNavigateToSearchedNote = onNavigateToSearchedNote,
                    onNavigateToNoteDetail = onNavigateToNoteDetail,
                    onNavigateToBankCardDetail = onNavigateToBankCardDetail,
                    onNavigateToDocumentDetail = onNavigateToDocumentDetail,
                    onNavigateToBillingAddressDetail = handleBillingAddressOpen,
                    onNavigateToPasskeyDetail = onNavigateToPasskeyDetail,
                    onNoteSelectionModeChange = { isSelectionMode ->
                        isNoteSelectionMode = isSelectionMode
                    },
                    timelineViewModel = timelineViewModel,
                    passkeyViewModel = passkeyViewModel,
                    onNavigateToPasswordDetail = onNavigateToPasswordDetail,
                    onNavigateToAuthenticator = {
                        selectedTabKey = BottomNavItem.Authenticator.key
                    },
                    onNavigateToPasskeys = {
                        selectedTabKey = BottomNavItem.Passkey.key
                    },
                    bitwardenViewModel = bitwardenViewModel,
                    onSendBitwardenEvent = handleSendBitwardenEvent,
                    onNavigateToChangePassword = onNavigateToChangePassword,
                    onNavigateToSecurityQuestion = onNavigateToSecurityQuestion,
                    onNavigateToMasterPasswordLocking = onNavigateToMasterPasswordLocking,
                    onNavigateToSyncBackup = onNavigateToSyncBackup,
                    onNavigateToAutofill = onNavigateToAutofill,
                    onNavigateToPasskeySettings = onNavigateToPasskeySettings,
                    onSecurityAnalysis = onSecurityAnalysis,
                    onNavigateToDeveloperSettings = onNavigateToDeveloperSettings,
                    onNavigateToPermissionManagement = onNavigateToPermissionManagement,
                    onNavigateToExtensions = onNavigateToExtensions,
                    onNavigateToCommonAccountTemplates = onNavigateToCommonAccountTemplates,
                    onNavigateToPageCustomization = onNavigateToPageCustomization,
                    onNavigateToThemeAndColorScheme = onNavigateToThemeAndColorScheme,
                    onOpenVaultV2HistoryPage = {
                        selectedTabKey = BottomNavItem.Passwords.key
                        openHistoryPage()
                    },
                    onOpenVaultV2TrashPage = {
                        selectedTabKey = BottomNavItem.Passwords.key
                        openTrashPage()
                    },
                    onOpenVaultV2ArchivePage = {
                        vaultV2PaneState.openArchiveView()
                    },
                    onManageKeePassDatabase = onManageKeePassDatabase,
                    onClearAllData = onClearAllData,
                    cardWalletSubTab = cardWalletSubTab,
                    passwordHistoryPageMode = passwordHistoryPageMode,
                    passwordHistoryInitialTrashScopeKey = passwordHistoryInitialTrashScopeKey,
                    onOpenHistoryPage = openHistoryPage,
                    onOpenTrashPage = openTrashPage,
                    onCloseHistoryPage = closeHistoryPage,
                    isPasswordSelectionMode = isPasswordSelectionMode,
                    selectedPasswordCount = selectedPasswordCount,
                    onExitPasswordSelection = onExitPasswordSelection,
                    onSelectAllPasswords = onSelectAllPasswords,
                    onFavoriteSelectedPasswords = onFavoriteSelectedPasswords,
                    onMoveToCategoryPasswords = onMoveToCategoryPasswords,
                    onManualStackPasswords = onManualStackPasswords,
                    onDeleteSelectedPasswords = onDeleteSelectedPasswords,
                    isTotpSelectionMode = totpSelectionState.isSelectionMode,
                    selectedTotpCount = totpSelectionState.selectedCount,
                    onExitTotpSelection = totpSelectionState.onExit,
                    onSelectAllTotp = totpSelectionState.onSelectAll,
                    onMoveToCategoryTotp = totpSelectionState.onMoveToCategory,
                    onDeleteSelectedTotp = totpSelectionState.onDelete,
                    isBankCardSelectionMode = bankCardSelectionState.isSelectionMode,
                    selectedBankCardCount = bankCardSelectionState.selectedCount,
                    onExitBankCardSelection = bankCardSelectionState.onExit,
                    onSelectAllBankCards = bankCardSelectionState.onSelectAll,
                    onFavoriteBankCards = bankCardSelectionState.onFavorite,
                    onMoveToCategoryBankCards = bankCardSelectionState.onMoveToCategory,
                    onDeleteSelectedBankCards = bankCardSelectionState.onDelete,
                    isDocumentSelectionMode = documentSelectionState.isSelectionMode,
                    selectedDocumentCount = documentSelectionState.selectedCount,
                    onExitDocumentSelection = documentSelectionState.onExit,
                    onSelectAllDocuments = documentSelectionState.onSelectAll,
                    onMoveToCategoryDocuments = documentSelectionState.onMoveToCategory,
                    onDeleteSelectedDocuments = documentSelectionState.onDelete,
                    vaultV2PaneState = vaultV2PaneState,
                )
            }
        )
    } else {
        // 使用传统底部导航栏
    Scaffold(
        // 容器色取 background（与 MainActivity 根布局 Surface 同色）：
        // ① 默认 surface 与根布局存在色差，会在底栏槽位形成「矩形接缝带」；
        // ② 必须不透明，否则转场/解锁瞬间下层不透明的 Login 屏会从列表空隙透出。
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            // 顶部栏由各自页面内部控制（如 ExpressiveTopBar），这里保持为空以避免叠加
        },
        // 沉浸状态栏：统一 0 insets，内容从屏幕顶部开始（状态栏底下）。
        // 状态栏避让下沉到各页面自行处理：
        // - ExpressiveTopBar 页面（密码/验证器/卡包/通行秘钥）：Bar 内 statusBarsPadding + 列表 contentPadding 含状态栏
        // - 内层自带 Scaffold 的页面（设置/Send/笔记）：内层 Scaffold 自动处理
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (isCompactWidth && !shouldHideBottomNavigation) {
                Column {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = shouldShowBitwardenSyncIndicator,
                        enter = slideInVertically(
                            initialOffsetY = { it / 2 },
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessLow
                            )
                        ) + fadeIn(),
                        exit = slideOutVertically(
                            targetOffsetY = { it / 2 },
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness = Spring.StiffnessLow
                            )
                        ) + fadeOut()
                    ) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp),
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxSize(),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                            )
                        }
                    }

                    // 底栏悬浮胶囊：Surface 提供圆角 + 阴影 + 半透明 + 离屏边内边距，
// NavigationBar 在内层透明显示。这样既有「浮起来」的层次感，又不依赖第三方库。
                    // 底栏悬浮胶囊（对齐酷安规格）：60dp 扁胶囊 + 内容可见的周围间隙。
                    // 选中态 = secondaryContainer 圆角块包住「图标+文字」整体；中间 + 号为圆角方形。
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            // bottom 20dp：胶囊明显抬离系统手势条（小白条），底部透出内容
                            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 20.dp)
                    ) {
                    androidx.compose.material3.Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 3.dp,
                        shadowElevation = 6.dp
                    ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 底栏布局：左侧 2 个 + 中间「添加」+ 右侧「1 个自定义 + 固定设置」。
                        // tabs 已保证数据 tab 最多 3 个且 Settings 固定在末尾。
                        val leftTabs = tabs.take(2)
                        val rightTabs = tabs.drop(2).take(2)

                        leftTabs.forEach { item ->
                            key(item.key) {
                                BottomNavTabItem(
                                    item = item,
                                    isSelected = item.key == selectedDockTab.key,
                                    onClick = { selectedTabKey = item.key },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        repeat(2 - leftTabs.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }

                        // 中间「添加」按钮：主色圆角方形（酷安样式），接近撑满胶囊高度。
                        // 行为沿用原 FAB：按当前 tab 跳到对应的添加页。
                        val addLabel = stringResource(R.string.add)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.Center
                        ) {
                            Surface(
                                onClick = {
                                    when (currentTab) {
                                        BottomNavItem.VaultV2,
                                        BottomNavItem.Passwords -> handlePasswordAddOpen()
                                        BottomNavItem.Authenticator -> handleTotpAddOpen()
                                        BottomNavItem.CardWallet -> handleWalletAddOpen()
                                        BottomNavItem.Notes -> handleNoteOpen(null)
                                        BottomNavItem.Send -> handleSendAddOpen()
                                        BottomNavItem.Generator -> generatorRefreshRequestKey++
                                        else -> handlePasswordAddOpen()
                                    }
                                },
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier
                                    .width(52.dp)
                                    .height(48.dp)
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = addLabel,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                            }
                        }

                        rightTabs.forEach { item ->
                            key(item.key) {
                                BottomNavTabItem(
                                    item = item,
                                    isSelected = item.key == selectedDockTab.key,
                                    onClick = { selectedTabKey = item.key },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        repeat(2 - rightTabs.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                    }  // 关闭 Row
                }  // 关闭 Surface 胶囊包装
                    }  // 关闭 Box 留白
            }
        },
        floatingActionButton = {} // FAB 移至外层 Overlay
    ) { paddingValues ->

        if (isCompactWidth) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // 不吃掉 bottom padding：让列表延伸到悬浮胶囊底下（胶囊浮于内容上层，
                    // 周围间隙透出内容，同 Coolapk）。最后一条的滚出空间由各列表的
                    // contentPadding.bottom 自行预留（胶囊总高 82dp，取 96dp）。
                    .padding(
                        top = paddingValues.calculateTopPadding(),
                        start = paddingValues.calculateLeftPadding(LocalLayoutDirection.current),
                        end = paddingValues.calculateRightPadding(LocalLayoutDirection.current)
                    )
            ) {
            AuthenticatorPasskeyAnimatedContent(currentTab = currentTab) { displayedTab ->
            when (displayedTab) {
                BottomNavItem.VaultV2 -> {
                    VaultV2Pane(
                        passwordViewModel = passwordViewModel,
                        totpViewModel = totpViewModel,
                        bankCardViewModel = bankCardViewModel,
                        documentViewModel = documentViewModel,
                        noteViewModel = noteViewModel,
                        passkeyViewModel = passkeyViewModel,
                    keepassDatabases = keepassDatabases,
                    bitwardenVaults = bitwardenVaults,
                    localKeePassViewModel = localKeePassViewModel,
                    settingsViewModel = settingsViewModel,
                    state = vaultV2PaneState,
                    onOpenPassword = handlePasswordDetailOpen,
                    onOpenTotp = handleTotpOpen,
                        onOpenBankCard = handleBankCardOpen,
                    onOpenDocument = handleDocumentOpen,
                    onOpenNote = { handleNoteOpen(it) },
                    onOpenPasskey = onNavigateToPasskeyDetail,
                    onOpenHistory = {
                        openHistoryPage()
                    },
                    onOpenTrashPage = {
                        openTrashPage()
                    },
                    onOpenArchivePage = {
                        vaultV2PaneState.openArchiveView()
                    },
                    onManageKeePassDatabase = onManageKeePassDatabase,
                    onOpenCommonAccountTemplates = onNavigateToCommonAccountTemplates,
                    onScanFidoQr = onNavigateToFidoQrScan,
                    onOpenStandaloneSettings = onNavigateToStandaloneSettings,
                    showStandaloneSettingsEntry = shouldHideBottomNavigation,
                    appSettings = appSettings,
                    securityManager = securityManager,
                    biometricEnabled = appSettings.biometricEnabled,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            BottomNavItem.Passwords -> {
                    PasswordTabPane(
                        isCompactWidth = isCompactWidth,
                        wideListPaneWidth = wideListPaneWidth,
                        passwordViewModel = passwordViewModel,
                        settingsViewModel = settingsViewModel,
                        securityManager = securityManager,
                        keepassDatabases = keepassDatabases,
                        bitwardenVaults = bitwardenVaults,
                        localKeePassViewModel = localKeePassViewModel,
                        timelineViewModel = timelineViewModel,
                        groupMode = passwordGroupMode,
                        stackCardMode = stackCardMode,
                        visibleContentTypes = passwordPageVisibleContentTypes,
                        selectedContentTypes = passwordPageSelectedContentTypes,
                        onToggleContentType = { type ->
                            passwordPageSelectedContentTypes = togglePasswordPageContentType(
                                currentTypes = passwordPageSelectedContentTypes,
                                toggledType = type,
                                visibleTypes = passwordPageVisibleContentTypes
                            )
                        },
                        onPasswordOpen = handlePasswordDetailOpen,
                    onNavigateToAddTotp = onNavigateToAddTotp,
                    onNavigateToBankCardDetail = onNavigateToBankCardDetail,
                    onNavigateToDocumentDetail = onNavigateToDocumentDetail,
                    onNavigateToBillingAddressDetail = handleBillingAddressOpen,
                    onNavigateToAddNote = handleNoteOpen,
                        onNavigateToNoteDetail = onNavigateToNoteDetail,
                        onNavigateToPasskeyDetail = onNavigateToPasskeyDetail,
                        onOpenHistoryPage = openHistoryPage,
                        onOpenTrashPage = openTrashPage,
                        onOpenCommonAccountTemplatesPage = onNavigateToCommonAccountTemplates,
                        onScanFidoQr = onNavigateToFidoQrScan,
                        onCloseHistoryPage = closeHistoryPage,
                        passwordHistoryPageMode = passwordHistoryPageMode,
                        passwordHistoryInitialTrashScopeKey = passwordHistoryInitialTrashScopeKey,
                        onTimelineLogSelected = handleTimelineLogOpen,
                        onSelectionModeChange = { isSelectionMode, count, onExit, onSelectAll, onFavorite, onMoveToCategory, onStack, onDelete ->
                            isPasswordSelectionMode = isSelectionMode
                            selectedPasswordCount = count
                            onExitPasswordSelection = onExit
                            onSelectAllPasswords = onSelectAll
                            onFavoriteSelectedPasswords = onFavorite
                            onMoveToCategoryPasswords = onMoveToCategory
                            onManualStackPasswords = onStack
                            onDeleteSelectedPasswords = onDelete
                        },
                        onBackToTopVisibilityChange = { visible ->
                            passwordListShowBackToTop = visible
                        },
                        scrollToTopRequestKey = passwordScrollToTopRequestKey,
                        isAddingPasswordInline = isAddingPasswordInline,
                        inlinePasswordEditorId = inlinePasswordEditorId,
                        selectedPasswordId = selectedPasswordId,
                        passwordNewItemDefaults = pendingInlinePasswordAddStorageDefaults ?: passwordNewItemDefaults,
                        onInlinePasswordEditorBack = handleInlinePasswordEditorBack,
                        onNavigateToAddWifi = onNavigateToAddWifi,
                        onNavigateToAddSshKey = onNavigateToAddSshKey,
                        pendingPasswordAuthenticatorQrResult = pendingPasswordAuthenticatorQrResult,
                        onConsumePendingPasswordAuthenticatorQrResult =
                            onConsumePendingPasswordAuthenticatorQrResult,
                        onScanPasswordAuthenticatorQrCode = onScanPasswordAuthenticatorQrCode,
                        totpViewModel = totpViewModel,
                        bankCardViewModel = bankCardViewModel,
                        noteViewModel = noteViewModel,
                        documentViewModel = documentViewModel,
                        billingAddressViewModel = billingAddressViewModel,
                        passkeyViewModel = passkeyViewModel,
                        biometricEnabled = appSettings.biometricEnabled,
                        iconCardsEnabled = appSettings.iconCardsEnabled && appSettings.passwordPageIconEnabled,
                        unmatchedIconHandlingStrategy = appSettings.unmatchedIconHandlingStrategy,
                        onClearSelectedPassword = clearSelectedPasswordPaneItem,
                        onEditPassword = handlePasswordEditOpen
                        ,
                        showStandaloneSettingsEntry = shouldHideBottomNavigation,
                        onOpenStandaloneSettings = onNavigateToStandaloneSettings
                    )
                }
                BottomNavItem.Authenticator -> {
                    AuthenticatorTabPane(
                        isCompactWidth = isCompactWidth,
                        wideListPaneWidth = wideListPaneWidth,
                        totpViewModel = totpViewModel,
                        passwordViewModel = passwordViewModel,
                        localKeePassViewModel = localKeePassViewModel,
                        onTotpOpen = handleTotpOpen,
                        onNavigateToQuickTotpScan = onNavigateToQuickTotpScan,
                        onSelectionModeChange = { isSelectionMode, count, onExit, onSelectAll, onMoveToCategory, onDelete ->
                            totpSelectionState.isSelectionMode = isSelectionMode
                            totpSelectionState.selectedCount = count
                            totpSelectionState.onExit = onExit
                            totpSelectionState.onSelectAll = onSelectAll
                            totpSelectionState.onMoveToCategory = onMoveToCategory
                            totpSelectionState.onDelete = onDelete
                        },
                        isAddingTotpInline = isAddingTotpInline,
                        selectedTotpId = selectedTotpId,
                        totpNewItemDefaults = pendingInlineTotpAddStorageDefaults ?: totpNewItemDefaults,
                        onInlineTotpEditorBack = handleInlineTotpEditorBack,
                        showStandaloneSettingsEntry = shouldHideBottomNavigation,
                        onOpenStandaloneSettings = onNavigateToStandaloneSettings,
                        onNavigateToPasskeys = {
                            selectedTabKey = BottomNavItem.Passkey.key
                        }
                    )
                }
                BottomNavItem.CardWallet -> {
                    CardWalletPane(
                        isCompactWidth = isCompactWidth,
                        wideListPaneWidth = wideListPaneWidth,
                        saveableStateHolder = cardWalletSaveableStateHolder,
                        bankCardViewModel = bankCardViewModel,
                        documentViewModel = documentViewModel,
                        billingAddressViewModel = billingAddressViewModel,
                        passwordViewModel = passwordViewModel,
                        bitwardenViewModel = bitwardenViewModel,
                        contentState = cardWalletContentState,
                        isAddingBankCardInline = isAddingBankCardInline,
                        inlineBankCardEditorId = inlineBankCardEditorId,
                        onInlineBankCardEditorBack = handleInlineBankCardEditorBack,
                        isAddingDocumentInline = isAddingDocumentInline,
                        inlineDocumentEditorId = inlineDocumentEditorId,
                        onInlineDocumentEditorBack = handleInlineDocumentEditorBack,
                        isAddingBillingAddressInline = isAddingBillingAddressInline,
                        inlineBillingAddressEditorId = inlineBillingAddressEditorId,
                        onInlineBillingAddressEditorBack = handleInlineBillingAddressEditorBack,
                        selectedBankCardId = selectedBankCardId,
                        onClearSelectedBankCard = clearSelectedBankCardPaneItem,
                        onEditBankCard = handleBankCardEditOpen,
                        selectedDocumentId = selectedDocumentId,
                        onClearSelectedDocument = clearSelectedDocumentPaneItem,
                        onEditDocument = handleDocumentEditOpen,
                        selectedBillingAddressId = selectedBillingAddressId,
                        onClearSelectedBillingAddress = clearSelectedBillingAddressPaneItem,
                        onEditBillingAddress = handleBillingAddressEditOpen,
                        initialCategoryId = pendingInlineWalletAddStorageDefaults?.categoryId,
                        initialKeePassDatabaseId = pendingInlineWalletAddStorageDefaults?.keepassDatabaseId,
                        initialKeePassGroupPath = pendingInlineWalletAddStorageDefaults?.keepassGroupPath,
                        initialBitwardenVaultId = pendingInlineWalletAddStorageDefaults?.bitwardenVaultId,
                        initialBitwardenFolderId = pendingInlineWalletAddStorageDefaults?.bitwardenFolderId,
                        showStandaloneSettingsEntry = shouldHideBottomNavigation,
                        onOpenStandaloneSettings = onNavigateToStandaloneSettings
                    )
                }
                BottomNavItem.Generator -> {
                    GeneratorPane(
                        isCompactWidth = isCompactWidth,
                        wideListPaneWidth = wideListPaneWidth,
                        generatorViewModel = generatorViewModel,
                        passwordViewModel = passwordViewModel,
                        externalRefreshRequestKey = generatorRefreshRequestKey,
                        onRefreshRequestConsumed = { generatorRefreshRequestKey = 0 },
                        selectedGenerator = selectedGeneratorType,
                        generatedValue = currentGeneratorResult,
                        showStandaloneSettingsEntry = shouldHideBottomNavigation,
                        onOpenStandaloneSettings = onNavigateToStandaloneSettings
                    )
                }
                BottomNavItem.Notes -> {
                    NotePane(
                        isCompactWidth = isCompactWidth,
                        wideListPaneWidth = wideListPaneWidth,
                        noteViewModel = noteViewModel,
                        settingsViewModel = settingsViewModel,
                        securityManager = securityManager,
                        passwordViewModel = passwordViewModel,
                        onNavigateToAddNote = handleNoteOpen,
                        onNavigateToSearchedNote = onNavigateToSearchedNote,
                        onSelectionModeChange = { isSelectionMode ->
                            isNoteSelectionMode = isSelectionMode
                        },
                        isAddingNoteInline = isAddingNoteInline,
                        inlineNoteEditorId = inlineNoteEditorId,
                        onInlineNoteEditorBack = handleInlineNoteEditorBack,
                        initialCategoryId = pendingInlineNoteAddStorageDefaults?.categoryId,
                        initialKeePassDatabaseId = pendingInlineNoteAddStorageDefaults?.keepassDatabaseId,
                        initialKeePassGroupPath = pendingInlineNoteAddStorageDefaults?.keepassGroupPath,
                        initialBitwardenVaultId = pendingInlineNoteAddStorageDefaults?.bitwardenVaultId,
                        initialBitwardenFolderId = pendingInlineNoteAddStorageDefaults?.bitwardenFolderId,
                        showStandaloneSettingsEntry = shouldHideBottomNavigation,
                        onOpenStandaloneSettings = onNavigateToStandaloneSettings
                    )
                }
                BottomNavItem.Passkey -> {
                    PasskeyPane(
                        isCompactWidth = isCompactWidth,
                        wideListPaneWidth = wideListPaneWidth,
                        passkeyViewModel = passkeyViewModel,
                        passwordViewModel = passwordViewModel,
                        onNavigateToPasswordDetail = onNavigateToPasswordDetail,
                        onNavigateToPasskeyDetail = onNavigateToPasskeyDetail,
                        onPasskeyOpen = handlePasskeyOpen,
                        selectedPasskey = selectedPasskey,
                        passkeyTotalCount = passkeyTotalCount,
                        passkeyBoundCount = passkeyBoundCount,
                        resolvePasswordTitle = { passwordId -> passwordById[passwordId]?.title },
                        onOpenPasswordDetail = handlePasswordDetailOpen,
                        onUnbindPasskey = handlePasskeyUnbind,
                        onDeletePasskey = { passkey -> pendingPasskeyDelete = passkey },
                        showStandaloneSettingsEntry = shouldHideBottomNavigation,
                        onOpenStandaloneSettings = onNavigateToStandaloneSettings,
                        onNavigateToAuthenticator = {
                            selectedTabKey = BottomNavItem.Authenticator.key
                        }
                    )
                }
                BottomNavItem.Send -> {
                    SendPane(
                        isCompactWidth = isCompactWidth,
                        wideListPaneWidth = wideListPaneWidth,
                        bitwardenViewModel = bitwardenViewModel,
                        sendState = sendState,
                        selectedSend = selectedSend,
                        isAddingSendInline = isAddingSendInline,
                        onSendClick = handleSendOpen,
                        onInlineSendEditorBack = handleInlineSendEditorBack,
                        onCreateSend = { vaultId, title, text, notes, password, maxAccessCount, hideEmail, hiddenText, expireInDays ->
                            bitwardenViewModel.createTextSend(
                                vaultId = vaultId,
                                title = title,
                                text = text,
                                notes = notes,
                                password = password,
                                maxAccessCount = maxAccessCount,
                                hideEmail = hideEmail,
                                hiddenText = hiddenText,
                                expireInDays = expireInDays
                            )
                        },
                        onCreateFileSend = { vaultId, title, fileUri, fileName, notes, password, maxAccessCount, hideEmail, expireInDays ->
                            bitwardenViewModel.createFileSend(
                                vaultId = vaultId,
                                title = title,
                                fileUri = fileUri,
                                fileName = fileName,
                                notes = notes,
                                password = password,
                                maxAccessCount = maxAccessCount,
                                hideEmail = hideEmail,
                                expireInDays = expireInDays
                            )
                        },
                        onBitwardenEvent = handleSendBitwardenEvent,
                        showStandaloneSettingsEntry = shouldHideBottomNavigation,
                        onOpenStandaloneSettings = onNavigateToStandaloneSettings
                    )
                }
                BottomNavItem.Settings -> {
                    SettingsTabContent(
                        viewModel = settingsViewModel,
                        onResetPassword = onNavigateToChangePassword,
                        onSecurityQuestions = onNavigateToSecurityQuestion,
                        onNavigateToMasterPasswordLocking = onNavigateToMasterPasswordLocking,
                        onNavigateToSyncBackup = onNavigateToSyncBackup,
                        onNavigateToAutofill = onNavigateToAutofill,
                        onNavigateToPasskeySettings = onNavigateToPasskeySettings,
                        onSecurityAnalysis = onSecurityAnalysis,
                        onNavigateToDeveloperSettings = onNavigateToDeveloperSettings,
                        onNavigateToPermissionManagement = onNavigateToPermissionManagement,
                        onNavigateToExtensions = onNavigateToExtensions,
                        onNavigateToPageCustomization = onNavigateToPageCustomization,
                        onNavigateToThemeAndColorScheme = onNavigateToThemeAndColorScheme,
                        isCompactWidth = isCompactWidth,
                        wideListPaneWidth = wideListPaneWidth,
                        onClearAllData = onClearAllData
                    )
                }
            }
            }

            MainScreenSelectionBars(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 20.dp),
                currentTab = currentTab,
                cardWalletSubTab = cardWalletSubTab,
                isPasswordSelectionMode = isPasswordSelectionMode,
                selectedPasswordCount = selectedPasswordCount,
                onExitPasswordSelection = onExitPasswordSelection,
                onSelectAllPasswords = onSelectAllPasswords,
                onFavoriteSelectedPasswords = onFavoriteSelectedPasswords,
                onMoveToCategoryPasswords = onMoveToCategoryPasswords,
                onManualStackPasswords = onManualStackPasswords,
                onDeleteSelectedPasswords = onDeleteSelectedPasswords,
                isTotpSelectionMode = totpSelectionState.isSelectionMode,
                selectedTotpCount = totpSelectionState.selectedCount,
                onExitTotpSelection = totpSelectionState.onExit,
                onSelectAllTotp = totpSelectionState.onSelectAll,
                onMoveToCategoryTotp = totpSelectionState.onMoveToCategory,
                onDeleteSelectedTotp = totpSelectionState.onDelete,
                isBankCardSelectionMode = bankCardSelectionState.isSelectionMode,
                selectedBankCardCount = bankCardSelectionState.selectedCount,
                onExitBankCardSelection = bankCardSelectionState.onExit,
                onSelectAllBankCards = bankCardSelectionState.onSelectAll,
                onFavoriteBankCards = bankCardSelectionState.onFavorite,
                onMoveToCategoryBankCards = bankCardSelectionState.onMoveToCategory,
                onDeleteSelectedBankCards = bankCardSelectionState.onDelete,
                isDocumentSelectionMode = documentSelectionState.isSelectionMode,
                selectedDocumentCount = documentSelectionState.selectedCount,
                onExitDocumentSelection = documentSelectionState.onExit,
                onSelectAllDocuments = documentSelectionState.onSelectAll,
                onMoveToCategoryDocuments = documentSelectionState.onMoveToCategory,
                onDeleteSelectedDocuments = documentSelectionState.onDelete
            )
            }
        } else {
            val railTopInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val railBottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                if (!shouldHideBottomNavigation) {
                    NavigationRail(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(wideNavigationRailWidth),
                        windowInsets = WindowInsets(0, 0, 0, 0),
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        Column(
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .padding(
                                    top = railTopInset + 8.dp,
                                    bottom = railBottomInset + 8.dp
                                ),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            tabs.forEach { item ->
                                val label = stringResource(item.shortLabelRes())
                                NavigationRailItem(
                                    selected = item.key == selectedDockTab.key,
                                    onClick = { selectedTabKey = item.key },
                                    icon = { Icon(item.icon, contentDescription = label) },
                                    label = {
                                        Text(
                                            text = label,
                                            maxLines = 2,
                                            overflow = TextOverflow.Clip
                                        )
                                    },
                                    alwaysShowLabel = true,
                                    // 与 NavigationBarItem 保持一致：关闭漂浮放大 indicator。
                                    colors = NavigationRailItemDefaults.colors(
                                        indicatorColor = Color.Transparent
                                    )
                                )
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                AuthenticatorPasskeyAnimatedContent(currentTab = currentTab) { displayedTab ->
                when (displayedTab) {
                    BottomNavItem.VaultV2 -> {
                        VaultV2Pane(
                            passwordViewModel = passwordViewModel,
                            totpViewModel = totpViewModel,
                            bankCardViewModel = bankCardViewModel,
                            documentViewModel = documentViewModel,
                            noteViewModel = noteViewModel,
                            passkeyViewModel = passkeyViewModel,
                        keepassDatabases = keepassDatabases,
                        bitwardenVaults = bitwardenVaults,
                        localKeePassViewModel = localKeePassViewModel,
                        settingsViewModel = settingsViewModel,
                        state = vaultV2PaneState,
                        onOpenPassword = handlePasswordDetailOpen,
                        onOpenTotp = handleTotpOpen,
                            onOpenBankCard = handleBankCardOpen,
                        onOpenDocument = handleDocumentOpen,
                        onOpenNote = { handleNoteOpen(it) },
                        onOpenPasskey = onNavigateToPasskeyDetail,
                        onOpenHistory = {
                            openHistoryPage()
                        },
                        onOpenTrashPage = {
                            openTrashPage()
                        },
                        onOpenArchivePage = {
                            vaultV2PaneState.openArchiveView()
                        },
                        onManageKeePassDatabase = onManageKeePassDatabase,
                        onOpenCommonAccountTemplates = onNavigateToCommonAccountTemplates,
                        onScanFidoQr = onNavigateToFidoQrScan,
                        onOpenStandaloneSettings = onNavigateToStandaloneSettings,
                        showStandaloneSettingsEntry = shouldHideBottomNavigation,
                        appSettings = appSettings,
                        securityManager = securityManager,
                        biometricEnabled = appSettings.biometricEnabled,
                        modifier = Modifier.fillMaxSize(),
                    )
                    }
                    BottomNavItem.Passwords -> {
                        PasswordTabPane(
                            isCompactWidth = isCompactWidth,
                            wideListPaneWidth = wideListPaneWidth,
                            passwordViewModel = passwordViewModel,
                            settingsViewModel = settingsViewModel,
                            securityManager = securityManager,
                            keepassDatabases = keepassDatabases,
                            bitwardenVaults = bitwardenVaults,
                            localKeePassViewModel = localKeePassViewModel,
                            timelineViewModel = timelineViewModel,
                            groupMode = passwordGroupMode,
                            stackCardMode = stackCardMode,
                            visibleContentTypes = passwordPageVisibleContentTypes,
                            selectedContentTypes = passwordPageSelectedContentTypes,
                            onToggleContentType = { type ->
                                passwordPageSelectedContentTypes = togglePasswordPageContentType(
                                    currentTypes = passwordPageSelectedContentTypes,
                                    toggledType = type,
                                    visibleTypes = passwordPageVisibleContentTypes
                                )
                            },
                            onPasswordOpen = handlePasswordDetailOpen,
                            onNavigateToAddTotp = onNavigateToAddTotp,
                            onNavigateToBankCardDetail = onNavigateToBankCardDetail,
                            onNavigateToDocumentDetail = onNavigateToDocumentDetail,
                            onNavigateToBillingAddressDetail = handleBillingAddressOpen,
                            onNavigateToAddNote = handleNoteOpen,
                            onNavigateToNoteDetail = onNavigateToNoteDetail,
                            onNavigateToPasskeyDetail = onNavigateToPasskeyDetail,
                            onOpenHistoryPage = openHistoryPage,
                            onOpenTrashPage = openTrashPage,
                            onOpenCommonAccountTemplatesPage = onNavigateToCommonAccountTemplates,
                            onScanFidoQr = onNavigateToFidoQrScan,
                            onCloseHistoryPage = closeHistoryPage,
                            passwordHistoryPageMode = passwordHistoryPageMode,
                            passwordHistoryInitialTrashScopeKey = passwordHistoryInitialTrashScopeKey,
                            onTimelineLogSelected = handleTimelineLogOpen,
                            onSelectionModeChange = { isSelectionMode, count, onExit, onSelectAll, onFavorite, onMoveToCategory, onStack, onDelete ->
                                isPasswordSelectionMode = isSelectionMode
                                selectedPasswordCount = count
                                onExitPasswordSelection = onExit
                                onSelectAllPasswords = onSelectAll
                                onFavoriteSelectedPasswords = onFavorite
                                onMoveToCategoryPasswords = onMoveToCategory
                                onManualStackPasswords = onStack
                                onDeleteSelectedPasswords = onDelete
                            },
                            onBackToTopVisibilityChange = { visible ->
                                passwordListShowBackToTop = visible
                            },
                            scrollToTopRequestKey = passwordScrollToTopRequestKey,
                            isAddingPasswordInline = isAddingPasswordInline,
                            inlinePasswordEditorId = inlinePasswordEditorId,
                            selectedPasswordId = selectedPasswordId,
                            passwordNewItemDefaults = pendingInlinePasswordAddStorageDefaults ?: passwordNewItemDefaults,
                            onInlinePasswordEditorBack = handleInlinePasswordEditorBack,
                            onNavigateToAddWifi = onNavigateToAddWifi,
                            onNavigateToAddSshKey = onNavigateToAddSshKey,
                            pendingPasswordAuthenticatorQrResult = pendingPasswordAuthenticatorQrResult,
                            onConsumePendingPasswordAuthenticatorQrResult =
                                onConsumePendingPasswordAuthenticatorQrResult,
                            onScanPasswordAuthenticatorQrCode = onScanPasswordAuthenticatorQrCode,
                            totpViewModel = totpViewModel,
                            bankCardViewModel = bankCardViewModel,
                            noteViewModel = noteViewModel,
                            documentViewModel = documentViewModel,
                            billingAddressViewModel = billingAddressViewModel,
                            passkeyViewModel = passkeyViewModel,
                            biometricEnabled = appSettings.biometricEnabled,
                            iconCardsEnabled = appSettings.iconCardsEnabled && appSettings.passwordPageIconEnabled,
                            unmatchedIconHandlingStrategy = appSettings.unmatchedIconHandlingStrategy,
                            onClearSelectedPassword = clearSelectedPasswordPaneItem,
                            onEditPassword = handlePasswordEditOpen
                            ,
                            showStandaloneSettingsEntry = shouldHideBottomNavigation,
                            onOpenStandaloneSettings = onNavigateToStandaloneSettings
                        )
                    }
                    BottomNavItem.Authenticator -> {
                        AuthenticatorTabPane(
                            isCompactWidth = isCompactWidth,
                            wideListPaneWidth = wideListPaneWidth,
                            totpViewModel = totpViewModel,
                            passwordViewModel = passwordViewModel,
                            localKeePassViewModel = localKeePassViewModel,
                            onTotpOpen = handleTotpOpen,
                            onNavigateToQuickTotpScan = onNavigateToQuickTotpScan,
                            onSelectionModeChange = { isSelectionMode, count, onExit, onSelectAll, onMoveToCategory, onDelete ->
                                totpSelectionState.isSelectionMode = isSelectionMode
                                totpSelectionState.selectedCount = count
                                totpSelectionState.onExit = onExit
                                totpSelectionState.onSelectAll = onSelectAll
                                totpSelectionState.onMoveToCategory = onMoveToCategory
                                totpSelectionState.onDelete = onDelete
                            },
                            isAddingTotpInline = isAddingTotpInline,
                            selectedTotpId = selectedTotpId,
                            totpNewItemDefaults = pendingInlineTotpAddStorageDefaults ?: totpNewItemDefaults,
                            onInlineTotpEditorBack = handleInlineTotpEditorBack,
                            showStandaloneSettingsEntry = shouldHideBottomNavigation,
                            onOpenStandaloneSettings = onNavigateToStandaloneSettings,
                            onNavigateToPasskeys = {
                                selectedTabKey = BottomNavItem.Passkey.key
                            }
                        )
                    }
                    BottomNavItem.CardWallet -> {
                        CardWalletPane(
                            isCompactWidth = isCompactWidth,
                            wideListPaneWidth = wideListPaneWidth,
                            saveableStateHolder = cardWalletSaveableStateHolder,
                            bankCardViewModel = bankCardViewModel,
                            documentViewModel = documentViewModel,
                            billingAddressViewModel = billingAddressViewModel,
                            passwordViewModel = passwordViewModel,
                            bitwardenViewModel = bitwardenViewModel,
                            contentState = cardWalletContentState,
                            isAddingBankCardInline = isAddingBankCardInline,
                            inlineBankCardEditorId = inlineBankCardEditorId,
                            onInlineBankCardEditorBack = handleInlineBankCardEditorBack,
                            isAddingDocumentInline = isAddingDocumentInline,
                            inlineDocumentEditorId = inlineDocumentEditorId,
                            onInlineDocumentEditorBack = handleInlineDocumentEditorBack,
                            isAddingBillingAddressInline = isAddingBillingAddressInline,
                            inlineBillingAddressEditorId = inlineBillingAddressEditorId,
                            onInlineBillingAddressEditorBack = handleInlineBillingAddressEditorBack,
                            selectedBankCardId = selectedBankCardId,
                            onClearSelectedBankCard = clearSelectedBankCardPaneItem,
                            onEditBankCard = handleBankCardEditOpen,
                            selectedDocumentId = selectedDocumentId,
                            onClearSelectedDocument = clearSelectedDocumentPaneItem,
                            onEditDocument = handleDocumentEditOpen,
                            selectedBillingAddressId = selectedBillingAddressId,
                            onClearSelectedBillingAddress = clearSelectedBillingAddressPaneItem,
                            onEditBillingAddress = handleBillingAddressEditOpen,
                            initialCategoryId = pendingInlineWalletAddStorageDefaults?.categoryId,
                            initialKeePassDatabaseId = pendingInlineWalletAddStorageDefaults?.keepassDatabaseId,
                            initialKeePassGroupPath = pendingInlineWalletAddStorageDefaults?.keepassGroupPath,
                            initialBitwardenVaultId = pendingInlineWalletAddStorageDefaults?.bitwardenVaultId,
                            initialBitwardenFolderId = pendingInlineWalletAddStorageDefaults?.bitwardenFolderId,
                            showStandaloneSettingsEntry = shouldHideBottomNavigation,
                            onOpenStandaloneSettings = onNavigateToStandaloneSettings
                        )
                    }
                    BottomNavItem.Generator -> {
                        GeneratorPane(
                            isCompactWidth = isCompactWidth,
                            wideListPaneWidth = wideListPaneWidth,
                            generatorViewModel = generatorViewModel,
                            passwordViewModel = passwordViewModel,
                            externalRefreshRequestKey = generatorRefreshRequestKey,
                            onRefreshRequestConsumed = { generatorRefreshRequestKey = 0 },
                            selectedGenerator = selectedGeneratorType,
                            generatedValue = currentGeneratorResult,
                            showStandaloneSettingsEntry = shouldHideBottomNavigation,
                            onOpenStandaloneSettings = onNavigateToStandaloneSettings
                        )
                    }
                    BottomNavItem.Notes -> {
                        NotePane(
                            isCompactWidth = isCompactWidth,
                            wideListPaneWidth = wideListPaneWidth,
                            noteViewModel = noteViewModel,
                            settingsViewModel = settingsViewModel,
                            securityManager = securityManager,
                            passwordViewModel = passwordViewModel,
                            onNavigateToAddNote = handleNoteOpen,
                            onNavigateToSearchedNote = onNavigateToSearchedNote,
                            onSelectionModeChange = { isSelectionMode ->
                                isNoteSelectionMode = isSelectionMode
                            },
                            isAddingNoteInline = isAddingNoteInline,
                            inlineNoteEditorId = inlineNoteEditorId,
                            onInlineNoteEditorBack = handleInlineNoteEditorBack,
                            initialCategoryId = pendingInlineNoteAddStorageDefaults?.categoryId,
                            initialKeePassDatabaseId = pendingInlineNoteAddStorageDefaults?.keepassDatabaseId,
                            initialKeePassGroupPath = pendingInlineNoteAddStorageDefaults?.keepassGroupPath,
                            initialBitwardenVaultId = pendingInlineNoteAddStorageDefaults?.bitwardenVaultId,
                            initialBitwardenFolderId = pendingInlineNoteAddStorageDefaults?.bitwardenFolderId,
                            showStandaloneSettingsEntry = shouldHideBottomNavigation,
                            onOpenStandaloneSettings = onNavigateToStandaloneSettings
                        )
                    }
                    BottomNavItem.Passkey -> {
                        PasskeyPane(
                            isCompactWidth = isCompactWidth,
                            wideListPaneWidth = wideListPaneWidth,
                            passkeyViewModel = passkeyViewModel,
                            passwordViewModel = passwordViewModel,
                            onNavigateToPasswordDetail = onNavigateToPasswordDetail,
                            onNavigateToPasskeyDetail = onNavigateToPasskeyDetail,
                            onPasskeyOpen = handlePasskeyOpen,
                            selectedPasskey = selectedPasskey,
                            passkeyTotalCount = passkeyTotalCount,
                            passkeyBoundCount = passkeyBoundCount,
                            resolvePasswordTitle = { passwordId -> passwordById[passwordId]?.title },
                            onOpenPasswordDetail = handlePasswordDetailOpen,
                            onUnbindPasskey = handlePasskeyUnbind,
                            onDeletePasskey = { passkey -> pendingPasskeyDelete = passkey },
                            showStandaloneSettingsEntry = shouldHideBottomNavigation,
                            onOpenStandaloneSettings = onNavigateToStandaloneSettings,
                            onNavigateToAuthenticator = {
                                selectedTabKey = BottomNavItem.Authenticator.key
                            }
                        )
                    }
                    BottomNavItem.Send -> {
                        SendPane(
                            isCompactWidth = isCompactWidth,
                            wideListPaneWidth = wideListPaneWidth,
                            bitwardenViewModel = bitwardenViewModel,
                            sendState = sendState,
                            selectedSend = selectedSend,
                            isAddingSendInline = isAddingSendInline,
                            onSendClick = handleSendOpen,
                            onInlineSendEditorBack = handleInlineSendEditorBack,
                            onCreateSend = { vaultId, title, text, notes, password, maxAccessCount, hideEmail, hiddenText, expireInDays ->
                                bitwardenViewModel.createTextSend(
                                    vaultId = vaultId,
                                    title = title,
                                    text = text,
                                    notes = notes,
                                    password = password,
                                    maxAccessCount = maxAccessCount,
                                    hideEmail = hideEmail,
                                    hiddenText = hiddenText,
                                    expireInDays = expireInDays
                                )
                            },
                            onCreateFileSend = { vaultId, title, fileUri, fileName, notes, password, maxAccessCount, hideEmail, expireInDays ->
                                bitwardenViewModel.createFileSend(
                                    vaultId = vaultId,
                                    title = title,
                                    fileUri = fileUri,
                                    fileName = fileName,
                                    notes = notes,
                                    password = password,
                                    maxAccessCount = maxAccessCount,
                                    hideEmail = hideEmail,
                                    expireInDays = expireInDays
                                )
                            },
                            onBitwardenEvent = handleSendBitwardenEvent,
                            showStandaloneSettingsEntry = shouldHideBottomNavigation,
                            onOpenStandaloneSettings = onNavigateToStandaloneSettings
                        )
                    }
                    BottomNavItem.Settings -> {
                        SettingsTabContent(
                            viewModel = settingsViewModel,
                            onResetPassword = onNavigateToChangePassword,
                            onSecurityQuestions = onNavigateToSecurityQuestion,
                            onNavigateToMasterPasswordLocking = onNavigateToMasterPasswordLocking,
                            onNavigateToSyncBackup = onNavigateToSyncBackup,
                            onNavigateToAutofill = onNavigateToAutofill,
                            onNavigateToPasskeySettings = onNavigateToPasskeySettings,
                            onSecurityAnalysis = onSecurityAnalysis,
                            onNavigateToDeveloperSettings = onNavigateToDeveloperSettings,
                            onNavigateToPermissionManagement = onNavigateToPermissionManagement,
                            onNavigateToExtensions = onNavigateToExtensions,
                            onNavigateToPageCustomization = onNavigateToPageCustomization,
                            onNavigateToThemeAndColorScheme = onNavigateToThemeAndColorScheme,
                            isCompactWidth = isCompactWidth,
                            wideListPaneWidth = wideListPaneWidth,
                            onClearAllData = onClearAllData
                        )
                    }
                }
                }

                MainScreenSelectionBars(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 16.dp, bottom = 20.dp),
                    currentTab = currentTab,
                    cardWalletSubTab = cardWalletSubTab,
                    isPasswordSelectionMode = isPasswordSelectionMode,
                    selectedPasswordCount = selectedPasswordCount,
                    onExitPasswordSelection = onExitPasswordSelection,
                    onSelectAllPasswords = onSelectAllPasswords,
                    onFavoriteSelectedPasswords = onFavoriteSelectedPasswords,
                    onMoveToCategoryPasswords = onMoveToCategoryPasswords,
                    onManualStackPasswords = onManualStackPasswords,
                    onDeleteSelectedPasswords = onDeleteSelectedPasswords,
                    isTotpSelectionMode = totpSelectionState.isSelectionMode,
                    selectedTotpCount = totpSelectionState.selectedCount,
                    onExitTotpSelection = totpSelectionState.onExit,
                    onSelectAllTotp = totpSelectionState.onSelectAll,
                    onMoveToCategoryTotp = totpSelectionState.onMoveToCategory,
                    onDeleteSelectedTotp = totpSelectionState.onDelete,
                    isBankCardSelectionMode = bankCardSelectionState.isSelectionMode,
                    selectedBankCardCount = bankCardSelectionState.selectedCount,
                    onExitBankCardSelection = bankCardSelectionState.onExit,
                    onSelectAllBankCards = bankCardSelectionState.onSelectAll,
                    onFavoriteBankCards = bankCardSelectionState.onFavorite,
                    onMoveToCategoryBankCards = bankCardSelectionState.onMoveToCategory,
                    onDeleteSelectedBankCards = bankCardSelectionState.onDelete,
                    isDocumentSelectionMode = documentSelectionState.isSelectionMode,
                    selectedDocumentCount = documentSelectionState.selectedCount,
                    onExitDocumentSelection = documentSelectionState.onExit,
                    onSelectAllDocuments = documentSelectionState.onSelectAll,
                    onMoveToCategoryDocuments = documentSelectionState.onMoveToCategory,
                    onDeleteSelectedDocuments = documentSelectionState.onDelete
                )
                }
            }
        }
    }
    }
    }

    val prepareTotpAddStorageDefaults: (Long?, Long?, String?,  Long?, String?) -> Unit = { categoryId, keepassDatabaseId, keepassGroupPath,  bitwardenVaultId, bitwardenFolderId ->
        if (isCompactWidth) {
            pendingInlineTotpAddStorageDefaults = null
            onPrepareTotpAddStorageDefaults(categoryId, keepassDatabaseId, keepassGroupPath,  bitwardenVaultId, bitwardenFolderId)
        } else {
            pendingInlineTotpAddStorageDefaults = NewItemStorageDefaults(
                categoryId = categoryId,
                keepassDatabaseId = keepassDatabaseId,
                keepassGroupPath = keepassGroupPath,

                bitwardenVaultId = bitwardenVaultId,
                bitwardenFolderId = bitwardenFolderId
            ).takeIf { it.hasAnyValue() }
        }
    }
    val preparePasswordAddStorageDefaults: (Long?, Long?, String?,  Long?, String?) -> Unit = { categoryId, keepassDatabaseId, keepassGroupPath,  bitwardenVaultId, bitwardenFolderId ->
        if (isCompactWidth) {
            pendingInlinePasswordAddStorageDefaults = null
            onPreparePasswordAddStorageDefaults(categoryId, keepassDatabaseId, keepassGroupPath,  bitwardenVaultId, bitwardenFolderId)
        } else {
            pendingInlinePasswordAddStorageDefaults = NewItemStorageDefaults(
                categoryId = categoryId,
                keepassDatabaseId = keepassDatabaseId,
                keepassGroupPath = keepassGroupPath,

                bitwardenVaultId = bitwardenVaultId,
                bitwardenFolderId = bitwardenFolderId
            ).takeIf { it.hasAnyValue() }
        }
    }
    val prepareNoteAddStorageDefaults: (Long?, Long?, String?,  Long?, String?) -> Unit = { categoryId, keepassDatabaseId, keepassGroupPath,  bitwardenVaultId, bitwardenFolderId ->
        if (isCompactWidth) {
            pendingInlineNoteAddStorageDefaults = null
            onPrepareNoteAddStorageDefaults(categoryId, keepassDatabaseId, keepassGroupPath,  bitwardenVaultId, bitwardenFolderId)
        } else {
            pendingInlineNoteAddStorageDefaults = NewItemStorageDefaults(
                categoryId = categoryId,
                keepassDatabaseId = keepassDatabaseId,
                keepassGroupPath = keepassGroupPath,

                bitwardenVaultId = bitwardenVaultId,
                bitwardenFolderId = bitwardenFolderId
            ).takeIf { it.hasAnyValue() }
        }
    }
    val prepareWalletAddStorageDefaults: (Long?, Long?, String?,  Long?, String?) -> Unit = { categoryId, keepassDatabaseId, keepassGroupPath,  bitwardenVaultId, bitwardenFolderId ->
        if (isCompactWidth) {
            pendingInlineWalletAddStorageDefaults = null
            onPrepareWalletAddStorageDefaults(categoryId, keepassDatabaseId, keepassGroupPath,  bitwardenVaultId, bitwardenFolderId)
        } else {
            pendingInlineWalletAddStorageDefaults = NewItemStorageDefaults(
                categoryId = categoryId,
                keepassDatabaseId = keepassDatabaseId,
                keepassGroupPath = keepassGroupPath,

                bitwardenVaultId = bitwardenVaultId,
                bitwardenFolderId = bitwardenFolderId
            ).takeIf { it.hasAnyValue() }
        }
    }

    val passwordFastScrollStripProgress by passwordViewModel.fastScrollProgress.collectAsState()
    MainScreenFabOverlay(
        currentTab = currentTab,
        isCompactWidth = isCompactWidth,
        shouldHideBottomNavigation = shouldHideBottomNavigation,
        wideFabHostWidth = wideFabHostWidth,
        appSettings = appSettings,
        passwordHistoryPageMode = passwordHistoryPageMode,
        isAnySelectionMode = isAnySelectionMode,
        isAddingPasswordInline = isAddingPasswordInline,
        inlinePasswordEditorId = inlinePasswordEditorId,
        isAddingTotpInline = isAddingTotpInline,
        selectedTotpId = selectedTotpId,
        isAddingBankCardInline = isAddingBankCardInline,
        inlineBankCardEditorId = inlineBankCardEditorId,
        selectedBankCardId = selectedBankCardId,
        isAddingDocumentInline = isAddingDocumentInline,
        inlineDocumentEditorId = inlineDocumentEditorId,
        selectedDocumentId = selectedDocumentId,
        isAddingBillingAddressInline = isAddingBillingAddressInline,
        inlineBillingAddressEditorId = inlineBillingAddressEditorId,
        selectedBillingAddressId = selectedBillingAddressId,
        isAddingNoteInline = isAddingNoteInline,
        inlineNoteEditorId = inlineNoteEditorId,
        isAddingSendInline = isAddingSendInline,
        // 中间的「添加」已接管原 FAB 的职责，这里不再显示悬浮 FAB
        // （Overlay 仍保留：回到顶部 / 快捷访问 / 快速滚动条 等功能）。
        isFabVisible = false,
        isFabExpanded = isFabExpanded,
        onFabExpandedChange = { expanded -> isFabExpanded = expanded },
        fastScrollStripVisible = isFastScrollStripVisible,
        onFastScrollStripVisibleChange = { visible -> isFastScrollStripVisible = visible },
        fastScrollStripProgress = if (currentTab == BottomNavItem.VaultV2) {
            vaultV2PaneState.fastScrollProgress
        } else {
            passwordFastScrollStripProgress
        },
        onFastScrollProgressChange = if (currentTab == BottomNavItem.VaultV2) {
            vaultV2PaneState::requestFastScroll
        } else {
            passwordViewModel::requestFastScroll
        },
        fastScrollIndicatorLabel = if (currentTab == BottomNavItem.VaultV2) {
            vaultV2PaneState.fastScrollIndicatorLabel
        } else {
            null
        },
        passwordListShowBackToTop = if (currentTab == BottomNavItem.VaultV2) {
            vaultV2PaneState.showBackToTop
        } else {
            passwordListShowBackToTop
        },
        onBackToTop = {
            if (currentTab == BottomNavItem.VaultV2) {
                vaultV2PaneState.requestScrollToTop()
            } else {
                passwordScrollToTopRequestKey++
            }
        },
        quickAccessEnabled =
            (currentTab == BottomNavItem.Passwords || currentTab == BottomNavItem.VaultV2) &&
                appSettings.passwordListQuickAccessEnabled,
        showPasswordQuickAccessSheet = showPasswordQuickAccessSheet,
        onShowPasswordQuickAccessSheetChange = { showPasswordQuickAccessSheet = it },
        recentOpenedPasswords = recentOpenedPasswords,
        frequentOpenedPasswords = frequentOpenedPasswords,
        onOpenPasswordFromQuickAccess = handlePasswordDetailOpen,
        onNavigateToPasskey = {
            selectedTabKey = BottomNavItem.Passkey.key
        },
        cardWalletSubTab = cardWalletSubTab,
        onPasswordAddOpen = handlePasswordAddOpen,
        onTotpAddOpen = handleTotpAddOpen,
        onBankCardAddOpen = handleBankCardAddOpen,
        onWalletAddOpen = handleWalletAddOpen,
        onNavigateToWalletAdd = onNavigateToWalletAdd,
        passwordPageAggregateEnabled =
            if (currentTab == BottomNavItem.VaultV2) true else appSettings.passwordPageAggregateEnabled,
        passwordNewItemDefaults = passwordNewItemDefaults,
        onPreparePasswordAddStorageDefaults = preparePasswordAddStorageDefaults,
        onPrepareTotpAddStorageDefaults = prepareTotpAddStorageDefaults,
        onPrepareNoteAddStorageDefaults = prepareNoteAddStorageDefaults,
        onPrepareWalletAddStorageDefaults = prepareWalletAddStorageDefaults,
        onNoteAddOpen = { handleNoteOpen(null) },
        onSendAddOpen = handleSendAddOpen,
        onGeneratorRefresh = { generatorRefreshRequestKey++ },
        passwordViewModel = passwordViewModel,
        totpViewModel = totpViewModel,
        bankCardViewModel = bankCardViewModel,
        localKeePassViewModel = localKeePassViewModel,
        totpNewItemDefaults = totpNewItemDefaults,
        onNavigateToQuickTotpScan = onNavigateToQuickTotpScan,
        pendingPasswordAuthenticatorQrResult = pendingPasswordAuthenticatorQrResult,
        onConsumePendingPasswordAuthenticatorQrResult =
            onConsumePendingPasswordAuthenticatorQrResult,
        onScanPasswordAuthenticatorQrCode = onScanPasswordAuthenticatorQrCode,
        walletUnifiedAddType = walletUnifiedAddType,
        onWalletUnifiedAddTypeChange = { walletUnifiedAddType = it },
        documentViewModel = documentViewModel,
        walletAddSaveableStateHolder = walletAddSaveableStateHolder,
        noteViewModel = noteViewModel,
        sendState = sendState,
        bitwardenViewModel = bitwardenViewModel,
        onNavigateToAddWifi = onNavigateToAddWifi,
        onNavigateToAddSshKey = onNavigateToAddSshKey
    )

    val navBarInsetBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val compactBottomOffset = if (useDraggableNav) {
        92.dp + navBarInsetBottom
    } else {
        88.dp + navBarInsetBottom
    }
    val hintModifier = if (isCompactWidth) {
        Modifier
            .align(Alignment.BottomStart)
            .padding(start = 16.dp, bottom = compactBottomOffset + 28.dp)
            .zIndex(4f)
    } else {
        Modifier
            .align(Alignment.BottomStart)
            .padding(start = wideFabHostWidth + 16.dp, bottom = 24.dp + 12.dp)
            .zIndex(4f)
    }
    val statusHintContainerColor = when (currentTab) {
        BottomNavItem.CardWallet -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val statusHintTextColor = when (currentTab) {
        BottomNavItem.CardWallet -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    Column(
        modifier = hintModifier.animateContentSize(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SyncMiniHintBubble(
            visible = syncHintVisible,
            text = stringResource(bottomStatusUiState?.messageRes ?: R.string.sync_status_syncing_short),
            containerColor = statusHintContainerColor,
            textColor = statusHintTextColor
        )

        activeMiniHints.forEach { hint ->
            val hintVisible = hint.id !in dismissingHintIds
            CustomMiniHintBubble(
                visible = hintVisible,
                hint = hint,
                containerColor = statusHintContainerColor,
                textColor = statusHintTextColor
            )
        }
    }
    } // End Outer Box
    }

    RenderMainSurface()

    if (pendingPasskeyDelete != null) {
        val passkey = pendingPasskeyDelete!!
        AlertDialog(
            onDismissRequest = { pendingPasskeyDelete = null },
            title = { Text(stringResource(R.string.passkey_delete_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.passkey_delete_message,
                        passkey.rpName.ifBlank { passkey.rpId },
                        passkey.userName.ifBlank { "-" }
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = confirmPasskeyDelete) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingPasskeyDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

}

@Composable
private fun MainScreenTabResetEffects(
    currentTab: BottomNavItem,
    isCompactWidth: Boolean,
    cardWalletSubTab: CardWalletTab,
    passwordHistoryPageMode: PasswordHistoryPageMode,
    onResetPasswordPane: () -> Unit,
    onHideBackToTop: () -> Unit,
    onResetTotpPane: () -> Unit,
    onResetCardWalletPaneAll: () -> Unit,
    onResetCardWalletDocumentPane: () -> Unit,
    onResetCardWalletBankCardPane: () -> Unit,
    onResetCardWalletBillingAddressPane: () -> Unit,
    onSyncWalletUnifiedAddType: (CardWalletTab) -> Unit,
    onResetNotePane: () -> Unit,
    onResetPasskeyPane: () -> Unit,
    onResetSendPane: () -> Unit,
    onResetTimelineSelection: () -> Unit,
) {
    // Each effect owns one tab domain reset. Keep them split to avoid hidden coupling.
    LaunchedEffect(currentTab.key, isCompactWidth) {
        if (isCompactWidth || currentTab != BottomNavItem.Passwords) {
            onResetPasswordPane()
        }
    }
    LaunchedEffect(currentTab.key) {
        if (currentTab != BottomNavItem.Passwords && currentTab != BottomNavItem.VaultV2) {
            onHideBackToTop()
        }
    }
    LaunchedEffect(currentTab.key, isCompactWidth) {
        if (
            isCompactWidth ||
            (currentTab != BottomNavItem.Authenticator && currentTab != BottomNavItem.Passkey)
        ) {
            onResetTotpPane()
        }
    }
    LaunchedEffect(currentTab.key, isCompactWidth, cardWalletSubTab) {
        if (isCompactWidth || currentTab != BottomNavItem.CardWallet) {
            onResetCardWalletPaneAll()
        } else {
            when (cardWalletSubTab) {
                CardWalletTab.BANK_CARDS -> {
                    onResetCardWalletDocumentPane()
                    onResetCardWalletBillingAddressPane()
                }
                CardWalletTab.DOCUMENTS -> {
                    onResetCardWalletBankCardPane()
                    onResetCardWalletBillingAddressPane()
                }
                CardWalletTab.BILLING_ADDRESSES -> {
                    onResetCardWalletBankCardPane()
                    onResetCardWalletDocumentPane()
                }
                CardWalletTab.ALL -> Unit
            }
        }
    }
    LaunchedEffect(cardWalletSubTab) {
        if (
            cardWalletSubTab == CardWalletTab.BANK_CARDS ||
            cardWalletSubTab == CardWalletTab.DOCUMENTS ||
            cardWalletSubTab == CardWalletTab.BILLING_ADDRESSES
        ) {
            onSyncWalletUnifiedAddType(cardWalletSubTab)
        }
    }
    LaunchedEffect(currentTab.key, isCompactWidth) {
        if (isCompactWidth || currentTab != BottomNavItem.Notes) {
            onResetNotePane()
        }
    }
    LaunchedEffect(currentTab.key, isCompactWidth) {
        if (
            isCompactWidth ||
            (currentTab != BottomNavItem.Authenticator && currentTab != BottomNavItem.Passkey)
        ) {
            onResetPasskeyPane()
        }
    }
    LaunchedEffect(currentTab.key, isCompactWidth) {
        if (isCompactWidth || currentTab != BottomNavItem.Send) {
            onResetSendPane()
        }
    }
    LaunchedEffect(currentTab.key, isCompactWidth, passwordHistoryPageMode) {
        if (isCompactWidth || currentTab != BottomNavItem.Passwords || !passwordHistoryPageMode.isVisible) {
            onResetTimelineSelection()
        }
    }
}
