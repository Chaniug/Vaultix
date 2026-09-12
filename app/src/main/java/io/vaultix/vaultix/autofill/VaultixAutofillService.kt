/*
 * Vaultix — app:autofill
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * 部分匹配/解析逻辑移植自 Bastion（GPL-3.0，Copyright 2025 JoyinJoester），
 * 移植处均保留溯源声明（见 match/ 与 parser/ 包内对应文件）。
 */
package io.vaultix.vaultix.autofill

import android.content.Intent
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import dagger.hilt.android.AndroidEntryPoint
import io.vaultix.common.OtpUriParser
import io.vaultix.common.TotpGenerator
import io.vaultix.domain.ItemRepository
import io.vaultix.domain.VaultRepository
import io.vaultix.model.VaultItem
import io.vaultix.vaultix.R
import io.vaultix.vaultix.autofill.engine.AutofillCredentialMapper
import io.vaultix.vaultix.autofill.engine.AutofillDatasets
import io.vaultix.vaultix.autofill.engine.FillPlanner
import io.vaultix.vaultix.autofill.fillassist.FillAssistRepository
import io.vaultix.vaultix.autofill.fillassist.FillAssistRules
import io.vaultix.vaultix.autofill.match.AutofillFillTargetPolicy
import io.vaultix.vaultix.autofill.match.AutofillRequestContextPolicy
import io.vaultix.vaultix.autofill.match.BitwardenLikeAutofillMatcher
import io.vaultix.vaultix.autofill.match.MatchConfig
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.vaultix.autofill.model.AutofillCredential
import io.vaultix.vaultix.autofill.model.FieldHint
import io.vaultix.vaultix.autofill.model.FillSuggestion
import io.vaultix.vaultix.autofill.model.ParsedStructure
import io.vaultix.vaultix.autofill.parser.AssistStructureParser
import io.vaultix.vaultix.autofill.save.AutofillSaveInfo
import io.vaultix.vaultix.autofill.save.AutofillSaveIntents
import io.vaultix.vaultix.session.ActiveVaultStore
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 系统自动填充服务（M2-a）。
 *
 * 链路：`AssistStructure` → [AssistStructureParser] → [BitwardenLikeAutofillMatcher]
 * → [FillPlanner] → [FillResponse]（下拉 Dataset）。与 `MainActivity` 同进程同 UID，
 * 直接经 Hilt 读取已解锁库的解密明文；库全部锁定时改为认证回灌（[AutofillActivity] 引导解锁）。
 */
@AndroidEntryPoint
class VaultixAutofillService : AutofillService() {

    @Inject
    lateinit var vaultRepository: VaultRepository

    @Inject
    lateinit var itemRepository: ItemRepository

    @Inject
    lateinit var prefs: VaultixPreferences

    @Inject
    lateinit var activeVaultStore: ActiveVaultStore

