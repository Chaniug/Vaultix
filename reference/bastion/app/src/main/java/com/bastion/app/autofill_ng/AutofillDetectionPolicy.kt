package com.bastion.app.autofill_ng

import java.util.Locale
import com.bastion.app.autofill_ng.EnhancedAutofillStructureParserV2.Accuracy
import com.bastion.app.autofill_ng.EnhancedAutofillStructureParserV2.FieldHint

internal object AutofillDetectionPolicy {
    private val usernameLabelTranslations = listOf(
        "nickname",
        "username",
        "utilisateur",
        "login",
        "логин",
        "логін",
        "користувач",
        "пользовател",
        "用户名",
        "用戶名",
        "id",
        "customer",
    )

    fun genericNumberFallbackAccuracy(): Accuracy = Accuracy.LOW

    fun shouldKeepTarget(
        hint: FieldHint,
        accuracy: Accuracy,
        hasPasswordTarget: Boolean,
        manualRequest: Boolean,
    ): Boolean {
        if (manualRequest) return true
        if (!isAccountHint(hint)) return true
        return accuracy.score >= Accuracy.MEDIUM.score || hasPasswordTarget
    }

    /**
     * 识别层登录字段保留规则（P1，替代 weakLoginContext 全量放行分支）。
     *
     * 对齐 bitwarden「识别为 Login.Username 才 Fillable」：无密码框时，账号类字段仅当其
     * 精度达到 MEDIUM（真实信号：标准 autofill hint / id·name 术语）才保留；LOWEST 等弱
     * 信号字段等价于 bitwarden 的 Unused，直接丢弃、不参与弹出决策——孤立文本框
     * （搜索栏/昵称等）因此不会误弹。有密码框时全量放行（电影猎手等原生登录页的
     * 保护来源，与 hasPasswordInItems 分支一致）。
     *
     * 注意与 [shouldKeepTarget] 的区别：本函数不感知 manualRequest，识别层一律按
     * 「MEDIUM+ 或 有密码框」保留账号字段；手动请求的宽松由服务层 selectFillableTargets
     * 的 manualRequest 分支体现。
     */
    fun shouldKeepLoginField(
        hint: FieldHint,
        accuracy: Accuracy,
        hasPasswordInItems: Boolean,
    ): Boolean {
        if (!isAccountHint(hint)) return true
        return hasPasswordInItems || accuracy.score >= Accuracy.MEDIUM.score
    }

    fun shouldIncludeHiddenCredential(
        hint: FieldHint,
        accuracy: Accuracy,
    ): Boolean {
        // 密码类是强登录信号，即便是低精度（如 VISIBLE_PASSWORD / NUMBER_PASSWORD
        // 变体映射为 LOW）且当前不可见，也应纳入解析，避免电影猎手这类 App 在聚焦
        // 账号框时因密码框尚未可见而被整体丢弃、导致密码填充失效。账号类仍需较高
        // 精度，避免把隐藏的搜索/备注等误判为登录账号（QQ 搜索框修复不受影响）。
        if (isPasswordHint(hint)) return accuracy.score >= Accuracy.LOWEST.score
        val credentialHint = isAccountHint(hint)
        return credentialHint && accuracy.score >= Accuracy.MEDIUM.score
    }

    /**
     * 服务层守卫已随 P1 移除（2026-08-11）：
     * - 京东搜索栏误弹的根因（WEB_EDIT_TEXT 兜底 USERNAME:LOWEST + weakLoginContext 全量放行）
     *   已由 P0 的 UNKNOWN 语义 + P1 移除 weakLoginContext 在识别层解决，守卫失去存在意义；
     * - 其以 HIGH 为门槛拦截「MEDIUM 账号字段无密码框」页面（如 native id="username"）属于
     *   过度收紧，与 bitwarden「识别为 Login.Username 即 Fillable」不一致，一并移除。
     */

    fun matchesUsernameLabel(value: String): Boolean {
        val normalized = value.lowercase(Locale.ENGLISH).trim()
        if (normalized.isBlank()) return false
        return usernameLabelTranslations.any { translation ->
            if (translation == "id") {
                normalized
                    .split(Regex("[^\\p{L}\\p{N}]+"))
                    .any { token -> token == translation }
            } else {
                translation in normalized
            }
        }
    }

    fun matchesPhoneFieldName(value: String): Boolean {
        val normalized = value.lowercase(Locale.ENGLISH).trim()
        if (normalized.isBlank()) return false
        if (
            "phone" in normalized ||
            "mobile" in normalized ||
            "telephone" in normalized ||
            "手机号" in normalized ||
            "手機號" in normalized ||
            "电话号码" in normalized ||
            "電話號碼" in normalized
        ) {
            return true
        }
        return normalized
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .any { token -> token == "tel" }
    }

    private fun isAccountHint(hint: FieldHint): Boolean =
        hint == FieldHint.USERNAME ||
            hint == FieldHint.EMAIL_ADDRESS ||
            hint == FieldHint.PHONE_NUMBER

    private fun isPasswordHint(hint: FieldHint): Boolean =
        hint == FieldHint.PASSWORD || hint == FieldHint.NEW_PASSWORD
}
