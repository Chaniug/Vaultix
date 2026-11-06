/*
 * Vaultix — app:ui:glass
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.glass

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
 * | ≥ 33 | 模糊 → AGSL 折射 / 分色 / rim 暗线 / 高光 |
 * | 31–32 | 仅模糊（原行为，观感由 `drawGlass` 的静态光学装饰补足） |
 * | < 31 | 无模糊，`liquidGlassStyle` 已给不透明实色 |
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

float sdRoundBox(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + float2(r, r);
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

half4 main(float2 coord) {
    float2 center = (uRect.xy + uRect.zw) * 0.5;
    float2 halfSize = (uRect.zw - uRect.xy) * 0.5;
    float d = sdRoundBox(coord - center, halfSize, uRadius);
    if (d >= 0.0) return content.eval(coord);
    float inset = -d;
    float band = 1.0 - smoothstep(0.0, uDepth * 3.0, inset);
    float2 normal = normalize(coord - center + float2(0.001, 0.001));
    float2 shift = normal * uDepth * band;
    float2 lo = uRect.xy;
    float2 hi = uRect.zw;
    half4 base = content.eval(clamp(coord + shift, lo, hi));
    float2 part = normal * uPart * band;
    base.r = content.eval(clamp(coord + shift + part, lo, hi)).r;
    base.b = content.eval(clamp(coord + shift - part, lo, hi)).b;
    float darken = 1.0 - uLine * (1.0 - smoothstep(0.0, 1.5, inset));
    base.r = base.r * darken;
    base.g = base.g * darken;
    base.b = base.b * darken;
    float shine = uShine * abs(normal.y) * band;
    base.r = base.r + (1.0 - base.r) * shine;
    base.g = base.g + (1.0 - base.g) * shine;
    base.b = base.b + (1.0 - base.b) * shine;
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
        rect: FloatArray,
        radius: Float,
        depth: Float,
        part: Float,
    ): ComposeRenderEffect {
        val current = shader ?: RuntimeShader(SOURCE).also { shader = it }
        current.setFloatUniform("uRect", rect[0], rect[1], rect[2], rect[3])
        current.setFloatUniform("uRadius", radius)
        current.setFloatUniform("uDepth", depth)
        current.setFloatUniform("uPart", part)
        current.setFloatUniform("uLine", LINE)
        current.setFloatUniform("uShine", SHINE)
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
 */
internal fun glassRenderEffect(
    blurRadiusPx: Float,
    boundsInLayer: Rect,
    cornerRadiusPx: Float,
    depthPx: Float,
    partPx: Float,
): ComposeRenderEffect? {
    val blur = androidBlurEffect(blurRadiusPx) ?: return null
    val plain = blur.asComposeRenderEffect()
    // ⚠️ 这行必须写成 `Build.VERSION.SDK_INT` 的裸比较：lint 靠它识别 NewApi 守卫，
    //    包进自定义函数就推断不出来（与 blurEffect 里 API 31 的写法同一条纪律）。
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return plain
    if (opticsDisabled) return plain
    return runCatching {
        OpticsShader.effect(
            blur = blur,
            rect = floatArrayOf(
                boundsInLayer.left,
                boundsInLayer.top,
                boundsInLayer.right,
                boundsInLayer.bottom,
            ),
            radius = cornerRadiusPx,
            depth = depthPx,
            part = partPx,
        )
    }.onFailure { opticsDisabled = true }.getOrNull() ?: plain
}
