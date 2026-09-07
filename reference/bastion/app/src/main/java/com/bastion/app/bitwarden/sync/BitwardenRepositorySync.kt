package com.bastion.app.bitwarden.sync

import com.bastion.app.bitwarden.repository.BitwardenRepository
import com.bastion.app.sync.SyncDiagnostics
import com.bastion.app.sync.SyncError
import com.bastion.app.sync.SyncErrorKind
import com.bastion.app.sync.SyncMode
import com.bastion.app.sync.SyncNetworkPolicy
import com.bastion.app.sync.SyncPriority
import com.bastion.app.sync.SyncRequest
import com.bastion.app.sync.SyncTarget
import com.bastion.app.sync.SyncTaskAwaitResult
import com.bastion.app.sync.SyncTaskRunner
import com.bastion.app.sync.SyncTrigger

sealed class BitwardenCoordinatedSyncResult {
    data class Completed(val result: BitwardenRepository.SyncResult) : BitwardenCoordinatedSyncResult()
    data object Merged : BitwardenCoordinatedSyncResult()
    data class Skipped(val reason: String) : BitwardenCoordinatedSyncResult()
    data class Blocked(val error: SyncError) : BitwardenCoordinatedSyncResult()
    data class Canceled(val reason: String?) : BitwardenCoordinatedSyncResult()
    data class Failed(val error: Exception) : BitwardenCoordinatedSyncResult()
}

suspend fun BitwardenRepository.syncViaCoordinator(
    vaultId: Long,
    requestIdPrefix: String,
    trigger: SyncTrigger,
    pullAfterPush: Boolean = true,
    // 强制全量：用户手动刷新 / 重试 / 恢复时绕过 revision-date 预检跳过，确保拿到最新数据。
    forced: Boolean = false,
    priority: SyncPriority = SyncPriority.forTrigger(trigger),
    mode: SyncMode = SyncMode.BACKGROUND,
    // 「仅 Wi-Fi 同步」已移除：一律要求有网络即可，不区分 Wi-Fi/移动
    networkPolicy: SyncNetworkPolicy = SyncNetworkPolicy.REQUIRED,
    requiresUnlockedTarget: Boolean = true
): BitwardenCoordinatedSyncResult {
    val request = SyncRequest(
        requestId = SyncDiagnostics.nextTaskId(requestIdPrefix),
        target = SyncTarget.BitwardenVault(vaultId),
        trigger = trigger,
        createdAtMillis = System.currentTimeMillis(),
        priority = priority,
        mode = mode,
        networkPolicy = networkPolicy,
        requiresUnlockedTarget = requiresUnlockedTarget
    )

    @Suppress("DEPRECATION")
    return when (val result = SyncTaskRunner.requestAndAwait(request) { sync(vaultId, pullAfterPush, forced) }) {
        is SyncTaskAwaitResult.Completed -> BitwardenCoordinatedSyncResult.Completed(result.value)
        is SyncTaskAwaitResult.Merged -> BitwardenCoordinatedSyncResult.Merged
        is SyncTaskAwaitResult.Skipped -> BitwardenCoordinatedSyncResult.Skipped(result.reason)
        is SyncTaskAwaitResult.Blocked -> BitwardenCoordinatedSyncResult.Blocked(result.error)
        is SyncTaskAwaitResult.Canceled -> BitwardenCoordinatedSyncResult.Canceled(result.reason)
        is SyncTaskAwaitResult.Failed -> BitwardenCoordinatedSyncResult.Failed(result.error)
    }
}

suspend fun BitwardenRepository.syncForUserVisibleRequest(
    vaultId: Long,
    requestIdPrefix: String
): BitwardenRepository.SyncResult {
    return syncViaCoordinator(
        vaultId = vaultId,
        requestIdPrefix = requestIdPrefix,
        trigger = SyncTrigger.MANUAL,
        // 用户主动触发的可见同步：强制全量，绕过 revision-date 预检跳过
        forced = true,
        priority = SyncPriority.MANUAL,
        mode = SyncMode.FOREGROUND
    ).toRepositorySyncResultForUi()
}

fun BitwardenCoordinatedSyncResult.toRepositorySyncResultForUi(): BitwardenRepository.SyncResult {
    return when (this) {
        is BitwardenCoordinatedSyncResult.Completed -> result
        BitwardenCoordinatedSyncResult.Merged -> emptyBitwardenSyncSuccess()
        is BitwardenCoordinatedSyncResult.Skipped -> emptyBitwardenSyncSuccess()
        is BitwardenCoordinatedSyncResult.Blocked -> {
            BitwardenRepository.SyncResult.Error(error.redactedMessage ?: error.kind.name)
        }
        is BitwardenCoordinatedSyncResult.Canceled -> {
            BitwardenRepository.SyncResult.Error(reason ?: "同步被取消")
        }
        is BitwardenCoordinatedSyncResult.Failed -> {
            BitwardenRepository.SyncResult.Error(error.message ?: "同步失败")
        }
    }
}

fun SyncError.toBitwardenBlockReason(): SyncBlockReason {
    return when (kind) {
        SyncErrorKind.NETWORK_UNAVAILABLE -> SyncBlockReason.NETWORK_UNAVAILABLE
        SyncErrorKind.WIFI_REQUIRED -> SyncBlockReason.WIFI_REQUIRED
        SyncErrorKind.AUTH_REQUIRED -> SyncBlockReason.AUTH_REQUIRED
        SyncErrorKind.TARGET_LOCKED -> SyncBlockReason.VAULT_LOCKED
        else -> SyncBlockReason.NETWORK_UNAVAILABLE
    }
}

private fun emptyBitwardenSyncSuccess(): BitwardenRepository.SyncResult.Success {
    return BitwardenRepository.SyncResult.Success(
        appliedChangeCount = 0,
        remoteAddedCount = 0,
        remoteUpdatedCount = 0,
        uploadedCount = 0,
        deletedCount = 0,
        availableOfflineCount = 0,
        conflictCount = 0,
        uploadFailedCount = 0,
        skippedDueToLocalDirtyCount = 0
    )
}
