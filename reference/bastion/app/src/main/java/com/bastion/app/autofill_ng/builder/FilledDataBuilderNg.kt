package com.bastion.app.autofill_ng.builder

import com.bastion.app.logging.runCatchingObserved
import android.content.Context
import android.view.autofill.AutofillValue
import android.widget.inline.InlinePresentationSpec
import com.bastion.app.autofill_ng.AccountFillPolicy
import com.bastion.app.autofill_ng.model.AutofillCipher
import com.bastion.app.autofill_ng.model.AutofillRequest
import com.bastion.app.autofill_ng.model.AutofillPartition
import com.bastion.app.autofill_ng.model.AutofillView
import com.bastion.app.autofill_ng.model.FilledData
import com.bastion.app.autofill_ng.model.FilledItem
import com.bastion.app.autofill_ng.model.FilledPartition
import com.bastion.app.autofill_ng.model.toAutofillCipherLogin
import com.bastion.app.autofill_ng.AutofillSecretResolver
import com.bastion.app.data.PasswordEntry
import com.bastion.app.security.SecurityManager
import com.bastion.app.security.SessionManager
import com.bastion.app.autofill_ng.AutofillConfigCache
import com.bastion.app.autofill_ng.core.AutofillLogger

// 不在此处截断条目数量，让系统键盘自行控制横向滚动显示所有条目。
private const val MAX_FILLED_PARTITIONS_COUNT = Int.MAX_VALUE
private const val MAX_INLINE_SUGGESTION_COUNT = Int.MAX_VALUE

