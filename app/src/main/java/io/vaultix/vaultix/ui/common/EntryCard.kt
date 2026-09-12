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
 * 条目**卡片外框**的规格移植自 Bastion（GPL-3.0，Copyright 2025 JoyinJoester）的
 * `ui/password/PasswordEntryCard.kt`：
 *   - 容器用 Material3 `Card`，且**沿用默认的 `CardDefaults.cardColors()` /
 *     `cardElevation()`**（上游原文即如此：底色与高度交给 M3 的 filled-card token，
 *     不自己拍一个 surface 颜色）；
 *   - 圆角 `RoundedCornerShape(12.dp)`（上游「稀疏列表卡片」取值；仅单卡态用 16dp）；
 *   - 内边距 16dp 四边等宽（上游 `padding(if (isSingleCard) 20.dp else 16.dp)`）；
 *   - 点击：先 `clip(shape)` 再 `clickable`，水波纹被裁进圆角内（上游同款写法）；
 *   - 标题字重 SemiBold、标题与副标题之间 `spacedBy(6.dp)`（上游列表态取值）。
 * 本文件为独立实现，不含其代码。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/** 卡片圆角（Bastion 列表态 = 12dp）。 */
private val CARD_CORNER = 12.dp

/** 卡片内边距（Bastion 列表态 = 16dp 四边等宽）。 */
private val CARD_PADDING = 16.dp

/**
 * 条目卡片外框 —— 密码 / 验证码 / 卡包三个列表共用（保证三处观感完全一致）。
 *
 * ## 为什么需要一个共用组件（而不是各页各写一个 Surface）
 * 三个列表此前都是 `Surface(color = colorScheme.surface, shape = shapes.large)`：
 * **底色与页面背景同色、且没有任何高度** ⇒ 卡片边界几乎看不见，条目看起来只是
 * 「悬浮在背景上的一行字」。用户反馈的「密码条目外框不够精致」即指此处。
 *
 * 改成本组件后：
 * - 底色/高度随主题（含动态取色）自动分层 —— 这正是 M3 filled card 的语义；
 * - 三处列表自动统一，不会再出现「密码页是卡片、卡包页是裸行」这种不一致
 *   （卡包此前**完全没有**外框，只有 `clickable` 的 Row）。
 *
 * ⚠️ 这与「搬 Bastion 的壳」无关：壳（底部导航 / Tab 容器 / 转场）已迁移完毕，
 * 本条属于**组件级视觉规格**。
 *
 * @param onClick 点击条目（水波纹被裁进圆角内）。
 * @param content 卡片内容（纵向排列；需要横排时在里面再放一个 `Row` 即可）。
 */
@Composable
fun EntryCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(CARD_CORNER)
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(),
        elevation = CardDefaults.cardElevation(),
        shape = shape,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .clickable(onClick = onClick)
                .padding(CARD_PADDING),
            content = content,
        )
    }
}

/** 卡片内「标题 + 副标题」的纵向间距（Bastion 列表态 = 6dp）。 */
val EntryCardTextSpacing = 6.dp

/** 卡片内左侧图标与文本区的间距（Bastion = 16dp）。 */
val EntryCardIconSpacing = 16.dp

/** 卡片内左侧图标尺寸（Bastion = 40dp）。 */
val EntryCardIconSize = 40.dp
