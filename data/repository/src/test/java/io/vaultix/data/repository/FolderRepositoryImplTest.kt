/*
 * Vaultix — data:repository（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.data.repository

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.vaultix.crypto.SymmetricCryptoKey
import com.google.common.truth.Truth.assertThat
import io.vaultix.crypto.VaultixCrypto
import io.vaultix.data.bitwarden.api.BitwardenVaultApi
import io.vaultix.data.bitwarden.di.BitwardenApiFactory
import io.vaultix.data.bitwarden.model.FolderDto
import io.vaultix.data.bitwarden.model.FolderRequest
import io.vaultix.data.kdbx.Kdbx
import io.vaultix.data.kdbx.KdbxFileSource
import io.vaultix.data.kdbx.KdbxFileStat
import io.vaultix.data.kdbx.KdbxFileWriteResult
import io.vaultix.database.dao.FolderDao
import io.vaultix.database.dao.VaultDao
import io.vaultix.database.entity.FolderEntity
import io.vaultix.database.entity.VaultEntity
import io.vaultix.domain.VaultSaveOutcome
import io.vaultix.model.VaultFolder
import io.vaultix.model.VaultKind
import kotlinx.coroutines.Dispatchers
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * 文件夹读路径的**按库类型分流**（A2 · 2026-10-13）。
 *
 * ## 修的是什么
 *
 * 此类此前**只绑 Room** ⇒ KDBX 库的分组整体不可见（表单下拉框恒空、
 * 分组视图全落"无文件夹"、导出丢分组、导入全部落根组），
 * 而 `KdbxMappedContent.folders` 早就映射好了，只差一个消费方。
 *
 * ## 判据与反证
 *
 * "KDBX 用例返回空"是一条**断言没发生**（Room 行没被泄出来）——
 * 它可能因为"整条路径根本没跑"而假绿。⇒ 同文件配了
 * [Bitwarden 库仍走 Room 解密链路] 作反证：**同一行 Room 数据**，
 * 在 Bitwarden 库下确实能解出来。两条一起读才知道"空"是真的分流对了。
 *
 * ⚠️ 分层口径：**非空分组的映射本身**（组树 → `VaultFolder`、嵌套路径、
 * 条目归属）由 `data:kdbx` 的 `KdbxReadPathTest` 钉死；
 * 本文件只钉 `data:repository` 这一层的分流——因为 `data:repository`
 * 的测试**造不出带分组的 KDBX 会话**（`KdbxSessionStore` / `KdbxEncoder`
 * 是 `data:kdbx` 的 internal，公开 API 也没有"建分组"这个动作）。
 */
class FolderRepositoryImplTest {

    private val vaultId = "https://vault.example.com"
    private val key: SymmetricCryptoKey = SymmetricCryptoKey.random()

    private lateinit var folderDao: FolderDao
    private lateinit var vaultDao: VaultDao
    private lateinit var sessions: VaultSessionManager
    private lateinit var kdbxSessions: KdbxSessionFlow
    private lateinit var crypto: VaultixCrypto
    private lateinit var apiFactory: BitwardenApiFactory
    private lateinit var api: BitwardenVaultApi
    private lateinit var kdbxItemWrites: KdbxItemRepository
    private lateinit var repo: FolderRepositoryImpl

    @Before
    fun setUp() {
        folderDao = mockk()
        vaultDao = mockk()
        // 与生产同构：Hilt 只有一个 KdbxSessionFlow 单例，同时喂给
        // VaultSessionManager 与 FolderRepositoryImpl —— 这里也只建一个。
        kdbxSessions = KdbxSessionFlow()
        sessions = VaultSessionManager(kdbxSessions)
        crypto = VaultixCrypto(Dispatchers.Default)
        api = mockk()
        apiFactory = mockk()
        every { apiFactory.vault(any()) } returns api
        kdbxItemWrites = mockk()
        repo = FolderRepositoryImpl(
            folderDao = folderDao,
            vaultDao = vaultDao,
            sessions = sessions,
            kdbxSessions = kdbxSessions,
            kdbxItemWrites = kdbxItemWrites,
            apiFactory = apiFactory,
            crypto = crypto,
            cryptoDispatcher = Dispatchers.Default,
        )
    }

