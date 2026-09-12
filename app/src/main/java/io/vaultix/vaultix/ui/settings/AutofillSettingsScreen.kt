/*
 * Vaultix — app:ui · settings
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 信息架构（对齐 Bastion「设置 → 自动填充」二级页 AutofillSettingsV2Screen）
 *
 * Bastion 的自动填充设置是一个**独立的二级页**，页内先给一张**三态状态卡**
 * （未启用 errorContainer / 需注意 tertiaryContainer / 正常 primaryContainer），再按
 * SectionCard 分组（系统设置 / 填充行为 / 验证器 / 保存行为 / 黑名单…）。Vaultix 原先把
 * 这些项平铺在设置首页，且**没有状态卡**——用户无法一眼看出「到底是没启用，还是启用了
 * 但填不出来」。本页按 Bastion 的结构补齐：
 *
 * - 顶部状态卡（三态，可刷新）
 * - 系统设置：系统自动填充服务
 * - 通行密钥：凭据提供商状态 + 已保存数量（Android 14+ 才显示）
 * - 填充行为：严格匹配 / 允许子域名匹配 / 快速填充磁贴
 * - 验证器：填充后自动复制验证码
 * - 保存行为：登录成功后是否询问保存
 * - 其他：权限管理
 *
 * 未搬运 Bastion 的「黑名单 / 屏蔽字段 / 智能标题 / 通知时长 / 密码建议 / 诊断」等分组
 * ——Vaultix 没有对应能力，搬过来只会是点不动的假开关。
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.vaultix.vaultix.R
import io.vaultix.vaultix.autofill.shortcut.AutofillTileService
import io.vaultix.vaultix.util.AutofillStatus
import io.vaultix.vaultix.util.AutofillStatusChecker

/**
 * 自动填充二级设置页（设置首页「自动填充」入口进入，也是系统凭据设置的落地页）。
 *
 * 状态每次回前台刷新（跳系统设置开启后返回要能立刻看到变化），顶栏另有手动刷新。
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
    val baseDomainMatch by viewModel.autofillBaseDomainMatch.collectAsStateWithLifecycle()
    val exactDomainOnly by viewModel.autofillExactDomainOnly.collectAsStateWithLifecycle()
    var tileUnsupported by rememberSaveable { mutableStateOf(false) }

    var status by remember { mutableStateOf(AutofillStatusChecker.check(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                status = AutofillStatusChecker.check(context)
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
                actions = {
                    IconButton(onClick = { status = AutofillStatusChecker.check(context) }) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.autofill_status_refresh),
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
                .padding(bottom = 32.dp),
        ) {
            AutofillStatusCard(
                status = status,
                onGoToSettings = { openSystemAutofillSettings(context) },
            )

            // ---- 系统设置 ----
            SettingsGroupTitle(stringResource(R.string.group_autofill_system))
            SettingsRow(
                icon = { Icon(Icons.Filled.Password, contentDescription = null) },
                title = stringResource(R.string.setting_autofill),
                subtitle = stringResource(R.string.setting_autofill_desc),
                onClick = { openSystemAutofillSettings(context) },
            )

            // ---- 通行密钥（Android 14+ 才有 Credential Provider）----
            PasskeySection(
                enabled = status.credentialProviderEnabled,
                onOpenProviderSettings = { openCredentialProviderSettings(context) },
            )

            // ---- 填充行为 ----
            SettingsGroupTitle(stringResource(R.string.group_autofill_behavior))
            SettingsRow(
                icon = { Icon(Icons.Filled.Shield, contentDescription = null) },
                title = stringResource(R.string.setting_autofill_exact_domain),
                subtitle = stringResource(R.string.setting_autofill_exact_domain_desc),
                trailing = {
                    Switch(
                        checked = exactDomainOnly,
                        onCheckedChange = viewModel::setAutofillExactDomainOnly,
                    )
                },
            )
            SettingsRow(
                icon = { Icon(Icons.Filled.Language, contentDescription = null) },
                title = stringResource(R.string.setting_autofill_base_domain),
                subtitle = stringResource(R.string.setting_autofill_base_domain_desc),
                trailing = {
                    Switch(
                        checked = baseDomainMatch,
                        onCheckedChange = viewModel::setAutofillBaseDomainMatch,
                    )
                },
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
 * 顶部状态卡（移植自 Bastion `AutofillSettingsV2Screen` 的状态卡）。
 *
 * 三态配色与 Bastion 一致：未启用 errorContainer / 需注意 tertiaryContainer /
 * 正常 primaryContainer。「需注意」= 密码能填但通行密钥没开——这是 Chromium 浏览器
 * 最常见的半残状态，必须让用户看见，否则会误以为「已经全配好了」。
 *
 * 「前往系统设置」按钮只在未出现「已启用」时出现：已启用时它与下方「系统自动填充」
 * 入口完全重复，语义上也和「已设为默认服务」冲突（Bastion 同处理）。
 */
