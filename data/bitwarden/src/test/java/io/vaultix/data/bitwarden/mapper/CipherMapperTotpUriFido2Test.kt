package io.vaultix.data.bitwarden.mapper

import io.vaultix.crypto.SymmetricCryptoKey
import io.vaultix.crypto.VaultixCrypto
import io.vaultix.data.bitwarden.model.CardDto
import io.vaultix.data.bitwarden.model.CipherDto
import io.vaultix.data.bitwarden.model.Fido2CredentialDto
import io.vaultix.data.bitwarden.model.LoginDto
import io.vaultix.data.bitwarden.model.SshKeyDto
import io.vaultix.data.bitwarden.model.UriDto
import io.vaultix.data.bitwarden.model.toStoredCipherDto
import io.vaultix.model.UriMatch
import io.vaultix.model.VaultCard
import io.vaultix.model.VaultFido2Credential
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import io.vaultix.model.VaultSshKey
import io.vaultix.model.VaultUri
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 登录条目字段保真回归（2026-09-08 本轮修复）：
 * 修复前 [VaultItem] 仅 6 字段，[CipherMapper.toDomain] 也只读 6 字段，导致
 * login.uris / login.totp / login.fido2Credentials 虽在 DTO 中却从未进入领域模型——
 * 即「验证码与通行密钥读不到」。本测试锁定：读得到、写得出、往返不丢。
 */
class CipherMapperTotpUriFido2Test {

    private val crypto = VaultixCrypto(Dispatchers.Default)
    private val mapper = CipherMapper(crypto)
    private val accountKey = SymmetricCryptoKey.random()

    @Test
    fun toDomainReadsLoginUrisTotpAndFido2() {
        val stored = CipherDto(
            id = "c1",
            type = 1,
            name = crypto.encryptString("站点", accountKey),
            login = LoginDto(
                username = crypto.encryptString("u@x.com", accountKey),
                password = crypto.encryptString("p", accountKey),
                uris = listOf(
                    UriDto(uri = crypto.encryptString("https://x.com/login", accountKey), match = 1),
                    UriDto(uri = crypto.encryptString("https://y.com", accountKey), match = null),
                ),
                totp = crypto.encryptString("JBSWY3DPEHPK3PXP", accountKey),
                fido2Credentials = listOf(
                    Fido2CredentialDto(
                        credentialId = crypto.encryptString("cid-1", accountKey),
                        rpId = crypto.encryptString("x.com", accountKey),
                        rpName = crypto.encryptString("X Corp", accountKey),
                        userName = crypto.encryptString("u@x.com", accountKey),
                        userDisplayName = crypto.encryptString("User X", accountKey),
                        creationDate = crypto.encryptString("2026-01-01T00:00:00Z", accountKey),
                    ),
                ),
            ),
        )

        val item = mapper.toDomain(stored, accountKey)

        assertEquals(
            listOf(
                VaultUri("https://x.com/login", UriMatch.Host),
                VaultUri("https://y.com", null),
            ),
            item.uris,
        )
        assertEquals("JBSWY3DPEHPK3PXP", item.totp)
        assertEquals(1, item.fido2Credentials.size)
        val fido = item.fido2Credentials[0]
        assertEquals("cid-1", fido.credentialId)
        assertEquals("x.com", fido.rpId)
        assertEquals("X Corp", fido.rpName)
        assertEquals("u@x.com", fido.userName)
        assertEquals("User X", fido.userDisplayName)
        assertEquals("2026-01-01T00:00:00Z", fido.creationDate)
    }

