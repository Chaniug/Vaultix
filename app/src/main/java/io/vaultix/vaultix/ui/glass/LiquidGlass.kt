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
 *
 * ## 2026-11-11 第七轮（#281）：常态磨砂与液态玻璃**共用这条链路**
 *
 * 原来「关液态玻璃」= 一块不透明 `Surface`，用户要「平常状态也能隐约看到下方内容」。
 * 本轮把开关语义从「有没有模糊」改成「哪种材质」：[DockMaterial.Frosted] 用更大半径的
 * 模糊 + 更实的填充、不挂 AGSL；[DockMaterial.Liquid] 保持全套光学。
 * ⚠️ 两档的层 A / 层 B / 坐标偏移 / 测量结果**完全一致**，只是效果链末端与绘制层数不同。
 */

/**
 * 2026-11-11 第七轮（#281）：底栏材质**分档**。
 *
 * 11-09 那轮为了「淡淡看到下方条目」把模糊 24→18dp、填充 0.40→0.38/0.52、
 * 阴影 8→6dp 一路调淡；#280 又只补了动态光学。用户这轮反馈「质感和通透感还很薄、
 * 整体效果不强」—— 根因不是**不够透**，是**透得没有玻璃的自我**：一大片 0.38 的
 * 半透明盖在 18dp 模糊上，背后的条目形状还读得出来，和图标抢视觉，边界又薄，
 * 结果既不像玻璃也不像板。
 *
 * ⇒ 本轮反向修正：模糊加大（透出来的是**色块**而不是可辨文字），填充才有余量回提；
 *   再补沿轮廓的**内阴影**做厚度。两件事分开：
 *
 * | 材质 | 开关 | 模糊 | 填充 | 光学 |
 * |---|---|---|---|---|
 * | [DockMaterial.Frosted] | 关（默认） | 30dp | 0.74 / 0.80 | 静态磨砂，无 AGSL / 无流光 |
 * | [DockMaterial.Liquid] | 开 | 24dp | 0.50 / 0.62 | AGSL 折射 / 分色 / 跟手流光 |
 *
 * ⚠️ 两档**共用同一条**「源 → 效果层 → 消费」链路与同一个 [LiquidGlassLayer]，
 *    不另起一套采样 —— 本项目已因「同一规则写两处」栽过三次（#120/#193/#195）。
 * ⚠️ 只改观感；采样、层级、坐标偏移、底栏边界与大小一行未动。
 */

/**
 * 底栏材质档位。
 *
 * @property Frosted 常态磨砂：更实的填充 + 更大的模糊，隐约透出下方内容的色块，
 *   不折射、不流光、不跟手 —— 「平常状态」应当安静。
 * @property Liquid 液态玻璃：更透、有边缘折射 / 色散 / 按压流光 / 跟随手指的高光。
 */
enum class DockMaterial { Frosted, Liquid }

/** 设置开关 → 材质。抽成纯函数是为了 JVM 单测能钉住「开关关 = 磨砂而不是实色」。 */
internal fun dockMaterialOf(liquidGlass: Boolean): DockMaterial =
    if (liquidGlass) DockMaterial.Liquid else DockMaterial.Frosted

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
 *    22:20 用户真机反馈「常态还是看不到条目」后再降到 0.38 / 0.52。
 *
 * ⚠️ **2026-11-11 #281 反向修正**：连续两轮调淡之后，用户反馈「质感和通透感还很薄、
 *    整体效果不强」。玻璃感来自「透出的内容」与「玻璃自身」的**对比**，不是越透越好：
 *    0.38 的填充几乎没有自我，只剩一层雾。本轮回提到 **0.50 / 0.62**
 *    （仍在 ultraThin 与 thin 之间），同时把模糊从 18dp 提到 24dp ——
 *    模糊更充分 ⇒ 透出来的是柔和色块而非可辨文字 ⇒ 填充回提也不会挡内容。
 */
const val FILL_ALPHA_LIGHT = 0.50f
const val FILL_ALPHA_DARK = 0.62f

