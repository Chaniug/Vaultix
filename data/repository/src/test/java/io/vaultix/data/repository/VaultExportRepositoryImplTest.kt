/*
 * Vaultix — data:repository（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.data.repository

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.vaultix.crypto.SymmetricCryptoKey
import io.vaultix.data.bitwarden.export.BitwardenExportFacade
import io.vaultix.database.dao.VaultDao
import io.vaultix.database.entity.VaultEntity
import io.vaultix.domain.FolderRepository
import io.vaultix.domain.ImportPlan
import io.vaultix.domain.ImportedVault
import io.vaultix.domain.ItemRepository
import io.vaultix.domain.VaultNotUnlockedException
import io.vaultix.domain.VaultSaveOutcome
import io.vaultix.model.VaultFolder
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import io.vaultix.model.VaultKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * [VaultExportRepositoryImpl] 的**导入编排**单测（2026-10-28 扩容）。
 *
 * 只测编排队列与跳过语义，不重复测 `ImportDuplicatePolicy` 的判定口径
 * （那是 domain 单测的事）—— 这里关心的是：
 *
 * 1. 已存在 / 文件内重复的条目**一条都不能被写**（`createItem` 调用次数是硬证据）；
 * 2. 预演的数字与真正写入的数字**对得上**（差 1 都是用户能看见的谎）；
 * 3. 文件夹先建、条目按**源 id → 目标 id** 映射（不再按名字二次查找）；
 * 4. 库没解锁时**什么都不读**（别为了算个预览去碰明文）。
 */
class VaultExportRepositoryImplTest {

    private val vaultId = "vault-1"

    private fun item(
        title: String,
        username: String = "alice",
        type: VaultItemType = VaultItemType.Login,
        folderId: String? = null,
    ): VaultItem = VaultItem(
        id = "",
        title = title,
        username = username,
        type = type,
        folderId = folderId,
    )

    private class Fixture(existing: List<VaultItem>) {
        val itemRepository: ItemRepository = mockk()
        val folderRepository: FolderRepository = mockk()
        val vaultDao: VaultDao = mockk()
        val sessions = VaultSessionManager(KdbxSessionFlow())
        val repo: VaultExportRepositoryImpl

        init {
            coEvery {
                itemRepository.createItem(any(), any())
            } returns Result.success(VaultSaveOutcome.Synced)
            every { itemRepository.observeItems(any()) } returns flowOf(existing)
            every { folderRepository.observeFolders(any()) } returns flowOf(emptyList())
            // 默认按 Bitwarden 库处理（kind = null 时不走 KDBX 分流）。
            coEvery { vaultDao.get(any()) } returns null
            repo = VaultExportRepositoryImpl(
                itemRepository = itemRepository,
                folderRepository = folderRepository,
                sessions = sessions,
                facade = mockk<BitwardenExportFacade>(relaxed = true),
                vaultDao = vaultDao,
                cryptoDispatcher = Dispatchers.Unconfined,
            )
        }
    }

    @Test
    fun `已存在的条目一条都不写 其余照写`() = runTest {
        val fixture = Fixture(existing = listOf(item("GitHub"), item("GitLab")))
        fixture.sessions.unlock(vaultId, SymmetricCryptoKey.random())

        val result = fixture.repo.applyImportedVault(
            vaultId,
            ImportedVault(
                folders = emptyList(),
                items = listOf(item("GitHub"), item("Gitea"), item("GitLab"), item("Codeberg")),
            ),
        )

        assertThat(result.written).isEqualTo(2)
        assertThat(result.duplicateExisting).isEqualTo(2)
        coVerify(exactly = 2) { fixture.itemRepository.createItem(any(), any()) }
    }

    @Test
    fun `文件内重复只写第一条`() = runTest {
        val fixture = Fixture(existing = emptyList())
        fixture.sessions.unlock(vaultId, SymmetricCryptoKey.random())
        val imported = ImportedVault(
            folders = emptyList(),
            items = listOf(item("GitHub"), item("GitHub")),
        )

        val plan = fixture.repo.previewImport(vaultId, imported)
        val result = fixture.repo.applyImportedVault(vaultId, imported)

        assertThat(plan.newItemCount).isEqualTo(1)
        assertThat(plan.duplicateInFileCount).isEqualTo(1)
        assertThat(result.written).isEqualTo(1)
        assertThat(result.duplicateInFile).isEqualTo(1)
        coVerify(exactly = 1) { fixture.itemRepository.createItem(any(), any()) }
    }

