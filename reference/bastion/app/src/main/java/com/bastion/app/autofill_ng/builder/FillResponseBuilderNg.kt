package com.bastion.app.autofill_ng.builder

import com.bastion.app.logging.runCatchingObserved
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.service.autofill.FillResponse
import android.service.autofill.InlinePresentation
import android.service.autofill.Presentations
import android.service.autofill.SaveInfo
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import com.bastion.app.R
import com.bastion.app.autofill_ng.EnhancedAutofillStructureParserV2.FieldHint
import com.bastion.app.autofill_ng.AutofillCipherCallbackActivity
import com.bastion.app.autofill_ng.AutofillPickerActivityV2
import com.bastion.app.autofill_ng.AutofillUnlockActivity
import com.bastion.app.autofill_ng.PasswordSuggestionActivity
import com.bastion.app.autofill_ng.auth.AutofillAuthenticationPolicy
import com.bastion.app.autofill_ng.auth.AutofillGrantContext
import com.bastion.app.autofill_ng.auth.AutofillUnlockRequests
import com.bastion.app.autofill_ng.auth.PendingAutofillUnlockRequest
import com.bastion.app.autofill_ng.builder.AutofillDatasetBuilder
import com.bastion.app.autofill_ng.core.AutofillLogger
import com.bastion.app.autofill_ng.model.AutofillRequest
import com.bastion.app.autofill_ng.model.AutofillView
import com.bastion.app.autofill_ng.model.FilledData
import com.bastion.app.autofill_ng.model.FilledPartition
import com.bastion.app.data.PasswordEntry
import com.bastion.app.utils.PasswordGenerator
import kotlin.random.Random

