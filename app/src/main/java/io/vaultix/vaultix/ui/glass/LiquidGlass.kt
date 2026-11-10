/*
 * Vaultix — app:ui:glass
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.glass

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/**
 * 液态玻璃（Liquid Glass）观感的**唯一实现点**。
 *
 * ## 一轮返工（2026-10-27 真机反馈）
 *
 * 第一版把 `graphicsLayer { renderEffect = 模糊 }` 挂在包住底栏图标的 Box 上 ——
 * `RenderEffect` 模糊的是**该图层的输出，含全部子内容** ⇒ 图标文字+中央「+」被一起糊成
 * 灰雾。**这个 KDoc 上一版写的"必须挂最外层 Box"是错的**，真机截图直接证伪。
 *
 * ## 现在的结构（对齐 chrisbanes/Haze 的源→消费分离）
 *
 * ```
 * ┌ 源：Modifier.liquidGlassSource（Tab 内容区：录一份进 contentLayer，并照常回屏）
 * └ 浮层：LiquidGlassLayer
 *      ├─ 层 A：Modifier.liquidGlassBackdrop —— 只画"模糊后的源"，带 clip 与 renderEffect
 *      └─ 层 B：content() —— 图标 / 文字 / 中央「+」，完全不含任何模糊
 * ```
 *
 * `Box` 的兄弟节点各画各的层，所以层 B 不会被层 A 的 `RenderEffect` 波及。
 * 这是 StackOverflow「内容放在同一层里也会受效果影响，解法是两个叠加的 Box」
 * 所说的做法，也是 Haze `hazeEffect` 内部对源图层的处理方式。
 *
 * ## 🔴 2026-10-28（第三轮）：源必须**照常回屏**，模糊必须挂在**中转层**
 *
 * 第二轮把源写成"只录不画、屏幕不落像素"，自称是与 Haze 的"唯一有意分歧"。
 * 结果是**除底栏外整屏没有像素** ⇒ 露出窗口底色 ⇒ 用户报「开启液态玻璃后整个页面变白」。
 * 现在：源 `record` 后 `drawLayer(contentLayer)` 回屏；模糊只挂 `effectLayer`。
 * **与 Haze 零分歧**。细节与五条约束见 `LiquidGlassSource.kt` 文件头。
 *
 * ## 🔴 为什么填充 / 高光 / 描边画在层 B 上，而不是层 A
 *
 * 层 A 上有 `renderEffect`：**在同一图层内先画填充再挂效果，填充自己也会被糊**（虽然
 * 半透明纯色糊了看不出，但上沿高光那条 1~6dp 的细带会被糊没 —— 而它恰恰是"玻璃"与
 * "塑料板"的观感分界）。所以视觉装饰一律画在**上层（层 B）的 `drawBehind`**：
 * 它盖在模糊结果之上，是清晰的。
 *
 * ## 2026-11-06：折射补上了，但没有走回读那条路
 *
 * 上面那段旧结论成立于「把背景 `toImageBitmap()` 再喂给 Paint.shader」的写法。
 * 现在 [glassRenderEffect] 用 `createRuntimeShaderEffect` 让 AGSL 直接采样
 * RenderNode 自己的内容，与模糊合成一条 RenderEffect 链 —— 全程留在 GPU，
 * 不需要每帧回读。细节与三档降级见 `LiquidGlassOptics.kt` 文件头。
 *
 * ⇒ 当前观感 = 模糊 + AGSL 边缘折射/分色（API 33+） + 上沿高光 / 下沿暗线 /
 *   内描边（所有版本）。低版本或 shader 创建失败时自动退回「仅模糊 + 静态光学装饰」。
 */

/**
 * 模糊半径（dp）。
 *
 * 2026-11-09 精装修：24dp → **16dp**。24 是 Haze `HazeMaterials` 的默认值，
 * 但配上调淡后的填充，24 会把背后的条目糊得完全没有形状，
 * 用户想要的是「稍微看得出下面有东西」而不是一片雾。16dp 保留轮廓、去掉文字可读性。
 * ⚠️ 只影响观感；`liquidGlassBackdrop` 的采样与层级结构一行未动。
 */
private val BlurRadius = 18.dp

