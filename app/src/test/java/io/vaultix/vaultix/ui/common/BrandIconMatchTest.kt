package io.vaultix.vaultix.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 域名 → 品牌图标的**换算规则**（[brandSlugForHost]）。
 *
 * ## 为什么要这么多条用例
 *
 * 这条链路上判错的代价是**张冠李戴**：把 `live.google.com` 认成微软不会报错、
 * 不会崩，只会让用户在密码列表里看到一个错的标志 —— 而"图标"的全部价值就是可信。
 *
 * ⇒ 除了"该命中"的正例，本文件专门配了三个**反例**：
 * - `live.google.com` 必须走 `google`（别名 `live`→`microsoft` 不许抢）；
 * - `192.168.1.1` / `localhost` 必须 null（局域网设备不该有品牌）；
 * - 未知域名必须 null（宁可显示首字母，也不要猜）。
 */
class BrandIconMatchTest {

    @Test
    fun `主域命中`() {
        assertSlug("github.com", "github")
        assertSlug("1password.com", "1password")
        assertSlug("alipay.com", "alipay")
    }

    @Test
    fun `网址或带端口或带路径也能命中`() {
        assertSlug("https://WWW.GitHub.com/login?next=/x", "github")
        assertSlug("WWW.GitHub.COM:443", "github")
        assertSlug("  github.com.  ", "github")
    }

    @Test
    fun `子域回落主域`() {
        assertSlug("mail.google.com", "google")
        assertSlug("docs.google.com", "google")
        assertSlug("accounts.google.com", "google")
    }

    @Test
    fun `品牌名与域名不一致时走别名`() {
        assertSlug("outlook.com", "microsoft")
        assertSlug("office.com", "microsoft")
        assertSlug("aliyun.com", "alibabacloud")
        assertSlug("googlemail.com", "gmail")
    }

    @Test
    fun `别名也作用于子域`() {
        // `weixin.qq.com` 的主域标签是 `qq`（表里没有），别名挂在子域上。
        assertSlug("weixin.qq.com", "wechat")
    }

    @Test
    fun `前缀匹配救回带后缀的品牌域名`() {
        assertSlug("login.microsoftonline.com", "microsoft")
    }

    @Test
    fun `别名绝不许抢走更精确的子域`() {
        // 反例：`live` 在别名表里（→ microsoft），但这里主域是 google。
        assertSlug("live.google.com", "google")
    }

    @Test
    fun `两段式后缀不影响主域判定`() {
        assertSlug("amazon.co.uk", "amazon")
        assertSlug("www.amazon.com.cn", "amazon")
    }

    @Test
    fun `单字母品牌只认精确匹配`() {
        assertSlug("x.com", "x")
        // 反例：不能因为 slug `x` 存在，就把所有 x 开头的域名都认成 X。
        assertSlug("xbox.com", "xbox")
        assertSlug("xhamster.com", null)
    }

    @Test
    fun `IP 与 localhost 一律不认`() {
        assertSlug("192.168.1.1", null)
        assertSlug("10.0.0.1:8080", null)
        assertSlug("localhost", null)
        assertSlug("localhost:8080", null)
    }

    @Test
    fun `未知域名宁可返回 null 也不猜`() {
        assertSlug("some-unknown-site.example", null)
        assertSlug("intranet", null)
        assertSlug(null, null)
        assertSlug("   ", null)
    }

    @Test
    fun `浅色品牌色必须用深色图形`() {
        // 亮黄（Yandex 系）/ 纯白：白图形会糊在底上。
        assertTrue(isLightBrandColor(0xFFFFCC00))
        assertTrue(isLightBrandColor(0xFFFFFFFF))
        // 深色：Slack 的紫、GitHub 的近黑、纯黑。
        assertFalse(isLightBrandColor(0xFF4A154B))
        assertFalse(isLightBrandColor(0xFF181717))
        assertFalse(isLightBrandColor(0xFF000000))
    }

    @Test
    fun `图标包规模不能悄悄缩水`() {
        // 防的是"生成脚本跑了一半"或"清资源时顺手删了一批 drawable"：
        // 那样不会报错，只会让一堆站点悄悄退回首字母，而没有任何地方看得出原因。
        assertTrue(
            "离线图标包只剩 ${BrandIconCatalog.size} 个 —— 少于 200 个说明生成/清理出过错",
            BrandIconCatalog.size >= MIN_EXPECTED_ICONS,
        )
    }

    private fun assertSlug(host: String?, expected: String?) {
        val actual = brandSlugForHost(host)
        assertEquals("host=$host", expected, actual)
    }

    private companion object {
        /** 当前是 229 个；留出余量，只拦"大面积丢失"。 */
        const val MIN_EXPECTED_ICONS = 200
    }
}
