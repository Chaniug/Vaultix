package com.bastion.app.bitwarden.mapper

import com.bastion.app.bitwarden.api.CipherLoginFido2CredentialApiData
import com.bastion.app.bitwarden.crypto.BitwardenCrypto.SymmetricCryptoKey
import com.bastion.app.data.PasskeyEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Fido2CredentialCodec 纯逻辑测试。
 *
 * 注意：不覆盖真实加解密路径（BitwardenCrypto 依赖 android.util.Base64，JVM 单测不可用），
 * 只覆盖与加密无关的纯逻辑：mergeByCredentialId 去重合并、明文透传、信号过滤、解密失败降级。
 */
class Fido2CredentialCodecTest {

    private val testKey = SymmetricCryptoKey(
        encKey = ByteArray(32) { it.toByte() },
        macKey = ByteArray(32) { (it + 1).toByte() }
    )

    private fun credential(
        credentialId: String,
        keyValue: String = "key-$credentialId",
        rpId: String = "example.com",
        userName: String = "user",
        counter: String = "1",
        discoverable: String = "true",
        keyAlgorithm: String = "ECDSA"
    ) = CipherLoginFido2CredentialApiData(
        credentialId = credentialId,
        keyType = "public-key",
        keyAlgorithm = keyAlgorithm,
        keyCurve = "P-256",
        keyValue = keyValue,
        rpId = rpId,
        rpName = "Example",
        counter = counter,
        userHandle = "handle-$credentialId",
        userName = userName,
        userDisplayName = userName,
        discoverable = discoverable,
        creationDate = "2026-08-25T00:00:00Z"
    )

    // ========== mergeByCredentialId ==========

    @Test
    fun mergeByCredentialId_appendsNewCredential() {
        val local = credential("cred-1")
        val existing = listOf(credential("cred-2"))

        val merged = Fido2CredentialCodec.mergeByCredentialId(local, existing)

        assertEquals(listOf("cred-2", "cred-1"), merged.map { it.credentialId })
    }

    @Test
    fun mergeByCredentialId_overwritesSameCredentialIdAndKeepsOrder() {
        val local = credential("cred-1", keyValue = "new-key", userName = "new-user")
        val existing = listOf(
            credential("cred-1", keyValue = "old-key"),
            credential("cred-2")
        )

        val merged = Fido2CredentialCodec.mergeByCredentialId(local, existing)

        assertEquals(listOf("cred-1", "cred-2"), merged.map { it.credentialId })
        val mergedLocal = merged.first { it.credentialId == "cred-1" }
        assertEquals("new-key", mergedLocal.keyValue)
        assertEquals("new-user", mergedLocal.userName)
    }

    @Test
    fun mergeByCredentialId_keepsOtherCredentialsIntact() {
        val local = credential("cred-3")
        val existing = listOf(credential("cred-1"), credential("cred-2"))

        val merged = Fido2CredentialCodec.mergeByCredentialId(local, existing)

        assertEquals(3, merged.size)
        assertEquals(setOf("cred-1", "cred-2", "cred-3"), merged.map { it.credentialId }.toSet())
    }

    @Test
    fun mergeByCredentialId_normalizesUuidCredentialId() {
        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        val local = credential(uuid, keyValue = "local-key")
        val existing = listOf(credential(uuid, keyValue = "remote-key"))

        val merged = Fido2CredentialCodec.mergeByCredentialId(local, existing)

        assertEquals(1, merged.size)
        assertEquals("local-key", merged.first().keyValue)
    }

    @Test
    fun mergeByCredentialId_emptyExistingAppends() {
        val local = credential("cred-1")

        val merged = Fido2CredentialCodec.mergeByCredentialId(local, emptyList())

        assertEquals(listOf("cred-1"), merged.map { it.credentialId })
    }

    // ========== decryptCredentialsToPlainApiData（明文透传，不触发真实解密） ==========

    @Test
    fun decryptCredentialsToPlainApiData_passesThroughPlainValues() {
        val plain = listOf(credential("cred-1"))

        val decoded = Fido2CredentialCodec.decryptCredentialsToPlainApiData(plain, testKey)

        assertEquals(1, decoded.size)
        assertEquals("cred-1", decoded[0].credentialId)
        assertEquals("Example", decoded[0].rpName)
        assertEquals("user", decoded[0].userName)
    }

