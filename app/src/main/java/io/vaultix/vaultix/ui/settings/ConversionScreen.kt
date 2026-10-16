/*
 * Vaultix — app:ui:settings
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 格式转换二级页（施工单 W5 · JSON ⇄ KDBX；从「导入与导出」页首入口进入）。
 *
 * 两个方向各自独立成区（[JsonToKdbxSection] / [KdbxToJsonSection]），共用一套
 * 「选文件 → 输密码 → 预览 / 转换 → SAF 保存」骨架（表单三件套见 FormComponents.kt，
 * 文件读写见 ui/common/SafFiles.kt）。
 *
 * ⚠️ 与 [ImportExportScreen] 的关键差异：本页**不碰任何库的解锁状态** ——
 * 来源与产物都是独立文件字节（用户在服务器宕机时的自救路径）。因此本页没有
 * 「当前库未解锁」这类错误，密码校验只针对文件本身。
 *
 * ⚠️ JSON → KDBX 有**预览步**（KDBX 侧能力边界必须在转换前说清，见
 * [ConversionViewModel.previewJsonImport] 的 KDoc）；KDBX → JSON 没有 ——
 * 读路径的能力是超集（Login / SecureNote / TOTP / 通行密钥全承载），
 * 密码错了在转换时当场报错即可，多一个预览步只是让用户多点一次。
 *
 * 回调统一走 *Actions 包装类而不是裸参数列表：每个分区有 4-7 个回调，
 * 裸传会撞 detekt 的 LongParameterList（阈值 8，实际上限 7）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.vaultix.domain.JsonImportPreview
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.common.queryDisplayName
import io.vaultix.vaultix.ui.common.readAllBytes
import io.vaultix.vaultix.ui.common.writeBytes
import io.vaultix.vaultix.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** JSON 文件 MIME（SAF 选文件过滤器 / 保存对话框默认后缀）。 */
private const val JSON_MIME = "application/json"

/**
 * KDBX 文件的 MIME。
 *
 * `.kdbx` 没有注册过的标准 MIME（KeePass 生态从来只用扩展名区分），
 * 用 `application/octet-stream` 兜底；配合**自带后缀的建议文件名**
 * （见 ConversionViewModel 的 suggestKdbxFileName），产物落地时一定有 `.kdbx`。
 */
private const val KDBX_MIME = "application/octet-stream"

/** 通配 MIME：用户改过后缀 / 下载器标记的文件也要能选，格式合法性由解密阶段判定。 */
private const val ANY_MIME = "*/*"

