package io.vaultix.vaultix.ui.totp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.vaultix.common.OtpType
import io.vaultix.common.TotpConfig
import io.vaultix.common.TotpGenerator
import io.vaultix.model.VaultItem
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.common.EntryCard
import io.vaultix.vaultix.ui.common.EntryCardIconSpacing
import io.vaultix.vaultix.ui.common.EntryCardTextSpacing
import io.vaultix.vaultix.ui.common.PressAndSwipeToDelete
import io.vaultix.vaultix.ui.common.SiteIconByHost
import io.vaultix.vaultix.ui.common.VaultixExpressiveTopBar
import io.vaultix.vaultix.ui.common.VaultixSearchTopAppBar
import io.vaultix.vaultix.ui.common.rememberImmersiveBarPadding
import io.vaultix.vaultix.ui.common.rememberScrollCollapseFraction
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
    /** 底部叠层悬浮栏占用的高度（宿主给；非内嵌时为 0）——列表要留出它，否则末条被压住。 */
    bottomInset: Dp = 0.dp,
    viewModel: TotpCodesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    // 搜索态自己消费返回手势：主界面是根路由（栈里没有上一层），不拦就会直接退回桌面。
    BackHandler(enabled = searchActive) {
        searchActive = false
        viewModel.setQuery("")
    }
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
        // 顶栏改为**浮在内容之上**的画法（沉浸式，见 [VaultixExpressiveTopBar]）：
        // Scaffold 不再为顶栏预留空间，状态栏内边距由顶栏自己处理。
        contentWindowInsets = if (searchActive) {
            ScaffoldDefaults.contentWindowInsets
        } else {
            WindowInsets(0, 0, 0, 0)
        },
        topBar = {
            // 搜索态仍需固定高度顶栏（输入框不能塞进会折叠的大标题里）。
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
        val listState = rememberLazyListState()
        val collapse = rememberScrollCollapseFraction(listState)
        val barPadding = rememberImmersiveBarPadding(collapse)
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 顶部让出「状态栏 + 顶栏」的高度（随收起动画变短），顶栏浮在它之上。
            // ⚠️ 搜索态必须归零：此时 Scaffold 已按 `ScaffoldDefaults.contentWindowInsets`
            // 为搜索顶栏预留了高度，这里再叠一次就是「双重留白」= 纯黑大横幅。
            val barTopInset = if (searchActive) 0.dp else barPadding
            Column(modifier = Modifier.fillMaxSize().padding(top = barTopInset)) {
                // 整页一条倒计时进度条，「通行密钥」入口挂在它右侧。
                TotpPageProgress(
                    entries = entries,
                    nowSeconds = nowSeconds,
                    onOpenPasskeys = onOpenPasskeys,
                )
                when {
                    state.items.isEmpty() -> EmptyTotpState(
                        title = stringResource(R.string.totp_empty_title),
                        message = stringResource(R.string.totp_empty_body),
                    )

                    entries.isEmpty() -> EmptyTotpState(
                        message = stringResource(R.string.totp_empty_body),
                    )

                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        // 与密码列表同一套留白结构（卡片不再自带外边距，见 [EntryCard]）。
                        // 底部留出叠层悬浮底栏的高度，否则最后一条被胶囊压住。
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            top = 8.dp,
                            end = 16.dp,
                            bottom = 8.dp + bottomInset,
                        ),
                        verticalArrangement = Arrangement.spacedBy(TOTP_CARD_GAP),
                    ) {
                        items(entries, key = { it.itemId }) { entry ->
                            TotpRow(
                                entry = entry,
                                nowSeconds = nowSeconds,
                                serverOrigin = state.serverOrigin,
                                snackbarHostState = snackbarHostState,
                                onEdit = { editing = entry },
                                onDelete = { viewModel.deleteTotp(entry) },
                                onBind = { if (!entry.bound) binding = entry },
                                onCopy = viewModel::copyCode,
                            )
                        }
                    }
                }
            }
            if (!searchActive) {
                VaultixExpressiveTopBar(
                    title = stringResource(R.string.totp_screen_title),
                    collapseFraction = collapse,
                    modifier = Modifier.align(Alignment.TopCenter),
                    navigationIcon = if (embedded) {
                        null
                    } else {
                        {
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
                    },
                )
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
    serverOrigin: String?,
    snackbarHostState: SnackbarHostState,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onBind: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val code = TotpGenerator.generate(entry.toConfig(), nowSeconds)
    val isHotp = entry.type == OtpType.HOTP
    // HOTP 没有时间衰减，不做过期警示。
    val remaining = TotpGenerator.remainingSeconds(entry.period, nowSeconds)
    val copiedMessage = stringResource(R.string.copy_totp)
    val scope = rememberCoroutineScope()
    // 点一下即复制（对齐 Bastion 验证器页：整行可点 → 复制 + 提示）。
    // ⚠️ 显式标注返回类型：`scope.launch` 的返回值是 Job，推断成 `() -> Job` 会与
    // EntryCard 的 `onClick: () -> Unit` 不匹配（编译期报 "actual type is () -> Job"）。
    val copyNow: () -> Unit = {
        onCopy(code)
        scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
    }

    // 卡片外框与密码 / 卡包列表完全一致（见 [EntryCard]）；内边距由卡片统一给 16dp。
    // 「按住后滑动删除」包在外层（见 [PressAndSwipeToDelete]）。
    PressAndSwipeToDelete(onDelete = onDelete) {
        EntryCard(onClick = copyNow) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 站点图标（库内服务器地址 + 条目域名），取不到回退首字母 ——
                // 与密码列表同一套观感（见 [SiteIconByHost]）。
                SiteIconByHost(
                    domain = entry.domain,
                    fallbackText = entry.title,
                    serverOrigin = serverOrigin,
                )
                Spacer(Modifier.width(EntryCardIconSpacing))
                Text(
                    text = entry.title.ifBlank { stringResource(R.string.totp_screen_title) },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
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
                    modifier = Modifier.padding(top = EntryCardTextSpacing),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SelectionContainer(modifier = Modifier.weight(1f)) {
                    Text(
                        text = groupCode(code),
                        // 对齐 Bastion `TotpCodeCard`（40sp / 普通模式 32–36sp）：
                        // 验证码是「一眼读出来照着敲」的数字，24sp 的 `headlineSmall` 在小屏上
                        // 得凑近看；**等宽**保证每秒刷新时数字宽度不抖，分组空格（[groupCode]）
                        // 比 letterSpacing 更利于口头念读。
                        fontSize = TOTP_CODE_FONT_SP,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold,
                        // 剩余 ≤5 秒转警示色：不必盯着顶部进度条也知道「快过期了，先别念」。
                        color = if (isHotp || remaining > TOTP_HOT_WARNING_SECONDS) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
                if (isHotp) {
                    // HOTP 基于计数器，无时间衰减：展示当前 counter 而非倒计时
                    Text(
                        text = stringResource(R.string.totp_hotp_counter, entry.counter),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = copyNow) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.totp_action_copy),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            // 倒计时不再逐行画进度条：整页共用顶部的统一进度条（见 [UnifiedTotpProgressBar]），
            // 既统一观感，也省掉每行每秒一次的绘制/动画开销（用户要求「降低功耗」）。
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!entry.bound) {
                    TextButton(onClick = onBind) { Text(stringResource(R.string.totp_action_bind)) }
                }
                Spacer(Modifier.weight(1f))
                // 整行点击已改为「复制」，编辑入口因此必须显式留一个（否则改不了条目）。
                TextButton(onClick = onEdit) { Text(stringResource(R.string.totp_action_edit)) }
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

/**
 * 整页统一倒计时进度条 + 「通行密钥」入口。
 *
 * 抽成独立 composable 的原因：主函数已贴着 detekt `LongMethod ≤150` 的门禁线，
 * 而这段逻辑（挑「最近过期」的条目决定周期、空列表时用占位条）与主流程无耦合。
 *
 * 「通行密钥」入口挂在进度条右侧，**空列表时也必须渲染**
 * （[UnifiedTotpProgressPlaceholder]）—— 否则用户在一条验证码都没有时，
 * 会彻底失去进入通行密钥页的路径（对齐 Bastion）。
 */
@Composable
private fun TotpPageProgress(
    entries: List<TotpEntry>,
    nowSeconds: Long,
    onOpenPasskeys: () -> Unit,
) {
    val passkeyEntry: @Composable () -> Unit = {
        IconButton(onClick = onOpenPasskeys) {
            Icon(
                imageVector = Icons.Filled.Fingerprint,
                contentDescription = stringResource(R.string.totp_passkey_button),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
    val soonest = entries.minByOrNull { TotpGenerator.remainingSeconds(it.period, nowSeconds) }
    if (soonest == null) {
        UnifiedTotpProgressPlaceholder(trailingContent = passkeyEntry)
    } else {
        UnifiedTotpProgressBar(
            periodSeconds = soonest.period,
            nowSeconds = nowSeconds,
            trailingContent = passkeyEntry,
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

/**
 * 验证码字号（对齐 Bastion `TotpCodeCard`：统一进度条模式 40sp / 普通 32–36sp）。
 * 取 36sp：小屏一行放得下 6 位分组码 + 复制按钮，又明显大于正文。
 */
private val TOTP_CODE_FONT_SP = 36.sp

/** 剩余秒数 ≤ 它时验证码转 `error` 警示色（对齐 Bastion 的 5 秒阈值）。 */
private const val TOTP_HOT_WARNING_SECONDS = 5

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

/** 卡片之间的纵向间距（与密码列表一致）。 */
private val TOTP_CARD_GAP = 8.dp
