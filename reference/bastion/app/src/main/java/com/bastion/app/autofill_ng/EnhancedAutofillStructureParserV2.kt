package com.bastion.app.autofill_ng

import com.bastion.app.logging.runCatchingObserved
import android.app.assist.AssistStructure
import android.os.Build
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId
import androidx.autofill.HintConstants
import com.bastion.app.autofill_ng.core.AutofillLogger
import com.bastion.app.autofill_ng.core.safeTextOrNull
import android.util.Log
import java.net.URL
import java.util.Locale

class EnhancedAutofillStructureParserV2 {
    private companion object {
        private val PACKAGE_NAME_REGEX =
            Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
        // 正则静态化：避免每次 EnhancedAutofillStructureParserV2() 实例化重编译。
        private val autofillLabelCreditCardNumberTranslations = listOf(
            ".*(credit|debit|card)+.*number.*".toRegex(),
            ".*(cc|card)[-_ ]?(no|num|number).*".toRegex(),
            ".*银行卡号.*".toRegex(),
            ".*信用卡号.*".toRegex(),
            ".*卡号.*".toRegex(),
        )
    }

    data class ParsedStructure(
        val applicationId: String? = null,
        val webScheme: String? = null,
        val webDomain: String? = null,
        val webView: Boolean = false,
        /** 从浏览器地址栏直接读到的网址原文（仅命中 [URL_BARS] 时非空）。 */
        val urlBarWebsite: String? = null,
        val items: List<ParsedItem>,
    )

    data class ParsedItem(
        val id: AutofillId,
        val hint: FieldHint,
        val accuracy: Accuracy,
        val value: String? = null,
        val isFocused: Boolean = false,
        val isVisible: Boolean = true,
        val parentWebViewNodeId: Int? = null,
        val traversalIndex: Int = 0,
    )

    enum class FieldHint {
        USERNAME,
        PASSWORD,
        NEW_PASSWORD,
        EMAIL_ADDRESS,
        PHONE_NUMBER,
        SEARCH_FIELD,
        CREDIT_CARD_NUMBER,
        CREDIT_CARD_EXPIRATION_DATE,
        CREDIT_CARD_EXPIRATION_MONTH,
        CREDIT_CARD_EXPIRATION_YEAR,
        CREDIT_CARD_SECURITY_CODE,
        CREDIT_CARD_HOLDER_NAME,
        POSTAL_ADDRESS,
        POSTAL_CODE,
        PERSON_NAME,
        PERSON_FIRST_NAME,
        PERSON_LAST_NAME,
        ADDRESS_CITY,
        ADDRESS_REGION,
        ADDRESS_COUNTRY,
        COMPANY_NAME,
        IDENTITY_NUMBER,
        OTP_CODE,
        UNKNOWN,
    }

    enum class Accuracy(val score: Float) {
        LOWEST(0.3f),
        LOW(0.7f),
        MEDIUM(1.5f),
        HIGH(4f),
        HIGHEST(10f),
    }

    private enum class InternalHint {
        USERNAME,
        PASSWORD,
        NEW_PASSWORD,
        EMAIL_ADDRESS,
        PHONE_NUMBER,
        CREDIT_CARD_NUMBER,
        CREDIT_CARD_EXPIRATION_DATE,
        CREDIT_CARD_EXPIRATION_MONTH,
        CREDIT_CARD_EXPIRATION_YEAR,
        CREDIT_CARD_EXPIRATION_DAY,
        CREDIT_CARD_SECURITY_CODE,
        CREDIT_CARD_HOLDER_NAME,
        POSTAL_ADDRESS,
        POSTAL_CODE,
        PERSON_NAME,
        PERSON_FIRST_NAME,
        PERSON_LAST_NAME,
        ADDRESS_CITY,
        ADDRESS_REGION,
        ADDRESS_COUNTRY,
        COMPANY_NAME,
        IDENTITY_NUMBER,
        OTP_CODE,
        OFF,
        UNKNOWN,
    }

    private data class RawParsedStructure(
        val webScheme: String? = null,
        val webDomain: String? = null,
        val webView: Boolean = false,
        val items: List<RawParsedItem>,
    )

    private data class RawParsedItem(
        val id: AutofillId,
        val accuracy: Accuracy,
        val hint: InternalHint,
        val value: String? = null,
        val reason: String? = null,
        val parentWebViewNodeId: Int? = null,
        val isFocused: Boolean = false,
        val isVisible: Boolean = true,
        val traversalIndex: Int = 0,
        val hasPasswordTerm: Boolean = false,
        val hasLoginTerm: Boolean = false,
    )

    private data class ParsedItemBuilder(
        val accuracy: Accuracy,
        val hint: InternalHint,
        val value: String? = null,
        val reason: String? = null,
    )

    private data class HintScore(
        val score: Float,
        val hint: InternalHint,
        val value: String?,
        val accuracy: Accuracy,
        val isFocused: Boolean,
        val isVisible: Boolean,
        val parentWebViewNodeId: Int?,
        val traversalIndex: Int,
    )

    /**
     * 浏览器包名 → 地址栏控件资源 id（`ViewNode.idEntry`）映射，对齐 bitwarden
     * `AutofillParserImpl.URL_BARS`。
     *
     * 用途：Edge / Opera / Brave / 三星等浏览器的 WebView 不总会上报 `webDomain`，
     * 此时**直接读地址栏文本**即可拿到权威网址，优于无障碍服务跟踪的 60s 启发式兜底
     * （BrowserAutofillContextStore），让条目匹配拿到正确域名。
     * Chrome 不在表内 —— 它通过 WebView 正常上报 webDomain，无需此路径。
     *
     * 新增浏览器时：用 `adb shell dumpsys` 或布局检查工具确认该浏览器地址栏的
     * `idEntry` 即可，注意同一浏览器不同渠道（beta/canary/nightly）包名不同。
     */
    private val URL_BARS: Map<String, String> = mapOf(
        // Edge 浏览器各渠道
        "com.microsoft.emmx" to "url_bar",
        "com.microsoft.emmx.beta" to "url_bar",
        "com.microsoft.emmx.canary" to "url_bar",
        "com.microsoft.emmx.dev" to "url_bar",
        // 三星浏览器
        "com.sec.android.app.sbrowser" to "location_bar_edit_text",
        "com.sec.android.app.sbrowser.beta" to "location_bar_edit_text",
        // Opera
        "com.opera.browser" to "url_bar",
        "com.opera.browser.beta" to "url_bar",
        // Brave
        "com.brave.browser" to "url_bar",
        "com.brave.browser_beta" to "url_bar",
        "com.brave.browser_nightly" to "url_bar",
    )

    private data class ParseContext(
        var traversalIndex: Int = 0,
        var nodesWithAutofillId: Int = 0,
        var nodesWithoutAutofillId: Int = 0,
        var totalNodesVisited: Int = 0,
        // 祖先搜索容器深度：进入 role=search/searchbox（或 class/id/data-* 含 search）的容器时 +1，
        // 离开时 -1。容器内（含容器自身）所有 input 据此稳定判定为搜索框，
        // 摆脱对「单个 input 自身 placeholder/htmlInfo 是否在本轮 AssistStructure 捕获中序列化」的依赖。
        // 直接修复「搜索框偶尔仍弹密码条目」的间歇性问题（捕获时序导致自身文本信号偶发缺失）。
        var searchContainerDepth: Int = 0,
        /**
         * 地址栏文本（当前网址）。命中 [URL_BARS] 的节点收集，取首个为准
         * （对齐 bitwarden `traversalDataList.flatMap { it.urlBarWebsites }.firstOrNull()`）。
         */
        val urlBarWebsites: MutableList<String> = mutableListOf(),
    )

    private class AutofillHintMatcher(
        val hint: InternalHint,
        val target: String,
        val partly: Boolean = false,
    ) {
        val accuracy = if (partly) Accuracy.MEDIUM else Accuracy.HIGH

        fun matches(value: String): Boolean = if (partly) {
            value.contains(target, ignoreCase = true)
        } else {
            value.equals(target, ignoreCase = true)
        }
    }

    private val autofillLabelPasswordTranslations = listOf(
        "password",
        "парол",
        "parol",
        "passwort",
        "passe",
        "密码",
        "密碼",
    )

    private val autofillLabelLoginTranslations = listOf(
        "username",
        "login",
        "account",
        "用户名",
        "用戶名",
        "账号",
        "帳號",
        "账户",
        "账户",
        "登录",
        "登錄",
        "логин",
        "логін",
        "identifiant",
        "utilisateur",
        "kullanıcı",
        "用户",
        "密码",
        "密碼",
        "password",
    )

    private val autofillLabel2faTranslations = listOf(
        "totp",
        "otp",
        "2fa",
    )

    private val autofillLabelEmailTranslations = listOf(
        "email",
        "e-mail",
        "почта",
        "пошта",
        "мейл",
        "мэйл",
        "майл",
        "电子邮箱",
        "電子郵箱",
    )

    private val autofillLabelCreditCardSecurityCodeTranslations = listOf(
        "cvv",
        "cvc",
        "security code",
        "security-code",
        "card code",
        "card-code",
        "安全码",
        "验证码",
    )

    private val autofillLabelCreditCardExpirationTranslations = listOf(
        "expiry",
        "expiration",
        "exp date",
        "exp-date",
        "valid thru",
        "valid-thru",
        "有效期",
        "到期",
    )

    private val autofillLabelCreditCardHolderTranslations = listOf(
        "cardholder",
        "card holder",
        "card-holder",
        "holder name",
        "holder-name",
        "name on card",
        "持卡人",
        "持有人",
    )

    private val autofillLabelPersonFirstNameTranslations = listOf(
        "first name",
        "given name",
        "名",
    )

    private val autofillLabelPersonLastNameTranslations = listOf(
        "last name",
        "family name",
        "surname",
        "姓",
    )

    private val autofillLabelPostalAddressTranslations = listOf(
        "address",
        "street",
        "地址",
    )

    private val autofillLabelIdentityNumberTranslations = listOf(
        "passport",
        "license",
        "document number",
        "identity",
        "证件",
        "身份证",
        "护照",
        "驾照",
        "社保",
        "ssn",
    )

