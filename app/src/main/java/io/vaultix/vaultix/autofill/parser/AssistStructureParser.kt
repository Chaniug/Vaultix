/*
 * Vaultix — app:autofill · parser
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * 解析算法移植并改写自 Bastion autofill_ng 的 EnhancedAutofillStructureParserV2
 * （GPL-3.0，Copyright 2025 JoyinJoester）：遍历 AssistStructure 的 ViewNode 树，
 * 逐节点经 [HintClassifier] 归一语义。本类只做薄封装，重活（单节点分类）在纯函数里。
 */
package io.vaultix.vaultix.autofill.parser

import android.app.assist.AssistStructure
import android.app.assist.AssistStructure.ViewNode
import android.view.View
import io.vaultix.vaultix.autofill.fillassist.FillAssistMatcher
import io.vaultix.vaultix.autofill.fillassist.FillAssistRules
import io.vaultix.vaultix.autofill.fillassist.HostRule
import io.vaultix.vaultix.autofill.model.FieldHint
import io.vaultix.vaultix.autofill.model.ParsedField
import io.vaultix.vaultix.autofill.model.ParsedStructure

/**
 * 从**重定向根**出发的子索引路径，用作 [ViewNode] 在投影树里的身份。
 *
 * 为什么用路径而不是对象引用：投影出的 [FillTargetNode] 是 data class，
 * 两棵结构相同的子树会 `equals` 相等，拿它当 map 的键会互相覆盖。
 */
private typealias NodePath = List<Int>

/** 把 [AssistStructure] 拆成 [ParsedStructure]（字段语义 + 登录框 id + WebView 标记）。 */
object AssistStructureParser {

    fun parse(
        structure: AssistStructure,
        fillAssistRules: FillAssistRules = FillAssistRules.EMPTY,
    ): ParsedStructure {
        val packageName = structure.activityComponent?.packageName
        // 填充辅助按**页面主机**取规则：先做一次轻量扫描拿到主机，再决定本次遍历用哪种分类。
        // 该主机没有规则（绝大多数站点）→ hostRules 为空 → 完全走原有启发式，行为不变。
        val hostRules = fillAssistRules.forHost(firstWebDomainOf(structure))

        val fields = mutableListOf<ParsedField>()
        val webDomains = mutableListOf<String>()
        val urlBarHosts = mutableListOf<String>()
        var webView = false
        repeat(structure.windowNodeCount) { index ->
            val root = structure.getWindowNodeAt(index)?.rootViewNode ?: return@repeat
            webView = traverse(root, fields, webDomains, urlBarHosts, packageName, hostRules) || webView
        }

        val webDomain = webDomains.firstOrNull()
        // Edge / 三星 / Opera 等浏览器的 WebView 不总会上报 webDomain：此时读地址栏
        // （包名 + idEntry 双重匹配），再退化到结构文本扫描。该兜底只用于匹配。
        val fallbackWebDomain = if (webDomain.isNullOrBlank()) {
            urlBarHosts.firstOrNull() ?: BrowserUrlBars.domainFromStructureText(structure)
        } else {
            null
        }
        val effectiveDomain = webDomain ?: fallbackWebDomain
        val webUri = effectiveDomain?.let { "https://$it" }
        val resolved = promoteUsernameField(fields)
        // 只认**可见**的账号 / 密码框：隐藏框（自动填充辅助框、隐藏的登录弹层）既不该被填，
        // 也不该凭它触发保存提示 —— 否则「只填了个搜索词」也会弹保存；`SaveInfo` 的
        // requiredIds 正是取自这两个 id。
        val usernameId = resolved.firstOrNull { it.hint == FieldHint.USERNAME && it.isVisible }?.id
        val passwordId = resolved.firstOrNull { it.hint == FieldHint.PASSWORD && it.isVisible }?.id
        return ParsedStructure(
            packageName = packageName,
            webScheme = null,
            webDomain = webDomain,
            fallbackWebDomain = fallbackWebDomain,
            webUri = webUri,
            webView = webView,
            usernameId = usernameId,
            passwordId = passwordId,
            fields = resolved,
        )
    }

