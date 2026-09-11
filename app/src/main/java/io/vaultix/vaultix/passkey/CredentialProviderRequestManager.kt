/*
 * Vaultix — app:passkey
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * Credential Provider 请求的单例持有者。
 *
 * 逐句对齐 Bitwarden Android 官方客户端
 * `data/credentials/manager/CredentialProviderRequestManagerImpl.kt` 的语义：
 * 凭据提供商流程横跨「系统回调 → 透明中转 Activity → 主界面 UI」多个组件，
 * 请求对象在这些组件之间传递。
 *
 * ⚠️ **绝不把凭据（或含凭据的请求）经 Intent extras 传给 exported Activity**：
 * Intent extras 会跨进程可见、可能被系统/第三方读取、也可能被写进 bug report。
 * 这里用进程内单例持有，Intent 只传一个「这是个凭据请求」的信号。
 *
 * 安全约定：
 * - 只存**请求**（不含任何已解密的密钥材料）；
 * - 用完即清（`clear()`），不长期驻留；
 * - `isUserPreVerified` 表示「系统已完成设备验证」（来自 BiometricPromptData 的
 *   `biometricPromptResult.isSuccessful`，或解锁时已验证的标记）。
 */

package io.vaultix.vaultix.passkey

import androidx.annotation.RequiresApi
import android.os.Build
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.ProviderGetCredentialRequest

/**
 * 当前正在处理的凭据提供商请求（同一时刻只可能有一个）。
 *
 * 三种类型互斥，对应 Bitwarden 的
 * `getCreateCredentialRequestOrNull` / `getFido2AssertionRequestOrNull` /
 * `getGetCredentialsRequestOrNull`。
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
object CredentialProviderRequestManager {

    /** 注册（通行密钥创建）请求。 */
    @Volatile
    var createCredentialRequest: BeginCreateCredentialRequest? = null
        private set

    /** 断言（通行密钥使用）请求。 */
    @Volatile
    var getCredentialRequest: ProviderGetCredentialRequest? = null
        private set

    /** 候选列表请求（`onBeginGetCredentialRequest` 的原始请求）。 */
    @Volatile
    var beginGetCredentialRequest: BeginGetCredentialRequest? = null
        private set

    /**
     * 系统是否已在入口处完成设备验证。
     *
     * 对齐 Bitwarden `CredentialProviderIntentUtils`：
     * ```
     * isUserPreVerified = systemRequest.biometricPromptResult?.isSuccessful
     *     ?: intent.getBooleanExtra(EXTRA_KEY_UV_PERFORMED_DURING_UNLOCK, false)
     * ```
     * 为真时跳过多余的生物识别弹窗。
     */
    @Volatile
    var isUserPreVerified: Boolean = false
        private set

    fun setCreateCredentialRequest(request: BeginCreateCredentialRequest, preVerified: Boolean) {
        clear()
        createCredentialRequest = request
        isUserPreVerified = preVerified
    }

    fun setGetCredentialRequest(request: ProviderGetCredentialRequest, preVerified: Boolean) {
        clear()
        getCredentialRequest = request
        isUserPreVerified = preVerified
    }

    fun setBeginGetCredentialRequest(request: BeginGetCredentialRequest) {
        clear()
        beginGetCredentialRequest = request
    }

    /** 清空全部持有状态（流程结束 / 取消时必须调用）。 */
    fun clear() {
        createCredentialRequest = null
        getCredentialRequest = null
        beginGetCredentialRequest = null
        isUserPreVerified = false
    }
}
