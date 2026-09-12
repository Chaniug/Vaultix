/*
 * Vaultix — app:ui:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * 条目**能力徽标**（验证码 / 通行密钥 / SSH / 笔记 / 银行卡 …）。
 *
 * 为什么需要：列表行与详情标题行此前对类型只有**纯文字**（或者干脆没有），一屏几十条里
 * 分不清「哪条带验证码、哪条绑了通行密钥」。用户原话：「还有不同颜色的验证码、通行秘钥的显示，
 * 比如 2FA 验证码、通行秘钥。」
 *
 * 配色取自 Bastion 的类型编码（`PasswordDetailScreen` 按类型换容器色；通行密钥徽标用
 * `tertiaryContainer` 胶囊），语义是「**能力**」而不是「类型」——
 * 一条登录条目可以同时带验证码与通行密钥，所以徽标是**可叠加**的。
 */
package io.vaultix.vaultix.ui.common

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 徽标圆角（小圆角，比胶囊更"标签"；与文字行高匹配）。 */
private const val BADGE_CORNER_DP = 6

/**
 * 能力徽标：小圆角 + 容器色低饱和底 + 短文案（通常 2–4 字）。
 *
 * @param text 徽标文案（用现有 `items_filter_*` 等短词，避免另造一串近义文案）。
 * @param tone 配色语义（见 [BadgeTone]）。
 */
@Composable
fun TypeBadge(text: String, tone: BadgeTone, modifier: Modifier = Modifier) {
    val (container, content) = badgeColors(tone)
    Surface(
        color = container,
        shape = RoundedCornerShape(BADGE_CORNER_DP.dp),
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = content,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * 徽标配色（独立函数，避免在主 composable 里堆 `when` 拉高圈复杂度）。
 *
 * 全部取 M3 的 **container/on-container** 配对：容器色是低饱和底，配对的 on-color
 * 保证对比度达标（自己配 `alpha` 的底色在深色主题下很容易读不清）。
 */
@Composable
private fun badgeColors(tone: BadgeTone): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (tone) {
        BadgeTone.PRIMARY -> scheme.primaryContainer to scheme.onPrimaryContainer
        BadgeTone.TERTIARY -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        BadgeTone.SECONDARY -> scheme.secondaryContainer to scheme.onSecondaryContainer
        BadgeTone.NEUTRAL -> scheme.surfaceContainerHighest to scheme.onSurfaceVariant
    }
}
