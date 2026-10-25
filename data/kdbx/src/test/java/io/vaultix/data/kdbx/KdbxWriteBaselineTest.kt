/*
 * Vaultix — data:kdbx（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
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
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test

/**
 * **写回基线**（`.ai/ISSUES.md` #185）。
 *
 * ## 这一层要证明的是"令牌对得上 ≠ 内容来源对得上"
 *
 * 版本令牌（ETag）回答的是「远端动过没」，回答不了「我手里这份是不是它」。
 * 主写路径 `saveVia(expectedVersion = 缓存里的旧令牌)` 天生不满足"内容与令牌配套"
 * 这一前提 ⇒ 远端变过而我们没拉时，服务端会**放行**写入 ⇒ 基于旧内容的一次编辑
 * 把远端的新改动静默盖掉。本文件逐条钉住那道补上的防线。
 *
 * ## 每条用例对应一个具体的失效场景
 *
 * | 用例 | 若没有这道防线会发生什么 |
 * |---|---|
 * | 远端变过没拉就保存 | 静默覆盖远端（本文件最贵的一条） |
 * | 远端只是重新编码 | 恒误判"内容过期"（假冲突，用户无法保存） |
 * | 解锁后不再登记基线 | 所有保存都被拦（防线自己变成 bug） |
 * | 解锁后第一次编辑 | 因"没基线"被硬拦，正常用户走不通 |
 * | 锁库/内容对账 | 基线该跟着会话走，不能留下"上一世"的判定 |
 * | 强制覆盖 | 用户点了"用本机覆盖"，拦下等于按钮失效 |
 *
 * ## 为什么用 `runBlocking` 而不是 `runTest`
 *
 * 与 `KdbxMutationTest` 同一取舍：本模块没有 `kotlinx-coroutines-test` 依赖，
 * 而这里要验的也不是虚拟时间。
 */
class KdbxWriteBaselineTest {

    @After
    fun tearDown() {
        // ⚠️ 会话与基线都是**进程级单例**：不清理会污染同模块其它用例。
        KdbxSessionStore.close(VAULT_ID)
        KdbxBaseStore.clearAll()
    }

    // ---------------------------------------------------- 防线本身：拦截

    @Test
    fun `远端变过而没拉时，保存被拦下且一个字节都没写出去`() = runBlocking {
        // 远端 v2（另一台设备改的）—— 本机从没见过它。
        registerSession(entryTitle = "本机 v1")
        // 基线登记成"远端 v2 的内容"：本机会话不是它 ⇒ 正是"远端变过而我们没拉"。
        KdbxBaseStore.record(VAULT_ID, mappedContentOf(databaseWith(entryTitle = "远端 v2")))

        val source = RecordingSource(versionToken = "v1")
        val write = Kdbx.updateItem(
            VAULT_ID,
            before = item(title = "本机 v1"),
            after = item(title = "本机改过"),
        ).getOrThrow()

        val result = Kdbx.saveVia(
            VAULT_ID,
            source,
            encodedBytes = write.bytes,
            baselineAligned = write.baselineAligned,
        )

        assertThat(write.baselineAligned).isFalse()
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(KdbxWriteFailure.StaleBaseline::class.java)
        // ★ 核心断言：**网络与文件一个字都没动**（不是"失败了但已经写了一半"）。
        assertThat(source.writes).isEmpty()
        assertThat(source.statCalls).isEqualTo(0)
    }

    @Test
    fun `内容与基线一致时正常放行`() = runBlocking {
        registerSession(entryTitle = "本机 v1")
        KdbxBaseStore.record(VAULT_ID, mappedContentOf(databaseWith(entryTitle = "本机 v1")))

        val source = RecordingSource(versionToken = "v1")
        val write = Kdbx.updateItem(
            VAULT_ID,
            before = item(title = "本机 v1"),
            after = item(title = "本机改过"),
        ).getOrThrow()

        val result = Kdbx.saveVia(
            VAULT_ID,
            source,
            encodedBytes = write.bytes,
            expectedVersion = "v1",
            baselineAligned = write.baselineAligned,
        )

        assertThat(write.baselineAligned).isTrue()
        assertThat(result.isSuccess).isTrue()
        assertThat(source.writes).hasSize(1)
        assertThat(source.writes.single().expectedVersion).isEqualTo("v1")
    }

