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
import app.keemobile.kotpass.database.getEntry
import app.keemobile.kotpass.database.getGroupBy
import app.keemobile.kotpass.database.modifiers.modifyGroup
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.models.CustomDataValue
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.Meta
import com.google.common.truth.Truth.assertThat
import io.vaultix.model.CustomFieldType
import io.vaultix.model.VaultCustomField
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import java.util.UUID
import org.junit.Test

/**
 * KDBX **条目写回**（阶段 B · 批次 W1）的安全网。
 *
 * ## 每条用例对应一个具体的"会静默坏事"
 *
 * 写坏一个密码库**不会报错** —— 它要么当场抛（少见），要么安静地把数据变样。
 * 所以这里不写"跑通就算"的用例，每条都写清"不这么做会怎样"：
 *
 * | 用例 | 不这么做会怎样 |
 * |---|---|
 * | 改 A 不影响 B | 一次编辑顺手把别的条目改花（`copy` 用错范围） |
 * | 历史留的是**改前**那版 | 用户以为能回滚，点开历史发现存的是改后值 |
 * | 未改的 TOTP 不重写 | 用户的 Hex 存法被改成 otpauth，其它工具展示随之变 |
 * | `KPEX_*` 不被清 | **通行密钥静默失效**（一丢就再也签不了名） |
 * | 恢复时原组没了回根组 | `moveEntry` 先删后加 ⇒ **条目直接消失** |
 * | folderId 指向不存在的组 | `modifyGroup` 原样返回 ⇒ 条目"保存成功"却哪里都不在 |
 */
class KdbxItemWriterTest {

    // ---------------------------------------------------------------- 新建

    @Test
    fun `新建落在根组，且 id 能算回条目`() {
        val db = databaseWith()

        val result = KdbxItemWriter.createEntry(db, folderId = null, item = sampleItem(title = "GitHub"))
        val entry = result.database.getEntry { it.uuid == result.entryUuid }?.second

        assertThat(entry).isNotNull()
        assertThat(entry!!.fields.title?.content).isEqualTo("GitHub")
        // 条目 id 与 uuid 必须能互相算回来（否则 UI 点进去会找不到）。
        assertThat(itemIdOf(result.entryUuid!!)).isEqualTo("kdbx-entry:${result.entryUuid}")
        assertThat(entryUuidOf(itemIdOf(result.entryUuid!!))).isEqualTo(result.entryUuid)
    }

    @Test
    fun `folderId 指向不存在的组时退回根组（不静默丢条目）`() {
        // ⚠️ 上游 `modifyGroup` 匹配不到 uuid 会**原样返回** —— 不守卫的话
        //    条目会"保存成功"但列表里永远不出现（用户以为存上了）。
        val db = databaseWith()
        val ghostFolder = folderIdOf(UUID.randomUUID())

        val result = KdbxItemWriter.createEntry(db, folderId = ghostFolder, item = sampleItem(title = "X"))

        val content = result.database.toMappedContent()
        assertThat(content.items.map { it.title }).contains("X")
    }

    @Test
    fun `新建到指定分组里`() {
        val group = Group(uuid = UUID.randomUUID(), name = "工作")
        val db = databaseWith(groups = listOf(group))

        val result = KdbxItemWriter.createEntry(db, folderId = folderIdOf(group.uuid), item = sampleItem("X"))

        assertThat(result.database.getGroupBy { uuid == group.uuid }!!.entries).hasSize(1)
        assertThat(result.database.content.group.entries).isEmpty()
    }

    // ---------------------------------------------------------------- 改

    @Test
    fun `改一条不影响别的条目`() {
        // ★ 保真自检的判据用例：`copy` 用错范围时，这条会红。
        val victim = entry(uuid = UUID.randomUUID(), title = "Keep", notes = "别动我")
        val target = entry(uuid = UUID.randomUUID(), title = "Change")
        val db = databaseWith(entries = listOf(victim, target))

        val before = db.toMappedContent().items.single { it.title == "Change" }
        val after = before.copy(title = "Changed", password = "new-pw")
        val updated = KdbxItemWriter.updateEntry(db, before, after).database

        val untouched = updated.getEntry { it.uuid == victim.uuid }!!.second
        assertThat(untouched.fields.title?.content).isEqualTo("Keep")
        assertThat(untouched.fields.notes?.content).isEqualTo("别动我")
        assertThat(updated.getEntry { it.uuid == target.uuid }!!.second.fields.title?.content)
            .isEqualTo("Changed")
    }

