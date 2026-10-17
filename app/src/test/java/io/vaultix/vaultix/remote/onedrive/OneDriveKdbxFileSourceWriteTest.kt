/*
 * Vaultix — app / OneDrive
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.remote.onedrive

import io.mockk.coEvery
import io.mockk.mockk
import io.vaultix.data.kdbx.KdbxFileConflictException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * [OneDriveKdbxFileSource] 的**契约层**行为（ISSUES #172 的第二道闸）。
 *
 * ## 守的是什么
 *
 * `KdbxFileSource.write` 的契约要求：**写完必须读回校验**。
 * OneDrive 这条路径原先既没有写前预检、也没有写后读回 ——
 * 唯一的无基线调用方（`Kdbx.createVault`）恰好先 `stat` 过，所以一直没炸。
 * 那是**调用方的自觉**，不是来源层的保证。
 *
 * 这里钉死三件事：
 * 1. 写成功后**一定**会再下载一次并逐字节比对（不是"看看响应码就信了"）；
 * 2. 读回内容不一致 ⇒ 抛 [KdbxFileConflictException]（**不是**静默算成功）；
 * 3. 读回失败（网络断）⇒ 同样抛冲突，**绝不**当成写成功
 *    —— "以为存好了其实没有"是这类同步最坏的失败模式。
 *
 * ## 为什么现在能测
 *
 * [OneDriveKdbxFileSource] 原先依赖具体的 `OneDriveAuthManager`（带
 * `@ApplicationContext` + MSAL），JVM 单测里**构造不出来**。
 * 2026-10-17 把它收窄成 [OneDriveTokenProvider] 接口，
 * 于是这里给个返回固定 token 的匿名实现就够，不需要模拟登录。
 *
 * ## 变异验证（还原成没修的版本，看测试是否精确报红）
 *
 * | 变异 | 期望报红的用例 |
 * |---|---|
 * | 删掉 `write` 末尾的 `verifyReadBack(...)` 调用 | 写成功后必须读回校验，内容不一致要抛冲突 |
 * | 把 `!actual.contentEquals(bytes)` 改成 `false`（永不报） | 同上 |
 * | 把读回失败那一支改成 `return`（当成功） | 读回失败必须抛冲突而不是当成功 |
 */
class OneDriveKdbxFileSourceWriteTest {

    private val written = ByteArray(128) { (it * 7).toByte() }

    /** 固定 token 的假来源 —— 本文件不关心登录，只关心写完之后的动作。 */
    private val tokenProvider = object : OneDriveTokenProvider {
        override suspend fun acquireAccessToken(accountId: String) = OneDriveAccountSession(
            accountId = accountId,
            username = "u@example.com",
            displayName = "U",
            accessToken = TOKEN,
        )
    }

    private fun entry(sizeBytes: Long?, etag: String? = ETAG_V2) = OneDriveEntry(
        id = "item-1",
        name = "vault.kdbx",
        isDirectory = false,
        versionToken = etag,
        sizeBytes = sizeBytes,
        lastModified = "2026-10-17T10:00:00Z",
    )

    private fun sourceWith(graph: OneDriveGraphClient) = OneDriveKdbxFileSource(
        authManager = tokenProvider,
        graph = graph,
        accountId = "acc-1",
        path = DB_PATH,
    )

    // ───────────────────────── 正向：确实做了读回 ─────────────────────────

    @Test
    fun `写成功后必须读回一次并比对内容`() = runBlocking {
        val graph = mockk<OneDriveGraphClient>()
        var downloadCount = 0
        coEvery {
            graph.upload(any(), any(), any(), any(), any(), any())
        } returns entry(sizeBytes = written.size.toLong())
        coEvery { graph.download(any(), any()) } coAnswers {
            downloadCount++
            written
        }

        val result = sourceWith(graph).write(bytes = written, expectedVersion = null)

        assertEquals("读回校验必须**恰好**做一次（多一次是浪费流量）", 1, downloadCount)
        assertEquals(ETAG_V2, result.versionToken)
    }