    @Test
    fun `导入会补建缺失文件夹并按源id映射条目`() = runTest {
        val fixture = Fixture(existing = emptyList())
        fixture.sessions.unlock(vaultId, SymmetricCryptoKey.random())
        coEvery { fixture.folderRepository.createFolder(vaultId, "工作") } returns
            Result.success(VaultFolder(id = "new-folder", name = "工作"))
        val imported = ImportedVault(
            folders = listOf(VaultFolder(id = "src-folder", name = "工作")),
            items = listOf(item("GitHub", folderId = "src-folder")),
        )

        val plan = fixture.repo.previewImport(vaultId, imported)
        val result = fixture.repo.applyImportedVault(vaultId, imported)

        assertThat(plan.newFolderCount).isEqualTo(1)
        assertThat(result.written).isEqualTo(1)
        coVerify(exactly = 1) { fixture.folderRepository.createFolder(vaultId, "工作") }
        coVerify(exactly = 1) {
            fixture.itemRepository.createItem(vaultId, match { it.folderId == "new-folder" })
        }
    }

    @Test
    fun `KDBX目标跳过不支持的类型并计数`() = runTest {
        val fixture = Fixture(existing = emptyList())
        fixture.sessions.unlock(vaultId, SymmetricCryptoKey.random())
        coEvery { fixture.vaultDao.get(any()) } returns mockk<VaultEntity> {
            every { kind } returns VaultKind.KDBX.name
        }
        val imported = ImportedVault(
            folders = emptyList(),
            items = listOf(item("Login"), item("Visa", type = VaultItemType.Card)),
        )

        val plan = fixture.repo.previewImport(vaultId, imported)
        val result = fixture.repo.applyImportedVault(vaultId, imported)

        assertThat(plan.newItemCount).isEqualTo(1)
        assertThat(plan.unsupportedCount).isEqualTo(1)
        assertThat(result.written).isEqualTo(1)
        assertThat(result.unsupported).isEqualTo(1)
        coVerify(exactly = 1) { fixture.itemRepository.createItem(any(), any()) }
    }

    @Test
    fun `预演的数字与实际写入的数字一致`() = runTest {
        val fixture = Fixture(existing = listOf(item("GitHub")))
        fixture.sessions.unlock(vaultId, SymmetricCryptoKey.random())
        val imported = ImportedVault(
            folders = emptyList(),
            items = listOf(item("GitHub"), item("Gitea"), item("Codeberg")),
        )

        val plan = fixture.repo.previewImport(vaultId, imported)
        val result = fixture.repo.applyImportedVault(vaultId, imported)

        assertThat(plan.newItemCount).isEqualTo(2)
        assertThat(plan.duplicateCount).isEqualTo(1)
        assertThat(result.written).isEqualTo(plan.newItemCount)
    }

    @Test
    fun `样例标题去重且最多三条`() = runTest {
        val existing = (1..5).map { item("Site$it") }
        val fixture = Fixture(existing = existing)
        fixture.sessions.unlock(vaultId, SymmetricCryptoKey.random())

        val plan = fixture.repo.previewImport(
            vaultId,
            ImportedVault(folders = emptyList(), items = existing + existing),
        )

        assertThat(plan.duplicateCount).isEqualTo(10)
        assertThat(plan.duplicateTitles).hasSize(ImportPlan.IMPORT_CONFLICT_SAMPLE)
        assertThat(plan.duplicateTitles).containsNoDuplicates()
    }

    @Test
    fun `全部已存在时新建数为零`() = runTest {
        val existing = listOf(item("GitHub"), item("GitLab"))
        val fixture = Fixture(existing = existing)
        fixture.sessions.unlock(vaultId, SymmetricCryptoKey.random())

        val plan = fixture.repo.previewImport(vaultId, ImportedVault(emptyList(), existing))

        assertThat(plan.newItemCount).isEqualTo(0)
        assertThat(plan.duplicateCount).isEqualTo(2)
    }

    @Test
    fun `库未解锁时预演直接抛错 不读明文`() = runTest {
        val fixture = Fixture(existing = listOf(item("GitHub")))

        val error = runCatching {
            fixture.repo.previewImport(vaultId, ImportedVault(emptyList(), listOf(item("GitHub"))))
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(VaultNotUnlockedException::class.java)
        coVerify(exactly = 0) { fixture.itemRepository.observeItems(any()) }
    }

    @Test
    fun `写入路径同样要求已解锁`() = runTest {
        val fixture = Fixture(existing = emptyList())

        val error = runCatching {
            fixture.repo.applyImportedVault(vaultId, ImportedVault(emptyList(), listOf(item("GitHub"))))
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(VaultNotUnlockedException::class.java)
        coVerify(exactly = 0) { fixture.itemRepository.createItem(any(), any()) }
        verify(exactly = 0) { fixture.folderRepository.observeFolders(any()) }
    }
}
