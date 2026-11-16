/*
 * Vaultix — data:bitwarden（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * #298（2026-11-16 真机）：CREATE / UPDATE 请求体必须带 `encryptedFor`（= user id）。
 *
 * Vaultwarden 1.37+ 在 Rocket 的 Json<CipherData> 守卫里要求该字段，缺即在 handler
 * 之前 422。真机日志 `flushPending op=UPDATE ... code=422` + 从 pending_ops.payload
 * 直读的请求体（确无 encryptedFor）锁定了根因。官方 bitwarden-android / keyguard /
 * Monica 三客户端都发这个字段，且值 = access token 的 `sub` claim。
 *
 * 本文件钉三条：
 *   1. UPDATE / CREATE 发送前注入 encryptedFor，值 = JWT sub；
 *   2. 存量 op（payload 本无该字段）同样被注入 —— 这正是注入点放在发送出口的原因；
 *   3. token 缺失 / sub 非 UUID 时**降级跳过**而不是阻断同步（旧版服务端兼容）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.bitwarden.sync

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.vaultix.data.bitwarden.api.BitwardenVaultApi
import io.vaultix.data.bitwarden.auth.BitwardenAuthRepository
import io.vaultix.data.bitwarden.di.BitwardenApiFactory
import io.vaultix.data.bitwarden.model.CipherRequest
import io.vaultix.data.bitwarden.model.CipherResponse
import io.vaultix.data.bitwarden.model.SyncResponse
import io.vaultix.database.dao.CipherDao
import io.vaultix.database.dao.CollectionDao
import io.vaultix.database.dao.FolderDao
import io.vaultix.database.dao.OrganizationDao
import io.vaultix.database.dao.PendingOpDao
import io.vaultix.database.dao.VaultDao
import io.vaultix.database.entity.PendingOpEntity
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Test
import java.util.Base64

class BitwardenSyncEncryptedForTest {

    private val vaultId = "https://vault.example.com"
    private val server = vaultId
    /** accessTokenForHost 的 key 是纯 host——与生产 hostOf()/URL(server).host 同口径。 */
    private val host = "vault.example.com"
    private val cipherId = "cipher-1"
    private val userId = "3fa85f64-5717-4562-b3fc-2c963f66afa6"

    private val vaultDao = mockk<VaultDao>(relaxed = true)
    private val cipherDao = mockk<CipherDao>(relaxed = true)
    private val folderDao = mockk<FolderDao>(relaxed = true)
    private val organizationDao = mockk<OrganizationDao>(relaxed = true)
    private val collectionDao = mockk<CollectionDao>(relaxed = true)
    private val pendingOpDao = mockk<PendingOpDao>(relaxed = true)
    private val authRepository = mockk<BitwardenAuthRepository>(relaxed = true)
    private val preferences = mockk<io.vaultix.datastore.VaultixPreferences>(relaxed = true)
    private val api = mockk<BitwardenVaultApi>(relaxed = true)
    private val apiFactory = mockk<BitwardenApiFactory>()

    private lateinit var service: BitwardenSyncService

    /** 造一个三段式 JWT，payload 里带指定 sub（keyguard userId() 的前提）。 */
    private fun tokenWith(sub: String?): String {
        val payload = if (sub == null) "{}" else """{"sub":"$sub","email":"u@example.com"}"""
        val body = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(Charsets.UTF_8))
        return "header.$body.signature"
    }

    /** 存量 op：payload 里**没有** encryptedFor —— 这是真机被拒请求体的形态。 */
    private fun op(opName: String) = PendingOpEntity(
        localId = 1L,
        vaultId = vaultId,
        cipherId = cipherId,
        op = opName,
        payload = """{"type":1,"name":"2.iv|ct|mac","lastKnownRevisionDate":"2026-10-28T10:15:23.073585Z"}""",
        createdAt = 1L,
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
        coEvery { pendingOpDao.remove(any()) } just runs
        coEvery { pendingOpDao.incrementRetry(any()) } just runs
        coEvery { cipherDao.listByVault(vaultId) } returns emptyList()
        coEvery { folderDao.listByVault(vaultId) } returns emptyList()
        coEvery { organizationDao.listByVault(vaultId) } returns emptyList()
        coEvery { collectionDao.listByVault(vaultId) } returns emptyList()
        coEvery { vaultDao.get(vaultId) } returns null
        coEvery { vaultDao.updateRevision(any(), any()) } just runs
        coEvery { cipherDao.deleteByIds(any()) } just runs
        every { preferences.pendingPruneFingerprint(vaultId) } returns flowOf(null)
        // 让 sync 早早 Skipped，只测 flushPending 这一段
        coEvery { api.revisionDate() } returns 1L
        coEvery { api.sync() } returns SyncResponse()
    }

    /** 主契约：UPDATE 发送前注入 encryptedFor，值 = JWT sub。 */
    @Test
    fun `UPDATE 请求体必须带 encryptedFor 等于 user id`() = runTest {
        coEvery { pendingOpDao.listByVault(vaultId) } returns listOf(op("UPDATE"))
        every { authRepository.accessTokenForHost(host) } returns tokenWith(userId)
        val bodySlot = slot<CipherRequest>()
        coEvery { api.updateCipher(any(), capture(bodySlot)) } returns CipherResponse(cipherId, "rev")

        service.flushPending(vaultId, server)

        assertThat(bodySlot.isCaptured).isTrue()
        assertThat(bodySlot.captured.encryptedFor).isEqualTo(userId)
        // 其余字段不受影响
        assertThat(bodySlot.captured.name).isEqualTo("2.iv|ct|mac")
        // ★ 钉住"查 token 用的是 host 而非完整 URL"——首次真机验收正是栽在这里：
        //    传 https://vault.example.com 去查 serverByHost（key 是纯 host）⇒ null ⇒ 不注入。
        coVerify(exactly = 1) { authRepository.accessTokenForHost(host) }
        coVerify(exactly = 1) { pendingOpDao.remove(1L) }
    }

    /** CREATE 同样要带（同一协议要求）。 */
    @Test
    fun `CREATE 请求体必须带 encryptedFor 等于 user id`() = runTest {
        coEvery { pendingOpDao.listByVault(vaultId) } returns listOf(op("CREATE"))
        every { authRepository.accessTokenForHost(host) } returns tokenWith(userId)
        val bodySlot = slot<CipherRequest>()
        coEvery { api.createCipher(capture(bodySlot)) } returns CipherResponse(cipherId, "rev")

        service.flushPending(vaultId, server)

        assertThat(bodySlot.isCaptured).isTrue()
        assertThat(bodySlot.captured.encryptedFor).isEqualTo(userId)
    }

    /** 降级：token 拿不到时不注入，但同步照常进行（不阻断队列）。 */
    @Test
    fun `token 缺失时不注入 encryptedFor 但同步继续`() = runTest {
        coEvery { pendingOpDao.listByVault(vaultId) } returns listOf(op("UPDATE"))
        every { authRepository.accessTokenForHost(host) } returns null
        val bodySlot = slot<CipherRequest>()
        coEvery { api.updateCipher(any(), capture(bodySlot)) } returns CipherResponse(cipherId, "rev")

        service.flushPending(vaultId, server)

        assertThat(bodySlot.isCaptured).isTrue() // 请求照样发出去了
        assertThat(bodySlot.captured.encryptedFor).isNull() // 只是没注入
        coVerify(exactly = 1) { pendingOpDao.remove(1L) } // 队列照常清
    }

    /** 降级：sub 不是合法 UUID 时也不注入（防止畸形值打进请求体）。 */
    @Test
    fun `sub 非 UUID 时不注入 encryptedFor`() = runTest {
        coEvery { pendingOpDao.listByVault(vaultId) } returns listOf(op("UPDATE"))
        every { authRepository.accessTokenForHost(host) } returns tokenWith("not-a-uuid")
        val bodySlot = slot<CipherRequest>()
        coEvery { api.updateCipher(any(), capture(bodySlot)) } returns CipherResponse(cipherId, "rev")

        service.flushPending(vaultId, server)

        assertThat(bodySlot.isCaptured).isTrue()
        assertThat(bodySlot.captured.encryptedFor).isNull()
    }
}