class FillResponseBuilderNg(
    private val context: Context,
) {
    private companion object {
        private const val TAG = "BastionAutofillBwCompat"
        private const val MANUAL_PLACEHOLDER_VALUE = "PLACEHOLDER"
    }

    fun build(
        request: AutofillRequest.Fillable,
        filledData: FilledData,
        passwordSuggestionEnabled: Boolean = true,
        requireAuthentication: Boolean = true,
        matchedPasswords: List<PasswordEntry> = emptyList(),
    ): FillResponse? {
        val fillableAutofillIds = filledData.fillableAutofillIds
        if (fillableAutofillIds.isEmpty()) {
            android.util.Log.w(TAG, "build skipped: no fillableAutofillIds")
            AutofillLogger.w("FILLING", "Build skipped: no fillableAutofillIds")
            return null
        }

        if (AutofillAuthenticationPolicy.requiresResponseUnlock(
                authenticationRequired = requireAuthentication,
                vaultLocked = filledData.isVaultLocked,
                grantActive = false,
            )
        ) {
            return buildLockedResponse(
                request = request,
                filledData = filledData,
                matchedPasswords = matchedPasswords,
                passwordSuggestionEnabled = passwordSuggestionEnabled,
            )
        }

        val responseBuilder = FillResponse.Builder()
        var cipherDatasetCount = 0
        var failedCipherDatasetCount = 0
        var callbackCipherDatasetCount = 0
        filledData.filledPartitions.forEachIndexed { index, partition ->
            if (partition.filledItems.isEmpty()) return@forEachIndexed
            runCatchingObserved {
                responseBuilder.addDataset(
                    buildCipherDataset(
                        request = request,
                        partition = partition,
                        index = index,
                    )
                )
                cipherDatasetCount++
                if (partition.requiresAuthentication ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                        partition.inlinePresentationSpec != null
                    )
                ) {
                    callbackCipherDatasetCount++
                }
            }.onFailure { error ->
                failedCipherDatasetCount++
                android.util.Log.w(TAG, "Failed to build cipher dataset index=$index", error)
                AutofillLogger.w(
                    "FILLING",
                    "Failed to build cipher dataset",
                    metadata = mapOf(
                        "index" to index,
                        "error" to (error.message ?: error::class.java.simpleName)
                    )
                )
            }
        }

        val strongPasswordDataset = if (passwordSuggestionEnabled) {
            buildStrongPasswordSuggestionDataset(
                request = request
            )
        } else {
            null
        }
        if (strongPasswordDataset != null) {
            responseBuilder.addDataset(strongPasswordDataset)
        }

        responseBuilder.addDataset(
            buildVaultItemDataset(
                request = request,
                filledData = filledData,
                fillableAutofillIds = fillableAutofillIds
            )
        )

        if (filledData.ignoreAutofillIds.isNotEmpty()) {
            responseBuilder.setIgnoredIds(*filledData.ignoreAutofillIds.toTypedArray())
        }

        attachSaveInfoIfNeeded(
            responseBuilder = responseBuilder,
            request = request
        )

        val demotedToAuthCount = filledData.filledPartitions.count { it.requiresAuthentication }
        android.util.Log.i(
            TAG,
            "build result: cipherDatasets=$cipherDatasetCount, " +
                "failedCipherDatasets=$failedCipherDatasetCount, " +
                "strongPasswordDataset=${if (strongPasswordDataset != null) 1 else 0}, " +
                "vaultDataset=1, fillableIds=${fillableAutofillIds.size}, " +
                "suggestedIds=${filledData.filledPartitions.count { it.autofillCipher.cipherId != null }}, " +
                "demotedToAuth=$demotedToAuthCount, " +
                "authRequired=$requireAuthentication, sdk=${Build.VERSION.SDK_INT}, " +
                "callbackCipherDatasets=$callbackCipherDatasetCount"
        )
        AutofillLogger.i(
            "FILLING",
            "FillResponse build result",
            metadata = mapOf(
                "cipherDatasets" to cipherDatasetCount,
                "failedCipherDatasets" to failedCipherDatasetCount,
                "strongPasswordDataset" to (strongPasswordDataset != null),
                "vaultDataset" to true,
                "fillableIds" to fillableAutofillIds.size,
                "suggestedIds" to filledData.filledPartitions.count { it.autofillCipher.cipherId != null },
                "demotedToAuth" to demotedToAuthCount,
                "authRequired" to requireAuthentication,
                "sdk" to Build.VERSION.SDK_INT,
                "callbackCipherDatasets" to callbackCipherDatasetCount,
            )
        )
        return responseBuilder.build()
    }

    private fun buildLockedResponse(
        request: AutofillRequest.Fillable,
        filledData: FilledData,
        matchedPasswords: List<PasswordEntry>,
        passwordSuggestionEnabled: Boolean,
    ): FillResponse {
        val targetIds = filledData.fillableAutofillIds.distinct()
        val grantContext = AutofillGrantContext.fromRequestUri(
            packageName = request.packageName,
            requestUri = request.uri,
            interactionIdentifier = request.interactionIdentifier,
            fieldSignatureKey = request.fieldSignatureKey,
        )
        val requestToken = AutofillUnlockRequests.put(
            PendingAutofillUnlockRequest(
                request = request,
                passwordIds = matchedPasswords.map { it.id }.distinct(),
                passwordSuggestionEnabled = passwordSuggestionEnabled,
                grantContext = grantContext,
            )
        )
        val unlockIntent = AutofillUnlockActivity.getIntent(context, requestToken)
        val pendingIntent = PendingIntent.getActivity(
            context,
            Random.nextInt(),
            unlockIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_CANCEL_CURRENT
            }
        )
        val unlockTitle = context.getString(R.string.autofill_unlock_bastion)
        val menuPresentation = AutofillDatasetBuilder.RemoteViewsFactory.createUnlockPrompt(
            context = context,
            message = unlockTitle,
        )
        val inlinePresentation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val spec = filledData.vaultItemInlinePresentationSpec
                ?: request.inlinePresentationSpecs?.firstOrNull()
            spec?.let {
                AutofillDatasetBuilder.InlinePresentationBuilder.tryCreate(
                    context = context,
                    spec = it,
                    specs = request.inlinePresentationSpecs,
                    index = request.inlinePresentationSpecs?.indexOf(it) ?: 0,
                    pendingIntent = pendingIntent,
                    title = unlockTitle,
                    subtitle = grantContext.webDomain ?: request.packageName,
                    icon = AutofillDatasetBuilder.InlinePresentationBuilder.createAppIcon(
                        context = context,
                        packageName = request.packageName,
                    ),
                    contentDescription = unlockTitle,
                )
            }
        } else {
            null
        }

        val responseBuilder = FillResponse.Builder()
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                val presentations = Presentations.Builder()
                    .setMenuPresentation(menuPresentation)
                    .apply {
                        if (inlinePresentation != null) {
                            setInlinePresentation(inlinePresentation)
                        }
                    }
                    .build()
                responseBuilder.setAuthentication(
                    targetIds.toTypedArray(),
                    pendingIntent.intentSender,
                    presentations,
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                @Suppress("DEPRECATION")
                responseBuilder.setAuthentication(
                    targetIds.toTypedArray(),
                    pendingIntent.intentSender,
                    menuPresentation,
                    inlinePresentation,
                )
            }
            else -> {
                @Suppress("DEPRECATION")
                responseBuilder.setAuthentication(
                    targetIds.toTypedArray(),
                    pendingIntent.intentSender,
                    menuPresentation,
                )
            }
        }
        if (filledData.ignoreAutofillIds.isNotEmpty()) {
            responseBuilder.setIgnoredIds(*filledData.ignoreAutofillIds.toTypedArray())
        }
        AutofillLogger.i(
            "AUTH",
            "Locked vault response contains one response-level unlock action",
            metadata = mapOf(
                "packageName" to request.packageName,
                "webDomain" to (grantContext.webDomain ?: "none"),
                "targetCount" to targetIds.size,
                "passwordCount" to matchedPasswords.size,
            )
        )
        return responseBuilder.build()
    }

    private fun buildCipherDataset(
        request: AutofillRequest.Fillable,
        partition: FilledPartition,
        index: Int,
    ): android.service.autofill.Dataset {
        val menuPresentation = AutofillDatasetBuilder.RemoteViewsFactory.createPasswordEntry(
            context = context,
            title = partition.autofillCipher.name,
            username = partition.autofillCipher.subtitle
        )

        val hasInlinePresentation = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            (partition.inlinePresentationSpec != null || !request.inlinePresentationSpecs.isNullOrEmpty())
        val callbackTargets = buildLoginCallbackTargets(request.partition.views)

        // 对齐 Bitwarden：vault 锁定（requiresAuthentication）或 WebView 场景（forceDatasetAuth）
        // 都挂 setAuthentication，让框架走"用户点选→AutofillCipherCallbackActivity 回灌→回填"
        // 路径。该路径对系统 WebView 密码框虚拟节点回填比纯直填更可靠（Bitwarden 始终挂 auth）。
        // 关键差异：forceDatasetAuth 时 vault 已解锁，回调 requireAuthentication=false → 不触发指纹；
        // filledItems 保留真实值，回调直接回填，不重新按 hint 映射，避免 Edge 账户名失配。
        val authPendingIntent = if (partition.requiresAuthentication || partition.forceDatasetAuth) {
            val intent = createCipherAuthPendingIntent(
                request = request,
                partition = partition,
                callbackTargets = callbackTargets,
                requireAuthentication = partition.requiresAuthentication,
            )
            if (intent == null) {
                throw IllegalStateException("Authentication required but cipher callback pending intent is unavailable")
            }
            intent
        } else {
            null
        }

        val fields = linkedMapOf<AutofillId, AutofillDatasetBuilder.FieldData?>()
        partition.filledItems.forEach { filledItem ->
            fields[filledItem.autofillId] = AutofillDatasetBuilder.FieldData(
                value = filledItem.value,
                presentation = menuPresentation
            )
        }

        val inlinePendingIntent = if (hasInlinePresentation) {
            // Inline 填充走 picker / callback 路径，需要一个 PendingIntent
            createCipherAuthPendingIntent(
                request = request,
                partition = partition,
                callbackTargets = callbackTargets,
                requireAuthentication = partition.requiresAuthentication,
            )
        } else {
            null
        }
        val datasetBuilder = AutofillDatasetBuilder.create(
            menuPresentation = menuPresentation,
            fields = fields
        ) {
            // 此处无需再判 `SDK_INT >= R`：本 lambda 只会被 AutofillDatasetBuilder.create
            // 在 API >= 30 时回调（createForTiramisu 要求 33；createPreTiramisu 内部先以
            // `SDK_INT >= R` 守卫后才回调本 lambda），该判断恒为真，lint 报 ObsoleteSdkInt。
            // WebView 兜底：Via 等浏览器对 menu 建议回写不兼容，但 inline（IME commitText）
            // 100% 可靠。partition.inlinePresentationSpec 可能为 null（inline 开关关闭时），
            // 此时从 request.inlinePresentationSpecs 取第一个兼容 spec 构建 inline。
            val spec = partition.inlinePresentationSpec
                ?: request.inlinePresentationSpecs?.firstOrNull()
                ?: return@create null
            AutofillDatasetBuilder.InlinePresentationBuilder.tryCreate(
                context = context,
                spec = spec,
                specs = request.inlinePresentationSpecs,
                index = index,
                pendingIntent = inlinePendingIntent ?: return@create null,
                title = partition.autofillCipher.name,
                subtitle = partition.autofillCipher.subtitle,
                icon = AutofillDatasetBuilder.InlinePresentationBuilder.createAppIcon(
                    context = context,
                    packageName = request.packageName
                ),
                contentDescription = partition.autofillCipher.name
            )
        }
        // 仅 vault 锁定时挂 setAuthentication，对齐 Bitwarden 行为。
        // vault 解锁时走纯直填 dataset：框架直接写值进输入框，Via 等 WebView 无需无障碍。
        if (authPendingIntent != null) {
            datasetBuilder.setAuthentication(authPendingIntent.intentSender)
            android.util.Log.d(
                "BastionOtpCopy",
                "dataset auth attached: cipherId=${partition.autofillCipher.cipherId}, " +
                    "requiredAuth=${partition.requiresAuthentication}, inline=$hasInlinePresentation"
            )
        } else {
            android.util.Log.d(
                "BastionOtpCopy",
                "dataset direct-fill (no auth): cipherId=${partition.autofillCipher.cipherId}, " +
                    "inline=$hasInlinePresentation"
            )
        }
        return datasetBuilder.build()
    }

    private fun buildStrongPasswordSuggestionDataset(
        request: AutofillRequest.Fillable,
    ): android.service.autofill.Dataset? {
        val passwordHints = request.partition.views
            .filterIsInstance<AutofillView.Login.Password>()
            .map { it.data.hint }
        if (!StrongPasswordSuggestionPolicy.shouldOffer(passwordHints)) {
            return null
        }

        val newPasswordIds = request.partition.views
            .filterIsInstance<AutofillView.Login.Password>()
            .filter { it.data.hint == FieldHint.NEW_PASSWORD }
            .map { it.data.autofillId }
            .distinct()
            .ifEmpty { return null }

        val pendingIntent = createStrongPasswordSuggestionPendingIntent(
            request = request,
            passwordFieldIds = newPasswordIds,
        )
        val menuPresentation = AutofillDatasetBuilder.RemoteViewsFactory.createPasswordSuggestion(context)
        val fields = linkedMapOf<AutofillId, AutofillDatasetBuilder.FieldData?>()
        newPasswordIds.forEach { autofillId ->
            fields[autofillId] = AutofillDatasetBuilder.FieldData(
                value = AutofillValue.forText(MANUAL_PLACEHOLDER_VALUE),
                presentation = menuPresentation,
            )
        }

        val datasetBuilder = AutofillDatasetBuilder.create(
            menuPresentation = menuPresentation,
            fields = fields
        ) {
            // 同上：本 lambda 仅在 API >= 30 时被回调，无需再判 SDK_INT。
            val spec = request.inlinePresentationSpecs?.firstOrNull() ?: return@create null
            AutofillDatasetBuilder.InlinePresentationBuilder.tryCreate(
                context = context,
                spec = spec,
                specs = request.inlinePresentationSpecs,
                index = 0,
                pendingIntent = pendingIntent,
                title = context.getString(R.string.password_suggestion_title),
                subtitle = context.getString(R.string.password_suggestion_subtitle),
                icon = AutofillDatasetBuilder.InlinePresentationBuilder.createAppIcon(
                    context = context,
                    packageName = request.packageName
                ),
                contentDescription = context.getString(R.string.password_suggestion_title)
            )
        }
        datasetBuilder.setAuthentication(pendingIntent.intentSender)
        return datasetBuilder.build()
    }

    private fun createStrongPasswordSuggestionPendingIntent(
        request: AutofillRequest.Fillable,
        passwordFieldIds: List<AutofillId>,
    ): PendingIntent {
        val webDomain = extractWebDomain(request.uri)
        val username = request.partition.views
            .filterIsInstance<AutofillView.Login.Username>()
            .firstOrNull { !it.data.textValue.isNullOrBlank() }
            ?.data
            ?.textValue
            .orEmpty()
        val generatedPassword = PasswordGenerator().generatePassword(
            PasswordGenerator.PasswordOptions(
                length = 16,
                includeUppercase = true,
                includeLowercase = true,
                includeNumbers = true,
                includeSymbols = true,
                excludeSimilar = true,
            )
        )

        val intent = Intent(context, PasswordSuggestionActivity::class.java).apply {
            putExtra(PasswordSuggestionActivity.EXTRA_USERNAME, username)
            putExtra(PasswordSuggestionActivity.EXTRA_GENERATED_PASSWORD, generatedPassword)
            putExtra(PasswordSuggestionActivity.EXTRA_PACKAGE_NAME, request.packageName)
            putExtra(PasswordSuggestionActivity.EXTRA_WEB_DOMAIN, webDomain)
            putParcelableArrayListExtra(
                PasswordSuggestionActivity.EXTRA_PASSWORD_FIELD_IDS,
                ArrayList(passwordFieldIds)
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return PendingIntent.getActivity(
            context,
            Random.nextInt(),
            intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_CANCEL_CURRENT
            }
        )
    }

    private fun createCipherAuthPendingIntent(
        request: AutofillRequest.Fillable,
        partition: FilledPartition,
        callbackTargets: List<AutofillCallbackTarget>,
        requireAuthentication: Boolean,
    ): PendingIntent? {
        val passwordId = partition.autofillCipher.cipherId?.toLongOrNull() ?: return null
        val targets = callbackTargets.ifEmpty {
            partition.filledItems
                .map { AutofillCallbackTarget(it.autofillId, "") }
                .distinctBy { it.autofillId.toString() }
        }
        if (targets.isEmpty()) return null

        val args = AutofillCipherCallbackActivity.Args(
            passwordId = passwordId,
            applicationId = request.packageName,
            webDomain = extractWebDomain(request.uri),
            interactionIdentifier = request.interactionIdentifier,
            interactionIdentifierAliases = ArrayList(request.interactionIdentifierAliases),
            autofillIds = ArrayList(targets.map { it.autofillId }),
            autofillHints = ArrayList(targets.map { it.hintName }),
            fieldSignatureKey = request.fieldSignatureKey,
            rememberLastFilled = true,
            requireAuthentication = requireAuthentication,
            forceCallbackTargets = partition.forceDatasetAuth,
        )
        val pickerIntent = AutofillCipherCallbackActivity.getIntent(context, args)
        return PendingIntent.getActivity(
            context,
            Random.nextInt(),
            pickerIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_CANCEL_CURRENT
            }
        )
    }

    private fun buildVaultItemDataset(
        request: AutofillRequest.Fillable,
        filledData: FilledData,
        fillableAutofillIds: List<AutofillId>,
    ): android.service.autofill.Dataset {
        val targetIds = fillableAutofillIds.distinct()
        val manualEntry = buildManualEntryArtifacts(
            request = request,
            filledData = filledData,
            fillableAutofillIds = targetIds,
        )
        val fields = linkedMapOf<AutofillId, AutofillDatasetBuilder.FieldData?>()
        targetIds.forEach { autofillId ->
            fields[autofillId] = AutofillDatasetBuilder.FieldData(
                // Bitwarden-compatible approach:
                // keep one authenticated manual dataset anchored to all fillable fields
                // with a placeholder value so framework keeps entry visible consistently.
                value = AutofillValue.forText(MANUAL_PLACEHOLDER_VALUE),
                presentation = manualEntry.menuPresentation
            )
        }
        val datasetBuilder = AutofillDatasetBuilder.create(
            menuPresentation = manualEntry.menuPresentation,
            fields = fields
        ) { manualEntry.inlinePresentation }
        datasetBuilder.setAuthentication(manualEntry.pendingIntent.intentSender)
        return datasetBuilder.build()
    }

    private fun buildManualEntryArtifacts(
        request: AutofillRequest.Fillable,
        filledData: FilledData,
        fillableAutofillIds: List<AutofillId>,
    ): ManualEntryArtifacts {
        val webDomain = extractWebDomain(request.uri)
        val autofillHints = buildAutofillHintNames(request.partition.views)
        val suggestedPasswordIds = filledData.filledPartitions
            .mapNotNull { it.autofillCipher.cipherId?.toLongOrNull() }
            .distinct()
            .toLongArray()
        val args = AutofillPickerActivityV2.Args(
            applicationId = request.packageName,
            webDomain = webDomain,
            interactionIdentifier = request.interactionIdentifier,
            interactionIdentifierAliases = ArrayList(request.interactionIdentifierAliases),
            autofillIds = ArrayList(fillableAutofillIds),
            autofillHints = ArrayList(autofillHints),
            suggestedPasswordIds = suggestedPasswordIds,
            isSaveMode = false,
            fieldSignatureKey = request.fieldSignatureKey,
            responseAuthMode = false,
            rememberLastFilled = true,
        )
        val pickerIntent = AutofillPickerActivityV2.getIntent(context, args)
        val pendingIntent = PendingIntent.getActivity(
            context,
            Random.nextInt(),
            pickerIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_CANCEL_CURRENT
            }
        )

        val menuPresentation = if (filledData.isVaultLocked) {
            AutofillDatasetBuilder.RemoteViewsFactory.createUnlockPrompt(
                context = context,
                message = context.getString(R.string.autofill_manual_entry_title)
            )
        } else {
            AutofillDatasetBuilder.RemoteViewsFactory.createManualSelection(
                context = context,
                domain = webDomain,
                packageName = request.packageName
            )
        }

        val inlinePresentation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            filledData.vaultItemInlinePresentationSpec?.let { spec ->
                AutofillDatasetBuilder.InlinePresentationBuilder.tryCreate(
                    context = context,
                    spec = spec,
                    specs = request.inlinePresentationSpecs,
                    index = request.inlinePresentationSpecs?.indexOf(spec) ?: 0,
                    pendingIntent = pendingIntent,
                    title = context.getString(R.string.autofill_manual_entry_title),
                    subtitle = webDomain?.takeIf { it.isNotBlank() } ?: request.packageName,
                    icon = AutofillDatasetBuilder.InlinePresentationBuilder.createAppIcon(
                        context = context,
                        packageName = request.packageName
                    ),
                    contentDescription = context.getString(R.string.autofill_manual_entry_title)
                )
            }
        } else {
            null
        }

        return ManualEntryArtifacts(
            pendingIntent = pendingIntent,
            menuPresentation = menuPresentation,
            inlinePresentation = inlinePresentation,
        )
    }

    private fun extractWebDomain(uri: String?): String? =
        uri
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatchingObserved { Uri.parse(it).host }.getOrNull() }
            ?.takeIf { it.isNotBlank() }

    private fun attachSaveInfoIfNeeded(
        responseBuilder: FillResponse.Builder,
        request: AutofillRequest.Fillable,
    ) {
        // Bitwarden-compatible: skip save for login fields in compat mode because password
        // values can be masked and lead to low-quality save prompts.
        if (request.isCompatMode) return
        if (!request.partition.canPerformSaveRequest) return
        val requiredIds = request.partition.requiredSaveIds.toTypedArray()
        if (requiredIds.isEmpty()) return

        val saveInfoBuilder = SaveInfo.Builder(request.partition.saveType, requiredIds)
        val requiredSet = requiredIds.toSet()
        val optionalIds = request.partition.optionalSaveIds
            .filterNot { requiredSet.contains(it) }
            .toTypedArray()
        if (optionalIds.isNotEmpty()) {
            saveInfoBuilder.setOptionalIds(optionalIds)
        }
        responseBuilder.setSaveInfo(saveInfoBuilder.build())
    }
}

