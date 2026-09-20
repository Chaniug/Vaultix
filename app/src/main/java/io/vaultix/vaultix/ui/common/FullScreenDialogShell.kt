/*
 * Vaultix — app:ui:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.theme.Spacing

/**
 * **全屏编辑壳**：顶部标题行（含关闭）+ 可滚动正文 + 底部固定操作条。
 *
 * ⚠️ 2026-09-13 第二轮用户反馈：「编辑条目 / 验证码条目不是全屏显示，看起来不舒服。」
 *
 * 这两个编辑界面原本都是 `AlertDialog` —— 居中卡片、宽度被平台硬限制在 ~280dp，
 * 而条目编辑表单有「身份 17 字段 + 自定义字段」，塞进那个宽度里必然局促、必须蜷着滚动。
 *
 * 现在统一成**整页**：`Dialog(usePlatformDefaultWidth = false)` + 全屏 `Surface`。
 * 为什么不直接用一条路由去承载：编辑是**模态**的（它必须阻断「编辑中又被别处改掉」），
 * 走路由就要自己在导航栈上模拟模态语义；`Dialog` 天然就是模态，且不改动导航图。
 *
 * 三个区域的分工：
 * - 顶部：标题 + 关闭（关闭 = 取消，语义与「返回」一致）；
 * - 中部：`weight(1f)` + `verticalScroll` —— 表单再长也不会把按钮顶出屏幕，
 *   且 `imePadding()` 在壳上统一处理，键盘弹出时输入框不会被遮；
 * - 底部：**固定**的取消 / 确认（不随表单滚动，长表单也能一键保存）。
 *
 * @param onDismiss 关闭（顶部 ✕ / 系统返回 / 点外部）——调用方需自行处理「保存中不许关」。
 * @param onConfirm 底部主操作；[confirmEnabled] 为 `false` 时置灰（例如保存中）。
 * @param content 表单正文（[ColumnScope]，纵向排列；需要横排时内部自己套 `Row`）。
 */
