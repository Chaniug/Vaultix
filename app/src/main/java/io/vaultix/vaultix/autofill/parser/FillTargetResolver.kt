/*
 * Vaultix — app:autofill · parser
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * 「容器重定向」纯函数骨架，对齐 Bitwarden `ViewNodeExtensions.findFirstAutofillableChild`
 * （GPL-3.0，© Bitwarden Inc.）与 `ViewStructureUtils` 的 `autofillType` 判据。
 *
 * ⚠️ 为什么主体写成 object 而不是一组散落的顶层函数：detekt 的 `MatchingDeclarationName`
 * 要求「文件里若只有一个顶层类声明，文件名必须与之同名」。本文件唯一的顶层**类**是
 * [FillTargetNode]，而语义重心在重定向算法上 —— 把算法收进 `object FillTargetResolver`
 * 既让文件名有了对应的同名声明，也给算法带了命名空间（调用处 `FillTargetResolver.xxx`
 * 比对着一串裸函数更好读）。
 */
package io.vaultix.vaultix.autofill.parser

/**
 * 一棵 AssistStructure 子树在**重定向视角**下的最小抽象。
 *
 * 只保留判定所必需的信息：本节点能否被框架回填、子节点是谁、以及回指真实节点的身份。
 * 把 `ViewNode` 剥到这几个字段后，重定向算法就成了纯函数，可以直接喂给 JVM 单测 ——
 * 而 `ViewNode` 是 `@SystemApi` 的抽象类，构造不出实例，真正的解析器
 * （[AssistStructureParser]）在 JVM 上无从断言。
 *
 * @param canReceiveValue 本节点能否被框架回填（`AUTOFILL_TYPE_TEXT` 且有 `autofillId`）。
 * @param children 直接子节点，**顺序即 DFS 顺序**（决定下钻时取哪一个后代）。
 * @param identity 回指调用方真实节点用的不透明标识（如从根出发的子索引路径）。
 *   本模块不解释它，只负责原样带回。
 */
internal data class FillTargetNode(
    val canReceiveValue: Boolean,
    val children: List<FillTargetNode> = emptyList(),
    val identity: Any? = null,
)

/** 容器重定向与逐字段站点继承的策略本体（全部为纯函数，便于 JVM 单测）。 */
internal object FillTargetResolver {

    // ── 与框架常量同值 ──
    //
    // 直接引用 `View.AUTOFILL_TYPE_TEXT` 在 JVM 单测里会拿到 **0**（`android.jar` 是 stub jar，
    // 字段不带常量值），与 `AUTOFILL_TYPE_NONE` 撞车 → 重定向测试恒为「全部可填」而假通过。
    // 真值出处：Android 平台 `View.java`（`AUTOFILL_TYPE_NONE = 0`、`AUTOFILL_TYPE_TEXT = 1`），
    // 并由 `FillTargetResolverTest` 断言锁住，防止日后被误改。
    internal const val AUTOFILL_TYPE_NONE = 0
    internal const val AUTOFILL_TYPE_TEXT = 1

    /**
     * 解析本次填充**真正该写入**的节点：自己可填就用自己，否则深度优先下钻到第一个可填后代。
     *
     * ## 为什么必须重定向（真机症状与上游结论）
     * 浏览器 / WebView 常把语义挂在外层容器上（`<form autocomplete="username">`、包住
     * `<input>` 的 `<div>`），而容器自身 `autofillType == AUTOFILL_TYPE_NONE`。若把 hint
     * 落在容器上，框架回填时会发现目标不可填并**丢弃整条 dataset**（Bitwarden 上游原文：
     * `the container's type=0 (NONE) would cause buildFilledItemOrNull to return null and
     * drop the field from the fill dataset entirely`）。表现正是用户看到的
     * 「**偶尔**点填充没反应 / 偶尔不出现密码条目」—— 命中与否取决于站点的 DOM 嵌套方式，
     * 同一站点两次捕获的层级也未必一致。
     *
     * ## 下钻顺序
     * 与上游一致：前序深度优先，子树内取**第一个**可填节点。顺序即 DFS 顺序，
     * 对表单而言 ≈ 视觉自上而下，因此天然命中容器里的第一个 `<input>`。
     *
     * ## 边界
     * 整棵子树都不可填时返回 null，调用方应**丢弃该字段**（不是保留为 null 目标）——
     * 保留会凭空造出一个「解析到了但填不进去」的字段，污染候选计数与 `SaveInfo`。
     *
     * @return 该写入的节点；无任何可填节点时返回 null。
     */
    internal fun resolveFillTarget(node: FillTargetNode): FillTargetNode? {
        // 自己可填就不下钻：容器与内部 `<input>` 都带同一语义时，语义只该落一次。
        if (node.canReceiveValue) return node
        node.children.forEach { child ->
            resolveFillTarget(child)?.let { return it }
        }
        return null
    }

    /**
     * 该节点是否**自己就能收值**：`autofillType` 为 `AUTOFILL_TYPE_TEXT`。
     *
     * ⚠️ 判据是 `autofillType`，**不是**「是不是输入控件」（[isEditableNode]：
     * `htmlInfo.tag == "input"` 或 className 是 EditText 家族）—— 后者是错的：
     * 一个 `<input>` 完全可能 `autofillType == NONE`（`type=hidden`、`disabled`、`readonly`），
     * 而一个 EditText 也可能因 `importantForAutofill=no` 而不可填。上游 Bitwarden 用的就是
     * `viewNode.autofillType == View.AUTOFILL_TYPE_TEXT`，不是控件类型。
     *
     * 调用方还需自行确认节点带 `autofillId`（框架可寻址）—— 本函数只看类型。
     */
    internal fun canReceiveValue(autofillType: Int): Boolean = autofillType == AUTOFILL_TYPE_TEXT

    /**
     * 逐字段站点（对齐 Bitwarden `ViewNodeExtensions.toAutofillView`：
     * `website = this.website ?: parentWebsite`）。
     *
     * 框架只在**跨域 iframe 的根节点**上带 `webDomain`，iframe 内部的每个输入框自身都是
     * null。若只用页面级的单一域名（旧实现是 `webDomains.firstOrNull()`），跨域 iframe
     * 里的登录框会被当成「主文档的框」，填充阶段拿主文档域名去做站点校验，结果要么误填、
     * 要么校验失败静默不填。自顶向下继承后，每个字段都带上**自己真正所属**的域名。
     *
     * @return 本节点或任一祖先的域名；都没有则 null（原生 App 字段）。
     */
    internal fun inheritWebDomain(nodeWebDomain: String?, parentWebDomain: String?): String? =
        nodeWebDomain?.takeIf { it.isNotBlank() } ?: parentWebDomain
}
