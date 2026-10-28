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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntSize

/**
 * 液态玻璃的**背景源**（2026-10-27 第二轮：用户真机反馈后重做；2026-10-28 第三轮修"整页变白"）。
 *
 * ## 它修的是什么
 *
 * 第一版把 `graphicsLayer { renderEffect = 模糊 }` 挂在**包住底栏图标的那个 Box** 上。
 * `RenderEffect` 模糊的是**该图层的输出，包含它全部子内容** ⇒ 图标、文字、中央「+」
 * 被一起糊成灰雾 —— 用户截图里"按钮都看不清"就是这个。
 *
 * ## 正确的架构：源 → 效果层 → 消费（对齐 chrisbanes/Haze）
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
 * - [LiquidGlassSource] 持有**两块** `GraphicsLayer`（离屏画布）：
 *   [contentLayer] 录源内容，[effectLayer] 是模糊的中转；
 * - [Modifier.liquidGlassSource] 把内容录进 [contentLayer]，**并照样画到屏幕上**；
 * - 浮层（[Modifier.liquidGlassBackdrop]）把 [contentLayer] 抄进 [effectLayer]、
 *   **只在 effectLayer 上挂模糊**，再按坐标差画出来。
 *
 * ## 🔴 2026-10-28（第三轮）：「源不落屏幕」是错的 —— 它就是"整页变白"的根因
 *
 * 第二轮写的是「内容只进 layer，**屏幕上不落任何像素**，真正显示它的是浮层」，
 * 并在下面自称这是**与 Haze 的"唯一有意分歧"**。这个分歧不成立：
 *
 * - 浮层只覆盖**底栏那一小块**，源录的是**整屏 Tab 内容**；
 * - 没有浮层覆盖的那 95% 区域，屏幕上就**什么都不画** ⇒ 露出窗口底色（浅色主题下 = 白）；
 * - 用户看到的就是"开启液态玻璃后整个页面变白"。
 *
 * Haze 源码里那两行的顺序恰恰是**先录、再画回屏幕**（本项目已照此改回）：
 *
 * ```kotlin
 * contentLayer.record { this@draw.drawContentSafely() }   // 内容进 layer
 * drawLayer(contentLayer)                                  // 再画回屏幕（清晰的那份）
 * ```
 *
 * ⇒ **本文件与 Haze 现在零分歧。** 看到上面那段"有意分歧"的老注释请以此为准。
 *
 * ### 由此派生的一条硬约束：模糊**不能**挂在 [contentLayer] 上
 *
 * 一旦内容要靠 `drawLayer(contentLayer)` 回屏，`contentLayer.renderEffect` 就会
 * **把屏幕上那份也一起糊掉**（同一块 layer 只有一个 renderEffect）。
 * ⇒ 模糊必须挂在中转层 [effectLayer] 上 —— 这也是 Haze `hazeEffect` 内部的做法。
 * **只挂一处、且只能挂 effectLayer**，改错一行就复现"整页糊"或"整页白"。
 *
 * ## 🔴 五个"不报错、只是不糊/糊错"的约束
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
 * 5. **浮层必须在源的子树之外**（本 App 里两者是 `AdaptiveMainScaffold` 的兄弟节点，
 *    `content` 先画、`bottomBar` 后画）。若浮层嵌在源里面，`drawContent()` 会在
 *    `contentLayer` **录制期间**执行 ⇒ 一边录一边读同一块 layer，行为未定义。
 */
class LiquidGlassSource internal constructor(
    /**
     * 录制目标 —— **永不挂任何效果**（它要原样画回屏幕）。
     *
     * 由 [rememberLiquidGlassSource] 在组合期创建（`rememberGraphicsLayer` 是
     * `@Composable`，不能放在构造函数里）。
     */
    internal val contentLayer: GraphicsLayer,
    /**
     * 模糊中转层 —— 只有它会被挂上 `renderEffect`。
     *
     * 每帧步骤：`record(源尺寸) { drawLayer(contentLayer) }` → 挂模糊 → 画到浮层位置。
     * 与 [contentLayer] 分开是"内容回屏"之后能保持清晰的前提（见文件头第三轮的说明）。
     */
    internal val effectLayer: GraphicsLayer,
) {

    /** 本子树在**根窗口**里的位置与尺寸（浮层据此决定采样哪一块）。 */
    internal var bounds: Rect = Rect.Zero

    /**
     * 源的像素尺寸 —— 效果层要按它开一块**同样大**的离屏画布。
     *
     * ⚠️ `record` 不传 size 时会用**当前 DrawScope 的尺寸**，而浮层只有胶囊那么高
     * ⇒ 不显式传就会把整屏内容裁成底栏那一小条（表现为"糊影只剩顶部一点"）。
     */
    internal var size: IntSize = IntSize.Zero
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
    val content = rememberGraphicsLayer()
    val effect = rememberGraphicsLayer()
    return remember(content, effect) { LiquidGlassSource(content, effect) }
}

