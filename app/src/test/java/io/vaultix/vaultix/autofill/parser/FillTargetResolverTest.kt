/*
 * Vaultix — app:autofill · parser（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.autofill.parser

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 容器重定向（[resolveFillTarget]）与逐字段站点继承（[inheritWebDomain]）。
 *
 * 回归锁：**语义挂在不可填容器上时，必须下钻到第一个可填后代**。
 * 对齐 Bitwarden `ViewNodeExtensions.findFirstAutofillableChild` —— 上游原文：
 * ```
 * the container's type=0 (NONE) would cause buildFilledItemOrNull to return null
 * and drop the field from the fill dataset entirely
 * ```
 * 即：目标不可填时框架丢的是**整条 dataset**，不只是这一个字段。表现就是用户报告的
 * 「Edge 里偶尔不弹出密码条目」—— 是否命中取决于站点的 DOM 嵌套层级。
 *
 * 本文件只测纯函数（[FillTargetNode]）；真实 `ViewNode` 是 `@SystemApi` 抽象类，
 * 构造不出实例，投影桥（`AssistStructureParser.resolveFillTarget`）靠真机验证。
 */
class FillTargetResolverTest {

    // ── 判据：autofillType，不是「是不是输入控件」 ──

    @Test
    fun `only AUTOFILL_TYPE_TEXT can receive a value`() {
        assertThat(FillTargetResolver.canReceiveValue(FillTargetResolver.AUTOFILL_TYPE_TEXT)).isTrue()
        assertThat(FillTargetResolver.canReceiveValue(FillTargetResolver.AUTOFILL_TYPE_NONE)).isFalse()
    }

    @Test
    fun `framework constants mirror the platform values`() {
        // android.jar 是 stub jar，JVM 单测里直接引用 View.AUTOFILL_TYPE_* 会拿到 0
        // （字段不带常量值），与 NONE 撞车 → 全部断言假通过。这里用显式复制值并锁住它。
        // 真值出处：Android 平台 View.java —— AUTOFILL_TYPE_NONE = 0、AUTOFILL_TYPE_TEXT = 1。
        assertThat(FillTargetResolver.AUTOFILL_TYPE_NONE).isEqualTo(0)
        assertThat(FillTargetResolver.AUTOFILL_TYPE_TEXT).isEqualTo(1)
        // 二者必须可区分：相等就意味着「容器」和「输入框」在重定向里没区别。
        assertThat(FillTargetResolver.AUTOFILL_TYPE_NONE).isNotEqualTo(FillTargetResolver.AUTOFILL_TYPE_TEXT)
    }

    @Test
    fun `a text node receives itself without drilling down`() {
        val child = leaf(canReceive = true, identity = "child")
        val self = container(child, identity = "self", canReceive = true)

        // 容器自己也能填时不下钻 —— 语义落点应当就是它自己。
        assertThat(FillTargetResolver.resolveFillTarget(self)?.identity).isEqualTo("self")
    }

    // ── 下钻 ──

    @Test
    fun `a non-fillable container redirects to the first fillable descendant`() {
        // 真实形态：`<div autocomplete="username">` 包着 `<input>`，语义挂在外层容器上。
        val input = leaf(canReceive = true, identity = "input")
        val form = container(input, identity = "form")

        assertThat(FillTargetResolver.resolveFillTarget(form)?.identity).isEqualTo("input")
    }

    @Test
    fun `redirect drills through multiple container levels`() {
        // 浏览器 DOM 嵌套很深：form > div > div > input
        val input = leaf(canReceive = true, identity = "input")
        val inner = container(input, identity = "inner-div")
        val middle = container(inner, identity = "middle-div")
        val form = container(middle, identity = "form")

        assertThat(FillTargetResolver.resolveFillTarget(form)?.identity).isEqualTo("input")
    }

    @Test
    fun `redirect picks the first fillable descendant in DFS order`() {
        // 顺序即视觉自上而下：取第一个，不是最后一个、也不是最深的。
        val first = leaf(canReceive = true, identity = "first")
        val second = leaf(canReceive = true, identity = "second")
        val form = container(container(first), container(second), identity = "form")

        assertThat(FillTargetResolver.resolveFillTarget(form)?.identity).isEqualTo("first")
    }

