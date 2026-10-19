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
 * 本文件是**原创实现**。数值取自 Material Design 3 的 shape scale（extra/small/medium/large
 * 四档圆角），未复用任何上游源码。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * 全项目**圆角的三个语义档位**。
 *
 * ## 为什么要有这个文件（2026-10-19 审计 §2.4）
 *
 * 改前全项目有 **6 种**圆角取值，而且都写成字面量或本地私有常量：
 *
 * | 值 | 出现处 | 语义 |
 * |---|---|---|
 * | 6dp | 徽章 `TypeBadge` | 小标签 |
 * | 12dp | `Theme.extraSmall`（输入框）、`CloudAccountsScreen` 的提示条、`SettingsScreen` 的日志框 | ? |
 * | 16dp | `EntryCard`、`ItemDetailScreen`、`VaultixBottomDock` 的 tab、`VaultListScreen` 的库卡 | ? |
 * | 20dp | `SettingsGroupCard`、`AddVaultTypeDialog`、`ItemFormDialog` 的分组卡 | ? |
 * | 28dp | `DialogShell`、`SearchTopAppBar`、`AppPickerDialog`、`PermissionsScreen`、
 * `AutofillSettingsScreen` | 大面 / 对话框 |
 * | 50% | 胶囊底栏、搜索栏 | 药丸 |
 *
 * 问题不是"值太多" —— M3 本身就有四档。问题是 **12/16/20 三档没有语义边界**：
 * 同一个"卡片"，`EntryCard` 用 16、`SettingsGroupCard` 用 20、`AddKdbxScreen` 的
 * KeyFile 卡用 12；同一个"提示条"，云账号页用 12、库列表页用 `Spacing.lg`(16)。
 * 于是同屏并置时会读成"两套规格"。
 *
 * ## 三档的边界（**按尺寸分，不按组件分**）
 *
 * | 档位 | 值 | 用在哪 |
 * |---|---|---|
 * | [SmallShape] | **12dp** | 高度 < 64dp 的**小面**：徽章、提示条、日志框、可选卡片 |
 * | [PanelShape] | **16dp** | 高度 ≥ 64dp 的**大面**：条目卡、设置分组卡、底部 tab pill、库卡 |
 * | [PillShape] | **50%** | 两端半圆的**药丸**：底线胶囊、搜索栏 |
 *
 * 判定规则一句话：**看它有多高**。小面用大圆角会像"半个球"，大面用小圆角会显方。
 *
 * ⚠️ 20dp 与 28dp **不再进入"面"的档位**：
 * - 20dp 的用法（设置分组卡 / 类型对话框 / 表单分组卡）全部归到 [PanelShape] 16dp ——
 *   它们和 `EntryCard` 是同一种东西，不该有两种圆角；
 * - 28dp 只留给 **M3 对话框**（`Dialog(minWidth/maxWidth)` 那类真正的系统容器，
 *   以及 `SearchTopAppBar` 的展开搜索框）—— 那是"更大的面"，与列表里的卡不同层级，
 *   继续走各组件自己的常量，不进本文件的三档。
 *
 * ⚠️ 输入框（`MaterialTheme.shapes.extraSmall` = 12dp）**不**归本文件管：
 * 它是"控件"不是"面"，由 `VaultixShapes` 统一供给（见 `Theme.kt` 的推导）。
 * 12dp 同时出现在两处是巧合，不是同一个语义 —— 别为了"统一"把输入框改成 16。
 */
object VaultixCorner {
    /** 小面（高度 < 64dp）：徽章、提示条、日志框、表单里的可选卡片。12dp。 */
    val Small = RoundedCornerShape(12.dp)

    /** 大面（高度 ≥ 64dp）：条目卡、设置分组卡、底部 tab pill、库卡。16dp。 */
    val Panel = RoundedCornerShape(16.dp)

    /** 药丸：两端完全半圆（百分比，不是 dp）。 */
    val Pill = RoundedCornerShape(percent = 50)
}
