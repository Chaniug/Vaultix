/*
 * Vaultix — app:passkey
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * Credential Provider（M3 最高优先批次）：Android 14+ 系统层凭据供应方。
 *
 * 根因（见 next-steps「Edge 填充失效根因」）：Chromium（Chrome/Edge）在 Android 14+/17 上
 * 取凭据优先走 Credential Manager；不注册 `CredentialProviderService`，系统根本不会向 Vaultix
 * 询问 passkey —— 表现为「网站登录时通行密钥不出现」。注册后即被系统识别。
 *
 * 行为：
 *  - onBeginGetCredentialRequest：
 *      * 通行密钥（public-key）：按请求 rpId 匹配已解锁库的 passkey → 列 PublicKeyCredentialEntry；
 *      * 密码（password）：列已解锁库的 Login 条目 → PasswordCredentialEntry（点击经 PasswordGetActivity
 *        取解密后的明文用户名/密码回灌 Credential Manager）；
 *      * 全部库锁定时，列一条「解锁 Vaultix」入口（按选项类型生成对应 Entry），其 PendingIntent 复用
 *        AutofillActivity 解锁链（与自动填充下拉的解锁同款：用户解锁后回 Edge 重新触发，届时库已解锁）。
 *  - onBeginCreateCredentialRequest：列 CreateEntry → 引导用户在 Vaultix 内创建并绑定到登录条目。
 *  - onClearCredentialStateRequest：Vaultix 不维护登录态，直接成功。
 *
 * 重要：注册 CredentialProviderService 后，Android 14+ 的 Credential Manager 会接管「登录字段」的
 * 自动填充（次要 autofill = com.android.credentialmanager），并同时向所有启用方询问 密码 + 通行密钥。
 * 因此本服务必须同时声明 password 能力（见 credential_provider.xml）：若只声明 public-key，系统只见
 * passkey、密码框为空、传统 VaultixAutofillService 在凭据字段上被绕过（即「启用 provider 后密码弹框消失」）。
 */
package io.vaultix.vaultix.passkey

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import androidx.annotation.RequiresApi
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginCreatePublicKeyCredentialRequest
import androidx.credentials.provider.BeginGetCredentialOption
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.BeginGetPasswordOption
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.credentials.provider.CreateEntry
import androidx.credentials.provider.CredentialEntry
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.PasswordCredentialEntry
import androidx.credentials.provider.PublicKeyCredentialEntry
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import dagger.hilt.android.AndroidEntryPoint
import io.vaultix.common.WebAuthn
import io.vaultix.domain.ItemRepository
import io.vaultix.domain.VaultRepository
import io.vaultix.model.VaultFido2Credential
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import io.vaultix.vaultix.R
import io.vaultix.vaultix.autofill.AutofillIntents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import javax.inject.Inject

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
@AndroidEntryPoint
class VaultixCredentialProviderService : CredentialProviderService() {

    @Inject
    lateinit var vaultRepository: VaultRepository

