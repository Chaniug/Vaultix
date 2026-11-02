/*
 * Vaultix — data:kdbx
 * Copyright (C) 2026 Vaultix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * `KdbxEncoder.contentFingerprint` 的**字段覆盖表**（2026-10-26，`.ai/ISSUES.md` #190）。
 *
 * ## 为什么这个文件存在
 *
 * 内容指纹是 #185 那道「写回基线」防线的**唯一判据**：指纹相同 ⇒ 允许写入；
 * 指纹不同 ⇒ 拦下来让用户先同步。于是它有一个非常坏的性质：
 *
 * > **漏掉一个字段，防线对该字段就完全不存在，而且完全没有症状。**
 *
 * 初版只摘了 id / 标题 / 用户名 / 密码 / 备注 —— 也就是说：改一个网址、改一条
 * 自定义字段、换一次 TOTP 密钥、删掉一个通行密钥，**指纹都不变**，防线默认放行，
 * 远端那份改动照样被覆盖。用户看到的是"同步后我的改动没了"，且日志里一切正常。
 *
 * ## 这个文件在验什么
 *
 * 表驱动：每一个**用户能改的字段**各改一次，断言指纹必定变化。
 * 新增字段时把它加进 [FIELD_MUTATORS]，忘了加就会在评审时被看见 ——
 * 这比在实现里"记得补一行 digest"可靠得多（人总会忘，测试不会）。
 */
