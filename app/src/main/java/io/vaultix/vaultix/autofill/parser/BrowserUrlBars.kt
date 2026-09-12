/*
 * Vaultix — app:autofill · parser
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * 地址栏 / 结构文本域名兜底，移植自 Bastion `EnhancedAutofillStructureParserV2.URL_BARS`
 * 与 `extractDomainFromStructureText`（GPL-3.0，Copyright 2025 JoyinJoester），
 * 该实现对齐 Bitwarden `AutofillParserImpl.URL_BARS`。
 */
package io.vaultix.vaultix.autofill.parser

import android.app.assist.AssistStructure
import android.app.assist.AssistStructure.ViewNode
import java.net.URI
import java.util.Locale

/**
 * 浏览器地址栏识别与域名兜底。
 *
 * 背景：Chrome 的 WebView 会正常上报 `ViewNode.webDomain`，但 **Edge / 三星 / Opera /
 * Brave 等浏览器的 WebView 不总会上报**（Edge 包名 `com.microsoft.emmx` 即典型），
 * 此时 `webDomain == null` → 匹配器拿不到目标域名 → 表现为「浏览器里填充失效」。
 * 兜底优先级（对齐 Bastion）：
 * 1. `ViewNode.webDomain`（权威，见 [AssistStructureParser]）；
 * 2. 地址栏节点文本（本表，包名 + 资源 id **双重**匹配，比启发式更权威）；
 * 3. 结构文本里形似 URL 的串（[domainFromStructureText]，last-resort）。
 *
 * 只认「末段是字母的合法 host」：搜索词、版本号（`v2.0`）、纯 IP 都不会被当成域名。
 */
object BrowserUrlBars {

    /** 结构文本扫描的最大深度（防深层 UI 树拖慢填充响应）。 */
    private const val MAX_SCAN_DEPTH = 6

    /** 系统默认填充的 idPackage 值，不是合法包名，需排除（Bastion 同款处理）。 */
    private const val SYSTEM_ID_PACKAGE = "android"

    /** 末段（TLD）必须是 ≥2 位字母：`v2.0`、`127.0.0.1` 之类直接排除。 */
    private val TLD_PATTERN = Regex("^[a-z]{2,}$")

    /**
     * 参与拼「表单信号」的 HTML 属性键（对齐 Bitwarden `SUPPORTED_HTML_ATTRIBUTE_HINTS`
     * = name / label / type / hint / autofill / autocomplete，按**子串**匹配属性名，
     * 故 `aria-label`、`*-autofill-hints` 一类也能吃到）。
     *
     * ⚠️ `autocomplete` 是浏览器场景最关键的一路信号：站点用
     * `<input autocomplete="username">` / `"email"` / `"current-password"` 明确声明字段语义，
     * 上游正是靠它把账号框直接判成 Username（不依赖「紧邻密码框」的兜底升格）。
     */
    private val HTML_SIGNAL_KEY_TERMS = listOf(
        "name",
        "label",
        "type",
        "hint",
        "autofill",
        "autocomplete",
        "placeholder",
    )

    /**
     * 拼出该节点的**表单信号**（对齐 Bitwarden 的信号来源：`hint` → `idEntry` → `htmlInfo`）。
     *
     * ⚠️ **刻意不含 `node.text`**（2026-09-12 真机实证后收紧）：`text` 是控件里的
     * **内容 / 标签文字**，浏览器 WebView 会把整棵 DOM 建成可填节点，`<label>Password</label>`、
     * 「Forgot password?」这类展示文字全在 `text` 里 —— 拿它做语义判定会让这些节点被判成
     * PASSWORD / USERNAME，进而挡住真正的账号框（详见 [isEditableNode] 的 KDoc）。
     * 上游 Bitwarden 的启发式**从不读** `node.text`，只看 `idEntry` / `hint` / `htmlInfo`。
     *
     * `text` 仍然作为**字段当前值**被解析（`ParsedField.value`，保存流程要用），
     * 只是不参与「这是什么字段」的判定 —— 两件事必须分开。
     */
    fun formSignalOf(node: ViewNode): String? {
        // htmlInfo.attributes 是 android.util.Pair（Java 类型，不可解构）
        val html = node.htmlInfo?.attributes
            ?.filter { attribute ->
                HTML_SIGNAL_KEY_TERMS.any { it in attribute.first.lowercase(Locale.ROOT) }
            }
            ?.joinToString(" ") { it.second }
        return listOfNotNull(node.hint, node.idEntry, html)
            .joinToString(" ")
            .trim()
            .ifEmpty { null }
    }

