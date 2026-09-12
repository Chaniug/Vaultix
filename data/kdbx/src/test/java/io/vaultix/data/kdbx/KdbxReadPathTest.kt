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
import app.keemobile.kotpass.database.encode
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.Meta
import com.google.common.truth.Truth.assertThat
import io.vaultix.common.OtpUriParser
import io.vaultix.model.CustomFieldType
import io.vaultix.model.VaultItemType
import java.io.ByteArrayOutputStream
import java.util.UUID
import org.junit.Test

/**
 * KDBX **读路径**端到端回归（M2 阶段 A 的安全网）。
 *
 * 覆盖链路：kotpass 写出的真实 KDBX 字节 → [KdbxOpener.open]（格式识别 + 候选凭据 + 解密）
 * → [KdbxMappedContent]（分组/条目/受保护字段/TOTP/自定义字段）。
 *
 * ⚠️ 这是「加密路径」的测试，宁可多断言也不要「跑通就算」：密码管理器读错一个字段，
 * 用户不会立刻发现，但后果严重（Docs/09）。
 */
class KdbxReadPathTest {

    @Test
    fun `opens a real kdbx and maps title username password url notes`() {
        val bytes = buildDatabase()

        val session = KdbxOpener.open(bytes, PASSWORD, keyFileBytes = null).getOrThrow()
        val item = session.content.items.single { it.title == "GitHub" }

        assertThat(item.username).isEqualTo("alice")
        assertThat(item.password).isEqualTo("s3cret-pw")
        assertThat(item.notes).isEqualTo("note-body")
        assertThat(item.uris.map { it.uri }).containsExactly("https://github.com")
        assertThat(item.type).isEqualTo(VaultItemType.Login)
    }

    @Test
    fun `maps protected custom field as hidden and plain field as text`() {
        val session = KdbxOpener.open(buildDatabase(), PASSWORD, null).getOrThrow()
        val item = session.content.items.single { it.title == "GitHub" }

        val recovery = item.customFields.single { it.name == RECOVERY_FIELD }
        assertThat(recovery.value).isEqualTo("code-1234")
        assertThat(recovery.type).isEqualTo(CustomFieldType.Hidden)

        val plain = item.customFields.single { it.name == PLAIN_FIELD }
        assertThat(plain.value).isEqualTo("plain-value")
        assertThat(plain.type).isEqualTo(CustomFieldType.Text)
    }

    @Test
    fun `maps keePass totp fields into an otpauth uri`() {
        val session = KdbxOpener.open(buildDatabase(), PASSWORD, null).getOrThrow()
        val item = session.content.items.single { it.title == "GitHub" }

        // TOTP 字段不应当再出现在自定义字段里（已升格为 otpauth URI）。
        assertThat(item.customFields.map { it.name }).doesNotContain("TimeOtp-Secret-Base32")
        assertThat(item.totp).isNotNull()
        assertThat(item.totp).contains("otpauth://totp/")
        assertThat(item.totp).contains("secret=$TOTP_SECRET")
        // 默认值（period=30 / digits=6 / SHA1）不在 URI 里出现：OtpUriParser.buildUri
        // 只写非默认段，读回来仍是 30 —— 断言读回值而不是字面串，更贴近"能不能算出码"。
        assertThat(OtpUriParser.parse(item.totp!!)?.period).isEqualTo(30)
        assertThat(OtpUriParser.parse(item.totp!!)?.digits).isEqualTo(6)
    }

    @Test
    fun `maps group tree into folders and assigns entries to them`() {
        val session = KdbxOpener.open(buildDatabase(), PASSWORD, null).getOrThrow()

        val work = session.content.folders.single { it.name == WORK_GROUP }
        val nested = session.content.folders.single { it.name == "$WORK_GROUP/$NESTED_GROUP" }
        assertThat(work.id).startsWith("kdbx-group:")
        // 嵌套分组用「父/子」路径命名（Bitwarden 文件夹是扁平的，路径最不丢信息）。
        assertThat(nested.name).isEqualTo("$WORK_GROUP/$NESTED_GROUP")

        val inGroup = session.content.items.single { it.title == "InGroup" }
        assertThat(inGroup.folderId).isEqualTo(work.id)
    }

    @Test
    fun `entry without username and password becomes a secure note`() {
        val session = KdbxOpener.open(buildDatabase(), PASSWORD, null).getOrThrow()
        val note = session.content.items.single { it.title == "JustANote" }

        assertThat(note.type).isEqualTo(VaultItemType.SecureNote)
        assertThat(note.notes).isEqualTo("only notes here")
    }

    @Test
    fun `recycle bin entries are excluded from items and counted`() {
        val session = KdbxOpener.open(buildDatabase(), PASSWORD, null).getOrThrow()

        assertThat(session.content.items.map { it.title }).doesNotContain("Deleted")
        assertThat(session.content.recycleBinCount).isEqualTo(1)
    }

    @Test
    fun `wrong password fails with invalid credentials and lists attempts`() {
        val result = KdbxOpener.open(buildDatabase(), "wrong-password", null)

        assertThat(result.isFailure).isTrue()
        val error = (result.exceptionOrNull() as KdbxFailure).error
        assertThat(error).isInstanceOf(KdbxOpenError.InvalidCredentials::class.java)
        assertThat((error as KdbxOpenError.InvalidCredentials).attempted).isNotEmpty()
    }

