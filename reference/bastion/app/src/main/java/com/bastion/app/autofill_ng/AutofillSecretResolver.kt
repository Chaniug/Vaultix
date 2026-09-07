package com.bastion.app.autofill_ng

import com.bastion.app.logging.runCatchingObserved
import android.util.Base64
import android.util.Log
import com.bastion.app.autofill_ng.core.AutofillLogger
import com.bastion.app.security.SecurityManager

/**
 * Autofill-only secret resolver.
 *
 * Never returns encrypted payload as fillable password.
 */
object AutofillSecretResolver {
    private const val TAG = "AutofillSecret"
    private const val DATA_PREFIX_V2 = "V2|"
    private const val DATA_PREFIX_MDK = "MDK|"
    private const val DATA_PREFIX_COMPAT = "C2|"

    fun decryptPasswordOrNull(
        securityManager: SecurityManager,
        encryptedOrPlain: String,
        logTag: String = TAG,
    ): String? {
        if (encryptedOrPlain.isBlank()) return ""

        val decrypted = runCatchingObserved {
            securityManager.decryptData(encryptedOrPlain)
        }.onFailure { e ->
            Log.w(logTag, "Password decrypt failed for autofill entry", e)
            AutofillLogger.w(
                "SECRET",
                "Password decrypt failed for autofill entry",
                metadata = mapOf(
                    "logTag" to logTag,
                    "error" to (e.message ?: e::class.java.simpleName),
                    "looksEncrypted" to looksEncryptedPayload(encryptedOrPlain)
                )
            )
        }.getOrNull() ?: return if (looksEncryptedPayload(encryptedOrPlain)) {
            AutofillLogger.w(
                "SECRET",
                "Encrypted payload unresolved, returning null (may trigger auth-callback demotion)",
                metadata = mapOf("logTag" to logTag)
            )
            null
        } else {
            encryptedOrPlain
        }

        if (decrypted == encryptedOrPlain && looksEncryptedPayload(encryptedOrPlain)) {
            Log.w(logTag, "Password decrypt unresolved, skipping encrypted payload")
            AutofillLogger.w(
                "SECRET",
                "Password decrypt unresolved (decrypted==encrypted), skipping encrypted payload",
                metadata = mapOf("logTag" to logTag)
            )
            return null
        }

        return decrypted
    }

    private fun looksEncryptedPayload(value: String): Boolean {
        if (
            value.startsWith(DATA_PREFIX_V2) ||
            value.startsWith(DATA_PREFIX_MDK) ||
            value.startsWith(DATA_PREFIX_COMPAT)
        ) {
            return true
        }

        // Legacy V1 payload format: Base64(12-byte IV + encrypted bytes + 16-byte GCM tag)
        // 探测性解码: 对明文字段必然抛 "bad base-64", 属预期失败, 用静默 runCatching（不要 runCatchingObserved）
        val decoded = runCatching {
            Base64.decode(value, Base64.DEFAULT)
        }.getOrNull() ?: return false

        return decoded.size > 28
    }
}