    @Test
    fun `改条目会把改前那一版推进历史`() {
        val uuid = UUID.randomUUID()
        val db = databaseWith(entries = listOf(entry(uuid = uuid, title = "Old")))
        val before = db.toMappedContent().items.single()

        val updated = KdbxItemWriter.updateEntry(db, before, before.copy(title = "New")).database
        val history = updated.getEntry { it.uuid == uuid }!!.second.history

        assertThat(history).hasSize(1)
        // ★ 历史里必须是**改前**的值：存成改后值的话，用户点开历史看到的是当前值，
        //   等于"历史"这个功能名存实亡（而且他永远不会发现）。
        assertThat(history.single().fields.title?.content).isEqualTo("Old")
        // 历史快照不该自带嵌套历史（历史套历史会让文件指数膨胀）。
        assertThat(history.single().history).isEmpty()
    }

    @Test
    fun `未变更的 TOTP 不被重写（保持原本的字段形态）`() {
        // ★ 场景：库用 KeePass 的 `TimeOtp-Secret-Hex` 存 TOTP，用户只改了标题。
        //   若把读出来的 otpauth URI 无条件写回 `otp` 字段 ⇒ 表示形态被改写，
        //   别的工具（以及用户自己的对照）看起来就"变了个东西"。
        val uuid = UUID.randomUUID()
        val db = databaseWith(
            entries = listOf(
                entry(
                    uuid = uuid,
                    title = "TOTP 库",
                    extraFields = mapOf(
                        KdbxTotpCodec.FIELD_TIMEOTP_HEX to EntryValue.Plain("31323334353637383930"),
                    ),
                ),
            ),
        )
        val before = db.toMappedContent().items.single()
        assertThat(before.totp).isNotNull()

        val updated = KdbxItemWriter.updateEntry(db, before, before.copy(title = "改标题")).database
        val fields = updated.getEntry { it.uuid == uuid }!!.second.fields

        assertThat(fields[KdbxTotpCodec.FIELD_TIMEOTP_HEX]).isNotNull()
        assertThat(fields[KdbxTotpCodec.FIELD_OTP]).isNull()
    }

    @Test
    fun `改了 TOTP 时写成 otp 字段，并清掉旧的 TimeOtp 系列`() {
        val uuid = UUID.randomUUID()
        val db = databaseWith(
            entries = listOf(
                entry(
                    uuid = uuid,
                    title = "TOTP 库",
                    extraFields = mapOf(
                        KdbxTotpCodec.FIELD_TIMEOTP_HEX to EntryValue.Plain("31323334353637383930"),
                    ),
                ),
            ),
        )
        val before = db.toMappedContent().items.single()
        val newUri = "otpauth://totp/New?secret=JBSWY3DPEHPK3PXP&period=30&digits=6"

        val updated = KdbxItemWriter.updateEntry(db, before, before.copy(totp = newUri)).database
        val fields = updated.getEntry { it.uuid == uuid }!!.second.fields

        assertThat(fields[KdbxTotpCodec.FIELD_OTP]?.content).isEqualTo(newUri)
        // ★ 两种表示同时存在时不同工具会各读各的、显示出**两个不同的验证码**。
        assertThat(fields[KdbxTotpCodec.FIELD_TIMEOTP_HEX]).isNull()
    }

