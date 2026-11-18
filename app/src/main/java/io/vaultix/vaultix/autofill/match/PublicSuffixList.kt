/*
 * Vaultix — app:autofill · match
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * 基域（registrable domain / eTLD+1）计算，对齐 Bitwarden 对公共后缀列表（PSL）的使用
 * （GPL-3.0，Copyright 2025 JoyinJoester）。规则数据来自 Mozilla Public Suffix List，
 * 由 PublicSuffixData 内联（exact / wildcard / exception 三类），算法遵循 PSL 官方规范：
 * 取最长匹配规则；例外规则（!）命中时公共后缀再上移一段。
 */
package io.vaultix.vaultix.autofill.match

/** 公共后缀计算（基域 / registrable domain）。 */
object PublicSuffixList {

    private val exactRules: Set<String> = PublicSuffixData.PSL_EXACT
        .lineSequence().filter { it.isNotBlank() }.toSet()
    private val wildcardRules: Set<String> = PublicSuffixData.PSL_WILDCARD
        .lineSequence().filter { it.isNotBlank() }.toSet()
    private val exceptionRules: Set<String> = PublicSuffixData.PSL_EXCEPTION
        .lineSequence().filter { it.isNotBlank() }.toSet()

    // 单段主机（localhost / 内网名）直接视为基域。
    private const val MIN_LABELS_FOR_REGISTRABLE_DOMAIN = 2
    // 无规则命中时的隐式公共后缀长度（仅取最后一段 TLD，基域即末两段）。
    private const val IMPLICIT_SUFFIX_LABEL_COUNT = 1
    // 例外规则命中后，公共后缀相对规则再上移的段数（去掉最左 label）。
    private const val EXCEPTION_LABEL_TRIM = 1

    // IPv4 字面量：4 段、每段至多 3 位数字且不超过 255。
    private const val IPV4_LABEL_COUNT = 4
    private const val IPV4_MAX_LABEL_CHARS = 3
    private const val IPV4_MAX_LABEL_VALUE = 255

    /**
     * 返回 [host] 的基域（注册域 / eTLD+1）。
     * 例：`sub.example.com` → `example.com`；`sub.example.co.uk` → `example.co.uk`；
     * `myapp.compute.amazonaws.com` → `myapp.compute.amazonaws.com`（compute.amazonaws.com 为公共后缀）。
     * 不足两段时原样返回；自动忽略前导 `www.`；IPv4 字面量原样返回（见 [isIpv4Literal]）。
     */
    fun baseDomain(host: String): String {
        val h = host.lowercase().removePrefix("www.")
        if (isIpv4Literal(h)) return h
        val labels = h.split('.')
        if (labels.size < MIN_LABELS_FOR_REGISTRABLE_DOMAIN) return h
        val suffixLength = prevailingSuffixLength(labels)
        if (labels.size <= suffixLength) return h
        val start = labels.size - suffixLength - 1
        return labels.subList(start, labels.size).joinToString(".")
    }

    /**
     * 是否是 IPv4 字面量。
     *
     * ⚠️ **不拦这一步，内网设备会互相填密码**（2026-11-18 实测）：
     * `192.168.1.1` 与 `172.16.1.1` 的「基域」都会被算成 `1.1` ⇒ `baseDomainMatch` 为真。
     * Bitwarden 的 `parseDomainOrNull` 对 IP 直接返回 host 本身，Bastion 另有
     * `AutofillHostPolicy.isIp` 守卫；这里按同样口径处理。
     */
    private fun isIpv4Literal(host: String): Boolean {
        val labels = host.split('.')
        if (labels.size != IPV4_LABEL_COUNT) return false
        return labels.all { label ->
            if (label.isEmpty() || label.length > IPV4_MAX_LABEL_CHARS) return@all false
            if (label.any { ch -> ch !in '0'..'9' }) return@all false
            (label.toIntOrNull() ?: return@all false) <= IPV4_MAX_LABEL_VALUE
        }
    }

    /**
     * 求 [labels] 的「公共后缀」段数：
     * 在全部匹配规则中取段数最多者；段数相同时例外规则胜出。
     * 例外规则命中时，公共后缀 = 规则段数 - [EXCEPTION_LABEL_TRIM]。
     * 无任何规则命中时回退到隐式长度（仅末段 TLD）。
     */
    private fun prevailingSuffixLength(labels: List<String>): Int {
        var bestRuleLength = 0
        var bestIsException = false
        for (i in labels.indices) {
            val suffix = labels.subList(i, labels.size).joinToString(".")
            val ruleLength = labels.size - i
            val isException = exceptionRules.contains(suffix)
            val isExact = exactRules.contains(suffix)
            val isWildcard = i + 1 < labels.size &&
                wildcardRules.contains(labels.subList(i + 1, labels.size).joinToString("."))
            if (!isException && !isExact && !isWildcard) continue
            val longer = ruleLength > bestRuleLength
            val tieExceptionWins = ruleLength == bestRuleLength && isException && !bestIsException
            if (longer || tieExceptionWins) {
                bestRuleLength = ruleLength
                bestIsException = isException
            }
        }
        if (bestRuleLength == 0) return IMPLICIT_SUFFIX_LABEL_COUNT
        return bestRuleLength - if (bestIsException) EXCEPTION_LABEL_TRIM else 0
    }
}
