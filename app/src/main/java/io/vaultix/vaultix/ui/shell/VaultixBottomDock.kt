/*
 * Vaultix — app:ui:shell
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 窄屏底部导航条（悬浮胶囊 + 中央「+」按钮）。
 * 抽取自 Bastion 项目（GPL-3.0，Copyright 2025 JoyinJoester）的
 * ui/SimpleMainScreen.kt 内联实现（原为 bottomBar lambda 内约 110 行）：
 *   - 悬浮胶囊规格：RoundedCornerShape(50) + surfaceContainerHigh + tonal3/shadow6，
 *     height(60dp)，外层留白 start/end=12dp, top=6dp, bottom=20dp（总高 82dp）
 *   - 布局：左 2 Tab + 中间「+」+ 右 2 Tab（各 weight(1f) 均分）
 *   - 中央「+」：圆角方块（**非 FAB**）RoundedCornerShape(16dp) + primary，
 *     52×48dp，图标 26dp；行为随当前 Tab 变化（对齐 Bastion `when(currentTab)`）
 *
 * 同时**原样保留** Bastion 在该处沉淀的两条踩坑注释（见 [DockTabItem] 与
 * [VaultixBottomDock] 的 KDoc）——这两条是「点一个 tab 旁边跟着闪」的真实根因，
 * 简单化改写会重新引入该缺陷。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.glass.DockMaterial
import io.vaultix.vaultix.ui.glass.GlassPress
import io.vaultix.vaultix.ui.glass.LiquidGlass
import io.vaultix.vaultix.ui.glass.LiquidGlassLayer
import io.vaultix.vaultix.ui.glass.LiquidGlassSource
import io.vaultix.vaultix.ui.glass.dockMaterialOf
import io.vaultix.vaultix.ui.glass.isDarkSurface
import io.vaultix.vaultix.ui.glass.liquidGlassStyle
import io.vaultix.vaultix.ui.glass.shouldApplyBlur
import io.vaultix.vaultix.ui.theme.UiSize
import io.vaultix.vaultix.ui.theme.VaultixCorner
import io.vaultix.vaultix.ui.theme.Spacing
import kotlin.math.abs

/** 悬浮胶囊底栏固定高度（对齐 Bastion：胶囊 60dp + 留白 6/20 = 86dp 视觉占位）。 */
private val DockBarHeight = 60.dp

// ── 2026-11-10 第四轮：按压联动的槽位几何 ────────────────────────────────
//
// DockRow 恒为 5 个等分 weight(1f)（左 2 + 中「+」+ 右 2，不足用 Spacer 补齐），
// 所以「被按的是第几个槽」可以直接换成 0~1 的水平位置，供玻璃的焦点高光使用。
// ⚠️ 这些常量只参与**绘制**，不参与测量：胶囊的宽/高/圆角/留白在按压全程不变。

/** 等分槽数。 */
private const val DOCK_SLOTS = 5

/** 中央「+」的槽号。 */
private const val DOCK_ADD_SLOT = 2

/** 取槽中心而不是槽左边缘。 */
private const val DOCK_SLOT_CENTER = 0.5f

/** 松手哨兵：负值 = 当前没有任何槽被按住。 */
private const val DOCK_NO_PRESS = -1f

/** 第 [slot] 个等分槽的中心，0（最左）→ 1（最右）。抽成纯函数便于单测钉住。 */
internal fun dockSlotFocus(slot: Int): Float = (slot + DOCK_SLOT_CENTER) / DOCK_SLOTS

/** 松手后光源回到正中（正好是中央「+」的槽中心）。 */
internal fun dockIdleFocus(): Float = dockSlotFocus(DOCK_ADD_SLOT)

/**
 * 按压时整项的缩放（2026-11-09 方案 ③）：比旧值 0.94 更深一点，
 * 配合 spring 做出「吸入 → 松手回弹」。只作用于被按的那一个 item。
 */
private const val DOCK_PRESS_SCALE = 0.88f

/** 加号的按压缩放：比 tab 更克制，且绝不改变「+」符号本身。 */
private const val ADD_PRESS_SCALE = 0.90f

/** 图标 one-shot 弹跳的起点：0.76 → 1.0，spring 过冲后停在 1.0。 */
private const val ICON_BOUNCE_START = 0.76f

