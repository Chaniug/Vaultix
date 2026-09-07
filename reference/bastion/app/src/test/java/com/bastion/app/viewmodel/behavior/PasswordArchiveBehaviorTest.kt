package com.bastion.app.viewmodel.behavior

import com.bastion.app.data.PasswordArchiveSyncMeta
import com.bastion.app.data.PasswordEntry
import com.bastion.app.repository.PasswordRepository
import com.bastion.app.security.SecurityManager
import com.bastion.app.viewmodel.PasswordViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * `PasswordViewModel` 归档 / 取消归档路径的**行为**测试（Phase B.3 Step 1，集群 6 前置）。
 *
 * 与 [PasswordDeleteBehaviorTest] 同源动机：归档簇此前零测试覆盖，
 * 抽 `PasswordArchiveOrchestrator` 之前先用行为断言把语义钉死。
 *
 * 归档链路有两条容易在重构中被写坏的隐性契约，本文件专门盯住它们：
 *
 * 1. **写回顺序**：先 `updatePasswordEntry`（改条目归档态），再 `upsertArchiveSyncMeta`
 *    （记录归档来源）。顺序颠倒会让崩溃恢复时出现「有元数据但条目未归档」的孤儿态。
 * 2. **来源保真**：取消归档时 `originKeePassGroupPath` / `originBitwardenFolderId`
 *    必须从既有归档元数据读取，而不是从当前条目重新推断——条目在归档期间
 *    其分组字段可能已被同步逻辑改写，重新推断会把条目还原到错误的位置。
 *
 * 夹具约定（`context = null`）与副作用说明见 [PasswordDeleteBehaviorTest] 的类注释。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PasswordArchiveBehaviorTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun localEntry(id: Long = 1L, title: String = "本地条目") = PasswordEntry(
        id = id,
        title = title,
        website = "https://example.com",
        username = "user$id",
        password = "encrypted-$id"
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
    // 归档
    // ---------------------------------------------------------------------

    @Test
    fun `archiving a local entry flags it and records archive metadata`() = runTest {
        val repository = mockk<PasswordRepository>(relaxed = true)
        val entry = localEntry()
        coEvery { repository.getPasswordsByIds(listOf(entry.id)) } returns listOf(entry)
        val viewModel = newViewModel(repository)
        val written = slot<PasswordEntry>()
        val meta = slot<PasswordArchiveSyncMeta>()

        viewModel.archivePassword(entry.id)

        coVerify(exactly = 1) { repository.updatePasswordEntry(capture(written)) }
        assertTrue("归档应置 isArchived", written.captured.isArchived)
        assertNotNull("归档必须记录时间，归档列表按此排序", written.captured.archivedAt)
        assertFalse("归档不得顺带把条目标成已删除", written.captured.isDeleted)

        coVerify(exactly = 1) { repository.upsertArchiveSyncMeta(capture(meta)) }
        assertEquals(entry.id, meta.captured.entryId)
        assertEquals(
            "纯本地条目的归档提供方应为 LOCAL",
            PasswordArchiveSyncMeta.PROVIDER_LOCAL,
            meta.captured.providerType
        )
        assertEquals(
            "本地归档无需远端同步，应直接落 SYNCED",
            PasswordArchiveSyncMeta.STATUS_SYNCED,
            meta.captured.syncStatus
        )
        assertNull("成功路径不应带错误信息", meta.captured.lastError)
    }

    @Test
    fun `archiving a bitwarden entry marks metadata as pending remote sync`() = runTest {
        val repository = mockk<PasswordRepository>(relaxed = true)
        val entry = localEntry(2L, "Bitwarden 条目").copy(
            bitwardenVaultId = 100L,
            bitwardenCipherId = "cipher-uuid-2",
            bitwardenFolderId = "folder-uuid-9"
        )
        coEvery { repository.getPasswordsByIds(listOf(entry.id)) } returns listOf(entry)
        val viewModel = newViewModel(repository)
        val written = slot<PasswordEntry>()
        val meta = slot<PasswordArchiveSyncMeta>()

        viewModel.archivePassword(entry.id)

        coVerify(exactly = 1) { repository.updatePasswordEntry(capture(written)) }
        assertTrue(written.captured.isArchived)
        assertTrue(
            "Bitwarden 条目归档后必须标记待同步，否则远端看不到归档",
            written.captured.bitwardenLocalModified
        )

        coVerify(exactly = 1) { repository.upsertArchiveSyncMeta(capture(meta)) }
        assertEquals(
            PasswordArchiveSyncMeta.PROVIDER_BITWARDEN_NATIVE,
            meta.captured.providerType
        )
        assertEquals(
            "远端提供方归档应处于 PENDING，直到同步任务确认",
            PasswordArchiveSyncMeta.STATUS_PENDING,
            meta.captured.syncStatus
        )
        assertEquals(
            "必须留存原文件夹，取消归档时才知道放回哪里",
            "folder-uuid-9",
            meta.captured.originBitwardenFolderId
        )
    }

    @Test
    fun `archiving an already archived entry is a no-op`() = runTest {
        val repository = mockk<PasswordRepository>(relaxed = true)
        val entry = localEntry().copy(isArchived = true, archivedAt = java.util.Date())
        coEvery { repository.getPasswordsByIds(listOf(entry.id)) } returns listOf(entry)
        val viewModel = newViewModel(repository)

        viewModel.archivePassword(entry.id)

        coVerify(exactly = 0) { repository.updatePasswordEntry(any()) }
        coVerify(exactly = 0) { repository.upsertArchiveSyncMeta(any()) }
    }

    @Test
    fun `archiving skips entries already in the trash`() = runTest {
        // 已在回收站的条目若被归档，会同时具备 isDeleted + isArchived，
        // 导致它在回收站和归档两个列表里都出现（或都不出现）。
        val repository = mockk<PasswordRepository>(relaxed = true)
        val deleted = localEntry().copy(isDeleted = true, deletedAt = java.util.Date())
        coEvery { repository.getPasswordsByIds(listOf(deleted.id)) } returns listOf(deleted)
        val viewModel = newViewModel(repository)

        viewModel.archivePassword(deleted.id)

        coVerify(exactly = 0) { repository.updatePasswordEntry(any()) }
        coVerify(exactly = 0) { repository.upsertArchiveSyncMeta(any()) }
    }

    @Test
    fun `batch archive processes every requested entry`() = runTest {
        val repository = mockk<PasswordRepository>(relaxed = true)
        val entries = listOf(localEntry(1L, "条目一"), localEntry(2L, "条目二"))
        coEvery { repository.getPasswordsByIds(listOf(1L, 2L)) } returns entries
        val viewModel = newViewModel(repository)

        viewModel.archivePasswords(listOf(1L, 2L))

        coVerify(exactly = 2) { repository.updatePasswordEntry(any()) }
        coVerify(exactly = 2) { repository.upsertArchiveSyncMeta(any()) }
    }

    // ---------------------------------------------------------------------
    // 取消归档
    // ---------------------------------------------------------------------

    @Test
    fun `unarchiving clears the archive flags`() = runTest {
        val repository = mockk<PasswordRepository>(relaxed = true)
        val entry = localEntry().copy(isArchived = true, archivedAt = java.util.Date())
        coEvery { repository.getPasswordsByIds(listOf(entry.id)) } returns listOf(entry)
        val viewModel = newViewModel(repository)
        val written = slot<PasswordEntry>()

        viewModel.unarchivePasswordsAwait(listOf(entry.id))

        coVerify(exactly = 1) { repository.updatePasswordEntry(capture(written)) }
        assertFalse("取消归档应清除 isArchived", written.captured.isArchived)
        assertNull("取消归档应清空归档时间，避免残留导致再次误判", written.captured.archivedAt)
    }

    @Test
    fun `unarchiving restores origin from the stored metadata rather than the current entry`() = runTest {
        // 归档期间条目的 folderId 被同步改写成了 drifted-folder；
        // 取消归档必须以归档时记录的 original-folder 为准。
        val repository = mockk<PasswordRepository>(relaxed = true)
        val entry = localEntry(3L, "Bitwarden 归档条目").copy(
            isArchived = true,
            archivedAt = java.util.Date(),
            bitwardenVaultId = 100L,
            bitwardenCipherId = "cipher-uuid-3",
            bitwardenFolderId = "drifted-folder"
        )
        val storedMeta = PasswordArchiveSyncMeta(
            entryId = entry.id,
            providerType = PasswordArchiveSyncMeta.PROVIDER_BITWARDEN_NATIVE,
            originKeePassDatabaseId = null,
            originKeePassGroupPath = null,
            originBitwardenFolderId = "original-folder",
            syncStatus = PasswordArchiveSyncMeta.STATUS_SYNCED,
            lastError = null,
            updatedAt = System.currentTimeMillis()
        )
        coEvery { repository.getPasswordsByIds(listOf(entry.id)) } returns listOf(entry)
        coEvery { repository.getArchiveSyncMeta(entry.id) } returns storedMeta
        val viewModel = newViewModel(repository)
        val meta = slot<PasswordArchiveSyncMeta>()

        viewModel.unarchivePasswordsAwait(listOf(entry.id))

        coVerify(exactly = 1) { repository.upsertArchiveSyncMeta(capture(meta)) }
        assertEquals(
            "必须回到归档时记录的原文件夹，而不是归档期间漂移出来的那个",
            "original-folder",
            meta.captured.originBitwardenFolderId
        )
        assertEquals(
            "提供方应沿用归档元数据中记录的值",
            PasswordArchiveSyncMeta.PROVIDER_BITWARDEN_NATIVE,
            meta.captured.providerType
        )
    }

    @Test
    fun `unarchiving a non-archived entry is a no-op`() = runTest {
        val repository = mockk<PasswordRepository>(relaxed = true)
        val entry = localEntry()
        coEvery { repository.getPasswordsByIds(listOf(entry.id)) } returns listOf(entry)
        val viewModel = newViewModel(repository)

        viewModel.unarchivePasswordsAwait(listOf(entry.id))

        coVerify(exactly = 0) { repository.updatePasswordEntry(any()) }
        coVerify(exactly = 0) { repository.upsertArchiveSyncMeta(any()) }
    }

    @Test
    fun `archive and unarchive short-circuit on an empty id list`() = runTest {
        val repository = mockk<PasswordRepository>(relaxed = true)
        val viewModel = newViewModel(repository)

        viewModel.archivePasswords(emptyList())
        viewModel.unarchivePasswordsAwait(emptyList())

        coVerify(exactly = 0) { repository.getPasswordsByIds(any()) }
        coVerify(exactly = 0) { repository.updatePasswordEntry(any()) }
    }
}
