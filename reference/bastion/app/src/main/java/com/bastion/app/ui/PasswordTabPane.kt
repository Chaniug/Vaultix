package com.bastion.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bastion.app.data.PasswordPageContentType
import com.bastion.app.data.UnmatchedIconHandlingStrategy
import com.bastion.app.data.bitwarden.BitwardenVault
import com.bastion.app.data.LocalKeePassDatabase
import com.bastion.app.data.model.TimelineEvent
import com.bastion.app.security.SecurityManager
import com.bastion.app.ui.common.layout.DetailPane
import com.bastion.app.ui.common.layout.ListPane
import com.bastion.app.ui.password.PasswordListAggregateConfig
import com.bastion.app.ui.password.StackCardMode
import com.bastion.app.ui.screens.AddEditPasswordScreen
import com.bastion.app.ui.screens.HistoryTab
import com.bastion.app.ui.screens.PasswordDetailScreen
import com.bastion.app.ui.screens.TimelineScreen
import com.bastion.app.viewmodel.BankCardViewModel
import com.bastion.app.viewmodel.BillingAddressViewModel
import com.bastion.app.viewmodel.DocumentViewModel
import com.bastion.app.viewmodel.NoteViewModel
import com.bastion.app.viewmodel.PasskeyViewModel
import com.bastion.app.viewmodel.PasswordViewModel
import com.bastion.app.viewmodel.SettingsViewModel
import com.bastion.app.viewmodel.TimelineViewModel

