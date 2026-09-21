/*
 * Vaultix — app:ui:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.common

import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.BottomAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.theme.Spacing

/**
 * **全屏编辑壳**：满宽标题栏 + 可滚动正文 + 满宽操作条，两条栏都带**渐变保护层**并**叠在正文之上**。
 *
 * ⚠️ 2026-09-13 第二轮用户反馈：「编辑条目 / 验证码条目不是全屏显示，看起来不舒服。」
 *
 * 这两个编辑界面原本都是 `AlertDialog` —— 居中卡片、宽度被平台硬限制在 ~280dp，
 * 而条目编辑表单有「身份 18 字段 + 自定义字段」，塞进那个宽度里必然局促、必须蜷着滚动。
 *
 * 现在统一成**整页**：`Dialog(usePlatformDefaultWidth = false)` + 全屏 `Surface`。
 * 为什么不直接用一条路由去承载：编辑是**模态**的（它必须阻断「编辑中又被别处改掉」），
 * 走路由就要自己在导航栈上模拟模态语义；`Dialog` 天然就是模态，且不改动导航图。
 *
 * ## 布局契约（三个区域）
 *
 * - **顶部** [TitleBar]：标题 + 关闭（关闭 = 取消，语义与「返回」一致）。满宽、常驻不隐藏。
 * - **中部**：`weight(1f)` + `verticalScroll` —— 表单再长也不会把按钮顶出屏幕，
 *   且 `imePadding()` 在壳上统一处理，键盘弹出时输入框不会被遮；
 *   **两侧让位由 [contentClearance] 按实测 insets 算出**，正文首/末元素因此不会钻到栏底下。
 * - **底部** [ActionBar]：取消 / 确认。满宽，随滚动自动隐藏 / 出现（**不改变正文让位**）。
 *
 * ## ⚠️ 上下两条栏的形态演进（四轮返工，别重走）
 *
 * | 轮次 | 形态 | 用户反馈 | 结论 |
 * |---|---|---|---|
 * | 一 | 满宽不透明色带，让位只算栏高 | 「上边标题部分和下方部分遮住了显示内容」 | 让位少算了 insets，真 bug |
 * | 二 | 满宽 + 随滚动淡出 | 「页面不沉浸，上下也不是透明」 | 淡出方向不对，M3 语义是 scrolled-under |
 * | 三 | **悬浮胶囊** | 「上方下方还有黑色的…造型不好看」 | ❌ **把问题弄严重了**（见下） |
 * | 四 | 胶囊缩短 | 「上下胶囊都好长，太碍眼了」 | ❌ `RoundedCornerShape(50)` 在扁容器上失效 |
 * | 五 | **满宽薄栏 + 渐变保护层** | — | ✅ 定稿 |
 *
 * **第三轮为什么反而更糟**：胶囊四周留了白，恰好把 `Dialog` 窗口的**黑色背景**露出来。
 * 黑带从来不是栏的形状问题 —— 真根因在窗口层，见函数体内 [SideEffect] 上方那段说明。
 * **满宽栏反而能盖住它**，这是"改成胶囊后黑带更明显"的全部原因。
 *
 * **第四轮为什么形状不成立**：`RoundedCornerShape(Int)` 是**百分比**语义。本壳里栏是
 * 「宽满屏 × 高 48~72dp」的扁容器，50% 半径被**高度钳死** ⇒ 两端半圆、中间一大段直线
 * ⇒ 观感是**扁椭圆枕头**。M3 里没有任何组件用这种形状；它只在"宽高接近"的元素上成立
 * （FAB、Chip、列表页底栏的方形「+」按钮）。
 *
 * ## 定稿形态的依据（官方 edge-to-edge 指南）
 *
 * [developer.android.com/design/ui/mobile/guides/layout-and-content/edge-to-edge](https://developer.android.com/design/ui/mobile/guides/layout-and-content/edge-to-edge)
 *
 * - **Do**：小顶栏不吸顶时，加**与背景匹配的渐变**作为保护层；
 * - **Do**：系统栏保持半透明，让 UI 从下方滚过；
 * - **Don't**：渐变**与各 pane 的背景不匹配** ⟹ 端点必须用 `colorScheme.surface`
 *   （正文里 `FormGroupCard` 用的是 `surfaceContainerHighest`，**不同色**，见 [topGradientStops]）；
 * - **Don't**：**叠加**多层 status bar 保护 ⟹ 只有一层渐变，**不加** scrim、不加第二层色块。
 *
 * ## 滚动隐藏
 *
 * 底栏用 M3 官方 [BottomAppBarDefaults.exitAlwaysScrollBehavior] 状态机 —— 但**只借**
 * 它的 `nestedScrollConnection` + `heightOffset`，**不用** `BottomAppBar` 组件本体
 * （其默认 `containerColor = surfaceContainer` 与渐变端点不匹配、自带 `windowInsets`
 * 会与壳的让位体系双重叠加）。详见 [ActionBar]。
 *
 * ## 常见改动误区
 *
 * - 改 [TITLE_BAR_HEIGHT] / [ACTION_BAR_HEIGHT] 却忘了同步 [TitleBar] / [ActionBar]
 *   里 `Row` 的 `vertical = BAR_VERTICAL_PADDING` ⟹ 正文让位偏，退化成第一轮的 bug；
 * - 把三档渐变改回两档 ⟹ 栏体上半段就变半透明，标题读不清；
 * - 试图用 `Modifier.blur` 做保护层 ⟹ minSdk 26 上 API 31- 是 no-op、离屏渲染掉帧、OLED 下模糊纯黑仍是纯黑。
 *
 * @param title 顶栏标题。
 * @param onDismiss 关闭（顶部 ✕ / 系统返回 / 点外部）——调用方需自行处理「保存中不许关」。
 * @param onConfirm 底部主操作；[confirmEnabled] 为 `false` 时置灰（例如保存中）。
 * @param confirmLabel 底部主操作文案。
 * @param confirmEnabled 主操作是否可用。
 * @param destructive 破坏性操作（如验证码编辑里的「移除」）；为 `null` 时不画，排在「取消」左侧。
 * @param content 表单正文（[ColumnScope]，纵向排列；需要横排时内部自己套 `Row`）。
 */
