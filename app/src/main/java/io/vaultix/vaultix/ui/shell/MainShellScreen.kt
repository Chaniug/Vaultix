/*
 * Vaultix — app:ui:shell
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 主界面 Tab 容器，按 Bastion 主界面范式（「解锁即进主界面」，多库不占导航层）
 * 在 Vaultix 架构下重写，依据 Docs/progress/main-shell-migration.md 方案 A4：
 *   - **不带 `vaultId` 参数**：库是**筛选状态**（[ActiveVaultStore]），不是导航参数
 *     （Bastion `grep vaultId ui/main/` 零命中，已实证）；
 *   - Tab 集合 = 密码 / 验证码 / 卡包 / 设置 + 中央「+」（用户 2026-09-10 定稿）；
 *   - 二级页（条目详情 / 回收站 / 通行密钥 / 自动填充设置）仍走路由 push。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import io.vaultix.model.VaultItem
import io.vaultix.vaultix.ui.cardwallet.CardWalletScreen
import io.vaultix.vaultix.ui.items.ItemsScreen
import io.vaultix.vaultix.ui.settings.SettingsScreen
import io.vaultix.vaultix.ui.totp.TotpCodesScreen

/** 宽屏阈值（dp）：≥ 该宽度走 NavigationRail，否则走悬浮胶囊底栏。 */
private const val WIDE_SCREEN_MIN_WIDTH_DP = 600

/**
 * 主界面（解锁后的落点，对齐 Bastion「解锁即进 `Screen.Main`」）。
 *
 * 各 Tab 的库来源统一为 `ActiveVaultStore`（**单一活跃库**），本容器不持 `vaultId`。
 *
 * @param onOpenItem 打开条目详情（二级页，仍带 `vaultId` 路由参数）。
 * @param onOpenTrash 打开回收站。
 * @param onOpenPasskeys 打开通行密钥列表（验证码 Tab 内的入口，对齐 Bastion）。
 * @param onOpenAutofillSettings 打开自动填充设置（设置 Tab 二级页）。
 * @param onLocked 该库被锁定：交由根导航收回到解锁页（本容器不再自持锁态判定）。
 */
@Composable
fun MainShellScreen(
    onOpenItem: (VaultItem) -> Unit,
    onOpenTrash: () -> Unit,
    onOpenPasskeys: () -> Unit,
    onOpenAutofillSettings: () -> Unit,
    onLocked: () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val isCompactWidth = configuration.screenWidthDp < WIDE_SCREEN_MIN_WIDTH_DP

    var currentTab by rememberSaveable { mutableStateOf(VaultixNavItem.Passwords) }
    // 三个「+」请求计数：非零即触发对应 Tab 的新建表单，弹出后由宿主清零
    var passwordsAddRequest by rememberSaveable { mutableIntStateOf(0) }
    var totpAddRequest by rememberSaveable { mutableIntStateOf(0) }
    var cardAddRequest by rememberSaveable { mutableIntStateOf(0) }

    val tabs = VaultixNavItem.entries.toList()

    // 「+」分发：按当前 Tab 决定新建什么（Bastion `when(currentTab)` 语义）。
    // 设置 Tab 回退为「新建密码」（Bastion `else -> handlePasswordAddOpen()`）。
    val dispatchAdd: (VaultixNavItem) -> Unit = { tab ->
        when (tab.addTarget) {
            VaultixAddTarget.Login -> {
                currentTab = VaultixNavItem.Passwords
                passwordsAddRequest++
            }

            VaultixAddTarget.Totp -> {
                currentTab = VaultixNavItem.Authenticator
                totpAddRequest++
            }

            VaultixAddTarget.Card -> {
                currentTab = VaultixNavItem.CardWallet
                cardAddRequest++
            }
        }
    }

    AdaptiveMainScaffold(
        isCompactWidth = isCompactWidth,
        tabs = tabs,
        current = currentTab,
        onSelect = { currentTab = it },
        onAdd = dispatchAdd,
        bottomBar = {
            VaultixBottomDock(
                tabs = tabs,
                selected = currentTab,
                onSelect = { currentTab = it },
                onAdd = dispatchAdd,
            )
        },
    ) { padding ->
        // propagateMinConstraints：把整块可用区域「压」给 Tab 内容，否则各页的
        // Scaffold 会按内容尺寸收缩（Box 默认给子项的是松约束）。
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            propagateMinConstraints = true,
        ) {
            when (currentTab) {
                VaultixNavItem.Passwords -> ItemsScreen(
                    embedded = true,
                    addRequest = passwordsAddRequest,
                    onAddConsumed = { passwordsAddRequest = 0 },
                    onBack = {},
                    onLocked = onLocked,
                    onOpenTrash = onOpenTrash,
                    onOpenItem = onOpenItem,
                    onOpenTotp = { currentTab = VaultixNavItem.Authenticator },
                )

                VaultixNavItem.Authenticator -> TotpCodesScreen(
                    embedded = true,
                    addRequest = totpAddRequest,
                    onAddConsumed = { totpAddRequest = 0 },
                    onBack = {},
                    onOpenPasskeys = onOpenPasskeys,
                )

                VaultixNavItem.CardWallet -> CardWalletScreen(
                    embedded = true,
                    addRequest = cardAddRequest,
                    onAddConsumed = { cardAddRequest = 0 },
                    onOpenItem = onOpenItem,
                )

                VaultixNavItem.Settings -> SettingsScreen(
                    embedded = true,
                    onBack = {},
                    onOpenAutofillSettings = onOpenAutofillSettings,
                )
            }
        }
    }
}