/**
 * 是否叠加 M3 pressed state layer（10%，官方 StateTokens.PressedStateLayerOpacity）。
 * 默认关：玻璃态下半透明层会被模糊吃掉，项目自绘 pill 更可靠（见 [DockTabItem] KDoc）。
 * 想对比 M3 合规手感时改成 true 即可，其余逻辑不用动 —— 施工单里「可单独开关」的那一项。
 */
private const val USE_M3_STATE_LAYER = false
private const val M3_PRESSED_LAYER_ALPHA = 0.10f

/**
 * 玻璃态下选中 / 按压 pill 的不透明度（2026-11-09 精装修）。
 *
 * 原来 pill 是**实色** `secondaryContainer` / `primaryContainer`，浮在半透明玻璃上
 * 像贴了一块塑料。带 alpha 后读作「玻璃里一块更浓的玻璃」，材质才贯通。
 * ⚠️ 只改 alpha，**颜色仍瞬切**（[DockTabItem] KDoc 的老 bug 不复发）。
 */
private const val DOCK_GLASS_PILL_ALPHA = 0.55f

/**
 * 玻璃态下给 pill 叠 alpha；透明色保持透明。
 *
 * 抽成**纯函数**而不是内联在 [DockTabItem] 里：那个函数本来贴着 detekt
 * `CyclomaticComplexMethod ≤14` 的上限，内联一个 `if + &&` 会把它推到 15。
 */
private fun glassPillColor(color: Color, glass: Boolean): Color =
    if (glass && color != Color.Transparent) {
        color.copy(alpha = DOCK_GLASS_PILL_ALPHA)
    } else {
        color
    }

/** spring 参数集中一处，tab / 加号 / 图标共用同一套物理手感。 */
private val DockSpring = spring<Float>(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessMedium,
)

/**
 * 玻璃按压**光强**的非对称时序（FlClash 口径：亮 120ms / 灭 360ms）。
 *
 * 🔴 #279 的记录写了「亮起 120ms / 熄灭 360ms 非对称」，但代码一直让 `DockSpring`
 * 一条 spring 同时管按下和松手 —— 文档与实现不符，本轮补上。
 * 快亮慢灭是"灯被点亮"的手感：按下立刻有反馈，松手留一点余辉扫过玻璃。
 *
 * ⚠️ 只换 `animationSpec`（组合期的一个普通值），不把动画值提到组合期读 ——
 *    #277 那条「动画期间零重组」的纪律原样保留。
 * ⚠️ focusX/focusY 仍走 [DockSpring]：位置是连续跟随，用 tween 会在每次移动时重启，
 *    反而追不上手指。
 */
private val GlassPressInSpec = tween<Float>(durationMillis = 120, easing = FastOutSlowInEasing)
private val GlassPressOutSpec = tween<Float>(durationMillis = 360, easing = FastOutSlowInEasing)

/**
 * 手指位置上报的量化步长（占胶囊宽 / 高的比例）。
 *
 * #278 只在 `awaitFirstDown` 报一次，注释写着「期间手指移动不跟随」—— 这跟
 * 「跟随手指的触摸光晕」是相悖的。本轮改成持续上报，但**量化到 1/50 才写一次 state**：
 * 写 `pressedFocus` 会让 `VaultixBottomDock` 重组一次，若每个 move 事件都写，
 * 就把 #277 花大力气消掉的「手势期间重组」又请回来了。
 * 0.02 ≈ 窄屏胶囊 7px，肉眼看是连续的（后面还有 spring 平滑），重组却稀疏。
 */
private const val DOCK_TRACKING_QUANTUM = 0.02f

/**
 * 悬浮胶囊底栏的**总视觉占位高度**（胶囊 60 + 上留白 6 + 下留白 20 = 86dp）。
 *
 * 底栏改成**叠层悬浮**后（见 [AdaptiveMainScaffold]），内容会一直铺到屏幕底 ——
 * 好处是胶囊周围那圈留白能透出内容（酷安观感），代价是最后一条会被胶囊压住，
 * 所以各页列表必须把它算进 `contentPadding` 的底部。
 *
 * ⚠️ 2026-10-19：**这个常量只对默认字号成立**。2.0x 字号下 tab 的文字会把胶囊撑高
 * （见 [DockTabItem] 的 `heightIn`），此时真实占位比 86dp 大。所以：
 * - **列表让位一律调 [rememberBottomDockInset]**（它会按当前字号补上增量）；
 * - 只有「需要在非组合环境里引用一个名义值」的地方（如 `SelectionActionBar` 的文档、
 *   预览里的示意留白）才直接用这个常量。
 */
