package io.vaultix.data.repository.kdbx

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * 切库宽限期（2026-11-10，真机反馈「B/C 互锁 + OneDrive 库指纹解锁很慢」）。
 *
 * ## 这里守的是「时机」，不是「要不要锁」
 *
 * 「进 KDBX ⇒ 其它 KDBX 最终必须锁」这条判据由 `ShouldLockOtherKdbxTest` 守着，
 * 本文件只回答**什么时候真的执行**。两者分开测是有意的：
 * 判据错了是安全问题，时机错了是体验问题，混在一个用例里红了你不知道是哪一类。
 *
 * ## ★ 最容易被写错的一条：重复进入不能续期
 *
 * 若每次 `keep` 都重置计时，「用户在 C 里反复点几下别的库」就会让 B 永远不到点。
 * 那种实现看起来人畜无害（每次只是 `delay(60s)`），跑起来就是**永不锁定** ——
 * 而 `#60` 的整个内存约束都建立在"最终会锁"之上。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KdbxGraceLocksTest {

    private val expired = mutableListOf<String>()

    @Test
    fun `宽限期内不锁_到点才锁`() = runTest {
        val locks = KdbxGraceLocks(scope = this, onExpire = { expired += it })

        locks.keep(keepVaultId = "C", unlocked = listOf("B", "C"))

        advanceTimeBy(KdbxGraceLocks.DEFAULT_GRACE_MS - 1)
        runCurrent()
        assertThat(expired).isEmpty()

        advanceTimeBy(1)
        runCurrent()
        assertThat(expired).containsExactly("B")
    }

    @Test
    fun `宽限期内切回_撤销自己的倒计时`() = runTest {
        val locks = KdbxGraceLocks(scope = this, onExpire = { expired += it })

        locks.keep(keepVaultId = "C", unlocked = listOf("B", "C"))
        // 30 秒后切回 B：B 的倒计时应当被撤销，C 重新开始计时。
        advanceTimeBy(30_000)
        runCurrent()
        locks.keep(keepVaultId = "B", unlocked = listOf("B", "C"))

        assertThat(locks.pendingIds()).containsExactly("C")

        // 再过 60 秒：只有 C 到点，B 始终没被锁。
        advanceTimeBy(KdbxGraceLocks.DEFAULT_GRACE_MS)
        runCurrent()
        assertThat(expired).containsExactly("C")
    }

    @Test
    fun `重复进入同一个库_不重置倒计时`() = runTest {
        val locks = KdbxGraceLocks(scope = this, onExpire = { expired += it })

        locks.keep(keepVaultId = "C", unlocked = listOf("B", "C"))
        // 中途又在同一个库上触发了一次（返回列表再点进来、配置变更等）。
        advanceTimeBy(30_000)
        runCurrent()
        locks.keep(keepVaultId = "C", unlocked = listOf("B", "C"))

        // 若实现重置了计时，这里还剩 30 秒 ⇒ 断言会红。
        advanceTimeBy(30_000)
        runCurrent()
        assertThat(expired).containsExactly("B")
    }

    @Test
    fun `cancel 撤销指定库的倒计时`() = runTest {
        val locks = KdbxGraceLocks(scope = this, onExpire = { expired += it })

        locks.keep(keepVaultId = "C", unlocked = listOf("B", "C"))
        locks.cancel("B")
        assertThat(locks.pendingIds()).isEmpty()

        advanceTimeBy(KdbxGraceLocks.DEFAULT_GRACE_MS)
        runCurrent()
        assertThat(expired).isEmpty()
    }

    @Test
    fun `cancelAll 清空所有倒计时`() = runTest {
        val locks = KdbxGraceLocks(scope = this, onExpire = { expired += it })

        locks.keep(keepVaultId = "keep", unlocked = listOf("a", "b", "keep"))
        assertThat(locks.pendingIds()).containsExactly("a", "b")

        locks.cancelAll()
        assertThat(locks.pendingIds()).isEmpty()

        advanceTimeBy(KdbxGraceLocks.DEFAULT_GRACE_MS)
        runCurrent()
        assertThat(expired).isEmpty()
    }

    @Test
    fun `同一次进入排出的多个库_到点全部锁掉`() = runTest {
        val locks = KdbxGraceLocks(scope = this, onExpire = { expired += it })

        // 用户同时开着 a / b，进入第三个库 ⇒ 两个都要排期。
        locks.keep(keepVaultId = "keep", unlocked = listOf("a", "b", "keep"))
        assertThat(locks.pendingIds()).containsExactly("a", "b")

        advanceTimeBy(KdbxGraceLocks.DEFAULT_GRACE_MS)
        runCurrent()
        assertThat(expired).containsExactly("a", "b")
    }

    @Test
    fun `撤销后重新排期_不从旧起点触发`() = runTest {
        val locks = KdbxGraceLocks(scope = this, onExpire = { expired += it })

        // t=0：进入 X ⇒ A 的倒计时定在 60s。
        locks.keep(keepVaultId = "X", unlocked = listOf("A", "X"))
        advanceTimeBy(30_000)
        runCurrent()

        // t=30：切回 A（撤销 A）⇒ X 排到 90s。
        locks.keep(keepVaultId = "A", unlocked = listOf("A", "X"))
        // t=40：又切回 X（撤销 X）⇒ A 重新排到 100s。
        advanceTimeBy(10_000)
        runCurrent()
        locks.keep(keepVaultId = "X", unlocked = listOf("A", "X"))

        // t=60：A 的**旧**起点到了 —— 必须已经被撤销，不能在这儿锁。
        advanceTimeBy(20_000)
        runCurrent()
        assertThat(expired).isEmpty()

        // t=90：X 的旧起点同理。
        advanceTimeBy(30_000)
        runCurrent()
        assertThat(expired).isEmpty()

        // t=100：A 的新起点 +60s，这时才锁。
        advanceTimeBy(10_000)
        runCurrent()
        assertThat(expired).containsExactly("A")
    }

    @Test
    fun `到点后从待锁集合里摘掉_不会残留`() = runTest {
        val locks = KdbxGraceLocks(scope = this, onExpire = { expired += it })

        locks.keep(keepVaultId = "C", unlocked = listOf("B", "C"))
        advanceTimeBy(KdbxGraceLocks.DEFAULT_GRACE_MS)
        runCurrent()

        // 残留会让下一次 keep 命中 `isActive == false` 却仍在 map 里的僵尸条目，
        // 也会让诊断输出误导人。
        assertThat(locks.pendingIds()).isEmpty()
    }
}
