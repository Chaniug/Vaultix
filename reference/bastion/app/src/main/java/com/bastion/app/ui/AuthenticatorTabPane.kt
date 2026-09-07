package com.bastion.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bastion.app.ui.common.layout.DetailPane
import com.bastion.app.ui.common.layout.ListPane
import com.bastion.app.ui.screens.AddEditTotpScreen
import com.bastion.app.viewmodel.PasswordViewModel

@Composable
internal fun AuthenticatorTabPane(
    isCompactWidth: Boolean,
    wideListPaneWidth: Dp,
    totpViewModel: com.bastion.app.viewmodel.TotpViewModel,
    passwordViewModel: PasswordViewModel,
    localKeePassViewModel: com.bastion.app.viewmodel.LocalKeePassViewModel,
    onTotpOpen: (Long) -> Unit,
    onNavigateToQuickTotpScan: () -> Unit,
    onSelectionModeChange: (
        Boolean,
        Int,
        () -> Unit,
        () -> Unit,
        () -> Unit,
        () -> Unit
    ) -> Unit,
    isAddingTotpInline: Boolean,
    selectedTotpId: Long?,
    totpNewItemDefaults: NewItemStorageDefaults,
    onInlineTotpEditorBack: () -> Unit,
    showStandaloneSettingsEntry: Boolean,
    onOpenStandaloneSettings: () -> Unit,
    onNavigateToPasskeys: () -> Unit = {}
) {
    val listPaneContent: @Composable ColumnScope.() -> Unit = {
        TotpListContent(
            viewModel = totpViewModel,
            passwordViewModel = passwordViewModel,
            onTotpClick = onTotpOpen,
            onDeleteTotp = { totp ->
                totpViewModel.deleteTotpItem(totp)
            },
            onQuickScanTotp = onNavigateToQuickTotpScan,
            onSelectionModeChange = onSelectionModeChange,
            showStandaloneSettingsEntry = showStandaloneSettingsEntry,
            onOpenStandaloneSettings = onOpenStandaloneSettings,
            onNavigateToPasskeys = onNavigateToPasskeys
        )
    }

    if (isCompactWidth) {
        ListPane(
            modifier = Modifier.fillMaxSize(),
            content = listPaneContent
        )
    } else {
        val totpItems by totpViewModel.totpItems.collectAsState()
        val selectedTotpItem = remember(selectedTotpId, totpItems) {
            selectedTotpId?.let { selectedId ->
                totpItems.firstOrNull { it.id == selectedId }
            }
        }
        val parsedTotpItems by totpViewModel.parsedTotpItems.collectAsState()
        val selectedTotpData = remember(selectedTotpId, parsedTotpItems) {
            parsedTotpItems.firstOrNull { it.item.id == selectedTotpId }?.totpData
        }
        val totpCategories by totpViewModel.categories.collectAsState()

        Row(modifier = Modifier.fillMaxSize()) {
            ListPane(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(wideListPaneWidth),
                content = listPaneContent
            )
            DetailPane(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                if (isAddingTotpInline) {
                    AddEditTotpScreen(
                        totpId = null,
                        initialData = null,
                        initialTitle = "",
                        initialNotes = "",
                        initialCategoryId = totpNewItemDefaults.categoryId,
                        initialKeePassDatabaseId = totpNewItemDefaults.keepassDatabaseId,
                        initialKeePassGroupPath = totpNewItemDefaults.keepassGroupPath,
                        initialBitwardenVaultId = totpNewItemDefaults.bitwardenVaultId,
                        initialBitwardenFolderId = totpNewItemDefaults.bitwardenFolderId,
                        initialIsFavorite = false,
                        categories = totpCategories,
                        passwordViewModel = passwordViewModel,
                        totpViewModel = totpViewModel,
                        localKeePassViewModel = localKeePassViewModel,
                        onSave = { title, notes, totpData, isFavorite, targets, onComplete ->
                            // 智能归属判定：绑定型验证器且 targets 与密码同 scope（或未显式选择）
                            // → 跟随密码存储（不占独立 Bitwarden 存储位）；
                            // 绑定型但 targets 指向别处 → 视为解绑，按独立条目保存到所选位置；
                            // 非绑定型 → 按用户选择的 targets 保存。
                            totpViewModel.saveTotpWithOwnershipPolicy(
                                id = null,
                                title = title,
                                notes = notes,
                                totpData = totpData,
                                isFavorite = isFavorite,
                                targets = targets,
                                onComplete = { saved ->
                                    if (saved) {
                                        totpViewModel.revealSavedTotpTargets(targets)
                                        onInlineTotpEditorBack()
                                    }
                                    onComplete(saved)
                                }
                            )
                        },
                        onNavigateBack = onInlineTotpEditorBack,
                        onScanQrCode = onNavigateToQuickTotpScan,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (selectedTotpId == null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Select an item to view details",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (selectedTotpItem == null || selectedTotpItem.id <= 0L || selectedTotpData == null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "This item is not available for inline editing",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    AddEditTotpScreen(
                        totpId = selectedTotpItem.id,
                        initialData = selectedTotpData,
                        initialTitle = selectedTotpItem.title,
                        initialNotes = selectedTotpItem.notes,
                        initialCategoryId = selectedTotpData.categoryId,
                        initialKeePassDatabaseId = selectedTotpItem.keepassDatabaseId,
                        initialKeePassGroupPath = selectedTotpItem.keepassGroupPath,
                        initialBitwardenVaultId = selectedTotpItem.bitwardenVaultId,
                        initialBitwardenFolderId = selectedTotpItem.bitwardenFolderId,
                        initialReplicaGroupId = selectedTotpItem.replicaGroupId,
                        initialIsFavorite = selectedTotpItem.isFavorite,
                        categories = totpCategories,
                        passwordViewModel = passwordViewModel,
                        totpViewModel = totpViewModel,
                        localKeePassViewModel = localKeePassViewModel,
                        onSave = { title, notes, totpData, isFavorite, targets, onComplete ->
                            // 智能归属判定（同新建分支）：绑定型跟随密码存储，除非用户显式把
                            // targets 改选到别处（跨库）——此时视为解绑，按独立条目保存。
                            // 修复旧逻辑：绑定型一律走 savePasswordBoundTotp，会静默丢弃
                            // 用户在编辑器里选择的 targets，导致"改存储位置不生效"。
                            totpViewModel.saveTotpWithOwnershipPolicy(
                                id = selectedTotpItem.id,
                                title = title,
                                notes = notes,
                                totpData = totpData,
                                isFavorite = isFavorite,
                                targets = targets,
                                preferredTotpId = selectedTotpItem.id,
                                onComplete = { saved ->
                                    if (saved) {
                                        totpViewModel.revealSavedTotpTargets(targets)
                                    }
                                    onComplete(saved)
                                }
                            )
                        },
                        onNavigateBack = onInlineTotpEditorBack,
                        onScanQrCode = onNavigateToQuickTotpScan,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}
