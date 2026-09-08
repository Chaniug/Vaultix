package io.vaultix.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.vaultix.crypto.SymmetricCryptoKey
import io.vaultix.crypto.VaultixCrypto
import io.vaultix.data.bitwarden.mapper.CipherMapper
import io.vaultix.data.bitwarden.model.CipherDto
import io.vaultix.data.bitwarden.model.CipherRequest
import io.vaultix.data.bitwarden.model.toStoredCipherDto
import io.vaultix.data.bitwarden.network.BitwardenJson
import io.vaultix.data.bitwarden.sync.BitwardenSyncService
import io.vaultix.database.dao.CipherDao
import io.vaultix.database.dao.PendingOpDao
import io.vaultix.database.dao.VaultDao
import io.vaultix.database.entity.CipherEntity
import io.vaultix.database.entity.PendingOpEntity
import io.vaultix.database.entity.VaultEntity
import io.vaultix.domain.VaultSaveOutcome
import io.vaultix.model.VaultFido2Credential
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 条目写路径（Docs/02 密文落盘语义）：
 * - create：本地 uuid 行 + CREATE 入队 + flush（Synced / 离线 Queued）；
 * - update：沿用原 id / revisionDate，UPDATE 入队，密文用会话密钥可还原；
 * - softDelete：本地标记 deletedDate（列表立即隐藏）+ SOFT_DELETE 入队（无 payload）；
 * - observe：未解锁返回空，解锁后明文往返一致（含 password）。
 *
 * 注：单测刻意直连 Dispatchers.Default（绕过 DI 门禁），生产注入点在
 * ItemRepositoryImpl 构造（@CryptoDispatcher），此处不做注入分层。
 */
@Suppress("InjectDispatcher")
class ItemRepositoryImplTest {

    private lateinit var vaultDao: VaultDao
    private lateinit var cipherDao: CipherDao
    private lateinit var pendingOpDao: PendingOpDao
    private lateinit var syncService: BitwardenSyncService
    private lateinit var sessions: VaultSessionManager
    private lateinit var crypto: VaultixCrypto
    private lateinit var mapper: CipherMapper
    private lateinit var repo: ItemRepositoryImpl

    private val vaultId = "https://vault.example.com"
    private val key: SymmetricCryptoKey = SymmetricCryptoKey.random()

    @Before
    fun setUp() {
        vaultDao = mockk()
        cipherDao = mockk()
        pendingOpDao = mockk()
        syncService = mockk()
        sessions = VaultSessionManager()
        crypto = VaultixCrypto(Dispatchers.Default)
        mapper = CipherMapper(crypto)
        repo = ItemRepositoryImpl(
            vaultDao = vaultDao,
            cipherDao = cipherDao,
            pendingOpDao = pendingOpDao,
            sessions = sessions,
            mapper = mapper,
            json = BitwardenJson,
            syncService = syncService,
            cryptoDispatcher = Dispatchers.Default,
        )
    }

    private fun bitwardenVaultRow() = VaultEntity(
        id = vaultId,
        kind = VaultKind.BITWARDEN.name,
        displayName = "Bitwarden",
        origin = vaultId,
        account = "alice@example.com",
        createdAt = 0L,
    )

    private fun plainItem(id: String) = VaultItem(
        id = id,
        title = "GitHub",
        username = "alice@example.com",
        password = "s3cret-pass",
        notes = "工作账号",
    )

    @Test
    fun createItem_enqueuesCreateAndFlushes() = runTest {
        sessions.unlock(vaultId, key)
        coEvery { vaultDao.get(vaultId) } returns bitwardenVaultRow()
        val rowSlot = slot<List<CipherEntity>>()
        val opSlot = slot<PendingOpEntity>()
        coEvery { cipherDao.upsertAll(capture(rowSlot)) } returns Unit
        coEvery { pendingOpDao.enqueue(capture(opSlot)) } returns Unit
        coEvery { syncService.flushPending(vaultId, vaultId) } returns Result.success(Unit)

        val outcome = repo.createItem(vaultId, plainItem(id = "ignored"))

        assertEquals(VaultSaveOutcome.Synced, outcome.getOrThrow())
        val row = rowSlot.captured.single()
        assertEquals(vaultId, row.vaultId)
        assertTrue(row.encryptedPayload.isNotBlank())
        val op = opSlot.captured
        assertEquals("CREATE", op.op)
        assertEquals(row.id, op.cipherId)
        assertNotNull(op.payload)
        // 队列里的密文可以用会话密钥解密回明文
        val request = BitwardenJson.decodeFromString<CipherRequest>(op.payload!!)
        assertEquals("GitHub", crypto.decryptToString(request.name!!, key))
        coVerify { syncService.flushPending(vaultId, vaultId) }
    }

