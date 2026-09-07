package com.bastion.app.ui

import com.bastion.app.logging.runCatchingObserved
import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.bastion.app.R
import com.bastion.app.bitwarden.repository.BitwardenRepository
import com.bastion.app.data.Category
import com.bastion.app.data.ItemType
import com.bastion.app.data.LocalKeePassDatabase
import com.bastion.app.data.PasskeyEntry
import com.bastion.app.data.PasswordEntry
import com.bastion.app.data.PasswordPageContentType
import com.bastion.app.data.SecureItem
import com.bastion.app.data.bitwarden.BitwardenPendingOperation
import com.bastion.app.data.isKeePassOwned
import com.bastion.app.data.isLocalOnlyItem
import com.bastion.app.data.model.TimelinePasswordRecreatedEntry
import com.bastion.app.notes.domain.NoteContentCodec
import com.bastion.app.passkey.PasskeyPrivateKeyStore
import com.bastion.app.security.SecurityManager
import com.bastion.app.ui.components.UnifiedMoveAction
import com.bastion.app.ui.components.UnifiedMoveCategoryTarget
import com.bastion.app.util.TotpDataResolver
import com.bastion.app.viewmodel.PasswordViewModel
import com.bastion.app.ui.password.PasswordAggregateListItemUi
import com.bastion.app.ui.password.PasswordAggregateWalletItemType

internal data class PasswordBatchAggregateSelection(
    val bankCards: List<SecureItem> = emptyList(),
    val documents: List<SecureItem> = emptyList(),
    val billingAddresses: List<SecureItem> = emptyList(),
    val notes: List<SecureItem> = emptyList(),
    val totpItems: List<SecureItem> = emptyList(),
    val passkeys: List<PasskeyEntry> = emptyList()
) {
    val hasKeePassOwned: Boolean
        get() = bankCards.any { it.isKeePassOwned() } ||
            documents.any { it.isKeePassOwned() } ||
            billingAddresses.any { it.isKeePassOwned() } ||
            notes.any { it.isKeePassOwned() } ||
            totpItems.any { it.isKeePassOwned() } ||
            passkeys.any { it.isKeePassOwned() }

    val hasItems: Boolean
        get() = bankCards.isNotEmpty() ||
            documents.isNotEmpty() ||
            billingAddresses.isNotEmpty() ||
            notes.isNotEmpty() ||
            totpItems.isNotEmpty() ||
            passkeys.isNotEmpty()
}

internal data class MixedPasswordBatchMoveResult(
    val successCount: Int,
    val failedCount: Int,
    val blockedPasskeyCount: Int,
    val copiedPasswordIds: List<Long>
)

internal fun PasswordListAggregateUiState.resolveBatchAggregateSelection(
    selectedSupplementaryItems: List<PasswordAggregateListItemUi>
): PasswordBatchAggregateSelection {
    if (selectedSupplementaryItems.isEmpty()) {
        return PasswordBatchAggregateSelection()
    }

    val bankCardIds = selectedSupplementaryItems
        .filter {
            it.type == PasswordPageContentType.CARD_WALLET &&
                it.walletItemType == PasswordAggregateWalletItemType.BANK_CARD
        }
        .mapNotNullTo(linkedSetOf()) { it.secureItemId }
    val documentIds = selectedSupplementaryItems
        .filter {
            it.type == PasswordPageContentType.CARD_WALLET &&
                it.walletItemType == PasswordAggregateWalletItemType.DOCUMENT
        }
        .mapNotNullTo(linkedSetOf()) { it.secureItemId }
    val billingAddressIds = selectedSupplementaryItems
        .filter {
            it.type == PasswordPageContentType.CARD_WALLET &&
                it.walletItemType == PasswordAggregateWalletItemType.BILLING_ADDRESS
        }
        .mapNotNullTo(linkedSetOf()) { it.secureItemId }
    val noteIds = selectedSupplementaryItems
        .filter { it.type == PasswordPageContentType.NOTE }
        .mapNotNullTo(linkedSetOf()) { it.secureItemId }
    val totpIds = selectedSupplementaryItems
        .filter { it.type == PasswordPageContentType.AUTHENTICATOR }
        .mapNotNullTo(linkedSetOf()) { it.secureItemId }
    val passkeyIds = selectedSupplementaryItems
        .filter { it.type == PasswordPageContentType.PASSKEY }
        .mapNotNullTo(linkedSetOf()) { it.passkeyRecordId }

    return PasswordBatchAggregateSelection(
        bankCards = bankCards.filter { it.id in bankCardIds },
        documents = documents.filter { it.id in documentIds },
        billingAddresses = billingAddresses.filter { it.id in billingAddressIds },
        notes = notes.filter { it.id in noteIds },
        totpItems = totpItems.filter { it.id in totpIds },
        passkeys = passkeys.filter { it.id in passkeyIds }
    )
}

