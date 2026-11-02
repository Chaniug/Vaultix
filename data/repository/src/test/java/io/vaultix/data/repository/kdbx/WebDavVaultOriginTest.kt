/*
 * Vaultix — data:repository（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.data.repository.kdbx

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [WebDavVaultOrigin] 的编解码（2026-11-02）。
 *
 * ## 为什么必须单独钉住"编码后不含 SQLite 特殊字符"
 *
 * origin 直接进 `vaults.id` / `vaults.origin`。SQLite 里：
 * - `trim()` **只认空格**，`'a\n' = 'a'` 判 false —— 看着没事，但它让所有
 *   "先 trim 再比"的代码在库表这一层**静默失效**；
 * - `LIKE` 把 `%` `_` 当通配 —— `vaultId LIKE ?` 这种写法就会**匹配到别的库**。
 *
 * 旧实现用 `URLEncoder`（`-encode`）：它把空格编成 `+`，但 `%` / `_` **原样留下**。
 * 一个叫 `密码 100%.kdbx` 的库就能把这两条同时踩中。
 *
 * 现行用 base64url（`+encode`）：字母表只有 `A-Z a-z 0-9 - _` —— 没有任何
 * 引号 / 通配 / 控制字符。而 `_` 在 LIKE 里虽然仍是单字符通配，但它只在
 * "用户输入被当成模式"时才生效，`=` 比较（本项目读库表用的）不受影响；
 * 真正会撞的 `%` `'` `"` 已经不可能出现。
 */
class WebDavVaultOriginTest {

    @Test
    fun `build 之后 parse 必须原样还原 URL`() {
        val url = "https://nas.local:5006/dav/我的 密码库/vault.kdbx"
        val origin = WebDavVaultOrigin.build("cred-1", url)

        val parsed = WebDavVaultOrigin.parse(origin)

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.credentialId).isEqualTo("cred-1")
        assertThat(parsed.fileUrl).isEqualTo(url)
    }

    @Test
    fun `编码结果不得含 SQLite 的 LIKE 通配与控制字符`() {
        // `%` `_` 是 LIKE 通配；`'` `"` 是引号；`\t` `\n` `\r` 是 SQLite trim() 不认的字符。
        val url = "https://nas.local/dav/100%_don't\"x\ty\nz\r.kdbx"
        val origin = WebDavVaultOrigin.build("cred%_1", url)

        val payload = origin.removePrefix(WebDavVaultOrigin.PREFIX).substringAfter(':')
        listOf('%', '_', '\'', '"', '\\', '\t', '\n', '\r', '?', '&').forEach { ch ->
            assertThat(payload).doesNotContain(ch.toString())
        }
    }

    @Test
    fun `旧的 URLEncoder 形 origin 必须继续能读`() {
        // 用户库里已经存着这种形式（2026-11-02 之前的版本写进去的），不能因为换了编码就读不回来。
        val legacyUrl = "https://nas.local/dav/我的 密码库.kdbx"
        val legacyOrigin = WebDavVaultOrigin.buildLegacyForTest("cred-1", legacyUrl)

        val parsed = WebDavVaultOrigin.parse(legacyOrigin)

        assertThat(parsed?.fileUrl).isEqualTo(legacyUrl)
    }

    @Test
    fun `含凭据的 URL 必须被拒绝`() {
        // `https://user:pass@host/x.kdbx` 会让密码进入日志 / 崩溃报告。
        val origin = WebDavVaultOrigin.build("cred-1", "https://user:pass@nas.local/dav/x.kdbx")

        assertThat(WebDavVaultOrigin.parse(origin)).isNull()
    }

    @Test
    fun `非 http scheme 必须被拒绝`() {
        val origin = WebDavVaultOrigin.build("cred-1", "file:///sdcard/vault.kdbx")

        assertThat(WebDavVaultOrigin.parse(origin)).isNull()
    }
}
