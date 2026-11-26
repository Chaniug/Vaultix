/*
 * Vaultix — app:autofill · parser
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.autofill.parser

/**
 * #307 结构级诊断：`fields=0` 时把整棵 AssistStructure 里**值得看的节点**打出来，
 * 补上 [UnknownFieldDiagnostics] 的盲区。
 *
 * ## 为什么需要它（fields=0 的三种可能，修法完全不同）
 *
 * [UnknownFieldDiagnostics] 只对「进了字段表但判 UNKNOWN」的节点打 probe。但被
 * `isEditableNode` 准入闸**直接拒绝**（`classifyNode` 返回 null）的节点根本不进字段表，
 * 于是 `fields=0` 时一条 probe 都没有，分不清：
 * 1. 树里压根没有可填节点（用户点错 / 内核没上报输入框）；
 * 2. 有 `autofillId` + `autofillType=TEXT` 的节点，但 className/htmlTag 非标准被准入闸拒；
 * 3. 有节点、也过了准入闸，但被规则覆盖拒绝（`hostRules` 命中即严格模式）。
 *
 * 本诊断输出：① 全树计数（节点总数 / 带 autofillId / TEXT 可填 / focused）；② 值得看的
 * 节点样本（带 autofillId、focused、或 className 可疑），每条含 className / autofillType /
 * htmlTag / idEntry / hints。一次抓日志就能定位夸克到底把登录框做成什么控件。
 *
 * ## 安全红线（与 [io.vaultix.vaultix.autofill.AutofillLogger] 一致）
 *
 * `Probe` 签名里**没有** `text`/`value` 参数——编译期杜绝用户输入值入日志。所有字符串
 * 截断 + 去换行，样本条数上限防止刷屏（VaultixLog 另有每 tag 50 条/60s 限频兜底）。
 */
internal object StructureDiagnostics {

    /** 全树计数摘要。字段数卡在 detekt `LongParameterList` 上限 8。 */
    data class Summary(
        val windows: Int,
        val nodes: Int,
        val withAutofillId: Int,
        val textFillable: Int,
        val focused: Int,
    )

    /** 单个值得看的节点元数据；刻意不含 text / value。 */
    data class NodeProbe(
        val className: String?,
        val autofillType: Int,
        val inputType: Int,
        val htmlTag: String?,
        val idEntry: String?,
        val hints: List<String>?,
        val isFocused: Boolean,
        val childCount: Int,
    )

    /** 样本上限（防刷屏）。 */
    const val MAX_SAMPLES = 16

    /** 单字符串最大长度（截断防夹带 / 刷屏）。 */
    private const val MAX_PART_LEN = 80

    /** 十六进制进制（detekt MagicNumber）。 */
    private const val HEX_RADIX = 16

    /** 摘要串：`structSummary windows=… nodes=… withId=… textFillable=… focused=…` */
    fun formatSummary(s: Summary): String = buildString {
        append("structSummary ")
        append("windows=${s.windows} ")
        append("nodes=${s.nodes} ")
        append("withId=${s.withAutofillId} ")
        append("textFillable=${s.textFillable} ")
        append("focused=${s.focused}")
    }

    /** 节点串：`structNode cls=… a11yType=… inputType=0x…(…) tag=… id=… hints=[…] focus=… children=…` */
    fun formatNode(p: NodeProbe): String = buildString {
        append("structNode ")
        append("cls=${clip(p.className)} ")
        append("a11yType=${p.autofillType} ")
        append("inputType=0x${p.inputType.toString(HEX_RADIX)}(${p.inputType}) ")
        append("tag=${clip(p.htmlTag)} ")
        append("id=${clip(p.idEntry)} ")
        append("hints=[${formatHints(p.hints)}] ")
        append("focus=${p.isFocused} ")
        append("children=${p.childCount}")
    }

    /** 判据：这个节点是否值得作为样本输出（有 autofillId / focused / className 可疑）。 */
    fun isWorthSampling(
        hasAutofillId: Boolean,
        isFocused: Boolean,
        className: String?,
    ): Boolean {
        if (hasAutofillId || isFocused) return true
        val name = className ?: return false
        return SUSPICIOUS_CLASS_TERMS.any { name.contains(it, ignoreCase = true) }
    }

    /** 可疑控件类名关键词：UC/Chromium 内核自绘输入框常落在这些名里。 */
    private val SUSPICIOUS_CLASS_TERMS = listOf(
        "edit", "input", "text", "web", "browser", "uc",
    )

    private fun formatHints(hints: List<String>?): String =
        if (hints.isNullOrEmpty()) "-" else hints.joinToString(",") { clip(it) }

    private fun clip(value: String?): String {
        if (value.isNullOrEmpty()) return "-"
        return value
            .replace('\n', ' ')
            .replace('\r', ' ')
            .let { if (it.length > MAX_PART_LEN) it.take(MAX_PART_LEN) + "…" else it }
    }
}
