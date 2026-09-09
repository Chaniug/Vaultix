/*
 * Vaultix — app:autofill · save
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * URI 归一与更新判定思路参考 Bastion `AutofillSaveActivity`（GPL-3.0，
 * Copyright 2025 JoyinJoester）与 Bitwarden 保存流程：网页存 `https://host`、
 * App 存 `androidapp://包名`；已存在「同 URI + 同账号」条目时提示更新而非新建。
 */
package io.vaultix.vaultix.autofill.save

import io.vaultix.common.UriFormat
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import io.vaultix.vaultix.autofill.match.UriMatcher
import java.util.Locale

/** 保存流程的纯判定（无 Android 框架依赖，可 JVM 单测）。 */
object AutofillSaveMatcher {

    /**
     * 本次保存要写入条目的 URI：
     * - 网页：`https://<host>`（浏览器场景用地址栏/结构兜底域名同样生效）；
     * - 原生 App：`androidapp://<package>`（Bitwarden 官方形态，其它客户端也认）；
     * - 两者都没有 → null（仍然可以保存，只是条目不带 URI，后续无法自动匹配）。
     */
    fun targetUri(webDomain: String?, packageName: String?): String? {
        val host = webDomain?.trim()?.takeIf { it.isNotBlank() }
        if (host != null) return "https://${host.lowercase(Locale.ROOT)}"
        val pkg = packageName?.trim()?.takeIf { it.isNotBlank() }
        return pkg?.let { UriFormat.androidAppUri(it) }
    }

    /**
     * 找「同一站点 / 同一 App + 同一账号」的既有条目（Bitwarden 保存时的更新判定）：
     * 命中即提示「更新密码」，避免同一账号反复存出重复条目。
     *
     * 判定顺序：URI 相同（基域口径）且账号相同 → 仅账号相同（URI 缺失时兜底）。
     * 账号为空时不按账号匹配（否则会把所有无名条目当成同一个）。
     */
    fun findExisting(items: List<VaultItem>, uri: String?, username: String): VaultItem? {
        val candidates = items.filter { it.type == VaultItemType.Login }
        val name = username.trim()
        val byUriAndName = candidates.firstOrNull { item ->
            sameUri(item, uri) && name.isNotBlank() && item.username.equals(name, ignoreCase = true)
        }
        if (byUriAndName != null) return byUriAndName
        if (name.isBlank() || uri.isNullOrBlank()) return null
        return candidates.firstOrNull { item ->
            item.username.equals(name, ignoreCase = true) && item.uris.isEmpty()
        }
    }

    /** 默认条目名：优先域名（去掉 www.），其次应用包名。 */
    fun defaultTitle(webDomain: String?, packageName: String?, appLabel: String?): String {
        val host = webDomain?.trim()?.takeIf { it.isNotBlank() }
        if (host != null) return host.lowercase(Locale.ROOT).removePrefix("www.")
        val label = appLabel?.trim()?.takeIf { it.isNotBlank() }
        if (label != null) return label
        return packageName.orEmpty()
    }

    private fun sameUri(item: VaultItem, uri: String?): Boolean {
        if (uri.isNullOrBlank()) return false
        return item.uris.any { saved -> uriEquals(saved.uri, uri) }
    }

    /** 基域口径相等（与匹配器一致）：`https://a.example.com` 与 `example.com` 视为同一站点。 */
    private fun uriEquals(left: String, right: String): Boolean {
        if (left.equals(right, ignoreCase = true)) return true
        val l = UriMatcher.hostOf(left) ?: return false
        val r = UriMatcher.hostOf(right) ?: return false
        return UriMatcher.baseDomainMatch(l, r)
    }
}
