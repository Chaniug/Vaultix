package com.bastion.app.viewmodel.behavior

import com.bastion.app.data.PasswordEntry
import com.bastion.app.repository.PasswordRepository
import com.bastion.app.security.SecurityManager
import com.bastion.app.viewmodel.PasswordViewModel
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * `PasswordViewModel` 删除路径的**行为**测试（Phase B.3 Step 1，集群 6 前置）。
 *
 * ## 为什么需要这组测试
 *
 * 删除/归档簇共 23 个函数，此前只有 1 个被任何测试引用过——等于**零回归网**。
 * 而这正是用户明令不得回归的「密码条目」热路径。在把这簇抽成
 * `PasswordDeleteOrchestrator` 之前，必须先用行为测试锁住当前语义，
 * 否则抽取过程中的任何语义漂移都不会被发现。
 *
 * ## 与既有守卫测试的区别
 *
 * 仓库里现有的 `*RegressionGuardTest` 绝大多数是**源码文本断言**
 * （读 .kt 文件、正则匹配函数体），它们只能证明「代码长得没变」，
 * 不能证明「行为没变」。本文件用 mockk 伪造 `PasswordRepository`，
 * 断言的是**真实调用链与写回的实体状态**，抽取重构后依然应当全绿。
 *
 * ## 测试夹具的关键约定：`context = null`
 *
 * `PasswordViewModel` 的多个协作者由 `context` 派生：
 * - `settingsManager = context?.let { SettingsManager(it) }` → null
 * - `bitwardenRepository = context?.let { BitwardenRepository.getInstance(...) }` → null
 * - `keepassBridge`（需要 context + localKeePassDatabaseDao 同时非空）→ null
 *
 * 传 `context = null` 后这些全部退化为 null，删除路径被收敛到**纯本地分支**，
 * 既避开了 Android Framework 依赖，也让断言目标唯一（只剩 `repository` 交互）。
 *
 * 副作用：`trashSettings` 也随之为 null，`trashEnabled` 固定取兜底值 `true`，
 * 因此「回收站关闭 → 永久删除」分支无法在此夹具下覆盖。该分支需等
 * 集群 8 把 `settingsManager` 改为构造注入后再补，已在计划文档中登记。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PasswordDeleteBehaviorTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun localEntry(id: Long = 1L, title: String = "本地条目") = PasswordEntry(
        id = id,
        title = title,
        website = "https://example.com",
        username = "user$id",
        password = "encrypted-$id"
    )

    private fun bitwardenEntry(
        id: Long = 2L,
        title: String = "Bitwarden 条目",
        cipherId: String? = "cipher-uuid-$id"
    ) = localEntry(id = id, title = title).copy(
        bitwardenVaultId = 100L,
        bitwardenCipherId = cipherId
    )

    private fun newViewModel(repository: PasswordRepository): PasswordViewModel {
        return PasswordViewModel(
            repository = repository,
            securityManager = mockk<SecurityManager>(relaxed = true),
            secureItemRepository = null,
            customFieldRepository = null,
            context = null,
            localKeePassDatabaseDao = null
        )
    }

    // ---------------------------------------------------------------------
    // 单条删除
    // ---------------------------------------------------------------------

    @Test
    fun `deleting a local entry with trash on writes back a soft-deleted entry`() = runTest {
        val repository = mockk<PasswordRepository>(relaxed = true)
        val viewModel = newViewModel(repository)
        val entry = localEntry()
        val written = slot<PasswordEntry>()

        viewModel.deletePasswordEntry(entry)

        coVerify(exactly = 1) { repository.updatePasswordEntry(capture(written)) }
        val softDeleted = written.captured
        assertEquals("应写回同一条目", entry.id, softDeleted.id)
        assertTrue("回收站开启时应软删除而非物理删除", softDeleted.isDeleted)
        assertNotNull("软删除必须记录删除时间，否则自动清空无法计时", softDeleted.deletedAt)
        assertFalse("移入回收站时应清除归档态，避免条目同时处于归档+回收站", softDeleted.isArchived)

        // 归档元数据必须随之清理，否则恢复时会读到陈旧的归档来源。
        coVerify(exactly = 1) { repository.deleteArchiveSyncMeta(entry.id) }
        // 回收站语义 = 不物理删除。
        coVerify(exactly = 0) { repository.deletePasswordEntry(any()) }
    }

    @Test
    fun `deleting a bitwarden cipher writes a tombstone marked for remote sync`() = runTest {
        val repository = mockk<PasswordRepository>(relaxed = true)
        val viewModel = newViewModel(repository)
        val entry = bitwardenEntry()
        val written = slot<PasswordEntry>()

        viewModel.deletePasswordEntry(entry)

        coVerify(exactly = 1) { repository.updatePasswordEntry(capture(written)) }
        val tombstone = written.captured
        assertTrue("Bitwarden 条目删除同样先落软删除墓碑", tombstone.isDeleted)
        assertTrue(
            "墓碑必须标记 bitwardenLocalModified，否则远端永远收不到这次删除",
            tombstone.bitwardenLocalModified
        )
        assertEquals("墓碑不得丢失 cipher 绑定，否则无法定位远端要删哪一条",
            entry.bitwardenCipherId, tombstone.bitwardenCipherId)
        coVerify(exactly = 1) { repository.deleteArchiveSyncMeta(entry.id) }
        coVerify(exactly = 0) { repository.deletePasswordEntry(any()) }
    }

    @Test
    fun `a bitwarden entry without cipher id falls back to the plain trash path`() = runTest {
        // bitwardenVaultId 存在但 cipherId 为空 = 只在本地建过、从未推上去的条目。
        // 此时 usesRemoteDeleteQueue = false，不应进远端删除队列。
        val repository = mockk<PasswordRepository>(relaxed = true)
        val viewModel = newViewModel(repository)
        val entry = bitwardenEntry(cipherId = null)
        val written = slot<PasswordEntry>()

        viewModel.deletePasswordEntry(entry)

        coVerify(exactly = 1) { repository.updatePasswordEntry(capture(written)) }
        assertTrue(written.captured.isDeleted)
        coVerify(exactly = 1) { repository.deleteArchiveSyncMeta(entry.id) }
    }

    // ---------------------------------------------------------------------
    // 批量删除
    // ---------------------------------------------------------------------

    @Test
    fun `batch delete of local entries writes them back in a single batch call`() = runTest {
        val repository = mockk<PasswordRepository>(relaxed = true)
        val viewModel = newViewModel(repository)
        val entries = listOf(localEntry(1L, "条目一"), localEntry(2L, "条目二"), localEntry(3L, "条目三"))
        val written = slot<List<PasswordEntry>>()

        val deletedCount = viewModel.deletePasswordEntriesBatch(entries)

        assertEquals("应返回实际删除条数", 3, deletedCount)
        coVerify(exactly = 1) { repository.updatePasswordEntries(capture(written)) }
        assertEquals(3, written.captured.size)
        assertTrue("批量删除的每一条都应处于软删除态", written.captured.all { it.isDeleted })
        assertTrue("批量删除的每一条都应带删除时间", written.captured.all { it.deletedAt != null })
    }

    @Test
    fun `batch delete reports progress from zero to total`() = runTest {
        val repository = mockk<PasswordRepository>(relaxed = true)
        val viewModel = newViewModel(repository)
        val entries = listOf(localEntry(1L), localEntry(2L))
        val progress = mutableListOf<Pair<Int, Int>>()

        viewModel.deletePasswordEntriesBatch(entries) { processed, total ->
            progress += processed to total
        }

        assertTrue("必须先报告 0/total，UI 才能立刻画出进度条起点", progress.isNotEmpty())
        assertEquals("首个进度回调应为 0/total", 0 to entries.size, progress.first())
        assertEquals("末个进度回调应为 total/total", entries.size to entries.size, progress.last())
    }

    @Test
    fun `batch delete of an empty list short-circuits without touching the repository`() = runTest {
        val repository = mockk<PasswordRepository>(relaxed = true)
        val viewModel = newViewModel(repository)

        val deletedCount = viewModel.deletePasswordEntriesBatch(emptyList())

        assertEquals(0, deletedCount)
        coVerify(exactly = 0) { repository.updatePasswordEntries(any()) }
        coVerify(exactly = 0) { repository.deletePasswordEntries(any()) }
    }
}
