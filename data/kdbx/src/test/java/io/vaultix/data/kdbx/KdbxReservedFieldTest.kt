/*
 * Vaultix — data:kdbx
 * Copyright (C) 2026 Vaultix contributors
 *
 * 字段保留区（R1）单元测试（W1 · JSON⇄KDBX 无损互转）。
 * 验证铁律 R1：自定义字段名命中保留区（标准 / OTP / 通行密钥）时**绝不覆盖**标准字段，
 * 且普通自定义字段照常写入、删除照常生效。
 *
 * 纯 JVM（kotpass 在 JVM 上可跑），不需要 Android 运行时。
 */
package io.vaultix.data.kdbx

import app.keemobile.kotpass.constants.BasicField
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import com.google.common.truth.Truth.assertThat
import io.vaultix.model.VaultCustomField
import io.vaultix.model.VaultItem
import org.junit.Test

class KdbxReservedFieldTest {

    private fun item(id: String, customFields: List<VaultCustomField>) = VaultItem(
        id = id,
        title = "标题",
        customFields = customFields,
    )

    @Test
    fun `custom field named Title does not overwrite standard title`() {
        // 库里本来的库名（标准 Title 字段）。
        val existing = EntryFields.createDefault() + (BasicField.Title.key to EntryValue.Plain("真实库名"))
        val result = KdbxItemWriter.applyCustomFields(
            existing,
            item("kdbx-entry:00000000-0000-0000-0000-000000000001", listOf(VaultCustomField("Title", "恶意标题"))),
            null,
        )
        assertThat(result.title?.content).isEqualTo("真实库名")
    }

    @Test
    fun `custom field named Url does not overwrite standard url`() {
        val existing = EntryFields.createDefault() + (BasicField.Url.key to EntryValue.Plain("http://orig.example"))
        val result = KdbxItemWriter.applyCustomFields(
            existing,
            item("kdbx-entry:00000000-0000-0000-0000-000000000002", listOf(VaultCustomField("Url", "http://evil.example"))),
            null,
        )
        assertThat(result.url?.content).isEqualTo("http://orig.example")
    }

    @Test
    fun `custom field named title lowercase does not overwrite standard title`() {
        // 大小写变体也必须挡住（探针实测 "title" 与 "Title" 会在 kotpass 里并存两条）。
        val existing = EntryFields.createDefault() + (BasicField.Title.key to EntryValue.Plain("真实库名"))
        val result = KdbxItemWriter.applyCustomFields(
            existing,
            item("kdbx-entry:00000000-0000-0000-0000-000000000003", listOf(VaultCustomField("title", "小写标题"))),
            null,
        )
        assertThat(result.title?.content).isEqualTo("真实库名")
    }

    @Test
    fun `ordinary custom field is still written`() {
        val existing = EntryFields.createDefault()
        val result = KdbxItemWriter.applyCustomFields(
            existing,
            item("kdbx-entry:00000000-0000-0000-0000-000000000004", listOf(VaultCustomField("MyNote", "好值"))),
            null,
        )
        assertThat(result["MyNote"]?.content).isEqualTo("好值")
    }

    @Test
    fun `minus still removes a deleted custom field`() {
        // before 有 MyNote，after 没有 ⇒ 删除判据应把它清掉。
        val existing = EntryFields.createDefault() + ("MyNote" to EntryValue.Plain("旧值"))
        val before = item("kdbx-entry:00000000-0000-0000-0000-000000000005", listOf(VaultCustomField("MyNote", "旧值")))
        val after = item("kdbx-entry:00000000-0000-0000-0000-000000000005", emptyList())
        val result = KdbxItemWriter.applyCustomFields(existing, after, before)
        assertThat(result["MyNote"]).isNull()
    }
}
