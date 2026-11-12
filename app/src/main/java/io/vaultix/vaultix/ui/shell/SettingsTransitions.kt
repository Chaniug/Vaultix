/*
 * Vaultix — app:ui:shell
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * ---------------------------------------------------------------------------
 * 设置二级页转场（2026-11-12 修「过渡不自然」）。
 *
 * ## 为什么不能继续用 Tab 那套
 *
 * `TabTransitions.kt` 的 140ms / 起始 alpha 0.95 / 零位移，是为**两页共享底栏、
 * 布局一致**的 Tab 切换调的，观感上就是硬切。设置首页（带 dock + 分组卡片）→
 * 二级页（全屏列表、无 dock）是**整屏布局突变**，同一套参数会直接暴露成「唰一下」。
 *
 * 真机取证（2026-11-12）：录屏逐帧确认设置首页 → 子页在相邻两帧（17ms）内换完，
 * 无任何中间态；而 `dumpsys gfxinfo framestats` 三轮复测 99th 仅 18~27ms
 * ⇒ **不是掉帧，是缺过渡**。所以这里只补观感，不动性能、不动 Tab。
 *
 * ## 参数取舍（都在「别再加花哨动效」的边界内）
 *
 * | 约束 | 取值 | 理由 |
 * |---|---|---|
 * | 位移 | 进入 1/16 屏宽、让位 1/32 屏宽 | 有方向连续性，但远小于「整屏滑走」（历史上被读作「多余」） |
 * | alpha | 0.92 → 1，双向对称 | 两页全程接近不透明 ⇒ 不漏黑色背景（深色模式闪烁的成因见 TabTransitions KDoc） |
 * | 时长 | 180ms | 比 Tab 的 140ms 略长到「看得见」，仍短于 Material 标准 300ms |
 * | 尺寸补间 | 由调用点显式 `sizeTransform = null` | 见 VaultixApp NavHost 注释：容器尺寸补间会造成「整页缩小一帧」 |
 *
 * ⚠️ push / pop 必须互为镜像：前进 = 向右展开，返回 = 向右收回。
 * ⚠️ 不要改回全屏 `fadeIn(0f)`：整屏 alpha 合成要离屏渲染，是历史掉帧根因
 *    （`VaultixApp.kt` NavHost 注释 184-189）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.shell

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.IntOffset

/** 时长：比 Tab 的 140ms 长一点，让过渡「看得见」，又不至于像一段动画表演。 */
private const val SETTINGS_NAV_MS = 180

/** 新页进入 / 子页退出时的位移 = 屏宽 / 此值（1/16 ≈ 6%，在 1256px 宽上约 78px）。 */
private const val ENTER_SLIDE_DIVISOR = 16

/** 让位页 / 父页回位的位移 = 屏宽 / 此值（1/32 ≈ 3%）。 */
private const val EXIT_SLIDE_DIVISOR = 32

/**
 * alpha 下限 0.92：两页任意时刻都接近不透明，合成后漏出的背景峰值
 * 约 `(1-0.92)²/4 ≈ 0.16%`，深色模式下不可见。取 1 就等于纯位移，
 * 边缘会露出一截旧页面；取 0 就是历史掉帧的全屏淡入。
 */
private const val SETTINGS_ALPHA_FLOOR = 0.92f

/** emphasized decelerate 的常用近似：快起、缓停，起步不拖泥带水。 */
private val settingsEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

private val settingsFadeSpec = tween<Float>(durationMillis = SETTINGS_NAV_MS, easing = settingsEasing)
private val settingsSlideSpec = tween<IntOffset>(durationMillis = SETTINGS_NAV_MS, easing = settingsEasing)

/** 前进：新页从右侧轻推入，同时从 0.92 淡到不透明。 */
internal fun settingsEnter(): EnterTransition =
    slideInHorizontally(animationSpec = settingsSlideSpec) { it / ENTER_SLIDE_DIVISOR } +
        fadeIn(animationSpec = settingsFadeSpec, initialAlpha = SETTINGS_ALPHA_FLOOR)

/** 前进：旧页向左微让，保持接近不透明（不做「淡出到 0」那种漏背景的事）。 */
internal fun settingsExit(): ExitTransition =
    slideOutHorizontally(animationSpec = settingsSlideSpec) { -it / EXIT_SLIDE_DIVISOR } +
        fadeOut(animationSpec = settingsFadeSpec, targetAlpha = SETTINGS_ALPHA_FLOOR)

/** 返回：父页从左侧微位移处回位 —— [settingsEnter] 的镜像。 */
internal fun settingsPopEnter(): EnterTransition =
    slideInHorizontally(animationSpec = settingsSlideSpec) { -it / EXIT_SLIDE_DIVISOR } +
        fadeIn(animationSpec = settingsFadeSpec, initialAlpha = SETTINGS_ALPHA_FLOOR)

/** 返回：子页向右退出 —— [settingsExit] 的镜像。 */
internal fun settingsPopExit(): ExitTransition =
    slideOutHorizontally(animationSpec = settingsSlideSpec) { it / ENTER_SLIDE_DIVISOR } +
        fadeOut(animationSpec = settingsFadeSpec, targetAlpha = SETTINGS_ALPHA_FLOOR)
