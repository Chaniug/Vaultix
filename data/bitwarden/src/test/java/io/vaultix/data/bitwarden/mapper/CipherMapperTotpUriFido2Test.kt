package io.vaultix.data.bitwarden.mapper

import io.vaultix.crypto.SymmetricCryptoKey
import io.vaultix.crypto.VaultixCrypto
import io.vaultix.data.bitwarden.model.CardDto
import io.vaultix.data.bitwarden.model.CipherDto
import io.vaultix.data.bitwarden.model.Fido2CredentialDto
import io.vaultix.data.bitwarden.model.IdentityDto
import io.vaultix.data.bitwarden.model.LoginDto
import io.vaultix.data.bitwarden.model.SshKeyDto
import io.vaultix.data.bitwarden.model.UriDto
import io.vaultix.data.bitwarden.model.toStoredCipherDto
import io.vaultix.model.UriMatch
import io.vaultix.model.VaultCard
import io.vaultix.model.VaultFido2Credential
import io.vaultix.model.VaultIdentity
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

    /**
     * 通行密钥 13 字段**往返保真**（2026-09-09 修复的数据破坏回归）。
     *
     * 修复前 `mapFido2` 只读 8 个字段，`mapFido2Request` 却按 13 字段写回
     * → 编辑一次条目就把服务端已存的 `keyValue`（密钥材料）、`counter`、`discoverable`
     * 覆盖成空/默认值，官方端与其它设备上的这条通行密钥再也签不了名（不可逆）。
     */
    @Test
    fun fido2RoundTripKeepsKeyMaterialCounterAndDiscoverable() {
        val stored = CipherDto(
            id = "c3",
            type = 1,
            name = crypto.encryptString("站点", accountKey),
            login = LoginDto(
                username = crypto.encryptString("u@x.com", accountKey),
                password = crypto.encryptString("p", accountKey),
                fido2Credentials = listOf(
                    Fido2CredentialDto(
                        credentialId = crypto.encryptString("cid-1", accountKey),
                        rpId = crypto.encryptString("x.com", accountKey),
                        keyType = crypto.encryptString("public-key", accountKey),
                        keyCurve = crypto.encryptString("P-256", accountKey),
                        keyValue = crypto.encryptString("PRIVATE-KEY-MATERIAL", accountKey),
                        counter = crypto.encryptString("7", accountKey),
                        discoverable = crypto.encryptString("false", accountKey),
                    ),
                ),
            ),
        )

        val item = mapper.toDomain(stored, accountKey)
        val fido = item.fido2Credentials.single()

        // 读：密钥材料 / 计数器 / 可发现性必须原样进来
        assertEquals("PRIVATE-KEY-MATERIAL", fido.keyValue)
        assertEquals(7L, fido.counter)
        assertEquals(false, fido.discoverable)
        assertEquals("public-key", fido.keyType)
        assertEquals("P-256", fido.keyCurve)

        // 写：再走一次更新，服务端值不能被默认值顶掉
        val request = mapper.toUpdateRequest(item, stored, accountKey)
        val written = request.login!!.fido2Credentials.single()
        assertEquals(
            "PRIVATE-KEY-MATERIAL",
            crypto.decryptToString(written.keyValue!!, accountKey),
        )
        assertEquals("7", crypto.decryptToString(written.counter!!, accountKey))
        assertEquals("false", crypto.decryptToString(written.discoverable!!, accountKey))
    }

    /**
     * ★★★ P0 回归（2026-09-11 真机事故）：模型里 `keyValue` 为空时，更新请求**必须原样
     * 沿用服务端的密文**，绝不能写成 `null`。
     *
     * 修复前：`overlayLogin` 对整张 fido2 表 `mapFido2Request` 重加密，而
     * `encryptOpt(c.keyValue.orEmpty())` 会把空值产出 `null` ⇒ **服务端已存的私钥材料被
     * 覆写抹除**，官方客户端与其它设备此后同样无法签名，且不可恢复。
     */
    @Test
    fun updateKeepsServerKeyValueWhenModelKeyValueIsBlank() {
        val serverKeyValue = crypto.encryptString("PRIVATE-KEY-MATERIAL", accountKey)
        val stored = CipherDto(
            id = "c11",
            type = 1,
            name = crypto.encryptString("站点", accountKey),
            login = LoginDto(
                fido2Credentials = listOf(
                    Fido2CredentialDto(
                        credentialId = crypto.encryptString("cid-p0", accountKey),
                        rpId = crypto.encryptString("github.com", accountKey),
                        keyType = crypto.encryptString("public-key", accountKey),
                        keyValue = serverKeyValue,
                    ),
                ),
            ),
        )

        val loaded = mapper.toDomain(stored, accountKey)
        // 模拟「模型里这条凭据的私钥材料取不到」——解密失败或字段缺失的等价形态
        val item = loaded.copy(
            fido2Credentials = loaded.fido2Credentials.map { it.copy(keyValue = null) },
        )

        val written = mapper.toUpdateRequest(item, stored, accountKey)
            .login!!.fido2Credentials.single()

        // 密文必须逐字节一致（CBC 随机 IV ⇒ 只要重加密就必然不等）
        assertEquals(serverKeyValue, written.keyValue)
        assertEquals(
            "PRIVATE-KEY-MATERIAL",
            crypto.decryptToString(written.keyValue!!, accountKey),
        )
    }

    /**
     * 未改动通行密钥集合时，写回应与服务端密文**逐字节一致**（不重加密）。
     * 重加密虽不丢明文，但会让「这次到底动没动过」无从判断，也会掩盖真正的破坏性问题。
     */
    @Test
    fun updateDoesNotReEncryptUntouchedFido2Credentials() {
        val storedCredential = Fido2CredentialDto(
            credentialId = crypto.encryptString("cid-a", accountKey),
            rpId = crypto.encryptString("x.com", accountKey),
            keyValue = crypto.encryptString("KEY-A", accountKey),
            counter = crypto.encryptString("3", accountKey),
            discoverable = crypto.encryptString("true", accountKey),
        )
        val stored = CipherDto(
            id = "c12",
            type = 1,
            name = crypto.encryptString("站点", accountKey),
            login = LoginDto(fido2Credentials = listOf(storedCredential)),
        )

        val request = mapper.toUpdateRequest(mapper.toDomain(stored, accountKey), stored, accountKey)

        assertEquals(listOf(storedCredential), request.login!!.fido2Credentials)
    }

    /**
     * 追加新凭据时，**既有条目的密文一字不改**（对齐 Bastion
     * `Fido2CredentialCodec.mergeByCredentialId`：其余既有条目全部保留，避免覆盖丢失）。
     */
    @Test
    fun updateAppendsNewCredentialAndKeepsExistingCipherText() {
        val storedCredential = Fido2CredentialDto(
            credentialId = crypto.encryptString("cid-a", accountKey),
            rpId = crypto.encryptString("x.com", accountKey),
            keyValue = crypto.encryptString("KEY-A", accountKey),
        )
        val stored = CipherDto(
            id = "c13",
            type = 1,
            name = crypto.encryptString("站点", accountKey),
            login = LoginDto(fido2Credentials = listOf(storedCredential)),
        )

        val loaded = mapper.toDomain(stored, accountKey)
        val item = loaded.copy(
            fido2Credentials = loaded.fido2Credentials + VaultFido2Credential(
                credentialId = "cid-new",
                rpId = "y.com",
                keyValue = "KEY-NEW",
            ),
        )

        val output = mapper.toUpdateRequest(item, stored, accountKey).login!!.fido2Credentials

        assertEquals(2, output.size)
        assertEquals(storedCredential, output[0]) // 既有条目原样保留
        assertEquals("cid-new", crypto.decryptToString(output[1].credentialId!!, accountKey))
        assertEquals("KEY-NEW", crypto.decryptToString(output[1].keyValue!!, accountKey))
    }

    /** 模型里移除某条 → 该条从请求中剔除（其余条目仍原样保留）。 */
    @Test
    fun updateDropsCredentialRemovedFromModel() {
        val keepMe = Fido2CredentialDto(
            credentialId = crypto.encryptString("cid-keep", accountKey),
            rpId = crypto.encryptString("keep.com", accountKey),
            keyValue = crypto.encryptString("KEY-KEEP", accountKey),
        )
        val dropMe = Fido2CredentialDto(
            credentialId = crypto.encryptString("cid-drop", accountKey),
            rpId = crypto.encryptString("drop.com", accountKey),
            keyValue = crypto.encryptString("KEY-DROP", accountKey),
        )
        val stored = CipherDto(
            id = "c14",
            type = 1,
            name = crypto.encryptString("站点", accountKey),
            login = LoginDto(fido2Credentials = listOf(dropMe, keepMe)),
        )

        val loaded = mapper.toDomain(stored, accountKey)
        val item = loaded.copy(
            fido2Credentials = loaded.fido2Credentials.filter { it.credentialId == "cid-keep" },
        )

        val output = mapper.toUpdateRequest(item, stored, accountKey).login!!.fido2Credentials

        assertEquals(listOf(keepMe), output)
    }

    /**
     * 保守回退：服务端某条凭据的 `credentialId` 解不开时，**一律沿用原密文整表返回**，
     * 绝不冒险做增删判断（宁可这次不生效，也不能误删用户的密钥材料）。
     */
    @Test
    fun updateFallsBackToStoredWhenStoredCredentialIdIsUndecryptable() {
        val damaged = Fido2CredentialDto(
            credentialId = "2.AAAA|BBBB", // 非法密文：解不开
            rpId = crypto.encryptString("x.com", accountKey),
            keyValue = crypto.encryptString("KEY-X", accountKey),
        )
        val stored = CipherDto(
            id = "c15",
            type = 1,
            name = crypto.encryptString("站点", accountKey),
            login = LoginDto(fido2Credentials = listOf(damaged)),
        )

        val request = mapper.toUpdateRequest(mapper.toDomain(stored, accountKey), stored, accountKey)

        assertEquals(listOf(damaged), request.login!!.fido2Credentials)
        assertEquals(
            "KEY-X",
            crypto.decryptToString(request.login!!.fido2Credentials.single().keyValue!!, accountKey),
        )
    }

    /**
     * `creationDate` 是 Bitwarden 的**明文** DateTime（不加密）。
     * 修复前读侧一律按密文解密，解析失败降级空串 → 通行密钥创建时间永远显示「—」。
     */
    @Test
    fun creationDateInPlainTextIsReadAsIs() {
        val stored = CipherDto(
            id = "c4",
            type = 1,
            name = crypto.encryptString("站点", accountKey),
            login = LoginDto(
                fido2Credentials = listOf(
                    // Bitwarden 真实形态：明文 ISO DateTime
                    Fido2CredentialDto(creationDate = "2026-09-05T12:34:56.789Z"),
                ),
            ),
        )

        assertEquals(
            "2026-09-05T12:34:56.789Z",
            mapper.toDomain(stored, accountKey).fido2Credentials.single().creationDate,
        )
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

    @Test
    fun toDomainReadsIdentity() {
        // 身份条目（type=4）：全量 17 字段必须进入领域模型（覆盖 Bastion 仅映射少数字段的兼容缺陷）
        val stored = CipherDto(
            id = "id1",
            type = 4,
            name = crypto.encryptString("张三的身份", accountKey),
            identity = IdentityDto(
                title = crypto.encryptString("Mr", accountKey),
                firstName = crypto.encryptString("三", accountKey),
                lastName = crypto.encryptString("张", accountKey),
                address1 = crypto.encryptString("中关村大街 1 号", accountKey),
                city = crypto.encryptString("北京", accountKey),
                state = crypto.encryptString("北京", accountKey),
                postalCode = crypto.encryptString("100000", accountKey),
                country = crypto.encryptString("CN", accountKey),
                company = crypto.encryptString("Vaultix", accountKey),
                email = crypto.encryptString("zhang@vaultix.dev", accountKey),
                phone = crypto.encryptString("13800000000", accountKey),
                ssn = crypto.encryptString("110101199001011234", accountKey),
                username = crypto.encryptString("zhang", accountKey),
                passportNumber = crypto.encryptString("E12345678", accountKey),
                licenseNumber = crypto.encryptString("110000000000", accountKey),
            ),
        )
        val item = mapper.toDomain(stored, accountKey)

        assertEquals(VaultItemType.Identity, item.type)
        val id = item.identity!!
        assertEquals("Mr", id.title)
        assertEquals("三", id.firstName)
        assertEquals("张", id.lastName)
        assertEquals("中关村大街 1 号", id.address1)
        assertEquals("北京", id.city)
        assertEquals("100000", id.postalCode)
        assertEquals("CN", id.country)
        assertEquals("Vaultix", id.company)
        assertEquals("zhang@vaultix.dev", id.email)
        assertEquals("13800000000", id.phone)
        assertEquals("110101199001011234", id.ssn)
        assertEquals("zhang", id.username)
        assertEquals("E12345678", id.passportNumber)
        assertEquals("110000000000", id.licenseNumber)
    }

    @Test
    fun toRequestWritesIdentityAndDropsLogin() {
        val item = VaultItem(
            id = "id2",
            title = "张三的身份",
            type = VaultItemType.Identity,
            identity = VaultIdentity(
                firstName = "三",
                lastName = "张",
                email = "zhang@vaultix.dev",
                phone = "13800000000",
                passportNumber = "E12345678",
            ),
        )
        val request = mapper.toRequest(item, accountKey)

        assertEquals(4, request.type)
        assertEquals(null, request.login)
        val id = request.identity!!
        assertEquals("三", crypto.decryptToString(id.firstName!!, accountKey))
        assertEquals("张", crypto.decryptToString(id.lastName!!, accountKey))
        assertEquals("zhang@vaultix.dev", crypto.decryptToString(id.email!!, accountKey))
        assertEquals("13800000000", crypto.decryptToString(id.phone!!, accountKey))
        assertEquals("E12345678", crypto.decryptToString(id.passportNumber!!, accountKey))
    }

    @Test
    fun roundTripPreservesIdentity() {
        val item = VaultItem(
            id = "id3",
            title = "张三的身份",
            type = VaultItemType.Identity,
            identity = VaultIdentity(
                firstName = "三",
                lastName = "张",
                email = "zhang@vaultix.dev",
                passportNumber = "E12345678",
            ),
        )
        val request = mapper.toRequest(item, accountKey)
        val stored = request.toStoredCipherDto(id = "id3", revisionDate = "r1")
        val back = mapper.toDomain(stored, accountKey)
        assertEquals(item.identity, back.identity)
    }
}
