/*
 * Vaultix — app:ui:totp
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 「验证码页统一的倒计时进度条」移植自 Bastion（GPL-3.0，Copyright 2025 JoyinJoester）的
 * `ui/components/UnifiedProgressBar.kt`：
 *   - 放在**列表之上、顶栏之下**，整页共用**一条**进度条（不再每行一条）；
 *   - 进度 = **本周期已过时间 / 周期**（从 0 涨到 1，然后随周期翻转重置）；
 *   - 剩余 ≤ 5s 时进度色切 `error`，否则 `primary`；轨道 = `surfaceVariant` 50% 透明；
 *   - 右侧固定展示 `"{剩余}s"`（labelLarge / SemiBold），其后是**可选的尾部插槽**
 *     （上游用它放验证器页的「通行密钥」入口，见 main-shell-migration.md §6.1.2）；
 *   - 平滑视觉走绘制层 1s 线性动画（`rememberTotpSmoothProgress`），数据源保持秒级。
 * 本文件为独立实现：用 Material3 的 `LinearProgressIndicator` 承担绘制，保留上述规格；
 * 上游额外的 WAVE 形态与 AppSettings.ProgressBarStyle 开关未搬运（Vaultix 无该设置项）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.totp

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.vaultix.common.TotpGenerator

/** 进度条高度（上游 12dp）。 */
private val BAR_HEIGHT = 12.dp

/** 「即将过期」阈值：剩余 ≤ 该秒数时进度改警示色（上游同值）。 */
private const val EXPIRING_SECONDS = 5

/** 平滑动画时长：与秒级数据源同周期（1s），视觉上恰好匀速。 */
private const val SMOOTH_ANIM_MS = 1_000

/**
 * 验证码页的统一倒计时进度条。
 *
 * @param periodSeconds 当前进度条所依据的周期（取**最快翻转**那条条目的周期）。
 * @param nowSeconds 秒级时钟（与列表共用同一份 tick，避免第二个定时器）。
 * @param trailingContent 尾部插槽（本项目用于「通行密钥」入口按钮）。
 */
@Composable
fun UnifiedTotpProgressBar(
    periodSeconds: Int,
    nowSeconds: Long,
    modifier: Modifier = Modifier,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    val elapsed = TotpGenerator.progress(periodSeconds, nowSeconds)
    val remaining = TotpGenerator.remainingSeconds(periodSeconds, nowSeconds)
    // 周期翻转（1 → 0）时直接 snap，避免动画倒着卷回去（对齐 Bastion 同款处理）。
    val progress = rememberSmoothProgress(elapsed)
    val barColor = if (remaining <= EXPIRING_SECONDS) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.weight(1f).height(BAR_HEIGHT),
            color = barColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        )
        Text(
            text = "${remaining}s",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = barColor,
        )
        trailingContent?.invoke()
    }
}

/**
 * 只给进度条用的「空行占位」——无验证码条目时仍占住进度条那一行的高度，
 * 避免顶栏与空态之间出现高度跳变（上游同样在该场景改走「独立成行的兜底入口」）。
 */
@Composable
fun UnifiedTotpProgressPlaceholder(trailingContent: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.weight(1f))
        trailingContent()
    }
}

/**
 * 平滑进度：对**秒级阶梯值**施加 1s 线性动画补齐视觉（数据源保持 1Hz，省功耗），
 * 翻转时 `snap()`。移植自 Bastion `rememberTotpSmoothProgress`。
 */
@Composable
private fun rememberSmoothProgress(target: Float): Float {
    val clamped = target.coerceIn(0f, 1f)
    var lastTarget by remember { mutableFloatStateOf(clamped) }
    val wrapDetected = clamped < lastTarget - 0.5f
    val animated by animateFloatAsState(
        targetValue = clamped,
        animationSpec = if (wrapDetected) {
            snap()
        } else {
            tween(durationMillis = SMOOTH_ANIM_MS, easing = LinearEasing)
        },
        label = "unified_totp_progress",
    )
    SideEffect { lastTarget = clamped }
    return animated
}
