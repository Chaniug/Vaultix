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
import io.vaultix.vaultix.autofill.model.FieldHint
import io.vaultix.vaultix.autofill.model.ParsedField
import io.vaultix.vaultix.autofill.model.ParsedStructure

/** 把 [AssistStructure] 拆成 [ParsedStructure]（字段语义 + 登录框 id + WebView 标记）。 */
object AssistStructureParser {

    fun parse(structure: AssistStructure): ParsedStructure {
        val packageName = structure.activityComponent?.packageName

        val fields = mutableListOf<ParsedField>()
        val webDomains = mutableListOf<String>()
        var webView = false
        repeat(structure.windowNodeCount) { index ->
            val root = structure.getWindowNodeAt(index)?.rootViewNode ?: return@repeat
            webView = traverse(root, fields, webDomains) || webView
        }

        val webDomain = webDomains.firstOrNull()
        val webUri = webDomain?.let { "https://$it" }
        val usernameId = fields.firstOrNull { it.hint == FieldHint.USERNAME }?.id
        val passwordId = fields.firstOrNull { it.hint == FieldHint.PASSWORD }?.id
        return ParsedStructure(
            packageName = packageName,
            webScheme = null,
            webDomain = webDomain,
            webUri = webUri,
            webView = webView,
            usernameId = usernameId,
            passwordId = passwordId,
            fields = fields,
        )
    }

    /** 深度优先遍历，收集有 autofillId 的字段与 WebView 节点的域名；返回子树内是否出现 WebView。 */
    private fun traverse(
        node: ViewNode,
        out: MutableList<ParsedField>,
        webDomains: MutableList<String>,
    ): Boolean {
        var webView = node.className?.contains("WebView", ignoreCase = true) == true
        node.webDomain?.let { webDomains += it }
        val id = node.autofillId
        if (id != null) {
            val hints = node.autofillHints?.map { it.toString() }
            val inputType = node.inputType
            val text = (node.text ?: node.hint)?.toString()
            out += ParsedField(
                id = id,
                hint = HintClassifier.classify(hints, inputType, text),
                value = node.text?.toString(),
                isFocused = node.isFocused,
                isVisible = node.visibility == View.VISIBLE,
            )
        }
        repeat(node.childCount) { index ->
            val child = node.getChildAt(index) ?: return@repeat
            webView = traverse(child, out, webDomains) || webView
        }
        return webView
    }
}