/**
 * 常态**磨砂**档的填充不透明度（#281 新增）。
 *
 * 取 Haze `regular`（0.73 / 0.80）口径：用户要的是「隐约看到下方内容」，
 * 不是「透过底栏读列表」。比液态玻璃明显更实，两档一眼能分出来，开关才有意义。
 * 30dp 模糊 + 0.74/0.80 填充 ⇒ 只剩色块轮廓，文字完全不可读，图标对比度也稳。
 */
const val FROSTED_FILL_ALPHA_LIGHT = 0.74f
const val FROSTED_FILL_ALPHA_DARK = 0.80f

/** 两档模糊半径：磨砂比液态玻璃更"雾"，符合「磨砂」而非「清玻璃」的语义。 */
val LiquidBlurRadius = 24.dp
val FrostedBlurRadius = 30.dp

    /**
     * 降级（无模糊硬件能力）时**直接画实色**，而不是"半透明 + 一点补偿"。
     *
     * Haze 的做法同样是降级成 flat scrim（`fallbackColorEffect`），而不是留半透明糊弄：
     * 半透明的底栏浮在滚动内容上，文字会**穿过**底栏（读不清），那才是真正不可用的状态。
     * ⇒ 无模糊 = 主题色 `surfaceContainerHigh` **全不透明**，观感等同第一版的实心底栏。
     * ⚠️ 本轮「默认开」在低版本走的就是这一档，所以默认开不会造成可用性回退。
     */
    const val FALLBACK_ALPHA = 1f

    /** 上沿高光带高（占整体高度的比例）。#281：0.16 → 0.20，边界受光面更可读。 */
    const val EDGE_HIGHLIGHT_FRACTION = 0.20f

    /**
     * 上沿高光透明度（0.55 → 0.32 → **0.42**）。
     *
     * 22:37 用户反馈「玻璃效果看不出来」：填充已经很透，但当列表条目不足一屏、
     * 或静止时胶囊后面恰好是空白，透出来的就是**纯背景** —— 此时唯一能证明
     * 「这是一块玻璃而不是实色板」的，只剩上沿高光与内描边。
     * ⇒ 适度回提装饰，但不回到 0.55 那种塑料感；填充透明度保持不变。
     */
    const val TOP_SHEEN_ALPHA = 0.48f

    /** 磨砂档上沿高光：刻意比液态玻璃淡，保持「平常状态」的安静。 */
    const val FROSTED_TOP_SHEEN_ALPHA = 0.18f

    /** 内描边透明度（深色下白描边要更淡；单测钉住 dark < light）。 */
    const val STROKE_ALPHA_LIGHT = 0.42f
    const val STROKE_ALPHA_DARK = 0.28f

    /** 磨砂档内描边：填充已经很实，描边再重会变成塑料圈。 */
    const val FROSTED_STROKE_ALPHA_LIGHT = 0.26f
    const val FROSTED_STROKE_ALPHA_DARK = 0.16f

    /**
     * 阴影高度（8 → 6 → **8dp**）。
     *
     * 11-09 因「玻璃调淡」把它降到 6dp；#281 填充回提、又补了内阴影，
     * 8dp 才把半透明胶囊从滚动内容里托起来，不会重新压成塑料板。
     */
    val ShadowElevation = 8.dp

    // ── 2026-11-10 第四轮：沿轮廓的光学规格 ──────────────────────────────
    //
    // 旧实现用 `drawRect` 画「顶部 1dp 亮线 + 矩形内描边 + 底部矩形暗线」，
    // 外层 `.clip(Pill)` 之后，药丸**两端半圆既没有高光也没有描边** ⇒ 玻璃看起来
    // 像「上下有边、左右没边」的一块板。真实液态玻璃的边界靠**沿轮廓**的
    // specular rim 定义（iOS 26 HIG / PrismalAGSL / LiquidGlassBar 口径一致）。
    // ⇒ 现在填充 / 高光带 / 镜面 rim / 内描边一律按 [Shape] 的 Outline 画。

    /** 背景饱和度增益。1 = 原样；Haze vibrancy / Prismal saturation 常用 1.3~1.5。 */
    const val SATURATION = 1.38f

    /** 镜面 rim 线宽。#281：1.6 → 2dp，边界才有"玻璃有厚度"而不是"画了条边"。 */
    val SpecularWidth = 2.dp

    /** 镜面 rim 上沿强度（垂直渐变起点）。#281：0.52 → 0.62，配合回提的填充拉开层次。 */
    const val SPECULAR_ALPHA = 0.62f

    /** 磨砂档镜面 rim：只留一条极弱受光边，不与液态玻璃抢"玻璃感"。 */
    const val FROSTED_SPECULAR_ALPHA = 0.24f

    // ── 2026-11-11 第七轮（#281）：沿轮廓的**内阴影** ──────────────────
    //
    // 之前只有「上沿高光 + 一圈内描边」，边缘缺少暗部 ⇒ 玻璃像是"贴"在内容上，
    // 没有厚度、也不浮起。真实玻璃的厚边来自全内反射：上沿受光、下沿偏暗，
    // 侧沿介于两者之间。这里沿 Outline 描一圈带垂直渐变的 Stroke，中心透明、
    // 下沿最暗；层 B 已 `.clip(shape)`，描边外侧那一半会被裁掉 ⇒ 正好是内阴影。
    // ⚠️ 与其余装饰同口径：只画颜色、不碰尺寸，圆角两端连续不断。

    /** 液态玻璃内阴影峰值（下沿）。 */
    const val INNER_SHADOW_ALPHA = 0.26f

    /** 磨砂档内阴影峰值：填充更实，暗边要更轻，否则整圈发灰。 */
    const val FROSTED_INNER_SHADOW_ALPHA = 0.12f

    /** 内阴影上沿保留的暗度比例（上沿主要受光，不该和下沿一样暗）。 */
    const val INNER_SHADOW_TOP_FACTOR = 0.15f

    /** 内阴影沿轮廓的描边宽度。比镜面 rim 宽，读作"厚度"而不是"又一条亮线"。 */
    val InnerShadowWidth = 3.dp

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

    /**
     * 触摸光晕**半径** = 胶囊高度 × 该系数。
     *
     * 🔴 2026-11-11 第六轮：从 0.9 改为 **0.42**。旧值不是"太大"的问题，是**量纲错了**：
     * #278 的 shader 写 `exp(-d² / uGlow.w)`，数学上把 w 当**方差**用，而这里传的是**半径**
     * ⇒ 实际可见半径只有 `√w`。以 60dp 胶囊 ≈156px 算，旧值 w≈140px 时光晕只有 ≈12px，
     * 真机上几乎看不见 —— 用户说的「点击的那种流光没做出来」就是它。
     * 现在 shader 改成 `exp(-d² / w²)`，w 才是真半径；0.42 × 156 ≈ 65px，
     * 光晕覆盖约大半个胶囊高度，读作"从手指亮开"而不是一个白点。
     */
    const val TOUCH_GLOW_REACH_FACTOR = 0.42f

    // ── 2026-11-11 第六轮：通透厚度 + 表面流光 + 按压透镜 ──────────────
    //
    // #277 把折射/色散/光晕全放进 API 33+ 的 AGSL，#278 又只补了 shader 内的光晕。
    // 后果有两个：① Android 12/12L（31-32）只能看到模糊 + 静态 rim，点按时玻璃毫无光学反应；
    // ② 光晕画在层 A（模糊背景）上，又被层 B 的 0.38/0.52 实色填充盖掉一层 ⇒ 双重衰减。
    //
    // ⇒ 本轮把「玻璃表面」的活搬回层 B 的 drawBehind：厚度感（静态）与流光（按压）
    //   一律沿 Outline 填充，画在填充之上、图标之下，**覆盖 31+ 所有真模糊档**，
    //   不依赖 AGSL。shader 只保留"透出来的内容"该有的折射 / 透镜 / 分色。
    // ⚠️ 与第四/第五轮同一条硬边界：全部只改颜色与渐变中心，不碰任何尺寸。

    /**
     * 玻璃中心「通透」提亮峰值（静态，沿轮廓填充的**横向三段渐变**）。抵消模糊后的灰雾感。
     *
     * ⚠️ 刻意不用径向渐变：胶囊宽高比 ≈6:1，按宽度取半径会在高度方向铺满整条底栏，
     *    「中心亮、两端淡」完全读不出来 —— 见 [INNER_VIGNETTE_START_FRACTION]。
     */
    const val INNER_CLARITY_ALPHA = 0.10f

    /** 下沿渐晕峰值：与中心提亮配对，做出上透下压的厚度，而不是一块平板。 */
    const val INNER_VIGNETTE_ALPHA = 0.09f

    /**
     * 表面流光内部光斑峰值（再乘 intensity²）。
     *
     * 关键在**画在哪**：它在层 B 的 drawBehind、填充之上，所以不会被填充吃掉；
     * 与 shader 里那个照亮模糊背景的 `uGlow` 是两层，一内一表，才有玻璃的层次。
     */
    const val SURFACE_GLOW_ALPHA = 0.30f

    /** 表面光斑半径 = 胶囊宽度 × 该系数（比 shader 光晕宽，读作"表面反射"）。 */
    const val SURFACE_GLOW_RADIUS_FRACTION = 0.34f

    /** 斜向扫光带峰值（乘 intensity）：一条随手指横向移动的高光带，不是又一个斑点。 */
    const val SWEEP_ALPHA = 0.16f

    /** 扫光带宽度占胶囊宽度的比例。 */
    const val SWEEP_WIDTH_FRACTION = 0.42f

    /** 扫光带倾斜量（终点相对起点的竖直偏移，占胶囊高度比例）。 */
    const val SWEEP_TILT_FRACTION = 0.85f

    /** 按压透镜：手指下内容被径向推开的强度（乘 uGlow.z 与 uDepth，喂给 AGSL）。 */
    const val LENS_STRENGTH = 2.0f

    /** shader 内玻璃中心提亮（33+ 档与 31-32 的 [INNER_CLARITY_ALPHA] 同职责）。 */
    const val SHADER_CLARITY = 0.055f

    // 几何比例也进参数表：detekt `MagicNumber` 会钉住 `Brush` 实参里的裸浮点，
    // 且「参数集中一处」本来就是本文件的纪律。
    /**
     * 下沿渐晕起点（占胶囊高度比例）：之上完全不压暗，保住中心通透。
     *
     * ⚠️ 通透提亮刻意**不用** `Brush.radialGradient`：胶囊宽高比 ≈6:1，按宽度取半径
     * 会在高度方向铺满整条底栏，「中心亮、两端淡」完全读不出来。改用横向三段线性渐变。
     */
    const val INNER_VIGNETTE_START_FRACTION = 0.52f

    /** 扫光带半宽系数：把"全宽比例"折成单侧半宽。 */
    const val SWEEP_HALF_FACTOR = 0.5f

    /**
     * 暗线颜色：固定黑叠 alpha，不随主题反色（玻璃的暗边在两个主题里都是暗的）。
     * #281：0.10 → 0.18，原来那条下沿暗线几乎看不见，玻璃读不出"压在内容上"。
     */
    val RimLineColor = Color.Black.copy(alpha = 0.18f)
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
 * @param material #281 的两档材质。默认值 [DockMaterial.Liquid] 保证老调用点行为不变。
 */
