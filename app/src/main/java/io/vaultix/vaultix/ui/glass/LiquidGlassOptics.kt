/*
 * Vaultix — app:ui:glass
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.glass

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.RenderEffect as ComposeRenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect

/**
 * 液态玻璃的**光学层**：在既有「源 → 模糊中转层」链路末端补一道 AGSL shader。
 *
 * ## 为什么可以不做 GPU→CPU 回读
 *
 * `LiquidGlass.kt` 文件头那条「折射需要 `toImageBitmap()` 每帧回读」的结论，成立于
 * 「把背景当 Bitmap 纹理喂给 Paint.shader」的写法。这里走的是另一条路：
 * [RenderEffect.createRuntimeShaderEffect] 让 shader 通过 `uniform shader content`
 * **直接采样 RenderNode 自己的内容**，再用 [RenderEffect.createChainEffect] 把
 * 「模糊」放在链的内层、「折射」放在外层 —— 输入纹理就是模糊结果，全程留在 GPU。
 *
 * 这与 FlClash / prism_ui 的「一次捕获、多个 pane 共享采样」是同一条思路的 Compose 版：
 * 本项目只有一个 pane（底栏），`LiquidGlassSource.effectLayer` 就是那张共享图。
 *
 * ## 三档降级（minSdk 26 ⇒ 不能只写一套）
 *
 * | 系统 | 链路 |
 * |---|---|
 * | ≥ 33 | 模糊 → **饱和度增强** → AGSL 折射 / 分色 / 按压透镜 / 触摸光晕 / 中心通透提亮 |
 * | 31–32 | 模糊 → **饱和度增强** + `drawGlass` 的**绘制层**通透厚度 / 表面流光 / 镜面 rim |
 * | < 31 | 无模糊，`liquidGlassStyle` 已给不透明实色 |
 *
 * ⚠️ **2026-11-11 第六轮：绘制层与 shader 分工，别再往 AGSL 里塞"表面反射"**。
 * #277/#278 把折射、色散、光晕全放进 33+ 的 AGSL，结果 31-32 点按时玻璃毫无反应，
 * 而光晕画在层 A（模糊背景）上还会被层 B 的实色填充盖掉一层。现在：
 * `drawOpticalBody` / `drawSurfaceSweep` 画在层 B，**31+ 全档可见**；
 * AGSL 只负责"透出来的内容"该怎么被折射、推开、分色、照亮。两层一表一里，不是重复。
 *
 * ⚠️ **2026-11-10 第四轮补的饱和度不是装饰，是玻璃能不能"透出内容"的关键**：
 * 只做模糊时，背后彩色的条目会被糊成一片中性灰 —— 看起来像蒙了层雾而不是玻璃。
 * Haze 的 vibrancy / PrismalAGSL 的 saturation / LiquidGlassBar 的 1.5× 都做同一件事。
 * 用 `createColorFilterEffect` 串在模糊之后，好处是 **31-32 也拿得到**，且 AGSL 源码一行不改
 * （AGSL 是运行时编译、CI 覆盖不到，改它的风险与收益不对等）。
 *
 * ⚠️ shader 创建失败（AGSL 编译错误、软件渲染路径、ROM 缺陷）一律回落成**仅模糊**，
 * 绝不让主界面崩。这条与 `blurEffect` 的 `runCatching` 是同一条纪律。
 *
 * ⚠️ 本层**不含任何动画**：底栏选中态、转场仍走项目既有的静态/`translationX` 口径。
 */

/**
 * 进程级缓存放在 [OpticsShader] 内部：`RuntimeShader(...)` 会**编译** AGSL，而
 * `liquidGlassBackdrop` 每帧都跑一遍 ⇒ 每帧新建等于每帧编译一次，帧率会打穿。
 * 把 shader 与链对象一并收进那个只在 API 33+ 才被触碰的 object 还有第二个好处：
 * 低版本设备连 `RuntimeShader` 这个类都不会解析（Dalvik 惰性加载）。
 */

