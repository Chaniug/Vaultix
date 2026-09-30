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

    // ---------------------------------------------------------------- helpers

    private fun registerSession() {
        val database = databaseWithOneEntry()
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

    private fun databaseWithOneEntry(): KeePassDatabase {
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
        return created.copy(
            content = created.content.copy(
                group = created.content.group.copy(entries = listOf(entry)),
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
        val CREDENTIALS: Credentials = Credentials.from(EncryptedValue.fromString(PASSWORD))
    }
}
