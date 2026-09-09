package io.vaultix.vaultix.autofill.parser

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 浏览器地址栏 / 文本域名兜底。
 *
 * 背景：Edge（com.microsoft.emmx）等浏览器的 WebView 不总会上报 `ViewNode.webDomain`，
 * 缺了这条兜底就表现为「浏览器里填充失效」。
 */
class BrowserUrlBarsTest {

    // ---- 地址栏识别 ----

    @Test
    fun edgeUrlBar_isDetectedByPackageAndEntry() {
        assertThat(
            BrowserUrlBars.isUrlBarNode("com.microsoft.emmx", "com.microsoft.emmx", "url_bar"),
        ).isTrue()
    }

    @Test
    fun urlBarFallsBackToPagePackage_whenIdPackageMissing() {
        assertThat(BrowserUrlBars.isUrlBarNode("com.microsoft.emmx", null, "url_bar")).isTrue()
    }

    @Test
    fun urlBarRejected_whenEntryMismatchOrSystemIdPackage() {
        // 资源 id 不匹配（不同 App 可能复用同名 id）
        assertThat(BrowserUrlBars.isUrlBarNode("com.microsoft.emmx", "com.microsoft.emmx", "search_box"))
            .isFalse()
        // OS 有时把 idPackage 给成 "android"，不是合法包名
        assertThat(BrowserUrlBars.isUrlBarNode("com.microsoft.emmx", "android", "url_bar")).isFalse()
    }

    @Test
    fun urlBarRejected_forUnknownPackage() {
        assertThat(BrowserUrlBars.isUrlBarNode("com.example.app", "com.example.app", "url_bar")).isFalse()
        assertThat(BrowserUrlBars.isUrlBarNode(null, null, "url_bar")).isFalse()
    }

    // ---- 文本 → 域名 ----

    @Test
    fun hostFromText_extractsHostFromFullUrl() {
        assertThat(BrowserUrlBars.hostFromText("https://www.example.com/login?a=1"))
            .isEqualTo("www.example.com")
        assertThat(BrowserUrlBars.hostFromText("example.com")).isEqualTo("example.com")
    }

    @Test
    fun hostFromText_rejectsSearchWordsVersionsAndIp() {
        // 地址栏里是搜索词 / 版本号 / 纯 IP 时不能当域名用
        assertThat(BrowserUrlBars.hostFromText("如何重置密码")).isNull()
        assertThat(BrowserUrlBars.hostFromText("v2.0")).isNull()
        assertThat(BrowserUrlBars.hostFromText("127.0.0.1")).isNull()
        assertThat(BrowserUrlBars.hostFromText("localhost")).isNull()
        assertThat(BrowserUrlBars.hostFromText("")).isNull()
        assertThat(BrowserUrlBars.hostFromText(null)).isNull()
    }
}
