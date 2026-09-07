package com.bastion.app.ui

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bastion.app.data.AppSettings
import com.bastion.app.data.PasskeyEntry
import com.bastion.app.security.SecurityManager
import com.bastion.app.ui.main.navigation.BottomNavItem
import com.bastion.app.ui.password.resolvePasswordPageVisibleTypes
import com.bastion.app.ui.password.sanitizeSelectedPasswordPageTypes
import com.bastion.app.ui.screens.CardWalletTab
import com.bastion.app.ui.screens.GeneratorScreen
import com.bastion.app.ui.screens.NoteListScreen
import com.bastion.app.ui.screens.PasskeyListScreen
import com.bastion.app.ui.screens.SendScreen

import com.bastion.app.ui.vaultv2.VaultV2Pane
import com.bastion.app.ui.vaultv2.VaultV2PaneState
import com.bastion.app.viewmodel.BankCardViewModel
import com.bastion.app.viewmodel.BillingAddressViewModel
import com.bastion.app.viewmodel.DocumentViewModel
import com.bastion.app.viewmodel.GeneratorViewModel
import com.bastion.app.viewmodel.NoteViewModel
import com.bastion.app.viewmodel.PasskeyViewModel
import com.bastion.app.viewmodel.PasswordViewModel
import com.bastion.app.viewmodel.SettingsViewModel
import com.bastion.app.viewmodel.TimelineViewModel

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun CompactDraggableTabContent(
    paddingValues: PaddingValues,
    currentTab: BottomNavItem,
    showStandaloneSettingsEntry: Boolean,
    onOpenStandaloneSettings: () -> Unit,
    passwordViewModel: PasswordViewModel,
    settingsViewModel: SettingsViewModel,
    securityManager: SecurityManager,
    keepassDatabases: List<com.bastion.app.data.LocalKeePassDatabase>,
    bitwardenVaults: List<com.bastion.app.data.bitwarden.BitwardenVault>,
    localKeePassViewModel: com.bastion.app.viewmodel.LocalKeePassViewModel,
    passwordGroupMode: String,
    stackCardMode: com.bastion.app.ui.password.StackCardMode,
    onPasswordOpen: (Long) -> Unit,
    onBankCardOpen: (Long) -> Unit,
    onDocumentOpen: (Long) -> Unit,
    onNoteOpen: (Long) -> Unit,
    onPasskeyOpen: (PasskeyEntry) -> Unit,
    onNavigateToAddTotp: (Long?) -> Unit,
    onNavigateToBankCardDetail: (Long) -> Unit,
    onNavigateToDocumentDetail: (Long) -> Unit,
    onNavigateToBillingAddressDetail: (Long) -> Unit,
    onNavigateToPasskeyDetail: (Long) -> Unit,
    onPasswordSelectionModeChange: (
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
    passwordScrollToTopRequestKey: Int,
    totpViewModel: com.bastion.app.viewmodel.TotpViewModel,
    onTotpOpen: (Long) -> Unit,
    onNavigateToQuickTotpScan: () -> Unit,
    onNavigateToFidoQrScan: () -> Unit,
    onTotpSelectionModeChange: (
        Boolean,
        Int,
        () -> Unit,
        () -> Unit,
        () -> Unit,
        () -> Unit
    ) -> Unit,
    cardWalletSaveableStateHolder: androidx.compose.runtime.saveable.SaveableStateHolder,
    bankCardViewModel: BankCardViewModel,
    documentViewModel: DocumentViewModel,
    billingAddressViewModel: BillingAddressViewModel,
    cardWalletContentState: CardWalletContentState,
    generatorViewModel: GeneratorViewModel,
    generatorRefreshRequestKey: Int,
    onGeneratorRefreshRequestConsumed: () -> Unit,
    noteViewModel: NoteViewModel,
    onNavigateToAddNote: (Long?) -> Unit,
    onNavigateToSearchedNote: (Long, String) -> Unit,
    onNavigateToNoteDetail: (Long) -> Unit,
    onNoteSelectionModeChange: (Boolean) -> Unit,
    timelineViewModel: TimelineViewModel,
    passkeyViewModel: PasskeyViewModel,
    onNavigateToPasswordDetail: (Long) -> Unit,
    onNavigateToAuthenticator: () -> Unit,
    onNavigateToPasskeys: () -> Unit,
    bitwardenViewModel: com.bastion.app.bitwarden.viewmodel.BitwardenViewModel,
    onSendBitwardenEvent: (com.bastion.app.bitwarden.viewmodel.BitwardenViewModel.BitwardenEvent) -> Boolean,
    onNavigateToChangePassword: () -> Unit,
    onNavigateToSecurityQuestion: () -> Unit,
    onNavigateToMasterPasswordLocking: () -> Unit,
    onNavigateToSyncBackup: () -> Unit,
    onNavigateToAutofill: () -> Unit,
    onNavigateToPasskeySettings: () -> Unit,
    onSecurityAnalysis: () -> Unit,
    onNavigateToDeveloperSettings: () -> Unit,
    onNavigateToPermissionManagement: () -> Unit,
    onNavigateToExtensions: () -> Unit,
    onNavigateToCommonAccountTemplates: () -> Unit,
    onNavigateToPageCustomization: () -> Unit,
    onNavigateToThemeAndColorScheme: () -> Unit = {},
    onOpenVaultV2HistoryPage: () -> Unit,
    onOpenVaultV2TrashPage: () -> Unit,
    onOpenVaultV2ArchivePage: () -> Unit,
    onManageKeePassDatabase: (Long) -> Unit = {},
    onClearAllData: (Boolean, Boolean, Boolean, Boolean, Boolean, Boolean) -> Unit,
    cardWalletSubTab: CardWalletTab,
    passwordHistoryPageMode: PasswordHistoryPageMode,
    passwordHistoryInitialTrashScopeKey: String?,
    onOpenHistoryPage: () -> Unit,
    onOpenTrashPage: () -> Unit,
    onCloseHistoryPage: () -> Unit,
    isPasswordSelectionMode: Boolean,
    selectedPasswordCount: Int,
    onExitPasswordSelection: () -> Unit,
    onSelectAllPasswords: () -> Unit,
    onFavoriteSelectedPasswords: (() -> Unit)?,
    onMoveToCategoryPasswords: (() -> Unit)?,
    onManualStackPasswords: (() -> Unit)?,
    onDeleteSelectedPasswords: () -> Unit,
    isTotpSelectionMode: Boolean,
    selectedTotpCount: Int,
    onExitTotpSelection: () -> Unit,
    onSelectAllTotp: () -> Unit,
    onMoveToCategoryTotp: () -> Unit,
    onDeleteSelectedTotp: () -> Unit,
    isBankCardSelectionMode: Boolean,
    selectedBankCardCount: Int,
    onExitBankCardSelection: () -> Unit,
    onSelectAllBankCards: () -> Unit,
    onFavoriteBankCards: () -> Unit,
    onMoveToCategoryBankCards: () -> Unit,
    onDeleteSelectedBankCards: () -> Unit,
    isDocumentSelectionMode: Boolean,
    selectedDocumentCount: Int,
    onExitDocumentSelection: () -> Unit,
    onSelectAllDocuments: () -> Unit,
    onMoveToCategoryDocuments: () -> Unit,
    onDeleteSelectedDocuments: () -> Unit,
    vaultV2PaneState: VaultV2PaneState
) {
    val appSettings by settingsViewModel.settings.collectAsState()
    val currentFilter by passwordViewModel.categoryFilter.collectAsState()
    val passwordNewItemDefaults = remember(currentFilter) { defaultsFromPasswordFilter(currentFilter) }
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
    LaunchedEffect(passwordPageVisibleContentTypes) {
        passwordPageSelectedContentTypes = sanitizeSelectedPasswordPageTypes(
            visibleTypes = passwordPageVisibleContentTypes,
            selectedTypes = passwordPageSelectedContentTypes
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
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
                    onOpenPassword = onPasswordOpen,
                    onOpenTotp = onTotpOpen,
                    onOpenBankCard = onBankCardOpen,
                    onOpenDocument = onDocumentOpen,
                    onOpenNote = onNoteOpen,
                    onOpenPasskey = onNavigateToPasskeyDetail,
                    onOpenHistory = onOpenVaultV2HistoryPage,
                    onOpenTrashPage = onOpenVaultV2TrashPage,
                    onOpenArchivePage = onOpenVaultV2ArchivePage,
                    onManageKeePassDatabase = onManageKeePassDatabase,
                    onOpenCommonAccountTemplates = onNavigateToCommonAccountTemplates,
                    onScanFidoQr = onNavigateToFidoQrScan,
                    onOpenStandaloneSettings = onOpenStandaloneSettings,
                    showStandaloneSettingsEntry = showStandaloneSettingsEntry,
                    appSettings = appSettings,
                    modifier = Modifier.fillMaxSize()
                )
            }
            BottomNavItem.Passwords -> {
                PasswordTabPane(
                    isCompactWidth = true,
                    wideListPaneWidth = 0.dp,
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
                    onPasswordOpen = onPasswordOpen,
                    onNavigateToAddTotp = onNavigateToAddTotp,
                    onNavigateToBankCardDetail = onNavigateToBankCardDetail,
                    onNavigateToDocumentDetail = onNavigateToDocumentDetail,
                    onNavigateToBillingAddressDetail = onNavigateToBillingAddressDetail,
                    onNavigateToAddNote = onNavigateToAddNote,
                    onNavigateToNoteDetail = onNavigateToNoteDetail,
                    onNavigateToPasskeyDetail = onNavigateToPasskeyDetail,
                    onOpenHistoryPage = onOpenHistoryPage,
                    onOpenTrashPage = onOpenTrashPage,
                    onOpenCommonAccountTemplatesPage = onNavigateToCommonAccountTemplates,
                    onScanFidoQr = onNavigateToFidoQrScan,
                    onCloseHistoryPage = onCloseHistoryPage,
                    passwordHistoryPageMode = passwordHistoryPageMode,
                    passwordHistoryInitialTrashScopeKey = passwordHistoryInitialTrashScopeKey,
                    onTimelineLogSelected = {},
                    onSelectionModeChange = onPasswordSelectionModeChange,
                    onBackToTopVisibilityChange = onBackToTopVisibilityChange,
                    scrollToTopRequestKey = passwordScrollToTopRequestKey,
                    isAddingPasswordInline = false,
                    inlinePasswordEditorId = null,
                    selectedPasswordId = null,
                    passwordNewItemDefaults = passwordNewItemDefaults,
                    onInlinePasswordEditorBack = {},
                    totpViewModel = totpViewModel,
                    bankCardViewModel = bankCardViewModel,
                    noteViewModel = noteViewModel,
                    documentViewModel = documentViewModel,
                    billingAddressViewModel = billingAddressViewModel,
                    passkeyViewModel = passkeyViewModel,
                    biometricEnabled = appSettings.biometricEnabled,
                    iconCardsEnabled = appSettings.iconCardsEnabled && appSettings.passwordPageIconEnabled,
                    unmatchedIconHandlingStrategy = appSettings.unmatchedIconHandlingStrategy,
                    onClearSelectedPassword = {},
                    onEditPassword = {},
                    showStandaloneSettingsEntry = showStandaloneSettingsEntry,
                    onOpenStandaloneSettings = onOpenStandaloneSettings,
                    onNavigateToPasskeys = onNavigateToPasskeys
                )
            }
            BottomNavItem.Authenticator -> {
                TotpListContent(
                    viewModel = totpViewModel,
                    passwordViewModel = passwordViewModel,
                    onTotpClick = onTotpOpen,
                    onDeleteTotp = { totp ->
                        totpViewModel.deleteTotpItem(totp)
                    },
                    onQuickScanTotp = onNavigateToQuickTotpScan,
                    onSelectionModeChange = onTotpSelectionModeChange,
                    showStandaloneSettingsEntry = showStandaloneSettingsEntry,
                    onOpenStandaloneSettings = onOpenStandaloneSettings,
                    onNavigateToPasskeys = onNavigateToPasskeys
                )
            }
            BottomNavItem.CardWallet -> {
                CardWalletContent(
                    saveableStateHolder = cardWalletSaveableStateHolder,
                    bankCardViewModel = bankCardViewModel,
                    documentViewModel = documentViewModel,
                    billingAddressViewModel = billingAddressViewModel,
                    passwordViewModel = passwordViewModel,
                    bitwardenViewModel = bitwardenViewModel,
                    state = cardWalletContentState,
                    showStandaloneSettingsEntry = showStandaloneSettingsEntry,
                    onOpenStandaloneSettings = onOpenStandaloneSettings
                )
            }
            BottomNavItem.Generator -> {
                GeneratorScreen(
                    onNavigateBack = {},
                    viewModel = generatorViewModel,
                    passwordViewModel = passwordViewModel,
                    externalRefreshRequestKey = generatorRefreshRequestKey,
                    onRefreshRequestConsumed = onGeneratorRefreshRequestConsumed,
                    useExternalRefreshFab = true,
                    showStandaloneSettingsEntry = showStandaloneSettingsEntry,
                    onOpenStandaloneSettings = onOpenStandaloneSettings
                )
            }
            BottomNavItem.Notes -> {
                NoteListScreen(
                    viewModel = noteViewModel,
                    settingsViewModel = settingsViewModel,
                    onNavigateToAddNote = onNavigateToAddNote,
                    onNavigateToSearchedNote = onNavigateToSearchedNote,
                    securityManager = securityManager,
                    passwordViewModel = passwordViewModel,
                    onSelectionModeChange = onNoteSelectionModeChange,
                    showStandaloneSettingsEntry = showStandaloneSettingsEntry,
                    onOpenStandaloneSettings = onOpenStandaloneSettings
                )
            }
            BottomNavItem.Passkey -> {
                PasskeyListScreen(
                    viewModel = passkeyViewModel,
                    passwordViewModel = passwordViewModel,
                    onNavigateToPasswordDetail = onNavigateToPasswordDetail,
                    onPasskeyClick = {},
                    showStandaloneSettingsEntry = showStandaloneSettingsEntry,
                    onOpenStandaloneSettings = onOpenStandaloneSettings,
                    onNavigateToAuthenticator = onNavigateToAuthenticator
                )
            }
            BottomNavItem.Send -> {
                SendScreen(
                    bitwardenViewModel = bitwardenViewModel,
                    onBitwardenEvent = onSendBitwardenEvent,
                    showStandaloneSettingsEntry = showStandaloneSettingsEntry,
                    onOpenStandaloneSettings = onOpenStandaloneSettings
                )
            }
            BottomNavItem.Settings -> {
                SettingsTabContent(
                    isCompactWidth = true,
                    wideListPaneWidth = 0.dp,
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
                    onClearAllData = onClearAllData
                )
            }
        }
        }

        MainScreenSelectionBars(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
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
            isTotpSelectionMode = isTotpSelectionMode,
            selectedTotpCount = selectedTotpCount,
            onExitTotpSelection = onExitTotpSelection,
            onSelectAllTotp = onSelectAllTotp,
            onMoveToCategoryTotp = onMoveToCategoryTotp,
            onDeleteSelectedTotp = onDeleteSelectedTotp,
            isBankCardSelectionMode = isBankCardSelectionMode,
            selectedBankCardCount = selectedBankCardCount,
            onExitBankCardSelection = onExitBankCardSelection,
            onSelectAllBankCards = onSelectAllBankCards,
            onFavoriteBankCards = onFavoriteBankCards,
            onMoveToCategoryBankCards = onMoveToCategoryBankCards,
            onDeleteSelectedBankCards = onDeleteSelectedBankCards,
            isDocumentSelectionMode = isDocumentSelectionMode,
            selectedDocumentCount = selectedDocumentCount,
            onExitDocumentSelection = onExitDocumentSelection,
            onSelectAllDocuments = onSelectAllDocuments,
            onMoveToCategoryDocuments = onMoveToCategoryDocuments,
            onDeleteSelectedDocuments = onDeleteSelectedDocuments
        )
    }
}
