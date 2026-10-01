package io.vaultix.data.repository.kdbx

// ⚠️ `coEvery` 的 stub 里**不能裸写 `get(...)` / `upsert(...)`**：
//    那些名字会被 `MockKMatcherScope` 的同名成员遮蔽（`.ai/ISSUES.md` #146），
//    必须显式写 receiver（`delegate.get(...)`）。
//    `coAnswers` / `firstArg` 等是 `MockKAnswerScope` 的成员，**不需要 import**。
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.vaultix.data.kdbx.Kdbx
import io.vaultix.data.kdbx.KdbxFileSource
import io.vaultix.data.kdbx.KdbxFileStat
import io.vaultix.data.kdbx.KdbxFileWriteResult
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.database.dao.VaultDao
import io.vaultix.database.entity.VaultEntity
import io.vaultix.domain.NewKdbxVaultOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `KdbxCreateRepositoryImpl` 的**新建 KDBX 库**纪律。
 *
 * ## 本文件守的三件事
 *
 * 1. **origin 以实际来源为准，而不是请求时那个 URI**（[origin 取来源实际报的_而不是请求时那个]）。
 *    这条最容易在评审时被当成"多此一举"删掉 —— 它是"库在列表里却怎么点都打不开"
 *    这类只在**部分** SAF provider 上复现的故障的唯一防线。
 * 2. **失败时绝不留下 vaults 行**（[创建失败_不得在列表里留下打不开的行]）。
 *    顺序必须是「先建文件、后登记」。
 * 3. **首个库设为默认**只在真的新增时发生（[已存在的同路径_不重复设默认库]）。
 *
 * ## 弱断言与反证
 *
 * 第 2 条是**断言"没发生"**（"没有 upsert"）—— 它可能因为"整条路径压根没跑"而假绿。
 * ⇒ 配了 [创建成功_落一行且 origin 正确] 作为反证：同一套装置下，成功路径**确实**
 * 落了一行。两条一起读才知道"没落行"是真的没落，而不是装置坏了。
 */
class KdbxCreateRepositoryTest {

    @After
    fun tearDown() {
        // `Kdbx.createVault` 会把会话登记进进程级单例 ⇒ 测试之间必须清干净，
        // 否则"建了两个库"的用例会看到上一个用例的残留。
        Kdbx.lock(VAULT_URI)
        Kdbx.lock(RESOLVED_URI)
    }

    /**
     * **成功路径**（同时是下面"不留行"那条的反证）：建库成功后必须落一行，
     * 且 `origin` / `id` 用的是**来源实际报的** URI。
     */
    @Test
    fun `创建成功_落一行且 origin 正确`() = runTest {
        val dao = RecordingVaultDao()
        val repo = repositoryWith(dao = dao, source = fakeSource(RESOLVED_URI))

        val outcome = repo.createVault(
            targetUri = VAULT_URI,
            displayName = "我的库",
            masterPassword = PASSWORD,
        )

        assertTrue("应当成功，实际：$outcome", outcome is NewKdbxVaultOutcome.Created)
        val row = dao.saved.single()
        assertEquals(RESOLVED_URI, row.id)
        assertEquals(RESOLVED_URI, row.origin)
        assertEquals("我的库", row.displayName)
        // 新建的库必须是**已解锁**的（`createVault` 顺手登记了会话）。
        assertTrue("新库应当是已解锁的", Kdbx.isUnlocked(RESOLVED_URI))
    }

    /**
     * ★ 本文件最要紧的一条：**origin 以来源实际报的为准**。
     *
     * 装置让 `targetUri`（请求时那个）与 `stat().remoteId`（来源实际报的）**不同** ——
     * 这正是部分 SAF provider（Downloads 之类）的行为。若实现图省事直接用 `targetUri`，
     * 这条会红。
     */
    @Test
    fun `origin 取来源实际报的_而不是请求时那个`() = runTest {
        val dao = RecordingVaultDao()
        val repo = repositoryWith(dao = dao, source = fakeSource(RESOLVED_URI))

        val outcome = repo.createVault(
            targetUri = VAULT_URI,
            displayName = "我的库",
            masterPassword = PASSWORD,
        )

        val created = outcome as NewKdbxVaultOutcome.Created
        assertEquals(RESOLVED_URI, created.origin)
        assertEquals(RESOLVED_URI, created.vaultId)
        // 反证：两个 URI 确实不同 —— 否则上面两条断言在"根本没校对"时也会通过。
        assertNotEquals(VAULT_URI, RESOLVED_URI)
    }

    /**
     * **失败时不得留下 vaults 行**（断言"没发生"）。
     *
     * 顺序必须是「先建文件、后登记」：反过来的话，`createVault` 失败（目标不可写 /
     * KDF 被拒）就会留下**一行指向不存在库的记录** —— 用户看到列表里多了一项、
     * 点进去永远报错，而列表里**没有"删掉它"的入口**。
     */
    @Test
    fun `创建失败_不得在列表里留下打不开的行`() = runTest {
        val dao = RecordingVaultDao()
        // 来源的 write 会抛（模拟"目标不可写"）。
        val repo = repositoryWith(dao = dao, source = failingWriteSource(RESOLVED_URI))

        val outcome = repo.createVault(
            targetUri = VAULT_URI,
            displayName = "我的库",
            masterPassword = PASSWORD,
        )

        assertTrue("应当失败，实际：$outcome", outcome is NewKdbxVaultOutcome.Failed)
        // ★ 一行都不许落。
        //   ⚠️ 用手写替身自己的记录断言，**不要**用 `coVerify`：`RecordingVaultDao`
        //   是个普通类、不是 mockk 代理，`coVerify` 对它会直接抛
        //   "not a mock"（而且报错信息与测试意图毫无关系）。
        assertTrue("失败路径不允许写 vaults", dao.saved.isEmpty())
    }