// ⚠️ `@OptIn` 必须在 `@Composable` **之前**（两者都是注解，顺序无强制，但放一起更清楚）：
// `BottomAppBarDefaults.exitAlwaysScrollBehavior()` 是 `@ExperimentalMaterial3Api` 的，
// material3 1.5.0-alpha16 里**还没有** stable 替代品。
// 选择"显式 OptIn 到具体 API"而不是给整个文件/模块加开关 —— 后者会让以后新引入的实验性
// API 静默通过门禁。`.ai/tools/check_experimental_optin.py` 会校验这个作用域是否覆盖。
@OptIn(ExperimentalMaterial3Api::class)
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
    //
    // ⚠️ 第五轮修复后这个判据**变得更精确**：渐变层的不透明段用 `surface` 覆盖状态栏区域，
    // 且导航栏的对比度遮罩被主动关闭（见下方 SideEffect）⇒ 实际渲染色**恒等于** `surface`，
    // 判据与实际色不再有任何偏差。
    val surface = MaterialTheme.colorScheme.surface
    val surfaceArgb = surface.toArgb()
    val lightSurface = surface.luminance() > 0.5f

    // 底部栏的滚动隐藏：借用 M3 官方 `BottomAppBar` 的滚动行为状态机。
    //
    // ⚠️ 只借它的 `nestedScrollConnection` + `heightOffset`，**不用** `BottomAppBar` 组件
    // 本体 —— 组件自带的 `containerColor`（`surfaceContainer`，与渐变端点 `surface` 不匹配）
    // 和 `windowInsets` 处理（会与壳的让位体系双重叠加）都与本方案冲突，see KDoc 的官方 Don't。
    // 语义：内容向上拉（要看后面的内容）⇒ 底栏立刻收起；向下拉 ⇒ 立刻出现。
    val bottomBarScrollBehavior = BottomAppBarDefaults.exitAlwaysScrollBehavior()

    Dialog(
        onDismissRequest = onDismiss,
        // ⚠️ 2026-09-13 第二轮用户反馈：「全屏的时候上下不沉浸」。
        // `Dialog` 的窗口默认会**避开系统栏**（decorFitsSystemWindows = true）⇒ 全屏 Surface
        // 铺不到状态栏与手势条，那两条露出的是弹窗遮罩的灰底 —— 看起来就是上下"缺了一块"。
        // 关掉它让内容真正 edge-to-edge，再由壳自己按 insets 留出让位（与列表页顶栏的做法一致）。
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // ⚠️ **2026-09-20 第五轮：黑带的真根因在这里，不在栏的形状。**
        // （三次返工才定位到，前两次都改错了地方，第二次还把问题弄严重了。）
        //
        // 用户连续两轮截图里都有「上下黑色的带子」。错判过两次：
        //   ① 以为是不透明色带的观感问题 ⇒ 改成随滚动淡出（第二轮）；
        //   ② 以为是形状问题 ⇒ 改成悬浮胶囊（第三轮）。**第二次把问题弄严重了** ——
        //      胶囊四周留白，恰好让窗口的黑色从留白处露出来。
        //
        // 真根因两条：
        //   A. `targetSdk ≥ 35` 起，`window.statusBarColor` / `navigationBarColor`
        //      **是 no-op**（Android 15 改为强制 edge-to-edge，旧 API 失效）。下面原本那两行
        //      赋值是**死代码** —— 而 `@Suppress("DEPRECATION")` 把"它已不生效"这个信号
        //      也一起压掉了，所以一直没被发现。
        //   B. `Dialog` 的窗口类型是 `TYPE_APPLICATION_PANEL`，**不继承 Activity 的
        //      edge-to-edge / systemUiVisibility**；它的 window background **来自主题 =
        //      黑色**。只要窗口顶/底留了白（胶囊方案正是如此），黑色就露出来。
        //      ⇒ 满宽栏反而能盖住它，这是"改成胶囊后黑带更明显"的全部原因。
        //
        // 修法两条，缺一不可：
        //   ① 把**窗口背景**换成 `surface`。⚠️ **不是 `transparent`** —— 设透明只是"不再
        //      遮挡"，而 API 35+ 在三键导航下仍会按 window background 叠一层 **80% 遮罩**，
        //      结果还是近黑。设成 `surface` 后，遮罩叠在**与页面同色**的底上，得到的是
        //      "同色的加深"，符合官方 edge-to-edge 的「渐变保护必须匹配背景 pane」口径。
        //      为什么用 `setBackgroundDrawable` 而不是 `setBackgroundDrawableResource`：
        //      我们的底色是**运行时动态的**（浅色/深色/OLED 纯黑/动态取色），资源 id 表达
        //      不了；而 `decorView.setBackgroundColor` 只改 DecorView 层、**改不到 Window
        //      层**，遮罩仍按旧色算。
        //   ② 关掉**导航栏对比度遮罩**（`isNavigationBarContrastEnforced`，默认 `true`）。
        //      它会在三键导航下把导航栏压暗 20%，在底部制造一条"半黑带"——正是用户截图里
        //      底部那条。关掉后底部与顶部对称。（`isStatusBarContrastEnforced` 在 API 35+
        //      已是 no-op，无须碰。）
        // ⚠️ 必须在 Dialog 内容里取 parent（这时 parent 才是这扇 dialog 窗口）。
        SideEffect {
            val dialogWindow = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            // ⚠️ `@Suppress("DEPRECATION")` 只压**局部**（不是整个 `SideEffect`）：
            // 作用域精确等于对那个旧 API 的调用，其余代码的废弃警告仍能正常报出来
            // —— 这次的教训正是"用 suppress 把'这行已不生效'的信号一起压掉了"。
            @Suppress("DEPRECATION") // API 35 起 deprecated（edge-to-edge 语义变化），但它仍是
                                     // **唯一**能真正改 Window 层背景的 API，覆盖 minSdk 26~37 全区间。
            val backgroundDrawable = ColorDrawable(surfaceArgb)
            dialogWindow.setBackgroundDrawable(backgroundDrawable)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                dialogWindow.isNavigationBarContrastEnforced = false
            }
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
                // ── 让位高度 = 系统栏内边距 + 栏的**垂直总占位**（**不是**只让栏体高）。
                //
                // ⚠️ 2026-09-20 第一轮用户反馈：「新建密码条目 / 验证码 / 卡包界面，不透明，
                // 上边标题部分和下方部分遮住了显示内容。」
                // 根因就在这里：两条栏的 modifier 链是「栏底色 → insets padding → 自身 padding」，
                // **实际占位 = 系统栏内边距 + 栏体高**；而正文此前只让了一个"纯栏高"常量
                // ⇒ 正文首/末元素正好钻到那条**不透明**色带底下。
                // 顶部少让一个状态栏（普通机型 24~48dp），底部少让一个手势条（24~48dp）。
                //
                // 让位改用**实测**而不是常量相加：状态栏/手势条高度随机型、分屏、折叠屏
                // 变化，`栏高 + 常量` 只是把误差从"少让"变成"多让"，换个机型还会偏。
                // 这里读的是与两条栏**同一份** [WindowInsets]，两者因此永远对齐。
                //
                // ⚠️ 仍保留常量作为**下限兜底**：极端情况下 insets 可能为 0（如全屏/桌面模式），
                // 那时不能退化成"零让位"，否则内容会直接压在按钮上。
                val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        // ⚠️ `nestedScroll` 挂在**滚动容器自身**（不是外层 Box）—— 与
                        // `VaultListScreen.kt` 挂在 `LazyColumn` 上的既有口径一致。
                        // 它是**纯 Compose 机制**（走 modifier 链的 NestedScrollDispatcher），
                        // 与宿主是 Activity 还是 Dialog **无关**，故在 Dialog 里同样有效。
                        .nestedScroll(bottomBarScrollBehavior.nestedScrollConnection)
                        .padding(horizontal = Spacing.xl)
                        .padding(
                            top = contentClearance(statusBar, TITLE_BAR_HEIGHT),
                            bottom = contentClearance(navBar, ACTION_BAR_HEIGHT),
                        ),
                    content = content,
                )

                TitleBar(title = title, onDismiss = onDismiss, statusBarInset = statusBar)
                ActionBar(
                    confirmLabel = confirmLabel,
                    confirmEnabled = confirmEnabled,
                    onDismiss = onDismiss,
                    onConfirm = onConfirm,
                    destructive = destructive,
                    navBarInset = navBar,
                    // ⚠️ `heightOffset` 挂在 `behavior.**state**` 上，不在 behavior 上：
                    // `BottomAppBarScrollBehavior` 只有 `state` / `nestedScrollConnection` /
                    // `isPinned` / `*AnimationSpec` 五个成员，offset 是 `BottomAppBarState` 的。
                    // 同理 `collapsedFraction` 也在 state 上（若哪天要按收起比例淡出，用它）。
                    scrollOffset = { bottomBarScrollBehavior.state.heightOffset },
                )
            }
        }
    }
}

