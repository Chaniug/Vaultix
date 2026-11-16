/*
 * Vaultix — data:bitwarden（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 写方向必须使用**条目自己的那把钥匙**（#295）。
 *
 * ## 用户报的症状
 *
 * 「在已有条目里加/改一条网址，保存后本地能看到，云端没有；点同步也没把云端覆盖下来。」
 *
 * ## 根因
 *
 * 读方向（`toDomain`）是**对的**：先把 `cipher.key`（被账号密钥包裹）解包成条目密钥，
 * 用它解字段，解不开才回退账号密钥 —— 见 `decryptToString`。
 *
 * 写方向（`toRequest` / `toUpdateRequest`）**一律用账号密钥加密**，而请求体里
 * **又没有 `key` 字段**（`CipherRequest` 根本没有声明它）。于是：
 *
 * | 谁 | 用什么解 | 结果 |
 * |---|---|---|
 * | Vaultix 自己 | 条目密钥失败 → 回退账号密钥 | 正常（**所以本地看起来一切正常**） |
 * | 官方客户端 / 网页 / 其它设备 | 只按 `key` 用条目密钥解 | `MacVerificationException` |
 *
 * 服务端只做密文搬运，完全看不出问题，响应里 revisionDate 照常返回新值 ——
 * 这正是「保存成功、云端没变」的全貌。
 *
 * ## 与官方客户端 / Keyguard 的对齐
 *
 * - Bitwarden 官方 `Cipher.toEncryptedNetworkCipher()`：字段是 SDK 用**该条目的
 *   `EncryptionContext`** 加密后的产物，`CipherJsonRequest.key` 只承载包裹态；
 * - Keyguard `buildCipherCodecPair`：`itemCrypto` 用 `key != null ? 条目密钥 : 全局密钥`，
 *   逐字段 `transform(itemCrypto)`，而 `keyBase64` 走 `globalCrypto`。
 *
 * 两边是同一个语义：**字段用条目密钥，`key` 本身用账号（或组织）密钥包裹。**
 *
 * ## 反证纪律
 *
 * 断言落在「**其它客户端**能否解开」上，而不是「Vaultix 自己能否解开」——
 * 后者在本缺陷下**照样通过**（回退掩盖了错误），这正是它此前没被发现的原因。
 */
package io.vaultix.data.bitwarden.mapper

