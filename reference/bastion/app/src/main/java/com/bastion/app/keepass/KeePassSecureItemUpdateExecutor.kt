package com.bastion.app.keepass

import android.util.Log
import com.bastion.app.data.SecureItem
import com.bastion.app.repository.KeePassCompatibilityBridge

class KeePassSecureItemUpdateExecutor(
    private val bridge: KeePassCompatibilityBridge?
) {
    suspend fun syncUpdatedItem(
        existingItem: SecureItem?,
        updatedItem: SecureItem
    ): Result<Unit> {
        return syncUpdatedItem(
            existingItem = existingItem,
            updatedItem = updatedItem,
            persistUpdate = null
        )
    }

    suspend fun syncUpdatedItem(
        existingItem: SecureItem?,
        updatedItem: SecureItem,
        persistUpdate: (suspend (SecureItem) -> Unit)?
    ): Result<Unit> {
        val keepassBridge = bridge
        if (keepassBridge == null) {
            persistUpdate?.invoke(updatedItem)
            return Result.success(Unit)
        }
        val oldKeepassId = existingItem?.keepassDatabaseId
        val newKeepassId = updatedItem.keepassDatabaseId

        if (newKeepassId != null) {
            val updateResult = keepassBridge.updateLegacySecureItem(newKeepassId, updatedItem)
            if (updateResult.isFailure) {
                Log.e(TAG, "KeePass update failed: ${updateResult.exceptionOrNull()?.message}")
                return Result.failure(
                    updateResult.exceptionOrNull()
                        ?: IllegalStateException("KeePass update failed")
                )
            }
        }

        persistUpdate?.invoke(updatedItem)

        if (oldKeepassId != null && oldKeepassId != newKeepassId) {
            val deleteResult = keepassBridge.deleteKeePassSecureItems(oldKeepassId, listOf(existingItem))
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
        const val TAG = "KeePassSecureUpdate"
    }
}
