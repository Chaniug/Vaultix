package com.bastion.app.keepass

import android.util.Log
import com.bastion.app.data.PasswordEntry
import com.bastion.app.repository.KeePassCompatibilityBridge
import com.bastion.app.utils.KeePassCustomFieldData

class KeePassPasswordUpdateExecutor(
    private val bridge: KeePassCompatibilityBridge?
) {
    suspend fun syncUpdatedEntry(
        existingEntry: PasswordEntry?,
        updatedEntry: PasswordEntry,
        resolvePassword: (PasswordEntry) -> String,
        customFields: List<KeePassCustomFieldData> = emptyList()
    ): Result<Unit> {
        return syncUpdatedEntry(
            existingEntry = existingEntry,
            updatedEntry = updatedEntry,
            resolvePassword = resolvePassword,
            customFields = customFields,
            persistUpdate = null
        )
    }

    suspend fun syncUpdatedEntry(
        existingEntry: PasswordEntry?,
        updatedEntry: PasswordEntry,
        resolvePassword: (PasswordEntry) -> String,
        customFields: List<KeePassCustomFieldData> = emptyList(),
        persistUpdate: (suspend (PasswordEntry) -> Unit)?
    ): Result<Unit> {
        val keepassBridge = bridge
        if (keepassBridge == null) {
            persistUpdate?.invoke(updatedEntry)
            return Result.success(Unit)
        }
        val oldKeepassId = existingEntry?.keepassDatabaseId
        val newKeepassId = updatedEntry.keepassDatabaseId

        if (newKeepassId != null) {
            val updateResult = keepassBridge.updateLegacyPasswordEntry(
                databaseId = newKeepassId,
                entry = updatedEntry,
                resolvePassword = resolvePassword,
                customFields = customFields
            )
            if (updateResult.isFailure) {
                Log.e(TAG, "KeePass update failed: ${updateResult.exceptionOrNull()?.message}")
                return Result.failure(
                    updateResult.exceptionOrNull()
                        ?: IllegalStateException("KeePass update failed")
                )
            }
        }

        persistUpdate?.invoke(updatedEntry)

        if (oldKeepassId != null && oldKeepassId != newKeepassId) {
            val deleteResult = keepassBridge.deleteKeePassPasswordEntries(
                databaseId = oldKeepassId,
                entries = listOf(existingEntry)
            )
            if (deleteResult.isFailure) {
                Log.e(TAG, "KeePass delete failed: ${deleteResult.exceptionOrNull()?.message}")
                return Result.failure(
                    deleteResult.exceptionOrNull()
                        ?: IllegalStateException("KeePass delete failed")
                )
            }
        }

        return Result.success(Unit)
    }

    private companion object {
        const val TAG = "KeePassPasswordUpdate"
    }
}
