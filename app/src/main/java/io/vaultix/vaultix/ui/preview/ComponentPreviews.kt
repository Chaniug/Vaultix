/*
 * Vaultix — app:ui:preview
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 本文件是**原创实现**（预览代码），被预览组件的实现溯源见各自源文件。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.vaultix.vaultix.ui.common.EntryCard
import io.vaultix.vaultix.ui.settings.SettingsDivider
import io.vaultix.vaultix.ui.settings.SettingsGroupCard
import io.vaultix.vaultix.ui.settings.SettingsRow
import io.vaultix.vaultix.ui.shell.BottomDockOccupiedHeight
import io.vaultix.vaultix.ui.shell.VaultixBottomDock
import io.vaultix.vaultix.ui.shell.VaultixNavItem
import io.vaultix.vaultix.ui.theme.Spacing

/*
 * ===========================================================================
 * 规格中心 1/3：底部胶囊导航栏 VaultixBottomDock
 * ===========================================================================
 * 这是**全 App 最贵的一处布局**：它的高度（[BottomDockOccupiedHeight]）被 4 个页面的
 * 列表 `contentPadding.bottom` 依赖，而 2026-10-19 之前库列表那里写的是字面量 96dp
 * （小于真实需要）⇒ 最后一条被压住。这张预览就是"这条算术还对不对"的常驻证据。
 *
 * ⚠️ 不要再给它写 `height(48.dp)` 之类的固定高：2.0x 档位就是专门盯这个的。
 */

/** 四 tab 全量、密码页选中（最常规的一帧）。 */
@Composable
private fun DockFourTabs() {
    PreviewCanvas(fontScale = PreviewFontScale.Normal, night = PreviewNight.Light) {
        VaultixBottomDock(
            tabs = ALL_TABS,
            selected = VaultixNavItem.Passwords,
            onSelect = {},
            onAdd = {},
            modifier = Modifier.background(MaterialTheme.colorScheme.background),
        )
    }
}

/** 2.0x：tab 文字放大后是否被裁 / 胶囊是否被撑破。 */
@Composable
private fun DockMaxFont() {
    PreviewCanvas(fontScale = PreviewFontScale.Max, night = PreviewNight.Dark) {
        Column(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
            PreviewSpecLabel(
                text = "2.0x：tab 文字不得裁切；胶囊允许变高，" +
                    "但「+」与 tab 必须仍在一行内居中",
            )
            VaultixBottomDock(
                tabs = ALL_TABS,
                selected = VaultixNavItem.CardWallet,
                onSelect = {},
                onAdd = {},
            )
        }
    }
}

/** 只有 2 个 tab（某些库形态下 `Settings` 不固定到末尾）。 */
@Composable
private fun DockTwoTabs() {
    PreviewCanvas(fontScale = PreviewFontScale.Large, night = PreviewNight.Light) {
        VaultixBottomDock(
            tabs = ALL_TABS.take(2),
            selected = VaultixNavItem.Authenticator,
            onSelect = {},
            onAdd = {},
            modifier = Modifier.background(MaterialTheme.colorScheme.background),
        )
    }
}