    @Test
    fun createItem_offlineFlushFailure_returnsQueued_andKeepsRow() = runTest {
        sessions.unlock(vaultId, key)
        coEvery { vaultDao.get(vaultId) } returns bitwardenVaultRow()
        coEvery { cipherDao.upsertAll(any()) } returns Unit
        coEvery { pendingOpDao.enqueue(any()) } returns Unit
        coEvery { syncService.flushPending(vaultId, vaultId) } returns
            Result.failure(IllegalStateException("no network"))

        val outcome = repo.createItem(vaultId, plainItem("x"))

        assertEquals(VaultSaveOutcome.Queued, outcome.getOrThrow())
        // 失败不删除队列（重试语义在 BitwardenSyncService 内，repository 只判断结果）
        coVerify(exactly = 1) { pendingOpDao.enqueue(any()) }
    }

    @Test
    fun updateItem_preservesIdAndRevision_andEnqueuesUpdate() = runTest {
        sessions.unlock(vaultId, key)
        val existing = CipherEntity(
            id = "cipher-1",
            vaultId = vaultId,
            type = 1,
            encryptedPayload = BitwardenJson.encodeToString(
                mapper.toRequest(plainItem(id = "cipher-1"), key)
                    .toStoredCipherDto(id = "cipher-1", revisionDate = "2026-09-01T00:00:00.000Z"),
            ),
            revisionDate = "2026-09-01T00:00:00.000Z",
            favorite = true,
            folderId = "folder-9",
        )
        coEvery { cipherDao.get("cipher-1") } returns existing
        coEvery { vaultDao.get(vaultId) } returns bitwardenVaultRow()
        val rowSlot = slot<List<CipherEntity>>()
        val opSlot = slot<PendingOpEntity>()
        coEvery { cipherDao.upsertAll(capture(rowSlot)) } returns Unit
        coEvery { pendingOpDao.enqueue(capture(opSlot)) } returns Unit
        coEvery { syncService.flushPending(vaultId, vaultId) } returns Result.success(Unit)

        val outcome = repo.updateItem(vaultId, plainItem(id = "cipher-1"))

        assertEquals(VaultSaveOutcome.Synced, outcome.getOrThrow())
        val row = rowSlot.captured.single()
        // 沿用原 id / revisionDate / folderId / favorite
        assertEquals("cipher-1", row.id)
        assertEquals(existing.revisionDate, row.revisionDate)
        assertEquals("folder-9", row.folderId)
        assertTrue(row.favorite)
        val op = opSlot.captured
        assertEquals("UPDATE", op.op)
        assertEquals("cipher-1", op.cipherId)
        assertNotNull(op.payload)
    }

    @Test
    fun updateItem_serverTypeUnknown_rejectedWithoutWrite() = runTest {
        // 未知类型（type=99）在领域层显示为 Login，但写路径类型守恒守卫必须拒绝，
        // 防止整条重写把服务端类型改写成 1（审计 M1-3 类型漂移）
        sessions.unlock(vaultId, key)
        val existing = CipherEntity(
            id = "cipher-99",
            vaultId = vaultId,
            type = 99,
            encryptedPayload = BitwardenJson.encodeToString(
                CipherDto(id = "cipher-99", type = 99),
            ),
            revisionDate = "2026-09-01T00:00:00.000Z",
        )
        coEvery { cipherDao.get("cipher-99") } returns existing

        val outcome = repo.updateItem(vaultId, plainItem(id = "cipher-99"))

        assertTrue(outcome.isFailure)
        assertTrue(outcome.exceptionOrNull()!!.message!!.contains("编辑暂不支持"))
        coVerify(exactly = 0) { cipherDao.upsertAll(any()) }
        coVerify(exactly = 0) { pendingOpDao.enqueue(any()) }
    }

