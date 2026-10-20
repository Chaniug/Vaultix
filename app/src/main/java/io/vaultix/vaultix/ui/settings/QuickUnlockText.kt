/*
 * Vaultix — app:ui · settings
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.settings

import androidx.annotation.StringRes

/**
 * 「快速解锁」对话框里的一行解释 / 原因，**分两类来源**。
 *
 * ## 为什么（审计 §3.4，2026-10-20）
 *
 * 改前 `Dialog.PinEntry.error` / `Dialog.KdbxPassword.error` / `FailureItem.reason`
 * 全是 `String?`，里面同时装着两种东西：
 *
 * | 改前写法 | 实际来源 | 问题 |
 * |---|---|---|
 * | `"两次输入不一致，请重新输入"` | **我们自己写的** | 进了 Kotlin，`strings.xml` 里没有 ⇒ 翻不到、也查不出 |
 * | `outcome.detail` | 来源层已翻译好的 | 本来就该原样展示 |
 *
 * 两者长得一模一样 ⇒ 评审时看不出哪句该进资源，哨兵脚本也无法区分。
 *
 * ## 两类（判据与 `UnlockUiError` / `CloudError` 完全一致）
 *
 * - [Res]：**这句话是我们自己写的** ⇒ 必须在 `strings.xml`，携带 `@StringRes` + 格式化参数。
 * - [Detail]：来源层已经翻译成人话 ⇒ 原样展示，不再套前缀。
 *
 * ⚠️ 三个类型（`UnlockUiError` / `CloudError` / 本类型）**判据相同、放的位置不同**：
 * 各在自己的页面包里，因为它们的 `Res` 分支要映射的资源集合不同。
 * 想抽公共抽象之前先问：除了"有 Res 和 Detail 两个分支"，它们还有共同行为吗？目前没有。
 */
sealed interface QuickUnlockText {
    /**
     * 我们自己写的文案。
     *
     * @param id `strings.xml` 里的资源 id
     * @param args 格式化参数（如剩余次数、PIN 位数）。用 `Array` 而不是 `vararg`
     *   是为了让 `data class` 的 `equals` 仍然按内容比较 —— `vararg` 在 data class
     *   里会退化成数组引用比较，两个内容相同的实例会判不相等（`copy()` 之后各种怪）。
     */
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : QuickUnlockText

    /** 来源层已经翻译好的一句话。 */
    data class Detail(val message: String) : QuickUnlockText
}

/**
 * 便捷构造：[text] 的两个典型用法 —— 无参，或带一个格式化参数。
 *
 * ⚠️ 这里**刻意不写 `R.string.<name>` 形式的例子**：`check_compile_smells.py`
 * 会把 KDoc 里的这种字样当成真引用去资源表里找，找不到就报"悬空引用"。
 * 那是它的正确行为（它得抓真代码），所以避让的是文档写法，不是放宽脚本。
 */
fun text(@StringRes id: Int, vararg args: Any): QuickUnlockText =
    QuickUnlockText.Res(id, args.toList())
