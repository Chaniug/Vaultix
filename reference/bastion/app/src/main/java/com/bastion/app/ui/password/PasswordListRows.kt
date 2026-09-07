package com.bastion.app.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.bastion.app.R
import com.bastion.app.data.AppSettings
import com.bastion.app.data.PasswordEntry
import com.bastion.app.data.PasswordPageContentType
import com.bastion.app.data.PasswordSwipeSelectionMode
import com.bastion.app.notes.domain.NoteContentCodec
import com.bastion.app.ui.cardwallet.CardBrandIcon
import com.bastion.app.ui.password.PasswordAggregateWalletItemType
import com.bastion.app.ui.password.PasswordGroupListItemUi
import com.bastion.app.ui.password.PasswordListAggregateConfig
import com.bastion.app.ui.password.PasswordListCardBadge
import com.bastion.app.ui.password.PasswordManualStackGroup
import com.bastion.app.ui.password.PasswordManualStackGroupListItemUi
import com.bastion.app.ui.password.PasswordPageCardItemUi
import com.bastion.app.ui.password.PasswordPageListItemUi
import com.bastion.app.ui.password.PasswordListSingleCardItem
import com.bastion.app.ui.password.PasswordSupplementaryListItemUi
import com.bastion.app.ui.password.StackCardMode
import com.bastion.app.ui.password.StackedPasswordGroup
import com.bastion.app.ui.password.passwordSelectionKey
import com.bastion.app.ui.password.selectionKeysForPasswords
import com.bastion.app.ui.password.toPasswordPageCardItemUi
import com.bastion.app.viewmodel.PasswordViewModel

// LazyColumn 复用池的类型标识（供下方 items 的 contentType 使用）。
// 取值刻意用 Int 常量：卡片类直接用 PasswordPageContentType 枚举，两者不会相等，
// 因此分组头/卡片/补充项各自落在独立复用池，互不串用。
private const val CONTENT_TYPE_GROUP = 0
private const val CONTENT_TYPE_MANUAL_STACK_GROUP = 1
private const val CONTENT_TYPE_SUPPLEMENTARY = 2

