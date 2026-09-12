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
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.vaultix.common.OtpType
import io.vaultix.common.TotpConfig
import io.vaultix.common.TotpGenerator
import io.vaultix.model.VaultItem
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.common.VaultixSearchTopAppBar
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
    /** 主界面 Tab 内嵌模式：隐藏返回键与 FAB（「+」由底部导航条统一承载）。 */
    embedded: Boolean = false,
    /** 外部「+」请求计数：非零即打开新建 TOTP 表单。 */
    addRequest: Int = 0,
    onAddConsumed: () -> Unit = {},
    viewModel: TotpCodesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TotpEntry?>(null) }
    var binding by remember { mutableStateOf<TotpEntry?>(null) }
    var importOpen by remember { mutableStateOf(false) }

    // 实时时钟：每秒推进，驱动所有验证码滚动刷新
    var nowSeconds by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(TOTP_TICK_MS)
            nowSeconds = System.currentTimeMillis() / MILLIS_PER_SECOND
        }
    }

    // 底部导航条「+」→ 打开新建 TOTP 表单（Tab 内嵌时不展示自己的 FAB）
    LaunchedEffect(addRequest) {
        if (addRequest > 0) {
            editing = TotpEntry.empty()
            onAddConsumed()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (searchActive) {
                VaultixSearchTopAppBar(
                    searchTerm = state.query,
                    placeholder = stringResource(R.string.totp_search_hint),
                    onSearchTermChange = viewModel::setQuery,
                    onClose = {
                        searchActive = false
                        viewModel.setQuery("")
                    },
                    clearIconContentDescription = stringResource(R.string.items_search_clear),
                    scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
                )
            } else {
                LargeTopAppBar(
                    title = { Text(text = stringResource(R.string.totp_screen_title)) },
                    navigationIcon = {
                        if (!embedded) {
                            IconButton(onClick = onBack) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.action_back),
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { searchActive = true }) {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = stringResource(R.string.totp_search_hint),
                            )
                        }
                        IconButton(onClick = { importOpen = true }) {
                            Icon(
                                Icons.Filled.FileDownload,
                                contentDescription = stringResource(R.string.totp_import_button),
                            )
                        }
                        IconButton(onClick = onOpenPasskeys) {
                            Icon(
                                Icons.Filled.Key,
                                contentDescription = stringResource(R.string.totp_passkey_button),
                            )
                        }
                    },
                    scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
                )
            }
        },
        floatingActionButton = {
            if (!embedded) {
                FloatingActionButton(onClick = { editing = TotpEntry.empty() }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.totp_add_title))
                }
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
                            onCopy = viewModel::copyCode,
                        )
                    }
                }
            }
        }
    }

    TotpDialogs(
        editing = editing,
        binding = binding,
        importOpen = importOpen,
        viewModel = viewModel,
        snackbarHostState = snackbarHostState,
        onEditingChange = { editing = it },
        onBindingChange = { binding = it },
        onImportOpenChange = { importOpen = it },
    )
}

/**
 * 三个对话框（编辑 / 绑定密码条目 / 导入）。
 *
 * 抽出来是为让 [TotpCodesScreen] 守住 detekt 门禁（2026-09-12：主函数曾达 151 行 /
 * 圈复杂度 15，均超阈值 —— 上一轮加 `embedded` / `addRequest` 参数时被推过线）。
 * 对话框本身是独立交互单元，与页面骨架无耦合，拆出后主函数只剩「骨架 + 状态分发」。
 */
