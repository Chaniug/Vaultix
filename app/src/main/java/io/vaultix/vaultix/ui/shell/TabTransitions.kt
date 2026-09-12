/*
 * Vaultix — app:ui:shell
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 缓动曲线 `CubicBezierEasing(0.6f, 0.0f, 0.4f, 1.0f)` 移植自 Bastion
 * （GPL-3.0，Copyright 2025 JoyinJoester）的 `ui/navigation/NavTransitions.kt`
 * （Bastion 注明与 Keyguard 一致）。
 *
 * ⚠️ **参数未照搬**：Bastion 原为「淡入 + 轻微上移 / 纯淡出，220ms / 120ms」，
 * 直接照搬会在深色模式下**闪一下黑**（2026-09-13 用户反馈）—— 原理与修正后的参数
 * 见本文件 [DURATION_TAB_SWITCH] 与 [TAB_FADE_START_ALPHA] 的注释。
 * 那一处属于上游在特定主题（Expressive + 浅色）下未暴露的问题，Vaultix 走 Standard 主题
 * 且用户常用深色，必须自行收敛。
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

/**
 * 进入 / 退出时长：**必须相等**（140ms）。
 *
 * ⚠️ 這是 2026-09-13 修「深色模式 Tab 切换闪一下」的关键约束，改数值前先读下面这段。
 *
 * 旧参数是「进入 220ms / 退出 120ms」。在 `AnimatedContent` 里进出**同时**播放，
 * 于是存在一段「旧页面已经淡完、新页面才半透明」的窗口（约 100ms）：
 * 此时屏幕上只有 `alpha≈0.5` 的新页面叠在**黑色背景**上 —— 深色模式下就是**明显一暗**，
 * 用户描述为「闪烁一下，很晃眼睛」。
 *
 * 数学上：设新页面 alpha = a、旧页面 alpha = b，两者叠在背景 Bg 上的合成结果为
 * `新*a + 旧*b*(1-a) + Bg*(1-a)*(1-b)`；只要 a、b 同时远离 1，**最后那一项就是漏出来的背景**。
 * `a=b=0.5` 时它高达 **25%** —— 深色模式的 Bg 近纯黑，25% 的黑就是一眼可见的闪。
 *
 * 因此两条硬约束：
 * 1. **进出同时长**：避免「旧页面先消失」留下半透明新页面；
 * 2. **新页面起步 alpha 要高**（见 [TAB_FADE_START_ALPHA]）：a 的下限直接压住漏光峰值。
 */
private const val DURATION_TAB_SWITCH = 140

/**
 * 新页面淡入的**起步透明度**。
 *
 * 取 0.95 而非 0：漏出的背景峰值 ≈ `(1-a)²/4`，a=0.95 时约 **1.2%**（肉眼不可见），
 * 而 a=0 时高达 25%。代价是「淡入」几乎察觉不到 —— 但 Tab 切换本就该快而稳，
 * 用户要的是**不闪**，不是一段明显的转场。
 */
private const val TAB_FADE_START_ALPHA = 0.95f

/** 与 Bastion / Keyguard 一致的缓动曲线：快起、匀速、缓停。 */
private val navEasing = CubicBezierEasing(0.6f, 0.0f, 0.4f, 1.0f)

/**
 * Tab 进入：高起步淡入（**不带位移**，原因见下）。
 *
 * `initialAlpha` 不取 0 的原因见 [TAB_FADE_START_ALPHA] —— 这是本次修闪烁的核心改动。
 *
 * ⚠️ 为什么去掉原来的 `slideInVertically`：新页面一旦位移，动画期间它**没盖住**的那条
 * （旧实现 1/16 屏高）露出的是正在淡出的旧页面 —— 上下两截内容不一致，看着像「画面裂开」。
 * 而 Tab 切换的位移天然很小（大了就成了翻页），收益远小于副作用，故整体去掉。
 */
fun tabSwitchEnter(): EnterTransition =
    fadeIn(
        animationSpec = tween(durationMillis = DURATION_TAB_SWITCH, easing = navEasing),
        initialAlpha = TAB_FADE_START_ALPHA,
    )

/**
 * Tab 退出：淡出到 [TAB_FADE_START_ALPHA]（**不是 0**）。
 *
 * 与进入**同时长、互为补数** ⇒ 任意时刻「新 alpha + 旧 alpha ≈ 1.95」，
 * 背景的漏光系数 `(1-a)(1-b)` 峰值仅约 1.2%，深色模式下完全看不出来。
 * 旧实现淡出到 0 且只用 120ms（短于进入 220ms），正是「旧页面先消失、新页面还半透明」
 * 那段闪的来源。
 */
fun tabSwitchExit(): ExitTransition =
    fadeOut(
        animationSpec = tween(durationMillis = DURATION_TAB_SWITCH, easing = navEasing),
        targetAlpha = 1f - TAB_FADE_START_ALPHA,
    )
