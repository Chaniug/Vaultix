/*
 * Vaultix — app:autofill · fillassist
 * Copyright (C) 2026 Vaultix contributors
 *
 * HtmlInfo 与规则选择器的比对。移植自 Bitwarden `HtmlInfoExtensions`
 * （GPL-3.0，© Bitwarden Inc.）。
 *
 * 语义要点（照搬上游，勿「优化」）：
 * - `id`/`name`/`type`/`role` 都是**精确相等**比对（不是包含/前缀）；
 * - 子句若没有任何约束（连 tag 都没有）→ **一律不算命中**，
 *   否则会变成「匹配一切节点」；
 * - 一个字段同时命中 `username` 与 `email` 时**优先 username**：
 *   `Login.Username` 不限制值形态，而 Email 会用 `isValidEmail()` 拒掉手机号这类账号。
 */
package io.vaultix.vaultix.autofill.fillassist

import android.view.ViewStructure
import io.vaultix.vaultix.autofill.model.FieldHint

/** 规则命中判定。 */
object FillAssistMatcher {

    private const val ATTR_ID = "id"
    private const val ATTR_NAME = "name"
    private const val ATTR_TYPE = "type"
    private const val ATTR_ROLE = "role"

    /**
     * 用该主机的规则判定节点语义；无命中返回 null（调用方此时**丢弃**该节点，
     * 不再退回启发式——对齐上游「rules are authoritative」）。
     */
    fun matchHint(htmlInfo: ViewStructure.HtmlInfo?, rules: List<HostRule>): FieldHint? {
        val info = htmlInfo ?: return null
        val hits = rules
            .flatMap { rule -> rule.fields.entries }
            .filter { (_, clauses) -> clauses.any { info.matchesSelectorClause(it) } }
            .mapNotNull { (fieldKey, _) -> fieldKeyToHint(fieldKey) }
        return hits.firstOrNull { it == FieldHint.USERNAME } ?: hits.firstOrNull()
    }

    /** 该节点是否命中任意规则（用于「命中才收进字段表」的过滤）。 */
    private fun ViewStructure.HtmlInfo.matchesSelectorClause(clause: SelectorClause): Boolean {
        if (!clause.hasConstraint) return false
        if (clause.tag != null && clause.tag != tag) return false
        val attrs = attributes ?: return clause.hasNoAttributeConstraints
        return listOf(
            matchesAttr(attrs, clause.id, ATTR_ID),
            matchesAttr(attrs, clause.name, ATTR_NAME),
            matchesAttr(attrs, clause.type, ATTR_TYPE),
            matchesAttr(attrs, clause.role, ATTR_ROLE),
        ).all { it }
    }

    /** 子句没有任何属性约束（只约束了 tag）。 */
    private val SelectorClause.hasNoAttributeConstraints: Boolean
        get() = id == null && name == null && type == null && role == null

    /** [value] 无约束时视为通过；否则要求 [attrs] 中存在同名同值的属性。 */
    private fun matchesAttr(attrs: List<android.util.Pair<String, String>>, value: String?, key: String): Boolean =
        value == null || attrs.any { it.first == key && it.second == value }
}
