/*
 * Vaultix — app:ui:settings
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.settings

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.vaultix.vaultix.BuildConfig
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.theme.Spacing
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 日志快照最多取多少行（与 Bastion `LOG_LINE_LIMIT` 同量级）。 */
private const val LOGCAT_MAX_LINES = 1200

/** 导出文件最多保留几个（超出按修改时间删最旧）。 */
private const val EXPORT_KEEP_FILES = 10

/** 分享附件里 `EXTRA_TEXT` 的降级文本上限（避免超出 Binder 事务上限）。 */
private const val SHARE_TEXT_LIMIT = 48_000

/** 日志导出目录名（**必须**与 `res/xml/file_paths.xml` 的白名单一致）。 */
private const val EXPORT_DIR = "dev_logs"

/** 毫秒 / 秒。logcat `-v epoch` 给的是**秒**（带小数），而 `System.currentTimeMillis()` 是毫秒。 */
private const val MILLIS_PER_SECOND = 1000.0

/**
 * 日志级别filter：全部 / 仅错误 / 仅警告。
 *
 * 只保留这三档：再细就要做 tag 列表与搜索框了，而**这是给开发者自己看**的入口，
 * 三档足够定位"刚才那一下为什么没成功"。
 */
private enum class LogLevelFilter(val labelRes: Int) {
    ALL(R.string.developer_logs_filter_all),
    ERROR(R.string.developer_logs_filter_error),
    WARN(R.string.developer_logs_filter_warn),
    ;

    fun accepts(level: Char): Boolean = when (this) {
        ALL -> true
        ERROR -> level == 'E' || level == 'F'
        WARN -> level == 'W' || level == 'E' || level == 'F'
    }
}

/**
 * **开发者模式 · 日志查看与导出**（仅 debug 构建可达，入口见 `SettingsScreen` 的 `DeveloperSection`）。
 *
 * ## ⚠️ 能力边界（必须知道，否则会误判）
 *
 * **只能读到本应用自己进程的日志。** Android 4.1（API 16）起，应用未持 `READ_LOGS` 时
 * 读 logcat **只返回本 UID 的条目**。因此：
 * - ✅ 能看：`VaultixAutofill` 等**我们自己**打的日志（这正是 `AutofillLogger` 的输出）；
 * - ❌ 看不到：其它进程/系统组件的日志 —— 例如浏览器侧
 *   `cr_ChromiumWebauthn: [CredManHelper] Failed to convert response…`、
 *   `CredentialManager: …`。**「应用内没报错」不等于「没问题」**，
 *   跨进程排查仍然只能靠 `adb logcat`。
 *
 * ⇒ 本入口的定位是"**在没接电脑时也能看一眼自己的日志并导出**"，
 *   不是 `adb` 的替代品。
 *
 * ## 为什么不新增文件日志
 *
 * 日志源直接取 logcat 快照，**不引入任何持久化日志文件** —— 那样会让 release 常驻占盘。
 * 日志策略见 `Docs/progress/totp-ux-and-dev-diagnostics.md` §5：
 * **占空间的是"往自己的存储写文件"，不是"写 logcat"**（logcat 是系统环形缓冲，系统回收）。
 */
