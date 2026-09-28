/*
 * Vaultix — app:util (test)
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UpdateChecker] 的**纯函数**部分。
 *
 * ⚠️ 刻意不测 `checkForUpdate` / `checkStable` / `checkPreview`：它们一上来就打
 * `api.github.com`，单测里只能靠 mock OkHttp 才有意义 —— 那会把测试绑死在
 * "库怎么发请求"的实现细节上（换个 HTTP 客户端就得改测试），而对**行为正确性**
 * 的覆盖并不比端到端手测更强。网络那部分靠真机手动检查（设置 → 版本）验证。
 *
 * 这里只覆盖**不碰网络、但逻辑边界多**的两处：
 *   - [UpdateChecker.mirrorUrl]：地址转换 + **防二次代理**的守卫；
 *   - [UpdateChecker.compareVersionTags]：语义化版本比对（预览版 / 带 `v` 前缀 / 段数不等）。
 */
class UpdateCheckerTest {

    // ---- mirrorUrl ---------------------------------------------------------

    @Test
    fun mirrorUrl_disabled_returnsOriginalUrl() {
        val url = "https://github.com/Chaniug/Vaultix/releases/tag/v0.5.0"
        assertEquals(url, UpdateChecker.mirrorUrl(url, useMirror = false))
    }

    @Test
    fun mirrorUrl_enabled_prefixesGithubUrl() {
        val url = "https://github.com/Chaniug/Vaultix/releases/tag/v0.5.0"
        val mirrored = UpdateChecker.mirrorUrl(url, useMirror = true)
        assertTrue(
            "镜像地址应以镜像前缀开头，实际：$mirrored",
            mirrored.startsWith("https://ghproxy.net/"),
        )
        // 原始地址必须**完整保留在后面**（简单前缀拼接，不改 URL 结构）。
        assertTrue("镜像地址应包含原始地址，实际：$mirrored", mirrored.endsWith(url))
    }

    @Test
    fun mirrorUrl_enabled_doesNotDoublePrefixAnAlreadyMirroredUrl() {
        // ★ 这条是本文件的**核心**用例。若守卫写错（无条件拼接），第二次转换会得到
        //   `https://ghproxy.net/https://ghproxy.net/https://github.com/...` ——
        //   一个必然打不开的地址，且症状只在"用户先开镜像、某处再转一次"时才暴露，
        //   属于极难从表象反推的坑。用一条测试把它钉住。
        val already = "https://ghproxy.net/https://github.com/Chaniug/Vaultix/releases"
        assertEquals(already, UpdateChecker.mirrorUrl(already, useMirror = true))
    }

    @Test
    fun mirrorUrl_enabled_leavesNonGithubUrlUntouched() {
        // 非 github.com 开头的地址（例如将来换成自建发布页、或 Gitee 镜像本身）
        // 一律原样返回 —— "不认识就不要乱动"。盲目拼接会造出无意义的地址。
        val custom = "https://example.com/downloads/vaultix.apk"
        assertEquals(custom, UpdateChecker.mirrorUrl(custom, useMirror = true))
    }

    @Test
    fun mirrorHost_isBareHostWithoutSchemeOrTrailingSlash() {
        // 展示用：对话框里要告诉用户"你的下载页将由谁代理"，不能带 `https://` 和结尾 `/`
        // （那串符号在 UI 上是噪音）。同时断言它确实是个非空主机名，避免将来前缀格式
        // 改成不含 scheme 时这里静默返回一个空串。
        val host = UpdateChecker.mirrorHost()
        assertTrue("镜像主机名不应为空", host.isNotBlank())
        assertTrue("镜像主机名不应含 scheme，实际：$host", !host.contains("://"))
        assertTrue("镜像主机名不应以 / 结尾，实际：$host", !host.endsWith("/"))
    }

    // ---- compareVersionTags -------------------------------------------------

    @Test
    fun compareVersionTags_detectsNewerRelease() {
        assertTrue(UpdateChecker.compareVersionTags("v0.6.0", "0.5.0") > 0)
    }

    @Test
    fun compareVersionTags_detectsOlderRelease() {
        assertTrue(UpdateChecker.compareVersionTags("v0.4.0", "0.5.0") < 0)
    }

    @Test
    fun compareVersionTags_treatsEqualVersionWithVPrefixAsSame() {
        // `v0.5.0` vs `0.5.0`：前缀 `v` 不该影响判定（tag 带 v、versionName 不带，是常态）。
        assertEquals(0, UpdateChecker.compareVersionTags("v0.5.0", "0.5.0"))
    }

    @Test
    fun compareVersionTags_ignoresNonNumericSuffix() {
        // 预览版形如 `0.5.0-dev-78db0ed`：dev 后缀不是版本号的一部分，
        // 比对只认前三段数字 ⇒ 与本机 `0.5.0` 视为同一版本（否则预览包会永远"有更新"）。
        assertEquals(0, UpdateChecker.compareVersionTags("0.5.0", "0.5.0-dev-78db0ed"))
    }

    @Test
    fun compareVersionTags_handlesDifferentPartCounts() {
        // 段数不等时，缺的段按 0 处理：`0.5` == `0.5.0`，而 `0.5.1` > `0.5`。
        assertEquals(0, UpdateChecker.compareVersionTags("0.5", "0.5.0"))
        assertTrue(UpdateChecker.compareVersionTags("0.5.1", "0.5") > 0)
    }
}
