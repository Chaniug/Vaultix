package io.vaultix.data.repository.kdbx

// ⚠️ `coAnswers` / `secondArg` / `thirdArg` **都不需要 import**：
//    它们是 `MockKAnswerScope` 的成员函数（不是顶层函数），
//    写了 `import io.mockk.coAnswers` 反而 `Unresolved reference`（CI 上炸过）。
import io.mockk.coEvery
import io.mockk.mockk
import io.vaultix.data.kdbx.KdbxFileSource
import io.vaultix.data.kdbx.KdbxFileStat
import io.vaultix.database.dao.VaultDao
import io.vaultix.database.entity.VaultEntity
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `KdbxSyncOrchestrator` 的**三态决策与基线推进纪律**。
 *
 * ## ⚠️ 本文件的重点是「基线什么时候**不该**推进」
 *
 * `remoteVersionToken` 是条件写（`If-Match`）的基线，它的语义是
 * **「本机内容 = 远端的哪一版」**。所以推进它的唯一合法时机是
 * **本机真的跟上了远端**（拉取成功 / 强推成功）。
 *
 * 2026-10-01 修掉的真实事故：编排器在「只有远端变」这条分支里顺手把基线推进成了
 * `remoteNow`，而**本机会话其实还是旧的**。于是：
 *
 * ```
 * ① 远端变到 T1 ⇒ 报 REMOTE_CHANGED，基线被记成 T1；
 * ② 用户在本机改一笔 ⇒ PENDING_UPLOAD ⇒ 自动上传；
 * ③ 上传拿 T1 当 expectedVersion，服务端当前也正好是 T1 ⇒ 条件写**通过**；
 * ④ 而本机会话还是旧的 ⇒ 远端 T1 上另一台设备的新内容被**静默覆盖**。
 * ```
 *
 * ⇒ 最后一条用例（[远端报更新后本机又改了_再同步必须判成冲突]）就是这条链的复现。
 *
 * ## 弱断言与反证
 *
 * 「不推进基线」是**断言"没发生"** —— 它可能因为"压根没写状态"而假绿。
 * ⇒ 配了 [两边都没变_才把远端当前版本记为基线] 作为反证：
 * 同一套装置下，该推进的时候**确实**推进到了 T1。
 */
class KdbxSyncOrchestratorTest {

    @Test
    fun `远端变了而本地没改_上报需拉取且不推进基线`() = runTest {
        val dao = RecordingDao(initialToken = T0)
        val orchestrator = orchestratorWith(dao, remoteToken = T1)

        val outcome = orchestrator.sync(VAULT_ID, ORIGIN, localChangedSinceLastSync = false)

        assertEquals(SyncOutcome.RemoteNewerNeedsReload, outcome)
        assertEquals(KdbxSyncStatus.REMOTE_CHANGED.name, dao.status())
        // ★ 基线**必须**还是 T0：本机没拉过，凭什么说已经跟上了 T1。
        assertEquals(T0, dao.token())
    }

    /**
     * [远端变了而本地没改_上报需拉取且不推进基线] 的**反证**。
     *
     * 同一套装置下，「两边都没变」是**应当**推进基线的情形（此时本机确实等于远端那一版）。
     * 这里必须看到 T1 ⇒ 证明装置能观测到"推进"，上面那条"没推进"才不是假绿。
     */
    @Test
    fun `两边都没变_才把远端当前版本记为基线`() = runTest {
        val dao = RecordingDao(initialToken = T1)
        val orchestrator = orchestratorWith(dao, remoteToken = T1)

        val outcome = orchestrator.sync(VAULT_ID, ORIGIN, localChangedSinceLastSync = false)

        assertEquals(SyncOutcome.AlreadyInSync, outcome)
        assertEquals(KdbxSyncStatus.IN_SYNC.name, dao.status())
        assertEquals(T1, dao.token())
    }

    @Test
    fun `两边都改了_上报冲突且不推进基线`() = runTest {
        val dao = RecordingDao(initialToken = T0)
        val orchestrator = orchestratorWith(dao, remoteToken = T1)

        val outcome = orchestrator.sync(VAULT_ID, ORIGIN, localChangedSinceLastSync = true)

        assertEquals(SyncOutcome.NeedsUserDecision(T1), outcome)
        assertEquals(KdbxSyncStatus.CONFLICT.name, dao.status())
        // ★ 冲突时绝不推进：基线一推进，下次同步就会把"两边都改"误判成"只有本地改"。
        assertEquals(T0, dao.token())
    }

