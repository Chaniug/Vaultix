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
 * ⚠️ 但**本服务只声明 public-key 能力**（见 credential_provider.xml）。原因（2026-09-11 `f815ab2`）：
 * 一旦声明 password 能力，Chromium 系会把**密码请求**也路由到 CP 通道、绕过传统 Autofill 框架，
 * 而 CP 密码分支任一环节失败就返回空 ⇒ 密码框什么都不弹、老 AutofillService 同时被绕过（两条路全废）。
 * 因此密码填充回归 [io.vaultix.vaultix.autofill.VaultixAutofillService.onFillRequest]，两路各司其职。
 */
package io.vaultix.vaultix.passkey

import android.app.PendingIntent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import androidx.annotation.RequiresApi
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.AuthenticationAction
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginCreatePublicKeyCredentialRequest
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
import io.vaultix.domain.ItemRepository
import io.vaultix.domain.VaultRepository
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import io.vaultix.vaultix.R
import io.vaultix.vaultix.autofill.AutofillIntents
import io.vaultix.vaultix.autofill.AutofillLogger
import io.vaultix.vaultix.autofill.engine.AutofillCredentialMapper
import io.vaultix.vaultix.autofill.match.BitwardenLikeAutofillMatcher
import io.vaultix.vaultix.autofill.match.UriMatcher
import io.vaultix.vaultix.session.ActiveVaultStore
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

    @Inject
    lateinit var activeVaultStore: ActiveVaultStore

    /**
     * 候选列表的构建（2026-09-17 从本类抽出）。
     *
     * 抽出的理由见 [CredentialProviderEntryBuilder] 的 KDoc：解锁动作的收尾
     * **必须自己把刷新后的候选塞进结果 Intent**（系统不会回来重查），
     * 而那段构建逻辑得让解锁 Activity 也能用 ⇒ 两处共用同一份。
     */
    @Inject
    lateinit var entryBuilder: CredentialProviderEntryBuilder

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
        // 对齐 Bitwarden `processGetCredentialRequest`：必须接收取消信号，否则系统
        // 在用户切走 / 请求超时后仍等待回调，表现为「列表长时间空白后消失」。
        val job = serviceScope.launch {
            runCatching { entryBuilder.buildGetResponse(request) }
                .onSuccess {
                    log("GET ok entries=${it.credentialEntries.size} actions=${it.authenticationActions.size}")
                    callback.onResult(it)
                }
                .onFailure {
                    // 现场排障关键：任何异常都会让整张凭据列表为空（浏览器表现为「毫无反应」）。
                    log("GET failed: ${it.javaClass.simpleName}: ${it.message}")
                    callback.onError(GetCredentialUnknownException(it.message))
                }
        }
        cancellationSignal.setOnCancelListener {
            log("GET cancelled by system")
            job.cancel()
            callback.onError(GetCredentialCancellationException("Cancelled"))
        }
    }

    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>,
    ) {
        val job = serviceScope.launch {
            runCatching { buildCreateResponse(request) }
                .onSuccess { callback.onResult(it) }
                .onFailure { callback.onError(CreateCredentialUnknownException(it.message)) }
        }
        cancellationSignal.setOnCancelListener {
            log("CREATE cancelled by system")
            job.cancel()
            callback.onError(CreateCredentialCancellationException("Cancelled"))
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
    // ⚠️ **密码填充不走 CP 通道**（credential_provider.xml 已移除 TYPE_PASSWORD_CREDENTIAL
    // 能力声明，对齐 Bastion）。Chromium 系浏览器的密码请求由系统路由到 Autofill 框架
    // （VaultixAutofillService.onFillRequest），本服务只负责通行密钥（passkey）。
    // pwOptions 分支保留供将来重新启用，但当前系统不会下发 password 请求。

    //  2. **无可用 cipher**：Vaultix 的库密钥只在内存（VaultSessionManager），没有绑定到
    //     单个条目、且经 setUserAuthenticationRequired 的 Keystore 密钥可作 CryptoObject。
    // 因此设备验证统一由条目点击后的 Activity（PasskeyGetActivity / PasswordGetActivity）
    // 承担 —— 与 Vaultix 既有行为一致，且不受 ROM 差异影响。

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
        // 浏览器流程下系统给了这份哈希（浏览器那份 clientDataJSON 的 SHA-256）；
        // 原生流程为 null。注册侧只拿它做自检对比（attestation = none，无签名）。
        // 注：`BeginCreatePublicKeyCredentialRequest` 自带 `clientDataHash`（与 GET 侧的
        // `BeginGetPublicKeyCredentialOption.clientDataHash` 对称），无需下转型到
        // `CreatePublicKeyCredentialRequest`——后者是调用方侧的类，provider 侧拿不到。
        val clientDataHash = request.clientDataHash

        val intent = PasskeyProviderIntents.createIntent(
            context = this,
            requestJson = request.requestJson,
            rpId = rpId,
            rpName = rpName,
            userName = userName,
            userDisplayName = userDisplayName,
            clientDataHash = clientDataHash,
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

    /**
     * 现场排障日志（仅 debug 构建；只记选项类型/数量等非敏感元数据，禁记条目内容）。
     *
     * ⚠️ 统一走 [AutofillLogger] 的 `VaultixAutofill` tag（15 字符）：Android 的 log tag
     * 上限为 23 字符，而本类名派生的 tag 长达 27 字符——真机实测该 tag 的日志**一条都进不了
     * logcat**（疑似 ROM 按长度丢弃），导致 CP 全链路"零日志"、排障只能靠猜。
     */
    private fun log(message: String) = AutofillLogger.d("CP $message")

    private companion object {
        const val REQUEST_CREATE_CP = 2102
    }
}