    /** 填充辅助规则（站点级选择器）；拉不到时行为等同没有它。 */
    @Inject
    lateinit var fillAssistRepository: FillAssistRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var activeJob: Job? = null

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback,
    ) {
        val structure = request.fillContexts.lastOrNull()?.structure
        if (structure == null) {
            callback.onSuccess(null)
            return
        }
        val job = scope.launch {
            // 填充辅助开关（设置 → 自动填充 → 填充行为）。关掉后**完全不读规则表**：
            // 识别退回纯启发式，行为与搬运 Fill Assist 之前一致。
            // 上游同样以 `settingsRepository.isFillAssistEnabled` 门控
            // （`AutofillParserImpl` 里 `isFillAssistEnabled` 为假就直接走启发式分支）。
            //
            // ⚠️ 开关是偏好流，只能挂起读取 —— 故整段解析都放在协程里（`onFillRequest`
            // 本身不是挂起函数，同文件曾因此在编译期报「suspend 只能在协程里调用」）。
            val fillAssistEnabled = runCatching { prefs.fillAssistEnabled.first() }.getOrDefault(true)
            // 填充辅助：按站点选择器精确识别字段（读内存/磁盘缓存，廉价）。
            val parsed = AssistStructureParser.parse(
                structure = structure,
                fillAssistRules = if (fillAssistEnabled) {
                    fillAssistRepository.currentRules()
                } else {
                    FillAssistRules.EMPTY
                },
            )
            // 诊断（仅元数据）：浏览器填充静默失效时靠它定位「是没解析到字段，还是没匹配到条目」；
            // `targets=0` 表示页面上没有值得填充的字段（搜索框 / 昵称框 …）→ 本次有意不响应。
            // `seq=` 是**字段序列**（语义+可见性），用于定位「账号框为什么没被认成 USERNAME」
            // —— 只看计数分不清「真账号框是 UNKNOWN」还是「被假 USERNAME 占了位」。
            AutofillLogger.d(
                "fillRequest pkg=${parsed.packageName} webDomain=${parsed.webDomain} " +
                    "fallback=${parsed.fallbackWebDomain} webView=${parsed.webView} " +
                    "fields=${parsed.fields.size} hints=${parsed.fields.groupingBy { it.hint }.eachCount()} " +
                    "targets=${AutofillFillTargetPolicy.fillTargets(parsed).size} " +
                    "user=${parsed.usernameId != null} pass=${parsed.passwordId != null} " +
                    "seq=${fieldSequence(parsed)}",
            )
            // 对齐 Bitwarden blocked URIs：系统界面 / 设置 / 本应用自身不提供填充。
            if (AutofillRequestContextPolicy.isBlockedPackage(parsed.packageName, packageName)) {
                withContext(Dispatchers.Main) { callback.onSuccess(null) }
                return@launch
            }
            // 规则表刷新（6 小时节流、失败静默）：只影响**后续**填充，绝不阻塞本次。
            // 开关关闭时不刷新 —— 省掉无意义的联网与磁盘写。
            if (fillAssistEnabled) runCatching { fillAssistRepository.refreshIfStale() }
            val response = runCatching { buildResponse(parsed) }.getOrNull()
            withContext(Dispatchers.Main) {
                if (!cancellationSignal.isCanceled) callback.onSuccess(response)
            }
        }
        activeJob = job
        cancellationSignal.setOnCancelListener { job.cancel() }
    }

    /**
     * 保存回调：用户在 App / 网页提交登录表单后由框架调用（**前提是 FillResponse 挂了
     * `SaveInfo`**，见 [AutofillSaveInfo]）。
     *
     * 这里只做「取当前账号密码 + 拉起确认界面」，真正的落库在 [AutofillSaveActivity]：
     * 保存要写密文，必须在解锁会话里进行，且要让用户确认存成什么名字。
     */
    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        val parsed = request.fillContexts.lastOrNull()?.structure?.let(AssistStructureParser::parse)
        val webDomain = parsed?.webDomain ?: parsed?.fallbackWebDomain
        val username = parsed?.fields?.firstOrNull { it.hint == FieldHint.USERNAME }?.value.orEmpty()
        val password = parsed?.fields?.firstOrNull { it.hint == FieldHint.PASSWORD }?.value.orEmpty()
        // 先回执：框架不等我们，保存界面自己异步起。
        callback.onSuccess()
        if (username.isBlank() && password.isBlank()) return
        scope.launch {
            if (!prefs.autofillSavePrompt.first()) return@launch
            val intent = AutofillSaveIntents.create(
                context = this@VaultixAutofillService,
                packageName = parsed?.packageName,
                webDomain = webDomain,
                username = username,
                password = password,
            )
            withContext(Dispatchers.Main) { startActivity(intent) }
        }
    }

    override fun onDestroy() {
        activeJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    /** 解析 → 匹配 → 规划 → Dataset；任何异常都退化为「无响应」，不阻塞被填充的 App。 */
    private suspend fun buildResponse(parsed: ParsedStructure): FillResponse? {
        val ids = AutofillDatasets.allFillableIds(parsed)
        if (ids.isEmpty()) {
            // 页面上没有值得填充的字段（搜索框 / 昵称框 / 订阅框 …）→ **直接不响应**。
            //
            // 对齐 Bitwarden：`AutofillRequest.Unfillable` 时 `fillCallback.onSuccess(null)`
            // ——「This effectively disables autofill for this view set and allows the
            // AutofillService to be unbound」；其 FillResponseBuilder 在无可填 id 时同样返回 null。
            // 此前这里用的是「全部可见字段」，于是任何页面都能凑出 ids，再挂一个认证响应
            // ⇒ 搜索框也会把填充 UI 勾出来（用户反馈的误弹）。
            AutofillLogger.d("noFillTarget → 不响应（非凭据字段，避免误弹）")
            return null
        }

        val unlocked = vaultRepository.observeUnlockedVaultIds().first()
        if (unlocked.isEmpty()) {
            AutofillLogger.d("locked: no unlocked vault → unlock fallback")
            return AutofillDatasets.buildFallback(
                context = this,
                ids = ids,
                authIntent = AutofillIntents.pending(
                    context = this,
                    intent = AutofillIntents.create(
                        context = this,
                        mode = AutofillIntents.MODE_UNLOCK,
                        title = getString(R.string.autofill_unlock_title),
                        subtitle = getString(R.string.autofill_unlock_subtitle),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    requestCode = REQUEST_UNLOCK,
                ),
                title = getString(R.string.autofill_unlock_title),
                subtitle = getString(R.string.autofill_unlock_subtitle),
            )
        }

        // ★ 候选来源收敛到**单个活跃库**（Docs/progress/main-shell-migration.md 阶段 2
        // 「★ 全局活跃库真源」）。历史行为是 `for (vaultId in unlocked)` 遍历全部已解锁库聚合：
        // 云端库与 KDBX 库同时解锁时，同一站点会冒出两条来源不同的候选（用户不知点哪条），
        // 保存时也不知写回哪个库 —— 即用户最初反馈的「条目错乱 / 保存重复」。
        val sources = singleActiveVault(unlocked)
        val vault = collectCandidates(sources)
        // Edge 等浏览器不上报 webDomain → 用地址栏 / 结构文本兜底域名参与匹配。
        val webDomain = parsed.webDomain ?: parsed.fallbackWebDomain
        val matched = BitwardenLikeAutofillMatcher.match(
            credentials = vault.credentials,
            packageName = parsed.packageName,
            webDomain = webDomain,
            config = MatchConfig(
                // 域匹配宽严由用户在「设置 → 自动填充 → 填充行为」控制，默认与
                // Bitwarden 一致（允许基域匹配、不强制精确域）。浏览器里填不出来时
                // 先关掉「严格匹配」再试，是成本最低的排查第一步。
                allowBaseDomainMatch = prefs.autofillBaseDomainMatch.first(),
                exactDomainOnly = prefs.autofillExactDomainOnly.first(),
                allowPackageMatch = AutofillRequestContextPolicy.allowPackageMatching(
                    packageName = parsed.packageName,
                    webDomain = webDomain,
                    isWebView = parsed.webView,
                ),
            ),
        )
        val plan = FillPlanner.plan(
            context = AutofillCredentialMapper.toFillContext(parsed, webDomain),
            matchedLogins = matched,
            cards = vault.cards,
            identities = vault.identities,
            totpProvider = ::totpCode,
        )

        val saveInfo = AutofillSaveInfo.build(parsed)
        // 填充后自动复制验证码（对齐 Bitwarden：**无条件**复制，受 autoCopyTotp 开关门控）
        val copyTotp = prefs.autoCopyTotp.first()
        val builder = FillResponse.Builder()
        saveInfo?.let { builder.setSaveInfo(it) }
        var added = 0
        // 上限保护：FillResponse 经 Binder 传输有大小限制，条目多时不截断会导致
        // 整个响应失败（表现为「浏览器里一点反应都没有」）。
        for (suggestion in plan.suggestions.take(MAX_DATASETS)) {
            val dataset = datasetFor(parsed, suggestion, copyTotp) ?: continue
            builder.addDataset(dataset)
            added++
        }
        AutofillLogger.d(
            "fillResponse domain=$webDomain active=${sources.firstOrNull() ?: "-"} " +
                "unlocked=${unlocked.size} candidates=${vault.credentials.size} " +
                "matched=${matched.size} datasets=$added",
        )
        if (added > 0) return builder.build()

        AutofillLogger.d("noMatch domain=$webDomain → search fallback")
        return AutofillDatasets.buildFallback(
            context = this,
            ids = ids,
            authIntent = AutofillIntents.pending(
                context = this,
                intent = AutofillIntents.create(
                    context = this,
                    mode = AutofillIntents.MODE_SEARCH,
                    title = getString(R.string.autofill_no_match_title),
                    subtitle = getString(R.string.autofill_no_match_subtitle),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                requestCode = REQUEST_SEARCH,
            ),
            title = getString(R.string.autofill_no_match_title),
            subtitle = getString(R.string.autofill_no_match_subtitle),
            saveInfo = saveInfo,
        )
    }

    /**
     * 候选来源 = **唯一活跃库**。
     *
     * [unlocked] 非空已由调用方保证；这里只在活跃库解析结果不在其中时退化成
     * 「字典序最小的已解锁库」——**仍然只取一个**，绝不回退成遍历全部。
     */
    private suspend fun singleActiveVault(unlocked: Set<String>): Set<String> {
        val active = activeVaultStore.resolve()
        if (active != null && active in unlocked) return setOf(active)
        return setOfNotNull(unlocked.minOrNull())
    }

    /** 汇总**活跃库**的候选（登录 / 卡片 / 身份）。 */
    private suspend fun collectCandidates(unlocked: Set<String>): VaultCandidates {
        val credentials = mutableListOf<AutofillCredential>()
        val cards = mutableListOf<VaultItem>()
        val identities = mutableListOf<VaultItem>()
        for (vaultId in unlocked) {
            val items = runCatching { itemRepository.observeItems(vaultId).first() }.getOrElse { emptyList() }
            for (item in items) {
                when {
                    AutofillCredentialMapper.isLoginCandidate(item) ->
                        credentials += AutofillCredentialMapper.toCredential(vaultId, item)
                    AutofillCredentialMapper.isCardCandidate(item) -> cards += item
                    AutofillCredentialMapper.isIdentityCandidate(item) -> identities += item
                    else -> Unit
                }
            }
        }
        return VaultCandidates(credentials, cards, identities)
    }

    /**
     * 单条建议 → Dataset。
     *
     * 需要「先认证再回填」的两种情况：
     * - 主密码二次验证（[FillSuggestion.requiresReprompt]）；
     * - 条目带验证码 → 走回调路径，回填后把验证码复制到剪贴板
     *   （[AutofillIntents.MODE_COPY_TOTP]）。
     *
     * ⚠️ 复制是**无条件**的（只要有 totp 且开关开启），对齐 Bitwarden
     * `AutofillCompletionManagerImpl` —— 它在每次填充成功后都调
     * `tryCopyTotpToClipboard`（仅由 `isAutoCopyTotpDisabled` 门控）。
     * 此前 Vaultix 只在「页面没有验证码框」时才复制，与上游不一致（2026-09-12 对齐）。
     */
    private fun datasetFor(
        parsed: ParsedStructure,
        suggestion: FillSuggestion,
        copyTotpEnabled: Boolean,
    ): Dataset? {
        val entries = AutofillDatasets.entriesFor(parsed, suggestion)
        if (entries.isEmpty()) return null
        val authIntent = when {
            suggestion.requiresReprompt -> repromptIntent(suggestion, entries)
            copyTotpEnabled && !suggestion.totpSecret.isNullOrBlank() -> copyTotpIntent(suggestion, entries)
            else -> null
        }
        return AutofillDatasets.build(
            context = this,
            entries = entries,
            title = suggestion.title,
            subtitle = suggestion.subtitle,
            datasetId = suggestion.id,
            authIntent = authIntent,
            // 图标随条目类别（登录 = 地球 / 银行卡 = 卡片 / 身份 = 人像），
            // 对齐 Bitwarden `AutofillCipher.iconRes` —— 面板里一行一图标才分得清类型。
            iconRes = AutofillDatasets.iconFor(suggestion.category),
        )
    }

    private fun repromptIntent(
        suggestion: FillSuggestion,
        entries: List<Pair<android.view.autofill.AutofillId, String>>,
    ) = AutofillIntents.pending(
        context = this,
        intent = AutofillIntents.create(
            context = this,
            mode = AutofillIntents.MODE_REPROMPT,
            title = suggestion.title,
            subtitle = suggestion.subtitle,
            datasetId = suggestion.id,
            entries = entries,
            category = suggestion.category,
        ),
        requestCode = suggestion.id.hashCode(),
    )

    private fun copyTotpIntent(
        suggestion: FillSuggestion,
        entries: List<Pair<android.view.autofill.AutofillId, String>>,
    ) = AutofillIntents.pending(
        context = this,
        intent = AutofillIntents.create(
            context = this,
            mode = AutofillIntents.MODE_COPY_TOTP,
            title = suggestion.title,
            subtitle = suggestion.subtitle,
            datasetId = suggestion.id,
            entries = entries,
            totpSecret = suggestion.totpSecret,
            category = suggestion.category,
        ),
        requestCode = suggestion.id.hashCode(),
    )

    /** TOTP 密钥 → 当前验证码；解析或计算失败返回 null（不阻塞账号密码填充）。 */
    private fun totpCode(raw: String): String? = runCatching {
        val config = OtpUriParser.parse(raw) ?: return null
        TotpGenerator.generate(config)
    }.getOrNull()

    /**
     * 字段序列诊断串：`USERNAME,UNKNOWN(hid),PASSWORD,…`（最多 [LOG_FIELD_SEQ_LIMIT] 项）。
     *
     * 为什么值得单独打一行：账号框填不进去时，有两种完全不同的病因 ——
     * ①真账号框在序列里是 `UNKNOWN`（升格没生效）；②它前面/后面有个假 `USERNAME`
     * （展示节点被误判）把语义位占了。只看 `hints={}` 计数无法区分，看序列一眼就能定。
     */
    private fun fieldSequence(parsed: ParsedStructure): String = parsed.fields
        .take(LOG_FIELD_SEQ_LIMIT)
        .joinToString(",") { field -> field.hint.name + if (field.isVisible) "" else "(hid)" }

    /** 一次填充请求内汇总的候选集合。 */
    private data class VaultCandidates(
        val credentials: List<AutofillCredential>,
        val cards: List<VaultItem>,
        val identities: List<VaultItem>,
    )

    private companion object {
        const val REQUEST_UNLOCK = 1001
        const val REQUEST_SEARCH = 1002

        /** 下拉面板最多给几条建议（超出转「在 Vaultix 中搜索」，防响应过大被系统丢弃）。 */
        const val MAX_DATASETS = 10

        /** 字段序列诊断串最多打几项（避免长页面把日志刷爆）。 */
        const val LOG_FIELD_SEQ_LIMIT = 12
    }
}
