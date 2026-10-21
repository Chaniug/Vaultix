/*
 * Vaultix — data:bitwarden（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.data.bitwarden.sync

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.vaultix.crypto.VaultixCrypto
import io.vaultix.data.bitwarden.api.BitwardenVaultApi
import io.vaultix.data.bitwarden.auth.BitwardenAuthRepository
import io.vaultix.data.bitwarden.di.BitwardenApiFactory
import io.vaultix.data.bitwarden.model.CollectionDto
import io.vaultix.data.bitwarden.model.OrganizationDto
import io.vaultix.data.bitwarden.model.OrganizationProfileDto
import io.vaultix.data.bitwarden.model.ProfileDto
import io.vaultix.data.bitwarden.model.SyncResponse
import io.vaultix.database.dao.CipherDao
import io.vaultix.database.dao.CollectionDao
import io.vaultix.database.dao.FolderDao
import io.vaultix.database.dao.OrganizationDao
import io.vaultix.database.dao.PendingOpDao
import io.vaultix.database.dao.VaultDao
import io.vaultix.database.entity.CollectionEntity
import io.vaultix.database.entity.OrganizationEntity
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Test

/**
 * 组织 / 集合落库（2026-10-21，P1 组织视图）。
 *
 * 这里钉的是**「缺一半数据时不能把另一半抹掉」**——组织信息来自两个不同位置
 * （顶层给名字、profile 给密钥），而三种服务器实现给的字段集合并不一致：
 *
 * | 用例 | 服务器给什么 | 必须保住什么 |
 * |---|---|---|
 * | [顶层无名字但有密钥时沿用旧名字] | 只有 profile | 旧名字不能变 null |
 * | [有名字但无密钥时沿用旧密钥] | 只有顶层 | 旧密钥不能丢（丢了组织名解不开） |
 * | [组织段整段缺失时不动本地] | 两处都空 | 本地缓存原样保留 |
 * | [服务端明确有组织时才收敛移除的] | 顶层有 A，本地有 A+B | B 被删（真的退了组织） |
 *
 * ⚠️ 第 3、4 条是**同一条规则的两半**：`organizations` 为空既可能是"旧服务器没这个字段"
 * （必须不动），也可能是"用户退了所有组织"（必须清）。无法区分时选择**不动**——
 * 多留一条过期组织只是显示问题，错删一条会让共享条目名全部解不开。
 */
class BitwardenSyncOrganizationPersistTest {

    private val vaultId = "https://vault.example.com"
    private val server = vaultId

    private val vaultDao = mockk<VaultDao>(relaxed = true)
    private val cipherDao = mockk<CipherDao>(relaxed = true)
    private val folderDao = mockk<FolderDao>(relaxed = true)
    private val organizationDao = mockk<OrganizationDao>(relaxed = true)
    private val collectionDao = mockk<CollectionDao>(relaxed = true)
    private val pendingOpDao = mockk<PendingOpDao>(relaxed = true)
    private val authRepository = mockk<BitwardenAuthRepository>(relaxed = true)
    private val preferences = mockk<io.vaultix.datastore.VaultixPreferences>(relaxed = true)
    private val api = mockk<BitwardenVaultApi>()
    private val apiFactory = mockk<BitwardenApiFactory>()

    private lateinit var service: BitwardenSyncService

    private val existingOrg = OrganizationEntity(
        id = "org-1",
        vaultId = vaultId,
        encryptedName = "OLD_NAME_ENC",
        wrappedKey = "OLD_KEY_ENC",
        status = 2,
    )

    @Before
    fun setUp() {
        service = BitwardenSyncService(
            apiFactory = apiFactory,
            vaultDao = vaultDao,
            cipherDao = cipherDao,
            folderDao = folderDao,
            organizationDao = organizationDao,
            collectionDao = collectionDao,
            pendingOpDao = pendingOpDao,
            authRepository = authRepository,
            json = Json { ignoreUnknownKeys = true },
            preferences = preferences,
        )
        every { apiFactory.vault(server) } returns api
        // 首次同步（本地无 revision）⇒ 走全量；无本地条目 ⇒ 不触发空库保护。
        coEvery { vaultDao.get(vaultId) } returns null
        coEvery { cipherDao.listByVault(vaultId) } returns emptyList()
        coEvery { pendingOpDao.listByVault(vaultId) } returns emptyList()
        coEvery { folderDao.listByVault(vaultId) } returns emptyList()
        coEvery { organizationDao.listByVault(vaultId) } returns listOf(existingOrg)
        coEvery { collectionDao.listByVault(vaultId) } returns emptyList()
        coEvery { organizationDao.upsertAll(any()) } just runs
        coEvery { collectionDao.upsertAll(any()) } just runs
        coEvery { organizationDao.deleteByIds(any()) } just runs
        coEvery { collectionDao.deleteByIds(any()) } just runs
        coEvery { cipherDao.upsertAll(any()) } just runs
        coEvery { folderDao.upsertAll(any()) } just runs
        coEvery { vaultDao.updateRevision(any(), any()) } just runs
        // ⚠️ `pendingPruneFingerprint` 返回 Flow：relaxed mock 会造一个**空** Flow，
        //    `checkDeleteConfirmation` 里的 `.first()` 直接在空流上抛 NoSuchElementException
        //    （"Expected at least one element"），整条同步链断在这里。
        every { preferences.pendingPruneFingerprint(vaultId) } returns flowOf(null)
        // revision-date 探测：给 null 走全量
        coEvery { api.revisionDate() } throws IllegalStateException("no revision endpoint")
    }

