package com.bastion.app.autofill_ng

import android.content.Context
import com.bastion.app.autofill_ng.core.AutofillLogger
import com.bastion.app.logging.runCatchingObserved
import com.bastion.app.utils.SettingsManager
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * autofill 进程级配置缓存。
 *
 * 背景：填充热路径（AccountFillPolicy / FilledDataBuilderNg / AutofillPickerActivityV2 /
 * BastionAutofillServiceNg.onFillRequest 关键读）原先每次都 runBlocking(SettingsManager
 * .settingsFlow.first())，在 autofill 独立进程(:autofill)内反复冷起 DataStore 读取，是
 * Phase C 暴露的填充延迟/偶发卡顿根因之一。
 *
 * 方案：服务冷启动（onCreate，非热路径）一次性预加载本进程所需配置到 @Volatile 字段，之后由
 * 服务 scope 内的观测协程持续刷新；热路径直接读缓存字段，零阻塞。onCreate 内的单次
 * runBlocking(200ms 上限) 与热路径治理不冲突。
 *
 * 注意：本对象为 autofill 进程单例，仅在 BastionAutofillServiceNg.onCreate 调用 preload，
 * 并由该服务的协程作用域刷新。主进程 BaseBastionActivity 的启动期语言读取不在本缓存范围内。
 */
object AutofillConfigCache {

    @Volatile
    var language: String = "SYSTEM"

    @Volatile
    var autoLockMinutes: Int = 5

    @Volatile
    var separateUsernameAccountEnabled: Boolean = false

    @Volatile
    var isAutofillEnabled: Boolean = true

    @Volatile
    var isInlineSuggestionsEnabled: Boolean = true

    @Volatile
    var autofillAuthRequired: Boolean = true

    // Phase C onFillRequest 全量缓存化（方案 B 延伸）
    @Volatile
    var isV2RespectAutofillOffEnabled: Boolean = false

    @Volatile
    var v2DefaultSourceFilter: AutofillPreferences.AutofillDefaultSourceFilter =
        AutofillPreferences.AutofillDefaultSourceFilter.ALL

    @Volatile
    var v2DefaultKeepassDatabaseId: Long? = null

    @Volatile
    var v2DefaultBitwardenVaultId: Long? = null

    @Volatile
    var isBitwardenStrictModeEnabled: Boolean = true

    @Volatile
    var isBitwardenSubdomainMatchEnabled: Boolean = true

    @Volatile
    var domainMatchStrategy: DomainMatchStrategy = DomainMatchStrategy.BASE_DOMAIN

    @Volatile
    var isPasswordSuggestionEnabled: Boolean = true

    @Volatile
    private var preloaded: Boolean = false

    fun isReady(): Boolean = preloaded

    /**
     * 冷路径预加载（仅在 BastionAutofillServiceNg.onCreate 调用）。
     * 读取失败或超时均安全降级到字段默认值，绝不向外抛异常。
     */
    fun preload(context: Context) {
        val appCtx = context.applicationContext
        runCatching {
            runBlocking {
                withTimeout(200) {
                    val settings = SettingsManager(appCtx).settingsFlow.first()
                    val prefs = AutofillPreferences(appCtx)
                    language = settings.language.name
                    autoLockMinutes = settings.autoLockMinutes
                    separateUsernameAccountEnabled = settings.separateUsernameAccountEnabled
                    autofillAuthRequired = settings.autofillAuthRequired
                    // 并行读取 10 个独立 Flow，避免串行 .first() 累加超时（低端机/IO 抖动）。
                    // 各类型不同，不能 awaitAll 解构；用 async 各自启动 + 集中 await 实现并行。
                    val dInline = async { prefs.isInlineSuggestionsEnabled.first() }
                    val dEnabled = async { prefs.isAutofillEnabled.first() }
                    val dRespectOff = async { prefs.isV2RespectAutofillOffEnabled.first() }
                    val dFilter = async { prefs.v2DefaultSourceFilter.first() }
                    val dKeepassId = async { prefs.v2DefaultKeepassDatabaseId.first() }
                    val dBwId = async { prefs.v2DefaultBitwardenVaultId.first() }
                    val dStrict = async { prefs.isBitwardenStrictModeEnabled.first() }
                    val dSubdomain = async { prefs.isBitwardenSubdomainMatchEnabled.first() }
                    val dStrategy = async { prefs.domainMatchStrategy.first() }
                    val dSuggestion = async { prefs.isPasswordSuggestionEnabled.first() }
                    isInlineSuggestionsEnabled = dInline.await()
                    isAutofillEnabled = dEnabled.await()
                    isV2RespectAutofillOffEnabled = dRespectOff.await()
                    v2DefaultSourceFilter = dFilter.await()
                    v2DefaultKeepassDatabaseId = dKeepassId.await()
                    v2DefaultBitwardenVaultId = dBwId.await()
                    isBitwardenStrictModeEnabled = dStrict.await()
                    isBitwardenSubdomainMatchEnabled = dSubdomain.await()
                    domainMatchStrategy = dStrategy.await()
                    isPasswordSuggestionEnabled = dSuggestion.await()
                }
            }
        }.onFailure {
            AutofillLogger.w(
                "AFCACHE",
                "preload timed out or failed, falling back to defaults: ${it.message}"
            )
        }
        preloaded = true
    }
}
