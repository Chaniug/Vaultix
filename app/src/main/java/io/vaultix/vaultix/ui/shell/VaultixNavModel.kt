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
 * 底部导航项模型（Tab 枚举 + 图标 + 长短标签）。
 * 结构参考 Bastion 项目（GPL-3.0，Copyright 2025 JoyinJoester）的
 * ui/main/navigation/BottomNavModel.kt，按 Vaultix 架构裁剪重写：
 *   - Bastion 的 9 项（含 VaultV2/CardWallet/Notes/Send/Generator/Passkey）裁剪为
 *     Vaultix 的 4 项 —— 通行密钥不占 Tab，走验证码页内入口（与 Bastion 一致）；
 *     Send/Notes/Generator 为 Vaultix 暂不具备的能力。
 *   - **不搬** Bastion 的「用户自定义排序」与「可见性开关」
 *     （`BottomNavContentTab.DEFAULT_ORDER` / `sanitizeOrder` / `BottomNavVisibility`）：
 *     固定 4 项顺序即可，骨架稳定后再评估。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.shell

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.ui.graphics.vector.ImageVector
import io.vaultix.vaultix.R

/**
 * 主界面底部导航的可选页签。
 *
 * **顺序即显示顺序**（固定，不做用户自定义）。中央「+」按钮不属于本枚举——
 * 它是导航条内的独立动作按钮（见 [VaultixBottomDock]）。
 */
enum class VaultixNavItem {
    Passwords,
    Authenticator,
    CardWallet,
    Settings,
    ;

    val icon: ImageVector
        get() = when (this) {
            Passwords -> Icons.Default.Lock
            Authenticator -> Icons.Default.Security
            CardWallet -> Icons.Default.Wallet
            Settings -> Icons.Default.Settings
        }

    /** 导航条标签（短文案，容纳 2 行）。 */
    val labelRes: Int
        get() = when (this) {
            Passwords -> R.string.nav_passwords
            Authenticator -> R.string.nav_authenticator
            CardWallet -> R.string.nav_card_wallet
            Settings -> R.string.nav_settings
        }

    /** 该页签下「+」按钮新建的条目类型（`Settings` 回退为新建密码，对齐 Bastion `else ->`）。 */
    val addTarget: VaultixAddTarget
        get() = when (this) {
            Passwords -> VaultixAddTarget.Login
            Authenticator -> VaultixAddTarget.Totp
            CardWallet -> VaultixAddTarget.Card
            // 对齐 Bastion SimpleMainScreen 的 `else -> handlePasswordAddOpen()`：
            // 设置页按「+」回退为新建密码条目，不隐藏按钮。
            Settings -> VaultixAddTarget.Login
        }
}

/** 「+」按钮的语义目标（由 Tab 决定，宿主按此分发到对应的新建流程）。 */
enum class VaultixAddTarget {
    Login,
    Totp,
    Card,
}
