package io.vaultix.common

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * OTP 生成与 URI 解析（RFC 6238 / RFC 4226 标准向量 + mOTP 规格 + 五类型 URI）。
 * 算法移植 Bastion，按 Vaultix 架构重写为无 Android 依赖的纯算法；此处校验数值保真。
 */
@Suppress("MagicNumber")
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

    // ---- HOTP（RFC 4226）----

    @Test
    fun hotpRfc4226AppendixD() {
        // RFC 4226 附录 D：ASCII "12345678901234567890"，counter 0..9
        val expected = listOf(
            "755224", "287082", "359152", "969429", "338314",
            "254676", "287922", "162583", "399871", "520489",
        )
        expected.forEachIndexed { counter, code ->
            assertEquals(code, TotpGenerator.generateHotp(rfcSecret, counter.toLong(), digits = 6))
        }
    }

    @Test
    fun hotpEightDigitsMatchesRfc6238() {
        // counter=1 的 8 位形式 = 94287082（与 RFC 6238 T=59 向量同源）
        assertEquals("94287082", TotpGenerator.generateHotp(rfcSecret, counter = 1, digits = 8))
    }

    // ---- Yandex / mOTP ----

    @Test
    fun yandexDelegatesToStandardTotp() {
        assertEquals(
            TotpGenerator.generateTotp(rfcSecret, timeSeconds = 59, digits = 6),
            TotpGenerator.generateYandexCode(rfcSecret, timeSeconds = 59),
        )
    }

    @Test
    fun mobileOtpFollowsSpecFormat() {
        // 规格断言：MD5(epoch/10 + secret + pin) 的 hex 数字字符前 6 位（不足补 0）
        val secret = "0123456789abcdef"
        val pin = "1234"
        val timeSeconds = 1_000_000L
        val epoch = timeSeconds / 10
        val digest = MessageDigest.getInstance("MD5")
            .digest("${epoch}${secret}${pin}".toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { String.format("%02x", it) }
        val expected = hex.filter { it.isDigit() }.take(6).padEnd(6, '0')
        assertEquals(expected, TotpGenerator.generateMobileOtp(secret, pin, timeSeconds))
    }

    // ---- 统一入口 generate(config) ----

    @Test
    fun generateDispatchesByType() {
        assertEquals(
            "287082",
            TotpGenerator.generate(TotpConfig(secret = rfcSecret), timeSeconds = 59),
        )
        assertEquals(
            "287082",
            TotpGenerator.generate(TotpConfig(secret = rfcSecret, type = OtpType.HOTP, counter = 1)),
        )
        assertEquals(
            "PV9M4",
            TotpGenerator.generate(TotpConfig(secret = steamSecretB64, type = OtpType.STEAM), timeSeconds = 59),
        )
        val motp = TotpConfig(secret = "0123456789abcdef", type = OtpType.MOTP, pin = "1234")
        assertEquals(
            TotpGenerator.generateMobileOtp("0123456789abcdef", "1234", 1_000_000L),
            TotpGenerator.generate(motp, timeSeconds = 1_000_000L),
        )
    }

    // ---- 扩展 URI 解析（hotp / yaotp / motp / encoder=steam）----

    @Test
    fun parseHotpUriCarriesCounter() {
        val config = OtpUriParser.parse(
            "otpauth://hotp/ACME:alice?secret=JBSWY3DPEHPK3PXP&counter=42",
        )
        assertEquals(OtpType.HOTP, config?.type)
        assertEquals(HOTP_COUNTER_EXPECTED, config?.counter)
        assertEquals("JBSWY3DPEHPK3PXP", config?.secret)
    }

    @Test
    fun parseYandexAuthorityCarriesPin() {
        val config = OtpUriParser.parse(
            "otpauth://yaotp/alice?secret=JBSWY3DPEHPK3PXP&pin=9999",
        )
        assertEquals(OtpType.YANDEX, config?.type)
        assertEquals(YANDEX_PIN, config?.pin)
    }

    @Test
    fun parseMotpUriWithPin() {
        val config = OtpUriParser.parse(
            "motp://Example:alice@example.com?secret=0123456789abcdef&pin=1234",
        )
        assertEquals(OtpType.MOTP, config?.type)
        assertEquals(MOTP_PERIOD_EXPECTED, config?.period)
        assertEquals(MOTP_DIGITS_EXPECTED, config?.digits)
        assertEquals("0123456789abcdef", config?.secret)
        assertEquals("1234", config?.pin)
    }

    @Test
    fun parseToDisplayMotpSplitsIssuerAccount() {
        val parsed = OtpUriParser.parseToDisplay(
            "motp://Example:alice@example.com?secret=0123456789abcdef&pin=1234",
        )
        assertEquals("Example", parsed?.issuer)
        assertEquals("alice@example.com", parsed?.account)
        assertEquals(OtpType.MOTP, parsed?.type)
    }

    @Test
    fun parseDetectsSteamByEncoderParam() {
        // Bastion 的显式标记方式：encoder=steam（issuer 即便不含 Steam 也识别）
        val config = OtpUriParser.parse(
            "otpauth://totp/alice?secret=$steamSecretB64&encoder=steam",
        )
        assertEquals(true, config?.steam)
        assertEquals(STEAM_DIGITS_EXPECTED, config?.digits)
    }

    @Test
    fun parseOtpAuthDecodesEncodedLabel() {
        val parsed = OtpUriParser.parseToDisplay(
            "otpauth://totp/ACME%3Aalice%40mail.io?secret=JBSWY3DPEHPK3PXP",
        )
        assertEquals("ACME", parsed?.issuer)
        assertEquals("alice@mail.io", parsed?.account)
    }

    // ---- buildUri 五类型 roundtrip ----

    @Test
    fun buildUriRoundTripForAllTypes() {
        val configs = listOf(
            TotpConfig(secret = "JBSWY3DPEHPK3PXP"),
            TotpConfig(secret = "JBSWY3DPEHPK3PXP", type = OtpType.HOTP, counter = HOTP_COUNTER_EXPECTED),
            TotpConfig(secret = steamSecretB64, type = OtpType.STEAM),
            TotpConfig(secret = "JBSWY3DPEHPK3PXP", type = OtpType.YANDEX, pin = YANDEX_PIN),
            TotpConfig(secret = "0123456789abcdef", type = OtpType.MOTP, pin = "1234"),
        )
        configs.forEach { config ->
            val uri = OtpUriParser.buildUri(config, issuer = "ACME", account = "alice")
            val parsed = OtpUriParser.parseToDisplay(uri) ?: error("无法解析: $uri")
            assertEquals(config.type, parsed.type)
            assertEquals(config.secret, parsed.secret)
            assertEquals(config.counter, parsed.counter)
            assertEquals(config.pin, parsed.pin)
        }
    }

    @Test
    fun buildUriSteamRoundTripKeepsBase64Secret() {
        val config = TotpConfig(secret = steamSecretB64, type = OtpType.STEAM)
        val uri = OtpUriParser.buildUri(config, issuer = "ACME", account = "alice")
        // Base64 的 + / = 必须被编码进 URI 且解码无损
        val parsed = OtpUriParser.parseToDisplay(uri) ?: error("无法解析: $uri")
        assertEquals(steamSecretB64, parsed.secret)
        assertEquals(OtpType.STEAM, parsed.type)
        assertEquals(STEAM_DIGITS_EXPECTED, parsed.digits)
    }

    // ---- uriEncode / uriDecode 语义 ----

    @Test
    fun uriEncodeDecodeRoundTripsBase64Secret() {
        val secret = "AB+CD/EF=="
        val encoded = uriEncode(secret)
        assertEquals("AB%2BCD%2FEF%3D%3D", encoded)
        assertEquals(secret, uriDecode(encoded))
    }

    @Test
    fun uriDecodeKeepsLiteralPlus() {
        // 对齐 android.net.Uri.decode：裸 + 不转空格（Steam Base64 密钥兼容）
        assertEquals("AB+CD", uriDecode("AB+CD"))
    }

    @Test
    fun uriDecodeInvalidEscapeKeptLiteral() {
        assertEquals("100%", uriDecode("100%"))
        assertEquals("%2", uriDecode("%2"))
    }

    private companion object {
        const val STEAM_DIGITS_EXPECTED = 5
        const val MOTP_PERIOD_EXPECTED = 10
        const val MOTP_DIGITS_EXPECTED = 6
        const val HOTP_COUNTER_EXPECTED = 42L
        const val YANDEX_PIN = "9999"
    }
}
