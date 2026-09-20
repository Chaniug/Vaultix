/*
 * Vaultix — app:ui:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.common

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.luminance
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
 * 而条目编辑表单有「身份 18 字段 + 自定义字段」，塞进那个宽度里必然局促、必须蜷着滚动。
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
                // ── 让位高度 = 系统栏内边距 + 栏的**垂直总占位**（**不是**只让栏体高）。
                //
                // ⚠️ 2026-09-20 第一轮用户反馈：「新建密码条目 / 验证码 / 卡包界面，不透明，
                // 上边标题部分和下方部分遮住了显示内容。」
                // 根因就在这里：两条栏都带 `statusBarsPadding()` / `navigationBarsPadding()`
                // （**之外**再叠自己的 padding），而正文只让了一个"纯栏高"常量
                // ⇒ 正文首/末元素正好钻到那条**不透明**色带底下。
                // 顶部少让一个状态栏（普通机型 24~48dp），底部少让一个手势条（24~48dp）。
                //
                // ⚠️ 第三轮把栏改成胶囊后，占位口径再多一层 → 见 [TITLE_BAR_OCCUPIED] /
                // [ACTION_BAR_OCCUPIED]（含胶囊的 gap 与 margin 留白）。
                //
                // 让位改用**实测**而不是常量相加：状态栏/手势条高度随机型、分屏、折叠屏
                // 变化，`栏高 + 常量` 只是把误差从"少让"变成"多让"，换个机型还会偏。
                // 这里读的是与两条栏**同一份** [WindowInsets]，两者因此永远对齐。
                //
                // ⚠️ 仍保留常量作为**下限兜底**：极端情况下 insets 可能为 0（如全屏/桌面模式），
                // 那时不能退化成"零让位"，否则内容会直接压在按钮上。
                val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

                // ── 沉浸：两条栏改**悬浮胶囊**，内容从胶囊**外侧留白**穿过。
                //
                // ⚠️ 2026-09-20 第三轮用户反馈：「初始状态下，上方，下方还有黑色的，你说是胶囊。
                // 但是感觉这样的造型不好看。优化一下。」
                //
                // 前两轮的口径是"满宽栏 + 底色随滚动淡出"，但它有**两个都难看的状态**：
                //   ① 未滚动：`alpha = 1` ⇒ 两条**满宽色带**。深色模式接近黑、**OLED 纯黑**下
                //      `surface` 就是 `#000000` ⇒ 上下各一条**满宽纯黑带**（既然栏与页面同色，
                //      用户看不出"这是栏"，只觉得上下被切了两刀）；
                //   ② 滚动后：`alpha = 0` ⇒ 标题文字与卡片内容**同区同色叠印**。
                // 这正是用户说的"造型不好看"。
                //
                // ⇒ 改法与 [io.vaultix.vaultix.ui.shell.VaultixBottomDock] **完全同规格**：
                //   圆角 50%、`surfaceContainerHigh`、tonal 3dp、shadow 6dp（恒定）。
                //   胶囊**恒不透明**，靠"缩小自身体积 + 四周留白"制造沉浸感 ——
                //   内容从胶囊外侧穿过（不经过胶囊本体），既不叠字、又有清晰层次。
                //   ⚠️ 这同时否定了"毛玻璃/半透明"方向：`Modifier.blur` 在 minSdk 26 的
                //      API 31- 是 no-op、且离屏渲染掉帧耗电、OLED 下模糊纯黑仍是纯黑；
                //      半透明底色则踩 `8.4`「容器色阶嵌套必须换档，禁止同色叠 alpha」。
                //
                // ⇒ 因此不再需要滚动淡出：`barAlpha` / `scrollState` 的那套判定整体移除，
                //   栏的外观与滚动位置**无关**（与列表页底栏 dock 一致）。
                val scrollState = rememberScrollState()

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(horizontal = Spacing.xl)
                        .padding(
                            top = contentClearance(statusBar, TITLE_BAR_OCCUPIED),
                            bottom = contentClearance(navBar, ACTION_BAR_OCCUPIED),
                        ),
                    content = content,
                )

                // ── 顶部标题栏：悬浮胶囊（与列表页底栏同规格）──────────────
                // ⚠️ `statusBarsPadding()` 排在胶囊**外层**（先 systemBar padding 再画胶囊）——
                //    胶囊因此落在状态栏**下方**、四周留白露出内容（不再覆盖状态栏区域）。
                //    这与前两轮"满宽栏覆盖状态栏"是有意的行为改变：胶囊要"浮"起来，
                //    就不能吞掉整条状态栏；状态栏图标仍由上面的 SideEffect 按 surface 亮度定色。
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(
                            start = FLOATING_BAR_MARGIN,
                            end = FLOATING_BAR_MARGIN,
                            top = FLOATING_TOP_GAP,
                        ),
                    shape = RoundedCornerShape(FLOATING_BAR_CORNER_PERCENT),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = FLOATING_BAR_TONAL,
                    shadowElevation = FLOATING_BAR_SHADOW, // 恒定，不参与动画（见 ExpressiveTopBar 的掉帧记录）
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = Spacing.sm, end = Spacing.sm),
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

                // ── 底部操作条：同样悬浮成胶囊（与顶部、与列表页底栏同规格）──────────
                //
                // ⚠️ 与 `.ai/SESSION-2026-09-20.md` §5.3 的关系：那条记录"有意保留满宽条、
                //    不做胶囊"，理由是「底部必须**一直可点**」。本轮复核：**胶囊同样一直可点**
                //    —— 列表页的 [io.vaultix.vaultix.ui.shell.VaultixBottomDock] 就是胶囊且恒在
                //    最上层。§5.3 真正要防的是"按钮做成半透明、看不出能点"；胶囊**不透明**，
                //    在不牺牲可点性的前提下消除了上下两条黑带，故 §5.3 的结论据此更新。
                // ⚠️ `navigationBarsPadding()` 同样排在胶囊**外层**。
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(
                            start = FLOATING_BAR_MARGIN,
                            end = FLOATING_BAR_MARGIN,
                            bottom = FLOATING_BAR_MARGIN,
                        ),
                    shape = RoundedCornerShape(FLOATING_BAR_CORNER_PERCENT),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = FLOATING_BAR_TONAL,
                    shadowElevation = FLOATING_BAR_SHADOW,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
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
}

/**
 * 正文要为一条**悬浮胶囊栏**让出的高度 = `系统栏内边距 + 栏水平总占位`。
 *
 * ## ⚠️ 这个函数修的是一个真实 bug（2026-09-20 第一轮用户反馈）
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
 * ## ⚠️ 2026-09-20 第三轮：两条栏改悬浮胶囊后，`barHeight` 的**口径**跟着变
 *
 * 栏从"满宽色带"改成"悬浮胶囊"后，垂直占位多出了**留白**（胶囊上/下 gap + 边缘 margin）。
 * 传进来的 `barHeight` 因此不再是"栏身高"，而是**这一侧的总占位**（见
 * [TITLE_BAR_OCCUPIED] / [ACTION_BAR_OCCUPIED]）—— 公式本身不变，`maxOf` 不变，
 * 只是入参口径从"栏体高"升为"栏体高 + 胶囊留白"。
 * 让位取的是**垂直方向的最大占位**（正文满宽，首/末元素可能与胶囊在垂直方向重叠），
 * 宁可多让也不能少让（少让就退化成第一轮那个"被遮住"的 bug）。
 *
 * @param systemBarInset 该侧的系统栏内边距。
 * @param barHeight 该侧栏的**总垂直占位**（栏体高 + 胶囊留白，**不含**系统栏内边距）。
 */