    @After
    fun tearDown() {
        // `KdbxSessionStore` 是进程级单例：播的种必须清掉（同 ItemRepositoryImplTest）。
        Kdbx.lock(vaultId)
    }

    /**
     * ★ A2 的判据：KDBX 库的分组来自**内存会话**，Room 的 folder 行一个都不泄。
     *
     * 修前本类只绑 Room ⇒ 这一行会被解出来当成 KDBX 的分组 ——
     * 用户看到的分组来自一个**早就不同步的旧世界**（KDBX 文件里根本没有它）。
     */
    @Test
    fun `KDBX 库读会话分组，Room 文件夹行一个都不泄`() = runTest {
        // Room 里同时躺着 KDBX 库行和一条 folder 行（模拟"修前会泄"的那份数据）。
        every { vaultDao.observeAll() } returns flowOf(listOf(kdbxVaultRow()))
        every { folderDao.observeByVault(vaultId) } returns flowOf(listOf(roomFolderRow()))
        seedKdbxSession()

        val folders = repo.observeFolders(vaultId).first()

        // 新库只有根组（+ 回收站，两者都不进 folders）⇒ 空。
        // ★ 判据不是"空"本身，而是**不是** Room 那一行 —— 反证见下一条用例。
        assertTrue("KDBX 库不该读出 Room 的 folder 行，实际：$folders", folders.isEmpty())
    }

    /**
     * 反证 + Bitwarden 回归：**同一行 Room 数据**在 Bitwarden 库下确实能解出来。
     *
     * 没有这一条，上面那个"空"分不清是"分流正确"还是"装置根本没跑"。
     */
    @Test
    fun `Bitwarden 库仍走 Room 解密链路`() = runTest {
        sessions.unlock(vaultId, key)
        every { vaultDao.observeAll() } returns flowOf(listOf(bitwardenVaultRow()))
        every { folderDao.observeByVault(vaultId) } returns flowOf(listOf(roomFolderRow()))

        val folders = repo.observeFolders(vaultId).first()

        assertEquals(listOf(VaultFolder(id = "folder-1", name = "工作")), folders)
    }

    /** 旧契约①：Bitwarden 库未解锁 ⇒ 空列表（不泄露"这个库有几个文件夹"）。 */
    @Test
    fun `Bitwarden 库未解锁时返回空列表`() = runTest {
        every { vaultDao.observeAll() } returns flowOf(listOf(bitwardenVaultRow()))
        every { folderDao.observeByVault(vaultId) } returns flowOf(listOf(roomFolderRow()))
        // 不解锁

        assertTrue(repo.observeFolders(vaultId).first().isEmpty())
    }

    /** 旧契约②：KDBX 库未解锁 ⇒ 同样空列表（contentOf 为 null 的同口径表现）。 */
    @Test
    fun `KDBX 库未解锁时返回空列表`() = runTest {
        every { vaultDao.observeAll() } returns flowOf(listOf(kdbxVaultRow()))
        every { folderDao.observeByVault(vaultId) } returns flowOf(listOf(roomFolderRow()))
        // 不播种会话 ⇒ Kdbx.contentOf(vaultId) == null

        assertTrue(repo.observeFolders(vaultId).first().isEmpty())
    }

    // ---------------------------------------------------------------- 写路径（#199）

    /**
     * ★ 新建成功 ⇒ 服务端收到的是**密文**，本地行用的是**服务端返回的 id**。
     *
     * 用本地自造 id 的话，下一次 sync 的 `persistFolders`（按 id `upsertAll`）
     * 会多出一行 —— 同一名字的文件夹在列表里出现两次。
     */
    @Test
    fun `新建文件夹_服务端收到密文_本地用服务端 id`() = runTest {
        sessions.unlock(vaultId, key)
        coEvery { vaultDao.get(vaultId) } returns bitwardenVaultRow()
        val requestSlot = slot<FolderRequest>()
        coEvery { api.createFolder(capture(requestSlot)) } returns
            FolderDto(id = "srv-1", name = crypto.encryptString("工作", key), revisionDate = "rev-1")
        val rowSlot = slot<FolderEntity>()
        coEvery { folderDao.upsertOne(capture(rowSlot)) } returns Unit

        val result = repo.createFolder(vaultId, "  工作  ")

        assertThat(result.isSuccess).isTrue()
        // ① 上行的名字是密文，且能解回 trim 之后的原文。
        assertThat(requestSlot.captured.name).isNotEqualTo("工作")
        assertThat(crypto.decryptToString(requestSlot.captured.name, key)).isEqualTo("工作")
        // ② 本地行 id 是服务端的 id。
        assertThat(rowSlot.captured.id).isEqualTo("srv-1")
        assertThat(rowSlot.captured.vaultId).isEqualTo(vaultId)
        assertThat(rowSlot.captured.revisionDate).isEqualTo("rev-1")
    }

