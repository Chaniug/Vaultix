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
import androidx.credentials.CreateCredentialRequest
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

    /** 注册（通行密钥创建）请求（客户端侧 [CreateCredentialRequest]）。 */
    @Volatile
    var createCredentialRequest: CreateCredentialRequest? = null
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
     *
     * ⚠️ **读取请用 [consumeUserPreVerified]**，不要直接读本字段 ——
     * 本标记是**流程内一次性**的（见 `.ai/decisions/通行密钥UV豁免-定稿.md` §3 I1），
     * 直接读不会消费它，会导致同一标记被多次使用（退化成"验证过就一直免验证"）。
     * 本字段保留为 `private set` 仅供 [markUserPreVerified] / [clear] 维护。
     */
    @Volatile
    var isUserPreVerified: Boolean = false
        private set

    /**
     * ★ 标记「本次凭据流程内刚完成过设备验证」（唯一合法写入点）。
     *
     * 目前唯一调用者是 `AutofillActivity`：**库锁定**时用户为完成 CP 认证动作而做的
     * 那次生物识别，成功且解封成功后才可置位（决策文档 §3 I2 / I6）。
     *
     * ⚠️ **绝不可**由「库当前已解锁」这类**状态**反推置位（I2）——
     * 解锁可能来自主密码、可能发生在很久以前，与"本次断言前的验证"不是一回事。
     */
    fun markUserPreVerified() {
        isUserPreVerified = true
    }

    /**
     * ★ 消费「已预验证」标记：返回当前值并**立即清零**（一次性语义）。
     *
     * 调用方（`PasskeyGetActivity` / `PasskeyCreateActivity`）拿到 `true` 后仍有义务
     * 自己校验**库仍未上锁**（决策文档 §3 I5）——本方法只负责"读过即失效"。
     */
    fun consumeUserPreVerified(): Boolean {
        val value = isUserPreVerified
        isUserPreVerified = false
        return value
    }

    fun setCreateCredentialRequest(request: CreateCredentialRequest, preVerified: Boolean) {
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
