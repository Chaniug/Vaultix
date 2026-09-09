/*
 * Vaultix — app:ui · common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * 应用选择思路参考 Bastion `AppSelector`（GPL-3.0，Copyright 2025 JoyinJoester）：
 * 用 LAUNCHER intent 枚举（而非 getInstalledPackages），规避 Android 11+ 包可见性限制，
 * 且不申请 QUERY_ALL_PACKAGES（应用商店审核风险）。
 */
package io.vaultix.vaultix.ui.common

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import io.vaultix.vaultix.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** 图标位图边长（px）。 */
private const val ICON_SIZE_PX = 48

/** 对话框宽度占屏比。 */
private const val DIALOG_WIDTH_FRACTION = 0.95f

/** 对话框高度占屏比。 */
private const val DIALOG_HEIGHT_FRACTION = 0.85f

/**
 * 应用选择对话框：搜索 → 点选 → 回传包名（由调用方拼成 `androidapp://<pkg>` 存进条目 URI）。
 * 同时提供「手动输入包名」入口（桌面上未安装 / 被 Launcher 过滤的 App）。
 */
@Composable
fun AppPickerDialog(
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var manualOpen by rememberSaveable { mutableStateOf(false) }
    var manualText by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) {
        apps = loadInstalledApps(context)
        loading = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(DIALOG_WIDTH_FRACTION)
                .fillMaxHeight(DIALOG_HEIGHT_FRACTION),
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.app_picker_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel))
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.app_picker_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                when {
                    loading -> LoadingHint()
                    manualOpen -> ManualInput(
                        text = manualText,
                        onTextChange = { manualText = it },
                        onCancel = { manualOpen = false },
                        onConfirm = {
                            val pkg = manualText.trim()
                            if (pkg.isNotBlank()) onPick(pkg)
                        },
                    )
                    else -> AppList(
                        modifier = Modifier.weight(1f),
                        apps = filterApps(apps, query),
                        onPick = onPick,
                        onManual = { manualOpen = true },
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingHint() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun AppList(
    modifier: Modifier = Modifier,
    apps: List<AppInfo>,
    onPick: (String) -> Unit,
    onManual: () -> Unit,
) {
    if (apps.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.app_picker_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        LazyColumn(modifier = modifier.fillMaxWidth()) {
            items(apps, key = { it.packageName }) { app ->
                AppRow(app = app, onPick = onPick)
            }
        }
    }
    TextButton(onClick = onManual, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.app_picker_manual))
    }
}

@Composable
private fun AppRow(app: AppInfo, onPick: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPick(app.packageName) }
            .padding(vertical = 10.dp, horizontal = 4.dp),
    ) {
        AppIcon(packageName = app.packageName)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.appName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 异步加载应用图标（失败 / 无权限时留空，不影响选择）。 */
@Composable
private fun AppIcon(packageName: String) {
    val context = LocalContext.current
    val drawable by produceState<Drawable?>(initialValue = null, key1 = packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
        }
    }
    val bitmap = remember(drawable) {
        drawable?.let { runCatching { it.toBitmap(ICON_SIZE_PX, ICON_SIZE_PX) }.getOrNull() }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(36.dp),
        )
    } else {
        Spacer(Modifier.size(36.dp))
    }
}

@Composable
private fun ManualInput(
    text: String,
    onTextChange: (String) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.app_picker_manual_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            label = { Text(stringResource(R.string.app_picker_manual_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_done)) }
        }
    }
}

/** 搜索过滤：名称前缀 > 名称包含 > 包名包含 > 字母序。 */
private fun filterApps(apps: List<AppInfo>, query: String): List<AppInfo> {
    val q = query.trim().lowercase(Locale.ROOT)
    if (q.isEmpty()) return apps
    val matched = apps.filter {
        it.appName.lowercase(Locale.ROOT).contains(q) || it.packageName.lowercase(Locale.ROOT).contains(q)
    }
    return matched.sortedWith(
        compareBy<AppInfo> { !it.appName.lowercase(Locale.ROOT).startsWith(q) }
            .thenBy { it.appName.lowercase(Locale.ROOT) },
    )
}
