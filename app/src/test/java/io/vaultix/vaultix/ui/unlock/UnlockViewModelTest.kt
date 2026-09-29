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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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

    @Test
    fun candidateVaultsExcludeTheTargetItself() = runTest {
        // 目标库由核心段单独开，不该出现在"其余库"里（否则白开一次、还多算一次失败）。
        val vaults = listOf(
            vaultSummary(id = "target"),
            vaultSummary(id = "other"),
        )
        val viewModel = unlockViewModel(
            vaultFlow = { flowOf(vaults) },
            scope = setOf("target", "other"),
        )
        advanceUntilIdle()

        val prompt = startAndCapturePrompt(viewModel)

        assertThat(prompt.rest).containsExactly("other")
    }

    @Test
    fun candidateVaultsExcludeVaultsOutsideTheQuickUnlockScope() = runTest {
        // 只走主密码的库不在范围内：对它们开房间必然 NotEnrolled，纯浪费。
        val vaults = listOf(
            vaultSummary(id = "target"),
            vaultSummary(id = "inScope"),
            vaultSummary(id = "outOfScope"),
        )
        val viewModel = unlockViewModel(
            vaultFlow = { flowOf(vaults) },
            scope = setOf("target", "inScope"),
        )
        advanceUntilIdle()

        val prompt = startAndCapturePrompt(viewModel)

        assertThat(prompt.rest).containsExactly("inScope")
    }

    @Test
    fun candidateVaultsExcludeAlreadyUnlockedVaults() = runTest {
        // 已解锁的库密钥就在内存里，再开一次房间是纯浪费。
        val vaults = listOf(
            vaultSummary(id = "target"),
            vaultSummary(id = "stillLocked"),
            vaultSummary(id = "alreadyOpen", unlocked = true),
        )
        val viewModel = unlockViewModel(
            vaultFlow = { flowOf(vaults) },
            scope = setOf("target", "stillLocked", "alreadyOpen"),
        )
        advanceUntilIdle()

        val prompt = startAndCapturePrompt(viewModel)

        assertThat(prompt.rest).containsExactly("stillLocked")
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
        backgroundScope.launch {
            viewModel.events.collect {
                if (it is UnlockViewModel.Event.PromptForUnlock) prompts += it
            }
        }
        viewModel.startLocalUnlock()
        // ⚠️ 不能只用 `advanceUntilIdle()`：`candidateVaultIds` 内部走
        //   `withContext(Dispatchers.IO)`（真实线程池），测试调度器管不到它。
        //   与项目既有纪律一致 —— 轮询终态。
        val prompt = withTimeoutOrNull(5_000L) {
            while (prompts.isEmpty()) delay(1)
            prompts.single()
        }
        return prompt ?: error("没有收到 PromptForUnlock 事件（cipher 未就绪？）")
    }

    private fun unlockViewModel(
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

        return UnlockViewModel(SavedStateHandle(), repository, sessions, recovery, reseal, prefs)
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
}
