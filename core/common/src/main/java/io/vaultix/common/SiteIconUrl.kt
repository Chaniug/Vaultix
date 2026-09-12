/*
 * Vaultix — core:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 端点与拼接规则取自 Bitwarden Android 官方客户端（GPL-3.0，Copyright Bitwarden Inc.）：
 *   - `data/.../util/EnvironmentExtensions.kt:113-126`：自建服务器的图标基址是
 *     `<base>/icons`（未单独配置 icon 时），官方服务器为 `https://icons.bitwarden.net`；
 *   - 条目侧路径 `<baseIconUrl>/<host>/icon.png`（其 `VaultItemListingDataUtil` /
 *     `SearchUtil` 夹具实证：`https://vault.bitwarden.com/icons/www.mockuri.com/icon.png`）。
 * Vaultwarden 同端点且带服务端缓存。本文件为独立实现的**纯字符串**拼接（无网络、无 Android），
 * 便于 JVM 单测。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.common

import java.net.URI
import java.util.Locale

/**
 * 站点图标 URL 解析（`<服务器>/icons/<域名>/icon.png`）。
 *
 * ## 为什么要有独立的纯函数对象
 * 图标能否取到**只取决于 URL 拼得对不对**，而拼接要处理一堆边界：
 * 服务器地址带不带斜杠 / 带不带子路径、条目 URI 有 `www.` 前缀与否、
 * `androidapp://` 与 `iosapp://` 这类非网址绑定、KDBX 库的 `content://` origin…
 * 把它做成纯函数就能用单测把这些边界一次性钉死，而不是靠真机一张张看。
 */
object SiteIconUrl {

    /** 图标端点路径段（Bitwarden / Vaultwarden 一致）。 */
    const val ICONS_PATH = "icons"

    /** 图标文件名（两个上游实现都用 `icon.png`）。 */
    const val ICON_FILE = "icon.png"

    /**
     * 由「库的服务器地址」+「站点域名」拼图标 URL。
     *
     * @param serverOrigin 库的 origin（Bitwarden 库是 `https://vault.example.com`；
     *   KDBX 库是 `content://...` → 返回 null）。
     * @param host 站点域名（见 [hostOfItemUri]）。
     * @return 可请求的 https URL；任一输入不合法（非 http(s) / 空域名 / 域名里带非法字符）
     *   返回 null —— **调用方据此直接走首字母兜底，不要发一个必然 404 的请求**。
     */
    fun forHost(serverOrigin: String?, host: String?): String? {
        val base = httpOriginOf(serverOrigin) ?: return null
        val domain = sanitizeHost(host) ?: return null
        return "$base/$ICONS_PATH/$domain/$ICON_FILE"
    }

    /**
     * 从一串 URI 里取出用于图标的域名（第一条**可解析为网站**的）。
     *
     * 刻意收 `List<String>` 而不是 `List<VaultUri>`：本对象属 core:common，不依赖
     * core:model —— 调用点一行 `item.uris.map { it.uri }` 即可，换来的是一个纯字符串
     * 工具（JVM 单测不需要任何领域模型夹具）。
     *
     * 只认第一条网站形态：Bitwarden 的登录条目常同时挂着 `androidapp://...` 与网址，
     * 取错就会拿包名去问图标服务。
     */
    fun hostOfItemUris(uris: List<String>): String? = uris
        .asSequence()
        .mapNotNull { hostOfUri(it) }
        .firstOrNull()

    /** 单个 uri → 域名（非网站形态返回 null）。 */
    fun hostOfUri(uri: String?): String? {
        val raw = uri?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val lower = raw.lowercase(Locale.ROOT)
        // 非网址绑定（应用 / iOS / 其它 scheme）一律不参与图标
        if (lower.startsWith("androidapp://") || lower.startsWith("android-app://")) return null
        if (lower.startsWith("iosapp://")) return null
        val parsed = runCatching {
            URI(if (lower.contains("://")) raw else "https://$raw")
        }.getOrNull() ?: return null
        // 只认 http(s)：`content://` / `file://` 之类没有站点图标可言
        val scheme = parsed.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return null
        return sanitizeHost(parsed.host)
    }

    /**
     * 服务器地址 → `scheme://host[:port]`（去掉路径 / 查询 / 末尾斜杠）。
     *
     * **必须剥掉路径**：`https://example.com/vault` 这类反代部署下，
     * `https://example.com/vault/icons/...` 通常并不是图标端点（图标端点在站点根），
     * 而多拼一段路径只会稳定 404。
     *
     * ⚠️ 只接受 **https**：明文 http 会被 Android 的 cleartext 策略拦掉
     * （表现为「图标永远加载失败但没有任何报错」），不如直接返回 null 走兜底。
     */
    fun httpOriginOf(serverOrigin: String?): String? {
        val raw = serverOrigin?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val parsed = runCatching { URI(raw) }.getOrNull() ?: return null
        if (!parsed.scheme.equals("https", ignoreCase = true)) return null
        // 主机统一小写：主机名大小写不敏感，而缓存键是「整个 URL 字符串」——
        // 不归一就会出现 `https://Vault.x.com/...` 与 `https://vault.x.com/...`
        // 两份互不命中的缓存（同一张图下载两次）。
        val host = parsed.host?.takeIf { it.isNotBlank() }?.lowercase(Locale.ROOT) ?: return null
        val port = if (parsed.port > 0) ":${parsed.port}" else ""
        return "https://$host$port"
    }

    /**
     * 域名净化：只留字母 / 数字 / `.` / `-`（含 punycode 的 `xn--`）。
     *
     * 域名会被**直接拼进 URL 路径**，出现 `/` `?` `#` `..` 等字符就能改写请求目标
     * （路径穿越 / 请求到别的端点）。这里做白名单过滤，不合格一律返回 null。
     */
    private fun sanitizeHost(host: String?): String? {
        val value = host?.trim()?.trimEnd('.')?.lowercase(Locale.ROOT).orEmpty()
        if (value.isEmpty() || value.length > MAX_HOST_LENGTH) return null
        if (!value.contains('.')) return null
        val allowed = value.all { it.isLetterOrDigit() || it == '.' || it == '-' }
        if (!allowed) return null
        if (value.startsWith('.') || value.contains("..")) return null
        return value
    }

    /** 域名长度上限（RFC 1035 的 253 已含点；留一点余量即可）。 */
    private const val MAX_HOST_LENGTH = 253
}
