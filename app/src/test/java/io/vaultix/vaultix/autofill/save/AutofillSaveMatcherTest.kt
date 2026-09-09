package io.vaultix.vaultix.autofill.save

import com.google.common.truth.Truth.assertThat
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import io.vaultix.model.VaultUri
import org.junit.Test

/** 保存流程的纯判定：目标 URI 归一 + 「已有条目 → 更新」判定 + 默认名称。 */
class AutofillSaveMatcherTest {

    private fun item(
        title: String,
        username: String,
        uris: List<String> = emptyList(),
    ) = VaultItem(
        id = title,
        title = title,
        type = VaultItemType.Login,
        username = username,
        uris = uris.map { VaultUri(it) },
    )

    // ---- 目标 URI ----

    @Test
    fun targetUri_prefersWebDomainOverPackage() {
        assertThat(AutofillSaveMatcher.targetUri("Example.com", "com.demo.app"))
            .isEqualTo("https://example.com")
    }

    @Test
    fun targetUri_fallsBackToAndroidAppUri() {
        assertThat(AutofillSaveMatcher.targetUri(null, "com.demo.app"))
            .isEqualTo("androidapp://com.demo.app")
    }

    @Test
    fun targetUri_nullWhenNothingKnown() {
        assertThat(AutofillSaveMatcher.targetUri(null, null)).isNull()
        assertThat(AutofillSaveMatcher.targetUri("  ", "")).isNull()
    }

    // ---- 更新判定 ----

    @Test
    fun findExisting_matchesSameSiteAndSameUsername() {
        val items = listOf(
            item("Demo", "alice", listOf("https://demo.example.com")),
            item("Other", "bob", listOf("https://other.com")),
        )
        val found = AutofillSaveMatcher.findExisting(items, "https://example.com", "alice")
        assertThat(found?.id).isEqualTo("Demo")
    }

    @Test
    fun findExisting_ignoresDifferentUsernameOnSameSite() {
        val items = listOf(item("Demo", "alice", listOf("https://example.com")))
        assertThat(AutofillSaveMatcher.findExisting(items, "https://example.com", "carol")).isNull()
    }

    @Test
    fun findExisting_ignoresDifferentSite() {
        val items = listOf(item("Demo", "alice", listOf("https://example.com")))
        assertThat(AutofillSaveMatcher.findExisting(items, "https://other.com", "alice")).isNull()
    }

    @Test
    fun findExisting_requiresUsername() {
        // 账号为空时不做「按账号」匹配，否则会把所有无名条目当成同一个
        val items = listOf(item("Demo", "", listOf("https://example.com")))
        assertThat(AutofillSaveMatcher.findExisting(items, "https://example.com", "")).isNull()
    }

    // ---- 默认名称 ----

    @Test
    fun defaultTitle_stripsWwwFromDomain() {
        assertThat(AutofillSaveMatcher.defaultTitle("WWW.Example.com", "com.demo", "Demo"))
            .isEqualTo("example.com")
    }

    @Test
    fun defaultTitle_fallsBackToAppLabelThenPackage() {
        assertThat(AutofillSaveMatcher.defaultTitle(null, "com.demo.app", "演示应用"))
            .isEqualTo("演示应用")
        assertThat(AutofillSaveMatcher.defaultTitle(null, "com.demo.app", null))
            .isEqualTo("com.demo.app")
    }
}
