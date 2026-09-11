/*
 * Vaultix — app:passkey
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * 凭据提供商 Intent 解析工具。
 *
 * 逐句对齐 Bitwarden Android 官方客户端
 * `data/credentials/util/CredentialProviderIntentUtils.kt`（GPL-3.0，Copyright Bitwarden Inc.）：
 * 把系统递进来的 Intent 解析成「四选一」的请求类型判断 + 「是否已由系统完成设备验证」。
 *
 * 为什么要单独一层：`PendingIntentHandler.retrieveXxx()` 在请求类型不匹配时会返回 null，
 * 而「哪个字段有值」正是判定当前流程类型的唯一依据。把这套判定集中在此，避免
 * 各处 Activity 各写一遍、写漏一种。
 */

package io.vaultix.vaultix.passkey

import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.PendingIntentHandler
import androidx.credentials.provider.ProviderGetCredentialRequest

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
object CredentialProviderIntentUtils {

    /** 解锁流程中已完成设备验证时，由解锁侧写入此 extra。 */
    const val EXTRA_KEY_UV_PERFORMED_DURING_UNLOCK =
        "androidx.credentials.provider.extra.UV_PERFORMED_DURING_UNLOCK"

    /** 拿「注册通行密钥」请求；不是该类型时返回 null。 */
    fun Intent.getCreateCredentialRequestOrNull(): BeginCreateCredentialRequest? = runCatching {
        PendingIntentHandler.retrieveProviderCreateCredentialRequest(this)
    }.getOrNull()?.callingRequest

    /** 拿「使用通行密钥（断言）」请求；不是该类型时返回 null。 */
    fun Intent.getProviderGetCredentialRequestOrNull(): ProviderGetCredentialRequest? = runCatching {
        PendingIntentHandler.retrieveProviderGetCredentialRequest(this)
    }.getOrNull()

    /** 拿「候选列表」请求；不是该类型时返回 null。 */
    fun Intent.getBeginGetCredentialRequestOrNull(): BeginGetCredentialRequest? = runCatching {
        PendingIntentHandler.retrieveBeginGetCredentialRequest(this)
    }.getOrNull()

    /**
     * 系统是否已在入口处完成设备验证。
     *
     * 对齐 Bitwarden：
     * ```
     * val systemRequest = intent.getProviderGetCredentialRequestOrNull()
     * val isUserPreVerified = systemRequest
     *     ?.biometricPromptResult
     *     ?.isSuccessful
     *     ?: intent.getBooleanExtra(EXTRA_KEY_UV_PERFORMED_DURING_UNLOCK, false)
     * ```
     *
     * 第一条来源是 Android 15+ 的 entry 级 BiometricPromptData（Vaultix 当前不挂，
     * 见 `VaultixCredentialProviderService` 的说明）；第二条来源是本 App 自己的解锁流程
     * （用户在解锁时刚做过生物识别，无需再弹一次）。
     */
    fun Intent.isUserPreVerified(): Boolean {
        val fromSystem = getProviderGetCredentialRequestOrNull()?.biometricPromptResult?.isSuccessful
        return fromSystem ?: getBooleanExtra(EXTRA_KEY_UV_PERFORMED_DURING_UNLOCK, false)
    }
}
