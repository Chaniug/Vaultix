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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import io.vaultix.vaultix.ui.theme.Spacing

/** 行尾能力图标的尺寸（刻意克制：它只负责「一眼看出有没有」，不负责解释）。 */
val CapabilityIconSize = Spacing.lg

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

/**
 * 能力图标 / 徽标的**唯一配色出处**。
 *
 * ## 为什么要有这个对象（2026-09-20）
 * 用户提问：「material3 设计上，我这个页面是否还要稍微加一点强调色之类的，
 * 感觉目前界面的配色略微单一了。」
 *
 * 排查后的结论是：**不是强调色不够，是强调色没有语义**。
 * 具体查到的两处问题，都是「同一个概念在不同页面用了不同角色」——
 * 这种不一致既让配色看起来随意，也让"再补点颜色"变成无从下手的模糊需求：
 *
 * | 概念 | 列表页 | 详情页 | 现在统一为 |
 * |---|---|---|---|
 * | 通行密钥 | `tertiary` | `tertiary` | ✅ 本来就一致 |
 * | 收藏星标 | `primary` | `primary` | ✅ 本来就一致 |
 * | 条目类型（非登录） | `primary` 正文色 | `surfaceContainerHighest` 徽标 | 见 [TypeBadge] |
 *
 * 更要紧的是**只有两处硬编码的颜色值**（`ItemsScreen` 与 `ItemDetailScreen` 各写一遍
 * `tint = MaterialTheme.colorScheme.tertiary`）。一旦想调整通行密钥的色相，
 * 就得记得改两个地方 —— 忘一处就又制造出一组不一致。
 * 收进这里之后，「验证码 = primary、通行密钥 = tertiary」成为**声明**，
 * 调用点只表达意图，不再各自挑色。
 *
 * ⚠️ 角色选择依据（M3 官方对 tertiary 的定义）：tertiary 用于「与 primary/secondary
 * 形成对比的强调色，尤其适合徽标、贴纸、特殊操作元素」。通行密钥正是这种
 * 「需要跳出主色系、但又不是危险操作」的能力标记 ⇒ tertiary 是对的，
 * **不要**因为"想多几种颜色"就把它改成 secondary 或 error。
 * ```
 * primary   → 验证码（与全局主操作同色系，最常用、最中性）
 * tertiary  → 通行密钥（生物识别/免密，语义上"另一种登录方式"）
 * ```
 */
@Composable
fun capabilityTint(capability: Capability): Color = when (capability) {
    Capability.TOTP -> MaterialTheme.colorScheme.primary
    Capability.PASSKEY -> MaterialTheme.colorScheme.tertiary
}

/** [capabilityTint] 的语义枚举（避免调用点传裸色值）。 */
enum class Capability {
    /** 2FA 验证码（TOTP）。 */
    TOTP,

    /** 通行密钥（passkey / FIDO2）。 */
    PASSKEY,
}
