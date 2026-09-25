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
 *
 * ⚠️ 2026-09-16 **修正上游的两处观感缺陷**（上游规格本身有问题，不该照抄）：
 *   1. 底色 `surfaceContainerHigh.copy(alpha = 0.55f)` —— **同色加透明**。
 *      这在浅色下泛灰、深色下泛脏，且"卡片与背景的关系"全靠一个手调的 alpha 值，
 *      换主题 / 开动态取色就失准（`.ai/conventions/8.4-UI·观感.md`：
 *      容器必须走 Lowest < Low < Base < High 的**色阶**，不能用同色 + alpha 伪装一层）。
 *      ⇒ 改为 `surfaceContainerLow`。
 *   2. **每一行各自是一张卡片**。18 行 = 18 张漂浮的小"药丸"，页面被切成一堆碎片，
 *      读不出"这几行是一组" ⇒ 改为**一组一张卡片**，行间用 [SettingsDivider] 分隔
 *      （M3 设置页的标准形态）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.vaultix.vaultix.ui.theme.Spacing

/** 设置项卡片圆角（上游 20dp）。 */
private val SETTINGS_CARD_CORNER = 20.dp

/** 设置项最小高度（上游 72dp）。 */
private val SETTINGS_ROW_MIN_HEIGHT = 72.dp

/** 图标（28dp）与文字之间的间距（上游 18dp）。 */
private val SETTINGS_ICON_GAP = 18.dp

/** 行内的水平内边距（上游 20dp）。 */
private val SETTINGS_ROW_PADDING_H = 20.dp

/** 图标槽位尺寸：图标本身多为 24dp，这里给 28dp 的容器（视觉重量对齐上游）。 */
private val SETTINGS_ICON_BOX = 28.dp

/** 组内分隔线的起始缩进：对齐到文字左边缘（行内边距 + 图标槽 + 图标间距）。 */
private val SETTINGS_DIVIDER_INSET =
    SETTINGS_ROW_PADDING_H + SETTINGS_ICON_BOX + SETTINGS_ICON_GAP

/** 关态内容透明度（上游 0.38）。 */
private const val DISABLED_CONTENT_ALPHA = 0.38f

/**
 * 副标题最大行数。
 *
 * 取 2 而不是 1：副标题的价值就在于「说清后果」（见 `.ai/issues/06-界面与交互.md` #128
 * 那批"缺代价说明"的文案），砍到 1 行会把刚补上的信息又截掉。
 * 但也不能不限 —— 不限时长副标题（如 `setting_manual_fill_tile_desc` 有 60+ 字）
 * 在窄屏会撑高整行，让同组卡片里各行高度参差不齐，"一组一行"的分组感被破坏。
 */
private const val SETTINGS_SUBTITLE_MAX_LINES = 2

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
                .padding(start = Spacing.lg, top = Spacing.lg, end = Spacing.lg, bottom = Spacing.sm),
        )
    }
}

/**
 * 单行设置项（Bastion `SettingsItem` 规格）。
 *
 * @param trailing 右侧控件（Switch / 值文本等）；为空且 [onClick] 非空时右侧给箭头。
 * @param enabled false = 关态（文字与图标变淡，且不响应点击）。
 *
 * ⚠️ 本组件**只是行**，不再自带卡片外壳：请把它放进 [SettingsGroupCard]，
 * 相邻两行之间插 [SettingsDivider]（见 [SettingsGroupCard] 的取舍说明）。
 *
 * ⚠️ 2026-09-26 删除了 `showSubtitle` 参数：它自引入起**全项目无任何调用点传值**
 * （默认 `true`，各行都走这条默认路径），属冗余 API。删掉后本函数参数由 8 降到 7，
 * 顺带消掉一处既存的 `LongParameterList` 违例（detekt 阈值 8 是「参数个数 ≥ 8 即报」，
 * 即**实际上限是 7 个**，不是 8 个 —— 详见 `.ai/ISSUES.md` #130 的勘误）。
 * 真要"整行不显示副标题"时，传 `subtitle = null` 即可，语义比布尔开关更直接。
 */
