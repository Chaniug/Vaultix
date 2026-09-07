package com.bastion.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.bastion.app.data.ItemType
import com.bastion.app.utils.SecureItemRestoreTypeResolver

class SecureItemRestoreTypeResolverTest {

    @Test
    fun resolvesLegacyBankCardPayloadFromCardsDocsCsv() {
        val payload = """
            {"number":"6222020202020202","expMonth":"12","expYear":"2030","code":"123"}
        """.trimIndent()

        val type = SecureItemRestoreTypeResolver.resolve(
            rawType = "NOTE",
            itemData = payload,
            sourceFileName = "Bastion_20240101_cards_docs.csv"
        )

        assertEquals(ItemType.BANK_CARD, type)
    }

    @Test
    fun resolvesLegacyDocumentPayloadFromCardsDocsCsv() {
        val payload = """
            {"issueDate":"2020-01-01","issuingAuthority":"公安局","firstName":"Joy","lastName":"Lin"}
        """.trimIndent()

        val type = SecureItemRestoreTypeResolver.resolve(
            rawType = "NOTE",
            itemData = payload,
            sourceFileName = "Bastion_20240101_cards_docs.csv"
        )

        assertEquals(ItemType.DOCUMENT, type)
    }

    @Test
    fun keepsTrueNotePayloadAsNote() {
        val payload = """
            {"content":"hello","tags":["a","b"],"isMarkdown":true}
        """.trimIndent()

        val type = SecureItemRestoreTypeResolver.resolve(
            rawType = "NOTE",
            itemData = payload,
            sourceFileName = "Bastion_20240101_notes.csv"
        )

        assertEquals(ItemType.NOTE, type)
    }

    @Test
    fun returnsNullForUnknownPayloadWithoutType() {
        val payload = """{"foo":"bar"}"""

        val type = SecureItemRestoreTypeResolver.resolve(
            rawType = null,
            itemData = payload,
            sourceFileName = "secure_items.csv"
        )

        assertNull(type)
    }
}
