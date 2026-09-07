package com.bastion.app.autofill_ng

import com.bastion.app.logging.runCatchingObserved
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.view.View
import android.view.autofill.AutofillId
import android.text.InputType
import android.util.Log
import android.view.inputmethod.InlineSuggestionsRequest
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.bastion.app.autofill_ng.AutofillPreferences
import com.bastion.app.autofill_ng.AutofillSaveTransparentActivity
import com.bastion.app.autofill_ng.DomainMatchStrategy
import com.bastion.app.autofill_ng.EnhancedAutofillStructureParserV2
import com.bastion.app.autofill_ng.EnhancedAutofillStructureParserV2.Accuracy
import com.bastion.app.autofill_ng.EnhancedAutofillStructureParserV2.FieldHint
import com.bastion.app.autofill_ng.EnhancedAutofillStructureParserV2.ParsedItem
import com.bastion.app.autofill_ng.processor.AutofillProcessorNg
import com.bastion.app.autofill_ng.auth.AutofillGrantContext
import com.bastion.app.autofill_ng.auth.AutofillSessionGrants
import com.bastion.app.autofill_ng.auth.AutofillUnlockRequests
import com.bastion.app.autofill_ng.core.AutofillDiagnostics
import com.bastion.app.autofill_ng.core.AutofillLogger
import com.bastion.app.autofill_ng.core.safeTextOrNull
import com.bastion.app.autofill_ng.BitwardenLikeAutofillMatcherNg
import com.bastion.app.data.PasswordDatabase
import com.bastion.app.data.PasswordEntry
import com.bastion.app.repository.PasswordRepository
import com.bastion.app.service.BrowserAutofillContextStore
import com.bastion.app.service.AccessibilityFillCommandStore
import com.bastion.app.service.BastionAccessibilityService
import com.bastion.app.utils.DeviceUtils
import com.bastion.app.utils.SettingsManager
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Autofill service (Bitwarden engine).
 *
 * This service intentionally keeps a single deterministic pipeline:
 * parser -> bitwarden-style matcher -> bw-compatible response.
 */
class BastionAutofillServiceNg : AutofillService() {
    private companion object {
        private const val TAG = "BastionAutofillServiceNg"
        private val PACKAGE_NAME_REGEX =
            Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
        private const val PARSED_ITEM_ACCURACY_THRESHOLD = 1.5f
        private const val PASSWORD_ONLY_DIRECT_FILL_WINDOW_MS = 120_000L
        private const val RESPONSE_STABILITY_WINDOW_MS = 2_000L
        private val fillRequestSequence = AtomicLong(0L)
        // SHA-256 digest 复用：避免每次 onFillRequest 都 MessageDigest.getInstance（含 Provider 查找）。
        // ThreadLocal 保证线程安全（autofill 服务回调可能并发）。digest() 调用后自动 reset。
        private val shaDigest by lazy {
            ThreadLocal.withInitial { MessageDigest.getInstance("SHA-256") }
        }
    }