val BottomDockOccupiedHeight: Dp = DockBarHeight + UiSize.TightGap + UiSize.SecondaryIcon

/**
 * 2.0x 字号下 tab 文字把胶囊撑高的**增量上限**。
 *
 * 依据：tab 文字 12sp → 2.0x 时 24sp，行高约 32sp ≈ **16dp 增量**（12sp 行高约 16dp）。
 * 取 16dp 而不是精确测量：这里是**让位**用的保守值，宁可多留 16dp 的空白，
 * 也不能少留导致最后一条被压住 —— 多留的空白在默认字号下是透明的，看不出来。
 */
private val DOCK_MAX_FONT_GROWTH = 16.dp

/**
 * 列表底部应留出的高度 = 胶囊底栏占位 + **字体放大增量** + 系统手势条高度。
 *
 * 手势条高度随机型 / 横竖屏变化，故不做成常量。
 *
 * ⚠️ 2026-10-19：补上字体增量。改前只算 86dp + 手势条，2.0x 字号下胶囊被 tab 文字撑高
 * ⇒ 最后一条列表项仍然会被压住一截（审计 §3.3）。增量按 [LocalDensity] 的 `fontScale`
 * 线性插值：1.0x → 0，2.0x → [DOCK_MAX_FONT_GROWTH]，中间档按比例。
 */
@Composable
fun rememberBottomDockInset(): Dp {
    val fontScale = LocalDensity.current.fontScale
    // 只对"超过 1.0 的部分"补偿；用户把字号调小（<1.0）时不多留空白。
    val growth = DOCK_MAX_FONT_GROWTH * ((fontScale - 1f) / 1f).coerceIn(0f, 1f)
    return BottomDockOccupiedHeight +
        growth +
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
}

/**
 * 窄屏底部导航条：悬浮胶囊 + 中央「+」。
 *
 * ## ⚠️ 为什么「+」是圆角方块而不是 FAB
 * 对齐 Bastion 实测样式（酷安规格）：胶囊内中央一块接近撑满高度的主色圆角方形，
 * 视觉上与两侧 tab 同层，而非悬浮于其上的 FAB。**勿改成 FloatingActionButton。**
 *
 * ## ⚠️ 为什么 tab 项必须用 [key] 包裹
 * 见 [DockTabItem] KDoc —— 嵌套函数 + 无 key 会导致组合身份不稳定，
 * 表现为「点一个 tab、旁边那个 pill 也跟着闪」。
 *
 * @param tabs 数据 tab（**不含设置**；设置由调用方决定是否固定在末尾）。
 * @param selected 当前选中项。
 * @param onSelect 切换 tab。
 * @param onAdd 中央「+」点击；入参为当前 Tab，由宿主按 [VaultixNavItem.addTarget] 分发。
 */
