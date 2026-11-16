/*
 * Vaultix — data:bitwarden（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 「同步过程必须能自证」的行为锁定（2026-11-16，`.ai/ISSUES.md` #297）。
 *
 * ## 为什么这个文件值得存在
 *
 * 真机两次日志把排查逼到了死胡同：
 *
 * ```
 * 14:28:52  VaultixSync: flushPending op=UPDATE ... code=422 definitive=true
 * 14:29:03  VaultixSync: sync skipped: revision 未变
 * ```
 * 随后（#296 之后）日志退化成只剩一行：
 * ```
 * 15:51:54  VaultixSync: sync skipped: revision 未变
 * ```
 * **`flushPending` 一条都没有** —— 于是无法区分两种完全不同的故障：
 *
 * | 可能 | 修法 |
 * |---|---|
 * | 改动**没进队列**（写路径没跑 / 被前置条件挡住） | 改 `ItemRepository` |
 * | 改动进了队列但**被清掉**（推送路径丢弃） | 改 `flushPending` |
 *
 * 根因是 `flushPending` 在「队列为空」时**静默 `return Result.success(Unit)`**：
 * 没日志、没痕迹、整体结果仍是 `Skipped`（上层当成功）。
 *
 * ⇒ 修法：空队列也要留一条 WARN，成功/失败都留收尾统计。本文件钉住这三件事，
 *   并顺便钉住「摘要只记类型与条数、不记 id」这条日志铁律。
 */
package io.vaultix.data.bitwarden.sync

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.vaultix.common.logging.LogLevel
import io.vaultix.common.logging.VaultixLog
import io.vaultix.data.bitwarden.api.BitwardenVaultApi
import io.vaultix.data.bitwarden.auth.BitwardenAuthRepository
import io.vaultix.data.bitwarden.di.BitwardenApiFactory
import io.vaultix.database.dao.CipherDao
import io.vaultix.database.dao.CollectionDao
import io.vaultix.database.dao.FolderDao
import io.vaultix.database.dao.OrganizationDao
import io.vaultix.database.dao.PendingOpDao
import io.vaultix.database.dao.VaultDao
import io.vaultix.database.entity.PendingOpEntity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test

class BitwardenSyncFlushObservabilityTest {

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

    /** 被捕获的日志：(tag, level, message)。 */
    private val captured = mutableListOf<Triple<String, LogLevel, String>>()

    private lateinit var service: BitwardenSyncService

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
        // ★ 直接装配真实门面：本文件要断言的**就是**日志内容，不能用 mock 掉 sink 的方式
        //   绕开门面（那样连限频与铁律都测不到）。
        VaultixLog.install(enabled = true) { tag, level, message, _ ->
            captured += Triple(tag, level, message)
        }
        every { apiFactory.vault(server) } returns api
        coEvery { pendingOpDao.remove(any()) } just runs
        coEvery { pendingOpDao.incrementRetry(any()) } just runs
        coEvery { cipherDao.listByVault(vaultId) } returns emptyList()
    }

    @After
    fun tearDown() {
        VaultixLog.reset()
        captured.clear()
    }

    private fun messagesOf(tag: String): List<String> =
        captured.filter { it.first == tag }.map { it.third }

    @Test
    fun `队列为空时必须留下日志而不是静默成功`() = runTest {
        // 真机现场：改动没上云，日志里连一条 flushPending 都没有。
        coEvery { pendingOpDao.listByVault(vaultId) } returns emptyList()

        val result = service.flushPending(vaultId, server)

        // 行为不变：空队列本来就不是错误。
        assertThat(result.isSuccess).isTrue()
        // ★ 但必须留痕，且要说清后果（本次同步不会上传任何改动）。
        val lines = messagesOf("VaultixSync")
        assertThat(lines).isNotEmpty()
        assertThat(lines.any { it.contains("队列为空") }).isTrue()
        assertThat(lines.any { it.contains("不会上传") }).isTrue()
        // 级别必须是 WARN：这是"用户改动可能没上去"的线索，不该埋在 DEBUG 里。
        assertThat(captured.filter { it.third.contains("队列为空") }.map { it.second })
            .containsExactly(LogLevel.WARN)
    }

    @Test
    fun `推送成功时必须报告条数且不泄露条目 id`() = runTest {
        val op = PendingOpEntity(
            localId = 7L,
            vaultId = vaultId,
            cipherId = "d19f28e7-8ff7-4aa6-b12b-94962c82e38c",
            op = "UPDATE",
            payload = """{"type":1,"name":"2.xxx"}""",
            createdAt = 1L,
        )
        coEvery { pendingOpDao.listByVault(vaultId) } returnsMany
            listOf(listOf(op), emptyList())
        coEvery { api.updateCipher(any(), any()) } returns
            io.vaultix.data.bitwarden.model.CipherResponse(id = op.cipherId, revisionDate = "fresh")

        val result = service.flushPending(vaultId, server)

        assertThat(result.isSuccess).isTrue()
        val lines = messagesOf("VaultixSync")
        assertThat(lines.any { it.contains("待推送 1 条") }).isTrue()
        assertThat(lines.any { it.contains("UPDATE=1") }).isTrue()
        assertThat(lines.any { it.contains("全部推送成功") }).isTrue()
        // ★ 铁律：摘要里不得出现条目 id（别人的错误报告里也不能泄露条目身份）。
        assertThat(lines.any { it.contains("d19f28e7") }).isFalse()
    }

    @Test
    fun `推送失败时必须说明改动仍在队列`() = runTest {
        val op = PendingOpEntity(
            localId = 7L,
            vaultId = vaultId,
            cipherId = "cipher-1",
            op = "UPDATE",
            payload = """{"type":1,"name":"2.xxx"}""",
            createdAt = 1L,
        )
        coEvery { pendingOpDao.listByVault(vaultId) } returnsMany
            listOf(listOf(op), listOf(op))
        coEvery { api.updateCipher(any(), any()) } throws
            retrofit2.HttpException(
                retrofit2.Response.error<Any>(500, "{}".toResponseBody()),
            )

        val result = service.flushPending(vaultId, server)

        assertThat(result.isFailure).isTrue()
        val lines = messagesOf("VaultixSync")
        assertThat(lines.any { it.contains("剩 1 条留在队列") }).isTrue()
        coVerify(exactly = 1) { pendingOpDao.incrementRetry(op.localId) }
    }
}
