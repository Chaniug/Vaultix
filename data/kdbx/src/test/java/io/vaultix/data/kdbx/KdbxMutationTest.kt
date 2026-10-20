/*
 * Vaultix — data:kdbx（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.data.kdbx

import app.keemobile.kotpass.constants.BasicField
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Meta
import com.google.common.truth.Truth.assertThat
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

/**
 * [Kdbx] 的**内存事务**语义（阶段 B · W2 的数据层入口）。
 *
 * ## 为什么这一层需要单独的测试
 *
 * 写路径失败时**不能留半成品**，而"半成品"在这里有三种表现，每种都要一条用例：
 *
 * 1. 库没解锁 ⇒ 若"顺手新建一个库"或"先解锁再写"，用户会拿一份**不是他的库**去覆盖真文件；
 * 2. 目标条目不存在 ⇒ 上游 `modifyEntry` **原样返回旧库、不抛异常**，
 *    若按成功对待，上层会落盘一份**内容未变**的文件，而用户看到"保存成功"；
 * 3. 改完**没把会话换掉** ⇒ 后续读到的还是旧库（界面看起来"改了没反应"）。
 *
 * ## 为什么用 `runBlocking` 而不是 `runTest`
 *
 * 本模块没有 `kotlinx-coroutines-test` 依赖（同 `KdbxFileSourceReadTest` 的取舍）。
 * 这里要验的是"事务做没做对"，不涉及虚拟时间，`runBlocking` 足够。
 */
class KdbxMutationTest {

    @After
    fun tearDown() {
        // ⚠️ 会话是**进程级单例**：不清理会污染同模块的其它用例（它们会读到一个不该存在的会话）。
        KdbxSessionStore.close(VAULT_ID)
    }

    @Test
    fun `未解锁时拒绝写，且不留下任何会话`() = runBlocking {
        KdbxSessionStore.close(VAULT_ID)

        val result = Kdbx.createItem(VAULT_ID, folderId = null, item = item(title = "X"))

        assertThat(result.isFailure).isTrue()
        val kind = (result.exceptionOrNull() as? KdbxFailure)?.error
        assertThat(kind).isEqualTo(KdbxOpenError.NotUnlocked)
        // ★ 绝不能"顺手建一个空库"：那会让后续落盘把用户的真文件覆盖成空库。
        assertThat(Kdbx.isUnlocked(VAULT_ID)).isFalse()
    }

    @Test
    fun `目标条目不存在时返回失败，且会话内容不变`() = runBlocking {
        registerSession()
        val ghost = item(title = "不存在", uuid = UUID.randomUUID())

        val result = Kdbx.updateItem(VAULT_ID, before = ghost, after = ghost.copy(title = "改了"))

        // ★ 上游 `modifyEntry` 找不到目标时**原样返回旧库**（不抛异常）——
        //   不显式判 applied 的话这里会"成功"，上层随即落盘一份内容未变的文件。
        assertThat(result.isFailure).isTrue()
        assertThat(Kdbx.contentOf(VAULT_ID)?.items).hasSize(1)
        assertThat(Kdbx.contentOf(VAULT_ID)?.items?.single()?.title).isEqualTo("原始")
    }

    @Test
    fun `写回成功后会话被换新，且返回的字节能被重新打开并带着改动`() = runBlocking {
        registerSession()
        val before = Kdbx.contentOf(VAULT_ID)!!.items.single()

        val result = Kdbx.updateItem(VAULT_ID, before = before, after = before.copy(title = "改过了"))
        val write = result.getOrThrow()

        // ① 会话换成了新的那一份（不换的话：界面看起来"改了没反应"、下次再改又回到老值）。
        assertThat(Kdbx.contentOf(VAULT_ID)?.items?.single()?.title).isEqualTo("改过了")
        // ② 返回的字节是一份**真实可用**的库（走格式识别 + 解密，不是只信内存里那份）。
        val reopened = KdbxOpener.open(write.bytes, PASSWORD, keyFileBytes = null).getOrThrow()
        assertThat(reopened.content.items.single().title).isEqualTo("改过了")
        // ③ 重新打开后版本仍是 4.1（写回不能把库的格式降级）。
        assertThat(inspectKdbxFormat(write.bytes)).isEqualTo(KdbxFormat.Supported("4.1", 4))
    }

