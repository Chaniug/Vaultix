/*
 * Vaultix — app:passkey
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * Credential Provider 的**透明中转 Activity**（trampoline）。
 *
 * 逐句对齐 Bitwarden Android 官方客户端
 * `ui/credentials/CredentialProviderActivity.kt`（GPL-3.0，Copyright Bitwarden Inc.）：
 *
 * ```kotlin
 * // Bitwarden 原文（结构）
 * override fun onCreate(savedInstanceState: Bundle?) {
 *     super.onCreate(savedInstanceState)
 *     val intent = intent.validate()
 *     viewModel.trySendAction(CredentialProviderViewModel.Action.ReceiveFirstIntent(intent))
 *     launchMainActivityForResult()
 * }
 * private fun launchMainActivityForResult() {
 *     registerForActivityResult(StartActivityForResult()) { result ->
 *         setResult(result.resultCode, result.data)
 *         finish()
 *     }.launch(intent)
 * }
 * ```
 *
 * 为什么必须是这样一个**没有 UI 的 Activity**：
 *  1. Credential Manager 的候选点击通过 `PendingIntent.getActivity` 拉起，必须落在
 *     一个 Activity 上；但它不该有自己的界面（否则用户看到"闪一下"）；
 *  2. 真正的解锁 / 确认界面在主界面（`MainActivity` 的根导航）里，本 Activity 只负责
 *     「把请求交接过去，再把结果原样带回」；
 *  3. `exported="false"` —— 只有本 App 自己（或系统经 PendingIntent 授权）能拉起它，
 *     外部 App 无法借此注入请求；
 *  4. 结果必须**原样**透传（`setResult(result.resultCode, result.data)`），
 *     因为 Credential Manager 的响应体（`setGetCredentialResponse` 等）是由主界面
 *     填好的，本 Activity 只是搬运工。
 *
 * ⚠️ 与 Bitwarden 的差异（Vaultix 的既有结构）：
 * Vaultix 没有把 assert 逻辑放进主界面的 ViewModel，而是保留
 * `PasskeyGetActivity` / `PasswordGetActivity` / `PasskeyCreateActivity` 三个
 * 带确认卡片的 Activity。因此本 trampoline 的职责收敛为：
 *  - 打点「本次启动源于 autofill / 凭据流程」（供 [VaultixApplication] 的进程创建豁免）；
 *  - 把请求存进 [CredentialProviderRequestManager]（不经 Intent 传凭据）；
 *  - **库锁定时的解锁路由**：起一个解锁意图，让用户在同一个流程里完成解锁后重试。
 */

package io.vaultix.vaultix.passkey

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.fragment.app.FragmentActivity
import io.vaultix.vaultix.VaultixApplication
import io.vaultix.vaultix.autofill.AutofillIntents
import io.vaultix.vaultix.autofill.AutofillLogger
import io.vaultix.vaultix.passkey.CredentialProviderIntentUtils.EXTRA_KEY_UV_PERFORMED_DURING_UNLOCK
import io.vaultix.vaultix.passkey.CredentialProviderIntentUtils.getBeginGetCredentialRequestOrNull
import io.vaultix.vaultix.passkey.CredentialProviderIntentUtils.getCreateCredentialRequestOrNull
import io.vaultix.vaultix.passkey.CredentialProviderIntentUtils.getProviderGetCredentialRequestOrNull
import io.vaultix.vaultix.passkey.CredentialProviderIntentUtils.isUserPreVerified

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class CredentialProviderActivity : FragmentActivity() {

    /**
     * 把主界面的结果**原样**透传回 Credential Manager。
     *
     * `registerForActivityResult` 必须在 `onCreate` 之前（字段初始化时）注册，
     * 否则在 Activity 已 STARTED 之后再注册会抛 IllegalStateException。
     */
    private val mainActivityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        setResult(result.resultCode, result.data)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ⚠️ **最早的时机**标记本次进程创建源于凭据流程。
        // 见 VaultixApplication.markCreatedForAutofill 的 KDoc：必须在 super.onCreate
        // 之后、任何可能触发进程创建判定之前完成置位。
        (application as? VaultixApplication)?.markCreatedForAutofill()

        // 请求分类：三种类型互斥（注册 / 断言 / 候选列表）。
        val createRequest = intent.getCreateCredentialRequestOrNull()
        val getRequest = intent.getProviderGetCredentialRequestOrNull()
        val beginGetRequest = intent.getBeginGetCredentialRequestOrNull()
        val preVerified = intent.isUserPreVerified()

        AutofillLogger.d(
            "CP-TM onCreate create=${createRequest != null} get=${getRequest != null} " +
                "beginGet=${beginGetRequest != null} preVerified=$preVerified",
        )

        // 请求存进进程内单例（**不经 Intent 传凭据**，见 CredentialProviderRequestManager 的说明）。
        when {
            createRequest != null -> CredentialProviderRequestManager
                .setCreateCredentialRequest(createRequest, preVerified)

            getRequest != null -> CredentialProviderRequestManager
                .setGetCredentialRequest(getRequest, preVerified)

            beginGetRequest != null -> CredentialProviderRequestManager
                .setBeginGetCredentialRequest(beginGetRequest)

            else -> {
                // 系统递来的 Intent 无法识别：明确失败，避免主界面白起一次。
                AutofillLogger.d("CP-TM unrecognized request → cancel")
                setResult(Activity.RESULT_CANCELED)
                finish()
                return
            }
        }

        launchMainActivityForResult()
    }

    /**
     * 拉主界面并把结果透传。
     *
     * 对齐 Bitwarden：带 `FLAG_ACTIVITY_CLEAR_TOP or FLAG_ACTIVITY_SINGLE_TOP`，
     * 避免在已有主界面实例之上再叠一个。
     */
    private fun launchMainActivityForResult() {
        val intent = Intent(this, io.vaultix.vaultix.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_KEY_UV_PERFORMED_DURING_UNLOCK, CredentialProviderRequestManager.isUserPreVerified)
            .putExtra(AutofillIntents.EXTRA_CREDENTIAL_FLOW, true)
        mainActivityLauncher.launch(intent)
    }

    override fun finish() {
        // 流程结束即清空持有状态（不长期驻留请求对象）。
        // 注意：清空放在 finish 而非 onCreate，因为主界面可能还没读完请求。
        CredentialProviderRequestManager.clear()
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
