package com.bastion.app.ui.password

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bastion.app.R
import com.bastion.app.data.PasswordPageContentType
import com.bastion.app.data.PasswordListQuickFilterItem
import com.bastion.app.ui.components.BastionExpressiveFilterChip

internal fun resolvePasswordPageVisibleTypes(
    aggregateEnabled: Boolean,
    configuredTypes: List<PasswordPageContentType>
): List<PasswordPageContentType> {
    if (!aggregateEnabled) return listOf(PasswordPageContentType.PASSWORD)
    return PasswordPageContentType.normalizeEnabledTypes(configuredTypes)
}

internal fun sanitizeSelectedPasswordPageTypes(
    visibleTypes: List<PasswordPageContentType>,
    selectedTypes: Set<PasswordPageContentType>
): Set<PasswordPageContentType> {
    val allowed = resolvePasswordPageQuickFilterTypes(visibleTypes).toSet()
    return selectedTypes.filterTo(linkedSetOf()) { it in allowed }
}

internal fun resolvePasswordPageQuickFilterTypes(
    visibleTypes: List<PasswordPageContentType>
): List<PasswordPageContentType> {
    return visibleTypes.filter { it != PasswordPageContentType.PASSWORD }
}

internal fun resolvePasswordPageDisplayedTypes(
    visibleTypes: List<PasswordPageContentType>,
    selectedTypes: Set<PasswordPageContentType>
): Set<PasswordPageContentType> {
    val quickFilterTypes = resolvePasswordPageQuickFilterTypes(visibleTypes).toSet()
    val sanitizedSelectedTypes = selectedTypes.filterTo(linkedSetOf()) { it in quickFilterTypes }
    if (sanitizedSelectedTypes.isNotEmpty()) {
        return sanitizedSelectedTypes
    }
    return visibleTypes.toSet()
}

@Composable
internal fun PasswordPageContentTypeFilterRow(
    selectedTypes: Set<PasswordPageContentType>,
    visibleTypes: List<PasswordPageContentType>,
    onToggleType: (PasswordPageContentType) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PasswordPageContentTypeFilterChips(
            selectedTypes = selectedTypes,
            visibleTypes = visibleTypes,
            onToggleType = onToggleType
        )
    }
}

@Composable
internal fun PasswordPageContentTypeFilterChips(
    selectedTypes: Set<PasswordPageContentType>,
    visibleTypes: List<PasswordPageContentType>,
    onToggleType: (PasswordPageContentType) -> Unit
) {
    resolvePasswordPageQuickFilterTypes(visibleTypes).forEach { type ->
        val selected = selectedTypes.contains(type)
        BastionExpressiveFilterChip(
            selected = selected,
            onClick = { onToggleType(type) },
            label = stringResource(type.labelRes()),
            leadingIcon = type.icon()
        )
    }
}

internal fun PasswordPageContentType.labelRes(): Int = when (this) {
    PasswordPageContentType.PASSWORD -> R.string.nav_passwords
    PasswordPageContentType.CARD_WALLET -> R.string.nav_card_wallet
    PasswordPageContentType.NOTE -> R.string.nav_notes
    PasswordPageContentType.AUTHENTICATOR -> R.string.nav_authenticator
    PasswordPageContentType.PASSKEY -> R.string.nav_passkey
}

internal fun PasswordPageContentType.icon(): ImageVector = when (this) {
    PasswordPageContentType.PASSWORD -> Icons.Default.Security
    PasswordPageContentType.CARD_WALLET -> Icons.Default.CreditCard
    PasswordPageContentType.NOTE -> Icons.Default.Description
    PasswordPageContentType.AUTHENTICATOR -> Icons.Default.Security
    PasswordPageContentType.PASSKEY -> Icons.Default.VpnKey
}

internal fun PasswordListQuickFilterItem.toPasswordPageContentTypeOrNull(): PasswordPageContentType? = when (this) {
    PasswordListQuickFilterItem.TWO_FA -> PasswordPageContentType.AUTHENTICATOR
    PasswordListQuickFilterItem.CARD_WALLET -> PasswordPageContentType.CARD_WALLET
    PasswordListQuickFilterItem.NOTE -> PasswordPageContentType.NOTE
    PasswordListQuickFilterItem.AUTHENTICATOR -> PasswordPageContentType.AUTHENTICATOR
    PasswordListQuickFilterItem.PASSKEY -> PasswordPageContentType.PASSKEY
    else -> null
}

private fun PasswordPageContentType.toAggregateQuickFilterItemOrNull(): PasswordListQuickFilterItem? = when (this) {
    PasswordPageContentType.PASSWORD -> null
    PasswordPageContentType.CARD_WALLET -> PasswordListQuickFilterItem.CARD_WALLET
    PasswordPageContentType.NOTE -> PasswordListQuickFilterItem.NOTE
    PasswordPageContentType.AUTHENTICATOR -> PasswordListQuickFilterItem.TWO_FA
    PasswordPageContentType.PASSKEY -> PasswordListQuickFilterItem.PASSKEY
}

/**
 * 快捷筛选的基础列表：以持久化的用户排序为准，DEFAULT_ORDER 中新增的条目
 * （老版本持久化数据里没有的）自动追加到尾部，保证新筛选项不丢失。
 */
internal fun resolvedQuickFilterBaseItems(
    persistedOrder: List<PasswordListQuickFilterItem>
): List<PasswordListQuickFilterItem> {
    if (persistedOrder.isEmpty()) return PasswordListQuickFilterItem.DEFAULT_ORDER
    return persistedOrder + PasswordListQuickFilterItem.DEFAULT_ORDER.filterNot { it in persistedOrder }
}

internal fun appendAggregateContentQuickFilterItems(
    configuredItems: List<PasswordListQuickFilterItem>,
    visibleTypes: List<PasswordPageContentType>,
    aggregateEnabled: Boolean,
    // 密码页不渲染通行秘钥 chip：passkey 存独立 PasskeyEntry 表，列表筛选必然为空，
    // 入口收敛到验证器页指纹按钮。VaultV2 聚合页保持 true（那里的类型筛选有效）。
    includePasskeyChip: Boolean = true
): List<PasswordListQuickFilterItem> {
    val baseItems = if (includePasskeyChip) {
        configuredItems
    } else {
        configuredItems.filterNot { it == PasswordListQuickFilterItem.PASSKEY }
    }
    if (!aggregateEnabled) return baseItems
    val aggregateItems = resolvePasswordPageQuickFilterTypes(visibleTypes)
        .mapNotNull(PasswordPageContentType::toAggregateQuickFilterItemOrNull)
    return buildList {
        baseItems.forEach { item ->
            if (item !in this) add(item)
        }
        aggregateItems.forEach { item ->
            if (item !in this) add(item)
        }
    }
}
