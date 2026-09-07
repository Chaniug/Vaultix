@file:Suppress("LocalContextGetResourceValueCall")
package com.bastion.app.ui.screens

import com.bastion.app.logging.runCatchingObserved
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.bastion.app.BuildConfig
import com.bastion.app.R
import com.bastion.app.autofill_ng.core.AutofillLogger
import com.bastion.app.bitwarden.service.BitwardenDiagLogger
import com.bastion.app.data.AppLauncherLabel
import com.bastion.app.passkey.PasskeyValidationDiagnostics
import com.bastion.app.security.SecurityDiagLogger
import com.bastion.app.viewmodel.SettingsViewModel

/**
 * 开发者设置页面
 * 包含日志查看、清除以及开发者专用功能
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun DeveloperSettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    var showDebugLogsDialog by remember { mutableStateOf(false) }
    var appLauncherLabel by remember {
        mutableStateOf(settings.appLauncherLabel)
    }
    LaunchedEffect(settings.appLauncherLabel) {
        appLauncherLabel = settings.appLauncherLabel
    }

    // 准备共享元素 Modifier
    val sharedTransitionScope = com.bastion.app.ui.LocalSharedTransitionScope.current
    val animatedVisibilityScope = com.bastion.app.ui.LocalAnimatedVisibilityScope.current

    var sharedModifier: Modifier = Modifier
    if (false && sharedTransitionScope != null && animatedVisibilityScope != null) {
        with(sharedTransitionScope!!) {
            sharedModifier = Modifier.sharedBounds(
                sharedContentState = rememberSharedContentState(key = "developer_settings_card"),
                animatedVisibilityScope = animatedVisibilityScope!!,
                resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds
            )
        }
    }

    Scaffold(
        modifier = sharedModifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.developer_settings)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.developer_settings_back)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
        ) {
            // 日志调试区域
            SettingsSection(
                title = stringResource(R.string.developer_log_debugging)
            ) {
                SettingsItem(
                    icon = Icons.Default.BugReport,
                    title = stringResource(R.string.developer_view_logs),
                    subtitle = stringResource(R.string.developer_view_logs_desc),
                    onClick = { showDebugLogsDialog = true }
                )

                SettingsItem(
                    icon = Icons.Default.DeleteSweep,
                    title = stringResource(R.string.developer_clear_log_buffer),
                    subtitle = stringResource(R.string.developer_clear_log_buffer_desc),
                    onClick = {
                        scope.launch {
                            val clearResult = DeveloperLogDebugHelper.clearLogs(context)
                            val message = if (clearResult.logcatCleared) {
                                context.getString(R.string.developer_log_buffer_cleared)
                            } else {
                                context.getString(
                                    R.string.developer_clear_failed,
                                    clearResult.reason ?: "unknown"
                                )
                            }
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }
                    }
                )

                SettingsItem(
                    icon = Icons.Default.Share,
                    title = stringResource(R.string.developer_share_logs),
                    subtitle = stringResource(R.string.developer_share_logs_desc),
                    onClick = {
                        scope.launch {
                            try {
                                val snapshot = DeveloperLogDebugHelper.collectLogs(context)
                                val shareIntent =
                                    DeveloperLogDebugHelper.createShareIntent(context, snapshot.report)
                                context.startActivity(
                                    Intent.createChooser(
                                        shareIntent,
                                        context.getString(R.string.developer_share_title)
                                    )
                                )
                            } catch (e: Exception) {
                                Toast.makeText(
                                    context,
                                    context.getString(
                                        R.string.developer_share_failed,
                                        e.message ?: "unknown"
                                    ),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                )
            }

            // 开发者功能
            SettingsSection(
                title = stringResource(R.string.developer_functions)
            ) {
                SettingsItemWithSwitch(
                    icon = Icons.Default.AutoAwesome,
                    title = stringResource(R.string.developer_launcher_name_use_pass),
                    subtitle = stringResource(R.string.developer_launcher_name_use_pass_desc),
                    checked = appLauncherLabel == AppLauncherLabel.MONICA_PASS,
                    onCheckedChange = { enabled ->
                        val nextLabel = if (enabled) {
                            AppLauncherLabel.MONICA_PASS
                        } else {
                            AppLauncherLabel.MONICA
                        }
                        appLauncherLabel = nextLabel
                        scope.launch {
                            viewModel.updateAppLauncherLabel(nextLabel)
                        }
                    }
                )

                SettingsItemWithSwitch(
                    icon = Icons.Default.LockOpen,
                    title = stringResource(R.string.developer_bypass_app_lock),
                    subtitle = stringResource(R.string.developer_bypass_app_lock_desc),
                    checked = settings.devBypassAppLock,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            viewModel.updateDevBypassAppLock(enabled)
                        }
                    }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))

            // 警告提示
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.developer_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // 显示日志对话框
    if (showDebugLogsDialog) {
        DebugLogsDialog(
            onDismiss = { showDebugLogsDialog = false }
        )
    }
}

/**
 * 调试日志对话框 - 分级显示关键日志
 */
