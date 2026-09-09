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