@Composable
fun VaultixBottomDock(
    tabs: List<VaultixNavItem>,
    selected: VaultixNavItem,
    onSelect: (VaultixNavItem) -> Unit,
    onAdd: (VaultixNavItem) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 液态玻璃底栏（设置页「主题模式 → 液态玻璃底栏」，默认关）。
     *
     * #281 起开关不再决定「有没有模糊」，而是决定**材质档位**：
     * - `true` → [DockMaterial.Liquid]：更透 + AGSL 折射 / 分色 / 跟手流光；
     * - `false` → [DockMaterial.Frosted]：常态磨砂，更实的填充 + 更大的模糊，
     *   隐约透出下方内容，但不折射、不流光（用户 2026-11-11 反馈）。
     *
     * 两档都走同一条 `LiquidGlassLayer` 链路；API < 31 统一降级为实色 `Surface`。
     * 参数放在末尾且给默认值，是为了让既有调用点与预览一行都不改。
     */
    liquidGlass: Boolean = false,
    /**
     * 液态玻璃的**背景源**（2026-10-27 第二轮）。由 [MainShellScreen] 挂在 Tab 内容区上，
     * 这里只负责采样。
     *
     * ⚠️ 为 null 时退化成"一块纯色玻璃"（不糊任何东西）—— 预览 / 单测环境就是这种情况。
     * **不要**因此把模糊挂回自己身上：那正是用户截图里"图标全糊"的那个 bug。
     */
    glassSource: LiquidGlassSource? = null,
) {
    // ★ 2026-11-10 第四轮：按压状态提升到这里，玻璃本体才能跟着"被按厚"。
    //
    // 之前按压缩放 / 图标弹跳 / pill 换色都只发生在**被按的那一个 item 内部**，
    // 玻璃层对此一无所知 ⇒ 按下时"玻璃"毫无反应，用户说点击效果没做完善。
    // 现在把「有没有在按、按在哪个槽」折算成 [GlassPress] 的两个绘制参数：
    //   intensity → 镜面 rim 增亮、AGSL 折射变深；focusX/focusY → 光晕落在手指上。
    //   #278 第五轮：focus 从「槽中心」升级为**手指的精确位置**（FlClash 口径
    //   "lights the bar from the finger"），由 DockRow 的 pointerInput 上报；
    //   #280 第六轮：移动期间持续跟手 + 光强亮 120 / 灭 360 非对称时序。
    // ⚠️ 三者都**只进 drawBehind / shader uniform**，绝不进任何 Modifier 的尺寸参数。
    //    底栏的边界与大小在按压全程恒定（#205「高度塌 0」之后定下的硬边界）。
    var pressedFocus by remember { mutableFloatStateOf(DOCK_NO_PRESS) }
    var pressedFocusY by remember { mutableFloatStateOf(DOCK_SLOT_CENTER) }
    // ⚠️ 刻意**不用 `by`**：动画值一旦在组合期读，spring 每帧都会重组整条底栏。
    //   收成 State 再交给玻璃层，由 `drawBehind` / `drawWithContent` 在绘制阶段读
    //   ⇒ 动画期间零重组，手感才稳（见 [NoGlassPress] 的说明）。
    val pressing = pressedFocus >= 0f
    val pressIntensity = animateFloatAsState(
        targetValue = if (pressing) 1f else 0f,
        // #280：按方向切亮 120 / 灭 360，兑现 #279 写了却没做的非对称时序。
        animationSpec = if (pressing) GlassPressInSpec else GlassPressOutSpec,
    )
    val focusX = animateFloatAsState(
        targetValue = if (pressing) pressedFocus else dockIdleFocus(),
        animationSpec = DockSpring,
    )
    val focusY = animateFloatAsState(
        targetValue = if (pressing) pressedFocusY else DOCK_SLOT_CENTER,
        animationSpec = DockSpring,
    )
    val glassPress = remember {
        derivedStateOf { GlassPress(pressIntensity.value, focusX.value, focusY.value) }
    }
    val haptic = LocalHapticFeedback.current
    // 触觉放在**松手确认**时（clickable 的 onClick），与选中色瞬切同一刻；
    // 不在 Press 时给，否则长按后取消也会震一下。系统关闭触感时 Compose 自动不响。
    val handleSelect: (VaultixNavItem) -> Unit = { item ->
        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
        onSelect(item)
    }
    val handleAdd: (VaultixNavItem) -> Unit = { tab ->
        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
        onAdd(tab)
    }

    // 外层留白：bottom 20dp 让胶囊明显抬离系统手势条，底部透出内容（酷安观感）。
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Spacing.md, end = Spacing.md, top = UiSize.TightGap, bottom = UiSize.SecondaryIcon)
    ) {
        // ★ 2026-11-11 第七轮（#281）：由「实色 / 液态玻璃」两态改为**三档**。
        //
        // 用户原话：「不开液态玻璃的话，平常状态下的底栏，能做一下通透磨砂，
        // 能隐约看到下方内容的效果吗」—— 原来 else 分支是 `surfaceContainerHigh`
        // **全不透明**，底下铺了内容也一点看不见。#281 让常态走 [DockMaterial.Frosted]：
        // 更实的填充 + 更大的模糊，隐约透出条目色块，但不折射 / 不流光 / 不跟手。
        //
        // 🔴 第一版错在哪（2026-10-27 第二轮，仍然有效）：把
        //    `graphicsLayer { renderEffect = 模糊 }` 直接挂在本层，会糊掉本图层的一切，
        //    包括 [DockRow] 的图标与文字 ⇒ 真机上整条胶囊变成灰雾（用户截图）。
        //    ⇒ 模糊只能作用于"采样 [glassSource] 的那一层"，DockRow 必须在另一层。
        // ⚠️ 真模糊档**不能**再套 Surface：不透明底色会把模糊结果整块盖掉
        //    （见 ui/glass/LiquidGlass.kt 的顺序说明）；阴影改走外层 `.shadow(...)`。
        // ⚠️ 判深浅**不能看 `isSystemInDarkTheme()`** —— 本 App 有自己的「主题模式」
        //   （浅色 / 深色 / 跟随系统 + 动态取色 + OLED 纯黑）。取实际底色亮度最可靠。
        //
        // ⚠️ 三路分支刻意**不抽公共 lambda**：规则 2 钉住「三档共用 DockRow /
        //    VaultixCorner.Pill」，把调用点收成一处后，将来改间距/中央「+」会静默漏两处
        //    （#120/#193/#195 已栽过三次）。只把 style / modifier 收成局部变量。
        val surface = MaterialTheme.colorScheme.surfaceContainerHigh
        val blurAvailable = shouldApplyBlur(requested = true)
        val material = dockMaterialOf(liquidGlass)
        val glassStyle = liquidGlassStyle(
            darkTheme = isDarkSurface(surface),
            surface = surface,
            onSurface = MaterialTheme.colorScheme.onSurface,
            blurAvailable = blurAvailable,
            material = material,
        )
        val glassModifier = Modifier
            .fillMaxWidth()
            .shadow(elevation = LiquidGlass.ShadowElevation, shape = VaultixCorner.Pill)

        when {
            !blurAvailable -> {
                // API < 31：没有 RenderEffect，半透明会让列表文字穿过底栏 ⇒ 保持原实色。
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = VaultixCorner.Pill,
                    color = surface,
                    tonalElevation = 3.dp,
                    shadowElevation = 6.dp,
                ) {
                    DockRow(
                        tabs = tabs,
                        selected = selected,
                        onSelect = handleSelect,
                        onAdd = handleAdd,
                    )
                }
            }

            material == DockMaterial.Liquid -> {
                LiquidGlassLayer(
                    shape = VaultixCorner.Pill,
                    enabled = true,
                    source = glassSource,
                    style = glassStyle,
                    material = DockMaterial.Liquid,
                    press = glassPress,
                    modifier = glassModifier,
                ) {
                    DockRow(
                        tabs = tabs,
                        selected = selected,
                        onSelect = handleSelect,
                        onAdd = handleAdd,
                        glass = true,
                        onPress = { x, y ->
                            pressedFocus = x
                            pressedFocusY = y
                        },
                    )
                }
            }

            else -> {
                // 常态磨砂：不传 press（用 NoGlassPress），也不给 DockRow 上报位置 ⇒
                // 静止与滚动期间都不写状态，零重组；选中 pill 保持实色。
                LiquidGlassLayer(
                    shape = VaultixCorner.Pill,
                    enabled = true,
                    source = glassSource,
                    style = glassStyle,
                    material = DockMaterial.Frosted,
                    modifier = glassModifier,
                ) {
                    DockRow(
                        tabs = tabs,
                        selected = selected,
                        onSelect = handleSelect,
                        onAdd = handleAdd,
                    )
                }
            }
        }
    }
}

