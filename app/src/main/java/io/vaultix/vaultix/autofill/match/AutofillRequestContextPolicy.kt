/*
 * Vaultix — app:autofill · match
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * 包名匹配闸门移植自 Bastion `AutofillRequestContextPolicy`
 * （GPL-3.0，Copyright 2025 JoyinJoester）。
 */
package io.vaultix.vaultix.autofill.match

import java.util.Locale

/**
 * 请求上下文策略：判断本次填充「能不能退化到包名匹配」。
 *
 * 浏览器（Edge / Chrome / Firefox…）里若 WebView 没上报域名，请求的包名就是**浏览器自己**
 * 的包名——此时若允许包名匹配，会把「保存了 androidapp://com.microsoft.emmx 之外条目」
 * 的无关结果按浏览器包名弹出来，甚至把浏览器当成条目的身份。故对已知浏览器包名
 * **关闭**包名退化（Bastion 同款闸门）。
 */
internal object AutofillRequestContextPolicy {

    /** 已知浏览器包名（小写比较）。 */
    private val KNOWN_BROWSER_PACKAGES: Set<String> = setOf(
        "com.android.browser",
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "com.google.android.apps.chrome",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.fenix",
        "org.mozilla.fenix.nightly",
        "io.github.forkmaintainers.iceraven",
        "com.microsoft.emmx",
        "com.microsoft.emmx.beta",
        "com.microsoft.emmx.canary",
        "com.microsoft.emmx.dev",
        "com.sec.android.app.sbrowser",
        "com.mi.globalbrowser",
        "mark.via",
        "mark.via.gp",
        "com.ucmobile",
        "com.uc.browser.en",
        "com.uc.browser.hd",
        "com.quark.browser",
        "com.tencent.mtt",
        "com.baidu.browser.apps",
        "com.qihoo.browser",
        "com.ijinshan.browser_fast",
        "com.opera.browser",
        "com.brave.browser",
        "com.kiwibrowser.browser",
    )

    /**
     * 硬编码禁止填充的包名（对齐 Bitwarden `AutofillParserImpl` 的 blocked URIs）：
     * 系统 / 设置 / 自身 / 一加应用锁——在这些界面弹填充只会干扰，且自身会自填自己。
     */
    private val BLOCKED_PACKAGES: Set<String> = setOf(
        "android",
        "com.android.settings",
        "com.android.systemui",
        "com.oneplus.applocker",
    )

    /** 该请求 App 是否禁止填充（含本应用自身，避免「自己填自己」）。 */
    fun isBlockedPackage(packageName: String?, selfPackageName: String?): Boolean {
        val pkg = packageName?.trim()?.lowercase(Locale.ROOT) ?: return false
        if (selfPackageName != null && pkg == selfPackageName.lowercase(Locale.ROOT)) return true
        return pkg in BLOCKED_PACKAGES
    }

    /** 该包名是否为已知浏览器（缺域名时需要按「浏览器语义」而不是「App 语义」处理）。 */
    fun isKnownBrowser(packageName: String?): Boolean {
        val pkg = packageName?.trim()?.lowercase(Locale.ROOT) ?: return false
        return pkg in KNOWN_BROWSER_PACKAGES
    }

    /**
     * 是否允许按包名匹配（`androidapp://` 条目命中请求 App）——**宽松档**。
     *
     * - 有权威/兜底域名 → 允许（Web 场景靠域名匹配，包名匹配不冲突）；
     * - 没有域名但是 WebView → 禁止（浏览器里缺域名时包名是浏览器自己）；
     * - 没有域名且是已知浏览器 → 禁止（同上）；
     * - 其余原生 App → 允许（原生场景包名就是身份）。
     *
     * ⚠️ 本档**不覆盖**「App 内嵌 H5 本地登录页」——那种页面 `isWebView` 为真，
     * 但包名确实是 App 自己。补丁见 [allowExactPackageMatching]。
     */
    fun allowPackageMatching(packageName: String?, webDomain: String?, isWebView: Boolean): Boolean {
        if (!webDomain.isNullOrBlank()) return true
        if (isWebView) return false
        val pkg = packageName?.trim()?.lowercase(Locale.ROOT) ?: return false
        return pkg !in KNOWN_BROWSER_PACKAGES
    }

    /**
     * `file://` 本地 H5 的**精确**包名匹配例外（对齐 Bastion `allowExactPackageMatching`）。
     *
     * ## 为什么必须有这一条（2026-11-18 对照 Bastion 原实现）
     *
     * 大量国产 App 把登录页放在 `file:///android_asset/login.html`（或 `data:`）里：
     * 此时 `ViewNode.webDomain` 为空、`isWebView` 为真，而请求包名**就是 App 自己**。
     * 只按 [allowPackageMatching] 判会把它当成「浏览器缺域名」而关掉包名匹配 ⇒
     * 用户在 App 里存过的 `androidapp://包名` 条目永远匹配不上，还被无域名降级
     * 换成「列出全库别人的密码」。
     *
     * ## 为什么只放行 `file:` 且只放行精确匹配
     *
     * `file:` 页面没有可用的 origin，条目身份只能是 App 包名；而 `http(s)` 的 WebView
     * 页面即便没上报域名，它的身份仍是那个网站 —— 拿包名去匹配会让「App 条目」
     * 顶替「网站条目」。所以本例外**只**在 scheme 明确为 `file` 时生效，
     * 且只用于 `androidapp://` 的逐字符相等（不做任何模糊匹配）。
     *
     * 注：本函数**不重复**判断 [allowPackageMatching] 已覆盖的分支，调用方按「或」组合。
     */
    fun allowExactPackageMatching(
        packageName: String?,
        webDomain: String?,
        isWebView: Boolean,
        webScheme: String?,
    ): Boolean {
        if (packageName.isNullOrBlank() || isKnownBrowser(packageName)) return false
        if (!webDomain.isNullOrBlank() || !isWebView) return false
        return webScheme.equals("file", ignoreCase = true)
    }

    /**
     * 拿不到域名时，是否允许「列出全部候选」的降级。
     *
     * ## 为什么默认值从「允许」收紧成「只允许浏览器」
     *
     * [io.vaultix.vaultix.autofill.engine.AutofillCandidateSource.matchLogins] 的降级本是
     * 为 Firefox（GeckoView 不上报 `webDomain`）加的。但判据只写了「没有域名」⇒ 原生 App
     * 本来就没有域名，于是**每个没存 `androidapp://` 条目的 App 里都会列出整库密码**，
     * 而且自己那条反而不在列表里（用户体感：匹配错了）。
     *
     * 对齐 Bitwarden：原生 App 的请求 URI 恒为 `androidapp://包名`，匹配不到就**一条都不给**，
     * 只留「在 Vaultix 中搜索」入口；「列全部」只对「域名该有却拿不到」的浏览器成立。
     */
    fun allowDomainlessFallback(packageName: String?): Boolean = isKnownBrowser(packageName)
}
