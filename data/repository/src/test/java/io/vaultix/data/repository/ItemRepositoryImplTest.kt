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
import io.vaultix.database.dao.AtomicWriteDao
import io.vaultix.database.dao.CipherDao
import io.vaultix.database.dao.PendingOpDao
import io.vaultix.database.dao.VaultDao
import io.vaultix.database.entity.CipherEntity
import io.vaultix.database.entity.PendingOpEntity
import io.vaultix.database.entity.VaultEntity
import io.vaultix.domain.ReadOnlyVaultException
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
import java.time.Duration
import java.time.Instant

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
    private lateinit var atomicWriteDao: AtomicWriteDao
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
        // ★ 「行 + 队列」的原子写在生产里由 Room 的 @Transaction 生成实现；
        //   单测里没有 Room，所以手工接一个**只做转发**的子类：
        //   `upsertCipherAndEnqueue(row, op)` 的实体编排照旧执行，两个 `protected`
        //   落点转发到上面两个 mock ⇒ 既走通了原子写这条路径，
        //   又让本类原有的 `coVerify { cipherDao.upsertAll(...) }` 断言**依然成立**。
        //   （若改用 mockk 直接 mock 掉 `upsertCipherAndEnqueue`，行就不会真的落库，
        //    后续所有「写完再读回来」的断言会一起失效。）
        atomicWriteDao = object : AtomicWriteDao() {
            override suspend fun upsertCipher(row: CipherEntity) = cipherDao.upsertAll(listOf(row))
            override suspend fun upsertPendingOp(op: PendingOpEntity) = pendingOpDao.enqueue(op)
        }
        syncService = mockk()
        sessions = VaultSessionManager()
        crypto = VaultixCrypto(Dispatchers.Default)
        mapper = CipherMapper(crypto)
        repo = ItemRepositoryImpl(
            vaultDao = vaultDao,
            cipherDao = cipherDao,
            pendingOpDao = pendingOpDao,
            atomicWriteDao = atomicWriteDao,
            sessions = sessions,
            mapper = mapper,
            json = BitwardenJson,
            syncService = syncService,
            // KDBX 读路径分流用的会话桥：本测试全是 Bitwarden 库，桥永不被真正触发
            kdbxSessions = KdbxSessionFlow(),
            cryptoDispatcher = Dispatchers.Default,
        )
        // 读路径分流要先问「这个库是什么类型」（见 observeItems）：
        // 默认 mock 返回空列表 → 种类解析成 null → 走 Bitwarden 分支，与历史行为一致。
        every { vaultDao.observeAll() } returns flowOf(listOf(bitwardenVaultRow()))
        // ⚠️ 写路径也要问库类型了（`requireWritable`，见 `.ai/ISSUES.md` #106）：
        //   它走的是**挂起**的 `vaultDao.get`。严格 mock 不打桩会抛 MockKException，
        //   于是所有写路径测试都变成"因 mock 未打桩而失败"，
        //   把真正的断言（如类型守恒的「编辑暂不支持」）整条盖掉。
        coEvery { vaultDao.get(vaultId) } returns bitwardenVaultRow()
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

    private fun trashRow(id: String, deletedAt: Instant) = CipherEntity(
        id = id,
        vaultId = vaultId,
        type = 1,
        encryptedPayload = BitwardenJson.encodeToString(
            mapper.toRequest(plainItem(id), key).toStoredCipherDto(id, "rev"),
        ),
        revisionDate = "rev",
        deletedDate = deletedAt.toString(),
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
        // 服务端身份字段必须沿用原值：id / revisionDate 由服务端掌管，不得被表单改写
        assertEquals("cipher-1", row.id)
        assertEquals(existing.revisionDate, row.revisionDate)
        val op = opSlot.captured
        assertEquals("UPDATE", op.op)
        assertEquals("cipher-1", op.cipherId)
        assertNotNull(op.payload)
    }

    /**
     * folder / favorite 必须**按传入快照写入**，不能用 DB 旧值覆盖。
     *
     * ⚠️ 这条测试替换了此前 `assertEquals("folder-9", row.folderId)` 的断言。
     * 旧断言固化的是一个 bug：调用方（编辑页 `buildSnapshot`）传入的是**完整条目快照**，
     * 而 repository 又在写库前用 `existing.folderId/favorite` 覆盖一次
     * ⇒ 用户在编辑页选了文件夹、勾了收藏，保存后被**静默回退**。
     *
     * 旧断言的**担忧本身是对的**（编辑不该丢 folder/favorite），但防丢的正确位置在
     * 「调用方传完整快照」，而不是「在 repository 里无条件沿用旧值」——后者会让
     * 这两个字段**永远无法修改**（mapper.toUpdateRequest 2026-09-08 已专门按表单意图
     * 修复过，见 CipherMapper 注释）。
     */
    @Test
    fun updateItem_writesFolderAndFavoriteFromIncomingSnapshot() = runTest {
        sessions.unlock(vaultId, key)
        val existing = CipherEntity(
            id = "cipher-2",
            vaultId = vaultId,
            type = 1,
            encryptedPayload = BitwardenJson.encodeToString(
                mapper.toRequest(plainItem(id = "cipher-2"), key)
                    .toStoredCipherDto(id = "cipher-2", revisionDate = "rev-2"),
            ),
            revisionDate = "rev-2",
            favorite = false,
            folderId = "folder-old",
        )
        coEvery { cipherDao.get("cipher-2") } returns existing
        coEvery { vaultDao.get(vaultId) } returns bitwardenVaultRow()
        val rowSlot = slot<List<CipherEntity>>()
        coEvery { cipherDao.upsertAll(capture(rowSlot)) } returns Unit
        coEvery { pendingOpDao.enqueue(any()) } returns Unit
        coEvery { syncService.flushPending(vaultId, vaultId) } returns Result.success(Unit)

        // 用户在编辑页把条目挪到 folder-new 并勾上收藏
        val edited = plainItem(id = "cipher-2").copy(folderId = "folder-new", favorite = true)

        repo.updateItem(vaultId, edited)

        val row = rowSlot.captured.single()
        assertEquals("folder-new", row.folderId)
        assertTrue(row.favorite)
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

    /**
     * ★ 只读库闸（`.ai/ISSUES.md` #106）。
     *
     * 修之前，KDBX 库的写路径**没有分流**，两个方向都在骗人：
     * - `createItem` 会把一条 **Room 孤儿行**写进去 —— 而读侧走 `Kdbx.contentOf`，
     *   永远看不到它 ⇒ 用户看到「保存成功、条目却没出现」（**静默丢失**）；
     * - `updateItem` 先查 `cipherDao.get(id)`，KDBX 条目的 id 来自 `itemIdOf(uuid)`、
     *   不在 Room ⇒ 报「条目不存在」（与真实原因毫不相干）。
     *
     * 本用例锁死两条：**都返回 [ReadOnlyVaultException]** + **一个字节都没落**
     * （行、队列都不许动）—— 后者才是"没有幽灵数据"的真正判据。
     */
    @Test
    fun writeToKdbxVault_rejectedAndNothingWritten() = runTest {
        sessions.unlock(vaultId, key)
        coEvery { vaultDao.get(vaultId) } returns kdbxVaultRow()

        val created = repo.createItem(vaultId, plainItem(id = ""))
        val updated = repo.updateItem(vaultId, plainItem(id = "cipher-1"))
        val deleted = repo.softDeleteItem(vaultId, "cipher-1")

        assertTrue(created.exceptionOrNull() is ReadOnlyVaultException)
        assertTrue(updated.exceptionOrNull() is ReadOnlyVaultException)
        assertTrue(deleted.exceptionOrNull() is ReadOnlyVaultException)
        coVerify(exactly = 0) { cipherDao.upsertAll(any()) }
        coVerify(exactly = 0) { cipherDao.deleteByIds(any()) }
        coVerify(exactly = 0) { pendingOpDao.enqueue(any()) }
    }

    private fun kdbxVaultRow() = VaultEntity(
        id = vaultId,
        kind = VaultKind.KDBX.name,
        displayName = "KDBX",
        origin = "content://com.android.externalstorage.documents/document/primary%3Avault.kdbx",
        account = null,
        createdAt = 0L,
    )

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

    @Test
    fun cleanupExpiredTrash_deletesOnlyExpiredRows_andEnqueuesDelete() = runTest {
        sessions.unlock(vaultId, key)
        val now = Instant.now()
        val expired = trashRow("cipher-old", now.minus(Duration.ofDays(45)))
        val fresh = trashRow("cipher-new", now.minus(Duration.ofDays(10)))
        coEvery { cipherDao.getTrashByVault(vaultId) } returns listOf(expired, fresh)
        val opSlot = slot<PendingOpEntity>()
        coEvery { pendingOpDao.enqueue(capture(opSlot)) } returns Unit
        coEvery { cipherDao.deleteByIds(any()) } returns Unit
        coEvery { vaultDao.get(vaultId) } returns bitwardenVaultRow()
        coEvery { syncService.flushPending(vaultId, vaultId) } returns Result.success(Unit)

        val removed = repo.cleanupExpiredTrash(vaultId, 30)

        assertEquals(1, removed)
        // 只删过期行，未到期的保留（宁多留一天，不误删）
        coVerify(exactly = 1) { cipherDao.deleteByIds(listOf("cipher-old")) }
        val op = opSlot.captured
        assertEquals("DELETE", op.op)
        assertEquals("cipher-old", op.cipherId)
        assertNull(op.payload)
    }

    @Test
    fun cleanupExpiredTrash_nonPositiveDaysIsNoOp() = runTest {
        assertEquals(0, repo.cleanupExpiredTrash(vaultId, 0))
        assertEquals(0, repo.cleanupExpiredTrash(vaultId, -1))
        coVerify(exactly = 0) { cipherDao.getTrashByVault(any()) }
        coVerify(exactly = 0) { pendingOpDao.enqueue(any()) }
        coVerify(exactly = 0) { cipherDao.deleteByIds(any()) }
    }

    @Test
    fun cleanupExpiredTrash_survivesDaoFailure() = runTest {
        // 清理是后台辅助动作：库操作异常静默为 0，不打断进入回收站
        coEvery { cipherDao.getTrashByVault(vaultId) } throws IllegalStateException("db locked")
        assertEquals(0, repo.cleanupExpiredTrash(vaultId, 30))
    }

    @Test
    fun observeTrash_returnsEntriesWithDeletedDate() = runTest {
        sessions.unlock(vaultId, key)
        val deletedDate = "2026-09-01T00:00:00Z"
        every { cipherDao.observeTrashByVault(vaultId) } returns flowOf(
            listOf(trashRow("cipher-t", Instant.parse(deletedDate))),
        )

        val entries = repo.observeTrash(vaultId).first()

        assertEquals(1, entries.size)
        assertEquals(deletedDate, entries.single().deletedDate)
        assertEquals("GitHub", entries.single().item.title)
    }
}
