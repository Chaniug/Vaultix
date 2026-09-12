/*
 * Vaultix — app:ui:addvault
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * 添加本地 KDBX（KeePass）库：SAF 选文件 → 主密码（+ 可选 keyfile）→ 解锁入库。
 */
package io.vaultix.vaultix.ui.addvault

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.error.unlockErrorText

/**
 * MIME 过滤：KDBX 没有注册 MIME 类型，故按通配 MIME（星号斜杠星号）打开并靠引擎判格式
 * （见 `KdbxFormat`，签名 + 版本双重识别）。
 *
 * ⚠️ 注释里不要写「星号 + 斜杠」的字面序列：Kotlin 块注释**支持嵌套**，
 * 那个序列会被解析成注释结束符，导致文件后半段全部变成语法错误
 * （`.ai/ISSUES.md` #9 的同一个坑，当年排查了很久）。
 */
private const val ANY_MIME = "*/*"

/**
 * 添加本地 KDBX 库。
 *
 * ⚠️ SAF 持久授权：两处 picker 都用 [ActivityResultContracts.OpenDocument]（而不是
 * `GetContent`），并在回调里 `takePersistableUriPermission` —— KDBX 库每次解锁都要重读
 * 文件，没有持久授权就会出现「今天能解锁、明天说读不到文件」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddKdbxScreen(
    onBack: () -> Unit,
    onAdded: () -> Unit,
    viewModel: AddKdbxViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                AddKdbxViewModel.Event.VaultAdded -> onAdded()
            }
        }
    }

    val databasePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { persistReadPermission(context, it) }
        viewModel.onDatabasePicked(context, uri)
    }
    val keyFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { persistReadPermission(context, it) }
        viewModel.onKeyFilePicked(context, uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_kdbx_title)) },
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
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.add_kdbx_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))

            PickedFileRow(
                icon = Icons.Filled.Description,
                label = stringResource(R.string.add_kdbx_file),
                value = state.fileName,
                pickLabel = stringResource(R.string.add_kdbx_pick_file),
                onPick = { databasePicker.launch(arrayOf(ANY_MIME)) },
            )
            Spacer(Modifier.height(16.dp))

            // 密钥文件是**可选**的：默认收起为一行「添加密钥文件」，避免让无 keyfile 的
            // 用户以为必须提供（KeePass 的 keyfile 是少数派用法）。
            if (state.keyFileUri == null) {
                TextButton(onClick = { keyFilePicker.launch(arrayOf(ANY_MIME)) }) {
                    Icon(Icons.Filled.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.add_kdbx_add_keyfile))
                }
            } else {
                PickedFileRow(
                    icon = Icons.Filled.Key,
                    label = stringResource(R.string.add_kdbx_keyfile),
                    value = state.keyFileName,
                    pickLabel = stringResource(R.string.add_kdbx_pick_file),
                    onPick = { keyFilePicker.launch(arrayOf(ANY_MIME)) },
                    onClear = viewModel::clearKeyFile,
                )
            }
            Spacer(Modifier.height(8.dp))

            PasswordField(state = state, viewModel = viewModel)

            unlockErrorText(state.error)?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (state.submitting) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.add_kdbx_working),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(24.dp))
            SubmitButton(state = state, onSubmit = viewModel::submit)
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** 提交按钮（抽出来避免主函数越 detekt 的行数门禁）。 */
@Composable
private fun SubmitButton(state: AddKdbxViewModel.UiState, onSubmit: () -> Unit) {
    val focusManager = LocalFocusManager.current
    FilledTonalButton(
        onClick = {
            focusManager.clearFocus()
            onSubmit()
        },
        enabled = state.canSubmit,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
    ) {
        if (state.submitting) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Text(stringResource(R.string.add_kdbx_submit))
        }
    }
}

/** 主密码输入（KDBX 无邮箱/服务器，只有这一个必填字段）。 */
@Composable
private fun PasswordField(state: AddKdbxViewModel.UiState, viewModel: AddKdbxViewModel) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = state.password,
        onValueChange = viewModel::onPasswordChange,
        label = { Text(stringResource(R.string.add_kdbx_password)) },
        singleLine = true,
        visualTransformation = if (state.passwordVisible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = {
            focusManager.clearFocus()
            viewModel.submit()
        }),
        trailingIcon = {
            IconButton(onClick = { viewModel.onPasswordVisibleChange(!state.passwordVisible) }) {
                Icon(
                    imageVector = if (state.passwordVisible) {
                        Icons.Filled.VisibilityOff
                    } else {
                        Icons.Filled.Visibility
                    },
                    contentDescription = stringResource(
                        if (state.passwordVisible) {
                            R.string.add_vault_password_hidden
                        } else {
                            R.string.add_vault_password_visible
                        },
                    ),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 「已选文件」一行：图标 + 说明 + 文件名（+ 可选清除）。 */
@Composable
private fun PickedFileRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    pickLabel: String,
    onPick: () -> Unit,
    onClear: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(text = label, style = MaterialTheme.typography.labelMedium)
            Text(
                text = value.ifBlank { pickLabel },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onClear != null) {
            IconButton(onClick = onClear) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.add_kdbx_clear_keyfile),
                )
            }
        }
        OutlinedButton(onClick = onPick) {
            Text(stringResource(R.string.add_kdbx_browse))
        }
    }
}

/**
 * 取得**持久**读权限。
 *
 * 失败时静默（部分文档提供方不支持持久授权）：那会让下次解锁时读不到文件并如实报
 * 「请重新选择文件」，比在这里弹一个用户看不懂的错误要好。
 */
private fun persistReadPermission(context: android.content.Context, uri: android.net.Uri) {
    runCatching {
        context.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }
}
