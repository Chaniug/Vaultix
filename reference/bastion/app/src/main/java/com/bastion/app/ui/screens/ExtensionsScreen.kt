package com.bastion.app.ui.screens

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bastion.app.R
import com.bastion.app.data.PasswordSwipeSelectionMode
import com.bastion.app.data.SecureItem

/**
 * 功能拓展页面 - 聚合各种扩展功能的设置
 * 包含：显示分组、剪贴板自动清理、通知栏验证器等
 * 验证器相关（震动 / 即将过期复制）见「验证器卡片调整」页，避免重复。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun ExtensionsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToQuickSetup: () -> Unit = {},
    smartDeduplicationEnabled: Boolean = false,
    onSmartDeduplicationEnabledChange: (Boolean) -> Unit = {},
    clipboardAutoClearSeconds: Int = 0,
    onClipboardAutoClearSecondsChange: (Int) -> Unit = {},
    passwordDetailSecurityAnalysisEnabled: Boolean = true,
    onPasswordDetailSecurityAnalysisEnabledChange: (Boolean) -> Unit = {},
    passwordSwipeSelectionMode: PasswordSwipeSelectionMode = PasswordSwipeSelectionMode.DEFAULT,
    onPasswordSwipeSelectionModeChange: (PasswordSwipeSelectionMode) -> Unit = {},
    validatorUnifiedProgressBar: com.bastion.app.data.UnifiedProgressBarMode = com.bastion.app.data.UnifiedProgressBarMode.DISABLED,
    onValidatorUnifiedProgressBarChange: (com.bastion.app.data.UnifiedProgressBarMode) -> Unit = {},
    // 通知栏验证器参数
    notificationValidatorEnabled: Boolean = false,
    notificationValidatorAutoMatch: Boolean = false,
    notificationValidatorId: Long = 0L,
    totpItems: List<SecureItem> = emptyList(),
    onNotificationValidatorEnabledChange: (Boolean) -> Unit = {},
    onNotificationValidatorAutoMatchChange: (Boolean) -> Unit = {},
    onNotificationValidatorSelected: (Long) -> Unit = {}
) {
    val scrollState = rememberScrollState()

    var showClipboardAutoClearDialog by remember { mutableStateOf(false) }
    // 300 秒 = 5 分钟，对齐 Bitwarden 2026.4.1 的剪贴板自动清除安全默认。
    val clipboardAutoClearOptions = remember { listOf(0, 10, 20, 30, 60, 300) }

    if (showClipboardAutoClearDialog) {
        AlertDialog(
            onDismissRequest = { showClipboardAutoClearDialog = false },
            title = { Text(stringResource(R.string.clipboard_auto_clear_title)) },
            text = {
                Column {
                    clipboardAutoClearOptions.forEach { seconds ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onClipboardAutoClearSecondsChange(seconds)
                                    showClipboardAutoClearDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = seconds == clipboardAutoClearSeconds,
                                onClick = {
                                    onClipboardAutoClearSecondsChange(seconds)
                                    showClipboardAutoClearDialog = false
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(clipboardAutoClearLabel(seconds))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showClipboardAutoClearDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // 准备共享元素 Modifier
    val sharedTransitionScope = com.bastion.app.ui.LocalSharedTransitionScope.current
    val animatedVisibilityScope = com.bastion.app.ui.LocalAnimatedVisibilityScope.current
    
    var sharedModifier: Modifier = Modifier
    if (false && sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope!!) {
            sharedModifier = Modifier.sharedBounds(
                sharedContentState = rememberSharedContentState(key = "extensions_settings_card"),
                animatedVisibilityScope = animatedVisibilityScope!!,
                resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds
            )
        }
    }
    
    Scaffold(
        modifier = sharedModifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.extensions_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.go_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
        ) {
            // 顶部说明卡片
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Extension,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            stringResource(R.string.extensions_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            stringResource(R.string.extensions_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            ExtensionSection(title = stringResource(R.string.quick_init_section_title)) {
                ExtensionClickableItem(
                    icon = Icons.Default.SettingsSuggest,
                    title = stringResource(R.string.re_quick_init_title),
                    description = stringResource(R.string.re_quick_init_desc),
                    onClick = onNavigateToQuickSetup
                )
            }

            ExtensionSection(title = stringResource(R.string.extensions_security_settings)) {
                ExtensionChoiceItem(
                    icon = Icons.Default.ContentPaste,
                    title = stringResource(R.string.clipboard_auto_clear_title),
                    description = stringResource(R.string.clipboard_auto_clear_description),
                    value = clipboardAutoClearLabel(clipboardAutoClearSeconds),
                    onClick = { showClipboardAutoClearDialog = true }
                )
            }
            
            ExtensionSection(title = stringResource(R.string.display_options_menu_title)) {
                ExtensionSwitchItem(
                    icon = Icons.Default.CallMerge,
                    title = stringResource(R.string.smart_deduplication),
                    description = stringResource(R.string.smart_deduplication_desc),
                    checked = smartDeduplicationEnabled,
                    onCheckedChange = onSmartDeduplicationEnabledChange
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                ExtensionSwitchItem(
                    icon = Icons.Default.Security,
                    title = stringResource(R.string.password_detail_security_analysis_title),
                    description = stringResource(R.string.password_detail_security_analysis_desc),
                    checked = passwordDetailSecurityAnalysisEnabled,
                    onCheckedChange = onPasswordDetailSecurityAnalysisEnabledChange
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                ExtensionSegmentedItem(
                    icon = Icons.Default.Swipe,
                    title = stringResource(R.string.swipe_selection_mode),
                    description = stringResource(R.string.swipe_selection_mode_desc),
                    selectedMode = passwordSwipeSelectionMode,
                    onModeChange = onPasswordSwipeSelectionModeChange
                )
            }

             
            // 更多功能即将推出提示
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Upcoming,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                    Text(
                        stringResource(R.string.extensions_more_coming_soon),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun clipboardAutoClearLabel(seconds: Int): String {
    return when {
        seconds <= 0 -> stringResource(R.string.clipboard_auto_clear_never)
        // 整分钟档位（60/300）显示为分钟，与默认值「5 分钟」呼应。
        seconds % 60 == 0 -> stringResource(R.string.clipboard_auto_clear_minutes, seconds / 60)
        else -> stringResource(R.string.clipboard_auto_clear_seconds, seconds)
    }
}

/**
 * 功能分类区块
 */
