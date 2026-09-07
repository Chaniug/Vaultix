package io.vaultix.data.bitwarden.mapper

import io.vaultix.crypto.SymmetricCryptoKey
import io.vaultix.crypto.VaultixCrypto
import io.vaultix.data.bitwarden.model.CipherDto
import io.vaultix.data.bitwarden.model.LoginDto
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * per-item key（条目独立密钥）回归（2026-09-08 真机库抓取：219 条中 31 条带
 * cipher.key）：字段用条目密钥加密时，必须先用账号密钥解包 cipher.key 再解字段；
 * 老数据（无 key）路径保持账号密钥直解。
 */
class CipherMapperItemKeyTest {

    private val crypto = VaultixCrypto(Dispatchers.Default)
    private val mapper = CipherMapper(crypto)
    private val accountKey = SymmetricCryptoKey.random()
    private val itemKey = SymmetricCryptoKey.random()

    private fun fullKeyBytes(key: SymmetricCryptoKey): ByteArray {
        val enc = key.encKey.useBytes { it.copyOf() }
        val mac = key.macKey.useBytes { it.copyOf() }
        return enc + mac
    }

    /** 构造官方客户端样式的条目：字段用 item key 加密，cipher.key 用账号密钥包裹。 */
    private fun itemKeyProtectedCipher(
        title: String,
        username: String,
        password: String,
    ): CipherDto {
        val full = fullKeyBytes(itemKey)
        val wrapped = try {
            crypto.encrypt(full, accountKey)
        } finally {
            full.fill(0)
        }
        return CipherDto(
            id = "cipher-with-item-key",
            type = 1,
            name = crypto.encryptString(title, itemKey),
            login = LoginDto(
                username = crypto.encryptString(username, itemKey),
                password = crypto.encryptString(password, itemKey),
            ),
            key = wrapped,
        )
    }

    @Test
    fun decryptsItemKeyProtectedCipher() {
        val dto = itemKeyProtectedCipher("GitHub", "alice@example.com", "s3cret")

        val item = mapper.toDomain(dto, accountKey)

        assertEquals("GitHub", item.title)
        assertEquals("alice@example.com", item.username)
        assertEquals("s3cret", item.password)
    }

    @Test
    fun decryptsLegacyCipherWithoutKeyWithAccountKey() {
        val dto = CipherDto(
            id = "cipher-legacy",
            type = 1,
            name = crypto.encryptString("旧条目", accountKey),
            login = LoginDto(username = crypto.encryptString("bob", accountKey)),
            key = null,
        )

        val item = mapper.toDomain(dto, accountKey)

        assertEquals("旧条目", item.title)
        assertEquals("bob", item.username)
    }

    @Test
    fun corruptedItemKeyFallsBackToAccountKey() {
        // key 解包失败（用错误密钥包裹）→ 回退账号密钥直解；账号密钥也解不开字段
        val wrongKey = SymmetricCryptoKey.random()
        val full = fullKeyBytes(itemKey)
        val wrapped = try {
            crypto.encrypt(full, wrongKey)
        } finally {
            full.fill(0)
        }
        val dto = CipherDto(
            id = "cipher-bad-key",
            type = 1,
            name = crypto.encryptString("标题", itemKey),
            key = wrapped,
        )
        wrongKey.clear()

        // 不回退成功 → 空串降级，不抛异常（列表可浏览）
        val item = mapper.toDomain(dto, accountKey)
        assertEquals("", item.title)
    }
}
