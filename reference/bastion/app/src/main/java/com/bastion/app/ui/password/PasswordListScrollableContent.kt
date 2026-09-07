package com.bastion.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bastion.app.R
import com.bastion.app.data.AppSettings
import com.bastion.app.data.PasswordListQuickFilterItem
import com.bastion.app.data.PasswordListQuickFolderStyle
import com.bastion.app.data.PasswordPageContentType
import com.bastion.app.viewmodel.CategoryFilter

internal const val PASSWORD_LIST_QUICK_FILTERS_KEY = "quick_filters"
internal const val PASSWORD_LIST_QUICK_FOLDER_SHORTCUTS_KEY = "quick_folder_shortcuts"
private const val PASSWORD_LIST_EMPTY_STATE_WITH_HEADERS_KEY = "empty_state_with_quick_headers"
private const val PASSWORD_LIST_BOTTOM_SPACER_KEY = "password_list_bottom_spacer"

@Composable
internal fun PasswordListScrollableContent(
    listState: LazyListState,
    modifier: Modifier,
    isPasswordPageListModelReady: Boolean,
    hasVisibleQuickFilters: Boolean,
    quickFiltersExpanded: Boolean = true,
    hasVisibleCategoryQuickFilters: Boolean,
    appSettings: AppSettings,
    configuredQuickFilterItems: List<PasswordListQuickFilterItem>,
    aggregateUiState: PasswordListAggregateUiState,
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
    // 通行秘钥 chip：点击进入通行秘钥页，而非在密码列表里过滤
    // （筛选只能得到「绑定了 passkey 的密码条目」，得不到 passkey 条目本身）。
    onNavigateToPasskeys: (() -> Unit)? = null,
    categoryQuickFilterShortcuts: List<PasswordQuickFolderShortcut>,
    quickFolderShortcuts: List<PasswordQuickFolderShortcut>,
    quickFolderStyle: PasswordListQuickFolderStyle,
    currentFilter: CategoryFilter,
    onNavigateFilter: (CategoryFilter) -> Unit,
    hasVisibleListItems: Boolean,
    showEmptyState: Boolean,
    searchQuery: String,
    emptyStateMessage: PasswordListEmptyStateMessage,
    // 顶部留白：跟随顶部 Bar 的当前高度联动（展开 88dp / 收起 48dp）。
    // Bar 透明覆盖在列表之上，留白必须 ≥ Bar 当前高度，否则首屏首条内容会被 Bar 吞掉。
    listTopPadding: Dp = 0.dp,
    renderPasswordRows: LazyListScope.() -> Unit
) {
    // 滚动掉帧采样：只在确实掉帧时输出一行摘要，正常滚动静默（见 ScrollJankMonitor）
    com.bastion.app.ui.perf.ScrollJankReporter(listState = listState, label = "passwords")
    val categoryQuickFilterScrollState = rememberScrollState()

    LazyColumn(
        state = listState,
        modifier = modifier,
        // bottom 预留悬浮胶囊高度（68dp 栏高 + 6/8dp 外边距 ≈ 82dp，取 96dp 留余量），
        // 保证最后一条能滚出胶囊区域。
        // top 0：Bar 自带背景并透明覆盖在列表之上，列表内容可从 Bar 底下穿过
        // （若 top 设成 Bar 高度，卡片永远滚不进 Bar 区域，Bar 区就永远是不透明底色）。
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = listTopPadding,
            bottom = 96.dp
        )
    ) {
        if (hasVisibleQuickFilters || hasVisibleCategoryQuickFilters) {
            item(key = PASSWORD_LIST_QUICK_FILTERS_KEY) {
                val filterSectionSpacing =
                    if (hasVisibleQuickFilters && hasVisibleCategoryQuickFilters) 0.dp else 0.dp
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 0.dp, bottom = 4.dp)
                ) {
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(filterSectionSpacing)
                    ) {
                        // 快捷筛选横排的显示由标题点击控制（内建收纳：默认收起，点标题展开/收起）
                        if (hasVisibleQuickFilters && quickFiltersExpanded) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                configuredQuickFilterItems.forEach { item ->
                                    if (shouldShowQuickFilterItem(item, aggregateUiState.visibleContentTypes)) {
                                        PasswordQuickFilterChipItem(
                                            item = item,
                                            categoryEditMode = false,
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
                                            aggregateSelectedTypes = aggregateUiState.selectedContentTypes,
                                            aggregateVisibleTypes = aggregateUiState.visibleContentTypes,
                                            onToggleAggregateType = onToggleAggregateType,
                                            onNavigateToPasskeys = onNavigateToPasskeys
                                        )
                                    }
                                }
                                if (wifiQuickFilterVisible) {
                                    PasswordQuickFilterChip(
                                        selected = quickFilterWifi,
                                        onClick = { onQuickFilterWifiChange(!quickFilterWifi) },
                                        label = stringResource(R.string.entry_type_wifi),
                                        leadingIcon = Icons.Default.Wifi
                                    )
                                }
                                if (sshKeyQuickFilterVisible) {
                                    PasswordQuickFilterChip(
                                        selected = quickFilterSshKey,
                                        onClick = { onQuickFilterSshKeyChange(!quickFilterSshKey) },
                                        label = stringResource(R.string.password_list_quick_filter_ssh_key),
                                        leadingIcon = Icons.Default.Key
                                    )
                                }
                                if (barcodeQuickFilterVisible) {
                                    PasswordQuickFilterChip(
                                        selected = quickFilterBarcode,
                                        onClick = { onQuickFilterBarcodeChange(!quickFilterBarcode) },
                                        label = stringResource(R.string.password_list_quick_filter_barcode),
                                        leadingIcon = Icons.Default.QrCode2
                                    )
                                }
                            }
                        }

                        if (hasVisibleCategoryQuickFilters) {
                            PasswordQuickFolderChipRow(
                                params = PasswordQuickFolderChipRowParams(
                                    currentFilter = currentFilter,
                                    quickFolderShortcuts = categoryQuickFilterShortcuts,
                                    onSelectFilter = onNavigateFilter
                                ),
                                scrollState = categoryQuickFilterScrollState
                            )
                        }
                    }
                }
            }
        }

        if (quickFolderShortcuts.isNotEmpty()) {
            val quickFolderUseM3CardStyle =
                quickFolderStyle == PasswordListQuickFolderStyle.M3_CARD
            item(key = PASSWORD_LIST_QUICK_FOLDER_SHORTCUTS_KEY) {
                PasswordQuickFolderShortcutsSection(
                    shortcuts = quickFolderShortcuts,
                    currentFilter = currentFilter,
                    useM3CardStyle = quickFolderUseM3CardStyle,
                    onNavigate = onNavigateFilter
                )
            }
        }

        if (showEmptyState) {
            item(key = PASSWORD_LIST_EMPTY_STATE_WITH_HEADERS_KEY) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 84.dp, bottom = 24.dp),
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
            }
        } else {
            renderPasswordRows()
        }

        item(key = PASSWORD_LIST_BOTTOM_SPACER_KEY) {
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}
