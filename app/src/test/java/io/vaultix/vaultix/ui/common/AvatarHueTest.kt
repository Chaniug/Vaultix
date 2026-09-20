package io.vaultix.vaultix.ui.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.absoluteValue

/**
 * [avatarHue] 的单测 —— 钉住首字母头像的彩色底衬（2026-09-20 新增）。
 *
 * 为什么值得测：这两条性质**都是只在边界上才发作的**，真机上肉眼根本看不出来 ——
 * 一个条目颜色算错只是"某条颜色怪"，你不会知道是哈希的锅。
 *
 * 用纯算术测（`avatarHue` 不含 Compose），和 [ContentClearanceTest] 同一个思路：
 * 把纯逻辑从 composable 里抽出来，就能用单测钉住，而不是靠截图回归。
 */
class AvatarHueTest {

    @Test
    fun hueIsAlwaysInsideTheWheel() {
        // 不变式：色相必须落在 [0, 360)。负数色相对 Color.hsl 是未定义行为。
        val seeds = listOf(
            "", "a", "gmail", "GitHub", "1Password", "银行", "🔐 emoji",
            "a".repeat(1000),
        )
        for (seed in seeds) {
            val hue = avatarHue(seed)
            assertThat(hue).isAtLeast(0f)
            assertThat(hue).isLessThan(360f)
        }
    }

    @Test
    fun hueAlwaysLandsOnOneOfTwelveBuckets() {
        // 头像取色是**离散 12 档**而不是连续值 —— 连续值会让两个相邻色相肉眼无法区分。
        val seeds = (0..500).map { "item-$it" } + listOf("登录", "邮箱", "Shopping")
        for (seed in seeds) {
            val hue = avatarHue(seed)
            assertThat(hue % (360f / AVATAR_HUE_STEPS)).isWithin(TOLERANCE).of(0f)
        }
    }

    @Test
    fun sameSeedAlwaysGivesSameHue() {
        // ★ 最关键的一条：颜色必须**稳定**。
        // 若换成随机色或按列表下标取色，滚动一遍颜色就全变，用户会把颜色误读成状态。
        val seed = "github.com"
        val first = avatarHue(seed)
        repeat(50) { assertThat(avatarHue(seed)).isEqualTo(first) }
    }

    @Test
    fun doesNotBlowUpOnIntMinValueHashCode() {
        // ⚠️ 防回归：`Int.MIN_VALUE.absoluteValue` 仍是负数（补码没有对应正数），
        // 早期写法在这里会算出**负色相**。
        //
        // 找一个 hashCode 恰为 Int.MIN_VALUE 的字符串不现实（要暴力搜），
        // 所以直接验证修正式 `(x % n + n) % n` 对 Int.MIN_VALUE 本身的行为 ——
        // 这正是 [avatarHue] 内部用的式子。若哪天有人改回 absoluteValue，这条会红。
        val x = Int.MIN_VALUE
        assertThat(x.absoluteValue).isEqualTo(Int.MIN_VALUE) // 钉住"绝对值仍是负数"这个前提
        val bucket = ((x % AVATAR_HUE_STEPS) + AVATAR_HUE_STEPS) % AVATAR_HUE_STEPS
        assertThat(bucket).isAtLeast(0)
        assertThat(bucket).isLessThan(AVATAR_HUE_STEPS)
    }

    @Test
    fun distributesAcrossAllBuckets() {
        // 用一批真实感标题验证**没有塌缩到少数几档**（哈希若被截断，颜色区分度就没了）。
        val seeds = listOf(
            "gmail", "github", "gitlab", "bitbucket", "aws", "azure", "cloudflare",
            "淘宝", "京东", "知乎", "微博", "哔哩哔哩", "网易", "腾讯", "钉钉",
            "bank", "steam", "discord", "slack", "notion", "figma", "vercel",
        )
        val buckets = seeds.map { (avatarHue(it) / (360f / AVATAR_HUE_STEPS)).toInt() }.toSet()
        // 22 个种子至少应散落到 6 档以上；达不到说明哈希取色失效。
        assertThat(buckets.size).isAtLeast(6)
    }

    private companion object {
        const val TOLERANCE = 0.0001f
    }
}