internal fun LazyListScope.passwordPageListRows(
    isListScrolling: Boolean,
    passwordPageListItems: List<PasswordPageListItemUi>,
    effectiveStackCardMode: StackCardMode,
    expandedGroups: Set<String>,
    itemToDelete: PasswordEntry?,
    onItemToDeleteChange: (PasswordEntry?) -> Unit,
    isSelectionMode: Boolean,
    onSelectionModeChange: (Boolean) -> Unit,
    selectedItemKeys: Set<String>,
    onSelectedItemKeysChange: (Set<String>) -> Unit,
    swipeSelectionAnchorKey: String?,
    onSwipeSelectionAnchorKeyChange: (String?) -> Unit,
    selectedPasswords: Set<Long>,
    showBatchDeleteDialog: Boolean,
    onShowBatchDeleteDialogChange: (Boolean) -> Unit,
    viewModel: PasswordViewModel,
    haptic: com.bastion.app.ui.haptic.HapticFeedbackHelper,
    onPasswordClick: (PasswordEntry) -> Unit,
    appSettings: AppSettings,
    coroutineScope: CoroutineScope,
    context: Context,
    passwordEntries: List<PasswordEntry>,
    aggregateConfig: PasswordListAggregateConfig?,
    aggregateUiState: PasswordListAggregateUiState,
    decryptAuthenticatorKey: ((String) -> String)? = null
) {
    val orderedSelectionKeys = passwordPageListItems.flatMap { item ->
        when (item) {
            is PasswordGroupListItemUi ->
                selectionKeysForPasswords(item.passwords.map(PasswordEntry::id)).toList()
            is PasswordManualStackGroupListItemUi ->
                item.cards.map(PasswordPageCardItemUi::key)
            is PasswordSupplementaryListItemUi ->
                listOf(item.key)
        }
    }

    fun toggleSelectionForKey(key: String) {
        onSelectedItemKeysChange(
            if (key in selectedItemKeys) {
                selectedItemKeys - key
            } else {
                selectedItemKeys + key
            }
        )
        onSwipeSelectionAnchorKeyChange(key)
    }

    fun toggleSelectionForCards(cards: List<PasswordPageCardItemUi>) {
        val cardKeys = cards.mapTo(linkedSetOf(), PasswordPageCardItemUi::key)
        val allSelected = cardKeys.all { it in selectedItemKeys }
        onSelectedItemKeysChange(
            if (allSelected) {
                selectedItemKeys - cardKeys
            } else {
                selectedItemKeys + cardKeys
            }
        )
        if (!allSelected) {
            cardKeys.firstOrNull()?.let(onSwipeSelectionAnchorKeyChange)
        }
    }

    fun selectSwipeRangeTo(targetKey: String) {
        if (appSettings.passwordSwipeSelectionMode != PasswordSwipeSelectionMode.CONTINUOUS) {
            toggleSelectionForKey(targetKey)
            return
        }

        val anchorKey = swipeSelectionAnchorKey
        val anchorIndex = orderedSelectionKeys.indexOf(anchorKey)
        val targetIndex = orderedSelectionKeys.indexOf(targetKey)
        if (anchorKey == null || anchorIndex == -1 || targetIndex == -1) {
            onSelectedItemKeysChange(setOf(targetKey))
            onSwipeSelectionAnchorKeyChange(targetKey)
            return
        }

        val range = if (anchorIndex <= targetIndex) {
            orderedSelectionKeys.subList(anchorIndex, targetIndex + 1)
        } else {
            orderedSelectionKeys.subList(targetIndex, anchorIndex + 1)
        }
        onSelectedItemKeysChange(range.toSet())
    }

    fun selectSwipeRangeToKeys(targetKeys: Set<String>) {
        if (targetKeys.isEmpty()) return

        if (appSettings.passwordSwipeSelectionMode != PasswordSwipeSelectionMode.CONTINUOUS) {
            val allSelected = targetKeys.all { it in selectedItemKeys }
            onSelectedItemKeysChange(
                if (allSelected) {
                    selectedItemKeys - targetKeys
                } else {
                    onSwipeSelectionAnchorKeyChange(targetKeys.firstOrNull())
                    selectedItemKeys + targetKeys
                }
            )
            return
        }

        val orderedTargetKeys = orderedSelectionKeys.filter { it in targetKeys }
        val targetKey = orderedTargetKeys.lastOrNull() ?: targetKeys.lastOrNull() ?: return
        val anchorKey = swipeSelectionAnchorKey
        val anchorIndex = orderedSelectionKeys.indexOf(anchorKey)
        val targetIndex = orderedSelectionKeys.indexOf(targetKey)
        if (anchorKey == null || anchorIndex == -1 || targetIndex == -1) {
            onSelectedItemKeysChange(targetKeys)
            orderedTargetKeys.firstOrNull()?.let(onSwipeSelectionAnchorKeyChange)
            return
        }

        val range = if (anchorIndex <= targetIndex) {
            orderedSelectionKeys.subList(anchorIndex, targetIndex + 1)
        } else {
            orderedSelectionKeys.subList(targetIndex, anchorIndex + 1)
        }
        onSelectedItemKeysChange((range + targetKeys).toSet())
    }

    fun openCard(card: PasswordPageCardItemUi) {
        when (card.type) {
            PasswordPageContentType.PASSWORD ->
                card.passwordId?.let { passwordId ->
                    passwordEntries.firstOrNull { it.id == passwordId }?.let(onPasswordClick)
                }

            PasswordPageContentType.AUTHENTICATOR ->
                card.secureItemId?.let { aggregateConfig?.onOpenTotp?.invoke(it) }

            PasswordPageContentType.CARD_WALLET ->
                card.secureItemId?.let { itemId ->
                    when (card.walletItemType) {
                        PasswordAggregateWalletItemType.BANK_CARD ->
                            aggregateConfig?.onOpenBankCard?.invoke(itemId)
                        PasswordAggregateWalletItemType.DOCUMENT ->
                            aggregateConfig?.onOpenDocument?.invoke(itemId)
                        PasswordAggregateWalletItemType.BILLING_ADDRESS ->
                            aggregateConfig?.onOpenBillingAddress?.invoke(itemId)
                        null -> Unit
                    }
                }

            PasswordPageContentType.NOTE ->
                aggregateConfig?.onOpenNote?.invoke(card.secureItemId)

            PasswordPageContentType.PASSKEY ->
                card.passkeyRecordId?.let { aggregateConfig?.onOpenPasskey?.invoke(it) }
        }
    }

    fun requestDeleteForCards(cards: List<PasswordPageCardItemUi>) {
        haptic.performWarning()
        if (!isSelectionMode) {
            onSelectionModeChange(true)
        }
        onSelectedItemKeysChange(cards.mapTo(linkedSetOf(), PasswordPageCardItemUi::key))
        if (!showBatchDeleteDialog) {
            onShowBatchDeleteDialogChange(true)
        }
    }

    fun toggleFavoriteForCard(card: PasswordPageCardItemUi) {
        when (card.type) {
            PasswordPageContentType.PASSWORD ->
                card.passwordId?.let { passwordId ->
                    passwordEntries.firstOrNull { it.id == passwordId }?.let { password ->
                        viewModel.toggleFavorite(password.id, !password.isFavorite)
                    }
                }

            PasswordPageContentType.AUTHENTICATOR ->
                card.secureItemId?.let {
                    aggregateUiState.totpViewModel?.toggleFavorite(it, !card.entry.isFavorite)
                }

            PasswordPageContentType.CARD_WALLET ->
                card.secureItemId?.let { id ->
                    when (card.walletItemType) {
                        PasswordAggregateWalletItemType.BANK_CARD ->
                            aggregateUiState.bankCardViewModel?.toggleFavorite(id)
                        PasswordAggregateWalletItemType.DOCUMENT ->
                            aggregateUiState.documentViewModel?.toggleFavorite(id)
                        PasswordAggregateWalletItemType.BILLING_ADDRESS ->
                            aggregateUiState.billingAddressViewModel?.toggleFavorite(id)
                        null -> Unit
                    }
                }

            PasswordPageContentType.NOTE ->
                card.secureItemId?.let { noteId ->
                    aggregateUiState.notes.firstOrNull { it.id == noteId }?.let { note ->
                        val decoded = NoteContentCodec.decodeFromItem(note)
                        aggregateUiState.noteViewModel?.updateNote(
                            id = note.id,
                            content = decoded.content,
                            title = note.title,
                            tags = decoded.tags,
                            isMarkdown = decoded.isMarkdown,
                            isFavorite = !note.isFavorite,
                            createdAt = note.createdAt,
                            categoryId = note.categoryId,
                            imagePaths = note.imagePaths,
                            keepassDatabaseId = note.keepassDatabaseId,
                            keepassGroupPath = note.keepassGroupPath,
                            bitwardenVaultId = note.bitwardenVaultId,
                            bitwardenFolderId = note.bitwardenFolderId
                        )
                    }
                }

            PasswordPageContentType.PASSKEY -> Unit
        }
    }

    items(
        items = passwordPageListItems,
        key = { item -> item.key },
        // contentType：告诉 Compose 复用池按「结构类型」分池。
        // 本列表混合了分组头与 5 种内容卡片（密码/卡包/笔记/验证码/通行证），
        // 不传时复用池只有一类，滚动时可能拿错类型的 composition 重建。
        // 用 Int 常量 + 枚举（而非字符串）避免每次比较都产生临时对象。
        contentType = { item ->
            when (item) {
                is PasswordGroupListItemUi -> CONTENT_TYPE_GROUP
                is PasswordManualStackGroupListItemUi -> CONTENT_TYPE_MANUAL_STACK_GROUP
                is PasswordSupplementaryListItemUi -> CONTENT_TYPE_SUPPLEMENTARY
                is PasswordPageCardItemUi -> item.type
            }
        }
    ) { listItem ->
        when (listItem) {
            is PasswordGroupListItemUi -> {
                val groupKey = listItem.groupKey
                val passwords = listItem.passwords
                val isExpanded = when (effectiveStackCardMode) {
                    StackCardMode.AUTO -> expandedGroups.contains(groupKey)
                    StackCardMode.ALWAYS_EXPANDED -> true
                }

                StackedPasswordGroup(
                    website = groupKey,
                    passwords = passwords,
                    displayTitle = listItem.displayTitle,
                    isExpanded = isExpanded,
                    stackCardMode = effectiveStackCardMode,
                    enableSharedBounds = false,
                    swipedItemId = itemToDelete?.id,
                    onToggleExpand = {
                        if (effectiveStackCardMode == StackCardMode.AUTO) {
                            viewModel.toggleExpandedGroup(groupKey)
                        }
                    },
                    onPasswordClick = { password ->
                        if (isSelectionMode) {
                            toggleSelectionForKey(passwordSelectionKey(password.id))
                        } else {
                            onPasswordClick(password)
                        }
                    },
                    onSwipeLeft = { password ->
                        if (itemToDelete == null) {
                            haptic.performWarning()
                            onItemToDeleteChange(password)
                        }
                    },
                    onSwipeRight = { password ->
                        haptic.performSuccess()
                        if (!isSelectionMode) {
                            onSelectionModeChange(true)
                        }
                        selectSwipeRangeTo(passwordSelectionKey(password.id))
                    },
                    onGroupSwipeRight = { groupPasswords ->
                        haptic.performSuccess()
                        if (!isSelectionMode) {
                            onSelectionModeChange(true)
                        }
                        val groupSelectionKeys = selectionKeysForPasswords(
                            groupPasswords.map(PasswordEntry::id)
                        )
                        selectSwipeRangeToKeys(groupSelectionKeys)
                    },

                    onToggleFavorite = { password ->
                        viewModel.toggleFavorite(password.id, !password.isFavorite)
                    },
                    onToggleGroupFavorite = {
                        coroutineScope.launch {
                            val allFavorited = passwords.all { it.isFavorite }
                            val newState = !allFavorited
                            passwords.forEach { password ->
                                viewModel.toggleFavorite(password.id, newState)
                            }
                            val message = if (newState) {
                                context.getString(R.string.group_favorited, passwords.size)
                            } else {
                                context.getString(R.string.group_unfavorited, passwords.size)
                            }
                            Toast.makeText(
                                context,
                                message,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    onToggleGroupCover = { password ->
                        coroutineScope.launch {
                            val websiteKey = password.website.ifBlank {
                                context.getString(R.string.filter_uncategorized)
                            }
                            val newCoverState = !password.isGroupCover
                            if (newCoverState) {
                                val currentIndex = passwords.indexOfFirst { it.id == password.id }
                                if (currentIndex > 0) {
                                    val reordered = passwords.toMutableList()
                                    val movedPassword = reordered.removeAt(currentIndex)
                                    reordered.add(0, movedPassword)
                                    val firstItemInGroup = passwordEntries.firstOrNull {
                                        it.website.ifBlank {
                                            context.getString(R.string.filter_uncategorized)
                                        } == websiteKey
                                    } ?: return@launch
                                    val startSortOrder = passwordEntries.indexOf(firstItemInGroup)
                                    viewModel.updateSortOrders(
                                        reordered.mapIndexed { idx, entry ->
                                            entry.id to (startSortOrder + idx)
                                        }
                                    )
                                }
                            }
                            viewModel.toggleGroupCover(password.id, websiteKey, newCoverState)
                        }
                    },
                    isSelectionMode = isSelectionMode,
                    selectedPasswords = selectedPasswords,
                    onToggleSelection = { id ->
                        toggleSelectionForKey(passwordSelectionKey(id))
                    },
                    onOpenMultiPasswordDialog = { passwords ->
                        onPasswordClick(passwords.first())
                    },
                    onLongClick = { password ->
                        haptic.performLongPress()
                        if (!isSelectionMode) {
                            onSelectionModeChange(true)
                            val selectionKey = passwordSelectionKey(password.id)
                            onSelectedItemKeysChange(setOf(selectionKey))
                            onSwipeSelectionAnchorKeyChange(selectionKey)
                        }
                    },
                    iconCardsEnabled = appSettings.iconCardsEnabled && appSettings.passwordPageIconEnabled,
                    unmatchedIconHandlingStrategy = appSettings.unmatchedIconHandlingStrategy,
                    passwordCardDisplayMode = appSettings.passwordCardDisplayMode,
                    passwordCardDisplayFields = appSettings.passwordCardDisplayFields,
                    showAuthenticator = appSettings.passwordCardShowAuthenticator,
                    hideOtherContentWhenAuthenticator = appSettings.passwordCardHideOtherContentWhenAuthenticator,
                    totpTimeOffsetSeconds = appSettings.totpTimeOffset,
                    smoothAuthenticatorProgress = appSettings.validatorSmoothProgress && !isListScrolling,
                    decryptAuthenticatorKey = decryptAuthenticatorKey
                )
            }

            is PasswordManualStackGroupListItemUi -> {
                PasswordManualStackGroup(
                    groupKey = listItem.groupKey,
                    cards = listItem.cards,
                    isSelectionMode = isSelectionMode,
                    selectedItemKeys = selectedItemKeys,
                    onCardClick = ::openCard,
                    onToggleCardSelection = { card ->
                        haptic.performSuccess()
                        if (!isSelectionMode) {
                            onSelectionModeChange(true)
                        }
                        toggleSelectionForKey(card.key)
                    },
                    onToggleGroupSelection = { cards ->
                        haptic.performSuccess()
                        if (!isSelectionMode) {
                            onSelectionModeChange(true)
                        }
                        toggleSelectionForCards(cards)
                    },
                    onRequestDelete = ::requestDeleteForCards,
                    onToggleFavorite = ::toggleFavoriteForCard,
                    iconCardsEnabled = appSettings.iconCardsEnabled && appSettings.passwordPageIconEnabled,
                    unmatchedIconHandlingStrategy = appSettings.unmatchedIconHandlingStrategy,
                    passwordCardDisplayMode = appSettings.passwordCardDisplayMode,
                    passwordCardDisplayFields = appSettings.passwordCardDisplayFields,
                    showAuthenticator = appSettings.passwordCardShowAuthenticator,
                    hideOtherContentWhenAuthenticator = appSettings.passwordCardHideOtherContentWhenAuthenticator,
                    totpTimeOffsetSeconds = appSettings.totpTimeOffset,
                    smoothAuthenticatorProgress = appSettings.validatorSmoothProgress && !isListScrolling,
                    decryptAuthenticatorKey = decryptAuthenticatorKey
                )
            }

            is PasswordSupplementaryListItemUi -> {
                val item = listItem.item
                val card = item.toPasswordPageCardItemUi()
                PasswordListSingleCardItem(
                    entry = item.entry,
                    onClick = {
                        if (isSelectionMode) {
                            toggleSelectionForKey(item.key)
                        } else {
                            openCard(card)
                        }
                    },
                    onLongClick = {
                        // 【方案 A】长按只用于激活该条目的滑动删除（激活态由卡片内部维护）。
                        // 不再进入多选模式：多选操作栏已移除，长按进多选没有出口。
                        haptic.performLongPress()
                    },
                    onSwipeLeft = { requestDeleteForCards(listOf(card)) },
                    onSwipeRight = {
                        haptic.performSuccess()
                        if (!isSelectionMode) {
                            onSelectionModeChange(true)
                        }
                        selectSwipeRangeTo(item.key)
                    },
                    isSwiped = false,
                    isSelectionMode = isSelectionMode,
                    isSelected = item.key in selectedItemKeys,
                    onToggleFavorite = if (card.supportsFavorite) {
                        { toggleFavoriteForCard(card) }
                    } else {
                        null
                    },
                    unmatchedIconHandlingStrategy = aggregateUiState.cardStyle.unmatchedIconHandlingStrategy,
                    passwordCardDisplayMode = aggregateUiState.cardStyle.passwordCardDisplayMode,
                    passwordCardDisplayFields = aggregateUiState.cardStyle.passwordCardDisplayFields,
                    showAuthenticator = aggregateUiState.cardStyle.showAuthenticator,
                    hideOtherContentWhenAuthenticator = aggregateUiState.cardStyle.hideOtherContentWhenAuthenticator,
                    totpTimeOffsetSeconds = aggregateUiState.cardStyle.totpTimeOffsetSeconds,
                    smoothAuthenticatorProgress = aggregateUiState.cardStyle.smoothAuthenticatorProgress && !isListScrolling,
                    decryptAuthenticatorKey = decryptAuthenticatorKey,
                    iconCardsEnabled = aggregateUiState.cardStyle.iconCardsEnabled,
                    enableSharedBounds = false,
                    leadingIconOverride = card.bankCardBrand?.let { brand ->
                        {
                            CardBrandIcon(
                                brand = brand,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(width = 52.dp, height = 34.dp)
                            )
                        }
                    },
                    badge = PasswordListCardBadge(
                        text = item.badgeText,
                        color = item.badgeColor
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}
