/*
 * Vaultix — app:ui · settings
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.vaultix.datastore.VaultTimeout
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.common.deviceCanAuthenticate
import io.vaultix.vaultix.ui.theme.Spacing

/**
 * 「锁与安全」二级页（设置首页「锁与安全」入口进入）——**门锁层**的配置页。
 *
 * ## 为什么它必须与「密码库管理」分开两页（2026-09-30 晚，用户真机反馈）
 *
 * 用户原话：「设置里面的密码库设置和解锁方式打开好像都是同一个页面，这不对吧」。
 * 此前确实如此：那一行导航到 [VaultManagementRoute]，因为它当时是那一页里的一个组。
 * **两行入口指向同一处**的后果不是"省了一页"，而是：
 * - 用户无法判断该点哪一行（两行看起来是两件事，点开却一样）；
 * - 页面标题与内容错位（顶栏写「密码库管理」，第一组却是解锁方式）。
 *
 * 分开的依据是**作用域**（与房子化定稿的模型一致）：
 *
 * | 页 | 管什么 | 作用域 |
 * |---|---|---|
 * | [VaultManagementScreen] | **库**：有哪些库、浏览/默认哪个、库从哪来 | 逐个库 |
 * | 本页 | **锁**：怎么开（指纹 / PIN）+ 多久自动锁上 | **全局**（所有库都遵从） |
 *
 * ## 为什么叫「锁与安全」、为什么这么短
 *
 * 用户拍板（2026-09-30 晚）：「解锁方式应该和 keyguard 差不多，生物验证和 PIN 是
 * Vaultix 的**锁与安全机制**」⇒ 与系统 Keyguard 同构：**一把锁保护全部内容**。
 * 于是这一页的取名与分组照 Android 的「设置 → 安全 → 屏幕锁定」来：
 * **锁的方式**（指纹 / PIN）与**锁的超时**（自动锁定）同屏 —— 它们是同一个问题的两个参数。
 *
 * ⚠️ 页面**短 = 模型简单**的如实投影，别为了"看起来丰满"往回加东西：历史上这里曾有
 * 三行 + 一张逐库勾选表 + 一个配置向导，都被用户逐轮砍掉了；「自动锁定」也**不再是
 * 设置首页的一行**（2026-09-30 晚搬进本页），因为它本来就是"锁"的参数。
 *
 * ⚠️ **流程宿主 [QuickUnlockHost] 必须在本页**：拨开关会启动多步流程
 * （各库主密码 → PIN 输入 → 指纹认证 → 结果），那些对话框由它渲染。
 * 库管理页**不再**持有它 —— 那里的解锁入口已经搬到这里了。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnlockMethodScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val quickUnlockState by viewModel.quickUnlock.state.collectAsStateWithLifecycle()
    val legacyRemains by viewModel.quickUnlock.legacyRemains.collectAsStateWithLifecycle()
    // 全局默认档位（所有库共用）。⚠️ 可空、初值 null = 还没读出来 ⇒ 那一行先不给副标题
    //（不拿默认档冒充用户的选择，与 SettingsViewModel 顶部那段契约同一条）。
    val globalTimeout by viewModel.globalVaultTimeout.collectAsStateWithLifecycle()
    var showAutoLockDialog by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.lock_and_security_title)) },
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
            // ⚠️ 不画组标题：本页只有这一组，组名与页面标题会语义重复
            //   （2026-09-17 那条教训的同款，见 QuickUnlockSettingsRows 的 KDoc）。
            SettingsGroupCard {
                QuickUnlockSettingsRows(
                    state = quickUnlockState,
                    canAuthenticate = deviceCanAuthenticate(context),
                    legacyRemains = legacyRemains,
                    // 点整行与拨开关等效（向导已删，见 QuickUnlockSettingsRows 的 KDoc）。
                    onToggleBiometric = viewModel.quickUnlock::toggleBiometric,
                    onTogglePin = viewModel.quickUnlock::togglePin,
                    // 「修改 PIN」（#161）：只在 PIN 已启用时才会出现，见 QuickUnlockSettingsRows。
                    onStartChangePin = viewModel.quickUnlock::startChangePin,
                )
                SettingsDivider()
                // ★ 2026-09-30 晚搬入：**锁的超时**与锁的方式同页 ——
                //   它就是"多久自动锁上"，本就是同一件事的参数（Android 亦同屏）。
                //   此前它在设置首页单独占一行，理由（"独立一个设置、全局生效"）依然成立，
                //   只是位置更该在"锁"这一页里。
                SettingsRow(
                    icon = { Icon(Icons.Filled.Timer, contentDescription = null) },
                    title = stringResource(R.string.setting_auto_lock),
                    subtitle = globalTimeout?.let {
                        stringResource(R.string.auto_lock_global_summary_fmt, vaultTimeoutLabel(it))
                    },
                    onClick = { showAutoLockDialog = true },
                )
            }
            Text(
                text = stringResource(R.string.unlock_method_scope_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.xl),
            )
        }
    }

    if (showAutoLockDialog) {
        // 未读出来时用默认档顶初值：对话框只能由**点击**打开，那时订阅早已建立
        //（与副标题的"不确定就不显示"不同 —— 这里是用户主动要看的那张表）。
        AutoLockDialog(
            current = globalTimeout ?: VaultTimeout.DEFAULT,
            onSelect = {
                viewModel.setGlobalVaultTimeout(it)
                showAutoLockDialog = false
            },
            onDismiss = { showAutoLockDialog = false },
        )
    }

    // 流程宿主：各库主密码 / PIN 输入 / 指纹认证 / 结果（见类 KDoc）。
    QuickUnlockHost(viewModel.quickUnlock)
}