    @Test
    fun `清空 TOTP 时把 OTP 字段全删掉`() {
        val uuid = UUID.randomUUID()
        val db = databaseWith(
            entries = listOf(
                entry(
                    uuid = uuid,
                    extraFields = mapOf(
                        KdbxTotpCodec.FIELD_TIMEOTP_HEX to EntryValue.Plain("31323334353637383930"),
                    ),
                ),
            ),
        )
        val before = db.toMappedContent().items.single()

        val updated = KdbxItemWriter.updateEntry(db, before, before.copy(totp = null)).database
        val fields = updated.getEntry { it.uuid == uuid }!!.second.fields

        assertThat(KdbxTotpCodec.isOtpFieldName(KdbxTotpCodec.FIELD_TIMEOTP_HEX)).isTrue()
        assertThat(fields[KdbxTotpCodec.FIELD_TIMEOTP_HEX]).isNull()
        assertThat(fields[KdbxTotpCodec.FIELD_OTP]).isNull()
    }

    // ---------------------------------------------------------------- 收藏（A1）

    @Test
    fun `新建收藏条目写入 VPX_FAVORITE，读回来是 true`() {
        val db = databaseWith()

        val result = KdbxItemWriter.createEntry(
            db,
            folderId = null,
            item = sampleItem(title = "收藏我").copy(favorite = true),
        )
        val fields = result.database.getEntry { it.uuid == result.entryUuid }!!.second.fields

        assertThat(fields[KdbxToolFields.FAVORITE]?.content).isEqualTo("true")
        // 读方向闭环：星标必须在再次打开库之后还在（此前整块没读，星是静默 no-op）。
        val item = result.database.toMappedContent().items.single()
        assertThat(item.favorite).isTrue()
        // ★ 工具区字段不得以自定义字段身份泄漏进详情页（否则用户看到一排 VPX_ 噪音）。
        assertThat(item.customFields.map { it.name }).doesNotContain(KdbxToolFields.FAVORITE)
    }

    @Test
    fun `新建不带收藏时不写 VPX_FAVORITE（不产空字段）`() {
        val db = databaseWith()

        val result = KdbxItemWriter.createEntry(db, folderId = null, item = sampleItem(title = "普通条目"))
        val fields = result.database.getEntry { it.uuid == result.entryUuid }!!.second.fields

        assertThat(fields[KdbxToolFields.FAVORITE]).isNull()
        assertThat(result.database.toMappedContent().items.single().favorite).isFalse()
    }

    @Test
    fun `取消收藏删掉 VPX_FAVORITE 键（缺失即假）`() {
        val uuid = UUID.randomUUID()
        val db = databaseWith(
            entries = listOf(
                entry(
                    uuid = uuid,
                    title = "星标条目",
                    extraFields = mapOf(KdbxToolFields.FAVORITE to EntryValue.Plain("true")),
                ),
            ),
        )
        val before = db.toMappedContent().items.single()
        assertThat(before.favorite).isTrue()

        val updated = KdbxItemWriter.updateEntry(db, before, before.copy(favorite = false)).database
        val fields = updated.getEntry { it.uuid == uuid }!!.second.fields

        assertThat(fields[KdbxToolFields.FAVORITE]).isNull()
        assertThat(updated.toMappedContent().items.single().favorite).isFalse()
    }

    @Test
    fun `未变更的收藏不被重写（保持第三方写的 1）`() {
        // ★ 与 TOTP 同一条纪律：一次无关编辑不该把用户库里的 "1" 改写成 "true"。
        //   文件内容无故变化 ⇒ 版本控制 / 差异工具里全是噪音，且可能触发不必要的同步冲突。
        val uuid = UUID.randomUUID()
        val db = databaseWith(
            entries = listOf(
                entry(
                    uuid = uuid,
                    title = "T",
                    extraFields = mapOf(KdbxToolFields.FAVORITE to EntryValue.Plain("1")),
                ),
            ),
        )
        val before = db.toMappedContent().items.single()
        assertThat(before.favorite).isTrue()

        val updated = KdbxItemWriter.updateEntry(db, before, before.copy(title = "改个标题")).database
        val fields = updated.getEntry { it.uuid == uuid }!!.second.fields

        assertThat(fields[KdbxToolFields.FAVORITE]?.content).isEqualTo("1")
    }

    // ---------------------------------------------------------------- 安全笔记类型标记（A4）

