package io.vaultix.vaultix.ui.rootnav

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.vaultix.domain.VaultRepository
import io.vaultix.domain.VaultSessionRepository
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

    @Test
    fun viewLockedVaultGoesToUnlockCarryingItsId() = runTest {
        // 主页锁按钮 = 查看层锁：密钥仍在内存（unlocked=true），但界面必须回到解锁页，
        // 且**携带库 id** —— 否则解锁页会在多库场景里选错库（ISSUES #60 第 1c 步）。
        val unlocked = vaultSummary(id = "vault-1", unlocked = true)
        val viewModel = rootNavViewModel(
            vaults = listOf(unlocked),
            unlockedIds = setOf("vault-1"),
            viewLockedIds = setOf("vault-1"),
        )

        viewModel.rootNavState.test {
            val state = awaitItem()
            assertThat(state).isInstanceOf(RootNavState.VaultLocked::class.java)
            assertThat((state as RootNavState.VaultLocked).vaultId).isEqualTo("vault-1")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun viewLockTakesPrecedenceOverUnlockedGraph() = runTest {
        // 查看锁必须排在「已解锁」判定**之前**：两者的会话状态相同（都有密钥），
        // 顺序错了用户按锁按钮就会「什么都没发生」。
        val a = vaultSummary(id = "vault-1", unlocked = true)
        val b = vaultSummary(id = "vault-2", unlocked = true)
        val viewModel = rootNavViewModel(
            vaults = listOf(a, b),
            unlockedIds = setOf("vault-1", "vault-2"),
            viewLockedIds = setOf("vault-2"),
        )

        viewModel.rootNavState.test {
            assertThat(awaitItem()).isEqualTo(RootNavState.VaultLocked("vault-2"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun staleViewLockedIdForRemovedVaultIsIgnored() = runTest {
        // 库已被移除但标记残留 → 不能把用户钉在解锁页（那页没有可解锁的库 → 永久转圈，
        // 正是 2026-09-12 修掉的启动死锁同款症状）。
        val unlocked = vaultSummary(id = "vault-1", unlocked = true)
        val viewModel = rootNavViewModel(
            vaults = listOf(unlocked),
            unlockedIds = setOf("vault-1"),
            viewLockedIds = setOf("vault-gone"),
        )

        viewModel.rootNavState.test {
            assertThat(awaitItem()).isInstanceOf(RootNavState.VaultUnlockedGraph::class.java)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun rootNavViewModel(
        vaults: List<VaultSummary>,
        unlockedIds: Set<String>,
        viewLockedIds: Set<String> = emptySet(),
    ): RootNavViewModel {
        val repository = mockk<VaultRepository>()
        every { repository.observeVaults() } returns flowOf(vaults)
        every { repository.observeUnlockedVaultIds() } returns flowOf(unlockedIds)
        val sessions = mockk<VaultSessionRepository>()
        every { sessions.observeViewLockedVaultIds() } returns flowOf(viewLockedIds)
        return RootNavViewModel(repository, sessions)
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
