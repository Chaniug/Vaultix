/*
 * Vaultix — data:bitwarden（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.data.bitwarden.export

import io.vaultix.data.bitwarden.time.BitwardenTimestamps
import io.vaultix.model.CustomFieldType
import io.vaultix.model.UriMatch
import io.vaultix.model.VaultCustomField
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import io.vaultix.model.VaultPasswordHistoryEntry
import io.vaultix.model.VaultReprompt
import io.vaultix.model.VaultUri
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出 / 导入的**往返保真度**（2026-10-28）。
 *
 * ## 这条为什么值得单独钉
 *
 * 领域模型从 2026-10-21 起就承载 [VaultItem.passwordHistory]、
 * [VaultItem.createdAt] / [VaultItem.updatedAt]（界面上能看到密码历史），
 * 但导出侧把这三个字段**恒写成 null**、导入侧**根本不读** ⇒
 *
 * - 用户点「导出」，界面报**成功**，拿到的备份里**没有密码历史、没有时间戳**；
 * - 把这份备份再导回来，密码历史归零 —— 而这一切**没有任何报错**。
 *
 * 同 #155（编辑抹字段）那一类：**服务端/领域模型有、某一层没接**的丢失不报异常、
 * 不留日志，只有"数据少了一块"。⇒ 用**往返**断言（领域 → JSON → 领域）一次性锁住两侧。
 *
 * ## 为什么必须导出侧与导入侧成对改
 *
 * 只改一侧的话，"导出去的文件"与"读回来的模型"仍差一块，而且**测试能过去一半**
 * （导出的断言是绿的，导入的断言是红的）—— 这类"半绿"最容易被人顺手删掉红的那一半。
 */
class BitwardenExportRoundTripTest {

    /** 与 `BitwardenEncryptedExporter/Importer` 同配置（`encodeDefaults` 影响是否输出 null）。 */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun loginItem(
        history: List<VaultPasswordHistoryEntry> = emptyList(),
        createdAt: Long? = null,
        updatedAt: Long? = null,
    ): VaultItem = VaultItem(
        id = "3d0f5b0e-0000-4000-8000-000000000001",
        title = "example.com",
        username = "alice",
        password = "new-pass",
        type = VaultItemType.Login,
        createdAt = createdAt,
        updatedAt = updatedAt,
        passwordHistory = history,
    )

    /** 导出结构里三个字段真的有值（此前恒为 null）。 */
    @Test
    fun `密码历史与时间戳写进导出结构`() {
        val cipher = loginItem(
            history = listOf(
                VaultPasswordHistoryEntry(password = "old-pass", lastUsedAt = 1_763_000_000_000L),
            ),
            createdAt = 1_762_000_000_000L,
            updatedAt = 1_763_000_000_000L,
        ).toExportCipher()

        assertEquals(1, cipher.passwordHistory?.size)
        assertEquals("old-pass", cipher.passwordHistory!!.first().password)
        assertEquals("2025-11-01T12:26:40.000Z", cipher.creationDate)
        assertEquals("2025-11-13T02:13:20.000Z", cipher.revisionDate)
        assertNull(cipher.deletedDate)
    }

    /**
     * 官方**没有**给的字段（省略 / null）读回来必须是"空"，不是假值。
     *
     * 这条测的是"别人家的文件"：官方 exporter 会省略空字段，若我们把缺字段当异常，
     * 导入整份官方备份会直接失败。
     */
    @Test
    fun `官方文件缺字段时导入不炸且为空`() {
        val lenientJson = Json { ignoreUnknownKeys = true }
        val raw = """
            {
              "id": "3d0f5b0e-0000-4000-8000-0000000000ff",
              "name": "legacy",
              "type": 1,
              "login": { "uris": [ { "uri": "example.com" } ] }
            }
        """.trimIndent()

        val back = lenientJson.decodeFromString(BitwardenExportCipher.serializer(), raw).toVaultItem()!!

        assertEquals("legacy", back.title)
        assertEquals("example.com", back.uris.single().uri)
        assertNull("官方省略 match ⇒ 领域 null（不要自作主张填 Domain）", back.uris.single().match)
        assertTrue("缺 passwordHistory ⇒ 空表", back.passwordHistory.isEmpty())
        assertNull(back.createdAt)
        assertNull(back.updatedAt)
    }

