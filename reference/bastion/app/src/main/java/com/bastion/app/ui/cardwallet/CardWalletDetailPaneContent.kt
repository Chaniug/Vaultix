package com.bastion.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.bastion.app.ui.animation.AnimationUtils
import com.bastion.app.ui.screens.AddEditBankCardScreen
import com.bastion.app.ui.screens.AddEditBillingAddressScreen
import com.bastion.app.ui.screens.AddEditDocumentScreen
import com.bastion.app.ui.screens.BankCardDetailScreen
import com.bastion.app.ui.screens.BillingAddressDetailScreen
import com.bastion.app.ui.screens.DocumentDetailScreen
import com.bastion.app.viewmodel.BankCardViewModel
import com.bastion.app.viewmodel.BillingAddressViewModel
import com.bastion.app.viewmodel.DocumentViewModel

@Composable
internal fun CardWalletDetailPaneContent(
    bankCardViewModel: BankCardViewModel,
    documentViewModel: DocumentViewModel,
    billingAddressViewModel: BillingAddressViewModel,
    isAddingBankCardInline: Boolean,
    inlineBankCardEditorId: Long?,
    onInlineBankCardEditorBack: () -> Unit,
    isAddingDocumentInline: Boolean,
    inlineDocumentEditorId: Long?,
    onInlineDocumentEditorBack: () -> Unit,
    isAddingBillingAddressInline: Boolean,
    inlineBillingAddressEditorId: Long?,
    onInlineBillingAddressEditorBack: () -> Unit,
    selectedBankCardId: Long?,
    onClearSelectedBankCard: () -> Unit,
    onEditBankCard: (Long) -> Unit,
    selectedDocumentId: Long?,
    onClearSelectedDocument: () -> Unit,
    onEditDocument: (Long) -> Unit,
    selectedBillingAddressId: Long?,
    onClearSelectedBillingAddress: () -> Unit,
    onEditBillingAddress: (Long) -> Unit,
    initialCategoryId: Long? = null,
    initialKeePassDatabaseId: Long? = null,
    initialKeePassGroupPath: String? = null,
    initialBitwardenVaultId: Long? = null,
    initialBitwardenFolderId: String? = null
) {
    val detailContent = remember(
        isAddingBankCardInline,
        inlineBankCardEditorId,
        isAddingDocumentInline,
        inlineDocumentEditorId,
        isAddingBillingAddressInline,
        inlineBillingAddressEditorId,
        selectedBankCardId,
        selectedDocumentId,
        selectedBillingAddressId
    ) {
        when {
            isAddingBankCardInline -> CardWalletDetailContent.BankCardAdd
            inlineBankCardEditorId != null -> CardWalletDetailContent.BankCardEdit(inlineBankCardEditorId)
            isAddingDocumentInline -> CardWalletDetailContent.DocumentAdd
            inlineDocumentEditorId != null -> CardWalletDetailContent.DocumentEdit(inlineDocumentEditorId)
            isAddingBillingAddressInline -> CardWalletDetailContent.BillingAddressAdd
            inlineBillingAddressEditorId != null -> CardWalletDetailContent.BillingAddressEdit(inlineBillingAddressEditorId)
            selectedBankCardId != null -> CardWalletDetailContent.BankCardDetail(selectedBankCardId)
            selectedDocumentId != null -> CardWalletDetailContent.DocumentDetail(selectedDocumentId)
            selectedBillingAddressId != null -> CardWalletDetailContent.BillingAddressDetail(selectedBillingAddressId)
            else -> CardWalletDetailContent.Empty
        }
    }

    AnimatedContent(
        targetState = detailContent,
        transitionSpec = AnimationUtils.pageTransitionSpec(),
        label = "CardWalletDetailPaneContent"
    ) { content ->
        when (content) {
            CardWalletDetailContent.BankCardAdd,
            is CardWalletDetailContent.BankCardEdit -> {
                val editorId = (content as? CardWalletDetailContent.BankCardEdit)?.cardId
                AddEditBankCardScreen(
                    viewModel = bankCardViewModel,
                    cardId = editorId,
                    initialCategoryId = initialCategoryId,
                    initialKeePassDatabaseId = initialKeePassDatabaseId,
                    initialKeePassGroupPath = initialKeePassGroupPath,
                    initialBitwardenVaultId = initialBitwardenVaultId,
                    initialBitwardenFolderId = initialBitwardenFolderId,
                    onNavigateBack = onInlineBankCardEditorBack,
                    modifier = Modifier.fillMaxSize()
                )
            }
            CardWalletDetailContent.DocumentAdd,
            is CardWalletDetailContent.DocumentEdit -> {
                val editorId = (content as? CardWalletDetailContent.DocumentEdit)?.documentId
                AddEditDocumentScreen(
                    viewModel = documentViewModel,
                    documentId = editorId,
                    initialCategoryId = initialCategoryId,
                    initialKeePassDatabaseId = initialKeePassDatabaseId,
                    initialKeePassGroupPath = initialKeePassGroupPath,
                    initialBitwardenVaultId = initialBitwardenVaultId,
                    initialBitwardenFolderId = initialBitwardenFolderId,
                    onNavigateBack = onInlineDocumentEditorBack,
                    modifier = Modifier.fillMaxSize()
                )
            }
            CardWalletDetailContent.BillingAddressAdd,
            is CardWalletDetailContent.BillingAddressEdit -> {
                val editorId = (content as? CardWalletDetailContent.BillingAddressEdit)?.addressId
                AddEditBillingAddressScreen(
                    viewModel = billingAddressViewModel,
                    addressId = editorId,
                    initialCategoryId = initialCategoryId,
                    onNavigateBack = onInlineBillingAddressEditorBack,
                    modifier = Modifier.fillMaxSize()
                )
            }
            is CardWalletDetailContent.BankCardDetail -> {
                BankCardDetailScreen(
                    viewModel = bankCardViewModel,
                    cardId = content.cardId,
                    onNavigateBack = onClearSelectedBankCard,
                    onEditCard = onEditBankCard,
                    modifier = Modifier.fillMaxSize()
                )
            }
            is CardWalletDetailContent.DocumentDetail -> {
                DocumentDetailScreen(
                    viewModel = documentViewModel,
                    documentId = content.documentId,
                    onNavigateBack = onClearSelectedDocument,
                    onEditDocument = onEditDocument,
                    modifier = Modifier.fillMaxSize()
                )
            }
            is CardWalletDetailContent.BillingAddressDetail -> {
                BillingAddressDetailScreen(
                    viewModel = billingAddressViewModel,
                    addressId = content.addressId,
                    onNavigateBack = onClearSelectedBillingAddress,
                    onEditAddress = onEditBillingAddress,
                    modifier = Modifier.fillMaxSize()
                )
            }
            CardWalletDetailContent.Empty -> {
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
        }
    }
}

private sealed interface CardWalletDetailContent {
    data object Empty : CardWalletDetailContent
    data object BankCardAdd : CardWalletDetailContent
    data class BankCardEdit(val cardId: Long) : CardWalletDetailContent
    data class BankCardDetail(val cardId: Long) : CardWalletDetailContent
    data object DocumentAdd : CardWalletDetailContent
    data class DocumentEdit(val documentId: Long) : CardWalletDetailContent
    data class DocumentDetail(val documentId: Long) : CardWalletDetailContent
    data object BillingAddressAdd : CardWalletDetailContent
    data class BillingAddressEdit(val addressId: Long) : CardWalletDetailContent
    data class BillingAddressDetail(val addressId: Long) : CardWalletDetailContent
}
