package io.vaultix.vaultix.ui.totp

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.vaultix.common.TotpGenerator
import io.vaultix.model.VaultItem
import io.vaultix.vaultix.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 验证码统一界面（对齐 Bitwarden 的 TOTP 总览 + Bastion 独立验证器视图）。
 *
 * - 实时滚动验证码（逐秒刷新 + 进度条），点按复制当前码；
 * - 搜索发行方/账号；
 * - 条目分「已绑定 / 独立」两种徽标；独立项可「绑定到密码条目」；
 * - 编辑 / 删除；右下角新增独立验证码；
 * - 顶栏「通行密钥」按钮进入通行密钥列表（PasskeysRoute）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TotpCodesScreen(
    onBack: () -> Unit,
    onOpenPasskeys: () -> Unit,
    viewModel: TotpCodesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var searchActive by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TotpEntry?>(null) }
    var binding by remember { mutableStateOf<TotpEntry?>(null) }

    // 实时时钟：每秒推进，驱动所有验证码滚动刷新
    var nowSeconds by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(TOTP_TICK_MS)
            nowSeconds = System.currentTimeMillis() / MILLIS_PER_SECOND
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            LargeTopAppBar(
                title = {
                    if (searchActive) {
                        SearchField(
                            value = state.query,
                            onValueChange = viewModel::setQuery,
                            onClose = { searchActive = false; viewModel.setQuery("") },
                        )
                    } else {
                        Text(text = stringResource(R.string.totp_screen_title))
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
                        Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.totp_search_hint))
                    }
                    IconButton(onClick = onOpenPasskeys) {
                        Icon(Icons.Filled.Key, contentDescription = stringResource(R.string.totp_passkey_button))
                    }
                },
                scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = TotpEntry.empty() }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.totp_add_title))
            }
        },
    ) { padding ->
        val entries = viewModel.filteredEntries()
        when {
            state.items.isEmpty() -> EmptyTotpState(
                title = stringResource(R.string.totp_empty_title),
                message = stringResource(R.string.totp_empty_body),
            )
            entries.isEmpty() -> EmptyTotpState(
                message = stringResource(R.string.totp_empty_body),
            )
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    items(entries, key = { it.itemId }) { entry ->
                        TotpRow(
                            entry = entry,
                            nowSeconds = nowSeconds,
                            snackbarHostState = snackbarHostState,
                            onClick = { editing = entry },
                            onDelete = { viewModel.deleteTotp(entry) },
                            onBind = { if (!entry.bound) binding = entry },
                        )
                    }
                }
            }
        }
    }

    editing?.let { entry ->
        TotpEditDialog(
            entry = entry,
            onDismiss = { editing = null },
            onSave = { issuer, account, secret, period, digits, algorithm, steam ->
                viewModel.saveTotp(
                    entryId = entry.itemId.takeIf { it.isNotEmpty() && entry.totpRaw.isNotEmpty() },
                    issuer = issuer,
                    account = account,
                    secret = secret,
                    period = period,
                    digits = digits,
                    algorithm = algorithm,
                    steam = steam,
                )
                editing = null
            },
            onDelete = if (entry.totpRaw.isNotEmpty()) {
                {
                    viewModel.deleteTotp(entry)
                    editing = null
                }
            } else {
                null
            },
        )
    }

    binding?.let { entry ->
        LoginPickerDialog(
            candidates = viewModel.loginCandidates(entry.itemId),
            onDismiss = { binding = null },
            onPick = { login ->
                viewModel.bindStandaloneToLogin(entry, login.id)
                binding = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onClose: () -> Unit,
) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(stringResource(R.string.totp_search_hint)) },
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel))
                }
            },
            modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
        )
}

@Composable
private fun EmptyTotpState(title: String? = null, message: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (title != null) {
            Text(text = title, style = MaterialTheme.typography.titleLarge)
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, start = 24.dp, end = 24.dp),
        )
    }
}

/** 单个验证码行：实时码 + 进度条 + 复制；点击进入编辑。 */
@Composable
private fun TotpRow(
    entry: TotpEntry,
    nowSeconds: Long,
    snackbarHostState: SnackbarHostState,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onBind: () -> Unit,
) {
    val code = currentCode(entry, nowSeconds)
    val remaining = TotpGenerator.remainingSeconds(entry.period, nowSeconds)
    val progress = 1f - (remaining.toFloat() / entry.period)
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.label.ifBlank { stringResource(R.string.totp_screen_title) },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Badge(entry.bound)
            }
            if (entry.account.isNotBlank() && entry.account != entry.label) {
                Text(
                    text = entry.account,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SelectionContainer(modifier = Modifier.weight(1f)) {
                    Text(
                        text = groupCode(code),
                        style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 2.sp,
                    )
                }
                Text(
                    text = "${remaining}s",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = {
                    clipboard.setText(AnnotatedString(code))
                    scope.launch { snackbarHostState.showSnackbar(code) }
                }) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.totp_action_copy),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!entry.bound) {
                    TextButton(onClick = onBind) { Text(stringResource(R.string.totp_action_bind)) }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDelete) { Text(stringResource(R.string.totp_remove_action)) }
            }
        }
    }
}