    /** 密码历史里 password 为空的条目：导出省略该字段，导入回落空串（官方同语义）。 */
    @Test
    fun `历史密码为空 导出省略且导入回落空串`() {
        val cipher = loginItem(history = listOf(VaultPasswordHistoryEntry(""))).toExportCipher()
        assertNull("空密码不写成 \"\"，直接省字段", cipher.passwordHistory!!.single().password)

        val back = json.decodeFromString(
            BitwardenExportCipher.serializer(),
            json.encodeToString(BitwardenExportCipher.serializer(), cipher),
        ).toVaultItem()!!

        assertEquals("", back.passwordHistory.single().password)
    }

    /** 序列化后的 JSON 里字段名与官方一致（`lastUsedDate` / `creationDate` / `revisionDate`）。 */
    @Test
    fun `JSON 字段名与官方导出一致`() {
        val text = json.encodeToString(
            BitwardenExportCipher.serializer(),
            loginItem(
                history = listOf(VaultPasswordHistoryEntry("old-pass", 1_763_000_000_000L)),
                createdAt = 1_762_000_000_000L,
                updatedAt = 1_763_000_000_000L,
            ).toExportCipher(),
        )
        assertTrue(text, text.contains("\"passwordHistory\""))
        assertTrue(text, text.contains("\"lastUsedDate\":\"2025-11-13T02:13:20.000Z\""))
        assertTrue(text, text.contains("\"creationDate\":\"2025-11-01T12:26:40.000Z\""))
        assertTrue(text, text.contains("\"revisionDate\":\"2025-11-13T02:13:20.000Z\""))
    }

    /** ★ 往返：领域 → JSON → 领域，三个字段一个都不能少。 */
    @Test
    fun `导出再导入 密码历史与时间戳往返保真`() {
        val source = loginItem(
            history = listOf(
                VaultPasswordHistoryEntry(password = "old-2", lastUsedAt = 1_762_500_000_000L),
                VaultPasswordHistoryEntry(password = "old-1", lastUsedAt = 1_763_000_000_000L),
            ),
            createdAt = 1_762_000_000_000L,
            updatedAt = 1_763_000_000_000L,
        )

        val back = json.decodeFromString(
            BitwardenExportCipher.serializer(),
            json.encodeToString(BitwardenExportCipher.serializer(), source.toExportCipher()),
        ).toVaultItem()!!

        assertEquals(source.createdAt, back.createdAt)
        assertEquals(source.updatedAt, back.updatedAt)
        assertEquals(source.passwordHistory, back.passwordHistory)
    }

    /** 没有历史 / 没有时间时不炸，且读回来是"空"而不是"1970"。 */
    @Test
    fun `无历史与无时间时往返为空而非假值`() {
        val back = json.decodeFromString(
            BitwardenExportCipher.serializer(),
            json.encodeToString(BitwardenExportCipher.serializer(), loginItem().toExportCipher()),
        ).toVaultItem()!!

        assertTrue(back.passwordHistory.isEmpty())
        assertNull(back.createdAt)
        assertNull(back.updatedAt)
    }

    /**
     * 历史条目的时间缺失时：导出写**空串**（官方该字段非空，缺字段会让官方导入器整份失败），
     * 导入回到 `null`（不是 1970 —— 那是假信息）。
     */
    @Test
    fun `历史时间缺失 导出空串且导入回 null`() {
        val cipher = loginItem(history = listOf(VaultPasswordHistoryEntry("old-pass"))).toExportCipher()

        assertEquals("", cipher.passwordHistory!!.first().lastUsedDate)

        val back = json.decodeFromString(
            BitwardenExportCipher.serializer(),
            json.encodeToString(BitwardenExportCipher.serializer(), cipher),
        ).toVaultItem()!!

        assertEquals("old-pass", back.passwordHistory.single().password)
        assertNull(back.passwordHistory.single().lastUsedAt)
    }

    /** 非标准 / 空时间串 ⇒ null。兜默认值会让用户看到"看起来合理但错误的时间"。 */
    @Test
    fun `非法时间串导入为 null`() {
        listOf("", "   ", "not-a-date", "2026-13-45T99:99:99Z").forEach { raw ->
            assertNull(BitwardenTimestamps.parse(raw))
        }
        val cipher = loginItem().toExportCipher().copy(
            creationDate = "not-a-date",
            revisionDate = "",
        )
        val back = cipher.toVaultItem()!!
        assertNull(back.createdAt)
        assertNull(back.updatedAt)
    }