    /**
     * 浏览器包名 → 地址栏控件资源 id（`ViewNode.idEntry`）。
     *
     * 必须**包名 + idEntry 双重匹配**：不同 App 可能复用同名资源 id（如都叫 `url_bar`），
     * 只判 idEntry 会把无关输入框当成地址栏。
     */
    private val URL_BARS: Map<String, String> = mapOf(
        // Chromium 系（Chrome 通常上报 webDomain，仅在缺失时兜底；Edge 是本表的重点场景）
        "com.android.chrome" to "url_bar",
        "com.chrome.beta" to "url_bar",
        "com.chrome.dev" to "url_bar",
        "com.chrome.canary" to "url_bar",
        "com.google.android.apps.chrome" to "url_bar",
        "org.chromium.chrome" to "url_bar",
        // Edge 各渠道（用户反馈「Edge 填充失效」的根因场景）
        "com.microsoft.emmx" to "url_bar",
        "com.microsoft.emmx.beta" to "url_bar",
        "com.microsoft.emmx.canary" to "url_bar",
        "com.microsoft.emmx.dev" to "url_bar",
        // 三星浏览器
        "com.sec.android.app.sbrowser" to "location_bar_edit_text",
        "com.sec.android.app.sbrowser.beta" to "location_bar_edit_text",
        // Opera
        "com.opera.browser" to "url_bar",
        "com.opera.browser.beta" to "url_bar",
        "com.opera.mini.native" to "url_bar",
        "com.opera.touch" to "addressbarEdit",
        // Brave / Vivaldi / Kiwi / Bromite
        "com.brave.browser" to "url_bar",
        "com.brave.browser_beta" to "url_bar",
        "com.brave.browser_nightly" to "url_bar",
        "com.vivaldi.browser" to "url_bar",
        "com.kiwibrowser.browser" to "url_bar",
        "org.bromite.bromite" to "url_bar",
        // Firefox 全渠道
        "org.mozilla.firefox" to "mozac_browser_toolbar_url_view",
        "org.mozilla.firefox_beta" to "mozac_browser_toolbar_url_view",
        "org.mozilla.fenix" to "mozac_browser_toolbar_url_view",
        "org.mozilla.fenix.nightly" to "mozac_browser_toolbar_url_view",
        "org.mozilla.fennec_aurora" to "mozac_browser_toolbar_url_view",
        "org.mozilla.focus" to "mozac_browser_toolbar_url_view",
        "org.mozilla.klar" to "mozac_browser_toolbar_url_view",
        "org.mozilla.reference.browser" to "mozac_browser_toolbar_url_view",
        "org.mozilla.rocket" to "mozac_browser_toolbar_url_view",
        // DuckDuckGo / Via / 广告过滤浏览器 / 其它
        "com.duckduckgo.mobile.android" to "omnibarTextInput",
        "mark.via" to "url_bar",
        "mark.via.gp" to "url_bar",
        "org.adblockplus.browser" to "url_bar",
        "org.adblockplus.browser.beta" to "url_bar",
        "com.qwant.liberty" to "url_bar",
        "com.android.browser" to "url",
        "org.codeaurora.swe.browser" to "url_bar",
    )

    /**
     * 该节点是否为当前浏览器的地址栏。
     *
     * @param pagePackageName 触发填充的 App 包名（[AssistStructure] 的 activity 包名）。
     * @param idPackage 节点的 `idPackage`（OS 有时给成 `android`，需排除）。
     * @param idEntry 节点的 `idEntry`（资源 id 名）。
     */
    fun isUrlBarNode(pagePackageName: String?, idPackage: String?, idEntry: String?): Boolean {
        if (idEntry.isNullOrBlank()) return false
        val rawIdPackage = idPackage?.trim().orEmpty()
        // OS 有时把 idPackage 给成 "android"：不是合法包名，直接排除（不回退页面包名）
        if (rawIdPackage.equals(SYSTEM_ID_PACKAGE, ignoreCase = true)) return false
        val owner = rawIdPackage.ifEmpty { pagePackageName?.trim().orEmpty() }
        if (owner.isEmpty()) return false
        return URL_BARS[owner.lowercase(Locale.ROOT)] == idEntry
    }

    /** 地址栏 / 任意文本 → 主机（只认末段为字母的合法 host；搜索词返回 null）。 */
    fun hostFromText(raw: String?): String? {
        val trimmed = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val candidate = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val host = runCatching { URI(candidate).host }.getOrNull()?.lowercase(Locale.ROOT)
            ?: return null
        val labels = host.split('.')
        if (labels.size < 2) return null
        if (!TLD_PATTERN.matches(labels.last())) return null
        return host
    }

    /** Last-resort：广度优先扫描结构文本，取第一个形似 URL 的域名（深度 ≤ [MAX_SCAN_DEPTH]）。 */
    fun domainFromStructureText(structure: AssistStructure): String? {
        repeat(structure.windowNodeCount) { index ->
            val root = structure.getWindowNodeAt(index)?.rootViewNode ?: return@repeat
            scanNodeForHost(root, depth = 0)?.let { return it }
        }
        return null
    }

    private fun scanNodeForHost(node: ViewNode?, depth: Int): String? {
        if (node == null || depth > MAX_SCAN_DEPTH) return null
        hostFromText(node.text?.toString())?.let { return it }
        repeat(node.childCount) { index ->
            scanNodeForHost(node.getChildAt(index), depth + 1)?.let { return it }
        }
        return null
    }
}
