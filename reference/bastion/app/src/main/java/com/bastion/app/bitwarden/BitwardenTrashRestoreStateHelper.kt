package com.bastion.app.bitwarden

import com.bastion.app.data.PasswordEntry
import com.bastion.app.data.SecureItem

enum class BitwardenRestoreQueueOutcome {
    NO_REMOTE_ACTION,
    CANCELED_PENDING_DELETE,
    ENQUEUED_REMOTE_RESTORE,
    REMOTE_RESTORE_ALREADY_QUEUED
}

object BitwardenTrashRestoreStateHelper {

    fun applyToPasswordEntry(
        candidate: PasswordEntry,
        restoreOutcome: BitwardenRestoreQueueOutcome
    ): PasswordEntry {
        return candidate.copy(
            bitwardenLocalModified = resolveLocalModified(
                hasBitwardenVault = candidate.bitwardenVaultId != null,
                hasRemoteCipher = !candidate.bitwardenCipherId.isNullOrBlank(),
                currentValue = candidate.bitwardenLocalModified,
                restoreOutcome = restoreOutcome
            )
        )
    }

    fun applyToSecureItem(
        candidate: SecureItem,
        restoreOutcome: BitwardenRestoreQueueOutcome
    ): SecureItem {
        // 绑定型验证器载体（REFERENCE、无独立 cipher）不参与独立上传：它的验证码数据
        // 本体随所属密码条目的 authenticatorKey → cipher.login.totp 同步。恢复它时若按
        // 「vaultId 非空但无 cipher = 待上传新条目」处理（hasRemoteCipher=false → 标脏），
        // 载体会永远卡在"待同步"状态——上传器会跳过无独立 cipher 的条目，没有任何
        // 环节能把这个脏标记清掉。密码条目恢复链路已负责远端数据，载体恢复即干净。
        val isReferenceCarrier = candidate.syncStatus == "REFERENCE" &&
            candidate.bitwardenCipherId.isNullOrBlank()
        val localModified = if (isReferenceCarrier) {
            false
        } else {
            resolveLocalModified(
                hasBitwardenVault = candidate.bitwardenVaultId != null,
                hasRemoteCipher = !candidate.bitwardenCipherId.isNullOrBlank(),
                currentValue = candidate.bitwardenLocalModified,
                restoreOutcome = restoreOutcome
            )
        }
        return candidate.copy(
            bitwardenLocalModified = localModified,
            syncStatus = resolveSyncStatus(
                currentValue = candidate.syncStatus,
                localModified = localModified
            )
        )
    }

    private fun resolveLocalModified(
        hasBitwardenVault: Boolean,
        hasRemoteCipher: Boolean,
        currentValue: Boolean,
        restoreOutcome: BitwardenRestoreQueueOutcome
    ): Boolean {
        if (!hasBitwardenVault) return false
        if (!hasRemoteCipher) return true

        return when (restoreOutcome) {
            BitwardenRestoreQueueOutcome.CANCELED_PENDING_DELETE -> false
            BitwardenRestoreQueueOutcome.ENQUEUED_REMOTE_RESTORE,
            BitwardenRestoreQueueOutcome.REMOTE_RESTORE_ALREADY_QUEUED -> true
            BitwardenRestoreQueueOutcome.NO_REMOTE_ACTION -> currentValue
        }
    }

    private fun resolveSyncStatus(
        currentValue: String,
        localModified: Boolean
    ): String {
        if (currentValue == "REFERENCE") return "REFERENCE"
        return if (localModified) "PENDING" else currentValue
    }
}