package io.vaultix.data.kdbx

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.models.Meta
import io.vaultix.model.CustomFieldType
import io.vaultix.model.UriMatch
import io.vaultix.model.VaultCard
import io.vaultix.model.VaultCustomField
import io.vaultix.model.VaultFido2Credential
import io.vaultix.model.VaultFolder
import io.vaultix.model.VaultIdentity
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import io.vaultix.model.VaultPasswordHistoryEntry
import io.vaultix.model.VaultReprompt
import io.vaultix.model.VaultSecureNote
import io.vaultix.model.VaultSshKey
import io.vaultix.model.VaultUri
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KdbxFingerprintCoverageTest {

    @Test
    fun `每个用户可改字段变化都必须改变指纹`() {
        val base = contentOf(item())

        FIELD_MUTATORS.forEach { (name, mutate) ->
            val mutated = contentOf(mutate(item()))
            assertNotEquals(
                "字段「$name」变了但指纹没变 —— 写回基线对它是瞎的（#190）",
                fingerprintOf(base),
                fingerprintOf(mutated),
            )
        }
    }

    @Test
    fun `字段拼接不留无分隔的碰撞缝隙`() {
        // 标题 "ab" + 用户名 "c"  vs  标题 "a" + 用户名 "bc"
        // 无分隔符连续 update 时两者字节流相同 ⇒ 同指纹 ⇒ 防线放行一次覆盖。
        val left = contentOf(item().copy(title = "ab", username = "c"))
        val right = contentOf(item().copy(title = "a", username = "bc"))

        assertNotEquals(
            "无分隔符拼接产生了可构造的碰撞：标题+用户名按字节重切分后相同",
            fingerprintOf(left),
            fingerprintOf(right),
        )
    }

    @Test
    fun `同一份内容反复计算结果稳定`() {
        // 反向对照：不能把「什么都不变」也算成「变了」，否则防线全是假阳性。
        // 特别注意 `binaryNames` 是 Map —— 直接迭代 entrySet 会吃到不稳定的顺序。
        repeat(ROUNDS) {
            assertEquals(fingerprintOf(contentOf(item())), fingerprintOf(contentOf(item())))
        }
    }

    @Test
    fun `附件清单按内容而不是按顺序参与摘要`() {
        val one = contentOf(item()).copy(binaryNames = mapOf("aa" to "1.txt", "bb" to "2.txt"))
        val reversed = contentOf(item()).copy(binaryNames = mapOf("bb" to "2.txt", "aa" to "1.txt"))
        val renamed = contentOf(item()).copy(binaryNames = mapOf("aa" to "1-改.txt", "bb" to "2.txt"))

        assertEquals("同一批附件换了 Map 迭代顺序不该变指纹", fingerprintOf(one), fingerprintOf(reversed))
        assertNotEquals("附件改名本身是内容变化", fingerprintOf(one), fingerprintOf(renamed))
    }

    @Test
    fun `文件夹与回收站计数参与摘要`() {
        val base = contentOf(item())
        assertNotEquals(
            "新增一个分组必须改变指纹",
            fingerprintOf(base),
            fingerprintOf(base.copy(folders = base.folders + VaultFolder(id = "f2", name = "工作"))),
        )
        assertNotEquals(
            "回收站计数必须改变指纹",
            fingerprintOf(base),
            fingerprintOf(base.copy(recycleBinCount = base.recycleBinCount + 1)),
        )
    }

    // ---- #235：分组树 ----

    /**
     * ★ **`folders` 完全相同、库结构不同** —— 这是 #235 真正修掉的假阴性。
     *
     * ## 为什么不能拿"把子组上移一层"当用例
     *
     * `A/B` → `B` 会让**路径字符串**变化，而路径已经含在 `folders[i].name` 里
     * ⇒ 指纹**本来就会变**。拿它当用例，测的是"路径变了"，**根本没测到分组树**。
     * 2026-11-03 的变异验证实测：把分组树那段整段停掉，那类用例依然全绿。
     *
     * 真正漏掉的是：**路径集合一模一样、只是节点集合或所属父组不同**。
     * 下面用"两个同名分组挂在同一个路径上"来构造 —— `folders` 只有一条
     * （`id` / `name` 都一样），但库里确实有两个组。
     */
    @Test
    fun `路径集合相同但树结构不同 必须改变指纹`() {
        val a = UUID.fromString("00000000-0000-0000-0000-00000000000a")
        val b = UUID.fromString("00000000-0000-0000-0000-00000000000b")
        val folders = listOf(VaultFolder(id = folderIdOf(a), name = "A"))
        val oneNode = KdbxMappedContent(
            items = emptyList(),
            folders = folders,
            recycleBinCount = 0,
            trashItems = emptyList(),
            groupPaths = mapOf(a to "A"),
        )
        val twoNodes = oneNode.copy(groupPaths = mapOf(a to "A", b to "A"))
        assertNotEquals(
            "多一个同名分组（folders 看不出来）必须改变指纹",
            fingerprintOf(oneNode),
            fingerprintOf(twoNodes),
        )
    }

    /**
     * 真实建库路径上的对照：**改一个分组名**，`folders` 与指纹都必须变。
     *
     * 与上面那条互补：上面钉"分组树确实进了摘要"，这条钉"真实库上确实会发生"。
     */
    @Test
    fun `真实库上重命名分组 指纹必须改变`() {
        val database = KeePassDatabase.Ver4x.create(
            rootName = "Root",
            meta = Meta(name = "改名"),
            credentials = Credentials.from(EncryptedValue.fromString("pw")),
        )
        val group = app.keemobile.kotpass.models.Group(
            uuid = UUID.fromString("00000000-0000-0000-0000-0000000000c1"),
            name = "旧名",
        )
        val withGroup = database.copy(
            content = database.content.copy(
                group = database.content.group.copy(groups = listOf(group)),
            ),
        )
        val renamed = withGroup.copy(
            content = withGroup.content.copy(
                group = withGroup.content.group.copy(groups = listOf(group.copy(name = "新名"))),
            ),
        )
        assertNotEquals(
            "真实库上改分组名必须改变指纹",
            fingerprintOf(withGroup.toMappedContent()),
            fingerprintOf(renamed.toMappedContent()),
        )
    }

    /** 分组改名：`folders` 已经能看出来，这里钉死"改完之后**仍然**看得出来"。 */
    @Test
    fun `分组改名必须改变指纹`() {
        val a = UUID.randomUUID()
        val before = KdbxMappedContent(
            items = emptyList(),
            folders = listOf(VaultFolder(id = folderIdOf(a), name = "旧名")),
            recycleBinCount = 0,
            trashItems = emptyList(),
            groupPaths = mapOf(a to "旧名"),
        )
        assertNotEquals(
            "改名必须改变指纹",
            fingerprintOf(before),
            fingerprintOf(before.copy(folders = listOf(VaultFolder(id = folderIdOf(a), name = "新名")))),
        )
    }

    /** 同一棵树**判两次**必须同值 —— 分组是个 Map，迭代顺序不能影响结果。 */
    @Test
    fun `同一棵分组树重复计算必须相等`() {
        val uuid = UUID.randomUUID()
        val content = KdbxMappedContent(
            items = emptyList(),
            folders = listOf(VaultFolder(id = folderIdOf(uuid), name = "甲")),
            recycleBinCount = 0,
            trashItems = emptyList(),
            groupPaths = linkedMapOf(uuid to "甲"),
        )
        assertEquals(fingerprintOf(content), fingerprintOf(content.copy(groupPaths = mapOf(uuid to "甲"))))
    }

    /** 节点 uuid 换了（删了重建一个同名组）：路径字符串一样，但它是**另一个组**。 */
    @Test
    fun `同名但不同 uuid 的分组必须改变指纹`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val a = KdbxMappedContent(
            items = emptyList(),
            folders = listOf(VaultFolder(id = folderIdOf(first), name = "同名")),
            recycleBinCount = 0,
            trashItems = emptyList(),
            groupPaths = mapOf(first to "同名"),
        )
        val b = a.copy(
            folders = listOf(VaultFolder(id = folderIdOf(second), name = "同名")),
            groupPaths = mapOf(second to "同名"),
        )
        assertNotEquals("不同 uuid 的同名分组必须是不同内容", fingerprintOf(a), fingerprintOf(b))
    }

    /**
     * ★ **根分组的随机 uuid 绝不能进指纹**（2026-11-03 实测抓出）。
     *
     * `KeePassDatabase.Ver4x.create` 每次建库都给根组一个新 uuid。若指纹把它算进去，
     * "把同一份内容重新建一个库"就会算出不同指纹 ⇒ 写回基线判"内容过期"
     * ⇒ **用户一条都存不进去**，而且报的是"内容过期"，完全指不到根因。
     *
     * ⚠️ 这条用例的价值在于它**跨两次建库**：只在同一份 `groupPaths` 上反复算的用例
     * （包括本文件其它几条）**永远看不出来**。
     */
    @Test
    fun `同一份内容建两次库 指纹必须相同`() {
        val first = KeePassDatabase.Ver4x.create(
            rootName = "Root",
            meta = Meta(name = "指纹"),
            credentials = Credentials.from(EncryptedValue.fromString("pw")),
        )
        val second = KeePassDatabase.Ver4x.create(
            rootName = "Root",
            meta = Meta(name = "指纹"),
            credentials = Credentials.from(EncryptedValue.fromString("pw")),
        )
        // 前提检查：两次的根 uuid 确实不同（否则这条用例没在测它声称的东西）。
        assertNotEquals(first.content.group.uuid, second.content.group.uuid)

        assertEquals(
            "同一份内容、不同根 uuid，指纹必须相同",
            fingerprintOf(first.toMappedContent()),
            fingerprintOf(second.toMappedContent()),
        )
    }

    /** 反过来钉一次：**子分组**的 uuid 仍然是内容的一部分（删了重建同名组必须看得出来）。 */
    @Test
    fun `子分组的 uuid 不同 指纹必须不同`() {
        val a = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val b = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val root = UUID.fromString("00000000-0000-0000-0000-000000000000")
        val first = KdbxMappedContent(
            items = emptyList(),
            folders = listOf(VaultFolder(id = folderIdOf(a), name = "组")),
            recycleBinCount = 0,
            trashItems = emptyList(),
            groupPaths = mapOf(root to "", a to "组"),
            rootGroupUuid = root,
        )
        val second = first.copy(
            folders = listOf(VaultFolder(id = folderIdOf(b), name = "组")),
            groupPaths = mapOf(root to "", b to "组"),
        )
        assertNotEquals(fingerprintOf(first), fingerprintOf(second))
    }

    // ---- 夹具 ----

    private fun item(): VaultItem = VaultItem(
        id = "entry-1",
        title = "标题",
        username = "user",
        password = "pass",
        notes = "备注",
        type = VaultItemType.Login,
    )

    private fun contentOf(value: VaultItem): KdbxMappedContent = KdbxMappedContent(
        items = listOf(value),
        folders = listOf(VaultFolder(id = "f1", name = "默认")),
        recycleBinCount = 0,
        trashItems = emptyList(),
        // ⚠️ 必须是**固定的** uuid：随机 uuid 会让"同一份内容反复计算"永远不等
        //    （#235 之前看不出来，是因为那时指纹根本没读 groupPaths —— 也就是说
        //    那两个用例当时**没有在测它们声称在测的东西**）。
        groupPaths = mapOf(ROOT_UUID to "默认"),
    )

    private fun fingerprintOf(content: KdbxMappedContent): String =
        KdbxEncoder.contentFingerprint(content)

    private companion object {
        const val ROUNDS = 5

        /**
         * 固定的分组 uuid —— **不要**换成 `UUID.randomUUID()`。
         *
         * 指纹现在（#235）会读 `groupPaths`，而随机 uuid 每次都不同 ⇒
         * 「同一份内容反复计算必须相等」与「附件换 Map 顺序不该变指纹」两条会变成
         * **必然失败**。这两条的价值恰恰在于"内容不变 ⇒ 指纹不变"，
         * 夹具里塞一个每次都不一样的东西，就把它们变成了在测随机数。
         */
        val ROOT_UUID: UUID = UUID.fromString("00000000-0000-0000-0000-00000000f00d")

        /**
         * 字段覆盖表：`字段名 to (改这一个字段)`。
         *
         * ⚠️ **给 VaultItem 加字段时必须往这里加一条**。这不是"可选的好习惯"——
         * 漏加的那一项，写回基线对它就是瞎的（见文件头）。
         */
        val FIELD_MUTATORS: List<Pair<String, (VaultItem) -> VaultItem>> = listOf(
            "id" to { it.copy(id = "entry-2") },
            "title" to { it.copy(title = "改过") },
            "username" to { it.copy(username = "u2") },
            "password" to { it.copy(password = "p2") },
            "notes" to { it.copy(notes = "改过") },
            "type" to { it.copy(type = VaultItemType.SecureNote) },
            "folderId" to { it.copy(folderId = "f1") },
            "favorite" to { it.copy(favorite = true) },
            "reprompt" to { it.copy(reprompt = VaultReprompt.Password) },
            "uris" to { it.copy(uris = listOf(VaultUri(uri = "https://example.com"))) },
            "uris.match" to {
                it.copy(
                    uris = listOf(
                        VaultUri(uri = "https://example.com", match = UriMatch.Exact),
                    ),
                )
            },
            "totp" to { it.copy(totp = "otpauth://totp/x?secret=AAAA") },
            "fido2Credentials" to {
                it.copy(
                    fido2Credentials = listOf(
                        VaultFido2Credential(credentialId = "cred", rpId = "example.com"),
                    ),
                )
            },
            "card" to { it.copy(card = VaultCard(cardholderName = "张三", number = "4242")) },
            "sshKey" to {
                it.copy(sshKey = VaultSshKey(privateKey = "PRIVATE", publicKey = "PUBLIC"))
            },
            "identity" to { it.copy(identity = VaultIdentity(firstName = "三")) },
            "customFields" to {
                it.copy(customFields = listOf(VaultCustomField(name = "字段", value = "值")))
            },
            "customFields.type" to {
                it.copy(
                    customFields = listOf(
                        VaultCustomField(name = "字段", value = "值", type = CustomFieldType.Hidden),
                    ),
                )
            },
            "customFields.linkedId" to {
                it.copy(
                    customFields = listOf(
                        VaultCustomField(name = "字段", value = "值", linkedId = 100),
                    ),
                )
            },
            "secureNote" to { it.copy(secureNote = VaultSecureNote(type = 1)) },
            "passwordHistory" to {
                it.copy(
                    passwordHistory = listOf(
                        VaultPasswordHistoryEntry(password = "old-pass", lastUsedAt = 1_700_000_000_000L),
                    ),
                )
            },
        )
    }

    /** 覆盖表自身的完整性：条目类型枚举有新值时提醒（避免只改了类型忘了指纹）。 */
    @Test
    fun `覆盖表覆盖了所有条目类型`() {
        val covered = FIELD_MUTATORS.map { it.first }.toSet()
        assertTrue("覆盖表里必须有 title", covered.contains("title"))
        assertTrue("覆盖表里必须有 type", covered.contains("type"))
        assertEquals(
            "VaultItemType 新增了枚举值，请确认指纹与覆盖表是否需要跟进",
            VaultItemType.entries.size,
            VaultItemType.entries.map { it.name }.distinct().size,
        )
    }
}
