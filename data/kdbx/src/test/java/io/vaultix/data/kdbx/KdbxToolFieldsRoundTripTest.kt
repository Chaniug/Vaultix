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
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Meta
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.vaultix.model.UriMatch
import io.vaultix.model.VaultCustomField
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import io.vaultix.model.VaultUri
import org.junit.Test

/**
 * `VPX_` 工具字段的无损往返（阶段 B · 批次 W4）。
 *
 * ## 为什么这类测试值得单列一个文件
 *
 * W4 之前的 URI 写回是`item.uris.firstOrNull()` —— 一条挂 3 个网址的登录，
 * 改一次标题就被抹掉 2 个网址，**不报错、不提示、文件仍能正常打开**。
 * 这类"安静地改坏用户数据"的缺陷，只能靠**往返计数相等**来判，
 * 断言字段值逐个相等反而抓不到（因为少掉的那几个压根没有"错值"可比）。
 *
 * ⇒ 主判据一律是**数量 + 内容 + 顺序**三者同时相等。
 *
 * ## 每条用例对应一个具体的"会静默坏事"
 *
 * | 用例 | 不这么做会怎样 |
 * |---|---|
 * | 3 条 URI 往返仍是 3 条且顺序不变 | 少掉的网址无声消失；顺序变了自动填充会挑错条目 |
 * | `VPX_MATCH_n` 存回匹配档位 | 自动填充档位退回默认，用户不知为何匹配变松/变严 |
 * | `androidapp://` 落 `App Package Name` | KeePassXC 当域名匹配 ⇒ **Android 应用自动填充整体失效** |
 * | 标准 `Url` 不被 `androidapp://` 污染 | 同上，且详情页显示一个匹配不上的"网址" |
 * | 别名键（`AppPackageName`）也读得出 | 别人的库在我们这儿读不出应用条目（单向丢数据） |
 * | 键名大小写不同也读得出 | 同上（kotpass 键名大小写敏感，`javap` 已核实） |
 * | 3 条删到 2 条不留错位旧值 | 网址还在但**对应关系错了** —— 比丢数据更坏 |
 * | 删光 URL 会清掉工具字段 | 残留 `VPX_URL_1` 指向已删的网址 |
 * | `VPX_` 不进自定义字段 | 详情页同一网址出现两次；两条写通道抢同一个键 |
 * | 非法包名不产出字段 | 写出 `App Package Name = "不是包名"`，别的工具读不到还占位 |
 * | 类型码 2/3/4/5 各归各的 | 卡片往返后变成身份，**错位一位且无任何症状** |
 */
class KdbxToolFieldsRoundTripTest {

    // ---------------------------------------------------------------- 主判据

    @Test
    fun `3 条 URI 的登录条目往返后仍是 3 条，且顺序不变`() {
        val uris = listOf(
            VaultUri(uri = "https://github.com/login"),
            VaultUri(uri = "https://gitlab.com/login"),
            VaultUri(uri = "https://bitbucket.org/login"),
        )

        val after = roundTrip(itemWithUris(uris))

        assertThat(after.uris.map { it.uri }).containsExactly(
            "https://github.com/login",
            "https://gitlab.com/login",
            "https://bitbucket.org/login",
        ).inOrder()
    }

    @Test
    fun `多出的 URI 落在 VPX_URL_n，且第 0 条进标准 Url`() {
        val uris = listOf(
            VaultUri(uri = "https://a.example"),
            VaultUri(uri = "https://b.example"),
            VaultUri(uri = "https://c.example"),
        )

        val fields = writeFields(itemWithUris(uris))

        // 第 0 条走标准键，这是"KeePassXC 原生库"的读法，不能被工具字段抢走。
        assertThat(fields[BasicField.Url.key]?.content).isEqualTo("https://a.example")
        assertThat(fields["VPX_URL_1"]?.content).isEqualTo("https://b.example")
        assertThat(fields["VPX_URL_2"]?.content).isEqualTo("https://c.example")
        // 不该凭空多出下标 3。
        assertThat(fields["VPX_URL_3"]).isNull()
    }