    private val autofillHintMatchers = listOf(
        AutofillHintMatcher(
            hint = InternalHint.EMAIL_ADDRESS,
            target = HintConstants.AUTOFILL_HINT_EMAIL_ADDRESS,
        ),
        AutofillHintMatcher(
            hint = InternalHint.EMAIL_ADDRESS,
            target = "email",
            partly = true,
        ),
        AutofillHintMatcher(
            hint = InternalHint.USERNAME,
            target = HintConstants.AUTOFILL_HINT_USERNAME,
        ),
        AutofillHintMatcher(
            hint = InternalHint.USERNAME,
            target = "nickname",
        ),
        AutofillHintMatcher(
            hint = InternalHint.PASSWORD,
            target = HintConstants.AUTOFILL_HINT_PASSWORD,
        ),
        AutofillHintMatcher(
            hint = InternalHint.NEW_PASSWORD,
            target = "newPassword",
        ),
        AutofillHintMatcher(
            hint = InternalHint.NEW_PASSWORD,
            target = "new_password",
        ),
        AutofillHintMatcher(
            hint = InternalHint.PASSWORD,
            target = "password",
            partly = true,
        ),
        AutofillHintMatcher(
            hint = InternalHint.PHONE_NUMBER,
            target = HintConstants.AUTOFILL_HINT_PHONE,
        ),
        AutofillHintMatcher(
            hint = InternalHint.PHONE_NUMBER,
            target = HintConstants.AUTOFILL_HINT_PHONE_NUMBER,
        ),
        AutofillHintMatcher(
            hint = InternalHint.PHONE_NUMBER,
            target = "phone",
        ),
        AutofillHintMatcher(
            hint = InternalHint.NEW_PASSWORD,
            target = "new-password",
        ),
        AutofillHintMatcher(
            hint = InternalHint.NEW_PASSWORD,
            target = "new password",
        ),
        AutofillHintMatcher(
            hint = InternalHint.USERNAME,
            target = "new-username",
        ),
        AutofillHintMatcher(
            hint = InternalHint.CREDIT_CARD_NUMBER,
            target = HintConstants.AUTOFILL_HINT_CREDIT_CARD_NUMBER,
        ),
        AutofillHintMatcher(
            hint = InternalHint.CREDIT_CARD_NUMBER,
            target = "cc-number",
        ),
        AutofillHintMatcher(
            hint = InternalHint.CREDIT_CARD_NUMBER,
            target = "credit_card_number",
        ),
        AutofillHintMatcher(
            hint = InternalHint.CREDIT_CARD_SECURITY_CODE,
            target = HintConstants.AUTOFILL_HINT_CREDIT_CARD_SECURITY_CODE,
        ),
        AutofillHintMatcher(
            hint = InternalHint.CREDIT_CARD_SECURITY_CODE,
            target = "cc-csc",
        ),
        AutofillHintMatcher(
            hint = InternalHint.CREDIT_CARD_SECURITY_CODE,
            target = "credit_card_csv",
        ),
        AutofillHintMatcher(
            hint = InternalHint.CREDIT_CARD_EXPIRATION_DATE,
            target = HintConstants.AUTOFILL_HINT_CREDIT_CARD_EXPIRATION_DATE,
        ),
        AutofillHintMatcher(
            hint = InternalHint.CREDIT_CARD_EXPIRATION_DATE,
            target = "cc-exp",
        ),
        AutofillHintMatcher(
            hint = InternalHint.CREDIT_CARD_EXPIRATION_MONTH,
            target = "cc-exp-month",
        ),
        AutofillHintMatcher(
            hint = InternalHint.CREDIT_CARD_EXPIRATION_YEAR,
            target = "cc-exp-year",
        ),
        AutofillHintMatcher(
            hint = InternalHint.POSTAL_ADDRESS,
            target = HintConstants.AUTOFILL_HINT_POSTAL_ADDRESS,
        ),
        AutofillHintMatcher(
            hint = InternalHint.POSTAL_CODE,
            target = HintConstants.AUTOFILL_HINT_POSTAL_CODE,
        ),
        AutofillHintMatcher(
            hint = InternalHint.PERSON_NAME,
            target = HintConstants.AUTOFILL_HINT_PERSON_NAME,
        ),
        AutofillHintMatcher(
            hint = InternalHint.PERSON_NAME,
            target = HintConstants.AUTOFILL_HINT_NAME,
        ),
        AutofillHintMatcher(
            hint = InternalHint.PERSON_FIRST_NAME,
            target = "given-name",
        ),
        AutofillHintMatcher(
            hint = InternalHint.PERSON_FIRST_NAME,
            target = "first-name",
        ),
        AutofillHintMatcher(
            hint = InternalHint.PERSON_LAST_NAME,
            target = "family-name",
        ),
        AutofillHintMatcher(
            hint = InternalHint.PERSON_LAST_NAME,
            target = "last-name",
        ),
        AutofillHintMatcher(
            hint = InternalHint.POSTAL_ADDRESS,
            target = "street-address",
        ),
        AutofillHintMatcher(
            hint = InternalHint.POSTAL_ADDRESS,
            target = "address-line1",
        ),
        AutofillHintMatcher(
            hint = InternalHint.POSTAL_ADDRESS,
            target = "address-line2",
        ),
        AutofillHintMatcher(
            hint = InternalHint.ADDRESS_CITY,
            target = "address-level2",
        ),
        AutofillHintMatcher(
            hint = InternalHint.ADDRESS_CITY,
            target = "city",
            partly = true,
        ),
        AutofillHintMatcher(
            hint = InternalHint.ADDRESS_REGION,
            target = "address-level1",
        ),
        AutofillHintMatcher(
            hint = InternalHint.ADDRESS_REGION,
            target = "state",
            partly = true,
        ),
        AutofillHintMatcher(
            hint = InternalHint.ADDRESS_REGION,
            target = "province",
            partly = true,
        ),
        AutofillHintMatcher(
            hint = InternalHint.ADDRESS_COUNTRY,
            target = "country",
            partly = true,
        ),
        AutofillHintMatcher(
            hint = InternalHint.COMPANY_NAME,
            target = "organization",
            partly = true,
        ),
        AutofillHintMatcher(
            hint = InternalHint.COMPANY_NAME,
            target = "company",
            partly = true,
        ),
        AutofillHintMatcher(
            hint = InternalHint.IDENTITY_NUMBER,
            target = "passport-number",
        ),
        AutofillHintMatcher(
            hint = InternalHint.IDENTITY_NUMBER,
            target = "document-number",
        ),
        AutofillHintMatcher(
            hint = InternalHint.IDENTITY_NUMBER,
            target = "identity-number",
        ),
        AutofillHintMatcher(
            hint = InternalHint.IDENTITY_NUMBER,
            target = "license-number",
        ),
        AutofillHintMatcher(
            hint = InternalHint.IDENTITY_NUMBER,
            target = "driver-license",
        ),
        AutofillHintMatcher(
            hint = InternalHint.IDENTITY_NUMBER,
            target = "ssn",
            partly = true,
        ),
        AutofillHintMatcher(
            hint = InternalHint.OTP_CODE,
            target = "one-time-code",
        ),
        AutofillHintMatcher(
            hint = InternalHint.OTP_CODE,
            target = "sms-otp",
        ),
        AutofillHintMatcher(
            hint = InternalHint.OTP_CODE,
            target = "email-otp",
        ),
        AutofillHintMatcher(
            hint = InternalHint.OTP_CODE,
            target = "totp",
        ),
        AutofillHintMatcher(
            hint = InternalHint.OTP_CODE,
            target = "2fa",
        ),
        AutofillHintMatcher(
            hint = InternalHint.OFF,
            target = "chrome-off",
        ),
        AutofillHintMatcher(
            hint = InternalHint.OFF,
            target = "off",
        ),
    )

