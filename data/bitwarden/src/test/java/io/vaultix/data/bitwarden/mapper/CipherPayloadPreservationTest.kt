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
import io.vaultix.model.VaultCard
import io.vaultix.model.VaultFido2Credential
import io.vaultix.model.VaultIdentity
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import io.vaultix.model.VaultReprompt
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

    // ---- 身份 / 银行卡「可编辑」回归（2026-09-08）----
    // 表单已能编辑 card / identity 段，更新必须按表单明文重加密覆盖；
    // 未编辑段与「非本类型」的段仍沿用服务端原密文（防止顺手清掉别的载荷）。

    @Test
    fun updateWritesEditedCardFields() {
        val stored = CipherDto(
            id = "card-1",
            type = 3,
            name = crypto.encryptString("白金卡", accountKey),
            card = CardDto(
                cardholderName = crypto.encryptString("旧持卡人", accountKey),
                brand = crypto.encryptString("Visa", accountKey),
                number = crypto.encryptString("4111111111111111", accountKey),
                expMonth = crypto.encryptString("12", accountKey),
                expYear = crypto.encryptString("2029", accountKey),
                code = crypto.encryptString("123", accountKey),
            ),
        )

        val request = mapper.toUpdateRequest(
            item = VaultItem(
                id = "card-1",
                title = "白金卡",
                type = VaultItemType.Card,
                card = VaultCard(
                    cardholderName = "新持卡人",
                    brand = "Mastercard",
                    number = "5500000000000004",
                    expMonth = "01",
                    expYear = "2030",
                    code = "999",
                ),
            ),
            stored = stored,
            key = accountKey,
        )

        val card = request.card!!
        assertEquals("新持卡人", crypto.decryptToString(card.cardholderName!!, accountKey))
        assertEquals("Mastercard", crypto.decryptToString(card.brand!!, accountKey))
        assertEquals("5500000000000004", crypto.decryptToString(card.number!!, accountKey))
        assertEquals("01", crypto.decryptToString(card.expMonth!!, accountKey))
        assertEquals("2030", crypto.decryptToString(card.expYear!!, accountKey))
        assertEquals("999", crypto.decryptToString(card.code!!, accountKey))
    }

    @Test
    fun updateWritesEditedIdentityFieldsAndClearsBlanks() {
        val stored = CipherDto(
            id = "id-1",
            type = 4,
            name = crypto.encryptString("我的身份", accountKey),
            identity = IdentityDto(
                firstName = crypto.encryptString("旧名", accountKey),
                passportNumber = crypto.encryptString("E1234567", accountKey),
            ),
        )

        val request = mapper.toUpdateRequest(
            item = VaultItem(
                id = "id-1",
                title = "我的身份",
                type = VaultItemType.Identity,
                identity = VaultIdentity(
                    title = "Mr",
                    firstName = "新名",
                    lastName = "新姓",
                    passportNumber = "", // 清空：应写 null，而不是把空串加密上去
                ),
            ),
            stored = stored,
            key = accountKey,
        )

        val identity = request.identity!!
        assertEquals("Mr", crypto.decryptToString(identity.title!!, accountKey))
        assertEquals("新名", crypto.decryptToString(identity.firstName!!, accountKey))
        assertEquals("新姓", crypto.decryptToString(identity.lastName!!, accountKey))
        // 清空字段写 null（Bitwarden 语义），不残留服务端旧值
        assertNull(identity.passportNumber)
    }

    @Test
    fun updateOfLoginDoesNotClobberUnrelatedStoredSegments() {
        val stored = CipherDto(
            id = "login-1",
            type = 1,
            name = crypto.encryptString("站点", accountKey),
            login = LoginDto(username = crypto.encryptString("u", accountKey)),
            card = CardDto(number = "enc:keep-me"),
            sshKey = SshKeyDto(publicKey = "enc:keep-pub"),
        )

        val request = mapper.toUpdateRequest(
            item = VaultItem(
                id = "login-1",
                title = "站点",
                username = "u2",
                type = VaultItemType.Login,
            ),
            stored = stored,
            key = accountKey,
        )

        // 登录段按表单覆盖
        assertEquals("u2", crypto.decryptToString(request.login!!.username!!, accountKey))
        // 非本类型的 card / sshKey 段原样保留，绝不顺手清空
        assertEquals("enc:keep-me", request.card!!.number)
        assertEquals("enc:keep-pub", request.sshKey!!.publicKey)
    }

    // ---- 文件夹 / 收藏 / 主密码二次验证 / 安全笔记子类型（2026-09-08 补入）----
    // 背景：这四个字段此前**未进领域模型** → 拉取时直接丢弃、新建时不上传、
    // 编辑时固定沿用 stored（等于用户在 Vaultix 里根本无法修改）。

    @Test
    fun toDomainReadsFolderFavoriteAndReprompt() {
        val item = mapper.toDomain(
            CipherDto(
                id = "c1",
                type = 1,
                name = crypto.encryptString("条目", accountKey),
                folderId = "folder-abc",
                favorite = true,
                reprompt = 1,
            ),
            accountKey,
        )
        assertEquals("folder-abc", item.folderId)
        assertEquals(true, item.favorite)
        assertEquals(VaultReprompt.Password, item.reprompt)
    }

    @Test
    fun toDomainReadsSecureNoteSubtype() {
        val item = mapper.toDomain(
            CipherDto(
                id = "sn",
                type = 2,
                name = crypto.encryptString("笔记", accountKey),
                secureNote = SecureNoteDto(type = 0),
            ),
            accountKey,
        )
        assertEquals(VaultItemType.SecureNote, item.type)
        assertEquals(0, item.secureNote?.type)
    }

    @Test
    fun toRequestWritesFolderFavoriteRepromptAndSecureNote() {
        val loginRequest = mapper.toRequest(
            item = VaultItem(
                id = "",
                title = "新条目",
                type = VaultItemType.Login,
                folderId = "folder-1",
                favorite = true,
                reprompt = VaultReprompt.Password,
            ),
            key = accountKey,
        )
        assertEquals("folder-1", loginRequest.folderId)
        assertEquals(true, loginRequest.favorite)
        assertEquals(1, loginRequest.reprompt)

        // 安全笔记：即使领域模型没显式给子类型，也要补上通用子类型 0，
        // 否则服务端收到 type=2 却没有 secureNote 段，形态不完整。
        val noteRequest = mapper.toRequest(
            item = VaultItem(id = "", title = "笔记", type = VaultItemType.SecureNote),
            key = accountKey,
        )
        assertEquals(2, noteRequest.type)
        assertEquals(0, noteRequest.secureNote?.type)
    }

    @Test
    fun updateWritesEditedFolderFavoriteAndReprompt() {
        val stored = CipherDto(
            id = "c1",
            type = 1,
            name = crypto.encryptString("旧名", accountKey),
            folderId = "old-folder",
            favorite = false,
            reprompt = 0,
            login = LoginDto(username = crypto.encryptString("u", accountKey)),
        )

        val request = mapper.toUpdateRequest(
            item = VaultItem(
                id = "c1",
                title = "新名",
                type = VaultItemType.Login,
                username = "u",
                folderId = "new-folder", // 用户改了文件夹
                favorite = true, // 用户收藏了
                reprompt = VaultReprompt.Password, // 用户开了主密码二次验证
            ),
            stored = stored,
            key = accountKey,
        )

        // 关键：必须是表单的新值，不能沿用 stored 的旧值
        assertEquals("new-folder", request.folderId)
        assertEquals(true, request.favorite)
        assertEquals(1, request.reprompt)
    }
}
