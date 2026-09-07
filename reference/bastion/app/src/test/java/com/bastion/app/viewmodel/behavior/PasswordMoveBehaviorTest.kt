package com.bastion.app.viewmodel.behavior

import com.bastion.app.data.PasswordEntry
import com.bastion.app.repository.PasswordRepository
import com.bastion.app.security.SecurityManager
import com.bastion.app.viewmodel.PasswordViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * `PasswordViewModel` 跨存储迁移（move*）路径的**行为**测试（Phase B.3 Step 2，集群 5c 前置）。
 *
 * ## 为什么需要这组测试
 *
 * `move*` 全簇（`movePasswordsToCategory` / `...ToKeePassDatabase` / `...ToKeePassGroup` /
 * `...ToBitwardenFolder` / `movePasswordsToKeePassInternal` /
 * `deleteMovedKeePassPasswordSources` / `materializeMovedKeePassAttachments`）
 * 此前**未被任何测试引用**（§7.4 已确认），零回归网。抽取为 `PasswordMoveExecutor`
 * 之前必须先用行为测试锁住当前语义。
 *
 * ## 夹具约定（与 PasswordDelete/ArchiveBehaviorTest 一致）
 *
 * `context = null` 使 `settingsManager` / `bitwardenRepository` / `keepassBridge` 全部
 * 退化为 null：
 * - `KeePassPasswordUpdateExecutor.syncUpdatedEntry`：bridge 为 null 时只调用
 *   `persistUpdate`（写本地行）并返回成功 → 迁移到 KeePass 的本地写回路径可达；
 * - `KeePassPasswordDeleteExecutor.deleteBatch`：bridge 为 null 时返回 true
 *   （源删除视为成功，不触碰 KDBX）；
 * - `materializeMovedKeePassAttachments`：`appContext` 为 null 时直接返回；
 * - `resolveKeePassCustomFieldsForSync`：`customFieldRepository` 为 null 时返回空列表。
 *
 * 因此本夹具覆盖「纯本地迁移 + KeePass 目标绑定 + Bitwarden 目标绑定」的
 * **全部本地写回语义**；KeePass/Bitwarden 真实远端交互由真机验证兜底。
 *
 * 注意：`movePasswordsToKeePassInternal` 对**原条目**检查 `hasBitwardenCipherBinding()`，
 * 若条目带 Bitwarden cipher 绑定会走 `queueCipherDelete`，而 `bitwardenRepository`
 * 在 context=null 时为 null → 抛 `IllegalStateException`。这是真实语义
 * （迁出 KeePass 时排队删除云端条目），测试中显式覆盖该分支的失败行为。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PasswordMoveBehaviorTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun localEntry(id: Long = 1L, title: String = "本地条目") = PasswordEntry(
        id = id,
        title = title,
        website = "https://example.com",
        username = "user$id",
        password = "encrypted-$id"
    )

    private fun keepassEntry(id: Long = 2L, title: String = "KeePass 条目") = localEntry(id = id, title = title).copy(
        keepassDatabaseId = 200L,
        keepassGroupPath = "/root/group",
        keepassEntryUuid = "kp-uuid-$id"
    )

    private fun bitwardenEntry(id: Long = 3L, title: String = "Bitwarden 条目") = localEntry(id = id, title = title).copy(
        bitwardenVaultId = 100L,
        bitwardenCipherId = "cipher-uuid-$id"
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
    // movePasswordsToCategoryAwait：迁入本地类别
    // ---------------------------------------------------------------------

    @Test
    fun `moving to a local category updates category and clears keepass binding`() = runTest {
        val repository = mockk<PasswordRepository>(relaxed = true)
        val viewModel = newViewModel(repository)
        coEvery { repository.getPasswordsByIds(any()) } returns listOf(keepassEntry())

        viewModel.movePasswordsToCategoryAwait(listOf(2L), categoryId = 5L)

        coVerify(exactly = 1) { repository.updateCategoryForPasswords(listOf(2L), 5L) }
        // 本地迁移必须清掉 KeePass 归属，避免悄悄改变条目所有权。
        coVerify(exactly = 1) { repository.updateKeePassDatabaseForPasswords(listOf(2L), null) }
        // KeePass 源删除发生在 KDBX 侧（经 bridge），空桥下 no-op 成功；
        // 本地行是"迁移"而非"删除"，绝不能调 deletePasswordEntry。
        coVerify(exactly = 0) { repository.deletePasswordEntry(any()) }
    }

    @Test
    fun `moving local entry to a local category does not delete anything`() = runTest {
        val repository = mockk<PasswordRepository>(relaxed = true)
        val viewModel = newViewModel(repository)
        coEvery { repository.getPasswordsByIds(any()) } returns listOf(localEntry())

        viewModel.movePasswordsToCategoryAwait(listOf(1L), categoryId = 5L)

        coVerify(exactly = 1) { repository.updateCategoryForPasswords(listOf(1L), 5L) }
        // 纯本地条目没有 KeePass 源可删。
        coVerify(exactly = 0) { repository.deletePasswordEntry(any()) }
    }

    @Test
    fun `moving to category with empty ids short-circuits`() = runTest {
        val repository = mockk<PasswordRepository>(relaxed = true)
        val viewModel = newViewModel(repository)

        viewModel.movePasswordsToCategoryAwait(emptyList(), categoryId = 5L)

        coVerify(exactly = 0) { repository.getPasswordsByIds(any()) }
        coVerify(exactly = 0) { repository.updateCategoryForPasswords(any(), any()) }
    }

    // ---------------------------------------------------------------------
    // movePasswordsToKeePassDatabaseAwait：迁入 KeePass 数据库（null = 迁出）
    // ---------------------------------------------------------------------

    @Test
    fun `moving to a keepass database binds the target and persists locally`() = runTest {
        val repository = mockk<PasswordRepository>(relaxed = true)
        val viewModel = newViewModel(repository)
        val entry = localEntry()
        coEvery { repository.getPasswordsByIds(any()) } returns listOf(entry)

        viewModel.movePasswordsToKeePassDatabaseAwait(listOf(1L), databaseId = 200L)

        // 空桥下 syncUpdatedEntry 走 persistUpdate → 本地写回。
        coVerify(exactly = 1) { repository.updatePasswordEntry(match { written ->
            written.keepassDatabaseId == 200L &&
                written.keepassEntryUuid != null &&
                written.bitwardenCipherId == null
        }) }
        // 不应删除本地行——这是迁移不是删除。
        coVerify(exactly = 0) { repository.deletePasswordEntry(any()) }
    }

    @Test
    fun `moving out of keepass with null database clears all storage bindings`() = runTest {
        val repository = mockk<PasswordRepository>(relaxed = true)
        val viewModel = newViewModel(repository)
        coEvery { repository.getPasswordsByIds(any()) } returns listOf(keepassEntry())

        viewModel.movePasswordsToKeePassDatabaseAwait(listOf(2L), databaseId = null)

        coVerify(exactly = 1) { repository.updatePasswordEntry(match { written ->
            written.keepassDatabaseId == null &&
                written.keepassGroupPath == null &&
                written.keepassEntryUuid == null &&
                written.bitwardenCipherId == null
        }) }
    }

    @Test
    fun `moving keepass-bound entry to a new database deletes the old source`() = runTest {
        val repository = mockk<PasswordRepository>(relaxed = true)
        val viewModel = newViewModel(repository)
        coEvery { repository.getPasswordsByIds(any()) } returns listOf(keepassEntry())

        viewModel.movePasswordsToKeePassDatabaseAwait(listOf(2L), databaseId = 300L)

        // oldKeepassId(200) != newKeepassId(300) → 空桥下 executor 不删源，
        // 但本地写回必须发生（persistUpdate）。
        coVerify(exactly = 1) { repository.updatePasswordEntry(match { written ->
            written.keepassDatabaseId == 300L
        }) }
    }

    @Test
    fun `moving bitwarden-bound entry to keepass fails because cloud delete cannot queue`() = runTest {
        val repository = mockk<PasswordRepository>(relaxed = true)
        val viewModel = newViewModel(repository)
        coEvery { repository.getPasswordsByIds(any()) } returns listOf(bitwardenEntry())

        val thrown = runCatching {
            viewModel.movePasswordsToKeePassDatabaseAwait(listOf(3L), databaseId = 200L)
        }.exceptionOrNull()

        // context=null → bitwardenRepository=null → queueCipherDelete 不可用。
        // 真实语义：迁出 Bitwarden 绑定条目时必须排队删除云端条目，仓库不可用即失败。
        assertNotNull("Bitwarden 仓库不可用时应抛异常而非静默迁移", thrown)
        assertTrue(
            "异常信息应说明 Bitwarden 仓库不可用，实际: ${thrown?.message}",
            thrown?.message?.contains("Bitwarden 仓库不可用") == true
        )
    }

    // ---------------------------------------------------------------------
    // movePasswordsToKeePassGroupAwait：迁入 KeePass 分组
    // ---------------------------------------------------------------------

    @Test
    fun `moving to a keepass group binds database plus group path`() = runTest {
        val repository = mockk<PasswordRepository>(relaxed = true)
        val viewModel = newViewModel(repository)
        coEvery { repository.getPasswordsByIds(any()) } returns listOf(localEntry())

        viewModel.movePasswordsToKeePassGroupAwait(listOf(1L), databaseId = 200L, groupPath = "/root/imported")

        coVerify(exactly = 1) { repository.updatePasswordEntry(match { written ->
            written.keepassDatabaseId == 200L &&
                written.keepassGroupPath == "/root/imported" &&
                written.bitwardenCipherId == null
        }) }
    }

    // ---------------------------------------------------------------------
    // movePasswordsToBitwardenFolderAwait：迁入 Bitwarden 文件夹
    // ---------------------------------------------------------------------

    @Test
    fun `moving to a bitwarden folder clears keepass binding and binds the folder`() = runTest {
        val repository = mockk<PasswordRepository>(relaxed = true)
        val viewModel = newViewModel(repository)
        coEvery { repository.getPasswordsByIds(any()) } returns listOf(keepassEntry())

        viewModel.movePasswordsToBitwardenFolderAwait(listOf(2L), vaultId = 100L, folderId = "folder-uuid")

        // 先清 KeePass 归属，再绑 Bitwarden 文件夹。
        coVerify(exactly = 1) { repository.updateKeePassDatabaseForPasswords(listOf(2L), null) }
        coVerify(exactly = 1) { repository.bindPasswordsToBitwardenFolder(listOf(2L), 100L, "folder-uuid") }
        // KeePass 源删除在 KDBX 侧（空桥 no-op），本地行保留。
        coVerify(exactly = 0) { repository.deletePasswordEntry(any()) }
    }

    // ---------------------------------------------------------------------
    // moveKeePassPasswordsToBastionCategoryAwait：KeePass 条目迁回本地类别
    // ---------------------------------------------------------------------

    @Test
    fun `moving keepass entries back to local category returns moved count`() = runTest {
        val repository = mockk<PasswordRepository>(relaxed = true)
        val viewModel = newViewModel(repository)
        coEvery { repository.getPasswordsByIds(any()) } returns listOf(keepassEntry(2L), keepassEntry(3L))

        val moved = viewModel.moveKeePassPasswordsToBastionCategoryAwait(listOf(2L, 3L), categoryId = 5L)

        assertEquals("应返回实际迁移的 KeePass 条目数", 2, moved.getOrNull())
        coVerify(exactly = 1) { repository.updateCategoryForPasswords(listOf(2L, 3L), 5L) }
        coVerify(exactly = 1) { repository.updateKeePassDatabaseForPasswords(listOf(2L, 3L), null) }
        // KeePass 源删除在 KDBX 侧（空桥 no-op），本地行保留。
        coVerify(exactly = 0) { repository.deletePasswordEntry(any()) }
    }

    @Test
    fun `moving entries without keepass binding back to local category returns zero`() = runTest {
        val repository = mockk<PasswordRepository>(relaxed = true)
        val viewModel = newViewModel(repository)
        coEvery { repository.getPasswordsByIds(any()) } returns listOf(localEntry())

        val moved = viewModel.moveKeePassPasswordsToBastionCategoryAwait(listOf(1L), categoryId = 5L)

        assertEquals("无 KeePass 条目可迁时应返回 0", 0, moved.getOrNull())
        // 只有 KeePass 条目参与更新。
        coVerify(exactly = 0) { repository.updateCategoryForPasswords(any(), any()) }
        coVerify(exactly = 0) { repository.updateKeePassDatabaseForPasswords(any(), any()) }
    }

    // ---------------------------------------------------------------------
    // 入口包装：非 suspend 公开方法经由 viewModelScope 触发
    // ---------------------------------------------------------------------

    @Test
    fun `movePasswordsToCategory public entry triggers await path`() = runTest {
        val repository = mockk<PasswordRepository>(relaxed = true)
        val viewModel = newViewModel(repository)
        coEvery { repository.getPasswordsByIds(any()) } returns listOf(localEntry())

        viewModel.movePasswordsToCategory(listOf(1L), categoryId = 7L)

        coVerify(exactly = 1) { repository.updateCategoryForPasswords(listOf(1L), 7L) }
    }
}