    /**
     * 识别不到用户名字段时，把密码框**之前最近**的文本框升格为用户名。
     *
     * 对齐 Bitwarden `AutofillParserImpl`：浏览器 / WebView 表单里密码框几乎总有信号
     * （`type=password`），用户名字段却常常什么信号都没有——不补这一步就只能填密码、
     * 账号框空着，用户体感就是「填充没生效」。DFS 顺序 ≈ 视觉自上而下顺序。
     * 邮箱框优先（多数网站拿邮箱当账号），其次才是完全无信号的未知框。
     *
     * ⚠️ **两处必须的加强（2026-09-12，QQ 登录页实测）**：
     * 1. 判据是「没有**可见**的用户名框」，而不是「没有用户名字段」。带标签页的登录页
     *    （QQ 的「QQ号 / 手机号 / 邮箱」）会把非当前标签的输入框留在树里但置为**不可见**，
     *    它们可能已被文本启发式判成 USERNAME —— 只判「有没有」就会因这些**看不见**的框
     *    跳过升格，结果当前可见的账号框始终是 UNKNOWN：**既不出候选、也填不进去**。
     * 2. 升格时把 `strength` 提到 [HintClassifier.SignalStrength.MEDIUM]：紧挨密码框这一
     *    事实本身比纯文本启发式更可信（等同 Bitwarden 把 Unused 视图提升为
     *    `Login.Username`）；更重要的是 [AutofillFillTargetPolicy] 只认 ≥MEDIUM 的弱语义
     *    字段为目标——不改强度就会表现为「账号框点了不弹候选，只能填密码框」。
     */
    private fun promoteUsernameField(fields: List<ParsedField>): List<ParsedField> {
        if (fields.any { it.hint == FieldHint.USERNAME && it.isVisible }) return fields
        // 升格锚点必须是**可见**的密码框：页面里常带隐藏密码框（自动填充辅助 / 隐藏弹层），
        // 若以它为锚，会把它前面的搜索框误升格成账号框（进而乱弹密码条目）。
        val passwordIndex = fields.indexOfFirst { it.hint == FieldHint.PASSWORD && it.isVisible }
        if (passwordIndex <= 0) return fields
        val before = fields.subList(0, passwordIndex)
        val index = before.indexOfLast { it.isVisible && it.hint == FieldHint.EMAIL_ADDRESS }
            .takeIf { it >= 0 }
            ?: before.indexOfLast { it.isVisible && it.hint == FieldHint.UNKNOWN }
        if (index < 0) return fields
        return fields.toMutableList().apply {
            this[index] = this[index].copy(
                hint = FieldHint.USERNAME,
                strength = HintClassifier.SignalStrength.MEDIUM,
            )
        }
    }