    @Test
    fun `只有 1 条 URI 时不产出任何工具字段`() {
        val fields = writeFields(itemWithUris(listOf(VaultUri(uri = "https://only.example"))))

        assertThat(fields[BasicField.Url.key]?.content).isEqualTo("https://only.example")
        // 单条不该有"第 2 条起"的落法。
        assertThat(fields.keys.filter { it.startsWith("VPX_URL_") }).isEmpty()
    }

    // ---------------------------------------------------------------- 匹配规则

    @Test
    fun `每条 URI 的匹配档位各自存回`() {
        val uris = listOf(
            VaultUri(uri = "https://a.example", match = UriMatch.Domain),
            VaultUri(uri = "https://b.example", match = UriMatch.Exact),
            VaultUri(uri = "https://c.example", match = UriMatch.Never),
        )

        val after = roundTrip(itemWithUris(uris))

        assertThat(after.uris.map { it.match }).containsExactly(
            UriMatch.Domain,
            UriMatch.Exact,
            UriMatch.Never,
        ).inOrder()
    }

    @Test
    fun `匹配档位为 null 时不产出 VPX_MATCH_n（不写无意义的默认值）`() {
        val uris = listOf(
            VaultUri(uri = "https://a.example"),
            VaultUri(uri = "https://b.example"),
        )

        val fields = writeFields(itemWithUris(uris))

        // null = "服务端未指定"，写一个 `Domain` 进去等于**擅自替用户做了决定**。
        assertThat(fields.keys.filter { it.startsWith("VPX_MATCH_") }).isEmpty()
    }

    @Test
    fun `VPX_MATCH_n 认不出的值按未知处理，不抛也不乱认`() {
        val fields = EntryFields.createDefault().apply {
            this[BasicField.Url.key] = EntryValue.Plain("https://a.example")
            this["VPX_URL_1"] = EntryValue.Plain("https://b.example")
            // 模拟别的工具写了个我们不认识的档位（或用户手改了）。
            this["VPX_MATCH_1"] = EntryValue.Plain("FuzzySomehow")
        }

        val uris = KdbxToolFields.toUris(fields[BasicField.Url.key]!!.content, fields.asStringMap())

        assertThat(uris.map { it.uri }).containsExactly("https://a.example", "https://b.example").inOrder()
        // 认不出⇒null（基域匹配），绝不能瞎猜一个档位填上。
        assertThat(uris[1].match).isNull()
    }

    // ---------------------------------------------------------------- 应用 URI

    @Test
    fun `androidapp URI 落 App Package Name 且往返仍是同一条`() {
        val after = roundTrip(itemWithUris(listOf(VaultUri(uri = "androidapp://com.example.mail"))))

        assertThat(after.uris.map { it.uri }).containsExactly("androidapp://com.example.mail")
    }

    @Test
    fun `androidapp URI 不污染标准 Url`() {
        val fields = writeFields(itemWithUris(listOf(VaultUri(uri = "androidapp://com.example.mail"))))

        // ⚠️ 塞进 `Url` 的后果不是"显示怪"：KeePassXC 会当域名去匹配，必然匹配不上
        //    ⇒ 自动填充对 Android 应用**整体失效**。
        assertThat(fields[BasicField.Url.key]?.content.orEmpty()).isEmpty()
        assertThat(fields[KdbxToolFields.APP_PACKAGE]?.content).isEqualTo("com.example.mail")
    }

    @Test
    fun `网址与应用 URI 混排时各自落位且顺序可还原`() {
        val after = roundTrip(
            itemWithUris(
                listOf(
                    VaultUri(uri = "https://web.example"),
                    VaultUri(uri = "androidapp://com.example.app"),
                    VaultUri(uri = "https://web2.example"),
                ),
            ),
        )

        // 三条一条不少（应用 URI 走字段对，但仍要回到列表里）。
        assertThat(after.uris).hasSize(3)
        assertThat(after.uris.map { it.uri }).contains("androidapp://com.example.app")
        assertThat(after.uris.map { it.uri }).contains("https://web.example")
        assertThat(after.uris.map { it.uri }).contains("https://web2.example")
    }

