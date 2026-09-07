package com.bastion.app.ui.password

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bastion.app.data.PasswordCardDisplayField
import com.bastion.app.data.PasswordEntry
import com.bastion.app.data.UnmatchedIconHandlingStrategy
import com.bastion.app.ui.gestures.SwipeActions
import com.bastion.app.ui.gestures.rememberSwipeArmState

internal data class PasswordListCardBadge(
    val text: String,
    val color: Color
)

@Composable
internal fun PasswordListSingleCardItem(
    entry: PasswordEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
    isSwiped: Boolean,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onToggleFavorite: (() -> Unit)?,
    unmatchedIconHandlingStrategy: UnmatchedIconHandlingStrategy,
    passwordCardDisplayMode: com.bastion.app.data.PasswordCardDisplayMode,
    passwordCardDisplayFields: List<PasswordCardDisplayField>,
    showAuthenticator: Boolean,
    hideOtherContentWhenAuthenticator: Boolean,
    totpTimeOffsetSeconds: Int,
    smoothAuthenticatorProgress: Boolean,
    iconCardsEnabled: Boolean,
    enableSharedBounds: Boolean,
    decryptAuthenticatorKey: ((String) -> String)? = null,
    leadingIconOverride: (@Composable () -> Unit)? = null,
    badge: PasswordListCardBadge? = null
) {
    // 【方案 A】滑动删除需先长按激活：默认锁定，列表滚动绝不会误触。
    // 激活后 3 秒无操作自动解除（逻辑统一在 SwipeArmState）。
    val armState = rememberSwipeArmState()

    SwipeActions(
        onSwipeLeft = {
            armState.disarm()
            onSwipeLeft()
        },
        onSwipeRight = onSwipeRight,
        isSwiped = isSwiped,
        enabled = armState.armed,
        // 右滑原本用于进入多选，多选入口已移除，故不再允许右滑。
        allowSwipeRight = false,
        armed = armState.armed
    ) {
        PasswordEntryCard(
            entry = entry,
            onClick = {
                armState.disarm()
                onClick()
            },
            // 长按 = 激活本条的滑动删除（不再进入多选模式）
            onLongClick = {
                armState.arm()
                onLongClick()
            },
            onToggleFavorite = onToggleFavorite,
            onToggleGroupCover = null,
            supportingBadge = badge
                ?.takeIf { it.text == "passkey" }
                ?.let { badgeData ->
                    {
                        PasswordListCornerBadge(
                            badge = badgeData,
                            modifier = Modifier.padding(
                                top = 10.dp,
                                end = when {
                                    isSelectionMode -> 44.dp
                                    onToggleFavorite != null -> 52.dp
                                    else -> 12.dp
                                }
                            )
                        )
                    }
                },
            isSelectionMode = isSelectionMode,
            isSelected = isSelected,
            canSetGroupCover = false,
            isInExpandedGroup = false,
            isSingleCard = true,
            iconCardsEnabled = iconCardsEnabled,
            unmatchedIconHandlingStrategy = unmatchedIconHandlingStrategy,
            passwordCardDisplayMode = passwordCardDisplayMode,
            passwordCardDisplayFields = passwordCardDisplayFields,
            showAuthenticator = showAuthenticator,
            hideOtherContentWhenAuthenticator = hideOtherContentWhenAuthenticator,
            totpTimeOffsetSeconds = totpTimeOffsetSeconds,
            smoothAuthenticatorProgress = smoothAuthenticatorProgress,
            decryptAuthenticatorKey = decryptAuthenticatorKey,
            leadingIconOverride = leadingIconOverride,
            enableSharedBounds = enableSharedBounds
        )
    }
}

@Composable
private fun PasswordListCornerBadge(
    badge: PasswordListCardBadge,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
        ),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Text(
            text = badge.text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium)
        )
    }
}
