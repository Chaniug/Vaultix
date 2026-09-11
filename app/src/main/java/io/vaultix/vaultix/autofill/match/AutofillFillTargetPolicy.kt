/*
 * Vaultix — app:autofill · match
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.autofill.match

import io.vaultix.vaultix.autofill.model.FieldHint
import io.vaultix.vaultix.autofill.model.ParsedField
import io.vaultix.vaultix.autofill.model.ParsedStructure
import io.vaultix.vaultix.autofill.parser.HintClassifier

/**
 * 「这个字段是否值得为它弹出填充 UI」的判定。
 *
 * ## 为什么需要这道门（真机事故）
 * 此前 `VaultixAutofillService.onFillRequest` 只判断「页面上有没有可见字段」，
 * 没匹配到条目就一律挂一个整表认证响应（`MODE_SEARCH`）⇒ **只要页面存在任何可见
 * 输入框，填充 UI 就会弹出来** —— 搜索框、订阅框、昵称框全部中招。
 *
 * 对齐两处参考实现：
 * - **Bitwarden**：`AutofillRequest.Unfillable` 时直接 `fillCallback.onSuccess(null)`，
 *   并注释「This effectively disables autofill for this view set」；其
 *   `FillResponseBuilderImpl` 在 `filledData.fillableAutofillIds.isEmpty()` 时返回
 *   `null`。**连「我的密码库」入口条目也只挂在有可填字段的页面上。**
 * - **Bastion**：按信号强度 ≥ MEDIUM 过滤（其「京东搜索栏误弹」的 P1 修复），
 *   本文件的 KDoc 承诺见 [HintClassifier] 顶部（弱信号不得单独触发密码候选）。
 *
 * 两条规则合并为：
 * 1. 语义上不属于「凭据类」（[FieldHint.SEARCH] / [FieldHint.UNKNOWN]）→ 永不算数；
 * 2. 密码 / 新密码 / 验证码本身就是强证据，直接算数；
 * 3. 账号 / 邮箱 / 身份 / 卡片类，必须由标准 hint 或 inputType 判出（≥ MEDIUM）——
 *    仅凭文本启发式命中的**孤立**文本框中，`id` / `placeholder` 含 "login / 账号 / 用户名"
 *    的搜索框会被误判成 [FieldHint.USERNAME]，从而把填充 UI 勾出来。
 */
object AutofillFillTargetPolicy {

    /** 语义上是否属于「凭据类」字段（对齐 Bitwarden 的 Login / Card / Identity 分类）。 */
    private fun FieldHint.isCredentialBearing(): Boolean = when (this) {
        FieldHint.SEARCH, FieldHint.UNKNOWN -> false
        else -> true
    }

    /** 密码 / 新密码 / 验证码：字段类型本身就是证据，未经启发式也不影响判定。 */
    private fun FieldHint.isSelfEvident(): Boolean = when (this) {
        FieldHint.PASSWORD, FieldHint.NEW_PASSWORD, FieldHint.OTP -> true
        else -> false
    }

    /**
     * 单个字段是否值得为它弹出填充 UI。
     *
     * **纯函数重载**（不依赖 [ParsedField] / `AutofillId`）：便于 JVM 单测直接覆盖判定矩阵。
     */
    fun isFillTarget(
        hint: FieldHint,
        strength: HintClassifier.SignalStrength,
        isVisible: Boolean = true,
    ): Boolean = when {
        !isVisible -> false
        !hint.isCredentialBearing() -> false
        hint.isSelfEvident() -> true
        else -> strength != HintClassifier.SignalStrength.LOW
    }

    /** 单个字段是否值得为它弹出填充 UI。 */
    fun isFillTarget(field: ParsedField): Boolean =
        isFillTarget(hint = field.hint, strength = field.strength, isVisible = field.isVisible)

    /** 页面上全部「值得填充」的字段（保持原顺序）。 */
    fun fillTargets(parsed: ParsedStructure): List<ParsedField> = parsed.fields.filter(::isFillTarget)

    /** 页面上是否存在值得填充的字段（上层据此决定「响应 / 不响应」）。 */
    fun hasFillTarget(parsed: ParsedStructure): Boolean = parsed.fields.any(::isFillTarget)
}
