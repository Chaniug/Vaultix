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

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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
 * @param onOpenImportExport 打开导入 / 导出二级页（设置 Tab 二级页）。
 * @param onLocked 该库被锁定：交由根导航收回到解锁页（本容器不再自持锁态判定）。
 * @param onSwitchVault 「切换密码库」出口（issue #96）：**只在存在多个库时**由宿主传入，
 *   单库时传 null 即整项隐藏（没有可切换的对象）。
 */
@Composable
fun MainShellScreen(
    onOpenItem: (VaultItem) -> Unit,
    onOpenTrash: () -> Unit,
    onOpenPasskeys: () -> Unit,
    onOpenAutofillSettings: () -> Unit,
    onOpenImportExport: () -> Unit,
    /** 设置 Tab 内添加库（库列表路由在已有库时不可达，否则用户永远加不了 KDBX）。 */
    onAddBitwardenVault: () -> Unit,
    onAddKdbxVault: () -> Unit,
    /** 设置 Tab 内点了**未解锁**的库 → 去它的解锁页（见 `SettingsScreen.onOpenLockedVault`）。 */
    onOpenLockedVault: (String) -> Unit = {},
    /**
     * 去解锁**当前活跃库**（条目页空态里的兜底出口）。
     *
     * 与 [onSwitchVault] 分开：那个是「从多个库里挑一个」，单库时为 null（按纪律隐藏）；
     * 这个是「把这个锁着的库解开」，**与库数量无关**，单库用户同样需要。
     */
    onUnlockActiveVault: () -> Unit = {},
    onLocked: () -> Unit,
    onSwitchVault: (() -> Unit)? = null,
) {
    val configuration = LocalConfiguration.current
    val isCompactWidth = configuration.screenWidthDp < WIDE_SCREEN_MIN_WIDTH_DP

    var currentTab by rememberSaveable { mutableStateOf(VaultixNavItem.Passwords) }
    // 三个「+」请求计数：非零即触发对应 Tab 的新建表单，弹出后由宿主清零
    var passwordsAddRequest by rememberSaveable { mutableIntStateOf(0) }
    var totpAddRequest by rememberSaveable { mutableIntStateOf(0) }
    var cardAddRequest by rememberSaveable { mutableIntStateOf(0) }
    // Tab 离开组合时保存其 rememberSaveable 状态（滚动位置 / 搜索词…），切回来原样恢复。
    val tabStateHolder = rememberSaveableStateHolder()

    val tabs = VaultixNavItem.entries.toList()

    // 底栏是**叠层悬浮**的（见 AdaptiveMainScaffold），内容一直铺到屏幕底 ——
    // 所以各页的滚动内容必须自己留出胶囊的高度，否则最后一条永远被压住。
    val bottomInset = rememberBottomDockInset()

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
            // ⚠️ 「跳过首次过渡」旗标 —— 详见下面 `transitionSpec` 里的说明。
            // MainShell 每次**重新进入组合**（例如从详情页 / 设置子页返回）都会重建这个
            // AnimatedContent，而 `currentTab` 是 `rememberSaveable` 恢复的、会在首帧之后
            // 再"变化"一次 ⇒ 触发一次**本不该出现**的 Tab 过渡（与 NavHost 的返回转场叠加成双影）。
            var skipFirstTransition by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) { skipFirstTransition = false }

            // ★ 阶段 3 观感：Tab 切换过渡 + **Tab 状态保留**
            //
            // - `AnimatedContent` + [tabSwitchEnter]/[tabSwitchExit]：对齐 Bastion
            //   `AuthenticatorPasskeyAnimatedContent`（淡入 + 轻微上移 / 纯淡出）。
            //   `contentKey` 用 Tab 名，保证「A→B→A」不会因为 targetState 相等而跳过动画。
            // - `SaveableStateHolder`：每个 Tab 的 `rememberSaveable` 状态（滚动位置、
            //   搜索框内容、展开态）在离开组合时存入 holder，切回来原样恢复 ——
            //   否则「翻到卡包看个卡号，再切回密码页就回到列表顶部」。
            //   对齐 Bastion 的 `cardWalletSaveableStateHolder` 用法。
            // - ⚠️ `SizeTransform(clip = false) { _, _ -> null }` —— **尺寸补间被关成"瞬时"**。
            //   这是 2026-09-13 第 N 轮靠**录屏逐帧**才钉死的根因：
            //   返回（pop）时 MainShell 会**重新进入组合**，它的 Tab `AnimatedContent` 会把自己的
            //   容器尺寸补间一遍 ⇒ 外层容器随之变矮 ⇒ **满屏的旧页面（详情页）被裁成"小一号"、
            //   还往上偏**，用户看到的就是"返回时详情页缩小 1~2 帧"，且**每次返回都有**。
            //   传 `sizeAnimationSpec = { _, _ -> null }` = 尺寸变化**不做动画**（直接取目标值），
            //   同时保留 `clip = false`（不裁剪内容）。
            //   ⚠️ 别改成 `AnimatedContent(sizeTransform = null)` —— 本版本 Compose 里那个写法
            //   匹配不到重载（只会匹到 `Transition<S>.AnimatedContent` 扩展，报 receiver 不匹配）。
            AnimatedContent(
                targetState = currentTab,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    // ⚠️ 2026-09-13 第五轮（**录屏逐帧**得到的证据）：返回时 MainShell 会
                    // **重新进入组合**，这套 Tab 过渡就被**重放了一遍** —— 表现是
                    // 「详情页横向滑走的同时，列表带着纵向偏移淡入」，两页叠影，
                    // 用户读到的就是"返回时页面缩小 1~2 帧"，而且**每次返回都有**。
                    // Tab 过渡的语义只属于"切换 Tab"；重新进入组合时**不该播**
                    // （此时 NavHost 自己的转场正在放，两者叠加才是双影）。
                    // 用「跳过首次」把重放挡掉：切 Tab 的动画完全不受影响。
                    if (skipFirstTransition) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        (tabSwitchEnter() togetherWith tabSwitchExit())
                            .using(SizeTransform(clip = false) { _, _ -> tween(durationMillis = 0) })
                    }
                },
                contentKey = { it.name },
                label = "vaultix_tab_switch",
            ) { tab ->
                tabStateHolder.SaveableStateProvider(tab.name) {
                    when (tab) {
                        VaultixNavItem.Passwords -> ItemsScreen(
                            embedded = true,
                            addRequest = passwordsAddRequest,
                            onAddConsumed = { passwordsAddRequest = 0 },
                            onBack = {},
                            onLocked = onLocked,
                            onOpenTrash = onOpenTrash,
                            onOpenItem = onOpenItem,
                            // 不传 onOpenTotp：底部导航已有「验证码」页签，
                            // 再在 ⋮ 里放一个重复入口只会让菜单多一项（用户 2026-09-14 要求）。
                            bottomInset = bottomInset,
                            onSwitchVault = onSwitchVault,
                            // 兜底：活跃库若是锁定的，空态给一条自救路径。
                            // 不能只显示「还没有保存的密码」——那是假话（2026-09-15）。
                            // 用无参的 `onUnlockActiveVault` 而不是 `onSwitchVault`：
                            // 后者单库时为 null（该项按纪律隐藏），会让这个按钮变成死键。
                            onUnlockVault = onUnlockActiveVault,
                        )

                        VaultixNavItem.Authenticator -> TotpCodesScreen(
                            embedded = true,
                            addRequest = totpAddRequest,
                            onAddConsumed = { totpAddRequest = 0 },
                            onBack = {},
                            onOpenPasskeys = onOpenPasskeys,
                            bottomInset = bottomInset,
                        )

                        VaultixNavItem.CardWallet -> CardWalletScreen(
                            embedded = true,
                            addRequest = cardAddRequest,
                            onAddConsumed = { cardAddRequest = 0 },
                            onOpenItem = onOpenItem,
                            bottomInset = bottomInset,
                        )

                        VaultixNavItem.Settings -> SettingsScreen(
                            embedded = true,
                            onBack = {},
                            onOpenAutofillSettings = onOpenAutofillSettings,
                            onOpenImportExport = onOpenImportExport,
                            bottomInset = bottomInset,
                            onAddBitwardenVault = onAddBitwardenVault,
                            onAddKdbxVault = onAddKdbxVault,
                            onOpenLockedVault = onOpenLockedVault,
                        )
                    }
                }
            }
        }
    }
}