    @Test
    fun `新建空笔记的安全笔记_读回仍是安全笔记（核心回归）`() {
        // ★ 本文件的 bug：表单的备注**可空** ⇒ 空笔记的安全笔记三个标准字段全空，
        //   旧推导（无用户名 + 无密码 + 有笔记）判不出 ⇒ 读写往返后类型**静默翻转成登录**。
        //   Bitwarden 侧类型是服务端显式字段，无此问题 —— 两个库两种命运。
        val db = databaseWith()

        val result = KdbxItemWriter.createEntry(
            db,
            folderId = null,
            item = sampleItem(title = "空笔记").copy(
                type = VaultItemType.SecureNote,
                username = "",
                password = "",
                notes = "",
            ),
        )
        val fields = result.database.getEntry { it.uuid == result.entryUuid }!!.second.fields

        assertThat(fields[KdbxToolFields.SECURE_NOTE]?.content).isEqualTo("true")
        // 读方向闭环：再次打开库之后，类型必须还是安全笔记。
        val item = result.database.toMappedContent().items.single()
        assertThat(item.type).isEqualTo(VaultItemType.SecureNote)
        // ★ 工具区字段不得以自定义字段身份泄漏进详情页（否则用户看到一排 VPX_ 噪音）。
        assertThat(item.customFields.map { it.name }).doesNotContain(KdbxToolFields.SECURE_NOTE)
    }

    @Test
    fun `新建带笔记的安全笔记_读回是安全笔记（标记与启发式一致时不打架）`() {
        val db = databaseWith()

        val result = KdbxItemWriter.createEntry(
            db,
            folderId = null,
            item = sampleItem(title = "护照信息").copy(
                type = VaultItemType.SecureNote,
                username = "",
                password = "",
                notes = "号码 12345",
            ),
        )

        val item = result.database.toMappedContent().items.single()
        assertThat(item.type).isEqualTo(VaultItemType.SecureNote)
        assertThat(item.notes).isEqualTo("号码 12345")
    }

    @Test
    fun `新建登录条目不写 VPX_SECURE_NOTE（不产空字段）`() {
        val db = databaseWith()

        val result = KdbxItemWriter.createEntry(db, folderId = null, item = sampleItem(title = "普通登录"))
        val fields = result.database.getEntry { it.uuid == result.entryUuid }!!.second.fields

        assertThat(fields[KdbxToolFields.SECURE_NOTE]).isNull()
        assertThat(result.database.toMappedContent().items.single().type).isEqualTo(VaultItemType.Login)
    }

    @Test
    fun `未标记的第三方笔记型条目_仍按启发式判安全笔记（兼容不回归）`() {
        // 第三方工具（KeePassXC / DX）写的条目没有 VPX_SECURE_NOTE ⇒ 必须回落启发式。
        // ⚠️ 这条是兼容性闸：若 marker 缺失时默认判登录，升级后用户库里
        //   所有第三方安全笔记会一夜之间变成登录 —— 比原 bug 波及面更大。
        val uuid = UUID.randomUUID()
        val db = databaseWith(
            entries = listOf(
                Entry(
                    uuid = uuid,
                    fields = EntryFields(
                        linkedMapOf<String, EntryValue>(
                            BasicField.Title.key to EntryValue.Plain("第三方笔记"),
                            BasicField.Notes.key to EntryValue.Plain("恢复码：xxxx"),
                        ),
                    ),
                ),
            ),
        )

        assertThat(db.toMappedContent().items.single().type).isEqualTo(VaultItemType.SecureNote)
    }

    @Test
    fun `未标记的第三方登录型条目_仍是登录（反证启发式没被改坏）`() {
        val uuid = UUID.randomUUID()
        val db = databaseWith(entries = listOf(entry(uuid = uuid, title = "第三方登录")))

        val item = db.toMappedContent().items.single()
        assertThat(item.type).isEqualTo(VaultItemType.Login)
        assertThat(item.username).isEqualTo("alice")
    }

