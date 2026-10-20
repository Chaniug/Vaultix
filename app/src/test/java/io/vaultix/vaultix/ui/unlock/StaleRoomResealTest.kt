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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.setMain
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
 * ## ⚠️ 本文件的同步纪律（已随 #174 改成虚拟时间）
 *
 * `completeLocalUnlock` 内部有 `withContext(Dispatchers.IO)` —— 那是**真实**
 * 线程池，`runTest` 的 `advanceUntilIdle()` 管不到它（它只推进测试调度器上的
 * 任务）。2026-10 之前这里因此只能用 [awaitState] 轮询真实终态，代价是等一次
 * 真实的线程跳转，且**必须靠 `Dispatchers.Main` 承载 `scope.launch` 的续体** ——
 * 那正是 CI 上 `TestMainDispatcher` 被卸下后炸掉的那条路（#174）。
 *
 * ⇒ 修 #174 时给 `UnlockViewModel` 加了可注入的 `scope`，本文件注入自己的
 * `TestScope`，于是 `runTest { advanceUntilIdle() }` 能**确定性地**等到
 * `completeLocalUnlock` 的最后一跳，也不再摸 `Dispatchers.Main`。
 * 断言本身（`coVerify` / state 字段）一条没改 —— 修的只是等待方式。
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

    /**
     * `Dispatchers.Main` 的替身。
     *
     * ⚠️ 它只服务于**本 VM 之外**的代码（例如 `viewModelScope` 相关的第三方
     * 组件的默认值）。`UnlockViewModel` 自己已不再经 `Dispatchers.Main`
     * 派发任何东西（见 #174）。
     */
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
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
    private fun TestScope.viewModel(
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

        // ★ #174：把 VM 的协程钉在本测试的虚拟调度器上（构造后**立刻**赋值），
        //   这样 `advanceUntilIdle()` 就能确定性地等到编排走完。
        val vm = UnlockViewModel(
            SavedStateHandle(mapOf(UnlockViewModel.ARG_VAULT_ID to vaultId)),
            repository,
            sessions,
            mockk<UnlockRecoveryRepository>(relaxed = true),
            reseal,
            prefs,
            textContext(),
        ).also {
            it.testingScope = this
            // ★ #174 第二步：连后台调度器也换成本测试的虚拟调度器。
            //   只换 scope 不够 —— `withContext(Dispatchers.IO)` 会真的跳到线程池，
            //   虚拟时钟推不动它（实测：只换 scope 时 6 条用例报"没有收到事件"）。
            it.testingIoDispatcher = StandardTestDispatcher(testScheduler)
        }
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
        // 编排全挂在注入的 TestScope 上 ⇒ 虚拟时间能确定性地走完。
        advanceUntilIdle()

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
        advanceUntilIdle()

        coVerify(exactly = 0) { reseal.resealRoom(any(), any()) }
    }

    @Test
    fun `解锁成功时不重包`() = runTest {
        // 库已经开了 ⇒ 没有 Stale 这回事，重包纯属多余的写盘。
        val reseal = resealReturning(RoomResealOutcome.Resealed)
        val vm = viewModel(RoomUnlockOutcome.Opened, reseal, password = "某个密码")

        vm.completeLocalUnlock(cipher, forViewLock = false)
        advanceUntilIdle()

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
        advanceUntilIdle()

        coVerify(exactly = 0) { reseal.resealRoom(any(), any()) }
    }

    @Test
    fun `重包失败不改主提示`() = runTest {
        // 重包是**次要的自我修复**：它失败不该把「请输入当前主密码」这条
        // 清楚的指引，换成/叠加成另一个错误（两条互相矛盾的报错最伤用户）。
        val reseal = resealReturning(RoomResealOutcome.InvalidCredentials)
        val vm = viewModel(RoomUnlockOutcome.StaleCredentials, reseal, password = "输错的")

        vm.completeLocalUnlock(cipher, forViewLock = false)
        advanceUntilIdle()

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
        // 若异常逃出 helper，它会冒到 `runTest` 里 ⇒ 这里直接红（不会静默放过）。
        advanceUntilIdle()

        assertThat(vm.state.value.error).isNotNull()
    }
    /** 只给 ViewModel 解析文案用的假 Context：返回资源**名**，让断言能看出取的是哪条。 */
    private fun textContext(): android.content.Context {
        val ctx = mockk<android.content.Context>(relaxed = true)
        every { ctx.getString(any()) } answers { resourceName(firstArg()) }
        every { ctx.getString(any(), *anyVararg()) } answers { resourceName(firstArg()) }
        return ctx
    }

    /**
     * 把资源 id 反射回**符号名**。
     *
     * ⚠️ 为什么不用 Robolectric 的 `context.getString` 真解析：本模块单测不跑
     * Android 运行时（全是纯 JVM + mockk），引 Robolectric 是为了这一件事付整个
     * 运行时的代价。而断言真正关心的是"用的是哪条资源"，不是"那条资源的中文写了什么"
     * —— 后者一改文案就会红，反而把文案钉死在测试里（那正是 §3.4 要治的病）。
     */
    private fun resourceName(id: Int): String =
        io.vaultix.vaultix.R.string::class.java.fields
            .firstOrNull { it.getInt(null) == id }
            ?.name
            ?: "unknown_$id"

}