@Composable
fun FullScreenDialogShell(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmLabel: String,
    confirmEnabled: Boolean,
    /** 破坏性操作（如验证码编辑里的「移除」）；为 `null` 时不画，排在「取消」左侧。 */
    destructive: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val view = LocalView.current
    // ⚠️ 判「状态栏图标该用深色还是浅色」**不能看系统深色模式**，要看**我们实际画出来的底色**。
    // 2026-09-13 第三轮用户反馈：「状态栏沉浸的时候，**浅色模式下**状态栏的显示效果才有问题」
    // ——上一版我用的是 `isSystemInDarkTheme()`，而本 App 有**自己的主题设置**（浅色/深色/跟随系统
    // + OLED 纯黑 + 动态取色）：App 设成浅色、系统是深色时，图标被判成"浅色"，白图标压在白底上
    // 就是"看不见/像被遮住"。
    // 取 `surface` 的亮度最可靠 —— 它与真正渲染出来的底色永远一致（含 OLED / 动态取色）。
    val lightSurface = MaterialTheme.colorScheme.surface.luminance() > 0.5f

    Dialog(
        onDismissRequest = onDismiss,
        // ⚠️ 2026-09-13 第二轮用户反馈：「全屏的时候上下不沉浸」。
        // `Dialog` 的窗口默认会**避开系统栏**（decorFitsSystemWindows = true）⇒ 全屏 Surface
        // 铺不到状态栏与手势条，那两条露出的是弹窗遮罩的灰底 —— 看起来就是上下"缺了一块"。
        // 关掉它让内容真正 edge-to-edge，再由壳自己按 `statusBarsPadding` /
        // `navigationBarsPadding` 留出让位（与列表页顶栏的做法一致）。
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // ⚠️ 2026-09-13 第三轮用户反馈：「编辑页面虽然全屏沉浸了，但上面的通知栏配色好像不对，
        // 应该是被遮盖了。」
        // 根因：`Dialog` 有**自己的一扇窗口**，它默认会按主题给状态栏 / 手势条刷一层颜色；
        // 而我们的 `Surface` 已经铺满整屏 ⇒ 那层"主题色"压在最上面，看起来就是一条被遮住的色带。
        // 这里把系统栏刷成透明、并让图标颜色跟随深浅色 —— Surface 就能一路铺到屏幕最上/最下沿，
        // 与"沉浸式"一致。⚠️ 必须在 Dialog 内容里取 parent（这时 parent 才是这扇 dialog 窗口）。
        SideEffect {
            val dialogWindow = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            @Suppress("DEPRECATION") // SDK 35+ 用边到边渲染，但 transparent 仍是这段最省事的写法
            dialogWindow.statusBarColor = android.graphics.Color.TRANSPARENT
            @Suppress("DEPRECATION")
            dialogWindow.navigationBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.getInsetsController(dialogWindow, view).apply {
                isAppearanceLightStatusBars = lightSurface
                isAppearanceLightNavigationBars = lightSurface
            }
        }
        Surface(modifier = Modifier.fillMaxSize()) {
            // ── 2026-09-16：从 `Column`（标题栏 / 正文 / 底部条三者平铺、互不重叠）
            // 改为 `Box` **叠加**，让正文从标题栏与底部条**下面穿过**。
            //
            // 用户的观察：「新建条目的内容里面，上边和下边不是透明的」，滚动时顶部的卡片
            // 被标题栏**硬裁断**、底部同理 —— 那次改「整页全屏」其实只做了一半：
            // **窗口**变全屏了，但内容层没做穿越效果，所以"全屏"根本体现不出来。
            //
            // ⚠️ 为什么要让位（而不是让内容真的一路顶到屏幕边）：标题栏与底部条是
            // **不透明**的（否则内容与按钮会叠在一起看不清）⇒ 内容必须预留它们的高度，
            // 否则首帧就会钻到按钮底下。
            // ⚠️ 高度用常量而不是 `onGloballyPositioned` 实测：后者的首次组合拿不到值，
            // 会先按 0 让位、再跳一次 —— 那种一跳比"数值不精确"更难看。
            Box(modifier = Modifier.fillMaxSize().imePadding()) {
                // ── 让位高度 = 系统栏内边距 + 栏自身高度（**不是**只让栏高）。
                //
                // ⚠️ 2026-09-20 用户反馈：「新建密码条目 / 验证码 / 卡包界面，不透明，
                // 上边标题部分和下方部分遮住了显示内容。」
                // 根因就在这里：两条栏都带 `statusBarsPadding()` / `navigationBarsPadding()`
                // （**之外**再叠自己的 padding），而正文只让了 [ACTION_BAR_HEIGHT] 这类
                // 纯栏高 ⇒ 正文首/末元素正好钻到那条**不透明**色带底下。
                // 顶部少让一个状态栏（普通机型 24~48dp），底部少让一个手势条（24~48dp）。
                //
                // 让位改用**实测**而不是常量相加：状态栏/手势条高度随机型、分屏、折叠屏
                // 变化，`TITLE_BAR_HEIGHT + 常量` 只是把误差从"少让"变成"多让"，换个机型还会偏。
                // 这里读的是与两条栏**同一份** [WindowInsets]，两者因此永远对齐。
                //
                // ⚠️ 仍保留常量作为**下限兜底**：极端情况下 insets 可能为 0（如全屏/桌面模式），
                // 那时不能退化成"零让位"，否则内容会直接压在按钮上。
                val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

                // ── 沉浸：两条栏的**底色**随滚动淡出，与列表页「内容从栏下穿过」统一。
                //
                // ⚠️ 2026-09-20 用户第二轮反馈：「大小现在确实合适了，但是页面不沉浸，
                // 滑动的时候上下也不是透明，跟密码条目页面、验证码条目页面的效果不一样。」
                //
                // 上一轮只把**让位**算对了（内容不再被遮），但两条栏的底色仍是写死的
                // `.background(surface)` —— 恒不透明 ⇒ 内容滑到栏下就被盖住，"穿过"看不见。
                // 而列表页顶栏走的是 [VaultixExpressiveTopBar]：`barBackgroundAlpha` 随
                // `collapseFraction` 在 1 ↔ 0 之间过渡，一滚就透明。
                // 两边观感不一致的根源就在这里。
                //
                // 口径与列表页**完全一致**：滚动偏移超过 [COLLAPSE_THRESHOLD_DP] 即视为
                // "已滚动"，内部走 [BAR_FADE_MS] 的 tween —— 快照式而非连续
                //（连续会让栏在滚动过程中一直闪）。
                val scrollState = rememberScrollState()
                val thresholdPx = with(LocalDensity.current) { COLLAPSE_THRESHOLD_DP.toPx() }
                val collapsed = scrollState.value > thresholdPx
                val barAlpha by animateFloatAsState(
                    targetValue = if (collapsed) 0f else 1f,
                    animationSpec = tween(BAR_FADE_MS),
                    label = "fullscreen_bar_alpha",
                )

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(horizontal = Spacing.xl)
                        .padding(
                            top = contentClearance(statusBar, TITLE_BAR_HEIGHT),
                            bottom = contentClearance(navBar, ACTION_BAR_HEIGHT),
                        ),
                    content = content,
                )

                // 顶部标题栏：底色随滚动淡出 → 内容从其下方**可见地**穿过。
                // ⚠️ `background` 必须排在 `statusBarsPadding()` **之前** —— 这样淡出的是
                //    含状态栏区域的整条色带（与 [VaultixExpressiveTopBar] 同款顺序）；
                //    顺序反了状态栏那一条会永远留着底色，看着就像"没沉浸"。
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = barAlpha))
                        .statusBarsPadding()
                        .padding(start = Spacing.sm, end = Spacing.sm, top = Spacing.sm)
                        .height(TITLE_BAR_HEIGHT - Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.action_cancel),
                        )
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = Spacing.sm),
                    )
                }

                // 底部操作条：同样只淡出**底色**，按钮与文字**不**淡出
                //（它们必须始终可读可点 —— 全透会让按钮压在输入框上没法看）。
                //
                // ⚠️ 这里是本页与列表页**唯一的有意差异**：列表页底部是**悬浮胶囊**
                //（[io.vaultix.vaultix.ui.shell.VaultixBottomDock]，四周留白、底部透出内容），
                // 沉浸感来得很容易；而本页是同一扇 `Dialog` 窗口里的**满宽操作条**，
                // 底部必须一直可点 ⇒ 保留"淡出底色"这一档，不改成悬浮胶囊。
                // ⇒ 若哪天要做成胶囊，改的是这里，不是在外面再叠一层。
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = barAlpha))
                        .navigationBarsPadding()
                        .padding(horizontal = Spacing.xl, vertical = Spacing.lg),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (destructive != null) {
                        destructive()
                        Spacer(Modifier.width(Spacing.sm))
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    Spacer(Modifier.width(Spacing.sm))
                    TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                        Text(confirmLabel)
                    }
                }
            }
        }
    }
}

