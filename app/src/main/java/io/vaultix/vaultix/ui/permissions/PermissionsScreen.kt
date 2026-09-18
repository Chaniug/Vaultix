/*
 * Vaultix — app:ui · permissions
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 信息架构（对齐 Bastion「设置 → 权限」二级页 + Vaultix 自己的「自动填充」二级页）
 *
 * 结构照抄 `AutofillSettingsScreen`：**顶部三态状态卡**（未就绪 errorContainer /
 * 需注意 tertiaryContainer / 全部就绪 primaryContainer）+ 按用途分组的设置卡片。
 * 「检测 → 状态卡 → 分组引导」这条链路在自动填充页已被验证过（用户能一眼看出
 * "到底缺不缺、缺什么"），权限页是同一类问题，**不另起一套规格**
 * （8.4：一屏里出现两套规格 = 观感廉价的首要来源）。
 *
 * 未搬运 Bastion 的部分：它的权限页带「一键跳转各厂商自启管理」白名单
 * （针对国产 ROM 的后台存活优化）。Vaultix 没有后台常驻需求（无同步守护进程），
 * 搬过来会变成一组**点了也没用的入口** —— 宁可没有。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.permissions

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.settings.SettingsDivider
import io.vaultix.vaultix.ui.settings.SettingsGroupCard
import io.vaultix.vaultix.ui.settings.SettingsGroupTitle
import io.vaultix.vaultix.ui.settings.SettingsRow
import io.vaultix.vaultix.ui.theme.Spacing
import io.vaultix.vaultix.util.SystemSettingsIntents

/**
 * 权限引导二级页（设置首页「关于 → 权限管理」进入）。
 *
 * ## 这一页要回答的两个问题
 *
 * 1. **「这 App 要哪些权限、拿去干什么？」** —— 每项都有一句用途说明，
 *    并明确写出"不做什么"（相机不拍照不落盘、通知不带内容）。用户对密码管理器的
 *    权限是有戒心的，**不解释就等于心虚**；
 * 2. **「现在缺哪一项、怎么补？」** —— 顶部状态卡汇总"还有几项没就绪"，
 *    每项行尾给一个**能直接动作**的按钮（能就地弹授权框的就地弹，不能的跳系统页）。
 *
 * ## ⚠️ 状态必须每次回前台重算
 *
 * 用户从系统设置页返回时，`checkSelfPermission` 的结论已经变了，但 Compose 不会
 * 自己知道 —— 不重算就会停在旧状态（"我明明刚开了，这里还说没开"）。
 * 用 `DisposableEffect + ON_RESUME` 重算，与 `AutofillSettingsScreen` 同一套写法。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val checker = remember(context) { PermissionStatusChecker(context) }
    // ⚠️ 初值**每次进入组合都现算**（不用 `remember` 缓存）：本页的整个意义就是
    //    "反映此刻的系统状态"，缓存一个可能已经过期的结论只会骗人。
    //    `checker.entries()` 是幂等的只读系统查询，重算开销可忽略。
    var entries by remember { mutableStateOf(checker.entries()) }
    // 权限请求发起后，无论结果如何都要重算（用户可能只授予了其中一个）。
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { entries = checker.entries() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                entries = checker.entries()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val pendingCount = entries.count { it.state == PermissionState.Denied }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.permission_management_title)) },
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
                .padding(bottom = Spacing.xxl),
        ) {
            PermissionStatusCard(pendingCount = pendingCount)

            SettingsGroupTitle(stringResource(R.string.permission_group_needed))
            SettingsGroupCard {
                entries.forEachIndexed { index, entry ->
                    if (index > 0) SettingsDivider()
                    PermissionRow(
                        entry = entry,
                        onRequest = { permission -> launcher.launch(permission) },
                    )
                }
            }

            // 页脚：告诉用户「这一页只是索引，真正的开关在系统里」——
            // 避免有人以为页面上的按钮是"应用自带的开关"。
            Text(
                text = stringResource(R.string.permission_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
            )
        }
    }
}

/**
 * 顶部三态状态卡（与 `AutofillStatusCard` 同一规格：28dp 圆角 / 三态配色）。
 *
 * 「全部就绪」必须是**一个明确的好消息**（primaryContainer + 对勾），
 * 而不是什么都不显示 —— 用户点进"权限管理"就是想确认一句"没问题吧"，
 * 页面得正面回答他（8.4：永远给用户一个肯定的结果，别让他靠"没有红字"去推理）。
 */