/**
 * 胶囊内的 tab 行（两种外观共用）。
 *
 * 抽出来是为了让"实色"与"液态玻璃"两条分支**共用同一份布局**：
 * 否则以后调 tab 间距 / 中央「+」尺寸时只会改一处，
 * 而另一条分支会静默停在旧规格（本项目已因"同一规则写两处"栽过三次，#120/#193/#195）。
 *
 * ## #278 第五轮 / #280 第六轮：触摸光晕的位置来源
 * 行级 `pointerInput` 观察手势，上报**手指的精确位置**（0~1 分数）；
 * 松手 / 取消上报 [DOCK_NO_PRESS]。⚠️ 观察**不消费**任何事件 —— clickable 的
 * 点击、长按、无障碍语义全部原样保留；`requireUnconsumed = false` 是为了
 * 即使子项先消费了移动事件也照样能看到按下位置。
 *
 * #278 只在按下那一瞬报一次，光其实钉死在按下点；
 * #280 改为**移动期间持续上报**，并用 [DOCK_TRACKING_QUANTUM] 量化，避免每个 move
 * 事件都写 state 把整条底栏拖进重组。位置仍由 spring 平滑，不会逐帧抖。
 */
@Composable
private fun DockRow(
    tabs: List<VaultixNavItem>,
    selected: VaultixNavItem,
    onSelect: (VaultixNavItem) -> Unit,
    onAdd: (VaultixNavItem) -> Unit,
    /** 玻璃态下选中 / 按压 pill 带 alpha，让材质贯通；实心态默认 false 不受影响。 */
    glass: Boolean = false,
    /**
     * 上报按压位置：x = 0（最左）→ 1（最右），y = 0（顶沿）→ 1（底沿）；
     * 松手传 x = [DOCK_NO_PRESS]。宿主只把它折算成 [GlassPress] 的绘制参数，
     * **不参与本行的测量**。
     */
    onPress: (Float, Float) -> Unit = { _, _ -> },
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(DockBarHeight)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var lastX = (down.position.x / size.width).coerceIn(0f, 1f)
                    var lastY = (down.position.y / size.height).coerceIn(0f, 1f)
                    onPress(lastX, lastY)
                    // #280：移动期间持续上报，光晕与扫光带真的跟手（#278 是钉在按下点）。
                    // ⚠️ 量化后才写 state：onPress 会触发一次组合，逐事件写会重现
                    //    #277 消掉的「按压期间每帧重组」；spring 会把量化台阶抹平。
                    // ⚠️ 本项目 Compose 版本的 `waitForUpOrCancellation` 没有
                    //    `requireUnconsumed` 参数：手动等到全部指针抬起即可。
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.pressed }
                        if (change == null) break
                        val nx = (change.position.x / size.width).coerceIn(0f, 1f)
                        val ny = (change.position.y / size.height).coerceIn(0f, 1f)
                        val moved = abs(nx - lastX) >= DOCK_TRACKING_QUANTUM ||
                            abs(ny - lastY) >= DOCK_TRACKING_QUANTUM
                        if (moved) {
                            lastX = nx
                            lastY = ny
                            onPress(nx, ny)
                        }
                    }
                    onPress(DOCK_NO_PRESS, DOCK_SLOT_CENTER)
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 布局：左 2 个 + 中间「+」+ 右 2 个（不足则用 Spacer 占位保持「+」居中）。
        val leftTabs = tabs.take(2)
        val rightTabs = tabs.drop(2).take(2)

        leftTabs.forEachIndexed { index, item ->
            key(item) {
                DockTabItem(
                    item = item,
                    isSelected = item == selected,
                    onClick = { onSelect(item) },
                    modifier = Modifier.weight(1f),
                    glass = glass,
                )
            }
        }
        repeat(2 - leftTabs.size) { Spacer(modifier = Modifier.weight(1f)) }

        // 中央「+」：独立函数无法访问 RowScope.weight，故此处显式传 weight 修饰符。
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            AddButton(
                currentTab = selected,
                onAdd = onAdd,
            )
        }

        rightTabs.forEachIndexed { index, item ->
            key(item) {
                DockTabItem(
                    item = item,
                    isSelected = item == selected,
                    onClick = { onSelect(item) },
                    modifier = Modifier.weight(1f),
                    glass = glass,
                )
            }
        }
        repeat(2 - rightTabs.size) { Spacer(modifier = Modifier.weight(1f)) }
    }
}