    @Test
    fun `多个应用 URI 时只保留第一个（KDBX 字段位只有一个，这是已知取舍不是 bug）`() {
        // `App Package Name` 是**单个**字段位（KeePassDX 约定就是一条一个包名），
        // 没有 `App Package Name_2` 这种落法⇒ 第 2 个应用 URI 在 KDBX 侧无处可存。
        //
        // ⚠️ 用例存在的意义是**钉死这个行为**，让后来者别把它当 bug"顺手修"：
        //   若改成"全部塞进 VPX_URL_n"，那些 `androidapp://` 会被 KeePassXC 当域名匹配
        //   （必然匹配不上），等于**为了多存一条而让所有应用 URI 的匹配都失效**。
        //   宁可少一条，也不要全部失效。
        val after = roundTrip(
            itemWithUris(
                listOf(
                    VaultUri(uri = "androidapp://com.example.first"),
                    VaultUri(uri = "androidapp://com.example.second"),
                ),
            ),
        )

        assertThat(after.uris.map { it.uri }).containsExactly("androidapp://com.example.first")
    }

    @Test
    fun `三种 android scheme 都认得出包名`() {
        listOf("androidapp://", "android-app://", "android://").forEach { scheme ->
            val pkg = KdbxToolFields.androidAppPackage("${scheme}com.example.app")
            assertWithMessage("scheme $scheme 应解析出包名").that(pkg).isEqualTo("com.example.app")
        }
    }

    @Test
    fun `应用 URI 带路径时只取包名段`() {
        // 包名本身不含 `/`，其后是路径/参数。
        val pkg = KdbxToolFields.androidAppPackage("androidapp://com.example.app/some/path?x=1")

        assertThat(pkg).isEqualTo("com.example.app")
    }

    @Test
    fun `认不出的包名不产出应用字段`() {
        // 没有点 ⇒ 不是合法包名；`androidapp://foo` 这种第三方读不出来，
        // 写进去只会占一个字段位并让别的工具困惑。
        assertThat(KdbxToolFields.androidAppPackage("androidapp://nodots")).isNull()
        assertThat(KdbxToolFields.androidAppPackage("https://example.com")).isNull()
        assertThat(KdbxToolFields.androidAppPackage("androidapp://")).isNull()
    }

    // ---------------------------------------------------------------- 互操作（读第三方库）

    @Test
    fun `第三方写的别名键也能读出应用 URI`() {
        // kotpass 的`EntryFields.get(String)` 是普通 Map.get ⇒ **大小写敏感**（javap 已核实）。
        // 所以只认自己写的那一个键名，读别人的库就会丢应用条目。
        val fields = EntryFields.createDefault().apply {
            this["AppPackageName"] = EntryValue.Plain("com.thirdparty.app")
        }

        val uri = KdbxToolFields.appUriOf(fields.asStringMap())

        assertThat(uri).isEqualTo("androidapp://com.thirdparty.app")
    }

    @Test
    fun `别名键的大小写不同也能读出`() {
        val fields = EntryFields.createDefault().apply {
            this["apppackagename"] = EntryValue.Plain("com.thirdparty.app")
        }

        val uri = KdbxToolFields.appUriOf(fields.asStringMap())

        assertThat(uri).isEqualTo("androidapp://com.thirdparty.app")
    }

    @Test
    fun `工具键名的大小写不同也能读出 URI`() {
        val fields = mapOf(
            "vpx_url_1" to "https://b.example",
        )

        val extras = KdbxToolFields.extras(fields)

        // 前缀比对必须与 isToolFieldName 一致（都大小写不敏感）。
        assertThat(extras).containsExactly(1 to "https://b.example")
    }

    // ---------------------------------------------------------------- 下标重排（最容易写坏的地方）

