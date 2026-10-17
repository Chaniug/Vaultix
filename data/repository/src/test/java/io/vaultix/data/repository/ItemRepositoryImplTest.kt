package io.vaultix.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.vaultix.crypto.SymmetricCryptoKey
import io.vaultix.crypto.VaultixCrypto
import io.vaultix.data.repository.KdbxItemRepository
import io.vaultix.data.bitwarden.mapper.CipherMapper
import io.vaultix.data.bitwarden.model.CipherDto
import io.vaultix.data.bitwarden.model.CipherRequest
import io.vaultix.data.bitwarden.model.toStoredCipherDto
import io.vaultix.data.bitwarden.network.BitwardenJson
import io.vaultix.data.bitwarden.sync.BitwardenSyncService
import io.vaultix.data.kdbx.Kdbx
import io.vaultix.data.kdbx.KdbxFileSource
import io.vaultix.data.kdbx.KdbxFileStat
import io.vaultix.data.kdbx.KdbxFileWriteResult
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
import io.vaultix.model.VaultItemType
import io.vaultix.model.VaultKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
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
    /** KDBX 写回（W2 起写路径分层）。只有 KDBX 那条用例真的会用到它。 */
    private val kdbxItemWrites = mockk<KdbxItemRepository>(relaxed = true)

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
        //
        // ⚠️ 这个转发层**不验证原子性**（两个 mock 是独立桩，不会一起回滚）——
        //    所以本类只能测"行为对不对"，测不到"中途失败会不会半途而废"。
        //    原子性边界由 `AtomicWriteBoundaryTest` 用**真实 Room in-memory 库**验证。
        atomicWriteDao = object : AtomicWriteDao() {
            override suspend fun upsertCipher(row: CipherEntity) = cipherDao.upsertAll(listOf(row))
            override suspend fun upsertPendingOp(op: PendingOpEntity) = pendingOpDao.enqueue(op)
            override suspend fun deleteCiphers(ids: List<String>) = cipherDao.deleteByIds(ids)
        }
        syncService = mockk()
        sessions = VaultSessionManager(KdbxSessionFlow())
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
            // KDBX 写回（W2 起写路径也分流）。本文件绝大多数用例是 Bitwarden 库，
            // 只有下面那条 KDBX 用例会真的用到它 ⇒ 用 relaxed mock，并逐条 coVerify。
            kdbxItemWrites = kdbxItemWrites,
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
     * ★ KDBX 写路径分流（`.ai/ISSUES.md` #106，2026-10-01 起**真的写回**）。
     *
     * ## 这条用例守两件事，第二件才是判据
     *
     * 1. 三个写方法**都委托给 KDBX 的写回实现**（`KdbxItemRepository`）——
     *    在这之前它们会直接抛"库只读"（那是 2026-09-30 的诚实拒绝，现在换成真支持）；
     * 2. ★ **一个字节都没写进 Room**（行、队列都不许动）—— 这才是"没有幽灵数据"的判据。
     *    修 #106 之前，`createItem` 在 KDBX 库会写出一条 Room 孤儿行，
     *    而读侧走 `Kdbx.contentOf` 永远看不到它 ⇒ 用户看到「保存成功、条目却没出现」。
     *    换成分流之后，Room 那侧必须**完全安静**：那条孤儿行就是从这里来的。
     */
    @Test
    fun writeToKdbxVault_delegatesToKdbxWriteAndTouchesNothingInRoom() = runTest {
        coEvery { vaultDao.get(vaultId) } returns kdbxVaultRow()
        coEvery { kdbxItemWrites.create(vaultId, any()) } returns Result.success(VaultSaveOutcome.Queued)
        coEvery { kdbxItemWrites.update(vaultId, any()) } returns Result.success(VaultSaveOutcome.Queued)
        coEvery { kdbxItemWrites.softDelete(vaultId, any()) } returns Result.success(VaultSaveOutcome.Queued)

        val created = repo.createItem(vaultId, plainItem(id = ""))
        val updated = repo.updateItem(vaultId, plainItem(id = "cipher-1"))
        val deleted = repo.softDeleteItem(vaultId, "cipher-1")

        assertTrue(created.isSuccess)
        assertTrue(updated.isSuccess)
        assertTrue(deleted.isSuccess)
        coVerify(exactly = 1) { kdbxItemWrites.create(vaultId, any()) }
        coVerify(exactly = 1) { kdbxItemWrites.update(vaultId, any()) }
        coVerify(exactly = 1) { kdbxItemWrites.softDelete(vaultId, "cipher-1") }

        // ★ 真正的判据：Room 侧**完全没被碰过**。
        coVerify(exactly = 0) { cipherDao.upsertAll(any()) }
        coVerify(exactly = 0) { cipherDao.deleteByIds(any()) }
        coVerify(exactly = 0) { pendingOpDao.enqueue(any()) }
    }

    /**
     * 反证：**通道是通的**（不许把"没写 Room"归因于"根本没走到分流"）。
     *
     * 上面那条用例只断言"Room 没动"，而"Room 没动"也可能因为**整条路径根本没跑**
     * （比如方法在第一行就抛了）。⇒ 补一条正向断言：委托调用**确实发生了**。
     * 这正是变异验证的思路：把分流删掉，这条必须红。
     */
    @Test
    fun writeToKdbxVault_reportsFailureWhenKdbxWriteFails() = runTest {
        coEvery { vaultDao.get(vaultId) } returns kdbxVaultRow()
        coEvery { kdbxItemWrites.create(vaultId, any()) } returns
            Result.failure(IllegalStateException("请先解锁该密码库"))

        val created = repo.createItem(vaultId, plainItem(id = ""))

        assertTrue(created.isFailure)
        assertEquals("请先解锁该密码库", created.exceptionOrNull()?.message)
        coVerify(exactly = 0) { cipherDao.upsertAll(any()) }
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

        // 未解锁 → 空列表（不泄密）。
        val locked = repo.observeItems(vaultId).first()
        assertTrue(locked.isEmpty())

        sessions.unlock(vaultId, key)
        // 共享缓存（Eagerly + replay=1）先给到锁定期的旧快照，解锁触发的重算
        // 由 shareScope 的真实线程异步完成 —— 用 first{非空} 等它追上
        // （与 UI / autofill 消费响应式发射的方式一致；冷流时代的 first() 直读
        // 当前状态，那个契约已随缓存化失效）。
        val items = repo.observeItems(vaultId).first { it.isNotEmpty() }
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

    /**
     * 恢复回收站条目：清 `deletedDate` + 入队 `RESTORE`。
     *
     * ⚠️ **这条用例守的是"恢复不会再被打回回收站"**（2026-10-10 修的一致性缺口）。
     *
     * 修之前 `restoreItem` 是两个**独立**的 suspend 调用，顺序还是"先改行、后入队"：
     * 若入队失败，行已恢复、队列里却没有 `RESTORE` ⇒ 下次同步 `persistCiphers`
     * 用服务端旧版（仍带 `deletedDate`）覆盖它 ⇒ **条目又被打回回收站**，
     * 而用户看到的是"恢复成功"。这正是 `AtomicWriteDao` KDoc 里
     * "软删除的条目则会被服务端版本复活" 的镜像。
     *
     * ⇒ 断言分两层：
     * 1. **行为**：行清标记、队列有 RESTORE（与修复前相同，防止改坏）；
     * 2. ★ **走的是原子入口**：`atomicWriteDao` 被调用了，且行/队列入参**都在同一次调用里**
     *    —— 这才是"要么都成、要么都不成"的判据（见 [AtomicWriteBoundaryTest] 的真实回滚验证）。
     */
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
        // 原子转发最终落到这两张表（setUp 探针的 upsertCipher / upsertPendingOp 转发的就是它们），
        // 非 relaxed mock 不 stub 就抛 "no answer found"（与 507 用例的 519–521 对齐）。
        coEvery { cipherDao.upsertAll(any()) } returns Unit
        coEvery { pendingOpDao.enqueue(any()) } returns Unit
        coEvery { syncService.flushPending(vaultId, vaultId) } returns Result.success(Unit)

        val outcome = repo.restoreItem(vaultId, "cipher-5")

        assertEquals(VaultSaveOutcome.Synced, outcome.getOrThrow())
        // 行为层：行清标记 + RESTORE 入队
        val rowSlot = slot<List<CipherEntity>>()
        val opSlot = slot<PendingOpEntity>()
        coVerify(exactly = 1) { cipherDao.upsertAll(capture(rowSlot)) }
        coVerify(exactly = 1) { pendingOpDao.enqueue(capture(opSlot)) }
        assertNull(rowSlot.captured.single().deletedDate) // 主列表立即恢复显示
        val op = opSlot.captured
        assertEquals("RESTORE", op.op)
        assertEquals("cipher-5", op.cipherId)
        assertNull(op.payload)
    }

    /**
     * ★ 反向判据：`restoreItem` **必须**走原子入口，不许退回两个独立调用。
     *
     * 实现手法：给 `atomicWriteDao` 装一个**记录调用次数**的探针。
     * 若有人把 `restoreItem` 改回 `cipherDao.upsertAll(...)` + `pendingOpDao.enqueue(...)`
     * （即撤销本次修复），`upsertCipherAndEnqueue` 的计数会变成 0 ⇒ **这条红**。
     *
     * 这是"变异验证"思路：不是断言结果对，而是断言**修复本身在场**。
     */
    @Test
    fun restoreItem_goesThroughAtomicWriteDao_notTwoIndependentCalls() = runTest {
        sessions.unlock(vaultId, key)
        val existing = CipherEntity(
            id = "cipher-5",
            vaultId = vaultId,
            type = 1,
            encryptedPayload = "{}",
            revisionDate = "rev",
            deletedDate = "2026-09-08T00:00:00Z",
        )
        coEvery { cipherDao.get("cipher-5") } returns existing
        coEvery { vaultDao.get(vaultId) } returns bitwardenVaultRow()
        coEvery { cipherDao.upsertAll(any()) } returns Unit
        coEvery { pendingOpDao.enqueue(any()) } returns Unit
        coEvery { syncService.flushPending(vaultId, vaultId) } returns Result.success(Unit)

        var atomicCalls = 0
        val probed = object : AtomicWriteDao() {
            override suspend fun upsertCipher(row: CipherEntity) = cipherDao.upsertAll(listOf(row))
            override suspend fun upsertPendingOp(op: PendingOpEntity) = pendingOpDao.enqueue(op)
            override suspend fun deleteCiphers(ids: List<String>) = cipherDao.deleteByIds(ids)
            override suspend fun upsertCipherAndEnqueue(row: CipherEntity, op: PendingOpEntity) {
                atomicCalls++
                super.upsertCipherAndEnqueue(row, op)
            }
        }
        val probedRepo = ItemRepositoryImpl(
            vaultDao = vaultDao,
            cipherDao = cipherDao,
            pendingOpDao = pendingOpDao,
            atomicWriteDao = probed,
            sessions = sessions,
            mapper = mapper,
            json = BitwardenJson,
            syncService = syncService,
            kdbxSessions = KdbxSessionFlow(),
            kdbxItemWrites = kdbxItemWrites,
            cryptoDispatcher = Dispatchers.Default,
        )

        probedRepo.restoreItem(vaultId, "cipher-5")

        assertEquals("恢复必须走 upsertCipherAndEnqueue 原子入口", 1, atomicCalls)
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

    // ------------------------------------------------------------
    // B6：KDBX 通行密钥写回（2026-10-13）
    //
    // 修复前 `updateFido2Credentials` / `removeFido2Credential` 第一行就是
    // `requireNotKdbx` ⇒ KDBX 用户「读得到、存不下」：详情页能看通行密钥，
    // 一保存就得到一句"由浏览器 / 服务端管理"。现在与条目增删改同一套分流：
    // 解锁校验双会话、loadItem 走内存会话、updateItem 委托 kdbxItemWrites
    // （写回由 `KdbxItemWriter.applyPasskeys` 承担，私钥强制受保护）。
    // ------------------------------------------------------------

    @After
    fun tearDownKdbxSessions() {
        // `KdbxSessionStore` 是进程级单例：B6 用例播的种必须清掉，
        // 否则同 JVM 后续用例会看到一个"已经解锁"的 KDBX 库。
        Kdbx.lock(vaultId)
    }

    /**
     * KDBX 上的保存：委托给 KDBX 写回实现，Room 一个字节都不碰。
     *
     * 三条判据，第三条才是"没有幽灵数据"：
     * 1. 委托**确实发生**（反证分流没被删掉——删了会走 Bitwarden 老路报"库未解锁"）；
     * 2. 委托体 = 原条目（标题/用户名原样带着）+ 通行密钥（丢字段 = 静默数据丢失）；
     * 3. Room 侧**完全安静**（行、队列都不许动）。
     */
    @Test
    fun updateFido2Credentials_onKdbxVault_delegatesToKdbxWriteAndTouchesNothingInRoom() = runTest {
        val itemId = seedKdbxVaultWithLoginItem()
        val cred = VaultFido2Credential(
            credentialId = "cred-kdbx-1",
            rpId = "github.com",
            rpName = "GitHub",
            userName = "alice@example.com",
        )
        val captured = slot<VaultItem>()
        coEvery { kdbxItemWrites.update(vaultId, capture(captured)) } returns
            Result.success(VaultSaveOutcome.Queued)

        val outcome = repo.updateFido2Credentials(vaultId, itemId, listOf(cred))

        assertTrue("应当成功，实际：${outcome.exceptionOrNull()}", outcome.isSuccess)
        // ① 委托确实发生
        coVerify(exactly = 1) { kdbxItemWrites.update(vaultId, any()) }
        // ② 委托体 = 原条目 + 通行密钥（领域对象原样透传，不重新解码）
        val written = captured.captured
        assertEquals(itemId, written.id)
        assertEquals("GitHub", written.title)
        assertEquals("alice@example.com", written.username)
        assertEquals(listOf(cred), written.fido2Credentials)
        // ③ Room 侧完全安静
        coVerify(exactly = 0) { cipherDao.upsertAll(any()) }
        coVerify(exactly = 0) { pendingOpDao.enqueue(any()) }
    }

    /**
     * 删除单条通行密钥：只摘掉那一条，其余原样带回写。
     *
     * 播种时直接给条目挂**两条完整凭证**（`KdbxItemWriter.applyPasskeys` 新建即生效）。
     * 必须完整：`KdbxPasskeyCodec.toCredential` 要求 rpId / credentialId /
     * privateKeyPem / userName / userHandle 五项非空才回认凭证（缺一项就当普通条目）。
     * 也不借道 `updateFido2Credentials` 预写——那一条走的是 **mock**，并不会真的
     * 改会话，第二次 loadItem 会读到空集合，"只剩一条"将因"从来只有一条"假绿。
     */
    @Test
    fun removeFido2Credential_onKdbxVault_removesOnlyThatCredential() = runTest {
        val itemId = seedKdbxVaultWithLoginItem(
            item = VaultItem(
                id = "",
                title = "GitHub",
                username = "alice@example.com",
                password = "s3cret-pass",
                fido2Credentials = listOf(
                    fullCredential("cred-keep", "gitlab.com", "GitLab"),
                    fullCredential("cred-drop", "github.com", "GitHub"),
                ),
            ),
        )
        // 反证：会话里确实是两条（否则"删完剩一条"可能因为本来就只有一条而假绿）
        assertEquals(2, Kdbx.contentOf(vaultId)!!.items.single().fido2Credentials.size)

        val captured = slot<VaultItem>()
        coEvery { kdbxItemWrites.update(vaultId, capture(captured)) } returns
            Result.success(VaultSaveOutcome.Queued)

        val outcome = repo.removeFido2Credential(vaultId, itemId, "cred-drop")

        assertTrue("应当成功，实际：${outcome.exceptionOrNull()}", outcome.isSuccess)
        val remaining = captured.captured.fido2Credentials
        assertEquals(1, remaining.size)
        assertEquals("cred-keep", remaining.single().credentialId)
    }

    /** 安全笔记不是登录条目 ⇒ 拒绝（两个库同一口径，KDBX 侧由类型守卫拦下）。 */
    @Test
    fun updateFido2Credentials_onKdbxVault_rejectsSecureNote() = runTest {
        // ⚠️ 必须带**非空 notes**：KDBX 侧 SecureNote 是推导出来的
        // （无用户名 + 无密码 + 有备注，见 KdbxItemMapper.toVaultItem），
        // 空备注会被映射回 Login，类型守卫便不会触发（本用例第一版即如此，已红）。
        val itemId = seedKdbxVaultWithLoginItem(
            item = VaultItem(id = "", title = "会议笔记", notes = "周会纪要", type = VaultItemType.SecureNote),
        )

        val outcome = repo.updateFido2Credentials(
            vaultId,
            itemId,
            listOf(VaultFido2Credential(credentialId = "cred-x", rpId = "github.com")),
        )

        assertTrue(outcome.isFailure)
        assertTrue(outcome.exceptionOrNull()!!.message!!.contains("通行密钥只能绑定到登录（密码）条目"))
        coVerify(exactly = 0) { kdbxItemWrites.update(any(), any()) }
    }

    /**
     * 库未解锁 ⇒ 明确的"库未解锁"（而不是绕道 loadItem 报一句「条目不存在」——
     * 那与真实原因无关：KDBX 条目不在 Room，未解锁时 loadItem 必然返回 null）。
     */
    @Test
    fun updateFido2Credentials_onKdbxVault_failsWhenLocked() = runTest {
        coEvery { vaultDao.get(vaultId) } returns kdbxVaultRow()
        // 不播种会话 ⇒ Kdbx.isUnlocked(vaultId) == false

        val outcome = repo.updateFido2Credentials(
            vaultId,
            "no-such-item",
            listOf(VaultFido2Credential(credentialId = "cred-x", rpId = "github.com")),
        )

        assertTrue(outcome.isFailure)
        assertEquals("库未解锁，无法保存：$vaultId", outcome.exceptionOrNull()?.message)
    }

    // ---- B6 夹具 ----

    /**
     * 用**公开 API** 播种一个真实的 KDBX 会话，再往会话里塞一个条目，
     * 返回该条目的 id。
     *
     * `KdbxSessionStore` 是 `data:kdbx` 的 internal 类型，本模块测试摸不到
     * ⇒ 只能走 `Kdbx.createVault` 这条唯一公开入口（与 `KdbxCreateRepositoryTest`
     * 同一手法）。主密码只喂内存库，不进任何断言。
     */
    private suspend fun seedKdbxVaultWithLoginItem(
        item: VaultItem = VaultItem(
            id = "",
            title = "GitHub",
            username = "alice@example.com",
            password = "s3cret-pass",
        ),
    ): String {
        coEvery { vaultDao.get(vaultId) } returns kdbxVaultRow()
        Kdbx.createVault(vaultId, inMemorySource(), "测试库", "correct horse battery staple")
            .getOrThrow()
        Kdbx.createItem(vaultId, folderId = null, item = item).getOrThrow()
        return Kdbx.contentOf(vaultId)!!.items.single().id
    }

    /**
     * 内存文件来源。`read()` 刻意抛错：新建路径**不该**读远端
     * （调了就让用例红，而不是静默返回空数组）。
     */
    private fun inMemorySource(): KdbxFileSource = mockk {
        coEvery { stat() } returns KdbxFileStat(versionToken = "token")
        coEvery { write(bytes = any(), expectedVersion = any(), force = any(), failIfExists = any()) } returns
            KdbxFileWriteResult(versionToken = "token")
        coEvery { read() } throws AssertionError("新建路径不应读远端文件")
    }

    /**
     * 一条能被 `toCredential` 回认的完整凭证（五项必填非空，见用例注释）。
     * credentialId 取非 base64 串 ⇒ 写读往返原样（编解码对旧数据不硬转换）。
     */
    private fun fullCredential(credentialId: String, rpId: String, rpName: String) =
        VaultFido2Credential(
            credentialId = credentialId,
            rpId = rpId,
            rpName = rpName,
            userName = "alice@example.com",
            userDisplayName = "alice@example.com",
            userHandle = "user-handle-$credentialId",
            keyValue = "-----BEGIN PRIVATE KEY-----\nMC4CAQAwBQYDK2Vw\n-----END PRIVATE KEY-----",
            keyType = "public-key",
            keyCurve = "P-256",
            counter = 0L,
            discoverable = true,
        )

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

    // ==================================================================
    // ★ 结构判据：「行 + 队列」的六处写路径必须整体走原子入口
    // ==================================================================

    /**
     * ★★ 遍历 `ItemRepositoryImpl` 的**全部写路径**，断言每一处都经由
     * `AtomicWriteDao` 的原子入口 —— 没有任何一处退回「行」「队列」两次独立写。
     *
     * ## 为什么要"全部"而不是逐条断言
     *
     * S1 的成因不是某一处写错了，而是**这个模式有 5 个复制点、修了 2 个、漏了 3 个**：
     * `restoreItem` / `permanentDeleteItem` / `cleanupExpiredTrash` 当时都是两个独立调用。
     * 逐条用例只能守住"已经想到的那几条"，新增一处写路径时**没有任何东西会提醒你**。
     *
     * 这条判据把「六处」这个集合本身钉在测试里：
     * - 新增写路径时，若它绕开原子入口 ⇒ [atomicCalls] 计数不够 ⇒ **红**；
     * - 有人把某处改回两次独立写（撤销修复）⇒ 同样**红**。
     *
     * ## 手法
     *
     * 给 `atomicWriteDao` 装一个探针子类，记录三个原语各自的调用次数；
     * 然后依次触发六条写路径，逐条比对**期望的入口**。
     *
     * ⚠️ 探针只计数、不校验入参 —— 入参正确性由上面各条行为用例负责（分工见
     * [AtomicWriteBoundaryTest] 的类 KDoc 表格）。
     */
    @Test
    fun allSixWritePathsGoThroughAtomicDao() = runTest {
        sessions.unlock(vaultId, key)

        // 记录三个原子入口各自被调用的次数
        var upsertCalls = 0
        var deleteOneCalls = 0
        var deleteBatchCalls = 0
        val probed = object : AtomicWriteDao() {
            override suspend fun upsertCipher(row: CipherEntity) = cipherDao.upsertAll(listOf(row))
            override suspend fun upsertPendingOp(op: PendingOpEntity) = pendingOpDao.enqueue(op)
            override suspend fun deleteCiphers(ids: List<String>) = cipherDao.deleteByIds(ids)
            override suspend fun upsertCipherAndEnqueue(row: CipherEntity, op: PendingOpEntity) {
                upsertCalls++
                super.upsertCipherAndEnqueue(row, op)
            }

            override suspend fun deleteCipherAndEnqueue(cipherId: String, op: PendingOpEntity) {
                deleteOneCalls++
                super.deleteCipherAndEnqueue(cipherId, op)
            }

            override suspend fun deleteCiphersAndEnqueue(ids: List<String>, ops: List<PendingOpEntity>) {
                deleteBatchCalls++
                super.deleteCiphersAndEnqueue(ids, ops)
            }
        }
        val probedRepo = ItemRepositoryImpl(
            vaultDao = vaultDao,
            cipherDao = cipherDao,
            pendingOpDao = pendingOpDao,
            atomicWriteDao = probed,
            sessions = sessions,
            mapper = mapper,
            json = BitwardenJson,
            syncService = syncService,
            kdbxSessions = KdbxSessionFlow(),
            kdbxItemWrites = kdbxItemWrites,
            cryptoDispatcher = Dispatchers.Default,
        )

        // —— 公共桩 ——
        coEvery { cipherDao.upsertAll(any()) } returns Unit
        coEvery { pendingOpDao.enqueue(any()) } returns Unit
        coEvery { cipherDao.deleteByIds(any()) } returns Unit
        val plain = plainItem("cipher-atomic")
        coEvery { cipherDao.get(plain.id) } returns CipherEntity(
            id = plain.id,
            vaultId = vaultId,
            type = 1,
            encryptedPayload = """{"id":"${plain.id}"}""",
            revisionDate = "rev",
            deletedDate = null,
        )
        val inTrash = trashRow("cipher-trash", Instant.now().minus(Duration.ofDays(60)))
        coEvery { cipherDao.get(inTrash.id) } returns inTrash
        // 回收站行：deletedDate 取"60 天前"（比 30 天档位更早）⇒ 一定过期。
        // 注意 `trashRow` 写的是 `deletedAt.toString()`，与 TrashCleanupPolicy 的
        // `Instant.parse` 同格式，故必然能解析、必然判为过期（非"宁可保留"的兜底路径）。
        coEvery { cipherDao.getTrashByVault(vaultId) } returns listOf(inTrash)
        // 恢复（④）专用：一条真正在回收站里的行。restoreItem 要求 deletedDate != null
        // （ItemRepositoryImpl.kt:365 的存在性守卫）⇒ 不能复用 plain.id —— mock 不会因 ③ 的
        // 软删而改变 get 的返回（cipher-atomic 的 deletedDate 恒为 null）。
        val forRestore = trashRow("cipher-restore", Instant.now().minus(Duration.ofDays(60)))
        coEvery { cipherDao.get("cipher-restore") } returns forRestore
        coEvery { syncService.flushPending(any(), any()) } returns Result.success(Unit)

        // ① 新增（CREATE）
        probedRepo.createItem(vaultId, plain).getOrThrow()
        assertEquals("createItem 必须走原子入口", 1, upsertCalls)

        // ② 编辑（UPDATE）
        probedRepo.updateItem(vaultId, plain.copy(title = "改过的标题")).getOrThrow()
        assertEquals("updateItem 必须走原子入口", 2, upsertCalls)

        // ③ 软删除（SOFT_DELETE）
        probedRepo.softDeleteItem(vaultId, plain.id).getOrThrow()
        assertEquals("softDeleteItem 必须走原子入口", 3, upsertCalls)

        // ④ 恢复（RESTORE）—— 本轮修复点之一
        // ⚠️ 恢复的必须是一条 deletedDate != null 的回收站行（上面 forRestore），
        //    复用 plain.id 会踩 ItemRepositoryImpl.kt:365 的"条目不在回收站中"守卫。
        probedRepo.restoreItem(vaultId, "cipher-restore").getOrThrow()
        assertEquals("★ restoreItem 必须走原子入口（本轮修复点）", 4, upsertCalls)

        // ⑤ 永久删除（DELETE）—— 本轮修复点之二
        probedRepo.permanentDeleteItem(vaultId, inTrash.id).getOrThrow()
        assertEquals("★ permanentDeleteItem 必须走单删原子入口（本轮修复点）", 1, deleteOneCalls)

        // ⑥ 回收站到期清理（批量 DELETE）—— 本轮修复点之三
        probedRepo.cleanupExpiredTrash(vaultId, 30)
        assertEquals("★ cleanupExpiredTrash 必须走批量原子入口（本轮修复点）", 1, deleteBatchCalls)

        // 反向：禁止任何写路径绕过原子入口直接写两张表
        coVerify(exactly = 4) { cipherDao.upsertAll(any()) }
        // deleteByIds 计 2：⑤ 永久删除（deleteCipherAndEnqueue → deleteCiphers([id])）与
        // ⑥ 批量清理（deleteCiphersAndEnqueue → deleteCiphers(ids)）各触发一次 ——
        // 见 AtomicWriteDao.deleteCipherAndEnqueueInTransaction / deleteCiphersAndEnqueueInTransaction。
        coVerify(exactly = 2) { cipherDao.deleteByIds(any()) }
        coVerify(exactly = 6) { pendingOpDao.enqueue(any()) }
    }
}
