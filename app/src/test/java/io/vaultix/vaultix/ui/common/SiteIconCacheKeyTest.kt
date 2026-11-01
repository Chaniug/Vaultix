package io.vaultix.vaultix.ui.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [siteIconCacheKey] 的单测 —— 钉住图标内存缓存键（2026-11-01 新增）。
 *
 * ## 为什么这个纯函数值得单独测
 *
 * Coil 在显式设置了 `memoryCacheKey` 时会**整条替换**它自己算的键
 * （`MemoryCacheService.newCacheKey()` 的第一句 `request.memoryCacheKey?.let { return it }`），
 * 而它默认的键里才带着尺寸等 `extras`。
 *
 * ⇒ 如果这个函数漏了尺寸，40dp 的列表图标与 56dp 的详情页图标会共用同一条缓存。
 * 症状**不是崩溃、也不是报错**，而是每进一次详情页就重新解码一次、
 * 再回列表又解码回来 —— 用户感知到的只是"图标好像总是在加载"。
 * 这种"不崩不报、只是慢"的缺陷，真机看一天也定位不到，只能靠断言钉住。
 *
 * 与 [AvatarHueTest] 同一思路：把纯逻辑从 composable 里抽出来即可用 JVM 单测覆盖。
 */
class SiteIconCacheKeyTest {

    private val url = "https://vault.example.com/icons/github.com/icon.png"

    @Test
    fun 同URL同尺寸_键必须相同() {
        // 不稳定的键 = 每次重组都换一条缓存 = 缓存等于没有。
        assertThat(siteIconCacheKey(url, 120)).isEqualTo(siteIconCacheKey(url, 120))
    }

    @Test
    fun 尺寸不同_键必须不同() {
        // ★ 核心不变式：40dp（3x 屏 120px）与 56dp（3x 屏 168px）**不能**共用一条缓存。
        //   共用之后 Coil 的 `isCacheValueValid` 会因尺寸不符判无效 ⇒ 反复重解码。
        val listIcon = siteIconCacheKey(url, 120)
        val detailIcon = siteIconCacheKey(url, 168)
        assertThat(listIcon).isNotEqualTo(detailIcon)
    }

    @Test
    fun URL不同_键必须不同() {
        // 不同站点必须各自一条。若键只由尺寸决定，全列表的图标会互相覆盖成同一张图。
        val github = siteIconCacheKey("https://v.example.com/icons/github.com/icon.png", 120)
        val gitlab = siteIconCacheKey("https://v.example.com/icons/gitlab.com/icon.png", 120)
        assertThat(github).isNotEqualTo(gitlab)
    }

    @Test
    fun 库不同_键必须不同() {
        // 同一个域名挂在两个自建服务器下时，图标是**两份不同的资源**（各自的图标代理）。
        val onA = siteIconCacheKey("https://a.example.com/icons/github.com/icon.png", 120)
        val onB = siteIconCacheKey("https://b.example.com/icons/github.com/icon.png", 120)
        assertThat(onA).isNotEqualTo(onB)
    }

    @Test
    fun 键带命名空间前缀_不与其它Coil缓存撞车() {
        // Coil 默认键就是 URL 字符串本身（`UriKeyer` 的产物）。我们的键若也**以 URL 开头**，
        // 将来若有别处用默认键请求同一张图，两条键会指向同一格缓存却语义不同。
        //
        // ⚠️ 这里**只能**断言前缀，不能断言「不含 `://`」：键里刻意保留了原始 URL
        // （见下一个用例「尺寸段可读」），URL 本身就带 `://`。
        // 本条最初写成了 `doesNotContain("://")` —— CI #332 立刻抓到
        // （1428 例失败 1 例）。这不是实现错，是**断言与注释里写的意图不一致**：
        // 想表达的是"不许冒充 URL"，写成了"不许出现 URL 的特征字符"。
        val key = siteIconCacheKey(url, 120)
        assertThat(key).startsWith("site-icon:")
        assertThat(key).doesNotStartWith("http")
        // 前缀里必须带尺寸段，且整条键**不能**等于裸 URL（否则真的会和默认键撞车）。
        assertThat(key).isNotEqualTo(url)
    }

    @Test
    fun 键包含可读的尺寸段_便于排查() {
        // 缓存文件/键里的尺寸是排查"图标糊 / 不糊"的唯一线索，
        // 别把它做成哈希或下标（那样 dump 出来看不出是哪个尺寸）。
        assertThat(siteIconCacheKey(url, 168)).contains("168")
        assertThat(siteIconCacheKey(url, 120)).contains("120")
    }
}