    private fun stubSync(response: SyncResponse) {
        coEvery { api.sync() } returns response
    }

    @Test
    fun `顶层无名字但有密钥时沿用旧名字`() = runTest {
        stubSync(
            SyncResponse(
                profile = ProfileDto(
                    id = "u",
                    organizations = listOf(OrganizationProfileDto(id = "org-1", key = "NEW_KEY", status = 2)),
                ),
            ),
        )

        service.sync(vaultId, server, force = true)

        val saved = mutableListOf<List<OrganizationEntity>>()
        coVerify { organizationDao.upsertAll(capture(saved)) }
        assertThat(saved.single().single().wrappedKey).isEqualTo("NEW_KEY")
        // ★ 名字不能被服务器"没给"抹成 null
        assertThat(saved.single().single().encryptedName).isEqualTo("OLD_NAME_ENC")
    }

    @Test
    fun `有名字但无密钥时沿用旧密钥`() = runTest {
        stubSync(
            SyncResponse(
                organizations = listOf(OrganizationDto(id = "org-1", name = "NEW_NAME", status = 2)),
            ),
        )

        service.sync(vaultId, server, force = true)

        val saved = mutableListOf<List<OrganizationEntity>>()
        coVerify { organizationDao.upsertAll(capture(saved)) }
        assertThat(saved.single().single().encryptedName).isEqualTo("NEW_NAME")
        // ★ 密钥丢了 = 组织名从此解不开，必须沿用
        assertThat(saved.single().single().wrappedKey).isEqualTo("OLD_KEY_ENC")
    }

    @Test
    fun `组织段整段缺失时不动本地`() = runTest {
        stubSync(SyncResponse())

        service.sync(vaultId, server, force = true)

        coVerify(exactly = 0) { organizationDao.upsertAll(any()) }
        coVerify(exactly = 0) { organizationDao.deleteByIds(any()) }
        coVerify(exactly = 0) { collectionDao.deleteByIds(any()) }
    }

    @Test
    fun `服务端明确有组织时才收敛移除的`() = runTest {
        coEvery { organizationDao.listByVault(vaultId) }.returns(
            listOf(
                existingOrg,
                existingOrg.copy(id = "org-removed", encryptedName = "X", wrappedKey = "Y"),
            ),
        )
        stubSync(
            SyncResponse(
                organizations = listOf(OrganizationDto(id = "org-1", name = "N", status = 2)),
                collections = listOf(CollectionDto(id = "col-1", organizationId = "org-1", name = "C")),
            ),
        )

        service.sync(vaultId, server, force = true)

        val removed = mutableListOf<List<String>>()
        coVerify { organizationDao.deleteByIds(capture(removed)) }
        assertThat(removed.single()).containsExactly("org-removed")
    }

    /**
     * 反证：上面「整段缺失时不动本地」若因为**同步路径根本没走到这一步**而假绿，
     * 这条会一起红（同一套桩，只是服务器给了组织）。
     */
    @Test
    fun `服务器给了组织时确实写库（前一条的反证）`() = runTest {
        stubSync(
            SyncResponse(organizations = listOf(OrganizationDto(id = "org-1", name = "N", status = 2))),
        )

        service.sync(vaultId, server, force = true)
        coVerify(exactly = 1) { organizationDao.upsertAll(any()) }
    }

    @Test
    fun `status 为 0 时沿用旧状态`() = runTest {
        stubSync(
            SyncResponse(organizations = listOf(OrganizationDto(id = "org-1", name = "N", status = 0))),
        )

        service.sync(vaultId, server, force = true)

        val saved = mutableListOf<List<OrganizationEntity>>()
        coVerify { organizationDao.upsertAll(capture(saved)) }
        assertThat(saved.single().single().status).isEqualTo(2)
    }
}