fun liquidGlassStyle(
    darkTheme: Boolean,
    surface: Color,
    onSurface: Color,
    blurAvailable: Boolean = true,
    material: DockMaterial = DockMaterial.Liquid,
): LiquidGlassStyle {
    val frosted = material == DockMaterial.Frosted
    val alpha = when {
        !blurAvailable -> LiquidGlass.FALLBACK_ALPHA
        frosted && darkTheme -> LiquidGlass.FROSTED_FILL_ALPHA_DARK
        frosted -> LiquidGlass.FROSTED_FILL_ALPHA_LIGHT
        darkTheme -> LiquidGlass.FILL_ALPHA_DARK
        else -> LiquidGlass.FILL_ALPHA_LIGHT
    }
    val strokeAlpha = when {
        frosted && darkTheme -> LiquidGlass.FROSTED_STROKE_ALPHA_DARK
        frosted -> LiquidGlass.FROSTED_STROKE_ALPHA_LIGHT
        darkTheme -> LiquidGlass.STROKE_ALPHA_DARK
        else -> LiquidGlass.STROKE_ALPHA_LIGHT
    }
    return LiquidGlassStyle(
        fill = surface.copy(alpha = alpha),
        stroke = onSurface.copy(alpha = strokeAlpha),
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
 * @param enabled 是否挂模糊链路。#281 起两档材质都传 true —— 开关决定的是
 *   [material]（磨砂 / 液态玻璃），不是"有没有模糊"。API < 31 时由 [shouldApplyBlur] 降级。
 * @param source 背景源；null 时等同一块纯色背景（预览 / 无源的场合）。
 * @param style 填充 / 描边 / 高光配色。
 * @param material #281 材质档位：只换模糊半径、是否走 AGSL、绘制哪几层光学装饰，
 *   **不换层级结构**。默认值保证老调用点仍是液态玻璃。
 */
@Composable
fun LiquidGlassLayer(
    shape: Shape,
    enabled: Boolean,
    style: LiquidGlassStyle,
    modifier: Modifier = Modifier,
    source: LiquidGlassSource? = null,
    material: DockMaterial = DockMaterial.Liquid,
    /** 按压联动。收 State 是为了在绘制阶段读值，动画期间不重组（见 [NoGlassPress]）。 */
    press: State<GlassPress> = NoGlassPress,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val blur = shouldApplyBlur(requested = enabled)
    // #281：两档共用同一条链路，只换半径与"要不要走 AGSL"。
    val radius = when (material) {
        DockMaterial.Frosted -> LiquidGlass.FrostedBlurRadius
        DockMaterial.Liquid -> LiquidGlass.LiquidBlurRadius
    }
    val radiusPx = with(density) { radius.toPx() }
    val opticsEnabled = material == DockMaterial.Liquid
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
                        opticsEnabled = opticsEnabled,
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
                            material = material,
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
 * 现在 [drawOutline] 用同一个 `shape.createOutline(...)` 画，圆角连续。
 * **液态玻璃档八层**：① 填充（含上亮下暗的厚度渐变）；② 上沿渐隐高光带；
 * ③ 沿轮廓内阴影（#281，上淡下暗的厚度）；④ 内部通透厚度（#280 静态）；
 * ⑤ 按压表面流光（#280 光斑 + 斜扫光带）；⑥ 沿轮廓镜面 rim（上亮 → 侧淡 → 下沿暗线）；
 * ⑦ 按压焦点 rim 高光；⑧ 一圈弱内描边。
 * **磨砂档（#281）五层**：①② 同上但高光更淡 → ③ 更弱的内阴影 → 一条极弱受光 rim → ⑧ 内描边。
 * ④⑤ 是**填充类**且画在层 B 的 drawBehind，覆盖 API 31+ 所有真模糊档，
 * 不依赖 API 33 的 AGSL —— 见 `LiquidGlassOptics.kt` 与 #280 的施工记录。
 *
 * ⚠️ 降级档（`blurAvailable = false`）填充已是不透明实色，只画 ① 与内描边：
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
    material: DockMaterial,
) {
    val frosted = material == DockMaterial.Frosted
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
    //    #281 磨砂档更淡（"平常状态"不抢眼），液态玻璃回提到 0.48。
    val band = (size.height * LiquidGlass.EDGE_HIGHLIGHT_FRACTION).coerceAtLeast(1f)
    val sheenAlpha = if (frosted) {
        LiquidGlass.FROSTED_TOP_SHEEN_ALPHA
    } else {
        LiquidGlass.TOP_SHEEN_ALPHA
    }
    drawOutline(
        outline = outline,
        brush = Brush.verticalGradient(
            colors = listOf(style.sheen.copy(alpha = sheenAlpha), Color.Transparent),
            startY = 0f,
            endY = band,
        ),
    )

    val strokeWidth = with(density) { LiquidGlass.InnerStrokeWidth.toPx() }
    if (!blurAvailable) {
        drawOutline(outline = outline, color = style.stroke, style = Stroke(width = strokeWidth))
        return
    }

    // ③ 内阴影（#281，两档共有）：沿轮廓描一圈"上淡下暗"的透明渐变，
    //    层 B 的 clip 会裁掉描边外侧那一半 ⇒ 读作玻璃的厚边而不是又一条亮线。
    drawInsetShadow(
        outline = outline,
        density = density,
        alpha = if (frosted) {
            LiquidGlass.FROSTED_INNER_SHADOW_ALPHA
        } else {
            LiquidGlass.INNER_SHADOW_ALPHA
        },
    )

    if (frosted) {
        // 磨砂档：只留一条极弱受光 rim。**不画**中心提亮 / 表面流光 / 焦点高光 ——
        // 那些是液态玻璃的身份特征；常态磨砂应当安静，且不与图标抢注意力。
        drawOutline(
            outline = outline,
            brush = Brush.verticalGradient(
                colors = listOf(
                    style.sheen.copy(alpha = LiquidGlass.FROSTED_SPECULAR_ALPHA),
                    Color.Transparent,
                ),
                startY = 0f,
                endY = size.height,
            ),
            style = Stroke(width = with(density) { LiquidGlass.SpecularWidth.toPx() }),
        )
    } else {
        // ④ 通透厚度（静态，液态玻璃档 31+ 全档）：中心提亮 + 下沿渐晕。
        drawOpticalBody(style = style, outline = outline)

        // ⑤ 表面流光（按压，液态玻璃档 31+ 全档）：画在 rim 之前才不会盖住边缘高光。
        drawSurfaceSweep(style = style, outline = outline, press = press)

        // ⑥ 镜面 rim：垂直渐变让"上沿受光、侧沿弱、下沿压暗"，圆角不断。
        drawSpecularRim(style = style, outline = outline, density = density, press = press)

        // ⑦ 焦点 rim 高光：只在按住时画，光源跟随手指（函数内自己挡 intensity=0）。
        drawFocusSheen(style = style, outline = outline, density = density, press = press)
    }

    // ⑧ 内描边：比镜面 rim 更淡，职责是"与背后内容分界"，不是"打光"。两档共有。
    drawOutline(outline = outline, color = style.stroke, style = Stroke(width = strokeWidth))
}

/**
 * 沿轮廓的**内阴影**（#281）：上沿只留极淡暗度，中段透明，下沿最暗。
 *
 * 之前玻璃只有「上沿高光 + 一圈内描边」，边缘缺暗部 ⇒ 像是"贴"在内容上，
 * 既没厚度也不浮起。真实玻璃的厚边来自全内反射，上受光、下偏暗。
 *
 * ⚠️ 用 [drawOutline] + [Stroke]：描边中心落在轮廓线上，层 B 的 `.clip(shape)`
 *    正好把外侧那半裁掉 ⇒ 留下的就是内阴影，圆角两端连续不断（规则 1）。
 * ⚠️ 只画颜色，不碰任何尺寸。
 */
private fun DrawScope.drawInsetShadow(
    outline: Outline,
    density: Density,
    alpha: Float,
) {
    val top = alpha * LiquidGlass.INNER_SHADOW_TOP_FACTOR
    drawOutline(
        outline = outline,
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.Black.copy(alpha = top),
                Color.Transparent,
                Color.Black.copy(alpha = alpha),
            ),
            startY = 0f,
            endY = size.height,
        ),
        style = Stroke(width = with(density) { LiquidGlass.InnerShadowWidth.toPx() }),
    )
}