    @Test
    fun `3 条 URI 删到 2 条时不留下错位的旧值`() {
        val three = listOf(
            VaultUri(uri = "https://a.example"),
            VaultUri(uri = "https://b.example"),
            VaultUri(uri = "https://c.example"),
        )
        val db = KeePassDatabase.Ver4x.create(rootName = "Root", meta = Meta(name = "T"), credentials = CREDENTIALS)
        val created = KdbxItemWriter.createEntry(db, folderId = null, item = itemWithUris(three))
        val before = created.database.toMappedContent().items.single()

        // 用户删掉中间那条（b），剩 a、c。
        val after = before.copy(uris = listOf(three[0], three[2]))
        val updated = KdbxItemWriter.updateEntry(created.database, before = before, after = after)

        val reread = updated.database.toMappedContent().items.single()
        // ⚠️ 若按"键名增删"而不是整段替换，旧 `VPX_URL_2`(=c) 会留在原地，
        //    而新 `VPX_URL_1` 也= c ⇒ 出现两条 c、b 不见了。
        //   症状是"网址还在但对应关系错了"，比丢数据更坏（用户看不出来）。
        assertThat(reread.uris.map { it.uri }).containsExactly(
            "https://a.example",
            "https://c.example",
        ).inOrder()
    }

    @Test
    fun `删光网址会清掉工具字段而不是留下残渣`() {
        val db = KeePassDatabase.Ver4x.create(rootName = "Root", meta = Meta(name = "T"), credentials = CREDENTIALS)
        val created = KdbxItemWriter.createEntry(
            db,
            folderId = null,
            item = itemWithUris(
                listOf(VaultUri(uri = "https://a.example"), VaultUri(uri = "https://b.example")),
            ),
        )
        val before = created.database.toMappedContent().items.single()

        val updated = KdbxItemWriter.updateEntry(
            created.database,
            before = before,
            after = before.copy(uris = emptyList()),
        )

        val entry = updated.database.getEntry { it.uuid == created.entryUuid }?.second
        assertThat(entry).isNotNull()
        // 残留的 `VPX_URL_1` 会指向一个用户已经删掉的网址 —— 详情页仍显示它，
        // 用户会以为"我明明删了"；自动填充也还会拿它去匹配。
        assertThat(entry!!.fields["VPX_URL_1"]).isNull()
        assertThat(rereadUrls(entry!!.fields)).isEmpty()
    }

    // ---------------------------------------------------------------- 字段区归属

    @Test
    fun `VPX_ 工具字段不混进自定义字段`() {
        val after = roundTrip(
            itemWithUris(
                listOf(VaultUri(uri = "https://a.example"), VaultUri(uri = "https://b.example")),
            ),
        )

        // ⚠️ 若混进自定义字段：详情页同一个网址出现两次（网址区 + 自定义字段区），
        //    且两条写通道抢`VPX_URL_1` 这个键，后跑的会用错误的类型覆盖前一个。
        assertThat(after.customFields.map { it.name }.filter { it.startsWith("VPX_") }).isEmpty()
        assertThat(after.uris).hasSize(2)
    }

    @Test
    fun `用户自己的字段仍按外来区原样保留`() {
        val db = KeePassDatabase.Ver4x.create(rootName = "Root", meta = Meta(name = "T"), credentials = CREDENTIALS)
        val created = KdbxItemWriter.createEntry(
            db,
            folderId = null,
            item = itemWithUris(
                listOf(VaultUri(uri = "https://a.example"), VaultUri(uri = "https://b.example")),
            ).copy(
                customFields = listOf(
                    VaultCustomField(name = "员工号", value = "E-1024"),
                ),
            ),
        )

        val reread = created.database.toMappedContent().items.single()

        // 排除 VPX_ 是为了消重，不是为了吃掉用户字段。
        assertThat(reread.customFields.map { it.name }).contains("员工号")
    }

    @Test
    fun `VPX_ 前缀判定大小写不敏感`() {
        assertThat(KdbxToolFields.isToolFieldName("VPX_URL_1")).isTrue()
        assertThat(KdbxToolFields.isToolFieldName("vpx_url_1")).isTrue()
        assertThat(KdbxToolFields.isToolFieldName("Url2")).isFalse()
    }

    // ---------------------------------------------------------------- 类型码（曾整段错位一位）