    @Test
    fun `第三方把标记写成 1_读回是安全笔记（宽松真值）`() {
        // 用户手改库写成 "1" / "yes" 也按安全笔记处理（同收藏的宽松真值口径）。
        val uuid = UUID.randomUUID()
        val db = databaseWith(
            entries = listOf(
                entry(
                    uuid = uuid,
                    title = "手改标记",
                    extraFields = mapOf(KdbxToolFields.SECURE_NOTE to EntryValue.Plain("1")),
                ),
            ),
        )

        assertThat(db.toMappedContent().items.single().type).isEqualTo(VaultItemType.SecureNote)
    }

    @Test
    fun `标记显式为 false 时_即使是笔记型内容也判登录`() {
        // marker 存在且非真值 ⇒ 显式结论压倒启发式（三态语义的另一半）。
        val uuid = UUID.randomUUID()
        val db = databaseWith(
            entries = listOf(
                Entry(
                    uuid = uuid,
                    fields = EntryFields(
                        linkedMapOf<String, EntryValue>(
                            BasicField.Title.key to EntryValue.Plain("显式登录"),
                            BasicField.Notes.key to EntryValue.Plain("只有笔记"),
                            KdbxToolFields.SECURE_NOTE to EntryValue.Plain("false"),
                        ),
                    ),
                ),
            ),
        )

        assertThat(db.toMappedContent().items.single().type).isEqualTo(VaultItemType.Login)
    }

    @Test
    fun `未变更的类型不被重写（保持第三方写的 1）`() {
        // ★ 与收藏 / TOTP 同一条纪律：一次无关编辑不该把用户库里的 "1" 改写成 "true"。
        val uuid = UUID.randomUUID()
        val db = databaseWith(
            entries = listOf(
                entry(
                    uuid = uuid,
                    title = "T",
                    extraFields = mapOf(KdbxToolFields.SECURE_NOTE to EntryValue.Plain("1")),
                ),
            ),
        )
        val before = db.toMappedContent().items.single()
        assertThat(before.type).isEqualTo(VaultItemType.SecureNote)

        val updated = KdbxItemWriter.updateEntry(db, before, before.copy(title = "改个标题")).database
        val fields = updated.getEntry { it.uuid == uuid }!!.second.fields

        assertThat(fields[KdbxToolFields.SECURE_NOTE]?.content).isEqualTo("1")
        assertThat(updated.toMappedContent().items.single().type).isEqualTo(VaultItemType.SecureNote)
    }

    @Test
    fun `更新空笔记的安全笔记_类型不翻回登录`() {
        // 编辑一条已标记的安全笔记（只改标题）：标记与类型都必须原样。
        val db = databaseWith()
        val note = sampleItem(title = "空笔记")
            .copy(type = VaultItemType.SecureNote, username = "", password = "", notes = "")
        val created = KdbxItemWriter.createEntry(
            db,
            folderId = null,
            item = note,
        )
        val before = created.database.toMappedContent().items.single()
        assertThat(before.type).isEqualTo(VaultItemType.SecureNote)

        val updated = KdbxItemWriter.updateEntry(created.database, before, before.copy(title = "空笔记·改")).database
        val item = updated.toMappedContent().items.single()

        assertThat(item.type).isEqualTo(VaultItemType.SecureNote)
        assertThat(item.title).isEqualTo("空笔记·改")
    }

    // ---------------------------------------------------------------- 自定义字段与通行密钥