/**
 * 格式转换二级页（「导入与导出」页首「格式转换」入口进入）。
 *
 * @param onBack 返回上层（导入 / 导出页）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversionScreen(
    onBack: () -> Unit,
    viewModel: ConversionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 跨 launcher 回调传递的内容 / 产物（纯内存态；进程重建后需重选 / 重转，
    // 与 ImportExportScreen 同款取舍 —— 密码管理器不把用户文件缓进磁盘）。
    var jsonFileContent by remember { mutableStateOf<String?>(null) }
    var kdbxFileBytes by remember { mutableStateOf<ByteArray?>(null) }
    // ⚠️ 两个 pending 态刻意用 remember 而非 rememberSaveable：SaveKdbxFile 含
    // ByteArray、SaveJsonFile 含整份 JSON 文本，都进不了 Bundle（进程重建后
    // 重新走一次转换即可，与 ImportExportScreen 的 pendingExportContent 同款取舍）。
    var pendingKdbxOutput by remember { mutableStateOf<ConversionViewModel.Event.SaveKdbxFile?>(null) }
    var pendingJsonOutput by remember { mutableStateOf<ConversionViewModel.Event.SaveJsonFile?>(null) }

    // SAF 交互（两个方向各一个打开器、各一个保存器）。
    val saf = rememberConversionSafHandlers(
        snackbarHostState = snackbarHostState,
        pendingKdbxOutput = { pendingKdbxOutput },
        onPendingKdbxOutput = { pendingKdbxOutput = it },
        pendingJsonOutput = { pendingJsonOutput },
        onPendingJsonOutput = { pendingJsonOutput = it },
        onJsonFileContent = { jsonFileContent = it },
        onKdbxFileBytes = { kdbxFileBytes = it },
        onJsonFilePicked = viewModel::onJsonFilePicked,
        onKdbxFilePicked = viewModel::onKdbxFilePicked,
    )

    // 事件收集：转换产物就绪 → SAF 保存。
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ConversionViewModel.Event.SaveKdbxFile -> {
                    pendingKdbxOutput = event
                    saf.saveKdbx(event.fileName)
                }
                is ConversionViewModel.Event.SaveJsonFile -> {
                    pendingJsonOutput = event
                    saf.saveJson(event.fileName)
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.conversion_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(bottom = Spacing.xxl),
        ) {
            JsonToKdbxSection(
                state = state,
                input = JsonInputActions(
                    onPickFile = {
                        jsonFileContent = null
                        saf.openJson()
                    },
                    onPasswordChange = viewModel::onJsonPasswordChange,
                    onToggleVisible = viewModel::onJsonPasswordVisibleChange,
                    onPreview = { jsonFileContent?.let { viewModel.previewJsonImport(it) } },
                ),
                output = KdbxOutputActions(
                    onPickFile = {
                        jsonFileContent = null
                        saf.openJson()
                    },
                    onNameChange = viewModel::onOutputNameChange,
                    onPasswordChange = viewModel::onOutputPasswordChange,
                    onConfirmChange = viewModel::onOutputPasswordConfirmChange,
                    onToggleVisible = viewModel::onOutputPasswordVisibleChange,
                    onConvert = viewModel::convertJsonToKdbx,
                ),
            )

            Spacer(Modifier.height(Spacing.sm))

            KdbxToJsonSection(
                state = state,
                actions = KdbxJsonActions(
                    onPickFile = {
                        kdbxFileBytes = null
                        saf.openKdbx()
                    },
                    onKdbxPasswordChange = viewModel::onKdbxPasswordChange,
                    onKdbxToggleVisible = viewModel::onKdbxPasswordVisibleChange,
                    onExportPasswordChange = viewModel::onExportPasswordChange,
                    onExportConfirmChange = viewModel::onExportPasswordConfirmChange,
                    onExportToggleVisible = viewModel::onExportPasswordVisibleChange,
                    onConvert = { kdbxFileBytes?.let { viewModel.convertKdbxToJson(it) } },
                ),
            )

            // 错误提示（分类文案）
            state.error?.let { kind ->
                ConversionErrorCard(kind = kind, detail = state.errorDetail)
            }
        }
    }
}

/**
 * 转换页的 SAF 交互装配（拆出以守住 [ConversionScreen] 的 LongMethod 门禁）。
 *
 * 与 ImportExportScreen 的 rememberSafHandlers 结构相同但**不共用**：两页的
 * launcher 集合不同（本页两读两写），硬凑一个参数化的装配函数会比各写一份更难读。
 *
 * @param onJsonFilePicked / [onKdbxFilePicked] 打开器选中文件后登记显示名
 *   （读内容在内部完成，方向由 launcher 区分：json 内容经 [onJsonFileContent]、
 *   kdbx 字节经 [onKdbxFileBytes]）。
 */