@Composable
internal fun SettingsRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    titleColor: Color? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = SETTINGS_ROW_MIN_HEIGHT)
            // ⚠️ `clickable` 必须排在 `padding` **之前**：否则水波纹只覆盖内边距以内的
            // 那一小块，整行看上去"只有中间能点"（改成分组卡片后这一点尤其明显——
            // 点击热区小了，会让相邻行的边界变得可疑）。
            .then(
                if (onClick != null && enabled) {
                    Modifier.clickable(onClick = onClick, role = Role.Button)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = SETTINGS_ROW_PADDING_H, vertical = Spacing.md),
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
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = SETTINGS_SUBTITLE_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = if (enabled) 1f else DISABLED_CONTENT_ALPHA,
                    ),
                )
            }
        }

        if (trailing != null) {
            trailing()
        } else if (onClick != null) {
            Spacer(Modifier.width(Spacing.sm))
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 一组的**整张卡片**：组内各行同处一张 `surfaceContainerLow` 卡片，行间用 [SettingsDivider] 分隔。
 *
 * 这是本轮（2026-09-16）观感改造的核心，理由见文件头的「修正上游的两处观感缺陷」。
 * 简言之：一组一张卡 = 分组边界一眼可见；一行一张卡 = 满屏碎片。
 */
@Composable
internal fun SettingsGroupCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        shape = RoundedCornerShape(SETTINGS_CARD_CORNER),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.xs),
            content = content,
        )
    }
}

/** 组内相邻两行之间的分隔线（起点缩进到文字左边缘，与文字列对齐）。 */
@Composable
internal fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = SETTINGS_DIVIDER_INSET),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

/**
 * 设置页的布尔开关：**数据未到达时不猜状态**。
 *
 * ## 为什么不能写 `value ?: false`
 *
 * `null` 的语义是「偏好还没从磁盘读出来」（`.ai/ISSUES.md` #84「三种空」），
 * 而 `false` 是一个**确定的答案**。把前者渲染成后者，就是拿假答案冒充事实 ——
 * 2026-09-16 真机报告：冷启动后**快速**进设置页，「动态取色」开关先从「关」
 * 跳到「开」，看上去像在闪烁。
 *
 * ⚠️ 反过来的写法（`?: true`）同样错，只是把方向倒过来 —— 那正是 2026-09-14
 * 「防截屏先开后关」的成因。**两个方向都试过了，都错**：问题不在于默认值该取什么，
 * 而在于**不该在不知道的时候给答案**。
 *
 * ⇒ **不认识就什么都不说**：用同尺寸占位撑住布局，开关等数据到了再出现。
 *   「短暂没有」比「短暂错」诚实。
 *
 * ## 占位为什么也要可点（2026-09-26 修正）
 *
 * 原实现占位是一个纯 [Spacer]，**不可点**。这造成一个讨厌的交互断层：数据没到的那
 * 几百毫秒里，用户手指已经落在开关位置上，点击却被吞掉 —— 于是在用户看来，
 * 「这个开关按了没反应（有时）」。这比"开关晚出现一会儿"更难理解。
 *
 * 修正为：占位期依然接收点击，语义是**「打开」**（`onCheckedChange(true)`）。
 *
 * ### 为什么是「打开」而不是「取反」
 *
 * 因为 `value == null` 时**根本没有"当前值"可以取反** —— 取反需要先知道现在是开还是关，
 * 而那正是此刻不知道的东西。硬要"取反"，只能拿上一次会话的旧值来猜，等于把
 * 一个未定状态偷偷替换成一个可能过期的确定值，又回到了本篇开头反对的老路。
 *
 * 而「打开」在**所有**情形下都是安全的：
 * - 若该偏好本来就是开 → 点一下传 `true`，是幂等的，最终仍是开；
 * - 若本来就是关 → 点一下传 `true`，正是用户"我要打开它"的意图。
 *
 * 反方向的「关闭」则不安全：对本来就是开的偏好，用户看到空占位、点一下，结果偏好被
 * 关掉了，而他从头到尾没见过"开"的样子 —— 这是"我什么时候关的？"的来源。
 *
 * ⚠️ 已知代价：用户想关掉一个"本来是开"的开关，且恰好在 `null` 窗口内点击 ——
 * 那一下会变成"确认打开"而非关闭，他需要再点一次。这个窗口是**订阅建立的那一瞬**
 * （DataStore 首次 emit 极快），比"点击石沉大海"罕见得多，且用户再点一次即可纠正。
 */
@Composable
internal fun SettingsSwitch(
    value: Boolean?,
    onCheckedChange: (Boolean) -> Unit,
) {
    if (value != null) {
        Switch(checked = value, onCheckedChange = onCheckedChange)
    } else {
        // 占位：尺寸对齐 M3 Switch 的视觉宽度（track 52×32dp），避免数据到达时整行跳动。
        // 刻意**不用禁用态开关** —— 那个会被读成「关」，正是要避免的那个假答案。
        // 点击仍然接收（见上方 KDoc）：语义固定为「打开」，避免"按了没反应"。
        Box(
            modifier = Modifier
                .size(SWITCH_VISUAL_WIDTH, SWITCH_VISUAL_HEIGHT)
                .clickable { onCheckedChange(true) },
        )
    }
}

/** M3 `Switch` 的视觉尺寸（track 大小）。占位用它撑住布局，避免数据到达时跳动。 */
private val SWITCH_VISUAL_WIDTH = 52.dp
private val SWITCH_VISUAL_HEIGHT = 32.dp
