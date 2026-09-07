package com.bastion.app.bitwarden.export

import com.bastion.app.data.Category
import com.bastion.app.data.PasswordEntry
import com.bastion.app.security.SecurityManager
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T1 守卫测试：Bitwarden JSON 导出需把 entry.website（逗号/中文逗号分隔的多网址）
 * 对称拆分为多个独立 BwUri，与读取侧 parseLoginUris / 上传侧 buildEncryptedLoginUris 一致，
 * 避免把整串 website 当作单个畸形 URI 导出。
 */
class BitwardenJsonExportTest {

    private fun newExporter(): BitwardenJsonExporter {
        // relaxed mock：looksLikeBastionCiphertext 默认 false，明文 website 走原值，符合 safeDecrypt 行为。
        val securityManager = mockk<SecurityManager>(relaxed = true)
        return BitwardenJsonExporter(securityManager, emptyList<Category>())
    }

    @Test
    fun multipleWebsitesSplitIntoSeparateBwUris() {
        val entry = PasswordEntry(
            title = "Multi",
            website = "https://a.com, https://b.com",
            username = "",
            password = ""
        )
        val export = newExporter().buildPlainExport(listOf(entry), emptyList())
        val uris = export.items.first().login?.uris
        assertEquals(2, uris?.size)
        assertEquals("https://a.com", uris?.get(0)?.uri)
        assertEquals("https://b.com", uris?.get(1)?.uri)
    }

    @Test
    fun singleWebsiteStaysOneUri() {
        val entry = PasswordEntry(
            title = "Single",
            website = "https://a.com",
            username = "",
            password = ""
        )
        val export = newExporter().buildPlainExport(listOf(entry), emptyList())
        val uris = export.items.first().login?.uris
        assertEquals(1, uris?.size)
        assertEquals("https://a.com", uris?.get(0)?.uri)
    }

    @Test
    fun blankWebsiteYieldsNoUris() {
        val entry = PasswordEntry(title = "Blank", website = "", username = "", password = "")
        val export = newExporter().buildPlainExport(listOf(entry), emptyList())
        assertEquals(null, export.items.first().login?.uris)
    }

    @Test
    fun chineseCommaIsTreatedAsSeparator() {
        val entry = PasswordEntry(
            title = "Cn",
            website = "https://a.com，https://b.com",
            username = "",
            password = ""
        )
        val export = newExporter().buildPlainExport(listOf(entry), emptyList())
        val uris = export.items.first().login?.uris
        assertEquals(2, uris?.size)
        assertEquals("https://a.com", uris?.get(0)?.uri)
        assertEquals("https://b.com", uris?.get(1)?.uri)
    }
}
