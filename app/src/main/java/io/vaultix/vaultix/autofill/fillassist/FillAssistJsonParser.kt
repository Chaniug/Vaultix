/*
 * Vaultix — app:autofill · fillassist
 * Copyright (C) 2026 Vaultix contributors
 *
 * 规则表 JSON 解析。移植自 Bitwarden `FillAssistManagerImpl.parseForms` /
 * `parseCompositeSelectorArray`（GPL-3.0，© Bitwarden Inc.）。
 *
 * 线上文件形态：
 * - `manifest.json` → `maps.forms["v1"] = { filename, cid, schema }`
 * - `forms.v1.json` → `{ schemaVersion, hosts: { <host>: { forms: [...], pathnames: {...} } } }`
 *
 * 主版本不匹配（非 `1.x`）时**整体放弃**——宁可不用规则，也不要用错语义的选择器。
 */
package io.vaultix.vaultix.autofill.fillassist

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** manifest / forms 的纯解析器。 */
object FillAssistJsonParser {

    /** manifest 里 `forms.v1` 那一项。 */
    data class ManifestEntry(val filename: String, val cid: String)

    /** 线上 forms 的版本键；与客户端常量一致。 */
    private const val FORMS_VERSION_KEY = "v1"
    private const val EXPECTED_SCHEMA_MAJOR = "1"
    private const val KEY_MAPS = "maps"
    private const val KEY_FORMS = "forms"
    private const val KEY_HOSTS = "hosts"
    private const val KEY_PATHNAMES = "pathnames"
    private const val KEY_CATEGORY = "category"
    private const val KEY_FIELDS = "fields"
    private const val KEY_FILENAME = "filename"
    private const val KEY_CID = "cid"
    private const val KEY_SCHEMA_VERSION = "schemaVersion"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 解析 manifest；结构不符时返回 null（调用方跳过本次刷新）。 */
    fun parseManifest(raw: String): ManifestEntry? {
        val root = parseObject(raw) ?: return null
        val entry = root[KEY_MAPS]
            ?.asObject()
            ?.get(KEY_FORMS)
            ?.asObject()
            ?.get(FORMS_VERSION_KEY)
            ?.asObject()
            ?: return null
        val filename = entry[KEY_FILENAME].asString() ?: return null
        val cid = entry[KEY_CID].asString().orEmpty()
        return ManifestEntry(filename = filename, cid = cid)
    }

    /** 解析 forms；schema 主版本不符或结构异常时返回 null。 */
    fun parseForms(raw: String): FillAssistRules? {
        val root = parseObject(raw) ?: return null
        val schemaMajor = root[KEY_SCHEMA_VERSION].asString()?.substringBefore('.')
        if (schemaMajor != EXPECTED_SCHEMA_MAJOR) return null
        val hosts = root[KEY_HOSTS]?.asObject() ?: return null
        val hostRules = hosts.mapNotNull { (host, hostEntry) ->
            val rules = hostEntry.asObject()?.let(::parseHostEntry).orEmpty()
            if (rules.isEmpty()) null else host to rules
        }
        return FillAssistRules(hostRules.toMap())
    }

    /** 一个主机下可能有多份表单（含 `pathnames` 下的路径级表单），按类别合并。 */
    private fun parseHostEntry(entry: JsonObject): List<HostRule> {
        val allForms = buildList {
            addAll(entry[KEY_FORMS].asArray().orEmpty())
            entry[KEY_PATHNAMES]?.asObject()?.values?.forEach { pathEntry ->
                addAll(pathEntry.asObject()?.get(KEY_FORMS).asArray().orEmpty())
            }
        }.distinct()

        val grouped = mutableMapOf<String, MutableMap<String, MutableList<SelectorClause>>>()
        allForms.mapNotNull { it.asObject() }.forEach { collectFields(it, grouped) }
        return grouped.map { (category, fields) ->
            HostRule(category = category, fields = fields.mapValues { it.value.distinct() })
        }
    }

    /** 把一份表单里的字段选择器并入 [grouped]；结构不符时提前返回。 */
    private fun collectFields(
        form: JsonObject,
        grouped: MutableMap<String, MutableMap<String, MutableList<SelectorClause>>>,
    ) {
        val category = form[KEY_CATEGORY].asString() ?: return
        val fields = form[KEY_FIELDS]?.asObject() ?: return
        for ((fieldKey, element) in fields) {
            val clauses = parseCompositeSelectors(element)
            if (clauses.isEmpty()) continue
            grouped.getOrPut(category) { mutableMapOf() }
                .getOrPut(fieldKey) { mutableListOf() }
                .addAll(clauses)
        }
    }

    /**
     * 字段的选择器既可能是字符串，也可能是 `["a"]` / `["a","b"]`（同一字段的多条备选）。
     * 逐层展开后**逐条**解析，无法安全表达的条目在此被丢弃。
     */
    private fun parseCompositeSelectors(element: JsonElement): List<SelectorClause> {
        val array = element as? JsonArray ?: return emptyList()
        return array.flatMap { item ->
            when (item) {
                is JsonPrimitive -> listOfNotNull(FillAssistSelectorParser.parse(item.content))
                is JsonArray -> item
                    .filterIsInstance<JsonPrimitive>()
                    .mapNotNull { FillAssistSelectorParser.parse(it.content) }
                else -> emptyList()
            }
        }
    }

    private fun parseObject(raw: String): JsonObject? =
        runCatching { json.parseToJsonElement(raw) }.getOrNull().asObject()

    private fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

    private fun JsonElement?.asArray(): List<JsonElement>? = (this as? JsonArray)?.toList()

    private fun JsonElement?.asString(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content
}
