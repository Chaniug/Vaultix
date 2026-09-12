package io.vaultix.data.kdbx

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * KeePass 通行密钥（KeePassDX 的 `KPEX_PASSKEY_*` 约定）映射单测。
 *
 * 最贵的两条：
 *  ① 必填字段缺失时必须返回 **null**（半截凭证会比"没有"更糟：列表里出现一条点进去什么都没有）；
 *  ② `credentialId` 的 base64url ↔ 标准 base64 转换：**转换失败要原样保留**，
 *     硬转会把非 base64 的旧数据变成空串（等于把用户的通行密钥弄丢了）。
 */
class KdbxPasskeyCodecTest {

    private val base = KdbxPasskeyFields(
        username = "alice",
        privateKeyPem = "-----BEGIN PRIVATE KEY-----\nMIGHAgEA\n-----END PRIVATE KEY-----",
        credentialId = "AQIDBA", // base64url(无填充) 的 01 02 03 04
        userHandle = "dXNlci1oYW5kbGU",
        relyingParty = "GitHub.com",
    )

    @Test
    fun `maps all fields into a domain credential`() {
        val credential = KdbxPasskeyCodec.toCredential(base, title = "GitHub [Passkey]")

        assertThat(credential).isNotNull()
        with(credential!!) {
            assertThat(rpId).isEqualTo("github.com") // 域名小写归一
            assertThat(rpName).isEqualTo("GitHub") // 去掉 "[Passkey]" 后缀
            assertThat(userName).isEqualTo("alice")
            assertThat(userDisplayName).isEqualTo("alice")
            assertThat(userHandle).isEqualTo("dXNlci1oYW5kbGU")
            assertThat(keyValue).contains("BEGIN PRIVATE KEY")
            assertThat(keyAlgorithm).isEqualTo("ES256")
            assertThat(keyCurve).isEqualTo("P-256")
            // 计数恒 0：同步型通行密钥不实现单调计数器（见 .ai/ISSUES.md #33）
            assertThat(counter).isEqualTo(0L)
            assertThat(discoverable).isTrue()
        }
    }

    @Test
    fun `base64url credential id is normalized to standard base64`() {
        // "AQIDBA" (8 chars) 的 base64url 原样是 01 02 03 04；标准 base64 应为 "AQIDBA=="
        val credential = KdbxPasskeyCodec.toCredential(base)
        assertThat(credential!!.credentialId).isEqualTo("AQIDBA==")
    }

    @Test
    fun `already standard base64 credential id is kept as is`() {
        val credential = KdbxPasskeyCodec.toCredential(base.copy(credentialId = "AQID+BA=="))
        assertThat(credential!!.credentialId).isEqualTo("AQID+BA==")
    }

    @Test
    fun `undecodable credential id is preserved verbatim`() {
        // ★ 关键反向用例：非法 base64 不能变成空串（那等于丢掉凭证）
        val weird = "not!a!base64!!"
        val credential = KdbxPasskeyCodec.toCredential(base.copy(credentialId = weird))
        assertThat(credential!!.credentialId).isEqualTo(weird)
    }

    @Test
    fun `missing required fields yields null`() {
        listOf(
            base.copy(username = ""),
            base.copy(privateKeyPem = ""),
            base.copy(credentialId = ""),
            base.copy(userHandle = ""),
            base.copy(relyingParty = ""),
        ).forEach { incomplete ->
            assertThat(KdbxPasskeyCodec.toCredential(incomplete)).isNull()
        }
    }

    @Test
    fun `isPasskey detects the entry from any single field`() {
        assertThat(KdbxPasskeyCodec.isPasskey(base)).isTrue()
        assertThat(KdbxPasskeyCodec.isPasskey(KdbxPasskeyFields())).isFalse()
        assertThat(KdbxPasskeyCodec.isPasskey(KdbxPasskeyFields(relyingParty = "github.com")))
            .isTrue()
    }

    @Test
    fun `field names are recognized case insensitively and excluded from custom fields`() {
        listOf(
            "KPEX_PASSKEY_USERNAME",
            "kpex_passkey_private_key_pem",
            "KPEX_PASSKEY_CREDENTIAL_ID",
            "KPEX_PASSKEY_USER_HANDLE",
            "KPEX_PASSKEY_RELYING_PARTY",
            "KPEX_PASSKEY_FLAG_BE",
            "KPEX_PASSKEY_FLAG_BS",
            "Passkey",
        ).forEach { name ->
            assertThat(KdbxPasskeyCodec.isPasskeyFieldName(name)).isTrue()
        }
        assertThat(KdbxPasskeyCodec.isPasskeyFieldName("External Passkey Plugin Field")).isFalse()
    }

    @Test
    fun `backup flags default to true when absent`() {
        // 跨设备同步的通行密钥语义就是「可备份且已备份」（.ai/ISSUES.md #32）
        assertThat(KdbxPasskeyCodec.isBackedUp(KdbxPasskeyFields())).isTrue()
        assertThat(KdbxPasskeyCodec.isBackupEligible(KdbxPasskeyFields())).isTrue()
        assertThat(KdbxPasskeyCodec.isBackedUp(KdbxPasskeyFields(flagBs = "false"))).isFalse()
        assertThat(KdbxPasskeyCodec.isBackupEligible(KdbxPasskeyFields(flagBe = "no"))).isFalse()
        // 宽松布尔：1/yes 也认
        assertThat(KdbxPasskeyCodec.isBackedUp(KdbxPasskeyFields(flagBs = "1"))).isTrue()
    }

    @Test
    fun `rpName falls back to rpId when title is blank`() {
        val credential = KdbxPasskeyCodec.toCredential(base, title = "")
        assertThat(credential!!.rpName).isEqualTo("github.com")
    }
}
