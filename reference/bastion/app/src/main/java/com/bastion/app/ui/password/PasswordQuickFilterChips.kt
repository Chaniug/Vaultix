package com.bastion.app.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LinearScale
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.bastion.app.R
import com.bastion.app.data.PasswordListQuickFilterItem
import com.bastion.app.data.PasswordPageContentType
import com.bastion.app.ui.components.BastionExpressiveFilterChip
import com.bastion.app.ui.password.icon
import com.bastion.app.ui.password.labelRes
import com.bastion.app.ui.password.toPasswordPageContentTypeOrNull

@Composable
internal fun PasswordQuickFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource? = null,
    leadingIcon: ImageVector? = null,
    selectedLeadingIcon: ImageVector? = leadingIcon,
    animated: Boolean = true
) {
    BastionExpressiveFilterChip(
        selected = selected,
        onClick = onClick,
        label = label,
        modifier = modifier,
        interactionSource = interactionSource,
        leadingIcon = leadingIcon,
        selectedLeadingIcon = selectedLeadingIcon,
        animated = animated
    )
}

internal fun shouldShowQuickFilterItem(
    item: PasswordListQuickFilterItem,
    aggregateVisibleContentTypes: List<PasswordPageContentType>
): Boolean {
    if (item == PasswordListQuickFilterItem.TWO_FA ||
        item == PasswordListQuickFilterItem.PASSKEY ||
        item == PasswordListQuickFilterItem.NOTE ||
        item == PasswordListQuickFilterItem.ATTACHMENTS
    ) {
        return true
    }
    val type = item.toPasswordPageContentTypeOrNull() ?: return true
    return type in aggregateVisibleContentTypes
}