    /**
     * 同一个路径重复创建（例如用户换了密码重来）**不该**重复设默认库。
     *
     * 与 `addKdbxVault` 的 `Updated` 分支同一条纪律：重复动作没有"首次"的副作用。
     */
    @Test
    fun `已存在的同路径_不重复设默认库`() = runTest {
        val dao = RecordingVaultDao(existing = RESOLVED_URI)
        val prefs = mockk<VaultixPreferences>(relaxed = true)
        val repo = repositoryWith(dao = dao, source = fakeSource(RESOLVED_URI), preferences = prefs)

        repo.createVault(
            targetUri = VAULT_URI,
            displayName = "我的库",
            masterPassword = PASSWORD,
        )

        // 已经存在 ⇒ 不该再设默认（那是"首个库"才有的语义）。
        coVerify(exactly = 0) { prefs.trySetDefaultVaultIfAbsent(any()) }
    }

    /** 来源解析不出来 ⇒ 失败，且**一行都不落**（还没碰过任何文件，安全）。 */
    @Test
    fun `来源解析不出来_失败且不落行`() = runTest {
        val dao = RecordingVaultDao()
        // resolver 返回 null（origin 没有可用来源）。
        val repo = KdbxCreateRepositoryImpl(
            vaultDao = dao,
            fileSourceResolver = { null },
            preferences = mockk(relaxed = true),
            kdbxSessions = mockk(relaxed = true),
        )

        val outcome = repo.createVault(
            targetUri = VAULT_URI,
            displayName = "我的库",
            masterPassword = PASSWORD,
        )

        assertTrue(outcome is NewKdbxVaultOutcome.Failed)
        assertTrue(dao.saved.isEmpty())
    }

    // ------------------------------------------------------------ 装置

    /**
     * `stat()` 报 [reportedId]、`write()` 成功；`read()` 不该被新建路径调用
     * （调了就让用例红，而不是静默返回空数组）。
     */
    private fun fakeSource(reportedId: String): KdbxFileSource = mockk {
        coEvery { stat() } returns KdbxFileStat(
            versionToken = "token",
            sizeBytes = 0,
            remoteId = reportedId,
            displayName = "vault.kdbx",
        )
        coEvery { write(bytes = any(), expectedVersion = any(), force = any()) } returns
            KdbxFileWriteResult(versionToken = "token", sizeBytes = 0, remoteId = reportedId)
        coEvery { read() } throws AssertionError("新建路径不应读远端文件")
    }

    /** `write()` 抛异常（模拟目标不可写 / 写后读回校验失败）。 */
    private fun failingWriteSource(reportedId: String): KdbxFileSource = mockk {
        coEvery { stat() } returns KdbxFileStat(
            versionToken = "token",
            sizeBytes = 0,
            remoteId = reportedId,
            displayName = "vault.kdbx",
        )
        coEvery { write(bytes = any(), expectedVersion = any(), force = any()) } throws
            IllegalStateException("写入后读回校验不一致")
    }

    private fun repositoryWith(
        dao: VaultDao,
        source: KdbxFileSource,
        preferences: VaultixPreferences = mockk(relaxed = true),
    ): KdbxCreateRepositoryImpl = KdbxCreateRepositoryImpl(
        vaultDao = dao,
        fileSourceResolver = { source },
        preferences = preferences,
        kdbxSessions = mockk(relaxed = true),
    )

    /**
     * 只记 `upsert` 的 VaultDao 替身。
     *
     * ⚠️ **手写实现**（而不是 `VaultDao by mockk()` 委托）：`VaultDao` 只有 6 个方法，
     * 手写一遍的成本远低于委托带来的风险 —— 委托会让"没 stub 到的方法"走到
     * mockk 的默认行为上（`suspend` 方法默认抛异常或返回 null），
     * 而失败现场是一句与测试意图无关的 mockk 报错。
     * `KdbxSyncOrchestratorTest` 的 `RecordingDao` 是同样写法。
     */
    private class RecordingVaultDao(private val existing: String? = null) : VaultDao {
        val saved = mutableListOf<VaultEntity>()

        override suspend fun get(id: String): VaultEntity? =
            if (id == existing) EXISTING_ROW else null

        override suspend fun upsert(vault: VaultEntity) {
            saved += vault
        }

        override fun observeAll(): Flow<List<VaultEntity>> = flowOf(emptyList())

        override suspend fun updateRevision(id: String, revision: String?) = Unit

        override suspend fun updateSyncState(
            id: String,
            status: String?,
            versionToken: String?,
            syncedAt: Long?,
        ) = Unit

        override suspend fun delete(id: String) = Unit
    }

    private companion object {
        const val VAULT_URI = "content://com.android.providers.downloads/req-1"
        const val RESOLVED_URI = "content://com.android.providers.downloads/document/1"
        const val PASSWORD = "correct horse battery staple"
        val EXISTING_ROW = VaultEntity(
            id = RESOLVED_URI,
            kind = "KDBX",
            displayName = "旧库",
            origin = RESOLVED_URI,
            account = null,
            revisionDate = null,
            createdAt = 0L,
        )
    }
}
