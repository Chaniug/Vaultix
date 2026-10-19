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
 * 本文件是**原创实现**。设计依据为 Android 官方文档 `@Preview`（compose.ui.tooling.preview）
 * 与 Material 3 的无障碍/字体缩放指引，未复用任何上游源码。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.vaultix.vaultix.ui.theme.Spacing
import io.vaultix.vaultix.ui.theme.ThemeMode
import io.vaultix.vaultix.ui.theme.VaultixTheme

/**
 * 预览用的**字体缩放**档位。
 *
 * ## 为什么预览必须带字体缩放（这不是"顺便加一张"）
 *
 * 2026-10-19 审计抓到两处**只在放大字体时现形**的问题：
 * `VaultixBottomDock` 的 tab 用 `height(48.dp)` 固定高（2.0x 下文字被裁半截）、
 * `PasswordGeneratorDialog` 的开关行 `height(40.dp)` 同理。它们在 1.0x 下**完全正常**，
 * 而项目此前 **0 条 `@Preview`**，验证只能靠"装包 → 真机 → 系统设置里把字号调到最大"，
 * 于是这类问题只能等用户报。
 *
 * ⇒ 规格中心的预览**一律三档**：1.0 / 1.3 / 2.0。1.3 是"看得出的轻微放大"，
 * 2.0 是 Android 允许的**上限**（设置 → 显示 → 字体大小，最右档）。
 * 2.0 不裁切、不重叠，中间档就是安全的。
 */
enum class PreviewFontScale(val scale: Float, val label: String) {
    Normal(FONT_SCALE_NORMAL, "1.0x"),
    Large(FONT_SCALE_LARGE, "1.3x"),
    Max(FONT_SCALE_MAX, "2.0x"),
}

/** 1.0x —— 系统默认字号。 */
private const val FONT_SCALE_NORMAL = 1f

/** 1.3x —— "看得出的轻微放大"档。 */
private const val FONT_SCALE_LARGE = 1.3f

/** 2.0x —— Android「字体大小」滑块的最右档，也是本项目的验收上限。 */
private const val FONT_SCALE_MAX = 2f

/** 预览用的明暗档位。 */
enum class PreviewNight(val label: String) {
    Light("浅色"),
    Dark("深色"),
    ;

    /** 给 `@Preview(uiMode = …)` 用的值。 */
    val uiMode: Int
        get() = if (this == Dark) UI_MODE_NIGHT_YES else UI_MODE_NIGHT_NO

    /** 对应到项目的主题模式（`VaultixTheme` 的入参）。 */
    internal fun toThemeMode(): ThemeMode =
        if (this == Dark) ThemeMode.DARK else ThemeMode.LIGHT
}

private const val UI_MODE_NIGHT_NO = 0x10
private const val UI_MODE_NIGHT_YES = 0x20

/**
 * 预览画布宽度（`@Preview(widthDp = …)`）。
 *
 * 取 360dp：Android 手机最常见的宽度档（`sw360dp`），项目里所有"贴边 / 让位"的算术
 * 也都是按它推的（见 [Spacing] 与审计报告 §2）。高度**不设**，让组件按内容自适应 ——
 * 固定高会把"内容被裁掉"伪装成"布局正常"。
 */
const val PREVIEW_WIDTH_DP: Int = 360

/**
 * 规格中心预览的**统一入口**：套好主题 + 强制字体缩放 + 铺底色。
 *
 * 写 `@Preview` 时不要各自 `VaultixTheme { }` —— 那样会漏掉字体缩放。
 * 一律走这里：一个 [Density] 覆盖把 [fontScale] 施加到**整棵子树**。
 *
 * ⚠️ 用 `LocalDensity` 覆盖而不是给每个 `Text` 描 `TextStyle`：字体缩放影响的是
 * 整棵子树的 dp↔sp 换算（含 M3 组件内部的固定尺寸）。只改 `TextStyle` 会绕过
 * "组件自带的 48dp 底线"，预览就成了假绿。
 *
 * ⚠️ 关掉 `dynamicColor`：动态取色的结果取决于**预览机器当前的壁纸**，
 * 换个环境预览就变色 ⇒ 拿来对观感没有意义。固定色板才能比较"这次和上次哪里不一样"。
 */
