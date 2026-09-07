package com.bastion.app.autofill_ng

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.bastion.app.autofill_ng.EnhancedAutofillStructureParserV2.Accuracy
import com.bastion.app.autofill_ng.EnhancedAutofillStructureParserV2.FieldHint

/**
 * 识别层登录字段保留规则回归测试（P1，替代原 WeakLoginGuardTest 服务层守卫）。
 *
 * P1 拆掉了 weakLoginContext（无密码框 + 有 Login 类型字段即全量放行）与服务层守卫
 * shouldSuppressWeakLoginSuggestion，判定下沉到识别层：
 *   AutofillDetectionPolicy.shouldKeepLoginField —— 无密码框时账号类字段仅当
 *   MEDIUM+（真实信号）才保留，LOWEST 弱信号字段等价于 bitwarden 的 Unused 被丢弃。
 *
 * 场景对应：
 * - 京东搜索栏：孤立弱账号字段（现为 UNKNOWN / 或残留 LOWEST 路径）→ 不保留、不弹 ✅
 * - 电影猎手等原生登录页：有密码框 → 弱账号字段也保留、正常弹 ✅
 * - native id="username"（MEDIUM）无密码框：保留、弹（对齐 bitwarden 识别即 Fillable）✅
 */
class LoginFieldRecognitionTest {

    // --- 无密码框 + 弱账号字段 → 不保留（丢弃，不弹）---

    @Test
    fun lowestUsernameWithoutPassword_isDropped() {
        assertFalse(
            AutofillDetectionPolicy.shouldKeepLoginField(
                hint = FieldHint.USERNAME,
                accuracy = Accuracy.LOWEST,
                hasPasswordInItems = false,
            )
        )
    }

    @Test
    fun lowUsernameWithoutPassword_isDropped() {
        assertFalse(
            AutofillDetectionPolicy.shouldKeepLoginField(
                hint = FieldHint.USERNAME,
                accuracy = Accuracy.LOW,
                hasPasswordInItems = false,
            )
        )
    }

    @Test
    fun lowestEmailWithoutPassword_isDropped() {
        assertFalse(
            AutofillDetectionPolicy.shouldKeepLoginField(
                hint = FieldHint.EMAIL_ADDRESS,
                accuracy = Accuracy.LOWEST,
                hasPasswordInItems = false,
            )
        )
    }

    // --- 无密码框 + 真实信号账号字段 → 保留（弹，对齐 bitwarden）---

    @Test
    fun mediumUsernameWithoutPassword_isKept() {
        assertTrue(
            AutofillDetectionPolicy.shouldKeepLoginField(
                hint = FieldHint.USERNAME,
                accuracy = Accuracy.MEDIUM,
                hasPasswordInItems = false,
            )
        )
    }

    @Test
    fun highPhoneWithoutPassword_isKept() {
        assertTrue(
            AutofillDetectionPolicy.shouldKeepLoginField(
                hint = FieldHint.PHONE_NUMBER,
                accuracy = Accuracy.HIGH,
                hasPasswordInItems = false,
            )
        )
    }

    @Test
    fun highestEmailWithoutPassword_isKept() {
        assertTrue(
            AutofillDetectionPolicy.shouldKeepLoginField(
                hint = FieldHint.EMAIL_ADDRESS,
                accuracy = Accuracy.HIGHEST,
                hasPasswordInItems = false,
            )
        )
    }

    // --- 有密码框 → 账号字段全量保留（真实登录页保护）---

    @Test
    fun lowestUsernameWithPassword_isKept() {
        // 电影猎手等原生登录页：密码框在 → 弱账号字段也保留，正常弹密码
        assertTrue(
            AutofillDetectionPolicy.shouldKeepLoginField(
                hint = FieldHint.USERNAME,
                accuracy = Accuracy.LOWEST,
                hasPasswordInItems = true,
            )
        )
    }

    // --- 非账号类 hint → 始终保留，由下游 isSupportedFillableHint 等处理 ---

    @Test
    fun passwordHint_isAlwaysKept() {
        assertTrue(
            AutofillDetectionPolicy.shouldKeepLoginField(
                hint = FieldHint.PASSWORD,
                accuracy = Accuracy.LOWEST,
                hasPasswordInItems = false,
            )
        )
    }

    @Test
    fun cardHint_isAlwaysKept() {
        assertTrue(
            AutofillDetectionPolicy.shouldKeepLoginField(
                hint = FieldHint.CREDIT_CARD_NUMBER,
                accuracy = Accuracy.MEDIUM,
                hasPasswordInItems = false,
            )
        )
    }

    @Test
    fun otpHint_isAlwaysKept() {
        assertTrue(
            AutofillDetectionPolicy.shouldKeepLoginField(
                hint = FieldHint.OTP_CODE,
                accuracy = Accuracy.LOW,
                hasPasswordInItems = false,
            )
        )
    }
}
