package io.vaultix.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 密码强度评分（移植 Bastion PasswordStrengthCalculator）：
 * 分级边界、长度/多样性权重、重复与序列惩罚、混合奖励。
 */
class PasswordStrengthTest {

    @Test
    fun emptyIsWeakZero() {
        assertEquals(0, PasswordStrength.score(""))
        assertEquals(PasswordStrength.Level.WEAK, PasswordStrength.levelOf(0))
    }

    @Test
    fun longDiversePasswordScoresStrongOrVeryStrong() {
        val pwd = "Tr0ub4dor&3-Tr0ub4dor"
        val s = PasswordStrength.score(pwd)
        assertTrue("expected >=76, got $s", s >= 76)
        val level = PasswordStrength.levelOf(s)
        assertTrue(
            "expected STRONG/VERY_STRONG, got $level",
            level == PasswordStrength.Level.STRONG || level == PasswordStrength.Level.VERY_STRONG,
        )
    }

    @Test
    fun simplePasswordScoresWeak() {
        val s = PasswordStrength.score("123456")
        assertTrue(s <= 40)
    }

    @Test
    fun levelBoundariesMatchScoreRanges() {
        assertEquals(PasswordStrength.Level.WEAK, PasswordStrength.levelOf(40))
        assertEquals(PasswordStrength.Level.FAIR, PasswordStrength.levelOf(41))
        assertEquals(PasswordStrength.Level.FAIR, PasswordStrength.levelOf(60))
        assertEquals(PasswordStrength.Level.GOOD, PasswordStrength.levelOf(61))
        assertEquals(PasswordStrength.Level.GOOD, PasswordStrength.levelOf(75))
        assertEquals(PasswordStrength.Level.STRONG, PasswordStrength.levelOf(76))
        assertEquals(PasswordStrength.Level.STRONG, PasswordStrength.levelOf(90))
        assertEquals(PasswordStrength.Level.VERY_STRONG, PasswordStrength.levelOf(91))
    }

    @Test
    fun repeatedAndSequentialCharsArePenalized() {
        val repetitive = PasswordStrength.score("aaaaaaaaaaaaaa") // 长度满分但全重复
        val sequential = PasswordStrength.score("abcdefghijklmn") // 全递增序列
        val mixed = PasswordStrength.score("aB3x!kQ9zLm2wP8t")
        assertTrue(repetitive < mixed)
        assertTrue(sequential < mixed)
    }

    @Test
    fun twelvePlusWithThreeClassesGetsBonus() {
        // 手工核对：长度 30 + 多样性(3类) 25 + 唯一高 20 + 混合奖励 10 + 无惩罚 ≈ 85
        val pwd = "abcdefgh12!X"
        val s = PasswordStrength.score(pwd)
        assertTrue(s >= 60)
    }
}
