package io.vaultix.vaultix.ui.common

import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher

/**
 * 生物识别 / 设备 PIN 认证封装（本地快速解锁专用）。
 *
 * - API 30+：强生物识别 **或** 设备凭据（PIN/图案/密码，系统凭据面板）；
 *   低版本仅强生物识别（Keystore 绑定限制，见 LocalUnlockKeyStore），
 *   需负按钮提供「取消」；
 * - cipher 必须是本次 Keystore 认证会话的加密/解封 Cipher（已 init）；
 * - 认证成功回调携带同一 cipher，调用方随即完成 wrap/unwrap。
 */
class BiometricPrompter(
    private val activity: FragmentActivity,
) {
    fun authenticate(
        cipher: Cipher,
        title: String,
        subtitle: String? = null,
        cancelText: String? = null,
        onSuccess: (Cipher) -> Unit,
        onError: (message: String, isCancelled: Boolean) -> Unit,
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess(result.cryptoObject?.cipher ?: cipher)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // 「用户/系统取消」一律安静收起：都是正常的放弃路径，弹错误反而打扰。
                // ⚠️ ERROR_CANCELED 必须算进来：它是**系统**取消（宿主被切走、弹窗被
                // 提前撤下），在进程刚冷启动/重启后的首帧发起认证时很常见；
                // 不算取消就会把调用方留在 submitting 卡死态（按钮全灰、转圈不停）。
                val cancelled = errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_CANCELED
                onError(errString.toString(), cancelled)
            }
            // onAuthenticationFailed（指纹不匹配）：保持对话框可重试，不回调
        }
        val prompt = BiometricPrompt(activity, executor, callback)

        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
        subtitle?.let { builder.setSubtitle(it) }

        if (deviceCredentialAllowed()) {
            // 含 DEVICE_CREDENTIAL 时系统凭据面板自带返回，禁止设置负按钮
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
        } else {
            builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            if (cancelText != null) builder.setNegativeButtonText(cancelText)
        }

        prompt.authenticate(builder.build(), BiometricPrompt.CryptoObject(cipher))
    }

    companion object {
        /** API 30+ 支持设备凭据绑定 Keystore 的 user-auth key。 */
        fun deviceCredentialAllowed(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    }
}

/**
 * 设备是否具备可用的认证方式（强生物识别，或 API 30+ 的设备凭据）。
 * 供「解锁页按钮 / 启用横幅」前置判断；BIOMETRIC_ERROR_NONE_ENROLLED 等
 * 返回 false（UI 不显示入口，设置页给出解释文案）。
 */
fun deviceCanAuthenticate(context: android.content.Context): Boolean {
    val manager = BiometricManager.from(context)
    val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        manager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        )
    } else {
        // API 26-29：仅有参重载不存在，用无参（即强生物识别）
        @Suppress("DEPRECATION")
        manager.canAuthenticate()
    }
    return result == BiometricManager.BIOMETRIC_SUCCESS
}

/** 当前 Compose 宿主是否可承载 BiometricPrompt（MainActivity 已改 FragmentActivity）。 */
@Composable
fun rememberFragmentActivity(): FragmentActivity? =
    LocalContext.current as? FragmentActivity