    /**
     * ★ 2026-10-01 那条**真实数据丢失链**的复现（本文件里最要紧的一条）。
     *
     * 若编排器在第一次同步时把基线推进成 T1，则第二次同步会算出
     * `remoteChanged = false`，于是走进"只有本地变 ⇒ 直接推"的分支 ——
     * 而本机会话还是旧的，推上去就把远端 T1 的新内容**静默覆盖**了。
     *
     * ⚠️ 修好之后第二次必须判成 **CONFLICT**（拒写、交用户拍板）。
     *   注意：基线被错误推进时，这条断言会拿到 `Failed`（因为测试里没有 KDBX 会话，
     *   `Kdbx.saveVia` 会返回 NotUnlocked）⇒ 断言照样失败 ⇒ 它真能抓住那个 bug。
     */
    @Test
    fun `远端报更新后本机又改了_再同步必须判成冲突`() = runTest {
        val dao = RecordingDao(initialToken = T0)
        val orchestrator = orchestratorWith(dao, remoteToken = T1)

        // ① 第一次：只有远端变 ⇒ 报"该拉一次"。
        orchestrator.sync(VAULT_ID, ORIGIN, localChangedSinceLastSync = false)
        // ② 用户没拉，直接在本地改了一笔（仓储会把它标成 PENDING_UPLOAD）。
        dao.setStatus(KdbxSyncStatus.PENDING_UPLOAD)

        // ③ 自动上传触发第二次同步：本地改了 **且** 远端也还是新的那一版。
        val second = orchestrator.sync(VAULT_ID, ORIGIN, localChangedSinceLastSync = true)

        assertTrue(
            "第二次同步必须判成冲突（拒写），实际是 $second",
            second is SyncOutcome.NeedsUserDecision,
        )
        assertEquals(KdbxSyncStatus.CONFLICT.name, dao.status())
    }

    /**
     * ★ #185 第二道防线：**"令牌变了"不等于"内容变了"**。
     *
     * 远端可能只是被重新编码了一次（同一个库经另一台设备另存），内容一字未改。
     * 此时仍报 `REMOTE_CHANGED` 会让用户白点一次"同步"，点完发现"啥也没变" ——
     * 那是最伤信任的一类交互。
     *
     * ⇒ 编排器会做**内容对账**：远端字节解出来的内容 == 本机会话内容 ⇒ 直接判一致。
     *
     * ⚠️ 本条用例的会话是空的（未解锁）：对账必然失败 ⇒ 退回保守判定。它钉的是
     *    **"对账失败不许假定一样"**这条纪律 —— 而这正是静默覆盖的入口。
     *    "对账成功则降级"由 `KdbxWriteBaselineTest` 的 `adoptBaselineIfMatches` 那几条覆盖。
     */
    @Test
    fun `远端只换令牌时对账失败也绝不假定内容相同`() = runTest {
        val dao = RecordingDao(initialToken = T0)
        val orchestrator = orchestratorWith(dao, remoteToken = T1, remoteBytes = ByteArray(0))

        val outcome = orchestrator.sync(VAULT_ID, ORIGIN, localChangedSinceLastSync = false)

        assertEquals(SyncOutcome.RemoteNewerNeedsReload, outcome)
        assertEquals(KdbxSyncStatus.REMOTE_CHANGED.name, dao.status())
        // ★ 对账没成功 ⇒ 基线一点都不能动（动了就等于承认"本机已是远端那一版"）。
        assertEquals(T0, dao.token())
    }

    // ---------------------------------------------------------------- 装置

    private fun orchestratorWith(
        dao: RecordingDao,
        remoteToken: String?,
        /** 远端字节；给了对账才会真的去 `read()`。 */
        remoteBytes: ByteArray? = null,
    ): KdbxSyncOrchestrator =
        KdbxSyncOrchestrator(
            vaultDao = dao.dao,
            fileSourceFactory = { sourceStub(remoteToken, remoteBytes) },
        )

    // ───────────── 上传成功后的收敛（#233） ─────────────

    /**
     * ★ 本地 SAF 来源**没有**"缓存令牌"这回事 ⇒ [KdbxSyncOrchestrator.recordUploaded]
     * 必须安静地什么都不做。
     *
     * ⚠️ 这条防的是"给所有来源都硬套一层缓存"：本地来源的令牌是**内容 SHA-256**，
     * 算它要读整份文件；真给它套缓存，每次保存都会多读一次整库，
     * 而收益（省一次下载）根本不存在 —— 本地本来就没有下载。
     */
    @Test
    fun `本地来源没有缓存令牌_收敛必须安静跳过`() = runTest {
        val dao = RecordingDao(initialToken = null)
        val orchestrator = KdbxSyncOrchestrator(
            vaultDao = dao.dao,
            // 裸来源（不是 CachedKdbxFileSource）—— 正是 SAF 这条路径的形状。
            fileSourceFactory = { sourceStub(remoteToken = null) },
        )

        // 关键：不抛、不改状态。
        orchestrator.recordUploaded(ORIGIN, ByteArray(8), "etag-new")

        assertEquals("收敛不该动同步状态", KdbxSyncStatus.IN_SYNC.name, dao.status())
        assertEquals("收敛不该动基线令牌", null, dao.token())
    }

