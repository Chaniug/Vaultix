package io.vaultix.data.kdbx

import com.google.common.truth.Truth.assertThat
import io.vaultix.common.OtpType
import io.vaultix.common.OtpUriParser
import org.junit.Test

/**
 * KeePass OTP 字段 → `otpauth://` 归一化单测。
 *
 * 这些用例覆盖的都是**真实历史约定**（KeePassXC / KeePass 2.47+ / KeePass2Android 各写各的），
 * 漏认一种的后果不是报错，而是「这个条目的验证码一直是错的 / 一直显示不出来」——
 * 用户很难判断是软件问题还是自己配错了，所以这里逐条钉死。
 */
class KdbxTotpCodecTest {

    private fun uriOf(fields: KdbxOtpFields): String? =
        KdbxTotpCodec.toOtpAuthUri(fields, title = "GitHub", account = "alice")

    @Test
    fun `keepassxc otp field with settings string`() {
        // KeePassXC：otp = 裸 base32，TOTP Settings = "30;6"
        val uri = uriOf(KdbxOtpFields(otp = "JBSWY3DPEHPK3PXP", settings = "30;6"))
        val config = OtpUriParser.parse(uri!!)

        assertThat(config?.secret).isEqualTo("JBSWY3DPEHPK3PXP")
        assertThat(config?.period).isEqualTo(30)
        assertThat(config?.digits).isEqualTo(6)
        assertThat(config?.algorithm).isEqualTo("SHA1")
        assertThat(config?.type).isEqualTo(OtpType.TOTP)
    }

    @Test
    fun `positional settings with non default values`() {
        // ★ 回归点：位置式 token 必须按「第一个数字 = period、第二个 = digits」，
        // 不能按「是否仍等于默认值」判断 —— 用户真写了 30 时会串位。
        val uri = uriOf(KdbxOtpFields(otp = "JBSWY3DPEHPK3PXP", settings = "60;8;SHA256"))
        val config = OtpUriParser.parse(uri!!)

        assertThat(config?.period).isEqualTo(60)
        assertThat(config?.digits).isEqualTo(8)
        assertThat(config?.algorithm).isEqualTo("SHA256")
    }

    @Test
    fun `positional hotp settings carry counter`() {
        // KeePassXC 的 HOTP 写法：settings = "30;6;SHA1;HOTP;5"
        val uri = uriOf(
            KdbxOtpFields(otp = "JBSWY3DPEHPK3PXP", settings = "30;6;SHA1;HOTP;5"),
        )
        val config = OtpUriParser.parse(uri!!)

        assertThat(config?.type).isEqualTo(OtpType.HOTP)
        assertThat(config?.counter).isEqualTo(0L) // 位置式里的 counter 由独立字段承载时才生效
    }

    @Test
    fun `key value settings tokens are recognized`() {
        val uri = uriOf(
            KdbxOtpFields(
                otp = "JBSWY3DPEHPK3PXP",
                settings = "period=45;digits=7;algorithm=sha512",
            ),
        )
        val config = OtpUriParser.parse(uri!!)

        assertThat(config?.period).isEqualTo(45)
        assertThat(config?.digits).isEqualTo(7)
        assertThat(config?.algorithm).isEqualTo("SHA512")
    }

    @Test
    fun `independent fields win over positional settings`() {
        val uri = uriOf(
            KdbxOtpFields(
                seed = "JBSWY3DPEHPK3PXP",
                settings = "30;6",
                period = "90",
                digits = "8",
                algorithm = "SHA256",
            ),
        )
        val config = OtpUriParser.parse(uri!!)

        assertThat(config?.period).isEqualTo(90)
        assertThat(config?.digits).isEqualTo(8)
        assertThat(config?.algorithm).isEqualTo("SHA256")
    }