/**
 * 正文要为一条**叠加栏**让出的高度 = `系统栏内边距 + 栏自身高度`。
 *
 * ## ⚠️ 这个函数修的是一个真实 bug（2026-09-20 用户反馈）
 *
 * 「新建密码条目 / 验证码 / 卡包界面，不透明，上边标题部分和下方部分遮住了显示内容。」
 *
 * 两条栏的 modifier 链都是 `background(surface)` → `statusBarsPadding()`/`navigationBarsPadding()`
 * → 自身 padding ⇒ 它们占据的高度是 **insets + 栏高**。
 * 而正文此前只让了**栏高**这一个常量，于是：
 *
 * | | 栏实际占位 | 正文让位 | 差额 |
 * |---|---|---|---|
 * | 顶部 | 状态栏 24 + 56 | 56 | **少让 24dp**（状态栏高的机型更多） |
 * | 底部 | 手势条 24 + 80 | 80 | **少让 24dp** |
 *
 * 正文首/末元素正好落在这条**不透明**色带底下 ⇒ 就是用户看到的"被遮住"。
 *
 * ## 为什么取实测 insets 而不是再加一个常量
 *
 * 状态栏/手势条高度随机型、分屏、折叠屏、分辨率而变（24 / 36 / 48dp 都见过）。
 * `TITLE_BAR_HEIGHT + 常量` 只是把误差从"少让"换成"多让"，换台机型仍然偏。
 * 这里读的是与两条栏**同一份** insets，两者因此永远对齐。
 *
 * ## 为什么要 `maxOf` 兜底
 *
 * 极端情况下 insets 可能读到 0（全屏 / 桌面模式 / insets 尚未分发）。
 * 那时不能退化成"零让位"，否则内容直接压在按钮上 —— 同一个 bug 换个成因再来一次。
 * 故下限取栏自身高度。
 *
 * @param systemBarInset 该侧的系统栏内边距。
 * @param barHeight 栏自身高度（**不含**系统栏内边距）。
 */
internal fun contentClearance(systemBarInset: Dp, barHeight: Dp): Dp =
    maxOf(barHeight, systemBarInset + barHeight)

/**
 * 判定「已经滚动过」的阈值（dp）—— 超过它两条栏的背景就开始淡出。
 *
 * 与列表页 [VaultixExpressiveTopBar] 用同一个值（`Spacing.sm`），保证两边
 * "滚多少才算滚动"的手感一致。
 */
private val COLLAPSE_THRESHOLD_DP = Spacing.sm

/** 栏背景淡出的时长（ms）—— 与列表页顶栏同值，切换手感一致。 */
private const val BAR_FADE_MS = 200

/**
 * 标题栏**自身**高度（不含状态栏内边距）：IconButton 48dp + 顶部内边距 8dp。
 *
 * ⚠️ 它**不是**正文要给的全部让位 —— 标题栏还额外带 `statusBarsPadding()`。
 * 正文的让位见 [contentClearance]（必须把状态栏内边距一起算上）。
 */
private val TITLE_BAR_HEIGHT = 56.dp

/**
 * 底部操作条**自身**高度（不含手势条内边距）：按钮 48dp + 上下内边距 16dp×2。
 *
 * ⚠️ 同上：它**不含** `navigationBarsPadding()`，别直接拿它当正文的底部让位。
 */
private val ACTION_BAR_HEIGHT = 80.dp