import io.vaultix.crypto.SymmetricCryptoKey
import io.vaultix.crypto.VaultixCrypto
import io.vaultix.data.bitwarden.model.CipherDto
import io.vaultix.data.bitwarden.model.CustomFieldDto
import io.vaultix.data.bitwarden.model.LoginDto
import io.vaultix.data.bitwarden.model.UriDto
import io.vaultix.model.VaultCustomField
import io.vaultix.model.CustomFieldType
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CipherMapperItemKeyWriteTest {

    private val crypto = VaultixCrypto(Dispatchers.Default)
    private val mapper = CipherMapper(crypto)
    private val accountKey = SymmetricCryptoKey.random()
    private val itemKey = SymmetricCryptoKey.random()

    private fun fullKeyBytes(key: SymmetricCryptoKey): ByteArray {
        val enc = key.encKey.useBytes { it.copyOf() }
        val mac = key.macKey.useBytes { it.copyOf() }
        return enc + mac
    }

    /** 官方客户端样式的条目：字段用条目密钥加密，`key` 用账号密钥包裹。 */
    private fun itemKeyProtectedCipher(uri: String): CipherDto {
        val full = fullKeyBytes(itemKey)
        val wrapped = try {
            crypto.encrypt(full, accountKey)
        } finally {
            full.fill(0)
        }
        return CipherDto(
            id = "cipher-with-item-key",
            type = 1,
            revisionDate = "2026-11-16T04:00:00.000000Z",
            name = crypto.encryptString("GitHub", itemKey),
            login = LoginDto(
                username = crypto.encryptString("alice", itemKey),
                password = crypto.encryptString("s3cret", itemKey),
                uris = listOf(UriDto(uri = crypto.encryptString(uri, itemKey), match = 0)),
            ),
            fields = listOf(
                CustomFieldDto(
                    name = crypto.encryptString("PIN", itemKey),
                    value = crypto.encryptString("1234", itemKey),
                    type = 1,
                ),
            ),
            key = wrapped,
        )
    }

    /** 模拟「官方客户端 / 网页」的读法：有条目密钥就只用条目密钥解，绝不回退。 */
    private fun asOtherClient(encString: String?, dto: CipherDto): String? {
        val key = mapper.resolveItemKey(dto, accountKey) ?: return null
        return try {
            runCatching { crypto.decryptToString(encString.orEmpty(), key) }.getOrNull()
        } finally {
            key.clear()
        }
    }

    private fun editUri(dto: CipherDto, newUri: String): VaultItem =
        mapper.toDomain(dto, accountKey).let { domain ->
            domain.copy(uris = listOf(domain.uris.first().copy(uri = newUri)))
        }

    /**
     * ★ 主契约（用户报的那条）：改一条 URI 后，**其它客户端**要能解出新值。
     */
    @Test
    fun `改URI后其它客户端必须能解出新值`() {
        val stored = itemKeyProtectedCipher("https://old.example.com")

        val request = mapper.toUpdateRequest(editUri(stored, "https://new.example.com"), stored, accountKey)

        assertEquals("https://new.example.com", asOtherClient(request.login?.uris?.first()?.uri, stored))
    }

    /** 名称同理：名称也是用户可编辑字段，同样落在条目密钥上。 */
    @Test
    fun `改名后其它客户端必须能解出新值`() {
        val stored = itemKeyProtectedCipher("https://old.example.com")

        val request = mapper.toUpdateRequest(
            editUri(stored, "https://old.example.com").copy(title = "GitHub 新名字"),
            stored,
            accountKey,
        )

        assertEquals("GitHub 新名字", asOtherClient(request.name, stored))
    }

    /** 自定义字段同理（它是最容易被漏掉的一段：走的是另一个 map 函数）。 */
    @Test
    fun `改自定义字段后其它客户端必须能解出新值`() {
        val stored = itemKeyProtectedCipher("https://old.example.com")
        val edited = editUri(stored, "https://old.example.com").copy(
            customFields = listOf(
                VaultCustomField(name = "PIN", value = "9999", type = CustomFieldType.Hidden),
            ),
        )

        val request = mapper.toUpdateRequest(edited, stored, accountKey)

        assertEquals("9999", asOtherClient(request.fields.first().value, stored))
    }

    /** 用户名 / 密码 / TOTP 一并钉住：它们共用同一条 overlay 路径。 */
    @Test
    fun `改密码后其它客户端必须能解出新值`() {
        val stored = itemKeyProtectedCipher("https://old.example.com")
        val edited = editUri(stored, "https://old.example.com").copy(password = "new-secret")

        val request = mapper.toUpdateRequest(edited, stored, accountKey)

        assertEquals("new-secret", asOtherClient(request.login?.password, stored))
    }

    /**
     * ★ 反证的另一半：**没有条目密钥的老条目不能被顺手塞一个 key 进去**。
     *
     * 服务端对请求体里的 `key` 是「带了就用新的」，凭空给一条老个人条目加 key
     * 会让它在其它客户端上变成另一个加密域 —— 属于比原缺陷更坏的破坏。
     */
    @Test
    fun `没有条目密钥的条目写回时不带key`() {
        val stored = CipherDto(
            id = "legacy",
            type = 1,
            name = crypto.encryptString("旧条目", accountKey),
            login = LoginDto(
                username = crypto.encryptString("bob", accountKey),
                uris = listOf(UriDto(uri = crypto.encryptString("https://legacy.example", accountKey))),
            ),
            key = null,
        )

        val request = mapper.toUpdateRequest(editUri(stored, "https://legacy2.example"), stored, accountKey)

        assertNull("老条目写回不得凭空带 key", request.key)
        // 没有条目密钥 ⇒ 账号密钥就是正确的钥匙，其它客户端（也是账号密钥）能解开。
        val decrypted = runCatching {
            crypto.decryptToString(request.login?.uris?.first()?.uri.orEmpty(), accountKey)
        }.getOrNull()
        assertEquals("https://legacy2.example", decrypted)
    }

    /**
     * 条目密钥存在时，请求体要把**原样的包裹态 `key`** 带上。
     *
     * 不带 `key` 并不是"保持不变"：服务端 `CipherRequestModel` 对 key 是整体替换语义，
     * 这次编辑一旦不带，条目在服务端就失去了自己的密钥，而字段仍是条目密钥的密文
     * ⇒ 变成谁的解不开的死数据。
     */
    @Test
    fun `有条目密钥时写回必须原样带上包裹态key`() {
        val stored = itemKeyProtectedCipher("https://old.example.com")

        val request = mapper.toUpdateRequest(editUri(stored, "https://new.example.com"), stored, accountKey)

        assertNotNull("必须带 key", request.key)
        assertEquals("包裹态必须原样透传", stored.key, request.key)
        // 并且它确实能被账号密钥解开（没有被重新加密成别的东西）。
        val unwrapped = runCatching { crypto.decryptSymmetricKey(request.key!!, accountKey) }.getOrNull()
        assertNotNull("key 必须仍可用账号密钥解包", unwrapped)
        unwrapped?.clear()
    }

    /**
     * 新建路径：`toRequest` 一律不带 key（服务端还没有这条记录），
     * 但加密也应当走账号密钥 —— 与写回的「无 key」分支保持同一口径。
     */
    @Test
    fun `新建路径不携带key且可用账号密钥解开`() {
        val item = VaultItem(
            id = "local",
            title = "新条目",
            username = "carol",
            type = VaultItemType.Login,
            uris = listOf(io.vaultix.model.VaultUri("https://new.example", null)),
        )

        val request = mapper.toRequest(item, accountKey)

        assertNull(request.key)
        val decrypted = runCatching {
            crypto.decryptToString(request.login?.uris?.first()?.uri.orEmpty(), accountKey)
        }.getOrNull()
        assertEquals("https://new.example", decrypted)
    }
}
