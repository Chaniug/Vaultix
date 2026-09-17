/*
 * Vaultix — data:repository（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.data.repository.kdbx

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * WebDAV 的 URL 规范化与拼接。
 *
 * ## 为什么这几条值得单独测
 *
 * 拼接的每一处都对应一个**用户可见且极难自查**的失败：
 *
 * | 拼接错了 | 用户看到的现象 |
 * |---|---|
 * | 双斜杠（`/dav//x`） | 部分服务器 404 ⇒ "这个文件明明在，就是打不开" |
 * | 少了尾斜杠 | PROPFIND 打在**上一级** ⇒ 进了子目录却看到父目录内容 |
 * | 中文 / 空格没按段编码 | 404，而日志里的 URL 看起来完全正常（肉眼分不出全角半角） |
 * | `normalizeServer` 不幂等 | 第二次进同一目录时 URL 变长一截 |
 *
 * 上游 Bastion 的 `WebDavUrlBuilderTest` 也是因为同样的理由存在。
 */
class WebDavUrlBuilderTest {

    // ---------------------------------------------------------------- 规范化

    @Test
    fun `normalizeServer defaults a missing scheme to https`() {
        assertThat(WebDavUrlBuilder.normalizeServer("nas.local/dav"))
            .isEqualTo("https://nas.local/dav")
    }

    @Test
    fun `normalizeServer keeps an explicit http scheme`() {
        // 局域网 NAS 用明文 http 是常态，不能被"顺手升级"成 https
        // （那会让用户在自家网络里连不上，而错误信息只说"连不上"）。
        assertThat(WebDavUrlBuilder.normalizeServer("http://nas.local:5006/dav"))
            .isEqualTo("http://nas.local:5006/dav")
    }

    @Test
    fun `normalizeServer strips the trailing slash and is idempotent`() {
        val once = WebDavUrlBuilder.normalizeServer("  https://nas.local/dav/  ")
        assertThat(once).isEqualTo("https://nas.local/dav")
        // ★ 幂等：拼接前会调它，不幂等会让每进一层目录 URL 都多一截。
        assertThat(WebDavUrlBuilder.normalizeServer(once)).isEqualTo(once)
    }

    // ---------------------------------------------------------------- 拼接

    @Test
    fun `join does not produce a double slash`() {
        // ⚠️ 这条是核心：带尾斜杠的 base 直接交给 addPathSegment 会产出 `/dav//x`。
        assertThat(WebDavUrlBuilder.join("https://nas.local/dav/", "vault.kdbx"))
            .isEqualTo("https://nas.local/dav/vault.kdbx")
    }

    @Test
    fun `join percent encodes each segment but keeps slashes as separators`() {
        val joined = WebDavUrlBuilder.join("https://nas.local/dav", "我的 密码库/vault.kdbx")

        // `/` 必须还是分隔符（整串 URLEncoder 会把它编成 %2F，路径就变成一个段名）
        assertThat(joined).contains("/")
        assertThat(joined).doesNotContain("%2F")
        // 空格与中文必须被编码
        assertThat(joined).doesNotContain(" ")
        assertThat(joined).contains("%20")
    }

    @Test
    fun `join tolerates leading trailing and backslash separators`() {
        assertThat(WebDavUrlBuilder.join("https://nas.local/dav", "/sub\\vault.kdbx"))
            .isEqualTo("https://nas.local/dav/sub/vault.kdbx")
    }

    @Test
    fun `join returns null when the base is not a usable url`() {
        // 宁可交回 null 让上层报"服务器地址不对"，也不要拼出一个必然 404 的字符串。
        assertThat(WebDavUrlBuilder.join("", "vault.kdbx")).isNull()
    }

    @Test
    fun `joinDirectory keeps the trailing slash that listChildren relies on`() {
        // ⚠️ 少了这个斜杠，`WebDavKdbxFileSource.directoryUrl()` 会砍掉最后一段，
        //    PROPFIND 就打在上一级 —— 用户进了子目录却看到父目录的内容。
        assertThat(WebDavUrlBuilder.joinDirectory("https://nas.local/dav/", "Vaultix"))
            .isEqualTo("https://nas.local/dav/Vaultix/")
    }
}
