/*
 * Vaultix — app:ui:glass
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * 液态玻璃的**背景源**（2026-10-27 第二轮：用户真机反馈后重做）。
 *
 * ## 它修的是什么
 *
 * 第一版把 `graphicsLayer { renderEffect = 模糊 }` 挂在**包住底栏图标的那个 Box** 上。
 * `RenderEffect` 模糊的是**该图层的输出，包含它全部子内容** ⇒ 图标、文字、中央「+」
 * 被一起糊成灰雾 —— 用户截图里"按钮都看不清"就是这个。
 *
 * ## 正确的架构：源 → 消费 两段分离（对齐 chrisbanes/Haze）
 *
 * Haze 是本领域的标准做法，核心是**两个修饰符 + 一个共享 state**：
 *
 * ```kotlin
 * val hazeState = rememberHazeState()
 * LazyColumn(Modifier.hazeSource(hazeState)) { … }   // ① 登记"可被模糊的内容"
 * TopAppBar(Modifier.hazeEffect(hazeState)) { … }    // ② 浮层采样并模糊它，自己不受影响
 * ```
 *
 * 本文件是 ① 的自实现（项目不引新依赖）—— 思路与 Haze 完全一致：
 *
 * - [LiquidGlassSource] 持有一个 `GraphicsLayer`（一块离屏画布）；
 * - [Modifier.liquidGlassSource] 把**自己的内容录进这块 layer**，屏幕上什么也不画；
 * - 浮层（[Modifier.liquidGlassBackdrop]）再按两层的坐标差把这块 layer
 *   **模糊着画出来**，浮层自己的图标文字在**另一层**，一点都没被碰到。
 *
 * ## 🔴 四个"不报错、只是不糊"的约束
 *
 * 1. **源的子树里不能有不透明的铺底**（`Surface(color = …)` / `Modifier.background`）。
 *    录到的是"这棵子树自己画了什么"，不透明铺底会把底下的一切盖掉 ⇒ 录下一块纯色，
 *    模糊它等于没做（表现为"糊了，但糊的是一片灰"）。本项目的 Tab 页背景是**透明**的
 *    （`VaultixTheme` 的 `Surface` 铺在更外层；真机取证见 `ItemsScreen` 的 chip 行 KDoc），
 *    而**顶栏自己有不透明的 `Surface`** ⇒ 记录区必须从顶栏下沿开始，不能含顶栏。
 * 2. **源与浮层必须能被同一套根坐标描述**（两者都 `boundsInRoot()`）。
 * 3. **每帧重录**：`drawWithContent` 每帧跑一遍 `record` —— 列表滚动才会跟着糊，
 *    这也是本效果的成本所在。所以**只挂一个源、只服务一个浮层**（本 App 只有底栏）。
 * 4. **子树有尺寸才录得到**：`size` 为 0 时跳过 `record`（首帧 / 页面切走时会出现）。
 */
class LiquidGlassSource internal constructor(
    /** 录制目标。由 [rememberLiquidGlassSource] 在组合期创建（`rememberGraphicsLayer` 是
     *  `@Composable`，不能放在构造函数里）。 */
    internal val layer: GraphicsLayer,
) {

    /** 本子树在**根窗口**里的位置与尺寸（浮层据此决定采样哪一块）。 */
    internal var bounds: Rect = Rect.Zero
}

/**
 * 记住一个背景源。**一次组合只建一个**，交给 [Modifier.liquidGlassSource] 与浮层共用。
 *
 * ⚠️ 刻意**不收 `enabled`**：开关只是决定要不要挂源修饰符，与 layer 的创建无关。
 * 收进来当参数也没用（`rememberGraphicsLayer` 总得建），反而制造一个"看起来在控制什么"
 * 的假参数 —— 真正的开关在 [Modifier.liquidGlassSource] 与浮层两处的 `enabled`。
 */
@Composable
fun rememberLiquidGlassSource(): LiquidGlassSource {
    val layer = rememberGraphicsLayer()
    return remember(layer) { LiquidGlassSource(layer) }
}

/**
 * 把自己这棵子树登记为**可被浮层模糊的背景源**。
 *
 * 屏幕上不落任何像素（内容只进 layer）；真正显示它的是浮层的
 * [Modifier.liquidGlassBackdrop]。
 *
 * ⚠️ 必须加在本子树**最外层**那个 `Modifier` 上，否则录到的区域只有半个屏幕。
 */
fun Modifier.liquidGlassSource(source: LiquidGlassSource, enabled: Boolean): Modifier =
    if (!enabled) {
        this
    } else {
        onGloballyPositioned { source.bounds = it.boundsInRoot() }
            .drawWithContent {
                // ⚠️ 尺寸为 0 时不录（`record` 会分配一张 0×0 位图）。
                if (size.minDimension < 1f) {
                    drawContent()
                    return@drawWithContent
                }
                // ★ 关键：**内容只进 layer，不直接画到屏幕**。
                //   如果照常 `drawContent()`（= 先画一遍屏幕）再画一遍 layer，
                //   内容就会显示两遍（第二遍是未裁剪的录制结果）。
                //   layer 由浮层 [Modifier.liquidGlassBackdrop] 采样后画出。
                source.layer.record { this@drawWithContent.drawContent() }
            }
    }

/**
 * 把 [source] 录下的内容**模糊着**画到本修饰符的位置上 —— 液态玻璃浮层的背景。
 *
 * 与第一版的关键差别：模糊挂在**这个只画背景的修饰符**上，
 * 浮层的 `content()` 是同一 `Box` 的**另一个子节点**，不属于这层 ⇒ 不会被糊。
 *
 * @param offset 浮层左上角 − 源左上角（根坐标系）。底栏贴屏幕底、源自上而下铺满 ⇒ 通常为负。
 */
fun Modifier.liquidGlassBackdrop(
    source: LiquidGlassSource,
    offset: Offset,
    radiusPx: Float,
): Modifier = drawWithCache {
    val layer = source.layer
    val hasSize = size.width >= 1f && size.height >= 1f
    onDrawBehind {
        if (!hasSize) return@onDrawBehind
        drawIntoCanvas { canvas ->
            canvas.save()
            // 把 layer 挪到"源本来该在的位置"：layer 的坐标系原点是源的左上角，
            // 而本节点现在在浮层的位置 ⇒ 反向平移 offset。
            canvas.translate(-offset.x, -offset.y)
            // ⚠️ 模糊挂在 **layer** 上（不是本节点）：layer 里只有"别人"的内容，
            //    本节点后续再画填充/高光也不受影响 —— 这正是第一版搞反的那一步。
            layer.renderEffect = blurEffect(radiusPx)
            layer.alpha = 1f
            drawLayer(layer)
            canvas.restore()
        }
    }
}
