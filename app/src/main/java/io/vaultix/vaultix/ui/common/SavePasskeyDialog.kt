package io.vaultix.vaultix.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.vaultix.model.VaultFido2Credential
import io.vaultix.model.VaultItem
import io.vaultix.vaultix.R

/**
 * 保存（绑定）一个通行密钥到所选密码条目。
 *
 * 对齐 Bitwarden / Keyguard：通行密钥必须绑定到登录条目，不存在独立通行密钥条目。
 * 调用方负责在 onSave 中把凭证追加进目标登录条目的 fido2 列表。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavePasskeyDialog(
    candidates: List<VaultItem>,
    onDismiss: () -> Unit,
    onSave: (loginId: String, credential: VaultFido2Credential) -> Unit,
) {
    var selectedLogin by remember { mutableStateOf(candidates.firstOrNull()) }
    var rpId by remember { mutableStateOf("") }
    var rpName by remember { mutableStateOf("") }
    var userName by remember { mutableStateOf("") }
    var userDisplayName by remember { mutableStateOf("") }
    var credentialId by remember { mutableStateOf("") }
    var keyValue by remember { mutableStateOf("") }
    var loginExpanded by remember { mutableStateOf(false) }
    var errorRes by remember { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.passkeys_add_title)) },
        text = {
            Column {
                if (candidates.isEmpty()) {
                    Text(
                        stringResource(R.string.passkey_select_login),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ExposedDropdownMenuBox(expanded = loginExpanded, onExpandedChange = { loginExpanded = it }) {
                        OutlinedTextField(
                            value = selectedLogin?.title.orEmpty(),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.passkey_select_login)) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                        )
                        ExposedDropdownMenu(expanded = loginExpanded, onDismissRequest = { loginExpanded = false }) {
                            candidates.forEach { login ->
                                DropdownMenuItem(
                                    text = { Text(login.title.ifBlank { login.username }) },
                                    onClick = { selectedLogin = login; loginExpanded = false },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = rpId,
                    onValueChange = { rpId = it },
                    label = { Text(stringResource(R.string.passkey_field_rp_id)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = rpName,
                    onValueChange = { rpName = it },
                    label = { Text(stringResource(R.string.passkey_field_rp_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = userName,
                    onValueChange = { userName = it },
                    label = { Text(stringResource(R.string.passkey_field_user)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = userDisplayName,
                    onValueChange = { userDisplayName = it },
                    label = { Text(stringResource(R.string.passkey_user)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = credentialId,
                    onValueChange = { credentialId = it },
                    label = { Text(stringResource(R.string.passkey_field_credential_id)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = keyValue,
                    onValueChange = { keyValue = it },
                    label = { Text(stringResource(R.string.passkey_field_key_value)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                errorRes?.let {
                    Text(
                        stringResource(it),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val login = selectedLogin
                if (login == null || rpId.isBlank() || credentialId.isBlank()) {
                    errorRes = R.string.totp_invalid_secret
                    return@TextButton
                }
                // ⚠️ **私钥材料必填**（P0，2026-09-11）：没有 keyValue 的通行密钥「看得到、
                // 点不动」—— 候选能列出来，签名时 parseEcPrivateKey 返回 null，浏览器侧报
                // `Cannot parse passkey key`。真机上那条唯一缺 keyValue 的凭据正是这样来的。
                // 参考实现里 keyValue 一律来自真实密钥对（Keyguard：
                // `PasskeyBase64.encodeToString(keyMaterial.privateKeyPkcs8)`），
                // **没有任何实现允许手工留空**。
                if (keyValue.isBlank()) {
                    errorRes = R.string.passkey_error_key_value_required
                    return@TextButton
                }
                onSave(
                    login.id,
                    VaultFido2Credential(
                        credentialId = credentialId.trim(),
                        rpId = rpId.trim(),
                        rpName = rpName.takeIf { it.isNotBlank() } ?: rpId.trim(),
                        userName = userName.trim(),
                        userDisplayName = userDisplayName.takeIf { it.isNotBlank() } ?: userName.trim(),
                        keyValue = keyValue.trim(),
                    ),
                )
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