    /**
     * ★ 上传成功 ⇒ 缓存令牌必须被刷新成**服务端给的新令牌**（#233 的正向）。
     *
     * ⚠️ 这里断言的是落在缓存里的值，而不是"有没有调用"：
     * 前者才是"两份真相收敛了"这件事本身。
     */
    @Test
    fun `上传成功必须把新令牌写进缓存`() = runTest {
        val dao = RecordingDao(initialToken = T0)
        val cache = InMemoryCache()
        cache.save(ORIGIN, CachedKdbxFile(ByteArray(1), T0))
        val delegate = sourceStub(remoteToken = T1)
        val cached = CachedKdbxFileSource(delegate, cache, ORIGIN, backgroundScope)
        val orchestrator = KdbxSyncOrchestrator(
            vaultDao = dao.dao,
            fileSourceFactory = { cached },
        )

        val uploaded = ByteArray(16) { it.toByte() }
        orchestrator.recordUploaded(ORIGIN, uploaded, T1)
        runCurrent()

        assertEquals(
            "不刷新就会出现『vaults 已是 T1、缓存还是 T0』——每次解锁都白下一次整库",
            T1,
            cache.load(ORIGIN)?.versionToken,
        )
        assertArrayEquals(uploaded, cache.load(ORIGIN)?.bytes)
    }

    /** 内存缓存（`KdbxFileCache` 本来就是为"可测"拆出来的）。 */
    private class InMemoryCache : KdbxFileCache {
        private val map = mutableMapOf<String, CachedKdbxFile>()

        override suspend fun load(key: String): CachedKdbxFile? = map[key]

        override suspend fun save(key: String, file: CachedKdbxFile) {
            map[key] = file
        }

        override suspend fun remove(key: String) {
            map.remove(key)
        }
    }

    // ───────────── 降级标记：哪些状态不许被覆盖（2026-11-02 统一） ─────────────

    /**
     * ★ [KdbxSyncOrchestrator.markLocalEdited] **不许**把
     * `PENDING_UPLOAD_WITH_LOCAL_CHANGES` 降级成 `PENDING_UPLOAD`。
     *
     * ## 修的是什么
     *
     * 两个降级入口此前各自漏了不同的状态：`markLocalEdited` 只拦 `CONFLICT`，
     * 而 `markRemoteChanged` 拦了三个。同一个语义在两条路径上有两种行为，
     * 将来读代码的人必然踩 —— 所以判据收敛成一份（`NON_DOWNGRADABLE`）。
     *
     * 为什么不能降级：`PENDING_UPLOAD_WITH_LOCAL_CHANGES` 是 `PENDING_UPLOAD` 的
     * **上位状态**（信息更多：它额外说明"上一份已经推上去过"）。降级不丢数据，
     * 但会丢掉"刚上传完又攒了一笔"这条事实，而 UI 靠它决定要不要提示。
     */
    @Test
    fun `本地编辑标记_不得把已上传又改动降级成待上传`() = runTest {
        val dao = RecordingDao(initialToken = T1)
        dao.setStatus(KdbxSyncStatus.PENDING_UPLOAD_WITH_LOCAL_CHANGES)
        val orchestrator = orchestratorWith(dao, remoteToken = T1)

        orchestrator.markLocalEdited(VAULT_ID)

        assertEquals(
            "已上传又改动的状态信息更多，不许被降级",
            KdbxSyncStatus.PENDING_UPLOAD_WITH_LOCAL_CHANGES.name,
            dao.status(),
        )
    }

    /** 反证：`PENDING_UPLOAD` 本身没有更细的信息，编辑后仍是它（说明上面的拦截不误伤）。 */
    @Test
    fun `本地编辑标记_自身是待上传时保持不变`() = runTest {
        val dao = RecordingDao(initialToken = T1)
        dao.setStatus(KdbxSyncStatus.PENDING_UPLOAD)
        val orchestrator = orchestratorWith(dao, remoteToken = T1)

        orchestrator.markLocalEdited(VAULT_ID)

        assertEquals(KdbxSyncStatus.PENDING_UPLOAD.name, dao.status())
    }