/**
 * 把自己这棵子树登记为**可被浮层模糊的背景源**。
 *
 * 内容**照常显示在屏幕上**（清晰的那份），同时录一份进 [LiquidGlassSource.contentLayer]
 * 供浮层采样。
 *
 * ⚠️ 必须加在本子树**最外层**那个 `Modifier` 上，否则录到的区域只有半个屏幕。
 *
 * 🔴 **不要**再改成"只录不画"：那样整块源区域在屏幕上没有像素，只留下窗口底色
 * （= 用户报的"开启液态玻璃后整个页面变白"，2026-10-28 第三轮修复）。
 */
fun Modifier.liquidGlassSource(source: LiquidGlassSource, enabled: Boolean): Modifier =
    if (!enabled) {
        this
    } else {
        onGloballyPositioned {
            source.bounds = it.boundsInRoot()
            source.size = it.size
        }
            .drawWithContent {
                // ⚠️ 尺寸为 0 时不录（`record` 会分配一张 0×0 位图）。
                if (size.minDimension < 1f) {
                    drawContent()
                    return@drawWithContent
                }
                // ★ 关键之一：**录一份**。
                //   这里不能画到屏幕 —— `record` 的绘制目标是 layer 的画布。
                source.contentLayer.record { this@drawWithContent.drawContent() }
                // ★ 关键之二：**内容必须回屏**。
                //   画 layer（而不是再 `drawContent()` 一遍）：只多一次 GPU 合成，
                //   不重跑一遍子树的绘制命令。且 [contentLayer] 上永远没有 renderEffect
                //   ⇒ 屏幕上这份是清晰的。
                drawLayer(source.contentLayer)
            }
    }

/**
 * 浮层左上角 − 源左上角（根坐标系），即把源内容挪到浮层坐标系所需的**平移量**。
 *
 * 抽成纯函数是因为**符号极易写反**：底栏在源下方 ⇒ 源 top 更小 ⇒ 本值**为负**，
 * 而绘制时 `canvas.translate(offset.x, offset.y)` 用的是**同一个值**（不是它的相反数）。
 * 第二轮写成 `translate(-offset.x, -offset.y)` 就是在这里搞反了一次，
 * 表现为"底栏糊的是屏幕外的一块空白"。有单测钉住（见 `LiquidGlassSourceTest`）。
 *
 * @param sourceBounds 源在根坐标系里的位置（[LiquidGlassSource.bounds]）。
 * @param layerBounds 浮层在根坐标系里的位置。
 */
internal fun backdropOffset(sourceBounds: Rect, layerBounds: Rect): Offset =
    Offset(sourceBounds.left - layerBounds.left, sourceBounds.top - layerBounds.top)

/**
 * 把 [source] 录下的内容**模糊着**画到本修饰符的位置上 —— 液态玻璃浮层的背景。
 *
 * 与第一版的关键差别：模糊挂在**独立的中转层** [LiquidGlassSource.effectLayer] 上，
 * 既碰不到源内容（屏幕上的那份仍清晰），也碰不到浮层的 `content()`
 * （它是同一 `Box` 的**另一个子节点**）。
 *
 * @param offset 浮层左上角 − 源左上角（根坐标系），见 [backdropOffset]。
 *               底栏贴屏幕底、源自上而下铺满 ⇒ y 通常为负。
 * @param radiusPx 模糊半径（像素）。
 */
fun Modifier.liquidGlassBackdrop(
    source: LiquidGlassSource,
    offset: Offset,
    radiusPx: Float,
): Modifier = drawWithContent {
    // ⚠️ 用 drawWithContent（而不是 drawBehind / drawWithCache）：`GraphicsLayer.record`
    //    是 ContentDrawScope 的成员扩展，只有这里的 receiver 才调得到。
    val srcSize = source.size
    // ⚠️ IntSize 没有 `minDimension`（那是 Size 的），只能逐分量判 —— 写错一个字就编译不过。
    val blur = blurEffect(radiusPx)
    if (srcSize.width < 1 || srcSize.height < 1 || size.minDimension < 1f || blur == null) {
        // 🔴 提前返回时**必须**把子树画出来。
        //   `drawWithContent` 不调 `drawContent()` 的写法等于"条件一不满足就把这一层吞掉"：
        //   现在层 A 是空 Box、看不出后果，但这是**同一类"静默消失"**的写法
        //   （对照 #205 的底栏高度塌 0 —— 那次是没人告诉父 Box 尺寸，这次是没人画子树）。
        //   本文件 KDoc 反复讲的"不报错、只是不糊"里，最坏的一档就是"不画"。
        drawContent()
        return@drawWithContent
    }
    drawIntoCanvas { canvas ->
        canvas.save()
        // 把 layer 挪到"源本来该在的位置"：layer 的坐标系原点是源的左上角，
        // 而本节点现在在浮层的位置 ⇒ 平移 [offset]（**不是**它的相反数）。
        canvas.translate(offset.x, offset.y)
        // ① 源内容 → 中转层。尺寸必须是源的尺寸，否则整屏内容被裁成底栏那一小条。
        source.effectLayer.record(size = srcSize) {
            drawLayer(source.contentLayer)
        }
        // ② 模糊只挂在中转层上 —— contentLayer 保持干净（它还要原样回屏）。
        source.effectLayer.renderEffect = blur
        source.effectLayer.alpha = 1f
        drawLayer(source.effectLayer)
        canvas.restore()
    }
}
