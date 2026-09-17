/*
 * Vaultix — data:repository
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * **WebDAV 来源的 origin 编解码**。
 *
 * ## 格式
 *
 * ```
 * webdav:<credentialId>:<percentEncodedFileUrl>
 * ```
 *
 * ## ★ 为什么是三段（为什么不能只存 URL）
 *
 * 用户的 WebDAV 凭据（账号密码）**绝不能进 Room**（方案 §10 的安全约定）。
 * 但"重新打开 App 后要能连上这台服务器"又必须有凭据。
 * ⇒ 拆开：
 *   - **origin（进 Room）**：只存 `credentialId`（一个索引）+ 文件 URL；
 *   - **凭据本体（进 `SecureCredentialStore`）**：以 `credentialId` 为 key 存
 *     账号密码（Keystore 包装的 AES-GCM）。
 *
 * 这样 origin 字符串即使被导出 / 出现在日志里，**也只是一个 URL 加一个不含秘密的 id**。
 *
 * ## ⚠️ URL 里绝不能嵌凭据
 *
 * `https://user:pass@host/dav/x.kdbx` 这种写法在 WebDAV 场景下很常见（curl 支持），
 * 但它会让密码进入：日志、崩溃报告、OkHttp 的 `request.url.toString()`、代理记录。
 * ⇒ 本文件**显式拒绝**含 `@` 的 userinfo（见 [parse] 的校验），
 *   遇到这种 URL 就当作不可用，而不是"帮用户先用着"。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.repository.kdbx

/** WebDAV 来源的 origin 编解码（格式见文件头）。 */
object WebDavVaultOrigin {

    const val PREFIX: String = "webdav:"

    /** 是否是 WebDAV 来源的 origin（**只看前缀**，不解析）。 */
    fun matches(origin: String): Boolean = origin.startsWith(PREFIX)

    fun build(credentialId: String, fileUrl: String): String =
        "$PREFIX$credentialId:${encode(fileUrl.trim())}"

    fun parse(origin: String): Parsed? {
        if (!matches(origin)) return null
        val rest = origin.removePrefix(PREFIX)
        val separator = rest.indexOf(':')
        if (separator <= 0) return null
        val credentialId = rest.substring(0, separator)
        val fileUrl = runCatching { decode(rest.substring(separator + 1)) }.getOrNull()
        if (credentialId.isBlank() || fileUrl.isNullOrBlank()) return null
        if (!isSupportedScheme(fileUrl)) return null
        // ★ 拒绝把凭据藏在 URL 里的写法 —— 见文件头说明。
        val afterScheme = fileUrl.substringAfter("://", missingDelimiterValue = "")
        val authority = afterScheme.substringBefore('/')
        if (authority.contains('@')) return null
        return Parsed(credentialId = credentialId, fileUrl = fileUrl)
    }

    data class Parsed(val credentialId: String, val fileUrl: String)

    /**
     * ⚠️ 只允许 `https`（与 `http`，但后者会被 [requireSecureScheme] 拦下）。
     *
     * 不接受 `file://` 等 scheme：origin 是**用户可编辑的配置**，
     * 放开 scheme 等于给了一个"指向任意本地路径"的入口。
     */
    private fun isSupportedScheme(url: String): Boolean =
        url.startsWith("https://", ignoreCase = true) || url.startsWith("http://", ignoreCase = true)

    /** 明文 HTTP 只允许在"用户明确接受风险"时使用（局域网 NAS 的常见情况）。 */
    fun requireSecureScheme(fileUrl: String): Boolean = fileUrl.startsWith("https://", ignoreCase = true)

    private fun encode(url: String): String = java.net.URLEncoder.encode(url, "UTF-8")

    private fun decode(url: String): String = java.net.URLDecoder.decode(url, "UTF-8")
}