/**
 * 液态玻璃的视觉参数（集中一处，避免各处拼参数导致观感漂移）。
 *
 * ## 2026-11-09 精装修：从 thin 调淡到 ultraThin 与 thin 之间
 *
 * 用户装机后要的是「透过底栏**淡淡**看到下方密码条目」，而原来的 thin 档偏实。
 * 这一轮把填充与三层装饰一起压淡，但**不动任何结构**：
 * 源→消费分离、`drawGlass` 的绘制顺序、降级档全不透明、采样偏移，一行未改。
 *
 * ⚠️ 硬边界（`8.4-UI·观感-part3.md` + `LiquidGlassTest` 反向锁定）：
 * 1. 填充 alpha 必须 `< 1f` 且 **深色 > 浅色**（深色背景糊完接近黑，太薄会分不清边界）；
 * 2. `FALLBACK_ALPHA` **保持 1f** —— API 30 及以下没有真模糊，半透明会让列表文字穿栏；
 *    本轮调淡只对「真模糊档」生效，降级档仍是实色底栏；
 * 3. 不在 `ui/glass/` 之外另叠半透明蒙层假装磨砂（会和真模糊打架）。
 */
object LiquidGlass {
    /**
     * 填充不透明度 —— 基准取自 chrisbanes/Haze 的 `HazeMaterials`（Apache-2.0）：
     *
     * | 档位 | light | dark |
     * |---|---|---|
     * | ultraThin | 0.35 | 0.55 |
     * | thin | 0.60 | 0.65 |
     * | regular | 0.73 | 0.80 |
     * | thick | 0.83 | 0.90 |
     * | ultraThick | 0.92 | 0.97 |
     *
     * 第一版取的 0.62 / 0.48（**深色比浅色更透**）是拍脑袋定的，方向反了：
     * 深色主题下背景本身更暗、模糊结果更接近黑，填充再薄就分不清"玻璃"与"背景"。
     * Haze 五档**全部是深色更实**。
     *
     * ⇒ 旧值取 **thin**（0.60 / 0.65）；精装修先降到 0.40 / 0.55，
     *    22:20 用户真机反馈「常态还是看不到条目」后再降到 **0.38 / 0.52**
     *    （对齐探索预览里 ③ ultraThin 档的浅色 0.38 / 深色 0.52）：
     *    浅色贴近 ultraThin，深色保守地略低于 ultraThin 的 dark 值，
     *    既让背后条目的色块透出来，又保住图标对比度与「深色更实」的规律。
     */
    const val FILL_ALPHA_LIGHT = 0.38f
    const val FILL_ALPHA_DARK = 0.52f

    /**
     * 降级（无模糊硬件能力）时**直接画实色**，而不是"半透明 + 一点补偿"。
     *
     * Haze 的做法同样是降级成 flat scrim（`fallbackColorEffect`），而不是留半透明糊弄：
     * 半透明的底栏浮在滚动内容上，文字会**穿过**底栏（读不清），那才是真正不可用的状态。
     * ⇒ 无模糊 = 主题色 `surfaceContainerHigh` **全不透明**，观感等同第一版的实心底栏。
     * ⚠️ 本轮「默认开」在低版本走的就是这一档，所以默认开不会造成可用性回退。
     */
    const val FALLBACK_ALPHA = 1f

    /** 上沿高光带高（占整体高度的比例）。 */
    const val EDGE_HIGHLIGHT_FRACTION = 0.16f

    /**
     * 上沿高光透明度（0.55 → 0.32 → **0.42**）。
     *
     * 22:37 用户反馈「玻璃效果看不出来」：填充已经很透，但当列表条目不足一屏、
     * 或静止时胶囊后面恰好是空白，透出来的就是**纯背景** —— 此时唯一能证明
     * 「这是一块玻璃而不是实色板」的，只剩上沿高光与内描边。
     * ⇒ 适度回提装饰，但不回到 0.55 那种塑料感；填充透明度保持不变。
     */
    const val TOP_SHEEN_ALPHA = 0.42f

    /** 内描边透明度（深色下白描边要更淡；单测钉住 dark < light）。 */
    const val STROKE_ALPHA_LIGHT = 0.42f
    const val STROKE_ALPHA_DARK = 0.28f

