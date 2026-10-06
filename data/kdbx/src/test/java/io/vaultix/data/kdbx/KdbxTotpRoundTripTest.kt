/*
 * Vaultix — data:kdbx（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * TOTP **写回往返**（W3 · JSON⇄KDBX 无损互转）单元测试。
 *
 * ## 为什么必须钉死"往返"
 *
 * `applyTotp` 此前**从未被验证过往返**（施工单 §1.2 缺口表最后一行）：
 * 写侧把 `VaultItem.totp` 整串塞进 `otp` 字段，读侧 `KdbxTotpCodec.toOtpAuthUri`
 * 再把它归一成 `otpauth://`。中间隔着 `EntryFields` 的**键名大小写敏感**、
 * `Plain`/`Encrypted` 两种取值、以及一次 URI 解析 —— 任何一环出错都**不报错**，
 * 只是让用户的验证码**从此算错**（界面上仍是 6 位数字，照常刷新）。
 *
 * "不报错"正是这类 bug 能活到今天的原因：单测全绿 ≠ 用户能用。
 *
 * ## 断言的是**语义等价**，不是字符串相等
 *
 * `otpauth://totp/GitHub:alice?secret=X&algorithm=SHA256` 与
 * `otpauth://totp/GitHub:alice?secret=X&issuer=GitHub&algorithm=SHA256`
 * 解析后是同一个配置。断言字面串相等会在两种合法写法之间**误报**，
 * 而真正的数据丢失（digits 从 8 掉回 6）反而可能被放过。
 * ⇒ 一律断 `OtpUriParser.parse` 之后的 `TotpConfig` 各分量。
 *
 * 纯 JVM（kotpass 可在 JVM 跑），不需要 Android 运行时。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.kdbx

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.models.Meta
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.vaultix.common.OtpType
import io.vaultix.common.OtpUriParser
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import org.junit.Test

class KdbxTotpRoundTripTest {

    // ---------------------------------------------------------------- 全形态往返

    @Test
    fun `every otpauth shape survives a write and read back unchanged`() {
        // ★ W3 的主判据：otpauth 全形态逐个往返一遍，
        //   任一形态不一致都会在这里报出名字。
        // 覆盖维度：SHA1/256/512 × 6/8 位× 秒级/分钟级 step × HOTP counter × Steam。
        for (case in ROUND_TRIP_CASES) {
            val before = requireNotNull(OtpUriParser.parse(case.uri)) { case.name }
            val after = requireNotNull(OtpUriParser.parse(roundTrip(case.uri).totp!!)) { case.name }

            assertWithMessage("${case.name} · secret").that(after.secret).isEqualTo(before.secret)
            assertWithMessage("${case.name} · period").that(after.period).isEqualTo(before.period)
            assertWithMessage("${case.name} · digits").that(after.digits).isEqualTo(before.digits)
            assertWithMessage("${case.name} · algorithm").that(after.algorithm).isEqualTo(before.algorithm)
            assertWithMessage("${case.name} · type").that(after.type).isEqualTo(before.type)
            assertWithMessage("${case.name} · counter").that(after.counter).isEqualTo(before.counter)
            assertWithMessage("${case.name} · pin").that(after.pin).isEqualTo(before.pin)
        }
    }

    @Test
    fun `otp field holds the uri verbatim after a write`() {
        // 为什么单独断这一条：`toOtpAuthUri` 认得"otp 字段里直接躺着一条完整 URI"，
        // 所以往返**本该**是恒等的。若某次重构让写侧改成了裸 secret，
        // 下面的语义断言**仍会全绿**（secret 一样），但文件内容已经变了 ——
        // KeePassXC 打开看到的是 `otp` = 一串裸 base32，与用户原先的库长得不一样。
        val uri = "otpauth://totp/GitHub:alice?secret=JBSWY3DPEHPK3PXP&algorithm=SHA256"

        val db = databaseWith()
        val item = itemWithTotp(uri)
        val created = KdbxItemWriter.createEntry(db, folderId = null, item = item)
        val entry = created.database.getEntry { it.uuid == created.entryUuid }!!.second

        assertThat(entry.fields[KdbxTotpCodec.FIELD_OTP]?.content).isEqualTo(uri)
    }

    @Test
    fun `steam secret with url unsafe characters is not corrupted`() {
        // ★ Steam 密钥是 **base64**，必含 `+` `/` `=`；写进 URI 须经百分号编码。
        //   若 `uriEncode` 漏了转义，`&`/`=` 会把 query 拆坏 ⇒ secret 被截断。
        val uri = "otpauth://totp/Steam:alice?secret=%2B%2F%2B%2BAQIDBAU%3D&issuer=Steam&encoder=steam"

        val after = requireNotNull(OtpUriParser.parse(roundTrip(uri).totp!!))

        assertThat(after.secret).isEqualTo("+/++AQIDBAU=")
        assertThat(after.type).isEqualTo(OtpType.STEAM)
        // 断"能算出码"，而不只是断解析成功：Steam 走 25 字符专属字母表，
        // 密钥错一位照样能算出 5 位数字，但和 Steam 客户端永远对不上。
        assertThat(after.digits).isEqualTo(5)
    }

    @Test
    fun `hotp counter survives the round trip`() {
        // ★ HOTP 的命门：counter 丢了不会报错，只会让同一个密钥永远算出同一个码。
        val uri = "otpauth://hotp/GitHub:alice?secret=JBSWY3DPEHPK3PXP&counter=42"

        val after = requireNotNull(OtpUriParser.parse(roundTrip(uri).totp!!))

        assertThat(after.type).isEqualTo(OtpType.HOTP)
        assertThat(after.counter).isEqualTo(42L)
    }

    // ---------------------------------------------------------------- 幂等与边界

    @Test
    fun `writing an unchanged item twice is a no-op`() {
        // ★ 转换器可重复执行的底线：写两次不能把库改花。
        //   落点是"值相同就不重写"（applyTotp 首行）—— 它一旦失效，
        //   用户的 Hex存法 会被改写成 otpauth，且每次开关库都再洗一遍。
        val uri = "otpauth://totp/GitHub:alice?secret=JBSWY3DPEHPK3PXP&algorithm=SHA512&digits=8"
        val db = databaseWith()
        val first = KdbxItemWriter.createEntry(db, folderId = null, item = itemWithTotp(uri))
        val before = first.database.toMappedContent().items.single()

        val second = KdbxItemWriter.updateEntry(
            first.database,
            before,
            before.copy(title = "改名了"),
        ).database

        assertThat(second.toMappedContent().items.single().totp).isEqualTo(uri)
    }

    @Test
    fun `totp never leaks into custom fields`() {
        // ★ `otp` 是 OTP 家族的键，必须被 `isOtpFieldName` 挡住。
        //   一旦漏判，密钥会以"自定义字段"的身份出现在详情页（明文展示）。
        val uri = "otpauth://totp/GitHub:alice?secret=JBSWY3DPEHPK3PXP"

        val item = roundTrip(uri)

        assertThat(item.customFields.map { it.name })
            .doesNotContain(KdbxTotpCodec.FIELD_OTP)
    }

    @Test
    fun `an item without totp reads back without totp`() {
        // 反向用例：证明往返测试**真的会断**空值，而不是恒返回非空。
        val db = databaseWith()
        val created = KdbxItemWriter.createEntry(
            db,
            folderId = null,
            item = VaultItem(
                id = "kdbx-entry:00000000-0000-0000-0000-000000000009",
                title = "无验证码",
                username = "alice",
                password = "pw",
                type = VaultItemType.Login,
            ),
        )

        val read = created.database.toMappedContent().items.single()

        assertThat(read.totp).isNull()
    }

    @Test
    fun `clearing totp removes the otp field entirely`() {
        // ★ 清空是唯一会让"OTP 字段全消失"的动作：漏删就会留下一个陈旧密钥，
        //   界面上看着像没有验证码，KDBX 里却还躺着能用的 secret。
        val db = databaseWith()
        val created = KdbxItemWriter.createEntry(
            db,
            folderId = null,
            item = itemWithTotp("otpauth://totp/GitHub:alice?secret=JBSWY3DPEHPK3PXP"),
        )
        val before = created.database.toMappedContent().items.single()

        val cleared = KdbxItemWriter.updateEntry(created.database, before, before.copy(totp = null))
        val fields = cleared.database.getEntry { it.uuid == created.entryUuid }!!.second.fields

        assertThat(fields[KdbxTotpCodec.FIELD_OTP]).isNull()
        assertThat(cleared.database.toMappedContent().items.single().totp).isNull()
    }

    @Test
    fun `changing a non default digit count is not silently reset to six`() {
        // ★ 最容易被"默认值兜底"吃掉的一维：`digits` 被 clamp 回 6 时毫无征兆，
        //   用户只会发现"8 位验证码变成了 6 位"，且不知道密码管理器改了他的设置。
        val uri = "otpauth://totp/GitHub:alice?secret=JBSWY3DPEHPK3PXP&digits=8&period=60"

        val after = requireNotNull(OtpUriParser.parse(roundTrip(uri).totp!!))

        assertThat(after.digits).isEqualTo(8)
        assertThat(after.period).isEqualTo(60)
    }

    // ---------------------------------------------------------------- 夹具

    /** 全链路往返：建库 → 写条目 → 映射回领域模型。 */
    private fun roundTrip(uri: String): VaultItem {
        val db = databaseWith()
        val created = KdbxItemWriter.createEntry(db, folderId = null, item = itemWithTotp(uri))
        return created.database.toMappedContent().items.single()
    }

    private fun databaseWith(): KeePassDatabase = KeePassDatabase.Ver4x.create(
        rootName = "Root",
        meta = Meta(name = "Totp"),
        credentials = CREDENTIALS,
    )

    private fun itemWithTotp(uri: String) = VaultItem(
        id = "kdbx-entry:00000000-0000-0000-0000-000000000001",
        title = "GitHub",
        username = "alice",
        password = "pw",
        type = VaultItemType.Login,
        totp = uri,
    )

    /** 一条待往返的 URI：名字会进断言失败信息，所以要能一眼看出是哪种形态。 */
    private data class TotpCase(val name: String, val uri: String)

    private companion object {
        val CREDENTIALS: Credentials = Credentials.from(EncryptedValue.fromString("master-pw"))

        val ROUND_TRIP_CASES: List<TotpCase> = listOf(
            TotpCase("SHA1/6位/30秒（最简形态）", "otpauth://totp/GitHub:alice?secret=JBSWY3DPEHPK3PXP"),
            TotpCase("SHA256/6位/30秒", "otpauth://totp/GitHub:alice?secret=JBSWY3DPEHPK3PXP&algorithm=SHA256"),
            TotpCase(
                "SHA512/8位/30秒",
                "otpauth://totp/GitHub:alice?secret=JBSWY3DPEHPK3PXP&algorithm=SHA512&digits=8",
            ),
            // 步长 60 与默认 30 相同，但**写在 URI 里**与省略是不同的形态，各测一次。
            TotpCase("SHA1/8位/60秒", "otpauth://totp/GitHub:alice?secret=JBSWY3DPEHPK3PXP&period=60&digits=8"),
            // 15 秒是"秒级 step"的极端形态（0.5 分钟），归一时最容易把它当默认值丢掉。
            TotpCase(
                "SHA256/6位/15秒",
                "otpauth://totp/GitHub:alice?secret=JBSWY3DPEHPK3PXP&period=15&algorithm=SHA256",
            ),
            TotpCase("带 issuer 参数", "otpauth://totp/GitHub:alice?secret=JBSWY3DPEHPK3PXP&issuer=GitHub"),
            // label 里带空格 ⇒ URI 编码路径（%20）。
            TotpCase("label 含空格", "otpauth://totp/GitHub%20Inc:alice?secret=JBSWY3DPEHPK3PXP"),
            TotpCase("HOTP counter=0", "otpauth://hotp/GitHub:alice?secret=JBSWY3DPEHPK3PXP&counter=0"),
            TotpCase("HOTP counter=42", "otpauth://hotp/GitHub:alice?secret=JBSWY3DPEHPK3PXP&counter=42"),
            TotpCase("Yandex变体", "otpauth://yaotp/Yandex:alice?secret=JBSWY3DPEHPK3PXP&issuer=Yandex"),
            TotpCase(
                "Steam（base64 密钥，含 + / =）",
                "otpauth://totp/Steam:alice?secret=%2B%2F%2B%2BAQIDBAU%3D&issuer=Steam&encoder=steam",
            ),
        )
    }
}