/*
 * Vaultix — app:ui · settings
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 信息架构（对齐 Bastion「设置 → 自动填充」二级页）
 *
 * Bastion 的自动填充设置是一个**独立的二级页**（AutofillSettingsV2Screen），页内再按
 * SectionCard 分组（系统设置 / 填充行为 / 验证器 / 保存行为 / 黑名单…）。Vaultix 原先把
 * 这些项**平铺在设置首页**，条数一多就把安全 / 外观 / 数据组挤到很下面。本文件按 Bastion
 * 的结构搬运为二级页，首页只留一个入口。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.settings

import android.app.StatusBarManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.vaultix.vaultix.R
import io.vaultix.vaultix.autofill.shortcut.AutofillTileService
import io.vaultix.vaultix.util.CredentialProviderStatus

/**
 * 自动填充二级设置页（设置首页「自动填充」入口进入）。
 *
 * 三个分组（对齐 Bastion `autofill_*_title` 的 SectionCard 划分，按 Vaultix 已有能力取子集）：
 * - **系统设置**：系统自动填充服务入口 / 凭据提供商启用状态 / 快速填充磁贴
 * - **验证器**：填充后自动复制验证码
 * - **保存行为**：登录成功后是否询问保存
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutofillSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val savePrompt by viewModel.autofillSavePrompt.collectAsStateWithLifecycle()
    val autoCopyTotp by viewModel.autoCopyTotp.collectAsStateWithLifecycle()
    var tileUnsupported by rememberSaveable { mutableStateOf(false) }
    // 凭据提供商启用状态：只读检测 + 每次回前台刷新（跳系统设置开启后返回要能看到变化）
    var credentialProviderEnabled by remember { mutableStateOf(CredentialProviderStatus.isEnabled(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                credentialProviderEnabled = CredentialProviderStatus.isEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.group_autofill)) },
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
                .verticalScroll(rememberScrollState()),
        ) {
            // ---- 系统设置 ----
            SettingsGroupTitle(stringResource(R.string.group_autofill_system))
            SettingsRow(
                icon = { Icon(Icons.Filled.Password, contentDescription = null) },
                title = stringResource(R.string.setting_autofill),
                subtitle = stringResource(R.string.setting_autofill_desc),
                onClick = { openSystemAutofillSettings(context) },
            )
            // Credential Provider（Android 14+）：Chromium（Chrome/Edge）取密码/通行密钥只问
            // 系统已启用的 Provider——App 无法自行启用（安全设置，需用户手动开）。
            SettingsRow(
                icon = { Icon(Icons.Filled.Key, contentDescription = null) },
                title = stringResource(R.string.setting_credential_provider),
                subtitle = stringResource(
                    if (credentialProviderEnabled) {
                        R.string.setting_credential_provider_enabled_desc
                    } else {
                        R.string.setting_credential_provider_disabled_desc
                    }
                ),
                // 直达「启用本 Provider」的系统界面（Android 14+ createSettingsPendingIntent）；
                // 低版本退化到自动填充设置页（老路径服务选择）
                onClick = { openCredentialProviderSettings(context) },
            )
            // 快捷磁贴：国产输入法大多不支持键盘内联建议、部分国产 ROM 会吞掉系统填充弹窗，
            // 这条「复制 + 粘贴」路径不依赖输入法和无障碍，是最稳的兜底入口（仅说明如何添加）。
            SettingsRow(
                icon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                title = stringResource(R.string.setting_manual_fill_tile),
                subtitle = stringResource(R.string.setting_manual_fill_tile_desc),
                onClick = { requestAddTile(context) { tileUnsupported = true } },
            )

            // ---- 验证器 ----
            // 链路保持最简：识别到条目 → 填密码 → 验证码进剪贴板。
            SettingsGroupTitle(stringResource(R.string.group_otp))
            SettingsRow(
                icon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                title = stringResource(R.string.setting_auto_copy_totp),
                subtitle = stringResource(R.string.setting_auto_copy_totp_desc),
                trailing = {
                    Switch(
                        checked = autoCopyTotp,
                        onCheckedChange = viewModel::setAutoCopyTotp,
                    )
                },
            )

            // ---- 保存行为 ----
            SettingsGroupTitle(stringResource(R.string.group_autofill_save))
            SettingsRow(
                icon = { Icon(Icons.Filled.Save, contentDescription = null) },
                title = stringResource(R.string.setting_autofill_save_prompt),
                subtitle = stringResource(R.string.setting_autofill_save_prompt_desc),
                trailing = {
                    Switch(
                        checked = savePrompt,
                        onCheckedChange = viewModel::setAutofillSavePrompt,
                    )
                },
            )
        }
    }

    if (tileUnsupported) {
        AlertDialog(
            onDismissRequest = { tileUnsupported = false },
            title = { Text(stringResource(R.string.setting_manual_fill_tile)) },
            text = { Text(stringResource(R.string.setting_manual_fill_tile_hint)) },
            confirmButton = {
                TextButton(onClick = { tileUnsupported = false }) {
                    Text(stringResource(R.string.action_done))
                }
            },
        )
    }
}

/**
 * 把「快速填充」磁贴加到快捷设置。
 *
 * Android 13+ 用系统 API 弹确认框（`StatusBarManager.requestAddTileService`）；
 * 更低版本没有公开 API，只能引导用户手动拖动（系统不允许应用替用户改快捷设置布局，
 * 所以这里**没有也不该有**「开关」——磁贴的增删权限在系统手里）。
 */
private fun requestAddTile(context: Context, onUnsupported: () -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        onUnsupported()
        return
    }
    val manager = context.getSystemService(StatusBarManager::class.java)
    val added = runCatching {
        manager.requestAddTileService(
            ComponentName(context, AutofillTileService::class.java),
            context.getString(R.string.tile_manual_fill),
            Icon.createWithResource(context, R.drawable.ic_stat_lock),
            ContextCompat.getMainExecutor(context),
        ) { }
    }.isSuccess
    if (!added) onUnsupported()
}

/** 打开系统自动填充设置：优先请求直接把 Vaultix 设为服务，失败回退到服务列表。 */
private fun openSystemAutofillSettings(context: Context) {
    // 直接请求把 Vaultix 设为自动填充服务（Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, API 26）。
    val direct = Intent("android.settings.REQUEST_SET_AUTOFILL_SERVICE").apply {
        data = Uri.parse("package:${context.packageName}")
    }
    try {
        context.startActivity(direct)
    } catch (_: ActivityNotFoundException) {
        // 部分 OEM 不支持直接请求，退到自动填充服务选择列表
        //（Settings.ACTION_AUTOFILL_SERVICE_SETTINGS, API 28）。
        context.startActivity(Intent("android.settings.AUTOFILL_SERVICE_SETTINGS"))
    }
}

/**
 * 打开「启用本应用为 Credential Provider」的系统界面。
 *
 * Android 14+ 用 [androidx.credentials.CredentialManager.createSettingsPendingIntent]
 * ——系统据此展示自家 provider 的启用开关（此前的 REQUEST_SET_AUTOFILL_SERVICE 在
 * 部分设备上无反应，且老自动填充与凭据提供商是两个独立设置项，互不替代）。
 * 低版本没有 Credential Provider，退化到老自动填充设置页。
 */
private fun openCredentialProviderSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        val pendingIntent = runCatching {
            androidx.credentials.CredentialManager.create(context).createSettingsPendingIntent()
        }.getOrNull()
        if (pendingIntent != null) {
            val sent = runCatching { pendingIntent.send(context, 0, null) }.isSuccess
            if (sent) return
        }
    }
    openSystemAutofillSettings(context)
}
