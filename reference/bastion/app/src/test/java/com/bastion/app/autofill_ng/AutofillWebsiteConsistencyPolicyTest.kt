package com.bastion.app.autofill_ng

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2 条目级 website 双向一致性校验回归测试（AutofillWebsiteConsistencyPolicy）。
 *
 * 对齐 bitwarden fillLoginPartition 的第二道防线，采用「仅拒绝明确矛盾」：
 * - web 页面比对注册域（支持子域名/www/端口/大小写/多 URI 换行）；
 * - 原生页面比对包名（appPackageName 或 androidapp:// URI）；
 * - 无 website 且无包名（KeePass 裸条目 / WiFi 条目）→ 放行。
 */
class AutofillWebsiteConsistencyPolicyTest {

    // ================= web 页面（pageWebDomain 非空）=================

    @Test
    fun webPage_exactDomainMatch_isConsistent() {
        assertTrue(consistent(entryWebsite = "https://jd.com", pageWebDomain = "login.jd.com"))
    }

    @Test
    fun webPage_wwwVariant_isConsistent() {
        assertTrue(consistent(entryWebsite = "https://www.jd.com", pageWebDomain = "login.jd.com"))
    }

    @Test
    fun webPage_subdomainEntry_isConsistent() {
        // 子域名关系不算矛盾（比 bitwarden 的严格相等更宽松，符合「仅拒绝矛盾」定位）
        assertTrue(consistent(entryWebsite = "https://m.example.com", pageWebDomain = "example.com"))
    }

    @Test
    fun webPage_portAndPath_isConsistent() {
        assertTrue(consistent(entryWebsite = "http://example.com:8080/path", pageWebDomain = "example.com"))
    }

    @Test
    fun webPage_bareHostWithoutScheme_isConsistent() {
        assertTrue(consistent(entryWebsite = "example.com", pageWebDomain = "example.com"))
    }

    @Test
    fun webPage_uppercaseDomain_isConsistent() {
        assertTrue(consistent(entryWebsite = "HTTPS://JD.COM", pageWebDomain = "login.jd.com"))
    }

    @Test
    fun webPage_differentRegistrableDomain_isRejected() {
        // 京东搜索栏误弹的同类场景：条目绑定其它站点 → 明确矛盾
        assertFalse(consistent(entryWebsite = "https://taobao.com", pageWebDomain = "login.jd.com"))
    }

    @Test
    fun webPage_multiUri_anyHostMatches_isConsistent() {
        assertTrue(
            consistent(
                entryWebsite = "https://a.com\nhttps://b.com",
                pageWebDomain = "b.com",
            )
        )
    }

    @Test
    fun webPage_multiUri_noneHostMatches_isRejected() {
        // 多个 URI 的注册域全部与页面不同 → 明确矛盾
        assertFalse(
            consistent(
                entryWebsite = "https://a.com\nhttps://b.com",
                pageWebDomain = "c.com",
            )
        )
    }

    @Test
    fun webPage_entryWithoutWebHost_isConsistent() {
        // 条目仅有 androidapp:// URI 或无 website → 无 web 域名可判 → 放行
        assertTrue(consistent(entryWebsite = "androidapp://com.other.app", pageWebDomain = "login.jd.com"))
        assertTrue(consistent(entryWebsite = "", pageWebDomain = "login.jd.com"))
        assertTrue(consistent(entryWebsite = null, pageWebDomain = "login.jd.com"))
    }

    // ================= 原生页面（pageWebDomain 为空）=================

    @Test
    fun nativePage_exactPackageMatch_isConsistent() {
        assertTrue(
            consistent(
                entryAppPackage = "com.example.shop",
                pageWebDomain = null,
                pagePackageName = "com.example.shop",
            )
        )
    }

    @Test
    fun nativePage_packageMismatch_isRejected() {
        assertFalse(
            consistent(
                entryAppPackage = "com.other.app",
                pageWebDomain = null,
                pagePackageName = "com.example.shop",
            )
        )
    }

    @Test
    fun nativePage_androidappUriPackageMismatch_isRejected() {
        assertFalse(
            consistent(
                entryWebsite = "androidapp://com.other.app",
                pageWebDomain = null,
                pagePackageName = "com.example.shop",
            )
        )
    }

    @Test
    fun nativePage_androidappUriPackageMatch_isConsistent() {
        assertTrue(
            consistent(
                entryWebsite = "androidapp://com.example.shop",
                pageWebDomain = null,
                pagePackageName = "com.example.shop",
            )
        )
    }