@Composable
private fun rememberConversionSafHandlers(
    snackbarHostState: SnackbarHostState,
    pendingKdbxOutput: () -> ConversionViewModel.Event.SaveKdbxFile?,
    onPendingKdbxOutput: (ConversionViewModel.Event.SaveKdbxFile?) -> Unit,
    pendingJsonOutput: () -> ConversionViewModel.Event.SaveJsonFile?,
    onPendingJsonOutput: (ConversionViewModel.Event.SaveJsonFile?) -> Unit,
    onJsonFileContent: (String?) -> Unit,
    onKdbxFileBytes: (ByteArray?) -> Unit,
    onJsonFilePicked: (String) -> Unit,
    onKdbxFilePicked: (String) -> Unit,
): ConversionSafLaunchers {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ---- 读：JSON 方向 ----
    val openJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        onJsonFilePicked(displayNameOf(context, uri))
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { readAllBytes(context, uri) }
            if (bytes == null) {
                snackbarHostState.showSnackbar(context.getString(R.string.conversion_read_failed))
            } else {
                onJsonFileContent(bytes.toString(Charsets.UTF_8))
            }
        }
    }

    // ---- 读：KDBX 方向 ----
    val openKdbxLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        onKdbxFilePicked(displayNameOf(context, uri))
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { readAllBytes(context, uri) }
            if (bytes == null) {
                snackbarHostState.showSnackbar(context.getString(R.string.conversion_read_failed))
            } else {
                onKdbxFileBytes(bytes)
            }
        }
    }

    // ---- 写：KDBX 产物 ----
    val saveKdbxLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(KDBX_MIME),
    ) { uri ->
        val pending = pendingKdbxOutput()
        onPendingKdbxOutput(null)
        if (uri == null || pending == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) { writeBytes(context, uri, pending.bytes) }
            if (ok) {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.conversion_kdbx_success, pending.itemCount),
                )
            } else {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.conversion_save_failed, ""),
                )
            }
        }
    }

    // ---- 写：JSON 产物 ----
    val saveJsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(JSON_MIME),
    ) { uri ->
        val pending = pendingJsonOutput()
        onPendingJsonOutput(null)
        if (uri == null || pending == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                writeBytes(context, uri, pending.content.toByteArray(Charsets.UTF_8))
            }
            if (ok) {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.conversion_json_success, pending.itemCount),
                )
            } else {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.conversion_save_failed, ""),
                )
            }
        }
    }

    return ConversionSafLaunchers(
        openJson = { openJsonLauncher.launch(arrayOf(JSON_MIME, ANY_MIME)) },
        openKdbx = { openKdbxLauncher.launch(arrayOf(KDBX_MIME, ANY_MIME)) },
        saveKdbx = { name -> saveKdbxLauncher.launch(name) },
        saveJson = { name -> saveJsonLauncher.launch(name) },
    )
}

/** 四个 SAF 启动器的出口（主 Screen 只认这一组，不直接碰 launcher）。 */
private class ConversionSafLaunchers(
    val openJson: () -> Unit,
    val openKdbx: () -> Unit,
    val saveKdbx: (String) -> Unit,
    val saveJson: (String) -> Unit,
)

/** SAF 显示名（选不到时退回 URI 末段）。 */
private fun displayNameOf(context: Context, uri: Uri): String =
    queryDisplayName(context, uri) ?: uri.lastPathSegment.orEmpty()

// ---- 分区组件，全部拆出以守住 LongMethod 门禁 ----

/** JSON → KDBX：输入侧（选文件 + 文件密码 + 预览）。 */
private class JsonInputActions(
    val onPickFile: () -> Unit,
    val onPasswordChange: (String) -> Unit,
    val onToggleVisible: (Boolean) -> Unit,
    val onPreview: () -> Unit,
)

/** JSON → KDBX：输出侧（新库名 + 输出密码 + 转换）。 */
private class KdbxOutputActions(
    val onPickFile: () -> Unit,
    val onNameChange: (String) -> Unit,
    val onPasswordChange: (String) -> Unit,
    val onConfirmChange: (String) -> Unit,
    val onToggleVisible: (Boolean) -> Unit,
    val onConvert: () -> Unit,
)

/** KDBX → JSON：两套密码 + 转换（无预览步，理由见文件头）。 */
private class KdbxJsonActions(
    val onPickFile: () -> Unit,
    val onKdbxPasswordChange: (String) -> Unit,
    val onKdbxToggleVisible: (Boolean) -> Unit,
    val onExportPasswordChange: (String) -> Unit,
    val onExportConfirmChange: (String) -> Unit,
    val onExportToggleVisible: (Boolean) -> Unit,
    val onConvert: () -> Unit,
)

