package com.bastion.app.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.bastion.app.R
import com.bastion.app.data.AppSettings
import com.bastion.app.data.PasswordListQuickFilterItem
import com.bastion.app.data.PasswordListQuickFolderStyle
import com.bastion.app.data.PasswordPageContentType
import com.bastion.app.ui.common.pull.PullActionStateHandle
import com.bastion.app.ui.components.PullActionVisualState
import com.bastion.app.ui.password.PasswordBatchDeleteGlobalProgressState
import com.bastion.app.ui.password.PasswordBatchTransferGlobalProgressState
import com.bastion.app.viewmodel.CategoryFilter

@Composable
internal fun PasswordListMainPane(
    canCollapseExpandedGroups: Boolean,
    outsideTapInteractionSource: MutableInteractionSource,
    onCollapseExpandedGroups: () -> Unit,
    isBitwardenDatabaseView: Boolean,
    pullAction: PullActionStateHandle,
    triggerDistance: Float,
    syncTriggerDistance: Float,
    density: Density,
    showPinnedQuickFolderPathBanner: Boolean,
    quickFolderBreadcrumbs: List<PasswordQuickFolderBreadcrumb>,
    quickStatusTransferState: PasswordBatchTransferGlobalProgressState? = null,
    onQuickStatusTransferClick: (() -> Unit)? = null,
    quickStatusDeleteState: PasswordBatchDeleteGlobalProgressState? = null,
    onQuickStatusDeleteClick: (() -> Unit)? = null,
    quickStatusBitwardenSyncState: QuickStatusBitwardenSyncState? = null,
    quickStatusKeePassSyncState: QuickStatusKeePassSyncState? = null,
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
    quickFilterSshKey: Boolean = false,
    onQuickFilterSshKeyChange: (Boolean) -> Unit = {},
    sshKeyQuickFilterVisible: Boolean = false,
    quickFilterBarcode: Boolean = false,
    onQuickFilterBarcodeChange: (Boolean) -> Unit = {},
    barcodeQuickFilterVisible: Boolean = false,
    onToggleAggregateType: ((PasswordPageContentType) -> Unit)?,
    onNavigateToPasskeys: (() -> Unit)? = null,
    categoryQuickFilterShortcuts: List<PasswordQuickFolderShortcut>,
    quickFolderShortcuts: List<PasswordQuickFolderShortcut>,
    quickFolderStyle: PasswordListQuickFolderStyle,
    // 顶部留白：跟随顶部 Bar 高度联动（展开 88dp / 收起 48dp），保证首条内容不被 Bar 遮挡
    listTopPadding: Dp = 0.dp,
    renderPasswordRows: LazyListScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                enabled = canCollapseExpandedGroups,
                interactionSource = outsideTapInteractionSource,
                indication = null
            ) {
                onCollapseExpandedGroups()
            }
    ) {
        val contentPullOffset = if (isBitwardenDatabaseView) {
            (pullAction.currentOffset * 0.28f).toInt()
        } else {
            pullAction.currentOffset.toInt()
        }

        Column(modifier = Modifier.fillMaxSize()) {
            if (showPinnedQuickFolderPathBanner) {
                PasswordQuickFolderBreadcrumbBanner(
                    breadcrumbs = quickFolderBreadcrumbs,
                    currentFilter = currentFilter,
                    onNavigate = onNavigateFilter,
                    transferState = quickStatusTransferState,
                    onTransferStatusClick = onQuickStatusTransferClick,
                    deleteState = quickStatusDeleteState,
                    onDeleteStatusClick = onQuickStatusDeleteClick,
                    bitwardenSyncState = quickStatusBitwardenSyncState,
                    keePassSyncState = quickStatusKeePassSyncState
                )
            }

            if (shouldGateInitialPasswordFirstFrame && searchQuery.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .offset { IntOffset(0, contentPullOffset) },
                    contentAlignment = Alignment.Center
                ) {
                    PasswordListInitialLoadingIndicator()
                }
            } else if (showEmptyState && !hasScrollableHeaderContent) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .offset { IntOffset(0, contentPullOffset) }
                        .pointerInput(isBitwardenDatabaseView) {
                            detectDragGesturesAfterLongPress(
                                onDrag = { _, _ -> }
                            )
                        }
                        .pointerInput(isBitwardenDatabaseView) {
                            detectVerticalDragGestures(
                                onVerticalDrag = { _, dragAmount ->
                                    pullAction.onVerticalDrag(dragAmount)
                                },
                                onDragEnd = { pullAction.onDragEnd() },
                                onDragCancel = { pullAction.onDragCancel() }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    PasswordListEmptyState(
                        message = if (aggregateUiState.hasActiveContentTypeFilter) {
                            PasswordListEmptyStateMessage(titleRes = R.string.no_results)
                        } else {
                            emptyStateMessage
                        }
                    )
                }
            } else {
                PasswordListScrollableContent(
                    listState = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .offset { IntOffset(0, contentPullOffset) }
                        .nestedScroll(pullAction.nestedScrollConnection),
                    isPasswordPageListModelReady = isPasswordPageListModelReady,
                    hasVisibleQuickFilters = hasVisibleQuickFilters,
                    quickFiltersExpanded = quickFiltersExpanded,
                    hasVisibleCategoryQuickFilters = hasVisibleCategoryQuickFilters,
                    appSettings = appSettings,
                    configuredQuickFilterItems = configuredQuickFilterItems,
                    aggregateUiState = aggregateUiState,
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
                    currentFilter = currentFilter,
                    onNavigateFilter = onNavigateFilter,
                    hasVisibleListItems = hasVisibleListItems,
                    showEmptyState = showEmptyState,
                    searchQuery = searchQuery,
                    emptyStateMessage = emptyStateMessage,
                    listTopPadding = listTopPadding,
                    renderPasswordRows = renderPasswordRows
                )
            }
        }
    }
}