    fun parse(
        structure: AssistStructure,
        respectAutofillOff: Boolean = true,
        allowWeakTargets: Boolean = false,
    ): ParsedStructure {
        var applicationId: String? = structure.activityComponent?.packageName
        var rawStructure: RawParsedStructure? = null
        val parseContext = ParseContext()

        for (i in 0 until structure.windowNodeCount) {
            val windowNode = structure.getWindowNodeAt(i)
            val appIdCandidate = windowNode.title?.toString()?.split("/")?.firstOrNull()
            if (!appIdCandidate.isNullOrBlank()) {
                if (appIdCandidate.contains(":")) {
                    continue
                }
                val normalizedCandidate = appIdCandidate.trim()
                if (isLikelyAndroidPackageName(normalizedCandidate)) {
                    applicationId = normalizedCandidate
                }
            }

            val nodeStructure = parseViewNode(
                node = windowNode.rootViewNode,
                context = parseContext,
            )
            if (rawStructure == null) {
                rawStructure = nodeStructure
            }
            val hasItems = nodeStructure.items.any { it.hint != InternalHint.OFF }
            if (hasItems) {
                rawStructure = nodeStructure
                break
            }
        }

        val allowOnlyWebViewItems = rawStructure?.webView == true
        var candidateItems = rawStructure?.items.orEmpty()
        if (allowOnlyWebViewItems) {
            // 仅保留挂在 WebView 节点下的字段；但密码类 / 含密码术语的字段即使没有
            // parentWebViewNodeId 也保留——PayPal 等站点的登录框常为 iframe / 动态插入，
            // 其密码框未必能拿到 WebView 节点 id 而被直接丢弃（导致只弹条目、密码框填不进）。
            // 密码是登录最强信号，且只放行密码相关字段，不会误伤搜索框 / 其他站点。
            candidateItems = candidateItems.filter {
                it.parentWebViewNodeId != null ||
                    it.hint == InternalHint.PASSWORD ||
                    it.hint == InternalHint.NEW_PASSWORD ||
                    it.hasPasswordTerm
            }
        }

        val confidenceFilteredItems = candidateItems.let { list ->
            if (allowWeakTargets) return@let list
            val onlyLowAccuracy = list.all {
                it.accuracy.score <= Accuracy.LOW.score || it.hint == InternalHint.OFF
            }
            if (!onlyLowAccuracy) {
                return@let list
            }

            // 全 LOW 精度时，只要有密码框（即使账号框精度低/缺失）就保留所有字段，
            // 登录场景的关键判定信号是密码框而非账号框。纯搜索框/备注等（无密码框）
            // 仍返回空，不会误弹密码建议（如 QQ 搜索框）。
            val hasPasswordLike = list.any {
                val passwordLike =
                    it.hint == InternalHint.PASSWORD ||
                        it.hint == InternalHint.NEW_PASSWORD
                passwordLike && it.accuracy.score > Accuracy.LOWEST.score
            }
            if (hasPasswordLike) {
                list
            } else {
                emptyList()
            }
        }

        // 登录字段过滤（P1 起不再有 weakLoginContext「无密码框 + 有 Login 类型字段即
        // 全量放行」分支——那正是京东搜索栏误弹的温床，已被 P0 的 UNKNOWN 语义与
        // 结构提升取代）：
        // - 有密码框 → 全量放行（电影猎手等原生登录页的保护来自这里，而非弱模式放行）；
        // - 无密码框 → 仅保留 MEDIUM+ 精度的账号字段（真实信号：标准 autofill hint、
        //   id/name 术语等），LOWEST 弱账号字段（如 idType="text" 兜底）直接丢弃。
        //   对齐 bitwarden「识别为 Login.Username 才 Fillable」——弱信号字段等价于其
        //   Unused，不参与弹出决策，孤立文本框（搜索栏/昵称等）不会误弹。
        // 两级结构化提升（顺序不可颠倒）先行执行，为上面的「有密码框」分支提供依据：
        //   1. promotePasswordTermCandidates —— 按 password 术语找回密码框
        //      （对齐 bitwarden updateForMissingPasswordFields）
        //   2. promoteUsernameNeighborCandidates —— 密码框就位后，把它上方最近的
        //      UNKNOWN 认定为账号框（对齐 bitwarden updateForMissingUsernameFields）
        // 密码术语提升无条件执行，不依赖 allowWeakTargets：Via 等轻量 WebView 浏览器
        // 常把密码框报成 TYPE_TEXT_VARIATION_WEB_EDIT_TEXT（P0 起兜底为 UNKNOWN），
        // 仅靠弱目标二次解析会在「首轮已识别到账号框」时被跳过（firstPassTargets 非空
        // 不触发弱解析），导致密码框被 accuracy<MEDIUM 丢弃、密码无法填充。Bitwarden 的
        // 缺失密码字段恢复本就是无条件执行的，这里对齐——仅提升「含密码术语」的候选为
        // PASSWORD，不会误伤搜索框（搜索字段不含密码术语）。
        // 这两步取代了原先「WEB_EDIT_TEXT 一律兜底成 USERNAME:LOWEST + weakLoginContext
        // 无密码框全量放行」的做法：召回同样覆盖 WebView 登录页，但判据从「精度阈值 +
        // 页面整体弱上下文」换成了「与密码框的结构关系」，孤立搜索栏因此不再被误判。
        val effectiveItems = promoteUsernameNeighborCandidates(
            promotePasswordTermCandidates(confidenceFilteredItems),
        )
        val hasPasswordInItems = effectiveItems.any {
            it.hint == InternalHint.PASSWORD || it.hint == InternalHint.NEW_PASSWORD
        }
        val loginFilteredItems = if (hasPasswordInItems) {
            effectiveItems
        } else {
            // 识别层保留规则下沉到 AutofillDetectionPolicy.shouldKeepLoginField（P1）：
            // 无密码框时仅保留 MEDIUM+ 精度的账号字段，弱账号字段（如 idType="text"
            // 兜底 USERNAME:LOWEST）直接丢弃，对齐 bitwarden「识别为 Login.Username
            // 才 Fillable」。非账号类 hint（含 UNKNOWN/OFF）原样保留，由下游处理。
            effectiveItems.filter { item ->
                val fieldHint = mapHint(item.hint)
                fieldHint == null || AutofillDetectionPolicy.shouldKeepLoginField(
                    hint = fieldHint,
                    accuracy = item.accuracy,
                    hasPasswordInItems = false,
                )
            }
        }

        val items = mutableListOf<ParsedItem>()
        AutofillLogger.d(
            "PARSING",
            "Parser field selection",
            metadata = mapOf(
                "candidateCount" to candidateItems.size,
                "confidenceFilteredCount" to confidenceFilteredItems.size,
                "loginFilteredCount" to loginFilteredItems.size,
                "candidates" to candidateItems.joinToString { "${it.hint}:${it.accuracy.name}" },
                "allowWeakTargets" to allowWeakTargets,
                "promotedPasswordCount" to (effectiveItems.size - confidenceFilteredItems.size).coerceAtLeast(0),
                "hasPasswordInItems" to hasPasswordInItems,
            )
        )
        loginFilteredItems
            .groupBy { it.id }
            .forEach { groupedById ->
                val groupHints = groupedById.value.joinToString { "${it.hint}:${it.accuracy.name}" }
                val forceAutofillOff = groupedById.value.any {
                    it.hint == InternalHint.OFF && it.accuracy == Accuracy.HIGHEST
                }
                var structureItems = if (forceAutofillOff) {
                    // importantForAutofill=NO 的节点（OFF:HIGHEST）应跳过，
                    // 但若同 id 组有来自其它信号（autofillHints/html）的非 OFF 候选
                    // （如 EMAIL/PHONE/PASSWORD），应保留这些非 OFF 候选而非整组跳过。
                    // 否则电影猎手等 App 的重要ForAutofill=NO 节点会连带丢弃同组的有效字段。
                    val nonOff = groupedById.value.filter { it.hint != InternalHint.OFF }
                    if (nonOff.isEmpty()) {
                        AutofillLogger.d("PARSING",
                            "Group skipped: forceAutofillOff (OFF:HIGHEST) with no non-OFF candidates",
                            metadata = mapOf(
                                "groupId" to groupedById.key.toString(),
                                "groupHints" to groupHints,
                            ))
                        return@forEach
                    }
                    nonOff
                } else if (respectAutofillOff) {
                    if (groupedById.value.any { it.hint == InternalHint.OFF }) {
                        AutofillLogger.d("PARSING",
                            "Group skipped: respectAutofillOff (has OFF candidate)",
                            metadata = mapOf(
                                "groupId" to groupedById.key.toString(),
                                "groupHints" to groupHints,
                            ))
                        return@forEach
                    }
                    groupedById.value
                } else {
                    groupedById.value.filter { it.hint != InternalHint.OFF }
                }

                val derivesOfPassword = structureItems.any {
                    it.hint == InternalHint.CREDIT_CARD_SECURITY_CODE || it.hint == InternalHint.OTP_CODE
                }
                if (derivesOfPassword) {
                    structureItems = structureItems.filter { it.hint != InternalHint.PASSWORD }
                }

                val derivesOfUsername = structureItems.any {
                    it.hint == InternalHint.CREDIT_CARD_NUMBER ||
                        it.hint == InternalHint.CREDIT_CARD_EXPIRATION_DATE ||
                        it.hint == InternalHint.CREDIT_CARD_EXPIRATION_MONTH ||
                        it.hint == InternalHint.CREDIT_CARD_EXPIRATION_YEAR ||
                        it.hint == InternalHint.CREDIT_CARD_EXPIRATION_DAY
                }
                if (derivesOfUsername) {
                    structureItems = structureItems.filter { it.hint != InternalHint.USERNAME }
                }

                val rankedItems = structureItems
                    .groupBy { it.hint }
                    .mapNotNull { groupedByHint ->
                        val score = groupedByHint.value.fold(0f) { acc, item ->
                            acc + item.accuracy.score
                        }
                        val best = groupedByHint.value
                            .maxByOrNull { it.accuracy.score }
                            ?: return@mapNotNull null
                        val value = groupedByHint.value
                            .sortedByDescending { it.accuracy.score }
                            .asSequence()
                            .mapNotNull { it.value }
                            .firstOrNull()
                        HintScore(
                            score = score,
                            hint = groupedByHint.key,
                            value = value,
                            accuracy = best.accuracy,
                            isFocused = best.isFocused,
                            isVisible = best.isVisible,
                            parentWebViewNodeId = best.parentWebViewNodeId,
                            traversalIndex = best.traversalIndex,
                        )
                    }
                val fieldRoleSelection = AutofillFieldRolePolicy.selectWithDiagnostics(
                    rankedItems.mapNotNull { item ->
                        val mappedHint = mapHint(item.hint) ?: return@mapNotNull null
                        AutofillFieldRoleCandidate(
                            value = item,
                            hint = mappedHint,
                            score = item.score,
                            strongestAccuracy = item.accuracy,
                        )
                    }
                )
                    ?: run {
                        AutofillLogger.d("PARSING",
                            "Group skipped: AutofillFieldRolePolicy.selectWithDiagnostics returned null",
                            metadata = mapOf(
                                "groupId" to groupedById.key.toString(),
                                "groupHints" to groupHints,
                                "rankedHintCount" to rankedItems.size,
                                "rankedHints" to rankedItems.joinToString { "${it.hint}:${it.accuracy.name}" },
                            ))
                        return@forEach
                    }
                val selectedItem = fieldRoleSelection.value
                if (fieldRoleSelection.resolvedExplicitAccountPasswordConflict) {
                    AutofillLogger.w(
                        "PARSING",
                        "Explicit account evidence overrode conflicting password evidence",
                        metadata = mapOf(
                            "selectedHint" to (mapHint(selectedItem.hint)?.name ?: "none"),
                            "candidateRoles" to rankedItems.joinToString(separator = ",") { item ->
                                "${mapHint(item.hint)?.name ?: "ignored"}:${item.score}:${item.accuracy.name}"
                            },
                            "focused" to selectedItem.isFocused,
                            "visible" to selectedItem.isVisible,
                            "traversalIndex" to selectedItem.traversalIndex,
                        ),
                    )
                }

                if (selectedItem.score <= Accuracy.LOWEST.score + 0.1f) {
                    val shouldSkip = rawStructure?.items.orEmpty().any {
                        it.hint == selectedItem.hint &&
                            it.accuracy.score > Accuracy.LOWEST.score
                    }
                    if (shouldSkip) {
                        AutofillLogger.d("PARSING",
                            "Group skipped: low score (<=LOWEST+0.1) with higher-accuracy same-hint candidate in rawStructure",
                            metadata = mapOf(
                                "groupId" to groupedById.key.toString(),
                                "groupHints" to groupHints,
                                "selectedHint" to selectedItem.hint.name,
                                "selectedScore" to selectedItem.score.toString(),
                            ))
                        return@forEach
                    }
                }

                val mappedHint = mapHint(selectedItem.hint) ?: run {
                    AutofillLogger.d("PARSING",
                        "Group skipped: mapHint returned null for selected hint",
                        metadata = mapOf(
                            "groupId" to groupedById.key.toString(),
                            "groupHints" to groupHints,
                            "selectedHint" to selectedItem.hint.name,
                        ))
                    return@forEach
                }
                items += ParsedItem(
                    id = groupedById.key,
                    hint = mappedHint,
                    value = selectedItem.value,
                    accuracy = selectedItem.accuracy,
                    isFocused = selectedItem.isFocused,
                    isVisible = selectedItem.isVisible,
                    parentWebViewNodeId = selectedItem.parentWebViewNodeId,
                    traversalIndex = selectedItem.traversalIndex,
                )

                // 对齐 Bitwarden：账户覆盖密码冲突时，不彻底丢弃密码候选。
                // Via/系统 WebView 在 PayPal 等站点会把用户名/密码框分配相同 autofillId，
                // 落入同组后被上面策略丢弃 → fillableTargets 无 PASSWORD → 密码框填不进。
                // 这里把被丢弃的最高分密码候选追加为独立 ParsedItem(PASSWORD)，使密码框
                // 重新进入可填目标。同 autofillId 多 hint：框架对同 id 的 setValue 以目标框
                // 实际类型为准，非密码框不接受 password 值，不会造成错误填充。
                val droppedPassword = fieldRoleSelection.droppedPasswordCandidate
                if (droppedPassword != null) {
                    val passwordMappedHint = mapHint(droppedPassword.hint)
                    if (passwordMappedHint != null) {
                        items += ParsedItem(
                            id = groupedById.key,
                            hint = passwordMappedHint,
                            value = droppedPassword.value,
                            accuracy = droppedPassword.accuracy,
                            isFocused = droppedPassword.isFocused,
                            isVisible = droppedPassword.isVisible,
                            parentWebViewNodeId = droppedPassword.parentWebViewNodeId,
                            traversalIndex = droppedPassword.traversalIndex,
                        )
                        AutofillLogger.i(
                            "PARSING",
                            "Dropped password candidate retained as independent target",
                            metadata = mapOf(
                                "groupId" to groupedById.key.toString(),
                                "passwordHint" to passwordMappedHint.name,
                                "passwordAccuracy" to droppedPassword.accuracy.name,
                                "passwordScore" to droppedPassword.score.toString(),
                                "focused" to droppedPassword.isFocused,
                                "visible" to droppedPassword.isVisible,
                                "traversalIndex" to droppedPassword.traversalIndex,
                            ),
                        )
                    }
                }
            }

        // 诊断：记录 groupBy 后每组被跳过的原因，排查 loginFilteredCount>0 但 parsedItems=0
        if (loginFilteredItems.isNotEmpty() && items.isEmpty()) {
            val groupDiagnostics = loginFilteredItems
                .groupBy { it.id }
                .map { (id, group) ->
                    val hints = group.joinToString { "${it.hint}:${it.accuracy.name}" }
                    val hasOff = group.any { it.hint == InternalHint.OFF }
                    val mappedHints = group.mapNotNull { mapHint(it.hint) }
                    "id=$id hints=[$hints] hasOff=$hasOff mappedCount=${mappedHints.size}"
                }
            AutofillLogger.d(
                "PARSING",
                "All groups skipped after groupBy despite non-empty loginFilteredItems",
                metadata = mapOf(
                    "loginFilteredCount" to loginFilteredItems.size,
                    "groupCount" to groupDiagnostics.size,
                    "respectAutofillOff" to respectAutofillOff,
                    "groups" to groupDiagnostics.joinToString(" | "),
                ),
            )
        }

        val isInSelfHostedServer = kotlin.run {
            val webDomain = rawStructure?.webDomain
            val webView = rawStructure?.webView == true
            webView && (webDomain == "127.0.0.1" || webDomain == "localhost")
        }

        val effectiveWebDomain = rawStructure?.webDomain.takeUnless { isInSelfHostedServer }
            ?: extractDomainFromStructureText(structure)
        if (effectiveWebDomain != null && rawStructure?.webDomain == null && !isInSelfHostedServer) {
            Log.d("BastionAutofill", "webDomain recovered from structure text: $effectiveWebDomain")
        }

        Log.d(
            "BastionAutofill",
            "parse result: items=${items.size}, totalNodes=${parseContext.totalNodesVisited}, " +
                "withAutofillId=${parseContext.nodesWithAutofillId}, " +
                "withoutAutofillId=${parseContext.nodesWithoutAutofillId}, " +
                "webDomain=$effectiveWebDomain, webScheme=${rawStructure?.webScheme}, " +
                "urlBar=${parseContext.urlBarWebsites.firstOrNull() ?: "none"}, " +
                "itemHints=[${items.joinToString { "${it.hint}:${it.accuracy.name}" }}]"
        )

        return ParsedStructure(
            applicationId = applicationId,
            webDomain = effectiveWebDomain,
            webScheme = rawStructure?.webScheme.takeUnless { isInSelfHostedServer },
            webView = if (isInSelfHostedServer) false else rawStructure?.webView == true,
            urlBarWebsite = parseContext.urlBarWebsites.firstOrNull(),
            items = items.sortedBy { it.traversalIndex },
        )
    }