    @Test
    fun toRequestWritesLoginUrisTotpAndFido2() {
        val item = VaultItem(
            id = "c2",
            title = "站点",
            username = "u@x.com",
            password = "p",
            type = VaultItemType.Login,
            uris = listOf(VaultUri("https://x.com", UriMatch.Exact)),
            totp = "JBSWY3DPEHPK3PXP",
            // 新增/保存的通行密钥必须随登录条目一并写回（绑定到密码条目）
            fido2Credentials = listOf(
                VaultFido2Credential(
                    credentialId = "new-cid",
                    rpId = "x.com",
                    rpName = "X Corp",
                    userName = "u@x.com",
                    keyAlgorithm = "ECDSA",
                    counter = 4,
                ),
            ),
        )

        val request = mapper.toRequest(item, accountKey)

        assertEquals(1, request.type)
        assertEquals(
            "https://x.com",
            crypto.decryptToString(request.login!!.uris!!.single().uri!!, accountKey),
        )
        assertEquals(3, request.login!!.uris!!.single().match) // Exact = 3
        assertEquals(
            "JBSWY3DPEHPK3PXP",
            crypto.decryptToString(request.login!!.totp!!, accountKey),
        )
        // 通行密钥随登录条目写回：credentialId / rpId / rpName / keyAlgorithm / counter 均加密落地
        assertEquals(1, request.login!!.fido2Credentials.size)
        val written = request.login!!.fido2Credentials[0]
        assertEquals("new-cid", crypto.decryptToString(written.credentialId!!, accountKey))
        assertEquals("x.com", crypto.decryptToString(written.rpId!!, accountKey))
        assertEquals("X Corp", crypto.decryptToString(written.rpName!!, accountKey))
        assertEquals("ECDSA", crypto.decryptToString(written.keyAlgorithm!!, accountKey))
        assertEquals("4", crypto.decryptToString(written.counter!!, accountKey))
        // creationDate 不加密（Bitwarden 期望可解析 DateTime）
        assertEquals(true, written.creationDate?.startsWith("20"))
    }

    @Test
    fun toUpdateRequestMergesFido2IntoStoredLogin() {
        // 既有登录条目已有一个通行密钥（服务端密文）
        val stored = CipherDto(
            id = "c9",
            type = 1,
            name = crypto.encryptString("站点", accountKey),
            login = LoginDto(
                username = crypto.encryptString("u@x.com", accountKey),
                password = crypto.encryptString("p", accountKey),
                fido2Credentials = listOf(
                    Fido2CredentialDto(credentialId = crypto.encryptString("existing-cid", accountKey)),
                ),
            ),
        )
        val existingItem = mapper.toDomain(stored, accountKey)
        // 保存流程：在已加载的 fido2 列表上追加一个新凭证，再更新条目
        val updatedItem = existingItem.copy(
            fido2Credentials = existingItem.fido2Credentials + VaultFido2Credential(
                credentialId = "added-cid",
                rpId = "x.com",
                rpName = "X Corp",
            ),
        )

        val request = mapper.toUpdateRequest(updatedItem, stored, accountKey)

        assertEquals(2, request.login!!.fido2Credentials.size)
        val ids = request.login!!.fido2Credentials.map {
            crypto.decryptToString(it.credentialId!!, accountKey)
        }
        assertEquals(listOf("existing-cid", "added-cid"), ids)
    }

    @Test
    fun standaloneTotpIsLoginWithOnlyTotp() {
        // 独立验证码 = password 为空的 Login Cipher；toRequest 落库后被 toDomain 原样读回
        val item = VaultItem(
            id = "c10",
            title = "Steam Guard",
            username = "",
            password = "",
            type = VaultItemType.Login,
            totp = "otpauth://totp/Steam:alice?secret=MTIz&issuer=Steam",
        )
        val request = mapper.toRequest(item, accountKey)
        val stored = request.toStoredCipherDto(id = "c10", revisionDate = "r")
        val back = mapper.toDomain(stored, accountKey)

        assertEquals(VaultItemType.Login, back.type)
        assertEquals("", back.password)
        assertEquals("otpauth://totp/Steam:alice?secret=MTIz&issuer=Steam", back.totp)
    }

    @Test
    fun roundTripPreservesUrisAndTotp() {
        val item = VaultItem(
            id = "c3",
            title = "站点",
            username = "u@x.com",
            password = "p",
            uris = listOf(
                VaultUri("https://x.com", UriMatch.Host),
                VaultUri("https://y.com", UriMatch.Domain),
            ),
            totp = "JBSWY3DPEHPK3PXP",
        )
        val request = mapper.toRequest(item, accountKey)
        val stored = request.toStoredCipherDto(id = "c3", revisionDate = "r1")
        val back = mapper.toDomain(stored, accountKey)

        assertEquals(item.uris, back.uris)
        assertEquals(item.totp, back.totp)
    }

    @Test
    fun toDomainReadsCard() {
        val stored = CipherDto(
            id = "card1",
            type = 3,
            name = crypto.encryptString("招行卡", accountKey),
            card = CardDto(
                cardholderName = crypto.encryptString("张三", accountKey),
                brand = crypto.encryptString("Visa", accountKey),
                number = crypto.encryptString("4111111111111111", accountKey),
                expMonth = crypto.encryptString("08", accountKey),
                expYear = crypto.encryptString("2029", accountKey),
                code = crypto.encryptString("123", accountKey),
            ),
        )
        val item = mapper.toDomain(stored, accountKey)

        assertEquals(VaultItemType.Card, item.type)
        val card = item.card!!
        assertEquals("张三", card.cardholderName)
        assertEquals("Visa", card.brand)
        assertEquals("4111111111111111", card.number)
        assertEquals("08", card.expMonth)
        assertEquals("2029", card.expYear)
        assertEquals("123", card.code)
    }