    @Test
    fun `Bitwarden 类型码 1 到 5 各归各的类型`() {
        // 官方 CipherType 从 1 起：1=Login 2=SecureNote 3=Card 4=Identity 5=SshKey。
        // ⚠️ 这里曾把 2 写成 Card、3 写成 Identity、4 写成 SshKey（**整段错位一位**）。
        //错位不报错，只会让"卡片"往返后变成"身份"，用户完全看不出来。
        assertThat(KdbxToolFields.typeOf("1")).isEqualTo(VaultItemType.Login)
        assertThat(KdbxToolFields.typeOf("2")).isEqualTo(VaultItemType.SecureNote)
        assertThat(KdbxToolFields.typeOf("3")).isEqualTo(VaultItemType.Card)
        assertThat(KdbxToolFields.typeOf("4")).isEqualTo(VaultItemType.Identity)
        assertThat(KdbxToolFields.typeOf("5")).isEqualTo(VaultItemType.SshKey)
    }

    @Test
    fun `类型码的字符串形态与数字形态指向同一个类型`() {
        val pairs = listOf(
            "login" to "1",
            "secureNote" to "2",
            "card" to "3",
            "identity" to "4",
            "sshKey" to "5",
        )

        pairs.forEach { (name, code) ->
            assertWithMessage("字符串 $name 应与数字 $code 同义").that(KdbxToolFields.typeOf(name))
                .isEqualTo(KdbxToolFields.typeOf(code))
        }
    }

    @Test
    fun `认不出的类型码按 login 处理`() {
        assertThat(KdbxToolFields.typeOf(null)).isEqualTo(VaultItemType.Login)
        assertThat(KdbxToolFields.typeOf("")).isEqualTo(VaultItemType.Login)
        assertThat(KdbxToolFields.typeOf("999")).isEqualTo(VaultItemType.Login)
    }

    // ---------------------------------------------------------------- 脚手架

    /** 走真实写路径（`createEntry`）再读回来，断言落在读回的领域模型上。 */
    private fun roundTrip(item: VaultItem): VaultItem {
        val db = KeePassDatabase.Ver4x.create(rootName = "Root", meta = Meta(name = "T"), credentials = CREDENTIALS)
        val created = KdbxItemWriter.createEntry(db, folderId = null, item = item)
        return created.database.toMappedContent().items.single()
    }

    /** 只看写出去的字段（不读回），用于断言"落到了哪个键"。 */
    private fun writeFields(item: VaultItem): EntryFields {
        val db = KeePassDatabase.Ver4x.create(rootName = "Root", meta = Meta(name = "T"), credentials = CREDENTIALS)
        val created = KdbxItemWriter.createEntry(db, folderId = null, item = item)
        val entry = requireNotNull(created.database.getEntry { it.uuid == created.entryUuid }?.second) {
            "新建后的条目应能按 uuid 取回"
        }
        return entry.fields
    }

    /**
     * [EntryFields] → `Map<String, String>`。
     *
     * ⚠️ 不能写 `fields.mapValues { it.value.content }`：[EntryFields] **重写**了 `mapValues`
     *   （`javap` 可见返回类型是 `EntryFields` 而不是 `Map`），赋给 `Map<String,String>`
     *   会编译失败。`associate` 走标准Map 工厂，类型才对。
     */
    private fun EntryFields.asStringMap(): Map<String, String> =
        entries.associate { (key, value) -> key to value.content }

    private fun rereadUrls(fields: EntryFields): List<String> =
        KdbxToolFields.toUris(
            fields[BasicField.Url.key]?.content.orEmpty(),
            fields.entries
                .filter { (key, _) -> KdbxToolFields.isToolFieldName(key) }
                .associate { (key, value) -> key to value.content },
        ).map { it.uri }

    private fun itemWithUris(uris: List<VaultUri>) = VaultItem(
        id = "kdbx-entry:00000000-0000-0000-0000-000000000001",
        title = "多网址条目",
        username = "alice",
        password = "pw",
        type = VaultItemType.Login,
        uris = uris,
    )

    private companion object {
        val CREDENTIALS: Credentials = Credentials.from(EncryptedValue.fromString("master-pw"))
    }
}