/**
 * 中央「+」：主色圆角方形（52×48dp，圆角 16dp，图标 26dp）。
 *
 * ## ⚠️ 不许旋转（2026-11-09 用户在预览页当场否决）
 * 「+」有四次对称，旋转 90° 的终态与初始态完全相同 ⇒ 松手后看不到任何结果；
 * 而过渡过程中它会变成「×」，× 在界面语义里是「关闭 / 取消」，与「新建」相反。
 * ⇒ 这里只做压缩回弹，符号恒为「+」。
 * 判据：**动作按钮的形状变化，不能让用户以为它变成了另一个功能。**
 *
 * 用自绘 Box + clickable（与 [DockTabItem] 同一模式），不用 Surface 的默认 ripple：
 * 玻璃态下 ripple 会被半透明层吃掉（见 [DockTabItem] KDoc）。
 */
@Composable
private fun AddButton(
    currentTab: VaultixNavItem,
    onAdd: (VaultixNavItem) -> Unit,
) {
    val addLabel = stringResource(R.string.nav_add)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) ADD_PRESS_SCALE else 1f,
        animationSpec = DockSpring,
    )
    Box(
        modifier = Modifier
            .width(52.dp)
            .height(48.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(VaultixCorner.Panel)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = { onAdd(currentTab) },
            )
            .semantics { contentDescription = addLabel },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Add,
            // 整项已在 Box 层声明名字，图标不重复播报。
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(26.dp),
        )
    }
}