/**
 * 只在 API 33+ 被触碰的一组 AGSL 代码。
 *
 * shader 里 `uRect` 是**胶囊在 effectLayer 坐标系里的矩形**：effectLayer 录的是整块
 * Tab 内容区，比胶囊大得多，所以不能用图层尺寸算圆角，必须显式传入这块矩形。
 * 这与 `backdropOffset` 处理的是同一个坐标差问题。
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object OpticsShader {
    const val SOURCE = """
uniform shader content;
uniform float4 uRect;
uniform float uRadius;
uniform float uDepth;
uniform float uPart;
uniform float uLine;
uniform float uShine;
uniform float uClarity;
uniform float uLens;
// 触摸光晕（#278 引入，#280 修量纲）：xy = 光心（跟手指），z = 强度，
// w = 高斯【半径】像素。⚠️ #278 写 exp(-d²/w) 是拿半径当方差，实际光晕只有
// √w ≈12px，真机几乎看不见；本轮统一为 exp(-d²/w²)，半径与衰减口径一致。
uniform float4 uGlow;

float sdRoundBox(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + float2(r, r);
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

// #280：用 SDF 的数值梯度当折射法线，替代 #277 的 normalize(coord - center)。
// 后者在药丸长边中段带很大的水平分量 ⇒ 折射方向是斜的；梯度才是轮廓外法线。
float2 sdfNormal(float2 p, float2 b, float r) {
    float2 g = float2(
        sdRoundBox(p + float2(1.0, 0.0), b, r) - sdRoundBox(p - float2(1.0, 0.0), b, r),
        sdRoundBox(p + float2(0.0, 1.0), b, r) - sdRoundBox(p - float2(0.0, 1.0), b, r)
    );
    return normalize(g + float2(0.0001, 0.0001));
}

half4 main(float2 coord) {
    float2 center = (uRect.xy + uRect.zw) * 0.5;
    float2 halfSize = (uRect.zw - uRect.xy) * 0.5;
    float2 p = coord - center;
    float d = sdRoundBox(p, halfSize, uRadius);
    if (d >= 0.0) return content.eval(coord);
    float inset = -d;
    float band = 1.0 - smoothstep(0.0, uDepth * 3.0, inset);
    // 中心区 band=0 ⇒ shift/shine 都是 0，法线无关紧要；只在边缘带算梯度，
    // 免得给绝大部分像素平白加 4 次带 sqrt 的 SDF 求值。
    float2 normal = float2(0.0, 1.0);
    if (band > 0.001) {
        normal = sdfNormal(p, halfSize, uRadius);
    }

    float2 toTouch = coord - uGlow.xy;
    float falloff = exp(-dot(toTouch, toTouch) / max(uGlow.w * uGlow.w, 1.0));
    // ① 按压局部透镜：手指下的内容被径向推开，玻璃像被"按下去"。
    float2 lensDir = normalize(toTouch + float2(0.001, 0.001));
    float2 lens = lensDir * (uGlow.z * uLens * falloff * uDepth);
    float2 shift = normal * uDepth * band + lens;
    float2 lo = uRect.xy;
    float2 hi = uRect.zw;
    half4 base = content.eval(clamp(coord + shift, lo, hi));

    // ② 色散：沿法线镜像分开红蓝，按压时随透镜一起增强。
    float2 part = normal * uPart * band * (1.0 + uGlow.z * uLens);
    base.r = content.eval(clamp(coord + shift + part, lo, hi)).r;
    base.b = content.eval(clamp(coord + shift - part, lo, hi)).b;

    // ③ 边缘暗线（玻璃厚度）
    float darken = 1.0 - uLine * (1.0 - smoothstep(0.0, 1.5, inset));
    base.r = base.r * darken;
    base.g = base.g * darken;
    base.b = base.b * darken;

    // ④ 中心通透提亮：只在离边缘较远的中心区，抵消模糊的灰雾。
    //    与 31-32 的 drawOpticalBody 同职责 —— 一个照亮"透出的内容"，一个照亮"玻璃表面"。
    float clarity = uClarity * (1.0 - band);
    base.r = base.r + (1.0 - base.r) * clarity;
    base.g = base.g + (1.0 - base.g) * clarity;
    base.b = base.b + (1.0 - base.b) * clarity;

    // ⑤ 沿法线的边缘镜面
    float shine = uShine * abs(normal.y) * band;
    base.r = base.r + (1.0 - base.r) * shine;
    base.g = base.g + (1.0 - base.g) * shine;
    base.b = base.b + (1.0 - base.b) * shine;

    // ⑥ 触摸光晕：径向高斯往白里滤（screen），复用 ① 的 falloff。
    //    FlClash 写 `glass += strength * exp(-d²/reach)`；这里用 screen，
    //    因为底栏已有填充与 rim，直接加法会把暗线洗白。
    float glow = uGlow.z * falloff;
    base.r = base.r + (1.0 - base.r) * glow;
    base.g = base.g + (1.0 - base.g) * glow;
    base.b = base.b + (1.0 - base.b) * glow;
    return base;
}
"""

    const val LINE = 0.18f
    const val SHINE = 0.10f

    private var shader: RuntimeShader? = null
    private var chain: ComposeRenderEffect? = null

    /** 每帧调用：只更新 uniform，shader 与 RenderEffect 链都复用首次创建的对象。 */
    fun effect(
        blur: RenderEffect,
        rect: Rect,
        radius: Float,
        depth: Float,
        part: Float,
        glowX: Float,
        glowY: Float,
        glowStrength: Float,
        glowReach: Float,
    ): ComposeRenderEffect {
        val current = shader ?: RuntimeShader(SOURCE).also { shader = it }
        current.setFloatUniform("uRect", rect.left, rect.top, rect.right, rect.bottom)
        current.setFloatUniform("uRadius", radius)
        current.setFloatUniform("uDepth", depth)
        current.setFloatUniform("uPart", part)
        current.setFloatUniform("uLine", LINE)
        current.setFloatUniform("uShine", SHINE)
        // #280：中心通透提亮与按压透镜强度都取自 LiquidGlass 的唯一参数表，
        // 不在 shader 里写死字面量 —— 否则数值单测与文本门禁都够不着。
        current.setFloatUniform("uClarity", LiquidGlass.SHADER_CLARITY)
        current.setFloatUniform("uLens", LiquidGlass.LENS_STRENGTH)
        current.setFloatUniform("uGlow", glowX, glowY, glowStrength, glowReach)
        return chain ?: RenderEffect.createChainEffect(
            RenderEffect.createRuntimeShaderEffect(current, "content"),
            blur,
        ).asComposeRenderEffect().also { chain = it }
    }
}