    @Test
    fun `强制覆盖绕过基线闸门 —— 那正是用户在冲突框里的明确选择`() = runBlocking {
        registerSession(entryTitle = "本机 v1")
        KdbxBaseStore.record(VAULT_ID, mappedContentOf(databaseWith(entryTitle = "远端 v2")))

        val source = RecordingSource(versionToken = "v2")
        val write = Kdbx.updateItem(
            VAULT_ID,
            before = item(title = "本机 v1"),
            after = item(title = "本机改过"),
        ).getOrThrow()

        val result = Kdbx.saveVia(VAULT_ID, source, force = true, encodedBytes = write.bytes)

        assertThat(result.isSuccess).isTrue()
        assertThat(source.writes).hasSize(1)
        assertThat(source.writes.single().force).isTrue()
    }

    // ---------------------------------------------------- 指纹本身：不能引入假冲突

    @Test
    fun `同一份内容编两次，指纹不变 —— 拿编码字节做哈希会全体误判`() = runBlocking {
        // ★ 这条防的是**实现走偏**：`KdbxEncoder.encode` 内部每次重派生内容密钥并
        //   重生成 IV/随机流 ⇒ 同一份库编两次字节必然不同。若指纹取自"编码后的字节"，
        //   所有正常保存都会被判成"内容过期"（防线自己制造全量假冲突）。
        val content = mappedContentOf(databaseWith(entryTitle = "同一份"))
        val first = KdbxEncoder.contentFingerprint(content)
        val second = KdbxEncoder.contentFingerprint(mappedContentOf(databaseWith(entryTitle = "同一份")))

        assertThat(first).isEqualTo(second)

        // 反向对照：证明"字节确实不稳定"，否则上面那条断言毫无信息量。
        val bytesA = KdbxEncoder.encode(databaseWith(entryTitle = "同一份"))
        val bytesB = KdbxEncoder.encode(databaseWith(entryTitle = "同一份"))
        assertThat(bytesA).isNotEqualTo(bytesB)
    }

    @Test
    fun `内容真的变了，指纹必须变`() {
        val before = KdbxEncoder.contentFingerprint(mappedContentOf(databaseWith(entryTitle = "原始")))
        val after = KdbxEncoder.contentFingerprint(mappedContentOf(databaseWith(entryTitle = "改过")))

        assertThat(after).isNotEqualTo(before)
    }

    @Test
    fun `回收站里的条目也算内容 —— 删一条不能看起来像没变`() {
        val living = KdbxEncoder.contentFingerprint(mappedContentOf(databaseWith(entryTitle = "原始")))
        val trashed = KdbxEncoder.contentFingerprint(
            mappedContentOf(databaseWith(entryTitle = "原始")).let { content ->
                content.copy(
                    items = emptyList(),
                    trashItems = content.items.map { KdbxTrashItem(item = it, lastModifiedAtMillis = null) },
                    recycleBinCount = content.items.size,
                )
            },
        )

        assertThat(trashed).isNotEqualTo(living)
    }

    // ---------------------------------------------------- 基线怎么建立、怎么推进