    private fun isLikelyAndroidPackageName(value: String): Boolean {
        if (value.length !in 3..255) return false
        if (!value.contains('.')) return false
        return PACKAGE_NAME_REGEX.matches(value)
    }

    /**
     * Last-resort fallback: scan AssistStructure text nodes for URL-like strings
     * and extract the domain. This helps when the browser doesn't report webDomain
     * for HTTP pages.
     */
    private fun extractDomainFromStructureText(structure: AssistStructure): String? {
        for (i in 0 until structure.windowNodeCount) {
            val windowNode = structure.getWindowNodeAt(i)
            val domain = scanNodeForUrlDomain(windowNode.rootViewNode, depth = 0)
            if (domain != null) return domain
        }
        return null
    }

    private fun scanNodeForUrlDomain(node: AssistStructure.ViewNode, depth: Int): String? {
        if (depth > 6) return null
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrBlank()) {
            val domain = extractDomainFromUrl(text)
            if (domain != null) return domain
        }
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) ?: continue
            val domain = scanNodeForUrlDomain(child, depth + 1)
            if (domain != null) return domain
        }
        return null
    }

    private fun extractDomainFromUrl(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.length < 4 || trimmed.length > 2048) return null
        if (!trimmed.contains(".")) return null
        // Skip obvious non-URL text (e.g. "v2.0", "3.14", placeholder text without slashes/colons)
        val looksLikeUrl = trimmed.contains("://") ||
            trimmed.startsWith("www.") ||
            (trimmed.contains(".") && trimmed.any { it == '/' || it == ':' })
        if (!looksLikeUrl) return null
        val urlStr = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val host = runCatchingObserved { URL(urlStr).host }.getOrNull()
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotBlank() && it.contains(".") && it.any { c -> c.isLetter() } }
        return host
    }

    private fun parseViewNode(
        node: AssistStructure.ViewNode,
        parentWebViewNodeId: Int? = null,
        context: ParseContext,
    ): RawParsedStructure {
        var webView = false
        val rawWebDomain = node.webDomain
        val rawWebScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) node.webScheme else null
        var webDomain: String? = rawWebDomain?.takeIf { it.isNotEmpty() }
        var webScheme: String? = rawWebScheme?.takeIf { it.isNotEmpty() }

        // 搜索容器判定：自身是 role=search/searchbox（或 class/id/data-* 含 search）的节点，
        // 其整棵子树（含自身）统一按搜索上下文处理。进入时 +1，离开（含子树递归完毕后）-1，
        // 使容器内 input 即便自身 placeholder/htmlInfo 在捕获中缺失，也能稳定排除凭据填充。
        val isSearchContainerNode = isSearchContainerNode(node)
        if (isSearchContainerNode) context.searchContainerDepth++

        // 地址栏识别（对齐 bitwarden URL_BARS）：浏览器包名与地址栏资源 id 同时命中时，
        // 该节点文本即当前网址。注意不能只判 idEntry——不同 App 可能复用同名资源 id，
        // 必须包名 + idEntry 双重匹配（bitwarden 原实现即如此）。
        // OS 有时会把 idPackage 默认成 "android"，该值不是合法包名，需排除。
        val urlBarPackage = node.idPackage?.takeUnless { it.isBlank() || it == "android" }
        val urlBarEntryId = urlBarPackage?.let { URL_BARS[it] }
        if (urlBarEntryId != null && urlBarEntryId == node.idEntry) {
            node.text
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { context.urlBarWebsites.add(it) }
        }

        if (context.traversalIndex == 0) {
            Log.d(
                "BastionAutofill",
                "parseViewNode root: rawWebDomain=$rawWebDomain, rawWebScheme=$rawWebScheme, " +
                    "webDomain=$webDomain, webScheme=$webScheme, " +
                    "className=${node.className}, sdk=${Build.VERSION.SDK_INT}"
            )
        }

        val out = mutableListOf<RawParsedItem>()
        webView = node.className == "android.webkit.WebView"
        val webViewNodeId = node.id.takeIf { webView } ?: parentWebViewNodeId

        context.totalNodesVisited++
        if (node.autofillId != null) {
            context.nodesWithAutofillId++
        } else {
            context.nodesWithoutAutofillId++
        }

        if (node.autofillId != null) {
            val outBuilders = mutableListOf<ParsedItemBuilder>()
            val hints = node.autofillHints
            if (!hints.isNullOrEmpty()) {
                outBuilders += parseNodeByAutofillHint(node)
            }

            outBuilders += parseNodeByHtmlAttributes(node)
            val inputOut = parseNodeByAndroidInput(node)
            val labelOut = parseNodeByLabel(node)
            outBuilders += inputOut + labelOut

            // 搜索框识别与排除：搜索框不应作为登录凭据字段。否则在「页面存在密码框即整页按登录
            // 上下文处理」的策略下，聚焦搜索框也会弹出密码条目（Edge 访问 GitHub 时顶部搜索框误弹）。
            // 命中则清空凭据候选，使该节点不进入 ParsedStructure.items，从而不触发填充建议。
            if (outBuilders.isNotEmpty() && isSearchField(node, context.searchContainerDepth > 0)) {
                AutofillLogger.d(
                    "PARSING",
                    "Search field excluded from credential fill",
                    metadata = mapOf(
                        "htmlTag" to (node.htmlInfo?.tag ?: "none"),
                        "ariaLabel" to (node.htmlInfo?.attributes
                            ?.firstOrNull { it.first.equals("aria-label", ignoreCase = true) }?.second ?: "none"),
                        "placeholder" to (node.htmlInfo?.attributes
                            ?.firstOrNull { it.first.equals("placeholder", ignoreCase = true) }?.second ?: "none"),
                        "isFocused" to node.isFocused,
                        // 祖先容器命中：即便本节点自身 placeholder/htmlInfo 缺失也能稳定排除
                        "ancestorSearchContainer" to (context.searchContainerDepth > 0),
                    )
                )
                outBuilders.clear()
            }

            if (node.visibility == View.VISIBLE || shouldIncludeHiddenCredentialNode(outBuilders)) {
                val nodeHasPasswordTerm = nodeHasPasswordTermMatch(node)
                val nodeHasLoginTerm = nodeHasLoginTermMatch(node)
                // 诊断：当节点走到弱信号兜底路径（LOWEST 精度的 USERNAME，或 WEB_EDIT_TEXT
                // 兜底产出的 UNKNOWN）时记录原始信号，用于排查账号框为何被弱推断、
                // 以及 bitwarden 等为何能识别（对比 autofillHints/idEntry/hint/inputType）。
                // UNKNOWN 需一并记录：它是邻居提升（promoteUsernameNeighborCandidates）的
                // 候选来源，排查 WebView 登录页漏弹时需要知道哪些节点落到了这一档。
                if (outBuilders.any {
                        (it.hint == InternalHint.USERNAME && it.accuracy == Accuracy.LOWEST) ||
                            it.hint == InternalHint.UNKNOWN
                    }
                ) {
                    AutofillLogger.d(
                        "PARSING",
                        "Weak-signal node signals (LOWEST username / UNKNOWN fallback)",
                        metadata = mapOf(
                            "className" to (node.className ?: "none"),
                            "inputType" to node.inputType.toString(),
                            "autofillHints" to (node.autofillHints?.joinToString(",") ?: "none"),
                            "idEntry" to (node.idEntry ?: "none"),
                            "hintLabel" to (node.hint?.toString() ?: "none"),
                            "hasPasswordTerm" to nodeHasPasswordTerm,
                            "hasLoginTerm" to nodeHasLoginTerm,
                            "htmlTag" to (node.htmlInfo?.tag ?: "none"),
                            "htmlAttrs" to (node.htmlInfo?.attributes
                                ?.joinToString(",") { "${it.first}=${it.second}" } ?: "none"),
                            "isVisible" to (node.visibility == View.VISIBLE),
                            "isFocused" to node.isFocused,
                        ),
                    )
                }
                out += outBuilders.map { builder ->
                    context.traversalIndex += 1
                    RawParsedItem(
                        id = node.autofillId!!,
                        accuracy = builder.accuracy,
                        hint = builder.hint,
                        value = builder.value ?: node.autofillValue.safeTextOrNull(
                            tag = "EnhancedParserV2",
                            fieldDescription = builder.hint.name,
                        ),
                        reason = builder.reason,
                        parentWebViewNodeId = webViewNodeId,
                        isFocused = node.isFocused,
                        isVisible = node.visibility == View.VISIBLE,
                        traversalIndex = context.traversalIndex,
                        hasPasswordTerm = nodeHasPasswordTerm,
                        hasLoginTerm = nodeHasLoginTerm,
                    )
                }
            }
        }

        for (i in 0 until node.childCount) {
            val childStructure = parseViewNode(
                node = node.getChildAt(i),
                parentWebViewNodeId = webViewNodeId,
                context = context,
            )
            if (childStructure.webView) {
                webView = true
            }
            webDomain = webDomain ?: childStructure.webDomain
            webScheme = webScheme ?: childStructure.webScheme
            out += childStructure.items
        }

        // 离开搜索容器：恢复祖先深度，确保兄弟节点不误判为搜索上下文
        if (isSearchContainerNode) context.searchContainerDepth--

        return RawParsedStructure(
            webScheme = webScheme,
            webDomain = webDomain,
            webView = webView,
            items = out,
        )
    }

    private fun parseNodeByAutofillHint(
        node: AssistStructure.ViewNode,
    ): List<ParsedItemBuilder> = kotlin.run {
        val out = mutableListOf<ParsedItemBuilder>()
        node.autofillHints?.forEach { value ->
            val matchers = autofillHintMatchers.filter { matcher -> matcher.matches(value) }
            matchers.forEach { matcher ->
                out += ParsedItemBuilder(
                    accuracy = matcher.accuracy,
                    hint = matcher.hint,
                    reason = "autofill-hint",
                )
            }
        }
        out
    }

    private fun parseNodeByHtmlAttributes(
        node: AssistStructure.ViewNode,
    ): List<ParsedItemBuilder> = kotlin.run {
        val out = mutableListOf<ParsedItemBuilder>()
        val nodeHtml = node.htmlInfo
        when (nodeHtml?.tag?.lowercase(Locale.ENGLISH)) {
            "input" -> {
                val attributes = kotlin.run {
                    nodeHtml.attributes
                        ?.map { it.first to it.second }
                        ?.takeUnless { it.isEmpty() } ?: runCatchingObserved {
                        val values = nodeHtml.javaClass.getDeclaredField("mValues")
                            .apply { isAccessible = true }
                            .get(nodeHtml)
                        val names = nodeHtml.javaClass.getDeclaredField("mNames")
                            .apply { isAccessible = true }
                            .get(nodeHtml)
                        if (values is Array<*> && names is Array<*>) {
                            val count = minOf(values.size, names.size)
                            (0 until count).map { i ->
                                names[i]?.toString().orEmpty() to values[i]?.toString().orEmpty()
                            }
                        } else if (values is Collection<*> && names is Collection<*>) {
                            val valuesList = values.toList()
                            val namesList = names.toList()
                            val count = minOf(valuesList.size, namesList.size)
                            (0 until count).map { i ->
                                namesList[i]?.toString().orEmpty() to valuesList[i]?.toString().orEmpty()
                            }
                        } else {
                            null
                        }
                    }.getOrNull()
                }

                attributes?.forEach { attribute ->
                    val key = attribute.first.lowercase(Locale.ENGLISH)
                    when (key) {
                        "autocomplete",
                        "ua-autofill-hints",
                        -> {
                            val value = attribute.second?.lowercase(Locale.ENGLISH).orEmpty()
                            val matchers = autofillHintMatchers.filter { matcher ->
                                matcher.matches(value)
                            }
                            matchers.forEach { matcher ->
                                out += ParsedItemBuilder(
                                    accuracy = matcher.accuracy,
                                    hint = matcher.hint,
                                    reason = key,
                                )
                            }
                        }

                        "type" -> {
                            val type = attribute.second.orEmpty()
                            extractOfType(type).let(out::addAll)
                        }

                        "inputmode" -> {
                            if (attribute.second.equals("tel", ignoreCase = true)) {
                                out += ParsedItemBuilder(
                                    accuracy = Accuracy.MEDIUM,
                                    hint = InternalHint.PHONE_NUMBER,
                                    reason = "inputmode",
                                )
                            }
                        }

                        "name" -> {
                            val type = attribute.second.orEmpty()
                            extractOfId(type).let(out::addAll)
                        }

                        "id" -> {
                            val type = attribute.second.orEmpty()
                            extractOfId(type).let(out::addAll)
                        }

                        "label" -> {
                            val label = attribute.second.orEmpty()
                            extractOfLabel(label).let(out::addAll)
                        }

                        "placeholder",
                        "aria-label",
                        -> {
                            val label = attribute.second.orEmpty()
                            extractOfLabel(label).let(out::addAll)
                        }
                    }
                }
            }
        }
        out
    }

    private fun parseNodeByLabel(
        node: AssistStructure.ViewNode,
    ): List<ParsedItemBuilder> {
        val hint = node.hint ?: return emptyList()
        return extractOfLabel(hint)
    }

    private fun extractOfType(
        value: String,
    ): List<ParsedItemBuilder> = when (value.lowercase(Locale.ENGLISH)) {
        "tel" -> ParsedItemBuilder(
            accuracy = Accuracy.MEDIUM,
            hint = InternalHint.PHONE_NUMBER,
            reason = "type",
        )

        "email" -> ParsedItemBuilder(
            accuracy = Accuracy.MEDIUM,
            hint = InternalHint.EMAIL_ADDRESS,
            reason = "type",
        )

        "username" -> ParsedItemBuilder(
            accuracy = Accuracy.MEDIUM,
            hint = InternalHint.USERNAME,
            reason = "type",
        )

        "text" -> ParsedItemBuilder(
            accuracy = Accuracy.LOWEST,
            hint = InternalHint.USERNAME,
            reason = "type",
        )

        "password" -> ParsedItemBuilder(
            accuracy = Accuracy.HIGH,
            hint = InternalHint.PASSWORD,
            reason = "type",
        )

        "totp",
        "twofa",
        "2fa",
        -> ParsedItemBuilder(
            accuracy = Accuracy.HIGH,
            hint = InternalHint.OTP_CODE,
            reason = "type",
        )

        "expdate" -> ParsedItemBuilder(
            accuracy = Accuracy.HIGH,
            hint = InternalHint.CREDIT_CARD_EXPIRATION_DATE,
            reason = "type",
        )

        "cc-number",
        "cc_number",
        "credit-card-number",
        "credit_card_number",
        "card-number",
        "card_number",
        -> ParsedItemBuilder(
            accuracy = Accuracy.HIGH,
            hint = InternalHint.CREDIT_CARD_NUMBER,
            reason = "type",
        )

        "cc-csc",
        "cc_csc",
        "cc-cvv",
        "cc_cvv",
        "cvv",
        "cvc",
        "security-code",
        "security_code",
        -> ParsedItemBuilder(
            accuracy = Accuracy.HIGH,
            hint = InternalHint.CREDIT_CARD_SECURITY_CODE,
            reason = "type",
        )

        "cc-exp-month",
        "cc_exp_month",
        -> ParsedItemBuilder(
            accuracy = Accuracy.HIGH,
            hint = InternalHint.CREDIT_CARD_EXPIRATION_MONTH,
            reason = "type",
        )

        "cc-exp-year",
        "cc_exp_year",
        -> ParsedItemBuilder(
            accuracy = Accuracy.HIGH,
            hint = InternalHint.CREDIT_CARD_EXPIRATION_YEAR,
            reason = "type",
        )

        "cc-exp",
        "cc_exp",
        "cc-exp-date",
        "cc_exp_date",
        "credit-card-expiration",
        "credit_card_expiration",
        "expiry",
        "expiration",
        -> ParsedItemBuilder(
            accuracy = Accuracy.HIGH,
            hint = InternalHint.CREDIT_CARD_EXPIRATION_DATE,
            reason = "type",
        )

        else -> null
    }.let { listOfNotNull(it) }

    private fun extractOfId(
        value: String,
    ): List<ParsedItemBuilder> = kotlin.run {
        val id = value.lowercase(Locale.ENGLISH)
        when {
            "email" in id -> ParsedItemBuilder(
                accuracy = Accuracy.MEDIUM,
                hint = InternalHint.EMAIL_ADDRESS,
                reason = "id",
            )

            AutofillDetectionPolicy.matchesPhoneFieldName(id) -> ParsedItemBuilder(
                accuracy = Accuracy.MEDIUM,
                hint = InternalHint.PHONE_NUMBER,
                reason = "id",
            )

            "username" in id -> ParsedItemBuilder(
                accuracy = Accuracy.MEDIUM,
                hint = InternalHint.USERNAME,
                reason = "id",
            )

            "password" in id -> ParsedItemBuilder(
                accuracy = Accuracy.HIGH,
                hint = InternalHint.PASSWORD,
                reason = "id",
            )

            "cc_number" in id || "ccnum" in id || "card_number" in id || "cardnumber" in id ||
                "card_no" in id || "cardno" in id || "credit_card_number" in id -> ParsedItemBuilder(
                accuracy = Accuracy.HIGH,
                hint = InternalHint.CREDIT_CARD_NUMBER,
                reason = "id",
            )

            "cardholder" in id || "holder_name" in id -> ParsedItemBuilder(
                accuracy = Accuracy.HIGH,
                hint = InternalHint.CREDIT_CARD_HOLDER_NAME,
                reason = "id",
            )

            "cc_name" in id || "card_name" in id || "name_on_card" in id -> ParsedItemBuilder(
                accuracy = Accuracy.HIGH,
                hint = InternalHint.CREDIT_CARD_HOLDER_NAME,
                reason = "id",
            )

            "cc_csc" in id || "cc_cvv" in id || "cvv" in id || "cvc" in id ||
                "security_code" in id || "securitycode" in id -> ParsedItemBuilder(
                accuracy = Accuracy.HIGH,
                hint = InternalHint.CREDIT_CARD_SECURITY_CODE,
                reason = "id",
            )

            "cc_exp_month" in id || "exp_month" in id -> ParsedItemBuilder(
                accuracy = Accuracy.HIGH,
                hint = InternalHint.CREDIT_CARD_EXPIRATION_MONTH,
                reason = "id",
            )

            "cc_exp_year" in id || "exp_year" in id -> ParsedItemBuilder(
                accuracy = Accuracy.HIGH,
                hint = InternalHint.CREDIT_CARD_EXPIRATION_YEAR,
                reason = "id",
            )

            "cc_exp" in id || "exp_date" in id || "expiry" in id || "expiration" in id -> ParsedItemBuilder(
                accuracy = Accuracy.HIGH,
                hint = InternalHint.CREDIT_CARD_EXPIRATION_DATE,
                reason = "id",
            )

            "passport" in id || "license" in id || "document" in id || "identity" in id || "ssn" in id -> ParsedItemBuilder(
                accuracy = Accuracy.HIGH,
                hint = InternalHint.IDENTITY_NUMBER,
                reason = "id",
            )

            "first_name" in id || "given_name" in id -> ParsedItemBuilder(
                accuracy = Accuracy.MEDIUM,
                hint = InternalHint.PERSON_FIRST_NAME,
                reason = "id",
            )

            "last_name" in id || "family_name" in id || "surname" in id -> ParsedItemBuilder(
                accuracy = Accuracy.MEDIUM,
                hint = InternalHint.PERSON_LAST_NAME,
                reason = "id",
            )

            "street" in id || "address" in id -> ParsedItemBuilder(
                accuracy = Accuracy.MEDIUM,
                hint = InternalHint.POSTAL_ADDRESS,
                reason = "id",
            )

            "city" in id -> ParsedItemBuilder(
                accuracy = Accuracy.MEDIUM,
                hint = InternalHint.ADDRESS_CITY,
                reason = "id",
            )

            "state" in id || "province" in id || "region" in id -> ParsedItemBuilder(
                accuracy = Accuracy.MEDIUM,
                hint = InternalHint.ADDRESS_REGION,
                reason = "id",
            )

            "country" in id -> ParsedItemBuilder(
                accuracy = Accuracy.MEDIUM,
                hint = InternalHint.ADDRESS_COUNTRY,
                reason = "id",
            )

            "company" in id || "organization" in id -> ParsedItemBuilder(
                accuracy = Accuracy.MEDIUM,
                hint = InternalHint.COMPANY_NAME,
                reason = "id",
            )

            "totp" in id || "twofa" in id || "2fa" in id -> ParsedItemBuilder(
                accuracy = Accuracy.HIGH,
                hint = InternalHint.OTP_CODE,
                reason = "id",
            )

            else -> null
        }.let { listOfNotNull(it) }
    }

    private fun extractOfLabel(
        value: String,
    ): List<ParsedItemBuilder> {
        val hint = value.lowercase(Locale.ENGLISH).trim()
        if (hint.isBlank()) {
            return emptyList()
        }

        val out = when {
            autofillLabelEmailTranslations.any { it in hint } ->
                ParsedItemBuilder(
                    accuracy = Accuracy.MEDIUM,
                    hint = InternalHint.EMAIL_ADDRESS,
                    reason = "label:$hint",
                )

            AutofillDetectionPolicy.matchesPhoneFieldName(hint) ->
                ParsedItemBuilder(
                    accuracy = Accuracy.MEDIUM,
                    hint = InternalHint.PHONE_NUMBER,
                    reason = "label:$hint",
                )

            AutofillDetectionPolicy.matchesUsernameLabel(hint) ->
                ParsedItemBuilder(
                    accuracy = Accuracy.MEDIUM,
                    hint = InternalHint.USERNAME,
                    reason = "label:$hint",
                )

            autofillLabelPasswordTranslations.any { it in hint } ->
                ParsedItemBuilder(
                    accuracy = Accuracy.MEDIUM,
                    hint = InternalHint.PASSWORD,
                    reason = "label:$hint",
                )

            autofillLabel2faTranslations.any { it in hint } ->
                ParsedItemBuilder(
                    accuracy = Accuracy.MEDIUM,
                    hint = InternalHint.OTP_CODE,
                    reason = "label:$hint",
                )

            autofillLabelCreditCardNumberTranslations.any { it.matches(hint) } ->
                ParsedItemBuilder(
                    accuracy = Accuracy.MEDIUM,
                    hint = InternalHint.CREDIT_CARD_NUMBER,
                    reason = "label:$hint",
                )

            autofillLabelCreditCardSecurityCodeTranslations.any { it in hint } ->
                ParsedItemBuilder(
                    accuracy = Accuracy.MEDIUM,
                    hint = InternalHint.CREDIT_CARD_SECURITY_CODE,
                    reason = "label:$hint",
                )

            autofillLabelCreditCardExpirationTranslations.any { it in hint } ->
                ParsedItemBuilder(
                    accuracy = Accuracy.MEDIUM,
                    hint = InternalHint.CREDIT_CARD_EXPIRATION_DATE,
                    reason = "label:$hint",
                )

            autofillLabelCreditCardHolderTranslations.any { it in hint } ->
                ParsedItemBuilder(
                    accuracy = Accuracy.MEDIUM,
                    hint = InternalHint.CREDIT_CARD_HOLDER_NAME,
                    reason = "label:$hint",
                )

            autofillLabelPersonFirstNameTranslations.any { it in hint } ->
                ParsedItemBuilder(
                    accuracy = Accuracy.MEDIUM,
                    hint = InternalHint.PERSON_FIRST_NAME,
                    reason = "label:$hint",
                )

            autofillLabelPersonLastNameTranslations.any { it in hint } ->
                ParsedItemBuilder(
                    accuracy = Accuracy.MEDIUM,
                    hint = InternalHint.PERSON_LAST_NAME,
                    reason = "label:$hint",
                )

            autofillLabelPostalAddressTranslations.any { it in hint } ->
                ParsedItemBuilder(
                    accuracy = Accuracy.MEDIUM,
                    hint = InternalHint.POSTAL_ADDRESS,
                    reason = "label:$hint",
                )

            autofillLabelIdentityNumberTranslations.any { it in hint } ->
                ParsedItemBuilder(
                    accuracy = Accuracy.MEDIUM,
                    hint = InternalHint.IDENTITY_NUMBER,
                    reason = "label:$hint",
                )

            else -> null
        }
        return listOfNotNull(out)
    }

    private fun parseNodeByAndroidInput(
        node: AssistStructure.ViewNode,
    ): List<ParsedItemBuilder> {
        val out = mutableListOf<ParsedItemBuilder>()

        if (node.idType.orEmpty().equals("id", ignoreCase = true)) {
            val idEntry = node.idEntry.orEmpty()
            if (
                idEntry.contains("url", ignoreCase = true) ||
                idEntry.contentEquals(other = "location_bar_edit_text", ignoreCase = true)
            ) {
                out += ParsedItemBuilder(
                    accuracy = Accuracy.HIGHEST,
                    hint = InternalHint.OFF,
                )
                return out
            }
        }

        val importance = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            node.importantForAutofill
        } else {
            View.IMPORTANT_FOR_AUTOFILL_AUTO
        }
        if (importance == View.IMPORTANT_FOR_AUTOFILL_NO) {
            out += ParsedItemBuilder(
                accuracy = Accuracy.HIGHEST,
                hint = InternalHint.OFF,
            )
            return out
        }

        val inputType = node.inputType
        when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> {
                when {
                    inputIsVariationType(
                        inputType,
                        InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                        InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                    ) -> {
                        out += ParsedItemBuilder(
                            accuracy = Accuracy.HIGH,
                            hint = InternalHint.EMAIL_ADDRESS,
                        )
                    }

                    inputIsVariationType(
                        inputType,
                        InputType.TYPE_TEXT_VARIATION_PERSON_NAME,
                    ) -> {
                        out += ParsedItemBuilder(
                            accuracy = Accuracy.LOW,
                            hint = InternalHint.PERSON_NAME,
                        )
                    }

                    inputIsVariationType(
                        inputType,
                        InputType.TYPE_TEXT_VARIATION_NORMAL,
                        InputType.TYPE_TEXT_VARIATION_PERSON_NAME,
                        InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT,
                    ) -> {
                        // 对齐 bitwarden：纯 text 变体不再无条件 fallback 为 USERNAME:LOWEST
                        // （bitwarden 的 toAutofillView 对纯 text 返回 Unused）。
                        // 仅当 idEntry / idType 含 username 术语时才产出 USERNAME（由
                        // extractOfId/extractOfType 按术语定 MEDIUM 精度），避免把
                        // 搜索框/备注等纯文本框误判为登录账号（QQ 搜索框误弹修复）。
                        //
                        // WEB_EDIT_TEXT 变体（WebView 内可编辑文本）产出 UNKNOWN 而非
                        // USERNAME:LOWEST——对齐 bitwarden 的 AutofillView.Unused 语义：
                        // 「这是个输入框，但我不知道它是什么」是明确的否定，而不是
                        // 「置信度低的账号框」这种弱肯定。弱肯定会被下游放宽分支
                        //（P0 前有 weakLoginContext 全量放行）当成真账号框放行，
                        // 正是京东搜索栏误弹密码条目的根因。
                        //
                        // WebView 真登录页的召回由 promoteUsernameNeighborCandidates 补回：
                        // 存在密码框且无账号框时，把密码框上方最近的 UNKNOWN 提升为 USERNAME，
                        // 对齐 bitwarden updateForMissingUsernameFields。搜索栏因孤立无密码框
                        // 而不会被提升，两个场景由「结构」而非「精度阈值」区分开。
                        if (inputIsVariationType(inputType, InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT)) {
                            out += ParsedItemBuilder(
                                accuracy = Accuracy.LOWEST,
                                hint = InternalHint.UNKNOWN,
                            )
                        }
                        extractOfType(node.idType.orEmpty()).let(out::addAll)
                        extractOfId(node.idEntry.orEmpty()).let(out::addAll)
                    }

                    inputIsVariationType(
                        inputType,
                        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                    ) -> {
                        // 可见密码变体一定是密码框（如"显示密码"切换），
                        // 原逻辑要求同节点已存在 username 才判定为 password，
                        // 但账号/密码通常是独立节点，导致密码框被误判为 username。
                        out += ParsedItemBuilder(
                            accuracy = Accuracy.LOW,
                            hint = InternalHint.PASSWORD,
                        )
                    }

                    inputIsVariationType(
                        inputType,
                        InputType.TYPE_TEXT_VARIATION_PASSWORD,
                        InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                    ) -> {
                        out += ParsedItemBuilder(
                            accuracy = Accuracy.HIGH,
                            hint = InternalHint.PASSWORD,
                        )
                    }

                    inputIsVariationType(
                        inputType,
                        InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT,
                        InputType.TYPE_TEXT_VARIATION_FILTER,
                        InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE,
                        InputType.TYPE_TEXT_VARIATION_PHONETIC,
                        InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS,
                        InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE,
                        InputType.TYPE_TEXT_VARIATION_URI,
                    ) -> {
                    }

                    else -> {
                    }
                }
            }

            InputType.TYPE_CLASS_NUMBER -> {
                when {
                    inputIsVariationType(
                        inputType,
                        InputType.TYPE_NUMBER_VARIATION_NORMAL,
                    ) -> {
                        extractOfType(node.idType.orEmpty()).let(out::addAll)
                        extractOfId(node.idEntry.orEmpty()).let(out::addAll)
                        out += ParsedItemBuilder(
                            accuracy = AutofillDetectionPolicy.genericNumberFallbackAccuracy(),
                            hint = InternalHint.USERNAME,
                        )
                    }

                    inputIsVariationType(
                        inputType,
                        InputType.TYPE_NUMBER_VARIATION_PASSWORD,
                    ) -> {
                        out += ParsedItemBuilder(
                            accuracy = Accuracy.LOW,
                            hint = InternalHint.PASSWORD,
                        )
                    }

                    else -> {
                    }
                }
            }
        }

        return out
    }

    private fun shouldIncludeHiddenCredentialNode(
        builders: List<ParsedItemBuilder>,
    ): Boolean {
        return builders.any { builder ->
            val mappedHint = mapHint(builder.hint) ?: return@any false
            AutofillDetectionPolicy.shouldIncludeHiddenCredential(
                hint = mappedHint,
                accuracy = builder.accuracy,
            )
        }
    }

    /**
     * 判断节点的文本信号（idEntry / label hint / autofillHints / html placeholder / className）
     * 是否含 password 术语。用于弱目标模式下把非标准 inputType 的密码框从 USERNAME:LOWEST
     * 提升为 PASSWORD（借鉴 bitwarden 的 updateForMissingPasswordFields）。
     */
    private fun nodeHasPasswordTermMatch(node: AssistStructure.ViewNode): Boolean {
        val candidates = buildList {
            node.idEntry?.let { add(it) }
            node.hint?.toString()?.let { add(it) }
            node.autofillHints?.forEach { add(it) }
            node.htmlInfo?.attributes?.forEach { attr ->
                val k = attr.first
                if (k.equals("placeholder", ignoreCase = true) ||
                    k.equals("name", ignoreCase = true) ||
                    k.equals("id", ignoreCase = true)
                ) {
                    attr.second?.let { add(it) }
                }
            }
            node.className?.let { add(it) }
        }
        return candidates.any { value ->
            autofillLabelPasswordTranslations.any { term -> value.contains(term, ignoreCase = true) }
        }
    }

    /**
     * 判断节点的文本信号是否含 login 术语（username/login/账号/用户名/password/密码 等）。
     * 用于弱目标模式下的「login 上下文门」：无密码框时，若屏幕上任意节点含 login 术语，
     * 放行低精度账号字段（对齐 bitwarden「有 Login 字段就 Fillable」门槛）；
     * 纯搜索/备注场景无 login 术语，不放行，缓解误弹。
     */
    private fun nodeHasLoginTermMatch(node: AssistStructure.ViewNode): Boolean {
        val candidates = buildList {
            node.idEntry?.let { add(it) }
            node.hint?.toString()?.let { add(it) }
            node.autofillHints?.forEach { add(it) }
            node.htmlInfo?.attributes?.forEach { attr ->
                val k = attr.first
                if (k.equals("placeholder", ignoreCase = true) ||
                    k.equals("name", ignoreCase = true) ||
                    k.equals("id", ignoreCase = true)
                ) {
                    attr.second?.let { add(it) }
                }
            }
        }
        return candidates.any { value ->
            autofillLabelLoginTranslations.any { term -> value.contains(term, ignoreCase = true) }
        }
    }

    /**
     * 判断节点是否为搜索框。搜索框不应被当作登录凭据字段——否则在「页面存在密码框即整页按登录
     * 上下文处理」的策略下，聚焦搜索框也会弹出密码条目（Edge 访问 GitHub 时顶部搜索框误弹）。
     *
     * 命中任一信号即判为搜索框：
     * - HTML type=search
     * - HTML autocomplete 含 "search"
     * - HTML role 含 "search"
     * - HTML inputmode=search
     * - aria-label / placeholder / title / name / id / 节点 hint(label) 含搜索相关词
     *   （search / 搜索 / 查询 / 查找 / recherche / buscar / suche 等）
     */
    private fun isSearchField(
        node: AssistStructure.ViewNode,
        inSearchContainer: Boolean = false,
    ): Boolean {
        // 0) 祖先搜索容器命中：容器内（含容器自身）所有 input 一律判为搜索框。
        //    这是根治「搜索框偶尔弹密码」的关键——容器的 role=search/searchbox 或 class/id 含 search
        //    在 AssistStructure 捕获中远比单个深层 input 的 placeholder 稳定，
        //    因此即便该 input 自身 htmlInfo/placeholder 在某次捕获中缺失，也能稳定排除凭据填充。
        if (inSearchContainer) return true

        // 1) Android 标准 autofillHints（系统 / WebView 可能直接给出语义提示）
        val autofillHints = node.autofillHints?.map { it.lowercase(Locale.ENGLISH) }.orEmpty()
        val hintSearchTerms = listOf("search", "filter", "find", "query")
        if (autofillHints.any { h -> hintSearchTerms.any { t -> t in h } }) return true

        // 2) inputType：筛选变体（TYPE_TEXT_VARIATION_FILTER = 0x000000B0，列表筛选框常见）
        if (node.inputType and android.text.InputType.TYPE_TEXT_VARIATION_FILTER
            == android.text.InputType.TYPE_TEXT_VARIATION_FILTER
        ) return true

        // 3) 文本信号（Android 级 hint 优先，不依赖 htmlInfo 是否非空）。
        //    关键修复：GitHub 等站点的「Find a user...」输入框在 autofill 结构里常无 htmlInfo，
        //    仅靠 node.hint 暴露占位文案；若在此之后才检查，会因 htmlInfo 为 null 提前 return，
        //    导致搜索框被当成 USERNAME 候选而弹出密码条目。
        //    注意：不能加入裸 "user"（会误伤真正的 username 登录框），仅匹配明确的「查找/搜索用户」短语。
        //    补充：把 node.idEntry / className / htmlInfo 的 class 与 data-* 属性也纳入匹配，
        //    覆盖「自身 placeholder 缺失但资源 id / class 含 search」的捕获情形。
        val searchTerms = listOf(
            "search", "searchbox", "filter", "find", "query", "jump", "lookup",
            "combobox", "find a user", "find user", "search user", "search for user",
            "invite user", "add user", "lookup user",
            "搜索", "查询", "查找", "筛选", "过滤", "搜索框", "找用户", "搜索用户",
            "recherche", "buscar", "suche",
        )
        val textPieces = mutableListOf<String>().apply {
            add(node.hint?.toString().orEmpty())
            add(node.idEntry?.toString().orEmpty())
            add(node.className?.toString().orEmpty())
            // contentDescription：Edge/Chromium 系常不填 node.hint，而是把占位文案或
            // 无障碍标签挂在这里。漏掉它时，仅有 contentDescription 的搜索框会判不出来，
            // 进而被"账号字段邻居提升"扶正成 USERNAME 而误弹密码条目。
            add(node.contentDescription?.toString().orEmpty())
            val html = node.htmlInfo
            if (html != null) {
                val attrs = html.attributes
                    ?.map { it.first.lowercase(Locale.ENGLISH) to (it.second ?: "") }
                    ?.toMap().orEmpty()
                add(attrs["aria-label"].orEmpty())
                add(attrs["placeholder"].orEmpty())
                add(attrs["title"].orEmpty())
                add(attrs["name"].orEmpty())
                add(attrs["id"].orEmpty())
                add(attrs["class"].orEmpty())
                // data-* 属性（如 data-component）可能携带 search 语义
                html.attributes?.forEach { attr ->
                    if (attr.first.lowercase(Locale.ENGLISH).startsWith("data-")) add(attr.second ?: "")
                }
            }
        }.map { it.lowercase(Locale.ENGLISH) }
        if (textPieces.any { t -> searchTerms.any { term -> term in t } }) return true

        // 4) HTML 属性（仅当 htmlInfo 存在；type=search / role / inputmode 等）
        val html = node.htmlInfo ?: return false
        val tag = html.tag?.lowercase(Locale.ENGLISH).orEmpty()
        if (tag != "input" && tag != "search" && tag != "textarea") return false

        val attrs = html.attributes
            ?.map { it.first.lowercase(Locale.ENGLISH) to (it.second ?: "") }
            ?.toMap()
            .orEmpty()

        val type = attrs["type"].orEmpty().lowercase(Locale.ENGLISH)
        val autocomplete = attrs["autocomplete"].orEmpty().lowercase(Locale.ENGLISH)
        val role = attrs["role"].orEmpty().lowercase(Locale.ENGLISH)
        val inputMode = attrs["inputmode"].orEmpty().lowercase(Locale.ENGLISH)

        if (type == "search") return true
        if ("search" in autocomplete) return true
        if ("search" in role) return true
        if (inputMode == "search") return true
        // role=searchbox / combobox（筛选下拉也是非登录语义）视为搜索/筛选
        if (role.contains("searchbox") || role.contains("combobox")) return true

        return false
    }

    /**
     * 判定节点自身是否为「搜索容器」：htmlInfo 的 role=search、tag=search，
     * 或 class / id / data-* 属性含 search 语义。命中则其整棵子树（含自身）都被视为搜索上下文，
     * 由 parseViewNode 通过 ParseContext.searchContainerDepth 传递给子节点——
     * 容器内 input 即使自身 placeholder/htmlInfo 在捕获中缺失，也能稳定排除凭据填充。
     * 仅用明确含 "search" 的信号，避免误伤普通 combobox / 下拉选择器。
     */
    private fun isSearchContainerNode(node: AssistStructure.ViewNode): Boolean {
        val html = node.htmlInfo ?: return false
        val tag = html.tag?.lowercase(Locale.ENGLISH).orEmpty()
        if (tag == "search") return true
        val attrs = html.attributes
            ?.map { it.first.lowercase(Locale.ENGLISH) to (it.second ?: "") }
            ?.toMap().orEmpty()
        val role = attrs["role"].orEmpty().lowercase(Locale.ENGLISH)
        if (role == "search" || role == "searchbox") return true
        if ("search" in (attrs["class"].orEmpty().lowercase(Locale.ENGLISH))) return true
        if ("search" in (attrs["id"].orEmpty().lowercase(Locale.ENGLISH))) return true
        html.attributes?.forEach { attr ->
            if (attr.first.lowercase(Locale.ENGLISH).startsWith("data-") &&
                (attr.second ?: "").lowercase(Locale.ENGLISH).contains("search")
            ) return true
        }
        return false
    }

    /**
     * 密码字段提升（借鉴 bitwarden updateForMissingPasswordFields，已改为无条件执行）：
     * 当候选里无 PASSWORD 时，把含 password 术语但被识别为其它 hint（通常是 USERNAME:LOWEST，
     * 因 inputType 非标准所致，如 Via 等轻量 WebView 浏览器把密码框报成 WEB_EDIT_TEXT）的候选
     * 提升为 PASSWORD:LOW。提升后 hasPasswordInItems=true，低精度账号字段自然保留并弹窗；
     * 无 password 术语的（如 QQ 搜索框）不受影响，仍按原逻辑过滤。
     * 注意：此提升已在 parse() 内无条件调用（不再依赖 allowWeakTargets），以对齐 Bitwarden
     * 「缺失密码字段恢复」的始终执行语义——否则在「首轮已识别账号框」的 Via 场景下会被跳过，
     * 导致密码框被 loginFilteredItems 丢弃、无法填充。
     */
    private fun promotePasswordTermCandidates(items: List<RawParsedItem>): List<RawParsedItem> {
        val hasPassword = items.any {
            it.hint == InternalHint.PASSWORD || it.hint == InternalHint.NEW_PASSWORD
        }
        if (hasPassword) return items
        val anyPromotable = items.any { it.hasPasswordTerm && it.hint != InternalHint.OFF }
        if (!anyPromotable) return items
        return items.map { item ->
            if (item.hasPasswordTerm &&
                item.hint != InternalHint.OFF &&
                item.hint != InternalHint.PASSWORD &&
                item.hint != InternalHint.NEW_PASSWORD
            ) {
                item.copy(hint = InternalHint.PASSWORD, accuracy = Accuracy.LOW)
            } else {
                item
            }
        }
    }

    /**
     * 账号字段邻居提升（对齐 bitwarden `updateForMissingUsernameFields`）：
     * 当候选中已存在密码框、但没有任何账号类字段时，把「位于首个密码框之前、且离它最近」的
     * UNKNOWN 候选提升为 USERNAME:LOW。
     *
     * 这是 WEB_EDIT_TEXT 兜底改判 UNKNOWN 之后的召回补偿。Via 等轻量 WebView 浏览器会把
     * 登录页的账号框和密码框都报成 TYPE_TEXT_VARIATION_WEB_EDIT_TEXT：密码框靠
     * promotePasswordTermCandidates 按 password 术语提升，账号框则没有任何术语可依，
     * 只能靠「它就在密码框上面」这个结构特征来认定——这正是 bitwarden 的做法。
     *
     * 与旧的 USERNAME:LOWEST 全量兜底相比，这里多了三重约束：
     *   1. 必须存在密码框（孤立搜索栏不满足，京东搜索栏误弹在此被切断）；
     *   2. 必须尚无账号框（已识别出账号框时不画蛇添足）；
     *   3. 只提升紧邻密码框上方的那一个（不会波及页面上其它无关文本框）。
     *
     * 调用时机必须在 [promotePasswordTermCandidates] 之后——密码框先就位，才谈得上找它的邻居。
     */
    private fun promoteUsernameNeighborCandidates(
        items: List<RawParsedItem>,
    ): List<RawParsedItem> {
        val promotionIndex = AutofillFieldPromotionPolicy.selectUsernameNeighborIndex(
            items.map { item ->
                AutofillFieldPromotionPolicy.Candidate(
                    isPassword = item.hint == InternalHint.PASSWORD ||
                        item.hint == InternalHint.NEW_PASSWORD,
                    isAccount = item.hint == InternalHint.USERNAME ||
                        item.hint == InternalHint.EMAIL_ADDRESS ||
                        item.hint == InternalHint.PHONE_NUMBER,
                    isUnknown = item.hint == InternalHint.UNKNOWN,
                    isVisible = item.isVisible,
                    traversalIndex = item.traversalIndex,
                )
            },
        )
        if (promotionIndex == AutofillFieldPromotionPolicy.NO_PROMOTION) return items

        val neighbor = items[promotionIndex]
        AutofillLogger.d(
            "PARSING",
            "Promoted UNKNOWN neighbor above password field to USERNAME",
            metadata = mapOf(
                "neighborTraversalIndex" to neighbor.traversalIndex,
                "unknownCandidateCount" to items.count { it.hint == InternalHint.UNKNOWN },
            ),
        )

        return items.mapIndexed { index, item ->
            if (index == promotionIndex) {
                item.copy(hint = InternalHint.USERNAME, accuracy = Accuracy.LOW)
            } else {
                item
            }
        }
    }

    private fun isEditableTextLikeNode(node: AssistStructure.ViewNode): Boolean {
        if (node.visibility != View.VISIBLE) return false
        if (node.autofillId == null) return false

        if (node.autofillType == View.AUTOFILL_TYPE_TEXT) return true
        if (node.inputType != 0) return true

        val className = node.className?.lowercase(Locale.ENGLISH).orEmpty()
        if (
            className.contains("edittext") ||
            className.contains("textinput") ||
            className.contains("textfield") ||
            className.contains("autocompletetextview")
        ) {
            return true
        }

        val htmlTag = node.htmlInfo?.tag?.lowercase(Locale.ENGLISH).orEmpty()
        if (htmlTag == "input" || htmlTag == "textarea") return true

        val htmlType = node.htmlInfo?.attributes
            ?.firstOrNull { it.first.equals("type", ignoreCase = true) }
            ?.second
            ?.lowercase(Locale.ENGLISH)
            .orEmpty()
        return htmlType == "text" ||
            htmlType == "email" ||
            htmlType == "tel" ||
            htmlType == "password"
    }

    private fun mapHint(hint: InternalHint): FieldHint? = when (hint) {
        InternalHint.USERNAME -> FieldHint.USERNAME
        InternalHint.PASSWORD -> FieldHint.PASSWORD
        InternalHint.NEW_PASSWORD -> FieldHint.NEW_PASSWORD
        InternalHint.EMAIL_ADDRESS -> FieldHint.EMAIL_ADDRESS
        InternalHint.PHONE_NUMBER -> FieldHint.PHONE_NUMBER
        InternalHint.CREDIT_CARD_NUMBER -> FieldHint.CREDIT_CARD_NUMBER
        InternalHint.CREDIT_CARD_EXPIRATION_DATE,
        InternalHint.CREDIT_CARD_EXPIRATION_DAY,
        -> FieldHint.CREDIT_CARD_EXPIRATION_DATE
        InternalHint.CREDIT_CARD_EXPIRATION_MONTH -> FieldHint.CREDIT_CARD_EXPIRATION_MONTH
        InternalHint.CREDIT_CARD_EXPIRATION_YEAR -> FieldHint.CREDIT_CARD_EXPIRATION_YEAR
        InternalHint.CREDIT_CARD_SECURITY_CODE -> FieldHint.CREDIT_CARD_SECURITY_CODE
        InternalHint.CREDIT_CARD_HOLDER_NAME -> FieldHint.CREDIT_CARD_HOLDER_NAME
        InternalHint.POSTAL_ADDRESS -> FieldHint.POSTAL_ADDRESS
        InternalHint.POSTAL_CODE -> FieldHint.POSTAL_CODE
        InternalHint.PERSON_NAME -> FieldHint.PERSON_NAME
        InternalHint.PERSON_FIRST_NAME -> FieldHint.PERSON_FIRST_NAME
        InternalHint.PERSON_LAST_NAME -> FieldHint.PERSON_LAST_NAME
        InternalHint.ADDRESS_CITY -> FieldHint.ADDRESS_CITY
        InternalHint.ADDRESS_REGION -> FieldHint.ADDRESS_REGION
        InternalHint.ADDRESS_COUNTRY -> FieldHint.ADDRESS_COUNTRY
        InternalHint.COMPANY_NAME -> FieldHint.COMPANY_NAME
        InternalHint.IDENTITY_NUMBER -> FieldHint.IDENTITY_NUMBER
        InternalHint.OTP_CODE -> FieldHint.OTP_CODE
        InternalHint.OFF -> null
        InternalHint.UNKNOWN -> FieldHint.UNKNOWN
    }

    private fun inputIsVariationType(inputType: Int, vararg type: Int): Boolean {
        type.forEach {
            if (inputType and InputType.TYPE_MASK_VARIATION == it) {
                return true
            }
        }
        return false
    }
}

