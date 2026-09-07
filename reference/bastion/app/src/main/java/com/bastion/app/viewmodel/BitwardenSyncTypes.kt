package com.bastion.app.viewmodel

sealed class BitwardenRecoveryResult {
    object Success : BitwardenRecoveryResult()
    data class Error(val message: String) : BitwardenRecoveryResult()
    data class EmptyVaultBlocked(val reason: String) : BitwardenRecoveryResult()
}

data class BitwardenSyncRawHistoryItem(
    val id: Long,
    val operation: String,
    val endpoint: String,
    val payloadSource: String,
    val payloadDigest: String,
    val responseCode: Int?,
    val success: Boolean,
    val capturedAt: Long,
    val payload: String?,
    val preview: BitwardenSyncSnapshotPreview? = null
)
