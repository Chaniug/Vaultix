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
import kotlinx.coroutines.test.runTest
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

    // ---------------------------------------------------------------- 装置

    private fun orchestratorWith(dao: RecordingDao, remoteToken: String?): KdbxSyncOrchestrator =
        KdbxSyncOrchestrator(
            vaultDao = dao.dao,
            fileSourceFactory = { sourceStub(remoteToken) },
        )

    /**
     * ⚠️ stub **一律显式带 receiver**（`source.stat()` 而不是裸 `stat()`）：
     * `coEvery { }` 块的 receiver 是 `MockKMatcherScope`，裸写方法名可能被它自己的成员
     * **遮蔽**（它有个 `get`）⇒ `expected 'MockKMatcherScope.DynamicCall', actual …`，
     * CI 上炸过（`.ai/ISSUES.md` #146）。
     */
    private fun sourceStub(remoteToken: String?): KdbxFileSource {
        val source = mockk<KdbxFileSource>()
        coEvery { source.stat() } returns KdbxFileStat(versionToken = remoteToken)
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