@Composable
fun DebugLogsDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var snapshot by remember {
        mutableStateOf(
            DeveloperLogSnapshot(
                report = "",
                lines = emptyList()
            )
        )
    }
    var isLoading by remember { mutableStateOf(true) }
    var filter by remember { mutableStateOf(DeveloperLogFilter.ALL) }

    suspend fun refreshLogs() {
        isLoading = true
        snapshot = try {
            DeveloperLogDebugHelper.collectLogs(context)
        } catch (e: Exception) {
            DeveloperLogSnapshot(
                report = context.getString(R.string.developer_load_failed, e.message ?: "unknown"),
                lines = emptyList()
            )
        }
        isLoading = false
    }

    LaunchedEffect(Unit) {
        refreshLogs()
    }

    val allLines = snapshot.lines
    val errorCount = allLines.count { it.level == DeveloperLogLevel.ERROR }
    val warningCount = allLines.count { it.level == DeveloperLogLevel.WARN }
    val filteredLines = remember(allLines, filter) {
        when (filter) {
            DeveloperLogFilter.ALL -> allLines
            DeveloperLogFilter.ERROR -> allLines.filter { it.level == DeveloperLogLevel.ERROR }
            DeveloperLogFilter.WARNING -> allLines.filter { it.level == DeveloperLogLevel.WARN }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.BugReport,
                contentDescription = null,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.developer_system_logs),
                    fontWeight = FontWeight.SemiBold
                )
                IconButton(
                    onClick = { scope.launch { refreshLogs() } },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.developer_refresh),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        },
        text = {
            Column {
                if (!isLoading && allLines.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = filter == DeveloperLogFilter.ALL,
                            onClick = { filter = DeveloperLogFilter.ALL },
                            label = { Text("${stringResource(R.string.developer_filter_all)} (${allLines.size})") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                        FilterChip(
                            selected = filter == DeveloperLogFilter.ERROR,
                            onClick = { filter = DeveloperLogFilter.ERROR },
                            label = { Text("${stringResource(R.string.developer_filter_errors)} ($errorCount)") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Error,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        )
                        FilterChip(
                            selected = filter == DeveloperLogFilter.WARNING,
                            onClick = { filter = DeveloperLogFilter.WARNING },
                            label = { Text("${stringResource(R.string.developer_filter_warnings)} ($warningCount)") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.WarningAmber,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    when {
                        isLoading -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator()
                            }
                        }

                        filteredLines.isEmpty() -> {
                            Text(
                                text = if (snapshot.report.isNotBlank()) {
                                    snapshot.report
                                } else {
                                    stringResource(R.string.developer_no_logs)
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(12.dp)
                                    .verticalScroll(rememberScrollState()),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 16.sp
                            )
                        }

                        else -> {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                itemsIndexed(filteredLines) { _, line ->
                                    DeveloperLogLineItem(line)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.developer_close))
            }
        }
    )
}

@Composable
private fun DeveloperLogLineItem(line: DeveloperLogLine) {
    val textColor = when (line.level) {
        DeveloperLogLevel.ERROR -> MaterialTheme.colorScheme.error
        DeveloperLogLevel.WARN -> MaterialTheme.colorScheme.tertiary
        DeveloperLogLevel.INFO -> MaterialTheme.colorScheme.onSurface
        DeveloperLogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
        DeveloperLogLevel.VERBOSE -> MaterialTheme.colorScheme.onSurfaceVariant
        DeveloperLogLevel.OTHER -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Text(
        text = line.text,
        color = textColor,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        lineHeight = 16.sp
    )
}

private enum class DeveloperLogLevel {
    ERROR,
    WARN,
    INFO,
    DEBUG,
    VERBOSE,
    OTHER
}

private enum class DeveloperLogFilter {
    ALL,
    ERROR,
    WARNING
}

private data class DeveloperLogLine(
    val text: String,
    val level: DeveloperLogLevel
)

private data class DeveloperLogSnapshot(
    val report: String,
    val lines: List<DeveloperLogLine>
)

private data class ClearLogsResult(
    val logcatCleared: Boolean,
    val reason: String?
)

private object DeveloperLogDebugHelper {
    private const val LOG_LINE_LIMIT = 1200
    private const val SHARE_DIR = "temp_share"
    private const val SHARE_PREFIX = "bastion_logs_"
    private val AUTOFILL_LOG_TAGS = arrayOf(
        "BastionAutofill:V",
        "AutofillPicker:V",
        "AutofillPickerV2:V",
        "EnhancedParser:V",
        "EnhancedFieldParser:V",
        "SmartFieldDetector:V",
        "*:S"
    )

    /**
     * 应用进程内的系统组件噪音 tag：这些组件（Android 视图/insets/输入子系统 +
     * 荣耀厂商扩展）在应用 pid 内打出大量与业务无关的内部日志，真机导出实测占
     * System Logcat 段 95% 以上。规则：tag 命中且级别 < WARN 时过滤；W/E 保留
     * （isCrashLogLine 依赖 libc 的 E 级识别崩溃，勿全滤）。
     */
    private val NOISY_SYSTEM_TAGS = setOf(
        "VRI", "SurfaceControl", "Surface", "BLASTBufferQueue",
        "BufferQueueProducer", "BufferQueueConsumer",
        "ImeTracker", "ImeFocusController",
        "InputEvent", "InputEventReceiver",
        "NavigationBarController", "ViewRootImpl", "WindowOnBackDispatcher",
        "FullScreenUtils", "HWUI", "Dialog", "Choreographer",
        "HnViewRootImplEx", "HnWidgetTransparencyImpl", "HwViewRootImpl", "HwForceDarkManager",
        "HwPhoneWindow", "HnPgAppThreadImpl", "VrrViewInfoHandler",
        "SessionManager",
        // ART JIT 编译日志（jit_compiled:[OK]...）与厂商 FMPS 等以进程名为 tag 的
        // 系统输出；应用代码从不主动用进程名做 tag（应用日志 tag 均为具体组件名），不冲突。
        "com.bastion.app",
    )

    /**
     * 纯调试噪音 tag：W/E 级亦无诊断价值（实测其 E 级全为 INSETS_DEBUG /
     * 厂商调度器/IME 的固定格式输出），全级别过滤。
     * libc 的 E 级（崩溃信号）由 crashLogs 段从原始 logcat 独立提取，
     * 不受本名单影响；此处滤掉的是 libc W 级 ro.debuggable 纯噪音。
     */
    private val PURE_NOISE_SYSTEM_TAGS = setOf(
        "RtgSchedManager", "RtgSchedIpcFile", "InsetsSourceConsumer",
        "FixedFlowFrameManager", "AnimationPromotionHandler", "HiTouch", "DE_TS", "AwareLog",
        "InsetsController", "HnInsetsControllerEx",
        "InputMethodManager", "libc", "libbinder",
        "ViewTreeObserver", "HwAppInnerBoostImpl", "AwareBitmapCacher",
    )

    /** 判定一行 logcat 是否为可过滤的系统噪音。 */
    private fun isNoisySystemLog(line: String): Boolean {
        val tag = logcatTagOf(line)
        if (tag.isEmpty()) return false
        fun matches(names: Set<String>) = names.any { tag == it || tag.startsWith(it) }
        if (matches(PURE_NOISE_SYSTEM_TAGS)) return true
        if (matches(NOISY_SYSTEM_TAGS)) {
            val level = detectLevel(line)
            return level != DeveloperLogLevel.WARN && level != DeveloperLogLevel.ERROR
        }
        return false
    }
    // 固定 Locale.US 而非 Locale.getDefault()：
    // 这两处分别是日志时间戳与导出文件名，均为纯数字格式，与界面语言无关。
    // 固定 Locale 既符合 lint 的 ConstantLocale 要求，也避免日志/文件名随系统语言漂移。
    private val timeFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val fileFormatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    suspend fun collectLogs(context: Context): DeveloperLogSnapshot = withContext(Dispatchers.IO) {
        runCatchingObserved { AutofillLogger.initialize(context.applicationContext) }
        runCatchingObserved { BitwardenDiagLogger.initialize(context.applicationContext) }
        runCatchingObserved { SecurityDiagLogger.initialize(context.applicationContext) }
        val myPid = android.os.Process.myPid().toString()
        // 合并为一整次 logcat dump 后客户端按 PID / 标签 / 崩溃切分，
        // 替代原先 3 次串行的 logcat -d 读取（每次都会遍历整个环形缓冲，是抓取慢的主因）。
        val allLogcat = readLogcatFull()
        val autofillTagSet = AUTOFILL_LOG_TAGS.map { it.substringBefore(':') }.toSet()
        val appProcessLogs = allLogcat
            .filter { logcatPidOf(it) == myPid }
            .filterNot { isNoisySystemLog(it) }
            .joinToString("\n")
        val crashLogs = allLogcat.filter { isCrashLogLine(it) }.joinToString("\n")
        val autofillTagLogs = allLogcat.filter { logcatTagOf(it) in autofillTagSet }.joinToString("\n")
        val selectedLogs = buildString {
            if (autofillTagLogs.isNotBlank()) {
                appendLine("---- autofill-tags ----")
                appendLine(autofillTagLogs.trim())
            }
            if (appProcessLogs.isNotBlank()) {
                if (isNotBlank()) appendLine()
                appendLine("---- app-process ----")
                appendLine(appProcessLogs.trim())
            }
            if (crashLogs.isNotBlank()) {
                if (isNotBlank()) appendLine()
                appendLine("---- crash/system ----")
                appendLine(crashLogs.trim())
            }
        }.trim()

        val autofillLogs = runCatchingObserved {
            AutofillLogger.exportLogs(300)
        }.getOrElse {
            "AutofillLogger unavailable: ${it.message}"
        }
        val persistedAutofillLogs = runCatchingObserved {
            AutofillLogger.exportPersistedLogs(1200)
        }.getOrElse {
            "Autofill persisted logs unavailable: ${it.message}"
        }
        val persistedBitwardenLogs = runCatchingObserved {
            BitwardenDiagLogger.exportPersistedLogs(2000)
        }.getOrElse {
            "Bitwarden persisted logs unavailable: ${it.message}"
        }
        val persistedSecurityLogs = runCatchingObserved {
            SecurityDiagLogger.exportPersistedLogs(2000)
        }.getOrElse {
            "Security persisted logs unavailable: ${it.message}"
        }
        val persistedPasskeyLogs = runCatchingObserved {
            PasskeyValidationDiagnostics.buildReport(context)
        }.getOrElse {
            "Passkey diagnostics unavailable: ${it.message}"
        }

        val report = buildString {
            appendLine("=== Bastion Developer Log Report ===")
            appendLine("exportedAt=${timeFormatter.format(Date())}")
            appendLine("package=${context.packageName}")
            appendLine("appVersion=${BuildConfig.FULL_VERSION_NAME}")
            appendLine("displayVersion=${BuildConfig.VERSION_NAME}")
            appendLine("android=${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine()
            appendLine("=== System Logcat ===")
            if (selectedLogs.isBlank()) {
                appendLine(context.getString(R.string.developer_no_logs))
            } else {
                appendLine(selectedLogs.trim())
            }
            appendLine()
            appendLine("=== Autofill Structured Logs ===")
            if (persistedAutofillLogs.isNotBlank()) {
                // AutofillLogger 双写：内存环形缓冲（本段）与持久化文件（Persisted 段）
                // 内容完全同源，同时导出会让报告近半行数是逐行重复。持久化段覆盖更全
                // （1200 条 vs 300 条且跨进程重启保留），本段仅在持久化不可用时兜底输出。
                appendLine("(与 Autofill Persisted Logs 段同源，为避免整段重复已省略；本段仅作持久化不可用时的兜底)")
            } else {
                appendLine(autofillLogs.trim())
            }
            appendLine()
            appendLine("=== Autofill Persisted Logs ===")
            if (persistedAutofillLogs.isBlank()) {
                appendLine(context.getString(R.string.developer_no_logs))
            } else {
                appendLine(persistedAutofillLogs.trim())
            }
            appendLine()
            appendLine("=== Bitwarden Persisted Logs ===")
            if (persistedBitwardenLogs.isBlank()) {
                appendLine(context.getString(R.string.developer_no_logs))
            } else {
                appendLine(persistedBitwardenLogs.trim())
            }
            appendLine()
            appendLine("=== Security Persisted Logs ===")
            if (persistedSecurityLogs.isBlank()) {
                appendLine(context.getString(R.string.developer_no_logs))
            } else {
                appendLine(persistedSecurityLogs.trim())
            }
            appendLine()
            appendLine("=== Passkey Persisted Logs ===")
            if (persistedPasskeyLogs.isBlank()) {
                appendLine(context.getString(R.string.developer_no_logs))
            } else {
                appendLine(persistedPasskeyLogs.trim())
            }
        }

        val parsedSystem = parseLines(selectedLogs)
        val parsedPersisted = parseLines(persistedAutofillLogs)
        val parsedBitwarden = parseLines(persistedBitwardenLogs)
        val parsedSecurity = parseLines(persistedSecurityLogs)
        val parsed = when {
            parsedSystem.isNotEmpty() -> parsedSystem
            parsedSecurity.isNotEmpty() -> parsedSecurity
            parsedBitwarden.isNotEmpty() -> parsedBitwarden
            parsedPersisted.isNotEmpty() -> parsedPersisted
            else -> parseLines(autofillLogs)
        }
        DeveloperLogSnapshot(report = report, lines = parsed)
    }

    suspend fun clearLogs(context: Context): ClearLogsResult = withContext(Dispatchers.IO) {
        runCatchingObserved {
            AutofillLogger.clear()
        }
        runCatchingObserved {
            BitwardenDiagLogger.clear()
        }
        runCatchingObserved {
            SecurityDiagLogger.clear()
        }

        val process = runCatchingObserved {
            ProcessBuilder("logcat", "-c")
                .redirectErrorStream(true)
                .start()
        }.getOrElse { error ->
            return@withContext ClearLogsResult(
                logcatCleared = false,
                reason = error.message
            )
        }

        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        val exitCode = runCatchingObserved { process.waitFor() }.getOrDefault(-1)
        if (exitCode == 0) {
            ClearLogsResult(logcatCleared = true, reason = null)
        } else {
            ClearLogsResult(
                logcatCleared = false,
                reason = if (output.isNotBlank()) output else "exit=$exitCode"
            )
        }
    }

    suspend fun createShareIntent(context: Context, report: String): Intent = withContext(Dispatchers.IO) {
        val shareDir = File(context.cacheDir, SHARE_DIR).apply {
            if (!exists()) {
                mkdirs()
            }
        }
        cleanupOldFiles(shareDir)

        val fileName = "${SHARE_PREFIX}${fileFormatter.format(Date())}.txt"
        val file = File(shareDir, fileName)
        file.writeText(report)

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.developer_share_subject))
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, buildDeveloperLogShareFallback(report))
            clipData = ClipData.newRawUri(fileName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun cleanupOldFiles(dir: File) {
        val exported = dir.listFiles { file ->
            file.isFile && file.name.startsWith(SHARE_PREFIX) && file.name.endsWith(".txt")
        } ?: return
        if (exported.size <= 10) return
        exported.sortedByDescending { it.lastModified() }
            .drop(10)
            .forEach { stale ->
                runCatchingObserved { stale.delete() }
            }
    }

    private fun readLogcat(command: Array<String>): String {
        val process = runCatchingObserved {
            ProcessBuilder(*command)
                .redirectErrorStream(true)
                .start()
        }.getOrNull() ?: return ""

        val output = runCatchingObserved {
            process.inputStream.bufferedReader().use { it.readText() }
        }.getOrDefault("")

        runCatchingObserved { process.waitFor() }
        return output.trim()
    }

    /**
     * 一次性 dump 整个 logcat 缓冲（带行数上限）。原实现对每个维度各起一次进程读取，
     * 这里改为单次读取后在客户端按 PID / 标签 / 级别切分，避免重复遍历环形缓冲。
     */
    private fun readLogcatFull(): List<String> {
        val raw = readLogcat(
            arrayOf(
                "logcat",
                "-d",
                "-v",
                "threadtime",
                "-t",
                LOG_LINE_LIMIT.toString(),
                "*:V"
            )
        )
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .toList()
    }

    /** threadtime 格式「date time pid tid level tag: msg」中解析 PID（第 3 个字段）。 */
    private fun logcatPidOf(line: String): String {
        val tokens = line.split(Regex("\\s+"))
        return if (tokens.size >= 4) tokens[2] else ""
    }

    /** threadtime 格式中解析 tag（紧接级别字符、以冒号结尾的字段）。 */
    private fun logcatTagOf(line: String): String {
        val match = Regex("""\s[VDIWEAF]\s([^:\s]+):""").find(line)
        return match?.groupValues?.getOrNull(1) ?: ""
    }

    private fun isCrashLogLine(line: String): Boolean {
        val level = detectLevel(line)
        return (line.contains("AndroidRuntime") && level == DeveloperLogLevel.ERROR) ||
                (line.contains("System.err") && (level == DeveloperLogLevel.WARN || level == DeveloperLogLevel.ERROR)) ||
                (line.contains("libc") && level == DeveloperLogLevel.ERROR)
    }

    private fun parseLines(raw: String): List<DeveloperLogLine> {
        if (raw.isBlank()) return emptyList()
        return raw
            .lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .map { line ->
                DeveloperLogLine(
                    text = line,
                    level = detectLevel(line)
                )
            }
            .toList()
    }

    private fun detectLevel(line: String): DeveloperLogLevel {
        if (line.contains("FATAL EXCEPTION", ignoreCase = true)) return DeveloperLogLevel.ERROR
        if (line.contains("[ERROR]")) return DeveloperLogLevel.ERROR
        if (line.contains("[WARN]")) return DeveloperLogLevel.WARN
        if (line.contains("[INFO]")) return DeveloperLogLevel.INFO
        if (line.contains("[DEBUG]")) return DeveloperLogLevel.DEBUG

        val match = Regex("""\s([VDIWEAF])\s[^:]+:\s""").find(line)
        val levelChar = match?.groupValues?.getOrNull(1) ?: return DeveloperLogLevel.OTHER
        return when (levelChar) {
            "E", "F", "A" -> DeveloperLogLevel.ERROR
            "W" -> DeveloperLogLevel.WARN
            "I" -> DeveloperLogLevel.INFO
            "D" -> DeveloperLogLevel.DEBUG
            "V" -> DeveloperLogLevel.VERBOSE
            else -> DeveloperLogLevel.OTHER
        }
    }
}

private const val DEVELOPER_LOG_SHARE_TEXT_LIMIT = 48_000
private const val DEVELOPER_LOG_SHARE_HEADER_LIMIT = 4_000

internal fun buildDeveloperLogShareFallback(
    report: String,
    maxChars: Int = DEVELOPER_LOG_SHARE_TEXT_LIMIT,
): String {
    val normalized = report.trim()
    if (normalized.length <= maxChars) return normalized

    val marker = "\n\n=== Share text truncated; full report is attached ===\n\n"
    val headerLength = minOf(DEVELOPER_LOG_SHARE_HEADER_LIMIT, maxChars / 3)
    val tailLength = (maxChars - headerLength - marker.length).coerceAtLeast(0)
    return buildString(maxChars) {
        append(normalized.take(headerLength))
        append(marker)
        append(normalized.takeLast(tailLength))
    }.take(maxChars)
}
