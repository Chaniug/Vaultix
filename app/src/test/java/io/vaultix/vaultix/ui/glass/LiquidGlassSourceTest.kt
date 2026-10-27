/*
 * Vaultix — app（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.glass

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 液态玻璃**采样偏移**的单测（2026-10-28 第三轮补）。
 *
 * ## 为什么要为"一行减法"写单测
 *
 * [backdropOffset] 的**符号**是整个效果里最容易写反、且**写反了不报错**的一处：
 * 反了之后底栏糊的是屏幕外的一块空白（表现为"底栏发灰但不透内容"），
 * 而它旁边正好是"整页变白"那个更显眼的 bug —— 两个问题叠在一起时，
 * 靠肉眼根本分不出哪一个才是偏移写反。
 *
 * 第二轮的代码写的是 `canvas.translate(-offset.x, -offset.y)`（多取了一次反），
 * 就是在这里栽的。⇒ 把方向钉成常量，谁再改符号谁先改测试。
 *
 * ## 约定（与绘制端严格对应）
 *
 * `Modifier.liquidGlassBackdrop` 里做的是 `canvas.translate(offset.x, offset.y)`，
 * 即**直接用本函数的返回值**，不再取反。
 */
class LiquidGlassSourceTest {

    /** 底栏在源下方（真实布局）⇒ 需要把源内容**往上**挪 ⇒ y 为负。 */
    @Test
    fun `底栏在源下方时偏移为负`() {
        val source = Rect(0f, 0f, 1080f, 1800f)
        val dock = Rect(40f, 1600f, 1040f, 1700f)

        val offset = backdropOffset(sourceBounds = source, layerBounds = dock)

        assertThat(offset.x).isEqualTo(-40f)
        assertThat(offset.y).isEqualTo(-1600f)
    }

    /** 语义锚点：偏移恒等于「源左上角 − 浮层左上角」，两个分量各自独立。 */
    @Test
    fun `偏移等于两个左上角之差`() {
        val cases = listOf(
            Rect(0f, 0f, 100f, 100f) to Rect(0f, 200f, 100f, 300f),
            Rect(15f, 120f, 900f, 1700f) to Rect(0f, 0f, 900f, 80f),
            Rect(-5f, -5f, 50f, 50f) to Rect(100f, 100f, 200f, 200f),
        )
        cases.forEach { (source, layer) ->
            val offset = backdropOffset(sourceBounds = source, layerBounds = layer)
            assertThat(offset.x).isEqualTo(source.left - layer.left)
            assertThat(offset.y).isEqualTo(source.top - layer.top)
        }
    }

    /** 两者重合 ⇒ 零偏移（套一层 layer 也不能引入位移）。 */
    @Test
    fun `源与浮层重合时零偏移`() {
        val box = Rect(10f, 20f, 300f, 400f)
        assertThat(backdropOffset(sourceBounds = box, layerBounds = box)).isEqualTo(Offset.Zero)
    }

    /** 浮层在源**上方**（宽屏 rail 之类反过来的场景）⇒ 符号跟着翻正，不是恒负。 */
    @Test
    fun `浮层在源上方时偏移为正`() {
        val source = Rect(0f, 300f, 800f, 1200f)
        val layer = Rect(0f, 0f, 800f, 100f)

        val offset = backdropOffset(sourceBounds = source, layerBounds = layer)

        assertThat(offset.x).isEqualTo(0f)
        assertThat(offset.y).isEqualTo(300f)
    }
}