/**
 * 底栏单个 tab 项（悬浮胶囊内的图标 + 文字）。
 *
 * ## ⚠️ 必须是【顶层】composable
 * 不能定义在 `bottomBar` / 其他 lambda 内部再经 forEach 调用 —— 嵌套函数在每次
 * 重组时会被重新定义，导致内部 `remember` / 动画的组合身份不稳定，点击一个 tab
 * 触发整行重组时动画状态会重启，表现为「**点一个、旁边那个 pill 也跟着闪**」。
 * 同时调用处必须用 `key(item.key)` 包裹，保证每个 tab 有稳定的组合槽。
 *
 * ## ⚠️ 选中色必须【瞬切】，不可用 animateColorAsState
 * 此前 Bastion 用 `animateColorAsState(tween(200))`，点击新 tab 时「新 pill 渐现」
 * 与「旧 pill 渐隐」两个动画并行 200ms，期间两个 pill 同时处于中间色，用户感知
 * 就是「**点一个、旁边那个也跟着闪**」。且下方 contentTint 本就是瞬间切换，
 * 背景若走渐变会与文字色不同步、更显抖动。改为瞬切后选中态与文字色严格同步。
 *
 * ## 11-07：按压反馈 ≠ 选中态渐变
 * 上面禁的是「**跨 tab、200ms、两个 pill 并行**」的选中色动画；而用户反馈
 * 「点击没什么区别，尤其液态玻璃下」指的是「**单个 tab 按下期间**」没有即时反馈。
 * 这两件事可以同时成立：选中色仍瞬切、不变；press 只改**被按的那一个** item 的
 * 自身底色与缩放，松手即回。因 `key(item)` 保证组合槽稳定，press 不会扩散到兄弟节点，
 * 故不会重现「旁边跟着闪」。这是组件内按压反馈，不违反 #87「不要花哨页面转场」。
 *
 * ⚠️ 玻璃态下 ripple 会被半透明层吃掉 ⇒ 这里**不用** `indication`，而是自绘瞬时 pill。
 *    实心态用 `surfaceContainerHigh` / `primaryContainer` 等**具名实色档**；
 *    玻璃态由 [DOCK_GLASS_PILL_ALPHA] 给同一批颜色叠 alpha，让材质贯通。
 *    两者都只改「被按的那一个」item，选中色仍瞬切。
 *
 * ## 2026-11-09 方案 ③：弹簧、选中形变、图标 one-shot
 * - **弹簧**：`tween(90)` → `DockSpring`，按压吸入、松手回弹（T1 那段原来空白）。
 * - **选中形变**：pill 圆角 33%(≈16dp) → 50%(胶囊)，用同一条 spring 连续变化；
 *   **颜色仍瞬切**（下面的 `pillColor` 还是 `when` 表达式）—— 形变与颜色解耦，
 *   才不会重现「跨 tab、200ms、两个 pill 并行渐变」那个老 bug。
 * - **图标 one-shot**：`Animatable` 只在 `PressInteraction.Release` 时播一次，
 *   0.76 → 1.0（spring 过冲后停住），**绝不循环**；循环会持续重组、滚动掉帧。
 *   ⚠️ 它依赖 [key] 保证的组合槽稳定，否则会重现「点一个、旁边跟着闪」。
 * - **M3 state layer** 由 [USE_M3_STATE_LAYER] 单独开关，默认关（玻璃态可靠性优先）。
 */
