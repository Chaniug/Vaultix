package com.bastion.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bastion.app.R
import com.bastion.app.data.ThemeMode
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSelectionSheet(
    currentTheme: ThemeMode,
    oledPureBlackEnabled: Boolean,
    onThemeSelected: (ThemeMode) -> Unit,
    onOledPureBlackChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.theme),
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = stringResource(R.string.appearance_sheet_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                shape = RoundedCornerShape(28.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    ThemeMode.values().forEach { theme ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { onThemeSelected(theme) }
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = theme == currentTheme,
                                onClick = { onThemeSelected(theme) }
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(getThemeDisplayName(theme, context))
                                if (theme == ThemeMode.DARK) {
                                    Text(
                                        text = stringResource(R.string.oled_pure_black_dark_mode_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                ),
                shape = RoundedCornerShape(28.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOledPureBlackChanged(!oledPureBlackEnabled) }
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.oled_pure_black),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.oled_pure_black_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = oledPureBlackEnabled,
                        onCheckedChange = onOledPureBlackChanged
                    )
                }
            }

            FilledTonalButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(stringResource(R.string.close))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoLockSelectionSheet(
    currentMinutes: Int,
    onMinutesSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    // 预设选项：0=立即, 1/5/15/60分钟, -1=从不, -2=重启后锁定（精简预设，因已支持自定义时间）
    val presetOptions = listOf(0, 1, 5, 15, 60, -1, -2)

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 自定义时间对话框状态
    var showCustomDialog by remember { mutableStateOf(false) }
    var customText by remember { mutableStateOf("") }
    var customError by remember { mutableStateOf(false) }

    if (showCustomDialog) {
        AlertDialog(
            onDismissRequest = { showCustomDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    val value = customText.toIntOrNull()
                    if (value == null || value < 1 || value > 100000) {
                        customError = true
                    } else {
                        customError = false
                        showCustomDialog = false
                        onMinutesSelected(value)
                    }
                }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            title = { Text(stringResource(R.string.auto_lock_custom_title)) },
            text = {
                OutlinedTextField(
                    value = customText,
                    onValueChange = {
                        // 仅保留数字，最多 6 位（上限 100000）
                        customText = it.filter { ch -> ch.isDigit() }.take(6)
                        customError = false
                    },
                    label = { Text(stringResource(R.string.auto_lock_custom_hint)) },
                    isError = customError,
                    supportingText = if (customError) {
                        { Text(stringResource(R.string.auto_lock_custom_invalid)) }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = stringResource(R.string.auto_lock),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                color = MaterialTheme.colorScheme.onSurface
            )

            presetOptions.forEach { minutes ->
                val isSelected = minutes == currentMinutes
                val containerColor = if (isSelected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    androidx.compose.ui.graphics.Color.Transparent
                }

                val contentColor = if (isSelected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(containerColor)
                        .clickable { onMinutesSelected(minutes) }
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = when {
                            minutes == 0 -> Icons.Default.LockOpen
                            minutes == -1 -> Icons.Default.Lock
                            minutes == -2 -> Icons.Default.Refresh
                            minutes >= 1440 -> Icons.Default.Bedtime
                            else -> Icons.Default.Timer
                        },
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(24.dp)
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    Text(
                        text = getAutoLockDisplayName(minutes, context),
                        style = MaterialTheme.typography.bodyLarge,
                        color = contentColor,
                        modifier = Modifier.weight(1f)
                    )

                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = contentColor
                        )
                    }
                }
            }

            // 自定义时间行：选择任意非预设正值时高亮，点击弹出数字输入框
            val customSelected = currentMinutes > 0 && currentMinutes !in presetOptions
            val customContainer = if (customSelected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                androidx.compose.ui.graphics.Color.Transparent
            }
            val customContent = if (customSelected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(customContainer)
                    .clickable {
                        customText = if (customSelected) currentMinutes.toString() else ""
                        customError = false
                        showCustomDialog = true
                    }
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = null,
                    tint = customContent,
                    modifier = Modifier.size(24.dp)
                )

                Spacer(modifier = Modifier.width(16.dp))

                Text(
                    text = if (customSelected) {
                        stringResource(R.string.auto_lock_minutes, currentMinutes)
                    } else {
                        stringResource(R.string.auto_lock_custom)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = customContent,
                    modifier = Modifier.weight(1f)
                )

                if (customSelected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = customContent
                    )
                }
            }
        }
    }
}