@Composable
internal fun PasswordQuickFilterChipItem(
    item: PasswordListQuickFilterItem,
    categoryEditMode: Boolean,
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
    modifier: Modifier = Modifier,
    aggregateSelectedTypes: Set<PasswordPageContentType> = emptySet(),
    aggregateVisibleTypes: List<PasswordPageContentType> = emptyList(),
    onToggleAggregateType: ((PasswordPageContentType) -> Unit)? = null,
    // 通行秘钥 chip 的语义是「导航到通行秘钥页」而非「在密码列表里过滤」：
    // 筛选只能筛出「绑定了 passkey 的密码条目」，筛不出 passkey 条目本身，
    // 而 passkey 存在独立的 PasskeyEntry 表，只有进通行秘钥页才能看到。
    // 传入该回调即启用跳转语义；为 null 时退回原有筛选行为（VaultV2 聚合模式）。
    onNavigateToPasskeys: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null
) {
    when (item) {
        PasswordListQuickFilterItem.FAVORITE -> {
            PasswordQuickFilterChip(
                selected = quickFilterFavorite,
                onClick = {
                    if (!categoryEditMode) {
                        onQuickFilterFavoriteChange(!quickFilterFavorite)
                    }
                },
                label = stringResource(R.string.password_list_quick_filter_favorite),
                modifier = modifier,
                interactionSource = interactionSource,
                leadingIcon = Icons.Outlined.FavoriteBorder,
                selectedLeadingIcon = Icons.Default.Favorite
            )
        }

        PasswordListQuickFilterItem.TWO_FA -> {
            val type = PasswordPageContentType.AUTHENTICATOR
            val useAggregateFilter = type in aggregateVisibleTypes
            PasswordQuickFilterChip(
                selected = if (useAggregateFilter) aggregateSelectedTypes.contains(type) else quickFilter2fa,
                onClick = {
                    if (!categoryEditMode) {
                        if (useAggregateFilter) {
                            onToggleAggregateType?.invoke(type)
                        } else {
                            onQuickFilter2faChange(!quickFilter2fa)
                        }
                    }
                },
                label = stringResource(R.string.password_list_quick_filter_2fa),
                modifier = modifier,
                interactionSource = interactionSource,
                leadingIcon = Icons.Default.Security
            )
        }

        PasswordListQuickFilterItem.NOTES -> {
            PasswordQuickFilterChip(
                selected = quickFilterNotes,
                onClick = {
                    if (!categoryEditMode) {
                        onQuickFilterNotesChange(!quickFilterNotes)
                    }
                },
                label = stringResource(R.string.password_list_quick_filter_notes),
                modifier = modifier,
                interactionSource = interactionSource,
                leadingIcon = Icons.Default.Description
            )
        }

        PasswordListQuickFilterItem.UNCATEGORIZED -> {
            PasswordQuickFilterChip(
                selected = quickFilterUncategorized,
                onClick = {
                    if (!categoryEditMode) {
                        onQuickFilterUncategorizedChange(!quickFilterUncategorized)
                    }
                },
                label = stringResource(R.string.password_list_quick_filter_uncategorized),
                modifier = modifier,
                interactionSource = interactionSource,
                leadingIcon = Icons.Default.FolderOff
            )
        }

        PasswordListQuickFilterItem.LOCAL_ONLY -> {
            PasswordQuickFilterChip(
                selected = quickFilterLocalOnly,
                onClick = {
                    if (!categoryEditMode) {
                        onQuickFilterLocalOnlyChange(!quickFilterLocalOnly)
                    }
                },
                label = stringResource(R.string.password_list_quick_filter_local_only),
                modifier = modifier,
                interactionSource = interactionSource,
                leadingIcon = Icons.Default.Key
            )
        }

        PasswordListQuickFilterItem.MANUAL_STACK_ONLY -> {
            PasswordQuickFilterChip(
                selected = quickFilterManualStackOnly,
                onClick = {
                    if (!categoryEditMode) {
                        onQuickFilterManualStackOnlyChange(!quickFilterManualStackOnly)
                    }
                },
                label = stringResource(R.string.password_list_quick_filter_manual_stack_only),
                modifier = modifier,
                interactionSource = interactionSource,
                leadingIcon = Icons.Default.Apps
            )
        }

        PasswordListQuickFilterItem.NEVER_STACK -> {
            PasswordQuickFilterChip(
                selected = quickFilterNeverStack,
                onClick = {
                    if (!categoryEditMode) {
                        onQuickFilterNeverStackChange(!quickFilterNeverStack)
                    }
                },
                label = stringResource(R.string.password_list_quick_filter_never_stack),
                modifier = modifier,
                interactionSource = interactionSource,
                leadingIcon = Icons.Default.LinearScale
            )
        }

        PasswordListQuickFilterItem.UNSTACKED -> {
            PasswordQuickFilterChip(
                selected = quickFilterUnstacked,
                onClick = {
                    if (!categoryEditMode) {
                        onQuickFilterUnstackedChange(!quickFilterUnstacked)
                    }
                },
                label = stringResource(R.string.password_list_quick_filter_unstacked),
                modifier = modifier,
                interactionSource = interactionSource,
                leadingIcon = Icons.Default.Straighten
            )
        }

        PasswordListQuickFilterItem.CARD_WALLET -> {
            val type = item.toPasswordPageContentTypeOrNull() ?: return
            if (type !in aggregateVisibleTypes) return
            PasswordQuickFilterChip(
                selected = aggregateSelectedTypes.contains(type),
                onClick = {
                    if (!categoryEditMode) {
                        onToggleAggregateType?.invoke(type)
                    }
                },
                label = stringResource(type.labelRes()),
                modifier = modifier,
                interactionSource = interactionSource,
                leadingIcon = type.icon()
            )
        }

        PasswordListQuickFilterItem.PASSKEY -> {
            val type = PasswordPageContentType.PASSKEY
            val useAggregateFilter = type in aggregateVisibleTypes
            // 不用 type.icon()（Icons.Default.VpnKey，盾牌+钥匙视觉上和仅本地的钥匙图标混淆），
            // 直接用指纹图标——这是 passkey 的标准视觉符号，与「仅本地」的钥匙图标明显区分。
            if (onNavigateToPasskeys != null) {
                // 跳转语义：点击直接进入通行秘钥页。无选中态（不是过滤器）。
                PasswordQuickFilterChip(
                    selected = false,
                    onClick = {
                        if (!categoryEditMode) onNavigateToPasskeys.invoke()
                    },
                    label = stringResource(type.labelRes()),
                    modifier = modifier,
                    interactionSource = interactionSource,
                    leadingIcon = Icons.Default.Fingerprint
                )
            } else {
                PasswordQuickFilterChip(
                    selected = if (useAggregateFilter) aggregateSelectedTypes.contains(type) else quickFilterPasskey,
                    onClick = {
                        if (!categoryEditMode) {
                            if (useAggregateFilter) {
                                onToggleAggregateType?.invoke(type)
                            } else {
                                onQuickFilterPasskeyChange(!quickFilterPasskey)
                            }
                        }
                    },
                    label = stringResource(type.labelRes()),
                    modifier = modifier,
                    interactionSource = interactionSource,
                    leadingIcon = Icons.Default.Fingerprint
                )
            }
        }

        PasswordListQuickFilterItem.NOTE -> {
            val type = PasswordPageContentType.NOTE
            val useAggregateFilter = type in aggregateVisibleTypes
            PasswordQuickFilterChip(
                selected = if (useAggregateFilter) aggregateSelectedTypes.contains(type) else quickFilterBoundNote,
                onClick = {
                    if (!categoryEditMode) {
                        if (useAggregateFilter) {
                            onToggleAggregateType?.invoke(type)
                        } else {
                            onQuickFilterBoundNoteChange(!quickFilterBoundNote)
                        }
                    }
                },
                label = stringResource(type.labelRes()),
                modifier = modifier,
                interactionSource = interactionSource,
                leadingIcon = type.icon()
            )
        }

        PasswordListQuickFilterItem.ATTACHMENTS -> {
            PasswordQuickFilterChip(
                selected = quickFilterAttachments,
                onClick = {
                    if (!categoryEditMode) {
                        onQuickFilterAttachmentsChange(!quickFilterAttachments)
                    }
                },
                label = stringResource(R.string.attachment_section_title),
                modifier = modifier,
                interactionSource = interactionSource,
                leadingIcon = Icons.Default.AttachFile
            )
        }

        PasswordListQuickFilterItem.AUTHENTICATOR -> Unit
    }
}
