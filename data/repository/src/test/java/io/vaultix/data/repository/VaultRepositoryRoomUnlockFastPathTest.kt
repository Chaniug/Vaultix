package io.vaultix.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.vaultix.data.bitwarden.auth.BitwardenAuthRepository
import io.vaultix.data.bitwarden.sync.BitwardenSyncService
import io.vaultix.data.kdbx.Kdbx
import io.vaultix.data.kdbx.KdbxFileSource
import io.vaultix.data.kdbx.KdbxFileStat
import io.vaultix.data.kdbx.KdbxFileWriteResult
import io.vaultix.data.repository.kdbx.KdbxFileSourceResolver
import io.vaultix.database.dao.CipherDao
import io.vaultix.database.dao.CollectionDao
import io.vaultix.database.dao.FolderDao
import io.vaultix.database.dao.OrganizationDao
import io.vaultix.database.dao.PendingOpDao
import io.vaultix.database.dao.VaultDao
import io.vaultix.database.entity.VaultEntity
import io.vaultix.datastore.LocalUnlockKeyStore
import io.vaultix.datastore.SecureCredentialStore
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.domain.RoomUnlockOutcome
import io.vaultix.model.VaultKind
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

/**
 * 房间解锁的**快速路径**（2026-11-04，#246 第四批）。
 *
 * ## 为什么这条用例必须存在
 *
 * 「查看锁 / 界面门禁」的语义是**挡住屏幕、不动会话**（见 `VaultSessionManager` 的
 * KDoc）。但解锁侧原先**无条件**走 `Kdbx.unlock` ⇒ 每次熄屏回来都把整库重新
 * Argon2 一遍，而明文本来就在内存里。用户对照 KeePass2Android 说的
 * 「它解锁是瞬时的」，差的就是这一步。
 *
 * 这条路径的失效方式是**静默**的：功能完全正常，只是每次多花一次完整 KDF
 * （真机 64 MiB / 46 轮）。不写用例，就只会在用户抱怨"怎么还是慢"时才发现。
 *
 * ## 两个用例各守一半
 *
 * 1. 会话还在 ⇒ **不得**解析文件来源（解析即意味着要 read / stat + 开库）；
 * 2. 会话不在 ⇒ 仍然真的开库（防止把短路写成无条件放行 —— 那是**认证绕过**）。
 */
class VaultRepositoryRoomUnlockFastPathTest {

    private val vaultDao = mockk<VaultDao>()
    private val cipherDao = mockk<CipherDao>(relaxed = true)
    private val folderDao = mockk<FolderDao>(relaxed = true)
    private val organizationDao = mockk<OrganizationDao>(relaxed = true)
    private val collectionDao = mockk<CollectionDao>(relaxed = true)
    private val pendingOpDao = mockk<PendingOpDao>(relaxed = true)
    private val authRepository = mockk<BitwardenAuthRepository>(relaxed = true)
    private val syncService = mockk<BitwardenSyncService>(relaxed = true)
    private val credentials = mockk<SecureCredentialStore>(relaxed = true)
    private val localUnlockKeyStore = mockk<LocalUnlockKeyStore>(relaxed = true)
    private val houseKeyStore = mockk<HouseKeyStore>(relaxed = true)
    private val localUnlockEnrollment = mockk<LocalUnlockEnrollment>(relaxed = true)
    private val preferences = mockk<VaultixPreferences>(relaxed = true)

    /**
     * KDBX 库的 id **就是** origin（`addKdbxVault` 的约定），这里用同一条串。
     */
    private val vaultId =
        "content://com.android.externalstorage.documents/document/primary%3Avault.kdbx"

    private val password = "correct horse battery staple"

    private lateinit var repo: VaultRepositoryImpl

    /** 建库用的来源：写入成功即可，会话建立后不再碰它。 */
    private val seedSource = mockk<KdbxFileSource>(relaxed = true).apply {
        coEvery { stat() } returns KdbxFileStat(versionToken = "v1")
        coEvery { write(bytes = any(), expectedVersion = any(), force = any(), failIfExists = any()) } returns
            KdbxFileWriteResult(versionToken = "v1")
    }

