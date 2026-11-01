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
import io.vaultix.model.VaultCustomField
import io.vaultix.model.VaultFido2Credential
import io.vaultix.model.VaultFolder
import io.vaultix.model.VaultIdentity
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import io.vaultix.model.VaultPasswordHistoryEntry
import io.vaultix.model.VaultReprompt
import io.vaultix.model.VaultSshKey
import io.vaultix.model.VaultUri
import java.util.LinkedHashMap
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
    fun `Card条目_载荷现在完整往返（2026-11-01 起不再是已知边界）`() {
        // ⚠️ 本用例此前断言「类型回退为 Login、载荷丢失」—— 那是 2026-10-31 之前的
        //    已知边界（写侧不写 VaultCard、读侧不产出）。现在两侧都接上了，
        //    银行卡转成 KDBX 再转回来就是**同一张卡**。
        //    守卫仍然有用：`restoredType` 的载荷判断还在，只是不再被触发。
        val card = sampleItem(title = "招行信用卡", username = "", password = "").copy(
            type = VaultItemType.Card,
            card = VaultCard(cardholderName = "ZHANG SAN", number = "6225xxxxxxxx1234"),
        )

        val back = buildAndRead(listOf(card)).items.single()

        assertWithMessage("type").that(back.type).isEqualTo(VaultItemType.Card)
        assertWithMessage("title").that(back.title).isEqualTo("招行信用卡")
        assertWithMessage("卡号").that(back.card?.number).isEqualTo("6225xxxxxxxx1234")
        assertWithMessage("持卡人").that(back.card?.cardholderName).isEqualTo("ZHANG SAN")
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

    // ---------------------------------------------------------------- 无损：载荷与文件夹

    @Test
    fun `银行卡条目往返_六个卡字段全部保真且类型不降级`() {
        // ⚠️ 2026-11-01 之前：卡 / 身份 / SSH 的载荷**两边都没实现** ⇒ 转成 KDBX 后
        //    只剩标题与备注，再转回去变成一条登录。用户看到的是一条"空条目"。
        val card = VaultCard(
            cardholderName = "张伟",
            brand = "Visa",
            number = "4111111111111111",
            expMonth = "7",
            expYear = "2031",
            code = "123",
        )
        val item = sampleItem(title = "招行信用卡").copy(type = VaultItemType.Card, card = card)

        val read = buildAndRead(listOf(item)).items.single()

        assertWithMessage("类型").that(read.type).isEqualTo(VaultItemType.Card)
        assertWithMessage("卡载荷").that(read.card).isEqualTo(card)
    }

    @Test
    fun `卡号与安全码必须受保护写入（不然库被别人打开就泄露）`() {
        val item = sampleItem().copy(
            type = VaultItemType.Card,
            card = VaultCard(number = "4111111111111111", code = "999", brand = "Visa"),
        )

        val bytes = KdbxConversionEngine.build("Vaultix", password, listOf(item)).getOrThrow()
        val fields = openRaw(bytes).content.group.entries.single().fields

        assertWithMessage("卡号").that(fields["${KdbxToolFields.CARD_PREFIX}number"])
            .isInstanceOf(EntryValue.Encrypted::class.java)
        assertWithMessage("安全码").that(fields["${KdbxToolFields.CARD_PREFIX}code"])
            .isInstanceOf(EntryValue.Encrypted::class.java)
        assertWithMessage("品牌不敏感、写明文").that(fields["${KdbxToolFields.CARD_PREFIX}brand"])
            .isInstanceOf(EntryValue.Plain::class.java)
    }

    @Test
    fun `身份信息条目往返_十八个字段全部保真`() {
        val identity = VaultIdentity(
            title = "Mr", firstName = "伟", middleName = "M", lastName = "张",
            address1 = "长青路 1 号", address2 = "5 栋", address3 = "902",
            city = "深圳", state = "广东", postalCode = "518000", country = "中国",
            company = "示例科技", email = "wei@example.com", phone = "+86 13800000000",
            ssn = "110101199001011234", username = "weizhang",
            passportNumber = "E12345678", licenseNumber = "440300199001011234",
        )
        val item = sampleItem(title = "身份").copy(type = VaultItemType.Identity, identity = identity)

        val read = buildAndRead(listOf(item)).items.single()

        assertWithMessage("类型").that(read.type).isEqualTo(VaultItemType.Identity)
        assertWithMessage("身份载荷").that(read.identity).isEqualTo(identity)
    }

    @Test
    fun `SSH 密钥条目往返_私钥公钥指纹全部保真`() {
        val sshKey = VaultSshKey(
            privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nAAA\n-----END OPENSSH PRIVATE KEY-----",
            publicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAA test",
            keyFingerprint = "SHA256:abc123",
        )
        val item = sampleItem(title = "服务器").copy(type = VaultItemType.SshKey, sshKey = sshKey)

        val read = buildAndRead(listOf(item)).items.single()

        assertWithMessage("类型").that(read.type).isEqualTo(VaultItemType.SshKey)
        assertWithMessage("SSH 载荷").that(read.sshKey).isEqualTo(sshKey)
    }

    @Test
    fun `密码历史往返_顺序按数值而非字符串（第 10 条不能排到第 2 条前面）`() {
        val history = (1..12).map { n ->
            VaultPasswordHistoryEntry(password = "old-$n", lastUsedAt = 1_700_000_000_000L + n)
        }
        val item = sampleItem().copy(passwordHistory = history)

        val read = buildAndRead(listOf(item)).items.single()

        assertWithMessage("历史条数").that(read.passwordHistory).hasSize(12)
        assertWithMessage("顺序").that(read.passwordHistory.map { it.password })
            .isEqualTo(history.map { it.password })
        assertWithMessage("时间").that(read.passwordHistory.map { it.lastUsedAt })
            .isEqualTo(history.map { it.lastUsedAt })
    }

    @Test
    fun `密码里含竖线也不被切坏（只切第一个分隔符）`() {
        val item = sampleItem().copy(
            passwordHistory = listOf(VaultPasswordHistoryEntry(password = "a|b|c", lastUsedAt = 42L)),
        )

        val read = buildAndRead(listOf(item)).items.single()

        assertWithMessage("密码").that(read.passwordHistory.single().password).isEqualTo("a|b|c")
        assertWithMessage("时间").that(read.passwordHistory.single().lastUsedAt).isEqualTo(42L)
    }

    @Test
    fun `需要主密码二次验证的标记往返`() {
        val item = sampleItem().copy(reprompt = VaultReprompt.Password)

        val read = buildAndRead(listOf(item)).items.single()

        assertWithMessage("reprompt").that(read.reprompt).isEqualTo(VaultReprompt.Password)
    }

    @Test
    fun `二次验证为默认值时不写字段（不给每条镶恒假键）`() {
        val bytes = KdbxConversionEngine.build("Vaultix", password, listOf(sampleItem())).getOrThrow()

        val fields = openRaw(bytes).content.group.entries.single().fields

        assertThat(fields.keys.map { it.uppercase() }).doesNotContain(KdbxToolFields.REPROMPT.uppercase())
    }

    @Test
    fun `文件夹层级按名字里的斜杠逐级建组，条目落回对应分组`() {
        // 对齐 KeePassXC `BitwardenReader::createGroup`：Bitwarden 的 folder 只有
        // id + name，层级靠名字里的 `/` 表达。
        val folders = listOf(
            VaultFolder(id = "f1", name = "工作"),
            VaultFolder(id = "f2", name = "工作/服务器"),
            VaultFolder(id = "f3", name = "个人"),
        )
        val items = listOf(
            sampleItem(title = "根").copy(folderId = null),
            sampleItem(title = "工作根").copy(folderId = "f1"),
            sampleItem(title = "服务器").copy(folderId = "f2"),
            sampleItem(title = "个人").copy(folderId = "f3"),
        )

        val bytes = KdbxConversionEngine.build("Vaultix", password, items, folders).getOrThrow()
        val read = KdbxConversionEngine.read(bytes, password).getOrThrow()

        assertWithMessage("分组名").that(read.folders.map { it.name })
            .containsExactly("工作", "工作/服务器", "个人")
        // ⚠️ 读侧的 `VaultFolder.name` 是**完整路径**（`KdbxItemMapper` 的约定：
        //    根组路径为空、子组拼成 `父/子`），而 `id` 指向各自的**真实分组**。
        //    这不是 bug：Bitwarden 的 folder 没有 parentId，层级本来就靠名字里的 `/` 表达，
        //    所以带路径的名字反过来正好能被下一次导出原样用上（往返稳定）。
        //    断言归属必须按 **id** 取组名，不能按名字猜。
        val groupNameOf = read.folders.associate { it.id to it.name }
        val byTitle = read.items.associate { it.title to groupNameOf[it.folderId] }
        assertThat(byTitle["根"]).isNull()
        assertThat(byTitle["工作根"]).isEqualTo("工作")
        assertWithMessage("二级目录要落在子组里，不能拍平到一级")
            .that(byTitle["服务器"]).isEqualTo("工作/服务器")
        assertThat(byTitle["个人"]).isEqualTo("个人")
        assertWithMessage("子组的 id 必须与父组不同（否则条目全挤在父组）")
            .that(read.folders.map { it.id }).containsNoDuplicates()
    }

    @Test
    fun `重复的文件夹路径只建一次组（A、B 两个文件夹同名于 A-Sub）`() {
        val folders = listOf(
            VaultFolder(id = "a", name = "A"),
            VaultFolder(id = "ab", name = "A/B"),
            VaultFolder(id = "ac", name = "A/C"),
        )
        val items = listOf(
            sampleItem(title = "x").copy(folderId = "ab"),
            sampleItem(title = "y").copy(folderId = "ac"),
        )

        val bytes = KdbxConversionEngine.build("Vaultix", password, items, folders).getOrThrow()
        val read = KdbxConversionEngine.read(bytes, password).getOrThrow()

        assertWithMessage("A 只应出现一次").that(read.folders.count { it.name == "A" }).isEqualTo(1)
        assertWithMessage("总分组数").that(read.folders).hasSize(3)    }

    @Test
    fun `文件夹名全空时条目退回根组（丢目录名但不能丢条目）`() {
        val folders = listOf(VaultFolder(id = "blank", name = "  "))
        val item = sampleItem(title = "孤儿").copy(folderId = "blank")

        val bytes = KdbxConversionEngine.build("Vaultix", password, listOf(item), folders).getOrThrow()
        val read = KdbxConversionEngine.read(bytes, password).getOrThrow()

        assertWithMessage("条目不能消失").that(read.items.map { it.title }).contains("孤儿")
        assertThat(read.items.single().folderId).isNull()
    }

    @Test
    fun `把银行卡改成登录后旧卡字段必须清掉`() {
        // ⚠️ 不清的话，用户把一条银行卡改成登录条目后，`VPX_CARD_number` 会永远
        //    留在文件里 —— 下次转换回 Bitwarden 又会"复活"成一张卡。
        val cardItem = sampleItem(title = "卡").copy(
            type = VaultItemType.Card,
            card = VaultCard(number = "4111111111111111"),
        )
        val credentials = KdbxCreator.credentialsFor(password, keyFileBytes = null)
        var db = KdbxCreator.blankDatabase("Vaultix", credentials)
        db = KdbxItemWriter.createEntry(db, folderId = null, item = cardItem).database
        val before = db.toMappedContent().items.single()

        val updated = KdbxItemWriter.updateEntry(db, before, before.copy(type = VaultItemType.Login, card = null))

        val fields = updated.database.content.group.entries.single().fields
        assertThat(fields.keys.filter { it.startsWith(KdbxToolFields.CARD_PREFIX, ignoreCase = true) })
            .isEmpty()
        assertThat(updated.database.toMappedContent().items.single().card).isNull()
    }

    // ------------------------------------------- KeePassXC 键名互通（2026-11-01）

    /**
     * KeePassXC 的 `BitwardenReader.cpp` 写的是**无前缀**的 `card_*` / `identity_*`。
     * 用它的库迁过来的用户，卡片/身份必须能被认出来，否则整类条目退化成普通登录。
     */
    @Test
    fun `KeePassXC键名的库_卡能读回来且类型是Card`() {
        val bytes = encodeRawEntry(
            linkedMapOf(
                "Title" to EntryValue.Plain("招行信用卡"),
                "card_cardholderName" to EntryValue.Plain("ZHANG SAN"),
                "card_brand" to EntryValue.Plain("Visa"),
                "card_number" to EntryValue.Encrypted(EncryptedValue.fromString("6225")),
                "card_expMonth" to EntryValue.Plain("12"),
                "card_expYear" to EntryValue.Plain("2030"),
                "card_code" to EntryValue.Encrypted(EncryptedValue.fromString("123")),
            ),
        )

        val item = KdbxConversionEngine.read(bytes = bytes, password = password).getOrThrow().items.single()

        assertWithMessage("类型").that(item.type).isEqualTo(VaultItemType.Card)
        val card = item.card
        assertWithMessage("card 载荷").that(card).isNotNull()
        assertThat(card!!.cardholderName).isEqualTo("ZHANG SAN")
        assertThat(card.brand).isEqualTo("Visa")
        assertThat(card.number).isEqualTo("6225")
        assertThat(card.code).isEqualTo("123")
    }

    /** 同上，身份：KeePassXC 拆的是 `identity_email` / `identity_ssn` 这套。 */
    @Test
    fun `KeePassXC键名的库_身份能读回来`() {
        val bytes = encodeRawEntry(
            linkedMapOf(
                "Title" to EntryValue.Plain("我的身份"),
                "identity_firstName" to EntryValue.Plain("三"),
                "identity_lastName" to EntryValue.Plain("张"),
                "identity_email" to EntryValue.Plain("zhang@example.com"),
                "identity_ssn" to EntryValue.Encrypted(EncryptedValue.fromString("123-45-6789")),
            ),
        )

        val item = KdbxConversionEngine.read(bytes = bytes, password = password).getOrThrow().items.single()

        assertThat(item.type).isEqualTo(VaultItemType.Identity)
        val identity = item.identity
        assertThat(identity).isNotNull()
        assertThat(identity!!.firstName).isEqualTo("三")
        assertThat(identity.lastName).isEqualTo("张")
        assertThat(identity.email).isEqualTo("zhang@example.com")
        assertThat(identity.ssn).isEqualTo("123-45-6789")
    }

    /**
     * KeePassXC 把第 2 条起的网址写成 `KP2A_URL_n`（`AdditionalUrlAttribute` 常量）。
     *
     * ⚠️ 标准字段的键名是 **`URL` 全大写**（`BasicField.Url`，已 `javap` 核实），
     *   不是 `Url`。kotpass 的 `EntryFields.get` 就是普通 `Map.get`，
     *   写错大小写 ⇒ 那条网址**静默消失**，而其余字段一切正常。
     */
    @Test
    fun `KeePassXC的KP2A额外网址能被读成多条URI`() {
        val bytes = encodeRawEntry(
            linkedMapOf(
                "Title" to EntryValue.Plain("多网址"),
                "UserName" to EntryValue.Plain("alice"),
                "URL" to EntryValue.Plain("https://a.example.com"),
                "KP2A_URL_1" to EntryValue.Plain("https://b.example.com"),
                "KP2A_URL_2" to EntryValue.Plain("https://c.example.com"),
            ),
        )

        val item = KdbxConversionEngine.read(bytes = bytes, password = password).getOrThrow().items.single()

        assertThat(item.uris.map { it.uri }).containsExactly(
            "https://a.example.com",
            "https://b.example.com",
            "https://c.example.com",
        ).inOrder()
    }

    /**
     * KeePassXC 额外写的 `identity_address` 多行块要摊回 7 个地址字段，
     * 并且**不能**剩下的漏到自定义字段区（用户在详情页会看到一行莫名其妙的原文）。
     */
    @Test
    fun `KeePassXC的identity_address合并块能摊回地址字段`() {
        val bytes = encodeRawEntry(
            linkedMapOf(
                "Title" to EntryValue.Plain("地址身份"),
                "identity_firstName" to EntryValue.Plain("三"),
                "identity_lastName" to EntryValue.Plain("张"),
                // 行序对称 BitwardenReader.cpp：3 行地址 + 「城市, 州 邮编」+ 国家。
                "identity_address" to EntryValue.Plain(
                    "中山路 1 号\n2 单元\n3 楼\n北京, Beijing 100000\n中国",
                ),
            ),
        )

        val item = KdbxConversionEngine.read(bytes = bytes, password = password).getOrThrow().items.single()

        assertThat(item.type).isEqualTo(VaultItemType.Identity)
        val identity = requireNotNull(item.identity)
        assertThat(identity.address1).isEqualTo("中山路 1 号")
        assertThat(identity.address2).isEqualTo("2 单元")
        assertThat(identity.address3).isEqualTo("3 楼")
        assertThat(identity.city).isEqualTo("北京")
        assertThat(identity.state).isEqualTo("Beijing")
        assertThat(identity.postalCode).isEqualTo("100000")
        assertThat(identity.country).isEqualTo("中国")
        assertWithMessage("合并块不该漏进自定义字段")
            .that(item.customFields.map { it.name })
            .doesNotContain("identity_address")
    }

    /**
     * 身份条目改成登录后，**KeePassXC 写进来的合并键**也必须一起清掉。
     *
     * ⚠️ 专测这一类：合并键（`identity_address` / `identity_name`）本应用从不写，
     *   只可能由别的工具写进来。若清理表只覆盖 18 个分段键，它会留在原地，
     *   而读侧又把它摊成身份 ⇒ 用户改成登录，读回来**类型又变回身份**。
     */
    @Test
    fun `身份改成登录后KeePassXC的合并键也必须清掉`() {
        val identityItem = sampleItem(title = "身份").copy(
            type = VaultItemType.Identity,
            identity = VaultIdentity(firstName = "三", lastName = "张"),
        )
        val credentials = KdbxCreator.credentialsFor(password, keyFileBytes = null)
        var db = KdbxCreator.blankDatabase("Vaultix", credentials)
        db = KdbxItemWriter.createEntry(db, folderId = null, item = identityItem).database

        // 模拟"这个库曾被 KeePassXC 写过"：手工塞进两个合并键。
        val entry = db.content.group.entries.single()
        val patched = entry.copy(
            fields = entry.fields +
                ("identity_address" to EntryValue.Plain("中山路 1 号\n北京, Beijing 100000\n中国")),
        )
        db = db.modifyParentGroup { copy(entries = listOf(patched)) }
        val before = db.toMappedContent().items.single()
        assertWithMessage("前置条件：合并键已被读成身份地址")
            .that(before.identity?.address1).isEqualTo("中山路 1 号")

        val updated = KdbxItemWriter.updateEntry(
            db,
            before,
            before.copy(type = VaultItemType.Login, identity = null),
        )

        val keys = updated.database.content.group.entries.single().fields.keys
            .map { it.lowercase() }
        assertWithMessage("合并键不该残留").that(keys).doesNotContain("identity_address")
        assertWithMessage("读回来不能再复活成身份")
            .that(updated.database.toMappedContent().items.single().identity)
            .isNull()
    }

    /**
     * 分段姓名全空时用 `identity_name` 兜底；**分段有值时一律以分段为准**
     * （合并值是空格拼接的，边界不可逆，拿它覆盖等于用有损盖无损）。
     */
    @Test
    fun `KeePassXC的identity_name只在分段姓名全空时兜底`() {
        val onlyMerged = encodeRawEntry(
            linkedMapOf(
                "Title" to EntryValue.Plain("合并姓名"),
                "identity_name" to EntryValue.Plain("三 张"),
                "identity_email" to EntryValue.Plain("a@example.com"),
            ),
        )
        val fallback = KdbxConversionEngine.read(bytes = onlyMerged, password = password)
            .getOrThrow().items.single().identity
        assertThat(fallback).isNotNull()
        assertThat(fallback!!.firstName).isEqualTo("三")
        assertThat(fallback.lastName).isEqualTo("张")

        val both = encodeRawEntry(
            linkedMapOf(
                "Title" to EntryValue.Plain("分段优先"),
                "identity_name" to EntryValue.Plain("错的 名字"),
                "identity_firstName" to EntryValue.Plain("三"),
                "identity_lastName" to EntryValue.Plain("张"),
            ),
        )
        val segmented = KdbxConversionEngine.read(bytes = both, password = password)
            .getOrThrow().items.single().identity
        assertThat(segmented).isNotNull()
        assertWithMessage("分段优先").that(segmented!!.firstName).isEqualTo("三")
        assertThat(segmented.lastName).isEqualTo("张")
    }

    /**
     * 写侧必须产出 `card_*`（KeePassXC 键名），而不是之前的 `VPX_CARD_*` ——
     * 否则用 KeePassXC 打开本应用转出的库，字段名与它自己导入的对不上。
     */
    @Test
    fun `写侧产出的是KeePassXC键名而不是VPX前缀`() {
        val card = sampleItem(title = "卡").copy(
            type = VaultItemType.Card,
            card = VaultCard(cardholderName = "ZHANG SAN", number = "6225", code = "123"),
        )

        val bytes = KdbxConversionEngine.build("Vaultix", password, listOf(card)).getOrThrow()
        val keys = openRaw(bytes).content.group.entries.single().fields.keys.map { it.lowercase() }

        assertThat(keys).contains("card_number")
        assertThat(keys).contains("card_code")
        assertThat(keys).containsNoneIn(listOf("vpx_card_number", "vpx_card_code", "vpx_card_brand"))
    }

    /**
     * `card_` 没有私有前缀 ⇒ 用户完全可能自建一个 `card_我的备注`。
     * 它：① 不能被当成载荷（否则详情页看不见），② 不能被写侧的清理顺手删掉。
     */
    @Test
    fun `用户的card前缀自定义字段既不当载荷也不被删除`() {
        val item = sampleItem(title = "带自定义字段的卡").copy(
            type = VaultItemType.Card,
            card = VaultCard(number = "6225"),
            customFields = listOf(VaultCustomField(name = "card_我的备注", value = "别删我")),
        )

        val read = buildAndRead(listOf(item)).items.single()

        val custom = read.customFields.firstOrNull { it.name == "card_我的备注" }
        assertWithMessage("用户字段必须仍在自定义字段里").that(custom).isNotNull()
        assertWithMessage("值不能被改").that(custom!!.value).isEqualTo("别删我")
        assertWithMessage("载荷只认已知的 6 个键").that(read.card?.number).isEqualTo("6225")
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

    /** 直接造一条「字段随手给」的条目，模拟第三方工具（如 KeePassXC）写出的库。 */
    /**
     * 直接按「原始键名」造一条条目再编码。
     *
     * ⚠️ 五个标准字段的键名是 `Title` / `UserName` / `Password` / **`URL`** / `Notes`
     *   （`BasicField`，已 `javap` 核实）。`URL` 是**全大写** —— 写成 `Url` 不会报错，
     *   只会让那条网址读不到。这正是本组「互通测试」要防的那类静默丢失。
     */
    private fun encodeRawEntry(fields: LinkedHashMap<String, EntryValue>): ByteArray {
        val credentials = Credentials.from(EncryptedValue.fromString(password))
        val created = KdbxCreator.blankDatabase("Raw", credentials)
        val entry = app.keemobile.kotpass.models.Entry(
            uuid = UUID.randomUUID(),
            fields = app.keemobile.kotpass.models.EntryFields(fields),
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
