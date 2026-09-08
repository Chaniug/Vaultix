package io.vaultix.data.bitwarden.mapper

import io.vaultix.crypto.SymmetricCryptoKey
import io.vaultix.crypto.VaultixCrypto
import io.vaultix.data.bitwarden.model.CardDto
import io.vaultix.data.bitwarden.model.CipherDto
import io.vaultix.data.bitwarden.model.CipherRequest
import io.vaultix.data.bitwarden.model.CustomFieldDto
import io.vaultix.data.bitwarden.model.Fido2CredentialDto
import io.vaultix.data.bitwarden.model.IdentityDto
import io.vaultix.data.bitwarden.model.LoginDto
import io.vaultix.data.bitwarden.model.SecureNoteDto
import io.vaultix.data.bitwarden.model.SshKeyDto
import io.vaultix.data.bitwarden.model.UriDto
import io.vaultix.data.bitwarden.model.toStoredCipherDto
import io.vaultix.data.bitwarden.network.BitwardenJson
import io.vaultix.model.UriMatch
import io.vaultix.model.VaultFido2Credential
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import io.vaultix.model.VaultUri
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 条目载荷保真回归（2026-09-08 批 1，Docs/progress/audit/bitwarden-alignment.md
 * M1-1/2/3）：
 * - 更新 = 合并上传：未编辑段（uri/totp/fido2/card/identity/sshKey/secureNote/
 *   fields）沿用服务端原密文，绝不整条重写；
 * - type5（sshKey）显式建模，未知类型不产生领域漂移（写路径另有类型守恒守卫）。
 */
class CipherPayloadPreservationTest {

    private val crypto = VaultixCrypto(Dispatchers.Default)
    private val mapper = CipherMapper(crypto)
    private val accountKey = SymmetricCryptoKey.random()

    // ---- type 映射 ----

    @Test
    fun mapsSshKeyTypeAndRejectsTypeDrift() {
        val ssh = mapper.toDomain(
            CipherDto(id = "ssh", type = 5, name = crypto.encryptString("服务器", accountKey)),
            accountKey,
        )
        assertEquals(VaultItemType.SshKey, ssh.type)
        assertEquals(5, mapper.serverTypeOf(VaultItemType.SshKey))

        // 未知类型（未来服务端新增）落到 Login 只是展示口径；写路径凭
        // serverTypeOf 守恒校验拒绝编辑，不会把 type 改写成 1
        val unknown = mapper.toDomain(
            CipherDto(id = "u", type = 99, name = crypto.encryptString("x", accountKey)),
            accountKey,
        )
        assertEquals(VaultItemType.Login, unknown.type)
        assertEquals(1, mapper.serverTypeOf(VaultItemType.Login))
    }

    // ---- 合并更新：登录载荷 ----

    @Test
    fun updatePreservesLoginUrisTotpFido2AndFields() {
        val stored = CipherDto(
            id = "c1",
            type = 1,
            name = crypto.encryptString("旧名", accountKey),
            login = LoginDto(
                username = crypto.encryptString("old@user", accountKey),
                password = crypto.encryptString("old-pass", accountKey),
                uris = listOf(UriDto(uri = "enc:uri://site", match = 1)),
                totp = "enc:totp-secret",
                fido2Credentials = listOf(Fido2CredentialDto(credentialId = "enc:fido")),
            ),
            fields = listOf(CustomFieldDto(name = "enc:fn", value = "enc:fv", type = 0)),
        )

        val request = mapper.toUpdateRequest(
            item = VaultItem(
                id = "c1",
                title = "新名",
                username = "new@user",
                password = "new-pass",
                uris = listOf(VaultUri("https://site.example", UriMatch.Host)),
                totp = "otpauth-secret",
                // 更新流程的 item 携带已加载的完整通行密钥列表（保存流程即通过替换此列表增删）
                fido2Credentials = listOf(VaultFido2Credential(credentialId = "enc:fido")),
            ),
            stored = stored,
            key = accountKey,
        )

        assertEquals(1, request.type)
        assertEquals("新名", crypto.decryptToString(request.name!!, accountKey))
        assertEquals("new@user", crypto.decryptToString(request.login!!.username!!, accountKey))
        assertEquals("new-pass", crypto.decryptToString(request.login!!.password!!, accountKey))
        // uri/totp 为用户可编辑段：按表单明文重新加密覆盖（非沿用服务端旧密文）
        assertEquals(
            "https://site.example",
            crypto.decryptToString(request.login!!.uris!!.single().uri!!, accountKey),
        )
        assertEquals(1, request.login!!.uris!!.single().match) // Host = 1
        assertEquals("otpauth-secret", crypto.decryptToString(request.login!!.totp!!, accountKey))
        // fido2：按 item 携带的列表完整重加密（保留既有凭证，不丢）
        assertEquals(
            "enc:fido",
            crypto.decryptToString(request.login!!.fido2Credentials!!.single().credentialId!!, accountKey),
        )
        // 自定义字段为只读段：原样保留服务端密文，绝不丢失
        assertEquals("enc:fn", request.fields!!.single().name)
    }

    // ---- 合并更新：非登录载荷 ----

    @Test
    fun updateKeepsCardIdentitySshAndSecureNotePayloads() {
        val stored = CipherDto(
            id = "c3",
            type = 3,
            name = crypto.encryptString("白金卡", accountKey),
            card = CardDto(
                cardholderName = "enc:holder",
                brand = "enc:visa",
                number = "enc:4111",
                expMonth = "enc:12",
                expYear = "enc:29",
                code = "enc:123",
            ),
            identity = IdentityDto(firstName = "enc:fn", lastName = "enc:ln"),
            sshKey = SshKeyDto(publicKey = "enc:pub"),
            secureNote = SecureNoteDto(type = 0),
            fields = listOf(CustomFieldDto(name = "enc:k", value = "enc:v", type = 0)),
        )

        val request = mapper.toUpdateRequest(
            item = VaultItem(id = "c3", title = "白金卡（改名）", type = VaultItemType.Card),
            stored = stored,
            key = accountKey,
        )

        assertEquals(3, request.type)
        assertEquals("白金卡（改名）", crypto.decryptToString(request.name!!, accountKey))
        assertNull(request.login)
        assertEquals("enc:holder", request.card!!.cardholderName)
        assertEquals("enc:4111", request.card!!.number)
        assertEquals("enc:fn", request.identity!!.firstName)
        assertEquals("enc:pub", request.sshKey!!.publicKey)
        assertEquals(0, request.secureNote!!.type)
        assertEquals("enc:v", request.fields!!.single().value)
    }

    // ---- 本地重建行 / 上传体的 JSON 往返无损 ----

    @Test
    fun requestJsonRoundTripKeepsPayloadSections() {
        val request = CipherRequest(
            type = 3,
            name = "enc:name",
            card = CardDto(number = "enc:4111", code = "enc:123"),
            fields = listOf(CustomFieldDto(name = "enc:f", value = null, type = 0)),
        )
        val dto = request.toStoredCipherDto(id = "server-id", revisionDate = "r1")
        val reencoded = BitwardenJson.encodeToString(CipherDto.serializer(), dto)
        val decoded = BitwardenJson.decodeFromString(CipherDto.serializer(), reencoded)

        assertEquals("server-id", decoded.id)
        assertEquals(3, decoded.type)
        assertEquals("enc:name", decoded.name)
        assertEquals("enc:4111", decoded.card!!.number)
        assertNull(decoded.login)
        assertNull(decoded.identity)
    }
}
