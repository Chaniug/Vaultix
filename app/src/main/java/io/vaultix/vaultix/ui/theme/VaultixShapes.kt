/*
 * Vaultix — app:ui:theme
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 本文件是**原创实现**。数值取自 Material Design 3 的 shape scale，未复用任何上游源码。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes

/**
 * M3 的 `Shapes` 供给。
 *
 * ⚠️ 2026-10-19：**新增 `medium` 档**。改前只覆盖了 `extraSmall`（输入框），于是
 * 6 处**不传 `shape`** 的 `Card` 吃的是 M3 基线 12dp —— 一个既不在代码里、也不在主题里、
 * 只存在于 M3 默认值里的圆角（审计 §2.4 的"幽灵圆角"）：
 *
 * ```
 * VaultListScreen:496 · TrashScreen:204 · ImportExportScreen:424,454 · ConversionScreen:573,658
 * ```
 *
 * 现在 `medium` = [VaultixCorner.Panel]（16dp），这 6 处会自动变成与 `EntryCard` 一致；
 * 以后谁再写 `Card { }` 不传 shape，也落在同一个值上，不会再出现"我以为它跟别处一样"。
 */
val VaultixShapes = Shapes(
    extraSmall = RoundedCornerShape(Spacing.md),
    medium = VaultixCorner.Panel,
)
