package io.vaultix.data.repository.kdbx

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * KDBX **解锁组**（2026-11-10 定稿）的单测。
 *
 * ## 这里守的是「份数上限」与「驱逐顺序」
 *
 * 「切库不锁」这个体验改动的**全部安全性**都压在本类上：一旦上限失效，
 * 它就退化成"永不锁"，`#60` 的内存约束彻底消失。所以本文件的第一职责
 * 不是验"能不能记账"，而是验 **`note` 返回的驱逐名单永远够用**。
 *
 * ## 为什么用普通 JUnit（没有 `runTest`）
 *
 * 本类是纯逻辑：不启动协程、不 `delay`、不读时钟。时间维度那一版
 * （`KdbxGraceLocks`）必须用虚拟时钟，因为它的核心是"到点才执行"；
 * 现在判据换成了**生命周期**，于是测试也从一个"时间机器"退回成了几行断言 ——
 * 这本身就是判据变简单的信号。
 *
 * ⚠️ 别把 LRU 顺序写成"看起来对"：本文件对**驱逐顺序**逐条断言，
 * 因为顺序错了的表现是"刚用过的库被锁了、很久没用的反而留着"，
 * 在真机上极难复现（要刚好开满 3 个库）。
 */
class KdbxUnlockGroupTest {

    @Test
    fun `未超上限时不驱逐任何人`() {
        val group = KdbxUnlockGroup(maxUnlocked = 3)

        assertThat(group.note("a", listOf("a"))).isEmpty()
        assertThat(group.note("b", listOf("a", "b"))).isEmpty()
        assertThat(group.note("c", listOf("a", "b", "c"))).isEmpty()
        assertThat(group.ids()).containsExactly("a", "b", "c")
    }

    @Test
    fun `超上限时驱逐最久未用的那个`() {
        val group = KdbxUnlockGroup(maxUnlocked = 3)
        group.note("a", listOf("a"))
        group.note("b", listOf("a", "b"))
        group.note("c", listOf("a", "b", "c"))

        // 进第 4 个库 ⇒ 必须有人让位。a 最久没用 ⇒ 被驱逐。
        val evicted = group.note("d", listOf("a", "b", "c", "d"))

        assertThat(evicted).containsExactly("a")
    }

    @Test
    fun `刚用过的库不会被自己驱逐`() {
        // 这是最容易写错的一处：若 `note` 先记别人、后记自己（或用了 <= 比较），
        // 本次进入的库可能被排成"最旧" ⇒ 用户刚打开就被锁，表现为"点进去又要解锁"。
        val group = KdbxUnlockGroup(maxUnlocked = 2)
        group.note("a", listOf("a"))
        group.note("b", listOf("a", "b"))

        val evicted = group.note("c", listOf("a", "b", "c"))

        assertThat(evicted).doesNotContain("c")
        assertThat(evicted).containsExactly("a")
    }

    @Test
    fun `访问会刷新顺序_反复用 a 时被挤掉的是 b`() {
        val group = KdbxUnlockGroup(maxUnlocked = 3)
        group.note("a", listOf("a"))
        group.note("b", listOf("a", "b"))
        group.note("c", listOf("a", "b", "c"))
        // 回头又用了 a（切换回去看一眼）⇒ a 变成最新。
        group.note("a", listOf("a", "b", "c"))

        val evicted = group.note("d", listOf("a", "b", "c", "d"))

        // 若访问不刷新顺序，这里会驱逐 a —— 而用户刚刚才用过它。
        assertThat(evicted).containsExactly("b")
    }

    @Test
    fun `一次超两个时按最久未用顺序驱逐`() {
        // `maxUnlocked` 被调小、或真源里出现了本类没见过的库时，一次可能超多个。
        // 只驱逐一个会让"上限"名存实亡（下次 note 又超）。
        val group = KdbxUnlockGroup(maxUnlocked = 2)
        group.note("a", listOf("a"))
        group.note("b", listOf("a", "b"))
        group.note("c", listOf("a", "b", "c"))

        // 此时已超 1 个（但上面那次已驱逐 a）⇒ 再进两个，一次超 2。
        val evicted = group.note("e", listOf("b", "c", "d", "e"))

        assertThat(evicted).hasSize(2)
        // b、c 都比 d、e 旧（d 是本类没见过的，补记时拿到较小的序号）
        assertThat(evicted).containsExactly("b", "c")
    }

    @Test
    fun `真源里已被锁掉的库不占名额`() {
        // 场景：用户在别处锁掉了 b（或 b 的会话因异常消失），但本类还记着它。
        // 不跟真源对齐 ⇒ 本类以为有 3 个，实际只有 2 个 ⇒ 下一次 note 会误驱逐一个
        // **仍在用**的库（用户看到的是"莫名锁了一个"）。
        val group = KdbxUnlockGroup(maxUnlocked = 3)
        group.note("a", listOf("a"))
        group.note("b", listOf("a", "b"))
        group.note("c", listOf("a", "b", "c"))

        // b 已不在真源里 ⇒ 这次进 d 不该驱逐任何人（实际只有 a、c、d 三个）。
        val evicted = group.note("d", listOf("a", "c", "d"))

        assertThat(evicted).isEmpty()
        assertThat(group.ids()).doesNotContain("b")
    }

    @Test
    fun `真源里没见过的库会被补记且排在本次使用之前`() {
        // 冷启动自动恢复会直接把库塞进会话，不经过 `note`。
        // 若补记发生在"记自己"之后，这些库会拿到更大的序号 ⇒ 本次进入的库被排成最旧。
        val group = KdbxUnlockGroup(maxUnlocked = 2)

        // a 是"没见过"的（模拟冷启动恢复），x 是本次进入的。
        val evicted = group.note("x", listOf("a", "x"))

        assertThat(evicted).isEmpty()
        assertThat(group.ids()).containsExactly("a", "x")
        // 再进一个 ⇒ 被驱逐的必须是 a（更旧），不是刚进的 x。
        assertThat(group.note("y", listOf("a", "x", "y"))).containsExactly("a")
    }

    @Test
    fun `forget 之后不再占名额`() {
        val group = KdbxUnlockGroup(maxUnlocked = 2)
        group.note("a", listOf("a"))
        group.note("b", listOf("a", "b"))

        group.forget("a")
        assertThat(group.ids()).containsExactly("b")

        // a 已让位 ⇒ 进 c 不该驱逐 b。
        assertThat(group.note("c", listOf("b", "c"))).isEmpty()
    }

    @Test
    fun `clear 后从零开始`() {
        val group = KdbxUnlockGroup(maxUnlocked = 2)
        group.note("a", listOf("a"))
        group.note("b", listOf("a", "b"))

        group.clear()

        assertThat(group.ids()).isEmpty()
        assertThat(group.note("c", listOf("c"))).isEmpty()
    }

    @Test
    fun `默认上限是 3`() {
        // 钉住常量本身：调大它等于放宽 `#60` 的份数约束，必须是一次**有意识的**改动。
        assertThat(KdbxUnlockGroup.DEFAULT_MAX_UNLOCKED).isEqualTo(3)
    }
}
