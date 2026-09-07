package io.vaultix.vaultix.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.vaultix.vaultix.R

/**
 * 登录条目表单对话框（新建 / 编辑共用，Docs/08 S10 最小版：仅登录条目字段）。
 *
 * 密码只在对话框存续期间存在于内存；关闭/保存后由调用方保证不再持有副本。
 * 编辑场景用 [initialXxx] 预填；切换目标条目时（key 变化）状态自动重置。
 */
@Composable
fun ItemFormDialog(
    title: String,
    initialName: String = "",
    initialUsername: String = "",
    initialPassword: String = "",
    initialNotes: String = "",
    saving: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (name: String, username: String, password: String, notes: String) -> Unit,
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    var username by rememberSaveable(initialUsername) { mutableStateOf(initialUsername) }
    var password by rememberSaveable(initialPassword) { mutableStateOf(initialPassword) }
    var notes by rememberSaveable(initialNotes) { mutableStateOf(initialNotes) }
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
                        onSave(name.trim(), username.trim(), password, notes.trim())
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
