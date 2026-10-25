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
        groupPaths = mapOf(UUID.randomUUID() to "默认"),
    )

    private fun fingerprintOf(content: KdbxMappedContent): String =
        KdbxEncoder.contentFingerprint(content)

    private companion object {
        const val ROUNDS = 5

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
