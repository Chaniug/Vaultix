package io.vaultix.vaultix.ui.vaultlist

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.vaultix.data.repository.BitwardenSyncOrchestrator
import io.vaultix.data.repository.LegacyQuickUnlockCleanup
import io.vaultix.data.repository.LocalUnlockEnrollment
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.domain.KdbxSyncRepository
import io.vaultix.domain.UnlockRecoveryRepository
import io.vaultix.domain.VaultRepository
import io.vaultix.model.VaultKind
import io.vaultix.model.VaultSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * 库列表「启用快速解锁」横幅的候选判定（#222 P1）。
 *
 * ## 修的是什么
 *
 * 旧实现的候选是 `vaults.firstOrNull { it.id in unlocked }` ——
 * **第一个已解锁的库**，然后只问它一个「能不能快解」。
 * 若那个库早已启用（`available == true`），结果就是 `null`：
 * 排在它后面的**新库永远查不到**，用户看到的现象是
 * 「新加的库不会自动关联快速解锁，也没有任何提示」。
 *
 * ## 本类守的不变量
 *
 * 1. **前一个已启用 ≠ 全都已启用**：后面的新库必须仍被选中；
 * 2. **真的都启用才不提示**：不能靠"第一个"下结论；
 * 3. **「以后再说」生效**：`dismissed` 时整批不提示；
 * 4. **保持响应性**：任一库启用后横幅要**自动消失**，而不是等列表再变一次 ——
 *    所以实现必须是 Flow 组合，不能退化成一次性读取。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultListViewModelQuickUnlockSuggestTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val vaultRepository = mockk<VaultRepository>(relaxed = true)
    private val preferences = mockk<VaultixPreferences>(relaxed = true)

    private fun viewModel() = VaultListViewModel(
        vaultRepository = vaultRepository,
        preferences = preferences,
        syncOrchestrator = mockk<BitwardenSyncOrchestrator>(relaxed = true),
        localUnlockEnrollment = mockk<LocalUnlockEnrollment>(relaxed = true),
        kdbxSyncRepository = mockk<KdbxSyncRepository>(relaxed = true),
        legacyQuickUnlockCleanup = mockk<LegacyQuickUnlockCleanup>(relaxed = true),
        unlockRecovery = mockk<UnlockRecoveryRepository>(relaxed = true),
    )

    private fun summary(id: String) = VaultSummary(
        id = id,
        kind = VaultKind.BITWARDEN,
        name = id,
        origin = id,
        unlocked = true,
    )

    @Test
    fun `前一个库已启用_仍能发现后面的新库`() = runTest(dispatcher) {
        // ★ 本类最重要的一条：旧实现在这里必然返回 null。
        val old = summary("old")
        val fresh = summary("fresh")

        every { vaultRepository.observeVaults() } returns flowOf(listOf(old, fresh))
        every { vaultRepository.observeUnlockedVaultIds() } returns flowOf(setOf("old", "fresh"))
        every { preferences.isQuickUnlockPromptDismissed() } returns flowOf(false)
        // 老库早已启用 ⇒ 旧实现取到它就返回 null，新库被永久掩盖。
        every { vaultRepository.fingerprintQuickUnlockAvailable("old") } returns flowOf(true)
        every { vaultRepository.fingerprintQuickUnlockAvailable("fresh") } returns flowOf(false)

        val vm = viewModel()
        val job = launch { vm.quickUnlockSuggest.collect {} }
        advanceUntilIdle()

        assertThat(vm.quickUnlockSuggest.value?.id).isEqualTo("fresh")

        job.cancel()
    }

    @Test
    fun `全部已启用_才不显示横幅`() = runTest(dispatcher) {
        val a = summary("a")
        val b = summary("b")

        every { vaultRepository.observeVaults() } returns flowOf(listOf(a, b))
        every { vaultRepository.observeUnlockedVaultIds() } returns flowOf(setOf("a", "b"))
        every { preferences.isQuickUnlockPromptDismissed() } returns flowOf(false)
        every { vaultRepository.fingerprintQuickUnlockAvailable("a") } returns flowOf(true)
        every { vaultRepository.fingerprintQuickUnlockAvailable("b") } returns flowOf(true)

        val vm = viewModel()
        val job = launch { vm.quickUnlockSuggest.collect {} }
        advanceUntilIdle()

        assertThat(vm.quickUnlockSuggest.value).isNull()

        job.cancel()
    }

    @Test
    fun `未解锁的库不参与候选`() = runTest(dispatcher) {
        // 横幅的语义是「已解锁但还不能快解」—— 锁定中的库无从判断，不该被推给用户。
        val locked = summary("locked").copy(unlocked = false)

        every { vaultRepository.observeVaults() } returns flowOf(listOf(locked))
        every { vaultRepository.observeUnlockedVaultIds() } returns flowOf(emptySet())
        every { preferences.isQuickUnlockPromptDismissed() } returns flowOf(false)

        val vm = viewModel()
        val job = launch { vm.quickUnlockSuggest.collect {} }
        advanceUntilIdle()

        assertThat(vm.quickUnlockSuggest.value).isNull()

        job.cancel()
    }

    @Test
    fun `以后再说_整批不提示`() = runTest(dispatcher) {
        val fresh = summary("fresh")

        every { vaultRepository.observeVaults() } returns flowOf(listOf(fresh))
        every { vaultRepository.observeUnlockedVaultIds() } returns flowOf(setOf("fresh"))
        every { preferences.isQuickUnlockPromptDismissed() } returns flowOf(true)

        val vm = viewModel()
        val job = launch { vm.quickUnlockSuggest.collect {} }
        advanceUntilIdle()

        assertThat(vm.quickUnlockSuggest.value).isNull()

        job.cancel()
    }

    @Test
    fun `库启用后_横幅自动消失`() = runTest(dispatcher) {
        // ★ 响应性回归：修 P1 时若退化成"一次性读取"，此例会红 ——
        //   因为首次判定之后不再订阅 availability，后续变化收不到。
        val only = summary("only")
        val available = MutableStateFlow(false)

        every { vaultRepository.observeVaults() } returns flowOf(listOf(only))
        every { vaultRepository.observeUnlockedVaultIds() } returns flowOf(setOf("only"))
        every { preferences.isQuickUnlockPromptDismissed() } returns flowOf(false)
        every { vaultRepository.fingerprintQuickUnlockAvailable("only") } returns available

        val vm = viewModel()
        val job = launch { vm.quickUnlockSuggest.collect {} }
        advanceUntilIdle()
        assertThat(vm.quickUnlockSuggest.value?.id).isEqualTo("only")

        // 用户在设置页/横幅里启用成功 ⇒ 横幅应立即消失。
        available.value = true
        advanceUntilIdle()

        assertThat(vm.quickUnlockSuggest.value).isNull()

        job.cancel()
    }
}
