/*
 * Vaultix — data:repository
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * [KdbxItemRepository] 的 **A3 类型硬闸**：KDBX 库只收 Login / SecureNote。
 *
 * ## 为什么仓储层也要拦（表单已经裁过了）
 *
 * 表单裁剪（`ui/common/ItemTypePolicy`）只覆盖「用户手点」。而导入
 * （`VaultExportRepositoryImpl.applyImportedVault → ItemRepositoryImpl.createItem → 本类`）
 * 同样汇入 #create：一份含银行卡的 Bitwarden JSON 导进 KDBX 库时会**绕开表单**，
 * 而 KDBX 的 writer 对卡字段静默丢弃 ⇒ 导入报"成功"、库里多出空壳残骸。
 *
 * ## 弱断言与反证
 *
 * 「什么都没写」是断言"没发生"——它可能因为"整条路径压根没跑"而假绿。
 * ⇒ 配了 [登录与安全笔记照常放行并落盘] 作反证：同一套装置下，放行的路径
 * 确实写了文件（`writes.size == 1`）。两条一起读才知道"没写"是真的没写。
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
     * 拒收银行卡：人话报错 + **会话与文件都不碰**。
     *
     * 两个"不碰"各钉一类故障：会话里多一条 = 残骸进去了；文件被写 = 坏数据落盘了。
     */
    @Test
    fun `KDBX 库拒收银行卡_人话报错_会话与文件都不碰`() = runTest {
        val harness = Harness()
        harness.seedLoginItem()

        val result = harness.repository.create(VAULT_ID, bankCard())

        assertTrue("应当失败，实际：$result", result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("要说清是哪类不被支持：$message", message.contains("银行卡"))
        assertTrue("要说清只支持哪两类：$message", message.contains("安全笔记"))
        // ① 会话里没有残骸（还是播种的那一条登录）
        assertEquals(1, Kdbx.contentOf(VAULT_ID)!!.items.size)
        // ② 文件一个字节都没写
        assertEquals(0, harness.source.writes.size)
    }

    /**
     * 拒收「把已有登录条目改写成银行卡」——类型漂移同样会产生残骸
     * （标题还在、卡字段全空），且会覆盖掉原来的用户名/密码。
     */
    @Test
    fun `KDBX 库拒收把登录条目改写成银行卡_原条目毫发无损`() = runTest {
        val harness = Harness()
        val loginId = harness.seedLoginItem()
        val before = Kdbx.contentOf(VAULT_ID)!!.items.single()

        val result = harness.repository.update(
            VAULT_ID,
            before.copy(type = VaultItemType.Card, card = VaultCard(number = "4111111111111111")),
        )

        assertTrue("应当失败，实际：$result", result.isFailure)
        assertTrue(
            "拒绝文案要说明白是哪种类型不支持：${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()?.message?.contains("银行卡") == true,
        )
        assertEquals(0, harness.source.writes.size)
        val after = Kdbx.contentOf(VAULT_ID)!!.items.single()
        assertEquals(loginId, after.id)
        assertEquals(VaultItemType.Login, after.type)
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
    private class Harness {
        val source = RecordingSource()
        private val vaultDao = mockk<VaultDao>()
        private val cloudSync = mockk<KdbxCloudSyncCoordinator>()
        val repository: KdbxItemRepository

        init {
            // ⚠️ stub 里必须显式写 receiver（`.ai/ISSUES.md` #146）：裸 `get(...)` 会被
            //    `MockKMatcherScope` 的同名成员遮蔽，stub 到匹配器上去。
            coEvery { vaultDao.get(VAULT_ID) } returns kdbxVaultRow()
            every { cloudSync.hasCloudSource(VAULT_ID) } returns false
            every { cloudSync.fileSourceFor(VAULT_ID) } returns source
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
