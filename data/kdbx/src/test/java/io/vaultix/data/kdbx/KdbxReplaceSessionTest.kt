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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

/**
 * [Kdbx.replaceSession] —— **同步拉取的落点**（2026-10-01）。
 *
 * ## 为什么这一层值得单独钉住
 *
 * 它处在「拉字节 → 替换会话 → 才记状态」这条链的正中间，而此前**中间那一步是空的**
 * （app 侧注入的实现恒定返回失败）⇒ 「用远端覆盖本地」与「拉取远端更新」两条路都是死路。
 * 补上之后，要防的是三个方向上的失败，每个方向都有一条用例 + 一条**反证**：
 *
 * | 局面 | 必须发生 | 绝不能发生（反证） |
 * |---|---|---|
 * | 正常替换 | 会话变成远端那份 | —— |
 * | 库没解锁 | 失败 | **顺手开一个空库**（用户看到库空了，下次保存把真文件覆盖成空库） |
 * | 远端换了密码 | 失败 | **动到会话**（本地这份是唯一可信的内容） |
 * | 远端不是 KDBX | 失败 | **动到会话** |
 *
 * ## 关于"复用会话凭据是安全的"这条论断
 *
 * `KdbxOpener.reopen` 的 KDoc 声称：同一个 kotpass `Credentials` 对象可以反复用于
 * decode。这不是想当然 —— 用例「替换之后还能继续写回」直接把它钉住了：
 * 写回要走 `KdbxEncoder.encode`，而 encode 用的正是**同一组**凭据。
 * 若复用不安全，那条用例会失败。
 *
 * ## 为什么用 `runBlocking` 而不是 `runTest`
 *
 * 与 `KdbxMutationTest` 同款取舍：本模块没有 `kotlinx-coroutines-test` 依赖，
 * 而这里验的是"会话换没换对"，不涉及虚拟时间。
 */
class KdbxReplaceSessionTest {

    @After
    fun tearDown() {
        // ⚠️ 会话是**进程级单例**：不清理会污染同模块的其它用例。
        KdbxSessionStore.close(VAULT_ID)
    }

    @Test
    fun `远端字节替换会话后，读到的就是远端那份`() = runBlocking {
        registerLocal(title = "本地的")
        assertThat(Kdbx.contentOf(VAULT_ID)?.items?.single()?.title).isEqualTo("本地的")

        val result = Kdbx.replaceSession(VAULT_ID, bytesWith(title = "远端的"))

        assertThat(result.isSuccess).isTrue()
        // ① 会话真的换了 —— 这正是此前缺失的那一步。
        assertThat(Kdbx.contentOf(VAULT_ID)?.items?.single()?.title).isEqualTo("远端的")
    }

    /**
     * ★ 这条是「复用凭据安全」的正证。
     *
     * 替换之后紧跟一次写回：写回要 `KdbxEncoder.encode`，而它用的是**会话里那一组凭据**。
     * 若 `reopen` 把凭据弄坏了（比如 `EncryptedValue` 被就地 XOR 改写过），
     * 这里会失败或写出一个**用错密钥加密、用户再也打不开**的文件。
     */
    @Test
    fun `替换之后还能继续写回，凭据没有被复用弄坏`() = runBlocking {
        registerLocal(title = "本地的")
        Kdbx.replaceSession(VAULT_ID, bytesWith(title = "远端的")).getOrThrow()

        val write = Kdbx.createItem(VAULT_ID, folderId = null, item = newItem("新条目"))

        assertThat(write.isSuccess).isTrue()
        // 往返自检已经跑过（在 `Kdbx.saveVia` 里）；这里再验"编码出来的字节真的打得开"。
        val reopened = KdbxOpener.open(
            bytes = write.getOrThrow().bytes,
            password = PASSWORD,
            keyFileBytes = null,
        ).getOrThrow()
        assertThat(reopened.content.items.map { it.title }).contains("新条目")
    }

    @Test
    fun `未解锁时失败，且绝不凭空建会话`() = runBlocking {
        KdbxSessionStore.close(VAULT_ID)

        val result = Kdbx.replaceSession(VAULT_ID, bytesWith(title = "远端的"))

        assertThat(result.isFailure).isTrue()
        assertThat((result.exceptionOrNull() as? KdbxFailure)?.error)
            .isEqualTo(KdbxOpenError.NotUnlocked)
        // ★ 反证：绝不能"顺手开一个空库"顶上。
        assertThat(Kdbx.isUnlocked(VAULT_ID)).isFalse()
    }

    @Test
    fun `远端换了密码时失败，且本地会话一个字节都不动`() = runBlocking {
        registerLocal(title = "本地的")
        val remoteWithOtherPassword = bytesWith(title = "远端的", password = "另一台设备改过的密码")

        val result = Kdbx.replaceSession(VAULT_ID, remoteWithOtherPassword)

        assertThat(result.isFailure).isTrue()
        // ★ 必须归到"远端换了凭据"，而不是笼统的"密码不正确" ——
        //   后者会让用户去怀疑一个他根本没输过、而且本来是对的密码。
        assertThat((result.exceptionOrNull() as? KdbxFailure)?.error)
            .isEqualTo(KdbxOpenError.RemoteCredentialsMismatch)
        // ★ 反证（本用例的重点）：解码失败**绝不能**动会话。
        assertThat(Kdbx.contentOf(VAULT_ID)?.items?.single()?.title).isEqualTo("本地的")
    }

    @Test
    fun `远端不是 KDBX 文件时失败，且本地会话一个字节都不动`() = runBlocking {
        registerLocal(title = "本地的")

        val result = Kdbx.replaceSession(VAULT_ID, "这显然不是 kdbx".toByteArray())

        assertThat(result.isFailure).isTrue()
        assertThat((result.exceptionOrNull() as? KdbxFailure)?.error)
            .isEqualTo(KdbxOpenError.NotKdbxFile)
        // ★ 反证：同上，会话必须是原封不动的那一份。
        assertThat(Kdbx.contentOf(VAULT_ID)?.items?.single()?.title).isEqualTo("本地的")
    }

    // ---------------------------------------------------------------- helpers

    /** 按真实路径建会话：字节 → `KdbxOpener.open` → 放进会话存储。 */
    private fun registerLocal(title: String) {
        val bytes = bytesWith(title)
        KdbxSessionStore.put(
            VAULT_ID,
            KdbxOpener.open(bytes = bytes, password = PASSWORD, keyFileBytes = null).getOrThrow(),
        )
    }

    /** 造一份**真实可解码**的 KDBX 字节（内容与标题不同即视为不同版本）。 */
    private fun bytesWith(title: String, password: String = PASSWORD): ByteArray {
        val credentials = Credentials.from(EncryptedValue.fromString(password))
        val database = KeePassDatabase.Ver4x.create(
            rootName = "Root",
            meta = Meta(name = "ReplaceSession"),
            credentials = credentials,
        )
        val entry = Entry(
            uuid = java.util.UUID.randomUUID(),
            fields = EntryFields(
                linkedMapOf<String, EntryValue>(BasicField.Title.key to EntryValue.Plain(title)),
            ),
        )
        return KdbxEncoder.encode(
            database.copy(
                content = database.content.copy(
                    group = database.content.group.copy(entries = listOf(entry)),
                ),
            ),
        )
    }

    private fun newItem(title: String) = io.vaultix.model.VaultItem(
        id = "item-$title",
        title = title,
        type = io.vaultix.model.VaultItemType.Login,
    )

    private companion object {
        const val VAULT_ID = "kdbx-vault-replace-session-test"
        const val PASSWORD = "master-pw"
    }
}
