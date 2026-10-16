/*
 * Vaultix — data:repository（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 格式转换器（W5 · JSON⇄KDBX）端到端测试。
 *
 * ## 为什么必须端到端
 *
 * 转换链是**三段拼接**：facade 解密 → Kdbx 编解码 → facade 加密。
 * 每一段单独测过都不算数 —— 丢数据只发生在**接缝**上（如 KDBX 侧 id 带
 * `kdbx-entry:` 前缀直接进 JSON，官方导入器会整个文件拒读）。
 * 所以主用例是完整往返：造 JSON → 转 KDBX → 转回 JSON → 再解密比对。
 *
 * ## KDF 口径
 *
 * 主往返走 facade 的 600k PBKDF2（与生产一致，别为快改口径 —— 那是被测行为）；
 * 只有"手搓未知类型 JSON"一条用低迭代（那条测的是映射，不是 KDF）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.repository

import io.vaultix.common.OtpUriParser
import io.vaultix.crypto.VaultixCrypto
import io.vaultix.data.bitwarden.export.BitwardenEncryptedExporter
import io.vaultix.data.bitwarden.export.BitwardenEncryptedImporter
import io.vaultix.data.bitwarden.export.BitwardenExportCipher
import io.vaultix.data.bitwarden.export.BitwardenExportFacade
import io.vaultix.data.bitwarden.export.BitwardenExportKdfType
import io.vaultix.data.bitwarden.export.BitwardenPlainExport
import io.vaultix.data.bitwarden.export.ExportKdf
import io.vaultix.domain.VaultConversionException
import io.vaultix.model.VaultCard
import io.vaultix.model.VaultFido2Credential
import io.vaultix.model.VaultFolder
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import io.vaultix.model.VaultUri
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultConverterRepositoryImplTest {

    private val crypto = VaultixCrypto(Dispatchers.Unconfined)
    private val exporter = BitwardenEncryptedExporter(crypto)
    private val facade = BitwardenExportFacade(
        exporter = exporter,
        importer = BitwardenEncryptedImporter(crypto),
    )
    private val repository = VaultConverterRepositoryImpl(facade, Dispatchers.Unconfined)

    private val filePassword = "json-file-pw"
    private val outputPassword = "kdbx-master-pw"
    private val exportPassword = "re-export-pw"

    // ---------------------------------------------------------------- 端到端往返

    @Test
    fun `端到端往返_登录与安全笔记_核心字段与id全部保真`() = runTest {
        val loginId = UUID.randomUUID().toString()
        val login = VaultItem(
            id = loginId,
            title = "GitHub",
            username = "alice",
            password = "s3cret",
            notes = "2FA backup codes inside",
            type = VaultItemType.Login,
            uris = listOf(
                VaultUri(uri = "https://github.com"),
                VaultUri(uri = "https://gist.github.com"),
            ),
            totp = "otpauth://totp/GitHub:alice?secret=JBSWY3DPEHPK3PXP&algorithm=SHA256&digits=6&period=30",
            fido2Credentials = listOf(passkey()),
            favorite = true,
        )
        val note = VaultItem(
            id = UUID.randomUUID().toString(),
            title = "Wi-Fi 密码",
            type = VaultItemType.SecureNote,
            notes = "SSID: home-5g / PW: hunter2",
        )

        // ① BW JSON（facade 加密，与生产同口径）
        val json = facade.export(folders = emptyList(), items = listOf(login, note), exportPassword = filePassword)
        // ② JSON → 预览 → KDBX 字节
        val preview = repository.previewJsonImport(json, filePassword)
        val kdbx = repository.convertJsonToKdbx(preview, outputPassword, "Vaultix")
        // ③ KDBX 字节 → 回 JSON（换一把导出密码）
        val back = repository.convertKdbxToJson(kdbx.bytes, outputPassword, exportPassword)
        // ④ 解密回来看
        val reparsed = repository.previewJsonImport(String(back.bytes, Charsets.UTF_8), exportPassword)

        val backLogin = reparsed.items.single { it.title == "GitHub" }
        val backNote = reparsed.items.single { it.title == "Wi-Fi 密码" }

        assertEquals("id 应经 VPX_BW_ID 原样保真", loginId, backLogin.id)
        assertEquals("alice", backLogin.username)
        assertEquals("s3cret", backLogin.password)
        assertEquals("2FA backup codes inside", backLogin.notes)
        assertTrue("收藏应经 VPX_FAVORITE 往返", backLogin.favorite)
        assertEquals(
            "两条 URI 都应往返（第 2 条走 VPX_URL_1）",
            listOf("https://github.com", "https://gist.github.com"),
            backLogin.uris.map { it.uri },
        )
        val beforeTotp = requireNotNull(OtpUriParser.parse(login.totp!!))
        val afterTotp = requireNotNull(OtpUriParser.parse(backLogin.totp!!))
        assertEquals("TOTP secret 应往返", beforeTotp.secret, afterTotp.secret)
        assertEquals("TOTP digits 应往返", beforeTotp.digits, afterTotp.digits)
        assertEquals("TOTP period 应往返", beforeTotp.period, afterTotp.period)
        val backPasskey = backLogin.fido2Credentials.single()
        assertEquals("example.com", backPasskey.rpId)
        assertEquals("私钥 PEM 应往返", passkey().keyValue, backPasskey.keyValue)

        assertEquals("安全笔记类型应经 VPX_BW_TYPE 往返", VaultItemType.SecureNote, backNote.type)
        assertEquals("SSID: home-5g / PW: hunter2", backNote.notes)
    }

    @Test
    fun `端到端往返_产物字节可用输出密码直接打开`() = runTest {
        val json = facade.export(emptyList(), listOf(sampleLogin()), filePassword)

        val preview = repository.previewJsonImport(json, filePassword)
        val kdbx = repository.convertJsonToKdbx(preview, outputPassword, "Vaultix")

        // 反向转换用的就是"输出库主密码"——自己转的文件必须能用它打开。
        val back = repository.convertKdbxToJson(kdbx.bytes, outputPassword, exportPassword)
        assertEquals(1, back.stats.itemCount)
    }

    // ---------------------------------------------------------------- 计数与降级

    @Test
    fun `预览计数_条目TOTP通行密钥降级各归各位`() = runTest {
        val items = listOf(
            sampleLogin().copy(
                totp = "otpauth://totp/x?secret=JBSWY3DPEHPK3PXP",
                fido2Credentials = listOf(passkey()),
            ),
            VaultItem(id = UUID.randomUUID().toString(), title = "卡", type = VaultItemType.Card, card = VaultCard()),
        )
        val json = facade.export(emptyList(), items, filePassword)

        val preview = repository.previewJsonImport(json, filePassword)

        assertEquals(2, preview.stats.itemCount)
        assertEquals(1, preview.stats.totpCount)
        assertEquals(1, preview.stats.passkeyCount)
        assertEquals("Card 条目转 KDBX 只剩标题备注，必须计数告知", 1, preview.stats.typeDegradedCount)
    }

    @Test
    fun `未知类型条目_预览计为跳过且不进入转换`() = runTest {
        // 手搓一条 type=99（未来的 BankAccount）的加密 JSON：官方导出器会过滤它，
        // 但我们可能收到**别的工具**产出的文件 —— 未知类型宁可跳过也不猜映射。
        val plain = BitwardenPlainExport(
            items = listOf(
                BitwardenExportCipher(id = UUID.randomUUID().toString(), name = "银行卡", type = 99),
                BitwardenExportCipher(id = UUID.randomUUID().toString(), name = "正常登录", type = 1),
            ),
        )
        val json = exporter.export(plain, filePassword, FAST_KDF)

        val preview = repository.previewJsonImport(json, filePassword)

        assertEquals("未知类型应计为跳过", 1, preview.stats.skippedCount)
        assertEquals("跳过的不该进条目列表", 1, preview.stats.itemCount)
        assertEquals("正常登录", preview.items.single().title)
    }

    // ---------------------------------------------------------------- id 合法性

    @Test
    fun `文件夹id重映射_产出合法UUID且条目跟随`() {
        val oldId = "kdbx-group:${UUID.randomUUID()}"
        val folders = listOf(VaultFolder(id = oldId, name = "工作"))

        val (remapped, remap) = repository.remapFolderIds(folders)

        val newId = remapped.single().id
        // Bitwarden 官方导入器对非 UUID id 在反序列化阶段直接报错（BitwardenExportFolder.id）。
        // 这一行抛 IllegalArgumentException 即测试失败。
        UUID.fromString(newId)
        assertEquals(oldId, remap.entries.single().key)
        assertEquals(newId, remap[oldId])
    }

    // ---------------------------------------------------------------- 失败分类

    @Test
    fun `JSON密码错_抛WrongPassword`() = runTest {
        val json = facade.export(emptyList(), listOf(sampleLogin()), filePassword)

        val error = runCatching { repository.previewJsonImport(json, "wrong-pw") }.exceptionOrNull()

        assertEquals(VaultConversionException.WrongPassword::class.java, error?.javaClass)
    }

    @Test
    fun `JSON不是合法文件_抛MalformedJson`() = runTest {
        val error = runCatching { repository.previewJsonImport("这不是JSON", filePassword) }.exceptionOrNull()

        assertEquals(VaultConversionException.MalformedJson::class.java, error?.javaClass)
    }

    @Test
    fun `非KDBX字节_抛NotKdbxFile`() = runTest {
        val error = runCatching {
            repository.convertKdbxToJson("not a kdbx file".toByteArray(), outputPassword, exportPassword)
        }.exceptionOrNull()

        assertEquals(VaultConversionException.NotKdbxFile::class.java, error?.javaClass)
    }

    @Test
    fun `KDBX密码错_抛WrongPassword`() = runTest {
        val json = facade.export(emptyList(), listOf(sampleLogin()), filePassword)
        val preview = repository.previewJsonImport(json, filePassword)
        val kdbx = repository.convertJsonToKdbx(preview, outputPassword, "Vaultix")

        val error = runCatching {
            repository.convertKdbxToJson(kdbx.bytes, "wrong-master-pw", exportPassword)
        }.exceptionOrNull()

        assertEquals(VaultConversionException.WrongPassword::class.java, error?.javaClass)
    }

    // ---------------------------------------------------------------- helpers

    private fun sampleLogin() = VaultItem(
        id = UUID.randomUUID().toString(),
        title = "Example",
        username = "bob",
        password = "pw",
        type = VaultItemType.Login,
    )

    private fun passkey() = VaultFido2Credential(
        // credentialId 走 base64url 编解码（KdbxPasskeyCodec），取值须为合法 base64。
        credentialId = "////",
        rpId = "example.com",
        rpName = "Example",
        userName = "alice@example.com",
        userDisplayName = "Alice",
        userHandle = "dXNlci1oYW5kbGU=",
        keyAlgorithm = "ES256",
        creationDate = "2026-10-16T00:00:00.000Z",
        keyType = "public-key",
        keyCurve = "P-256",
        keyValue = "-----BEGIN PRIVATE KEY-----\nMIGHAgEAMBMG\n-----END PRIVATE KEY-----",
    )

    private companion object {
        /** 低迭代 KDF：只用于"手搓未知类型 JSON"那条（测映射，不测 KDF 口径）。 */
        val FAST_KDF = ExportKdf(typeCode = BitwardenExportKdfType.PBKDF2_SHA256, iterations = 1_000)
    }
}