internal fun contentClearance(systemBarInset: Dp, barHeight: Dp): Dp =
    maxOf(barHeight, systemBarInset + barHeight)

/**
 * 悬浮胶囊栏的圆角百分比（50 = 50%，即两端完全半圆的「药丸」形）。
 *
 * `RoundedCornerShape(Int)` 的重载语义是**百分比**而非 dp（与
 * [io.vaultix.vaultix.ui.shell.VaultixBottomDock] 同款写法）。
 */
private const val FLOATING_BAR_CORNER_PERCENT = 50

/**
 * 悬浮胶囊的色调高度与阴影高度 —— **与列表页底栏 dock 完全同值**。
 *
 * ⚠️ `8.4` 纪律：**一屏里出现两套规格 = 观感廉价的首要来源**。本页上下两条栏、以及列表页
 * 底栏必须是同一套规格，否则用户从列表页进编辑页会看到两种"悬浮"。
 * ⚠️ `shadowElevation` 必须**恒定**（不来自 `animateXxxAsState`）：见
 * [VaultixExpressiveTopBar] 里 `PILL_SHADOW` 的说明 —— 阴影几何参与动画要重建渲染层、会掉帧。
 */
private val FLOATING_BAR_TONAL = 3.dp
private val FLOATING_BAR_SHADOW = 6.dp

/**
 * 悬浮胶囊离屏幕左右边缘的留白（与列表页底栏 dock 的 `start/end = Spacing.md` 同口径）。
 *
 * 这圈留白是"沉浸感"的来源：内容从胶囊**外侧**透出，胶囊因此有了真实的边界。
 */
private val FLOATING_BAR_MARGIN = Spacing.md

/** 顶部胶囊离状态栏的下方留白（让胶囊明显抬离状态栏）。 */
private val FLOATING_TOP_GAP = Spacing.sm

/**
 * 顶部悬浮胶囊的**总垂直占位**：胶囊高（IconButton 48dp）+ 下留白 [FLOATING_TOP_GAP] +
 * 边缘留白 [FLOATING_BAR_MARGIN]（正文满宽，垂直方向须按此整段让开）。
 *
 * ⚠️ 这是正文让位用的**总占位**，不是"胶囊身高"—— 见 [contentClearance]。
 */
private val TITLE_BAR_OCCUPIED = 48.dp + FLOATING_TOP_GAP + FLOATING_BAR_MARGIN

/**
 * 底部悬浮胶囊的**总垂直占位**：胶囊高（按钮 48dp + 上下内边距 [Spacing.md]×2 = 72dp）
 * + 边缘留白 [FLOATING_BAR_MARGIN]。
 *
 * ⚠️ 同上：**不含** `navigationBarsPadding()`（那部分由 [contentClearance] 的 insets 项负责）。
 * ⚠️ 48dp 是 `TextButton` 的 M3 最小高度；`Spacing.md` = 12dp 与下方 `Row` 的
 *    `vertical = Spacing.md` 严格对齐 —— 两处若不同步，让位就会偏。
 */
private val ACTION_BAR_OCCUPIED = 72.dp + FLOATING_BAR_MARGIN