    @Test
    fun `non kdbx bytes are rejected as not a kdbx file`() {
        val result = KdbxOpener.open("this is not a keepass database".toByteArray(), PASSWORD, null)

        val error = (result.exceptionOrNull() as KdbxFailure).error
        assertThat(error).isEqualTo(KdbxOpenError.NotKdbxFile)
    }

    @Test
    fun `format inspector reports kdbx version from header`() {
        val bytes = buildDatabase()

        val format = inspectKdbxFormat(bytes)
        assertThat(format).isInstanceOf(KdbxFormat.Supported::class.java)
        assertThat((format as KdbxFormat.Supported).major).isAtLeast(4)
        assertThat(inspectKdbxFormat(byteArrayOf(1, 2, 3))).isEqualTo(KdbxFormat.NotKdbx)
    }

    @Test
    fun `keyfile variants include raw xml and hex forms`() {
        val raw = ByteArray(32) { it.toByte() }
        val withPassword = buildCredentialCandidates("pw", raw)
        // 有密码时：每个 keyfile 形态生成一条「密码 + keyfile」候选（raw 与 sha256(raw) 都可能命中）。
        assertThat(withPassword).isNotEmpty()
        assertThat(withPassword.all { it.label.endsWith("password+key") }).isTrue()
        // 空密码 + keyfile：key-only 与 empty-password+key 两种组合都要有。
        val labels = buildCredentialCandidates("", raw).map { it.label }
        assertThat(labels.any { it.endsWith("key-only") }).isTrue()
        assertThat(labels.any { it.endsWith("empty-password+key") }).isTrue()
    }
}

// ---- 测试用的库构造 ----

private const val PASSWORD = "master-pw"
private const val WORK_GROUP = "Work"
private const val NESTED_GROUP = "Dev"
private const val RECOVERY_FIELD = "Recovery"
private const val PLAIN_FIELD = "PlainField"
private const val TOTP_SECRET = "JBSWY3DPEHPK3PXP"

/** 造一个含分组 / 受保护字段 / TOTP / 自定义字段 / 回收站的真实 KDBX 4.x 字节。 */
private fun buildDatabase(): ByteArray {
    val credentials = Credentials.from(EncryptedValue.fromString(PASSWORD))
    val created = KeePassDatabase.Ver4x.create(
        rootName = "VaultixTest",
        meta = Meta(),
        credentials = credentials,
    )
    val root = created.content.group

    val github = Entry(
        uuid = UUID.randomUUID(),
        fields = EntryFields.createDefault() + listOf(
            BasicField.Title() to EntryValue.Plain("GitHub"),
            BasicField.UserName() to EntryValue.Plain("alice"),
            BasicField.Password() to EntryValue.Encrypted(EncryptedValue.fromString("s3cret-pw")),
            BasicField.Url() to EntryValue.Plain("https://github.com"),
            BasicField.Notes() to EntryValue.Plain("note-body"),
            "TimeOtp-Secret-Base32" to EntryValue.Plain(TOTP_SECRET),
            "TimeOtp-Period" to EntryValue.Plain("30"),
            RECOVERY_FIELD to EntryValue.Encrypted(EncryptedValue.fromString("code-1234")),
            PLAIN_FIELD to EntryValue.Plain("plain-value"),
        ),
    )
    val inGroup = Entry(
        uuid = UUID.randomUUID(),
        fields = EntryFields.createDefault() + listOf(
            BasicField.Title() to EntryValue.Plain("InGroup"),
            BasicField.UserName() to EntryValue.Plain("bob"),
            BasicField.Password() to EntryValue.Encrypted(EncryptedValue.fromString("pw2")),
        ),
    )
    val note = Entry(
        uuid = UUID.randomUUID(),
        fields = EntryFields.createDefault() + listOf(
            BasicField.Title() to EntryValue.Plain("JustANote"),
            BasicField.Notes() to EntryValue.Plain("only notes here"),
        ),
    )
    val deleted = Entry(
        uuid = UUID.randomUUID(),
        fields = EntryFields.createDefault() + listOf(
            BasicField.Title() to EntryValue.Plain("Deleted"),
            BasicField.Password() to EntryValue.Encrypted(EncryptedValue.fromString("gone")),
        ),
    )

    val recycleBin = Group(uuid = UUID.randomUUID(), name = "RecycleBin", entries = listOf(deleted))
    val nested = Group(uuid = UUID.randomUUID(), name = NESTED_GROUP)
    val work = Group(
        uuid = UUID.randomUUID(),
        name = WORK_GROUP,
        groups = listOf(nested),
        entries = listOf(inGroup),
    )
    val newRoot = root.copy(
        entries = listOf(github, note),
        groups = listOf(work, recycleBin),
    )
    val withRecycleBin = created.copy(
        content = created.content.copy(
            meta = created.content.meta.copy(
                recycleBinEnabled = true,
                recycleBinUuid = recycleBin.uuid,
            ),
            group = newRoot,
        ),
    )

    return ByteArrayOutputStream().use { output ->
        withRecycleBin.encode(output)
        output.toByteArray()
    }
}
