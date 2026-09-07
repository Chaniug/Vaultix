@file:Suppress("LocalContextGetResourceValueCall")
package com.bastion.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import android.widget.Toast
import com.bastion.app.R
import com.bastion.app.data.SecureItem
import com.bastion.app.ui.components.TrashSettingsSheet
import com.bastion.app.ui.components.UnifiedMoveAction
import kotlinx.coroutines.launch
import java.util.Locale
import com.bastion.app.ui.password.PasswordBatchDeleteGlobalProgressState
import com.bastion.app.ui.password.PasswordBatchDeleteProgressTracker
import com.bastion.app.ui.password.PasswordBatchTransferGlobalProgressState
import com.bastion.app.ui.password.PasswordBatchTransferProgressTracker
import com.bastion.app.utils.UpdateDownloadProgress

@Composable
internal fun UpdateDownloadProgressSection(progress: UpdateDownloadProgress?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        HorizontalDivider()
        if (progress?.hasTotal == true) {
            LinearProgressIndicator(
                progress = { progress.fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            text = if (progress?.hasTotal == true) {
                stringResource(
                    R.string.update_download_progress_known,
                    formatUpdateDownloadBytes(progress.bytesRead),
                    formatUpdateDownloadBytes(progress.totalBytes)
                )
            } else {
                stringResource(
                    R.string.update_download_progress_unknown,
                    formatUpdateDownloadBytes(progress?.bytesRead ?: 0L)
                )
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

internal fun formatUpdateDownloadBytes(bytes: Long): String {
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    return String.format(Locale.US, "%.1f MB", mb)
}

@Composable
internal fun PasswordBatchDeleteProgressCard(
    progress: PasswordBatchDeleteGlobalProgressState
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.batch_delete_settings_card_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            LinearProgressIndicator(
                progress = { progress.progressFraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = if (progress.processed <= 0) {
                    stringResource(R.string.batch_delete_in_progress_preparing)
                } else {
                    stringResource(
                        R.string.batch_delete_in_progress_count,
                        progress.processed,
                        progress.total
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f)
            )
        }
    }
}

@Composable
internal fun PasswordBatchTransferProgressCard(
    progress: PasswordBatchTransferGlobalProgressState
) {
    val actionTitleRes = when (progress.action) {
        UnifiedMoveAction.COPY -> R.string.password_batch_transfer_progress_title_copy
        UnifiedMoveAction.MOVE -> R.string.password_batch_transfer_progress_title_move
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.password_batch_transfer_settings_card_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = stringResource(actionTitleRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = stringResource(
                    R.string.password_batch_transfer_target,
                    progress.targetLabel
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
            )
            LinearProgressIndicator(
                progress = { progress.progressFraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = stringResource(
                    R.string.password_batch_transfer_notification_progress,
                    progress.processed,
                    progress.total
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
            )
        }
    }
}




@Composable
fun ProgressBarStyleDialog(
    currentStyle: com.bastion.app.data.ProgressBarStyle,
    onStyleSelected: (com.bastion.app.data.ProgressBarStyle) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.validator_progress_bar_style)) },
        text = {
            Column {
                com.bastion.app.data.ProgressBarStyle.values().forEach { style ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = style == currentStyle,
                            onClick = { 
                                onStyleSelected(style)
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(getProgressBarStyleDisplayName(style, context))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ok))
            }
        }
    )
}

@Composable
fun NotificationValidatorCard(
    enabled: Boolean,
    autoMatchEnabled: Boolean,
    selectedId: Long,
    totpItems: List<SecureItem>,
    onEnabledChange: (Boolean) -> Unit,
    onAutoMatchChange: (Boolean) -> Unit,
    onValidatorSelected: (Long) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    
    // If disabled, collapse
    LaunchedEffect(enabled) {
        if (!enabled) expanded = false
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f)
            }
        )
    ) {
        Column {
            // Header with Switch
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { if (enabled) expanded = !expanded }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Notifications,
                    contentDescription = null,
                    tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.38f),
                    modifier = Modifier.size(24.dp)
                )
                
                Spacer(modifier = Modifier.width(16.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.notification_validator_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (enabled) LocalContentColor.current else LocalContentColor.current.copy(alpha = 0.38f)
                    )
                    Text(
                        text = if (enabled) stringResource(R.string.notification_validator_enabled) else stringResource(R.string.notification_validator_disabled),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    )
                }
                
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange
                )
            }
            
            // Expanded Content
            AnimatedVisibility(
                visible = expanded && enabled,
                enter = slideInHorizontally(
                    initialOffsetX = { fullWidth -> fullWidth },
                    animationSpec = tween(220)
                ),
                exit = slideOutHorizontally(
                    targetOffsetX = { fullWidth -> fullWidth / 3 },
                    animationSpec = tween(180)
                )
            ) {
                Column {
                    Divider(modifier = Modifier.padding(horizontal = 16.dp))

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
    }
}