@Composable
fun DeveloperLogsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    var filter by remember { mutableStateOf(LogLevelFilter.ALL) }
    var loading by remember { mutableStateOf(true) }
    // ★「清空」的时间下界（2026-09-21 用户要求）：清空后**只显示此刻之后**的日志。
    // ⚠️ 行为不依赖 `logcat -c` 是否被系统允许 —— 见 [tryClearLogcatBuffer] 的说明。
    var clearedAt by remember { mutableStateOf(0.0) }

    // 每次打开抓一次快照；点「刷新」再抓一次（不做实时跟随：那是 adb 的活）。
    var refreshTick by remember { mutableStateOf(0) }
    LaunchedEffect(refreshTick, clearedAt) {
        loading = true
        entries = withContext(Dispatchers.IO) { captureLogcat(clearedAt) }
        loading = false
    }

    val shown = remember(entries, filter) { entries.filter { filter.accepts(it.level) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.developer_logs_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    LogLevelFilter.entries.forEach { option ->
                        FilterChip(
                            selected = filter == option,
                            onClick = { filter = option },
                            label = { Text(stringResource(option.labelRes)) },
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.developer_logs_scope_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val body = if (loading) {
                    stringResource(R.string.developer_logs_loading)
                } else {
                    stringResource(R.string.developer_logs_empty)
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                ) {
                    itemsIndexed(shown) { _, entry ->
                        Text(
                            text = entry.text,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = levelColor(entry.level),
                        )
                    }
                    if (loading || shown.isEmpty()) {
                        item { Text(body, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { scope.launch { shareLogs(context, buildExportText(context, entries)) } },
                enabled = entries.isNotEmpty(),
            ) {
                Text(stringResource(R.string.developer_logs_export))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                // ★ 清空（2026-09-21 用户要求）：先尽力把系统缓冲也清掉，再把时间下界推到"现在"
                //   ⇒ 之后刷新只看到新日志，便于"复现一次、只看这一次"。
                TextButton(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { tryClearLogcatBuffer() }
                            clearedAt = System.currentTimeMillis() / MILLIS_PER_SECOND
                        }
                    },
                ) {
                    Text(stringResource(R.string.developer_logs_clear))
                }
                TextButton(onClick = { refreshTick++ }) {
                    Text(stringResource(R.string.developer_logs_refresh))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_close))
                }
            }
        },
    )
}

/** 一条日志（时间戳 + 可读文本 + 级别）。 */
private data class LogEntry(val timestamp: Double, val text: String, val level: Char)

/**
 * 抓一份 logcat 快照，只保留 [since] 之后的条目。
 *
 * 用 `-v epoch`（而不是 `-v time`）是为了让行首时间戳**可直接比较** ——
 * 「清空」功能靠它做时间过滤（见 [DeveloperLogsDialog] 里的 `clearedAt`）。
 *
 * @param since 时间戳下界（epoch 秒）。`0.0` = 不筛。
 */
private fun captureLogcat(since: Double): List<LogEntry> = runCatching {
    val process = ProcessBuilder(
        "logcat", "-d", "-t", LOGCAT_MAX_LINES.toString(), "-v", "epoch",
    ).redirectErrorStream(true).start()
    val raw = process.inputStream.bufferedReader().use { it.readLines() }
    process.waitFor()
    raw.asSequence()
        .mapNotNull(::parseEntry)
        .filter { it.timestamp > since }
        .filter { KEEP_PATTERN.containsMatchIn(it.text) || it.level in "EF" }
        .toList()
}.getOrElse { emptyList() }

/**
 * 尽力清空系统日志缓冲。
 *
 * ⚠️ **可能失败且不报错**：清全局缓冲区在无 `READ_LOGS` 时会被系统拒绝，而 app 拿不到反馈。
 * 所以「清空」不能只靠它 —— 真正保证效果的是 [LogEntry.timestamp] 那条时间下界
 * （见 [DeveloperLogsDialog] 的 `clearedAt`）。这里清一次是"顺手把缓冲也清掉"，
 * 失败也不影响用户看到的行为。
 */
private fun tryClearLogcatBuffer() {
    runCatching {
        ProcessBuilder("logcat", "-c").start().waitFor()
    }
}

/**
 * 解析 `-v epoch` 的一行：`<epoch>.<ms>  PID  TID  L  TAG: msg`
 * （`limit = 6` ⇒ [0]=epoch、[1]=pid、[2]=tid、[3]=级别、[4]=tag、[5]=msg）。
 *
 * 显示时把 epoch 换成 `HH:mm:ss.SSS`：epoch 对人不可读，而秒级时间在排查"刚才那一下"时
 * 恰恰是最关键的定位信息。
 */
private fun parseEntry(line: String): LogEntry? {
    val parts = line.trim().split(WHITESPACE, limit = LOGCAT_FIELD_LIMIT)
    val epoch = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
    val level = parts.getOrNull(3)?.firstOrNull()?.takeIf { it in "VDIWEF" } ?: ' '
    val clock = CLOCK_FORMAT.format(Date((epoch * 1000).toLong()))
    val rest = parts.drop(4).joinToString(" ")
    return LogEntry(epoch, "$clock  $rest", level)
}

private val WHITESPACE = Regex("\\s+")
private const val LOGCAT_FIELD_LIMIT = 6
private val CLOCK_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

