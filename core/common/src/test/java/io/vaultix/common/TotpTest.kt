package io.vaultix.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TOTP 生成与 otpauth URI 解析（RFC 6238 标准向量 + 裸密钥降级）。
 * 算法移植 Bastion，按 Vaultix 架构重写为无 Android 依赖的纯算法；此处校验数值保真。
 */
class TotpTest {

    // RFC 6238 测试密钥（ASCII "12345678901234567890" 的 Base32 形式）
    private val rfcSecret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

    @Test
    fun rfc6238Sha1EightDigitsAt59s() {
        // RFC 6238 附录：T=59 → counter=1 → 8 位动态码 94287082
        val code = TotpGenerator.generateTotp(
            secret = rfcSecret,
            timeSeconds = 59,
            period = 30,
            digits = 8,
            algorithm = "SHA1",
        )
        assertEquals("94287082", code)
    }

    @Test
    fun rfc6238Sha1SixDigitsAt59s() {
        // 6 位取 HOTP counter=1 → 287082（RFC 4226 向量）
        val code = TotpGenerator.generateTotp(
            secret = rfcSecret,
            timeSeconds = 59,
            period = 30,
            digits = 6,
            algorithm = "SHA1",
        )
        assertEquals("287082", code)
    }

    @Test
    fun rfc6238Sha1EightDigitsAt1111111109s() {
        // RFC 6238 附录：T=1111111109 → 07081804
        val code = TotpGenerator.generateTotp(
            secret = rfcSecret,
            timeSeconds = 1_111_111_109L,
            period = 30,
            digits = 8,
            algorithm = "SHA1",
        )
        assertEquals("07081804", code)
    }

    @Test
    fun decodeBase32RecoversRfcAsciiSecret() {
        val bytes = TotpGenerator.decodeBase32(rfcSecret)
        assertEquals("12345678901234567890", String(bytes, Charsets.UTF_8))
    }

    @Test
    fun remainingSecondsAndProgressTrackStepBoundary() {
        assertEquals(30, TotpGenerator.remainingSeconds(period = 30, timeSeconds = 0))
        assertEquals(1, TotpGenerator.remainingSeconds(period = 30, timeSeconds = 29))
        // progress = 1 - remaining/period，步长刚刷新时为 0
        assertEquals(0f, TotpGenerator.progress(period = 30, timeSeconds = 0))
    }

    // ---- OtpUriParser ----

    @Test
    fun parseOtpAuthUriWithAllParams() {
        val config = OtpUriParser.parse(
            "otpauth://totp/ACME:alice@google.com?secret=JBSWY3DPEHPK3PXP" +
                "&issuer=ACME&period=30&digits=6&algorithm=SHA1",
        )
        assertEquals(
            TotpConfig(secret = "JBSWY3DPEHPK3PXP", period = 30, digits = 6, algorithm = "SHA1"),
            config,
        )
    }

    @Test
    fun parseOtpAuthUriWithSha256() {
        val config = OtpUriParser.parse(
            "otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&algorithm=SHA256&digits=8&period=60",
        )
        assertEquals(
            TotpConfig(secret = "JBSWY3DPEHPK3PXP", period = 60, digits = 8, algorithm = "SHA256"),
            config,
        )
    }

    @Test
    fun parseBareBase32Secret() {
        // Bitwarden 常以裸密钥形式存储 login.totp
        val config = OtpUriParser.parse("JBSWY3DPEHPK3PXP")
        assertEquals(
            TotpConfig(secret = "JBSWY3DPEHPK3PXP", period = 30, digits = 6, algorithm = "SHA1"),
            config,
        )
    }

    @Test
    fun parseInvalidReturnsNull() {
        assertNull(OtpUriParser.parse(""))
        assertNull(OtpUriParser.parse("not a secret !!!"))
        assertNull(OtpUriParser.parse("otpauth://hotp/x?secret="))
    }

    @Test
    fun parseOtpAuthIgnoresCaseInSchemeAndAuthority() {
        val config = OtpUriParser.parse(
            "OTPAUTH://TOTP/x?secret=JBSWY3DPEHPK3PXP&algorithm=sha256",
        )
        assertEquals("SHA256", config?.algorithm)
    }

    // ---- Steam Guard ----

    // Steam 共享密钥为 Base64（非 Base32）；取自 RFC 风格 20 字节密钥的 Base64 形式。
    private val steamSecretB64 = "MTIzNDU2Nzg5MDEyMzQ1Njc4OTA="

    @Test
    fun generateSteamTotpKnownVectors() {
        // 与独立参考实现（HMAC-SHA1 / 25 字符字母表 / 5 位）一致
        assertEquals("PV9M4", TotpGenerator.generateSteamTotp(steamSecretB64, timeSeconds = 59))
        assertEquals(
            "PY4YB",
            TotpGenerator.generateSteamTotp(steamSecretB64, timeSeconds = 1_111_111_109L),
        )
    }

    @Test
    fun parseOtpAuthDetectsSteamByIssuer() {
        val config = OtpUriParser.parse(
            "otpauth://totp/Steam:alice?secret=$steamSecretB64&issuer=Steam",
        )
        assertEquals(true, config?.steam)
        assertEquals(STEAM_DIGITS_EXPECTED, config?.digits)
        assertEquals(30, config?.period)
    }

    @Test
    fun parseToDisplaySteamExposesIssuerAndAccount() {
        val parsed = OtpUriParser.parseToDisplay(
            "otpauth://totp/Steam:alice?secret=$steamSecretB64&issuer=Steam",
        )
        assertEquals(true, parsed?.steam)
        assertEquals("Steam", parsed?.issuer)
        assertEquals("alice", parsed?.account)
        assertEquals("Steam", parsed?.label)
    }

    @Test
    fun parseBareSecretYieldsDefaultTotpNotSteam() {
        val parsed = OtpUriParser.parseToDisplay("JBSWY3DPEHPK3PXP")
        assertEquals(false, parsed?.steam)
        assertEquals("JBSWY3DPEHPK3PXP", parsed?.secret)
    }

    private companion object {
        const val STEAM_DIGITS_EXPECTED = 5
    }
}