    /**
     * 反证：`REMOTE_CHANGED` 状态下的本地编辑**必须**降级成 `PENDING_UPLOAD`。
     *
     * ⚠️ 这条与上面两条方向相反，刻意配在一起：不能因为"别降级"就一刀切 ——
     * "远端有更新 + 本地也改了"正是冲突的成因，拦住它会让下一次同步
     * 判不出冲突（`impliesLocalChanges` 为 false ⇒ 走"只有远端变" ⇒ 静默拉取）。
     */
    @Test
    fun `远端有更新时本地编辑_必须降级成待上传`() = runTest {
        val dao = RecordingDao(initialToken = T0)
        dao.setStatus(KdbxSyncStatus.REMOTE_CHANGED)
        val orchestrator = orchestratorWith(dao, remoteToken = T1)

        orchestrator.markLocalEdited(VAULT_ID)

        assertEquals(
            "远端有更新 + 本地也改 ⇒ 必须留下'本地已改'的痕迹，否则判不出冲突",
            KdbxSyncStatus.PENDING_UPLOAD.name,
            dao.status(),
        )
    }

    /** `markRemoteChanged` 方向的对称用例：不许盖掉"待上传"。 */
    @Test
    fun `后台发现远端变了_不得盖掉待上传`() = runTest {
        val dao = RecordingDao(initialToken = T0)
        dao.setStatus(KdbxSyncStatus.PENDING_UPLOAD)
        val orchestrator = orchestratorWith(dao, remoteToken = T1)

        orchestrator.markRemoteChanged(VAULT_ID)

        assertEquals(
            "推不上去比'远端有更新'更紧急，不该被盖掉",
            KdbxSyncStatus.PENDING_UPLOAD.name,
            dao.status(),
        )
    }

    /** `markRemoteChanged` 正常生效（反证：上面那条不是因为"这个方法根本不写"才绿）。 */
    @Test
    fun `后台发现远端变了_一致态下必须标成远端有更新`() = runTest {
        val dao = RecordingDao(initialToken = T0)
        dao.setStatus(KdbxSyncStatus.IN_SYNC)
        val orchestrator = orchestratorWith(dao, remoteToken = T1)

        orchestrator.markRemoteChanged(VAULT_ID)

        assertEquals(KdbxSyncStatus.REMOTE_CHANGED.name, dao.status())
    }

    /**
     * ⚠️ stub **一律显式带 receiver**（`source.stat()` 而不是裸 `stat()`）：
     * `coEvery { }` 块的 receiver 是 `MockKMatcherScope`，裸写方法名可能被它自己的成员
     * **遮蔽**（它有个 `get`）⇒ `expected 'MockKMatcherScope.DynamicCall', actual …`，
     * CI 上炸过（`.ai/ISSUES.md` #146）。
     */
    private fun sourceStub(remoteToken: String?, remoteBytes: ByteArray? = null): KdbxFileSource {
        val source = mockk<KdbxFileSource>()
        coEvery { source.stat() } returns KdbxFileStat(versionToken = remoteToken)
        coEvery { source.read() } returns (remoteBytes ?: ByteArray(0))
        return source
    }

    /** 会记录「状态 / 基线」被写成什么 的 DAO 替身。 */
    private class RecordingDao(initialToken: String?) {
        private var status: String? = KdbxSyncStatus.IN_SYNC.name
        private var token: String? = initialToken

        // ⚠️ 同上：`dao.get(...)` 的 receiver 不能省（省了会被 `MockKMatcherScope.get` 遮蔽）。
        //    ⇒ 先建 mock 拿到变量名，再用 `variable.method(...)` 的形式 stub。
        private val delegate: VaultDao = mockk()

        val dao: VaultDao = delegate

        init {
            coEvery { delegate.get(any()) } coAnswers {
                VaultEntity(
                    id = VAULT_ID,
                    kind = "KDBX",
                    displayName = "库",
                    origin = ORIGIN,
                    createdAt = 0L,
                    syncStatus = status,
                    remoteVersionToken = token,
                )
            }
            // ⚠️ 不要以裸的 `Unit` 收尾：`updateSyncState` 返回 `Unit`，编译器会判
            //    "Expression is unused" 并告警（CI 日志里出现过，见本次执行记录），
            //    而这个告警最坏的情况是掩盖"stub 压根没被匹配上"这类真问题。
            //    ⇒ 用 `just Runs`（mockk 对 Unit 返回值的正规写法），语义更明确。
            coEvery { delegate.updateSyncState(any(), any(), any(), any()) } coAnswers {
                status = secondArg()
                token = thirdArg()
                Unit.also { }
            }
        }

        fun status(): String? = status

        fun token(): String? = token

        fun setStatus(next: KdbxSyncStatus) {
            status = next.name
        }
    }

    private companion object {
        const val VAULT_ID = "vault-orchestrator-test"
        const val ORIGIN = "onedrive:acc:/vault.kdbx"
        const val T0 = "etag-0"
        const val T1 = "etag-1"
    }
}
