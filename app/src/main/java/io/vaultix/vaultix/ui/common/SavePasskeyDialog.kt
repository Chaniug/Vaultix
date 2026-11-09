package io.vaultix.vaultix.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import io.vaultix.model.VaultFido2Credential
import io.vaultix.model.VaultItem
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.theme.Spacing

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
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (candidates.isEmpty()) {
                    Text(
                        stringResource(R.string.passkey_select_login),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    PasskeyLoginPicker(
                        candidates = candidates,
                        selected = selectedLogin,
                        expanded = loginExpanded,
                        onExpandedChange = { loginExpanded = it },
                        onSelect = { selectedLogin = it },
                    )
                    Spacer(Modifier.height(Spacing.sm))
                }
                OutlinedTextField(
                    value = rpId,
                    onValueChange = { rpId = it },
                    label = { Text(stringResource(R.string.passkey_field_rp_id)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Spacing.sm))
                OutlinedTextField(
                    value = rpName,
                    onValueChange = { rpName = it },
                    label = { Text(stringResource(R.string.passkey_field_rp_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Spacing.sm))
                OutlinedTextField(
                    value = userName,
                    onValueChange = { userName = it },
                    label = { Text(stringResource(R.string.passkey_field_user)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Spacing.sm))
                OutlinedTextField(
                    value = userDisplayName,
                    onValueChange = { userDisplayName = it },
                    label = { Text(stringResource(R.string.passkey_user)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Spacing.sm))
                OutlinedTextField(
                    value = credentialId,
                    onValueChange = { credentialId = it },
                    label = { Text(stringResource(R.string.passkey_field_credential_id)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Spacing.sm))
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

/**
 * 通行密钥的「选择密码条目」选择器（2026-11-09，用户反馈第 3 条）。
 *
 * ## 为什么要加搜索
 *
 * 此前就是一个纯 `ExposedDropdownMenu`，候选多时只能一条条往下翻。用户描述的
 * 对比很到位：「通行密钥自己调用 Vaultix 添加的那个步骤已经有搜索条目并保存到条目中，
 * 但单独的添加通行密钥的部分没有」—— 同一个 App 里两个入口，一个能搜一个不能，
 * 这是不一致，不是缺功能。
 *
 * ## 搜索框放在菜单**里面**而不是外面
 *
 * 放在外面的话，表单会先多出一行输入框，用户以为要填什么东西；而实际它只在
 * 挑条目时才有意义。放进菜单里就是"展开 → 边打边筛 → 点一条收起"，
 * 与系统里所有带搜索的选择器一致。
 *
 * ⚠️ 搜索期间菜单**不能关**：`ExposedDropdownMenuBox` 的默认行为是点菜单项才收，
 *    输入框是菜单的子项 ⇒ 打字不会触发 `onDismissRequest`。但**不要**给搜索框加
 *    `menuAnchor()`，否则它会把这个菜单当成自己的锚点、抢掉外层输入框的位置。
 *
 * ⚠️ 搜索用 `title` + `username` 两个字段匹配：很多条目标题是「GitHub」而账号是
 *    邮箱，用户凭记忆输入的往往是**任一个**，只按标题筛会漏。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PasskeyLoginPicker(
    candidates: List<VaultItem>,
    selected: VaultItem?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (VaultItem) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    // 关掉菜单时清空搜索词：下次展开是一个干净的全量列表，
    // 而不是上次筛剩下的几条（那不是用户此刻的意图）。
    // 用 side-effect 而不是在组合里直接 `query = ""`：后者是在渲染期间写状态，
    // 会让同一帧再跑一次组合，且 Compose 明确不建议。
    LaunchedEffect(expanded) {
        if (!expanded) query = ""
    }

    val visible = remember(candidates, query) {
        val q = query.trim()
        if (q.isEmpty()) {
            candidates
        } else {
            candidates.filter {
                it.title.contains(q, ignoreCase = true) || it.username.contains(q, ignoreCase = true)
            }
        }
    }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = onExpandedChange) {
        OutlinedTextField(
            value = selected?.title.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.passkey_select_login)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            // 菜单内的搜索框（`menuAnchor()` 不能加，见 KDoc）。
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.passkey_search_login)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            )
            if (visible.isEmpty()) {
                // 空结果也要给一句话：菜单只剩一个搜索框却不说话，用户会以为卡住了。
                Text(
                    text = stringResource(R.string.passkey_search_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                )
            }
            visible.forEach { login ->
                DropdownMenuItem(
                    text = {
                        // 标题为主、账号为副：标题可能重复（两个 GitHub），账号用来区分。
                        Column {
                            Text(login.title.ifBlank { login.username })
                            if (login.title.isNotBlank() && login.username.isNotBlank()) {
                                Text(
                                    text = login.username,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    onClick = {
                        onSelect(login)
                        // 选完就收：用户的目的已经达成，菜单留在屏幕上只会挡住下面的表单。
                        onExpandedChange(false)
                    },
                )
            }
        }
    }
}