    @Test
    fun toDomainReadsSshKey() {
        val stored = CipherDto(
            id = "ssh1",
            type = 5,
            name = crypto.encryptString("服务器密钥", accountKey),
            sshKey = SshKeyDto(
                privateKey = crypto.encryptString("-----BEGIN OPENSSH PRIVATE KEY-----\nabc", accountKey),
                publicKey = crypto.encryptString("ssh-ed25519 AAAA...", accountKey),
                keyFingerprint = crypto.encryptString("SHA256:abc", accountKey),
            ),
        )
        val item = mapper.toDomain(stored, accountKey)

        assertEquals(VaultItemType.SshKey, item.type)
        val ssh = item.sshKey!!
        assertEquals("-----BEGIN OPENSSH PRIVATE KEY-----\nabc", ssh.privateKey)
        assertEquals("ssh-ed25519 AAAA...", ssh.publicKey)
        assertEquals("SHA256:abc", ssh.keyFingerprint)
    }

    @Test
    fun toRequestWritesCardAndDropsLogin() {
        val item = VaultItem(
            id = "card2",
            title = "招行卡",
            type = VaultItemType.Card,
            card = VaultCard(
                cardholderName = "张三",
                brand = "Visa",
                number = "4111111111111111",
                expMonth = "08",
                expYear = "2029",
                code = "123",
            ),
        )
        val request = mapper.toRequest(item, accountKey)

        assertEquals(3, request.type)
        assertEquals(null, request.login)
        val card = request.card!!
        assertEquals("张三", crypto.decryptToString(card.cardholderName!!, accountKey))
        assertEquals("Visa", crypto.decryptToString(card.brand!!, accountKey))
        assertEquals("4111111111111111", crypto.decryptToString(card.number!!, accountKey))
        assertEquals("08", crypto.decryptToString(card.expMonth!!, accountKey))
        assertEquals("2029", crypto.decryptToString(card.expYear!!, accountKey))
        assertEquals("123", crypto.decryptToString(card.code!!, accountKey))
    }

    @Test
    fun toRequestWritesSshKeyAndDropsLogin() {
        val item = VaultItem(
            id = "ssh2",
            title = "服务器密钥",
            type = VaultItemType.SshKey,
            sshKey = VaultSshKey(
                privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nabc",
                publicKey = "ssh-ed25519 AAAA...",
                keyFingerprint = "SHA256:abc",
            ),
        )
        val request = mapper.toRequest(item, accountKey)

        assertEquals(5, request.type)
        assertEquals(null, request.login)
        val ssh = request.sshKey!!
        assertEquals(
            "-----BEGIN OPENSSH PRIVATE KEY-----\nabc",
            crypto.decryptToString(ssh.privateKey!!, accountKey),
        )
        assertEquals("ssh-ed25519 AAAA...", crypto.decryptToString(ssh.publicKey!!, accountKey))
        assertEquals("SHA256:abc", crypto.decryptToString(ssh.keyFingerprint!!, accountKey))
    }

    @Test
    fun roundTripPreservesCard() {
        val item = VaultItem(
            id = "card3",
            title = "招行卡",
            type = VaultItemType.Card,
            card = VaultCard(number = "4111111111111111", code = "123", expMonth = "08", expYear = "2029"),
        )
        val request = mapper.toRequest(item, accountKey)
        val stored = request.toStoredCipherDto(id = "card3", revisionDate = "r1")
        val back = mapper.toDomain(stored, accountKey)
        assertEquals(item.card, back.card)
    }

    @Test
    fun roundTripPreservesSshKey() {
        val item = VaultItem(
            id = "ssh3",
            title = "服务器密钥",
            type = VaultItemType.SshKey,
            sshKey = VaultSshKey(publicKey = "ssh-ed25519 AAAA...", keyFingerprint = "SHA256:abc"),
        )
        val request = mapper.toRequest(item, accountKey)
        val stored = request.toStoredCipherDto(id = "ssh3", revisionDate = "r1")
        val back = mapper.toDomain(stored, accountKey)
        assertEquals(item.sshKey, back.sshKey)
    }
}