    /** 深度优先遍历，收集字段 / WebView 域名 / 地址栏网址；返回子树内是否出现 WebView。 */
    private fun traverse(
        node: ViewNode,
        out: MutableList<ParsedField>,
        webDomains: MutableList<String>,
        urlBarHosts: MutableList<String>,
        pagePackageName: String?,
        hostRules: List<HostRule>,
        parentWebDomain: String? = null,
    ): Boolean {
        var webView = node.className?.contains("WebView", ignoreCase = true) == true
        node.webDomain?.let { webDomains += it }
        // ★ 逐字段站点（对齐 Bitwarden `ViewNodeExtensions.toAutofillView`：
        //   `website = this.website ?: parentWebsite`）。框架只在**跨域 iframe 的根节点**
        //   上带 webDomain，iframe 内部的每个输入框自身都是 null —— 若只用
        //   `parsed.webDomain`（全局首值），跨域 iframe 里的登录框会被当成「主文档的框」，
        //   填充阶段拿主文档域名做校验 → 校验失败 → 静默不填。
        //   自顶向下继承：本节点有值就用本节点的，没有就沿用父级。
        val webDomain = node.webDomain?.takeIf { it.isNotBlank() } ?: parentWebDomain
        // 地址栏只取网址，**不作为可填充字段**：否则地址栏文本含 "login" 会被启发式
        // 判成用户名字段，填充时把账号写进地址栏。
        val isUrlBar = BrowserUrlBars.isUrlBarNode(pagePackageName, node.idPackage, node.idEntry)
        if (isUrlBar) {
            BrowserUrlBars.hostFromText(node.text?.toString())?.let { urlBarHosts += it }
        }
        val id = node.autofillId
        if (id != null && !isUrlBar) {
            val hints = node.autofillHints?.map { it.toString() }
            val inputType = node.inputType
            // 语义信号来自**表单属性**（hint / idEntry / htmlInfo），刻意不含 `text`：
            // 浏览器把整棵 DOM 建成可填节点，`<label>Password</label>` 这类展示文字会让
            // 假字段挤掉真字段（详见 [isEditableNode]）。`text` 只作为字段值。
            val signal = BrowserUrlBars.formSignalOf(node)
            val classified = classifyNode(node, hints, inputType, signal, hostRules)
            // 规则覆盖本主机时 classified 为 null 表示「该节点不被规则承认」→ **不收进字段表**
            // （对齐上游：被规则覆盖的分区以规则为准，不做启发式兜底）。
            if (classified != null) {
                // ★ 容器重定向（对齐 Bitwarden `findFirstAutofillableChild`）：
                // 外层容器被赋予语义 hint（如带 `autocomplete=username` 的 `<form>`/`<div>`）
                // 但它自己 `autofillType == NONE`（收不到值）⇒ 必须下钻到第一个可填后代，
                // 否则框架回填时整条 dataset 会因「目标不可填」被丢弃
                // （上游原文：`buildFilledItemOrNull` returns null and **drops the field
                // from the fill dataset entirely**）。判据与算法见 [FillTargetResolver]。
                val target = resolveFillTarget(node)
                if (target != null) {
                    out += ParsedField(
                        id = target.id,
                        hint = classified.hint,
                        strength = classified.strength,
                        // 值取**下钻后**目标节点的文本（容器自身通常没有值）。
                        value = target.text?.toString(),
                        isFocused = target.isFocused,
                        isVisible = target.visibility == View.VISIBLE,
                        // 字段**自身**所属域名（含父级继承，见上方 webDomain 推导）：
                        // 页面里嵌了别的域名的 iframe 时，这些字段的 webDomain 与主页面不同
                        // → 填充阶段据此跳过（逐字段站点校验，对齐 Bitwarden
                        // fillLoginPartition 的 website 比对）。
                        webDomain = webDomain,
                    )
                }
            }
        }
        repeat(node.childCount) { index ->
            val child = node.getChildAt(index) ?: return@repeat
            webView = traverse(
                child, out, webDomains, urlBarHosts, pagePackageName, hostRules, webDomain,
            ) || webView
        }
        return webView
    }

    /**
     * 单个节点的语义分类。
     *
     * - 主机**有**规则（[hostRules] 非空）：以规则为准，命中即视为**强信号**
     *   （[HintClassifier.SignalStrength.HIGH]）——规则是人工核对过的选择器，
     *   比任何启发式都可信；未命中返回 null（调用方丢弃该节点）。
     * - 主机无规则：先过 [isEditableNode] 准入闸（对齐 Bitwarden `toAutofillView`：
     *   不是输入控件、又没有任何标准 hint 的节点**直接丢弃**），再走 [HintClassifier]。
     *
     * ⚠️ 准入闸是 2026-09-12 Edge 真机问题的根因修复：浏览器 WebView 会把整棵 DOM
     * 建成带 `autofillId` 的节点（实测一个页面 221 个），`<label>Password</label>` 这类
     * 展示节点被启发式判成 PASSWORD / USERNAME 后，会**占掉**真正的账号框语义位，
     * 使 `promoteUsernameField` 不再升格 → 账号框既不出候选也填不进去。
     */
    private fun classifyNode(
        node: ViewNode,
        hints: List<String>?,
        inputType: Int,
        signal: String?,
        hostRules: List<HostRule>,
    ): HintClassifier.Classified? {
        if (hostRules.isNotEmpty()) {
            return FillAssistMatcher.matchHint(node.htmlInfo, hostRules)
                ?.let { hint -> HintClassifier.Classified(hint, HintClassifier.SignalStrength.HIGH) }
        }
        if (!isEditableNode(node.htmlInfo?.tag, node.className, hints)) return null
        return HintClassifier.classify(hints, inputType, signal)
    }