    /** 阴影高度（8 → 6dp）：玻璃更淡了，重阴影会把边界重新压成一块塑料板。 */
    val ShadowElevation = 6.dp

    // ── 2026-11-10 第四轮：沿轮廓的光学规格 ──────────────────────────────
    //
    // 旧实现用 `drawRect` 画「顶部 1dp 亮线 + 矩形内描边 + 底部矩形暗线」，
    // 外层 `.clip(Pill)` 之后，药丸**两端半圆既没有高光也没有描边** ⇒ 玻璃看起来
    // 像「上下有边、左右没边」的一块板。真实液态玻璃的边界靠**沿轮廓**的
    // specular rim 定义（iOS 26 HIG / PrismalAGSL / LiquidGlassBar 口径一致）。
    // ⇒ 现在填充 / 高光带 / 镜面 rim / 内描边一律按 [Shape] 的 Outline 画。

    /** 背景饱和度增益。1 = 原样；Haze vibrancy / Prismal saturation 常用 1.3~1.5。 */
    const val SATURATION = 1.38f

    /** 镜面 rim 线宽。比旧 1dp 亮线略粗，才有"玻璃有厚度"而不是"画了条边"。 */
    val SpecularWidth = 1.6.dp

    /** 镜面 rim 上沿强度（垂直渐变起点）。 */
    const val SPECULAR_ALPHA = 0.52f

    /** 镜面 rim 侧沿强度（渐变中段，明显弱于上沿，形成方向感）。 */
    const val SPECULAR_TAIL_ALPHA = 0.10f

    /** 内描边宽度：与镜面 rim 分工 —— 后者给"光"，前者给"与背景的分界"。 */
    val InnerStrokeWidth = 1.dp

    /** 边缘折射位移深度（喂给 AGSL 的 `uDepth`）。 */
    val RefractionDepth = 5.dp

    /** 边缘 RGB 分色幅度（喂给 AGSL 的 `uPart`）。 */
    val ChromaticAberration = 1.2.dp

    /** 按压时折射深度的增量比例：玻璃被"按厚"，而不改变胶囊尺寸。 */
    const val PRESS_REFRACTION_GAIN = 0.35f

    /** 按压时镜面 rim 的增亮比例。 */
    const val PRESS_SHEEN_GAIN = 0.45f

    /** 按压焦点高光峰值：光源落在被按 tab 的上沿。 */
    const val FOCUS_SHEEN_ALPHA = 0.62f

    /** 焦点高光半径占胶囊宽度的比例。 */
    const val FOCUS_RADIUS_FRACTION = 0.60f

    /** 焦点高光比镜面 rim 略宽，才能读作"光晕扫过"而不是"又一条边"。 */
    const val FOCUS_WIDTH_FACTOR = 1.5f

    // ── 2026-11-10 第五轮（#278 玻璃精修，参考 FlClash / prism_ui）──────────
    //
    // FlClash 的底栏玻璃（plugins/prism_ui，liquid_glass.frag + navigation_dock.dart）
    // 在触摸时做三件事，本轮搬的是其中最"值钱"的一件：
    // ① 触摸光晕：`uGlow(center, strength, reach)`，径向高斯，加在被按位置而**不是槽中心**
    //   （"UIKit lights the bar from the finger"）；strength = peak * press²（压得越深越亮），
    //   亮起 120ms / 熄灭 360ms 非对称。② 透镜位移带：折射沿法线在 rim 附近的一条带内最强。
    //   ③ 色散沿法线镜像方向分开红蓝。②③ 上一轮已有对应物（uDepth/uPart），本轮只补 ①。
    //
    // ⚠️ 光晕是纯 shader uniform：底栏边界、大小、内容测量全程不变 —— 与第四轮同一条硬边界。

    /** 触摸光晕峰值（径向高斯的中心强度，会再乘 intensity²）。FlClash 用 0.06+wash，本项目的底栏更窄，取大些。 */
    const val TOUCH_GLOW_PEAK = 0.32f

