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
 * 生成算法迁移自 Bastion（GPL-3.0，Copyright 2025 JoyinJoester）的
 * util/PasswordGenerator.kt；其中「最小字符数要求」为 Keyguard 的核心算法
 * （Bastion 注明与其融合）。本文件按 Vaultix 架构改写：
 * - 去除 zxcvbn / Android Context / Bastion logging 依赖（强度分析沿用本模块
 *   [PasswordStrength]，仅做「提示不做门槛」）；
 * - passphrase 词表使用内置后备词表（无资源文件依赖，纯 JVM 可测）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.common

import java.security.SecureRandom

/**
 * 随机密码生成器（Bastion/Keyguard 同款核心算法）。
 *
 * - [generatePassword]：字符集组合 + 最小字符数保证 + SecureRandom 洗牌；
 * - [generatePinCode]：纯数字 PIN；
 * - [generatePassphrase]：Diceware 风格词组（内置词表，支持首字母大写/插数字）。
 */
object PasswordGenerator {

    private const val UPPERCASE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val LOWERCASE = "abcdefghijklmnopqrstuvwxyz"
    private const val NUMBERS = "0123456789"
    private const val SYMBOLS = "!@#$%^&*()_+-=[]{}|;:,.<>?"

    /** 易混淆字符（excludeSimilar 时剔除）：0/O/l/I/1。 */
    private const val SIMILAR_CHARS = "0OlI1"

    /** 歧义符号（excludeAmbiguous 时剔除）。 */
    private const val AMBIGUOUS_CHARS = "{}[]()/~`'\""

    private val random = SecureRandom()

    /**
     * 生成随机密码。
     *
     * @param length 目标长度
     * @param uppercaseMin / lowercaseMin / numbersMin / symbolsMin
     *   各字符集的**最小出现次数**（Keyguard 特性：先按最小数放置，再填充剩余并整体洗牌）
     * @param excludeSimilar 剔除易混淆字符（0OlI1）
     * @param excludeAmbiguous 剔除歧义符号
     * @return 生成的密码；字符集全被排除或长度非法时返回空串
     */
    fun generatePassword(
        length: Int,
        uppercase: Boolean = true,
        lowercase: Boolean = true,
        numbers: Boolean = true,
        symbols: Boolean = true,
        uppercaseMin: Int = 0,
        lowercaseMin: Int = 0,
        numbersMin: Int = 0,
        symbolsMin: Int = 0,
        excludeSimilar: Boolean = false,
        excludeAmbiguous: Boolean = false,
    ): String {
        if (length <= 0) return ""
        val upper = filter(if (uppercase) UPPERCASE else "", excludeSimilar, excludeAmbiguous)
        val lower = filter(if (lowercase) LOWERCASE else "", excludeSimilar, excludeAmbiguous)
        val number = filter(if (numbers) NUMBERS else "", excludeSimilar, excludeAmbiguous)
        val symbol = filter(if (symbols) SYMBOLS else "", excludeSimilar, excludeAmbiguous)
        val all = upper + lower + number + symbol
        if (all.isEmpty()) return ""

        val output = mutableListOf<Char>()
        fun takeFrom(chars: String, count: Int) {
            repeat(count) { if (chars.isNotEmpty()) output += chars[random.nextInt(chars.length)] }
        }
        // Phase 1：满足各字符集最小出现次数
        takeFrom(upper, uppercaseMin)
        takeFrom(lower, lowercaseMin)
        takeFrom(number, numbersMin)
        takeFrom(symbol, symbolsMin)
        // Phase 2：填充剩余长度
        takeFrom(all, length - output.size)
        // Phase 3：整体洗牌（保证最小字符数不暴露位置规律）
        return output.take(length).shuffled(random).joinToString("")
    }

    // PIN 长度上下限（对齐 Bastion 约束的宽松版）
    private const val PIN_DEFAULT_LENGTH = 6
    private const val PIN_MIN_LENGTH = 3
    private const val PIN_MAX_LENGTH = 12
    private const val PIN_ALPHABET_SIZE = 10

