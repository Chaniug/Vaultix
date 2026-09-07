package com.bastion.app.utils

import android.content.Context
import com.bastion.app.R
import kotlin.math.log2

/**
 * 密码强度分析器
 * 
 * 实时分析密码强度并提供改进建议。
 * 
 * ## 评分系统 (0-100分)
 * 
 * ### 1. 长度评分 (30分)
 * - < 6 字符: 0分
 * - 6-8 字符: 10分
 * - 9-12 字符: 20分
 * - > 12 字符: 30分
 * 
 * ### 2. 字符多样性 (40分)
 * - 包含小写字母: +10分
 * - 包含大写字母: +10分
 * - 包含数字: +10分
 * - 包含特殊字符: +10分
 * 
 * ### 3. 复杂度 (20分)
 * - 无重复字符: +10分
 * - 无连续字符: +10分
 * 
 * ### 4. 唯一性 (10分)
 * - 不在常见密码库: +10分
 * 
 * ## 强度等级
 * - 0-20: 非常弱 (VERY_WEAK) 🔴
 * - 21-40: 弱 (WEAK) 🟠
 * - 41-60: 一般 (FAIR) 🟡
 * - 61-80: 强 (STRONG) 🟢
 * - 81-100: 非常强 (VERY_STRONG) 💚
 * 
 * ## 使用示例
 * ```kotlin
 * val password = "MyP@ssw0rd123"
 * val strength = PasswordStrengthAnalyzer.calculateStrength(password) // 85
 * val level = PasswordStrengthAnalyzer.getStrengthLevel(strength) // VERY_STRONG
 * val suggestions = PasswordStrengthAnalyzer.getSuggestions(password)
 * ```
 */
object PasswordStrengthAnalyzer {

    /**
     * 密码强度等级
     */
    enum class StrengthLevel {
        VERY_WEAK,  // 0-20: 非常弱
        WEAK,       // 21-40: 弱
        FAIR,       // 41-60: 一般
        STRONG,     // 61-80: 强
        VERY_STRONG // 81-100: 非常强
    }

    /**
     * 计算密码强度
     * 
     * @param password 待分析的密码
     * @return 强度分数 (0-100)
     */
    fun calculateStrength(password: String): Int {
        if (password.isEmpty()) return 0

        var score = 0

        // 1. 长度评分 (30分)
        score += calculateLengthScore(password)

        // 2. 字符多样性 (40分)
        score += calculateDiversityScore(password)

        // 3. 复杂度 (20分)
        score += calculateComplexityScore(password)

        // 4. 唯一性 (10分)
        score += calculateUniquenessScore(password)

        return score.coerceIn(0, 100)
    }

    /**
     * 获取强度等级
     * 
     * @param score 强度分数 (0-100)
     * @return 对应的强度等级
     */
    fun getStrengthLevel(score: Int): StrengthLevel {
        return when (score) {
            in 0..20 -> StrengthLevel.VERY_WEAK
            in 21..40 -> StrengthLevel.WEAK
            in 41..60 -> StrengthLevel.FAIR
            in 61..80 -> StrengthLevel.STRONG
            else -> StrengthLevel.VERY_STRONG
        }
    }

    /**
     * 获取强度等级的本地化描述
     * 
     * @param level 强度等级
     * @param context Android Context for string resources
     * @return 本地化描述
     */
    fun getStrengthLevelText(level: StrengthLevel, context: Context): String {
        return when (level) {
            StrengthLevel.VERY_WEAK -> context.getString(R.string.strength_very_weak)
            StrengthLevel.WEAK -> context.getString(R.string.strength_weak)
            StrengthLevel.FAIR -> context.getString(R.string.strength_fair)
            StrengthLevel.STRONG -> context.getString(R.string.strength_strong)
            StrengthLevel.VERY_STRONG -> context.getString(R.string.strength_very_strong)
        }
    }

    /**
     * 获取改进建议
     * 
     * @param password 待分析的密码
     * @param context Android Context for string resources
     * @return 建议列表
     */
    fun getSuggestions(password: String, context: Context): List<String> {
        if (password.isEmpty()) {
            return listOf(context.getString(R.string.suggestion_enter_password))
        }

        val suggestions = mutableListOf<String>()

        // 长度建议
        when {
            password.length < 6 -> suggestions.add(context.getString(R.string.suggestion_too_short))
            password.length < 8 -> suggestions.add(context.getString(R.string.suggestion_use_8_chars))
            password.length < 12 -> suggestions.add(context.getString(R.string.suggestion_use_12_chars))
        }

        // 字符类型建议
        if (!password.any { it.isLowerCase() }) {
            suggestions.add(context.getString(R.string.suggestion_add_lowercase))
        }
        if (!password.any { it.isUpperCase() }) {
            suggestions.add(context.getString(R.string.suggestion_add_uppercase))
        }
        if (!password.any { it.isDigit() }) {
            suggestions.add(context.getString(R.string.suggestion_add_digits))
        }
        if (!password.any { !it.isLetterOrDigit() }) {
            suggestions.add(context.getString(R.string.suggestion_add_special))
        }

        // 复杂度建议
        if (hasRepeatingCharacters(password)) {
            suggestions.add(context.getString(R.string.suggestion_avoid_repeating))
        }
        if (hasSequentialCharacters(password)) {
            suggestions.add(context.getString(R.string.suggestion_avoid_sequential))
        }

        // 常见密码警告
        if (isCommonPassword(password)) {
            suggestions.add(context.getString(R.string.suggestion_common_password))
        }

        // 熵值建议
        val entropy = calculateEntropy(password)
        if (entropy < 50) {
            suggestions.add(context.getString(R.string.suggestion_increase_randomness))
        }

        return suggestions
    }