@Composable
private fun AutofillStatusCard(
    status: AutofillStatus,
    onGoToSettings: () -> Unit,
) {
    val container = when {
        !status.systemEnabled -> MaterialTheme.colorScheme.errorContainer
        status.needsAttention -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val content = when {
        !status.systemEnabled -> MaterialTheme.colorScheme.onErrorContainer
        status.needsAttention -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    Icons.Filled.Password,
                    contentDescription = null,
                    tint = content,
                )
                Text(
                    text = stringResource(
                        if (status.systemEnabled) {
                            R.string.autofill_status_enabled
                        } else {
                            R.string.autofill_status_disabled
                        }
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = content,
                )
            }
            Text(
                text = stringResource(statusDescription(status)),
                style = MaterialTheme.typography.bodySmall,
                color = content.copy(alpha = 0.9f),
            )
            if (!status.systemEnabled) {
                TextButton(onClick = onGoToSettings) {
                    Text(stringResource(R.string.autofill_status_go_to_settings))
                }
            }
        }
    }
}

private fun statusDescription(status: AutofillStatus): Int = when {
    !status.systemEnabled -> R.string.autofill_status_disabled_desc
    status.needsAttention -> R.string.autofill_status_attention_desc
    else -> R.string.autofill_status_enabled_desc
}

/**
 * 通行密钥分组。
 *
 * Android 14 以下没有 Credential Provider，整组换成一行版本说明（对齐 Bastion
 * `PasskeySettingsScreen` 的 `isPasskeySupported` 分支）。
 *
 * **刻意只留「凭据提供商」一个入口**（对齐 Bastion `SystemSettingsCard` 的思路）：
 * 已保存数量与「通行密钥能做什么」的说明属冗余信息（App 内已有独立的通行密钥列表页），
 * 2026-09-12 按用户要求移除。
 */
@Composable
private fun PasskeySection(
    enabled: Boolean,
    onOpenProviderSettings: () -> Unit,
) {
    SettingsGroupTitle(stringResource(R.string.group_passkey))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        SettingsRow(
            icon = { Icon(Icons.Filled.Key, contentDescription = null) },
            title = stringResource(R.string.setting_credential_provider),
            subtitle = stringResource(
                R.string.passkey_android_version_unsupported,
                Build.VERSION.RELEASE,
            ),
        )
        return
    }
    // Credential Provider（Android 14+）：Chromium（Chrome/Edge）取通行密钥只问系统已
    // 启用的 Provider——App 无法自行启用（安全设置，需用户手动开）。
    SettingsRow(
        icon = { Icon(Icons.Filled.Key, contentDescription = null) },
        title = stringResource(R.string.setting_credential_provider),
        subtitle = stringResource(
            if (enabled) {
                R.string.setting_credential_provider_enabled_desc
            } else {
                R.string.setting_credential_provider_disabled_desc
            }
        ),
        // 直达「启用本 Provider」的系统界面（Android 14+ createSettingsPendingIntent）。
        onClick = onOpenProviderSettings,
    )
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

