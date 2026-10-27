/*
 * Vaultix — app:ui:settings
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 界面结构对齐 Bitwarden 官方 Android 客户端「设置 → 导出密码库 / 导入数据」：
 *   - 导出：格式说明 → 文件密码 + 确认 → 「导出」按钮 → 确认框 → SAF 保存；
 *   - 导入：选文件 → 文件密码 → 「解密并预览」→ 条目 / 文件夹计数 → 「确认导入」。
 * 复用设置页既有的 `SettingsGroupTitle` / `SettingsRow` 组件，保持二级页视觉一致。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.settings

import android.net.Uri
import androidx.activity.compose.ManagedActivityResultLauncher
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.vaultix.domain.ImportPlan
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.common.readAllBytes
import io.vaultix.vaultix.ui.common.writeBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.vaultix.vaultix.ui.theme.Spacing

/** 导出文件 MIME：JSON（SAF 保存对话框据此给默认扩展名）。 */
private const val JSON_MIME = "application/json"

/**
 * 通配 MIME：加密导出文件不一定被系统登记为 JSON（用户改过后缀 / 下载器标记），
 * 选文件时放行任意类型，格式合法性由解密阶段判定。
 */
private const val ANY_MIME = "*/*"

/**
 * 导入与导出二级页（设置首页「数据管理 → 导入 / 导出」进入）。
 *
 * SAF 读 / 写都由本页持有（ViewModel 不碰文件系统，只收发字符串，
 * 对齐 Docs/09：data 层与业务层都不直接接触磁盘路径）。
 *
 * @param onOpenConversion 页首「格式转换」入口（JSON ⇄ KDBX 互转，施工单 W5）——
 *   与导入 / 导出**语义正交**：那两条是「本库 ⇄ Bitwarden 备份」，这一条是
 *   「Bitwarden 加密 JSON ⇄ .kdbx 文件」，后者不碰任何库的解锁状态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportExportScreen(
    onBack: () -> Unit,
    onOpenConversion: () -> Unit = {},
    viewModel: ImportExportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    var showExportConfirm by rememberSaveable { mutableStateOf(false) }
    // SAF 读写需要跨 launcher 回调传递的文件内容（纯内存态；进程重建后需重导 / 重选）。
    var pendingExportContent by remember { mutableStateOf<String?>(null) }
    var importFileContent by remember { mutableStateOf<String?>(null) }

    // 导入 / 导出的 SAF 交互副作用（拆出以守住 LongMethod 门禁）。
    val saf = rememberSafHandlers(
        viewModel = viewModel,
        snackbarHostState = snackbarHostState,
        pendingExportContent = { pendingExportContent },
        onPendingExportContent = { pendingExportContent = it },
        onImportFileContent = { importFileContent = it },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.import_export_title)) },
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
            // 页首入口：格式转换（JSON ⇄ KDBX）。放在最前是因为它是「换格式」
            // （与任何库无关），而下面的导入 / 导出都作用于当前活跃库 ——
            // 两类操作的先验条件不同，混排会让用户拿错文件。
            SettingsGroupCard {
                SettingsRow(
                    icon = { Icon(Icons.Filled.SwapHoriz, contentDescription = null) },
                    title = stringResource(R.string.conversion_entry),
                    subtitle = stringResource(R.string.conversion_entry_desc),
                    onClick = onOpenConversion,
                )
            }

            Spacer(Modifier.height(Spacing.sm))

            ExportSection(
                state = state,
                onPasswordChange = viewModel::onExportPasswordChange,
                onConfirmChange = viewModel::onExportPasswordConfirmChange,
                onToggleVisible = viewModel::onExportPasswordVisibleChange,
                onExportClick = { showExportConfirm = true },
            )

            Spacer(Modifier.height(Spacing.sm))

            ImportSection(
                state = state,
                onPickFile = {
                    importFileContent = null
                    saf.launch(arrayOf(JSON_MIME, ANY_MIME))
                },
                onPasswordChange = viewModel::onImportPasswordChange,
                onToggleVisible = viewModel::onImportPasswordVisibleChange,
                onDecrypt = { importFileContent?.let { viewModel.decryptForPreview(it) } },
                onApply = viewModel::applyImport,
                onClearPreview = viewModel::clearPreview,
            )

            // 错误提示（分类文案）
            state.error?.let { kind ->
                ErrorCard(kind = kind, detail = state.errorDetail)
            }
        }
    }

    if (showExportConfirm) {
        AlertDialog(
            onDismissRequest = { showExportConfirm = false },
            icon = { Icon(Icons.Filled.Upload, contentDescription = null) },
            title = { Text(stringResource(R.string.export_confirm_title)) },
            text = { Text(stringResource(R.string.export_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showExportConfirm = false
                    viewModel.export()
                }) {
                    Text(stringResource(R.string.export_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/**
 * 装配导入 / 导出的 SAF 副作用：导出走 [ActivityResultContracts.CreateDocument] 保存，
 * 导入走 [ActivityResultContracts.OpenDocument] 读取；文件内容经回调与宿主共享。
 *
 * 拆成独立 composable 纯粹为了守住 [ImportExportScreen] 的 `LongMethod` 门禁。
 * 返回「打开文件」启动器（导出保存的启动器在内部事件流里自行 launch）。
 */