/**
 * 玻璃内部的「通透厚度」（第六轮，静态；液态玻璃档 31+ 全档）。
 *
 * 只做模糊 + 饱和度时，胶囊中心离边缘 >`uDepth × 3`（≈39px）的区域是**零光学变化**
 * 的一块模糊底图，再叠一层 0.38/0.52 填充 ⇒ 读作"蒙了雾的板"，而不是透亮的玻璃。
 * 这里沿 [Outline] 补两层极淡的填充：横向的「中心亮、两端淡」提亮 + 下沿渐晕，
 * 做出「上透下压」。两层都刻意不用径向渐变（见 [LiquidGlass.INNER_VIGNETTE_START_FRACTION]）。
 *
 * ⚠️ 画在层 B 的 `drawBehind`，**液态玻璃档覆盖 API 31+ 所有真模糊档**，不依赖 AGSL；
 *    33+ 的 shader 另有 [LiquidGlass.SHADER_CLARITY] 负责照亮"透出来的内容"，两者分工。
 *    #281 起磨砂档刻意不画：它要的是均匀雾面，不是"中心透亮"的清玻璃。
 * ⚠️ 降级档不画（[drawGlass] 已提前 return）—— 实色板做厚度渐变等于两块塑料。
 */
private fun DrawScope.drawOpticalBody(style: LiquidGlassStyle, outline: Outline) {
    drawOutline(
        outline = outline,
        brush = Brush.horizontalGradient(
            colors = listOf(
                Color.Transparent,
                style.sheen.copy(alpha = LiquidGlass.INNER_CLARITY_ALPHA),
                Color.Transparent,
            ),
            startX = 0f,
            endX = size.width,
        ),
    )
    drawOutline(
        outline = outline,
        brush = Brush.verticalGradient(
            colors = listOf(
                Color.Transparent,
                Color.Black.copy(alpha = LiquidGlass.INNER_VIGNETTE_ALPHA),
            ),
            startY = size.height * LiquidGlass.INNER_VIGNETTE_START_FRACTION,
            endY = size.height,
        ),
    )
}

