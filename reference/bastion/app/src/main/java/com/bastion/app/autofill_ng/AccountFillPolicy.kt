package com.bastion.app.autofill_ng

import com.bastion.app.logging.runCatchingObserved
import android.content.Context
import com.bastion.app.data.PasswordEntry
import com.bastion.app.security.SecurityManager

object AccountFillPolicy {
    fun resolveAccountIdentifier(
        entry: PasswordEntry,
        securityManager: SecurityManager
    ): String {
        return try {
            if (looksEncryptedPayload(entry.username)) {
                securityManager.decryptData(entry.username)
            } else {
                entry.username
            }
        } catch (_: Exception) {
            entry.username
        }
    }

    fun resolveAccountIdentifierForDisplay(entry: PasswordEntry): String {
        val candidate = entry.username.trim()
        if (candidate.isBlank() || looksEncryptedPayload(candidate)) {
            return ""
        }
        return candidate
    }

    fun shouldFillEmailWithAccount(context: Context): Boolean {
        // 改读 autofill 进程配置缓存（方案 B），避免填充热路径 runBlocking 读取 DataStore。
        return runCatchingObserved {
            AutofillConfigCache.separateUsernameAccountEnabled
        }.getOrDefault(false)
    }

    private fun looksEncryptedPayload(value: String): Boolean {
        return value.startsWith("V2|") ||
            value.startsWith("MDK|") ||
            value.startsWith("C2|") ||
            (value.contains("==") && value.length > 20)
    }
}