class FilledDataBuilderNg(
    private val context: Context,
    private val securityManager: SecurityManager,
) {

    private fun resolveAutoLockTimeoutForAutofill(): Int {
        // 改读 autofill 进程配置缓存（方案 B），避免填充热路径 runBlocking 读取 DataStore。
        return runCatchingObserved {
            val autoLockMinutes = AutofillConfigCache.autoLockMinutes
            SessionManager.updateAutoLockTimeout(autoLockMinutes)
            autoLockMinutes
        }.onFailure { error ->
            android.util.Log.w(
                "FilledDataBuilderNg",
                "Failed to sync auto-lock timeout for autofill: ${error.message}"
            )
        }.getOrDefault(5)
    }

    fun build(
        request: AutofillRequest.Fillable,
        passwords: List<PasswordEntry>,
        requireAuthentication: Boolean = true,
        forceDatasetAuthForWeb: Boolean = false,
    ): FilledData {
        val autoLockMinutes = resolveAutoLockTimeoutForAutofill()
        // 用户设置“永不过期/不锁定”(autoLockMinutes == -1)时，只要密钥材料当前可读即视为已解锁，
        // 避免自动填充独立进程因会话状态不可见而每次填充都强制二次解锁。
        val isVaultLocked = if (autoLockMinutes == -1) {
            !securityManager.canAccessVaultMaterialNow()
        } else {
            !securityManager.canAccessVaultNowStrict(context, autoLockMinutes)
        }
        val maxCipherInlineSuggestionsCount = (request.maxInlineSuggestionsCount - 1)
            .coerceAtMost(MAX_INLINE_SUGGESTION_COUNT)

        var inlineSuggestionsAdded = 0

        fun getCipherInlinePresentationOrNull(): InlinePresentationSpec? =
            if (inlineSuggestionsAdded < maxCipherInlineSuggestionsCount) {
                request.inlinePresentationSpecs?.getOrLastOrNull(inlineSuggestionsAdded)
            } else {
                null
            }?.also { inlineSuggestionsAdded += 1 }

        val loginViews = when (val partition = request.partition) {
            is AutofillPartition.Login -> partition.views
            is AutofillPartition.Generic -> partition.views.filterIsInstance<AutofillView.Login>()
        }

        val filledPartitions = if (loginViews.isEmpty()) {
            emptyList()
        } else {
            val ciphers = passwords.mapNotNull { entry ->
                buildCipherForResponse(
                    entry = entry,
                    fallbackWebsite = request.uri.orEmpty(),
                    requireAuthentication = requireAuthentication,
                    isVaultLocked = isVaultLocked
                )
            }

            ciphers
                .map { autofillCipher ->
                    fillLoginPartition(
                        autofillCipher = autofillCipher,
                        autofillViews = loginViews,
                        inlinePresentationSpec = getCipherInlinePresentationOrNull(),
                        requiresAuthentication = requireAuthentication && isVaultLocked,
                        forceDatasetAuthForWeb = forceDatasetAuthForWeb,
                    )
                }
                .filter { it.filledItems.isNotEmpty() }
                .take(MAX_FILLED_PARTITIONS_COUNT)
        }

        val vaultItemInlinePresentationSpec = request
            .inlinePresentationSpecs
            ?.getOrLastOrNull(inlineSuggestionsAdded)

        return FilledData(
            filledPartitions = filledPartitions,
            ignoreAutofillIds = request.ignoreAutofillIds,
            originalPartition = request.partition,
            uri = request.uri,
            vaultItemInlinePresentationSpec = vaultItemInlinePresentationSpec,
            isVaultLocked = isVaultLocked
        )
    }

    private fun fillLoginPartition(
        autofillCipher: AutofillCipher.Login,
        autofillViews: List<AutofillView.Login>,
        inlinePresentationSpec: InlinePresentationSpec?,
        requiresAuthentication: Boolean,
        forceDatasetAuthForWeb: Boolean = false,
    ): FilledPartition {
        if (requiresAuthentication) {
            // 认证回灌路径：所有字段进 filledItems（value=null 占位），callback 重新解密写值。
            return FilledPartition(
                autofillCipher = autofillCipher,
                filledItems = autofillViews.map { autofillView ->
                    FilledItem(
                        autofillId = autofillView.data.autofillId,
                        value = null
                    )
                },
                inlinePresentationSpec = inlinePresentationSpec,
                requiresAuthentication = true
            )
        }

        // 非认证直填路径：对每个字段取解密后的值。
        // 关键修复（对齐 Bitwarden）：密码字段值为空（解密失败/为空）时不能静默丢弃，
        // 否则 dataset 只剩用户名 → 框架只填用户名、密码框空白（半填充）。
        // 此时把整个 partition 降级为认证回灌（AutofillCipherCallbackActivity 重新解密 +
        // WebView a11y 兜底），保证用户名+密码同批写入。
        val collected = autofillViews.map { autofillView ->
            val value = when (autofillView) {
                is AutofillView.Login.Username -> autofillCipher.username
                is AutofillView.Login.Password -> autofillCipher.password
            }
            Triple(autofillView, autofillView.data.autofillId, value)
        }

        val hasPasswordField = autofillViews.any { it is AutofillView.Login.Password }
        val passwordValueBlank = collected
            .filter { it.first is AutofillView.Login.Password }
            .all { it.third.isNullOrBlank() }

        if (hasPasswordField && passwordValueBlank) {
            // 密码缺失 → 降级认证回灌：全部字段 value=null 占位，走 setAuthentication + callback。
            AutofillLogger.w(
                "FILLING",
                "Login partition demoted to auth callback: password value blank, " +
                    "direct-fill would cause half-fill (username only)",
                metadata = mapOf(
                    "cipherId" to (autofillCipher.cipherId ?: "none"),
                    "fieldCount" to autofillViews.size,
                    "reason" to "password_decrypt_failed_or_blank"
                )
            )
            return FilledPartition(
                autofillCipher = autofillCipher,
                filledItems = collected.map { (_, autofillId, _) ->
                    FilledItem(autofillId = autofillId, value = null)
                },
                inlinePresentationSpec = inlinePresentationSpec,
                requiresAuthentication = true
            )
        }

        // 密码有值（或无密码字段）：正常直填。对齐 Bitwarden：保留全部字段进 filledItems，
        // 即使某字段 value 为空也保留（value=null 占位），保证 dataset 字段集 = 可填目标，
        // 避免框架因字段缺失而降级或错位。
        val filledItems = collected.map { (autofillView, autofillId, value) ->
            if (value.isNullOrBlank()) {
                FilledItem(autofillId = autofillId, value = null)
            } else {
                FilledItem(autofillId = autofillId, value = AutofillValue.forText(value))
            }
        }
        AutofillLogger.d(
            "FILLING",
            "Login partition direct-fill built",
            metadata = mapOf(
                "cipherId" to (autofillCipher.cipherId ?: "none"),
                "fieldCount" to autofillViews.size,
                "passwordValuePresent" to collected
                    .filter { it.first is AutofillView.Login.Password }
                    .any { !it.third.isNullOrBlank() },
                "forceDatasetAuth" to forceDatasetAuthForWeb
            )
        )
        return FilledPartition(
            autofillCipher = autofillCipher,
            filledItems = filledItems,
            inlinePresentationSpec = inlinePresentationSpec,
            requiresAuthentication = false,
            forceDatasetAuth = forceDatasetAuthForWeb,
        )
    }

    private fun buildCipherForResponse(
        entry: PasswordEntry,
        fallbackWebsite: String,
        requireAuthentication: Boolean,
        isVaultLocked: Boolean,
    ): AutofillCipher.Login? {
        if (requireAuthentication && isVaultLocked) {
            val subtitleValue = AccountFillPolicy
                .resolveAccountIdentifierForDisplay(entry)
                .takeIf { it.isNotBlank() }
                ?: entry.website.takeIf { it.isNotBlank() }
                ?: fallbackWebsite.takeIf { it.isNotBlank() }
                ?: entry.title
            val websiteValue = entry.website.takeIf { it.isNotBlank() } ?: fallbackWebsite
            val titleValue = entry.title
                .takeIf { it.isNotBlank() }
                ?: subtitleValue.takeIf { it.isNotBlank() }
                ?: websiteValue.takeIf { it.isNotBlank() }
                ?: "Credential"
            return AutofillCipher.Login(
                cipherId = entry.id.toString(),
                name = titleValue,
                subtitle = subtitleValue,
                username = "",
                password = "",
                website = websiteValue,
                appPackageName = entry.appPackageName.takeIf { it.isNotBlank() }
            )
        }

        val usernameValue = decryptForAutofill(entry.username)
        val passwordValue = decryptForAutofill(entry.password)
        if (usernameValue.isNullOrBlank() && passwordValue.isNullOrBlank()) {
            return null
        }
        return entry.toAutofillCipherLogin(
            fallbackWebsite = fallbackWebsite,
            usernameValue = usernameValue.orEmpty(),
            passwordValue = passwordValue.orEmpty()
        )
    }

    private fun decryptForAutofill(value: String): String? {
        if (value.isBlank()) return ""
        return AutofillSecretResolver.decryptPasswordOrNull(
            securityManager = securityManager,
            encryptedOrPlain = value,
            logTag = "FilledDataBuilderNg",
        )
    }
}

private fun <T> List<T>.getOrLastOrNull(index: Int): T? =
    getOrNull(index) ?: lastOrNull()
