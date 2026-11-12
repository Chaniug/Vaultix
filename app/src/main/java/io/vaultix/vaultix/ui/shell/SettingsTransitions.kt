/*
 * Vaultix — app:ui:shell
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 设置二级页转场（2026-11-12）。
 *
 * ## 一句话
 *
 * 用 **Compose Navigation 官方的 `slideIntoContainer` / `slideOutOfContainer`**
 * 实现 Material 3 的 **Shared Axis X**（层级导航的标准模式），不再自创参数。
 *
 * ## 为什么不能用 Tab 那套
 *
 * `TabTransitions.kt` 的 140ms / 起始 alpha 0.95 / 零位移，是为**两页共享底栏、
 * 布局一致**的 Tab 切换调的，观感上就是硬切。设置首页（带 dock + 分组卡片）→
 * 二级页（全屏列表、无 dock）是**整屏布局突变**，同一套参数会直接暴露成「唰一下」。
 *
 * ## 三版演进（★ 前两版都被否掉，别走回头路）
 *
 * | 版本 | 参数 | 结果 |
 * |---|---|---|
 * | v1 | 180ms 对称、位移 1/16、alpha 0.92→1 | 真机出现一帧 50~74ms（布局 39.6ms），用户报「卡顿」 |
 * | v2 | 让位 60ms / 进入 130ms、位移 1/24、alpha 0.95 | 为省性能搞成**不对称**，旧页先退场 ⇒ 视觉撕裂，用户报「和常见项目完全不对」 |
 * | **v3（当前）** | **官方 `slideIntoContainer`，Material 3 Shared Axis X** | 待真机验证 |
 *
 * v2 的错在于**自创**：既不是 Material 标准，也不是本项目 Tab 那套，是第三种形态。
 * 而且它把 `TabTransitions` 里 2026-09-13 用血换来的「进出同时长」约束反向破坏了
 * —— 那条约束的前提是**有 alpha 淡入淡出**；本文件**完全不做 alpha**，所以不适用。
 *
 * ## 参数与出处（不要凭手感改）
 *
 * | 项 | 取值 | 出处 |
 * |---|---|---|
 * | 位移 | `it / 4`（视差，两页各走 1/4 屏宽） | `AnimatedContentTransitionScope.slideIntoContainer` 的默认 `initialOffset` |
 * | 进入时长 | 300ms | Material 3 Shared Axis 进入（medium2） |
 * | 退出时长 | 200ms | Material 3 Shared Axis 退出（short4） |
 * | 进入缓动 | `emphasizedDecelerate` = cubic-bezier(0.05, 0.7, 0.1, 1) | M3 motion easing tokens |
 * | 退出缓动 | `emphasizedAccelerate` = cubic-bezier(0.3, 0, 0.8, 0.15) | 同上 |
 * | 方向 | 进入 `SlideDirection.Start`、返回 `SlideDirection.End` | Start/End 而非 Left/Right ⇒ RTL 语言自动镜像 |
 *
 * ## 为什么完全不做 alpha（这条最容易被"优化"回去，务必保留）
 *
 * 项目历史上的深色模式闪烁，全部来自**两页同时半透明**时漏出背景：
 * 背景漏光系数 `(1-a)(1-b)`，`a=b=0.5` 时高达 25%，深色下就是一眼可见的一暗
 * （详见 `TabTransitions.kt` 的数学推导）。
 * ⇒ 这里走的是另一条路：**两页全程不透明，靠位移建立层次**。
 *   新页从右侧覆盖上来，旧页退到 -1/4 处——位移本身就有空间连续性，
 *   不需要再叠一层淡入淡出，也就没有漏背景的机会。
 *   副作用是「退出比进入短」不再会闪（v2 的撕裂正是因为它保留了 alpha 又搞不对称）。
 *
 * ## ⚠️ 判读真机帧数据的陷阱（2026-11-12 踩过）
 *
 * `dumpsys gfxinfo framestats` 的 total = `FRAME_COMPLETED - IntendedVsync`，
 * 其中 **`IntendedVsync → HandleInputStart` 这一段包含 `adb shell input` 的注入延迟**：
 * 该命令每次 fork 一个 `app_process` 起 JVM，实测约 **120ms**。
 * ⇒ 「返回」曾测到 134ms/帧，其中 125ms 是这段延迟，真实主线程工作只有 9ms。
 * ⇒ 判读时必须把第 2→5 列单独拆出来，只看 total 会把 ADB 延迟当成应用卡顿。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.shell

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideIntoContainer
import androidx.compose.animation.slideOutOfContainer
import androidx.navigation.NavBackStackEntry

/** 进入时长：Material 3 Shared Axis 的 medium2（300ms）。 */
private const val SETTINGS_ENTER_MS = 300

/** 退出时长：Material 3 Shared Axis 的 short4（200ms）——规范本身就不对称。 */
private const val SETTINGS_EXIT_MS = 200

/** `emphasizedDecelerate`：快起缓停，用于**进入**（元素落位要有减速感）。 */
private val settingsDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/** `emphasizedAccelerate`：缓起快走，用于**退出**（元素离开要加速）。 */
private val settingsAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

/**
 * 前进（设置首页 → 二级页）：新页从右侧滑入，旧页同步退到左侧 1/4 处。
 *
 * 用 `slideIntoContainer` 而不是手拼 `slideInHorizontally`：前者是 Navigation 的官方 API，
 * 位移量 `it / 4` 是它自己的默认值（两页各走 1/4，合成后有视差层次），
 * 且方向用 `Start` / `End` 表达，RTL 语言下自动镜像。
 */
internal fun AnimatedContentTransitionScope<NavBackStackEntry>.settingsEnter(): EnterTransition =
    slideIntoContainer(
        towards = AnimatedContentTransitionScope.SlideDirection.Start,
        animationSpec = tween(durationMillis = SETTINGS_ENTER_MS, easing = settingsDecelerate),
    )

/** 前进：旧页向左让位（200ms，比进入短，规范如此）。 */
internal fun AnimatedContentTransitionScope<NavBackStackEntry>.settingsExit(): ExitTransition =
    slideOutOfContainer(
        towards = AnimatedContentTransitionScope.SlideDirection.Start,
        animationSpec = tween(durationMillis = SETTINGS_EXIT_MS, easing = settingsAccelerate),
    )

/** 返回：父页从左侧 1/4 处回位 —— [settingsEnter] 的镜像。 */
internal fun AnimatedContentTransitionScope<NavBackStackEntry>.settingsPopEnter(): EnterTransition =
    slideIntoContainer(
        towards = AnimatedContentTransitionScope.SlideDirection.End,
        animationSpec = tween(durationMillis = SETTINGS_ENTER_MS, easing = settingsDecelerate),
    )

/** 返回：子页向右退出 —— [settingsExit] 的镜像。 */
internal fun AnimatedContentTransitionScope<NavBackStackEntry>.settingsPopExit(): ExitTransition =
    slideOutOfContainer(
        towards = AnimatedContentTransitionScope.SlideDirection.End,
        animationSpec = tween(durationMillis = SETTINGS_EXIT_MS, easing = settingsAccelerate),
    )
