/*
 * Vaultix — app:ui · onboarding
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 本文件是**原创实现**（预览代码），被预览组件的实现溯源见同目录源文件。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.vaultix.vaultix.ui.preview.PREVIEW_WIDTH_DP
import io.vaultix.vaultix.ui.preview.PreviewCanvas
import io.vaultix.vaultix.ui.preview.PreviewFontScale
import io.vaultix.vaultix.ui.preview.PreviewNight
import io.vaultix.vaultix.ui.theme.Spacing

/**
 * 三张插画的**对照预览**（2026-10-24）。
 *
 * ## 为什么必须在同一张预览里同时画出浅色与深色
 *
 * 这些图是代码画的，**没有设计稿可对**。而最容易出错、又最不会报错的两种情形，
 * 只有并排看才现形：
 *
 * 1. **深浅色下某条线消失**：第 2 页的右卡片底板用 `surfaceVariant`，在深色里接近背景；
 *    如果上面再画一条接近背景色的描边，整块就"糊"成一片 —— 单看浅色完全看不出来；
 * 2. **三张图的视觉重量不一致**：盾牌、双卡、页面框要靠并排才能判断谁大谁小、谁太细。
 *
 * ⚠️ 用**两个 `PreviewCanvas`**（浅 / 深）而不是 `@Preview(uiMode = NIGHT)` 叠在同一个
 * 函数上：后者会被 `PreviewCanvas` 里显式传入的 `VaultixTheme(themeMode = …)` 覆盖
 * ⇒ 深色那格其实还是浅色，是个**假绿预览**。显式画两遍才是真的看到两套。
 *
 * 字体缩放只取 `Normal`：插画不含文字，放大字号不影响它 —— 这里看的是**图形比例**。
 */
@Preview(name = "插画对照 · 浅 / 深", widthDp = PREVIEW_WIDTH_DP, showBackground = true)
@Composable
private fun OnboardingArtworkPreview() {
    Column {
        ArtworkRow(caption = "浅色", night = PreviewNight.Light)
        ArtworkRow(caption = "深色", night = PreviewNight.Dark)
    }
}

@Composable
private fun ArtworkRow(caption: String, night: PreviewNight) {
    PreviewCanvas(fontScale = PreviewFontScale.Normal, night = night) {
        Column(modifier = Modifier.fillMaxWidth().padding(Spacing.xs)) {
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                OnboardingArtwork.entries.forEach { artwork ->
                    OnboardingArtworkImage(artwork = artwork)
                }
            }
        }
    }
}
