/*
 * Vaultix — data:bitwarden（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 「推送成功后，本地条目的 revisionDate 基线必须跟上服务端」的行为锁定（2026-11-16，#294）。
 *
 * ## 为什么这个文件值得存在
 *
 * 用户报「Bitwarden 自建服务器改了条目、点同步显示正常，服务器端看不到改动」。
 * 链条的起点是一条**看不见的基线**：
 *
 *   `CipherRequest.lastKnownRevisionDate` 取的是本地行里那个 `revisionDate`
 *   （`CipherMapper.toUpdateRequest` 的 `stored.revisionDate`）。服务端拿它做
 *   乐观并发校验 —— Vaultwarden `src/api/core/ciphers.rs`：
 *
 *   ```rust
 *   Ok(dt) if cipher.updated_at.signed_duration_since(dt).num_seconds() > 1
 *       => err!("The client copy of this cipher is out of date. Resync the client and try again.")
 *   ```
 *
 *   ⇒ 只要本地这个值比服务端旧超过 1 秒，**每一次** PUT 都会被拒。
 *
 * 而 `flushPending` 推送 UPDATE 成功后，服务端回的 `CipherResponse.revisionDate`
 * 被**直接丢弃**（`executeOperation` 的返回值只有 CREATE 分支用得上），
 * 本地行仍是编辑前那个旧值 ⇒ 用户的**第二次编辑**带着过期基线出门 ⇒ 被拒。
 *
 * ## 为什么用户看到的是「正常」
 *
 * 400 属于 `DEFINITIVE_HTTP_CODES`（401/409/408/429 之外的 4xx）⇒ 按「确定性失败」
 * **弃单**：队列行被 `remove`，本地行保留。而保存路径
 * （`ItemRepositoryImpl.flushAfterLocalWrite`）对推送失败只返回 `Queued`，
 * 界面照常显示「已保存」；UPDATE 不是 CREATE，不会抛异常。
 * ⇒ **改动永久留在本机，服务端一无所知，全程无一句报错**。
 *
 * ⚠️ 这里**只钉「基线必须推进」这一条**，不测服务端 400 文案：
 *    服务端行为不是本仓库能保证的东西，拿它做断言等于给外部系统发合格证。
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

class BitwardenSyncRevisionBaselineTest {

    private val vaultId = "https://vault.example.com"
    private val server = vaultId
    private val cipherId = "cipher-1"

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

    /** 编辑前本地记着的 revision（服务端当时那一版）。 */
    private val staleRevision = "2026-11-16T04:00:00.000000Z"

    /** 服务端这次保存后返回的新 revision。 */
    private val freshRevision = "2026-11-16T04:30:00.000000Z"

    /**
     * ⚠️ 夹具必须带 `lastKnownRevisionDate`。
     *
     * 第一版写的是 `{"type":1,"name":"2.xxx"}`，于是「请求体携带本地基线」那条
     * 断言拿到的是 null —— 那不是产品缺陷，是**夹具没复刻真实入队内容**
     * （真实 payload 由 `CipherMapper.toUpdateRequest` 生成，它一定填这个字段）。
     * 这类假红会把人往错方向带，见 `.ai/issues/07-数据与同步-part1.md` #91 的教训。
     */
    private fun updateOpWithBaseline(baseline: String) = PendingOpEntity(
        localId = 9L,
        vaultId = vaultId,
        cipherId = cipherId,
        op = "UPDATE",
        payload = """{"type":1,"name":"2.xxx","lastKnownRevisionDate":"$baseline"}""",
        createdAt = 1L,
    )

    private val updateOp = updateOpWithBaseline("2026-11-16T04:00:00.000000Z")

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
        coEvery { pendingOpDao.listByVault(vaultId) } returns listOf(updateOp)
        coEvery { pendingOpDao.remove(any()) } just runs
        coEvery { pendingOpDao.incrementRetry(any()) } just runs
        coEvery { vaultDao.get(vaultId) } returns null
        coEvery { cipherDao.listByVault(vaultId) } returns emptyList()
        coEvery { folderDao.listByVault(vaultId) } returns emptyList()
        coEvery { organizationDao.listByVault(vaultId) } returns emptyList()
        coEvery { collectionDao.listByVault(vaultId) } returns emptyList()
        coEvery { vaultDao.updateRevision(any(), any()) } just runs
        coEvery { cipherDao.deleteByIds(any()) } just runs
        every { preferences.pendingPruneFingerprint(vaultId) } returns flowOf(null)
        coEvery { api.revisionDate() } throws IllegalStateException("no revision endpoint")
        coEvery { api.sync() } returns SyncResponse()
    }

    /**
     * ★ 主契约：UPDATE 推送成功后，本地行的 `revisionDate` 必须推进到服务端回的新值。
     *
     * 不推进的后果（用户真机报的那条）：本地基线永远停在第一次同步那天的值，
     * 之后每次编辑都拿过期基线去 PUT ⇒ Vaultwarden 判「client copy is out of date」
     * 直接拒 ⇒ 400 被当成确定性失败弃单 ⇒ 改动只留在本机，界面还说正常。
     */
    @Test
    fun `UPDATE 推送成功后本地条目的 revisionDate 必须跟上服务端`() = runTest {
        // 本地行：编辑前那一版，revisionDate 是旧值。
        val before = io.vaultix.database.entity.CipherEntity(
            id = cipherId,
            vaultId = vaultId,
            type = 1,
            encryptedPayload = """{"id":"$cipherId","type":1,"revisionDate":"$staleRevision"}""",
            revisionDate = staleRevision,
        )
        coEvery { cipherDao.get(cipherId) } returns before
        coEvery { api.updateCipher(any(), any()) } returns CipherResponse(cipherId, freshRevision)
        val rowSlot = slot<List<io.vaultix.database.entity.CipherEntity>>()
        coEvery { cipherDao.upsertAll(capture(rowSlot)) } just runs

        service.flushPending(vaultId, server)

        // 队列必须清掉（推送成功）。
        coVerify(exactly = 1) { pendingOpDao.remove(updateOp.localId) }
        // ★ 落库那一行必须带新 revisionDate，不能还写旧值。
        assertThat(rowSlot.isCaptured).isTrue()
        val saved = rowSlot.captured.single()
        assertThat(saved.revisionDate).isEqualTo(freshRevision)
        // 载荷里的 revisionDate 也要一起推进：`toUpdateRequest` 读的是载荷里的那份。
        assertThat(saved.encryptedPayload).contains(freshRevision)
    }

    /**
     * ★ #296：推送成功后必须清掉 `vaultixLocalRevisionDate`。
     *
     * 它是导入用的本地展示字段，`CipherMapper.toDomain()` 里优先级高于服务端 revisionDate。
     * 不清掉的话，用户改完、推送也成功了，详情页「修改于」仍停在导入时间 ——
     * 用户拿这个时间当同步判据，就会一直以为没同步上去（本次真机反馈的第二个症状）。
     */
    @Test
    fun `UPDATE 推送成功后必须清除本地展示时间`() = runTest {
        val before = io.vaultix.database.entity.CipherEntity(
            id = cipherId,
            vaultId = vaultId,
            type = 1,
            encryptedPayload = """{"id":"$cipherId","type":1,"revisionDate":"$staleRevision",""" +
                """"vaultixLocalRevisionDate":"2026-10-01T00:00:00.000Z"}""",
            revisionDate = staleRevision,
        )
        coEvery { cipherDao.get(cipherId) } returns before
        coEvery { api.updateCipher(any(), any()) } returns CipherResponse(cipherId, freshRevision)
        val rowSlot = slot<List<io.vaultix.database.entity.CipherEntity>>()
        coEvery { cipherDao.upsertAll(capture(rowSlot)) } just runs

        service.flushPending(vaultId, server)

        assertThat(rowSlot.isCaptured).isTrue()
        val saved = rowSlot.captured.single()
        assertThat(saved.encryptedPayload).doesNotContain("vaultixLocalRevisionDate")
        assertThat(saved.encryptedPayload).contains(freshRevision)
    }

    /**
     * 反证的另一半：服务端没回 revision（空串）时**不能**把本地写成空。
     *
     * 空基线比旧基线更坏 —— `takeIf { it.isNotBlank() }` 会把它变成"不带
     * lastKnownRevisionDate"，服务端于是**放弃并发检测**，多设备并发编辑静默互相覆盖。
     */
    @Test
    fun `服务端未回 revisionDate 时保留本地原值不清空`() = runTest {
        val before = io.vaultix.database.entity.CipherEntity(
            id = cipherId,
            vaultId = vaultId,
            type = 1,
            encryptedPayload = """{"id":"$cipherId","type":1,"revisionDate":"$staleRevision"}""",
            revisionDate = staleRevision,
        )
        coEvery { cipherDao.get(cipherId) } returns before
        coEvery { api.updateCipher(any(), any()) } returns CipherResponse(cipherId, "")
        val rowSlot = slot<List<io.vaultix.database.entity.CipherEntity>>()
        coEvery { cipherDao.upsertAll(capture(rowSlot)) } just runs

        service.flushPending(vaultId, server)

        // ⚠️ 断言的是**结果**不是**调用次数**：服务端没给新 revision 时直接不动本地行，
        //    连 upsert 都不该发生（写一次同样的值是纯噪音）。
        //    第一版这里断言 `isCaptured == true`，红了一次 —— 那是夹具在替产品
        //    发明语义，不是产品缺陷。
        if (rowSlot.isCaptured) {
            assertThat(rowSlot.captured.single().revisionDate).isEqualTo(staleRevision)
        }
        // 真正要守的性质：**绝不能**把非空基线写成空串（空 = 放弃并发检测）。
        coVerify(exactly = 0) { cipherDao.upsertAll(match { it.any { r -> r.revisionDate.isBlank() } }) }
        // 推送本身成功，队列必须清掉。
        coVerify(exactly = 1) { pendingOpDao.remove(updateOp.localId) }
    }

    /**
     * 断言「请求体里带的是本地那份基线」——这是本缺陷的**入口条件**，
     * 若哪天有人把 `lastKnownRevisionDate` 改掉，本测试会提醒他这里有一条链。
     */
    @Test
    fun `UPDATE 请求体携带本地 revisionDate 作为 lastKnownRevisionDate`() = runTest {
        val before = io.vaultix.database.entity.CipherEntity(
            id = cipherId,
            vaultId = vaultId,
            type = 1,
            encryptedPayload = """{"id":"$cipherId","type":1,"revisionDate":"$staleRevision"}""",
            revisionDate = staleRevision,
        )
        coEvery { cipherDao.get(cipherId) } returns before
        val bodySlot = slot<CipherRequest>()
        coEvery { api.updateCipher(any(), capture(bodySlot)) } returns
            CipherResponse(cipherId, freshRevision)
        coEvery { cipherDao.upsertAll(any()) } just runs

        service.flushPending(vaultId, server)

        assertThat(bodySlot.isCaptured).isTrue()
        assertThat(bodySlot.captured.lastKnownRevisionDate).isEqualTo(staleRevision)
    }
}