@Composable
private fun rememberSafHandlers(
    viewModel: ImportExportViewModel,
    snackbarHostState: SnackbarHostState,
    pendingExportContent: () -> String?,
    onPendingExportContent: (String?) -> Unit,
    onImportFileContent: (String?) -> Unit,
): ManagedActivityResultLauncher<Array<String>, Uri?> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // SAF 保存（导出）：拿到目标 URI 后把内容写进去。
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(JSON_MIME),
    ) { uri ->
        val content = pendingExportContent()
        if (uri == null || content == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                writeBytes(context, uri, content.toByteArray(Charsets.UTF_8))
            }
            onPendingExportContent(null)
            if (ok) {
                viewModel.onExportFileSaved()
            } else {
                snackbarHostState.showSnackbar(context.getString(R.string.export_failed, ""))
            }
        }
    }

    // SAF 打开（导入）：选中后由 UI 读全文交给 ViewModel 解密。
    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.onImportFilePicked(context, uri)
        scope.launch {
            val content = withContext(Dispatchers.IO) {
                readAllBytes(context, uri)?.toString(Charsets.UTF_8)
            }
            if (content != null) {
                onImportFileContent(content)
            } else {
                snackbarHostState.showSnackbar(context.getString(R.string.import_error_malformed))
            }
        }
    }

    // 事件收集：导出落盘 / 导入完成 → Snackbar。
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ImportExportViewModel.Event.SaveExportFile -> {
                    onPendingExportContent(event.content)
                    saveLauncher.launch(event.fileName)
                }
                ImportExportViewModel.Event.ExportDone ->
                    snackbarHostState.showSnackbar(context.getString(R.string.export_success))
                is ImportExportViewModel.Event.ImportDone ->
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.import_success, event.count),
                    )
            }
        }
    }

    return openLauncher
}

// ---- 分区组件（导出 / 导入 / 预览 / 错误），全部拆出以守住 LongMethod 门禁 ----

/** 导出分区：说明 + 文件密码 + 确认 + 导出按钮。 */
@Composable
private fun ExportSection(
    state: ImportExportViewModel.UiState,
    onPasswordChange: (String) -> Unit,
    onConfirmChange: (String) -> Unit,
    onToggleVisible: (Boolean) -> Unit,
    onExportClick: () -> Unit,
) {
    SettingsGroupTitle(stringResource(R.string.export_title))
    Text(
        text = stringResource(R.string.export_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs),
    )
    PasswordField(
        value = state.exportPassword,
        onValueChange = onPasswordChange,
        label = stringResource(R.string.export_password_label),
        visible = state.exportPasswordVisible,
        onToggleVisible = onToggleVisible,
        imeAction = ImeAction.Next,
    )
    PasswordField(
        value = state.exportPasswordConfirm,
        onValueChange = onConfirmChange,
        label = stringResource(R.string.export_password_confirm_label),
        visible = state.exportPasswordVisible,
        onToggleVisible = onToggleVisible,
        imeAction = ImeAction.Done,
    )
    // 两次不一致时给出即时提示（非阻塞：只有点了导出才真正拦截）。
    if (state.exportPassword.isNotEmpty() &&
        state.exportPassword.length < ImportExportViewModel.UiState.MIN_PASSWORD_LENGTH
    ) {
        FieldHint(stringResource(R.string.export_password_too_weak))
    } else if (state.exportPassword.isNotEmpty() &&
        state.exportPasswordConfirm.isNotEmpty() &&
        state.exportPassword != state.exportPasswordConfirm
    ) {
        FieldHint(stringResource(R.string.export_password_mismatch))
    }
    ActionButton(
        text = stringResource(R.string.export_button),
        icon = Icons.Filled.Upload,
        enabled = state.canExport,
        busy = state.exporting,
        busyText = stringResource(R.string.export_in_progress),
        onClick = onExportClick,
    )
}

