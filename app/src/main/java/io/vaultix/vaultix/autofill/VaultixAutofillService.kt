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
        val parsed = AssistStructureParser.parse(structure)
        // 对齐 Bitwarden blocked URIs：系统界面 / 设置 / 本应用自身不提供填充。
        if (AutofillRequestContextPolicy.isBlockedPackage(parsed.packageName, packageName)) {
            callback.onSuccess(null)
            return
        }
        val job = scope.launch {
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
        if (ids.isEmpty()) return null

        val unlocked = vaultRepository.observeUnlockedVaultIds().first()
        if (unlocked.isEmpty()) {
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
                    ),
                    requestCode = REQUEST_UNLOCK,
                ),
                title = getString(R.string.autofill_unlock_title),
                subtitle = getString(R.string.autofill_unlock_subtitle),
            )
        }

        val vault = collectCandidates(unlocked)
        // Edge 等浏览器不上报 webDomain → 用地址栏 / 结构文本兜底域名参与匹配。
        val webDomain = parsed.webDomain ?: parsed.fallbackWebDomain
        val matched = BitwardenLikeAutofillMatcher.match(
            credentials = vault.credentials,
            packageName = parsed.packageName,
            webDomain = webDomain,
            config = MatchConfig(
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
        // 填充后自动复制验证码（条目标了 TOTP 但页面没有验证码框时）
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
        if (added > 0) return builder.build()

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
                ),
                requestCode = REQUEST_SEARCH,
            ),
            title = getString(R.string.autofill_no_match_title),
            subtitle = getString(R.string.autofill_no_match_subtitle),
            saveInfo = saveInfo,
        )
    }

    /** 汇总所有已解锁库的候选（登录 / 卡片 / 身份）。 */
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
     * - 条目有验证码但**页面没有验证码框** → 走回调路径，回填后自动复制验证码
     *   （[AutofillIntents.MODE_COPY_TOTP]，对齐 Bitwarden 的填充后自动复制 TOTP）。
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
            copyTotpEnabled && needsTotpCopy(suggestion) -> copyTotpIntent(suggestion, entries)
            else -> null
        }
        return AutofillDatasets.build(
            context = this,
            entries = entries,
            title = suggestion.title,
            subtitle = suggestion.subtitle,
            datasetId = suggestion.id,
            authIntent = authIntent,
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
        ),
        requestCode = suggestion.id.hashCode(),
    )

    /** 条目带验证码、但本次填充不会写验证码框 → 需要回调后再复制一个。 */
    private fun needsTotpCopy(suggestion: FillSuggestion): Boolean =
        !suggestion.totpSecret.isNullOrBlank() && FieldHint.OTP !in suggestion.fields

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
        ),
        requestCode = suggestion.id.hashCode(),
    )

    /** TOTP 密钥 → 当前验证码；解析或计算失败返回 null（不阻塞账号密码填充）。 */
    private fun totpCode(raw: String): String? = runCatching {
        val config = OtpUriParser.parse(raw) ?: return null
        TotpGenerator.generate(config)
    }.getOrNull()

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
    }
}