    /** 格式化恒为毫秒精度 + UTC（`Instant.toString()` 在整秒时会省掉 `.SSS`，不能直接用）。 */
    @Test
    fun `格式化恒毫秒精度且 UTC`() {
        assertEquals("2025-09-30T12:54:56.789Z", BitwardenTimestamps.format(1_759_236_896_789L))
        // 整秒也必须带 .000（否则与官方导出形状不一致）
        assertEquals("2025-09-30T12:54:56.000Z", BitwardenTimestamps.format(1_759_236_896_000L))
        assertNull(BitwardenTimestamps.format(null))
    }

    /** 解析能吃官方常见的几种写法（Z / 无毫秒 / 带偏移）。 */
    @Test
    fun `解析兼容官方常见写法`() {
        assertEquals(1_759_236_896_789L, BitwardenTimestamps.parse("2025-09-30T12:54:56.789Z"))
        assertEquals(1_759_236_896_000L, BitwardenTimestamps.parse("2025-09-30T12:54:56Z"))
        assertEquals(1_759_236_896_000L, BitwardenTimestamps.parse("2025-09-30T12:54:56+00:00"))
    }

    /**
     * ★ 全字段往返：**每个字段**都变一次，逐一断言能原样回来。
     *
     * 只挑三个字段测的问题（如本文件早期版本）是：**没被测到的字段丢了你不会知道**。
     * 这条用 `copy()` 把 [VaultItem] 的每个会被导出承载的字段都改成非默认值，
     * 再整条比对 —— 与 #190 的 `FIELD_MUTATORS` 表同一个思路（表驱动，不靠记性）。
     *
     * ⚠️ 它做不到"像 data class 的 `equals` 那样自动覆盖所有新字段"：
     * 新增字段仍要在这里加一行。**这条注释就是提醒**（#190 的老教训）。
     */
    @Test
    fun `全字段往返保真`() {
        val source = VaultItem(
            id = "3d0f5b0e-0000-4000-8000-000000000002",
            title = "full.example",
            username = "bob",
            password = "p@ss",
            notes = "备注",
            type = VaultItemType.Login,
            uris = listOf(
                VaultUri("https://a.example", UriMatch.Exact),
                VaultUri("https://b.example", UriMatch.Never),
                VaultUri("https://c.example", null),
            ),
            totp = "otpauth://totp/x?secret=AAAA",
            folderId = "9f0e1d2c-0000-4000-8000-0000000000aa",
            favorite = true,
            reprompt = VaultReprompt.Password,
            customFields = listOf(
                VaultCustomField("明文", "v1", CustomFieldType.Text),
                VaultCustomField("隐藏", "v2", CustomFieldType.Hidden),
                VaultCustomField("布尔", "true", CustomFieldType.Boolean),
                VaultCustomField("关联", "bob", CustomFieldType.Linked, linkedId = 100),
            ),
            passwordHistory = listOf(
                VaultPasswordHistoryEntry("older", 1_762_500_000_000L),
                VaultPasswordHistoryEntry("oldest", 1_762_000_000_000L),
            ),
            createdAt = 1_762_000_000_000L,
            updatedAt = 1_763_000_000_000L,
        )

        val back = json.decodeFromString(
            BitwardenExportCipher.serializer(),
            json.encodeToString(BitwardenExportCipher.serializer(), source.toExportCipher()),
        ).toVaultItem()!!

        assertEquals(source.id, back.id)
        assertEquals(source.title, back.title)
        assertEquals(source.username, back.username)
        assertEquals(source.password, back.password)
        assertEquals(source.notes, back.notes)
        assertEquals(source.type, back.type)
        assertEquals(source.uris, back.uris)
        assertEquals(source.totp, back.totp)
        assertEquals(source.folderId, back.folderId)
        assertEquals(source.favorite, back.favorite)
        assertEquals(source.reprompt, back.reprompt)
        assertEquals(source.customFields, back.customFields)
        assertEquals(source.passwordHistory, back.passwordHistory)
        assertEquals(source.createdAt, back.createdAt)
        assertEquals(source.updatedAt, back.updatedAt)
    }
}
