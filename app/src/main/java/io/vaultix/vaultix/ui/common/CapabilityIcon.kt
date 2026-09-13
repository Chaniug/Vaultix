/*
 * Vaultix — app:ui:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.common

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** 行尾能力图标的尺寸（刻意克制：它只负责「一眼看出有没有」，不负责解释）。 */
val CapabilityIconSize = 16.dp

/**
 * 条目「能力标志」小图标 —— 2FA 验证码 / 通行密钥。
 *
 * ⚠️ 2026-09-13 用户反馈：「密码条目上显示的验证码和通行密钥能改成小图标显示吗，
 * 尽量小一点。目前的文字显得很突兀。」⇒ 由 [TypeBadge] 的文字胶囊改为 **16dp 单色图标**。
 *
 * 为什么不干脆不显示：这两项决定用户能不能**跳过 App** 直接用验证码登录 / 免密登录，
 * 是挑选条目时的决策依据，不能只在详情页里说。
 *
 * 为什么不再用文字胶囊：行尾已经有「云同步」「收藏星标」两枚图标，再塞两个文字胶囊，
 * 一行里出现三种不同的标记语言，视觉噪声很大。
 *
 * 文字没有丢 —— 它进了 `contentDescription`，读屏仍然念得出「验证码」「通行密钥」。
 *
 * @param icon 语义矢量（单色，靠 [tint] 上语义色）。
 * @param tint 语义色（验证码 = primary，通行密钥 = tertiary，与筛选维度一致）。
 * @param contentDescription 无障碍描述（= 该能力的文字名）。
 */
@Composable
fun CapabilityIcon(
    icon: ImageVector,
    tint: Color,
    contentDescription: String,
) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = tint,
        modifier = Modifier.size(CapabilityIconSize),
    )
}