    @Inject
    lateinit var itemRepository: ItemRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBeginGetCredentialRequest(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>,
    ) {
        serviceScope.launch {
            runCatching { buildGetResponse(request) }
                .onSuccess { callback.onResult(it) }
                .onFailure { callback.onError(GetCredentialUnknownException(it.message)) }
        }
    }

    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>,
    ) {
        serviceScope.launch {
            runCatching { buildCreateResponse(request) }
                .onSuccess { callback.onResult(it) }
                .onFailure { callback.onError(CreateCredentialUnknownException(it.message)) }
        }
    }

    override fun onClearCredentialStateRequest(
        request: ProviderClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, ClearCredentialException>,
    ) {
        // Vaultix 不维护登录态（密钥只在内存，重启必锁），直接成功。
        callback.onResult(null)
    }

    // ===================== GET =====================

    private suspend fun buildGetResponse(request: BeginGetCredentialRequest): BeginGetCredentialResponse {
        val pkOptions = request.beginGetCredentialOptions.filterIsInstance<BeginGetPublicKeyCredentialOption>()
        val pwOptions = request.beginGetCredentialOptions.filterIsInstance<BeginGetPasswordOption>()
        if (pkOptions.isEmpty() && pwOptions.isEmpty()) return BeginGetCredentialResponse.Builder().build()

        val unlocked = vaultRepository.observeUnlockedVaultIds().first()
        val entries = mutableListOf<CredentialEntry>()

        if (unlocked.isEmpty()) {
            // 库锁定 → 复用 AutofillActivity 解锁链（引导用户在 Vaultix 解锁，回来重新触发）。
            // 取首个可用选项生成对应类型的「解锁」入口。
            val opt = (pkOptions.firstOrNull() ?: pwOptions.firstOrNull())
                ?: return BeginGetCredentialResponse.Builder().build()
            entries += unlockEntry(opt)
            return BeginGetCredentialResponse.Builder().setCredentialEntries(entries).build()
        }

        for (option in pwOptions) {
            entries += passwordEntries(option, unlocked)
        }
        for (option in pkOptions) {
            val matched = resolvePasskeys(option, unlocked)
            for (m in matched) {
                entries += publicKeyEntry(option, m)
            }
        }
        return BeginGetCredentialResponse.Builder().setCredentialEntries(entries).build()
    }

    /** 已解锁库的 Login 条目 → PasswordCredentialEntry（不预填明文，点击经 PasswordGetActivity 取密回灌）。 */
    private suspend fun passwordEntries(
        option: BeginGetPasswordOption,
        unlocked: Set<String>,
    ): List<CredentialEntry> {
        val result = mutableListOf<CredentialEntry>()
        for (vaultId in unlocked) {
            val items = runCatching { itemRepository.observeItems(vaultId).first() }.getOrDefault(emptyList())
            for (item in items) {
                if (!isUsablePasswordItem(item)) continue
                result += passwordEntry(option, vaultId, item)
            }
        }
        return result
    }

    private fun isUsablePasswordItem(item: VaultItem): Boolean {
        if (item.type != VaultItemType.Login) return false
        return item.username.isNotBlank() || item.password.isNotBlank()
    }

    private fun passwordEntry(
        option: BeginGetPasswordOption,
        vaultId: String,
        item: VaultItem,
    ): CredentialEntry {
        val intent = PasskeyProviderIntents.passwordGetIntent(this, vaultId, item.id)
        val pendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_PASSWORD_CP_BASE + item.id.hashCode(),
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val username = item.username.ifBlank { item.title }
        return PasswordCredentialEntry.Builder(this, username, pendingIntent, option)
            .setDisplayName(item.title.ifBlank { item.username })
            .setIcon(Icon.createWithResource(this, R.drawable.ic_passkey))
            .build()
    }

    /** 跨已解锁库扁平化所有登录条目的 fido2，按 rpId（+ allowCredentials）匹配。 */
    private suspend fun resolvePasskeys(
        option: BeginGetPublicKeyCredentialOption,
        unlocked: Set<String>,
    ): List<PasskeyMatch> {
        val json = runCatching { JSONObject(option.requestJson) }.getOrNull() ?: return emptyList()
        val rpId = json.optString("rpId", "").lowercase()
        if (rpId.isBlank()) return emptyList()
        val allowed = parseAllowedCredentialIds(json)

        val result = mutableListOf<PasskeyMatch>()
        for (vaultId in unlocked) {
            val items = runCatching { itemRepository.observeItems(vaultId).first() }.getOrDefault(emptyList())
            for (item in items) {
                if (item.type != VaultItemType.Login) continue
                for (cred in item.fido2Credentials) {
                    if (cred.rpId.equals(rpId, ignoreCase = true) &&
                        (allowed.isEmpty() || allowed.any { idMatches(cred.credentialId, it) })
                    ) {
                        result += PasskeyMatch(vaultId, item.id, cred, item.title)
                    }
                }
            }
        }
        return result
    }

    /** allowCredentials：[{type,id,transports}] 中的 id（base64url）。 */
    private fun parseAllowedCredentialIds(json: JSONObject): List<String> {
        val arr = json.optJSONArray("allowCredentials") ?: return emptyList()
        val ids = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }?.let { ids += it }
        }
        return ids
    }

    /** 容错比较 storedId 与允许列表 id（两种 base64 形态都试，转原始字节比较）。 */
    private fun idMatches(storedId: String, allowed: String): Boolean {
        val a = runCatching { WebAuthn.decodeBase64UrlOrStandard(storedId) }
            .getOrNull() ?: return false
        val b = runCatching { WebAuthn.decodeBase64UrlOrStandard(allowed) }
            .getOrNull() ?: return false
        return a.contentEquals(b)
    }

    private fun publicKeyEntry(option: BeginGetPublicKeyCredentialOption, m: PasskeyMatch): CredentialEntry {
        val intent = PasskeyProviderIntents.getIntent(
            context = this,
            requestJson = option.requestJson,
            vaultId = m.vaultId,
            itemId = m.itemId,
            credentialId = m.credential.credentialId,
            rpId = m.credential.rpId,
            clientDataHash = option.clientDataHash,
        )
        val pendingIntent = PendingIntent.getActivity(
            this,
            m.credential.credentialId.hashCode(),
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val username = m.credential.userName.ifBlank { m.loginTitle.ifBlank { m.credential.rpName } }
        return PublicKeyCredentialEntry.Builder(this, username, pendingIntent, option)
            .setDisplayName(m.credential.rpName.ifBlank { m.credential.rpId })
            .setIcon(Icon.createWithResource(this, R.drawable.ic_passkey))
            .build()
    }

    /** 库锁定时的「解锁 Vaultix」入口：复用 AutofillActivity 解锁链；按选项类型生成对应 Entry。 */
    private fun unlockEntry(option: BeginGetCredentialOption): CredentialEntry {
        val intent = AutofillIntents.create(
            context = this,
            mode = AutofillIntents.MODE_UNLOCK,
            title = getString(R.string.credential_unlock_title),
            subtitle = getString(R.string.credential_unlock_subtitle),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pendingIntent = AutofillIntents.pending(
            context = this,
            intent = intent,
            requestCode = REQUEST_UNLOCK_CP,
        )
        val title = getString(R.string.credential_unlock_title)
        val subtitle = getString(R.string.credential_unlock_subtitle)
        val icon = Icon.createWithResource(this, R.drawable.ic_passkey)
        return when (option) {
            is BeginGetPasswordOption -> {
                val entryBuilder = PasswordCredentialEntry.Builder(this, title, pendingIntent, option)
                entryBuilder.setDisplayName(subtitle).setIcon(icon).build()
            }
            is BeginGetPublicKeyCredentialOption -> {
                val entryBuilder = PublicKeyCredentialEntry.Builder(this, title, pendingIntent, option)
                entryBuilder.setDisplayName(subtitle).setIcon(icon).build()
            }
            else -> throw IllegalArgumentException("Unsupported credential option type")
        }
    }

    // ===================== CREATE =====================

    private suspend fun buildCreateResponse(request: BeginCreateCredentialRequest): BeginCreateCredentialResponse {
        if (request !is BeginCreatePublicKeyCredentialRequest) {
            throw UnsupportedOperationException("only public-key supported")
        }
        val json = JSONObject(request.requestJson)
        val rp = json.optJSONObject("rp")
        val rpId = rp?.optString("id") ?: json.optString("rpId", "")
        val rpName = rp?.optString("name") ?: rpId
        val user = json.optJSONObject("user")
        val userName = user?.optString("name") ?: ""
        val userDisplayName = user?.optString("displayName") ?: userName

        val intent = PasskeyProviderIntents.createIntent(
            context = this,
            requestJson = request.requestJson,
            rpId = rpId,
            rpName = rpName,
            userName = userName,
            userDisplayName = userDisplayName,
        )
        val pendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_CREATE_CP,
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val entry = CreateEntry.Builder(
            getString(R.string.passkey_create_for, rpName.ifBlank { rpId }),
            pendingIntent,
        )
            .setDescription(userName.ifBlank { rpName.ifBlank { getString(R.string.app_name) } })
            .setIcon(Icon.createWithResource(this, R.drawable.ic_passkey))
            .build()
        return BeginCreateCredentialResponse.Builder().addCreateEntry(entry).build()
    }

    /** 一次 GET 解析出的可匹配凭证（含定位信息，供 Activity 回取私钥）。 */
    private data class PasskeyMatch(
        val vaultId: String,
        val itemId: String,
        val credential: VaultFido2Credential,
        val loginTitle: String,
    )

    private companion object {
        const val REQUEST_UNLOCK_CP = 2101
        const val REQUEST_CREATE_CP = 2102
        const val REQUEST_PASSWORD_CP_BASE = 2200
    }
}