    /** 触摸光晕半径 = 胶囊高度 × 该系数。FlClash 取 height/√2 起步、松手放大 3 倍；按下时窄而亮更"聚"。 */
    const val TOUCH_GLOW_REACH_FACTOR = 0.9f

    /** 暗线颜色：固定黑叠 alpha，不随主题反色（玻璃的暗边在两个主题里都是暗的）。 */
    val RimLineColor = Color.Black.copy(alpha = 0.10f)
}

/**
 * 按压联动参数（2026-11-10 第四轮）。
 *
 * ⚠️ **纯绘制参数，绝不含任何尺寸**：`drawGlass` 只拿它调高光强度与光源位置，
 * `liquidGlassBackdrop` 只拿 [intensity] 调折射深度。两者都不碰 `Modifier.size/height/
 * width/padding` —— 底栏的高度、留白、圆角**不随按压变化**。
 * 这是 #205「底栏高度塌 0」之后定下的边界：**观感状态不许参与测量**。
 *
 * @param intensity 0（松手）→ 1（按住），由 spring 驱动。
 * @param focusX 光源水平位置，0（最左）→ 1（最右）；#278 起是**手指的精确位置**，
 *   松手时回 0.5（居中）—— 之前是槽中心，FlClash 口径是"光跟着手指"。
 * @param focusY 光源垂直位置，0（顶沿）→ 1（底沿）；手指在胶囊内的高度，松手回 0.5。
 */
data class GlassPress(
    val intensity: Float = 0f,
    val focusX: Float = 0.5f,
    val focusY: Float = 0.5f,
)

/**
 * 无按压时的常量 State。
 *
 * ⚠️ [LiquidGlassLayer] 收的是 `State<GlassPress>` 而不是裸值：按压是 spring 动画，
 * 若把值传进来，`VaultixBottomDock` 每帧都会在**组合期**读它 ⇒ 整条底栏（5 个 tab、
 * 各自的 `animateFloatAsState`）每帧重组，按压那 200ms 最容易掉帧。
 * 收 State 后只在 `drawBehind` / `drawWithContent` 的**绘制阶段**读 `.value`，
 * Compose 只 invalidate 绘制、不重组 content —— 动画期间零重组。
 */
internal val NoGlassPress: State<GlassPress> = mutableStateOf(GlassPress())

/**
 * 系统是否支持**真模糊**。
 *
 * 只有 API 31+ 可以。低版本返回 false，UI 侧据此给出"降级为实心底栏"的说明 ——
 * **不要静默降级**：用户打开开关却看不到模糊，会以为开关坏了。
 *
 * @param sdkInt 显式入参只为可单测；业务代码不要传。
 */