    /**
     * 跨请求登录字段记忆：缓存各包最近一次识别到的登录字段列表（账号 + 密码）。
     * 用于应对电影猎手等 App 的密码框在部分 FillRequest 中因布局/动画时序而不可见、
     * 被解析器丢弃，连带账号也被低精度过滤清掉，导致登录目标「时有时无」。
     * 仅当缓存的所有登录字段 AutofillId 仍存在于当前 AssistStructure 时才整体回补，
     * 避免注入失效 id。
     */
    // LruCache(16)：限制无界增长，accessOrder=true 使最近访问置尾，淘汰最久未用。
    // 原 mutableMapOf 无上限，长期运行的 autofill 进程切换 App 时不断累积 ParsedItem（含 AutofillId）。
    private val passwordMemoryByPackage =
        object : LinkedHashMap<String, List<ParsedItem>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, List<ParsedItem>>): Boolean =
                size > 16
        }

    private data class RecentFillSuggestions(
        val key: String,
        val createdAtMs: Long,
        val requestId: Long,
        val targetCount: Int,
        val passwords: List<PasswordEntry>,
    )

    private data class StructuredConfidenceDecision(
        val highConfidence: Boolean,
        val reason: String,
        val structuredCount: Int,
        val bankCardCount: Int,
        val documentCount: Int,
        val dominantCount: Int,
        val keyHintCount: Int,
        val confidentCount: Int,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * 启动配置观测协程：collect 依赖 scope 存活。scope 被取消（服务断开/系统回收）时,
     * CancellationException 属正常控制流, 静默; 其余真实故障才记 [AutofillLogger.w]。
     */
    private fun launchObserved(tag: String, message: String, block: suspend () -> Unit) {
        scope.launch {
            runCatchingObserved { block() }.onFailure { e ->
                if (e is CancellationException) return@onFailure
                AutofillLogger.w(tag, "$message: ${e.message}")
            }
        }
    }

    private lateinit var passwordRepository: PasswordRepository
    private lateinit var autofillPreferences: AutofillPreferences
    private lateinit var settingsManager: SettingsManager
    private lateinit var diagnostics: AutofillDiagnostics

    private val parser = EnhancedAutofillStructureParserV2()
    private val matcher = BitwardenLikeAutofillMatcherNg()
    private val bwCompatProcessor by lazy { AutofillProcessorNg(applicationContext) }
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                AutofillSessionGrants.clear()
                AutofillUnlockRequests.clear()
                AutofillLogger.i("AUTH", "Temporary autofill grant cleared on screen off")
            }
        }
    }
    private var screenOffReceiverRegistered = false
    @Volatile
    private var recentFillSuggestions: RecentFillSuggestions? = null
    private val recentFillSuggestionsLock = Any()

    override fun onCreate() {
        super.onCreate()
        AutofillLogger.initialize(applicationContext)
        // 提供应用上下文，使 BrowserAutofillContextStore 能跨进程(:accessibility 写、
        // :autofill 读)共享同一 filesDir 文件中的浏览器填充上下文。
        BrowserAutofillContextStore.attach(applicationContext)
        // 无障碍兜底命令存储：autofill 进程(:autofill)写入、accessibility 进程(:accessibility)消费。
        AccessibilityFillCommandStore.attach(applicationContext)

        val database = PasswordDatabase.getDatabase(applicationContext)
        passwordRepository = PasswordRepository(database.passwordEntryDao())
        autofillPreferences = AutofillPreferences(applicationContext)
        settingsManager = SettingsManager(applicationContext)
        diagnostics = AutofillDiagnostics(applicationContext)
        // 填充配置预加载缓存（方案 B）：冷路径一次性预加载，热路径改读 AutofillConfigCache，
        // 消除 onFillRequest / AccountFillPolicy / FilledDataBuilderNg 内的 runBlocking 读取。
        AutofillConfigCache.preload(applicationContext)
        // 观测设置流，持续刷新缓存字段；collect 立即发射当前值，之后每次变更刷新。
        launchObserved("AFCACHE", "settings observation failed") {
                settingsManager.settingsFlow.collect { s ->
                    AutofillConfigCache.autoLockMinutes = s.autoLockMinutes
                    AutofillConfigCache.separateUsernameAccountEnabled = s.separateUsernameAccountEnabled
                    AutofillConfigCache.language = s.language.name
                    AutofillConfigCache.autofillAuthRequired = s.autofillAuthRequired
                }
        }
        launchObserved("AFCACHE", "inline suggestion observation failed") {
                autofillPreferences.isInlineSuggestionsEnabled.collect { v ->
                    AutofillConfigCache.isInlineSuggestionsEnabled = v
                }
        }
        launchObserved("AFCACHE", "autofill-enabled observation failed") {
                autofillPreferences.isAutofillEnabled.collect { v ->
                    AutofillConfigCache.isAutofillEnabled = v
                }
        }
        // Phase C onFillRequest 全量缓存化（方案 B 延伸）：8 路配置观测
        launchObserved("AFCACHE", "v2-respect-off observation failed") {
                autofillPreferences.isV2RespectAutofillOffEnabled.collect { AutofillConfigCache.isV2RespectAutofillOffEnabled = it }
        }
        launchObserved("AFCACHE", "source-filter observation failed") {
                autofillPreferences.v2DefaultSourceFilter.collect { AutofillConfigCache.v2DefaultSourceFilter = it }
        }
        launchObserved("AFCACHE", "keepass-db-id observation failed") {
                autofillPreferences.v2DefaultKeepassDatabaseId.collect { AutofillConfigCache.v2DefaultKeepassDatabaseId = it }
        }
        launchObserved("AFCACHE", "bitwarden-vault-id observation failed") {
                autofillPreferences.v2DefaultBitwardenVaultId.collect { AutofillConfigCache.v2DefaultBitwardenVaultId = it }
        }
        launchObserved("AFCACHE", "strict-mode observation failed") {
                autofillPreferences.isBitwardenStrictModeEnabled.collect { AutofillConfigCache.isBitwardenStrictModeEnabled = it }
        }
        launchObserved("AFCACHE", "subdomain-match observation failed") {
                autofillPreferences.isBitwardenSubdomainMatchEnabled.collect { AutofillConfigCache.isBitwardenSubdomainMatchEnabled = it }
        }
        launchObserved("AFCACHE", "domain-strategy observation failed") {
                autofillPreferences.domainMatchStrategy.collect { AutofillConfigCache.domainMatchStrategy = it }
        }
        launchObserved("AFCACHE", "password-suggestion observation failed") {
                autofillPreferences.isPasswordSuggestionEnabled.collect { AutofillConfigCache.isPasswordSuggestionEnabled = it }
        }
        ContextCompat.registerReceiver(
            this,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        screenOffReceiverRegistered = true
        launchObserved("AF", "Failed to enforce V2 engine mode") {
            autofillPreferences.ensureBitwardenV2EngineMode()
        }

        // DEBUG：服务创建为常规生命周期事件，仅保留在诊断缓冲，不打 logcat；
        // 措辞避免 20+ 位纯字母数字串（类名）被 sanitize 误判为 token。
        AutofillLogger.d("AF", "Autofill service created")
    }

    override fun onDestroy() {
        AutofillSessionGrants.clear()
        if (screenOffReceiverRegistered) {
            runCatchingObserved { unregisterReceiver(screenOffReceiver) }
            screenOffReceiverRegistered = false
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback,
    ) {
        val requestId = fillRequestSequence.incrementAndGet()
        val startedAt = System.currentTimeMillis()
        // 自我请求预判：仅用于日志分级（received/callback 对 self 请求降 DEBUG），
        // 与 processFillRequest 开头的快速短路判定同源（activityComponent 包名）。
        val selfFastPath = request.fillContexts.lastOrNull()
            ?.structure?.activityComponent?.packageName?.let { isSelfPackage(it) } == true
        // 方法引用不可省略默认参数，三级全量传参；self 请求 received 仍记录（requestId
        // 序列与 flags 对排查"面板不弹"有诊断价值），仅降级不丢失。
        val logReceived = if (selfFastPath) AutofillLogger::d else AutofillLogger::i
        logReceived(
            "AF",
            "onFillRequest received",
            mapOf(
                "requestId" to requestId,
                "sdk" to Build.VERSION.SDK_INT,
                "device" to "${Build.MANUFACTURER}/${Build.MODEL}",
                "flags" to request.flags,
                "fillContextCount" to request.fillContexts.size,
                "cancelled" to cancellationSignal.isCanceled,
            )
        )
        val job = scope.launch {
            try {
                val response = withContext(Dispatchers.Default) {
                    processFillRequest(request, cancellationSignal, requestId)
                }
                val logResult = if (selfFastPath) AutofillLogger::d else AutofillLogger::i
                logResult(
                    "AF",
                    "onFillRequest callback success",
                    mapOf(
                        "requestId" to requestId,
                        "hasResponse" to (response != null),
                        "cancelled" to cancellationSignal.isCanceled,
                        "elapsedMs" to (System.currentTimeMillis() - startedAt),
                    )
                )
                callback.onSuccess(response)
            } catch (e: Exception) {
                AutofillLogger.e(
                    "AF",
                    "onFillRequest failed",
                    e,
                    metadata = mapOf(
                        "requestId" to requestId,
                        "elapsedMs" to (System.currentTimeMillis() - startedAt),
                    )
                )
                diagnostics.logError("AF", "Fill request failed: ${e.message}", e)
                callback.onFailure(e.message ?: "Autofill failed")
            }
        }
        cancellationSignal.setOnCancelListener {
            AutofillLogger.w(
                "AF",
                "onFillRequest cancelled by system",
                metadata = mapOf(
                    "requestId" to requestId,
                    "elapsedMs" to (System.currentTimeMillis() - startedAt),
                )
            )
            job.cancel()
        }
    }

    private suspend fun processFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        requestId: Long,
    ): FillResponse? {
        if (cancellationSignal.isCanceled) {
            AutofillLogger.w("AF", "Skip fill request: already cancelled", metadata = mapOf("requestId" to requestId))
            return null
        }
        if (!AutofillConfigCache.isAutofillEnabled) {
            AutofillLogger.i("AF", "Skip fill request: app autofill disabled", metadata = mapOf("requestId" to requestId))
            return null
        }

        val fillContext = request.fillContexts.lastOrNull() ?: run {
            AutofillLogger.i("AF", "Skip fill request: no fill context", metadata = mapOf("requestId" to requestId))
            return null
        }
        val structure = fillContext.structure
        val fallbackPackage = structure.activityComponent?.packageName.orEmpty()

        // 自我请求快速短路：Bastion 自己的输入框永远不需要填充，无需解析结构树。
        // 提前于 parser.parse 之前返回——既省解析开销，也避免产生 received/Parser×2/
        // DIAG/weak-reparse/Skip/callback 七行零信息量流水线日志（真机日志实测：主界面
        // 每次进出条目触发一轮，15/15 请求全部命中此路径）。
        if (isSelfPackage(fallbackPackage)) {
            AutofillLogger.d(
                "AF",
                "Skip fill request for Bastion itself (fast path): $fallbackPackage",
                metadata = mapOf("requestId" to requestId),
            )
            return null
        }

        if (fallbackPackage.isNotBlank() && autofillPreferences.isInBlacklist(fallbackPackage)) {
            AutofillLogger.i(
                "AF",
                "Package blocked by blacklist: $fallbackPackage",
                metadata = mapOf("requestId" to requestId)
            )
            return null
        }

        val respectAutofillOff = AutofillConfigCache.isV2RespectAutofillOffEnabled
        val isManualRequest = request.flags and FillRequest.FLAG_MANUAL_REQUEST != 0
        var parsed = parser.parse(
            structure = structure,
            respectAutofillOff = respectAutofillOff,
            allowWeakTargets = isManualRequest,
        )
        // 兼容回退（Bitwarden 式）：部分 App（如影视类自定义登录框）的登录字段没有标准
        // autofill hint，首轮保守解析会丢弃全部字段导致不弹面板。此时二次解析开启弱目标，
        // 再由 selectFillableTargets / shouldKeepTarget / isSupportedFillableHint 过滤掉
        // 搜索框、昵称等非登录误报。仅自动模式触发，手动模式本就开启弱目标。
        var usedWeakReparse = false
        if (!isManualRequest) {
            val firstPassTargets = selectFillableTargets(parsed.items, isManualRequest)
            AutofillLogger.d(
                "AF",
                "DIAG firstPassTargets",
                metadata = mapOf(
                    "requestId" to requestId,
                    "firstPassTargets" to firstPassTargets.size,
                    "hints" to firstPassTargets.joinToString { it.hint.name },
                    "parsedItems" to parsed.items.size,
                    "parsedHints" to parsed.items.joinToString { "${it.hint}:${it.accuracy.name}" },
                )
            )
            if (firstPassTargets.isEmpty()) {
                val weakParsed = parser.parse(
                    structure = structure,
                    respectAutofillOff = respectAutofillOff,
                    allowWeakTargets = true,
                )
                AutofillLogger.d(
                    "AF",
                    "Weak-target compatibility reparse triggered",
                    metadata = mapOf(
                        "requestId" to requestId,
                        "firstPassItemCount" to parsed.items.size,
                        "weakItemCount" to weakParsed.items.size,
                    )
                )
                parsed = weakParsed
                usedWeakReparse = true
            }
        }
        // 兼容回退（Bitwarden 式）：仅当屏幕已进入登录上下文（已识别到账号类字段
        // 或密码字段）时，才把当前聚焦的字段纳入可填充目标，用于补全被漏识别的
        // 登录字段（如影视类 App 的账号框/使用 VISIBLE_PASSWORD 变体的密码框）。
        // 非登录屏幕（搜索框/备注/昵称等，且无密码框）不会触发此逻辑，避免误弹密码建议。
        val hasAccountTarget = parsed.items.any {
            it.hint == FieldHint.USERNAME ||
                it.hint == FieldHint.EMAIL_ADDRESS ||
                it.hint == FieldHint.PHONE_NUMBER
        }
        val hasPasswordTarget = parsed.items.any {
            it.hint == FieldHint.PASSWORD || it.hint == FieldHint.NEW_PASSWORD
        }
        val focusedSyntheticItems = if (hasAccountTarget || hasPasswordTarget) {
            buildFocusedSyntheticItems(structure, parsed.items, hasPasswordTarget)
        } else {
            emptyList()
        }
        if (focusedSyntheticItems.isNotEmpty()) {
            parsed = parsed.copy(items = parsed.items + focusedSyntheticItems)
        }
        // 跨请求登录字段记忆与回补：电影猎手等 App 的密码框在部分 FillRequest 中因
        // 布局/动画时序而不可见、被解析器连同账号一起丢弃，导致登录目标「时有时无」。
        // 识别到登录字段时缓存（账号+密码）；后续同包请求若缺失密码、且缓存的所有登录
        // 字段 id 仍存在于当前结构（可见或不可见均可）时，整体回补，保证面板稳定出现。
        val pkgKey = parsed.applicationId ?: fallbackPackage
        val currentPasswordItems = parsed.items.filter {
            it.hint == FieldHint.PASSWORD || it.hint == FieldHint.NEW_PASSWORD
        }
        val currentHasLoginContext = parsed.items.any { isLoginHint(it.hint) }
        synchronized(passwordMemoryByPackage) {
            if (currentPasswordItems.isNotEmpty()) {
                passwordMemoryByPackage[pkgKey] = parsed.items.filter { isLoginHint(it.hint) }
            } else if (currentHasLoginContext || passwordMemoryByPackage.containsKey(pkgKey)) {
                val cached = passwordMemoryByPackage[pkgKey]
                if (cached != null && cached.isNotEmpty() &&
                    cached.all { structureContainsAutofillId(structure, it.id) }
                ) {
                    val baseIndex = parsed.items.maxOfOrNull { it.traversalIndex } ?: 0
                    val recovered = cached
                        .filter { c -> parsed.items.none { it.id == c.id } }
                        .mapIndexed { index, c ->
                            c.copy(
                                isVisible = findNodeVisibility(structure, c.id) ?: c.isVisible,
                                isFocused = false,
                                accuracy = EnhancedAutofillStructureParserV2.Accuracy.MEDIUM,
                                traversalIndex = baseIndex + index + 1,
                            )
                        }
                    if (recovered.isNotEmpty()) {
                        parsed = parsed.copy(items = parsed.items + recovered)
                        AutofillLogger.d(
                            "AF",
                            "Login targets recovered from memory",
                            metadata = mapOf(
                                "requestId" to requestId,
                                "packageName" to pkgKey,
                                "recoveredCount" to recovered.size,
                                "recoveredHints" to recovered.joinToString { it.hint.name },
                            )
                        )
                    }
                }
            }
        }
        val packageName = resolveEffectivePackageName(
            parsedApplicationId = parsed.applicationId,
            fallbackPackage = fallbackPackage,
        )
        if (isSelfPackage(packageName)) {
            AutofillLogger.i("AF", "Skip fill request for Bastion itself: $packageName")
            return null
        }
        // FLAG_COMPATIBILITY_MODE_REQUEST 为 API 29 常量，编译期内联为 int，低版本设备不会崩溃。
        @SuppressLint("InlinedApi")
        val isCompatMode = (request.flags or FillRequest.FLAG_COMPATIBILITY_MODE_REQUEST) == request.flags
        AutofillLogger.i(
            "AF",
            "Autofill request source diagnostics",
            metadata = mapOf(
                "sdk" to Build.VERSION.SDK_INT,
                "requestId" to requestId,
                "device" to "${Build.MANUFACTURER}/${Build.MODEL}",
                "windowNodeCount" to structure.windowNodeCount,
                "fallbackPackage" to if (fallbackPackage.isBlank()) "none" else fallbackPackage,
                "parsedApplicationId" to (parsed.applicationId ?: "none"),
                "resolvedPackageName" to if (packageName.isBlank()) "none" else packageName,
                "parsedWebDomain" to (parsed.webDomain ?: "none"),
                "parsedWebScheme" to (parsed.webScheme ?: "none"),
                "parsedItemCount" to parsed.items.size,
                "respectAutofillOff" to respectAutofillOff,
                "compatMode" to isCompatMode,
                "manualRequest" to isManualRequest,
            )
        )
        if (packageName.isBlank()) {
            AutofillLogger.i(
                "AF",
                "Skip request: empty resolved package name",
                metadata = mapOf(
                    "requestId" to requestId,
                    "fallbackPackage" to if (fallbackPackage.isBlank()) "none" else fallbackPackage,
                    "parsedApplicationId" to (parsed.applicationId ?: "none"),
                    "parsedItemCount" to parsed.items.size,
                )
            )
            return null
        }

        // 兼容回退路径（usedWeakReparse）：首轮保守解析零字段、二次弱解析才找回的字段，
        // 在选择期也放宽账号字段的精度/密码门槛（作用同 manualRequest 的 shouldKeepTarget 宽松分支），
        // 但非登录类字段仍被 isSupportedFillableHint 挡掉，不会误弹搜索框/昵称。
        val fillableTargets = selectFillableTargets(
            items = parsed.items,
            manualRequest = isManualRequest || usedWeakReparse,
        )
        AutofillLogger.d(
            "AF",
            "DIAG fillableTargets",
            metadata = mapOf(
                "requestId" to requestId,
                "fillableTargets" to fillableTargets.size,
                "hints" to fillableTargets.joinToString { it.hint.name },
                "usedWeakReparse" to usedWeakReparse,
                "hasLoginTargets" to fillableTargets.any { isLoginHint(it.hint) },
                "hasPasswordTarget" to (fillableTargets.any { it.hint == FieldHint.PASSWORD || it.hint == FieldHint.NEW_PASSWORD }),
            )
        )
        if (fillableTargets.isEmpty()) {
            AutofillLogger.i(
                "AF",
                "No supported autofill fields detected",
                metadata = mapOf(
                    "requestId" to requestId,
                    "packageName" to packageName,
                    "parsedItemCount" to parsed.items.size,
                    "parsedHintPreview" to parsed.items.take(8).joinToString(",") { it.hint.name },
                )
            )
            return null
        }
        val parsedWebDomain = parsed.webDomain?.takeIf { it.isNotBlank() }
        // 地址栏网址（对齐 bitwarden `AutofillParserImpl.URL_BARS`）：WebView 未上报
        // webDomain 时，直接读浏览器地址栏拿到**权威**网址，优先级高于无障碍服务跟踪的
        // 60s 启发式兜底（后者在原生 App 里可能残留无关域名）。
        val urlBarDomain = if (parsedWebDomain == null) {
            parsed.urlBarWebsite?.let { extractHostFromUrlBarWebsite(it) }.also { host ->
                AutofillLogger.i(
                    "AF",
                    "url bar website resolved",
                    metadata = mapOf(
                        "requestId" to requestId,
                        "packageName" to packageName,
                        "rawUrlBar" to (parsed.urlBarWebsite ?: "none"),
                        "resolvedHost" to (host ?: "none"),
                    ),
                )
            }
        } else {
            null
        }
        val browserFallbackDomain = if (parsedWebDomain == null && urlBarDomain == null) {
            val fallback = BrowserAutofillContextStore.getRecentDomain(packageName)
            AutofillLogger.i(
                "AF",
                "webDomain is null from AssistStructure, accessibility fallback=${fallback ?: "none"}",
                metadata = mapOf(
                    "requestId" to requestId,
                    "packageName" to packageName,
                    "parsedWebScheme" to (parsed.webScheme ?: "none"),
                    "fallbackDomain" to (fallback ?: "none"),
                ),
            )
            fallback
        } else {
            null
        }
        val webDomain = parsedWebDomain ?: urlBarDomain ?: browserFallbackDomain
        val allowPackageMatch = AutofillRequestContextPolicy.allowPackageMatching(
            packageName = packageName,
            webDomain = webDomain,
            isWebView = parsed.webView,
        )
        val loginTargetCount = fillableTargets.count { isLoginHint(it.hint) }
        val structuredTargetCount = fillableTargets.size - loginTargetCount
        val structuredDecision = evaluateStructuredConfidence(fillableTargets)
        if (!isManualRequest && loginTargetCount == 0 && !structuredDecision.highConfidence) {
            AutofillLogger.i(
                "AF",
                "Skip weak structured autofill request",
                metadata = mapOf(
                    "requestId" to requestId,
                    "packageName" to packageName,
                    "webDomain" to (webDomain ?: "none"),
                    "targetCount" to fillableTargets.size,
                    "structuredTargetCount" to structuredTargetCount,
                    "structuredReason" to structuredDecision.reason,
                    "structuredCount" to structuredDecision.structuredCount,
                    "bankCardCount" to structuredDecision.bankCardCount,
                    "documentCount" to structuredDecision.documentCount,
                    "dominantCount" to structuredDecision.dominantCount,
                    "keyHintCount" to structuredDecision.keyHintCount,
                    "confidentCount" to structuredDecision.confidentCount,
                ),
            )
            return null
        }
        val fieldSignatureKey = buildFieldSignatureKey(
            packageName = packageName,
            webDomain = webDomain,
            credentialTargets = fillableTargets,
        )
        AutofillLogger.i(
            "AF",
            "Autofill target diagnostics",
            metadata = mapOf(
                "requestId" to requestId,
                "packageName" to packageName,
                "webDomain" to (webDomain ?: "none"),
                "webView" to parsed.webView,
                "packageMatchAllowed" to allowPackageMatch,
                "domainSource" to when {
                    parsedWebDomain != null -> "assist_structure"
                    urlBarDomain != null -> "browser_url_bar"
                    browserFallbackDomain != null -> "accessibility_browser_context"
                    else -> "none"
                },
                "targetCount" to fillableTargets.size,
                "loginTargetCount" to loginTargetCount,
                "structuredTargetCount" to structuredTargetCount,
                "structuredHighConfidence" to structuredDecision.highConfidence,
                "structuredReason" to structuredDecision.reason,
                "structuredCount" to structuredDecision.structuredCount,
                "bankCardCount" to structuredDecision.bankCardCount,
                "documentCount" to structuredDecision.documentCount,
                "dominantCount" to structuredDecision.dominantCount,
                "keyHintCount" to structuredDecision.keyHintCount,
                "confidentCount" to structuredDecision.confidentCount,
                "fieldSignaturePresent" to !fieldSignatureKey.isNullOrBlank(),
                "fieldSignatureKey" to (fieldSignatureKey ?: "none"),
                "focusedTargetCount" to fillableTargets.count { it.isFocused },
                "visibleTargetCount" to fillableTargets.count { it.isVisible },
                "targetRolePreview" to fillableTargets.take(12).mapIndexed { index, target ->
                    "$index:${target.hint.name}:${target.accuracy.name}:" +
                        "${if (target.isFocused) "focused" else "idle"}:" +
                        if (target.isVisible) "visible" else "hidden"
                }.joinToString(separator = ","),
            )
        )
        if (!fieldSignatureKey.isNullOrBlank() &&
            autofillPreferences.isFieldSignatureBlocked(fieldSignatureKey)
        ) {
            AutofillLogger.i(
                "AF",
                "Skip autofill request: blocked field signature",
                metadata = mapOf(
                    "requestId" to requestId,
                    "packageName" to packageName,
                    "webDomain" to (webDomain ?: "none"),
                    "targetCount" to fillableTargets.size,
                ),
            )
            return null
        }
        val effectiveScheme = parsed.webScheme?.takeIf { it.isNotBlank() } ?: "https"
        val requestUri = webDomain?.let { "$effectiveScheme://$it" } ?: "androidapp://$packageName"
        val appDisplayName = resolveAppDisplayName(packageName)
        val interactionContext = AutofillInteractionContextResolver.build(
            packageName = packageName,
            webDomain = webDomain,
        )
        val primaryInteractionIdentifier = interactionContext.primaryIdentifier
        if (!primaryInteractionIdentifier.isNullOrBlank()) {
            autofillPreferences.touchAutofillInteraction(primaryInteractionIdentifier)
        }

        val hasLoginTargets = fillableTargets.any { isLoginHint(it.hint) }
        val isPasswordOnlyLogin = AutofillInteractionContextResolver.isPasswordOnlyLogin(fillableTargets)
        var candidatePasswordCount = 0
        val matchedPasswords = if (hasLoginTargets) {
            val allPasswords = passwordRepository.getAllPasswordEntries().first()
            val sourceFilter = AutofillConfigCache.v2DefaultSourceFilter
            val defaultKeepassDatabaseId = AutofillConfigCache.v2DefaultKeepassDatabaseId
            val defaultBitwardenVaultId = AutofillConfigCache.v2DefaultBitwardenVaultId
            val scopedPasswords = applyDefaultSourceFilter(
                entries = allPasswords,
                sourceFilter = sourceFilter,
                keepassDatabaseId = defaultKeepassDatabaseId,
                bitwardenVaultId = defaultBitwardenVaultId,
            )
            candidatePasswordCount = scopedPasswords.size
            val strictOnly = AutofillConfigCache.isBitwardenStrictModeEnabled
            val allowSubdomainToggle = AutofillConfigCache.isBitwardenSubdomainMatchEnabled
            val uriStrategy = AutofillConfigCache.domainMatchStrategy
            val uriConfig = resolveUriStrategyConfig(uriStrategy, allowSubdomainToggle)
            if (uriConfig.disableMatch) {
                emptyList()
            } else {
                val rankedMatches = matcher.match(
                    entries = scopedPasswords,
                    packageName = packageName,
                    webDomain = webDomain,
                    appDisplayName = appDisplayName,
                    config = BitwardenLikeAutofillMatcherNg.Config(
                        strictOnly = strictOnly,
                        allowSubdomainMatch = uriConfig.allowSubdomainMatch,
                        allowBaseDomainMatch = uriConfig.allowBaseDomainMatch,
                        exactDomainOnly = uriConfig.exactDomainOnly,
                        allowPackageMatch = allowPackageMatch,
                        maxSuggestions = Int.MAX_VALUE,
                    ),
                )
                val passwordOnlyLastFilledEntry = if (isPasswordOnlyLogin) {
                    resolveLastFilledEntry(
                        entries = scopedPasswords,
                        interactionContext = interactionContext,
                    )
                } else {
                    null
                }
                val directFillEntry = if (isPasswordOnlyLogin) {
                    resolvePasswordOnlyDirectFillEntry(
                        rankedMatches = rankedMatches,
                        lastFilled = passwordOnlyLastFilledEntry,
                        interactionContext = interactionContext,
                    )
                } else {
                    null
                }
                val prioritized = if (directFillEntry != null) {
                    listOf(directFillEntry)
                } else {
                    AutofillInteractionContextResolver.prioritizeLastFilled(
                        entries = rankedMatches,
                        lastFilled = passwordOnlyLastFilledEntry,
                    )
                }
                // 补丁：系统 Wi-Fi 设置页的密码输入框没有 webDomain，也匹配
                // 不到 appPackageName；这里补上所有 WIFI 条目作为候选。
                WifiAutofillAssist.augmentWithWifiEntries(
                    originalRanked = prioritized,
                    allEntries = scopedPasswords,
                    packageName = packageName,
                    maxSuggestions = Int.MAX_VALUE,
                )
            }
        } else {
            emptyList()
        }

        // P2 第二道防线（对齐 bitwarden fillLoginPartition）：条目级 website 双向一致性校验。
        // matcher 按域名/包名/标题打分，非严格模式的启发式匹配与 native 包名 token 匹配可能把
        // 「明确绑定其它站点/其它 App」的条目带进来；此处按「仅拒绝明确矛盾」原则过滤：
        // web 页面比对注册域，原生页面比对包名（包名不一致但有标题级强证据时视为「包名过期」
        // 放行，兼容混淆包名 App 重打包换包名）；无 website 且无包名的条目（KeePass 裸条目、
        // WiFi 条目）无信息可判，一律放行。
        //
        // 页面域名只用 parsedWebDomain（AssistStructure 的权威信号），不用 webDomain
        //（= parsedWebDomain ?: 无障碍回退域名）。回退域名是启发式（无障碍服务对 WebView/
        // 浏览器最近 60s 的跟踪），在原生 App（如电影猎手）里可能残留无关域名；若用它做
        // 「拒绝」判定，会把本应放行的 native 条目误杀。真实浏览器页面都会上报 webDomain，
        // web 轴保护不受影响；结构未上报域名的 WebView 登录则回退到包名轴，与 P1 行为一致。
        val consistentPasswords = if (parsedWebDomain != null || packageName.isNotBlank()) {
            matchedPasswords.filter { entry ->
                AutofillWebsiteConsistencyPolicy.isConsistent(
                    entryWebsite = entry.website,
                    entryAppPackage = entry.appPackageName,
                    entryTitle = entry.title,
                    entryAppName = entry.appName,
                    pageWebDomain = parsedWebDomain,
                    pagePackageName = packageName,
                    pageAppDisplayName = appDisplayName,
                )
            }.also { filtered ->
                if (filtered.size != matchedPasswords.size) {
                    val removed = matchedPasswords.filter { m ->
                        filtered.none { it.id == m.id }
                    }
                    AutofillLogger.i(
                        "AF",
                        "P2 website-consistency filtered entries",
                        metadata = mapOf(
                            "requestId" to requestId,
                            "packageName" to packageName,
                            "pageWebDomain" to (parsedWebDomain ?: "none"),
                            "webDomainWithFallback" to (webDomain ?: "none"),
                            "webDomainSource" to when {
                                parsedWebDomain != null -> "structure"
                                webDomain != null -> "accessibility_fallback"
                                else -> "none"
                            },
                            "rawMatches" to matchedPasswords.size,
                            "consistentMatches" to filtered.size,
                            "removedTitles" to removed.joinToString { it.title },
                        ),
                    )
                }
            }
        } else {
            matchedPasswords
        }

        val responseStabilityKey = buildResponseStabilityKey(
            packageName = packageName,
            webDomain = webDomain,
            fieldSignatureKey = fieldSignatureKey,
            fillableTargets = fillableTargets,
        )
        val passwordsForResponse = stabilizeMatchedPasswords(
            key = responseStabilityKey,
            matchedPasswords = consistentPasswords,
            requestId = requestId,
            targetCount = fillableTargets.size,
        )

        diagnostics.logPasswordMatching(
            packageName = packageName,
            domain = webDomain,
            matchStrategy = if (hasLoginTargets) {
                if (isPasswordOnlyLogin) "bitwarden_v2_hybrid_password_only"
                else "bitwarden_v2_hybrid"
            } else {
                "structured_manual_picker"
            },
            totalPasswords = candidatePasswordCount,
            matchedPasswords = passwordsForResponse.size,
        )

        // WebView 场景（有 webDomain）的 menu presentation 回写在部分浏览器（Via）上不可靠：
        // 数据集已正确返回但框架不回写值。Bitwarden 对 WebView 走 inline（IME 内嵌）建议，
        // 点选后输入法直接 commitText 进输入框，不依赖 autofillId 映射回写，对 Via 100% 可靠。
        // 因此对 WebView 场景，即使用户关着 inline 开关，也获取 inlineRequest 供 builder 使用。
        val isWebViewFill = webDomain != null
        val inlineRequest = if (AutofillConfigCache.isInlineSuggestionsEnabled || isWebViewFill) {
            getInlineRequest(request)
        } else {
            null
        }
        val autofillAuthRequired = AutofillConfigCache.autofillAuthRequired
        val grantContext = AutofillGrantContext(
            packageName = packageName,
            webDomain = webDomain,
            interactionIdentifier = primaryInteractionIdentifier,
            fieldSignatureKey = fieldSignatureKey,
        )
        val grantActive = autofillAuthRequired && AutofillSessionGrants.isGranted(grantContext)
        if (!autofillAuthRequired) {
            AutofillSessionGrants.clear()
        }
        // 单条匹配降级：response 级认证解锁（buildLockedResponse）在部分 WebView（Via）上
        // 解锁后的 EXTRA_AUTHENTICATION_RESULT 回灌不可靠，导致填充失败。单条匹配时退而求其次：
        // 走 dataset 级 setAuthentication（AutofillCipherCallbackActivity 认证），
        // 它内含 a11y 兜底 + OTP 自动复制，兼容所有 WebView。
        // 多匹配仍走 response 级认证 + Picker（Picker 有独立认证，不影响 Via 多匹配场景）。
        val effectiveAuthenticationRequired = if (passwordsForResponse.size == 1) {
            false
        } else {
            autofillAuthRequired && !grantActive
        }
        AutofillLogger.i(
            "AUTH",
            "Autofill authentication policy resolved",
            metadata = mapOf(
                "settingEnabled" to autofillAuthRequired,
                "grantActive" to grantActive,
                "authenticationRequired" to effectiveAuthenticationRequired,
                "packageName" to packageName,
                "webDomain" to (webDomain ?: "none"),
            )
        )
        // WebView 单条匹配对齐 Bitwarden：挂 setAuthentication 走"点选→回调→回填"路径，
        // 该路径对系统 WebView 密码框虚拟节点回填比纯直填更可靠（Bitwarden 始终挂 auth）。
        // forceDatasetAuth：挂 auth 但 vault 解锁态不触发指纹，filledItems 保留真实值，
        // 回调直接回填不重新映射，避免 Edge 账户名失配。
        // 追加条件：单条匹配且该条目带 TOTP 时同样挂 auth。原因：纯直填（setAuthentication
        // 为空）时框架直接 setValue 回填，不经过任何 Activity 回调，服务层无从感知"用户到底是
        // 否点选了条目"；只有走「点选 → AutofillCipherCallbackActivity → 回填」这条路径，
        // 才能保证 TOTP 复制发生在真正填充之后（对齐 Bitwarden：TOTP 复制是"填充"的副作用，
        // 而不是"展示建议"的副作用，避免在条目刚可见时就污染剪贴板）。
        val singleMatch = passwordsForResponse.size == 1
        val singleMatchHasTotp =
            passwordsForResponse.singleOrNull()?.authenticatorKey?.isNotBlank() == true
        val forceDatasetAuth = singleMatch && (isWebViewFill || singleMatchHasTotp)
        AutofillLogger.i(
            "AUTH",
            "Dataset auth policy for fill",
            metadata = mapOf(
                "isWebViewFill" to isWebViewFill,
                "singleMatch" to singleMatch,
                "singleMatchHasTotp" to singleMatchHasTotp,
                "forceDatasetAuth" to forceDatasetAuth,
                "a11yAvailable" to BastionAccessibilityService.isCredentialFillAvailable(applicationContext),
            )
        )
        val response = bwCompatProcessor.process(
            packageName = packageName,
            uri = requestUri,
            fillableTargets = fillableTargets,
            inlineRequest = inlineRequest,
            isCompatMode = isCompatMode,
            passwords = passwordsForResponse,
            fieldSignatureKey = fieldSignatureKey,
            preferDirectAutoFill = isPasswordOnlyLogin && passwordsForResponse.size == 1,
            passwordSuggestionEnabled = AutofillConfigCache.isPasswordSuggestionEnabled,
            requireAuthentication = effectiveAuthenticationRequired,
            forceDatasetAuthForWeb = forceDatasetAuth,
        )

        if (response == null) {
            AutofillLogger.w(
                "AF",
                "No fill response produced",
                metadata = mapOf(
                    "requestId" to requestId,
                    "packageName" to packageName,
                    "webDomain" to (webDomain ?: "none"),
                    "targets" to fillableTargets.size,
                    "matches" to passwordsForResponse.size,
                    "rawMatches" to matchedPasswords.size,
                    "candidatePasswords" to candidatePasswordCount,
                    "responseStabilityKey" to responseStabilityKey,
                )
            )
        } else {
            AutofillLogger.i(
                "AF",
                "Fill response ready",
                metadata = mapOf(
                    "requestId" to requestId,
                    "packageName" to packageName,
                    "webDomain" to (webDomain ?: "none"),
                    "targets" to fillableTargets.size,
                    "matches" to passwordsForResponse.size,
                    "rawMatches" to matchedPasswords.size,
                    "candidatePasswords" to candidatePasswordCount,
                    "authRequired" to autofillAuthRequired,
                    "inlineRequest" to (inlineRequest != null),
                    "sdk" to Build.VERSION.SDK_INT,
                    "responseStabilityKey" to responseStabilityKey,
                )
            )

            // TOTP 复制**不**在这里触发：此处只是在"构建 FillResponse"，条目刚对用户可见、
            // 用户尚未点选。带 TOTP 的单条匹配已通过 forceDatasetAuth 改为走
            // 「点选 → AutofillCipherCallbackActivity → 回填」路径，由回调内的
            // performOtpAutofillSideEffects 在真正填充完成后再复制到剪贴板，
            // 从而避免"条目一可见就往剪贴板堆验证码"。
            if (passwordsForResponse.size == 1) {

                // WebView 场景（如 Via + PayPal）的 menu 建议回写不可靠，inline 建议
                // 又受 ROM 限制（HarmonyOS / MIUI < 12 等）不可用。此时走无障碍直接注入兜底：
                // 把凭据写入跨进程命令存储并广播唤醒无障碍服务，由其对当前窗口的密码框
                // 直接注入文字——这绕开了框架 autofillId 回写，对任何 WebView 都可靠。
                if (isWebViewFill && BastionAccessibilityService.isCredentialFillAvailable(applicationContext)) {
                    val entry = passwordsForResponse.first()
                    AccessibilityFillCommandStore.attach(applicationContext)
                    val accountValue = com.bastion.app.autofill_ng.AccountFillPolicy
                        .resolveAccountIdentifier(entry, com.bastion.app.security.SecurityManager(applicationContext))
                    val decryptedPassword = com.bastion.app.autofill_ng.AutofillSecretResolver.decryptPasswordOrNull(
                        securityManager = com.bastion.app.security.SecurityManager(applicationContext),
                        encryptedOrPlain = entry.password,
                        logTag = TAG,
                    )
                    scope.launch(Dispatchers.IO) {
                        val otp = runCatching {
                            generateOtpCodeForPassword(applicationContext, entry)
                        }.getOrNull()
                        AccessibilityFillCommandStore.write(
                            AccessibilityFillCommandStore.Command(
                                packageName = packageName,
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
                        Log.d(TAG, "WebView accessibility fallback dispatched: " +
                            "pkg=$packageName, webDomain=$webDomain, " +
                            "passwordId=${entry.id}, otp=${if (otp.isNullOrBlank()) "none" else "present"}")
                    }
                }
            }
        }
        return response
    }

    private fun buildResponseStabilityKey(
        packageName: String,
        webDomain: String?,
        fieldSignatureKey: String?,
        fillableTargets: List<ParsedItem>,
    ): String {
        val fieldKey = fieldSignatureKey?.takeIf { it.isNotBlank() }
        if (fieldKey != null) {
            return listOf(
                packageName.trim().lowercase(),
                webDomain.orEmpty().trim().lowercase(),
                fieldKey,
            ).joinToString("|")
        }
        val targetShape = fillableTargets
            .sortedBy { it.traversalIndex }
            .joinToString(",") { "${it.hint.name}:${it.isFocused}:${it.isVisible}" }
        return listOf(
            packageName.trim().lowercase(),
            webDomain.orEmpty().trim().lowercase(),
            targetShape,
        ).joinToString("|")
    }

    private fun stabilizeMatchedPasswords(
        key: String,
        matchedPasswords: List<PasswordEntry>,
        requestId: Long,
        targetCount: Int,
    ): List<PasswordEntry> {
        val now = System.currentTimeMillis()
        // synchronized 防并发 onFillRequest 先读后写竞态（两个请求可能互相覆盖缓存）。
        synchronized(recentFillSuggestionsLock) {
            val cached = recentFillSuggestions
            val cachedIsUsable = cached != null &&
                cached.key == key &&
                now - cached.createdAtMs <= RESPONSE_STABILITY_WINDOW_MS &&
                cached.targetCount >= targetCount &&
                cached.passwords.size > matchedPasswords.size

            if (cachedIsUsable) {
                AutofillLogger.w(
                    "AF",
                    "Reusing recent stronger fill suggestions",
                    metadata = mapOf(
                        "requestId" to requestId,
                        "previousRequestId" to cached!!.requestId,
                        "ageMs" to (now - cached.createdAtMs),
                        "previousMatches" to cached.passwords.size,
                        "currentMatches" to matchedPasswords.size,
                        "targetCount" to targetCount,
                    ),
                )
                return cached.passwords
            }

            if (matchedPasswords.isNotEmpty()) {
                recentFillSuggestions = RecentFillSuggestions(
                    key = key,
                    createdAtMs = now,
                    requestId = requestId,
                    targetCount = targetCount,
                    passwords = matchedPasswords,
                )
            }
            return matchedPasswords
        }
    }

    private suspend fun resolveLastFilledEntry(
        entries: List<PasswordEntry>,
        interactionContext: AutofillInteractionContext,
    ): PasswordEntry? {
        var lastFilledPasswordId: Long? = null
        for (identifier in interactionContext.allIdentifiers) {
            lastFilledPasswordId = autofillPreferences.getLastFilledCredential(identifier)?.passwordId
            if (lastFilledPasswordId != null) break
        }
        val resolvedPasswordId = lastFilledPasswordId ?: return null
        return entries.firstOrNull { it.id == resolvedPasswordId }
    }

    private suspend fun resolvePasswordOnlyDirectFillEntry(
        rankedMatches: List<PasswordEntry>,
        lastFilled: PasswordEntry?,
        interactionContext: AutofillInteractionContext,
    ): PasswordEntry? {
        val candidate = lastFilled ?: return null
        if (rankedMatches.none { it.id == candidate.id }) return null

        val now = System.currentTimeMillis()
        val recentInteraction = interactionContext.allIdentifiers.firstNotNullOfOrNull { identifier ->
            autofillPreferences.getAutofillInteractionState(identifier)
        } ?: return null
        if (!recentInteraction.completed) return null
        if (recentInteraction.lastFilledPasswordId != candidate.id) return null
        if (recentInteraction.lastFilledAt <= 0L) return null
        if (now - recentInteraction.lastFilledAt > PASSWORD_ONLY_DIRECT_FILL_WINDOW_MS) return null

        AutofillLogger.i(
            "AF",
            "Using password-only direct autofill continuation",
            metadata = mapOf(
                "passwordId" to candidate.id,
                "interactionId" to recentInteraction.identifier,
                "elapsedMs" to (now - recentInteraction.lastFilledAt),
            )
        )
        return candidate
    }

    private data class UriStrategyConfig(
        val allowSubdomainMatch: Boolean,
        val allowBaseDomainMatch: Boolean,
        val exactDomainOnly: Boolean,
        val disableMatch: Boolean = false,
    )

    private fun resolveUriStrategyConfig(
        strategy: DomainMatchStrategy,
        allowSubdomainToggle: Boolean,
    ): UriStrategyConfig {
        return when (strategy) {
            DomainMatchStrategy.BASE_DOMAIN -> UriStrategyConfig(
                allowSubdomainMatch = allowSubdomainToggle,
                allowBaseDomainMatch = true,
                exactDomainOnly = false,
            )

            DomainMatchStrategy.DOMAIN -> UriStrategyConfig(
                allowSubdomainMatch = allowSubdomainToggle,
                allowBaseDomainMatch = false,
                exactDomainOnly = false,
            )

            DomainMatchStrategy.EXACT_MATCH -> UriStrategyConfig(
                allowSubdomainMatch = false,
                allowBaseDomainMatch = false,
                exactDomainOnly = true,
            )

            DomainMatchStrategy.NEVER -> UriStrategyConfig(
                allowSubdomainMatch = false,
                allowBaseDomainMatch = false,
                exactDomainOnly = false,
                disableMatch = true,
            )

            DomainMatchStrategy.STARTS_WITH,
            DomainMatchStrategy.REGEX -> {
                AutofillLogger.w(
                    "AF",
                    "URI strategy $strategy is not natively supported by NG matcher; fallback to BASE_DOMAIN",
                )
                UriStrategyConfig(
                    allowSubdomainMatch = allowSubdomainToggle,
                    allowBaseDomainMatch = true,
                    exactDomainOnly = false,
                )
            }
        }
    }

    private fun applyDefaultSourceFilter(
        entries: List<PasswordEntry>,
        sourceFilter: AutofillPreferences.AutofillDefaultSourceFilter,
        keepassDatabaseId: Long?,
        bitwardenVaultId: Long?,
    ): List<PasswordEntry> {
        return when (sourceFilter) {
            AutofillPreferences.AutofillDefaultSourceFilter.ALL -> entries
            AutofillPreferences.AutofillDefaultSourceFilter.LOCAL -> entries.filter { entry ->
                entry.isLocalOnlyEntry()
            }
            AutofillPreferences.AutofillDefaultSourceFilter.KEEPASS -> entries.filter { entry ->
                entry.keepassDatabaseId != null &&
                    (keepassDatabaseId == null || entry.keepassDatabaseId == keepassDatabaseId)
            }
            AutofillPreferences.AutofillDefaultSourceFilter.BITWARDEN -> entries.filter { entry ->
                entry.bitwardenVaultId != null &&
                    (bitwardenVaultId == null || entry.bitwardenVaultId == bitwardenVaultId)
            }
        }
    }

    private fun getInlineRequest(request: FillRequest): InlineSuggestionsRequest? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        if (!DeviceUtils.supportsInlineSuggestions()) return null
        return request.inlineSuggestionsRequest
    }

    /**
     * 从浏览器地址栏文本提取主机名。
     *
     * 地址栏内容形态不定：可能是完整 URL（`https://github.com/foo?a=1`）、纯域名
     * （`github.com`），也可能是用户正在输入的搜索词。
     * 只在能解析出「含点的合法主机」时返回，否则返回 null 交给后续兜底，不强行猜测。
     */
    private fun extractHostFromUrlBarWebsite(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        val candidate = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        return runCatching {
            java.net.URI(candidate)
                .host
                ?.lowercase(java.util.Locale.ROOT)
                ?.takeIf { it.isNotBlank() && it.contains('.') }
        }.getOrNull()
    }

    private fun selectFillableTargets(
        items: List<ParsedItem>,
        manualRequest: Boolean,
    ): List<ParsedItem> {
        if (items.isEmpty()) return emptyList()

        val rawCount = items.size
        val hasPasswordTarget = items.any {
            it.hint == FieldHint.PASSWORD || it.hint == FieldHint.NEW_PASSWORD
        }
        val filtered = items.filter { item ->
            isSupportedFillableHint(item.hint) &&
                AutofillDetectionPolicy.shouldKeepTarget(
                    hint = item.hint,
                    accuracy = item.accuracy,
                    hasPasswordTarget = hasPasswordTarget,
                    manualRequest = manualRequest,
                )
        }
        if (filtered.isEmpty()) return emptyList()

        // Keep targets close to Bitwarden behavior:
        // when a field is already populated, avoid anchoring manual-entry datasets on that
        // non-focused field as it can cause the framework to suppress the suggestion row.
        val hasFocusedTarget = filtered.any { it.isFocused }
        val preferredTargets = filtered.filter { item ->
            val hasValue = !item.value.isNullOrBlank()
            when {
                item.isFocused -> true
                !hasValue -> true
                hasFocusedTarget && isLoginHint(item.hint) -> true
                hasFocusedTarget -> false
                else -> true
            }
        }.ifEmpty { filtered }

        val deduped = linkedMapOf<String, ParsedItem>()
        preferredTargets.sortedWith(
            compareByDescending<ParsedItem> { it.isFocused }
                .thenByDescending { hintPriority(it.hint) }
                .thenByDescending { it.accuracy.score }
                .thenBy { it.traversalIndex },
        ).forEach { item ->
            deduped.putIfAbsent(item.id.toString(), item)
        }

        val droppedByHint = rawCount - filtered.size
        val droppedByValueSuppression = filtered.size - preferredTargets.size
        if (droppedByHint > 0 || droppedByValueSuppression > 0) {
            AutofillLogger.d(
                "AF",
                "Target selection pruned candidates",
                metadata = mapOf(
                    "rawCount" to rawCount,
                    "supportedCount" to filtered.size,
                    "preferredCount" to preferredTargets.size,
                    "finalCount" to deduped.size,
                    "droppedByHint" to droppedByHint,
                    "droppedByValueSuppression" to droppedByValueSuppression,
                    "hasFocusedTarget" to hasFocusedTarget,
                    "hasPasswordTarget" to hasPasswordTarget,
                    "manualRequest" to manualRequest,
                ),
            )
        }

        return deduped.values.toList()
    }

    private fun hintPriority(hint: FieldHint): Int = when (hint) {
        FieldHint.PASSWORD, FieldHint.NEW_PASSWORD -> 3
        FieldHint.USERNAME, FieldHint.EMAIL_ADDRESS, FieldHint.PHONE_NUMBER -> 2
        FieldHint.CREDIT_CARD_NUMBER,
        FieldHint.CREDIT_CARD_EXPIRATION_DATE,
        FieldHint.CREDIT_CARD_EXPIRATION_MONTH,
        FieldHint.CREDIT_CARD_EXPIRATION_YEAR,
        FieldHint.CREDIT_CARD_SECURITY_CODE,
        FieldHint.CREDIT_CARD_HOLDER_NAME,
        FieldHint.IDENTITY_NUMBER,
        -> 2
        FieldHint.PERSON_NAME,
        FieldHint.PERSON_FIRST_NAME,
        FieldHint.PERSON_LAST_NAME,
        FieldHint.POSTAL_ADDRESS,
        FieldHint.POSTAL_CODE,
        FieldHint.ADDRESS_CITY,
        FieldHint.ADDRESS_REGION,
        FieldHint.ADDRESS_COUNTRY,
        FieldHint.COMPANY_NAME,
        -> 1
        else -> 0
    }

    private fun evaluateStructuredConfidence(targets: List<ParsedItem>): StructuredConfidenceDecision {
        if (targets.isEmpty()) {
            return StructuredConfidenceDecision(
                highConfidence = false,
                reason = "empty_targets",
                structuredCount = 0,
                bankCardCount = 0,
                documentCount = 0,
                dominantCount = 0,
                keyHintCount = 0,
                confidentCount = 0,
            )
        }
        val structured = targets.filterNot { isLoginHint(it.hint) }
        if (structured.size < 2) {
            return StructuredConfidenceDecision(
                highConfidence = false,
                reason = "insufficient_structured_targets",
                structuredCount = structured.size,
                bankCardCount = 0,
                documentCount = 0,
                dominantCount = 0,
                keyHintCount = 0,
                confidentCount = 0,
            )
        }
        val bankCardTargets = structured.filter { isBankCardAutofillHint(it.hint.name) }
        val documentTargets = structured.filter { isDocumentAutofillHint(it.hint.name) }
        val dominantTargets = if (bankCardTargets.size >= documentTargets.size) bankCardTargets else documentTargets
        val secondaryCount = if (bankCardTargets.size >= documentTargets.size) documentTargets.size else bankCardTargets.size

        if (dominantTargets.size < 2) {
            return StructuredConfidenceDecision(
                highConfidence = false,
                reason = "insufficient_dominant_category",
                structuredCount = structured.size,
                bankCardCount = bankCardTargets.size,
                documentCount = documentTargets.size,
                dominantCount = dominantTargets.size,
                keyHintCount = 0,
                confidentCount = 0,
            )
        }
        val hasBalancedStructuredCategories = abs(bankCardTargets.size - documentTargets.size) < 1 &&
            secondaryCount > 0
        if (hasBalancedStructuredCategories) {
            val mixedKeyHintCount =
                bankCardTargets.count { isBankCardKeyAutofillHint(it.hint.name) } +
                    documentTargets.count { isDocumentKeyAutofillHint(it.hint.name) }
            val mixedConfidentCount = structured.count {
                it.accuracy.score >= PARSED_ITEM_ACCURACY_THRESHOLD
            }
            val highConfidence = mixedKeyHintCount >= 1 && mixedConfidentCount >= 2
            return StructuredConfidenceDecision(
                highConfidence = highConfidence,
                reason = if (highConfidence) {
                    "mixed_structured_categories"
                } else {
                    "weak_mixed_structured_categories"
                },
                structuredCount = structured.size,
                bankCardCount = bankCardTargets.size,
                documentCount = documentTargets.size,
                dominantCount = dominantTargets.size,
                keyHintCount = mixedKeyHintCount,
                confidentCount = mixedConfidentCount,
            )
        }

        val keyHintCount = dominantTargets.count {
            if (bankCardTargets.size >= documentTargets.size) {
                isBankCardKeyAutofillHint(it.hint.name)
            } else {
                isDocumentKeyAutofillHint(it.hint.name)
            }
        }
        if (keyHintCount < 1) {
            return StructuredConfidenceDecision(
                highConfidence = false,
                reason = "missing_key_structured_hint",
                structuredCount = structured.size,
                bankCardCount = bankCardTargets.size,
                documentCount = documentTargets.size,
                dominantCount = dominantTargets.size,
                keyHintCount = keyHintCount,
                confidentCount = 0,
            )
        }

        val confidentCount = dominantTargets.count { it.accuracy.score >= PARSED_ITEM_ACCURACY_THRESHOLD }
        val highConfidence = confidentCount >= 2
        return StructuredConfidenceDecision(
            highConfidence = highConfidence,
            reason = if (highConfidence) "high_confidence" else "insufficient_confident_targets",
            structuredCount = structured.size,
            bankCardCount = bankCardTargets.size,
            documentCount = documentTargets.size,
            dominantCount = dominantTargets.size,
            keyHintCount = keyHintCount,
            confidentCount = confidentCount,
        )
    }

    private fun isSupportedFillableHint(hint: FieldHint): Boolean {
        return isLoginHint(hint) ||
            hint == FieldHint.CREDIT_CARD_NUMBER ||
            hint == FieldHint.CREDIT_CARD_EXPIRATION_DATE ||
            hint == FieldHint.CREDIT_CARD_EXPIRATION_MONTH ||
            hint == FieldHint.CREDIT_CARD_EXPIRATION_YEAR ||
            hint == FieldHint.CREDIT_CARD_SECURITY_CODE ||
            hint == FieldHint.CREDIT_CARD_HOLDER_NAME ||
            hint == FieldHint.POSTAL_ADDRESS ||
            hint == FieldHint.POSTAL_CODE ||
            hint == FieldHint.PERSON_NAME ||
            hint == FieldHint.PERSON_FIRST_NAME ||
            hint == FieldHint.PERSON_LAST_NAME ||
            hint == FieldHint.ADDRESS_CITY ||
            hint == FieldHint.ADDRESS_REGION ||
            hint == FieldHint.ADDRESS_COUNTRY ||
            hint == FieldHint.COMPANY_NAME ||
            hint == FieldHint.IDENTITY_NUMBER
    }

    private fun isLoginHint(hint: FieldHint): Boolean {
        return hint == FieldHint.USERNAME ||
            hint == FieldHint.EMAIL_ADDRESS ||
            hint == FieldHint.PHONE_NUMBER ||
            hint == FieldHint.PASSWORD ||
            hint == FieldHint.NEW_PASSWORD
    }

    private fun buildFieldSignatureKey(
        packageName: String,
        webDomain: String?,
        credentialTargets: List<ParsedItem>,
    ): String? {
        if (credentialTargets.isEmpty()) return null
        val normalizedPackage = packageName.trim().lowercase()
        if (normalizedPackage.isBlank()) return null
        val normalizedDomain = webDomain?.trim()?.lowercase().orEmpty()
        val targetSummary = credentialTargets
            .sortedWith(compareBy<ParsedItem> { it.traversalIndex }.thenBy { it.hint.name })
            .joinToString(separator = "|") { item ->
                buildString {
                    append(item.hint.name)
                    append('@')
                    append(item.traversalIndex)
                    append('@')
                    append(item.parentWebViewNodeId ?: -1)
                    append('@')
                    append(if (item.isVisible) '1' else '0')
                }
            }
        if (targetSummary.isBlank()) return null
        val rawSignature = buildString {
            append(normalizedPackage)
            append('|')
            append(normalizedDomain)
            append('|')
            append(targetSummary)
        }
        val digest = shaDigest.get().digest(rawSignature.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        scope.launch {
            try {
                if (!autofillPreferences.isRequestSaveDataEnabled.first()) {
                    callback.onSuccess()
                    return@launch
                }

                val saveIntent = withContext(Dispatchers.Default) { buildSaveIntent(request) }
                if (saveIntent == null) {
                    callback.onSuccess()
                    return@launch
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val code = (System.currentTimeMillis().toInt() and 0x7FFFFFFF)
                    val flags = PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    val pendingIntent = PendingIntent.getActivity(
                        this@BastionAutofillServiceNg,
                        code,
                        saveIntent,
                        flags,
                    )
                    callback.onSuccess(pendingIntent.intentSender)
                } else {
                    saveIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(saveIntent)
                    callback.onSuccess()
                }
            } catch (e: Exception) {
                AutofillLogger.e("AF", "onSaveRequest failed", e)
                callback.onFailure(e.message ?: "Save failed")
            }
        }
    }

    private suspend fun buildSaveIntent(request: SaveRequest): Intent? {
        val structure = request.fillContexts.lastOrNull()?.structure ?: return null
        val parsed = parser.parse(
            structure = structure,
            respectAutofillOff = false,
        )

        val usernameId = parsed.items.firstOrNull {
            it.hint == FieldHint.USERNAME ||
                it.hint == FieldHint.EMAIL_ADDRESS ||
                it.hint == FieldHint.PHONE_NUMBER
        }?.id
        val passwordId = parsed.items.firstOrNull {
            it.hint == FieldHint.PASSWORD || it.hint == FieldHint.NEW_PASSWORD
        }?.id

        val username = usernameId?.let { extractTextFromStructure(structure, it) }.orEmpty()
        val password = passwordId?.let { extractTextFromStructure(structure, it) }.orEmpty()
        if (password.isBlank()) {
            AutofillLogger.i("AF", "Skip save request: no password value")
            return null
        }

        val packageName = resolveEffectivePackageName(
            parsedApplicationId = parsed.applicationId,
            fallbackPackage = structure.activityComponent?.packageName.orEmpty(),
        )
        val website = parsed.webDomain.orEmpty()

        if (isSelfPackage(packageName)) {
            AutofillLogger.i("AF", "Skip save request for Bastion itself: $packageName")
            return null
        }

        if (packageName.isNotBlank() && autofillPreferences.isInBlacklist(packageName)) {
            AutofillLogger.i("AF", "Skip save request: package in blacklist ($packageName)")
            return null
        }
        if (autofillPreferences.isSaveBlocked(packageName = packageName, webDomain = website)) {
            AutofillLogger.i("AF", "Skip save request: blocked target (pkg=$packageName, domain=$website)")
            return null
        }

        return Intent(this, AutofillSaveTransparentActivity::class.java).apply {
            putExtra(AutofillSaveTransparentActivity.EXTRA_USERNAME, username)
            putExtra(AutofillSaveTransparentActivity.EXTRA_PASSWORD, password)
            putExtra(AutofillSaveTransparentActivity.EXTRA_WEBSITE, website)
            putExtra(AutofillSaveTransparentActivity.EXTRA_PACKAGE_NAME, packageName)
        }
    }

    private fun extractTextFromStructure(
        structure: AssistStructure,
        targetId: AutofillId,
    ): String? {
        for (index in 0 until structure.windowNodeCount) {
            val root = structure.getWindowNodeAt(index).rootViewNode
            val value = extractTextFromNode(root, targetId)
            if (!value.isNullOrBlank()) {
                return value
            }
        }
        return null
    }

    private fun extractTextFromNode(
        node: AssistStructure.ViewNode,
        targetId: AutofillId,
    ): String? {
        if (node.autofillId == targetId) {
            return node.autofillValue.safeTextOrNull(
                tag = "AF",
                fieldDescription = node.idEntry ?: node.className ?: "field",
            )
        }
        for (childIndex in 0 until node.childCount) {
            val child = node.getChildAt(childIndex) ?: continue
            if (child.visibility != View.VISIBLE) continue
            val value = extractTextFromNode(child, targetId)
            if (!value.isNullOrBlank()) {
                return value
            }
        }
        return null
    }

    /**
     * 收集当前聚焦且未被已解析条目覆盖的可编辑字段，依据其 inputType 推断 hint 后
     * 合成 ParsedItem，使响应能锚定到用户实际聚焦的框（如密码框），对齐 Bitwarden 行为。
     */
    private fun buildFocusedSyntheticItems(
        structure: AssistStructure,
        existingItems: List<EnhancedAutofillStructureParserV2.ParsedItem>,
        hasPasswordTarget: Boolean,
    ): List<EnhancedAutofillStructureParserV2.ParsedItem> {
        val existingIds = existingItems.map { it.id }.toSet()
        val out = mutableListOf<EnhancedAutofillStructureParserV2.ParsedItem>()
        for (index in 0 until structure.windowNodeCount) {
            collectFocusedSyntheticItems(
                node = structure.getWindowNodeAt(index).rootViewNode,
                existingIds = existingIds,
                hasPasswordTarget = hasPasswordTarget,
                out = out,
            )
        }
        return out
    }

    private fun collectFocusedSyntheticItems(
        node: AssistStructure.ViewNode,
        existingIds: Set<AutofillId>,
        hasPasswordTarget: Boolean,
        out: MutableList<EnhancedAutofillStructureParserV2.ParsedItem>,
    ) {
        if (node.isFocused &&
            node.autofillId != null &&
            !existingIds.contains(node.autofillId)
        ) {
            val inferredHint = inferFocusedFieldHint(node, hasPasswordTarget)
            if (inferredHint != null) {
                out += EnhancedAutofillStructureParserV2.ParsedItem(
                    id = node.autofillId!!,
                    hint = inferredHint,
                    accuracy = EnhancedAutofillStructureParserV2.Accuracy.MEDIUM,
                    isFocused = true,
                    isVisible = node.visibility == View.VISIBLE,
                    traversalIndex = 0,
                )
            }
        }
        for (childIndex in 0 until node.childCount) {
            node.getChildAt(childIndex)?.let {
                collectFocusedSyntheticItems(
                    node = it,
                    existingIds = existingIds,
                    hasPasswordTarget = hasPasswordTarget,
                    out = out,
                )
            }
        }
    }

    private fun inferFocusedFieldHint(
        node: AssistStructure.ViewNode,
        hasPasswordTarget: Boolean,
    ): FieldHint? {
        val inputType = node.inputType
        val classBits = inputType and InputType.TYPE_MASK_CLASS
        if (classBits == InputType.TYPE_CLASS_TEXT) {
            when (inputType and InputType.TYPE_MASK_VARIATION) {
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                -> return FieldHint.PASSWORD

                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                -> return FieldHint.EMAIL_ADDRESS

                InputType.TYPE_TEXT_VARIATION_PHONETIC -> return FieldHint.PHONE_NUMBER
            }
        } else if (classBits == InputType.TYPE_CLASS_NUMBER) {
            if ((inputType and InputType.TYPE_MASK_VARIATION) ==
                InputType.TYPE_NUMBER_VARIATION_PASSWORD
            ) {
                return FieldHint.PASSWORD
            }
        }
        // 非密码/邮箱/电话类的聚焦文本框：仅当屏幕上已存在密码框（登录上下文）时，
        // 才当作账号字段合成，用于补全漏识别的账号框（如影视类 App）。
        // 无密码上下文的普通文本框（搜索框/备注/昵称等）不合成，避免误弹密码建议。
        return if (hasPasswordTarget) FieldHint.USERNAME else null
    }

    /**
     * 判断指定 [AutofillId] 是否存在于当前 [AssistStructure]（可见或不可见均可）。
     * 用于密码框跨请求携带时校验缓存 id 是否仍有效，避免注入失效 id。
     */
    private fun structureContainsAutofillId(
        structure: AssistStructure,
        id: AutofillId,
    ): Boolean {
        for (i in 0 until structure.windowNodeCount) {
            if (containsAutofillId(structure.getWindowNodeAt(i).rootViewNode, id)) {
                return true
            }
        }
        return false
    }

    private fun containsAutofillId(
        node: AssistStructure.ViewNode,
        id: AutofillId,
    ): Boolean {
        if (node.autofillId == id) return true
        for (i in 0 until node.childCount) {
            node.getChildAt(i)?.let {
                if (containsAutofillId(it, id)) return true
            }
        }
        return false
    }

    /**
     * 查找指定 [AutofillId] 在当前 [AssistStructure] 中的可见性；不存在则返回 null。
     */
    private fun findNodeVisibility(
        structure: AssistStructure,
        id: AutofillId,
    ): Boolean? {
        for (i in 0 until structure.windowNodeCount) {
            val visibility = findNodeVisibilityInWindow(
                structure.getWindowNodeAt(i).rootViewNode,
                id,
            )
            if (visibility != null) return visibility
        }
        return null
    }

    private fun findNodeVisibilityInWindow(
        node: AssistStructure.ViewNode,
        id: AutofillId,
    ): Boolean? {
        if (node.autofillId == id) return node.visibility == View.VISIBLE
        for (i in 0 until node.childCount) {
            node.getChildAt(i)?.let {
                val visibility = findNodeVisibilityInWindow(it, id)
                if (visibility != null) return visibility
            }
        }
        return null
    }

    override fun onConnected() {
        super.onConnected()
        // 系统反复绑定/解绑 autofill service 属常态心跳（真机日志 5 分钟内 30+30 次），
        // 降 DEBUG；真实故障另有 E 级日志，此处 INFO 级只会淹没有效信息。
        AutofillLogger.d("AF", "Service connected")
    }

    override fun onDisconnected() {
        AutofillSessionGrants.clear()
        passwordMemoryByPackage.clear()
        super.onDisconnected()
        AutofillLogger.d("AF", "Service disconnected")
    }

    private fun resolveEffectivePackageName(
        parsedApplicationId: String?,
        fallbackPackage: String,
    ): String {
        val parsedCandidate = parsedApplicationId
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.substringBefore(':')
        val fallbackCandidate = fallbackPackage
            .trim()
            .substringBefore(':')
        return when {
            !parsedCandidate.isNullOrBlank() && isLikelyAndroidPackageName(parsedCandidate) -> parsedCandidate
            fallbackCandidate.isNotBlank() -> fallbackCandidate
            else -> parsedCandidate.orEmpty()
        }
    }

    private fun isLikelyAndroidPackageName(value: String): Boolean {
        if (value.length !in 3..255) return false
        if (!value.contains('.')) return false
        return PACKAGE_NAME_REGEX.matches(value)
    }

    private fun resolveAppDisplayName(packageName: String): String? {
        if (packageName.isBlank()) return null
        return runCatchingObserved {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info)?.toString()
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun isSelfPackage(packageName: String): Boolean {
        return packageName.equals(applicationContext.packageName, ignoreCase = true)
    }
}
