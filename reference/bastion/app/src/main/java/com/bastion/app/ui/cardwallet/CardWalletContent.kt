package com.bastion.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.SaveableStateHolder
import com.bastion.app.ui.screens.CardWalletScreen
import com.bastion.app.ui.screens.CardWalletTab
import com.bastion.app.viewmodel.BankCardViewModel
import com.bastion.app.viewmodel.BillingAddressViewModel
import com.bastion.app.viewmodel.DocumentViewModel

internal data class CardWalletContentState(
    val currentTab: CardWalletTab,
    val onTabSelected: (CardWalletTab) -> Unit,
    val onCardClick: (Long) -> Unit,
    val onDocumentClick: (Long) -> Unit,
    val onBillingAddressClick: (Long) -> Unit,
    val onDocumentSelectionModeChange: (Boolean, Int, () -> Unit, () -> Unit, () -> Unit, () -> Unit) -> Unit,
    val onBankCardSelectionModeChange: (Boolean, Int, () -> Unit, () -> Unit, () -> Unit, () -> Unit, () -> Unit) -> Unit,
    val onBitwardenScopeChanged: (Long?) -> Unit = {}
)

@Composable
internal fun CardWalletContent(
    saveableStateHolder: SaveableStateHolder,
    bankCardViewModel: BankCardViewModel,
    documentViewModel: DocumentViewModel,
    billingAddressViewModel: BillingAddressViewModel,
    passwordViewModel: com.bastion.app.viewmodel.PasswordViewModel,
    bitwardenViewModel: com.bastion.app.bitwarden.viewmodel.BitwardenViewModel? = null,
    state: CardWalletContentState,
    showStandaloneSettingsEntry: Boolean = false,
    onOpenStandaloneSettings: () -> Unit = {}
) {
    saveableStateHolder.SaveableStateProvider("card_wallet") {
        CardWalletScreen(
            bankCardViewModel = bankCardViewModel,
            documentViewModel = documentViewModel,
            billingAddressViewModel = billingAddressViewModel,
            passwordViewModel = passwordViewModel,
            bitwardenViewModel = bitwardenViewModel,
            currentTab = state.currentTab,
            onTabSelected = state.onTabSelected,
            onCardClick = state.onCardClick,
            onDocumentClick = state.onDocumentClick,
            onBillingAddressClick = state.onBillingAddressClick,
            onSelectionModeChange = state.onDocumentSelectionModeChange,
            onBankCardSelectionModeChange = state.onBankCardSelectionModeChange,
            showStandaloneSettingsEntry = showStandaloneSettingsEntry,
            onOpenStandaloneSettings = onOpenStandaloneSettings,
            onBitwardenScopeChanged = state.onBitwardenScopeChanged
        )
    }
}