@Composable
fun PreviewCanvas(
    fontScale: PreviewFontScale,
    night: PreviewNight,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val base = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density = base.density, fontScale = fontScale.scale),
    ) {
        VaultixTheme(themeMode = night.toThemeMode(), dynamicColor = false) {
            Surface(modifier = modifier, color = MaterialTheme.colorScheme.background) {
                content()
            }
        }
    }
}

/**
 * 预览里的「页面底色 + 内容列」。
 *
 * 为什么不做成 `Screen(content)` 这种"长得像真页面"的壳：预览的价值是**看组件自己**，
 * 套一层假 scaffold 反而让人误判（把壳给的 padding 当成页面给的）。
 * 这里只给两样真东西：背景色（深色预览里透明区域看不出对比）+ 一个可调的横向边距。
 */
@Composable
fun PreviewColumn(
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = Spacing.lg,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(Spacing.sm),
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = Spacing.md),
        verticalArrangement = verticalArrangement,
    ) {
        // ⚠️ 不能写 `content = content`：`Column` 的内容槽是 `ColumnScope.() -> Unit`，
        //    这里的 `content` 是 `() -> Unit`，赋值会报 "actual type is
        //    ComposableFunction0<Unit>, but ComposableFunction1<ColumnScope, Unit> was expected"。
        content()
    }
}

/**
 * 预览里的**规格标注条**：一行小字，用来在截图里标出实测值。
 *
 * 用途是让**校验的人**（而不只是写的人）能对着截图核数：例如标上
 * 「触摸目标 48dp（视觉 36dp）」之后，谁看预览都能判断这一条还对不对。
 */
@Composable
fun PreviewSpecLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Start,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
    )
}

/**
 * 预览里表示「这里是空的」的浅色块（只用于撑布局，**不是**真组件）。
 *
 * 用 `height` 参数而不是 `size(h)`：`size` 只有一个值时会同时约束宽度，
 * 放进 `Row` 里会把占位挤成正方形，和真实内容的对比失真。
 */
@Composable
fun PreviewPlaceholderBlock(height: Dp = 48.dp, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 0.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(Spacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = "占位 ${height.value.toInt()}dp",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 预览命名约定：**图名 = 组件名 · 字体档位 · 明暗**。
 *
 * 几十张预览图混在一个面板里时，只能靠图名辨认 —— 所以三样都得在名字里。
 */
fun previewName(component: String, fontScale: PreviewFontScale, night: PreviewNight): String =
    "$component · ${fontScale.label} · ${night.label}"

/*
 * ---------------------------------------------------------------------------
 * 自检预览：确认上面这套壳**自己**是对的
 * ---------------------------------------------------------------------------
 * 这三张图是"测量尺的零刻度"：如果 `PreviewCanvas` 的字体缩放没生效，
 * 这里的方块**不会**随档位变大 —— 那就说明下面所有规格中心的 2.0x 预览都是假的。
 * 反过来，只要这一张对，别处的 `fontScale` 就一定能信。
 */

/** 字体缩放是否真的透传：方块边长固定 48dp，方框内的文字应随档位变大。 */
@Composable
private fun PreviewCanvasSelfCheck(fontScale: PreviewFontScale, night: PreviewNight) {
    PreviewCanvas(fontScale = fontScale, night = night) {
        PreviewColumn {
            PreviewSpecLabel(
                text = "自检：下面这行字的字号应随档位变大；外框恒 48dp 不放大",
            )
            Text(
                text = "字体 ${fontScale.label}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .padding(Spacing.sm)
                    .size(INCHWORM_BOX),
            )
        }
    }
}

/** 自检方框（48dp —— 恰好是 M3 最小触摸尺寸，别改）。 */
private val INCHWORM_BOX = 48.dp

@Preview(
    name = "PreviewCanvas 自检 · 1.0x · 浅色",
    widthDp = PREVIEW_WIDTH_DP,
    showBackground = true,
)
@Composable
private fun PreviewCanvasSelfCheckNormalLight() {
    PreviewCanvasSelfCheck(PreviewFontScale.Normal, PreviewNight.Light)
}

@Preview(
    name = "PreviewCanvas 自检 · 2.0x · 深色",
    widthDp = PREVIEW_WIDTH_DP,
    uiMode = 0x20,
    showBackground = true,
)
@Composable
private fun PreviewCanvasSelfCheckMaxDark() {
    PreviewCanvasSelfCheck(PreviewFontScale.Max, PreviewNight.Dark)
}
