package io.vaultix.vaultix.autofill.match

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 包名匹配闸门：浏览器里若 WebView 没上报域名，请求的包名是**浏览器自己**的包名——
 * 此时必须关掉包名退化，否则会把浏览器当成条目身份来匹配。
 */
class AutofillRequestContextPolicyTest {

    @Test
    fun packageMatchingAllowed_whenDomainPresent() {
        assertThat(
            AutofillRequestContextPolicy.allowPackageMatching("com.microsoft.emmx", "example.com", true),
        ).isTrue()
    }

    @Test
    fun packageMatchingBlocked_forBrowserWithoutDomain() {
        assertThat(
            AutofillRequestContextPolicy.allowPackageMatching("com.microsoft.emmx", null, false),
        ).isFalse()
        assertThat(AutofillRequestContextPolicy.allowPackageMatching("com.android.chrome", "", false)).isFalse()
    }

    @Test
    fun packageMatchingBlocked_forWebViewWithoutDomain() {
        assertThat(
            AutofillRequestContextPolicy.allowPackageMatching("com.unknown.app", null, isWebView = true),
        ).isFalse()
    }

    @Test
    fun packageMatchingAllowed_forNativeApp() {
        assertThat(
            AutofillRequestContextPolicy.allowPackageMatching("com.example.shop", null, false),
        ).isTrue()
        assertThat(AutofillRequestContextPolicy.allowPackageMatching(null, null, false)).isFalse()
    }

    @Test
    fun exactPackageMatchAllowed_forFileSchemeWebView() {
        // App 内嵌本地 H5（file:///android_asset/login.html）：域名拿不到，
        // 但请求包名就是 App 自己 ⇒ 精确包名匹配必须放行（Bastion 同款例外）。
        assertThat(
            AutofillRequestContextPolicy.allowExactPackageMatching(
                "com.example.shop", null, isWebView = true, webScheme = "file",
            ),
        ).isTrue()
        assertThat(
            AutofillRequestContextPolicy.allowExactPackageMatching(
                "com.example.shop", "", isWebView = true, webScheme = "FILE",
            ),
        ).isTrue()
    }

    @Test
    fun exactPackageMatchBlocked_forHttpWebViewWithoutDomain() {
        // http(s) 的 WebView 页面身份仍是那个网站，不能拿 App 包名顶替网站条目。
        assertThat(
            AutofillRequestContextPolicy.allowExactPackageMatching(
                "com.example.shop", null, isWebView = true, webScheme = "https",
            ),
        ).isFalse()
        assertThat(
            AutofillRequestContextPolicy.allowExactPackageMatching(
                "com.example.shop", null, isWebView = false, webScheme = "file",
            ),
        ).isFalse()
    }

    @Test
    fun exactPackageMatchBlocked_forBrowserAndBlankPackage() {
        assertThat(
            AutofillRequestContextPolicy.allowExactPackageMatching(
                "com.microsoft.emmx", null, isWebView = true, webScheme = "file",
            ),
        ).isFalse()
        assertThat(
            AutofillRequestContextPolicy.allowExactPackageMatching(
                null, null, isWebView = true, webScheme = "file",
            ),
        ).isFalse()
    }

    @Test
    fun domainlessFallbackOnlyForKnownBrowsers() {
        // Firefox 拿不到域名是平台限制 → 允许「列全库」兜底。
        assertThat(
            AutofillRequestContextPolicy.allowDomainlessFallback("org.mozilla.firefox"),
        ).isTrue()
        // 原生 App 本来就没有域名 → 绝不能列全库（否则把别的站点密码摊在它面前）。
        assertThat(
            AutofillRequestContextPolicy.allowDomainlessFallback("com.example.shop"),
        ).isFalse()
        assertThat(AutofillRequestContextPolicy.allowDomainlessFallback(null)).isFalse()
    }

    @Test
    fun blockedPackages_coversSystemAndSelf() {
        assertThat(AutofillRequestContextPolicy.isBlockedPackage("android", "io.vaultix.vaultix")).isTrue()
        assertThat(AutofillRequestContextPolicy.isBlockedPackage("com.android.settings", "io.vaultix.vaultix")).isTrue()
        assertThat(
            AutofillRequestContextPolicy.isBlockedPackage("io.vaultix.vaultix", "io.vaultix.vaultix"),
        ).isTrue()
        assertThat(
            AutofillRequestContextPolicy.isBlockedPackage("com.example.shop", "io.vaultix.vaultix"),
        ).isFalse()
    }
}
