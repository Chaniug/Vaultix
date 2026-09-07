/*
 * Vaultix — core:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 评分卡（长度/多样性/唯一字符/重复与序列惩罚/混合奖励及阈值）移植自 Bastion
 * 项目（GPL-3.0，Copyright 2025 JoyinJoester）的 utils/PasswordStrengthCalculator.kt，
 * 数值保持逐字一致以保证行为可对照；仅移除其中文描述文案（UI 层用资源展示）。
 * 为满足 Docs/16 复杂度门禁，将各评分段拆为独立函数（数值未变）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.common

/**
 * 密码强度评估（移植 Bastion PasswordStrengthCalculator，分值 0–100）。
 *
 * 用途：新建/编辑条目表单的强度提示（Docs/08 S10 强度条），非强制门槛；
 * 校验策略（checkPasswordRequirements）暂未移植，后续「设置 → 安全规则」需要时再加。
 *
 * @suppress MagicNumber：评分卡阈值/权重即算法参数，与 Bastion 逐字一致不可语义化改名。
 */
@Suppress("MagicNumber")
object PasswordStrength {

    enum class Level { WEAK, FAIR, GOOD, STRONG, VERY_STRONG }

    /** 0-40 弱 / 41-60 一般 / 61-75 良好 / 76-90 强 / 91-100 非常强 */
    fun score(password: String): Int {
        if (password.isEmpty()) return 0
        val diversity = diversityCount(password)
        var total = lengthScore(password)
        total += diversityScore(diversity)
        total += uniqueCharsScore(password)
        total -= repeatPenalty(password)
        total -= sequencePenalty(password)
        if (password.length >= LONG_BONUS_MIN && diversity >= DIVERSITY_BONUS_MIN) total += 10
        return total.coerceIn(0, 100)
    }

    fun levelOf(score: Int): Level = when {
        score <= 40 -> Level.WEAK
        score <= 60 -> Level.FAIR
        score <= 75 -> Level.GOOD
        score <= 90 -> Level.STRONG
        else -> Level.VERY_STRONG
    }

    // ---- 评分段（Bastion 数值逐字一致）----

    /** 长度（最高 30）。 */
    private fun lengthScore(password: String): Int = when {
        password.length < 6 -> 0
        password.length < 8 -> 5
        password.length < 10 -> 10
        password.length < 12 -> 15
        password.length < 16 -> 20
        password.length < 20 -> 25
        else -> 30
    }

    /** 字符多样性类数（小写/大写/数字/符号）。 */
    private fun diversityCount(password: String): Int =
        listOf(
            password.any { it.isLowerCase() },
            password.any { it.isUpperCase() },
            password.any { it.isDigit() },
            password.any { !it.isLetterOrDigit() },
        ).count { it }

    /** 多样性评分（最高 40）。 */
    private fun diversityScore(diversity: Int): Int = when (diversity) {
        1 -> 5
        2 -> 15
        3 -> 25
        4 -> 40
        else -> 0
    }

    /** 唯一字符数评分（最高 20）。 */
    private fun uniqueCharsScore(password: String): Int = when {
        password.toSet().size < 4 -> 0
        password.toSet().size < 6 -> 5
        password.toSet().size < 8 -> 10
        password.toSet().size < 10 -> 15
        else -> 20
    }

    /** 相邻重复惩罚（"aaa"→2；最多扣 20）。 */
    private fun repeatPenalty(password: String): Int {
        if (password.length < 2) return 0
        var repeats = 0
        for (i in 0 until password.length - 1) {
            if (password[i] == password[i + 1]) repeats++
        }
        return (repeats * 50 / password.length).coerceAtMost(20)
    }

    /** 递增/递减三元组惩罚（"abc"/"321"→1；最多扣 20）。 */
    private fun sequencePenalty(password: String): Int {
        if (password.length < 3) return 0
        var sequences = 0
        for (i in 0 until password.length - 2) {
            val a = password[i].code
            val b = password[i + 1].code
            val c = password[i + 2].code
            val ascending = b == a + 1 && c == b + 1
            val descending = b == a - 1 && c == b - 1
            if (ascending || descending) sequences++
        }
        return (sequences * 50 / password.length).coerceAtMost(20)
    }

    private const val LONG_BONUS_MIN = 12
    private const val DIVERSITY_BONUS_MIN = 3
}
