package io.vaultix.vaultix.ui.passkeys

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.vaultix.model.VaultFido2Credential
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.common.SavePasskeyDialog

/**
 * 通行密钥列表（从验证码界面「通行密钥」按钮进入）。
 * 对齐 Bitwarden / Keyguard：通行密钥只读（仅查看 / 删除），不可编辑——密钥由服务器 / 平台管理。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasskeysScreen(
    onBack: () -> Unit,
    viewModel: PasskeysViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var searchActive by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<PasskeyRow?>(null) }
    var saving by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            LargeTopAppBar(
                title = {
                    if (searchActive) {
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = viewModel::setQuery,
                            placeholder = { Text(stringResource(R.string.passkeys_search_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                        )
                    } else {
                        Text(text = stringResource(R.string.passkeys_screen_title))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { searchActive = true }) {
                        Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.passkeys_search_hint))
                    }
                },
                scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { saving = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.passkeys_add_title))
            }
        },
    ) { padding ->
        val rows = viewModel.passkeyRows()
        if (state.items.isEmpty() || rows.isEmpty()) {
            EmptyPasskeysState(
                totalItems = state.items.size,
                withFido2 = state.items.count { it.fido2Credentials.isNotEmpty() },
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            ) {
                items(rows, key = { "${it.itemId}:${it.credential.credentialId}" }) { row ->
                    PasskeyRowItem(row = row, onClick = { detail = row })
                }
            }
        }
    }

    detail?.let { row ->
        PasskeyDetailDialog(
            row = row,
            onDismiss = { detail = null },
            onDelete = { viewModel.deleteCredential(row); detail = null },
        )
    }

    if (saving) {
        SavePasskeyDialog(
            candidates = viewModel.loginCandidates(),
            onDismiss = { saving = false },
            onSave = { loginId, credential ->
                viewModel.savePasskey(loginId, credential)
                saving = false
            },
        )
    }
}

@Composable
/**
 * 空态：顺带把「库里多少条目 / 多少含通行密钥」显示出来——
 * 「读取不到」时一眼能分清是**服务端没数据**还是**本地解析不出来**。
 */
private fun EmptyPasskeysState(totalItems: Int, withFido2: Int) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = stringResource(R.string.passkeys_empty_title), style = MaterialTheme.typography.titleLarge)
        Text(
            text = stringResource(R.string.passkeys_empty_body, totalItems, withFido2),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, start = 24.dp, end = 24.dp),
        )
    }
}

@Composable
private fun PasskeyRowItem(row: PasskeyRow, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = CircleShape,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    Icons.Filled.Key,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(8.dp).size(24.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.credential.rpName.ifBlank { row.credential.rpId },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (row.credential.userName.isNotBlank()) {
                    Text(
                        text = row.credential.userName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(R.string.passkey_bound_to_login, row.loginTitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun PasskeyDetailDialog(row: PasskeyRow, onDismiss: () -> Unit, onDelete: () -> Unit) {
    val c = row.credential
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.passkeys_detail_title),
                    modifier = Modifier.weight(1f),
                )
                if (c.credentialId.isNotBlank()) {
                    IconButton(onClick = { clipboard.setText(AnnotatedString(c.credentialId)) }) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = stringResource(R.string.action_copy),
                        )
                    }
                }
            }
        },
        text = {
            Column {
                Text(
                    stringResource(R.string.passkeys_readonly_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                DetailLine(
                    stringResource(R.string.passkey_field_rp_name),
                    c.rpName.ifBlank { "—" },
                )
                DetailLine(stringResource(R.string.passkey_field_rp_id), c.rpId.ifBlank { "—" })
                DetailLine(stringResource(R.string.passkey_field_user), c.userName.ifBlank { "—" })
                DetailLine(stringResource(R.string.passkey_user), c.userDisplayName.ifBlank { "—" })
                DetailLine(stringResource(R.string.passkey_field_credential_id), c.credentialId.ifBlank { "—" })
                DetailLine(stringResource(R.string.passkey_field_key_algorithm), c.keyAlgorithm ?: "—")
                DetailLine(stringResource(R.string.passkey_field_counter), c.counter.toString())
                DetailLine(stringResource(R.string.passkey_field_discoverable), if (c.discoverable) "true" else "false")
                DetailLine(stringResource(R.string.passkey_field_created), c.creationDate ?: "—")
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.passkey_bound_to_login, row.loginTitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDelete) { Text(stringResource(R.string.totp_remove_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    if (value.isBlank()) return
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