    @Test
    fun decryptCredentialsToPlainApiData_dropsEmptySignalEntries() {
        val empty = CipherLoginFido2CredentialApiData()
        val plain = listOf(credential("cred-1"), empty)

        val decoded = Fido2CredentialCodec.decryptCredentialsToPlainApiData(plain, testKey)

        assertEquals(listOf("cred-1"), decoded.map { it.credentialId })
    }

    @Test
    fun decryptCredentialsToPlainApiData_handlesNullList() {
        val decoded = Fido2CredentialCodec.decryptCredentialsToPlainApiData(null, testKey)

        assertEquals(0, decoded.size)
    }

    // ========== decodeFido2Credentials（明文解析 + 信号过滤） ==========

    @Test
    fun decodeFido2Credentials_parsesPlainCredential() {
        val plain = listOf(
            credential("cred-1", counter = "7", discoverable = "false", keyAlgorithm = "ECDSA")
        )

        val decoded = Fido2CredentialCodec.decodeFido2Credentials(plain, testKey)

        assertEquals(1, decoded.size)
        assertEquals("cred-1", decoded[0].credentialId)
        assertEquals(7L, decoded[0].counter)
        assertEquals(false, decoded[0].discoverable)
        assertEquals(PasskeyEntry.ALGORITHM_ES256, decoded[0].publicKeyAlgorithm)
    }

    @Test
    fun decodeFido2Credentials_dropsEmptySignalEntries() {
        val plain = listOf(credential("cred-1"), CipherLoginFido2CredentialApiData())

        val decoded = Fido2CredentialCodec.decodeFido2Credentials(plain, testKey)

        assertEquals(1, decoded.size)
        assertEquals("cred-1", decoded[0].credentialId)
    }

    @Test
    fun decodeFido2Credentials_dropsUndecryptableCipherTextEntry() {
        // 所有字段都形似 cipher string（"数字.xxx|yyy"），JVM 上无法解密 →
        // decryptOrPlain 返回 null → 所有信号字段为空 → 条目被过滤
        val undecryptable = CipherLoginFido2CredentialApiData(
            credentialId = "1.iv|data",
            keyType = "1.iv|data",
            keyAlgorithm = "1.iv|data",
            keyCurve = "1.iv|data",
            keyValue = "1.iv|data",
            rpId = "1.iv|data",
            rpName = "1.iv|data",
            counter = "1.iv|data",
            userHandle = "1.iv|data",
            userName = "1.iv|data",
            userDisplayName = "1.iv|data",
            discoverable = "1.iv|data",
            creationDate = "1.iv|data"
        )
        val plain = listOf(credential("cred-1"), undecryptable)

        val decoded = Fido2CredentialCodec.decodeFido2Credentials(plain, testKey)

        // 不可解密的条目所有信号字段均为空 → 被过滤
        assertEquals(listOf("cred-1"), decoded.map { it.credentialId })
    }

    @Test
    fun decodeFido2Credentials_handlesNullList() {
        val decoded = Fido2CredentialCodec.decodeFido2Credentials(null, testKey)

        assertEquals(0, decoded.size)
    }

    // ========== decryptOrPlain（不触发真实解密的路径） ==========

    @Test
    fun decryptOrPlain_blankReturnsNull() {
        assertNull(Fido2CredentialCodec.decryptOrPlain(null, testKey))
        assertNull(Fido2CredentialCodec.decryptOrPlain("", testKey))
        assertNull(Fido2CredentialCodec.decryptOrPlain("   ", testKey))
    }

    @Test
    fun decryptOrPlain_plainValuePassesThrough() {
        assertEquals("plain-text", Fido2CredentialCodec.decryptOrPlain("plain-text", testKey))
    }

    @Test
    fun decryptOrPlain_undecryptableCipherStringReturnsNull() {
        // 形似密文（数字前缀 + 点）但 JVM 上无法解密 → 返回 null（不把密文当明文）
        val result = Fido2CredentialCodec.decryptOrPlain("1.abc|def", testKey)
        assertNull(result)
    }

    // ========== normalizeCredentialId ==========

    @Test
    fun normalizeCredentialId_normalizesUuid() {
        val uuid = "550e8400-e29b-41d4-a716-446655440000"

        assertEquals(uuid, Fido2CredentialCodec.normalizeCredentialId(uuid))
    }

    @Test
    fun normalizeCredentialId_plainStringPassesThrough() {
        assertEquals("cred-1", Fido2CredentialCodec.normalizeCredentialId("cred-1"))
    }
}
