package io.vaultix.vaultix.ui.unlock

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.domain.RoomResealRepository
import io.vaultix.domain.UnlockRecoveryRepository
import io.vaultix.domain.VaultRepository
import io.vaultix.domain.VaultSessionRepository
import io.vaultix.model.VaultKind
import io.vaultix.model.VaultSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * 解锁页「指纹入口」可用性状态的门禁（2026-09-13 修 #88）。
 *
 * ## 钉死的是什么
 * 解锁页有没有指纹入口，只取决于 [UnlockViewModel.UiState.localUnlockAvailable]。
 * 它的**唯一写入点**是订阅 `vaultRepository.fingerprintQuickUnlockAvailable(id)` 的那一处；
 * 而 `id` 过去是从一个**跨协程共享的 `var vaultId`** 里读的 ——
 * 「选库」（协程 A）与「订阅可用性」（协程 C）并发收集同一个 `observeVaults()`，
 * 若 C 那次发射先到，`vaultId` 仍是空串，C 就提前 return、**再也不订阅**
 * ⇒ 状态永久停在初值 `false`。
 *
 * 用户观感（实测）：
 * - 解锁页**没有指纹图标**、**也不自动弹**生物识别（`eligible` 里含同一项）；
 * - 不是 100%（纯竞态）；「手动返回」「清掉后台重开」才恢复 —— 因为那会重建 ViewModel，重掷一次。
 *
 * ⚠️ 与"钥匙坏了"无关：`LocalUnlockKeyStore.keyAvailable` 只在 KEK
 * `MISSING`/`INVALIDATED` 时为 false，而这两种**重启进程也救不回来** ——
 * 所以「重开就有」本身就把方向指向了这里的**状态固化**。
 *
 * ## 为什么这么写用例
 * 真机上的竞态是**不确定**的（两个 Room 查询谁先返回不定）。测试里要的是
 * **确定性调度**：让"先启动的 A 拿到的发射晚到"，把坏的那次交错固定下来。
 * 这样它既是复现，也是回归门禁 —— 修好后仍然必须通过。
 *
 * ## ★ #174：这里曾经在 CI 上红（本地绿），修的是等待方式，不是断言
 *
 * `candidateVaultsExcludeTheTargetItself` 在 CI 上稳定报
 * `IllegalStateException: Dispatchers.Main was accessed when the platform
 * dispatcher was absent…`（`TestMainDispatcher.isDispatchNeeded`）。
 *
 * 机理：真正把 `PromptForUnlock` 发出来之前，`UnlockViewModel` 要在
 * `withContext(Dispatchers.IO)` 的真线程上跑完候选库筛选，**续体再回
 * `Dispatchers.Main`**；而 `Dispatchers.Main` 在 JVM 单测上只是一具替身
 * （`kotlinx-coroutines-android` 不在测试 classpath 上），本文件的 `@After`
 * 一 `resetMain()` 它就没了，那一刻真线程恰好 resume ⇒ 炸。
 * 同文件的另外两条用例走同一段代码却没红 ⇒ 是**竞态**，不是必现。
 *
 * ⇒ 修法与 [StaleRoomResealTest] 一致：给 VM 注入本测试自己的 `TestScope`，
 * `candidateVaultIds` 的 `withContext(Dispatchers.IO)` 于是在虚拟时间里完成，
 * `advanceUntilIdle()` 能**确定性地**等到最后一跳，全程不碰 `Dispatchers.Main`。
 * ⚠️ 断言一条没放宽（变异验证：把 VM 侧的 `scope` 换回 `viewModelScope`，
 * 这三条用例会在**每个**协程续体处重新踩上 `Dispatchers.Main`）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnlockViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    /** 一个「已存在但处于锁定态」的库 —— 正是解锁页要服务的那种库。 */
    private val lockedVaults = listOf(vaultSummary(id = "vault-1"))

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun fingerprintEntryAppearsEvenIfFirstCollectorGetsTheVaultListLate() = runTest {
        // 坏交错：A（先启动、负责补 vaultId）拿到的发射**晚到**；
        // C（负责订阅可用性）先看到同一份库列表 ⇒ 旧实现在这里读到的 vaultId 还是空串。
        var collectCount = 0
        val viewModel = unlockViewModel {
            if (collectCount++ == 0) {
                flow {
                    delay(VaultIdArrivesLateMillis)
                    emit(lockedVaults)
                }
            } else {
                flowOf(lockedVaults)
            }
        }

        advanceUntilIdle()

        assertThat(viewModel.state.value.localUnlockAvailable).isTrue()
    }

    @Test
    fun fingerprintEntryAppearsInTheUsualOrder() = runTest {
        val viewModel = unlockViewModel { flowOf(lockedVaults) }

        advanceUntilIdle()

        assertThat(viewModel.state.value.localUnlockAvailable).isTrue()
    }

    @Test
    fun fingerprintEntryStaysHiddenWhenLocalUnlockIsNotEnabledForTheVault() = runTest {
        // 反向门禁：用户没为这个库开启快速解锁时，入口**必须**不出现 ——
        // 修竞态不能让"本来就不该有"变成"总有"。
        val viewModel = unlockViewModel(available = false) { flowOf(lockedVaults) }

        advanceUntilIdle()

        assertThat(viewModel.state.value.localUnlockAvailable).isFalse()
    }

    // ---- 候选库筛选（2026-09-29 提速修复，方案 C）----
    //
    // 一次认证要顺带开的"其余库"只可能是**纳入了快速解锁范围**、且**当前锁着**、
    // 且**不是本次目标**的库。旧实现逐库订阅 `fingerprintQuickUnlockAvailable(id)`
    // （每库一次 Keystore 往返 ⇒ 用户感知"指纹过了却要等好几秒"）；
    // 现在只读一次范围快照，在内存里求交。下面三条钉死这个筛选语义。
    //
    // ## ⚠️ #174：这三条以前是"事件级"用例，现在拆成两层
    //
    // 它们原本要**真的走到弹认证**才拿得到候选库（`Event.PromptForUnlock.rest`）。
    // 可那条路的最后一段是 `viewModelScope` 上的一次真实线程跳转 —— 在 CI 上会踩
    // 被卸下的 `Dispatchers.Main`（见类 KDoc），而且**每个用例都要多花一次真实的
    // 线程往返**才谈得上断言。
    //
    // 拆法：
    // 1. **纯筛选逻辑**用 `candidateVaultIds` 直接断言（下面三条）—— 确定、快、不碰调度器；
    // 2. **"筛出来的东西确实随事件发出去"**这条线，由
    //    [promptCarriesTheFilteredCandidateVaults] 一条端到端用例钉死。
    //
    // 这样既不丢覆盖面，也不必让三条用例各自赌一次线程时序。

    @Test
    fun candidateVaultsExcludeTheTargetItself() = runTest {
        // 目标库由核心段单独开，不该出现在"其余库"里（否则白开一次、还多算一次失败）。
        val viewModel = unlockViewModel(
            vaultFlow = {
                flowOf(
                    listOf(
                        vaultSummary(id = "target"),
                        vaultSummary(id = "other"),
                    ),
                )
            },
            scope = setOf("target", "other"),
        )
        advanceUntilIdle()

        val candidates = viewModel.candidateVaultIdsForTest("target")

        assertThat(candidates).containsExactly("other")
    }

    @Test
    fun candidateVaultsExcludeVaultsOutsideTheQuickUnlockScope() = runTest {
        // 只走主密码的库不在范围内：对它们开房间必然 NotEnrolled，纯浪费。
        val viewModel = unlockViewModel(
            vaultFlow = {
                flowOf(
                    listOf(
                        vaultSummary(id = "target"),
                        vaultSummary(id = "inScope"),
                        vaultSummary(id = "outOfScope"),
                    ),
                )
            },
            scope = setOf("target", "inScope"),
        )
        advanceUntilIdle()

        val candidates = viewModel.candidateVaultIdsForTest("target")

        assertThat(candidates).containsExactly("inScope")
    }

    @Test
    fun candidateVaultsExcludeAlreadyUnlockedVaults() = runTest {
        // 已解锁的库密钥就在内存里，再开一次房间是纯浪费。
        val viewModel = unlockViewModel(
            vaultFlow = {
                flowOf(
                    listOf(
                        vaultSummary(id = "target"),
                        vaultSummary(id = "stillLocked"),
                        vaultSummary(id = "alreadyOpen", unlocked = true),
                    ),
                )
            },
            scope = setOf("target", "stillLocked", "alreadyOpen"),
        )
        advanceUntilIdle()

        val candidates = viewModel.candidateVaultIdsForTest("target")

        assertThat(candidates).containsExactly("stillLocked")
    }

    @Test
    fun promptCarriesTheFilteredCandidateVaults() = runTest {
        // ★ 端到端那一条：筛选结果必须**真的随事件发出去**。
        //   （上三条只证明"筛选函数选得对"，不证明"调用点用了它"——
        //   把 `candidateVaultIds(vaultId)` 换成 `emptyList()` 时，只有本条会红。）
        val viewModel = unlockViewModel(
            vaultFlow = {
                flowOf(
                    listOf(
                        vaultSummary(id = "target"),
                        vaultSummary(id = "other"),
                    ),
                )
            },
            scope = setOf("target", "other"),
        )
        advanceUntilIdle()

        val prompt = startAndCapturePrompt(viewModel)

        assertThat(prompt.rest).containsExactly("other")
    }

    @Test
    fun overrideScopeIsActuallyUsedByTheViewModel() = runTest {
        // ★ 结构门禁（#174）：本文件所有"确定性等待"都建立在
        //   「VM 真的把协程挂在我注入的 scope 上」这个前提上。
        //   把 VM 里的 `testingScope ?: viewModelScope` 改成直接用 `viewModelScope`
        //   时，下面的断言必须红 —— 否则"注入成功"只是错觉，回归会静默通过。
        val viewModel = unlockViewModel { flowOf(lockedVaults) }
        advanceUntilIdle()

        val usedScope = viewModel.probeOwnScope()
        // 判据 = **同一个 Job**：注入失败时 VM 拿到的是 `viewModelScope`，Job 不同。
        assertThat(usedScope.coroutineContext[Job]).isSameInstanceAs(coroutineContext[Job])
    }

    /**
     * 走到「弹认证」那一步并截获 [UnlockViewModel.Event.PromptForUnlock]。
     *
     * 为什么不能直接读 `state`：候选库是**随事件携带**的（`Event.PromptForUnlock.rest`），
     * 刻意不进 UiState（认证期间库列表可能变化，进 state 会让 UI 读到过期值）。
     * 故必须从事件流里取。
     */
    private suspend fun TestScope.startAndCapturePrompt(
        viewModel: UnlockViewModel,
    ): UnlockViewModel.Event.PromptForUnlock {
        val prompts = mutableListOf<UnlockViewModel.Event.PromptForUnlock>()
        // 收集必须在 `startLocalUnlock()` 之前就绪，故用 `backgroundScope`
        // （runTest 结束时会自动取消，不会让测试挂住）。
        val collector = backgroundScope.launch {
            viewModel.events.collect {
                if (it is UnlockViewModel.Event.PromptForUnlock) prompts += it
            }
        }
        // ⚠️ 必须先让收集协程真正**跑起来**再触发解锁：`backgroundScope` 上的
        //   `launch` 是惰性的（要等一次调度才执行到 `collect`），若紧接着就
        //   `startLocalUnlock()`，事件可能在**收集器挂上之前**就发出去了 —— 悬空事件被丢。
        runCurrent()
        check(collector.isActive) { "事件收集协程没起来" }
        viewModel.startLocalUnlock()
        // ★ #174：VM 的 scope 与后台调度器都换成了本测试的虚拟调度器，故这里推进的
        //   是**确定性的虚拟时间**，不需要再赌真实线程池的时序（旧写法是 `delay(1)`
        //   轮询 + 5s 超时，且在 CI 上会踩到被卸下的 `Dispatchers.Main`）。
        //
        //   为什么推 1ms 就够：`startBiometricUnlock` 的链路是
        //   `launch`（0ms）→ `withContext(io)` → `candidateVaultIds` 里 `Flow.first()`
        //   （0ms）→ `_events.send`（0ms）。没有一处 `delay`。
        //   ⚠️ 不要换成 `advanceUntilIdle()`：实测它会把事件推过 `Channel` 的
        //   缓冲刷新点，结果"事件已发出、`prompts` 仍为空"。
        advanceTimeBy(1)
        runCurrent()
        return prompts.singleOrNull()
            ?: error("没有收到 PromptForUnlock 事件（cipher 未就绪？）")
    }

    private fun TestScope.unlockViewModel(
        available: Boolean = true,
        scope: Set<String> = emptySet(),
        vaultFlow: () -> Flow<List<VaultSummary>>,
    ): UnlockViewModel {
        val repository = mockk<VaultRepository>()
        every { repository.observeVaults() } answers { vaultFlow() }
        every { repository.fingerprintQuickUnlockAvailable(any()) } returns flowOf(available)
        // PIN 入口是**另一条独立**的可用性流：本文件测的是指纹入口，
        // 所以这里恒为 false（PIN 入口不渲染，不干扰断言）。要测 PIN 请另开用例。
        // 房子化后门锁是全局的（不再按库），无参。
        every { repository.pinLockAvailable() } returns flowOf(false)
        // 弹认证需要一个可用的 cipher：relaxed 的 mockk 无法返回非 null 的 javax 对象，
        // 故显式造一个未 init 的 AES Cipher（只被透传，不会被本层使用）。
        coEvery { repository.prepareFingerprintUnlock() } returns
            javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")

        val sessions = mockk<VaultSessionRepository>()
        every { sessions.observeViewLockedVaultIds() } returns flowOf(emptySet())
        every { sessions.isViewLocked(any()) } returns false

        // 失效善后：本文件测的是「指纹入口显不显示」，一次都没走到门锁失败分支，
        // 故给 relaxed mock —— 真要测 rearm/降级分叉请用 `LocalUnlockFanoutTest`。
        val recovery = mockk<UnlockRecoveryRepository>(relaxed = true)
        // 房间信封重包：同上，本文件不覆盖 Stale 分支
        //（那条路径见 `StaleRoomResealTest`）。
        val reseal = mockk<RoomResealRepository>(relaxed = true)

        // 范围偏好：候选库筛选取快照（2026-09-29 提速修复）。
        val prefs = mockk<VaultixPreferences>(relaxed = true)
        every { prefs.quickUnlockScope() } returns flowOf(scope)

        // ★ #174：把 VM 的协程钉在本测试的虚拟调度器上。必须是**构造后立刻**赋值，
        //   否则 VM 已经用自己的（= Dispatchers.Main）作用域发出协程了。
        return UnlockViewModel(
            SavedStateHandle(),
            repository,
            sessions,
            recovery,
            reseal,
            prefs,
            textContext(),
        ).also {
            it.testingScope = this
            // ★ #174 第二步：连后台调度器也换成本测试的虚拟调度器。
            //   只换 scope 不够 —— `withContext(Dispatchers.IO)` 会真的跳到线程池，
            //   虚拟时钟推不动它（实测：只换 scope 时 6 条用例报"没有收到事件"）。
            it.testingIoDispatcher = StandardTestDispatcher(testScheduler)
            // ★ #176 第三步（**必设**）：把 IO 段**拦截**掉，让续体不跳线程。
            //
            //   只做 #174 那两步时，CI（run `39139391458`）上
            //   `promptCarriesTheFilteredCandidateVaults` 仍报
            //   `Dispatchers.Main was accessed …`，堆栈是
            //   `DispatchedCoroutine.afterResume` ← `Dispatchers.IO` 的真线程 worker。
            //
            //   机理：`StandardTestDispatcher` 跑任务时**不恢复**原协程的
            //   `ContinuationInterceptor` ⇒ 跨 `withContext(io)` 的续体恢复时，
            //   上下文里既没有拦截器也没有 `Dispatchers.Main` ⇒
            //   `TestMainDispatcher.isDispatchNeeded` 炸。
            //
            //   传一个 `StandardTestDispatcher`（不是 `TestDispatcher` 子类）会有意思：
            //   它**不会**被 `runTest` 收编进虚拟时钟 ⇒ 只是把任务内联执行，
            //   且**不改变上下文** ⇒ 续体恢复时拦截器还在 ⇒ 全程不碰 `Dispatchers.Main`。
            it.testingBlockableIoDispatcher = StandardTestDispatcher(testScheduler)
            // ★ 2026-10-28：接缝全设好之后才启动观察协程（自己调，不靠 init）。
            //
            //   以前 `init` 里直接 `scope.launch` —— 那一刻 testingScope 还是 null ⇒
            //   落在 `viewModelScope`（= `Dispatchers.Main.immediate`，被 `setMain` 换成了
            //   **旧的**那个 TestMainDispatcher）上。`@After resetMain()` 卸掉它时，
            //   正在跑的协程就炸 `Dispatchers.Main was accessed …`。
            //   CI run 40563967972 的 `fingerprintEntryStaysHiddenWhen…` 即此（偶发）。
            it.startObservers()
        }
    }

    private fun vaultSummary(id: String, unlocked: Boolean = false) = VaultSummary(
        id = id,
        kind = VaultKind.BITWARDEN,
        name = "示例库",
        account = "user@example.com",
        origin = "https://vault.example.com",
        unlocked = unlocked,
    )

    private companion object {
        /** 让"晚到的发射"与"立即返回的发射"之间的先后关系明确，不依赖实现细节的时序。 */
        const val VaultIdArrivesLateMillis = 50L
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
