package io.vaultix.vaultix.ui.unlock

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.domain.RoomResealOutcome
import io.vaultix.domain.RoomResealRepository
import io.vaultix.domain.RoomUnlockOutcome
import io.vaultix.domain.UnlockRecoveryRepository
import io.vaultix.domain.VaultRepository
import io.vaultix.domain.VaultSessionRepository
import io.vaultix.model.VaultKind
import io.vaultix.model.VaultSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * 「快速解锁走到 StaleCredentials 时，顺手用刚输的密码重包房间信封」
 * （批次 4，定稿 §6 目标 3）。
 *
 * ## 修的是什么（用户体感）
 *
 * 旧行为：用户在别处改了 KDBX 主密码 → 指纹解锁 → 门锁开了、房钥匙拿到了，
 * 但信封里那份**旧主密码**打不开库 → UI 说「请输入当前主密码」→ 用户输了 →
 * 当次能开。**但信封没换** ⇒ 下次快速解锁又 Stale、又要重输。
 * 用户的真实体感是「指纹解锁坏了，每周都要重输一次密码」。
 *
 * 新行为：`StaleCredentials` 那一刻，把用户**上一次输进密码框的那个值**
 * 顺手用来重包该库的信封（`RoomResealRepository`）⇒ 自愈。
 *
 * ## ⚠️ 本文件的同步纪律（别照抄 `advanceUntilIdle`）
 *
 * `completeLocalUnlock` 内部有 `withContext(Dispatchers.IO)` —— 那是**真实**
 * 线程池，`runTest` 的 `advanceUntilIdle()` 管不到它（它只推进测试调度器上的
 * 任务）。第一版这里写了 `advanceUntilIdle()` 然后断言，结果是**间歇性红**：
 * 断言跑在 IO 块完成之前，看到的是初值（本文件实测踩过，不是理论担心）。
 *
 * ⇒ 本文件一律用 [awaitState] 轮询**可观测的终态**（state 字段），带超时。
 * 代价是等一次真实的线程跳转（毫秒级），换来的是确定性 ——
 * 同 `QuickUnlockControllerTest` KDoc 里记的那条教训（硬编码 IO dispatcher
 * 无法确定性推进，别硬推）。
 *
 * ⚠️ 更根本的修法是让 `UnlockViewModel` 接受一个可注入的 dispatcher；
 * 本轮不动生产签名（那是另一批的事），先让门禁稳定可用。
 *
 * ## 本文件的断言纪律
 *
 * ⚠️ **只在需要的分支重包**：`Opened` / `Unavailable` 分支一律不许碰重包 ——
 * 前者已经把库开了（重包是多余的写盘），后者根本没到「密码过时」这一步
 * （重包会用上一个不相干的密码覆盖信封，那是**数据损坏**）。
 * 这两条是本文件最重要的门禁。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StaleRoomResealTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * 轮询等待**异步编排**推进到预期终态（见类 KDoc 的同步纪律）。
     *
     * 超时 = 红（而不是静默放过）：`withTimeoutOrNull` 返回 null 时断言必失败。
     */
    private suspend fun awaitState(description: String, predicate: () -> Boolean) {
        val reached = withTimeoutOrNull(AWAIT_TIMEOUT_MS) {
            while (!predicate()) delay(1)
            true
        }
        if (reached != true) throw AssertionError("等待超时：$description")
    }

    private val vaultId = "vault-1"

    private val vault = VaultSummary(
        id = vaultId,
        kind = VaultKind.KDBX,
        name = "示例库",
        account = null,
        origin = "content://sample.kdbx",
        unlocked = false,
    )

    /**
     * 造一个解锁页 VM。
     *
     * @param firstOutcome 门锁解开后首个库的结论（本文件的核心开关）。
     * @param password 提交前先塞进密码框的值（模拟「用户刚输过一次密码」）。
     */
    private fun viewModel(
        firstOutcome: RoomUnlockOutcome,
        reseal: RoomResealRepository,
        password: String? = null,
    ): UnlockViewModel {
        val repository = mockk<VaultRepository>(relaxed = true)
        // ⚠️ 必须给出 vault 行：`vaultId` 来自 `observeVaults` + `ARG_VAULT_ID`，
        // 给空列表的话 VM 会停在「没有可解锁的库」，`completeLocalUnlock` 里的
        // `vaultId` 是空串，重包断言就永远匹配不上（本测试第一版的翻车点）。
        every { repository.observeVaults() } returns flowOf(listOf(vault))
        every { repository.fingerprintQuickUnlockAvailable(any()) } returns flowOf(false)
        every { repository.pinLockAvailable() } returns flowOf(false)
        coEvery { repository.prepareFingerprintUnlock() } returns null
        coEvery { repository.completeFingerprintUnlock(any()) } returns true
        coEvery { repository.unlockVaultFromRoom(any()) } returns firstOutcome

        val sessions = mockk<VaultSessionRepository>(relaxed = true)
        every { sessions.observeViewLockedVaultIds() } returns flowOf(emptySet())
        every { sessions.isViewLocked(any()) } returns false

        // ⚠️ 范围偏好：候选库筛选现在直接读它（2026-09-29 提速修复，不再逐库过 Keystore）。
        // 本文件只关心核心库的 Stale 重包，范围给空集即可（没有"其余库"要顺带开）。
        val prefs = mockk<VaultixPreferences>(relaxed = true)
        every { prefs.quickUnlockScope() } returns flowOf(emptySet())

        val vm = UnlockViewModel(
            SavedStateHandle(mapOf(UnlockViewModel.ARG_VAULT_ID to vaultId)),
            repository,
            sessions,
            mockk<UnlockRecoveryRepository>(relaxed = true),
            reseal,
            prefs,
        )
        if (password != null) vm.onPasswordChange(password)
        return vm
    }

    private fun resealReturning(outcome: RoomResealOutcome): RoomResealRepository =
        mockk { coEvery { resealRoom(any(), any()) } returns outcome }

    private val cipher = mockk<javax.crypto.Cipher>(relaxed = true)

    @Test
    fun `Stale时用刚输的密码重包信封`() = runTest {
        val reseal = resealReturning(RoomResealOutcome.Resealed)
        val vm = viewModel(RoomUnlockOutcome.StaleCredentials, reseal, password = "新主密码")

        vm.completeLocalUnlock(cipher, forViewLock = false)
        // 等到编排真的走完（判据 = Stale 分支的终态：错误已置位）。
        awaitState("Stale 分支落定") { vm.state.value.error != null }

        // ★ 这就是自愈：把用户刚输的那一份**当前有效**密码交给重包。
        // 传别的值（空串 / 旧值）会把一个打不开的信封留在盘上。
        coVerify(exactly = 1) { reseal.resealRoom(vaultId, "新主密码") }
    }

    @Test
    fun `密码框为空时不重包`() = runTest {
        // 没有可用密码就没有可包的东西。此时**绝不能**拿空串去覆盖信封 ——
        // 那会把一个「密码过时」的信封升级成「躺着一个空密码」的死信封，
        // 用户下次连「输新密码」的指引都救不回来了。
        val reseal = resealReturning(RoomResealOutcome.Resealed)
        val vm = viewModel(RoomUnlockOutcome.StaleCredentials, reseal, password = null)

        vm.completeLocalUnlock(cipher, forViewLock = false)
        awaitState("Stale 分支落定") { vm.state.value.error != null }

        coVerify(exactly = 0) { reseal.resealRoom(any(), any()) }
    }

    @Test
    fun `解锁成功时不重包`() = runTest {
        // 库已经开了 ⇒ 没有 Stale 这回事，重包纯属多余的写盘。
        val reseal = resealReturning(RoomResealOutcome.Resealed)
        val vm = viewModel(RoomUnlockOutcome.Opened, reseal, password = "某个密码")

        vm.completeLocalUnlock(cipher, forViewLock = false)
        // `Opened` 的终态特征：密码框被清空（那是成功路径独有的动作）。
        awaitState("Opened 分支落定") { vm.state.value.password.isEmpty() }

        coVerify(exactly = 0) { reseal.resealRoom(any(), any()) }
    }

    @Test
    fun `Unavailable时不重包`() = runTest {
        // ★ 重要门禁：`Unavailable` 覆盖「信封损坏 / 文件读不到 / 未纳入快速解锁」——
        // 这些**都不是**「主密码过时」。此时拿用户手上那个（可能是别的库的）密码
        // 去覆盖信封，是**数据损坏**：会把一个还有救的信封换成一份错的。
        val reseal = resealReturning(RoomResealOutcome.Resealed)
        val vm = viewModel(
            RoomUnlockOutcome.Unavailable("房间信封损坏"),
            reseal,
            password = "某个密码",
        )

        vm.completeLocalUnlock(cipher, forViewLock = false)
        awaitState("Unavailable 分支落定") { vm.state.value.error != null }

        coVerify(exactly = 0) { reseal.resealRoom(any(), any()) }
    }

    @Test
    fun `重包失败不改主提示`() = runTest {
        // 重包是**次要的自我修复**：它失败不该把「请输入当前主密码」这条
        // 清楚的指引，换成/叠加成另一个错误（两条互相矛盾的报错最伤用户）。
        val reseal = resealReturning(RoomResealOutcome.InvalidCredentials)
        val vm = viewModel(RoomUnlockOutcome.StaleCredentials, reseal, password = "输错的")

        vm.completeLocalUnlock(cipher, forViewLock = false)
        awaitState("Stale 分支落定") { vm.state.value.error != null }

        coVerify(exactly = 1) { reseal.resealRoom(vaultId, "输错的") }
        // 提示仍在：用户该知道要做什么，而不是看到一片沉默。
        assertThat(vm.state.value.error).isNotNull()
    }

    @Test
    fun `重包抛异常不崩且不改主提示`() = runTest {
        // 真实场景：重包路上读文件失败 / 存储满。一次认证失败不该升级成崩溃
        //（异常必须被 resealStaleRoomIfPossible 内部吃掉，主提示照常给出）。
        val reseal = mockk<RoomResealRepository> {
            coEvery { resealRoom(any(), any()) } throws RuntimeException("storage full")
        }
        val vm = viewModel(RoomUnlockOutcome.StaleCredentials, reseal, password = "新主密码")

        vm.completeLocalUnlock(cipher, forViewLock = false)
        // 若异常逃出 helper，这里会一直等不到 ⇒ 超时红。
        awaitState("Stale 分支落定（异常被吞）") { vm.state.value.error != null }

        assertThat(vm.state.value.error).isNotNull()
    }

    private companion object {
        /**
         * 等待异步编排落定的上限。
         *
         * 一次真实的 IO 线程跳转是毫秒级；给到秒级是留足 CI 抖动余量
         * （超时只会让测试红，不会让实现"看起来通过"）。
         */
        const val AWAIT_TIMEOUT_MS = 5_000L
    }
}
