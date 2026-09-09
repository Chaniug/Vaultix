/*
 * Vaultix — app:autofill · match
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * 网址匹配工具，对齐 Bitwarden login.uris[i].match 的五种规则
 * （Domain / Host / StartsWith / Exact / RegularExpression，GPL-3.0，Copyright 2025 JoyinJoester）。
 * 纯函数、无 Android 依赖，便于 JVM 单测。
 */
package io.vaultix.vaultix.autofill.match

import io.vaultix.model.UriMatch
import java.net.URI

/** 单个网址的匹配规则实现与主机提取。 */
object UriMatcher {

    /**
     * 从网址串提取主机名（忽略 scheme 与端口，小写）。
     * `androidapp://` 包名 Uri 不表示域名，返回 null。
     */
    internal fun hostOf(rawUri: String): String? {
        val uri = rawUri.trim()
        if (uri.isEmpty()) return null
        if (uri.startsWith("androidapp://", ignoreCase = true)) return null
        val withScheme = if (uri.contains("://")) uri else "https://$uri"
        return try {
            val parsed = URI(withScheme)
            val host = parsed.host ?: return null
            host.lowercase().ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    /** 精确主机匹配（含子域全等）。 */
    fun hostMatch(credHost: String, targetHost: String): Boolean = credHost == targetHost

    /** 基域匹配（忽略子域）。 */
    fun baseDomainMatch(credHost: String, targetHost: String): Boolean =
        PublicSuffixList.baseDomain(credHost) == PublicSuffixList.baseDomain(targetHost)

    /** 前缀匹配：目标网址以保存网址为前缀。 */
    fun startsWithMatch(credUri: String, targetUri: String): Boolean = targetUri.startsWith(credUri)

    /** 精确匹配：保存网址与目标完全一致。 */
    fun exactMatch(credUri: String, targetUri: String): Boolean = credUri == targetUri

    /** 正则匹配（非法正则按不匹配处理）。 */
    fun regexMatch(pattern: String, targetUri: String): Boolean =
        try {
            Regex(pattern).containsMatchIn(targetUri)
        } catch (_: Exception) {
            false
        }

    /**
     * 按 [UriMatch] 规则判断 [credUri] 是否匹配 [targetUri]。
     * 域名类规则先提取主机再比对；非域名类规则直接比对整串。
     */
    fun matchByRule(credUri: String, targetUri: String, rule: UriMatch): Boolean =
        when (rule) {
            UriMatch.Domain -> {
                val c = hostOf(credUri)
                val t = hostOf(targetUri)
                c != null && t != null && baseDomainMatch(c, t)
            }
            UriMatch.Host -> {
                val c = hostOf(credUri)
                val t = hostOf(targetUri)
                c != null && t != null && hostMatch(c, t)
            }
            UriMatch.StartsWith -> startsWithMatch(credUri, targetUri)
            UriMatch.Exact -> exactMatch(credUri, targetUri)
            UriMatch.RegularExpression -> regexMatch(credUri, targetUri)
            UriMatch.Never -> false
        }
}