/**
 * 顶部标题栏：**满宽细栏 + 渐变保护层**，常驻（不随滚动隐藏、不折叠）。
 *
 * ## 为什么不是悬浮胶囊（第四轮的错误，用户第四轮否掉）
 *
 * `RoundedCornerShape(50)` 的语义是**百分比**。在全屏壳里栏是「宽满屏 × 高 48~72dp」的
 * 扁容器，50% 半径被**高度钳死** ⇒ 两端画出半圆、中间留一大段直线 ⇒ 观感是**扁椭圆枕头**
 * （用户原话「好长」「太碍眼」）。M3 里没有任何组件用这种形状 —— 它只在"宽高接近"的元素上
 * 成立（列表页底栏的方形「+」按钮、FAB、Chip）。
 *
 * ## 定稿结构（自上而下两层叠加）
 *
 * ```
 * Box（渐变层宿主；高 = 状态栏 + 栏体 + 过渡尾段）
 *  ├─ 渐变层（drawBehind，见 [topGradientStops]）—— 不透明段覆盖状态栏区域 = 状态栏沉浸
 *  └─ Row（标题 + 关闭）—— 用 padding 让开状态栏，标题因此不被状态栏压
 * ```
 *
 * ⚠️ 渐变层与栏体读的是**同一份** `statusBarInset`，两者因此永远对齐。
 * ⚠️ 为什么用 `drawBehind` 而不是嵌一个 `Box(Modifier.background(Brush))`：
 *    `drawBehind` **零额外布局节点**（只在绘制阶段画），且渐变天然跟随宿主尺寸，
 *    不需要手算渐变端点坐标。名字即语义 —— 它画在该节点背景**之前**，即"栏的后面"。
 * ⚠️ **不用** `Modifier.blur`：minSdk 26 的 API 31- 是 no-op（设备分叉）、离屏渲染掉帧耗电、
 *    且 OLED 下模糊纯黑仍是纯黑。渐变不需要采样本层之外的内容，本来就不该用 blur。
 *
 * @param statusBarInset 状态栏内边距（实测值，与正文让位同一份）。
 */
