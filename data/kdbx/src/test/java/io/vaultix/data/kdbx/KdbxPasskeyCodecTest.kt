package io.vaultix.data.kdbx

import com.google.common.truth.Truth.assertThat
import io.vaultix.common.WebAuthn
import org.junit.Test

/**
 * KeePass 通行密钥（`KPEX_PASSKEY_*`，KeePassXC 定义 / KeePassDX 遵循）映射单测。
 *
 * 最贵的三条：
 *  ① 必填字段缺失时必须返回 **null**（半截凭证会比"没有"更糟：列表里出现一条点进去什么都没有）；
 *  ② `credentialId` **原样保留**：非 base64 的旧数据绝不硬转（会变成空串 = 丢凭证）；
 *  ③ #293：`credentialId` 从 KDBX 读出到断言响应的 rawId **逐字符不变** ——
 *     旧实现把它转成标准 base64（加 `=` 填充 / 换出 `+` `/`），RP 按 base64url 解码即失败。
 *     这条跨模块契约以前没有任何测试钉住，才让读侧与 `rawIdFromStored` 的
 *     「原样回传」决策漂移了三个月。
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
    fun `base64url credential id is preserved verbatim not padded to standard base64`() {
        // #293：旧实现把 "AQIDBA" 转成 "AQIDBA=="（标准 base64），断言回传时 RP 不认。
        val credential = KdbxPasskeyCodec.toCredential(base)
        assertThat(credential!!.credentialId).isEqualTo("AQIDBA")
    }

    @Test
    fun `already standard base64 credential id is kept as is`() {
        // 含 `+` 的标准形态样本：读侧一律原样保留，不做任何归一。
        // ⚠️ 别拿它去断 `rawIdFromStored` —— 这个短串是旧测试造的畸形 base64
        //    （长度非 4 倍数），会走该函数「非法文本 → UTF-8 兜底」分支。
        //    rawId 契约由下面真实 32 字节 ID 的用例 + WebAuthnTest ② 覆盖。
        val credential = KdbxPasskeyCodec.toCredential(base.copy(credentialId = "AQID+BA=="))
        assertThat(credential!!.credentialId).isEqualTo("AQID+BA==")
    }

    /**
     * #293 跨模块契约：KDBX 读出的 credentialId 必须能直接喂给断言链路而形态不变。
     *
     * 真机症状：KDBX 上的 passkey 登录 GitHub / x.com 报「通行密钥有问题」，
     * 而 Bitwarden passkey 与电脑上 KeePassXC 用同一个库都正常 —— 差异只在读侧这层转换。
     * 用 32 字节 ID（首字节含 0xFB/0xFF/0xFE，标准 base64 必现 `+` `/`）钉死
     * 「读 → rawId」逐字符一致；把 normalize 加回来这条立刻红。
     */
    @Test
    fun `KDBX credential id survives into assertion rawId byte for byte`() {
        val bytes = byteArrayOf(0xFB.toByte(), 0xFF.toByte(), 0xFE.toByte()) +
            ByteArray(29) { (it * 7 + 3).toByte() }
        val stored = WebAuthn.base64Url(bytes)
        assertThat(stored).doesNotContain("=")
        // 同一批字节若被转成标准 base64，必然出现 '+' '/' 与 '='。
        assertThat(java.util.Base64.getEncoder().encodeToString(bytes)).contains("+")

        val credential = KdbxPasskeyCodec.toCredential(base.copy(credentialId = stored))!!
        val rawId = WebAuthn.rawIdFromStored(credential.credentialId)
        assertThat(rawId).isEqualTo(stored)
        assertThat(rawId).doesNotContain("+")
        assertThat(rawId).doesNotContain("/")
        assertThat(rawId).doesNotContain("=")
        assertThat(WebAuthn.describeStoredIdForm(credential.credentialId)).isEqualTo("base64")
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
