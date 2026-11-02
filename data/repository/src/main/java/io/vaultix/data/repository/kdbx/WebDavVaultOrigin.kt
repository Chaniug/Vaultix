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

    /**
     * URL → origin 片段（写入侧）。
     *
     * ## ❌ `-encode`（旧）→ ✅ `+encode`（现行，2026-11-02）
     *
     * origin 进的是 `vaults.id` / `vaults.origin`（Room / SQLite）。而 **SQLite 的
     * `trim()` 不认 `\t` `\n` `\r`（只认空格），`LIKE` 的通配又是 `%` `_`** ——
     * 一旦片段里出现这几个字符，最坏的结果不是"查不到"，而是**查到别人那一行**。
     *
     * 直白的做法（把片段里的 `%` `_` 也换成 `%25` `%5F`）**不成立**：解码有
     * `URLDecoder` 与 base64 两条后备，`%255F` 第一遍变 `%5F`、第二遍才成 `_` ——
     * 中间态会被当成转义处理，还原不回来。
     * ⇒ 改用**根本没有这些字符的字母表**：base64url 只剩 `A-Za-z0-9-_`。
     *   这才是"换一个不会撞的表示"，而不是"给撞的那几个打补丁"。
     *
     * ⚠️ 旧 origin（`-encode`：中文是 `%E4%B8%AD`，空格是 `+`）**必须继续能读** ——
     *   用户库里已经存着它们（[decode] 的后备就是为这个留的）。
     */
    private fun encode(url: String): String =
        java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(url.toByteArray(Charsets.UTF_8))

    /**
     * 旧写法的编码（`URLEncoder`）—— **只给单测用**，用来证明老 origin 还能读。
     *
     * ⚠️ 生产代码**不得**再用它：`%` `_` 会原样留在 origin 里（见 [encode] 的说明）。
     * 留在这里而不是在测试里手写一份，是因为"old 形态"的定义必须与 [decode] 的兼容
     * 承诺绑在一起 —— 两处各写一份，下次改动就会分叉。
     */
    internal fun buildLegacyForTest(credentialId: String, fileUrl: String): String =
        "$PREFIX$credentialId:${java.net.URLEncoder.encode(fileUrl.trim(), "UTF-8")}"

    /**
     * origin 片段 → URL（读取侧，**两种形态都要认**）。
     *
     * ## ⚠️ 顺序：先 base64，再 `URLDecoder`
     *
     * 现行形态是 base64url，**必须先试它**：`%` 开头的旧形态解不成合法 base64url
     *（字母表里没有 `%`），所以"先 base64"不会误吞老 origin。
     *
     * ⚠️ 反过来、只用 `URLDecoder` 会**当场解错**：它按表单规则把 `+` 当空格。
     * 旧 origin 里空格确实是 `+`（编码端也是 `URLEncoder`，自洽），但 base64url 的
     * 字母表本身带 `-` 与 `_`，两种形态混在一起时没有任何一处能"猜"对 ——
     * 显式分支是唯一可靠的做法。
     *
     * ⚠️ 后备的判据是"解出来像不像一个 URL（含 `://`）"，而不是"base64 解码有没有抛"：
     * 大多数旧片段也能被"成功"解成**一堆乱码字节**，只看异常会把乱码当成结果。
     */
    private fun decode(payload: String): String {
        val fromBase64 = runCatching {
            java.util.Base64.getUrlDecoder().decode(payload).toString(Charsets.UTF_8)
        }.getOrNull()?.takeIf { it.contains("://") }
        if (fromBase64 != null) return fromBase64
        return java.net.URLDecoder.decode(payload, "UTF-8")
    }
}