    /**
     * 解析器**故意一被调用就抛错**：短路生效时它根本不该被碰到。
     */
    private var resolverCalls = 0

    private val kdbxFileSources = KdbxFileSourceResolver { origin ->
        resolverCalls++
        error("会话仍在内存时不该解析文件来源：$origin")
    }

    @Before
    fun setUp() {
        repo = VaultRepositoryImpl(
            vaultDao = vaultDao,
            cipherDao = cipherDao,
            folderDao = folderDao,
            organizationDao = organizationDao,
            collectionDao = collectionDao,
            pendingOpDao = pendingOpDao,
            authRepository = authRepository,
            sessions = VaultSessionManager(KdbxSessionFlow()),
            syncService = syncService,
            credentials = credentials,
            localUnlockKeyStore = localUnlockKeyStore,
            houseKeyStore = houseKeyStore,
            localUnlockEnrollment = localUnlockEnrollment,
            preferences = preferences,
            kdbxSessions = KdbxSessionFlow(),
            kdbxFileSources = kdbxFileSources,
            kdbxFileCache = mockk(relaxed = true),
        )
        coEvery { vaultDao.get(vaultId) } returns VaultEntity(
            id = vaultId,
            kind = VaultKind.KDBX.name,
            displayName = "KDBX",
            origin = vaultId,
            account = null,
            createdAt = 0L,
        )
        // 房间信封能解开（= 用户已通过门锁）—— 这是短路的前置条件。
        coEvery { houseKeyStore.openRoom(vaultId) } returns
            RoomOpen.Opened(KdbxUnlockPayload.encode(password, keyFileBytes = null))
        io.mockk.every { preferences.quickUnlockScope() } returns flowOf(emptySet())
    }

    @After
    fun tearDown() {
        // `KdbxSessionStore` 是进程级单例：播的种必须清掉，否则会漏进别的用例。
        Kdbx.lock(vaultId)
    }

    @Test
    fun `会话还在内存_房间解锁直接复用_不解析文件来源`() = runTest {
        Kdbx.createVault(vaultId, seedSource, "测试库", password).getOrThrow()
        // 前置断言：会话真的建立了（否则本用例会因为"根本没种上"而假通过）。
        assertEquals(true, Kdbx.isUnlocked(vaultId))

        val outcome = repo.unlockVaultFromRoom(vaultId)

        assertEquals(RoomUnlockOutcome.Opened, outcome)
        // ★ 核心断言：一个字节都没去读远端，也没有解析来源（那一步之后必然是 KDF）。
        assertEquals(0, resolverCalls)
        coVerify(exactly = 0) { seedSource.read() }
    }

    @Test
    fun `会话不在内存_仍然真的走开库路径`() = runTest {
        // 不曾建立会话（模拟硬锁 / 冷启动）：解信封成功也必须真的去读文件。
        val readSource = mockk<KdbxFileSource>(relaxed = true).apply {
            coEvery { read() } returns ByteArray(64) { 0x7F }
        }
        val resolver = KdbxFileSourceResolver { readSource }
        val fresh = VaultRepositoryImpl(
            vaultDao = vaultDao,
            cipherDao = cipherDao,
            folderDao = folderDao,
            organizationDao = organizationDao,
            collectionDao = collectionDao,
            pendingOpDao = pendingOpDao,
            authRepository = authRepository,
            sessions = VaultSessionManager(KdbxSessionFlow()),
            syncService = syncService,
            credentials = credentials,
            localUnlockKeyStore = localUnlockKeyStore,
            houseKeyStore = houseKeyStore,
            localUnlockEnrollment = localUnlockEnrollment,
            preferences = preferences,
            kdbxSessions = KdbxSessionFlow(),
            kdbxFileSources = resolver,
            kdbxFileCache = mockk(relaxed = true),
        )

        val outcome = fresh.unlockVaultFromRoom(vaultId)

        // 垃圾字节必然开不出来 —— 关键是它**没有**被当成"已解锁"直接放行。
        assertNotEquals(RoomUnlockOutcome.Opened, outcome)
        coVerify(exactly = 1) { readSource.read() }
    }
}
