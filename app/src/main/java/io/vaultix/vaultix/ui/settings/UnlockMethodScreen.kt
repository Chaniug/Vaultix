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
import androidx.compose.material.icons.filled.Fingerprint
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
                    // 「忘记 PIN？」（B5 / #161）：同行出现，面向忘了当前 PIN 的人
                    // （那一行要验旧 PIN，这条路不验 —— 清信封后用主密码开门再重设）。
                    onForgetPin = viewModel.quickUnlock::onForgetPin,
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
                // ★ 2026-10-30：「从不」档的**界面门禁**（用户诉求 = 对齐 Bitwarden 的保护强度）。
                //
                //   为什么只在「从不」档显示：
                //   - `N 分钟` / `立即` 档有自己的真锁（会清密钥），本来就要验证，再加一层是重复；
                //   - `重启 App 时` / `系统锁屏时` 档的**卖点就是切后台不打扰**，
                //     给它们叠"熄屏就验证"会直接破坏所选档位的语义。
                //   只有「从不」档是"进程活着期间永不免验证"的空白区，这一行就是补它。
                //
                //   为什么还要判指纹已启用：本门禁的唯一解锁手段是生物识别
                //   （`UnlockScreen` 的查看锁分支只给指纹按钮）。没有指纹时它只能
                //   降级成"每次回来输主密码" —— 比不做门禁更糟，所以**不显示**
                //   （显示一个"拨了没效果"的开关就是 #93「谎报状态的开关」）。
                if (globalTimeout == VaultTimeout.Never &&
                    quickUnlockState.biometric is QuickUnlockController.CapabilityState.On
                ) {
                    val autoGate by viewModel.autoViewGateOnNever.collectAsStateWithLifecycle()
                    SettingsDivider()
                    SettingsRow(
                        icon = { Icon(Icons.Filled.Fingerprint, contentDescription = null) },
                        title = stringResource(R.string.auto_view_gate_title),
                        subtitle = stringResource(
                            if (autoGate) {
                                R.string.auto_view_gate_desc_on
                            } else {
                                R.string.auto_view_gate_desc_off
                            },
                        ),
                        trailing = {
                            SettingsSwitch(
                                value = autoGate,
                                // 同向透传：`SettingsSwitch` 回调传入的已经是新值（#213）。
                                onCheckedChange = viewModel::setAutoViewGateOnNever,
                            )
                        },
                    )
                }
            }
            // ⚠️ 2026-10-04 观感：这段说明原先写 `horizontal = Spacing.xl`(24dp)，
            //   而卡片自身的水平外边距是 `Spacing.lg`(16dp)
            //   ⇒ **左缘比卡片内缩 8dp**，两条竖边对不齐，一眼看过去就是"歪的"。
            //   且它与卡片之间只有卡片自己那 8dp 下边距 ⇒ 贴得太紧。
            //   ⇒ ① 与卡片**左缘对齐**（同取 16dp）；② 上间距提到 8dp（合计 16dp，
            //     正好是"分区之间"那一档，见 8.4「先量再改」）。
            //   字号**维持 bodySmall 不动**：它是脚注，层级必须低于卡内行副标题
            //   （bodyMedium）；把它调大只会让"哪句是补充"变得不可读。
            Text(
                text = stringResource(R.string.unlock_method_scope_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = Spacing.lg,
                    end = Spacing.lg,
                    top = Spacing.sm,
                ),
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