/**
 * 关键字白名单：与本项目 adb 侧采集脚本（`.workbuddy/memory/2026-09-21.md` 记录的
 * `logcap.py`）保持**同一套口径**，避免"应用内看到的"和"adb 看到的"两套过滤互相打架。
 *
 * ⚠️ 不要退回"保留所有 E/F 级"：Honor 机型的 `afehal` / `WifiEnhanceServiceImpl` 等会刷屏，
 * 几十秒就能把有用记录淹没（这条是实测踩出来的）。
 */
private val KEEP_PATTERN = Regex(
    "Vaultix|Credential|passkey|Passkey|WebAuthn|Autofill|autofill|CredMan|FIDO|" +
        "KeyStore|keystore|Biometric|AndroidRuntime|FATAL",
)

/**
 * 取日志级别字符。
 *
 * `-v time` 的行格式：`MM-DD HH:MM:SS.mmm PID TID L TAG: msg`
 * ⇒ 第 5 个空白分隔字段（下标 4）是级别。取不到时返回空格（不匹配任何过滤器，等于不显示）。
 */
@Composable
private fun levelColor(level: Char) = when (level) {
    'E', 'F' -> MaterialTheme.colorScheme.error
    'W' -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * 组装导出文本：头部元信息 + 日志正文（已脱敏）。
 *
 * ⚠️ **脱敏必须在出口做一次**：Bastion 的对应实现只对"结构化日志"脱敏、
 * 而 `=== System Logcat ===` 段是原样导出的；我们的日志源**就是** logcat，
 * 所以出口这道脱敏是唯一的一道，不能省。
 */
private fun buildExportText(context: Context, entries: List<LogEntry>): String {
    val header = buildString {
        appendLine("# Vaultix 开发者日志导出")
        appendLine("exportedAt=${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLine("package=${context.packageName}")
        appendLine("versionName=${BuildConfig.VERSION_NAME}")
        appendLine("versionCode=${BuildConfig.VERSION_CODE}")
        appendLine("android=${android.os.Build.VERSION.RELEASE}(sdk ${android.os.Build.VERSION.SDK_INT})")
        appendLine("device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        appendLine()
    }
    return sanitize(header + entries.joinToString("\n") { it.text })
}

/**
 * 脱敏：**只做保守替换**，宁可多遮不可漏遮。
 *
 * 覆盖四类：`键=值` 形态的敏感键、邮箱、手机号、超长 base64/hex 串。
 * 最后一条是为了兜住"某个地方不小心把密钥/token 打进了日志"——
 * 项目纪律（`AutofillLogger` KDoc）要求只记非敏感元数据，但**纪律靠人守，脱敏靠机器**。
 */
private fun sanitize(text: String): String {
    var out = text
    out = out.replace(SENSITIVE_KV) { m -> "${m.groupValues[1]}=<redacted>" }
    out = out.replace(EMAIL, "<email>")
    out = out.replace(PHONE, "<phone>")
    out = out.replace(LONG_BLOB, "<redacted>")
    return out
}

private val SENSITIVE_KV = Regex(
    "(?i)(password|passwd|pwd|secret|token|api_?key|private_?key|recovery)\\s*[=:]\\s*\\S+",
)
private val EMAIL = Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+")
private val PHONE = Regex("1[3-9]\\d{9}")
private val LONG_BLOB = Regex("[A-Za-z0-9+/=]{40,}")

/**
 * 写文件到 `cacheDir/dev_logs/` 并走 FileProvider + `ACTION_SEND` 分享出去。
 *
 * ⚠️ 三处硬约束（任一处错都会在真机上抛异常，且报错信息不指向根因）：
 * 1. 目录必须在 `res/xml/file_paths.xml` 的 `<cache-path path="dev_logs/">` 之内；
 * 2. authority 必须与 Manifest 里的 `${applicationId}.fileprovider` 逐字一致；
 * 3. `FLAG_GRANT_READ_URI_PERMISSION`（+ `clipData`）必须给，否则接收方读不到。
 */
private fun shareLogs(context: Context, text: String) {
    runCatching {
        val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "vaultix_logs_$stamp.txt")
        file.writeText(text)
        // 只留最近若干个，避免反复导出把 cacheDir 撑大（cacheDir 本身可被系统回收，但仍要自律）。
        dir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(EXPORT_KEEP_FILES)
            ?.forEach { it.delete() }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, text.take(SHARE_TEXT_LIMIT))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri(null, uri)
        }
        context.startActivity(Intent.createChooser(send, null))
    }
}