@Composable
private fun BoxScope.TitleBar(title: String, onDismiss: () -> Unit, statusBarInset: Dp) {
    // ⚠️ 用 `MaterialTheme.colorScheme.surface` 而**不是** `LocalContentColor`：
    // 壳里包着一层 `Surface`，本栏是其内容，画出来的底色才是渐变端点该匹配的东西。
    val surface = MaterialTheme.colorScheme.surface
    // 本层总高先算出来：渐变要把尾段折算成档位比例，且**两端读数必须同源**
    // （`TitleBar` 的 `.height(...)` 与 `topGradientStops` 的比例分母是同一个数）。
    val layerHeight = layerHeight(statusBarInset, TITLE_BAR_HEIGHT)
    Box(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .height(layerHeight)
            .drawBehind {
                drawRect(
                    brush = Brush.verticalGradient(
                        colorStops = topGradientStops(surface, statusBarInset, layerHeight),
                    ),
                )
            },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = statusBarInset)
                .padding(vertical = BAR_VERTICAL_PADDING),
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
    }
}

/**
 * 底部操作条：**满宽细栏 + 渐变保护层**，随滚动自动隐藏 / 出现。
 *
 * ## 滚动隐藏
 *
 * 用 M3 官方的 [BottomAppBarDefaults.exitAlwaysScrollBehavior] 状态机，把它的
 * `heightOffset` 映射到本栏的 `translationY`。语义（官方定义）：
 * 内容向上拉 ⇒ 立刻收起；向下拉 ⇒ 立刻出现。
 *
 * ⚠️ 隐藏**不改变正文让位**（[ACTION_BAR_HEIGHT] 恒定计入 `contentPadding`）：
 * 让位是静态 padding、无法随滚动变；两个方向的错误代价不对称 ——
 * 多让最多是末尾略带留白（且滚到底时底栏会自动展开把留白填上），
 * 少让则末元素被不透明栏盖住（= 用户已报过的老 bug）。`exitAlways` 保证用户
 * 真要读末尾内容时底栏是**在的**，故按「底栏可见」让位恰好正确。
 *
 * ## ⚠️ `navBarInset` 的来源必须与主界面**一致**（不要"修正"它）
 *
 * 第六轮曾怀疑「底部白横条下沿不是屏幕底」是 `navigationBars` 取到了手势热区
 * （约 45dp）而非可视内容区所致。核对后**否掉**：主界面
 * `VaultixBottomDock.kt:87` 读的是**同一个** `WindowInsets.navigationBars`。
 * 换一个 insets 源反而会让编辑壳与主界面**分叉** —— 而用户的判据恰恰是
 * 「完全和主界面滑动的时候效果不搭调」。
 * ⇒ 真正的成因是渐变的**不透明段**把这片 inset 空白整个涂满了（见
 * [bottomGradientStops]），本轮改的是渐变分布，**不是** insets 源。
 *
 * @param navBarInset 手势条内边距（实测值，与正文让位同一份）。
 * @param scrollOffset 取当前滚动引起的位移（像素）。用 lambda 传而不是直接传值，
 *   是为了不在每次滚动重组时都重新组合本栏 —— 只有 `graphicsLayer` 的绘制阶段会读它。
 */