    /**
     * 该节点是否**自己就能收值**。
     *
     * 判据（对齐 Bitwarden `ViewNodeExtensions.toAutofillView`）：只有 `autofillType` 为
     * `AUTOFILL_TYPE_TEXT` 的节点才能被框架回填；`AUTOFILL_TYPE_NONE`（0）表示
     * 「这是个容器 / 不可填控件」——往它上面 `setValue` 会被框架丢弃。
     *
     * ⚠️ 判据是 `autofillType`，**不是**「是不是输入控件」（[isEditableNode]）。
     * 一个 `<input>` 完全可能 `autofillType == NONE`（`type=hidden`、`disabled`、
     * `readonly`），而 EditText 也可能因 `importantForAutofill=no` 而不可填。
     */
    private fun isFillable(node: ViewNode): Boolean = canReceiveValue(node.autofillType)

    /**
     * 重定向后的落点：`autofillType == TEXT` **且** 带 `autofillId`。
     *
     * 没有 `autofillId` 的节点框架无法寻址，写进去也不会生效 —— 必须继续往下找，
     * 否则会出现「命中一个填不了的目标」，与完全不做重定向一样无效。
     */
    private fun isFillableTarget(node: ViewNode): Boolean =
        isFillable(node) && node.autofillId != null

    /**
     * 解析本次填充真正该写入的节点：自己可填就用自己，否则**下钻**到第一个可填后代。
     *
     * 算法本体在 [io.vaultix.vaultix.autofill.parser.resolveFillTarget]（纯函数，
     * 单测见 `FillTargetResolverTest`）。这里只负责把框架的 [ViewNode] 树投影成最小
     * 抽象 [FillTargetNode] —— `ViewNode` 是 `@SystemApi` 抽象类，构造不出实例，
     * 逻辑留在这一类里就没法做 JVM 单测。
     *
     * 投影时给每个节点挂上**从根出发的子索引路径**（`List<Int>`）作为身份，回溯时按同一
     * 路径取回原节点。选路径而非对象引用，是因为 [FillTargetNode] 是 data class：
     * 两棵结构相同的子树会 `equals` 相等，用它做 map 的键会互相覆盖。
     *
     * @return 可写入的节点；整棵子树都不可填时返回 null（调用方丢弃该字段）。
     */
    private fun resolveFillTarget(node: ViewNode): ViewNode? {
        // 单节点快速路径：绝大多数节点自己就是输入框，不必建整棵投影树。
        if (isFillableTarget(node)) return node
        val byPath = mutableMapOf<NodePath, ViewNode>()
        val projection = project(node, emptyList(), byPath)
        val target = io.vaultix.vaultix.autofill.parser.resolveFillTarget(projection) ?: return null
        val path = target.identity as? NodePath ?: return null
        return byPath[path]
    }

    /**
     * 把 [node] 子树投影成 [FillTargetNode]，同时把「路径 → 原节点」记进 [byPath]。
     *
     * @param path 从本次重定向的根出发的子索引路径（根为空列表）。
     */
    private fun project(
        node: ViewNode,
        path: NodePath,
        byPath: MutableMap<NodePath, ViewNode>,
    ): FillTargetNode {
        byPath[path] = node
        val children = (0 until node.childCount).mapNotNull { index ->
            node.getChildAt(index)?.let { child -> project(child, path + index, byPath) }
        }
        return FillTargetNode(
            canReceiveValue = isFillableTarget(node),
            children = children,
            identity = path,
        )
    }

    /** 结构里第一个非空 webDomain（即页面主机）；没有则 null。 */
    private fun firstWebDomainOf(structure: AssistStructure): String? {
        repeat(structure.windowNodeCount) { index ->
            val root = structure.getWindowNodeAt(index)?.rootViewNode ?: return@repeat
            findWebDomain(root)?.let { return it }
        }
        return null
    }

    private fun findWebDomain(node: ViewNode): String? {
        node.webDomain?.takeIf { it.isNotBlank() }?.let { return it }
        repeat(node.childCount) { index ->
            val child = node.getChildAt(index) ?: return@repeat
            findWebDomain(child)?.let { return it }
        }
        return null
    }
}
