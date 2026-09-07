package com.bastion.app.autofill_ng

import com.bastion.app.logging.runCatchingObserved
import android.app.Activity
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.bastion.app.R
import com.bastion.app.autofill_ng.EnhancedAutofillStructureParserV2.FieldHint
import com.bastion.app.autofill_ng.EnhancedAutofillStructureParserV2.ParsedItem
import com.bastion.app.autofill_ng.builder.AutofillDatasetBuilder
import com.bastion.app.autofill_ng.core.AutofillLogger
import com.bastion.app.data.PasswordDatabase
import com.bastion.app.service.AccessibilityFillCommandStore
import com.bastion.app.service.BastionAccessibilityService
import com.bastion.app.repository.PasswordRepository
import com.bastion.app.security.SecurityManager
import com.bastion.app.ui.components.BastionwordDialogAuthScreen
import com.bastion.app.utils.BiometricAuthHelper
import com.bastion.app.utils.SettingsManager

class AutofillCipherCallbackActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_ARGS = "extra_args"
        private const val EXTRA_ARGS_BUNDLE = "extra_args_bundle"
        private const val EXTRA_ARGS_TOKEN = "extra_args_token"
        private const val TAG = "AutofillCipherCallback"
        private val pendingArgsByToken = ConcurrentHashMap<String, Args>()

        fun getIntent(context: Context, args: Args): Intent {
            val token = UUID.randomUUID().toString()
            pendingArgsByToken[token] = args
            return Intent(context, AutofillCipherCallbackActivity::class.java).apply {
                putExtra(EXTRA_ARGS_TOKEN, token)
                putExtra(
                    EXTRA_ARGS_BUNDLE,
                    Bundle().apply {
                        classLoader = Args::class.java.classLoader
                        putParcelable(EXTRA_ARGS, args)
                    }
                )
                putExtra(EXTRA_ARGS, args)
            }
        }
    }

    @Parcelize
    data class Args(
        val passwordId: Long,
        val applicationId: String? = null,
        val webDomain: String? = null,
        val interactionIdentifier: String? = null,
        val interactionIdentifierAliases: ArrayList<String>? = null,
        val autofillIds: ArrayList<AutofillId>? = null,
        val autofillHints: ArrayList<String>? = null,
        val fieldSignatureKey: String? = null,
        val rememberLastFilled: Boolean = true,
        val requireAuthentication: Boolean = false,
        /**
         * WebView 对齐 Bitwarden 场景：跳过 AssistStructure re-parse，直接用 callback_args 烘焙的
         * autofillIds/autofillHints 回填。re-parse 在 WebView（如 Edge）上可能给出与构建期不同的
         * hints，导致 resolveFilledValues 按 hint 映射时账户名失配。
         */
        val forceCallbackTargets: Boolean = false,
    ) : Parcelable

    private var callbackArgs: Args? = null
    private var callbackArgsToken: String? = null
    private lateinit var securityManager: SecurityManager
    private lateinit var settingsManager: SettingsManager
    private lateinit var biometricAuthHelper: BiometricAuthHelper
    private var biometricPromptShown = false
    private var resultPublished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        setContentView(R.layout.activity_transparent)
        callbackArgs = resolveArgsFromIntent(intent)
        securityManager = SecurityManager(applicationContext)
        settingsManager = SettingsManager(applicationContext)
        biometricAuthHelper = BiometricAuthHelper(this)

        val args = callbackArgs
        AutofillLogger.i(
            "CALLBACK",
            "Autofill callback activity started",
            metadata = mapOf(
                "hasArgs" to (args != null),
                "requireAuthentication" to (args?.requireAuthentication ?: false),
                "passwordId" to (args?.passwordId ?: -1L),
            )
        )
        if (args?.requireAuthentication == true) {
            startAuthentication()
        } else {
            lifecycleScope.launch {
                completeCipherAutofill()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        callbackArgs = resolveArgsFromIntent(intent)
    }

    private fun startAuthentication() {
        lifecycleScope.launch {
            val biometricEnabled = settingsManager.settingsFlow.first().biometricEnabled
            val biometricAvailable = biometricAuthHelper.isBiometricAvailable()
            AutofillLogger.i(
                "CALLBACK",
                "Starting autofill authentication",
                metadata = mapOf(
                    "biometricEnabled" to biometricEnabled,
                    "biometricAvailable" to biometricAvailable,
                )
            )
            if (biometricEnabled && biometricAvailable) {
                showBiometricPrompt()
            } else {
                showPasswordAuthentication()
            }
        }
    }

    private fun showBiometricPrompt() {
        if (biometricPromptShown || resultPublished || isFinishing || isDestroyed) {
            return
        }
        biometricPromptShown = true
        AutofillLogger.i("CALLBACK", "Showing biometric prompt for autofill")
        runCatchingObserved {
            biometricAuthHelper.authenticate(
                activity = this@AutofillCipherCallbackActivity,
                title = getString(R.string.autofill_auth_title),
                subtitle = getString(R.string.autofill_auth_subtitle),
                description = getString(R.string.autofill_auth_description),
                negativeButtonText = getString(R.string.use_password),
                onSuccess = {
                    AutofillLogger.i("CALLBACK", "Biometric prompt succeeded")
                    if (securityManager.unlockVaultWithBiometric()) {
                        lifecycleScope.launch { completeCipherAutofill() }
                    } else {
                        AutofillLogger.w("CALLBACK", "Biometric unlock failed, falling back to password")
                        showPasswordAuthentication()
                    }
                },
                onError = { code, message ->
                    AutofillLogger.w("CALLBACK", "Biometric prompt error: $code $message")
                    showPasswordAuthentication()
                },
                onCancel = {
                    AutofillLogger.w("CALLBACK", "Biometric prompt cancelled")
                    cancelAndFinish("authentication_cancelled")
                },
            )
        }.onFailure { error ->
            biometricPromptShown = false
            AutofillLogger.w(
                "CALLBACK",
                "Biometric prompt failed to show, falling back to password: ${error.message.orEmpty()}"
            )
            showPasswordAuthentication()
        }
    }

    private fun showPasswordAuthentication() {
        AutofillLogger.i("CALLBACK", "Showing password authentication for autofill")
        setContent {
            BastionwordDialogAuthScreen(
                settingsFlow = settingsManager.settingsFlow,
                appName = getString(R.string.app_name),
                title = getString(R.string.verify_identity),
                subtitle = getString(R.string.autofill_auth_subtitle),
                passwordLabel = getString(R.string.master_password),
                description = getString(R.string.enter_master_password),
                confirmText = getString(R.string.confirm),
                cancelText = getString(R.string.cancel),
                emptyError = getString(R.string.current_password_required),
                unsupportedCharacterError = getString(R.string.error_password_contains_unsupported_characters),
                minLengthError = getString(R.string.error_password_too_short),
                incorrectError = getString(R.string.password_incorrect),
                verifyPassword = { input -> securityManager.unlockVaultWithPassword(input) },
                onSuccess = {
                    lifecycleScope.launch { completeCipherAutofill() }
                },
                onCancel = { cancelAndFinish("authentication_cancelled") },
            )
        }
    }

    private suspend fun completeCipherAutofill() {
        val callbackArgs = callbackArgs ?: run {
            cancelAndFinish("missing_args")
            return
        }
        val repository = PasswordRepository(
            PasswordDatabase.getDatabase(applicationContext).passwordEntryDao()
        )
        val passwordEntry = withContext(Dispatchers.IO) {
            repository.getPasswordEntryById(callbackArgs.passwordId)
        } ?: run {
            cancelAndFinish("missing_password_entry")
            return
        }

        val accountValue = AccountFillPolicy.resolveAccountIdentifier(passwordEntry, securityManager)
        val decryptedPassword = AutofillSecretResolver.decryptPasswordOrNull(
            securityManager = securityManager,
            encryptedOrPlain = passwordEntry.password,
            logTag = TAG,
        )
        val resolvedTargets = resolveAutofillTargets(callbackArgs)
        if (resolvedTargets.ids.isEmpty()) {
            cancelAndFinish("missing_autofill_ids")
            return
        }
        val filledValues = resolveFilledValues(
            accountValue = accountValue,
            decryptedPassword = decryptedPassword,
            autofillIds = resolvedTargets.ids,
            autofillHints = resolvedTargets.hints,
        )
        if (filledValues.isEmpty()) {
            cancelAndFinish("no_resolved_values")
            return
        }

        val title = passwordEntry.title.ifBlank {
            getString(R.string.autofill_manual_entry_title)
        }
        val subtitle = accountValue.ifBlank {
            callbackArgs.webDomain
                ?: callbackArgs.applicationId
                ?: getString(R.string.app_name)
        }
        val menuPresentation = AutofillDatasetBuilder.RemoteViewsFactory.createPasswordEntry(
            context = this,
            title = title,
            username = subtitle
        )
        val fields = linkedMapOf<AutofillId, AutofillDatasetBuilder.FieldData?>()
        filledValues.forEach { (autofillId, value) ->
            fields[autofillId] = AutofillDatasetBuilder.FieldData(
                value = AutofillValue.forText(value),
                presentation = menuPresentation
            )
        }
        val dataset = AutofillDatasetBuilder.create(
            menuPresentation = menuPresentation,
            fields = fields
        ) { null }.build()

        withContext(Dispatchers.IO) {
            if (callbackArgs.rememberLastFilled) {
                rememberLastFilledCredential(
                    passwordId = passwordEntry.id,
                    primaryIdentifier = callbackArgs.interactionIdentifier,
                    aliases = callbackArgs.interactionIdentifierAliases.orEmpty(),
                )
            }
            rememberLearnedFieldSignature(callbackArgs.fieldSignatureKey)
        }

        AutofillLogger.i(
            "CALLBACK",
            "Returning authenticated dataset without picker UI",
            metadata = mapOf(
                "passwordId" to passwordEntry.id,
                "filledCount" to filledValues.size,
                "applicationId" to (callbackArgs.applicationId ?: "none"),
                "webDomain" to (callbackArgs.webDomain ?: "none"),
                "targetSource" to resolvedTargets.source,
                "targetHintPreview" to resolvedTargets.hints
                    .take(12)
                    .mapIndexed { index, hint -> "$index:${hint.trim().uppercase()}" }
                    .joinToString(separator = ","),
            )
        )

        setResult(
            Activity.RESULT_OK,
            Intent().apply {
                putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset)
            }
        )

        // 无障碍兜底：部分 WebView（如 Via + PayPal）框架 dataset 回填不可靠——上文 dataset 已正确
        // 返回，但密码/用户名框收不到值。此时改走无障碍直接注入：把凭据写入跨进程命令存储并广播
        // 唤醒无障碍服务，由其对当前浏览器窗口的密码/用户名节点直接注入（绕开框架 autofillId 匹配）。
        // 仅在 WebView（有 webDomain）且用户已开启无障碍服务时触发；原生 App 走框架即可，不受影响。
        if (!callbackArgs.webDomain.isNullOrBlank() &&
            BastionAccessibilityService.isCredentialFillAvailable(applicationContext)
        ) {
            AccessibilityFillCommandStore.attach(applicationContext)
            // OTP 计算需在协程内进行（涉及密钥解密 / 查库），用进程级作用域确保 Activity
            // finish 后仍能安全完成；无障碍接收端有重试窗口，稍晚广播不影响注入。
            ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.IO) {
                val otp = runCatching { generateOtpCodeForPassword(applicationContext, passwordEntry) }.getOrNull()
                AccessibilityFillCommandStore.write(
                    AccessibilityFillCommandStore.Command(
                        packageName = callbackArgs.applicationId ?: "",
                        username = accountValue,
                        password = decryptedPassword ?: "",
                        preferPasswordField = true,
                        otp = otp ?: "",
                        createdAt = System.currentTimeMillis(),
                    )
                )
                applicationContext.sendBroadcast(
                    Intent(AccessibilityFillCommandStore.ACTION_FILL_COMMAND)
                        .setPackage(applicationContext.packageName)
                )
                AutofillLogger.d(
                    "CALLBACK",
                    "Accessibility fallback command dispatched (otp=${if (otp.isNullOrBlank()) "none" else "present"})",
                    metadata = mapOf("webDomain" to (callbackArgs.webDomain ?: "none")),
                )
            }
        }

        // 回归修复：认证填充主路径此前缺少 OTP 自动复制副作用（仅挂在 Picker Activity 上，
        // 而 vault 锁定时框架直接走 AutofillCipherCallbackActivity 完成填充，绕过 Picker）。
        // 用 ProcessLifecycleOwner.get().lifecycleScope（进程级作用域）而非 Activity.lifecycleScope：
        // Activity 在 finish() 后仍可安全完成 OTP 自动复制副作用，协程在进程销毁时方被结构化取消。
        Log.d(
            "BastionOtpCopy",
            "trigger: completeCipherAutofill reached, passwordId=${passwordEntry.id}, " +
                "hints=${callbackArgs.autofillHints}, autoCopyEnabledPathPending"
        )
        ProcessLifecycleOwner.get().lifecycleScope.launch(Dispatchers.Default) {
            performOtpAutofillSideEffects(
                context = applicationContext,
                password = passwordEntry,
                autofillHints = callbackArgs.autofillHints,
            )
        }

        finishWithoutAnimation()
    }

    private fun resolveArgsFromIntent(intent: Intent?): Args? {
        if (intent == null) return null
        callbackArgsToken = intent.getStringExtra(EXTRA_ARGS_TOKEN)
        callbackArgsToken
            ?.let { pendingArgsByToken[it] }
            ?.let { return it }

        intent.getBundleExtra(EXTRA_ARGS_BUNDLE)
            ?.apply { classLoader = Args::class.java.classLoader }
            ?.let { bundle ->
                val args = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    bundle.getParcelable(EXTRA_ARGS, Args::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    bundle.getParcelable(EXTRA_ARGS)
                }
                if (args != null) return args
            }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_ARGS, Args::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_ARGS)
        }
    }

    private data class ResolvedAutofillTargets(
        val ids: List<AutofillId>,
        val hints: List<String>,
        val source: String,
    )

    private fun resolveAutofillTargets(callbackArgs: Args): ResolvedAutofillTargets {
        val assistStructure = getAssistStructureOrNull()
        val callbackIds = callbackArgs.autofillIds?.distinct().orEmpty()
        val callbackHints = callbackArgs.autofillHints.orEmpty()

        // WebView 对齐 Bitwarden 场景（forceCallbackTargets）：跳过 re-parse，直接用 callback_args
        // 烘焙的 ids/hints。re-parse 在 WebView（如 Edge）上可能给出与构建期不同的 hints，导致
        // resolveFilledValues 按 hint 映射时账户名失配。callback_args 的 id 虽是构建期的，但
        // forceDatasetAuth 路径下填充值已带在 dataset（filledItems），框架回灌时用相同 id 集合即可。
        if (callbackArgs.forceCallbackTargets && callbackIds.isNotEmpty()) {
            val idHintPairs = callbackIds.mapIndexed { index, id ->
                id to callbackHints.getOrNull(index).orEmpty()
            }
            return ResolvedAutofillTargets(
                ids = idHintPairs.map { it.first },
                hints = idHintPairs.map { it.second },
                source = "callback_args_forced_webview",
            )
        }

        // 认证回灌路径（forceCallbackTargets=false，如 vault 锁定 / 密码解密失败降级）：
        // 优先使用构建期烘焙的完整目标（含 username+password），条件是这些 id 在当前 assist
        // structure 中仍然存活。Edge 等标准 WebView 的 autofillId 在回调期保持有效，直接复用
        // 烘焙目标可避免 re-parse 只命中密码框（如 GitHub 登录页）导致"只填密码、账号缺失"的
        // 半填充。Via 等 DOM 节点复用的 WebView，构建期 id 已失效（不在当前结构中），此时回退
        // 下方 re-parse 拿"活的" id，保证写回不静默失败（回归保护）。
        if (callbackIds.isNotEmpty() && assistStructure != null) {
            val liveIds = collectAutofillIds(assistStructure)
            if (callbackIds.all { liveIds.contains(it) }) {
                return ResolvedAutofillTargets(
                    ids = callbackIds,
                    hints = callbackHints,
                    source = "callback_args_live_verified",
                )
            }
        }

        // Bitwarden 对齐：优先从当前 AssistStructure 重新解析 autofillId。
        // WebView（尤其是 Via 等第三方浏览器）的 autofillId 可能在 FillResponse
        // 构建时与 callback 回调时不一致（DOM 节点复用导致 id 变化），使用烘焙进
        // PendingIntent 的旧 id 会导致数据集写回静默失败。
        //
        // 策略（对齐 Bitwarden AutofillCompletionManagerImpl）：
        // 1. 优先从当前 AssistStructure re-parse，获取"活的" autofillId
        // 2. 按 hint 类型匹配 callbackArgs 的 hints，保持构建期合成逻辑的优势
        // 3. re-parse 失败时回退到 callback_args 的旧 id（兼容性保障）
        if (assistStructure != null) {
            val parsedTargets = runCatchingObserved {
                val parser = EnhancedAutofillStructureParserV2()
                val parsed = parser.parse(assistStructure, respectAutofillOff = false)
                selectLoginFillableTargets(parsed.items)
            }.getOrDefault(emptyList())

            if (parsedTargets.isNotEmpty()) {
                // 按 hint 类型匹配：re-parse 的 autofillId（活的）+ callback_args 的 hint（更准）
                // 当两者数量不一致时（如构建期合成了 username 但 re-parse 没找到），
                // 以 re-parse 为准（因为 id 必须有效），hint 用 re-parse 自身的。
                val mergedTargets = if (parsedTargets.size == callbackHints.size) {
                    // 数量一致：直接按索引覆盖 hints
                    parsedTargets.mapIndexed { index, target ->
                        target.id to (
                            callbackHints.getOrNull(index)
                                ?.takeIf { it.isNotBlank() }
                                ?: target.hint.name
                        )
                    }
                } else {
                    // 数量不一致：按 hint 类型做最佳匹配
                    val callbackHintSet = callbackHints.toSet()
                    parsedTargets.map { target ->
                        val matchedHint = if (callbackHintSet.contains(target.hint.name)) {
                            target.hint.name  // re-parse hint 在 callback 集合中，保持一致
                        } else {
                            // 尝试模糊匹配：password → PASSWORD, username → USERNAME
                            callbackHints.firstOrNull { ch ->
                                ch.equals(target.hint.name, ignoreCase = true) ||
                                    target.hint.name.contains(ch, ignoreCase = true) ||
                                    ch.contains(target.hint.name, ignoreCase = true)
                            } ?: target.hint.name
                        }
                        target.id to matchedHint
                    }
                }
                return ResolvedAutofillTargets(
                    ids = mergedTargets.map { it.first },
                    hints = mergedTargets.map { it.second },
                    source = "assist_structure_fresh",
                )
            }
        }

        // 回退：assist structure 不可用或 re-parse 无结果时，使用 callback_args 的旧 id。
        // 对旧 id 做有效性校验，过滤掉已销毁的节点。
        if (callbackIds.isNotEmpty()) {
            val idHintPairs = callbackIds.mapIndexed { index, id ->
                id to callbackHints.getOrNull(index).orEmpty()
            }
            val validPairs = if (assistStructure != null) {
                val liveIds = collectAutofillIds(assistStructure)
                idHintPairs.filter { liveIds.contains(it.first) }
            } else {
                idHintPairs
            }
            if (validPairs.isNotEmpty()) {
                return ResolvedAutofillTargets(
                    ids = validPairs.map { it.first },
                    hints = validPairs.map { it.second },
                    source = "callback_args_fallback",
                )
            }
        }

        return ResolvedAutofillTargets(emptyList(), emptyList(), "none")
    }

    private fun collectAutofillIds(structure: AssistStructure): Set<AutofillId> {
        val ids = mutableSetOf<AutofillId>()
        for (i in 0 until structure.windowNodeCount) {
            collectAutofillIds(structure.getWindowNodeAt(i).rootViewNode, ids)
        }
        return ids
    }

    private fun collectAutofillIds(node: AssistStructure.ViewNode, ids: MutableSet<AutofillId>) {
        node.autofillId?.let { ids.add(it) }
        for (i in 0 until node.childCount) {
            node.getChildAt(i)?.let { collectAutofillIds(it, ids) }
        }
    }

    private fun getAssistStructureOrNull(): AssistStructure? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(AutofillManager.EXTRA_ASSIST_STRUCTURE, AssistStructure::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(AutofillManager.EXTRA_ASSIST_STRUCTURE)
        }
    }

    private fun selectLoginFillableTargets(items: List<ParsedItem>): List<ParsedItem> {
        if (items.isEmpty()) return emptyList()
        val filtered = items.filter { isLoginHint(it.hint) }
        if (filtered.isEmpty()) return emptyList()

        val deduped = linkedMapOf<String, ParsedItem>()
        filtered.sortedWith(
            compareByDescending<ParsedItem> { it.isFocused }
                .thenByDescending { loginHintPriority(it.hint) }
                .thenByDescending { it.accuracy.score }
                .thenBy { it.traversalIndex }
        ).forEach { item ->
            deduped.putIfAbsent(item.id.toString(), item)
        }
        return deduped.values.toList()
    }

    private fun loginHintPriority(hint: FieldHint): Int = when (hint) {
        FieldHint.PASSWORD, FieldHint.NEW_PASSWORD -> 3
        FieldHint.USERNAME, FieldHint.EMAIL_ADDRESS, FieldHint.PHONE_NUMBER -> 2
        else -> 0
    }

    private fun isLoginHint(hint: FieldHint): Boolean {
        return hint == FieldHint.USERNAME ||
            hint == FieldHint.EMAIL_ADDRESS ||
            hint == FieldHint.PHONE_NUMBER ||
            hint == FieldHint.PASSWORD ||
            hint == FieldHint.NEW_PASSWORD
    }

    private fun resolveFilledValues(
        accountValue: String,
        decryptedPassword: String?,
        autofillIds: List<AutofillId>,
        autofillHints: List<String>,
    ): LinkedHashMap<AutofillId, String> {
        val normalizedHints = autofillHints.map { it.trim().lowercase() }
        val hasPasswordTarget = normalizedHints.any {
            it == EnhancedAutofillStructureParserV2.FieldHint.PASSWORD.name.lowercase() ||
                it == EnhancedAutofillStructureParserV2.FieldHint.NEW_PASSWORD.name.lowercase() ||
                it.contains("password") ||
                it.contains("pass")
        }
        if (hasPasswordTarget && decryptedPassword.isNullOrBlank()) {
            Log.w(TAG, "Authentication callback canceled: password decryption unavailable")
            return linkedMapOf()
        }

        val fillEmailWithAccount = AccountFillPolicy.shouldFillEmailWithAccount(applicationContext)
        val hasUsernameHint = normalizedHints.any {
            it == EnhancedAutofillStructureParserV2.FieldHint.USERNAME.name.lowercase() ||
                it.contains("username")
        }
        val hasPhoneHint = normalizedHints.any {
            it == EnhancedAutofillStructureParserV2.FieldHint.PHONE_NUMBER.name.lowercase() ||
                it.contains("phone") ||
                it.contains("mobile") ||
                it.contains("tel")
        }
        val hasEmailHint = normalizedHints.any {
            it == EnhancedAutofillStructureParserV2.FieldHint.EMAIL_ADDRESS.name.lowercase() ||
                it.contains("email")
        }
        val hasAccountHint = hasUsernameHint || hasPhoneHint
        val allowAccountInEmailField =
            fillEmailWithAccount || accountValue.contains("@") || (!hasAccountHint && hasEmailHint)

        val filledValues = linkedMapOf<AutofillId, String>()
        autofillIds.forEachIndexed { index, autofillId ->
            val normalizedHint = autofillHints.getOrNull(index)?.trim()?.lowercase().orEmpty()
            val value = when {
                normalizedHint == EnhancedAutofillStructureParserV2.FieldHint.USERNAME.name.lowercase() ||
                    normalizedHint.contains("username") -> accountValue
                normalizedHint == EnhancedAutofillStructureParserV2.FieldHint.PHONE_NUMBER.name.lowercase() ||
                    normalizedHint.contains("phone") ||
                    normalizedHint.contains("mobile") ||
                    normalizedHint.contains("tel") -> accountValue
                normalizedHint == EnhancedAutofillStructureParserV2.FieldHint.EMAIL_ADDRESS.name.lowercase() ||
                    normalizedHint.contains("email") -> if (allowAccountInEmailField) accountValue else null
                normalizedHint == EnhancedAutofillStructureParserV2.FieldHint.PASSWORD.name.lowercase() ||
                    normalizedHint == EnhancedAutofillStructureParserV2.FieldHint.NEW_PASSWORD.name.lowercase() ||
                    normalizedHint.contains("password") ||
                    normalizedHint.contains("pass") -> decryptedPassword
                else -> null
            }
            if (!value.isNullOrBlank()) {
                filledValues[autofillId] = value
            }
        }

        if (filledValues.isNotEmpty()) {
            return filledValues
        }

        Log.w(TAG, "No strict hint matched in callback, trying controlled fallback")
        autofillIds.forEachIndexed { index, autofillId ->
            val normalizedHint = autofillHints.getOrNull(index)?.lowercase().orEmpty()
            val fallbackValue = when {
                normalizedHint.contains("pass") -> decryptedPassword
                normalizedHint.contains("user") ||
                    normalizedHint.contains("email") ||
                    normalizedHint.contains("phone") ||
                    normalizedHint.contains("mobile") ||
                    normalizedHint.contains("tel") ||
                    normalizedHint.contains("号码") ||
                    normalizedHint.contains("手机号") ||
                    normalizedHint.contains("account") ||
                    normalizedHint.contains("login") -> accountValue
                autofillIds.size == 1 -> if (accountValue.isNotBlank()) accountValue else decryptedPassword
                index == 0 -> accountValue
                index == 1 -> decryptedPassword
                else -> null
            }
            if (!fallbackValue.isNullOrBlank()) {
                filledValues[autofillId] = fallbackValue
            }
        }
        return filledValues
    }

    private suspend fun rememberLastFilledCredential(
        passwordId: Long,
        primaryIdentifier: String?,
        aliases: List<String>,
    ) {
        val normalizedIdentifiers = buildList {
            primaryIdentifier
                ?.trim()
                ?.lowercase()
                ?.takeIf { it.isNotBlank() }
                ?.let(::add)
            aliases
                .asSequence()
                .map { it.trim().lowercase() }
                .filter { it.isNotBlank() }
                .forEach(::add)
        }.distinct()
        if (normalizedIdentifiers.isEmpty()) return

        val preferences = AutofillPreferences(applicationContext)
        normalizedIdentifiers.forEach { identifier ->
            preferences.completeAutofillInteraction(identifier, passwordId)
        }
    }

    private suspend fun rememberLearnedFieldSignature(fieldSignatureKey: String?) {
        val signatureKey = fieldSignatureKey?.trim()?.lowercase().orEmpty()
        if (signatureKey.isBlank()) return
        AutofillPreferences(applicationContext).markFieldSignatureLearned(signatureKey)
    }

    private fun cancelAndFinish(reason: String) {
        AutofillLogger.w("CALLBACK", "Cancel autofill callback: $reason")
        resultPublished = true
        setResult(Activity.RESULT_CANCELED)
        finishWithoutAnimation()
    }

    private fun finishWithoutAnimation() {
        resultPublished = true
        callbackArgsToken?.let { pendingArgsByToken.remove(it) }
        finish()
        overridePendingTransition(0, 0)
    }
}