@Composable
private fun BoxScope.ActionBar(
    confirmLabel: String,
    confirmEnabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    destructive: (@Composable () -> Unit)?,
    navBarInset: Dp,
    scrollOffset: () -> Float,
) {
    // ⚠️ 用 `MaterialTheme.colorScheme.surface` 而**不是** `LocalContentColor`：
    // 壳里包着一层 `Surface`，本栏是其内容，画出来的底色才是渐变端点该匹配的东西。
    val surface = MaterialTheme.colorScheme.surface
    // 本层总高需要先算出来：`bottomGradientStops` 要把尾段折算成档位比例（见其 KDoc）。
    val layerHeight = layerHeight(navBarInset, ACTION_BAR_HEIGHT)
    Box(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .graphicsLayer { translationY = scrollOffset() }
            .height(layerHeight)
            .drawBehind {
                drawRect(
                    brush = Brush.verticalGradient(
                        colorStops = bottomGradientStops(surface, navBarInset, layerHeight),
                    ),
                )
            },
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = navBarInset)
                .padding(horizontal = Spacing.lg, vertical = BAR_VERTICAL_PADDING),
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

/**
 * 顶部渐变保护层的颜色档位。
 *
 * ## 档位语义（自上而下，`0f` = 屏幕顶）
 *
 * ```
 * 0f ────────────────────────────── 屏幕顶
 *    surface，alpha = 1              ← 覆盖状态栏 inset = 状态栏沉浸
 * (状态栏 + 栏体) ─────────────────  ★ 渐变起点：栏体下沿（= 正文首像素的位置）
 *    surface → alpha 0                ← 过渡尾段，落在**内容侧**
 * (状态栏 + 栏体 + 尾段) ────────── 全透明
 * ```
 *
 * ## ⚠️ 2026-09-21 第六轮修正：尾段必须落在**内容侧**
 *
 * 上一版的档位是「不透明段 = `inset + 栏体`，**尾段在层底**」。数学上等价，
 * **观感上完全相反** —— 用户第六轮原话：「上方的白条显得很大……不沉浸，滑动的时候也不透明」。
 *
 * 根因：**渐变层最后那 `GRADIENT_TAIL` 段（全在层底）恰好落在"没有内容"的地方。**
 * 逐像素模拟（`SESSION-2026-09-20.md` §10）显示：渐变在 117~141dp 才从 1 → 0，
 * 而 >117dp 是系统栏 inset 的空白区，那里本来就是纯 `surface` ⇒ **渐变等于没画**，
 * 视觉上仍是"一条硬边白带"。真正有内容穿过（会露出卡片灰）的 12~60dp 区间反而是 1.00 恒不透明。
 *
 * ⇒ 尾段移到**栏体下沿之下**：不透明段仍是 `inset + 栏体`（让位恒等式不变），
 * 但"全透明点"落在 `inset + 栏体 + 尾段`，中间这段正是内容滚过的地方 ⇒ 才有真实的淡出。
 *
 * ## 为什么不透明段还是「系统栏 + 栏体」 = [contentClearance]
 *
 * `FormGroupCard` 用的是 `surfaceContainerHighest`，与 `surface` **不同色**。
 * 渐变端点必须用 `surface`（官方 Don't：「渐变保护与各 pane 背景**不匹配**」），
 * 于是"卡片会不会透进不透明段"就成了真问题。答案是不会 —— 因为**正文让位
 * [contentClearance] 恰好等于不透明段的长度**，正文的第一个像素永远从「栏体下沿」开始，
 * 与不透明段**几何上不重叠**。尾段（半透明区）里卡片可见，而那**正是设计意图**：
 * 官方要的就是"内容从栏下方滚过时可见"。
 *
 * ⚠️ 不透明比例**必须按实测 inset 算**，不能写死：状态栏 0/24/36/48dp 四种机型下
 * `(inset + 栏体) / layerHeight` 各不相同 —— 写死一个数会让某类机型的标题区**提前**变透明。
 * （对照表见 `SESSION-2026-09-20.md` §10.4。）
 *
 * @param surface 栏与页面的共同底色（`colorScheme.surface`）。
 * @param statusBarInset 状态栏内边距（实测值）。
 * @param layerHeight 本层总高（= [layerHeight]`(statusBarInset, TITLE_BAR_HEIGHT)`），
 *   用来把 [GRADIENT_TAIL] 折算成档位比例。
 */
private fun topGradientStops(
    surface: Color,
    statusBarInset: Dp,
    layerHeight: Dp,
): Array<Pair<Float, Color>> {
    // ⚠️ 分母用调用方传进来的 `layerHeight`（源头是 [layerHeight]），而不是在这里重算：
    // 它与 `TitleBar` 的 `.height(...)` 是**同一个数**，因此不透明段与栏的几何恒等对齐
    // —— 这是"卡片不会透进不透明段"这一论断能成立的前提（见本节 KDoc）。
    // ⚠️ `Dp / Dp` 返回的是 **Float**（不是 Dp）⇒ 这里**不能**再写 `.value`。
    // 注释会过时、类型不会 —— 这是编译器（CI 的 Build Debug APK）抓出来的一处。
    val opaqueFraction: Float = (statusBarInset + TITLE_BAR_HEIGHT) / layerHeight
    return arrayOf<Pair<Float, Color>>(
        GRADIENT_START to surface,
        // ★ 这里是「栏体下沿」，不是「层底」—— 尾段落在这条线**之后**（内容侧）。
        opaqueFraction to surface,
        GRADIENT_END to surface.copy(alpha = 0f),
    )
}

/**
 * 底部渐变保护层的颜色档位。
 *
 * ## 档位语义（自下而上，`1f` = 屏幕底）
 *
 * 与 [topGradientStops] **镜像**，但**必须按 inset 折算比例**（不再能用上一版的倒序技巧，
 * 原因见下）：
 *
 * ```
 * 0f ────────────────────────────── 层顶（= 屏幕底 - (手势条 + 栏体 + 尾段)）
 *    surface，alpha = 0              ← ★ 渐变起点：栏体上沿之上，落在**内容侧**
 * (尾段) ───────────────────────────  过渡尾段：内容从这里滚过时开始可见
 * (尾段 + 栏体) ────────────────────  ★ 栏体上沿：到这里已经完全不透明
 *    surface，alpha = 1              ← 覆盖栏体 + 手势条 inset
 * 1f ────────────────────────────── 屏幕底
 * ```
 *
 * ## ⚠️ 2026-09-21 第六轮修正：这是本轮的核心缺陷
 *
 * 上一版用倒序写法把不透明段表达为「尾段之上全部不透明」：
 * `opaqueUntil = 1f - GRADIENT_TAIL / layerHeight` —— 等价于**把渐隐死死钉在层底**。
 * 而层底 = `navBarInset` 所在的位置，**那是没有内容的空白区**（系统栏 inset 里本来就
 * 没有任何元素在画）。逐像素模拟（`SESSION-2026-09-20.md` §10）：层高 141dp 时
 * 不透明段 0~117dp、渐隐段 117~141dp，而 117dp 以下才有卡片/按钮 ——
 * ⇒ **渐变全程发生在空白上，视觉上等于没画**；按钮上沿处两侧都是 1.00 ⇒ **硬边**。
 *
 * 这就是用户第六轮看到的东西：「下方保存和取消……一条很大很宽的白色横条……
 * 滑动的时候也不透明。完全和主界面滑动的时候效果不搭调。」
 *
 * ## 为什么现在需要 `navBarInset` 入参（上一版特意省掉了它）
 *
 * 上一版 KDoc 的理由是「倒序写法可以避开 inset，省一个参数」—— 那个"省"恰恰把渐隐
 * 钉到了错的地方。**渐隐段的位置在语义上依赖 inset**（inset 有多高，层底就有多厚的空白），
 * 所以 inset 是**必要信息**，不是可以消掉的冗余。
 * ⚠️ 结论：能省参数 ≠ 该省参数；当被省掉的信息决定语义时，省它就是错。
 *
 * ⚠️ 不透明段的长度仍等于「手势条 + 栏体」= [contentClearance]，"卡片不会透出来"的
 * 依据见 [topGradientStops]。
 *
 * @param surface 栏与页面的共同底色（`colorScheme.surface`）。
 * @param navBarInset 手势条内边距（实测值）—— 决定底部这片空白有多厚，即渐隐段该从哪开始。
 * @param layerHeight 本层总高（= [layerHeight]`(navBarInset, ACTION_BAR_HEIGHT)`）。
 */
private fun bottomGradientStops(
    surface: Color,
    navBarInset: Dp,
    layerHeight: Dp,
): Array<Pair<Float, Color>> {
    // ⚠️ 同 [topGradientStops]：`Dp / Dp` 已是 **Float**，不要再 `.value`。
    // 「内容侧可见的」不透明段 = 屏幕底到栏体上沿 = inset + 栏体。
    // 这一段的长度**必须**与 `contentClearance(navBarInset, ACTION_BAR_HEIGHT)` 相等。
    val opaqueFraction: Float = (navBarInset + ACTION_BAR_HEIGHT) / layerHeight
    // 渐隐段的占比 = 尾段 / 层高；它落在 [opaqueFraction, 1f] 之外的**层顶那一侧**。
    val fadeStart: Float = GRADIENT_END - opaqueFraction
    return arrayOf<Pair<Float, Color>>(
        // ⚠️ 用 `GRADIENT_START`(0f) 作第一档而非负值：`colorStops` 的档位必须在 [0,1]。
        // ★ 层顶 = 内容侧，这里必须已经全透明 —— 上一版把不透明写到了 `1f - 尾段`，正是缺陷所。
        GRADIENT_START to surface.copy(alpha = 0f),
        // ★ 到这里（栏体上沿）为止都不透明；之后（更靠屏幕底）一直是 1f。
        fadeStart to surface,
        GRADIENT_END to surface,
    )
}

/**
 * 正文要为一条**满宽栏**让出的高度 = `系统栏内边距 + 栏体高`。
 *
 * ## ⚠️ 这个函数修的是一个真实 bug（2026-09-20 第一轮用户反馈）
 *
 * 「新建密码条目 / 验证码 / 卡包界面，不透明，上边标题部分和下方部分遮住了显示内容。」
 *
 * 两条栏的 modifier 链都是「栏底色 → insets padding → 自身 padding」
 * ⇒ 它们占据的高度是 **insets + 栏高**。
 * 而正文此前只让了**栏高**这一个常量，于是：
 *
 * | | 栏实际占位 | 正文让位 | 差额 |
 * |---|---|---|---|
 * | 顶部 | 状态栏 24 + 56 | 56 | **少让 24dp**（状态栏高的机型更多） |
 * | 底部 | 手势条 24 + 80 | 80 | **少让 24dp** |
 *
 * 正文首/末元素正好落在那条**不透明**色带底下 ⇒ 就是用户看到的"被遮住"。
 *
 * ## 为什么取实测 insets 而不是再加一个常量
 *
 * 状态栏/手势条高度随机型、分屏、折叠屏、分辨率而变（24 / 36 / 48dp 都见过）。
 * `栏高 + 常量` 只是把误差从"少让"换成"多让"，换台机型仍然偏。
 * 这里读的是与两条栏**同一份** insets，两者因此永远对齐。
 *
 * ## 为什么要 `maxOf` 兜底
 *
 * 极端情况下 insets 可能读到 0（全屏 / 桌面模式 / insets 尚未分发）。
 * 那时不能退化成"零让位"，否则内容直接压在按钮上 —— 同一个 bug 换个成因再来一次。
 * 故下限取栏自身高度。
 *
 * ## ⚠️ 入参 `barHeight` 的口径：始终是「该侧栏体总高」，**不含**系统栏内边距
 *
 * 第五轮栏回到满宽后，这个口径**又简化了**：不再有胶囊留白，`barHeight` 就是
 * "栏体自身高"（[TITLE_BAR_HEIGHT] / [ACTION_BAR_HEIGHT]）。公式与 `maxOf` 从头到尾没变过。
 *
 * ⚠️ **它与渐变保护层不透明段的长度必须一致**（都是 `inset + barHeight`）——
 * 这不是巧合，而是"卡片不会透进不透明段"的**全部依据**，见 [topGradientStops] 的说明。
 *
 * @param systemBarInset 该侧的系统栏内边距。
 * @param barHeight 该侧**栏体**高（**不含**系统栏内边距）。
 */
internal fun contentClearance(systemBarInset: Dp, barHeight: Dp): Dp =
    maxOf(barHeight, systemBarInset + barHeight)

/**
 * 渐变保护层的**总高** = `系统栏内边距 + 栏体高 + 过渡尾段`。
 *
 * ## 为什么抽成一个函数
 *
 * 三个消费者必须读到**同一个**数：[TitleBar] / [ActionBar] 的 `.height(...)`、
 * [bottomGradientStops] 折算尾段比例时用的 `layerHeight`、以及未来若要复核让位时的参照。
 * 一旦有人手抄一遍（例如写 `navBarInset + ACTION_BAR_HEIGHT + GRADIENT_TAIL`），
 * 改常量时就可能只改一处 —— 那正是 [TITLE_BAR_HEIGHT] 那条 ⚠️ 警告描述的翻车方式。
 *
 * ## 为什么公式是「加」而不是「取大」
 *
 * 与 [contentClearance] 的 `maxOf` 不同：那条是**兜底**（insets 为 0 时别退化成零让位），
 * 这条是**几何**——尾段是栏体外面额外的一条过渡带，和 insets 无关，永远要加上。
 *
 * @param systemBarInset 该侧系统栏内边距（实测值）。
 * @param barHeight 该侧栏体高（**不含**系统栏内边距）。
 */
private fun layerHeight(systemBarInset: Dp, barHeight: Dp): Dp =
    systemBarInset + barHeight + GRADIENT_TAIL

/**
 * M3 按钮的**最小触摸目标高度**（`ButtonDefaults.MinHeight` / `IconButton` 的 48dp 规范值）。
 *
 * 提为具名常量是为了让下面两条高度公式**自解释** —— 裸写 `48.dp` 读不出"这是触摸目标下限"，
 * 而它恰恰是"栏体高不是随手定的"的全部理由。
 *
 * ⚠️ **必须声明在 [TITLE_BAR_HEIGHT] / [ACTION_BAR_HEIGHT] 之前**：Kotlin 顶层属性按
 * **声明顺序**初始化，被引用者先声明，否则编译报 `must be initialized`
 * （`.ai/tools/check_compile_smells.py` 的规则 B 专查这一类）。
 */
private val BUTTON_MIN_HEIGHT = 48.dp

/**
 * 栏体的垂直内边距。`IconButton`/`TextButton` 的 48dp 触摸目标 + 它 ×2 = 栏体高。
 *
 * ⚠️ **2026-09-21 第六轮：`Spacing.md`(12dp) → `Spacing.xs`(4dp)**，两栏由 72dp 收到 56dp。
 *
 * 用户第六轮原话：「下方保存和取消**占用的空间太大**了，一条很大很宽的白色横条，
 * 上方也是一整片的白色横条」。逐像素核对：底部白条实测约 87dp（含 inset），
 * 而里面**真正有内容的按钮只有 48dp** —— 多出来的 24dp 是纯留白
 * （上下各 `Spacing.md` = 12dp），栏体因此比内容高 **50%**。
 *
 * ⇒ 只去掉**超出的留白**，触摸目标不缩：4dp 是 `Spacing` 里最小的一档，
 * 恰好充当"按钮与栏边的呼吸位"，不再是肉眼可辨的一大块空白。
 * 与用户自己项目 Bastion 的 `NoteEditorTopBarHeight = 64.dp` 同量级
 * （见 `reference/bastion/.../AddEditNoteScreen.kt`）。
 *
 * ⚠️ **两个栏共用同一个值**（顶部与底部都是这一档）—— 一屏里两套间距规格是观感廉价的首要
 * 来源（`8.4`）。
 *
 * ⚠️ **三处联动铁三角**，改这个值时必须同步核对：① 本常量 ② [TitleBar] / [ActionBar] 里
 * `Row` 的 `padding(vertical = BAR_VERTICAL_PADDING)`（两处都直接读本常量，无需改）
 * ③ 渐变比例的**分母** [layerHeight]（它引用下面的高度常量，自动跟随）。
 *
 * ⚠️ 同样受"声明顺序"约束：它在下面两条高度公式里被引用，故排在它们之前。
 */
private val BAR_VERTICAL_PADDING = Spacing.xs

/**
 * 顶部栏体高（**不含**状态栏内边距）= `IconButton` 48dp + [BAR_VERTICAL_PADDING] ×2 = **56dp**。
 *
 * ⚠️ 必须与 [TitleBar] 里 `Row` 的 `padding(vertical = BAR_VERTICAL_PADDING)` **严格对齐**：
 * 两处若不同步，正文让位就会偏，退化成第一轮那个"内容被遮住"的 bug。
 */
private val TITLE_BAR_HEIGHT = BUTTON_MIN_HEIGHT + BAR_VERTICAL_PADDING * 2

/**
 * 底部栏体高（**不含**手势条内边距）= `TextButton` 48dp + [BAR_VERTICAL_PADDING] ×2 = **56dp**。
 *
 * ⚠️ 同上，必须与 [ActionBar] 里 `Row` 的 `vertical = BAR_VERTICAL_PADDING` 严格对齐。
 *
 * ⚠️ 它还直接参与 [bottomGradientStops] 的 `opaqueFraction` 计算 —— 与正文让位
 * （[contentClearance]）读的是**同一个常量**，这正是"不透明段 ≡ 让位长度"恒等式成立的原因。
 */
private val ACTION_BAR_HEIGHT = BUTTON_MIN_HEIGHT + BAR_VERTICAL_PADDING * 2

/**
 * 渐变保护层"从完全不透明过渡到全透明"的**尾段长度**。
 *
 * ⚠️ 它落在**半透明区**，内容从这里穿过时可见 —— 而那**正是设计意图**（官方 edge-to-edge
 * 的 Do 项要的就是"内容从栏下方滚过时可见"），**不是**缺陷，不要试图消除它。
 *
 * ⚠️ 2026-09-21 第六轮起，尾段的**位置**也是设计要点：必须落在**有内容的那一侧**
 * （顶部在栏体下沿之下、底部在栏体上沿之上）。放在 inset 空白区等于没做 —— 见
 * [topGradientStops] / [bottomGradientStops] 的 KDoc。
 */
private val GRADIENT_TAIL = Spacing.xl

/**
 * 渐变档位的两个端点（避免 `colorStops` 数组里的裸字面量触发 detekt `MagicNumber`）。
 *
 * detekt 配了 `ignoreNamedArgument: true`，但 `arrayOf(0f to …)` 里的字面量是**位置参数**、
 * 仍会被查 ⇒ 提为具名常量。
 */
private const val GRADIENT_START = 0f
private const val GRADIENT_END = 1f
