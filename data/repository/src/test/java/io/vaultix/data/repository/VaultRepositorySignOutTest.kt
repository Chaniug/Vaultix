package io.vaultix.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * 「退出数据库」流程（`.ai/ISSUES.md` #60 第 3 步）：
 * 清会话 + 清本地缓存（快速解锁凭据 / token / 待推送队列 / 密文条目与文件夹
 * / 同步基线），**保留 vault 行**（与 [VaultRepositoryImpl.removeVault] 的差别）。
 *
 * ⚠️ 断言刻意逐条覆盖「清什么」与「**不清**什么」：
 * 这一条最容易出的错是「顺手把 vault 行也删了」——那会把「退出数据库」变成
 * 「移除库」，用户的库会从列表里消失（数据还在服务端，但用户以为丢了）。
 */
class VaultRepositorySignOutTest {

    private val context = mockk<android.content.Context>(relaxed = true)
    private val vaultDao = mockk<VaultDao>(relaxed = true)
    private val cipherDao = mockk<CipherDao>(relaxed = true)
    private val folderDao = mockk<FolderDao>(relaxed = true)
    private val pendingOpDao = mockk<PendingOpDao>(relaxed = true)
    private val authRepository = mockk<BitwardenAuthRepository>()
    private val syncService = mockk<BitwardenSyncService>()
    private val credentials = mockk<SecureCredentialStore>(relaxed = true)
    private val localUnlockKeyStore = mockk<LocalUnlockKeyStore>(relaxed = true)
    private val preferences = mockk<VaultixPreferences>(relaxed = true)
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
        coEvery { credentials.getString(any()) } returns null
        coEvery { preferences.setLocalUnlockEnabled(any(), any()) } returns Unit
        coEvery { preferences.setKdbxKeyFileUri(any(), any()) } returns Unit
        // `logout` 是非挂起函数（只清内存/加密存储），故用 every 而非 coEvery（见 ISSUES #11）
        io.mockk.every { authRepository.logout(any()) } returns Unit
    }

    @Test
    fun signOut_clearsSessionAndLocalCache_butKeepsVaultRow() = runTest {
        sessions.unlock(vaultId, SymmetricCryptoKey.random())
        sessions.viewLock(vaultId)

        repo.signOut(vaultId)

        // 1) 内存会话清零：退出后任何解密入口都拿不到密钥
        assertNull(sessions.keyOf(vaultId))
        // 1b) 查看层标记一并失效：密钥都没了，标记留着会让界面停在「只需认证」的死角
        assertFalse(sessions.isViewLocked(vaultId))
        // 2) 认证凭据清除（token / refresh / protected key / host→server 登记）
        verify(exactly = 1) { authRepository.logout(vaultId) }
        // 3) 本地缓存四件套：待推送队列 / 密文条目 / 文件夹 / 同步基线
        coVerify(exactly = 1) { pendingOpDao.clearVault(vaultId) }
        coVerify(exactly = 1) { cipherDao.clearVault(vaultId) }
        coVerify(exactly = 1) { folderDao.clearVault(vaultId) }
        coVerify(exactly = 1) { vaultDao.updateRevision(vaultId, null) }
        // 4) ★ vault 行**必须保留**（这是「退出」与「移除库」的唯一区别）
        coVerify(exactly = 0) { vaultDao.delete(any()) }
    }

    @Test
    fun signOut_keepsVaultRowForUnknownVault() = runTest {
        // 未知库退出不抛错，且同样不删行（幂等）
        repo.signOut(vaultId)
        coVerify(exactly = 0) { vaultDao.delete(any()) }
    }
}
