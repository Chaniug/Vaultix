package com.bastion.app.bitwarden

import com.bastion.app.data.PasswordEntry
import com.bastion.app.data.SecureItem

object BitwardenHistoricalRepairStateHelper {
    fun applyToSecureItem(
        candidate: SecureItem,
        shouldQueueRemoteRewrite: Boolean
    ): SecureItem {
        if (!shouldQueueRemoteRewrite || candidate.bitwardenCipherId.isNullOrBlank()) {
            return candidate
        }
        return candidate.copy(
            bitwardenLocalModified = true,
            syncStatus = if (candidate.syncStatus == "REFERENCE") "REFERENCE" else "PENDING"
        )
    }

    fun applyToPasswordEntry(
        candidate: PasswordEntry,
        shouldQueueRemoteRewrite: Boolean
    ): PasswordEntry {
        if (!shouldQueueRemoteRewrite || candidate.bitwardenCipherId.isNullOrBlank()) {
            return candidate
        }
        return candidate.copy(bitwardenLocalModified = true)
    }
}