    /**
     * ★★ **反证**：服务端失败 ⇒ 本地**一行都不许写**。
     *
     * 没有这条，"先写本地再调远端"的实现也能让其它用例全绿 ——
     * 而这种顺序在失败时留下的是"本地有、远端没有"的幽灵行，
     * 下一次同步会被 `pruneRemovedRows` 清掉：用户刚建的文件夹凭空消失。
     */
    @Test
    fun `新建失败_本地零写入`() = runTest {
        sessions.unlock(vaultId, key)
        coEvery { vaultDao.get(vaultId) } returns bitwardenVaultRow()
        coEvery { api.createFolder(any()) } throws httpError(500)

        val result = repo.createFolder(vaultId, "工作")

        assertThat(result.isFailure).isTrue()
        coVerify(exactly = 0) { folderDao.upsertOne(any()) }
        coVerify(exactly = 0) { folderDao.deleteById(any()) }
    }

    @Test
    fun `改名_走 updateFolder 并覆盖本地密文`() = runTest {
        sessions.unlock(vaultId, key)
        coEvery { vaultDao.get(vaultId) } returns bitwardenVaultRow()
        coEvery { folderDao.get("f-1") } returns FolderEntity("f-1", vaultId, "old-cipher", "rev-0")
        coEvery { api.updateFolder("f-1", any()) } returns
            FolderDto(id = "f-1", name = "ignored", revisionDate = "rev-2")
        val rowSlot = slot<FolderEntity>()
        coEvery { folderDao.upsertOne(capture(rowSlot)) } returns Unit

        val result = repo.renameFolder(vaultId, "f-1", "归档")

        assertThat(result.isSuccess).isTrue()
        assertThat(crypto.decryptToString(rowSlot.captured.encryptedName, key)).isEqualTo("归档")
        assertThat(rowSlot.captured.revisionDate).isEqualTo("rev-2")
        // id 与库归属都不许被改掉（否则那一行会"搬家"到别的库）。
        assertThat(rowSlot.captured.id).isEqualTo("f-1")
        assertThat(rowSlot.captured.vaultId).isEqualTo(vaultId)
    }

    @Test
    fun `改名失败_本地零写入`() = runTest {
        sessions.unlock(vaultId, key)
        coEvery { vaultDao.get(vaultId) } returns bitwardenVaultRow()
        coEvery { api.updateFolder(any(), any()) } throws httpError(400)

        val result = repo.renameFolder(vaultId, "f-1", "归档")

        assertThat(result.isFailure).isTrue()
        coVerify(exactly = 0) { folderDao.upsertOne(any()) }
    }

    @Test
    fun `删除_调用服务端并删本地行`() = runTest {
        sessions.unlock(vaultId, key)
        coEvery { vaultDao.get(vaultId) } returns bitwardenVaultRow()
        coEvery { api.deleteFolder("f-1") } returns Unit
        coEvery { folderDao.deleteById("f-1") } returns Unit

        assertThat(repo.deleteFolder(vaultId, "f-1").isSuccess).isTrue()

        coVerify(exactly = 1) { folderDao.deleteById("f-1") }
    }

    /**
     * 服务端 404 当作**成功**：远端已经没有了（别的设备删过），本地继续留着才是错。
     * 若不这样处理，用户会卡在一个"删不掉的文件夹"上，直到下次同步。
     */
    @Test
    fun `删除时服务端 404 仍清本地行`() = runTest {
        sessions.unlock(vaultId, key)
        coEvery { vaultDao.get(vaultId) } returns bitwardenVaultRow()
        coEvery { api.deleteFolder("f-1") } throws httpError(404)
        coEvery { folderDao.deleteById("f-1") } returns Unit

        assertThat(repo.deleteFolder(vaultId, "f-1").isSuccess).isTrue()

        coVerify(exactly = 1) { folderDao.deleteById("f-1") }
    }

