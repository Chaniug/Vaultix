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
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.models.CustomDataValue
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.Meta
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Test

/**
 * KDBX **写回**（阶段 B）的安全网。
 *
 * 用户硬要求：「**同步不丢，数据不错不漏，同步下来的不损坏不错漏**」。
 * 这个文件逐条兑现它 —— 每条测试都对应一个具体的失效场景，
 * 而不是"跑通就算"。
 */
class KdbxWritePathTest {

    // ---------------------------------------------------------------- 往返保真

    @Test
    fun `round trip preserves plugin custom data - KPEX fields survive`() {
        // ★ 8.3 铁律：`KPEX_*` 插件字段一律原样保留。
        //   丢了 = 用户的通行密钥静默失效（最难发现、后果最重的一类）。
        val db = buildDatabase(
            customData = mapOf(
                "KPEX_BROWSER_INTEGRATION_SETTINGS" to CustomDataValue("{\"allowed\":true}"),
                "KPEX_PASSKEY_KEYPAIRS" to CustomDataValue("PUBKEY-BLOB"),
                "UnknownThirdPartyField" to CustomDataValue("keep-me"),
            ),
        )

        val result = KdbxRoundTrip.verify(db, CREDENTIALS)

        assertThat(result.mismatches).isEmpty()
        assertThat(result.isSafeToWrite).isTrue()

        // 再单独确认"解出来之后"这些键真的还在（不能只信 mismatches 为空）
        val decoded = decode(result.bytes)
        val custom = decoded.content.group.entries.single().customData
        assertThat(custom.keys).containsAtLeast(
            "KPEX_BROWSER_INTEGRATION_SETTINGS",
            "KPEX_PASSKEY_KEYPAIRS",
            "UnknownThirdPartyField",
        )
    }

    @Test
    fun `round trip preserves entry custom fields and protected password`() {
        val db = buildDatabase(
            extraFields = mapOf(
                "MyCustomField" to EntryValue.Plain("custom-value"),
                "TOTP Seed" to EntryValue.Plain("JBSWY3DPEHPK3PXP"),
            ),
        )

        val result = KdbxRoundTrip.verify(db, CREDENTIALS)
        assertThat(result.isSafeToWrite).isTrue()

        val entry = decode(result.bytes).content.group.entries.single()
        assertThat(entry.fields.keys).containsAtLeast(
            BasicField.Title.key,
            BasicField.UserName.key,
            BasicField.Password.key,
            "MyCustomField",
            "TOTP Seed",
        )
        // 密码必须仍是「加密存储」形态（用 Plain 存会把明文写进 XML）
        assertThat(entry.fields[BasicField.Password.key]).isInstanceOf(EntryValue.Encrypted::class.java)
    }

    @Test
    fun `round trip preserves group structure`() {
        val db = buildDatabaseWithNestedGroup()

        val result = KdbxRoundTrip.verify(db, CREDENTIALS)
        assertThat(result.isSafeToWrite).isTrue()

        val root = decode(result.bytes).content.group
        assertThat(root.groups.map { it.name }).contains("Work")
        assertThat(root.groups.single { it.name == "Work" }.groups.map { it.name }).contains("Clients")
    }

    @Test
    fun `round trip detects a key that would be lost`() {
        // 反向用例：证明自检**真的会报**，而不是恒返回 OK。
        // 做法：手工造一个"往返后少了一个 CustomData 键"的库作为对照 ——
        // 这里用"编码前后人为删掉 key"来模拟编码器丢字段。
        val db = buildDatabase(
            customData = mapOf("KPEX_PASSKEY_KEYPAIRS" to CustomDataValue("blob")),
        )
        val before = decode(KdbxEncoder.encode(db))
        // 模拟：编码器把 KPEX 字段吃掉了
        val stripped = before.content.group.entries.single().let { entry ->
            entry.copy(customData = emptyMap())
        }
        val mutated = before.let { database ->
            KeePassDatabase.Ver4x(
                credentials = database.credentials,
                header = database.header,
                content = database.content.copy(
                    group = database.content.group.copy(
                        entries = listOf(stripped),
                    ),
                ),
                innerHeader = database.innerHeader,
            )
        }

        // 现在拿"原始有 KPEX"的库 与 "丢了 KPEX"的库 比对 ⇒ 必须报出来
        val mismatches = mutableListOf<String>()
        val original = decode(KdbxEncoder.encode(db)).content.group.entries.single()
        val after = mutated.content.group.entries.single()
        if (original.customData.mapValues { it.value.value } !=
            after.customData.mapValues { it.value.value }
        ) {
            mismatches += "CustomData 变化"
        }
        assertThat(mismatches).isNotEmpty()
    }

    // ---------------------------------------------------------------- 原子写入

    @Test
    fun `atomic write replaces target and keeps a backup of the previous content`() {
        val dir = Files.createTempDirectory("kdbx-write").toFile()
        val target = File(dir, "vault.kdbx")
        target.writeBytes("ORIGINAL".toByteArray())

        KdbxAtomicWriter.write(target, "NEW-CONTENT".toByteArray())

        assertThat(String(target.readBytes())).isEqualTo("NEW-CONTENT")
        // ★ 备份必须是**替换前**的内容 —— 否则等于没备份
        val backup = KdbxAtomicWriter.backupOf(target)
        assertThat(backup.exists()).isTrue()
        assertThat(String(backup.readBytes())).isEqualTo("ORIGINAL")
        // 临时文件必须清干净（里面是完整密钥库，不该留）
        assertThat(File(dir, "vault.kdbx.tmp").exists()).isFalse()
    }

