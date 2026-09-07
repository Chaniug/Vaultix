package com.bastion.app.bitwarden.cache

import com.bastion.app.logging.runCatchingObserved
import android.content.Context
import android.content.SharedPreferences
import com.bastion.app.data.PasswordEntry
import com.bastion.app.security.SecurityManager

/**
 * Persist the last readable Bitwarden secret locally so unreadable payloads can
 * still be displayed offline.
 */
class BitwardenOfflineSecretCache(
    context: Context,
    private val securityManager: SecurityManager
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREF_NAME,
        Context.MODE_PRIVATE
    )

    @Volatile
    private var memoryCache: Map<Long, CachedSecret> = emptyMap()

    fun remember(entry: PasswordEntry, plainSecret: String) {
        if (!entry.hasBitwardenCipherBinding() || plainSecret.isBlank()) return

        val entryId = entry.id
        val cipherId = entry.bitwardenCipherId.orEmpty()
        val existing = memoryCache[entryId]
        if (existing != null && existing.cipherId == cipherId && existing.secret == plainSecret) {
            return
        }

        val encrypted = runCatchingObserved { securityManager.encryptDataLegacyCompat(plainSecret) }
            .getOrNull() ?: return

        prefs.edit()
            .putString(secretKey(entryId), encrypted)
            .putString(cipherKey(entryId), cipherId)
            .apply()

        putMemory(entryId, cipherId, plainSecret)
    }

    fun recall(entry: PasswordEntry): String? {
        if (!entry.hasBitwardenCipherBinding()) return null

        val entryId = entry.id
        val cipherId = entry.bitwardenCipherId.orEmpty()

        val inMemory = memoryCache[entryId]
        if (inMemory != null && inMemory.cipherId == cipherId && inMemory.secret.isNotBlank()) {
            return inMemory.secret
        }

        val cachedCipherId = prefs.getString(cipherKey(entryId), null) ?: return null
        if (cachedCipherId != cipherId) {
            clear(entryId)
            return null
        }

        val encrypted = prefs.getString(secretKey(entryId), null) ?: return null
        val decrypted = runCatchingObserved { securityManager.decryptData(encrypted) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        putMemory(entryId, cipherId, decrypted)
        return decrypted
    }

    fun clear(entryId: Long) {
        prefs.edit()
            .remove(secretKey(entryId))
            .remove(cipherKey(entryId))
            .apply()

        if (memoryCache.containsKey(entryId)) {
            memoryCache = memoryCache.toMutableMap().also { it.remove(entryId) }
        }
    }

    private fun secretKey(entryId: Long): String = "secret_$entryId"

    private fun cipherKey(entryId: Long): String = "cipher_$entryId"

    /**
     * 清空内存中的明文 secret 缓存（不触碰磁盘离线兜底）。
     * 在保险库锁定（设备锁屏 / 应用锁定）时调用，避免已锁定的 App
     * 在后台长期持有明文。recall() 下次未命中时会从磁盘兜底解密，行为不变。
     */
    fun clearMemoryCache() {
        memoryCache = emptyMap()
    }

    /**
     * 线程安全地写入内存缓存并按容量上限淘汰最久未用的条目。
     * 保留既有的「整体替换 volatile Map 引用」发布模型，不引入锁。
     * toMutableMap() 返回 LinkedHashMap，保留插入顺序，故 keys.first()
     * 即最久未用条目，可作为 LRU 淘汰目标。
     */
    private fun putMemory(entryId: Long, cipherId: String, secret: String) {
        val next = memoryCache.toMutableMap()
        next[entryId] = CachedSecret(cipherId = cipherId, secret = secret)
        while (next.size > BITWARDEN_OFFLINE_SECRET_CACHE_MAX_ENTRIES) {
            val eldest = next.keys.firstOrNull() ?: break
            next.remove(eldest)
        }
        memoryCache = next
    }

    private data class CachedSecret(
        val cipherId: String,
        val secret: String
    )

    companion object {
        private const val PREF_NAME = "bitwarden_offline_secret_cache"
        private const val BITWARDEN_OFFLINE_SECRET_CACHE_MAX_ENTRIES = 1000
    }
}