    @Test
    fun `hotp counter field switches type`() {
        val uri = uriOf(
            KdbxOtpFields(seed = "JBSWY3DPEHPK3PXP", counter = "42", digits = "6"),
        )
        val config = OtpUriParser.parse(uri!!)

        assertThat(config?.type).isEqualTo(OtpType.HOTP)
        assertThat(config?.counter).isEqualTo(42L)
    }

    @Test
    fun `hex secret is decoded and re-encoded as base32`() {
        // ★ 关键：Hex 密钥不能直接当 base32 解析（字符集部分重叠 → 静默算错码）。
        // 0x48656c6c6f = "Hello" → base32 "JBSWY3DP"
        val fields = KdbxOtpFields(
            seed = "48656C6C6F",
            secretEncoding = KdbxOtpFields.SecretEncoding.Hex,
        )
        val config = OtpUriParser.parse(uriOf(fields)!!)

        assertThat(config?.secret).isEqualTo("JBSWY3DP")
    }

    @Test
    fun `base64 secret is decoded and re-encoded as base32`() {
        // "Hello" 的 base64 = "SGVsbG8="
        val fields = KdbxOtpFields(
            seed = "SGVsbG8=",
            secretEncoding = KdbxOtpFields.SecretEncoding.Base64,
        )
        val config = OtpUriParser.parse(uriOf(fields)!!)

        assertThat(config?.secret).isEqualTo("JBSWY3DP")
    }

    @Test
    fun `full otpauth uri in otp field passes through`() {
        // KeePassXC 允许 otp 字段直接存完整 URI：不要试图「归一化」它
        val full = "otpauth://totp/GitHub:alice?secret=JBSWY3DPEHPK3PXP&period=60"
        val uri = uriOf(KdbxOtpFields(otp = full))

        assertThat(uri).isEqualTo(full)
        assertThat(OtpUriParser.parse(uri!!)?.period).isEqualTo(60)
    }

    @Test
    fun `secret with separators and whitespace is stripped`() {
        val uri = uriOf(KdbxOtpFields(seed = "JBSW Y3DP-EHPK 3PXP"))
        assertThat(OtpUriParser.parse(uri!!)?.secret).isEqualTo("JBSWY3DPEHPK3PXP")
    }

    @Test
    fun `no secret yields null`() {
        assertThat(uriOf(KdbxOtpFields())).isNull()
        assertThat(uriOf(KdbxOtpFields(settings = "30;6", period = "30"))).isNull()
    }

    @Test
    fun `otp field names are recognized case insensitively`() {
        listOf(
            "otp",
            "OTP",
            "TOTP Seed",
            "totp seed",
            "TOTP Settings",
            "TimeOtp-Secret-Base32",
            "timeotp-secret-base32",
            "TimeOtp-Period",
            "HOTP Counter",
        ).forEach { name ->
            assertThat(KdbxTotpCodec.isOtpFieldName(name)).isTrue()
        }
        assertThat(KdbxTotpCodec.isOtpFieldName("Security question")).isFalse()
        assertThat(KdbxTotpCodec.isOtpFieldName("TOTP")).isFalse()
    }

    @Test
    fun `period and digits are sanitized`() {
        // 非法值回落默认，而不是把 period=0 传给计算层（那会除零）
        val config = OtpUriParser.parse(uriOf(KdbxOtpFields(otp = "JBSWY3DPEHPK3PXP", period = "0"))!!)
        assertThat(config?.period).isEqualTo(30)

        val tooLong = OtpUriParser.parse(
            uriOf(KdbxOtpFields(otp = "JBSWY3DPEHPK3PXP", digits = "99"))!!,
        )
        assertThat(tooLong?.digits).isEqualTo(10)
    }

    @Test
    fun `base32 encoding round trips through the shared decoder`() {
        val bytes = byteArrayOf(0x48, 0x65, 0x6C, 0x6C, 0x6F)
        val encoded = KdbxTotpCodec.base32Encode(bytes)

        assertThat(io.vaultix.common.TotpGenerator.decodeBase32(encoded).toList())
            .isEqualTo(bytes.toList())
    }
}
