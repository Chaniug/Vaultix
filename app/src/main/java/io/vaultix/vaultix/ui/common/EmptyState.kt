/*
 * Vaultix — app:ui:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 本文件是**原创实现**。版式依据 Material 3 的空态（empty state）指引：
 * 一屏只给「标题 + 一句解释 + 一个可选动作」，居中、最大宽度受限，未复用任何上游源码。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.vaultix.vaultix.ui.theme.Spacing

/**
 * **空态**：列表/页面没有内容时那一屏的统一样式。
 *
 * ## 为什么必须收敛成一个组件（2026-10-19 审计 §2.6）
 *
 * 改前 6 处空态各自手写，结果如下：
 *
 * | 位置 | 标题字号 | 图标 | 横向边距 | 标题缺失 |
 * |---|---|---|---|---|
 * | `ItemsScreen` | `titleLarge` | 无 | 页面给的 | — |
 * | `TrashScreen` | `titleLarge` | 无 | `Spacing.xxl`(32) | — |
 * | `TotpCodesScreen` | `titleLarge`（可空） | 无 | `Spacing.xl`(24)，只给正文 | 允许 |
 * | `PasskeysScreen` | `titleLarge`（可空） | 无 | `Spacing.xl`(24) | 允许 |
 * | `CardWalletScreen` | **只有正文** | 无 | 无 | **有**（内联 Box，没有标题） |
 * | `VaultListScreen` | `headlineMedium` + 品牌块 | 72dp | **28dp 魔法数** | — |
 *
 * 五种横向边距（0 / 24 / 28 / 32 / 页面给）、两种标题字号，其中卡包那处**完全没有标题**。
 * 用户从「密码」切到「卡包」，看到的不是一个"空列表"，而是一句孤零零的灰字。
 *
 * ⇒ 统一成：**可选图标 → 标题（必有）→ 一句解释（可选）→ 可选动作**，
 * 四级之间用 `Spacing` 标尺固定，正文最大宽度 [MAX_BODY_WIDTH] 防止大屏上拉成一整行。
 *
 * ## 为什么标题**必有**而正文可选
 *
 * 空态首先要回答"这是什么都没发生，还是出错了"，这必须由标题承担。
 * 只有正文时（卡包那处的旧样子）用户分不清"我还没有卡"和"加载失败"。
 *
 * @param title 标题（**必须**）：说清"现在是空的"这件事，如「还没有密码」「回收站是空的」。
 * @param body 一句解释：告诉用户**下一步该做什么**，或说明为什么会空。
 * @param icon 可选图标（放在标题上方，`Dp` 见 [iconSize]）。
 * @param action 可选动作区（通常是一枚 [PrimaryActionButton]）。
 * @param topInset 顶部需要让出的高度（沉浸顶栏 / 叠层顶栏用）。
 * @param bottomInset 底部需要让出的高度（叠层底栏用）。
 * @param modifier 外部修饰。
 * @param iconSize 图标尺寸，默认 48dp —— 比页面里的 72dp 品牌块小一档：
 *   空态图标是**提示**，不该抢过标题。
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null,
    topInset: Dp = 0.dp,
    bottomInset: Dp = 0.dp,
    iconSize: Dp = EMPTY_ICON_SIZE,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            // ⚠️ 横向边距统一 24dp（`Spacing.xl`）：改前最窄 0 / 最宽 32。
            //    取中间档而不是最宽档，是因为大屏上 32dp 的空白会显得"内容被挤在中间"。
            .padding(horizontal = Spacing.xl)
            .padding(top = topInset, bottom = bottomInset),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(iconSize),
            )
            Spacer(Modifier.height(EMPTY_ICON_GAP))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            // 大屏 / 横屏下不让标题拉成一整行 —— 空态的观感靠"紧凑"而不是"铺满"。
            modifier = Modifier.widthIn(max = MAX_BODY_WIDTH),
        )
        if (body != null) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = Spacing.sm)
                    .widthIn(max = MAX_BODY_WIDTH),
            )
        }
        if (action != null) {
            Column(modifier = Modifier.padding(top = Spacing.xl)) { action() }
        }
    }
}

/** 空态图标默认尺寸（48dp）。 */
val EMPTY_ICON_SIZE: Dp = 48.dp

/** 图标与标题之间的间隙。 */
private val EMPTY_ICON_GAP = Spacing.lg

/** 正文 / 标题的最大宽度：320dp ≈ 一行约 18 个汉字，再宽就该换成多行而不是拉满。 */
private val MAX_BODY_WIDTH: Dp = 320.dp