    @Test
    fun `自定义字段按名对齐（增 改 删），且通行密钥字段绝不被清`() {
        // ★ 本文件最重要的一条：`KPEX_*` 一丢，条目上的通行密钥就**永久失效**
        //   （而且不会报错 —— 用户要到下次用 passkey 登录时才发现）。
        val uuid = UUID.randomUUID()
        val db = databaseWith(
            entries = listOf(
                entry(
                    uuid = uuid,
                    customData = mapOf(
                        KdbxPasskeyCodec.FIELD_PRIVATE_KEY to CustomDataValue("PEM-BLOB"),
                        KdbxPasskeyCodec.FIELD_CREDENTIAL_ID to CustomDataValue("cred-id"),
                    ),
                    extraFields = mapOf(
                        "Keep" to EntryValue.Plain("keep-me"),
                        "Drop" to EntryValue.Plain("drop-me"),
                        "Edit" to EntryValue.Plain("old"),
                    ),
                ),
            ),
        )
        val before = db.toMappedContent().items.single()
        // 先确认读方向确实把 KPEX_* 排除在 customFields 之外（否则本用例的前提不成立）。
        assertThat(before.customFields.map { it.name }).containsNoneOf(
            KdbxPasskeyCodec.FIELD_PRIVATE_KEY,
            KdbxPasskeyCodec.FIELD_CREDENTIAL_ID,
        )

        val after = before.copy(
            customFields = listOf(
                VaultCustomField(name = "Keep", value = "keep-me"),
                VaultCustomField(name = "Edit", value = "new"),
                VaultCustomField(name = "Added", value = "added", type = CustomFieldType.Hidden),
            ),
        )
        val updated = KdbxItemWriter.updateEntry(db, before, after).database
        val entry = updated.getEntry { it.uuid == uuid }!!.second

        assertThat(entry.fields["Keep"]?.content).isEqualTo("keep-me")
        assertThat(entry.fields["Edit"]?.content).isEqualTo("new")
        assertThat(entry.fields["Drop"]).isNull()
        assertThat(entry.fields["Added"]?.content).isEqualTo("added")
        // 新增的 Hidden 必须是受保护形态（否则详情页会明文展示它）。
        assertThat(entry.fields["Added"]).isInstanceOf(EntryValue.Encrypted::class.java)
        // ★ 通行密钥纹丝不动。
        assertThat(entry.customData[KdbxPasskeyCodec.FIELD_PRIVATE_KEY]?.value).isEqualTo("PEM-BLOB")
        assertThat(entry.customData[KdbxPasskeyCodec.FIELD_CREDENTIAL_ID]?.value).isEqualTo("cred-id")
    }

    @Test
    fun `密码字段保持原有的是否受保护形态`() {
        val uuid = UUID.randomUUID()
        val db = databaseWith(entries = listOf(entry(uuid = uuid, title = "T")))
        val before = db.toMappedContent().items.single()
        // 建库时密码默认是受保护的（`EntryFields.createDefault`）。
        assertThat(db.getEntry { it.uuid == uuid }!!.second.fields.password)
            .isInstanceOf(EntryValue.Encrypted::class.java)

        val updated = KdbxItemWriter.updateEntry(db, before, before.copy(password = "new")).database

        assertThat(updated.getEntry { it.uuid == uuid }!!.second.fields.password)
            .isInstanceOf(EntryValue.Encrypted::class.java)
    }

    // ---------------------------------------------------------------- 删 / 恢复

    @Test
    fun `删除 = 移进回收站（列表里不再出现，回收站里 +1）`() {
        val uuid = UUID.randomUUID()
        val db = databaseWith(entries = listOf(entry(uuid = uuid, title = "删我")))
        val item = db.toMappedContent().items.single()

        val updated = KdbxItemWriter.moveToRecycleBin(db, item.id).database
        val content = updated.toMappedContent()

        assertThat(content.items).isEmpty()
        assertThat(content.recycleBinCount).isEqualTo(1)
    }

    @Test
    fun `从回收站恢复回原分组`() {
        val group = Group(uuid = UUID.randomUUID(), name = "工作")
        val uuid = UUID.randomUUID()
        val inGroup = entry(uuid = uuid, title = "在分组里")
        // 用公开的修饰器 API 把条目放进那个组（`KeePassDatabase` 是 sealed，没有 copy）。
        val db = databaseWith(groups = listOf(group)).modifyGroup(group.uuid) {
            copy(entries = entries + inGroup)
        }
        val item = db.toMappedContent().items.single()
        assertThat(item.folderId).isEqualTo(folderIdOf(group.uuid))

        val deleted = KdbxItemWriter.moveToRecycleBin(db, item.id).database
        val restored = KdbxItemWriter.restoreFromRecycleBin(deleted, item.id).database

        // ⚠️ 上游 `getGroupBy` 在这里会返回 null（同一份数据用直接遍历就能找到），
        //    所以断言走**直接遍历**：测试要断的是"条目回去了"，不是"某个上游查找函数好不好用"。
        val workGroup = restored.content.group.groups.single { it.uuid == group.uuid }
        val back = workGroup.entries
        assertThat(back.map { it.uuid }).contains(uuid)
    }