@Composable
private fun TotpDialogs(
    editing: TotpEntry?,
    binding: TotpEntry?,
    importOpen: Boolean,
    viewModel: TotpCodesViewModel,
    snackbarHostState: SnackbarHostState,
    onEditingChange: (TotpEntry?) -> Unit,
    onBindingChange: (TotpEntry?) -> Unit,
    onImportOpenChange: (Boolean) -> Unit,
) {
    editing?.let { entry ->
        TotpEditDialog(
            entry = entry,
            onDismiss = { onEditingChange(null) },
            onSave = { issuer, account, config ->
                viewModel.saveTotp(
                    entryId = entry.itemId.takeIf { it.isNotEmpty() && entry.totpRaw.isNotEmpty() },
                    issuer = issuer,
                    account = account,
                    config = config,
                )
                onEditingChange(null)
            },
            onDelete = if (entry.totpRaw.isNotEmpty()) {
                {
                    viewModel.deleteTotp(entry)
                    onEditingChange(null)
                }
            } else {
                null
            },
        )
    }

    binding?.let { entry ->
        LoginPickerDialog(
            candidates = viewModel.loginCandidates(entry.itemId),
            onDismiss = { onBindingChange(null) },
            onPick = { login ->
                viewModel.bindStandaloneToLogin(entry, login.id)
                onBindingChange(null)
            },
        )
    }

    if (importOpen) {
        ImportDialogWithOutcome(
            viewModel = viewModel,
            snackbarHostState = snackbarHostState,
            onSingle = { onEditingChange(it) },
            onDismiss = { onImportOpenChange(false) },
        )
    }
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
    onCopy: (String) -> Unit,
) {
    val code = TotpGenerator.generate(entry.toConfig(), nowSeconds)
    val isHotp = entry.type == OtpType.HOTP
    val remaining = TotpGenerator.remainingSeconds(entry.period, nowSeconds)
    // 数据层是**秒级**（每秒一次重组），进度条若直接用阶梯值会一秒一跳；
    // 平滑视觉在绘制层补齐（对齐 Bastion `rememberTotpSmoothProgress`）。
    val progress = rememberTotpSmoothProgress(1f - (remaining.toFloat() / entry.period))
    val copiedMessage = stringResource(R.string.copy_totp)
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
                    text = entry.title.ifBlank { stringResource(R.string.totp_screen_title) },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Badge(entry.bound)
            }
            if (entry.account.isNotBlank()) {
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
                if (isHotp) {
                    // HOTP 基于计数器，无时间衰减：展示当前 counter 而非倒计时
                    Text(
                        text = stringResource(R.string.totp_hotp_counter, entry.counter),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = "${remaining}s",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = {
                    onCopy(code)
                    scope.launch {
                        snackbarHostState.showSnackbar(copiedMessage)
                    }
                }) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.totp_action_copy),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (!isHotp) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
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

// 类型固定参数（对齐 Bastion TotpData 的固定口径）
private const val MOTP_FIXED_PERIOD = 10
private const val MOTP_FIXED_DIGITS = 6
private const val DEFAULT_EDIT_PERIOD = 30
private const val DEFAULT_EDIT_DIGITS = 6

// ===== 编辑 / 新增对话框（类型对齐 Bastion：TOTP/HOTP/Steam/Yandex/mOTP）=====

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TotpEditDialog(
    entry: TotpEntry,
    onDismiss: () -> Unit,
    onSave: (issuer: String, account: String, config: TotpConfig) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var issuer by remember { mutableStateOf(entry.issuer) }
    var account by remember { mutableStateOf(entry.account) }
    var secret by remember { mutableStateOf(entry.secret) }
    var type by remember { mutableStateOf(entry.type) }
    var period by remember { mutableStateOf(entry.period.toString()) }
    var digits by remember { mutableStateOf(entry.digits.toString()) }
    var algorithm by remember { mutableStateOf(entry.algorithm) }
    var counter by remember { mutableStateOf(entry.counter.toString()) }
    var pin by remember { mutableStateOf(entry.pin) }
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
                TypeDropdown(type) { type = it }
                Spacer(Modifier.height(8.dp))
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
                    label = { Text(secretLabel(type)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                when (type) {
                    OtpType.MOTP -> TotpMotpFields(pin, onPinChange = { pin = it })
                    OtpType.STEAM -> {
                        // Steam 固定 5 位 / 30s / SHA1，无可调参数
                    }
                    OtpType.HOTP -> TotpHotpFields(
                        counter = counter,
                        onCounterChange = { counter = it },
                        digits = digits,
                        onDigitsChange = { digits = it },
                        algorithm = algorithm,
                        onAlgorithmChange = { algorithm = it },
                    )
                    OtpType.TOTP, OtpType.YANDEX -> TotpTimedFields(
                        period = period,
                        onPeriodChange = { period = it },
                        digits = digits,
                        onDigitsChange = { digits = it },
                        algorithm = algorithm,
                        onAlgorithmChange = { algorithm = it },
                    )
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
                if (secret.isBlank()) {
                    showError = true
                    return@TextButton
                }
                onSave(issuer, account, buildTotpConfig(type, secret, period, digits, algorithm, counter, pin))
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            // ⚠️ 2026-09-08 修复数据丢失 bug：此前 onDelete != null 时这里只有一个
            // 按钮，文案是「取消」，onClick 却绑定 onDelete（删除验证码/软删独立
            // 条目）——用户取消编辑等于直接删除。现在「移除」与「取消」并排，
            // 且破坏性的移除用 error 色区分。
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text(
                            stringResource(R.string.totp_remove_action),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        },
    )
}

/** mOTP 专属字段:PIN 码(密钥为原始字符串,固定 10s / 6 位)。 */
@Composable
private fun TotpMotpFields(pin: String, onPinChange: (String) -> Unit) {
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = pin,
        onValueChange = onPinChange,
        label = { Text(stringResource(R.string.totp_field_pin)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        text = stringResource(R.string.totp_motp_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** HOTP 专属字段:计数器 + 位数 + 算法(步长由 counter 代替,无刷新周期)。 */
@Composable
private fun TotpHotpFields(
    counter: String,
    onCounterChange: (String) -> Unit,
    digits: String,
    onDigitsChange: (String) -> Unit,
    algorithm: String,
    onAlgorithmChange: (String) -> Unit,
) {
    Spacer(Modifier.height(8.dp))
    Row {
        OutlinedTextField(
            value = counter,
            onValueChange = onCounterChange,
            label = { Text(stringResource(R.string.totp_field_counter)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = digits,
            onValueChange = onDigitsChange,
            label = { Text(stringResource(R.string.totp_field_digits)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(8.dp))
    AlgorithmDropdown(algorithm, onSelected = onAlgorithmChange)
}

/** TOTP / Yandex 通用字段:刷新周期 + 位数 + 算法。 */
@Composable
private fun TotpTimedFields(
    period: String,
    onPeriodChange: (String) -> Unit,
    digits: String,
    onDigitsChange: (String) -> Unit,
    algorithm: String,
    onAlgorithmChange: (String) -> Unit,
) {
    Spacer(Modifier.height(8.dp))
    Row {
        OutlinedTextField(
            value = period,
            onValueChange = onPeriodChange,
            label = { Text(stringResource(R.string.totp_field_period)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = digits,
            onValueChange = onDigitsChange,
            label = { Text(stringResource(R.string.totp_field_digits)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
    }
    Spacer(Modifier.height(8.dp))
    AlgorithmDropdown(algorithm, onSelected = onAlgorithmChange)
}

/** 按类型归一化参数并构造 [TotpConfig](mOTP/Steam 的固定口径在此收敛)。 */
private fun buildTotpConfig(
    type: OtpType,
    secret: String,
    period: String,
    digits: String,
    algorithm: String,
    counter: String,
    pin: String,
): TotpConfig {
    val p = period.toIntOrNull()?.coerceAtLeast(1) ?: DEFAULT_EDIT_PERIOD
    val d = digits.toIntOrNull()?.coerceIn(1, 10) ?: DEFAULT_EDIT_DIGITS
    return TotpConfig(
        secret = secret.trim(),
        period = if (type == OtpType.MOTP) MOTP_FIXED_PERIOD else p,
        digits = if (type == OtpType.MOTP) MOTP_FIXED_DIGITS else d,
        algorithm = if (type == OtpType.STEAM || type == OtpType.MOTP) "SHA1" else algorithm,
        type = type,
        counter = counter.toLongOrNull() ?: 0L,
        pin = pin.trim(),
    )
}

@Composable
private fun secretLabel(type: OtpType): String = stringResource(
    when (type) {
        OtpType.MOTP -> R.string.totp_field_motp_secret
        else -> R.string.totp_field_secret
    },
)

/** 导入对话框 + 结果处理：单条预填编辑、批量提示计数、失败提示原因。 */
@Composable
private fun ImportDialogWithOutcome(
    viewModel: TotpCodesViewModel,
    snackbarHostState: SnackbarHostState,
    onSingle: (TotpEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // 事件回调（非 composition）里无法调 stringResource，提前解析固定文案
    val invalidMessage = stringResource(R.string.totp_import_invalid)
    val unsupportedMessage = stringResource(R.string.totp_import_unsupported)
    ImportDialog(
        onDismiss = onDismiss,
        onImport = { raw ->
            when (val outcome = viewModel.importTotp(raw)) {
                is ImportOutcome.Single -> onSingle(outcome.entry)
                is ImportOutcome.Multiple -> scope.launch {
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.totp_import_imported, outcome.count),
                    )
                }
                ImportOutcome.Unsupported -> scope.launch {
                    snackbarHostState.showSnackbar(unsupportedMessage)
                }
                ImportOutcome.Invalid -> scope.launch {
                    snackbarHostState.showSnackbar(invalidMessage)
                }
            }
            onDismiss()
        },
    )
}

/** 粘贴导入对话框：otpauth / motp / otpauth-migration / 裸密钥。 */
@Composable
private fun ImportDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.totp_import_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.totp_import_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.totp_import_label)) },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onImport(text) },
                enabled = text.isNotBlank(),
            ) { Text(stringResource(R.string.totp_import_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TypeDropdown(value: OtpType, onSelected: (OtpType) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = typeLabel(value),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.totp_field_type)) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            OtpType.entries.forEach { candidate ->
                DropdownMenuItem(
                    text = { Text(typeLabel(candidate)) },
                    onClick = { onSelected(candidate); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun typeLabel(type: OtpType): String = stringResource(
    when (type) {
        OtpType.TOTP -> R.string.totp_type_totp
        OtpType.HOTP -> R.string.totp_type_hotp
        OtpType.STEAM -> R.string.totp_type_steam
        OtpType.YANDEX -> R.string.totp_type_yandex
        OtpType.MOTP -> R.string.totp_type_motp
    },
)

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

/**
 * 验证码倒计时的**平滑进度**（移植自 Bastion `rememberTotpSmoothProgress`，GPL-3.0，
 * Copyright 2025 JoyinJoester）。
 *
 * 背景：Vaultix 的 TOTP 数据源是**秒级**的（每秒一次重组），进度条直接用阶梯值会一秒一跳；
 * Bastion 为此专门做过性能修订 —— 数据层从 50ms 高频发射改回秒级（50ms 会让整个列表
 * 每秒 20×N 次重组，是长列表掉帧主因），**平滑视觉改由绘制层动画补齐**：
 * - 常规推进：对秒级阶梯值施加 1s 线性动画，视觉上等价于匀速推进；
 * - 周期翻转：目标值从 ~1 回落到 ~0 时直接 `snap()`，避免动画倒着卷回去。
 */
@Composable
private fun rememberTotpSmoothProgress(target: Float): Float {
    val clamped = target.coerceIn(0f, 1f)
    var lastTarget by remember { mutableFloatStateOf(clamped) }
    val wrapDetected = clamped < lastTarget - 0.5f
    val animated by animateFloatAsState(
        targetValue = clamped,
        animationSpec = if (wrapDetected) {
            snap()
        } else {
            tween(durationMillis = TOTP_PROGRESS_ANIM_MS, easing = LinearEasing)
        },
        label = "totp_smooth_progress",
    )
    SideEffect { lastTarget = clamped }
    return animated
}

/** 平滑动画时长：与秒级数据源同周期（1s），视觉上恰好匀速。 */
private const val TOTP_PROGRESS_ANIM_MS = 1_000