@Composable
private fun ExtensionSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 12.dp)
        )
        
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            )
        ) {
            Column {
                content()
            }
        }
    }
}

/**
 * 可点击的功能项（导航到其他页面）
 */
@Composable
private fun ExtensionClickableItem(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 图标
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(48.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        // 文字内容
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        // 右箭头
        Icon(
            Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ExtensionChoiceItem(
    icon: ImageVector,
    title: String,
    description: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(48.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Icon(
            Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExtensionSegmentedItem(
    icon: ImageVector,
    title: String,
    description: String,
    selectedMode: PasswordSwipeSelectionMode,
    onModeChange: (PasswordSwipeSelectionMode) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        val modes = PasswordSwipeSelectionMode.values()
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = selectedMode == mode,
                    onClick = { onModeChange(mode) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = modes.size
                    ),
                    label = {
                        Text(
                            when (mode) {
                                PasswordSwipeSelectionMode.SINGLE -> stringResource(R.string.single_select)
                                PasswordSwipeSelectionMode.CONTINUOUS -> stringResource(R.string.swipe_mode_continuous)
                            }
                        )
                    }
                )
            }
        }
    }
}

/**
 * 带开关的功能项
 */
@Composable
private fun ExtensionSwitchItem(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 图标
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(48.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = if (enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        // 文字内容
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)
                }
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        Spacer(modifier = Modifier.width(8.dp))
        
        // 开关
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange
        )
    }
}

/**
 * 通知栏验证器卡片（扩展页面版本）
 */
@Composable
private fun NotificationValidatorExtensionCard(
    enabled: Boolean,
    autoMatchEnabled: Boolean,
    selectedId: Long,
    totpItems: List<SecureItem>,
    onEnabledChange: (Boolean) -> Unit,
    onAutoMatchChange: (Boolean) -> Unit,
    onValidatorSelected: (Long) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    
    // If disabled, collapse
    LaunchedEffect(enabled) {
        if (!enabled) expanded = false
    }

    Column(
        modifier = Modifier.animateContentSize()
    ) {
        // Header with Switch
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { if (enabled) expanded = !expanded }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图标
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = if (enabled) MaterialTheme.colorScheme.primaryContainer 
                        else MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Notifications,
                        contentDescription = null,
                        tint = if (enabled) MaterialTheme.colorScheme.primary 
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.notification_validator_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface 
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Text(
                    text = if (enabled) stringResource(R.string.notification_validator_enabled) 
                           else stringResource(R.string.notification_validator_disabled),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange
            )
        }
        
        // Expanded Content
        if (expanded && enabled) {
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.select_validator_to_display),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                if (totpItems.isEmpty()) {
                    Text(
                        text = stringResource(R.string.no_validators_available),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    totpItems.forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onValidatorSelected(item.id) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = item.id == selectedId,
                                onClick = { onValidatorSelected(item.id) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = item.title,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}