@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
internal fun PasswordTabPane(
    isCompactWidth: Boolean,
    wideListPaneWidth: Dp,
    passwordViewModel: PasswordViewModel,
    settingsViewModel: SettingsViewModel,
    securityManager: SecurityManager,
    keepassDatabases: List<LocalKeePassDatabase>,
    bitwardenVaults: List<BitwardenVault>,
    localKeePassViewModel: com.bastion.app.viewmodel.LocalKeePassViewModel,
    timelineViewModel: TimelineViewModel,
    groupMode: String,
    stackCardMode: StackCardMode,
    visibleContentTypes: List<PasswordPageContentType>,
    selectedContentTypes: Set<PasswordPageContentType>,
    onToggleContentType: (PasswordPageContentType) -> Unit,
    onPasswordOpen: (Long) -> Unit,
    onNavigateToAddTotp: (Long?) -> Unit,
    onNavigateToBankCardDetail: (Long) -> Unit,
    onNavigateToDocumentDetail: (Long) -> Unit,
    onNavigateToBillingAddressDetail: (Long) -> Unit,
    onNavigateToAddNote: (Long?) -> Unit,
    onNavigateToNoteDetail: (Long) -> Unit,
    onNavigateToPasskeyDetail: (Long) -> Unit,
    onOpenHistoryPage: () -> Unit,
    onOpenTrashPage: () -> Unit,
    onOpenCommonAccountTemplatesPage: () -> Unit,
    onScanFidoQr: () -> Unit,
    onCloseHistoryPage: () -> Unit,
    passwordHistoryPageMode: PasswordHistoryPageMode,
    passwordHistoryInitialTrashScopeKey: String?,
    onTimelineLogSelected: (TimelineEvent.StandardLog) -> Unit,
    onSelectionModeChange: (
        Boolean,
        Int,
        () -> Unit,
        () -> Unit,
        (() -> Unit)?,
        (() -> Unit)?,
        (() -> Unit)?,
        () -> Unit
    ) -> Unit,
    onBackToTopVisibilityChange: (Boolean) -> Unit,
    scrollToTopRequestKey: Int,
    isAddingPasswordInline: Boolean,
    inlinePasswordEditorId: Long?,
    selectedPasswordId: Long?,
    passwordNewItemDefaults: NewItemStorageDefaults,
    onInlinePasswordEditorBack: () -> Unit,
    onNavigateToAddWifi: (Long?) -> Unit = {},
    onNavigateToAddSshKey: (Long?) -> Unit = {},
    pendingPasswordAuthenticatorQrResult: String? = null,
    onConsumePendingPasswordAuthenticatorQrResult: () -> Unit = {},
    onScanPasswordAuthenticatorQrCode: () -> Unit = {},
    totpViewModel: com.bastion.app.viewmodel.TotpViewModel,
    bankCardViewModel: BankCardViewModel,
    noteViewModel: NoteViewModel,
    documentViewModel: DocumentViewModel,
    billingAddressViewModel: BillingAddressViewModel,
    passkeyViewModel: PasskeyViewModel,
    biometricEnabled: Boolean,
    iconCardsEnabled: Boolean,
    unmatchedIconHandlingStrategy: UnmatchedIconHandlingStrategy,
    onClearSelectedPassword: () -> Unit,
    onEditPassword: (Long) -> Unit,
    showStandaloneSettingsEntry: Boolean,
    onOpenStandaloneSettings: () -> Unit,
    // 通行秘钥 chip：点击进入通行秘钥页（不做列表过滤）
    onNavigateToPasskeys: (() -> Unit)? = null
) {
    val appSettings by settingsViewModel.settings.collectAsState()

    val listPaneContent: @Composable ColumnScope.() -> Unit = {
        PasswordListContent(
            viewModel = passwordViewModel,
            settingsViewModel = settingsViewModel,
            securityManager = securityManager,
            keepassDatabases = keepassDatabases,
            bitwardenVaults = bitwardenVaults,
            localKeePassViewModel = localKeePassViewModel,
            groupMode = groupMode,
            stackCardMode = stackCardMode,
            onRenameCategory = { category ->
                passwordViewModel.updateCategory(category)
            },
            onDeleteCategory = { category ->
                passwordViewModel.deleteCategory(category)
            },
            onPasswordClick = { password ->
                onPasswordOpen(password.id)
            },
            onSelectionModeChange = onSelectionModeChange,
            onBackToTopVisibilityChange = onBackToTopVisibilityChange,
            scrollToTopRequestKey = scrollToTopRequestKey,
            onOpenHistory = onOpenHistoryPage,
            onOpenTrash = onOpenTrashPage,
            onOpenCommonAccountTemplates = onOpenCommonAccountTemplatesPage,
            onScanFidoQr = onScanFidoQr,
            showStandaloneSettingsEntry = showStandaloneSettingsEntry,
            onOpenStandaloneSettings = onOpenStandaloneSettings,
            aggregateConfig = PasswordListAggregateConfig(
                visibleContentTypes = visibleContentTypes,
                selectedContentTypes = selectedContentTypes,
                onToggleContentType = onToggleContentType,
                totpViewModel = totpViewModel,
                bankCardViewModel = bankCardViewModel,
                documentViewModel = documentViewModel,
                billingAddressViewModel = billingAddressViewModel,
                noteViewModel = noteViewModel,
                passkeyViewModel = passkeyViewModel,
                onOpenTotp = { onNavigateToAddTotp(it) },
                onOpenBankCard = onNavigateToBankCardDetail,
                onOpenDocument = onNavigateToDocumentDetail,
                onOpenBillingAddress = onNavigateToBillingAddressDetail,
                onOpenNote = onNavigateToAddNote,
                onOpenPasskey = onNavigateToPasskeyDetail
            ),
            onNavigateToPasskeys = onNavigateToPasskeys
        )
    }

    if (passwordHistoryPageMode.isVisible) {
        TimelineScreen(
            viewModel = timelineViewModel,
            onLogSelected = onTimelineLogSelected,
            splitPaneMode = false,
            initialTab = passwordHistoryPageMode.tab ?: HistoryTab.TIMELINE,
            initialTrashScopeKey = passwordHistoryInitialTrashScopeKey,
            enableTabSwitch = false,
            showBackButton = true,
            onNavigateBack = onCloseHistoryPage
        )
        return
    }

    if (isCompactWidth) {
        ListPane(
            modifier = Modifier.fillMaxSize(),
            content = listPaneContent
        )
    } else {
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
                val detailContent = remember(
                    isAddingPasswordInline,
                    inlinePasswordEditorId,
                    selectedPasswordId
                ) {
                    when {
                        isAddingPasswordInline -> PasswordDetailContent.Add
                        inlinePasswordEditorId != null -> PasswordDetailContent.Edit(inlinePasswordEditorId)
                        selectedPasswordId != null -> PasswordDetailContent.Detail(selectedPasswordId)
                        else -> PasswordDetailContent.Empty
                    }
                }
                when (val content = detailContent) {
                    PasswordDetailContent.Empty -> {
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
                    }
                    PasswordDetailContent.Add,
                    is PasswordDetailContent.Edit -> {
                        val editorId = (content as? PasswordDetailContent.Edit)?.passwordId
                        AddEditPasswordScreen(
                            viewModel = passwordViewModel,
                            totpViewModel = totpViewModel,
                            bankCardViewModel = bankCardViewModel,
                            noteViewModel = noteViewModel,
                            localKeePassViewModel = localKeePassViewModel,
                            passwordId = editorId,
                            initialCategoryId = passwordNewItemDefaults.categoryId,
                            initialKeePassDatabaseId = passwordNewItemDefaults.keepassDatabaseId,
                            initialKeePassGroupPath = passwordNewItemDefaults.keepassGroupPath,
                            initialBitwardenVaultId = passwordNewItemDefaults.bitwardenVaultId,
                            initialBitwardenFolderId = passwordNewItemDefaults.bitwardenFolderId,
                            pendingQrResult = pendingPasswordAuthenticatorQrResult,
                            onConsumePendingQrResult = onConsumePendingPasswordAuthenticatorQrResult,
                            onScanAuthenticatorQrCode = onScanPasswordAuthenticatorQrCode,
                            onSwitchToWifi = { targetId ->
                                onInlinePasswordEditorBack()
                                onNavigateToAddWifi(targetId)
                            },
                            onSwitchToSshKey = { targetId ->
                                onInlinePasswordEditorBack()
                                onNavigateToAddSshKey(targetId)
                            },
                            onNavigateBack = onInlinePasswordEditorBack
                        )
                    }
                    is PasswordDetailContent.Detail -> {
                        androidx.compose.runtime.CompositionLocalProvider(
                            LocalSharedTransitionScope provides null,
                            LocalAnimatedVisibilityScope provides null
                        ) {
                            PasswordDetailScreen(
                                viewModel = passwordViewModel,
                                passkeyViewModel = passkeyViewModel,
                                noteViewModel = noteViewModel,
                                passwordId = content.passwordId,
                                biometricEnabled = biometricEnabled,
                                iconCardsEnabled = iconCardsEnabled,
                                unmatchedIconHandlingStrategy = unmatchedIconHandlingStrategy,
                                enableSharedBounds = false,
                                onNavigateBack = onClearSelectedPassword,
                                onOpenBoundNote = onNavigateToNoteDetail,
                                onEditPassword = onEditPassword,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
}

private sealed interface PasswordDetailContent {
    val isEmpty: Boolean
        get() = this == Empty
    val isEditor: Boolean
        get() = this == Add || this is Edit

    data object Empty : PasswordDetailContent
    data object Add : PasswordDetailContent
    data class Edit(val passwordId: Long) : PasswordDetailContent
    data class Detail(val passwordId: Long) : PasswordDetailContent
}
