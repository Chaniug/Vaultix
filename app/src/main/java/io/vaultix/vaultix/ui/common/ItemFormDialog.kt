package io.vaultix.vaultix.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.vaultix.common.PasswordStrength
import io.vaultix.vaultix.R

/**
 * 条目表单对话框（新建 / 编辑共用，Docs/08 S10 最小版）。
 *
 * 密码只在对话框存续期间存在于内存；关闭/保存后由调用方保证不再持有副本。
 * 密码框下方为实时强度条（[PasswordStrength]，算法移植 Bastion 见其溯源声明；
 * 非强制门槛，仅提示）。
 * 编辑场景用 [initialXxx] 预填；切换目标条目时（key 变化）状态自动重置。
 *
 * [loginFieldsVisible]：非 Login 类型条目编辑只允许改名称/备注（类型专属字段
 * 只读，合并更新由 data 层保证不丢载荷），此时隐藏用户名/密码框；[editHint]
 * 显示该限制说明。
 */
@Composable
fun ItemFormDialog(
    title: String,
    initialName: String = "",
    initialUsername: String = "",
    initialPassword: String = "",
    initialNotes: String = "",
    initialUris: List<String> = emptyList(),
    initialTotp: String = "",
    saving: Boolean = false,
    loginFieldsVisible: Boolean = true,
    editHint: String? = null,
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        username: String,
        password: String,
        notes: String,
        uris: List<String>,
        totp: String,
    ) -> Unit,
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    var username by rememberSaveable(initialUsername) { mutableStateOf(initialUsername) }
    var password by rememberSaveable(initialPassword) { mutableStateOf(initialPassword) }
    var notes by rememberSaveable(initialNotes) { mutableStateOf(initialNotes) }
    var totp by rememberSaveable(initialTotp) { mutableStateOf(initialTotp) }
    val uris = rememberSaveable(
        saver = listSaver<SnapshotStateList<String>, String>(
            save = { it.toList() },
            restore = { mutableStateListOf<String>().apply { addAll(it) } },
        ),
    ) { mutableStateListOf<String>().apply { addAll(initialUris) } }
    var showNameError by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(text = title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(modifier = Modifier.imePadding()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; showNameError = false },
                    label = { Text(stringResource(R.string.item_field_name)) },
                    singleLine = true,
                    isError = showNameError,
                    supportingText = if (showNameError) {
                        { Text(stringResource(R.string.item_name_required)) }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (editHint != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = editHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (loginFieldsVisible) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(stringResource(R.string.item_field_username)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.item_field_password)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PasswordStrengthHint(password = password)
                    Spacer(Modifier.height(8.dp))
                    UriListEditor(
                        uris = uris,
                        onAdd = { uris.add("") },
                        onRemove = { uris.removeAt(it) },
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = totp,
                        onValueChange = { totp = it },
                        label = { Text(stringResource(R.string.section_totp)) },
                        placeholder = { Text(stringResource(R.string.item_totp_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(stringResource(R.string.item_field_notes)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    if (name.isBlank()) {
                        showNameError = true
                    } else {
                        onSave(
                            name.trim(),
                            username.trim(),
                            password,
                            notes.trim(),
                            uris.filter { it.isNotBlank() },
                            totp.trim(),
                        )
                    }
                },
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/**
 * 密码强度条（Docs/08 S10）：分值 0–100 → 五档（弱/一般/良好/强/非常强），
 * 仅做提示不做门槛；空密码不渲染（保持编辑空表单轻量）。
 * 颜色递进：弱=error，一般=tertiary，良好及以上=primary。
 */
@Composable
private fun PasswordStrengthHint(password: String) {
    if (password.isEmpty()) return
    val score = PasswordStrength.score(password)
    val level = PasswordStrength.levelOf(score)
    val color = when (level) {
        PasswordStrength.Level.WEAK -> MaterialTheme.colorScheme.error
        PasswordStrength.Level.FAIR -> MaterialTheme.colorScheme.tertiary
        PasswordStrength.Level.GOOD,
        PasswordStrength.Level.STRONG,
        PasswordStrength.Level.VERY_STRONG,
        -> MaterialTheme.colorScheme.primary
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        LinearProgressIndicator(
            progress = { score / 100f },
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(strengthLabelRes(level)),
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

private fun strengthLabelRes(level: PasswordStrength.Level): Int = when (level) {
    PasswordStrength.Level.WEAK -> R.string.password_strength_weak
    PasswordStrength.Level.FAIR -> R.string.password_strength_fair
    PasswordStrength.Level.GOOD -> R.string.password_strength_good
    PasswordStrength.Level.STRONG -> R.string.password_strength_strong
    PasswordStrength.Level.VERY_STRONG -> R.string.password_strength_very_strong
}

/**
 * 多网址编辑（对齐 Bitwarden login.uris 可多值）：逐条输入，可增删。
 * [uris] 为就地可变列表，编辑直接回写索引以保持 UI 同步。
 */
@Composable
private fun UriListEditor(
    uris: SnapshotStateList<String>,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        uris.forEachIndexed { index, uri ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = uri,
                    onValueChange = { uris[index] = it },
                    label = { Text(stringResource(R.string.item_field_uri)) },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 4.dp),
                )
                IconButton(onClick = { onRemove(index) }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.item_remove_uri),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        TextButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.item_add_uri))
        }
    }
}