private data class ManualEntryArtifacts(
    val pendingIntent: PendingIntent,
    val menuPresentation: android.widget.RemoteViews,
    val inlinePresentation: InlinePresentation?,
)

private data class AutofillCallbackTarget(
    val autofillId: AutofillId,
    val hintName: String,
)

private fun buildLoginCallbackTargets(views: List<AutofillView>): List<AutofillCallbackTarget> {
    val seenIds = mutableSetOf<String>()
    return views.mapNotNull { view ->
        val target = when (view) {
            // 修复字段类型折叠：AutofillView.Login.Username 同时覆盖 USERNAME/EMAIL_ADDRESS/
            // PHONE_NUMBER 等账号类字段，必须用各自真实的 FieldHint 名称（view.data.hint.name），
            // 不能硬编码为 "USERNAME"，否则回调里邮箱/手机号框会被误标为 USERNAME，
            // 既降低填充精度，也可能让 WebView 框架回填错位。
            is AutofillView.Login.Username -> AutofillCallbackTarget(view.data.autofillId, view.data.hint.name)
            is AutofillView.Login.Password -> AutofillCallbackTarget(view.data.autofillId, view.data.hint.name)
            is AutofillView.Field -> null
        }
        target?.takeIf { seenIds.add(it.autofillId.toString()) }
    }
}

private fun buildAutofillHintNames(views: List<AutofillView>): List<String> {
    return views.map { view ->
        when (view) {
            // 与 buildLoginCallbackTargets 一致：保留真实 FieldHint 名称，避免账号类字段被统一折叠为 "USERNAME"
            is AutofillView.Login.Username -> view.data.hint.name
            is AutofillView.Login.Password -> view.data.hint.name
            is AutofillView.Field -> view.hint.name
        }
    }
}

private val FilledData.fillableAutofillIds: List<AutofillId>
    get() = originalPartition.views.map { it.data.autofillId }