/** 导入分区：选文件 → 密码 → 解密预览 → 确认导入。 */
@Composable
private fun ImportSection(
    state: ImportExportViewModel.UiState,
    onPickFile: () -> Unit,
    onPasswordChange: (String) -> Unit,
    onToggleVisible: (Boolean) -> Unit,
    onDecrypt: () -> Unit,
    onApply: () -> Unit,
    onClearPreview: () -> Unit,
) {
    SettingsGroupTitle(stringResource(R.string.import_title))
    Text(
        text = stringResource(R.string.import_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs),
    )

    val preview = state.importPreview
    if (preview == null) {
        // 第一步：选文件
        SettingsGroupCard {
            SettingsRow(
                icon = { Icon(Icons.Filled.FileOpen, contentDescription = null) },
                title = stringResource(R.string.import_pick_file),
                subtitle = if (state.importFileName.isNotEmpty()) {
                    stringResource(R.string.import_file_picked, state.importFileName)
                } else {
                    null
                },
                onClick = if (state.decoding) null else onPickFile,
            )
        }
        // 第二步：文件密码 + 解密预览
        PasswordField(
            value = state.importPassword,
            onValueChange = onPasswordChange,
            label = stringResource(R.string.import_password_label),
            visible = state.importPasswordVisible,
            onToggleVisible = onToggleVisible,
            imeAction = ImeAction.Done,
        )
        ActionButton(
            text = stringResource(R.string.import_decrypt),
            icon = Icons.Filled.Download,
            enabled = state.canDecrypt,
            busy = state.decoding,
            busyText = stringResource(R.string.import_in_progress),
            onClick = onDecrypt,
        )
    } else {
        // 第三步：预览 + 确认导入
        PreviewCard(
            itemCount = preview.items.size,
            folderCount = preview.folders.size,
            plan = state.importPlan,
            allDuplicated = state.allDuplicated,
        )
        ActionButton(
            text = stringResource(R.string.import_confirm),
            icon = Icons.Filled.Description,
            enabled = state.canApply,
            busy = state.importing,
            busyText = stringResource(R.string.import_in_progress),
            onClick = onApply,
        )
        OutlinedButton(
            onClick = onClearPreview,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        ) {
            Text(stringResource(R.string.action_cancel))
        }
    }
}

/**
 * 预览卡：文件里有多少条，以及**写进去会发生什么**（新建几条 / 已存在几条）。
 *
 * 为什么必须把"已存在"写出来：导入是不可撤销的批量写。只说"文件里 50 条"而不说
 * "其中 12 条已存在会被跳过"，用户会以为 50 条都进去了（或以为去重把数据吃了）。
 */
@Composable
private fun PreviewCard(
    itemCount: Int,
    folderCount: Int,
    plan: ImportPlan?,
    allDuplicated: Boolean,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Icon(Icons.Filled.Description, contentDescription = null)
                Text(
                    text = stringResource(R.string.import_preview_summary, itemCount, folderCount),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            when {
                // 预演算完之前什么都不说：显示"正在计算"比显示 0 更诚实。
                plan == null -> Text(
                    text = stringResource(R.string.import_plan_pending),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                plan.duplicateCount == 0 -> Text(
                    text = stringResource(R.string.import_plan_all_new, plan.newItemCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Text(
                    text = if (allDuplicated) {
                        stringResource(R.string.import_plan_all_duplicated, plan.duplicateCount)
                    } else {
                        stringResource(
                            R.string.import_plan_duplicates,
                            plan.newItemCount,
                            plan.duplicateCount,
                            plan.duplicateTitles.joinToString("、"),
                        )
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 错误卡片：把分类错误映射为可执行的中文文案。 */
@Composable
private fun ErrorCard(kind: ImportExportViewModel.ErrorKind, detail: String?) {
    val text = when (kind) {
        ImportExportViewModel.ErrorKind.MALFORMED -> stringResource(R.string.import_error_malformed)
        ImportExportViewModel.ErrorKind.WRONG_PASSWORD -> stringResource(R.string.import_error_wrong_password)
        ImportExportViewModel.ErrorKind.UNSUPPORTED_KDF -> stringResource(R.string.import_error_unsupported_kdf)
        ImportExportViewModel.ErrorKind.VAULT_LOCKED -> stringResource(R.string.vault_export_unavailable)
        ImportExportViewModel.ErrorKind.GENERIC -> stringResource(R.string.import_failed, detail.orEmpty())
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(Spacing.lg),
        )
    }
}
