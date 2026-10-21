/*
 * Vaultix — data:repository（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.data.repository

import io.mockk.every
import io.mockk.mockk
import io.vaultix.crypto.SymmetricCryptoKey
import io.vaultix.crypto.VaultixCrypto
import io.vaultix.database.dao.CollectionDao
import io.vaultix.database.dao.OrganizationDao
import io.vaultix.database.entity.CollectionEntity
import io.vaultix.database.entity.OrganizationEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 组织 / 集合读取（2026-10-21，P1 组织视图）。
 *
 * ## 三层钥匙的每一层都有反证
 *
 * 正向用例只证明"能解出来"；真正要钉的是**解不出来时不能连数据一起消失**：
 * - 没有组织密钥（profile 没给）⇒ 组织行仍在，名字是占位名 → [无组织密钥时组织仍在列表里且用占位名]
 * - 组织密钥被**别的账号密钥**包裹（换账号 / 密钥不对）⇒ 同上 → [账号密钥不匹配时同样保留占位名]
 * - 集合名用**错误密钥**加密 ⇒ 集合行仍在 → [集合名解不开时集合仍在列表里]
 * - 未解锁 ⇒ 两个流都为空 → [未解锁时两个流都为空]（防止"锁屏还能看到组织名"）
 *
 * ⚠️ 这几条都是"断言某个东西**不**发生"，必须配正向用例一起读：
 * [组织名与集合名用三层密钥正确解出] 用**同一份 DAO 数据 + 正确密钥**跑通全链路。
 */
class OrganizationRepositoryImplTest {

    private val vaultId = "https://vault.example.com"
    private val orgId = "org-1"
    private val collectionId = "col-1"

    private val accountKey = SymmetricCryptoKey(ByteArray(32) { 0x11 }, ByteArray(32) { 0x22 })
    private val otherAccountKey = SymmetricCryptoKey(ByteArray(32) { 0x33 }, ByteArray(32) { 0x44 })
    private val orgKey = SymmetricCryptoKey(ByteArray(32) { 0x55 }, ByteArray(32) { 0x66 })

    private lateinit var crypto: VaultixCrypto
    private lateinit var sessions: VaultSessionManager
    private lateinit var organizationDao: OrganizationDao
    private lateinit var collectionDao: CollectionDao
    private lateinit var repo: OrganizationRepositoryImpl

    private var orgRows: List<OrganizationEntity> = emptyList()
    private var collectionRows: List<CollectionEntity> = emptyList()

    @Before
    fun setUp() {
        crypto = VaultixCrypto(Dispatchers.Default)
        sessions = VaultSessionManager(KdbxSessionFlow())
        organizationDao = mockk(relaxed = true)
        collectionDao = mockk(relaxed = true)
        every { organizationDao.observeByVault(vaultId) } answers { flowOf(orgRows) }
        every { collectionDao.observeByVault(vaultId) } answers { flowOf(collectionRows) }
        repo = OrganizationRepositoryImpl(
            organizationDao = organizationDao,
            collectionDao = collectionDao,
            sessions = sessions,
            crypto = crypto,
            cryptoDispatcher = Dispatchers.Default,
        )
    }

    @After
    fun tearDown() {
        accountKey.clear()
        otherAccountKey.clear()
        orgKey.clear()
    }

    /** 用 [wrapper] 包裹组织密钥（模拟 profile.organizations[i].key）。 */
    private fun wrappedOrgKey(wrapper: SymmetricCryptoKey): String =
        crypto.encrypt(orgKey.fullBytes(), wrapper)

    private fun encName(name: String, key: SymmetricCryptoKey = orgKey): String =
        crypto.encryptString(name, key)

    private fun orgRow(wrappedKey: String?): OrganizationEntity = OrganizationEntity(
        id = orgId,
        vaultId = vaultId,
        encryptedName = encName("共享团队"),
        wrappedKey = wrappedKey,
        status = 2,
    )

    private fun collectionRow(nameEnc: String): CollectionEntity = CollectionEntity(
        id = collectionId,
        vaultId = vaultId,
        organizationId = orgId,
        encryptedName = nameEnc,
    )

    private fun SymmetricCryptoKey.fullBytes(): ByteArray {
        val out = ByteArray(64)
        encKey.useBytes { it.copyInto(out, 0) }
        macKey.useBytes { it.copyInto(out, 32) }
        return out
    }

    @Test
    fun `组织名与集合名用三层密钥正确解出`() = runTest {
        sessions.unlock(vaultId, accountKey)
        orgRows = listOf(orgRow(wrappedOrgKey(accountKey)))
        collectionRows = listOf(collectionRow(encName("运维组")))

        val orgs = repo.observeOrganizations(vaultId).first()
        val cols = repo.observeCollections(vaultId).first()

        assertEquals(1, orgs.size)
        assertEquals("共享团队", orgs.first().name)
        assertEquals(1, cols.size)
        assertEquals("运维组", cols.first().name)
        assertEquals(orgId, cols.first().organizationId)
    }

    @Test
    fun `无组织密钥时组织仍在列表里且用占位名`() = runTest {
        sessions.unlock(vaultId, accountKey)
        orgRows = listOf(orgRow(wrappedKey = null))

        val orgs = repo.observeOrganizations(vaultId).first()

        // ★ 关键：组织**没有消失**（消失会让用户以为共享库丢了），只是名字降级。
        assertEquals(1, orgs.size)
        assertNotEquals("共享团队", orgs.first().name)
        assertTrue(orgs.first().name.isNotBlank())
    }

    @Test
    fun `账号密钥不匹配时同样保留占位名`() = runTest {
        sessions.unlock(vaultId, otherAccountKey)
        orgRows = listOf(orgRow(wrappedOrgKey(accountKey)))

        val orgs = repo.observeOrganizations(vaultId).first()

        assertEquals(1, orgs.size)
        assertNotEquals("共享团队", orgs.first().name)
    }

    @Test
    fun `集合名解不开时集合仍在列表里`() = runTest {
        sessions.unlock(vaultId, accountKey)
        orgRows = listOf(orgRow(wrappedOrgKey(accountKey)))
        // 名字用**错误密钥**加密：组织密钥解出的是垃圾 / 校验失败
        collectionRows = listOf(collectionRow(encName("运维组", otherAccountKey)))

        val cols = repo.observeCollections(vaultId).first()

        assertEquals(1, cols.size)
        assertNotEquals("运维组", cols.first().name)
    }

    @Test
    fun `未解锁时两个流都为空`() = runTest {
        orgRows = listOf(orgRow(wrappedOrgKey(accountKey)))
        collectionRows = listOf(collectionRow(encName("运维组")))

        assertTrue(repo.observeOrganizations(vaultId).first().isEmpty())
        assertTrue(repo.observeCollections(vaultId).first().isEmpty())
    }

    /**
     * 反证：解锁后**同一份** DAO 数据确实能读出来。
     * 若上面那条"未解锁为空"是因为路径根本没跑而假绿，这一条会一起红。
     */
    @Test
    fun `解锁后同一份数据能读出来（未解锁用例的反证）`() = runTest {
        orgRows = listOf(orgRow(wrappedOrgKey(accountKey)))
        collectionRows = listOf(collectionRow(encName("运维组")))
        assertTrue(repo.observeOrganizations(vaultId).first().isEmpty())

        sessions.unlock(vaultId, accountKey)

        assertEquals(1, repo.observeOrganizations(vaultId).first().size)
        assertEquals(1, repo.observeCollections(vaultId).first().size)
    }
}
