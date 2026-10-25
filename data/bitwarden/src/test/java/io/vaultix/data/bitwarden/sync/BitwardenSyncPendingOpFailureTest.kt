/*
 * Vaultix — data:bitwarden（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 「服务端 4xx 时怎么处置本地改动」的行为锁定（2026-10-26，`.ai/ISSUES.md` #190 / #192）。
 *
 * ## 为什么这个文件值得存在
 *
 * `flushPending` 对每一种失败都有一条**不对称**的处置：
 *
 * | 状态码 | 处置 | 漏掉会怎样 |
 * |---|---|---|
 * | 409 | **保留在队列**、累计重试 | 当成"确定性失败"弃单 ⇒ 用户改动静默消失、界面还显示"已同步" |
 * | 400/403/404 | 弃单（毒丸条目不能卡死整条队列） | 永远卡住，队列里堆着永远推不上去的 op |
 * | 500/网络 | 保留在队列 | 一次网络抖动就把用户刚写的东西丢掉 |
 *
 * 三条都在同一个 `when` 里，而**改错一条不会有任何编译错误** —— 只有测试能钉住。
 *
 * ⚠️ 这里**只测分支处置**，不测"409 之后真的合并成功"：合并（三方 merge）还没实现，
 * 现在的正确行为是"留在队列、如实报错"。测试若写成"409 之后同步成功"，
 * 那是在给一个不存在的功能发合格证。
 */
package io.vaultix.data.bitwarden.sync

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.vaultix.data.bitwarden.api.BitwardenVaultApi
import io.vaultix.data.bitwarden.auth.BitwardenAuthRepository
import io.vaultix.data.bitwarden.di.BitwardenApiFactory
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class BitwardenSyncPendingOpFailureTest {

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

    private val updateOp = PendingOpEntity(
        localId = 7L,
        vaultId = vaultId,
        cipherId = "cipher-1",
        op = "UPDATE",
        // ⚠️ 必须是**合法**的 CipherRequest JSON（`type` 是必填项）。
        //   第一版写的是 `"{}"`，于是 `executeOperation` 在发请求**之前**就抛
        //   `MissingFieldException`（序列化失败），被 `runCatching` 当网络类异常
        //   保留在队列 —— 测试于是测的是"反序列化失败"而不是"404 弃单"。
        //   这类假绿/假红都是夹具的错，不是产品的错。
        payload = """{"type":1,"name":"2.xxx"}""",
        createdAt = 1L,
    )

    /**
     * ⚠️ `coVerify` 之前必须 `confirmVerified` 不适用，但**必须把 dao 的软桩钉死**：
     * mockk 的 `incrementRetry` / `remove` 都是 `Unit` 返回的挂起函数，
     * 若用 `relaxed = true` 而不显式 stub，某些 mockk 版本会把它们当成"未配置"而静默吞掉。
     * 这里显式 `just runs`，让 `coVerify` 看到真实调用。
     */
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

    @Test
    fun `409 冲突必须保留在队列而不是弃单`() = runTest {
        coEvery { api.updateCipher(any(), any()) } throws httpError(409)

        val result = service.sync(vaultId, server, force = true)

        // ★ 主断言：队列里那一条**不能被移除**（移除 = 用户改动永久消失）。
        coVerify(exactly = 0) { pendingOpDao.remove(any()) }
        // 且必须累计重试（否则 STUCK 报警永远不会触发）。
        coVerify(exactly = 1) { pendingOpDao.incrementRetry(updateOp.localId) }
        // ★ 整体结果必须是"可重试错误"，不能是 Success —— 否则界面会显示"已同步"。
        assertThat(result).isInstanceOf(SyncOutcome.RetryableError::class.java)
    }

    @Test
    fun `404 仍然弃单`() = runTest {
        // 反证：不能为了照顾 409 就把整段 4xx 都改成"保留"——
        // 那会让 404（服务端条目已不存在）变成永远推不上去的毒丸，卡死整条队列。
        coEvery { api.updateCipher(any(), any()) } throws httpError(404)

        service.sync(vaultId, server, force = true)

        coVerify(exactly = 1) { pendingOpDao.remove(updateOp.localId) }
        coVerify(exactly = 0) { pendingOpDao.incrementRetry(any()) }
    }

    @Test
    fun `500 保留在队列并可重试`() = runTest {
        coEvery { api.updateCipher(any(), any()) } throws httpError(500)

        val result = service.sync(vaultId, server, force = true)

        coVerify(exactly = 0) { pendingOpDao.remove(any()) }
        coVerify(exactly = 1) { pendingOpDao.incrementRetry(updateOp.localId) }
        // 5xx 属于"服务端暂时不行"，界面要说"稍后重试"，不能说"已同步"。
        assertThat(result).isInstanceOf(SyncOutcome.RetryableError::class.java)
    }

    @Test
    fun `服务端返回空分组且本地只剩一个时照常收敛`() = runTest {
        // #192 的另一半：保护不能过头。本地只有 1 个多余分组（用户真在网页端删掉的）
        // 必须照常删掉，否则分组"删了又复活"。
        coEvery { pendingOpDao.listByVault(vaultId) } returns emptyList()
        coEvery { folderDao.listByVault(vaultId) } returns listOf(
            io.vaultix.database.entity.FolderEntity(
                id = "f1",
                vaultId = vaultId,
                encryptedName = "X",
                revisionDate = "2026-10-26T00:00:00.000Z",
            ),
        )
        coEvery { folderDao.deleteByIds(any()) } just runs

        service.sync(vaultId, server, force = true)

        coVerify(exactly = 1) { folderDao.deleteByIds(listOf("f1")) }
    }

    @Test
    fun `服务端返回空分组而本地有一堆时跳过删除`() = runTest {
        // #192：服务端 0 个分组、本地 3 个以上更像"响应异常"而不是"用户删光了"。
        coEvery { pendingOpDao.listByVault(vaultId) } returns emptyList()
        coEvery { folderDao.listByVault(vaultId) } returns (1..THRESHOLD).map { index ->
            io.vaultix.database.entity.FolderEntity(
                id = "f$index",
                vaultId = vaultId,
                encryptedName = "X$index",
                revisionDate = "2026-10-26T00:00:00.000Z",
            )
        }
        coEvery { folderDao.deleteByIds(any()) } just runs

        service.sync(vaultId, server, force = true)

        coVerify(exactly = 0) { folderDao.deleteByIds(any()) }
    }

    private fun httpError(code: Int): HttpException = HttpException(
        Response.error<Any>(
            code,
            "{}".toResponseBody("application/json".toMediaType()),
        ),
    )

    private companion object {
        /** 与 `BitwardenSyncService.FOLDER_PRUNE_SUSPECT_THRESHOLD` 对齐（3）。 */
        const val THRESHOLD = 3
    }
}