fun isLiquidGlassBlurSupported(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
    sdkInt >= Build.VERSION_CODES.S

/**
 * 是否真的会把模糊挂上去（开关 × 系统能力）。
 *
 * 抽成纯函数是为了能在 JVM 单测里验证门槛逻辑 —— `RenderEffect` 本身在单测环境拿不到。
 */
internal fun shouldApplyBlur(requested: Boolean, sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
    requested && isLiquidGlassBlurSupported(sdkInt)

/**
 * 液态玻璃的绘制参数（由调用方从主题取，本文件不读 `MaterialTheme`）。
 *
 * @param fill **必须带 alpha**（真模糊档）：不透明的填充会把模糊结果整块盖掉。
 * @param stroke 内描边色。
 * @param sheen 上沿高光色（固定白，深色下仍成立）。
 */
data class LiquidGlassStyle(
    val fill: Color,
    val stroke: Color,
    val sheen: Color,
)

/**
 * 按深浅色取一套风格。
 *
 * @param blurAvailable 有没有真模糊。**没有时填充直接拉到不透明**（见 [LiquidGlass.FALLBACK_ALPHA]）：
 *   这是"降级成实心底栏"，不是"半透明凑合"。
 */
fun liquidGlassStyle(
    darkTheme: Boolean,
    surface: Color,
    onSurface: Color,
    blurAvailable: Boolean = true,
): LiquidGlassStyle {
    val alpha = when {
        !blurAvailable -> LiquidGlass.FALLBACK_ALPHA
        darkTheme -> LiquidGlass.FILL_ALPHA_DARK
        else -> LiquidGlass.FILL_ALPHA_LIGHT
    }
    return LiquidGlassStyle(
        fill = surface.copy(alpha = alpha),
        stroke = onSurface.copy(
            alpha = if (darkTheme) LiquidGlass.STROKE_ALPHA_DARK else LiquidGlass.STROKE_ALPHA_LIGHT,
        ),
        sheen = Color.White,
    )
}

/**
 * 原生模糊的创建已收进 `LiquidGlassOptics.androidBlurEffect`：那里还要把它作为
 * AGSL 链的内层复用，两处各写一遍会出现「模糊半径改了一处、另一处没改」的漂移。
 * 本文件的 `drawGlass` 只负责**不依赖平台**的静态光学装饰。
 */

/**
 * 液态玻璃图层（= 上面结构图里的"浮层"）。
 *
 * ## 🔴 本函数最长的一行注释：层 A 与层 B 必须**分开**
 *
 * ```kotlin
 * Box(modifier) {
 *     Box(Modifier.matchParentSize().liquidGlassBackdrop(…))   // ← 层 A：唯一带模糊的东西
 *     Box(Modifier.drawBehind { drawGlass(…) }) { content() }   // ← 层 B：图标，零模糊
 * }
 * ```
 *
 * 只要把这两件事写进**同一个** `Box` 的 modifier 链（第一版的写法），
 * `renderEffect` 就会连层 B 一起糊。要验证有没有写错，看**图标清不清晰**就够了。
 *
 * @param shape 外轮廓（底栏是药丸形）。
 * @param enabled 用户开关（设置页「主题模式」组里的「液态玻璃底栏」）。
 * @param source 背景源；null 时等同一块纯色背景（预览 / 无源的场合）。
 * @param style 填充 / 描边 / 高光配色。
 */
@Composable
fun LiquidGlassLayer(
    shape: Shape,
    enabled: Boolean,
    style: LiquidGlassStyle,
    modifier: Modifier = Modifier,
    source: LiquidGlassSource? = null,
    /** 按压联动。收 State 是为了在绘制阶段读值，动画期间不重组（见 [NoGlassPress]）。 */
    press: State<GlassPress> = NoGlassPress,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val blur = shouldApplyBlur(requested = enabled)
    val radiusPx = with(density) { BlurRadius.toPx() }
    // 折射的基础参数集中在这里算，避免 `liquidGlassBackdrop` 里再写一份 dp 字面量。
    // 按压增量在绘制阶段叠加（见 [Modifier.liquidGlassBackdrop]），胶囊尺寸一个像素都不动。
    val depthPx = with(density) { LiquidGlass.RefractionDepth.toPx() }
    val partPx = with(density) { LiquidGlass.ChromaticAberration.toPx() }
    // 浮层相对源的偏移。**必须是 state**：坐标系每帧都可能变（旋转、手势条、字号），
    // 而 `drawWithCache` 只在 key 变化时重建 —— 用普通字段会停在首帧的偏移上（底栏错位一截）。
    var offset by remember { mutableStateOf(Offset.Zero) }

    Box(modifier = modifier) {
        if (blur && source != null) {
            // ★ 层 A：只画"源被模糊之后的样子"。这里**不能**再画任何填充 ——
            //   同一个 renderEffect 会把它自己的填充也糊掉（见文件头 KDoc 的第四条注释）。
            //
            // ⚠️ `.clip(shape)` 是层 A 的第一要务：源录的是整块 Tab 区域，比胶囊高得多，
            //   不裁剪就会把一大块糊影画到胶囊外面去。
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(shape)
                    // 位置要在 backdrop **之前**更新：修饰符链按顺序执行，
                    // 放后面会让首帧用旧 offset 画一次（真机上表现为底栏糊影往上错一截）。
                    // ⚠️ `boundsInRoot()` 要取**自己**的位置，所以这个节点外的
                    //   `onGloballyPositioned` 拿到的就是本层 —— 与 [backdrop] 同尺寸。
                    .onGloballyPositioned { coords ->
                        val own = coords.boundsInRoot()
                        val src = source.bounds
                        // ⚠️ 符号别手写：底栏在源下方 ⇒ 该值 y 为负，绘制时按**同值**平移。
                        val next = backdropOffset(sourceBounds = src, layerBounds = own)
                        if (next != offset) offset = next
                    }
                    .liquidGlassBackdrop(
                        source = source,
                        offset = offset,
                        radiusPx = radiusPx,
                        depthPx = depthPx,
                        partPx = partPx,
                        press = press,
                    ),
            )
        }
        // ★ 层 B：视觉装饰 + 内容。装饰画在 `drawBehind`，因此永远是清晰的。
        //
        // 🔴 这一层必须是**唯一参与父 Box 测量**的子节点（2026-10-28 第四轮修复）。
        //
        //   原先它和层 A 一样写 `matchParentSize()` ⇒ 父 Box 里**没有一个可测量的子节点**
        //   ⇒ 高度只能取约束下限 = **0** ⇒ `clip(shape)` 把 0 高的区域裁成空
        //   ⇒ **整条底栏不可见**（用户报「打开液态玻璃后，底栏消失不见了」）。
        //
        //   `matchParentSize()` 的语义是「尺寸跟随父，且**不参与**父的尺寸计算」——
        //   全都用它，就等于没有谁告诉父 Box "你该多高"。这不是"没生效"，是"算出来就是 0"。
        //   第二轮的"整页变白"把这个问题盖住了（屏幕一片白时看不出底栏没了）。
        //
        //   ⇒ 这里改成按内容测量：`fillMaxWidth()` 只锁宽度，高度随 [content]。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .drawBehind {
                    if (enabled) {
                        drawGlass(
                            style = style,
                            density = density,
                            shape = shape,
                            // ★ 绘制阶段读 State：按压 spring 每帧只 invalidate 绘制，
                            //   不会把 5 个 tab 拖进重组。
                            press = press.value,
                            blurAvailable = blur,
                        )
                    }
                },
        ) {
            content()
        }
    }
}