    // ───────────────────────── 读回不一致 ⇒ 冲突 ─────────────────────────

    @Test
    fun `写成功后读回内容不一致必须抛冲突`() = runBlocking {
        val graph = mockk<OneDriveGraphClient>()
        coEvery {
            graph.upload(any(), any(), any(), any(), any(), any())
        } returns entry(sizeBytes = written.size.toLong())
        // 服务端说写成功了、大小也对，但读回来是别的内容（被并发覆盖 / 传输中被截断）。
        coEvery { graph.download(any(), any()) } returns ByteArray(written.size) { 0 }

        try {
            sourceWith(graph).write(bytes = written, expectedVersion = null)
            fail("读回内容不一致时必须抛 KdbxFileConflictException，实际正常返回了")
        } catch (expected: KdbxFileConflictException) {
            assertArrayEquals(written, written)
        }
    }

    @Test
    fun `大小对不上时连下载都不该发生`() = runBlocking {
        val graph = mockk<OneDriveGraphClient>()
        var downloadCount = 0
        // 远端记录的 size 与写入字节数不一致 ⇒ 廉价预筛就该拦下，不必白下一次。
        coEvery {
            graph.upload(any(), any(), any(), any(), any(), any())
        } returns entry(sizeBytes = written.size + 1L)
        coEvery { graph.download(any(), any()) } coAnswers {
            downloadCount++
            written
        }

        try {
            sourceWith(graph).write(bytes = written, expectedVersion = null)
            fail("大小不一致必须抛冲突")
        } catch (expected: KdbxFileConflictException) {
            assertEquals("大小预筛命中后不应再下载", 0, downloadCount)
        }
    }

    // ───────────────────────── 读回失败 ⇒ 冲突（不能算成功） ─────────────────────────

    @Test
    fun `读回失败必须抛冲突而不是当成功`() = runBlocking {
        val graph = mockk<OneDriveGraphClient>()
        coEvery {
            graph.upload(any(), any(), any(), any(), any(), any())
        } returns entry(sizeBytes = written.size.toLong())
        coEvery { graph.download(any(), any()) } throws java.io.IOException("网络断了")

        try {
            sourceWith(graph).write(bytes = written, expectedVersion = null)
            fail("读回失败时必须抛冲突：把'读不回来'当成功，就是'以为存好了其实没有'")
        } catch (expected: KdbxFileConflictException) {
            // 原始异常必须挂在 cause 上，否则排查时看不到"到底是网络还是权限"。
            assertEquals(java.io.IOException::class.java, expected.cause?.javaClass)
        }
    }

    // ───────────────────────── 412 ⇒ 统一冲突类型 ─────────────────────────

    @Test
    fun `服务端 412 必须翻成本项目的冲突类型`() = runBlocking {
        val graph = mockk<OneDriveGraphClient>()
        coEvery {
            graph.upload(any(), any(), any(), any(), any(), any())
        } throws OneDrivePreconditionFailedException("HTTP 412")
        // 冲突后要去拉"当前版本"，用户才能选"用远端覆盖本地"。
        coEvery { graph.stat(any(), any()) } returns entry(sizeBytes = 1, etag = ETAG_REMOTE)

        try {
            sourceWith(graph).write(bytes = written, expectedVersion = ETAG_V1)
            fail("412 必须翻成 KdbxFileConflictException")
        } catch (expected: KdbxFileConflictException) {
            // ★ 关键：currentVersion 必须是**冲突之后**拿到的那个，
            //   否则用户点"用远端覆盖本地"时又会立刻 412，陷入死循环。
            assertEquals(ETAG_REMOTE, expected.currentVersion)
        }
    }

    private companion object {
        const val TOKEN = "test-token"
        const val DB_PATH = "Vaultix/vault.kdbx"
        const val ETAG_V1 = "\"etag-v1\""
        const val ETAG_V2 = "\"etag-v2\""
        const val ETAG_REMOTE = "\"etag-remote\""
    }
}
