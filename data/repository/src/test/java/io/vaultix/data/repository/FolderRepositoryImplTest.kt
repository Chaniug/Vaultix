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
import io.mockk.every
import io.mockk.mockk
import io.vaultix.crypto.SymmetricCryptoKey
import io.vaultix.crypto.VaultixCrypto
import io.vaultix.data.kdbx.Kdbx
import io.vaultix.data.kdbx.KdbxFileSource
import io.vaultix.data.kdbx.KdbxFileStat
import io.vaultix.data.kdbx.KdbxFileWriteResult
import io.vaultix.database.dao.FolderDao
import io.vaultix.database.dao.VaultDao
import io.vaultix.database.entity.FolderEntity
import io.vaultix.database.entity.VaultEntity
import io.vaultix.model.VaultFolder
import io.vaultix.model.VaultKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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
        repo = FolderRepositoryImpl(
            folderDao = folderDao,
            vaultDao = vaultDao,
            sessions = sessions,
            kdbxSessions = kdbxSessions,
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