    @Test
    fun `恢复时原分组已被删除则退回根组（条目不消失）`() {
        // ★ `moveEntry` 是"先删后加"：目标组不存在时条目会**凭空消失**。
        //   构造：条目先移进回收站拿 previousParentGroup，然后那个组被删掉。
        val group = Group(uuid = UUID.randomUUID(), name = "临时组")
        val uuid = UUID.randomUUID()
        val db = databaseWith(groups = listOf(group)).modifyGroup(group.uuid) {
            copy(entries = entries + entry(uuid, "X"))
        }
        val item = db.toMappedContent().items.single()

        val inBin = KdbxItemWriter.moveToRecycleBin(db, item.id).database
        // 把原分组从库里拿掉（模拟"用户在别的工具里删了那个分组"）。
        val groupGone = inBin.modifyParentGroup {
            copy(groups = groups.filterNot { it.uuid == group.uuid })
        }

        val restored = KdbxItemWriter.restoreFromRecycleBin(groupGone, item.id).database

        // 条目**必须还在**（回根组），而不是"恢复成功但消失了"。
        assertThat(restored.getEntry { it.uuid == uuid }).isNotNull()
        assertThat(restored.content.group.entries.map { it.uuid }).contains(uuid)
    }

    @Test
    fun `永久删除会写墓碑并让条目消失`() {
        val uuid = UUID.randomUUID()
        val db = databaseWith(entries = listOf(entry(uuid = uuid, title = "永久删")))
        val item = db.toMappedContent().items.single()

        val updated = KdbxItemWriter.permanentDelete(db, item.id).database

        assertThat(updated.getEntry { it.uuid == uuid }).isNull()
        // ⚠️ 墓碑是给同步用的"这条没了"，不是冗余数据 —— 清掉它会让同步对不上账。
        assertThat(updated.content.deletedObjects.map { it.id }).contains(uuid)
    }

    // ---------------------------------------------------------------- helpers

    private fun databaseWith(
        entries: List<Entry> = emptyList(),
        groups: List<Group> = emptyList(),
    ): KeePassDatabase {
        val created = KeePassDatabase.Ver4x.create(
            rootName = "Root",
            meta = Meta(name = "Writer"),
            credentials = CREDENTIALS,
        )
        return created.copy(
            content = created.content.copy(
                group = created.content.group.copy(entries = entries, groups = groups),
            ),
        )
    }

    private fun entry(
        uuid: UUID,
        title: String = "T",
        notes: String = "",
        extraFields: Map<String, EntryValue> = emptyMap(),
        customData: Map<String, CustomDataValue> = emptyMap(),
    ): Entry = Entry(
        uuid = uuid,
        fields = EntryFields(
            linkedMapOf<String, EntryValue>(
                BasicField.Title.key to EntryValue.Plain(title),
                BasicField.UserName.key to EntryValue.Plain("alice"),
                BasicField.Password.key to EntryValue.Encrypted(EncryptedValue.fromString("pw")),
                BasicField.Url.key to EntryValue.Plain("https://example.com"),
                BasicField.Notes.key to EntryValue.Plain(notes),
            ).apply { putAll(extraFields) },
        ),
        customData = customData,
    )

    private fun sampleItem(title: String): VaultItem = VaultItem(
        id = "kdbx-entry:${UUID.randomUUID()}",
        title = title,
        username = "alice",
        password = "pw",
        type = VaultItemType.Login,
    )

    private companion object {
        val CREDENTIALS: Credentials = Credentials.from(EncryptedValue.fromString("master-pw"))
    }
}
