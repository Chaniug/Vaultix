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
/**
 * 一个形状正确的 OneDrive origin（`onedrive:<accountId>:<base64url 路径>`）。
 *
 * ⚠️ 用 `OneDriveVaultOrigin` 现算而不是手抄字面量：`data:repository` 不认识那个类
 * （它在 app 层），但**测试**认的是"前缀对得上、路径能解回来"这两件事 ——
 * 手抄一个字面量的话，将来 origin 编解码一改，这条用例就会在**与它无关的地方**变红。
 * 这里的 accountId / 路径都是假的，不含任何凭据。
 */
private object OneDriveOriginFixture {
    val origin: String = buildString {
        append("onedrive:")
        append("acc-fixture")
        append(':')
        append(
            java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("Vaultix/vault.kdbx".toByteArray(Charsets.UTF_8)),
        )
    }
}

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

    /**
     * ★ 新建**必须**以「只创建」的语义写入（ISSUES #172）。
     *
     * 为什么这条值得单独一个用例：`Kdbx.createVault` 是先 `stat()` 确认目标为空、
     * 再 `write()`。如果 `write` 不声明 `failIfExists = true`，那两次调用之间
     * 别人正好建了同名库（多设备 / 另一台机器同步过来），我们就会**直接覆盖它** ——
     * 而 `stat` 检查在那一瞬间已经过期了（TOCTOU）。声明之后，
     * 服务端（OneDrive/WebDAV）会用 `If-None-Match: *` 替我们兜住。
     *
     * ⚠️ 这条断言的是**调用方的意图**，不是服务端行为 ——
     * 服务端那一半由 `OneDriveGraphClientWriteTest` 与 WebDAV 的条件头保证，
     * 两边合起来才是完整的一条链。少了任何一半，另一半点亮也没有意义。
     */
    @Test
    fun `新建库必须声明只创建_以免覆盖同名文件`() = runTest {
        val dao = RecordingVaultDao()
        val source = fakeSource(RESOLVED_URI)
        val repo = repositoryWith(dao = dao, source = source)

        repo.createVault(
            targetUri = VAULT_URI,
            displayName = "我的库",
            masterPassword = PASSWORD,
        )

        coVerify(exactly = 1) {
            source.write(
                bytes = any(),
                expectedVersion = any(),
                force = any(),
                failIfExists = true,
            )
        }
    }

    /**
     * ★★ **网盘来源的 origin 必须原样保留，不能被 `remoteId` 顶掉**（2026-11-02 修）。
     *
     * ## 这个 bug 长什么样
     *
     * `stat().remoteId` 的语义按来源不同：SAF 给的是"provider 下文件的真实 URI"（要用它），
     * 而 OneDrive 给的是 **itemId**（形如 `01ABCDEF…`）。旧代码一视同仁地拿它当 origin ⇒
     * `vaults.id` 变成一个光秃秃的 itemId，而 `fileSourceFor(origin)` 只认
     * `onedrive:` / `webdav:` 前缀 ⇒ 建库"成功"、**下次解锁报"没有可用的文件来源"**，
     * 而且列表里没有任何入口能改回来（不可恢复）。
     *
     * ⇒ 断言的很直白：网盘 origin 建库后，落库的 `origin` 与 `id` **仍然等于 targetUri**。
     */
    @Test
    fun `网盘来源建库_origin必须原样保留而不是用itemId`() = runTest {
        val dao = RecordingVaultDao()
        val cloudOrigin = OneDriveOriginFixture.origin
        // 来源报的 remoteId 是 itemId —— 它**不是** origin。
        val repo = repositoryWith(dao = dao, source = fakeSource("01ABCDEF-item-id"))

        val outcome = repo.createVault(
            targetUri = cloudOrigin,
            displayName = "云库",
            masterPassword = PASSWORD,
        )

        assertTrue("应当成功，实际：$outcome", outcome is NewKdbxVaultOutcome.Created)
        val row = dao.saved.single()
        assertEquals("网盘的 origin 必须保持 targetUri 原样", cloudOrigin, row.origin)
        assertEquals(cloudOrigin, row.id)
        // 反证：remoteId 确实与 origin 不同 —— 否则上面两条在"根本没校对"时也会通过。
        assertNotEquals("01ABCDEF-item-id", cloudOrigin)
    }

    // -------------------------------------------------- 同步状态的初始值

    /**
     * ★ 新建的**网盘**库必须带上同步状态，不能留 null（2026-11-02 修；2026-11-03 #242 改判据）。
     *
     * ## 留 null 会发生什么（两件，都不报错）
     *
     * 1. `vaults.syncStatus` 的 null 语义是「该库**不适用**网盘同步」；
     * 2. `VaultListScreen` 有 `onSyncNow = if (vault.syncStatus != null)` ⇒
     *    新建的云库**没有「立即同步」入口**，用户只能退出重进才等得到自动同步。
     *
     * ## 为什么状态是 `IN_SYNC` 而**不再**是 `PENDING_UPLOAD`（#242）
     *
     * `PENDING_UPLOAD` 的语义是"本地有东西没推上去"。可刚建完的库是**本次刚写上去的**，
     * 远端此刻就是这一版 —— 说"待上传"既不是事实，还会触发自动上传。
     * 更要命的是它同时带着 `impliesLocalChanges = true` 而**基线令牌为空**（旧行为），
     * 编排器于是判 `isConflict(true, true)` ⇒ **用户什么都没改就被报冲突**。
     * ⇒ 现在建完立刻 stat，把刚写上去那一版的令牌记成基线，状态落 `IN_SYNC`。
     */
    @Test
    fun `新建网盘库_同步状态必须与远端对齐而不是null`() = runTest {
        val dao = RecordingVaultDao()
        val repo = repositoryWith(dao = dao, source = fakeSource("01ABCDEF-item-id"))

        repo.createVault(
            targetUri = OneDriveOriginFixture.origin,
            displayName = "云库",
            masterPassword = PASSWORD,
        )

        val update = dao.syncUpdates.single()
        assertEquals("刚建完的云库与远端就是同一版", "IN_SYNC", update.status)
        assertEquals("状态必须挂在这个库上", OneDriveOriginFixture.origin, update.id)
        // ★ 基线必须真的写进去：留空 ⇒ 下一次同步 `remoteChanged` 恒真 ⇒ 误报冲突。
        assertEquals("建库后必须带上基线令牌", "token", update.versionToken)
    }

    /**
     * 反证：**拿不到令牌**时不许假装对齐 ⇒ 退回"待上传 + 令牌留空"（保守，报一次冲突）。
     *
     * ⚠️ 这条守的是"别把 #242 修成另一种静默错误"：为了让用户不看到冲突而
     * 无条件写 `IN_SYNC` 是**没有依据的断言**（我们压根不知道远端是哪一版）。
     * 唯一的例外是内容对账过且一致 —— 这里 `readRemoteCurrent` 抛异常，走不到那一步。
     */
    @Test
    fun `网盘库拿不到令牌_退回待上传而不是假装对齐`() = runTest {
        val dao = RecordingVaultDao()
        val repo = repositoryWith(dao = dao, source = noTokenSource())

        repo.createVault(
            targetUri = OneDriveOriginFixture.origin,
            displayName = "云库",
            masterPassword = PASSWORD,
        )

        val update = dao.syncUpdates.single()
        assertEquals("判不出来时必须保守", "PENDING_UPLOAD", update.status)
        assertEquals("不该凭空造一个令牌", null, update.versionToken)
    }

    /**
     * 反证：**本地 SAF** 库必须留 null。
     *
     * 给它标 `PENDING_UPLOAD` 会让 UI 在一个根本没有云端的库上渲染「待上传」角标，
     * 而且自动上传会为一个连网盘来源都没有的库空转。
     */
    @Test
    fun `新建本地SAF库_同步状态必须留null`() = runTest {
        val dao = RecordingVaultDao()
        val repo = repositoryWith(dao = dao, source = fakeSource(RESOLVED_URI))

        repo.createVault(targetUri = VAULT_URI, displayName = "本地库", masterPassword = PASSWORD)

        assertEquals("本地库不该有同步状态", null, dao.syncUpdates.single().status)
    }

    /**
     * 重复创建同一路径时，**不许**把已有的基线令牌抹掉。
     *
     * ⚠️ 这条是"顺手用 upsert 一起写"会踩的坑：`upsert` 是整行替换，漏抄一列就是清空一列。
     * 基线被清空 ⇒ 下一轮同步会给一个从没拉过的库报"远端变了"，把用户直接推向冲突对话框。
     */
    @Test
    fun `重复创建同一路径_不得抹掉已有基线令牌`() = runTest {
        val existingToken = "existing-etag"
        val dao = RecordingVaultDao(existing = RESOLVED_URI).apply {
            // ⚠️ 旧行**必须真的带令牌**：装置默认那条（EXISTING_ROW）令牌是 null，
            //    拿它来测"不许抹掉"会在实现改成 `versionToken = null` 时照样绿 ——
            //    一条永远绿的用例比没有更糟（它让人以为守住了）。
            existingRow = EXISTING_ROW.copy(remoteVersionToken = existingToken, lastSyncedAt = 111L)
        }
        val repo = repositoryWith(dao = dao, source = fakeSource(RESOLVED_URI))

        repo.createVault(targetUri = VAULT_URI, displayName = "本地库", masterPassword = PASSWORD)

        val update = dao.syncUpdates.single()
        assertEquals("已有基线令牌必须原样带回（漏抄一列 = 静默清空一列）", existingToken, update.versionToken)
        assertEquals("已有同步时间同样要带回", 111L, update.syncedAt)
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
            localUnlockEnrollment = mockk(relaxed = true),
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
        coEvery { write(bytes = any(), expectedVersion = any(), force = any(), failIfExists = any()) } returns
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
        coEvery { write(bytes = any(), expectedVersion = any(), force = any(), failIfExists = any()) } throws
            IllegalStateException("写入后读回校验不一致")
    }

    /**
     * 建库能成功、但 **`stat()` 不给版本令牌**、远端也读不回来（离线 / 凭据过期）。
     *
     * ⚠️ 专门用来验"判不出来时保守"：`adoptRemoteBaseline` 走到 Unknown ⇒
     * 调用方必须退回 `PENDING_UPLOAD`，而**不是**写 `IN_SYNC`。
     * `readRemoteCurrent()` 抛异常是刻意的：它让内容对账这条退路也失效
     * （对账要求"读得到远端"），正是真机上 Offline 的样子。
     */
    private fun noTokenSource(): KdbxFileSource = mockk {
        coEvery { stat() } returns KdbxFileStat(
            versionToken = null,
            sizeBytes = 0,
            remoteId = null,
            displayName = "vault.kdbx",
        )
        coEvery { write(bytes = any(), expectedVersion = any(), force = any(), failIfExists = any()) } returns
            KdbxFileWriteResult(versionToken = null, sizeBytes = 0, remoteId = null)
        coEvery { readRemoteCurrent() } throws java.io.IOException("offline")
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
        // #241：新建库后静默纳入快速解锁。relaxed mock ⇒ autoEnrollNewVault 返回 false，
        // 对既有断言（只关心 dao / 文件读写）零影响。
        localUnlockEnrollment = mockk(relaxed = true),
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

        /**
         * 每一次 `updateSyncState` 的入参。
         *
         * ⚠️ 之所以要单独记：同步状态**不走** `upsert`（那会把不归本次管的列一起替换掉），
         * 所以只盯 `saved` 是看不见它的 —— 而这正是"建库后 syncStatus 是 null"这个
         * bug 能被漏掉整整一轮的原因。
         */
        val syncUpdates = mutableListOf<SyncUpdate>()

        /** 可替换的旧行：测"不许抹掉基线"那条时必须让旧行**真的带令牌**。 */
        var existingRow: VaultEntity? = EXISTING_ROW.takeIf { existing != null }

        override suspend fun get(id: String): VaultEntity? =
            if (id == existing) existingRow else null

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
        ) {
            syncUpdates += SyncUpdate(id, status, versionToken, syncedAt)
        }

        override suspend fun delete(id: String) = Unit
    }

    /** 一次 `updateSyncState` 的入参快照。 */
    private data class SyncUpdate(
        val id: String,
        val status: String?,
        val versionToken: String?,
        val syncedAt: Long?,
    )

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