/**
 * 填充 + 上沿高光带 + 沿轮廓镜面 rim + 内描边。**画在浮层的上层**（层 B 的 `drawBehind`）。
 *
 * ## 2026-11-10 第四轮：所有几何一律按 [Shape] 的 Outline 画
 *
 * 旧版全部用 `drawRect`：填充是矩形、上沿高光是矩形横带、亮线是矩形顶边、
 * 内描边是矩形四边。层 B 外面虽然 `.clip(Pill)`，但**被裁掉的正是药丸两端的半圆**
 * ⇒ 圆角处既没有高光也没有描边，玻璃读起来像「上下有边、左右没边」的一块板。
 * 用户这轮的「注意底栏的边界」指的就是它。
 *
 * 现在 [drawOutline] 用同一个 `shape.createOutline(...)` 画四层，圆角连续：
 * ① 填充（含上亮下暗的厚度渐变）；② 上沿渐隐高光带；③ 沿轮廓的镜面 rim
 * （垂直渐变：上亮 → 侧淡 → 下沿暗线）；④ 按压焦点高光；⑤ 一圈弱内描边。
 *
 * ⚠️ 降级档（`blurAvailable = false`）填充已是不透明实色，只画 ① 与 ⑤：
 * 给实色板叠镜面/暗线会变成两道塑料描边（旧版就靠那个 `if` 挡住）。
 *
 * ⚠️ **本函数只读 `size`，从不写任何尺寸**；[press] 只改颜色 alpha 与折射深度，
 * 因此按压不会改变底栏的边界与大小（#205 的教训）。
 */