@Composable
private fun Badge(bound: Boolean) {
    val text = if (bound) R.string.totp_badge_bound else R.string.totp_badge_standalone
    val color = if (bound) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
    Surface(color = color.copy(alpha = 0.14f), shape = RoundedCornerShape(6.dp)) {
        Text(
            text = stringResource(text),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** 计算当前验证码（Steam 走专属字母表）。 */
private fun currentCode(entry: TotpEntry, nowSeconds: Long): String = if (entry.steam) {
    TotpGenerator.generateSteamTotp(entry.secret, nowSeconds, entry.period)
} else {
    TotpGenerator.generateTotp(entry.secret, nowSeconds, entry.period, entry.digits, entry.algorithm)
}

/** 每 3 位分组显示，便于人工录入（123456 → 123 456）。 */
private fun groupCode(code: String): String {
    if (code.length <= TOTP_CODE_GROUP) {
        return code
    }
    return buildString {
        code.forEachIndexed { index, c ->
            if (index > 0 && index % TOTP_CODE_GROUP == 0) append(' ')
            append(c)
        }
    }
}

private const val TOTP_TICK_MS = 1000L
private const val TOTP_CODE_GROUP = 3
private const val MILLIS_PER_SECOND = 1000

// ===== 编辑 / 新增对话框（复用下方通用组件）=====

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TotpEditDialog(
    entry: TotpEntry,
    onDismiss: () -> Unit,
    onSave: (
        issuer: String,
        account: String,
        secret: String,
        period: Int,
        digits: Int,
        algorithm: String,
        steam: Boolean,
    ) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var issuer by remember { mutableStateOf(entry.issuer) }
    var account by remember { mutableStateOf(entry.account) }
    var secret by remember { mutableStateOf(entry.secret) }
    var period by remember { mutableStateOf(entry.period.toString()) }
    var digits by remember { mutableStateOf(entry.digits.toString()) }
    var algorithm by remember { mutableStateOf(entry.algorithm) }
    var steam by remember { mutableStateOf(entry.steam) }
    var showError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (entry.totpRaw.isEmpty()) R.string.totp_add_title else R.string.totp_edit_title,
                ),
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = issuer,
                    onValueChange = { issuer = it },
                    label = { Text(stringResource(R.string.totp_field_issuer)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = account,
                    onValueChange = { account = it },
                    label = { Text(stringResource(R.string.totp_field_account)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = { Text(stringResource(R.string.totp_field_secret)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    OutlinedTextField(
                        value = period,
                        onValueChange = { period = it },
                        label = { Text(stringResource(R.string.totp_field_period)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = digits,
                        onValueChange = { digits = it },
                        label = { Text(stringResource(R.string.totp_field_digits)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                AlgorithmDropdown(algorithm) { algorithm = it }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = steam, onCheckedChange = { steam = it })
                    Text(stringResource(R.string.totp_steam_label))
                }
                if (showError) {
                    Text(
                        stringResource(R.string.totp_invalid_secret),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val p = period.toIntOrNull()?.coerceAtLeast(1) ?: 30
                val d = digits.toIntOrNull()?.coerceIn(1, 10) ?: 6
                if (secret.isBlank()) {
                    showError = true
                    return@TextButton
                }
                onSave(issuer, account, secret, p, d, algorithm, steam)
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            if (onDelete != null) {
                TextButton(onClick = onDelete) { Text(stringResource(R.string.action_cancel)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlgorithmDropdown(value: String, onSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.totp_field_algorithm)) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ALGORITHMS.forEach { alg ->
                DropdownMenuItem(text = { Text(alg) }, onClick = { onSelected(alg); expanded = false })
            }
        }
    }
}

@Composable
private fun LoginPickerDialog(
    candidates: List<VaultItem>,
    onDismiss: () -> Unit,
    onPick: (VaultItem) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.totp_bind_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.totp_bind_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                if (candidates.isEmpty()) {
                    Text(stringResource(R.string.passkey_select_login), style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().height(240.dp)) {
                        items(candidates, key = { it.id }) { login ->
                            TextButton(onClick = { onPick(login) }, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    login.title.ifBlank { login.username },
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Start,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
        dismissButton = { },
    )
}

private val ALGORITHMS = listOf("SHA1", "SHA256", "SHA512")
