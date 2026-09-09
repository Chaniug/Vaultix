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

    /**
     * 是否允许按包名匹配（`androidapp://` 条目命中请求 App）。
     *
     * - 有权威/兜底域名 → 允许（Web 场景靠域名匹配，包名匹配不冲突）；
     * - 没有域名但是 WebView → 禁止（浏览器里缺域名时包名是浏览器自己）；
     * - 没有域名且是已知浏览器 → 禁止（同上）；
     * - 其余原生 App → 允许（原生场景包名就是身份）。
     */
    fun allowPackageMatching(packageName: String?, webDomain: String?, isWebView: Boolean): Boolean {
        if (!webDomain.isNullOrBlank()) return true
        if (isWebView) return false
        val pkg = packageName?.trim()?.lowercase(Locale.ROOT) ?: return false
        return pkg !in KNOWN_BROWSER_PACKAGES
    }
}
