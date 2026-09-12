package io.vaultix.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.vaultix.crypto.SymmetricCryptoKey
import io.vaultix.data.bitwarden.auth.BitwardenAuthRepository
import io.vaultix.data.bitwarden.sync.BitwardenSyncService
import io.vaultix.database.dao.CipherDao
import io.vaultix.database.dao.FolderDao
import io.vaultix.database.dao.PendingOpDao
import io.vaultix.database.dao.VaultDao
import io.vaultix.datastore.LocalUnlockKeyStore
import io.vaultix.datastore.SecureCredentialStore
import io.vaultix.datastore.VaultixPreferences
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 移除库（本地删除）流程：
 * 清内存会话 → 快速解锁痕迹清理 → 待推送队列清空（先于删行，防止同服务器
 * 重加账号后旧队列误推）→ vault 行删除（ciphers/folders 走外键级联）。
 */
class VaultRepositoryRemoveTest {

    private val vaultDao = mockk<VaultDao>()
    private val cipherDao = mockk<CipherDao>(relaxed = true)
    private val folderDao = mockk<FolderDao>(relaxed = true)
    private val pendingOpDao = mockk<PendingOpDao>()
    private val authRepository = mockk<BitwardenAuthRepository>()
    private val syncService = mockk<BitwardenSyncService>()
    private val credentials = mockk<SecureCredentialStore>()
    private val localUnlockKeyStore = mockk<LocalUnlockKeyStore>()
    private val preferences = mockk<VaultixPreferences>()
    private val context = mockk<android.content.Context>(relaxed = true)
    private val sessions = VaultSessionManager()
    private lateinit var repo: VaultRepositoryImpl

    private val vaultId = "https://vault.example.com"

    @Before
    fun setUp() {
        repo = VaultRepositoryImpl(
            vaultDao = vaultDao,
            cipherDao = cipherDao,
            folderDao = folderDao,
            pendingOpDao = pendingOpDao,
            authRepository = authRepository,
            sessions = sessions,
            syncService = syncService,
            credentials = credentials,
            localUnlockKeyStore = localUnlockKeyStore,
            preferences = preferences,
            kdbxSessions = KdbxSessionFlow(),
            context = context,
        )
        coEvery { credentials.remove(any()) } returns Unit
        coEvery { preferences.setLocalUnlockEnabled(any(), any()) } returns Unit
        io.mockk.every { authRepository.logout(any()) } returns Unit
        coEvery { pendingOpDao.clearVault(vaultId) } returns Unit
        coEvery { vaultDao.delete(vaultId) } returns Unit
    }

    @Test
    fun removeVault_locksSession_clearsQueueThenDeletesRow() = runTest {
        sessions.unlock(vaultId, SymmetricCryptoKey.random())

        repo.removeVault(vaultId)

        // 内存会话已清零：移除后任何解密入口都拿不到密钥
        assertNull(sessions.keyOf(vaultId))
        coVerify(exactly = 1) { credentials.remove(any()) }
        // 凭据与 host→server 登记一并清除（防残留会话被后续请求复用）
        io.mockk.verify(exactly = 1) { authRepository.logout(vaultId) }
        // 队列清理先于删行（同服务器重加账号时不允许残留旧离线改动）
        coVerifyOrder {
            pendingOpDao.clearVault(vaultId)
            vaultDao.delete(vaultId)
        }
    }

    @Test
    fun removeVault_isIdempotentForUnknownVault() = runTest {
        // 未知库删除不抛错（列表已无该行时再次触发也无害）
        repo.removeVault(vaultId)
        coVerify(exactly = 1) { vaultDao.delete(vaultId) }
    }
}