    /** 名称校验在**最前面**：名字不合法时连远端都不该碰。 */
    @Test
    fun `名称不合法时连远端都不调用`() = runTest {
        sessions.unlock(vaultId, key)
        coEvery { vaultDao.get(vaultId) } returns bitwardenVaultRow()

        val result = repo.createFolder(vaultId, "工作/服务器")

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()?.message).contains("/")
        coVerify(exactly = 0) { api.createFolder(any()) }
    }

    @Test
    fun `未解锁时拒绝写`() = runTest {
        coEvery { vaultDao.get(vaultId) } returns bitwardenVaultRow()
        // 不解锁

        assertThat(repo.createFolder(vaultId, "工作").isFailure).isTrue()
        coVerify(exactly = 0) { api.createFolder(any()) }
    }

    /** KDBX 库转交 [KdbxItemRepository]，**不**碰 Bitwarden API（两个库的写路径不能串）。 */
    @Test
    fun `KDBX 库建文件夹转交 KdbxItemRepository`() = runTest {
        coEvery { vaultDao.get(vaultId) } returns kdbxVaultRow()
        coEvery { kdbxItemWrites.createFolder(vaultId, null, "工作") } returns
            Result.success(VaultSaveOutcome.Queued)

        assertThat(repo.createFolder(vaultId, "工作").isSuccess).isTrue()

        coVerify(exactly = 1) { kdbxItemWrites.createFolder(vaultId, null, "工作") }
        coVerify(exactly = 0) { api.createFolder(any()) }
    }

    /** KDBX 写失败的信息要**原样透传**（里面写着"目标分组不存在"这类可照做的指引）。 */
    @Test
    fun `KDBX 写失败时透传原始信息`() = runTest {
        coEvery { vaultDao.get(vaultId) } returns kdbxVaultRow()
        coEvery { kdbxItemWrites.deleteFolder(vaultId, "kdbx-group:x") } returns
            Result.failure(IllegalStateException("目标条目或分组不存在，未做任何改动"))

        val result = repo.deleteFolder(vaultId, "kdbx-group:x")

        assertThat(result.exceptionOrNull()?.message).contains("未做任何改动")
    }

    // ---------------------------------------------------------------- 夹具

    /**
     * 用**公开 API** 播种一个真实的 KDBX 会话（`KdbxSessionStore` 是 internal，
     * 只能走 `Kdbx.createVault`；与 `ItemRepositoryImplTest` 的 B6 夹具同手法）。
     * 新库只有根组 + 回收站 ⇒ `folders` 为空（这一点由 data:kdbx 的测试钉死）。
     */
    private suspend fun seedKdbxSession() {
        Kdbx.createVault(vaultId, inMemorySource(), "测试库", "correct horse battery staple")
            .getOrThrow()
    }

    /** 内存文件来源；`read()` 不该被新建路径调用（调了就让用例红）。 */
    private fun inMemorySource(): KdbxFileSource = mockk {
        coEvery { stat() } returns KdbxFileStat(versionToken = "token")
        coEvery { write(bytes = any(), expectedVersion = any(), force = any(), failIfExists = any()) } returns
            KdbxFileWriteResult(versionToken = "token")
        coEvery { read() } throws AssertionError("新建路径不应读远端文件")
    }

    private fun httpError(code: Int): HttpException =
        HttpException(Response.error<Any>(code, "".toResponseBody(null)))

    private fun kdbxVaultRow() = VaultEntity(
        id = vaultId,
        kind = VaultKind.KDBX.name,
        displayName = "KDBX",
        origin = "content://com.android.externalstorage.documents/document/primary%3Avault.kdbx",
        account = null,
        createdAt = 0L,
    )

    private fun bitwardenVaultRow() = VaultEntity(
        id = vaultId,
        kind = VaultKind.BITWARDEN.name,
        displayName = "Bitwarden",
        origin = vaultId,
        account = "alice@example.com",
        createdAt = 0L,
    )

    private fun roomFolderRow() = FolderEntity(
        id = "folder-1",
        vaultId = vaultId,
        encryptedName = crypto.encryptString("工作", key),
        revisionDate = "rev",
    )
}
