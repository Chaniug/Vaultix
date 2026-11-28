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
import app.keemobile.kotpass.database.modifiers.binaries
import app.keemobile.kotpass.database.modifiers.modifyBinaries
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.BinaryData
import app.keemobile.kotpass.models.BinaryReference
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.Meta
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.GZIPOutputStream

/**
 * KDBX 附件导出（P1 · 2026-11-28）的行为测试。
 *
 * ## 为什么必须真造一个带 binary 的库
 *
 * 这里验证的是「kotpass 0.13.0 公开 API 能不能拿到正确明文」—— 纯字符串门禁
 * 只能证明代码形状，证明不了压缩态、`copy()` 防误清这些真正会出事的细节。
 */
class KdbxAttachmentTest {

    private val vaultId = "attachment-test-vault"

    @After
    fun tearDown() {
        KdbxSessionStore.close(vaultId)
    }

    @Test
    fun `未压缩附件按 hash 取回明文`() {
        val plain = "合同正文：A1B2-C3D4".toByteArray()
        val (db, hash) = databaseWithBinary(BinaryData.Uncompressed(false, plain))
        putSession(db)

        assertThat(Kdbx.attachmentBytes(vaultId, hash)).isEqualTo(plain)
    }

    @Test
    fun `压缩附件取回的是解压后的明文`() {
        val plain = "可以压缩的重复内容".repeat(40).toByteArray()
        val compressed = gzip(plain)
        // Uncompressed.getContent() 返回原文；Compressed.getContent() 必须解压。
        val (db, hash) = databaseWithBinary(BinaryData.Compressed(false, compressed))
        putSession(db)

        assertThat(Kdbx.attachmentBytes(vaultId, hash)).isEqualTo(plain)
    }

    @Test
    fun `取出的字节是副本，清空它不会破坏会话里的原件`() {
        val plain = "清空副本不能影响库".toByteArray()
        val (db, hash) = databaseWithBinary(BinaryData.Uncompressed(false, plain))
        putSession(db)

        val first = Kdbx.attachmentBytes(vaultId, hash)!!
        first.fill(0)
        val second = Kdbx.attachmentBytes(vaultId, hash)!!

        assertThat(second).isEqualTo(plain)
    }

    @Test
    fun `hash 不存在返回 null 而不是抛异常`() {
        val (db, _) = databaseWithBinary(BinaryData.Uncompressed(false, "x".toByteArray()))
        putSession(db)

        assertThat(Kdbx.attachmentBytes(vaultId, "00".repeat(32))).isNull()
    }

    @Test
    fun `库未解锁返回 null`() {
        assertThat(Kdbx.attachmentBytes(vaultId, "00".repeat(32))).isNull()
    }

    // ---------------------------------------------------------------- 构造

    private fun putSession(db: KeePassDatabase) {
        KdbxSessionStore.put(
            vaultId,
            KdbxSession(
                database = db,
                content = db.toMappedContent(),
                credentialLabel = "主密码",
                credentials = CREDENTIALS,
            ),
        )
    }

    private fun databaseWithBinary(binary: BinaryData): Pair<KeePassDatabase, String> {
        val created = KeePassDatabase.Ver4x.create(
            rootName = "Root",
            meta = Meta(name = "Test"),
            credentials = CREDENTIALS,
        )
        val entry = Entry(
            uuid = UUID.randomUUID(),
            binaries = listOf(BinaryReference(binary.hash, "attachment.txt")),
        )
        val withBinary = created
            .modifyBinaries { mapOf(binary.hash to binary) }
            .modifyParentGroup { copy(entries = entries + entry) }
        // 确认公开 API 真的能按 hash 找到（若 kotpass 改回 internal，这里先炸）。
        require(withBinary.binaries.containsKey(binary.hash))
        return withBinary to binary.hash.hex()
    }

    private fun gzip(plain: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(plain) }
        return out.toByteArray()
    }

    private companion object {
        val CREDENTIALS: Credentials = Credentials.from(EncryptedValue.fromString("master-pw"))
    }
}