    @Test
    fun softDeleteItem_marksDeletedAndEnqueuesWithoutPayload() = runTest {
        val existing = CipherEntity(
            id = "cipher-2",
            vaultId = vaultId,
            type = 1,
            encryptedPayload = "{}",
            revisionDate = "2026-09-01T00:00:00.000Z",
        )
        coEvery { cipherDao.get("cipher-2") } returns existing
        coEvery { vaultDao.get(vaultId) } returns bitwardenVaultRow()
        val rowSlot = slot<List<CipherEntity>>()
        val opSlot = slot<PendingOpEntity>()
        coEvery { cipherDao.upsertAll(capture(rowSlot)) } returns Unit
        coEvery { pendingOpDao.enqueue(capture(opSlot)) } returns Unit
        coEvery { syncService.flushPending(vaultId, vaultId) } returns Result.success(Unit)

        val outcome = repo.softDeleteItem(vaultId, "cipher-2")

        assertEquals(VaultSaveOutcome.Synced, outcome.getOrThrow())
        val row = rowSlot.captured.single()
        assertNotNull(row.deletedDate) // 列表查询 deletedDate IS NULL → 立即隐藏
        val op = opSlot.captured
        assertEquals("SOFT_DELETE", op.op)
        assertEquals("cipher-2", op.cipherId)
        assertNull(op.payload)
    }

    @Test
    fun observeItems_lockedEmpty_unlockedPlaintextRoundtrip() = runTest {
        val entity = CipherEntity(
            id = "cipher-3",
            vaultId = vaultId,
            type = 1,
            encryptedPayload = BitwardenJson.encodeToString(
                mapper.toRequest(plainItem("cipher-3"), key)
                    .toStoredCipherDto("cipher-3", "rev"),
            ),
            revisionDate = "rev",
        )
        every { cipherDao.observeByVault(vaultId) } returns flowOf(listOf(entity))

        // 未解锁 → 空列表（不泄密）
        val locked = repo.observeItems(vaultId).first()
        assertTrue(locked.isEmpty())

        sessions.unlock(vaultId, key)
        val items = repo.observeItems(vaultId).first()
        assertEquals(1, items.size)
        val item = items.single()
        assertEquals("GitHub", item.title)
        assertEquals("alice@example.com", item.username)
        assertEquals("s3cret-pass", item.password)
        assertEquals("工作账号", item.notes)
    }

    @Test
    fun observeItem_afterLocalDelete_returnsNull() = runTest {
        sessions.unlock(vaultId, key)
        val entity = CipherEntity(
            id = "cipher-4",
            vaultId = vaultId,
            type = 1,
            encryptedPayload = BitwardenJson.encodeToString(
                mapper.toRequest(plainItem("cipher-4"), key)
                    .toStoredCipherDto("cipher-4", "rev"),
            ),
            revisionDate = "rev",
            deletedDate = null,
        )
        // answers 延迟求值：模拟 Room 流在数据变化后重发
        var emitted: CipherEntity? = entity
        every { cipherDao.observe("cipher-4") } answers { flowOf(emitted) }

        val first = repo.observeItem(vaultId, "cipher-4").first()
        assertEquals("GitHub", first?.title)

        // 模拟本地软删除后：详情流发出 null（导航返回列表）
        emitted = entity.copy(deletedDate = "2026-09-08T00:00:00Z")
        val after = repo.observeItem(vaultId, "cipher-4").first()
        assertNull(after)
    }

    @Test
    fun restoreItem_clearsDeletedDate_andEnqueuesRestore() = runTest {
        sessions.unlock(vaultId, key)
        val existing = CipherEntity(
            id = "cipher-5",
            vaultId = vaultId,
            type = 1,
            encryptedPayload = BitwardenJson.encodeToString(
                mapper.toRequest(plainItem("cipher-5"), key)
                    .toStoredCipherDto("cipher-5", "rev"),
            ),
            revisionDate = "rev",
            deletedDate = "2026-09-08T00:00:00Z",
        )
        coEvery { cipherDao.get("cipher-5") } returns existing
        coEvery { vaultDao.get(vaultId) } returns bitwardenVaultRow()
        val rowSlot = slot<List<CipherEntity>>()
        val opSlot = slot<PendingOpEntity>()
        coEvery { cipherDao.upsertAll(capture(rowSlot)) } returns Unit
        coEvery { pendingOpDao.enqueue(capture(opSlot)) } returns Unit
        coEvery { syncService.flushPending(vaultId, vaultId) } returns Result.success(Unit)

        val outcome = repo.restoreItem(vaultId, "cipher-5")

        assertEquals(VaultSaveOutcome.Synced, outcome.getOrThrow())
        assertNull(rowSlot.captured.single().deletedDate) // 主列表立即恢复显示
        val op = opSlot.captured
        assertEquals("RESTORE", op.op)
        assertEquals("cipher-5", op.cipherId)
        assertNull(op.payload)
    }