    /** 生成纯数字 PIN（长度 3–12）。 */
    fun generatePinCode(length: Int = PIN_DEFAULT_LENGTH): String {
        require(length in PIN_MIN_LENGTH..PIN_MAX_LENGTH) { "PIN length must be between 3 and 12" }
        return (1..length).map { random.nextInt(PIN_ALPHABET_SIZE) }.joinToString("")
    }

    /**
     * 生成 Diceware 风格密码短语。
     *
     * @param wordCount 单词数
     * @param delimiter 分隔符
     * @param capitalize 单词首字母大写
     * @param includeNumber 在随机一个单词后附加数字
     */
    fun generatePassphrase(
        wordCount: Int = 4,
        delimiter: String = "-",
        capitalize: Boolean = false,
        includeNumber: Boolean = false,
    ): String {
        require(wordCount > 0) { "Word count must be greater than zero" }
        val phrases = buildList {
            repeat(wordCount) {
                val word = WORDLIST[random.nextInt(WORDLIST.size)]
                add(
                    if (capitalize) {
                        word.replaceFirstChar { c -> c.uppercaseChar() }
                    } else {
                        word
                    },
                )
            }
        }
        if (!includeNumber) return phrases.joinToString(delimiter)
        val targetIndex = random.nextInt(phrases.size)
        val numberRange = when {
            wordCount == 1 -> NUMBER_RANGE_ONE_WORD
            wordCount == 2 -> NUMBER_RANGE_TWO_WORDS
            else -> NUMBER_RANGE_DEFAULT
        }
        val number = random.nextInt(numberRange.last - numberRange.first + 1) + numberRange.first
        return phrases.mapIndexed { i, w -> if (i == targetIndex) "$w$number" else w }
            .joinToString(delimiter)
    }

    /** 应用排除规则（易混淆字符 / 歧义符号）。 */
    private fun filter(charset: String, excludeSimilar: Boolean, excludeAmbiguous: Boolean): String {
        var result = charset
        if (excludeSimilar) result = result.filter { it !in SIMILAR_CHARS }
        if (excludeAmbiguous) result = result.filter { it !in AMBIGUOUS_CHARS }
        return result
    }

    // passphrase 附加数字的取值范围（Bastion 同款）：词越多数位越短
    private val NUMBER_RANGE_ONE_WORD = 1000..9999
    private val NUMBER_RANGE_TWO_WORDS = 100..999
    private val NUMBER_RANGE_DEFAULT = 10..99

    /**
     * 内置后备词表（摘自 Bastion 的后备词表；体积小、纯 ASCII、均为 3–5 字母常见词，
     * 4 词组合约 26.5 bits 熵，配合数字与长度可满足轻量口令场景）。
     */
    private val WORDLIST = listOf(
        "able", "about", "above", "abuse", "actor", "acute", "admit", "adopt", "adult", "after",
        "again", "agent", "agree", "ahead", "alarm", "album", "alert", "alike", "alive", "allow",
        "alone", "along", "alter", "among", "anger", "angle", "angry", "apart", "apple", "apply",
        "arena", "argue", "arise", "array", "aside", "asset", "avoid", "awake", "award", "aware",
        "badly", "baker", "bases", "basic", "beach", "began", "begin", "bench", "billy", "birth",
        "black", "blame", "blind", "block", "blood", "board", "boost", "booth", "bound", "brain",
        "brand", "brass", "brave", "bread", "break", "breed", "brief", "bring", "broad", "broke",
        "brown", "build", "built", "buyer", "cable", "calif", "carry", "catch", "cause", "chain",
        "chair", "chaos", "charm", "chart", "chase", "cheap", "check", "chest", "chief", "child",
        "china", "chose", "civil", "claim", "class", "clean", "clear", "click", "climb", "clock",
    )
}