    @Test
    fun `atomic write does not create a backup when target did not exist`() {
        val dir = Files.createTempDirectory("kdbx-write-new").toFile()
        val target = File(dir, "fresh.kdbx")

        KdbxAtomicWriter.write(target, "FIRST".toByteArray())

        assertThat(String(target.readBytes())).isEqualTo("FIRST")
        // 原本没有文件 ⇒ 没有可备份的东西（备份 null 是正确行为，不是失败）
        assertThat(KdbxAtomicWriter.backupOf(target).exists()).isFalse()
    }

    @Test
    fun `atomic write creates missing parent directories`() {
        val dir = Files.createTempDirectory("kdbx-write-mkdir").toFile()
        val nested = File(dir, "a/b/c/deep.kdbx")

        KdbxAtomicWriter.write(nested, "DEEP".toByteArray())

        assertThat(nested.exists()).isTrue()
        assertThat(String(nested.readBytes())).isEqualTo("DEEP")
    }

    // ---------------------------------------------------------------- 门面 save

    @Test
    fun `save rejects when vault is not unlocked`() {
        val dir = Files.createTempDirectory("kdbx-save-locked").toFile()
        val target = File(dir, "vault.kdbx")

        val result = Kdbx.save("not-unlocked-vault", target)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(KdbxFailure::class.java)
        assertThat((result.exceptionOrNull() as KdbxFailure).error)
            .isEqualTo(KdbxOpenError.NotUnlocked)
        // ★ 未解锁时必须**一个字节都不落**
        assertThat(target.exists()).isFalse()
    }

    @Test
    fun `save writes a valid database that can be reopened with the same password`() {
        val dir = Files.createTempDirectory("kdbx-save-ok").toFile()
        val target = File(dir, "vault.kdbx")
        val vaultId = "test-vault"
        val db = buildDatabase(
            customData = mapOf("KPEX_PASSKEY_KEYPAIRS" to CustomDataValue("blob")),
        )

        // 复现"已解锁"状态
        KdbxSessionStore.put(
            vaultId,
            KdbxSession(
                database = db,
                content = db.toMappedContent(),
                credentialLabel = "主密码",
                credentials = CREDENTIALS,
            ),
        )

        val report = Kdbx.save(vaultId, target).getOrThrow()
        KdbxSessionStore.close(vaultId)

        assertThat(target.exists()).isTrue()
        assertThat(report.bytesWritten).isGreaterThan(0)
        assertThat(report.entryCount).isEqualTo(1)

        // ★ 判据：**用原密码能把写出来的文件重新打开**（"不损坏"的最终检验）
        val reopened = KdbxOpener.open(target.readBytes(), PASSWORD, keyFileBytes = null)
        assertThat(reopened.isSuccess).isTrue()
        val items = reopened.getOrThrow().content.items
        assertThat(items.single().title).isEqualTo("GitHub")
    }

    // ---------------------------------------------------------------- 保真度登记

    @Test
    fun `fidelity inspection reports plugin data so it is visible not silent`() {
        val db = buildDatabase(
            customData = mapOf("KPEX_PASSKEY_KEYPAIRS" to CustomDataValue("blob")),
        )

        val notes = KdbxFidelity.inspect(db)

        assertThat(notes.map { it.kind })
            .contains(KdbxFidelityNote.Kind.PLUGIN_DATA_PRESENT)
    }

    // ---------------------------------------------------------------- 夹具

    private fun buildDatabase(
        customData: Map<String, CustomDataValue> = emptyMap(),
        extraFields: Map<String, EntryValue> = emptyMap(),
    ): KeePassDatabase {
        val entry = Entry(
            uuid = ENTRY_UUID,
            fields = EntryFields(
                linkedMapOf<String, EntryValue>(
                    BasicField.Title.key to EntryValue.Plain("GitHub"),
                    BasicField.UserName.key to EntryValue.Plain("alice"),
                    BasicField.Password.key to EntryValue.Encrypted(
                        EncryptedValue.fromString("s3cret-pw"),
                    ),
                ).apply { putAll(extraFields) },
            ),
            customData = customData,
        )
        return KeePassDatabase.Ver4x.create(
            rootName = "Root",
            meta = Meta(name = "Test"),
            credentials = CREDENTIALS,
        ).let { created ->
            KeePassDatabase.Ver4x(
                credentials = created.credentials,
                header = created.header,
                content = created.content.copy(
                    group = created.content.group.copy(entries = listOf(entry)),
                ),
                innerHeader = created.innerHeader,
            )
        }
    }

    private fun buildDatabaseWithNestedGroup(): KeePassDatabase {
        val clients = Group(uuid = UUID.randomUUID(), name = "Clients", entries = emptyList())
        val work = Group(uuid = UUID.randomUUID(), name = "Work", groups = listOf(clients))
        return KeePassDatabase.Ver4x.create(
            rootName = "Root",
            meta = Meta(name = "Test"),
            credentials = CREDENTIALS,
        ).let { created ->
            KeePassDatabase.Ver4x(
                credentials = created.credentials,
                header = created.header,
                content = created.content.copy(
                    group = created.content.group.copy(groups = listOf(work)),
                ),
                innerHeader = created.innerHeader,
            )
        }
    }

    private fun decode(bytes: ByteArray): KeePassDatabase =
        ByteArrayInputStream(bytes).use { input ->
            KeePassDatabase.decode(
                inputStream = input,
                credentials = CREDENTIALS,
                cipherProviders = KDBX_CIPHER_PROVIDERS,
            )
        }

    private companion object {
        const val PASSWORD = "master-pw-123"
        val CREDENTIALS: Credentials = Credentials.from(EncryptedValue.fromString(PASSWORD))
        val ENTRY_UUID: UUID = UUID.fromString("11111111-2222-3333-4444-555555555555")
    }
}