/**
 * 回收站设置项组件
 */
@Composable
internal fun SettingsItemWithTrashConfig(
    trashEnabled: Boolean,
    trashAutoDeleteDays: Int,
    onTrashEnabledChange: (Boolean) -> Unit,
    onAutoDeleteDaysChange: (Int) -> Unit
) {
    var showTrashSettingsSheet by remember { mutableStateOf(false) }
    
    val subtitleText = if (trashEnabled) {
        if (trashAutoDeleteDays > 0) {
            stringResource(R.string.trash_status_enabled_auto_clear, trashAutoDeleteDays)
        } else {
            stringResource(R.string.trash_status_enabled_no_auto_clear)
        }
    } else {
        stringResource(R.string.trash_status_disabled_permanent_delete)
    }

    SettingsItem(
        icon = Icons.Default.Delete,
        title = stringResource(R.string.trash_bin),
        subtitle = subtitleText,
        onClick = { showTrashSettingsSheet = true }
    )

    if (showTrashSettingsSheet) {
        TrashSettingsSheet(
            currentSettings = com.bastion.app.viewmodel.TrashSettings(trashEnabled, trashAutoDeleteDays),
            onDismiss = { showTrashSettingsSheet = false },
            onConfirm = { enabled, days ->
                onTrashEnabledChange(enabled)
                onAutoDeleteDaysChange(days)
                showTrashSettingsSheet = false
            }
        )
    }
}

/**
 * 常用账号信息卡片（折叠式）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommonAccountCard() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val commonAccountPreferences = remember { com.bastion.app.data.CommonAccountPreferences(context) }
    
    val commonInfo by commonAccountPreferences.commonAccountInfo.collectAsState(
        initial = com.bastion.app.data.CommonAccountInfo()
    )
    
    var expanded by remember { mutableStateOf(false) }
    var email by remember(commonInfo.email) { mutableStateOf(commonInfo.email) }
    var phone by remember(commonInfo.phone) { mutableStateOf(commonInfo.phone) }
    var username by remember(commonInfo.username) { mutableStateOf(commonInfo.username) }
    var autoFillEnabled by remember(commonInfo.autoFillEnabled) { mutableStateOf(commonInfo.autoFillEnabled) }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                
                Spacer(modifier = Modifier.width(16.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.common_account_title),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = if (commonInfo.hasAnyInfo()) 
                            stringResource(R.string.common_account_configured) 
                        else 
                            stringResource(R.string.common_account_not_configured),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // Expanded Content
            AnimatedVisibility(
                visible = expanded,
                enter = slideInHorizontally(
                    initialOffsetX = { fullWidth -> fullWidth },
                    animationSpec = tween(220)
                ),
                exit = slideOutHorizontally(
                    targetOffsetX = { fullWidth -> fullWidth / 3 },
                    animationSpec = tween(180)
                )
            ) {
                Column {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.common_account_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // 常用邮箱
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = { Text(context.getString(R.string.common_account_email)) },
                            placeholder = { Text("name@example.com") },
                            leadingIcon = { Icon(Icons.Default.Email, null) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )

                        // 常用手机号
                        OutlinedTextField(
                            value = phone,
                            onValueChange = { if (it.all { c -> c.isDigit() } && it.length <= 15) phone = it },
                            label = { Text(context.getString(R.string.common_account_phone)) },
                            placeholder = { Text("13800000000") },
                            leadingIcon = { Icon(Icons.Default.Phone, null) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                        )

                        // 常用用户名
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text(context.getString(R.string.common_account_username)) },
                            placeholder = { Text(context.getString(R.string.common_account_username_hint)) },
                            leadingIcon = { Icon(Icons.Default.AccountCircle, null) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )

                        // 自动填入开关
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { autoFillEnabled = !autoFillEnabled }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = autoFillEnabled,
                                onCheckedChange = { autoFillEnabled = it }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.common_account_auto_fill),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = stringResource(R.string.common_account_auto_fill_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // 保存按钮
                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    commonAccountPreferences.setDefaultEmail(email)
                                    commonAccountPreferences.setDefaultPhone(phone)
                                    commonAccountPreferences.setDefaultUsername(username)
                                    commonAccountPreferences.setAutoFillEnabled(autoFillEnabled)
                                    Toast.makeText(context, context.getString(R.string.common_account_saved), Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.align(Alignment.End),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(stringResource(R.string.save))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 「版本与更新」对话框里的外链行（项目地址 / 下载地址）。
 */
@Composable
internal fun UpdateLinkRow(
    icon: ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * 下载方式选择 chip（应用内直接下载 / 浏览器打开）。
 */
@Composable
internal fun DownloadMethodChip(
    selected: Boolean,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        },
        shape = RoundedCornerShape(12.dp)
    )
}