internal suspend fun executeMixedPasswordBatchMove(
    context: Context,
    action: UnifiedMoveAction,
    target: UnifiedMoveCategoryTarget,
    selectedEntries: List<PasswordEntry>,
    aggregateSelection: PasswordBatchAggregateSelection,
    categories: List<Category>,
    keepassDatabases: List<LocalKeePassDatabase>,
    localKeePassViewModel: com.bastion.app.viewmodel.LocalKeePassViewModel,
    securityManager: SecurityManager,
    viewModel: PasswordViewModel,
    aggregateUiState: PasswordListAggregateUiState,
    bitwardenRepository: BitwardenRepository,
    onProgress: ((Int, Int) -> Unit)? = null
): MixedPasswordBatchMoveResult {
    val passwordActionResolution = resolvePasswordBatchMoveAction(
        requestedAction = action,
        selectedEntries = selectedEntries,
        target = target
    )
    val forceSupplementaryCopy =
        action == UnifiedMoveAction.MOVE && aggregateSelection.hasKeePassOwned
    if (passwordActionResolution.showKeepassCopyOnlyHint || forceSupplementaryCopy) {
        Toast.makeText(
            context,
            context.getString(R.string.keepass_copy_only_hint),
            Toast.LENGTH_SHORT
        ).show()
    }

    val effectiveAction = if (
        passwordActionResolution.effectiveAction == UnifiedMoveAction.COPY ||
        forceSupplementaryCopy
    ) {
        UnifiedMoveAction.COPY
    } else {
        action
    }

    if (
        effectiveAction == UnifiedMoveAction.COPY &&
        action == UnifiedMoveAction.COPY &&
        aggregateSelection.passkeys.isNotEmpty()
    ) {
        Toast.makeText(
            context,
            context.getString(R.string.passkey_copy_uses_move_hint),
            Toast.LENGTH_SHORT
        ).show()
    }

    val targetRouting = resolvePasswordBatchMoveTargetRouting(target)
    val selectedIds = selectedEntries.map(PasswordEntry::id)
    val targetCategoryId = targetRouting.bastionCategoryId
    val targetKeepassDatabaseId = when (target) {
        is UnifiedMoveCategoryTarget.KeePassDatabaseTarget -> target.databaseId
        is UnifiedMoveCategoryTarget.KeePassGroupTarget -> target.databaseId
        else -> null
    }
    val targetKeepassGroupPath = when (target) {
        is UnifiedMoveCategoryTarget.KeePassGroupTarget -> target.groupPath
        else -> null
    }
    val targetBitwardenVaultId = when (target) {
        is UnifiedMoveCategoryTarget.BitwardenVaultTarget -> target.vaultId
        is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> target.vaultId
        else -> null
    }
    val targetBitwardenFolderId = when (target) {
        is UnifiedMoveCategoryTarget.BitwardenVaultTarget -> ""
        is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> target.folderId
        else -> null
    }
    val isBastionLocalTarget = targetRouting.isBastionCopyTarget

    var successCount = 0
    var failedCount = 0
    var blockedPasskeyCount = 0
    val copiedPasswordIds = mutableListOf<Long>()

    val totalCount = selectedEntries.size +
        aggregateSelection.bankCards.size +
        aggregateSelection.documents.size +
        aggregateSelection.billingAddresses.size +
        aggregateSelection.notes.size +
        aggregateSelection.totpItems.size +
        aggregateSelection.passkeys.size
    var processedCount = 0

    fun reportProgress(step: Int = 1) {
        if (totalCount <= 0 || step <= 0) return
        processedCount = (processedCount + step).coerceAtMost(totalCount)
        onProgress?.invoke(processedCount, totalCount)
    }

    if (totalCount > 0) {
        onProgress?.invoke(0, totalCount)
    }

    if (effectiveAction == UnifiedMoveAction.COPY) {
        if (targetRouting.isBastionCopyTarget) {
            selectedEntries.forEach { entry ->
                val createdId = viewModel.copyPasswordToBastionLocal(entry, targetCategoryId)
                if (createdId != null && createdId > 0) {
                    copiedPasswordIds += createdId
                    successCount++
                } else {
                    failedCount++
                }
                reportProgress()
            }
        } else {
            selectedEntries.forEach { entry ->
                val createdId = viewModel.addPasswordEntryWithResultAwait(
                    buildCopiedEntryForTarget(entry, target)
                )
                if (createdId != null && createdId > 0) {
                    copiedPasswordIds += createdId
                    successCount++
                } else {
                    failedCount++
                }
                reportProgress()
            }
        }

        aggregateSelection.totpItems.forEach { item ->
            val totpViewModel = aggregateUiState.totpViewModel
            if (totpViewModel == null) {
                failedCount++
                reportProgress()
                return@forEach
            }
            if (isBastionLocalTarget) {
                if (totpViewModel.copyTotpToBastionLocal(item, targetCategoryId) != null) {
                    successCount++
                } else {
                    failedCount++
                }
                reportProgress()
                return@forEach
            }

            val totpData = TotpDataResolver.parseStoredItemData(
                itemData = item.itemData,
                fallbackIssuer = item.title,
                decryptIfNeeded = securityManager::decryptDataIfBastionCiphertext
            )
            if (totpData == null) {
                failedCount++
                reportProgress()
                return@forEach
            }
            val detachedTotpData = totpData.copy(
                boundPasswordId = null,
                categoryId = null,
                keepassDatabaseId = null
            )
            totpViewModel.saveTotpItem(
                id = null,
                title = item.title,
                notes = item.notes,
                totpData = detachedTotpData,
                isFavorite = item.isFavorite,
                categoryId = targetCategoryId,
                keepassDatabaseId = targetKeepassDatabaseId,
                bitwardenVaultId = targetBitwardenVaultId,
                bitwardenFolderId = targetBitwardenFolderId
            )
            successCount++
            reportProgress()
        }

        aggregateSelection.notes.forEach { item ->
            val noteViewModel = aggregateUiState.noteViewModel
            if (noteViewModel == null) {
                failedCount++
                reportProgress()
                return@forEach
            }
            if (isBastionLocalTarget) {
                if (noteViewModel.copyNoteToBastionLocal(item, targetCategoryId) != null) {
                    successCount++
                } else {
                    failedCount++
                }
                reportProgress()
                return@forEach
            }

            val decodedNote = NoteContentCodec.decodeFromItem(item)
            noteViewModel.addNote(
                content = decodedNote.content,
                title = item.title,
                tags = decodedNote.tags,
                isMarkdown = decodedNote.isMarkdown,
                isFavorite = item.isFavorite,
                categoryId = targetCategoryId,
                imagePaths = item.imagePaths,
                keepassDatabaseId = targetKeepassDatabaseId,
                keepassGroupPath = targetKeepassGroupPath,
                bitwardenVaultId = targetBitwardenVaultId,
                bitwardenFolderId = targetBitwardenFolderId
            )
            successCount++
            reportProgress()
        }

        aggregateSelection.bankCards.forEach { item ->
            val bankCardViewModel = aggregateUiState.bankCardViewModel
            if (bankCardViewModel == null) {
                failedCount++
                reportProgress()
                return@forEach
            }
            if (isBastionLocalTarget) {
                if (bankCardViewModel.copyCardToBastionLocal(item, targetCategoryId) != null) {
                    successCount++
                } else {
                    failedCount++
                }
                reportProgress()
                return@forEach
            }

            val cardData = bankCardViewModel.parseCardData(item.itemData)
            if (cardData == null) {
                failedCount++
                reportProgress()
                return@forEach
            }
            bankCardViewModel.addCard(
                title = item.title,
                cardData = cardData,
                notes = item.notes,
                isFavorite = item.isFavorite,
                imagePaths = item.imagePaths,
                categoryId = targetCategoryId,
                keepassDatabaseId = targetKeepassDatabaseId,
                keepassGroupPath = targetKeepassGroupPath,
                bitwardenVaultId = targetBitwardenVaultId,
                bitwardenFolderId = targetBitwardenFolderId
            )
            successCount++
            reportProgress()
        }

        aggregateSelection.documents.forEach { item ->
            val documentViewModel = aggregateUiState.documentViewModel
            if (documentViewModel == null) {
                failedCount++
                reportProgress()
                return@forEach
            }
            if (isBastionLocalTarget) {
                if (documentViewModel.copyDocumentToBastionLocal(item, targetCategoryId) != null) {
                    successCount++
                } else {
                    failedCount++
                }
                reportProgress()
                return@forEach
            }

            val documentData = documentViewModel.parseDocumentData(item.itemData)
            if (documentData == null) {
                failedCount++
                reportProgress()
                return@forEach
            }
            documentViewModel.addDocument(
                title = item.title,
                documentData = documentData,
                notes = item.notes,
                isFavorite = item.isFavorite,
                imagePaths = item.imagePaths,
                categoryId = targetCategoryId,
                keepassDatabaseId = targetKeepassDatabaseId,
                keepassGroupPath = targetKeepassGroupPath,
                bitwardenVaultId = targetBitwardenVaultId,
                bitwardenFolderId = targetBitwardenFolderId
            )
            successCount++
            reportProgress()
        }

        aggregateSelection.billingAddresses.forEach { item ->
            val billingAddressViewModel = aggregateUiState.billingAddressViewModel
            if (billingAddressViewModel == null) {
                failedCount++
                reportProgress()
                return@forEach
            }
            val createdId = when {
                isBastionLocalTarget ->
                    billingAddressViewModel.copyAddressToBastionLocal(item, targetCategoryId)
                else -> null
            }
            if (createdId != null) {
                successCount++
            } else {
                failedCount++
            }
            reportProgress()
        }

    } else {
        val oldStates = selectedEntries.map(::toLocationState)
        val newStates = selectedEntries.map { toMovedLocationState(it, target) }
        val recreatedEntries = mutableListOf<TimelinePasswordRecreatedEntry>()
        val decryptedPasswordSnapshot = selectedEntries
            .mapNotNull { entry ->
                runCatchingObserved { securityManager.decryptData(entry.password) }
                    .getOrNull()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { plain -> entry.password to plain }
            }
            .toMap()
        var passwordProgressHandledByCallback = false
        when {
            target == UnifiedMoveCategoryTarget.Uncategorized -> {
                try {
                    val keepassEntries = selectedEntries.filter { it.isKeePassEntry() }
                    val bitwardenEntries = selectedEntries.filter { it.isBitwardenEntry() }
                    val localIds = selectedEntries.filter { it.isLocalOnlyEntry() }.map { it.id }

                    if (keepassEntries.isNotEmpty()) {
                        val keepassIds = keepassEntries.map { it.id }
                        val result = viewModel.moveKeePassPasswordsToBastionCategoryAwait(
                            ids = keepassIds,
                            categoryId = null
                        )
                        if (result.isSuccess) {
                            viewModel.unarchivePasswordsAwait(keepassIds)
                            successCount += keepassIds.size
                        } else {
                            failedCount += keepassEntries.size
                        }
                    }

                    bitwardenEntries.forEach { entry ->
                        val result = viewModel.moveBitwardenPasswordToBastionLocal(entry, null)
                        if (result.isSuccess) {
                            recreatedEntries += TimelinePasswordRecreatedEntry(
                                sourceEntryId = entry.id,
                                recreatedEntryId = result.getOrThrow()
                            )
                            successCount++
                        } else {
                            failedCount++
                        }
                    }

                    if (localIds.isNotEmpty()) {
                        viewModel.unarchivePasswordsAwait(localIds)
                        viewModel.movePasswordsToCategoryAwait(localIds, null)
                        successCount += localIds.size
                    }
                } catch (_: Exception) {
                    failedCount += selectedEntries.size
                }
            }

            target is UnifiedMoveCategoryTarget.BastionCategory -> {
                try {
                    val keepassEntries = selectedEntries.filter { it.isKeePassEntry() }
                    val bitwardenEntries = selectedEntries.filter { it.isBitwardenEntry() }
                    val localIds = selectedEntries.filter { it.isLocalOnlyEntry() }.map { it.id }

                    if (keepassEntries.isNotEmpty()) {
                        val keepassIds = keepassEntries.map { it.id }
                        val result = viewModel.moveKeePassPasswordsToBastionCategoryAwait(
                            ids = keepassIds,
                            categoryId = target.categoryId
                        )
                        if (result.isSuccess) {
                            viewModel.unarchivePasswordsAwait(keepassIds)
                            successCount += keepassIds.size
                        } else {
                            failedCount += keepassEntries.size
                        }
                    }

                    bitwardenEntries.forEach { entry ->
                        val result = viewModel.moveBitwardenPasswordToBastionLocal(
                            entry = entry,
                            categoryId = target.categoryId
                        )
                        if (result.isSuccess) {
                            recreatedEntries += TimelinePasswordRecreatedEntry(
                                sourceEntryId = entry.id,
                                recreatedEntryId = result.getOrThrow()
                            )
                            successCount++
                        } else {
                            failedCount++
                        }
                    }

                    if (localIds.isNotEmpty()) {
                        viewModel.unarchivePasswordsAwait(localIds)
                        viewModel.movePasswordsToCategoryAwait(localIds, target.categoryId)
                        successCount += localIds.size
                    }
                } catch (_: Exception) {
                    failedCount += selectedEntries.size
                }
            }

            target is UnifiedMoveCategoryTarget.BitwardenVaultTarget -> {
                if (selectedIds.isNotEmpty()) {
                    viewModel.unarchivePasswordsAwait(selectedIds)
                    viewModel.movePasswordsToBitwardenFolderAwait(selectedIds, target.vaultId, "")
                    successCount += selectedEntries.size
                }
            }

            target is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> {
                if (selectedIds.isNotEmpty()) {
                    viewModel.unarchivePasswordsAwait(selectedIds)
                    viewModel.movePasswordsToBitwardenFolderAwait(selectedIds, target.vaultId, target.folderId)
                    successCount += selectedEntries.size
                }
            }

            target is UnifiedMoveCategoryTarget.KeePassDatabaseTarget -> {
                try {
                    val passwordProgressBase = processedCount
                    passwordProgressHandledByCallback = true
                    val result = localKeePassViewModel.movePasswordEntriesToKdbx(
                        databaseId = target.databaseId,
                        groupPath = null,
                        entries = selectedEntries,
                        decryptPassword = { encrypted ->
                            decryptedPasswordSnapshot[encrypted]
                                ?: securityManager.decryptData(encrypted)
                                ?: ""
                        },
                        onItemProcessed = { processed, total ->
                            val passwordTotal = selectedEntries.size.takeIf { it > 0 } ?: total
                            val normalizedProcessed = processed.coerceIn(0, passwordTotal)
                            val absoluteProcessed =
                                (passwordProgressBase + normalizedProcessed).coerceAtMost(totalCount)
                            processedCount = absoluteProcessed
                            onProgress?.invoke(absoluteProcessed, totalCount)
                        }
                    )
                    if (result.isSuccess) {
                        viewModel.unarchivePasswordsAwait(selectedIds)
                        viewModel.movePasswordsToKeePassDatabaseAwait(selectedIds, target.databaseId)
                        successCount += selectedEntries.size
                    } else {
                        failedCount += selectedEntries.size
                    }
                } catch (_: Exception) {
                    failedCount += selectedEntries.size
                }
            }

            target is UnifiedMoveCategoryTarget.KeePassGroupTarget -> {
                try {
                    val passwordProgressBase = processedCount
                    passwordProgressHandledByCallback = true
                    val result = localKeePassViewModel.movePasswordEntriesToKdbx(
                        databaseId = target.databaseId,
                        groupPath = target.groupPath,
                        entries = selectedEntries,
                        decryptPassword = { encrypted ->
                            decryptedPasswordSnapshot[encrypted]
                                ?: securityManager.decryptData(encrypted)
                                ?: ""
                        },
                        onItemProcessed = { processed, total ->
                            val passwordTotal = selectedEntries.size.takeIf { it > 0 } ?: total
                            val normalizedProcessed = processed.coerceIn(0, passwordTotal)
                            val absoluteProcessed =
                                (passwordProgressBase + normalizedProcessed).coerceAtMost(totalCount)
                            processedCount = absoluteProcessed
                            onProgress?.invoke(absoluteProcessed, totalCount)
                        }
                    )
                    if (result.isSuccess) {
                        viewModel.unarchivePasswordsAwait(selectedIds)
                        viewModel.movePasswordsToKeePassGroupAwait(selectedIds, target.databaseId, target.groupPath)
                        successCount += selectedEntries.size
                    } else {
                        failedCount += selectedEntries.size
                    }
                } catch (_: Exception) {
                    failedCount += selectedEntries.size
                }
            }
        }

        if (!passwordProgressHandledByCallback) {
            reportProgress(selectedEntries.size)
        }

        aggregateSelection.totpItems.forEach { item ->
            val totpViewModel = aggregateUiState.totpViewModel
            if (totpViewModel == null) {
                failedCount++
                reportProgress()
                return@forEach
            }
            if (isBastionLocalTarget) {
                val moved = if (item.isLocalOnlyItem()) {
                    totpViewModel.moveToCategory(listOf(item.id), targetCategoryId)
                    true
                } else {
                    totpViewModel.moveTotpToBastionLocal(item, targetCategoryId).isSuccess
                }
                if (moved) successCount++ else failedCount++
                reportProgress()
            }
        }

        aggregateSelection.notes.forEach { item ->
            val noteViewModel = aggregateUiState.noteViewModel
            if (noteViewModel == null) {
                failedCount++
                reportProgress()
                return@forEach
            }
            val moved = if (isBastionLocalTarget) {
                if (item.isLocalOnlyItem()) {
                    noteViewModel.moveNoteToStorage(
                        item = item,
                        categoryId = targetCategoryId,
                        keepassDatabaseId = null,
                        keepassGroupPath = null,
                        bitwardenVaultId = null,
                        bitwardenFolderId = null
                    )
                } else {
                    noteViewModel.moveNoteToBastionLocal(item, targetCategoryId).isSuccess
                }
            } else {
                noteViewModel.moveNoteToStorage(
                    item = item,
                    categoryId = targetCategoryId,
                    keepassDatabaseId = targetKeepassDatabaseId,
                    keepassGroupPath = targetKeepassGroupPath,
                    bitwardenVaultId = targetBitwardenVaultId,
                    bitwardenFolderId = targetBitwardenFolderId
                )
            }
            if (moved) successCount++ else failedCount++
            reportProgress()
        }

        aggregateSelection.bankCards.forEach { item ->
            val bankCardViewModel = aggregateUiState.bankCardViewModel
            if (bankCardViewModel == null) {
                failedCount++
                reportProgress()
                return@forEach
            }
            if (isBastionLocalTarget) {
                val moved = if (item.isLocalOnlyItem()) {
                    bankCardViewModel.moveCardToStorage(
                        id = item.id,
                        categoryId = targetCategoryId,
                        keepassDatabaseId = null,
                        keepassGroupPath = null,
                        bitwardenVaultId = null,
                        bitwardenFolderId = null
                    )
                    true
                } else {
                    bankCardViewModel.moveCardToBastionLocal(item, targetCategoryId).isSuccess
                }
                if (moved) successCount++ else failedCount++
                reportProgress()
            } else {
                val moved = bankCardViewModel.moveCardToStorage(
                    id = item.id,
                    categoryId = targetCategoryId,
                    keepassDatabaseId = targetKeepassDatabaseId,
                    keepassGroupPath = targetKeepassGroupPath,
                    bitwardenVaultId = targetBitwardenVaultId,
                    bitwardenFolderId = targetBitwardenFolderId
                )
                if (moved) successCount++ else failedCount++
                reportProgress()
            }
        }

        aggregateSelection.documents.forEach { item ->
            val documentViewModel = aggregateUiState.documentViewModel
            if (documentViewModel == null) {
                failedCount++
                reportProgress()
                return@forEach
            }
            if (isBastionLocalTarget) {
                val moved = if (item.isLocalOnlyItem()) {
                    documentViewModel.moveDocumentToStorage(
                        id = item.id,
                        categoryId = targetCategoryId,
                        keepassDatabaseId = null,
                        keepassGroupPath = null,
                        bitwardenVaultId = null,
                        bitwardenFolderId = null
                    )
                    true
                } else {
                    documentViewModel.moveDocumentToBastionLocal(item, targetCategoryId).isSuccess
                }
                if (moved) successCount++ else failedCount++
                reportProgress()
            } else {
                val moved = documentViewModel.moveDocumentToStorage(
                    id = item.id,
                    categoryId = targetCategoryId,
                    keepassDatabaseId = targetKeepassDatabaseId,
                    keepassGroupPath = targetKeepassGroupPath,
                    bitwardenVaultId = targetBitwardenVaultId,
                    bitwardenFolderId = targetBitwardenFolderId
                )
                if (moved) successCount++ else failedCount++
                reportProgress()
            }
        }

        aggregateSelection.billingAddresses.forEach { item ->
            val billingAddressViewModel = aggregateUiState.billingAddressViewModel
            if (billingAddressViewModel == null) {
                failedCount++
                reportProgress()
                return@forEach
            }
            val moved = when {
                isBastionLocalTarget ->
                    billingAddressViewModel.moveAddressToStorage(
                        id = item.id,
                        categoryId = targetCategoryId
                    )
                else -> false
            }
            if (moved) successCount++ else failedCount++
            reportProgress()
        }

        if (!isBastionLocalTarget) {
            aggregateSelection.totpItems.forEach { item ->
                val totpViewModel = aggregateUiState.totpViewModel
                if (totpViewModel == null) {
                    failedCount++
                    reportProgress()
                    return@forEach
                }
                val moved = totpViewModel.moveTotpToStorage(
                    id = item.id,
                    categoryId = targetCategoryId,
                    keepassDatabaseId = targetKeepassDatabaseId,
                    keepassGroupPath = targetKeepassGroupPath,
                    bitwardenVaultId = targetBitwardenVaultId,
                    bitwardenFolderId = targetBitwardenFolderId
                )
                if (moved) {
                    successCount++
                } else {
                    failedCount++
                }
                reportProgress()
            }
        }

        if (selectedEntries.isNotEmpty()) {
            val targetLabel = buildMoveTargetLabel(
                context = context,
                target = target,
                categories = categories,
                keepassDatabases = keepassDatabases
            )
            logPasswordBatchMoveTimeline(
                context = context,
                selectedEntries = selectedEntries,
                oldStates = oldStates,
                newStates = newStates,
                recreatedEntries = recreatedEntries,
                targetLabel = targetLabel
            )
        }
    }

    val movablePasskeys = aggregateSelection.passkeys
        .filter { it.boundPasswordId == null && it.syncStatus != "REFERENCE" }
    val blockedPasskeysByType = aggregateSelection.passkeys.size - movablePasskeys.size
    if (blockedPasskeysByType > 0) {
        failedCount += blockedPasskeysByType
        reportProgress(blockedPasskeysByType)
    }
    movablePasskeys.forEach { passkey ->
        val updateResult = applyPasswordPagePasskeyStorageTarget(
            passkey = passkey,
            target = target,
            bitwardenRepository = bitwardenRepository,
            context = context
        )
        when {
            updateResult.isSuccess -> {
                val updatedPasskey = updateResult.getOrThrow()
                val persisted = aggregateUiState.passkeyViewModel?.updatePasskey(updatedPasskey)
                if (persisted?.isSuccess == true) {
                    val queueDelete = queuePasswordPagePasskeyBitwardenDeleteAfterMove(
                        source = passkey,
                        target = target,
                        bitwardenRepository = bitwardenRepository
                    )
                    if (queueDelete.isFailure) {
                        android.util.Log.e(
                            "PasswordBatchMoveMixed",
                            "Bitwarden source delete failed after passkey move: ${queueDelete.exceptionOrNull()?.message}"
                        )
                        failedCount++
                    } else {
                        successCount++
                    }
                } else {
                    failedCount++
                }
            }

            updateResult.exceptionOrNull() is PasswordPagePasskeyBitwardenMoveBlockedException -> {
                blockedPasskeyCount++
                failedCount++
            }

            else -> failedCount++
        }
        reportProgress()
    }

    if (copiedPasswordIds.isNotEmpty()) {
        logPasswordBatchCopyTimeline(
            context = context,
            copiedEntryIds = copiedPasswordIds
        )
    }

    return MixedPasswordBatchMoveResult(
        successCount = successCount,
        failedCount = failedCount,
        blockedPasskeyCount = blockedPasskeyCount,
        copiedPasswordIds = copiedPasswordIds
    )
}

