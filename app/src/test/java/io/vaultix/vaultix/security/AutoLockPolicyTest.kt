package io.vaultix.vaultix.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动锁定判定策略（语义对齐 Bastion autoLockMinutes 档位）：
 * 0 = 立即；>0 = 空闲 N 分钟；<0 = 从不；屏幕锁定时强制重验证。
 */
class AutoLockPolicyTest {

    // ---- 档位判定 ----

    @Test
    fun immediateMode_onlyWhenZero() {
        assertTrue(AutoLockPolicy.lockImmediatelyOnBackground(0))
        assertFalse(AutoLockPolicy.lockImmediatelyOnBackground(1))
        assertFalse(AutoLockPolicy.lockImmediatelyOnBackground(-1))
    }

    @Test
    fun neverMode_onlyWhenNegative() {
        assertTrue(AutoLockPolicy.neverAutoLock(-1))
        assertFalse(AutoLockPolicy.neverAutoLock(0))
        assertFalse(AutoLockPolicy.neverAutoLock(5))
    }

    // ---- 超时判定 ----

    @Test
    fun timeoutElapsed_whenLeavingLongerThanMinutes() {
        val at = 1_000_000L
        assertTrue(AutoLockPolicy.backgroundTimeoutElapsed(at + 5 * 60_000L, at, 5))
        // 恰好等于档位也判定超时（Bastion: elapsed >= minutes）
        assertTrue(AutoLockPolicy.backgroundTimeoutElapsed(at + 5 * 60_000L, at, 5))
        assertFalse(AutoLockPolicy.backgroundTimeoutElapsed(at + 5 * 60_000L - 1, at, 5))
    }

    @Test
    fun timeoutNotApplied_forNeverImmediateOrNoBackground() {
        val at = 1_000_000L
        assertFalse(AutoLockPolicy.backgroundTimeoutElapsed(at + 99 * 60_000L, at, AutoLockPolicy.NEVER))
        assertFalse(AutoLockPolicy.backgroundTimeoutElapsed(at + 99 * 60_000L, at, AutoLockPolicy.IMMEDIATE))
        assertFalse(AutoLockPolicy.backgroundTimeoutElapsed(at + 99 * 60_000L, null, 5))
    }

    // ---- 屏幕锁定 ----

    @Test
    fun screenLock_requiresRelock() {
        assertTrue(AutoLockPolicy.screenLockRequiresRelock(screenLocked = true))
        assertFalse(AutoLockPolicy.screenLockRequiresRelock(screenLocked = false))
    }
}
