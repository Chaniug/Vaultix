package io.vaultix.data.repository

import io.vaultix.model.VaultKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「切库时该不该锁其它 KDBX 库」的判据（#246，2026-11-04）。
 *
 * ## 这条缺陷为什么必须有用例钉住
 *
 * 旧实现没有判据，只有 `Kdbx.unlockedIds().filter { it != keepVaultId }` ——
 * 它把"进 Bitwarden 库"也读成了"要锁掉所有 KDBX"。后果**完全静默**：
 *
 * ```
 * KDBX 库解锁 → 切到 Bitwarden 库 → 所有 KDBX 会话被销毁
 *   → 切回 KDBX → 重跑一次完整 Argon2（64 MiB / 46 轮）+ 可能重下整库
 * ```
 *
 * 用户看到的是「切换密码库后 kdbx 就锁定，每次解锁都很慢」，
 * 而代码里没有任何一处会报错、也没有一条用例会红 —— 所以它活了很久。
 *
 * ⚠️ 变异验证：把判据改成 `keepKind != null`（"有库就锁"）或直接 `return true`，
 * 第一条用例必须报红。只留 `null` 那条是不够的 —— 那种写法对两种真实类型都判 true。
 */
class ShouldLockOtherKdbxTest {

    @Test
    fun `进入的是 Bitwarden 库_不该锁 KDBX`() {
        assertFalse(
            "进 Bitwarden 库不涉及任何 KDBX 明文，锁它只会让用户切回来时重跑一次 KDF",
            shouldLockOtherKdbx(VaultKind.BITWARDEN),
        )
    }

    @Test
    fun `进入的是 KDBX 库_才需要锁其它 KDBX`() {
        assertTrue(
            "两个 KDBX 整库明文同时常驻破坏「同时只进一个库」的约束，这时才该锁",
            shouldLockOtherKdbx(VaultKind.KDBX),
        )
    }

    @Test
    fun `库类型未知_不锁`() {
        assertFalse(
            "取不到库行/类型未知时无从判断是否多库常驻；误锁的代价远大于少锁一次",
            shouldLockOtherKdbx(null),
        )
    }
}