    /**
     * 检测是否为常见密码
     * 
     * @param password 待检测的密码
     * @return true 如果是常见密码
     */
    fun isCommonPassword(password: String): Boolean {
        val lowerPassword = password.lowercase()
        return COMMON_PASSWORDS.contains(lowerPassword)
    }

    /**
     * 计算密码熵值（信息熵）
     * 
     * 熵值越高，密码越随机，越难破解。
     * 
     * @param password 密码
     * @return 熵值（bits）
     */
    fun calculateEntropy(password: String): Double {
        if (password.isEmpty()) return 0.0

        // 计算字符集大小
        var charsetSize = 0
        if (password.any { it.isLowerCase() }) charsetSize += 26
        if (password.any { it.isUpperCase() }) charsetSize += 26
        if (password.any { it.isDigit() }) charsetSize += 10
        if (password.any { !it.isLetterOrDigit() }) charsetSize += 32

        // 熵 = log2(字符集大小^密码长度)
        return password.length * log2(charsetSize.toDouble())
    }

    // ========== 私有方法 ==========

    /**
     * 计算长度评分 (30分)
     */
    private fun calculateLengthScore(password: String): Int {
        return when (password.length) {
            in 0..5 -> 0
            in 6..8 -> 10
            in 9..12 -> 20
            else -> 30
        }
    }

    /**
     * 计算字符多样性评分 (40分)
     */
    private fun calculateDiversityScore(password: String): Int {
        var score = 0
        if (password.any { it.isLowerCase() }) score += 10 // 小写字母
        if (password.any { it.isUpperCase() }) score += 10 // 大写字母
        if (password.any { it.isDigit() }) score += 10 // 数字
        if (password.any { !it.isLetterOrDigit() }) score += 10 // 特殊字符
        return score
    }

    /**
     * 计算复杂度评分 (20分)
     */
    private fun calculateComplexityScore(password: String): Int {
        var score = 0
        if (!hasRepeatingCharacters(password)) score += 10 // 无重复字符
        if (!hasSequentialCharacters(password)) score += 10 // 无连续字符
        return score
    }

    /**
     * 计算唯一性评分 (10分)
     */
    private fun calculateUniquenessScore(password: String): Int {
        return if (!isCommonPassword(password)) 10 else 0
    }

    /**
     * 检测重复字符 (如 aaa, 111, !!!)
     */
    private fun hasRepeatingCharacters(password: String): Boolean {
        if (password.length < 3) return false
        
        for (i in 0..password.length - 3) {
            if (password[i] == password[i + 1] && password[i] == password[i + 2]) {
                return true
            }
        }
        return false
    }

    /**
     * 检测连续字符 (如 abc, 123, xyz)
     */
    private fun hasSequentialCharacters(password: String): Boolean {
        if (password.length < 3) return false

        for (i in 0..password.length - 3) {
            val char1 = password[i]
            val char2 = password[i + 1]
            val char3 = password[i + 2]

            // 检查连续递增
            if (char2 == char1 + 1 && char3 == char2 + 1) {
                return true
            }
            // 检查连续递减
            if (char2 == char1 - 1 && char3 == char2 - 1) {
                return true
            }
        }
        return false
    }

    /**
     * 常见弱密码列表 (Top 100)
     * 
     * 来源: OWASP, Have I Been Pwned
     */
    private val COMMON_PASSWORDS = setOf(
        // 数字序列
        "123456", "12345678", "123456789", "1234567890",
        "000000", "111111", "123123", "654321",
        
        // 键盘模式
        "qwerty", "qwertyuiop", "asdfgh", "zxcvbn",
        "1q2w3e4r", "1qaz2wsx",
        
        // 常见单词
        "password", "password1", "password123",
        "admin", "administrator", "root",
        "user", "guest", "test", "demo",
        "welcome", "letmein", "login",
        
        // 名字
        "michael", "jennifer", "daniel", "jessica",
        "ashley", "matthew", "joshua", "david",
        
        // 品牌/产品
        "google", "facebook", "twitter", "instagram",
        "android", "iphone", "samsung", "apple",
        
        // 中文拼音常见密码
        "woaini", "woshishei", "nishishei",
        "woaini123", "wo123456", "aini1314",
        
        // 日期相关
        "20220101", "19900101", "20000101",
        
        // 其他常见
        "sunshine", "princess", "dragon", "master",
        "monkey", "charlie", "football", "baseball",
        "superman", "batman", "trustno1", "starwars",
        "hello", "freedom", "whatever", "iloveyou",
        "abc123", "123abc", "pass", "access",
        "shadow", "ninja", "azerty", "solo",
        "mustang", "phoenix", "hunter", "ranger",
        "jordan", "matrix", "buster", "killer",
        "soccer", "hockey", "tigger", "summer",
        "winter", "spring", "autumn", "flower",
        "cookie", "cookie1", "lovely", "angel",
        "forever", "secret", "diamond", "silver",
        "golden", "purple", "orange", "yellow",
        "monday", "friday", "sunday", "january"
    )
}
