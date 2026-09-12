/*
 * Vaultix — app:ui:shell
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * Tab 切换过渡动画的**参数与缓动曲线移植自 Bastion**（GPL-3.0，Copyright 2025 JoyinJoester）
 * 的 `ui/navigation/NavTransitions.kt` 中 tab 相关部分：
 *   - `tabSwitchEnter()` / `tabSwitchExit()`：淡入 + 轻微上移 / 纯淡出；
 *   - 时长 220ms（进入）/ 120ms（退出）、位移 1/16 屏高、缓动
 *     `CubicBezierEasing(0.6f, 0.0f, 0.4f, 1.0f)`（Bastion 注明与 Keyguard 一致）。
 * 本文件只保留 Tab 切换需要的两条（Bastion 的 182 行里其余是**路由级**转场：
 * 子页面滑入 / 父页面视差 / EasyNotes 缩放退场 —— Vaultix 的二级页走 Navigation Compose
 * 默认转场，阶段性 3 不引入，避免与导航库自带动画打架）。
 *
 * ## 为什么 Tab 切换必须显式写过渡（Bastion 的踩坑结论，原样保留）
 * tab 切换在此前对大多数组合是 `EnterTransition.None`——页面**硬切**。原先观感"流畅"
 * 其实来自 Material3 Expressive 组件内部的 spring 动画；主题改用 Standard motion scheme 后
 * 组件内部动画收敛，硬切就被暴露出来。因此这里显式补上页面级过渡，**不依赖组件内部动画**。
 *
 * ## 为什么只用 fade + slideVertically
 * 两者都是 Compose 稳定 API、不依赖 `MotionScheme`，因此在 Standard / Expressive 主题下
 * 表现一致（Bastion 原文即如此取舍）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.shell

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically

/** 进入时长：220ms —— 足够看出「换页了」，又不会拖慢高频的底栏点击。 */
private const val DURATION_TAB_SWITCH = 220

/**
 * 退出时长：120ms —— 刻意短于进入。
 *
 * 旧页面此时已经被新页面覆盖，看久了只是拖慢节奏；快速淡出让视觉焦点立刻落到新页面上。
 */
private const val DURATION_TAB_FADE_OUT = 120

/** 位移比例：1/16 屏高（越小越含蓄，避免整页「跳」起来）。 */
private const val TAB_SWITCH_OFFSET_RATIO = 16

/** 与 Bastion / Keyguard 一致的缓动曲线：快起、匀速、缓停。 */
private val navEasing = CubicBezierEasing(0.6f, 0.0f, 0.4f, 1.0f)

/** Tab 进入：淡入 + 从下方 1/16 屏高轻微上移。 */
fun tabSwitchEnter(): EnterTransition =
    fadeIn(animationSpec = tween(durationMillis = DURATION_TAB_SWITCH, easing = navEasing)) +
        slideInVertically(
            animationSpec = tween(durationMillis = DURATION_TAB_SWITCH, easing = navEasing),
            initialOffsetY = { fullHeight -> fullHeight / TAB_SWITCH_OFFSET_RATIO },
        )

/** Tab 退出：纯淡出（不带位移，见文件头「为什么」）。 */
fun tabSwitchExit(): ExitTransition =
    fadeOut(animationSpec = tween(durationMillis = DURATION_TAB_FADE_OUT, easing = navEasing))
