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
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.database.getEntryBy
import app.keemobile.kotpass.database.modifiers.modifyEntry
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Meta
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID
import org.junit.Test

/**
 * 条目**时间字段**（创建 / 最近修改）的映射与语义（2026-09-30 用户要求）。
 *
 * ## 为什么值得单独一个文件
 *
 * 这两行是「用户拿它来判断『这条是不是我刚改过』」的依据 —— 显示错了不会崩，
 * 但会**误导判断**。三个具体风险点各自需要一条断言钉住：
 *
 * | 风险 | 症状 | 本文件的用例 |
 * |---|---|---|
 * | 取错字段 | 显示成"上次查看时间"，每次打开条目都变 | [lastAccessTime 不会被当成最近修改] |
 * | 缺字段时兜默认值 | 显示 1970 或"现在"，看起来合理但是假的 | [条目没有 times 时两个时间都是 null] |
 * | 编辑后时间不更新 | 改完密码，"最近修改"还是老时间 | [上游的 modifyEntry 会自动推进最近修改时间] |
 */
class KdbxItemTimesTest {

    // ---------------------------------------------------------------- 读映射

    @Test
    fun `读映射带出创建时间与最近修改时间`() {
        val created = CREATED_AT
        val modified = MODIFIED_AT
        val db = databaseWith(
            Entry(
                uuid = ENTRY_UUID,
                fields = EntryFields(linkedMapOf(BasicField.Title.key to EntryValue.Plain("GitHub"))),
                times = app.keemobile.kotpass.models.TimeData(
                    creationTime = created,
                    lastAccessTime = created,
                    lastModificationTime = modified,
                    locationChanged = created,
                    expiryTime = null,
                ),
            ),
        )

        val item = mappedItemOf(db)

        assertThat(item.createdAt).isEqualTo(created.toEpochMilli())
        assertThat(item.updatedAt).isEqualTo(modified.toEpochMilli())
    }

    @Test
    fun `lastAccessTime 不会被当成最近修改`() {
        // ★ 这条守的是一个"看起来更合理"的错误改法：`lastAccessTime` 字面上是"最近的时间"，
        //   很容易被顺手拿去当"最近修改"。但 KeePass 的语义是**上次查看**，
        //   而且上游 `Group.modifyEntry` **每次改条目都会刷新它** ⇒ 用它当"最近修改"，
        //   用户会看到时间「每次点开条目都在变」，从而完全无法判断数据新旧。
        val accessed = Instant.parse("2030-01-01T00:00:00Z")
        val modified = Instant.parse("2020-01-01T00:00:00Z")
        val db = databaseWith(
            Entry(
                uuid = ENTRY_UUID,
                fields = EntryFields(linkedMapOf(BasicField.Title.key to EntryValue.Plain("GitHub"))),
                times = app.keemobile.kotpass.models.TimeData(
                    creationTime = CREATED_AT,
                    lastAccessTime = accessed, // ← 比 modified 新得多
                    lastModificationTime = modified,
                    locationChanged = CREATED_AT,
                    expiryTime = null,
                ),
            ),
        )

        assertThat(mappedItemOf(db).updatedAt).isEqualTo(modified.toEpochMilli())
    }

    @Test
    fun `条目没有 times 时两个时间都是 null（不兜默认值）`() {
        // ★ 兜默认值（"现在" / 1970）会显示一个**看起来合理但完全错误**的时间。
        //   正确行为是"这一行不显示" —— 见 VaultItem.createdAt 的 KDoc。
        val db = databaseWith(
            Entry(
                uuid = ENTRY_UUID,
                fields = EntryFields(linkedMapOf(BasicField.Title.key to EntryValue.Plain("GitHub"))),
                times = null,
            ),
        )

        val item = mappedItemOf(db)

        assertThat(item.createdAt).isNull()
        assertThat(item.updatedAt).isNull()
    }

    // ---------------------------------------------------------------- 写路径依赖的上游语义

    @Test
    fun `上游的 modifyEntry 会自动推进最近修改时间`() {
        // ★ 这条不是测我们的代码，而是**钉住我们依赖的上游契约**：
        //   W1（KdbxItemWriter）的 updateEntry 打算直接复用 `modifyEntry`，
        //   而"改完条目、最近修改时间会更新"这件事**不是我们写的** —— 是上游
        //   `Group.modifyEntry` 里 `times.copy(lastModificationTime = now)` 做的。
        //   ⇒ 一旦升级 kotpass 后它不再这么做，我们的"最近修改"就会静默停在老时间。
        //     本条用例就是那个升级门禁。
        val db = databaseWith(
            Entry(
                uuid = ENTRY_UUID,
                fields = EntryFields(linkedMapOf(BasicField.Title.key to EntryValue.Plain("GitHub"))),
                times = app.keemobile.kotpass.models.TimeData(
                    creationTime = CREATED_AT,
                    lastAccessTime = CREATED_AT,
                    lastModificationTime = MODIFIED_AT,
                    locationChanged = CREATED_AT,
                    expiryTime = null,
                ),
            ),
        )

        // ⚠️ `modifyEntry` 的 block 是**接收者 lambda**（`Entry.() -> Entry`）——
        //    必须用 `this` 形态（`copy(...)` 直接调），写 `{ entry -> ... }` 会被解析成
        //    "接收者 + 一个多余参数" 而签名不匹配。
        val updated = db.modifyEntry(ENTRY_UUID) {
            copy(fields = fields + (BasicField.Title.key to EntryValue.Plain("Renamed")))
        }
        // ⚠️ 同理 `getEntryBy` 的谓词也是接收者 lambda（`Entry.() -> Boolean`）⇒ 用 `uuid` 而不是 `it.uuid`。
        val times = updated.getEntryBy { uuid == ENTRY_UUID }?.times

        // ① 创建时间**不动**（它是"这条什么时候出现的"，改内容不该动它）。
        assertThat(times?.creationTime).isEqualTo(CREATED_AT)
        // ② 最近修改被推到"现在"（严格晚于我们写死的旧值）。
        assertThat(times?.lastModificationTime).isGreaterThan(MODIFIED_AT)
    }

    // ---------------------------------------------------------------- helpers

    private fun databaseWith(entry: Entry): KeePassDatabase {
        val created = KeePassDatabase.Ver4x.create(
            rootName = "Root",
            meta = Meta(name = "Times"),
            credentials = CREDENTIALS,
        )
        return created.copy(
            content = created.content.copy(
                group = created.content.group.copy(entries = listOf(entry)),
            ),
        )
    }

    /** 编一遍再解一遍 —— 走**真实字节**，避免"只在内存里对"的假绿。 */
    private fun mappedItemOf(db: KeePassDatabase): io.vaultix.model.VaultItem {
        val bytes = ByteArrayOutputStream().use { out ->
            db.encode(outputStream = out, cipherProviders = KDBX_CIPHER_PROVIDERS)
            out.toByteArray()
        }
        return KdbxOpener.open(bytes, PASSWORD, keyFileBytes = null).getOrThrow()
            .content.items.single()
    }

    private companion object {
        const val PASSWORD = "master-pw-123"
        val CREATED_AT: Instant = Instant.parse("2024-03-04T05:06:07Z")
        val MODIFIED_AT: Instant = Instant.parse("2025-07-08T09:10:11Z")
        val ENTRY_UUID: UUID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        val CREDENTIALS: Credentials = Credentials.from(EncryptedValue.fromString(PASSWORD))
    }
}