/** 让位算术的**可视化**：底栏实际占位 + 最后一条列表项是否露出来。 */
@Composable
private fun DockInsetProof() {
    PreviewCanvas(fontScale = PreviewFontScale.Normal, night = PreviewNight.Light) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = BottomDockOccupiedHeight),
                verticalArrangement = Arrangement.Bottom,
            ) {
                PreviewSpecLabel(
                    text = "↑ 这块留白 = BottomDockOccupiedHeight " +
                        "(${BottomDockOccupiedHeight.value.toInt()}dp)；" +
                        "真机上再加系统手势条 = rememberBottomDockInset()",
                )
                PreviewPlaceholderBlock(height = 56.dp)
            }
            VaultixBottomDock(
                tabs = ALL_TABS,
                selected = VaultixNavItem.Passwords,
                onSelect = {},
                onAdd = {},
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

private val ALL_TABS: List<VaultixNavItem> = listOf(
    VaultixNavItem.Passwords,
    VaultixNavItem.Authenticator,
    VaultixNavItem.CardWallet,
    VaultixNavItem.Settings,
)

@Preview(
    name = "VaultixBottomDock · 1.0x · 浅色",
    widthDp = PREVIEW_WIDTH_DP,
    showBackground = true,
)
@Composable
private fun DockFourTabsPreview() = DockFourTabs()

@Preview(
    name = "VaultixBottomDock · 2.0x · 深色",
    widthDp = PREVIEW_WIDTH_DP,
    uiMode = 0x20,
    showBackground = true,
)
@Composable
private fun DockMaxFontPreview() = DockMaxFont()

@Preview(
    name = "VaultixBottomDock · 1.3x · 两 tab",
    widthDp = PREVIEW_WIDTH_DP,
    showBackground = true,
)
@Composable
private fun DockTwoTabsPreview() = DockTwoTabs()

@Preview(
    name = "VaultixBottomDock · 让位算术",
    widthDp = PREVIEW_WIDTH_DP,
    showBackground = true,
)
@Composable
private fun DockInsetProofPreview() = DockInsetProof()

/*
 * ===========================================================================
 * 规格中心 2/3：条目卡片 EntryCard
 * ===========================================================================
 * 卡片是列表里出现次数最多的东西（一屏 6~8 张），它的圆角 / 内边距 / 描边
 * 任何一样飘了都是"整屏观感变了"。三种状态各一张：常态、选中态、无副标题。
 */

/** 常态：标题 + 用户名。 */
@Composable
private fun CardNormal() {
    PreviewCanvas(fontScale = PreviewFontScale.Normal, night = PreviewNight.Light) {
        PreviewColumn {
            EntryCard(onClick = {}) {
                CardDemoContent(title = "GitHub", subtitle = "octocat@example.com")
            }
            EntryCard(onClick = {}) {
                CardDemoContent(title = "公司 VPN", subtitle = "zhang.wei")
            }
        }
    }
}

/** 选中态：底色换成 `secondaryContainer`（不画 Checkbox，由调用方决定）。 */
@Composable
private fun CardSelected() {
    PreviewCanvas(fontScale = PreviewFontScale.Normal, night = PreviewNight.Dark) {
        PreviewColumn {
            PreviewSpecLabel(text = "上面一张未选中 / 下面一张选中")
            EntryCard(onClick = {}) {
                CardDemoContent(title = "GitHub", subtitle = "octocat@example.com")
            }
            EntryCard(onClick = {}, selected = true) {
                CardDemoContent(title = "公司 VPN", subtitle = "zhang.wei")
            }
        }
    }
}

/** 2.0x：标题与副标题之间的视觉缝是否还在（`EntryCard` KDoc 里那段"行高在骗人"）。 */
@Composable
private fun CardMaxFont() {
    PreviewCanvas(fontScale = PreviewFontScale.Max, night = PreviewNight.Light) {
        PreviewColumn {
            PreviewSpecLabel(text = "2.0x：标题/副标题不得重叠，卡片内边距不得被文字顶破")
            EntryCard(onClick = {}) {
                CardDemoContent(
                    title = "一个相当长的条目名称用来测试换行",
                    subtitle = "very.long.username@some-subdomain.example.com",
                )
            }
        }
    }
}

/** 卡片的**内部排版**样例（真组件里的内容由调用方给，这里只借一个代表性排布）。 */
@Composable
private fun CardDemoContent(title: String, subtitle: String?) {
    Column {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview(
    name = "EntryCard · 1.0x · 浅色",
    widthDp = PREVIEW_WIDTH_DP,
    showBackground = true,
)
@Composable
private fun CardNormalPreview() = CardNormal()

@Preview(
    name = "EntryCard · 1.0x · 深色（含选中态）",
    widthDp = PREVIEW_WIDTH_DP,
    uiMode = 0x20,
    showBackground = true,
)
@Composable
private fun CardSelectedPreview() = CardSelected()

@Preview(
    name = "EntryCard · 2.0x · 浅色",
    widthDp = PREVIEW_WIDTH_DP,
    showBackground = true,
)
@Composable
private fun CardMaxFontPreview() = CardMaxFont()

/*
 * ===========================================================================
 * 规格中心 3/3：设置分组卡 SettingsGroupCard + SettingsRow
 * ===========================================================================
 * 设置页是**行数与形态最杂**的一页（图标 / 开关 / 箭头 / 副标题 / 关态全在一屏），
 * 它的行高（72dp 下限）与卡片圆角（20dp）是另外两页（库管理、关于）的参照。
 */

/** 常规范式：图标 + 标题 + 副标题 + 开关 / 箭头。 */
@Composable
private fun SettingsGroupNormal() {
    PreviewCanvas(fontScale = PreviewFontScale.Normal, night = PreviewNight.Light) {
        PreviewColumn {
            PreviewSpecLabel(text = "行高下限 72dp；组内行间用 SettingsDivider；卡片圆角 20dp")
            SettingsGroupCard {
                SettingsRow(
                    icon = { Icon(Icons.Filled.Password, contentDescription = null) },
                    title = "自动填充",
                    subtitle = "在其它应用的密码框上显示填充提示",
                    trailing = { Switch(checked = true, onCheckedChange = {}) },
                )
                SettingsDivider()
                SettingsRow(
                    icon = { Icon(Icons.Filled.Wifi, contentDescription = null) },
                    title = "仅在 Wi-Fi 下同步",
                    subtitle = null,
                    trailing = { Switch(checked = false, onCheckedChange = {}) },
                )
                SettingsDivider()
                SettingsRow(
                    icon = { Icon(Icons.Filled.Password, contentDescription = null) },
                    title = "关于 Vaultix",
                    subtitle = "版本、许可证、源代码仓库",
                    onClick = {},
                )
            }
            PreviewSpecLabel(text = "关态（enabled = false）：文字与图标同时减淡，且不可点")
            SettingsGroupCard {
                SettingsRow(
                    icon = { Icon(Icons.Filled.Wifi, contentDescription = null) },
                    title = "生物识别解锁",
                    subtitle = "本机未录入指纹，先去系统设置添加",
                    enabled = false,
                    trailing = { Switch(checked = false, onCheckedChange = {}, enabled = false) },
                )
            }
        }
    }
}

/** 2.0x：72dp 是**下限**不是固定高 —— 这一张就是它的证据。 */
@Composable
private fun SettingsGroupMaxFont() {
    PreviewCanvas(fontScale = PreviewFontScale.Max, night = PreviewNight.Dark) {
        PreviewColumn {
            PreviewSpecLabel(
                text = "2.0x：行必须跟着长高（heightIn 不是 height）；" +
                    "副标题不得被截成半个字",
            )
            SettingsGroupCard {
                SettingsRow(
                    icon = { Icon(Icons.Filled.Password, contentDescription = null) },
                    title = "自动锁定",
                    subtitle = "离开应用后多久自动上锁（当前：15 分钟）",
                    trailing = { Switch(checked = true, onCheckedChange = {}) },
                )
                SettingsDivider()
                SettingsRow(
                    icon = { Icon(Icons.Filled.Wifi, contentDescription = null) },
                    title = "剪贴板自动清除",
                    subtitle = "复制密码后 30 秒清空剪贴板",
                    onClick = {},
                )
            }
        }
    }
}

@Preview(
    name = "SettingsRow · 1.0x · 浅色",
    widthDp = PREVIEW_WIDTH_DP,
    showBackground = true,
)
@Composable
private fun SettingsGroupNormalPreview() = SettingsGroupNormal()

@Preview(
    name = "SettingsRow · 2.0x · 深色",
    widthDp = PREVIEW_WIDTH_DP,
    uiMode = 0x20,
    showBackground = true,
)
@Composable
private fun SettingsGroupMaxFontPreview() = SettingsGroupMaxFont()

/*
 * ---------------------------------------------------------------------------
 * 备用：几个小工具组件的预览（不是规格中心，但常被改，顺手给一张）
 * ---------------------------------------------------------------------------
 */

/** 触摸目标对齐：视觉 36dp 的图标按钮，热区必须是 48dp。 */
@Composable
private fun TouchTargetRow() {
    PreviewCanvas(fontScale = PreviewFontScale.Normal, night = PreviewNight.Light) {
        PreviewColumn {
            PreviewSpecLabel(text = "视觉 36dp 的图标，触摸热区 ≥48dp；两者用底色标出来")
            Row(verticalAlignment = Alignment.CenterVertically) { TouchTargetDemo() }
        }
    }
}

/** 把「视觉尺寸」与「热区」画成两层，一眼能看出差多少。 */
@Composable
private fun TouchTargetDemo() {
    Box(
        modifier = Modifier
            .size(TOUCH_TARGET_DP)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .size(ICON_VISUAL_DP)
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHighest,
                    RoundedCornerShape(Spacing.sm),
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private val TOUCH_TARGET_DP = 48.dp
private val ICON_VISUAL_DP = 36.dp

@Preview(
    name = "触摸目标 · 48dp 热区 / 36dp 视觉",
    widthDp = PREVIEW_WIDTH_DP,
    showBackground = true,
)
@Composable
private fun TouchTargetRowPreview() = TouchTargetRow()

/** 分隔线在深色下的可见度（`outlineVariant` 50% 透明度容易被压没）。 */
@Composable
private fun DividerVisibility() {
    PreviewCanvas(fontScale = PreviewFontScale.Normal, night = PreviewNight.Dark) {
        PreviewColumn {
            PreviewSpecLabel(text = "深色下分隔线 = outlineVariant × 0.5，须仍可辨")
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SettingsDivider()
        }
    }
}

@Preview(
    name = "分隔线 · 深色可见度",
    widthDp = PREVIEW_WIDTH_DP,
    uiMode = 0x20,
    showBackground = true,
)
@Composable
private fun DividerVisibilityPreview() = DividerVisibility()
