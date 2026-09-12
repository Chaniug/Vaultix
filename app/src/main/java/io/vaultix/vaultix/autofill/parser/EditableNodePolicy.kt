/*
 * Vaultix — app:autofill · parser
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * 判定规则对齐 Bitwarden `ViewNodeExtensions.isInputField`（GPL-3.0，© Bitwarden Inc.）：
 * 上游 `toAutofillView` 的第一道闸就是「不是输入控件且没有标准 hint → 直接丢弃该节点」。
 */
package io.vaultix.vaultix.autofill.parser

/** HTML 输入元素的 tag（WebView / Chromium 系浏览器通过 `htmlInfo.tag` 暴露）。 */
private const val HTML_INPUT_TAG = "input"

/**
 * 可编辑控件的类名特征（原生 App 走 `className`）。
 *
 * `AutoCompleteTextView` 虽然是 `EditText` 的子类，但类名里不含 "EditText"，必须单列
 * （登录页的「账号联想输入框」大量使用它）。
 */
private val EDITABLE_CLASS_NAMES = listOf(
    "EditText",
    "AutoCompleteTextView",
    "SearchView",
)

/**
 * 「这个节点是否**可能**是可填充控件」的判定（纯函数，便于 JVM 单测）。
 *
 * ## 为什么需要这道闸（真机实证）
 * 2026-09-12 的 Edge 抓包（`build/adb-capture/device-log.txt`，github.com）：
 * ```
 * fillRequest pkg=com.microsoft.emmx fields=221 hints={UNKNOWN=215, USERNAME=2, PASSWORD=4}
 * ```
 * —— 一个页面被解析出 **221 个「字段」**，其中 4 个被判成 PASSWORD。而实际上 GitHub 登录
 * 页只有 1 个密码框、1 个账号框。多出来的是浏览器 WebView 给 **整棵 DOM** 建的可填节点
 * （标签 `<label>`、说明文字、按钮…），它们的 `autofillId` 非空、`text` 里恰好带着
 * "Password" / "Username or email address" 这类字样，于是：
 *
 * 1. 被文本启发式误判成 USERNAME / PASSWORD，**占掉**了「账号框」这个语义位；
 * 2. `promoteUsernameField` 的判据是「没有**可见的** USERNAME 才升格」→ 直接被这些假
 *    USERNAME 挡住 → 真正的账号输入框永远是 UNKNOWN；
 * 3. 结果 `hasUsernameField = false` ⇒ Dataset 里只有密码值 ⇒ **点账号框什么都不弹、
 *    也填不进去；点密码框能弹、但只填进密码**（用户报告的症状，与 QQ 修复前同型）。
 *
 * 上游 Bitwarden 不会踩这个坑：它的 `toAutofillView` 先要求节点「是输入控件
 * （`htmlInfo.tag == "input"` 或 className 是 EditText 家族）或带标准 autofillHints」，
 * 否则丢弃并把该 `autofillId` 记进 `ignoreAutofillIds`。
 *
 * ## 判定口径（保守优先，宁可多认不误杀）
 * 1. 带**任意**标准 autofill hint → 认（信号本身就说明它是可填控件）；
 * 2. 有 `htmlInfo` → 只看 tag 是否为 `input`（WebView / 浏览器的权威判据）；
 * 3. 没有 `htmlInfo` → 看 `className` 是否 EditText 家族（原生 App 的判据）；
 * 4. `className` 也没有（罕见）→ **放行**：判不出来时不能把原生控件一起误杀。
 *
 * ⚠️ 第 2 条**只看 tag，不看别的属性**：`htmlInfo` 存在即意味着这是 HTML 元素，
 * 此时 `tag != "input"`（label / div / a / button）就一定是不可填的展示节点。
 */
internal fun isEditableNode(
    htmlTag: String?,
    className: String?,
    hints: List<String>?,
): Boolean {
    if (hints?.any(HintClassifier::supportsHint) == true) return true
    if (htmlTag != null) return htmlTag.equals(HTML_INPUT_TAG, ignoreCase = true)
    val name = className ?: return true
    return EDITABLE_CLASS_NAMES.any { name.contains(it, ignoreCase = true) }
}