    @Test
    fun `解锁后没有基线时放行 —— 交给版本令牌那层兜底`() = runBlocking {
        // ★ 语义选择：信息不足时**不许**制造假冲突。真实场景是冷启动刚解锁，
        //   那时我们确实不知道基线；硬拦会让用户"保存不了"且没有出路。
        registerSession(entryTitle = "本机 v1")
        KdbxBaseStore.clear(VAULT_ID)

        val source = RecordingSource(versionToken = "v1")
        val write = Kdbx.updateItem(
            VAULT_ID,
            before = item(title = "本机 v1"),
            after = item(title = "本机改过"),
        ).getOrThrow()

        // ★ `mutateLocked` 的判据是"基线记录存在且等于改动前指纹"⇒ 没记录时 `false`，
        //   所以这里**必须**由上层显式表态 `true`（"我确实不知道基线，交给令牌兜底"）。
        //   这条用例钉住的正是那个语义：**没记录 ≠ 有问题**。
        assertThat(write.baselineAligned).isFalse()
        val result = Kdbx.saveVia(
            VAULT_ID,
            source,
            encodedBytes = write.bytes,
            expectedVersion = "v1",
            baselineAligned = null,
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(source.writes).hasSize(1)
    }

    @Test
    fun `锁库会丢掉基线`() = runBlocking {
        registerSession(entryTitle = "本机 v1")
        KdbxBaseStore.record(VAULT_ID, mappedContentOf(databaseWith(entryTitle = "本机 v1")))
        assertThat(KdbxBaseStore.current(VAULT_ID)).isNotNull()

        Kdbx.lock(VAULT_ID)

        assertThat(KdbxBaseStore.current(VAULT_ID)).isNull()
    }

    @Test
    fun `内容对账：远端与本机一模一样时采纳基线并放行后续保存`() = runBlocking {
        // 远端"变了令牌但没变内容"（另一台设备只是重新编码了一次）。
        registerSession(entryTitle = "同一份")
        KdbxBaseStore.clear(VAULT_ID)
        val remoteBytes = KdbxEncoder.encode(databaseWith(entryTitle = "同一份"))

        val adopted = Kdbx.adoptBaselineIfMatches(VAULT_ID, remoteBytes)

        assertThat(adopted).isTrue()
        assertThat(KdbxBaseStore.current(VAULT_ID)).isEqualTo(
            KdbxEncoder.contentFingerprint(mappedContentOf(databaseWith(entryTitle = "同一份"))),
        )
    }

    @Test
    fun `内容对账：远端内容不同时不采纳，且不动已有基线`() = runBlocking {
        registerSession(entryTitle = "本机 v1")
        val existing = KdbxEncoder.contentFingerprint(mappedContentOf(databaseWith(entryTitle = "本机 v1")))
        KdbxBaseStore.recordFingerprint(VAULT_ID, existing)
        val remoteBytes = KdbxEncoder.encode(databaseWith(entryTitle = "远端 v2"))

        val adopted = Kdbx.adoptBaselineIfMatches(VAULT_ID, remoteBytes)

        assertThat(adopted).isFalse()
        // ★ 关键：不能因为"对不上"就把基线清掉 —— 清掉等于把这道防线整个关掉。
        assertThat(KdbxBaseStore.current(VAULT_ID)).isEqualTo(existing)
    }

    @Test
    fun `内容对账：远端解不开时返回 false 且不动基线`() = runBlocking {
        registerSession(entryTitle = "本机 v1")
        val existing = KdbxEncoder.contentFingerprint(mappedContentOf(databaseWith(entryTitle = "本机 v1")))
        KdbxBaseStore.recordFingerprint(VAULT_ID, existing)
        // 远端换了主密码 ⇒ 用本机凭据解不开。这是"远端变了"的**强证据**，
        // 绝不能因为解不开就当作"没变化"。
        val foreignBytes = KdbxEncoder.encode(
            databaseWith(entryTitle = "远端 v2", credentials = OTHER_CREDENTIALS),
        )

        val adopted = Kdbx.adoptBaselineIfMatches(VAULT_ID, foreignBytes)

        assertThat(adopted).isFalse()
        assertThat(KdbxBaseStore.current(VAULT_ID)).isEqualTo(existing)
    }

    @Test
    fun `内容对账：未解锁时返回 false，不凭空登记`() = runBlocking {
        KdbxSessionStore.close(VAULT_ID)
        KdbxBaseStore.clear(VAULT_ID)
        val remoteBytes = KdbxEncoder.encode(databaseWith(entryTitle = "远端"))

        val adopted = Kdbx.adoptBaselineIfMatches(VAULT_ID, remoteBytes)

        assertThat(adopted).isFalse()
        assertThat(KdbxBaseStore.current(VAULT_ID)).isNull()
    }

    @Test
    fun `写回成功后由上层推进基线，下一次保存不再被拦`() = runBlocking {
        registerSession(entryTitle = "本机 v1")
        KdbxBaseStore.record(VAULT_ID, mappedContentOf(databaseWith(entryTitle = "本机 v1")))

        val write = Kdbx.updateItem(
            VAULT_ID,
            before = item(title = "本机 v1"),
            after = item(title = "本机改过"),
        ).getOrThrow()
        // `mutateLocked` 在改动**之前**取的基线判定：这一条必须是 true，
        // 否则上层不会推进基线 ⇒ 第二次保存会被自己刚写的改动拦下。
        assertThat(write.baselineAligned).isTrue()

        Kdbx.advanceBaseline(VAULT_ID, write)

        assertThat(KdbxBaseStore.current(VAULT_ID)).isEqualTo(write.newFingerprint)
        val source = RecordingSource(versionToken = "v1")
        val second = Kdbx.updateItem(
            VAULT_ID,
            before = item(title = "本机改过"),
            after = item(title = "本机再改"),
        ).getOrThrow()
        val saved = Kdbx.saveVia(
            VAULT_ID,
            source,
            encodedBytes = second.bytes,
            expectedVersion = "v1",
            baselineAligned = second.baselineAligned,
        )

        assertThat(saved.isSuccess).isTrue()
    }

    @Test
    fun `基线与本机不符时，写回标记为未对齐，上层不会推进基线`() = runBlocking {
        // 远端 v2 已存在（我们没拉），本机还在 v1 ⇒ 这次编辑**不算**在 v2 基础上做的。
        registerSession(entryTitle = "本机 v1")
        KdbxBaseStore.record(VAULT_ID, mappedContentOf(databaseWith(entryTitle = "远端 v2")))

        val write = Kdbx.updateItem(
            VAULT_ID,
            before = item(title = "本机 v1"),
            after = item(title = "本机改过"),
        ).getOrThrow()

        assertThat(write.baselineAligned).isFalse()
        // ★ 基线没被推进 ⇒ 下一次仍然被拦（防线不会因为一次失败就自己松手）。
        assertThat(KdbxBaseStore.current(VAULT_ID)).isNotEqualTo(write.newFingerprint)
    }

    // ---------------------------------------------------------------- 夹具

    private fun registerSession(entryTitle: String) {
        val database = databaseWith(entryTitle = entryTitle)
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

    private fun databaseWith(
        entryTitle: String,
        credentials: Credentials = CREDENTIALS,
    ): KeePassDatabase {
        val created = KeePassDatabase.Ver4x.create(
            rootName = "Root",
            meta = Meta(name = "Baseline"),
            credentials = credentials,
        )
        val entry = Entry(
            uuid = ENTRY_UUID,
            fields = EntryFields(
                linkedMapOf<String, EntryValue>(
                    BasicField.Title.key to EntryValue.Plain(entryTitle),
                ),
            ),
        )
        return KeePassDatabase.Ver4x(
            credentials = created.credentials,
            header = created.header,
            content = created.content.copy(group = created.content.group.copy(entries = listOf(entry))),
            innerHeader = created.innerHeader,
        )
    }

    private fun mappedContentOf(database: KeePassDatabase): KdbxMappedContent =
        database.toMappedContent()

    private fun item(title: String) = VaultItem(
        id = itemIdOf(ENTRY_UUID),
        title = title,
        type = VaultItemType.Login,
    )

    /** 只记账、不落盘的文件来源（写出去就说明"该拦的没拦住"）。 */
    private class RecordingSource(private val versionToken: String?) : KdbxFileSource {
        data class Write(val bytes: ByteArray, val expectedVersion: String?, val force: Boolean)

        val writes = mutableListOf<Write>()
        var statCalls = 0

        override suspend fun stat(): KdbxFileStat {
            statCalls++
            return KdbxFileStat(versionToken = versionToken)
        }

        override suspend fun read(): ByteArray = ByteArray(0)

        override suspend fun write(
            bytes: ByteArray,
            expectedVersion: String?,
            force: Boolean,
            failIfExists: Boolean,
        ): KdbxFileWriteResult {
            writes += Write(bytes, expectedVersion, force)
            return KdbxFileWriteResult(versionToken = "written")
        }

        override suspend fun testConnection(): Result<Unit> = Result.success(Unit)
    }

    private companion object {
        const val VAULT_ID = "kdbx-baseline-test"
        const val PASSWORD = "master-pw"
        val ENTRY_UUID: UUID = UUID.fromString("dddddddd-eeee-ffff-0000-111111111111")
        val CREDENTIALS: Credentials = Credentials.from(EncryptedValue.fromString(PASSWORD))
        val OTHER_CREDENTIALS: Credentials = Credentials.from(EncryptedValue.fromString("another-pw"))
    }
}
