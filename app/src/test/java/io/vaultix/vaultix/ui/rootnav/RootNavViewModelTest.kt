package io.vaultix.vaultix.ui.rootnav

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.vaultix.domain.VaultRepository
import io.vaultix.model.VaultKind
import io.vaultix.model.VaultSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * 根导航状态映射单测（2026-09-12 启动死锁修复的门禁）。
 *
 * 背景：全新安装（一个库都没有）时旧实现把状态判成 `VaultLocked`，`startDestination`
 * 被固化成解锁页 → 解锁页没有库可解锁 → 永久转圈。以下用例把「无库 ≠ 锁定」钉死。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RootNavViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun noVaultGoesToOnboardingNotUnlock() = runTest {
        val viewModel = rootNavViewModel(vaults = emptyList(), unlockedIds = emptySet())

        viewModel.rootNavState.test {
            assertThat(awaitItem()).isInstanceOf(RootNavState.Onboarding::class.java)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun lockedVaultsGoToUnlockScreen() = runTest {
        val locked = vaultSummary(id = "vault-1", unlocked = false)
        val viewModel = rootNavViewModel(vaults = listOf(locked), unlockedIds = emptySet())

        viewModel.rootNavState.test {
            assertThat(awaitItem()).isInstanceOf(RootNavState.VaultLocked::class.java)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun unlockedVaultGoesToMainGraph() = runTest {
        val unlocked = vaultSummary(id = "vault-1", unlocked = true)
        val viewModel = rootNavViewModel(vaults = listOf(unlocked), unlockedIds = setOf("vault-1"))

        viewModel.rootNavState.test {
            assertThat(awaitItem()).isInstanceOf(RootNavState.VaultUnlockedGraph::class.java)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun sessionTruthWinsWhenSummaryIsStale() = runTest {
        // 库摘要尚未投影出 unlocked、但会话里已有密钥：不能把用户弹回解锁页
        val stale = vaultSummary(id = "vault-1", unlocked = false)
        val viewModel = rootNavViewModel(vaults = listOf(stale), unlockedIds = setOf("vault-1"))

        viewModel.rootNavState.test {
            assertThat(awaitItem()).isInstanceOf(RootNavState.VaultUnlockedGraph::class.java)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun rootNavViewModel(
        vaults: List<VaultSummary>,
        unlockedIds: Set<String>,
    ): RootNavViewModel {
        val repository = mockk<VaultRepository>()
        every { repository.observeVaults() } returns flowOf(vaults)
        every { repository.observeUnlockedVaultIds() } returns flowOf(unlockedIds)
        return RootNavViewModel(repository)
    }

    private fun vaultSummary(id: String, unlocked: Boolean) = VaultSummary(
        id = id,
        kind = VaultKind.BITWARDEN,
        name = "示例库",
        account = "user@example.com",
        origin = "https://vault.example.com",
        unlocked = unlocked,
    )
}