private suspend fun PasswordViewModel.addPasswordEntryWithResultAwait(
    entry: PasswordEntry
): Long? {
    val deferred = CompletableDeferred<Long?>()
    addPasswordEntryWithResult(
        entry = entry,
        includeDetailedLog = false,
        // 来源 entry 的 password 已经是 Bastion 加密过的密文，不能再加密一次
        passwordAlreadyEncrypted = true,
        // mixed batch 的 target 已经在 buildCopiedEntryForTarget 里明确指定，不走 categoryFilter
        skipCategoryBinding = true
    ) { createdId ->
        deferred.complete(createdId)
    }
    return deferred.await()
}

private class PasswordPagePasskeyBitwardenMoveBlockedException :
    IllegalStateException("Passkey cannot be migrated to Bitwarden")

internal suspend fun applyPasswordPagePasskeyStorageTarget(
    passkey: PasskeyEntry,
    target: UnifiedMoveCategoryTarget,
    bitwardenRepository: BitwardenRepository,
    context: Context
): Result<PasskeyEntry> {
    val currentVaultId = passkey.bitwardenVaultId
    val currentCipherId = passkey.bitwardenCipherId
    val targetVaultId = when (target) {
        is UnifiedMoveCategoryTarget.BitwardenVaultTarget -> target.vaultId
        is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> target.vaultId
        else -> null
    }

    if (targetVaultId != null) {
        val canMoveToBitwarden = withContext(Dispatchers.IO) {
            isPasswordPagePasskeyMigratableToBitwarden(context, passkey)
        }
    if (!canMoveToBitwarden) {
            return Result.failure(PasswordPagePasskeyBitwardenMoveBlockedException())
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
            keepassDatabaseId = null
        )

        is UnifiedMoveCategoryTarget.BitwardenFolderTarget -> passkey.copy(
            bitwardenVaultId = target.vaultId,
            bitwardenFolderId = target.folderId,
            keepassGroupPath = null,
            keepassDatabaseId = null
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

private suspend fun queuePasswordPagePasskeyBitwardenDeleteAfterMove(
    source: PasskeyEntry,
    target: UnifiedMoveCategoryTarget,
    bitwardenRepository: BitwardenRepository
): Result<Unit> {
    val currentVaultId = source.bitwardenVaultId
    val currentCipherId = source.bitwardenCipherId
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
        vaultId = currentVaultId,
        cipherId = currentCipherId!!,
        itemType = BitwardenPendingOperation.ITEM_TYPE_PASSKEY
    )
}

private fun isPasswordPagePasskeyMigratableToBitwarden(context: Context, passkey: PasskeyEntry): Boolean {
    if (passkey.passkeyMode != PasskeyEntry.MODE_BW_COMPAT) return false
    if (passkey.syncStatus == "REFERENCE") return false
    return PasskeyPrivateKeyStore.hasBitwardenCompatiblePrivateKey(context, passkey.privateKeyAlias)
}
