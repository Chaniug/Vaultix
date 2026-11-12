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
 * 真机取证（2026-11-12 第一轮）：录屏逐帧确认设置首页 → 子页在相邻两帧（17ms）内换完，
 * 无任何中间态；而 `dumpsys gfxinfo framestats` 三轮复测 99th 仅 18~27ms
 * ⇒ **当时不是掉帧，是缺过渡**。
 * ⚠️ 但这条结论只对「没有转场」的那个版本成立。加上转场后**引入了新的成本**——
 *   两页同时在场要各测一遍布局，见下节「重叠窗口」。别再把「不是掉帧」当成
 *   「加多少动效都不会掉帧」的依据。
 *
 * ## 参数取舍（都在「别再加花哨动效」的边界内）
 *
 * | 约束 | 取值 | 理由 |
 * |---|---|---|
 * | 位移 | 进入 1/24 屏宽、让位 1/40 屏宽 | 有方向连续性，但远小于「整屏滑走」（历史上被读作「多余」） |
 * | alpha | 0.95 → 1，双向对称 | 两页全程接近不透明 ⇒ 不漏黑色背景（深色模式闪烁的成因见 TabTransitions KDoc） |
 * | 进入时长 | 130ms | 比 Tab 的 140ms 略短到「看得见」，仍远短于 Material 标准 300ms |
 * | **让位时长** | **60ms** | ★ 见下节「重叠窗口」——这一项直接决定卡不卡 |
 * | 尺寸补间 | 由调用点显式 `sizeTransform = null` | 见 VaultixApp NavHost 注释：容器尺寸补间会造成「整页缩小一帧」 |
 *
 * ## ★ 重叠窗口：这套转场唯一的性能开关（2026-11-12 第二轮真机定案）
 *
 * 第一版是 180ms 对称（进入/让位同长）。用户装了 preview 后反馈「点来点去、
 * 进二级页再返回有点动效卡顿」。`gfxinfo framestats` 三轮复测（120Hz 设备）：
 *
 * | 场景 | 最慢单帧 | 拆解 |
 * |---|---|---|
 * | 进入 · 改名前的旧版 | 21.8 / 27.6 / 18.2 ms | 无 >30ms 的帧 |
 * | 进入 · 180ms 对称版 | **73.9 / 50.1 / 51.8 ms** | `动画→布局 22.7` + `布局→绘制 39.6` |
 *
 * 多出来的 60ms 全在**布局**：两页同时在场时，设置首页（液态玻璃 dock + 分组卡片）
 * 与二级页（全屏列表）要在同一帧各测一遍。180ms 里这一帧占 40% ⇒ 观感是
 * 「滑到一半停一下」，而不是「掉帧」。
 * ⇒ 于是把**让位页单独收到 60ms**：重叠窗口 180→60ms（约 7 帧 @120Hz），
 *   之后 70ms 只有新页在动，布局成本不再叠加。
 *
 * ⚠️ 这次复测还纠正了一个**测量陷阱**：`framestats` 的 `总耗时` 是
 *   `FRAME_COMPLETED - IntendedVsync`，而 `adb shell input tap/keyevent` 每次都要
 *   fork 一个 app_process 起 JVM，事件真正投递前有 **120ms 左右**的注入延迟，
 *   这段会原样落进 `vsync→HandleInputStart`。实测「返回」那一帧 134ms 里有
 *   **125ms 是这段注入延迟**，真实主线程工作只有 9ms。
 *   ⇒ 判读 `framestats` **必须**把 `2→5` 这一段单独拆出来；只看 total 会把
 *     ADB 的延迟当成应用的卡顿（本项目上一轮就这么误判过一次）。
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

/**
 * 新页进入 / 父页回位的时长。
 *
 * ★ 2026-11-12 第二轮（真机复测后从 180ms 收到 130ms）：见文件头「重叠窗口」一节。
 */
private const val SETTINGS_NAV_MS = 130

/**
 * **让位页 / 退出页的时长 —— 单独比 [SETTINGS_NAV_MS] 短。**
 *
 * 这是本文件唯一的性能开关：两页**同时处于组合状态**的这段时间，每帧都要把
 * 设置首页（液态玻璃 dock + 分组卡片）和二级页（全屏列表）各测一遍。真机实测
 * 这一段里出现一帧 `布局→绘制 39.6ms`，把 180ms 的动画卡成「滑到一半停一下」。
 * ⇒ 让旧页 60ms 就退场，重叠窗口从 180ms 压到 60ms（约 7 帧），
 *   剩下的 70ms 只有新页在动，布局成本不再叠加。
 */
private const val SETTINGS_EXIT_MS = 60

/** 新页进入时的位移 = 屏宽 / 此值（1/24 ≈ 4%，在 1256px 宽上约 52px）。 */
private const val ENTER_SLIDE_DIVISOR = 24

/** 让位页 / 父页回位的位移 = 屏宽 / 此值（1/40 ≈ 2.5%，约 31px）。 */
private const val EXIT_SLIDE_DIVISOR = 40

/**
 * alpha 下限：两页任意时刻都接近不透明，合成后漏出的背景可忽略。
 * 取 1 就等于纯位移，边缘会露出一截旧页面；取 0 就是历史掉帧的全屏淡入。
 */
private const val SETTINGS_ALPHA_FLOOR = 0.95f

/** emphasized decelerate 的常用近似：快起、缓停，起步不拖泥带水。 */
private val settingsEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

private val settingsFadeSpec = tween<Float>(durationMillis = SETTINGS_NAV_MS, easing = settingsEasing)
private val settingsSlideSpec = tween<IntOffset>(durationMillis = SETTINGS_NAV_MS, easing = settingsEasing)
private val settingsExitFadeSpec = tween<Float>(durationMillis = SETTINGS_EXIT_MS, easing = settingsEasing)
private val settingsExitSlideSpec = tween<IntOffset>(durationMillis = SETTINGS_EXIT_MS, easing = settingsEasing)

/** 前进：新页从右侧轻推入，同时从 0.92 淡到不透明。 */
internal fun settingsEnter(): EnterTransition =
    slideInHorizontally(animationSpec = settingsSlideSpec) { it / ENTER_SLIDE_DIVISOR } +
        fadeIn(animationSpec = settingsFadeSpec, initialAlpha = SETTINGS_ALPHA_FLOOR)

/** 前进：旧页向左微让，用 [SETTINGS_EXIT_MS] 提前退场（压缩双页重叠）。 */
internal fun settingsExit(): ExitTransition =
    slideOutHorizontally(animationSpec = settingsExitSlideSpec) { -it / EXIT_SLIDE_DIVISOR } +
        fadeOut(animationSpec = settingsExitFadeSpec, targetAlpha = SETTINGS_ALPHA_FLOOR)

/** 返回：父页从左侧微位移处回位 —— [settingsEnter] 的镜像。 */
internal fun settingsPopEnter(): EnterTransition =
    slideInHorizontally(animationSpec = settingsSlideSpec) { -it / EXIT_SLIDE_DIVISOR } +
        fadeIn(animationSpec = settingsFadeSpec, initialAlpha = SETTINGS_ALPHA_FLOOR)

/** 返回：子页向右退出 —— [settingsEnter] 的镜像，同样用短时长提前让位。 */
internal fun settingsPopExit(): ExitTransition =
    slideOutHorizontally(animationSpec = settingsExitSlideSpec) { it / ENTER_SLIDE_DIVISOR } +
        fadeOut(animationSpec = settingsExitFadeSpec, targetAlpha = SETTINGS_ALPHA_FLOOR)
