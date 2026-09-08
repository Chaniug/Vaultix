package io.vaultix.vaultix.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.vaultix.vaultix.R

/**
 * 回收站自动清理档位（对齐 Bastion TrashSettingsSheet）：0 = 从不，其余 = 保留 N 天。
 * 回收站页顶栏与设置页「数据」组共用同一组件与偏好键（即选即存）。
 */
val TRASH_AUTO_DELETE_PRESETS: List<Int> = listOf(0, 7, 30, 90)

/** 自动清理档位设置对话框（radio 单选，即选即存）。 */
@Composable
fun TrashAutoDeleteDialog(
    currentDays: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.trash_auto_delete_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.trash_auto_delete_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TRASH_AUTO_DELETE_PRESETS.forEach { days ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(days) }
                            .padding(vertical = 4.dp),
                    ) {
                        RadioButton(selected = days == currentDays, onClick = { onSelect(days) })
                        Text(text = trashAutoDeleteLabel(days), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }
        },
    )
}

/** 档位文案：0 = 「从不自动清空」，其余 = 「保留 N 天后自动清空」。 */
@Composable
fun trashAutoDeleteLabel(days: Int): String =
    if (days <= 0) {
        stringResource(R.string.trash_auto_delete_never)
    } else {
        stringResource(R.string.trash_auto_delete_after_days, days)
    }