/**
 * 按压时的**表面流光**（第六轮）：内部光斑 + 斜向扫光带。
 *
 * #278 的触摸光晕只存在于 API 33+ 的 AGSL 里，且画在层 A（模糊背景）上，
 * 又被层 B 的实色填充盖掉一层 ⇒ 31-32 完全没有点击光学反馈，33+ 也淡到看不见。
 * 这里把"玻璃表面该有的反射"画在层 B、填充之上：**液态玻璃档 31+ 全档可见**。
 *
 * ① 光斑：径向渐隐，中心是手指精确位置，强度乘 `intensity²`（轻碰微亮、压实才亮）；
 * ② 扫光带：斜向线性渐变，横向随手指移动，读作"一道光扫过玻璃"而不是一个亮点。
 *
 * ⚠️ 两次都走 [drawOutline]（规则 1 钉住），不碰任何尺寸（规则 4 钉住）；
 *    与 shader 内照亮模糊背景的 `uGlow` 是**一表一里**两层，不是重复实现。
 */
private fun DrawScope.drawSurfaceSweep(
    style: LiquidGlassStyle,
    outline: Outline,
    press: GlassPress,
) {
    if (press.intensity <= 0f) return
    val strength = press.intensity
    val cx = size.width * press.focusX
    val cy = size.height * press.focusY
    drawOutline(
        outline = outline,
        brush = Brush.radialGradient(
            colors = listOf(
                style.sheen.copy(alpha = LiquidGlass.SURFACE_GLOW_ALPHA * strength * strength),
                Color.Transparent,
            ),
            center = Offset(cx, cy),
            radius = size.width * LiquidGlass.SURFACE_GLOW_RADIUS_FRACTION,
        ),
    )
    val halfWidth = size.width * LiquidGlass.SWEEP_WIDTH_FRACTION * LiquidGlass.SWEEP_HALF_FACTOR
    val tilt = size.height * LiquidGlass.SWEEP_TILT_FRACTION
    drawOutline(
        outline = outline,
        brush = Brush.linearGradient(
            colors = listOf(
                Color.Transparent,
                style.sheen.copy(alpha = LiquidGlass.SWEEP_ALPHA * strength),
                Color.Transparent,
            ),
            start = Offset(cx - halfWidth, -tilt),
            end = Offset(cx + halfWidth, size.height + tilt),
        ),
    )
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

/**
 * 按压时的焦点 rim 高光：径向渐隐，沿轮廓描一遍，读作"光晕扫到被按的位置"。
 *
 * 第六轮起自己挡 `intensity = 0`（[drawGlass] 不再包 `if`），与 [drawSurfaceSweep] 同口径：
 * 松手后不留任何残余高光，也不让绘制层在常态多做一次判断分支外的计算。
 */
private fun DrawScope.drawFocusSheen(
    style: LiquidGlassStyle,
    outline: Outline,
    density: Density,
    press: GlassPress,
) {
    if (press.intensity <= 0f) return
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