private fun DrawScope.drawGlass(
    style: LiquidGlassStyle,
    density: Density,
    shape: Shape,
    press: GlassPress,
    blurAvailable: Boolean,
) {
    val outline = shape.createOutline(size, layoutDirection, density)
    val fill = style.fill
    // ① 填充：真模糊档叠一道极轻的竖向渐变做"厚度"；降级档不加（不透明色做渐变=两块塑料）。
    drawOutline(
        outline = outline,
        brush = if (blurAvailable) {
            Brush.verticalGradient(listOf(fill, fill.copy(alpha = fill.alpha * 0.94f)))
        } else {
            Brush.verticalGradient(listOf(fill, fill))
        },
    )

    // ② 上沿渐隐高光带：仍按轮廓填充，靠 gradient 自己衰减，不需要 clipRect。
    val band = (size.height * LiquidGlass.EDGE_HIGHLIGHT_FRACTION).coerceAtLeast(1f)
    drawOutline(
        outline = outline,
        brush = Brush.verticalGradient(
            colors = listOf(style.sheen.copy(alpha = LiquidGlass.TOP_SHEEN_ALPHA), Color.Transparent),
            startY = 0f,
            endY = band,
        ),
    )

    val strokeWidth = with(density) { LiquidGlass.InnerStrokeWidth.toPx() }
    if (!blurAvailable) {
        drawOutline(outline = outline, color = style.stroke, style = Stroke(width = strokeWidth))
        return
    }

    // ③ 镜面 rim：整圈沿轮廓，垂直渐变让"上沿受光、侧沿弱、下沿压暗"。
    //    旧版的 1dp 亮线 + 底部矩形暗线合并进这一笔，圆角不再断。
    drawSpecularRim(style = style, outline = outline, density = density, press = press)

    // ④ 焦点高光：只在按住时画，光源落在被按 tab 的上沿。
    if (press.intensity > 0f) {
        drawFocusSheen(style = style, outline = outline, density = density, press = press)
    }

    // ⑤ 内描边：比镜面 rim 更淡，职责是"与背后内容分界"，不是"打光"。
    drawOutline(outline = outline, color = style.stroke, style = Stroke(width = strokeWidth))
}

/** 沿轮廓的镜面 rim（拆出来是为了让 `drawGlass` 不贴 detekt 复杂度上限）。 */
private fun DrawScope.drawSpecularRim(
    style: LiquidGlassStyle,
    outline: Outline,
    density: Density,
    press: GlassPress,
) {
    val width = with(density) { LiquidGlass.SpecularWidth.toPx() }
    val peak = (LiquidGlass.SPECULAR_ALPHA *
        (1f + LiquidGlass.PRESS_SHEEN_GAIN * press.intensity)).coerceAtMost(1f)
    drawOutline(
        outline = outline,
        brush = Brush.verticalGradient(
            colors = listOf(
                style.sheen.copy(alpha = peak),
                style.sheen.copy(alpha = LiquidGlass.SPECULAR_TAIL_ALPHA),
                LiquidGlass.RimLineColor,
            ),
            startY = 0f,
            endY = size.height,
        ),
        style = Stroke(width = width),
    )
}

/** 按压时的焦点高光：径向渐隐，沿轮廓描一遍，读作"光晕扫到被按的位置"。 */
private fun DrawScope.drawFocusSheen(
    style: LiquidGlassStyle,
    outline: Outline,
    density: Density,
    press: GlassPress,
) {
    val width = with(density) { LiquidGlass.SpecularWidth.toPx() } *
        LiquidGlass.FOCUS_WIDTH_FACTOR
    drawOutline(
        outline = outline,
        brush = Brush.radialGradient(
            colors = listOf(
                style.sheen.copy(alpha = LiquidGlass.FOCUS_SHEEN_ALPHA * press.intensity),
                Color.Transparent,
            ),
            // #278：焦点随手指（focusX/focusY 都是精确触控位置），不再是"上沿的槽中心"。
            center = Offset(size.width * press.focusX, size.height * press.focusY),
            radius = size.width * LiquidGlass.FOCUS_RADIUS_FRACTION,
        ),
        style = Stroke(width = width),
    )
}

/**
 * 深色判定的亮度中点。
 *
 * 0.5 不是随手取的：WCAG 的相对亮度在中点附近最容易分错，而 Material 的深浅两套
 * `colorScheme` 里 `surfaceContainerHigh` 恰好分别落在 0.02~0.15（深）与 0.85~0.95（浅），
 * 离 0.5 都足够远 ⇒ 判定不会因为主题微调而翻转。
 */
private const val DARK_LUMINANCE_THRESHOLD = 0.5f

/** 深色判定：取实际渲染底色的亮度，**不要**用 `isSystemInDarkTheme()`。 */
internal fun isDarkSurface(surface: Color): Boolean = surface.luminance() <= DARK_LUMINANCE_THRESHOLD
