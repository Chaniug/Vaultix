package com.bastion.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bastion.app.R
import com.bastion.app.data.LocalKeePassDatabase
import com.bastion.app.data.writeOperationAvailability
import com.bastion.app.data.bitwarden.BitwardenVault
import com.bastion.app.viewmodel.CategoryFilter

/**
 * 各存储来源的条目数统计（全部 / 本地 Bastion / 每个 KeePass 库 / 每个 Bitwarden 库）。
 *
 * 说明：本类是 public，因为 [com.bastion.app.ui.components.UnifiedCategoryFilterChipMenu]
 * 是 public 函数，其 entryCounts 参数不能暴露 internal 类型。
 * 验证器 / 卡包 / 通行密钥三页复用同一结构统计各自条数。
 */
data class PasswordStorageCounts(
    val total: Int,
    val bastionLocal: Int,
    val perKeePassDatabase: Map<Long, Int>,
    val perBitwardenVault: Map<Long, Int>
)

internal data class PasswordDatabaseFiltersSectionParams(
    val currentFilter: CategoryFilter,
    val keepassDatabases: List<LocalKeePassDatabase>,
    val bitwardenVaults: List<BitwardenVault>,
    val onSelectFilter: (CategoryFilter) -> Unit,
    /** 为 null 时不显示条数徽标（调用方未提供统计）。 */
    val entryCounts: PasswordStorageCounts? = null
)

/**
 * 数据库筛选区块。在方案 A 中由顶部 Tab 驱动展开：选中"数据库"Tab 时
 * [expanded] 为 true 显示内容，其余 Tab 时仅保留静态标题。
 *
 * 布局为统计行式（图标 + 名称 + 条数徽标）：分类筛选移至横排后本区块空间充裕，
 * 行式排版比原先的 All/Bastion/Bitwarden 三个矮 chip 更统一，且能展示每个库的密码数。
 */
@Composable
internal fun PasswordDatabaseFiltersSection(
    params: PasswordDatabaseFiltersSectionParams,
    modifier: Modifier = Modifier,
    expanded: Boolean = true
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(180)) + fadeIn(animationSpec = tween(120)),
            exit = shrinkVertically(animationSpec = tween(140)) + fadeOut(animationSpec = tween(100))
        ) {
            DatabaseFilterRows(params = params)
        }
    }
}

@Composable
private fun DatabaseFilterRows(params: PasswordDatabaseFiltersSectionParams) {
    val counts = params.entryCounts
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DatabaseStatRow(
            icon = Icons.Default.List,
            title = stringResource(R.string.category_all),
            count = counts?.total,
            selected = params.currentFilter is CategoryFilter.All,
            onClick = { params.onSelectFilter(CategoryFilter.All) }
        )
        DatabaseStatRow(
            icon = Icons.Default.Smartphone,
            title = stringResource(R.string.category_selection_menu_local_database),
            count = counts?.bastionLocal,
            selected = params.currentFilter.isBastionDatabaseFilter(),
            onClick = { params.onSelectFilter(CategoryFilter.Local) }
        )
        params.keepassDatabases.forEach { database ->
            DatabaseStatRow(
                icon = Icons.Default.Key,
                title = database.name,
                count = counts?.perKeePassDatabase?.get(database.id),
                selected = params.currentFilter.isKeePassDatabaseFilter(database.id),
                onClick = { params.onSelectFilter(CategoryFilter.KeePassDatabase(database.id)) },
                statusDotColor = if (database.writeOperationAvailability().canOperate) {
                    StorageHealthyGreen
                } else {
                    null
                }
            )
        }
        params.bitwardenVaults.forEach { vault ->
            // 库名显示：displayName 优先，其次邮箱 @ 前的用户名（完整邮箱在窄行里太长）
            val vaultTitle = vault.displayName?.takeIf { it.isNotBlank() }
                ?: vault.email.substringBefore("@").ifBlank { vault.email }.ifBlank { "Bitwarden" }
            DatabaseStatRow(
                icon = Icons.Default.CloudSync,
                title = vaultTitle,
                count = counts?.perBitwardenVault?.get(vault.id),
                selected = params.currentFilter.isBitwardenVaultFilter(vault.id),
                onClick = { params.onSelectFilter(CategoryFilter.BitwardenVault(vault.id)) },
                statusDotColor = if (vault.hasHealthyConnection()) StorageHealthyGreen else null
            )
        }
    }
}

/**
 * 数据源条目行：图标 + 名称 + 右侧条数徽标。
 *
 * 原为密码页私有组件，现提升为 internal 供 UnifiedCategoryFilterChipMenu 复用，
 * 让验证器 / 卡包 / 通行密钥三页的「数据库」Tab 与密码页保持同一视觉：
 * 纵向整齐排列、每条右侧显示条数统计。
 */
@Composable
internal fun DatabaseStatRow(
    icon: ImageVector,
    title: String,
    count: Int?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    statusDotColor: Color? = null
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (statusDotColor != null) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(statusDotColor, CircleShape)
                )
            }
            if (count != null) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    contentColor = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                ) {
                    Text(
                        text = stringResource(R.string.database_entry_count, count),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

private val StorageHealthyGreen = Color(0xFF22C55E)

private fun BitwardenVault.hasHealthyConnection(): Boolean {
    return isConnected && !encryptedRefreshToken.isNullOrBlank()
}