@Composable
private fun PermissionStatusCard(pendingCount: Int) {
    val allGood = pendingCount == 0
    val container = if (allGood) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }
    val content = if (allGood) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onTertiaryContainer
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = if (allGood) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = content,
                )
                Text(
                    text = stringResource(
                        if (allGood) {
                            R.string.permission_status_all_good
                        } else {
                            R.string.permission_status_pending
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = content,
                )
            }
            Text(
                text = if (allGood) {
                    stringResource(R.string.permission_status_all_good_desc)
                } else {
                    stringResource(R.string.permission_status_pending_desc, pendingCount)
                },
                style = MaterialTheme.typography.bodySmall,
                color = content.copy(alpha = 0.9f),
            )
        }
    }
}

/**
 * 单个权限行：图标 + 标题 + 用途 + 右侧状态/动作。
 *
 * 右侧**按状态与能力分三种**，不让用户猜：
 * - `Granted` 且恒为授予（网络）→ 只给一个「已允许」文本（**陈述**，不给按钮）；
 * - `Granted` → 同样是「已允许」，因为"关掉它"属于极低频操作，不值得在每行放一个
 *   通向系统设置的箭头（Bastion 同处理）；
 * - `Denied` → 给动作按钮：能就地弹系统授权框的写「授予」，否则写「去设置」；
 * - `Unavailable` → 写「设备不支持」，**不给任何按钮**（点了也没用的按钮比没有更糟）。
 *
 * 用 [SettingsRow] 的 `trailing` 槽承载右侧，从而与设置页其它行**共用同一套行高与
 * 缩进**（不另起一套规格）。
 */
@Composable
private fun PermissionRow(
    entry: PermissionEntry,
    onRequest: (String) -> Unit,
) {
    val context = LocalContext.current
    SettingsRow(
        icon = { Icon(iconFor(entry.id), contentDescription = null) },
        title = stringResource(entry.titleRes),
        subtitle = stringResource(entry.purposeRes),
        trailing = {
            PermissionTrailing(
                entry = entry,
                onRequest = onRequest,
                onOpenSettings = { openSystemPageFor(context, entry.id) },
            )
        },
    )
}

/**
 * 行尾的动作区。
 *
 * 「通知」这一项有**两层开关**（运行时权限 + 系统通知总开关），所以已授予时
 * 也仍然给一个「去设置」入口 —— 只有那里能看到总开关是否被关掉
 * （权限是 GRANTED 但通知发不出来，是最容易让人困惑的一种状态）。
 */
@Composable
private fun PermissionTrailing(
    entry: PermissionEntry,
    onRequest: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    when (entry.state) {
        PermissionState.Granted -> {
            if (entry.id == PermissionIds.NOTIFICATIONS) {
                TextButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.permission_action_settings))
                }
            } else {
                Text(
                    text = stringResource(R.string.permission_state_granted),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        PermissionState.Denied -> TextButton(
            onClick = {
                val permission = entry.runtimePermission
                if (permission != null) onRequest(permission) else onOpenSettings()
            },
        ) {
            Text(
                stringResource(
                    if (entry.canRequestInApp) {
                        R.string.permission_action_grant
                    } else {
                        R.string.permission_action_settings
                    },
                ),
            )
        }

        PermissionState.Unavailable -> Text(
            text = stringResource(R.string.permission_state_unavailable),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 「未授予」时该跳哪个系统页（每项各不相同：相机/通知跳各自设置，指纹跳录入页）。 */
private fun openSystemPageFor(context: Context, id: String) {
    when (id) {
        PermissionIds.CAMERA -> SystemSettingsIntents.openAppDetails(context)
        PermissionIds.NOTIFICATIONS -> SystemSettingsIntents.openAppNotificationSettings(context)
        PermissionIds.BIOMETRICS -> SystemSettingsIntents.openBiometricEnroll(context)
        else -> SystemSettingsIntents.openAppDetails(context)
    }
}

/** 每项各自的图标（与行文语义对齐，不用一个通用盾牌糊弄所有行）。 */
private fun iconFor(id: String): ImageVector = when (id) {
    PermissionIds.CAMERA -> Icons.Filled.PhotoCamera
    PermissionIds.NOTIFICATIONS -> Icons.Filled.Notifications
    PermissionIds.BIOMETRICS -> Icons.Filled.Fingerprint
    PermissionIds.NETWORK -> Icons.Filled.Language
    else -> Icons.Filled.CheckCircle
}
