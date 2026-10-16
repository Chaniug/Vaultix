/*
 * Vaultix — data:kdbx（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 转换引擎（W5 · JSON⇄KDBX 互转）单元测试。
 *
 * ## 这个文件防的是什么
 *
 * 转换的坏味道**全是静默的**：条目少了不报错、URI 丢一半不报错、通行密钥私钥
 * 变成明文也不报错 —— 用户要到用的时候（验证码算错、签名失败）才发现。
 * 所以每条用例都对应一个具体的"会怎么坏"：
 *
 * | 用例 | 不这么做会怎样 |
 * |---|---|
 * | 标准字段 / URI / TOTP / 通行密钥往返 | 转一次就丢一块，且无任何提示 |
 * | 私钥字段必须 `Encrypted` | KDBX 被别人打开即泄露私钥（施工单风险表） |
 * | `VPX_BW_ID` 还原原 id | 转两次得到两个新 id（幂等破） |
 * | `VPX_BW_TYPE` 还原类型 | 安全笔记往返后变登录（#169 翻版） |
 * | Card 载荷守卫 | 还原类型后 `requireNotNull(payload)` 抛异常，整次转换崩 |
 * | 无旁路字段时 id 是裸 UUID | 带 `kdbx-entry:` 前缀的 id 让官方导入器直接报错 |
 * | 平铺 | 分组映射错乱（条目进错组） |
 * | 输出密码可重开产物 | "自己转的文件自己打不开" |
 *
 * 纯 JVM（kotpass 可在 JVM 跑），不需要 Android 运行时。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.kdbx

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.getEntry
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.vaultix.common.OtpUriParser
import io.vaultix.model.VaultCard
import io.vaultix.model.VaultFido2Credential
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import io.vaultix.model.VaultUri
import java.util.UUID
import org.junit.Test

class KdbxConversionTest {

    private val password = "output-master-pw"

    // ---------------------------------------------------------------- 正向：build

    @Test
    fun `登录条目_平铺写入后读回_标准字段保真`() {
        val item = sampleItem(title = "GitHub", username = "alice", password = "s3cret")

        val read = buildAndRead(listOf(item))

        assertThat(read.items).hasSize(1)
        val back = read.items.single()
        assertWithMessage("title").that(back.title).isEqualTo("GitHub")
        assertWithMessage("username").that(back.username).isEqualTo("alice")
        assertWithMessage("password").that(back.password).isEqualTo("s3cret")
    }

    @Test
    fun `三条URI_往返后仍是三条且顺序不变`() {
        val item = sampleItem(title = "Multi").copy(
            uris = listOf(
                VaultUri(uri = "https://one.example.com"),
                VaultUri(uri = "https://two.example.com"),
                VaultUri(uri = "https://three.example.com"),
            ),
        )

        val back = buildAndRead(listOf(item)).items.single()

        // KDBX 的 `Url` 只装一条，其余走 `VPX_URL_n`（W4）—— 少一条就是静默丢数据。
        assertThat(back.uris.map { it.uri }).containsExactly(
            "https://one.example.com",
            "https://two.example.com",
            "https://three.example.com",
        ).inOrder()
    }

    @Test
    fun `TOTP_往返后语义不变`() {
        val uri = "otpauth://totp/GitHub:alice?secret=JBSWY3DPEHPK3PXP&algorithm=SHA256&digits=6&period=30"
        val before = requireNotNull(OtpUriParser.parse(uri))
        val item = sampleItem(title = "GitHub").copy(totp = uri)

        val back = buildAndRead(listOf(item)).items.single()

        val after = requireNotNull(OtpUriParser.parse(back.totp!!))
        assertWithMessage("secret").that(after.secret).isEqualTo(before.secret)
        assertWithMessage("digits").that(after.digits).isEqualTo(before.digits)
        assertWithMessage("period").that(after.period).isEqualTo(before.period)
        assertWithMessage("algorithm").that(after.algorithm).isEqualTo(before.algorithm)
    }

    @Test
    fun `通行密钥_往返保真且私钥字段受保护`() {
        val credential = VaultFido2Credential(
            // ⚠️ credentialId 在 KDBX 侧走 base64url 编解码（KdbxPasskeyCodec），
            //   取值必须是合法 base64 —— 与 KdbxPasskeyWritebackTest 同款常量。
            credentialId = "////",
            rpId = "example.com",
            rpName = "Example",
            userName = "alice",
            userDisplayName = "Alice",
            userHandle = "user-handle-1",
            keyAlgorithm = "ES256",
            creationDate = "2026-01-01T00:00:00Z",
            keyType = "public-key",
            keyCurve = "P-256",
            keyValue = "-----BEGIN PRIVATE KEY-----\nMHcCAQEE\n-----END PRIVATE KEY-----",
            counter = 7,
            discoverable = true,
        )
        val item = sampleItem(title = "Passkey Login").copy(fido2Credentials = listOf(credential))

        val read = buildAndRead(listOf(item))

        val back = read.items.single()
        assertWithMessage("凭证数").that(back.fido2Credentials).hasSize(1)
        val restored = back.fido2Credentials.single()
        assertWithMessage("rpId").that(restored.rpId).isEqualTo("example.com")
        assertWithMessage("credentialId").that(restored.credentialId).isEqualTo("////")
        assertWithMessage("userName").that(restored.userName).isEqualTo("alice")
        // ⚠️ counter 是**已知边界**，不是本转换器的缺陷：KeePassDX 的 KPEX_ 字段集
        //   没有 counter 位（KdbxPasskeyCodec.toCredential 恒填 0），既有写回测试
        //   同样不断言它。要补得先给码本加字段，那是独立立项，别顺手改。
        assertWithMessage("counter（已知边界：KPEX_ 无此字段）").that(restored.counter).isEqualTo(0)
        assertWithMessage("私钥 PEM").that(restored.keyValue).isEqualTo(credential.keyValue)

        // ★ 私钥必须落在**受保护**字段上（Protected="True" ⇒ EntryValue.Encrypted）：
        //   写明文等于"KDBX 被别人打开即泄露私钥"（施工单 §4 风险表）。
        //   read 回来两种取值都能解密，所以必须回到**原始字节**断表示形态。
        val bytes = KdbxConversionEngine.build("Vaultix", password, listOf(item)).getOrThrow()
        val raw = openRaw(bytes)
            .getEntry { it.fields[KdbxPasskeyCodec.FIELD_PRIVATE_KEY] != null }
            ?.second
            ?: error("私钥字段未找到")
        assertWithMessage("私钥字段表示").that(raw.fields[KdbxPasskeyCodec.FIELD_PRIVATE_KEY])
            .isInstanceOf(EntryValue.Encrypted::class.java)
    }

    @Test
    fun `安全笔记_类型与标记_往返保真`() {
        val note = sampleItem(title = "Wi-Fi 密码", username = "", password = "").copy(
            type = VaultItemType.SecureNote,
            notes = "SSID: home-5g",
        )

        val back = buildAndRead(listOf(note)).items.single()

        // 有 VPX_SECURE_NOTE 标记 ⇒ marker 优先（A4）；无标记会回落启发式，
        // "空备注的安全笔记"会被静默翻成登录 —— 这里钉死标记在转换里不丢。
        assertWithMessage("type").that(back.type).isEqualTo(VaultItemType.SecureNote)
        assertWithMessage("notes").that(back.notes).isEqualTo("SSID: home-5g")
    }

    // ---------------------------------------------------------------- 旁路字段（VPX_BW_*）

    @Test
    fun `VPX_BW_ID_读回时还原为原始id`() {
        val bwId = UUID.randomUUID().toString()
        val item = sampleItem(title = "GitHub").copy(id = bwId)

        val back = buildAndRead(listOf(item)).items.single()

        // 保往返幂等：转两次不会得到两个新 id（施工单风险表"转换不幂等"）。
        assertWithMessage("id").that(back.id).isEqualTo(bwId)
    }

    @Test
    fun `VPX_BW_TYPE_读回时还原类型`() {
        val note = sampleItem(title = "Note", username = "", password = "").copy(
            type = VaultItemType.SecureNote,
        )

        val back = buildAndRead(listOf(note)).items.single()

        assertWithMessage("type").that(back.type).isEqualTo(VaultItemType.SecureNote)
    }

    @Test
    fun `Card条目_载荷守卫_读回不还原类型且不抛异常`() {
        val card = sampleItem(title = "招行信用卡", username = "", password = "").copy(
            type = VaultItemType.Card,
            card = VaultCard(cardholderName = "ZHANG SAN", number = "6225xxxxxxxx1234"),
        )

        val back = buildAndRead(listOf(card)).items.single()

        // ⚠️ 这是**已知边界**（不是 bug）：KDBX 无 Card 载荷承载（写侧不写、读侧不产出），
        //   照字面还原类型会让导出侧 requireNotNull(payload) 直接抛异常、整次转换崩在
        //   最后一个条目上。守卫 ⇒ 类型回退为 Login，标题与备注仍在。
        //   预览计数（typeDegradedCount）负责提前告知用户，不静默。
        assertWithMessage("type").that(back.type).isEqualTo(VaultItemType.Login)
        assertWithMessage("title").that(back.title).isEqualTo("招行信用卡")
    }

    @Test
    fun `第三方条目_无旁路字段_读回id是裸UUID形态`() {
        // 用户用 KeePassXC 手建的库：没有 VPX_BW_ID / VPX_BW_TYPE。
        // id 必须退回**条目 uuid 的裸形态**：Bitwarden 官方导入器对非 UUID id
        // 在反序列化阶段直接报错（BitwardenExportCipher.id 的注释）。
        val entryUuid = UUID.randomUUID()
        val bytes = encodeEntryDirectly(entryUuid, title = " KeePassXC 手建 ", userName = "bob")

        val read = KdbxConversionEngine.read(bytes, password).getOrThrow()

        assertThat(read.items).hasSize(1)
        val back = read.items.single()
        assertWithMessage("id 形态").that(back.id).isEqualTo(entryUuid.toString())
        // 无类型标记 ⇒ 保持读路径推导（有用户名 ⇒ Login）。
        assertWithMessage("type").that(back.type).isEqualTo(VaultItemType.Login)
        assertWithMessage("userName").that(back.username).isEqualTo("bob")
    }

    // ---------------------------------------------------------------- 结构与自检

    @Test
    fun `平铺_读回folders不含用户分组`() {
        val items = (1..3).map { sampleItem(title = "Item $it") }

        val read = buildAndRead(items)

        // 施工单 §2.4 用户拍板"先平铺"：BW 文件夹层级不映射为 KDBX 分组。
        // blankDatabase 自带的回收站组不进 folders（toMappedContent 排除）。
        assertWithMessage("folders").that(read.folders).isEmpty()
        assertWithMessage("items").that(read.items).hasSize(3)
    }

    @Test
    fun `空条目列表_产出可用输出密码打开的空库`() {
        val read = buildAndRead(emptyList())

        assertWithMessage("items").that(read.items).isEmpty()
    }

    @Test
    fun `产物必须能用输出密码重新打开`() {
        // "自己转的文件自己打不开"是最伤人的一类 bug（凭据写错即如此）。
        val bytes = KdbxConversionEngine.build("Vaultix", password, listOf(sampleItem())).getOrThrow()

        val reopened = KdbxConversionEngine.read(bytes, password).getOrThrow()

        assertWithMessage(" reopened items").that(reopened.items).hasSize(1)
    }

    @Test
    fun `密码错误_打开失败且归因为凭据不对`() {
        val bytes = KdbxConversionEngine.build("Vaultix", password, listOf(sampleItem())).getOrThrow()

        val result = KdbxConversionEngine.read(bytes, "wrong-password")

        assertThat(result.isFailure).isTrue()
        val error = result.exceptionOrNull()
        assertThat(error).isInstanceOf(KdbxFailure::class.java)
        assertThat((error as KdbxFailure).error).isInstanceOf(KdbxOpenError.InvalidCredentials::class.java)
    }

    @Test
    fun `带分组的库_读回保留分组与条目归属`() {
        // 反向转换的分组路径：用户用 KeePassXC 建的库带分组 ⇒ 读回必须保留
        // 分组与条目归属（Kdbx→JSON 时它们变成 BW folders）。
        // folderId 在这里仍是 `kdbx-group:<uuid>` 前缀形态 —— 换成合法 UUID 是
        // data:repository 的职责（BW 官方导入器的要求），不在本模块。
        val groupUuid = UUID.randomUUID()
        val credentials = Credentials.from(EncryptedValue.fromString(password))
        val created = KdbxCreator.blankDatabase("WithGroups", credentials)
        val entry = Entry(
            uuid = UUID.randomUUID(),
            fields = EntryFields(
                linkedMapOf(
                    "Title" to EntryValue.Plain("In Group"),
                    "UserName" to EntryValue.Plain("carol"),
                ),
            ),
        )
        val database = created.modifyParentGroup {
            copy(groups = groups + Group(name = "工作", uuid = groupUuid, entries = listOf(entry)))
        }
        val bytes = KdbxRoundTrip.verify(database, credentials).bytes

        val read = KdbxConversionEngine.read(bytes, password).getOrThrow()

        assertWithMessage("folders").that(read.folders).hasSize(1)
        assertWithMessage("分组名").that(read.folders.single().name).isEqualTo("工作")
        val item = read.items.single()
        assertWithMessage("条目归属").that(item.folderId).isEqualTo(folderIdOf(groupUuid))
        assertWithMessage("userName").that(item.username).isEqualTo("carol")
    }

    // ---------------------------------------------------------------- helpers

    private fun buildAndRead(items: List<VaultItem>): KdbxConversionContent {
        val bytes = KdbxConversionEngine.build(name = "Vaultix", password = password, items = items)
            .getOrThrow()
        return KdbxConversionEngine.read(bytes = bytes, password = password).getOrThrow()
    }

    private fun openRaw(bytes: ByteArray) =
        KdbxOpener.open(bytes = bytes, password = password, keyFileBytes = null).getOrThrow().database

    /** 绕过 [KdbxConversionEngine.build] 直接造一条第三方条目（模拟别的工具写的库）。 */
    private fun encodeEntryDirectly(uuid: UUID, title: String, userName: String): ByteArray {
        val credentials = Credentials.from(EncryptedValue.fromString(password))
        val created = KdbxCreator.blankDatabase("ThirdParty", credentials)
        val entry = app.keemobile.kotpass.models.Entry(
            uuid = uuid,
            fields = app.keemobile.kotpass.models.EntryFields(
                linkedMapOf(
                    "Title" to EntryValue.Plain(title),
                    "UserName" to EntryValue.Plain(userName),
                    "Password" to EntryValue.Encrypted(EncryptedValue.fromString("pw")),
                ),
            ),
        )
        val database = created.modifyParentGroup { copy(entries = entries + entry) }
        return KdbxRoundTrip.verify(database, credentials).bytes
    }

    private fun sampleItem(
        title: String = "Sample",
        username: String = "alice",
        password: String = "pw",
    ): VaultItem = VaultItem(
        // 走 kdbx-entry: 前缀（正常读路径的 id 形态）——还原逻辑要能把它换成裸 UUID。
        id = "kdbx-entry:${UUID.randomUUID()}",
        title = title,
        username = username,
        password = password,
        type = VaultItemType.Login,
    )

}