    @Test
    fun permanentDeleteItem_removesLocalRow_andEnqueuesDelete() = runTest {
        sessions.unlock(vaultId, key)
        val existing = CipherEntity(
            id = "cipher-6",
            vaultId = vaultId,
            type = 1,
            encryptedPayload = "{}",
            revisionDate = "rev",
            deletedDate = "2026-09-08T00:00:00Z",
        )
        coEvery { cipherDao.get("cipher-6") } returns existing
        coEvery { vaultDao.get(vaultId) } returns bitwardenVaultRow()
        val opSlot = slot<PendingOpEntity>()
        coEvery { pendingOpDao.enqueue(capture(opSlot)) } returns Unit
        coEvery { cipherDao.deleteByIds(any()) } returns Unit
        coEvery { syncService.flushPending(vaultId, vaultId) } returns Result.success(Unit)

        val outcome = repo.permanentDeleteItem(vaultId, "cipher-6")

        assertEquals(VaultSaveOutcome.Synced, outcome.getOrThrow())
        coVerify(exactly = 1) { cipherDao.deleteByIds(listOf("cipher-6")) }
        val op = opSlot.captured
        assertEquals("DELETE", op.op)
        assertNull(op.payload)
    }

    @Test
    fun updateFido2Credentials_writesIntoLoginCipher_notSeparatePasskeyCipher() = runTest {
        // 对齐 Bitwarden 标准：保存通行密钥必须写入所属登录条目的 login.fido2Credentials，
        // 不得生成独立的 [Passkey] 条目。这正是 Bastion 的缺陷——其 PasskeyEntry.boundPasswordId
        // 恒为 null，通行密钥作为孤立 Login 密文存在，与所属密码条目并无真实关联（假绑定）。
        // 本测试锁定 Vaultix 的正确行为，防止回归。
        sessions.unlock(vaultId, key)
        val existing = CipherEntity(
            id = "cipher-pk",
            vaultId = vaultId,
            type = 1,
            encryptedPayload = BitwardenJson.encodeToString(
                mapper.toRequest(plainItem(id = "cipher-pk"), key)
                    .toStoredCipherDto("cipher-pk", "rev"),
            ),
            revisionDate = "rev",
        )
        coEvery { cipherDao.get("cipher-pk") } returns existing
        coEvery { vaultDao.get(vaultId) } returns bitwardenVaultRow()
        val rowSlot = slot<List<CipherEntity>>()
        val opSlot = slot<PendingOpEntity>()
        coEvery { cipherDao.upsertAll(capture(rowSlot)) } returns Unit
        coEvery { pendingOpDao.enqueue(capture(opSlot)) } returns Unit
        coEvery { syncService.flushPending(vaultId, vaultId) } returns Result.success(Unit)

        val cred = VaultFido2Credential(
            credentialId = "cred-abc-123",
            rpId = "github.com",
            rpName = "GitHub",
            userName = "alice@example.com",
        )
        val outcome = repo.updateFido2Credentials(vaultId, "cipher-pk", listOf(cred))

        assertEquals(VaultSaveOutcome.Synced, outcome.getOrThrow())
        // 仍是同一条目（登录条目），没有新建独立的通行密钥条目
        val row = rowSlot.captured.single()
        assertEquals("cipher-pk", row.id)
        // 上传体的 login.fido2Credentials 携带该凭证（可解密回原文）
        val op = opSlot.captured
        assertEquals("UPDATE", op.op)
        val request = BitwardenJson.decodeFromString<CipherRequest>(op.payload!!)
        assertNotNull(request.login)
        assertEquals(1, request.login?.fido2Credentials?.size)
        val stored = request.login?.fido2Credentials?.first()!!
        assertEquals("cred-abc-123", crypto.decryptToString(stored.credentialId!!, key))
        assertEquals("github.com", crypto.decryptToString(stored.rpId!!, key))
        assertEquals("GitHub", crypto.decryptToString(stored.rpName!!, key))
    }

    @Test
    fun restoreOrPermanentDeleteOfActiveItem_rejected() = runTest {        sessions.unlock(vaultId, key)
        val active = CipherEntity(
            id = "cipher-7",
            vaultId = vaultId,
            type = 1,
            encryptedPayload = "{}",
            revisionDate = "rev",
            deletedDate = null,
        )
        coEvery { cipherDao.get("cipher-7") } returns active

        assertTrue(repo.restoreItem(vaultId, "cipher-7").isFailure)
        assertTrue(repo.permanentDeleteItem(vaultId, "cipher-7").isFailure)
        coVerify(exactly = 0) { pendingOpDao.enqueue(any()) }
    }
}
