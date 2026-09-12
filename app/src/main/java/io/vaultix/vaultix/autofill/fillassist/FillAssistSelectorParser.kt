/*
 * Vaultix — app:autofill · fillassist
 * Copyright (C) 2026 Vaultix contributors
 *
 * CSS 选择器子集解析器。移植自 Bitwarden `FillAssistManagerImpl`（GPL-3.0，© Bitwarden Inc.）。
 *
 * 规则表里的选择器是**完整 CSS**，但 Android 的 `AssistStructure` 只能给出「单个节点」
 * 的 tag + 属性，祖先链无法表达，因此这里只保留最后一个片段中可表达的约束：
 * - 支持：tag、`[id='…']`、`[name='…']`、`[type='…']`、`[role='…']`、`#id` 简写；
 * - 丢弃：`.class` 限定符、以及 `[placeholder=…]` / `[autocomplete=…]` 等无法表达的属性
 *   —— 若只是丢掉它们而保留 tag，会退化成「匹配一切同 tag 元素」，故整条选择器作废。
 */
package io.vaultix.vaultix.autofill.fillassist

/** 单条 CSS 选择器 → [SelectorClause]；无法安全表达时返回 null。 */
object FillAssistSelectorParser {

    /** `[attr='value']` 与 `[attr="value"]` 两种引号。 */
    private val ATTRIBUTE_REGEX = Regex("""\[([a-zA-Z\-]+)=['"](.*?)['"]]""")

    /** CSS 的 `#id` 简写（如 `input#oid`），仅在没有 `[id='…']` 时兜底。 */
    private val ID_SHORTHAND_REGEX = Regex("""#([^.\[#\s]+)""")

    /** 选择器开头的标签名（如 `input` / `select` / `form`）。 */
    private val TAG_REGEX = Regex("""^([a-zA-Z][a-zA-Z0-9]*)""")

    /** `.class` 限定符；负向先行确保不会把属性值里的 `.`（如 `[title='Email.']`）误判。 */
    private val CLASS_QUALIFIER_REGEX = Regex("""\.[a-zA-Z][\w-]*(?![^\[]*])""")

    /** 后代选择器分隔空白；属性值内的空白不参与切分。 */
    private val DESCENDANT_SEPARATOR_REGEX = Regex("""\s+(?![^\[]*])""")

    /** Shadow DOM 边界标记（`>>>`）：其后的部分才是可表达的目标元素。 */
    private const val SHADOW_BOUNDARY = ">>>"

    private const val ATTR_ID = "id"
    private const val ATTR_NAME = "name"
    private const val ATTR_TYPE = "type"
    private const val ATTR_ROLE = "role"

    /**
     * 解析一条选择器。
     *
     * 只取**最后一个片段**：前面的部分描述祖先节点，而框架不把它们表示为 ViewNode。
     * `>>>`（shadow 边界）先剥掉——它同样不可表达，且其后仍可能含祖先片段。
     */
    fun parse(selector: String): SelectorClause? {
        val afterShadowBoundary = selector.substringAfterLast(SHADOW_BOUNDARY).trim()
        val effective = afterShadowBoundary.split(DESCENDANT_SEPARATOR_REGEX).last().trim()
        // 纯 class 选择器（`.foo`）没有任何可表达约束 → 作废。
        if (effective.startsWith(".")) return null

        val tag = TAG_REGEX.find(effective)?.groupValues?.get(1)
        var id: String? = null
        var name: String? = null
        var type: String? = null
        var role: String? = null
        var hasUnsupportedAttribute = false

        ATTRIBUTE_REGEX.findAll(effective).forEach { match ->
            val attrName = match.groupValues[1]
            val attrValue = match.groupValues[2]
            when (attrName) {
                ATTR_ID -> id = attrValue
                ATTR_NAME -> name = attrValue
                ATTR_TYPE -> type = attrValue
                ATTR_ROLE -> role = attrValue
                // 无法表达为约束的属性（autocomplete / placeholder…）要记下来，
                // 以免下面误退化成「只按 tag 匹配」。
                else -> hasUnsupportedAttribute = true
            }
        }
        if (id == null) id = ID_SHORTHAND_REGEX.find(effective)?.groupValues?.get(1)

        val hasClassQualifier = CLASS_QUALIFIER_REGEX.containsMatchIn(effective)
        val noAttributeConstraints =
            hasNoAttributeConstraints(id = id, name = name, type = type, role = role)
        if ((hasUnsupportedAttribute || hasClassQualifier) && noAttributeConstraints) return null

        return SelectorClause(tag = tag, id = id, name = name, type = type, role = role)
    }

    private fun hasNoAttributeConstraints(
        id: String?,
        name: String?,
        type: String?,
        role: String?,
    ): Boolean = id == null && name == null && type == null && role == null
}
