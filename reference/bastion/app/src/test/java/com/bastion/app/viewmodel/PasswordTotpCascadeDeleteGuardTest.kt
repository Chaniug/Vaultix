package com.bastion.app.viewmodel

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归守卫：删除密码条目必须级联清理其绑定型验证器在 secure_items 中的载体记录。
 *
 * 真机复现 bug（2026-09，荣耀/安卓 17）：删除带验证码的密码条目后，
 * 1. 验证器界面（Bitwarden 分组）仍残留该验证码；
 * 2. Bitwarden 服务器同步报冲突（墓碑密码 bitwardenLocalModified 与远端 revision 冲突）；
 * 3. 残留验证码需手动再删一次才消失。
 *
 * 根因：绑定型 TOTP 在 secure_items 有 SYNC_STATUS_REFERENCE 记录（boundPasswordId
 * 指向密码条目），删除密码条目的所有链路均未清理它；且手动删除该残留时
 * TotpViewModel.deleteTotpItem 会对已删除的墓碑密码条目再次清空 authenticatorKey
 * 并标记 bitwardenLocalModified，进一步触发同步冲突。
 */
class PasswordTotpCascadeDeleteGuardTest {

    @Test
    fun everyPasswordDeletePathCascadesToBoundTotpItems() {
        val source = projectFile(
            "app/src/main/java/com/bastion/app/viewmodel/PasswordViewModel.kt"
        ).readText()

        val cascadeFunction = source
            .substringAfter("private suspend fun cascadeDeleteBoundTotpItems(")
            .substringBefore("fun toggleFavorite(")

        assertTrue(
            "Cascade delete helper must exist and only target bound TOTPs without their own Bitwarden cipher.",
            cascadeFunction.contains("bitwardenCipherId.isNullOrBlank()") &&
                cascadeFunction.contains("boundPasswordId")
        )
        assertTrue(
            "Cascade delete must align with the password delete semantics (trash vs permanent).",
            cascadeFunction.contains("softDelete") &&
                cascadeFunction.contains("softDeleteItem") &&
                cascadeFunction.contains("deleteItem")
        )

        // Bitwarden 排队删除路径（单个 + 批量共用）
        val bitwardenQueuedDelete = source
            .substringAfter("private suspend fun handleBitwardenQueuedDelete(")
            .substringBefore("private suspend fun applyLocalDeleteBatch(")
        assertTrue(
            "Bitwarden queued delete must cascade-delete bound TOTP carriers, otherwise orphan authenticators stay visible.",
            bitwardenQueuedDelete.contains("cascadeDeleteBoundTotpItems(listOf(entry), softDelete = true)")
        )

        // 本地批量删除路径（回收站 + 永久删除两个分支）
        val localBatchDelete = source
            .substringAfter("private suspend fun applyLocalDeleteBatch(")
            .substringBefore("private suspend fun moveEntryToTrash(")
        assertTrue(
            "Local batch delete must cascade-delete bound TOTP carriers in both trash and permanent branches.",
            localBatchDelete.contains("cascadeDeleteBoundTotpItems(originalEntries, softDelete = true)") &&
                localBatchDelete.contains("cascadeDeleteBoundTotpItems(originalEntries, softDelete = false)")
        )

        // 单条本地软删路径
        val trashDelete = source
            .substringAfter("private suspend fun moveEntryToTrashLocalOnly(")
            .substringBefore("private fun syncKeePassTrashDelete(")
        assertTrue(
            "Single-entry trash delete must cascade-delete bound TOTP carriers.",
            trashDelete.contains("cascadeDeleteBoundTotpItems(listOf(entry), softDelete = true)")
        )

        // 单条永久删除路径
        val permanentDelete = source
            .substringAfter("private suspend fun permanentlyDeleteEntryLocalOnly(")
            .substringBefore("private suspend fun cascadeDeleteBoundTotpItems(")
        assertTrue(
            "Permanent delete must cascade-delete bound TOTP carriers.",
            permanentDelete.contains("cascadeDeleteBoundTotpItems(listOf(entry), softDelete = false)")
        )
    }

