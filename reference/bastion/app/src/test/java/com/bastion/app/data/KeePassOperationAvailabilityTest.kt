package com.bastion.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeePassOperationAvailabilityTest {

    @Test
    fun localDatabasesAreAlwaysWritable() {
        val database = LocalKeePassDatabase(
            name = "Local",
            filePath = "local.kdbx",
            sourceType = KeePassDatabaseSourceType.LOCAL_DOCUMENT_URI,
            lastSyncStatus = KeePassSyncStatus.FAILED
        )

        assertTrue(database.writeOperationAvailability().canOperate)
    }

    @Test
    fun remoteDatabaseWithoutLocalCopyRequiresManualRefresh() {
        val database = remoteDatabase(
            workingCopyPath = null,
            cacheCopyPath = null,
            isOfflineAvailable = false,
            lastSyncStatus = KeePassSyncStatus.IN_SYNC
        )

        val availability = database.writeOperationAvailability()

        assertFalse(availability.canOperate)
        assertEquals(KeePassOperationBlockReason.NEEDS_REFRESH, availability.reason)
    }

    @Test
    fun remoteDatabaseAllowsWritableSyncedLocalCopies() {
        val inSync = remoteDatabase(lastSyncStatus = KeePassSyncStatus.IN_SYNC)
        val pendingUpload = remoteDatabase(lastSyncStatus = KeePassSyncStatus.PENDING_UPLOAD)

        assertTrue(inSync.writeOperationAvailability().canOperate)
        assertTrue(pendingUpload.writeOperationAvailability().canOperate)
    }

    @Test
    fun remoteDatabaseBlocksUnsafeSyncStates() {
        val blockedStates = mapOf(
            KeePassSyncStatus.LOCAL_ONLY to KeePassOperationBlockReason.NEEDS_REFRESH,
            KeePassSyncStatus.REMOTE_CHANGED to KeePassOperationBlockReason.NEEDS_REFRESH,
            KeePassSyncStatus.CONFLICT to KeePassOperationBlockReason.CONFLICT
        )

        blockedStates.forEach { (status, reason) ->
            val availability = remoteDatabase(lastSyncStatus = status).writeOperationAvailability()

            assertFalse("Expected $status to block KeePass writes", availability.canOperate)
            assertEquals(reason, availability.reason)
        }
    }

    @Test
    fun freshSyncingRemoteStillBlocksToAvoidOverwritingRemote() {
        // 新鲜(未过期)的 SYNCING 远端必须阻塞，防止覆盖正在进行的远端同步。
        val availability = remoteDatabase(lastSyncStatus = KeePassSyncStatus.SYNCING).writeOperationAvailability()

        assertFalse("Fresh SYNCING remote must block writes", availability.canOperate)
        assertEquals(KeePassOperationBlockReason.SYNCING, availability.reason)
    }

    @Test
    fun staleSyncingAndFailedRemotesAllowLocalWritesToAvoidDeadlock() {
        // 反死锁设计：SYNCING 且已有本地副本但同步状态已陈旧(>10min)，或同步 FAILED 时，
        // 允许本地写入，避免远端卡死导致用户无法在本地继续修改数据库。
        val staleSyncing = remoteDatabase(
            lastSyncStatus = KeePassSyncStatus.SYNCING,
            lastSyncStateUpdatedAt = 0L
        ).writeOperationAvailability()
        assertTrue("Stale SYNCING remote with local copy must allow local writes", staleSyncing.canOperate)

        val failed = remoteDatabase(lastSyncStatus = KeePassSyncStatus.FAILED).writeOperationAvailability()
        assertTrue("FAILED remote must allow local writes to avoid deadlock", failed.canOperate)
    }

    private fun remoteDatabase(
        workingCopyPath: String? = "working.kdbx",
        cacheCopyPath: String? = null,
        isOfflineAvailable: Boolean = true,
        lastSyncStateUpdatedAt: Long = System.currentTimeMillis(),
        lastSyncStatus: KeePassSyncStatus
    ): LocalKeePassDatabase {
        return LocalKeePassDatabase(
            name = "Remote",
            filePath = "remote.kdbx",
            sourceType = KeePassDatabaseSourceType.REMOTE_ONEDRIVE,
            workingCopyPath = workingCopyPath,
            cacheCopyPath = cacheCopyPath,
            isOfflineAvailable = isOfflineAvailable,
            lastSyncStateUpdatedAt = lastSyncStateUpdatedAt,
            lastSyncStatus = lastSyncStatus
        )
    }
}
