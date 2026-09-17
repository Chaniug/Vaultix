/*
 * Vaultix — data:repository
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * **WebDAV 请求 URL 的规范化与拼接**。
 *
 * ## ★ 核心约定：子路径永远从「已验证可用的 base」拼，**不用服务器回显的 `href`**
 *
 * PROPFIND 的 `<D:href>` 是**绝对路径**（RFC 4918），但它反映的是**服务器内部**的
 * 视角。真实部署里两者经常对不上：
 *
 * ```
 *   用户填的 base ： https://nas.local:5006/vaultix-dav/     ← 反向代理后的入口，可用
 *   服务器回显的   ： /remote.php/dav/files/alice/           ← 代理背后的真实路径，不可直连
 * ```
 *
 * 直接拿 `href` 去请求 ⇒ 404（甚至打到别人的服务上）。而
 * `base + 名字` 一定打在我们**刚刚证明能用**的那个入口上 —— 代理、别名、
 * 子路径挂载全都自动正确。
 *
 * ⚠️ 代价要说清楚：这假设「服务器给的 displayname 就是 URL 里的那一段」。
 * 绝大多数 NAS / Nextcloud / AList 都成立，取向上游 Bastion 的同款做法
 * （它已在这条链路上跑了很久）。
 *
 * ## 为什么每段单独编码，而不是把整串 URL 拿去 encode
 *
 * `URLEncoder.encode` 会把 `/` 也编成 `%2F`，整条路径就变成一个段名了。
 * 这里用 OkHttp 的 `addPathSegment`：它按 RFC 3986 逐段编码、保留 `/` 作分隔符，
 * 中文与空格因此都正确（NAS 上用中文目录名很常见）。
 *
 * ## 为什么要先去掉 base 的尾斜杠
 *
 * `HttpUrl` 会把 `.../dav/` 解析成带一个**空段**的路径。直接 `addPathSegment("x")`
 * 会得到 `.../dav//x`（双斜杠）—— 部分服务器会对这种路径 404。
 * 统一先剥掉尾斜杠，拼接的语义就唯一了。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.repository.kdbx

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** WebDAV 的 URL 处理（规范化 / 逐段拼接）。 */
object WebDavUrlBuilder {

    private const val HTTPS_SCHEME = "https://"
    private const val SCHEME_SEPARATOR = "://"

    /**
     * 规范化用户输入的服务器地址。
     *
     * - 去首尾空白；
     * - **补 scheme**（缺省 `https://`）—— 用户只输 `nas.local` 是常态，
     *   非要他手打协议头是没必要的摩擦；
     * - **剥掉 path 末尾的 `/`**（保证幂等，拼接时不产生双斜杠）。
     *
     * 不解析时原样返回（宁可让后续请求失败并给出真实错误，也不要在这一步假装成功）。
     */
    fun normalizeServer(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val withScheme = if (trimmed.contains(SCHEME_SEPARATOR)) trimmed else HTTPS_SCHEME + trimmed
        return withScheme.trimEnd('/')
    }

    /**
     * 把 [child] 作为**相对片段**接到 [base] 后面（逐段 percent-encode）。
     *
     * @param child 允许含 `/`（多级）与首尾斜杠；空段会被忽略（避免 `//`）。
     * @return null = [base] 不是一个能解析的 URL（调用方据此报"服务器地址不对"，
     *   而不是拼出一个必然 404 的字符串）。
     */
    fun join(base: String, child: String): String? {
        val normalizedBase = normalizeServer(base)
        if (normalizedBase.isEmpty()) return null
        val url = normalizedBase.toHttpUrlOrNull() ?: return null

        val segments = child.replace('\\', '/').trim('/')
            .split('/')
            .filter { it.isNotEmpty() }
        if (segments.isEmpty()) return url.toString().trimEnd('/')

        val builder = url.newBuilder()
        segments.forEach { builder.addPathSegment(it) }
        return builder.build().toString().trimEnd('/')
    }

    /**
     * 子**目录**的完整 URL（**带尾斜杠**）。
     *
     * ⚠️ 尾斜杠不是装饰：`WebDavKdbxFileSource.listChildren()` 内部会用
     * `directoryUrl()` 砍掉最后一段来得到 PROPFIND 的目标，而它对已带尾斜杠的
     * URL 是幂等的。少了这个斜杠，PROPFIND 会打在**上一级**，
     * 用户明明进了子目录却看到父目录的内容。
     */
    fun joinDirectory(base: String, child: String): String? =
        join(base, child)?.let { "$it/" }
}