/** 原生模糊效果。API < 31 或创建失败返回 null（调用方按「实色底栏」降级）。 */
internal fun androidBlurEffect(radiusPx: Float): RenderEffect? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    return runCatching {
        RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP)
    }.getOrNull()
}

/**
 * 模糊 → 饱和度增强（vibrancy）。
 *
 * `ColorMatrix.setSaturation(>1)` 会把颜色往"离灰度更远"推：模糊把彩色条目洗成灰，
 * 这一层把洗掉的那点色相还回来，玻璃才读作"透出内容"而不是"蒙了雾"。
 *
 * ⚠️ 失败时**原样返回模糊**：饱和度是增益不是前提，链创建不了不能让底栏不糊。
 * ⚠️ 用 `createChainEffect(saturation, blur)` —— outer 先算还是后算，Compose/RenderEffect
 *    的口径是 **outer 作用于 inner 的结果**，所以饱和度必须放第一个参数。
 */
private fun saturatedBlur(blur: RenderEffect): RenderEffect = runCatching {
    val matrix = ColorMatrix().apply { setSaturation(LiquidGlass.SATURATION) }
    RenderEffect.createChainEffect(
        RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(matrix)),
        blur,
    )
}.getOrNull() ?: blur

/**
 * 一次失败后钉住降级。
 *
 * AGSL 编译失败通常是设备 / ROM 级别的，重试不会变好；若每帧都走一遍
 * `RuntimeShader(...)` → 抛异常 → `runCatching` 吞掉，这个「兜底」本身就成了每帧开销。
 */
private var opticsDisabled = false

/**
 * 供 `liquidGlassBackdrop` 每帧取用的最终效果。
 *
 * @param boundsInLayer 胶囊在 effectLayer 坐标系（= 源坐标系）里的矩形，像素。
 * @param cornerRadiusPx 药丸圆角，通常取胶囊高度的一半。
 * @param depthPx 边缘折射位移深度，像素。
 * @param partPx 边缘 RGB 分色幅度，像素。
 * @param glowCx / @param glowCy 触摸光晕中心（effectLayer 坐标系，像素）。
 * @param glowStrength 光晕强度（0 = 无按压）。
 * @param glowReach 光晕高斯**半径**，像素。shader 内按 `exp(-d²/w²)` 使用，
 *                  不是方差 —— #278 曾把它当方差除，实际半径缩成 `√w`（≈12px）。
 */
internal fun glassRenderEffect(
    blurRadiusPx: Float,
    boundsInLayer: Rect,
    cornerRadiusPx: Float,
    depthPx: Float,
    partPx: Float,
    glowCx: Float,
    glowCy: Float,
    glowStrength: Float,
    glowReach: Float,
): ComposeRenderEffect? {
    val blur = androidBlurEffect(blurRadiusPx) ?: return null
    // ★ 31+ 一律先补饱和度，再决定要不要叠 AGSL。31-32 因此也有 vibrancy。
    val saturated = saturatedBlur(blur)
    val plain = saturated.asComposeRenderEffect()
    // ⚠️ 这行必须写成 `Build.VERSION.SDK_INT` 的裸比较：lint 靠它识别 NewApi 守卫，
    //    包进自定义函数就推断不出来（与 blurEffect 里 API 31 的写法同一条纪律）。
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return plain
    if (opticsDisabled) return plain
    return runCatching {
        OpticsShader.effect(
            blur = saturated,
            rect = boundsInLayer,
            radius = cornerRadiusPx,
            depth = depthPx,
            part = partPx,
            glowX = glowCx,
            glowY = glowCy,
            glowStrength = glowStrength,
            glowReach = glowReach,
        )
    }.onFailure { opticsDisabled = true }.getOrNull() ?: plain
}
