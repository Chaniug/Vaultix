/*
 * Vaultix — app:ui:items
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 快捷筛选（顶栏点库名展开的那一排）。
 *
 * 语义参考 Bastion（GPL-3.0，Copyright 2025 JoyinJoester）的
 * `PasswordListQuickFilterItem` / `PasswordListTopSection` 的标题点击展开快捷筛选条：
 *   - 点击大标题 → 展开/收起一排筛选 chip（收起后为列表腾出一行空间）；
 *   - 每个 chip 是一个**独立的谓词**（验证码 / 通行密钥 / SSH / 笔记 …），
 *     与「分组方式」正交：分组决定怎么排，筛选决定显示哪些。
 * 维度按 Vaultix（Bitwarden canonical）模型裁剪 —— Bastion 的 wifi / 条码 / 附件 /
 * 本地专属等维度在 Vaultix 没有对应数据（搬过来只会是永远空的选择）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.items

import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType

/**
 * 快捷筛选维度。
 *
 * ⚠️ **单选而非多选**：多选需要「交集 / 并集」的取舍说明，而列表顶部只有一行 chip，
 * 用户看不出当前是哪种组合（Bastion 的多选 chip 也配了「已选 N 项」的说明行）。
 * 单选语义一眼可懂：标题后面挂的就是当前筛选。
 */
enum class ItemsQuickFilter(val storageKey: String) {
    /** 全部（默认；无筛选）。 */
    All("all"),

    /** 含验证码（`totp` 非空）。 */
    Totp("totp"),

    /** 含通行密钥（`fido2Credentials` 非空）。 */
    Passkey("passkey"),

    /** SSH 密钥条目。 */
    Ssh("ssh"),

    /** 安全笔记。 */
    Note("note"),

    /** 收藏。 */
    Favorite("favorite"),
    ;

    companion object {
        fun from(storageKey: String?): ItemsQuickFilter =
            entries.firstOrNull { it.storageKey == storageKey } ?: All
    }
}

/**
 * 按快捷筛选过滤（纯函数，便于 JVM 单测）。
 *
 * ⚠️ 谓词口径必须与**对应页面**一致，否则会出现「筛出 3 条、点进验证码页只有 2 条」：
 * - [ItemsQuickFilter.Totp] 用 `totp` 非空白 —— 与 `TotpCodesScreen` 的口径一致；
 * - [ItemsQuickFilter.Passkey] 用 `fido2Credentials` 非空 —— 与 `PasskeysScreen` 一致；
 *   注意它**只对登录条目有意义**（通行密钥永远挂在登录条目上），但服务端数据可能不干净，
 *   因此这里不额外限定类型，只按「有没有凭证」判。
 */
fun List<VaultItem>.applyQuickFilter(filter: ItemsQuickFilter): List<VaultItem> = when (filter) {
    ItemsQuickFilter.All -> this
    ItemsQuickFilter.Totp -> filter { !it.totp.isNullOrBlank() }
    ItemsQuickFilter.Passkey -> filter { it.fido2Credentials.isNotEmpty() }
    ItemsQuickFilter.Ssh -> filter { it.type == VaultItemType.SshKey }
    ItemsQuickFilter.Note -> filter { it.type == VaultItemType.SecureNote }
    ItemsQuickFilter.Favorite -> filter { it.favorite }
}
