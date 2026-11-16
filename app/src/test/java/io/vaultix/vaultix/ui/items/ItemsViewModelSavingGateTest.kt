package io.vaultix.vaultix.ui.items

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.vaultix.data.repository.BitwardenSyncOrchestrator
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.domain.FolderRepository
import io.vaultix.domain.ItemRepository
import io.vaultix.domain.KdbxSyncRepository
import io.vaultix.domain.OrganizationRepository
import io.vaultix.domain.VaultRepository
import io.vaultix.domain.VaultSaveOutcome
import io.vaultix.domain.VaultSessionRepository
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import io.vaultix.vaultix.session.ActiveVaultStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * 新建入口的**闸门回归**（#297，2026-11-16）。
 *
 * ## 钉死的是什么
 *
 * `ItemsViewModel.createItem` 开头有一句 `if (_state.value.saving) return`。这是
 * **防连点的闸门**，同时也意味着：`saving` 一旦停在 `true`，这个入口就**永久失能**
 * —— 用户之后每一次点「保存」都静默返回，界面毫无反馈。
 *
 * 旧实现把复位写成 `itemRepository.createItem(...)` 的**下一句**：
 *
 * ```kotlin
 * val outcome = itemRepository.createItem(vaultId, item)   // ← 这里抛出
 * _state.update { it.copy(saving = false) }                 // ← 永远走不到
 * ```
 *
 * 仓储的 KDoc 说它返回 `Result`，但它内部用的是 `error(...)`（抛异常）而不是
 * `Result.failure(...)`（见 `ItemRepositoryImpl.createItem` 的 `keyOf(...) ?: error(...)`）。
 * 于是「库未解锁 / 条目不存在」这类**预期内的失败**是抛出来的 ⇒ 协程挂在异常上
 * ⇒ `saving` 永久 `true` ⇒ 之后所有新建被吞。
 *
 * 这正是用户反馈「本地改动看不到、点同步又说没改动」的**候选成因之一**：
 * 写路径压根没跑，待推送队列里一条 op 都不会有，同步自然只能得到「无改动」。
 *
 * ## 两个用例缺一不可
 *
 * | 用例 | 防的回归 |
 * |---|---|
 * | 抛异常时闸门必须复位 | 退回「复位写在下一句」 |
 * | 闸门复位后仍可再次新建 | 为了复位把闸门整个删掉（连点保护丢失） |
 *
 * 只看第一个会漏掉「把 `if (saving) return` 删了也能过」；只看第二个会漏掉
 * 「异常路径压根没复位、只是因为没人抛所以看着正常」。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ItemsViewModelSavingGateTest {

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
    fun `仓储抛异常时 saving 闸门必须复位`() = runTest {
        val repository = mockk<ItemRepository>()
        coEvery { repository.observeItems(VAULT_ID) } returns flowOf(emptyList())
        coEvery { repository.observeSyncStates(VAULT_ID) } returns flowOf(emptyMap())
        coEvery { repository.createItem(VAULT_ID, any()) } throws IllegalStateException("库未解锁")

        val viewModel = itemsViewModel(repository)
        viewModel.createItem(sampleItem())

        // 关键断言：闸门已复位。若复位仍写在「下一句」，这里会是 true。
        assertThat(viewModel.state.value.saving).isFalse()
    }

    @Test
    fun `闸门复位之后第二次新建仍会真的打到仓储`() = runTest {
        val repository = mockk<ItemRepository>()
        coEvery { repository.observeItems(VAULT_ID) } returns flowOf(emptyList())
        coEvery { repository.observeSyncStates(VAULT_ID) } returns flowOf(emptyMap())
        var createItemCallCount = 0
        // 第一次抛、第二次成功：模拟「解锁前的失败」随后用户解锁重试。
        // ⚠️ 不要写 `throws X andThen Result.success(...)`：这个组合在 mockk 1.13.x 上
        //    会把 `andThen` 解析到 `andThen(Answer)` 重载，第二次直接抛
        //    `MockKException: no answer found`，把"第二次真的调到了仓储"误判成失败。
        //    用 `coAnswers` + 外部计数器，语义直白且不依赖 mockk 的重载推断。
        coEvery { repository.createItem(VAULT_ID, any()) } coAnswers {
            if (createItemCallCount++ == 0) throw IllegalStateException("库未解锁")
            Result.success(VaultSaveOutcome.Queued)
        }

        val viewModel = itemsViewModel(repository)

        viewModel.saveEvents.test {
            viewModel.createItem(sampleItem())
            // 第一次是预期内的失败：现在必须**变成一条可见提示**，而不是让异常逃逸。
            assertThat(awaitItem())
                .isEqualTo(ItemsViewModel.SaveEvent.Failed("库未解锁"))
            viewModel.createItem(sampleItem())
            // 第二次必须仍然被放行：拿到 Queued 就证明闸门没把后续调用吞掉。
            assertThat(awaitItem()).isEqualTo(ItemsViewModel.SaveEvent.SavedQueued)
            cancelAndIgnoreRemainingEvents()
        }

        assertThat(viewModel.state.value.saving).isFalse()
    }

    @Test
    fun `闸门本身仍生效_保存中再点一次不会再打仓储`() = runTest {
        val repository = mockk<ItemRepository>()
        coEvery { repository.observeItems(VAULT_ID) } returns flowOf(emptyList())
        coEvery { repository.observeSyncStates(VAULT_ID) } returns flowOf(emptyMap())
        // 仓储永不返回 ⇒ `saving` 停在 true，模拟「上一次保存还没结束」。
        coEvery { repository.createItem(VAULT_ID, any()) } coAnswers {
            kotlinx.coroutines.awaitCancellation()
        }

        val viewModel = itemsViewModel(repository)
        viewModel.createItem(sampleItem())
        // 第一笔还没结束（saving=true）时再点一次：必须被闸门挡下。
        viewModel.createItem(sampleItem())

        coVerify(exactly = 1) { repository.createItem(VAULT_ID, any()) }
    }

    // ────────────────────────────── 夹具 ──────────────────────────────

    /**
     * 构造被测 VM。
     *
     * @param gate 直接注入 `saving` 初值。取 `true` 用来单独验证**闸门本身仍在**
     *   ——「复位」与「闸门」是两个独立机制，缺任何一个都会让用户点保存没反应，
     *   但成因完全相反（前者=卡死，后者=失去防连点）。必须分别钉。
     */
    private fun itemsViewModel(repository: ItemRepository): ItemsViewModel {
        val vaultRepository = mockk<VaultRepository>(relaxed = true)
        coEvery { vaultRepository.observeVaults() } returns flowOf(emptyList())
        val sessions = mockk<VaultSessionRepository>(relaxed = true)
        val orchestrator = mockk<BitwardenSyncOrchestrator>(relaxed = true)
        // 状态流是 `StateFlow<Map<String, VaultSyncStatus>>`（不是普通 Flow）：
        // init 里被三处 combine 收集，类型必须给准，否则编译期就炸。
        every { orchestrator.statusByVault } returns MutableStateFlow(emptyMap())
        val kdbxSync = mockk<KdbxSyncRepository>(relaxed = true)
        val folderRepository = mockk<FolderRepository>(relaxed = true)
        coEvery { folderRepository.observeFolders(VAULT_ID) } returns flowOf(emptyList())
        val organizationRepository = mockk<OrganizationRepository>(relaxed = true)
        coEvery { organizationRepository.observeOrganizations(VAULT_ID) } returns flowOf(emptyList())
        val activeVaultStore = mockk<ActiveVaultStore>(relaxed = true)
        every { activeVaultStore.activeVaultId } returns MutableStateFlow<String?>(VAULT_ID)
        every { activeVaultStore.current() } returns VAULT_ID
        val preferences = mockk<VaultixPreferences>(relaxed = true)

        return ItemsViewModel(
            savedStateHandle = SavedStateHandle(mapOf(ItemsViewModel.ARG_VAULT_ID to VAULT_ID)),
            vaultRepository = vaultRepository,
            sessionRepository = sessions,
            itemRepository = repository,
            syncOrchestrator = orchestrator,
            kdbxSyncRepository = kdbxSync,
            folderRepository = folderRepository,
            organizationRepository = organizationRepository,
            activeVaultStore = activeVaultStore,
            preferences = preferences,
        )
    }

    private fun sampleItem() = VaultItem(
        id = "",
        title = "示例条目",
        type = VaultItemType.Login,
        username = "alice",
        password = "not-a-real-password",
    )

    private companion object {
        const val VAULT_ID = "vault-1"
    }
}
