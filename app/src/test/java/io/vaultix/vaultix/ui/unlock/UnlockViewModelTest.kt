package io.vaultix.vaultix.ui.unlock

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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
 * 它的**唯一写入点**是订阅 `vaultRepository.localUnlockAvailable(id)` 的那一处；
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

    private fun unlockViewModel(
        available: Boolean = true,
        vaultFlow: () -> Flow<List<VaultSummary>>,
    ): UnlockViewModel {
        val repository = mockk<VaultRepository>()
        every { repository.observeVaults() } answers { vaultFlow() }
        every { repository.localUnlockAvailable(any()) } returns flowOf(available)
        // PIN 入口是**另一条独立**的可用性流：本文件测的是指纹入口，
        // 所以这里恒为 false（PIN 入口不渲染，不干扰断言）。要测 PIN 请另开用例。
        every { repository.pinUnlockAvailable(any()) } returns flowOf(false)
        coEvery { repository.prepareLocalUnlock(any()) } returns null

        val sessions = mockk<VaultSessionRepository>()
        every { sessions.observeViewLockedVaultIds() } returns flowOf(emptySet())
        every { sessions.isViewLocked(any()) } returns false

        return UnlockViewModel(SavedStateHandle(), repository, sessions)
    }

    private fun vaultSummary(id: String) = VaultSummary(
        id = id,
        kind = VaultKind.BITWARDEN,
        name = "示例库",
        account = "user@example.com",
        origin = "https://vault.example.com",
        unlocked = false,
    )

    private companion object {
        /** 让"晚到的发射"与"立即返回的发射"之间的先后关系明确，不依赖实现细节的时序。 */
        const val VaultIdArrivesLateMillis = 50L
    }
}