    /**
     * **并发两次写不同条目，两笔改动都必须留下**（2026-10-20）。
     *
     * ## 这条钉的是 `mutate` 的读-改-写窗口
     *
     * `KdbxSessionStore` 用的是 `ConcurrentHashMap` —— 它只保证"map 本身的操作"安全，
     * **不保证"读出来 → 改 → 放回去"这个序列原子**。而 `mutate` 正是这个形状：
     *
     * ```
     * ① val session = KdbxSessionStore.get(vaultId)     // 拿到库 A
     * ② mutator(session.database)                       // 在 A 上加一笔
     * ③ KdbxSessionStore.put(vaultId, …)                // 放回 A'
     * ```
     *
     * 两条写并发（A 线程改条目甲、B 线程改条目乙）会交错成：
     * `A.get → B.get → A.put(含甲的 A') → B.put(含乙、**不含甲**的 B')`
     * ⇒ **甲那一笔改动被静默丢掉**，而两边都返回"成功"。
     *
     * ## 为什么这个并发在本项目里是**真会**发生的
     *
     * 写动作在 `withContext(Dispatchers.IO)` 里跑（`KdbxItemRepository` 五个入口），
     * 而自动上传器 `KdbxAutoUploader` 自带 `Dispatchers.IO` 的 scope；上传里的
     * `saveVia` 也读同一个 `session.database` 去序列化。⇒ 上传与编辑本来就不同线程。
     *
     * ⚠️ 断言必须看**两笔都在**：只看"两次都返回成功"会绿（丢改动的那次也返回成功）。
     */
    @Test
    fun `并发写两条不同条目_两笔改动都不能丢`(): Unit = runBlocking {
        registerSession(secondEntryToo = true)
        val items = Kdbx.contentOf(VAULT_ID)!!.items
        val first = items.first { it.title == "原始" }
        val second = items.first { it.title == "原始2" }

        // ⚠️ 用**线程**而不是协程：`runBlocking` 是单线程事件循环，两个 `launch` 会顺序执行，
        //    根本撞不进同一个窗口（那样这条用例会永远绿，等于没测）。
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val failures = ConcurrentLinkedQueue<Throwable>()

        fun writer(before: io.vaultix.model.VaultItem, newTitle: String) = Thread {
            start.await()
            try {
                // 每个线程自己跑一段真实的 suspend 调用（内部要拿 session 再放回）。
                runBlocking { Kdbx.updateItem(VAULT_ID, before = before, after = before.copy(title = newTitle)) }
                    .onFailure { failures += it }
            } catch (t: Throwable) {
                failures += t
            } finally {
                done.countDown()
            }
        }.apply { isDaemon = true; start() }

        writer(first, "改甲")
        writer(second, "改乙")
        start.countDown()
        done.await(10, TimeUnit.SECONDS)

        assertThat(failures).isEmpty()
        val titles = Kdbx.contentOf(VAULT_ID)!!.items.map { it.title }.toSet()
        assertThat(titles).containsAtLeast("改甲", "改乙")
    }

    /**
     * **锁库必须把写锁一并丢掉**（2026-10-20）。
     *
     * ## 这条钉的是 `writeLocks` 只增不减
     *
     * 每库一把 `Mutex` 存在 `ConcurrentHashMap<String, Mutex>` 里，`getOrPut` 只增不减。
     * 删库 / 退出登录都会 `Kdbx.lock(vaultId)`，而那条路径此前**只** `close` 会话，
     * map 里的 `vaultId` 永远留着 ⇒ 一个"已经锁掉、甚至删掉"的库还握着一把活锁。
     *
     * ## 为什么这条必须配反证
     *
     * 断言"清干净了"（`== 0`）有假绿风险：**如果锁压根没被创建过**，计数天然是 0，
     * 看起来"修好了"。⇒ 同一条用例里先做一次写、确认 `> 0`（装置能观测到"有"），
     * 再 `lock` 并确认回到 0（装置能观测到"没有"）。
     */
    @Test
    fun `锁库后写锁一并释放_不留孤儿锁`() = runBlocking {
        Kdbx.lock(VAULT_ID)
        registerSession()
        val before = Kdbx.contentOf(VAULT_ID)!!.items.single()
        Kdbx.updateItem(VAULT_ID, before = before, after = before.copy(title = "占住锁")).getOrThrow()

        // ① 反证：锁真的被创建了（否则下面的 0 毫无意义）。
        assertThat(Kdbx.writeLockCountForTest()).isGreaterThan(0)

        Kdbx.lock(VAULT_ID)

        // ② 锁掉之后一条都不许剩。
        assertThat(Kdbx.writeLockCountForTest()).isEqualTo(0)
        assertThat(KdbxSessionStore.get(VAULT_ID)).isNull()
    }

    // ---------------------------------------------------------------- helpers

    private fun registerSession(secondEntryToo: Boolean = false) {
        val database = databaseWithOneEntry(secondEntryToo)
        KdbxSessionStore.put(
            VAULT_ID,
            KdbxSession(
                database = database,
                content = database.toMappedContent(),
                credentialLabel = "test",
                credentials = CREDENTIALS,
            ),
        )
    }

    private fun databaseWithOneEntry(secondEntryToo: Boolean = false): KeePassDatabase {
        val created = KeePassDatabase.Ver4x.create(
            rootName = "Root",
            meta = Meta(name = "Mutation"),
            credentials = CREDENTIALS,
        )
        val entry = Entry(
            uuid = ENTRY_UUID,
            fields = EntryFields(
                linkedMapOf<String, EntryValue>(
                    BasicField.Title.key to EntryValue.Plain("原始"),
                ),
            ),
        )
        val entries = if (secondEntryToo) {
            listOf(
                entry,
                Entry(
                    uuid = SECOND_ENTRY_UUID,
                    fields = EntryFields(
                        linkedMapOf<String, EntryValue>(
                            BasicField.Title.key to EntryValue.Plain("原始2"),
                        ),
                    ),
                ),
            )
        } else {
            listOf(entry)
        }
        return created.copy(
            content = created.content.copy(
                group = created.content.group.copy(entries = entries),
            ),
        )
    }

    /**
     * ⚠️ `id` 必须能指定：早先这里写死了真实条目的 uuid，于是「不存在的条目」那条用例
     * 其实指向的是**存在的**那一条 —— 结果它当然"写成功了"，而我一开始还以为是实现的问题。
     */
    private fun item(title: String, uuid: UUID = ENTRY_UUID) = io.vaultix.model.VaultItem(
        id = itemIdOf(uuid),
        title = title,
        type = io.vaultix.model.VaultItemType.Login,
    )

    private companion object {
        const val VAULT_ID = "kdbx-vault-mutation-test"
        const val PASSWORD = "master-pw"
        val ENTRY_UUID: UUID = UUID.fromString("cccccccc-dddd-eeee-ffff-000000000000")
        val SECOND_ENTRY_UUID: UUID = UUID.fromString("cccccccc-dddd-eeee-ffff-111111111111")
        val CREDENTIALS: Credentials = Credentials.from(EncryptedValue.fromString(PASSWORD))
    }
}