@Composable
private fun DockTabItem(
    item: VaultixNavItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 见 [DOCK_GLASS_PILL_ALPHA]：只影响 pill 的 alpha，颜色仍瞬切。 */
    glass: Boolean = false,
) {
    val label = stringResource(item.labelRes)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) DOCK_PRESS_SCALE else 1f,
        animationSpec = DockSpring,
    )

    // 图标 one-shot：只在「松手」时播一次，spring 过冲后停在 1.0。
    // 用 Animatable 而不是 animateXxxAsState —— 后者表达的是「状态」，
    // 表达不了「一次性事件播完即停」。key(item) 保证组合槽稳定，动画不会扩散到兄弟节点。
    // （按压位置上报已上移到 DockRow 的 pointerInput，#278。）
    val iconBounce = remember { Animatable(1f) }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Release, is PressInteraction.Cancel -> {
                    iconBounce.snapTo(ICON_BOUNCE_START)
                    iconBounce.animateTo(1f, DockSpring)
                }
                else -> Unit
            }
        }
    }

    // 选中 pill 的形状形变：33%(≈16dp / 48dp) → 50%(全圆角胶囊)。
    // 用 percent 而不是 dp，「16dp 圆角」与「全圆角」才能落在同一条连续曲线上。
    val cornerPercent by animateFloatAsState(
        targetValue = if (isSelected) 50f else 33f,
        animationSpec = DockSpring,
    )
    val pillShape = RoundedCornerShape(percent = cornerPercent.toInt())

    // 选中色块包住「图标+文字」整体（酷安样式，圆角矩形非细长条）。
    // ⚠️ 选中态仍**瞬切**（见 KDoc）；press 时换到相邻色档，让玻璃上也能看见按下。
    val pillColor = when {
        isSelected && pressed -> MaterialTheme.colorScheme.primaryContainer
        isSelected -> MaterialTheme.colorScheme.secondaryContainer
        pressed -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> Color.Transparent
    }
    // 玻璃态：pill 带 alpha ⇒ 读作「玻璃里一块更浓的玻璃」而不是贴了一块塑料。
    // ⚠️ 只改 alpha，颜色仍由上面的 when 瞬切（不引入任何颜色动画）。
    val effectivePillColor = glassPillColor(pillColor, glass)
    val contentTint = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    // M3 pressed state layer（官方 0.10）。默认关；开关见文件头常量。
    val stateLayer = if (USE_M3_STATE_LAYER && pressed) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = M3_PRESSED_LAYER_ALPHA)
    } else {
        Color.Transparent
    }
    Box(
        modifier = modifier
            .fillMaxHeight()
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(pillShape)
            .background(stateLayer)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                // ★ 2026-10-19：自绘 tab 必须**自己声明语义**。
                //   这是导航不是按钮 —— 读屏要报「密码，标签页，已选中」
                //   而不是「密码，按钮」。缺 `role` + `selected` 时 TalkBack 完全读不出
                //   "我现在在哪一页"，这对 4 个 tab 的底栏是**功能缺失**，不是观感问题。
                //   `role` 让读屏按"标签页"播报；`selected` 提供选中态；
                //   `onClickLabel` 与 `CollectionInfo` 由 `selected` 自动推导，无需手写。
                role = Role.Tab,
                onClick = { onClick() },
            )
            .semantics {
                // ⚠️ 必须写显式 `selected`：`clickable` 的 `role` 只影响播报措辞，
                //    **不会**自动带上选中态 —— 那句"已选中"完全来自这里。
                this.selected = isSelected
                // 图标与文字都会被子节点并入；给整项一个稳定名字，避免读到两遍。
                contentDescription = label
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 56.dp)
                // ⚠️ 2026-10-19：`height(48.dp)` → `heightIn(min = 48.dp)`。
                //    固定高在 2.0x 字体下会把「22dp 图标 + 12sp 文字」裁掉下半截；
                //    `heightIn` 保 48dp 的下限（触摸目标不缩），但允许它随字号长高。
                //    代价是 60dp 的胶囊可能被撑高 —— 这是**对的**取舍：宁可底栏变高，
                //    也不能让 tab 文字被裁成半个字（见 .ai/conventions/8.4 §字体缩放）。
                .heightIn(min = 48.dp)
                .clip(pillShape)
                .background(effectivePillColor, pillShape),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = item.icon,
                // 名字已在 Box 层声明，图标不再重复播报。
                contentDescription = null,
                tint = contentTint,
                modifier = Modifier
                    .size(22.dp)
                    // graphicsLayer 在绘制阶段读 Animatable，只重绘不重组；
                    // 播完停在 1.0，不会持续消耗帧。
                    .graphicsLayer {
                        scaleX = iconBounce.value
                        scaleY = iconBounce.value
                    },
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = contentTint,
            )
        }
    }
}