    @Test
    fun manualTotpDeleteMustNotTouchTombstonedPasswords() {
        val source = projectFile(
            "app/src/main/java/com/bastion/app/viewmodel/TotpViewModel.kt"
        ).readText()
        val deleteBody = source
            .substringAfter("fun deleteTotpItem(")
            .substringBefore("Virtual TOTP items are derived")

        assertTrue(
            "Deleting a residual bound TOTP must skip the authenticator-key clearing for tombstoned passwords; mutating a deleted entry re-marks bitwardenLocalModified and reproduces CONCURRENT_EDIT sync conflicts.",
            deleteBody.contains("password != null && !password.isDeleted")
        )
    }

    @Test
    fun downlinkMergeMustSkipTombstonedLocalEntries() {
        val source = projectFile(
            "app/src/main/java/com/bastion/app/bitwarden/service/BitwardenSyncService.kt"
        ).readText()
        val mergeBody = source
            .substringAfter("private suspend fun syncCipher(")
            .substringBefore("private fun cipherToPasswordEntry(")

        assertTrue(
            "Downlink merge must skip tombstoned local entries; otherwise pending-delete items get resurrected or reported as conflicts while the delete queue retries.",
            mergeBody.contains("existingEntry.isDeleted") &&
                mergeBody.contains("tombstoned")
        )
    }

    @Test
    fun trashRestoreOfPasswordsCascadesBackToBoundTotpItems() {
        val source = projectFile(
            "app/src/main/java/com/bastion/app/viewmodel/TrashViewModel.kt"
        ).readText()

        assertTrue(
            "Restoring a password must cascade-restore its bound TOTP carriers, otherwise the authenticator is permanently lost after a delete/restore round-trip.",
            source.contains("cascadeRestoreBoundTotpItems(data)") &&
                source.contains("private suspend fun cascadeRestoreBoundTotpItems(")
        )

        val rollbackBody = source
            .substringAfter("private suspend fun rollbackLocalRestore(")
            .substringBefore("private fun needsKeepassRestore(")
        assertTrue(
            "Rolling back a failed password restore must re-trash bound TOTP carriers, otherwise the orphan-authenticator bug reappears via the KeePass batch-restore failure path.",
            rollbackBody.contains("cascadeDeleteBoundTotpItemsForRollback(rollbackEntry)")
        )
    }

    @Test
    fun trashRestoreMustClearDirtyFlagOnBoundTotpCarriers() {
        val source = projectFile(
            "app/src/main/java/com/bastion/app/viewmodel/TrashViewModel.kt"
        ).readText()

        val restoreBody = source
            .substringAfter("private suspend fun cascadeRestoreBoundTotpItems(")
            .substringBefore("private fun needsKeepassRestore(")

        assertTrue(
            "Cascade restore must explicitly clear bitwardenLocalModified on bound TOTP carriers: they have no independent cipher, so no uploader pass can ever clear the flag and the authenticator card would stay 'pending sync' forever.",
            restoreBody.contains("bitwardenLocalModified = false")
        )
        assertTrue(
            "Cascade restore must preserve the REFERENCE sync status: it means 'synced together with the owning password entry', not an independent pending upload.",
            restoreBody.contains("REFERENCE")
        )
    }

    @Test
    fun restoreStateHelperMustNotMarkReferenceCarriersDirty() {
        val source = projectFile(
            "app/src/main/java/com/bastion/app/bitwarden/BitwardenTrashRestoreStateHelper.kt"
        ).readText()

        val secureItemBody = source
            .substringAfter("fun applyToSecureItem(")
            .substringBefore("private fun resolveLocalModified(")

        assertTrue(
            "BitwardenTrashRestoreStateHelper must special-case REFERENCE carriers (no independent cipher is the normal state for bound TOTPs, not a pending upload). Marking them dirty produces a permanent 'not synced' badge on the authenticator card.",
            secureItemBody.contains("isReferenceCarrier") &&
                secureItemBody.contains("syncStatus == \"REFERENCE\"") &&
                secureItemBody.contains("bitwardenCipherId.isNullOrBlank()")
        )
    }

    private fun projectFile(relativePath: String): File {
        val candidates = mutableListOf<File>()
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            candidates += File(dir, relativePath)
            dir = dir.parentFile
        }

        return candidates.firstOrNull { it.isFile }
            ?: error("Unable to find project file: $relativePath from ${System.getProperty("user.dir")}")
    }
}
