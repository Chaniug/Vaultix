/*
 * Vaultix — app:ui:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * 徽标配色语义（配合 [TypeBadge] 使用）。
 *
 * 单独成文件而不是与 [TypeBadge] 放一起：detekt 的 `MatchingDeclarationName` 会要求
 * 「文件里唯一的类形声明」与文件名同名 —— 放一起时那个唯一的类形声明是 `BadgeTone`，
 * 于是文件名 `TypeBadge.kt` 反而被判违规。
 */
package io.vaultix.vaultix.ui.common

/** 徽标配色语义。 */
enum class BadgeTone {
    /** 验证码（TOTP）。 */
    PRIMARY,

    /** 通行密钥。 */
    TERTIARY,

    /** SSH 密钥 / 次要能力。 */
    SECONDARY,

    /** 中性（笔记、其他类型说明）。 */
    NEUTRAL,
}
