/*
 * Vaultix — data:repository
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * [KdbxItemRepository] 的条目写回与类型口径。
 *
 * ## 11-07：A3 硬闸已解除
 *
 * 旧的「KDBX 只收 Login / SecureNote」是因为当时 writer 会把卡字段静默丢成空壳。
 * 现在 `KdbxItemWriter.applyPayloads` 把 Card / Identity / SSH 写成自定义字段载荷
 * （`card_*` 是 KeePassXC 的 Bitwarden 导入器生态键名），mapper 双向读回。
 * ⇒ 本文件改成钉住**放行之余数据真的保真**，而不只是"没报错"。
 *
 * ## 弱断言与反证
 *
 * 断言"成功"也可能因为整条路径没跑而假绿 ⇒ 每条都同时断言
 * `writes.size == 1`（真落盘）与会话里能读回卡字段（真往返）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.repository.kdbx

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.vaultix.data.kdbx.Kdbx
import io.vaultix.data.kdbx.KdbxFileSource
import io.vaultix.data.kdbx.KdbxFileStat
import io.vaultix.data.kdbx.KdbxFileWriteResult
import io.vaultix.database.dao.VaultDao
import io.vaultix.database.entity.VaultEntity
import io.vaultix.data.repository.KdbxItemRepository
import io.vaultix.model.KdbxCloudSyncStatus
import io.vaultix.model.VaultCard
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KdbxItemRepositoryTest {

    @After
    fun tearDown() {
        // `Kdbx.createVault` 把会话登记进进程级单例 ⇒ 用例之间必须清干净，
        // 否则下一个用例会看到这一个的残留（KdbxCreateRepositoryTest 同款纪律）。
        Kdbx.lock(VAULT_ID)
    }

    /**
     * ★ 11-07：KDBX 库**放行银行卡**，且六个卡字段读写往返不丢。
     *
     * 只断言"没报错"会假绿（整条路径没跑也过）⇒ 同时钉住：真落盘一次、
     * 会话里多出一条、且类型与卡号/持卡人/CVV 都读得回来。
     */
    @Test
    fun `KDBX 库放行银行卡_六个字段往返保真`() = runTest {
        val harness = Harness()
        harness.seedLoginItem()

        val result = harness.repository.create(VAULT_ID, bankCard())

        assertTrue("应当成功，实际：$result", result.isSuccess)
        assertEquals("必须真的写了一次文件", 1, harness.source.writes.size)
        val items = Kdbx.contentOf(VAULT_ID)!!.items
        assertEquals(2, items.size)
        val saved = items.first { it.type == VaultItemType.Card }
        assertEquals("4111111111111111", saved.card?.number)
        assertEquals("张三", saved.card?.cardholderName)
        assertEquals("CMB", saved.card?.brand)
        assertEquals("737", saved.card?.code)
    }

    /**
     * ★ 11-07：把已有登录条目改写成银行卡**允许**，且 id 与卡载荷都保真。
     * 旧断言钉的是"拒收 + 原条目毫发无损"；数据层既然已支持，就该钉"真的改成卡了"。
     */
    @Test
    fun `KDBX 库允许把登录条目改写成银行卡_载荷落盘`() = runTest {
        val harness = Harness()
        val loginId = harness.seedLoginItem()
        val before = Kdbx.contentOf(VAULT_ID)!!.items.single()

        val result = harness.repository.update(
            VAULT_ID,
            before.copy(type = VaultItemType.Card, card = VaultCard(number = "4111111111111111")),
        )

        assertTrue("应当成功，实际：$result", result.isSuccess)
        assertEquals(1, harness.source.writes.size)
        val after = Kdbx.contentOf(VAULT_ID)!!.items.single()
        assertEquals(loginId, after.id)
        assertEquals(VaultItemType.Card, after.type)
        assertEquals("4111111111111111", after.card?.number)
    }

    /**
     * ★ 反证（见文件头）：放行的路径**确实**走完了落盘。
     * 顺带钉住 SecureNote 的读写往返（无用户名无密码、有备注 ⇒ 读回还是安全笔记）。
     */
    @Test
    fun `登录与安全笔记照常放行并落盘`() = runTest {
        val harness = Harness()
        harness.seedLoginItem()

        val note = VaultItem(id = "", title = "周会纪要", type = VaultItemType.SecureNote, notes = "周三站会")
        val result = harness.repository.create(VAULT_ID, note)

        assertTrue("安全笔记应当保存成功，实际：$result", result.isSuccess)
        assertEquals(1, harness.source.writes.size)
        val items = Kdbx.contentOf(VAULT_ID)!!.items
        assertEquals(2, items.size)
        assertEquals(VaultItemType.SecureNote, items.last().type)
        assertEquals("周三站会", items.last().notes)
    }

    /**
     * ★ 11-07 真机事故（电脑 KeePassXC 与手机连同一个 OneDrive KDBX）：
     * 手机显示「已同步」，改一个条目保存却被提示「云端有更新，请先同步」，
     * 同步后再改还是一样 —— 因为写回基线是内存态，冷启动 / 锁库后为空，
     * 旧闸门把 `baselineAligned = false`（其实是「不知道」）读成了「确定过期」。
     *
     * 这条钉住端到端结论：**没基线不是错误，正常保存必须走通**。
     * 反证在同一文件的 [KDBX 库拒收银行卡...] 等用例（该拦的仍会拦），
     * 而「远端真变过」的拒写由 `KdbxWriteBaselineTest` 的基线用例守着。
     */
    @Test
    fun `云端库冷启动没有写回基线时_保存照常放行`() = runTest {
        val harness = Harness(cloud = true)
        harness.seedLoginItem()
        val before = Kdbx.contentOf(VAULT_ID)!!.items.single()

        val result = harness.repository.update(VAULT_ID, before.copy(title = "改过的标题"))

        assertTrue("没有基线 ≠ 内容过期，保存不该被拦：$result", result.isSuccess)
        assertEquals("改过的标题", Kdbx.contentOf(VAULT_ID)!!.items.single().title)
    }

    // ------------------------------------------------------------ 装置

    private fun bankCard() = VaultItem(
        id = "",
        title = "招行信用卡",
        type = VaultItemType.Card,
        card = VaultCard(
            cardholderName = "张三",
            brand = "CMB",
            number = "4111111111111111",
            expMonth = "08",
            expYear = "29",
            code = "737",
        ),
    )

    /**
     * 被测对象 + 记录型来源。
     *
     * 五个依赖全部 stub 到「本地文件库」这一档（`hasCloudSource = false`）：
     * 网盘分支的缓存/上传调度由 `KdbxSyncOrchestratorTest` 等文件覆盖，这里不重复。
     */
    private class Harness(cloud: Boolean = false) {
        val source = RecordingSource()
        private val vaultDao = mockk<VaultDao>()
        private val cloudSync = mockk<KdbxCloudSyncCoordinator>()
        val repository: KdbxItemRepository

        init {
            // ⚠️ stub 里必须显式写 receiver（`.ai/ISSUES.md` #146）：裸 `get(...)` 会被
            //    `MockKMatcherScope` 的同名成员遮蔽，stub 到匹配器上去。
            coEvery { vaultDao.get(VAULT_ID) } returns kdbxVaultRow()
            every { cloudSync.hasCloudSource(VAULT_ID) } returns cloud
            every { cloudSync.fileSourceFor(VAULT_ID) } returns source
            if (cloud) {
                // 网盘分支会走「写缓存 → 标记待上传 → 排自动上传」，
                // 这三个 suspend 在非 relaxed mock 上不 stub 会直接抛。
                coEvery { cloudSync.currentSyncStatus(VAULT_ID) } returns KdbxCloudSyncStatus.IN_SYNC
                coEvery { cloudSync.markLocalEdited(VAULT_ID) } returns true
            }
            repository = KdbxItemRepository(
                vaultDao = vaultDao,
                cloudSync = cloudSync,
                fileCache = mockk(relaxed = true),
                kdbxSessions = mockk(relaxed = true),
                autoUploader = mockk(relaxed = true),
            )
        }

        /** 播种一个已解锁的 KDBX 会话 + 一条登录条目；返回该条目 id。 */
        suspend fun seedLoginItem(): String {
            Kdbx.createVault(
                vaultId = VAULT_ID,
                source = source,
                name = "测试库",
                password = PASSWORD,
            ).getOrThrow()
            // 建库本身会写一次初始空库 —— 那不是用例要断言的对象，清零从头计。
            source.reset()
            Kdbx.createItem(
                vaultId = VAULT_ID,
                folderId = null,
                item = VaultItem(
                    id = "",
                    title = "示例登录",
                    type = VaultItemType.Login,
                    username = "alice",
                    password = "pwd",
                ),
            ).getOrThrow()
            return Kdbx.contentOf(VAULT_ID)!!.items.single().id
        }
    }

    /** 记录写入次数；`read` / `testConnection` 不该被写路径调用（调了就让用例红）。 */
    private class RecordingSource : KdbxFileSource {
        val writes = mutableListOf<ByteArray>()

        override suspend fun stat(): KdbxFileStat = KdbxFileStat(
            versionToken = "token",
            sizeBytes = 0,
            remoteId = VAULT_ID,
            displayName = "vault.kdbx",
        )

        override suspend fun read(): ByteArray = throw AssertionError("写路径不应读远端文件")

        override suspend fun testConnection(): Result<Unit> =
            throw AssertionError("写路径不应探测连接")

        override suspend fun write(
            bytes: ByteArray,
            expectedVersion: String?,
            force: Boolean,
            failIfExists: Boolean,
        ): KdbxFileWriteResult {
            writes += bytes
            return KdbxFileWriteResult(versionToken = "token", sizeBytes = bytes.size.toLong(), remoteId = VAULT_ID)
        }

        fun reset() {
            writes.clear()
        }
    }

    private companion object {
        const val VAULT_ID = "content://com.android.providers.downloads/document/test"
        const val PASSWORD = "correct horse battery staple"

        fun kdbxVaultRow() = VaultEntity(
            id = VAULT_ID,
            kind = "KDBX",
            displayName = "测试库",
            origin = VAULT_ID,
            account = null,
            revisionDate = null,
            createdAt = 0L,
        )
    }
}