    @Test
    fun nativePage_packageCaseInsensitive_isConsistent() {
        assertTrue(
            consistent(
                entryAppPackage = "COM.EXAMPLE.SHOP",
                pageWebDomain = null,
                pagePackageName = "com.example.shop",
            )
        )
    }

    @Test
    fun nativePage_entryWithOnlyWebHost_isConsistent() {
        // KeePass 常见约定：原生 App 登录也存服务官网域名 → 不算矛盾
        assertTrue(
            consistent(
                entryWebsite = "https://example.com",
                pageWebDomain = null,
                pagePackageName = "com.example.shop",
            )
        )
    }

    @Test
    fun nativePage_multiUri_anyPackageMatches_isConsistent() {
        assertTrue(
            consistent(
                entryWebsite = "https://a.example.com\nandroidapp://com.example.shop",
                pageWebDomain = null,
                pagePackageName = "com.example.shop",
            )
        )
    }

    @Test
    fun nativePage_blankEntry_isConsistent() {
        // KeePass 裸条目 / WiFi 条目：无 website 无包名 → 放行
        assertTrue(
            consistent(
                entryWebsite = "",
                entryAppPackage = "",
                pageWebDomain = null,
                pagePackageName = "com.android.settings",
            )
        )
        assertTrue(
            consistent(
                entryWebsite = null,
                entryAppPackage = null,
                pageWebDomain = null,
                pagePackageName = "com.android.settings",
            )
        )
    }

    @Test
    fun nativePage_unknownPagePackage_isConsistent() {
        // 页面包名未知 → 无法判定矛盾 → 放行
        assertTrue(
            consistent(
                entryAppPackage = "com.example.shop",
                pageWebDomain = null,
                pagePackageName = null,
            )
        )
    }

    // ================= 包名过期 + 标题证据（混淆包名 App 回归修复）=================

    @Test
    fun nativePage_packageMismatch_butTitleEvidence_isConsistent() {
        // 电影猎手场景：条目存旧包名（混淆包名 App 重打包换包名），但条目标题与
        // 应用显示名归一化后一致（「电影猎手」↔「电影_猎手」）→ 视为包名过期，放行
        assertTrue(
            consistent(
                entryAppPackage = "com.dianying.old",
                entryTitle = "电影猎手",
                pageWebDomain = null,
                pagePackageName = "qingcore677.mingcore8lc.guanglane1dnx",
                pageAppDisplayName = "电影_猎手",
            )
        )
    }

    @Test
    fun nativePage_packageMismatch_withEnglishTitleVariant_isConsistent() {
        // 英文标题变体：下划线/大小写差异归一化后一致
        assertTrue(
            consistent(
                entryAppPackage = "com.old.pkg",
                entryTitle = "MovieHunter",
                entryAppName = "movie_hunter",
                pageWebDomain = null,
                pagePackageName = "com.new.random.pkg",
                pageAppDisplayName = "moviehunter",
            )
        )
    }

    @Test
    fun nativePage_packageMismatch_withUnrelatedTitle_isRejected() {
        // 包名矛盾 + 标题无关（如京东条目出现在电影猎手页面）→ 仍拒绝（P2 防误填保留）
        assertFalse(
            consistent(
                entryAppPackage = "com.other.app",
                entryTitle = "京东商城",
                pageWebDomain = null,
                pagePackageName = "com.example.shop",
                pageAppDisplayName = "电影猎手",
            )
        )
    }

    @Test
    fun nativePage_packageMismatch_blankTitle_isRejected() {
        // 包名矛盾 + 无标题证据 → 拒绝（保持原行为）
        assertFalse(
            consistent(
                entryAppPackage = "com.other.app",
                pageWebDomain = null,
                pagePackageName = "com.example.shop",
            )
        )
    }

    // ================= 注册域边界 =================

    @Test
    fun differentTld_isRejected() {
        assertFalse(consistent(entryWebsite = "https://example.com.cn", pageWebDomain = "example.com"))
    }

    private fun consistent(
        entryWebsite: String? = null,
        entryAppPackage: String? = null,
        entryTitle: String? = null,
        entryAppName: String? = null,
        pageWebDomain: String? = null,
        pagePackageName: String? = null,
        pageAppDisplayName: String? = null,
    ) = AutofillWebsiteConsistencyPolicy.isConsistent(
        entryWebsite = entryWebsite,
        entryAppPackage = entryAppPackage,
        entryTitle = entryTitle,
        entryAppName = entryAppName,
        pageWebDomain = pageWebDomain,
        pagePackageName = pagePackageName,
        pageAppDisplayName = pageAppDisplayName,
    )
}