/** JSON → KDBX 分区：选文件 → 文件密码 → 预览 → 输出库设置 → 转换。 */
@Composable
private fun JsonToKdbxSection(
    state: ConversionViewModel.UiState,
    input: JsonInputActions,
    output: KdbxOutputActions,
) {
    SettingsGroupTitle(stringResource(R.string.conversion_json_to_kdbx_title))
    Text(
        text = stringResource(R.string.conversion_json_to_kdbx_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs),
    )

    val preview = state.jsonPreview
    if (preview == null) {
        // 第一步：选文件 + 文件密码 → 解密预览
        SettingsGroupCard {
            SettingsRow(
                icon = { Icon(Icons.Filled.FileOpen, contentDescription = null) },
                title = stringResource(R.string.conversion_pick_json),
                subtitle = if (state.jsonFileName.isNotEmpty()) {
                    stringResource(R.string.conversion_file_picked, state.jsonFileName)
                } else {
                    null
                },
                onClick = if (state.jsonPreviewing) null else input.onPickFile,
            )
        }
        PasswordField(
            value = state.jsonPassword,
            onValueChange = input.onPasswordChange,
            label = stringResource(R.string.conversion_json_password_label),
            visible = state.jsonPasswordVisible,
            onToggleVisible = input.onToggleVisible,
            imeAction = ImeAction.Done,
        )
        ActionButton(
            text = stringResource(R.string.conversion_preview),
            icon = Icons.Filled.Download,
            enabled = state.canPreviewJson,
            busy = state.jsonPreviewing,
            busyText = stringResource(R.string.conversion_previewing),
            onClick = input.onPreview,
        )
    } else {
        // 第二步：预览计数 + 输出库设置 → 转换
        PreviewStatsCard(preview = preview)
        OutlinedButton(
            onClick = output.onPickFile,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        ) {
            Text(stringResource(R.string.conversion_change_file))
        }
        KdbxOutputFields(state = state, output = output)
        ActionButton(
            text = stringResource(R.string.conversion_convert_kdbx),
            icon = Icons.Filled.Upload,
            enabled = state.canConvertToKdbx,
            busy = state.convertingToKdbx,
            busyText = stringResource(R.string.conversion_converting),
            onClick = output.onConvert,
        )
    }
}

/** KDBX → JSON 分区：选文件 → 两套密码 → 转换（无预览步，理由见文件头）。 */
@Composable
private fun KdbxToJsonSection(
    state: ConversionViewModel.UiState,
    actions: KdbxJsonActions,
) {
    SettingsGroupTitle(stringResource(R.string.conversion_kdbx_to_json_title))
    Text(
        text = stringResource(R.string.conversion_kdbx_to_json_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs),
    )

    SettingsGroupCard {
        SettingsRow(
            icon = { Icon(Icons.Filled.FileOpen, contentDescription = null) },
            title = stringResource(R.string.conversion_pick_kdbx),
            subtitle = if (state.kdbxFileName.isNotEmpty()) {
                stringResource(R.string.conversion_file_picked, state.kdbxFileName)
            } else {
                null
            },
            onClick = if (state.convertingToJson) null else actions.onPickFile,
        )
    }
    PasswordField(
        value = state.kdbxPassword,
        onValueChange = actions.onKdbxPasswordChange,
        label = stringResource(R.string.conversion_kdbx_password_label),
        visible = state.kdbxPasswordVisible,
        onToggleVisible = actions.onKdbxToggleVisible,
        imeAction = ImeAction.Next,
    )
    PasswordField(
        value = state.exportPassword,
        onValueChange = actions.onExportPasswordChange,
        label = stringResource(R.string.conversion_export_password_label),
        visible = state.exportPasswordVisible,
        onToggleVisible = actions.onExportToggleVisible,
        imeAction = ImeAction.Next,
    )
    PasswordField(
        value = state.exportPasswordConfirm,
        onValueChange = actions.onExportConfirmChange,
        label = stringResource(R.string.conversion_export_password_confirm_label),
        visible = state.exportPasswordVisible,
        onToggleVisible = actions.onExportToggleVisible,
        imeAction = ImeAction.Done,
    )
    // 两次不一致 / 太短时即时提示（非阻塞：点了转换才真正拦截）。
    when {
        state.exportPassword.isNotEmpty() &&
            state.exportPassword.length < ConversionViewModel.UiState.MIN_PASSWORD_LENGTH ->
            FieldHint(stringResource(R.string.conversion_password_too_weak))
        state.exportPassword.isNotEmpty() &&
            state.exportPasswordConfirm.isNotEmpty() &&
            state.exportPassword != state.exportPasswordConfirm ->
            FieldHint(stringResource(R.string.conversion_password_mismatch))
    }
    ActionButton(
        text = stringResource(R.string.conversion_convert_json),
        icon = Icons.Filled.SwapHoriz,
        enabled = state.canConvertToJson,
        busy = state.convertingToJson,
        busyText = stringResource(R.string.conversion_converting),
        onClick = actions.onConvert,
    )
}

