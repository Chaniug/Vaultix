package com.bastion.app.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally

/**
 * Bastion Android 导航过渡动画，对齐 Keyguard 的 NavigationAnimation 实现。
 *
 * 进入（GoForward）：新页面从右侧 1/8 屏宽滑入 + fadeIn，
 *                   旧页面向左 1/12 屏宽滑出 + fadeOut。
 * 返回（GoBack）：  旧页面从左侧 -1/12 屏宽滑回 + fadeIn，
 *                   新页面向右 1/8 屏宽滑出 + fadeOut。
 *
 * easing 使用 CubicBezierEasing(0.6, 0.0, 0.4, 1.0)，与 Keyguard 一致。
 */

private const val DURATION_FORWARD = 300
private const val DURATION_BACK = 280

/**
 * EasyNotes 风格缩放转场的统一时长。
 *
 * 之前 fade 用 300ms、scale 用 400ms，**两者不同步**，造成两个可见问题：
 * - 进入：alpha 在 300ms 就到 1，剩下 100ms 是「已经完全不透明却还在缩放」
 * - 退出：alpha 在 300ms 就到 0，剩下 100ms 的缩放根本看不见，纯属空转
 *
 * 现统一为同一时长，让 alpha 与 scale 同步到位。
 */
private const val EASY_NOTES_DURATION = 260

/**
 * 子页面进入/退出的缩放幅度。
 *
 * 原先 0.9f 幅度偏大：进入方与退出方**同时做反向缩放且都带 alpha**，
 * 中间帧（约 t=0.5）两层内容缩放比接近、又都是半透明，高度重合 → 明显重影。
 * 收到 0.94f 后，重叠偏差降到肉眼不易察觉。
 */
private const val EASY_NOTES_INITIAL_SCALE = 0.94f

/**
 * 父页面（Main / 设置页）让位给子页面时的缩放幅度。
 * 设成 0.99 后肉眼几乎不可见，只保留极轻微景深，
 * 避免用户在「返回上一级」时看到明显的缩小感。
 */
private const val PARENT_PAGE_SCALE = 0.99f

/**
 * 返回（back）时子页面划出的时长。
 *
 * 短于进入动画（[EASY_NOTES_DURATION] = 260ms）：
 * 依据 Material Design motion 规范，返回应当**快速、干脆**，
 * 用户在返回时已经知道要去哪，不需要长动画来引导注意力。
 *
 * 取 200ms 而非更短：返回手势期间动画是**实时跟随手指**的，
 * 这里的时长只用于「松手后把剩余部分补完」；全宽划出若太短会显得急促。
 */
private const val DURATION_BACK_EXIT = 200

private val navEasing = CubicBezierEasing(0.6f, 0.0f, 0.4f, 1.0f)

private fun <T> tweenForward() = tween<T>(durationMillis = DURATION_FORWARD, easing = navEasing)
private fun <T> tweenBack() = tween<T>(durationMillis = DURATION_BACK, easing = navEasing)
private fun <T> tweenFadeOut() = tween<T>(durationMillis = DURATION_FORWARD / 2, easing = navEasing)

/** 子页面进入：从右侧 1/8 屏宽滑入 + fadeIn。 */
fun slideInFromRight(): EnterTransition =
    slideInHorizontally(
        animationSpec = tweenForward(),
        initialOffsetX = { fullWidth -> fullWidth / 8 },
    ) + fadeIn(animationSpec = tweenBack())

/** 子页面返回时退出：向右 1/8 屏宽滑出 + fadeOut。 */
fun slideOutToRight(): ExitTransition =
    slideOutHorizontally(
        animationSpec = tweenBack(),
        targetOffsetX = { fullWidth -> fullWidth / 8 },
    ) + fadeOut(animationSpec = tweenFadeOut())

/** 列表页退出：向左 1/12 屏宽滑出 + fadeOut（配合子页面进入）。 */
fun parallaxExitToLeft(): ExitTransition =
    slideOutHorizontally(
        animationSpec = tweenForward(),
        targetOffsetX = { fullWidth -> -fullWidth / 12 },
    ) + fadeOut(animationSpec = tweenFadeOut())

/** 列表页返回时进入：从左侧 -1/12 屏宽滑回 + fadeIn。 */
fun parallaxEnterFromLeft(): EnterTransition =
    slideInHorizontally(
        animationSpec = tweenBack(),
        initialOffsetX = { fullWidth -> -fullWidth / 12 },
    ) + fadeIn(animationSpec = tweenBack())

