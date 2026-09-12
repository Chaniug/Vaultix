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
 * 规则（2026-09-12 对齐 Bitwarden 后收敛为一条）：
 * - 字段**可见**、且语义属于「凭据类」（[FieldHint.SEARCH] / [FieldHint.UNKNOWN] 不算数）
 *   → 算填充目标；否则不算。
 *
 * ⚠️ 曾经的第三层「弱信号不算数」**已撤销**（见 [isFillTarget]）：上游 Bitwarden 没有强度闸，
 * 而当初要靠它拦的「搜索框误判」已改由
 * [io.vaultix.vaultix.autofill.parser.HintClassifier] 的**否定词**在**分类阶段**拦掉
 * （对齐上游 `IGNORED_RAW_HINTS`）——在更靠前、更准的位置解决同一个问题。
 */
object AutofillFillTargetPolicy {

    /** 语义上是否属于「凭据类」字段（对齐 Bitwarden 的 Login / Card / Identity 分类）。 */
    private fun FieldHint.isCredentialBearing(): Boolean = when (this) {
        FieldHint.SEARCH, FieldHint.UNKNOWN -> false
        else -> true
    }

    /**
     * 单个字段是否值得为它弹出填充 UI。
     *
     * ⚠️ **2026-09-12 对齐 Bitwarden：撤销「信号强度 ≥ MEDIUM」这道门槛。**
     * 上游的判定是「**分类结果即证据**」——节点要么被归为 Login / Card（可填），
     * 要么是 `Unused` 被直接剔除（`AutofillParserImpl.selectCandidateAutofillViews`），
     * **不存在第二层强度闸**。
     *
     * 我们此前多这一层，是为了在**关键词过宽**时兜底（旧词表含 `login`，
     * `id="login-search"` 这类搜索框会被误判成账号框）。该场景如今已在
     * [io.vaultix.vaultix.autofill.parser.HintClassifier] 用「否定词优先」兜住
     * （search / find / recipient / edit + 中文），强度闸因此变成**冗余且降召回**的一层：
     * `id="username"` 且没有密码框的页面，上游会出候选，我们却因 LOW 不出。
     *
     * **纯函数重载**（不依赖 [ParsedField] / `AutofillId`）：便于 JVM 单测直接覆盖判定矩阵。
     */
    fun isFillTarget(hint: FieldHint, isVisible: Boolean = true): Boolean =
        isVisible && hint.isCredentialBearing()

    /** 单个字段是否值得为它弹出填充 UI。 */
    fun isFillTarget(field: ParsedField): Boolean =
        isFillTarget(hint = field.hint, isVisible = field.isVisible)

    /** 页面上全部「值得填充」的字段（保持原顺序）。 */
    fun fillTargets(parsed: ParsedStructure): List<ParsedField> = parsed.fields.filter(::isFillTarget)

    /** 页面上是否存在值得填充的字段（上层据此决定「响应 / 不响应」）。 */
    fun hasFillTarget(parsed: ParsedStructure): Boolean = parsed.fields.any(::isFillTarget)
}