    @Test
    fun `redirect prefers the shallower node when both parent and child are fillable`() {
        // 前序 DFS：先看自己再看孩子。父子都可填时语义只落一次，落在父上。
        val inner = leaf(canReceive = true, identity = "inner")
        val outer = container(inner, identity = "outer", canReceive = true)

        assertThat(FillTargetResolver.resolveFillTarget(outer)?.identity).isEqualTo("outer")
    }

    @Test
    fun `redirect skips non-fillable subtrees and finds the real input elsewhere`() {
        // 兄弟子树里全是展示节点（label / div），真正可填的在后一个兄弟里。
        val labelBranch = container(leaf(canReceive = false), leaf(canReceive = false))
        val input = leaf(canReceive = true, identity = "input")
        val form = container(labelBranch, input, identity = "form")

        assertThat(FillTargetResolver.resolveFillTarget(form)?.identity).isEqualTo("input")
    }

    // ── 边界：无可填节点时必须整体丢弃 ──

    @Test
    fun `a fully non-fillable subtree resolves to null`() {
        // 整棵子树都不可填 → 调用方**丢弃该字段**，而不是保留一个填不进去的目标。
        val form = container(container(leaf(canReceive = false)), leaf(canReceive = false))

        assertThat(FillTargetResolver.resolveFillTarget(form)).isNull()
    }

    @Test
    fun `a lone non-fillable node resolves to null`() {
        assertThat(FillTargetResolver.resolveFillTarget(leaf(canReceive = false))).isNull()
    }

    @Test
    fun `an empty container resolves to null`() {
        assertThat(FillTargetResolver.resolveFillTarget(container())).isNull()
    }

    @Test
    fun `redirect is stable across identically shaped subtrees`() {
        // 两棵结构完全相同的子树：投影树是 data class，若用 equals 做身份会互相覆盖。
        // 调用方靠「子索引路径」区分，这里锁住「左边的容器命中左边的 input」。
        val left = container(leaf(canReceive = true, identity = "left-input"), identity = "left")
        val right = container(leaf(canReceive = true, identity = "right-input"), identity = "right")

        assertThat(FillTargetResolver.resolveFillTarget(left)?.identity).isEqualTo("left-input")
        assertThat(FillTargetResolver.resolveFillTarget(right)?.identity).isEqualTo("right-input")
    }

    // ── 逐字段站点继承 ──

    @Test
    fun `field without its own domain inherits the parent domain`() {
        // 跨域 iframe 的根节点带 webDomain，iframe 内部的输入框自身是 null → 继承。
        assertThat(FillTargetResolver.inheritWebDomain(null, "login.example.com")).isEqualTo("login.example.com")
    }

    @Test
    fun `field with its own domain keeps it over the parent`() {
        // iframe 根节点自身带域名 → 用它自己那份，不沿用主文档域名。
        assertThat(FillTargetResolver.inheritWebDomain("evil.example.net", "login.example.com"))
            .isEqualTo("evil.example.net")
    }

    @Test
    fun `blank domain is treated as absent and inherits`() {
        // 框架偶尔回空串而不是 null，等价于「本节点没有域名」。
        assertThat(FillTargetResolver.inheritWebDomain("", "login.example.com")).isEqualTo("login.example.com")
        assertThat(FillTargetResolver.inheritWebDomain("   ", "login.example.com")).isEqualTo("login.example.com")
    }

    @Test
    fun `no domain anywhere stays null for native app fields`() {
        // 原生 App 字段没有 webDomain，也不该被硬塞一个。
        assertThat(FillTargetResolver.inheritWebDomain(null, null)).isNull()
    }

    // ── 构造小工具 ──

    /** 叶子节点。[canReceive] 即 `autofillType == TEXT`；[identity] 仅用于辨认命中了哪个节点。 */
    private fun leaf(canReceive: Boolean, identity: Any? = null) =
        FillTargetNode(canReceiveValue = canReceive, identity = identity)

    /**
     * 容器节点（默认 `autofillType == NONE` —— 这正是语义挂载点的典型形态）。
     *
     * [canReceive] 只在「父子都可填」那条用例里显式传 true。
     */
    private fun container(
        vararg children: FillTargetNode,
        identity: Any? = null,
        canReceive: Boolean = false,
    ) = FillTargetNode(
        canReceiveValue = canReceive,
        children = children.toList(),
        identity = identity,
    )
}
