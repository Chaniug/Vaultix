/*
 * Vaultix — data:kdbx（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.data.kdbx

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.getEntry
import app.keemobile.kotpass.database.modifiers.binaries
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.BinaryReference
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Meta
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.UUID

/**
 * KDBX 附件**写入**（P2 · 2026-11-28）的行为测试。
 *
 * 验证的是保真铁律下的真往返：新增后重开库能取回同样字节；删除后二进制被清理；
 * 普通字段（自定义字段 / 自定义图标）不被附件操作误改；删除只清无引用的二进制。
 */
class KdbxAttachmentWriteTest {

    @Test
    fun `新增附件后条目引用与库内二进制都就位且明文可取回`() {
        val db = databaseWithEntry()
        val itemId = "kdbx-entry:${ITEM_UUID}"

        val result = KdbxItemWriter.addAttachment(db, itemId, "合同.pdf", PLAIN)
        assertThat(result.applied).isTrue()

        val entry = result.database.getEntry { it.uuid == ITEM_UUID }!!.second
        assertThat(entry.binaries).hasSize(1)
        assertThat(entry.binaries.first().name).isEqualTo("合同.pdf")
        val hash = entry.binaries.first().hash
        assertThat(result.database.binaries[hash]?.getContent()).isEqualTo(PLAIN)
    }

    @Test
    fun `同一条目新增两个附件不互相覆盖`() {
        val db = databaseWithEntry()
        val itemId = "kdbx-entry:${ITEM_UUID}"

        val first = KdbxItemWriter.addAttachment(db, itemId, "a.txt", "A".toByteArray())
        val second = KdbxItemWriter.addAttachment(first.database, itemId, "b.txt", "B".toByteArray())

        val entry = second.database.getEntry { it.uuid == ITEM_UUID }!!.second
        assertThat(entry.binaries.map { it.name }).containsExactly("a.txt", "b.txt")
        assertThat(second.database.binaries.size).isEqualTo(2)
    }

    @Test
    fun `删除附件后当前条目引用消失`() {
        val db = databaseWithEntry()
        val itemId = "kdbx-entry:${ITEM_UUID}"
        val added = KdbxItemWriter.addAttachment(db, itemId, "合同.pdf", PLAIN)
        val hash = added.database.getEntry { it.uuid == ITEM_UUID }!!.second.binaries.first().hash

        val removed = KdbxItemWriter.removeAttachment(added.database, itemId, hash.hex())

        assertThat(removed.applied).isTrue()
        val entry = removed.database.getEntry { it.uuid == ITEM_UUID }!!.second
        assertThat(entry.binaries).isEmpty()
        // ⚠️ 二进制不在此刻消失：withHistory 存的改动前快照仍引用它，而 kotpass 的
        //    removeUnusedBinaries 会把 history 一起算进「仍在用」——这是 KeePass 的正确
        //    语义（历史版本要能打开），不是清理失败。等该条目的历史被 KeePass 清理后，
        //    下次 removeUnusedBinaries 才会真正回收。见实现方法 KDoc。
        assertThat(removed.database.binaries).containsKey(hash)
    }

    @Test
    fun `删除只清无引用二进制别的条目仍引用时保留`() {
        val db = databaseWithEntry()
        val itemId = "kdbx-entry:${ITEM_UUID}"
        val added = KdbxItemWriter.addAttachment(db, itemId, "合同.pdf", PLAIN)
        val hash = added.database.getEntry { it.uuid == ITEM_UUID }!!.second.binaries.first().hash

        // 造第二个条目引用同一个 hash（内容哈希去重场景）。
        val other = Entry(uuid = OTHER_UUID, binaries = listOf(BinaryReference(hash, "shared.pdf")))
        val withOther = added.database.modifyParentGroup {
            copy(entries = entries + other)
        }
        val removed = KdbxItemWriter.removeAttachment(withOther, itemId, hash.hex())

        // 仍被别的条目引用 ⇒ 二进制不能被清。
        assertThat(removed.database.binaries).containsKey(hash)
        val otherEntry = removed.database.getEntry { it.uuid == OTHER_UUID }!!.second
        assertThat(otherEntry.binaries.map { it.name }).containsExactly("shared.pdf")
    }

    @Test
    fun `目标条目不存在时 applied 为 false 不改库`() {
        val db = databaseWithEntry()
        val result = KdbxItemWriter.addAttachment(db, "kdbx-entry:${MISSING_UUID}", "x", PLAIN)
        assertThat(result.applied).isFalse()
        assertThat(result.database.binaries).isEmpty()
    }

    @Test
    fun `附件操作不误改普通自定义字段`() {
        val db = databaseWithEntry()
        val itemId = "kdbx-entry:${ITEM_UUID}"
        val added = KdbxItemWriter.addAttachment(db, itemId, "a.txt", "A".toByteArray())

        val entry = added.database.getEntry { it.uuid == ITEM_UUID }!!.second
        assertThat(entry.fields[FIELD_KEY]?.content).isEqualTo(FIELD_VALUE)
    }

    // ---------------------------------------------------------------- 构造

    private fun databaseWithEntry(): KeePassDatabase {
        val created = KeePassDatabase.Ver4x.create(
            rootName = "Root",
            meta = Meta(name = "Test"),
            credentials = CREDENTIALS,
        )
        val entry = Entry(
            uuid = ITEM_UUID,
            fields = EntryFields.of(FIELD_KEY to EntryValue.Plain(FIELD_VALUE)),
            binaries = emptyList(),
        )
        return created.modifyParentGroup {
            copy(entries = entries + entry)
        }
    }

    private companion object {
        val ITEM_UUID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val OTHER_UUID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val MISSING_UUID: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
        const val FIELD_KEY = "MyCustomField"
        const val FIELD_VALUE = "do-not-touch"
        val PLAIN = "合同正文：A1B2-C3D4".toByteArray()
        val CREDENTIALS: Credentials = Credentials.from(EncryptedValue.fromString("master-pw"))
    }
}