/**
 * EasyNotes 风格页面进入：从 [EASY_NOTES_INITIAL_SCALE] 缩放到 1，同时淡入。
 *
 * fade 与 scale 现在共用 [EASY_NOTES_DURATION]，alpha 与缩放同步到位，
 * 不会再出现「已经不透明但还在缩放」的末段突起。
 */
fun easyNotesScreenEnter(): EnterTransition =
    fadeIn(animationSpec = tween(EASY_NOTES_DURATION, easing = navEasing)) +
        scaleIn(
            initialScale = EASY_NOTES_INITIAL_SCALE,
            animationSpec = tween(EASY_NOTES_DURATION, easing = navEasing)
        )

/**
 * 底部导航 tab 之间的通用切换过渡（淡入 + 轻微上移）。
 *
 * 背景：tab 切换此前对大多数组合使用 EnterTransition.None，页面是硬切的；
 * 原先观感"流畅"实际来自 Material3 Expressive 组件内部的 spring 动画。
 * 主题改用 Standard motion scheme 后组件内部动画收敛，硬切被暴露出来，
 * 因此这里显式补上页面级过渡，不依赖组件内部动画。
 *
 * 说明：这里只用 fade + slideVertically（Compose 稳定 API），
 * 不依赖 MotionScheme，因此在 Standard / Expressive 下表现一致。
 */
private const val DURATION_TAB_SWITCH = 220
private const val DURATION_TAB_FADE_OUT = 120
private const val TAB_SWITCH_OFFSET_RATIO = 16

fun tabSwitchEnter(): EnterTransition =
    fadeIn(animationSpec = tween(durationMillis = DURATION_TAB_SWITCH, easing = navEasing)) +
        slideInVertically(
            animationSpec = tween(durationMillis = DURATION_TAB_SWITCH, easing = navEasing),
            initialOffsetY = { fullHeight -> fullHeight / TAB_SWITCH_OFFSET_RATIO }
        )

fun tabSwitchExit(): ExitTransition =
    fadeOut(animationSpec = tween(durationMillis = DURATION_TAB_FADE_OUT, easing = navEasing))

/**
 * 子页面返回退出：**跟随手指向右划出屏幕**，同时淡出。
 *
 * 用户主要用「边缘侧滑」返回，而 Predictive Back 的灵魂就是**跟手** ——
 * 页面必须实时跟随手指位移。若只做淡出没有位移，侧滑时会有明显的「不跟手」感。
 *
 * 按 Android 标准，返回时当前页面向右划走，故用全宽 slideOutHorizontally。
 * **不做任何缩放** —— 缩放退场是 iOS modal 的表现方式，在安卓上会让人觉得
 * 「页面在缩回去」，与返回手势直觉不符。
 */
fun easyNotesScreenExit(): ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(DURATION_BACK_EXIT, easing = navEasing),
        targetOffsetX = { fullWidth -> fullWidth }
    ) + fadeOut(animationSpec = tween(DURATION_BACK_EXIT, easing = navEasing))

/**
 * 父页面（Main / 设置页）让位给子页面：只做极轻微缩小，**不淡出**。
 *
 * 为什么不淡出：子页面是在父页面上方淡入的。若父页面同时淡出，
 * 中间帧会出现两层半透明内容叠在一起（重影）。
 * 父页面保持不透明，就会被上层自然遮住，不需要靠 alpha 退场。
 */
fun parentPageExit(): ExitTransition =
    scaleOut(
        targetScale = PARENT_PAGE_SCALE,
        animationSpec = tween(EASY_NOTES_DURATION, easing = navEasing)
    )

/**
 * 父页面（Main / 设置页）在子页面返回时恢复：**从左后方轻微跟进（视差）**。
 *
 * 子页面向右划走的同时，父页面从左侧 1/12 处滑回原位，
 * 形成 Android 标准的层次感（parallax），让返回动作有空间关系。
 *
 * 不做缩放、不做淡入 —— 父页面在下层本来就可见，
 * 保持不透明更干净，也避免出现「两层半透明内容重叠」的重影。
 */
fun parentPageEnter(): EnterTransition =
    slideInHorizontally(
        animationSpec = tween(DURATION_BACK_EXIT, easing = navEasing),
        initialOffsetX = { fullWidth -> -fullWidth / 12 }
    )
