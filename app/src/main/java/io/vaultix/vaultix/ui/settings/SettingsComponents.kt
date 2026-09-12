/*
 * Vaultix — app:ui · settings
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 设置页的**分组标题 / 设置项卡片**规格移植自 Bastion（GPL-3.0，Copyright 2025 JoyinJoester）
 * 的 `ui/screens/SettingsComponents.kt`：
 *   - 分组标题 `SettingsSection`：`titleSmall` + **primary** 色 + 内边距 (16, 16, 16, 8)，
 *     分组之间留 8dp；可点（角色 Button）；
 *   - 设置项 `SettingsItem`：`Card` + **20dp 圆角** + `surfaceContainerHigh` 55% 透明底、
 *     行内 `heightIn(min = 72.dp)` + (20, 16) 内边距、图标 28dp 直出（**不套圆形底衬**）、
 *     图标与文字间距 18dp、标题 `bodyLarge` + Medium、副标题 `bodyMedium` + onSurfaceVariant、
 *     可点行右侧给 `ChevronRight`；
 *   - 关态（`enabled = false`）：容器 25% 透明、图标/文字 38% 透明（同上游 `SettingsItemWithSwitch`）。
 * 本文件为独立实现（图标仍以 composable 槽位传入，便于各调用点自定义）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 设置项卡片圆角（上游 20dp）。 */
private val SETTINGS_CARD_CORNER = 20.dp

/** 设置项最小高度（上游 72dp）。 */
private val SETTINGS_ROW_MIN_HEIGHT = 72.dp

/** 图标（28dp）与文字之间的间距（上游 18dp）。 */
private val SETTINGS_ICON_GAP = 18.dp

/** 图标槽位尺寸：图标本身多为 24dp，这里给 28dp 的容器（视觉重量对齐上游）。 */
private val SETTINGS_ICON_BOX = 28.dp

/** 关态透明度（上游：容器 0.25 / 内容 0.38）。 */
private const val DISABLED_CONTAINER_ALPHA = 0.25f
private const val DISABLED_CONTENT_ALPHA = 0.38f

/**
 * 设置页共用的行 / 分组标题组件。
 *
 * 抽出来是为了让**设置首页**与**二级设置页**（自动填充页）渲染完全一致——
 * 对齐 Bastion「设置 → 自动填充」的嵌套结构，避免两层页面各写一套样式后逐渐漂移。
 */

/** 分组标题（小号主色，左侧缩进；可点）。 */
@Composable
internal fun SettingsGroupTitle(title: String, onClick: (() -> Unit)? = null) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .then(
                    if (onClick != null) {
                        Modifier.clickable(onClick = onClick, role = Role.Button)
                    } else {
                        Modifier
                    },
                )
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp),
        )
    }
}

/**
 * 单行设置项（Bastion `SettingsItem` 规格）。
 *
 * @param trailing 右侧控件（Switch / 值文本等）；为空且 [onClick] 非空时右侧给箭头。
 * @param enabled false = 关态（整行变淡，且不响应点击）。
 */
@Composable
internal fun SettingsRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    titleColor: Color? = null,
    enabled: Boolean = true,
    showSubtitle: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val container = if (enabled) {
        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = DISABLED_CONTAINER_ALPHA)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .then(
                if (onClick != null && enabled) {
                    Modifier.clickable(onClick = onClick, role = Role.Button)
                } else {
                    Modifier
                },
            ),
        shape = RoundedCornerShape(SETTINGS_CARD_CORNER),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = SETTINGS_ROW_MIN_HEIGHT)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 图标直出（不套圆形底衬）：主色由 LocalContentColor 供给，
            // 调用点传 `Icon(vector, contentDescription = null)` 即自动上主色。
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.primary.copy(
                    alpha = if (enabled) 1f else DISABLED_CONTENT_ALPHA,
                ),
            ) {
                Box(
                    modifier = Modifier.size(SETTINGS_ICON_BOX),
                    contentAlignment = Alignment.Center,
                ) {
                    icon()
                }
            }

            Spacer(Modifier.width(SETTINGS_ICON_GAP))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = titleColor ?: LocalContentColor.current.copy(
                        alpha = if (enabled) 1f else DISABLED_CONTENT_ALPHA,
                    ),
                )
                if (showSubtitle && subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = if (enabled) 1f else DISABLED_CONTENT_ALPHA,
                        ),
                    )
                }
            }

            if (trailing != null) {
                trailing()
            } else if (onClick != null) {
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 分组之间的分隔留白（上游 `SettingsSection` 尾部 8dp）。 */
@Composable
internal fun SettingsGroupSpacing() {
    Spacer(Modifier.height(8.dp))
}
