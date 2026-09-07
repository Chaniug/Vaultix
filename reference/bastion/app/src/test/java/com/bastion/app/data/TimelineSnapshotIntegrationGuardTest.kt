package com.bastion.app.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineSnapshotIntegrationGuardTest {

    @Test
    fun roomDatabaseRegistersVersion73SnapshotMigration() {
        val source = projectFile(
            "app/src/main/java/com/bastion/app/data/PasswordDatabase.kt"
        ).readText()

        assertTrue(source.contains("TimelineVersionSnapshot::class"))
        assertTrue(source.contains("version = 77"))
        assertTrue(source.contains("Migration(73, 74)"))
        assertTrue(source.contains("Migration(74, 75)"))
        assertTrue(source.contains("Migration(75, 76)"))
        assertTrue(source.contains("Migration(76, 77)"))
        assertTrue(source.contains("CREATE TABLE IF NOT EXISTS timeline_version_snapshots"))
        assertTrue(source.contains("MIGRATION_73_74"))
        assertTrue(source.contains("MIGRATION_74_75"))
        assertTrue(source.contains("MIGRATION_75_76"))
        assertTrue(source.contains("MIGRATION_76_77"))
    }

    @Test
    fun snapshotPayloadNeverUsesCompatibilityFallbackWhenMasterPasswordExists() {
        val source = projectFile(
            "app/src/main/java/com/bastion/app/security/SecurityManager.kt"
        ).readText()

        val encryptBlock = source.substringAfter("fun encryptTimelineSnapshot")
            .substringBefore("fun decryptTimelineSnapshot")
        assertTrue(encryptBlock.contains("isVaultRuntimeUnlocked()"))
        assertTrue(encryptBlock.contains("DATA_PREFIX_MDK"))
        assertFalse(encryptBlock.contains("encryptDataCompat(data)") &&
            !encryptBlock.contains("if (!isMasterPasswordSet())"))

        val decryptBlock = source.substringAfter("fun decryptTimelineSnapshot")
            .substringBefore("fun looksLikeBastionCiphertext")
        assertTrue(decryptBlock.contains("isVaultRuntimeUnlocked()"))
        assertTrue(decryptBlock.contains("DATA_PREFIX_MDK"))
        assertTrue(decryptBlock.contains("DATA_PREFIX_COMPAT"))
    }

    @Test
    fun ordinaryTimelineBackupDoesNotExportEncryptedSnapshots() {
        val source = projectFile(
            "app/src/main/java/com/bastion/app/utils/WebDavHelper.kt"
        ).readText()
        val timelineBackupBlock = source.substringAfter("val allLogs = operationLogDao.getAllLogsSync()")
            .substringBefore("OperationLogger.logWebDavUpload")

        assertTrue(timelineBackupBlock.contains("OperationLogBackupEntry"))
        assertFalse(timelineBackupBlock.contains("timelineVersionSnapshotDao"))
        assertFalse(timelineBackupBlock.contains("encryptedChangesJson"))
    }

    @Test
    fun loggerSeparatesRedactedAuditFromEncryptedSnapshot() {
        val source = projectFile(
            "app/src/main/java/com/bastion/app/utils/OperationLogger.kt"
        ).readText()

        assertTrue(source.contains("val sanitizedChanges = sanitizeChanges(itemType, changes)"))
        assertTrue(source.contains("snapshotChanges: List<FieldChange> = changes"))
        assertTrue(source.contains("encryptTimelineSnapshot"))
        assertTrue(source.contains("Skipped incomplete encrypted timeline snapshot"))
    }

    @Test
    fun restoreAndCopyRefuseStaleOrKeePassBoundVersions() {
        val source = projectFile(
            "app/src/main/java/com/bastion/app/viewmodel/TimelineViewModel.kt"
        ).readText()

        assertTrue(source.contains("if (current != expectedValue(change)) return false"))
        assertTrue(source.contains("matchesCurrentEncryptedSnapshotState(log, snapshotChanges)"))
        assertTrue(source.contains("if (entry.keepassDatabaseId != null) return false"))
        assertTrue(source.contains("if (item.keepassDatabaseId != null) return false"))
        assertTrue(source.contains("bitwardenVaultId = null"))
        assertTrue(source.contains("keepassDatabaseId = null"))
        assertTrue(source.contains("replicaGroupId = null"))
    }

    private fun projectFile(relativePath: String): File {
        val candidates = mutableListOf<File>()
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            candidates += File(dir, relativePath)
            dir = dir.parentFile
        }
        return candidates.firstOrNull { it.isFile }
            ?: error("Unable to find project file: $relativePath")
    }
}
