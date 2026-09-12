package io.vaultix.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 站点图标 URL 拼接单测。
 *
 * ⚠️ 这些断言的**每一条都对应一个真实会发生的失败场景**（真机里表现为
 * 「图标永远加载不出来，且没有任何报错」）。拼接逻辑一旦放松，最坏情况不是「没图标」，
 * 而是**请求被改写**（域名直接进 URL 路径，出现 `/` `..` `?` 就能指向别的端点）——
 * 因此下面既有正向用例，也有**路径注入**的反向用例。
 */
class SiteIconUrlTest {

    @Test
    fun buildsBitwardenStyleUrl() {
        // 对齐 Bitwarden 夹具：https://vault.bitwarden.com/icons/www.mockuri.com/icon.png
        assertThat(SiteIconUrl.forHost("https://vault.bitwarden.com", "www.mockuri.com"))
            .isEqualTo("https://vault.bitwarden.com/icons/www.mockuri.com/icon.png")
    }

    @Test
    fun defaultBitwardenHostAlsoWorks() {
        assertThat(SiteIconUrl.forHost("https://vault.bitwarden.com/", "example.com"))
            .isEqualTo("https://vault.bitwarden.com/icons/example.com/icon.png")
    }

    @Test
    fun stripsPathFromSelfHostedOrigin() {
        // 反代部署 https://example.com/vault：图标端点在**站点根**，多拼一段必然 404
        assertThat(SiteIconUrl.forHost("https://example.com/vault", "example.com"))
            .isEqualTo("https://example.com/icons/example.com/icon.png")
    }

    @Test
    fun keepsNonDefaultPort() {
        assertThat(SiteIconUrl.forHost("https://example.com:8443", "example.com"))
            .isEqualTo("https://example.com:8443/icons/example.com/icon.png")
    }

    @Test
    fun rejectsCleartextServerOrigin() {
        // 明文 http 会被 Android cleartext 策略拦掉（无报错的静默失败）→ 直接走兜底
        assertThat(SiteIconUrl.forHost("http://192.168.1.10", "example.com")).isNull()
    }

    @Test
    fun rejectsContentUriOriginForKdbxVaults() {
        assertThat(SiteIconUrl.forHost("content://com.android.providers/document/1", "example.com"))
            .isNull()
    }

    @Test
    fun rejectsMissingHostOrOrigin() {
        assertThat(SiteIconUrl.forHost(null, "example.com")).isNull()
        assertThat(SiteIconUrl.forHost("https://vault.example.com", null)).isNull()
        assertThat(SiteIconUrl.forHost("https://vault.example.com", "")).isNull()
        assertThat(SiteIconUrl.forHost("", "example.com")).isNull()
    }

    @Test
    fun rejectsHostWithoutDot() {
        // 单标签（内网主机名）没有站点图标可言
        assertThat(SiteIconUrl.forHost("https://vault.example.com", "localhost")).isNull()
    }

    @Test
    fun rejectsPathInjectionInDomain() {
        // ★ 关键反向用例：域名会被拼进 URL 路径，任何分隔符都能改写请求目标
        listOf(
            "evil.com/../../admin",
            "evil.com?a=b",
            "evil.com#frag",
            "evil.com\\x",
            "..",
            ".hidden.com",
            "a..b.com",
        ).forEach { hostile ->
            assertThat(SiteIconUrl.forHost("https://vault.example.com", hostile))
                .isNull()
        }
    }

    @Test
    fun normalizesCaseAndTrailingDot() {
        assertThat(SiteIconUrl.forHost("https://Vault.Example.COM", "WWW.Example.COM."))
            .isEqualTo("https://vault.example.com/icons/www.example.com/icon.png")
    }

    @Test
    fun hostOfUriKeepsWwwPrefix() {
        // 保留 www.：图标服务按「条目里怎么写就怎么查」
        assertThat(SiteIconUrl.hostOfUri("https://www.mockuri.com")).isEqualTo("www.mockuri.com")
        // 裸域名（Bitwarden 允许）自动补 https 后解析
        assertThat(SiteIconUrl.hostOfUri("example.com")).isEqualTo("example.com")
    }

    @Test
    fun hostOfUriRejectsNonWebsiteBindings() {
        assertThat(SiteIconUrl.hostOfUri("androidapp://com.example.app")).isNull()
        assertThat(SiteIconUrl.hostOfUri("android-app://com.example.app")).isNull()
        assertThat(SiteIconUrl.hostOfUri("iosapp://com.example.app")).isNull()
        assertThat(SiteIconUrl.hostOfUri("content://media/external/1")).isNull()
        assertThat(SiteIconUrl.hostOfUri("file:///tmp/x")).isNull()
        assertThat(SiteIconUrl.hostOfUri("   ")).isNull()
        assertThat(SiteIconUrl.hostOfUri(null)).isNull()
    }

    @Test
    fun hostOfItemUrisPicksFirstWebsiteAndSkipsAppBindings() {
        // 真实场景：登录条目同时挂 androidapp:// 与网址 —— 取错就会拿包名去问图标服务
        val uris = listOf(
            "androidapp://com.example.app",
            "https://www.example.com/login",
            "https://other.example.org",
        )
        assertThat(SiteIconUrl.hostOfItemUris(uris)).isEqualTo("www.example.com")
        assertThat(SiteIconUrl.hostOfItemUris(emptyList())).isNull()
        assertThat(SiteIconUrl.hostOfItemUris(listOf("androidapp://a.b"))).isNull()
    }

    @Test
    fun endToEndFromItemUris() {
        val url = SiteIconUrl.forHost(
            serverOrigin = "https://vault.bitwarden.com",
            host = SiteIconUrl.hostOfItemUris(listOf("androidapp://x.y", "https://github.com/login")),
        )
        assertThat(url).isEqualTo("https://vault.bitwarden.com/icons/github.com/icon.png")
    }
}