/** 输出库设置（库名 + 输出密码 + 确认）。 */
@Composable
private fun KdbxOutputFields(
    state: ConversionViewModel.UiState,
    output: KdbxOutputActions,
) {
    OutlinedTextField(
        value = state.outputName,
        onValueChange = output.onNameChange,
        label = { Text(stringResource(R.string.conversion_output_name_label)) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = 6.dp),
    )
    PasswordField(
        value = state.outputPassword,
        onValueChange = output.onPasswordChange,
        label = stringResource(R.string.conversion_output_password_label),
        visible = state.outputPasswordVisible,
        onToggleVisible = output.onToggleVisible,
        imeAction = ImeAction.Next,
    )
    PasswordField(
        value = state.outputPasswordConfirm,
        onValueChange = output.onConfirmChange,
        label = stringResource(R.string.conversion_output_password_confirm_label),
        visible = state.outputPasswordVisible,
        onToggleVisible = output.onToggleVisible,
        imeAction = ImeAction.Done,
    )
    if (state.outputPassword.isNotEmpty() &&
        state.outputPassword.length < ConversionViewModel.UiState.MIN_PASSWORD_LENGTH
    ) {
        FieldHint(stringResource(R.string.conversion_password_too_weak))
    } else if (state.outputPassword.isNotEmpty() &&
        state.outputPasswordConfirm.isNotEmpty() &&
        state.outputPassword != state.outputPasswordConfirm
    ) {
        FieldHint(stringResource(R.string.conversion_password_mismatch))
    }
}

/** 预览计数卡：把 KDBX 侧的能力边界在转换前说清（不静默降级）。 */
@Composable
private fun PreviewStatsCard(preview: JsonImportPreview) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Icon(Icons.Filled.Description, contentDescription = null)
                Text(
                    text = stringResource(
                        R.string.conversion_preview_summary,
                        preview.stats.itemCount,
                        preview.folders.size,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (preview.stats.totpCount > 0) {
                Text(
                    text = stringResource(R.string.conversion_preview_totp, preview.stats.totpCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }
            if (preview.stats.passkeyCount > 0) {
                Text(
                    text = stringResource(
                        R.string.conversion_preview_passkey,
                        preview.stats.passkeyCount,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }
            if (preview.stats.typeDegradedCount > 0) {
                Text(
                    text = stringResource(
                        R.string.conversion_preview_degraded,
                        preview.stats.typeDegradedCount,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }
            if (preview.stats.skippedCount > 0) {
                Text(
                    text = stringResource(
                        R.string.conversion_preview_skipped,
                        preview.stats.skippedCount,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }
        }
    }
}

/** 错误卡片：把分类错误映射为可执行的中文文案。 */
@Composable
private fun ConversionErrorCard(kind: ConversionViewModel.ErrorKind, detail: String?) {
    val text = when (kind) {
        ConversionViewModel.ErrorKind.MALFORMED_JSON ->
            stringResource(R.string.conversion_error_malformed_json)
        ConversionViewModel.ErrorKind.WRONG_PASSWORD ->
            stringResource(R.string.conversion_error_wrong_password)
        ConversionViewModel.ErrorKind.NOT_KDBX ->
            stringResource(R.string.conversion_error_not_kdbx)
        ConversionViewModel.ErrorKind.UNSUPPORTED_KDBX_VERSION ->
            stringResource(R.string.conversion_error_unsupported_version, detail.orEmpty())
        ConversionViewModel.ErrorKind.UNSUPPORTED_KDF ->
            stringResource(R.string.conversion_error_unsupported_kdf)
        ConversionViewModel.ErrorKind.WRITE_CHECK_FAILED ->
            stringResource(R.string.conversion_error_write_check)
        ConversionViewModel.ErrorKind.GENERIC ->
            stringResource(R.string.conversion_error_generic, detail.orEmpty())
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            modifier = Modifier.padding(Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}
