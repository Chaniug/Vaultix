package io.vaultix.common

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // ---- 遮罩（2026-09-21 新增：验证码页可点标题隐藏数字）----

    @Test
    fun maskKeepsHalfOfSixDigitCode() {
        // 用户明确要求：6 位隐藏到只显示 3 位
        assertEquals("123***", TotpGenerator.mask("123456"))
    }

    @Test
    fun maskKeepsHalfOfEightDigitCode() {
        assertEquals("1234****", TotpGenerator.mask("12345678"))
    }

    @Test
    fun maskKeepsThreeForSevenDigitCode() {
        // 7/2 = 3（整除），仍不上浮
        assertEquals("123****", TotpGenerator.mask("1234567"))
    }

    @Test
    fun maskNeverShrinksBelowMinimumKeep() {
        // 4 位码：4/2 = 2 会被下限顶到 3 ⇒ 必须留 3 位，否则用户无法自行辨认
        assertEquals("123*", TotpGenerator.mask("1234"))
    }

    @Test
    fun maskLeavesShortCodeUntouched() {
        // 3 位及更短：keep >= length ⇒ 原样返回（不能把整条码遮光）
        assertEquals("123", TotpGenerator.mask("123"))
        assertEquals("", TotpGenerator.mask(""))
    }

    @Test
    fun maskKeepsLettersForNonNumericCodes() {
        // Steam 等码可能是字母数字混合，遮罩只按长度算、不假设内容
        assertEquals("ABCD****", TotpGenerator.mask("ABCDEFGH"))
    }

    private companion object {
        const val STEAM_DIGITS_EXPECTED = 5
        const val MOTP_PERIOD_EXPECTED = 10
        const val MOTP_DIGITS_EXPECTED = 6
        const val HOTP_COUNTER_EXPECTED = 42L
        const val YANDEX_PIN = "9999"
    }

    // ---------------------------------------------------------------- 保真回归

    // RFC 6238 附录：算法不同则密钥长度不同（SHA256 用 32 字节、SHA512 用 64 字节）。
    // 期望值由独立实现的参考计算得出，并与 RFC 公布向量一致（SHA256@T=59 → 46119246、
    // SHA512@T=59 → 90693936），不是照抄本仓库自己的输出。
    private val rfc256Secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZA===="
    // RFC 6238 的 SHA512 密钥是 64 字节；Base32 展开后很长，拆行拼接只为符合行长上限。
    private val rfc512Secret =
        "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ" +
            "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNA="

    @Test
    fun keePassXcHyphenatedAlgorithmDoesNotCollapseToZeros() {
        // 🔴 回归（KDBX 读路径）：KeePassXC / KeePassOTP 的 `TimeOtp-Algorithm`
        // 取值形态是 **`HMAC-SHA-256`**。归一化缺失时拼成 `HmacHMAC-SHA-256`
        // ⇒ `Mac.getInstance` 抛 `NoSuchAlgorithmException` ⇒
        //   被 `generateTotp` 的 `catch (_: Exception)` 吞成 `"0".repeat(digits)`
        // ⇒ 用户看到**恒定的** `00000000`，界面没有任何异常提示。
        val code = TotpGenerator.generateTotp(
            secret = rfc256Secret,
            timeSeconds = 59,
            period = 30,
            digits = 8,
            algorithm = "HMAC-SHA-256",
        )
        assertEquals("46119246", code)
    }

    @Test
    fun everyVendorSpellingOfSha512AgreesOnTheSameCode() {
        // 四种现实写法必须落到同一个 JCA 算法名。注意：只做"彼此相等"是不够的 ——
        // 若归一化整体失效，四种全会变 SHA1 ⇒ 依然互相相等，却是错的。
        // 所以这里同时钉住 RFC 绝对值。
        val expected = "90693936"
        listOf("SHA512", "HMAC-SHA-512", "HmacSHA512", "hmac-sha-512", " sha-512 ").forEach { spelling ->
            val code = TotpGenerator.generateTotp(
                secret = rfc512Secret,
                timeSeconds = 59,
                period = 30,
                digits = 8,
                algorithm = spelling,
            )
            assertEquals("写法 $spelling 归一化后有偏差", expected, code)
        }
    }

    @Test
    fun unknownAlgorithmFallsBackToSha1RatherThanThrowing() {
        // 认不出的写法一律回落 RFC 默认，而不是拼进 Mac.getInstance 让它抛再被吞成 0
        assertEquals("SHA1", normalizeAlgorithm("谁也不认识的算法"))
        assertEquals("SHA1", normalizeAlgorithm(""))
        assertEquals("SHA256", normalizeAlgorithm("HMAC-SHA-256"))
        assertEquals("SHA224", normalizeAlgorithm("SHA224"))
    }

    @Test
    fun zeroPeriodFallsBackInsteadOfDividingByZero() {
        // 🔴 回归（崩溃）：`TotpCodesScreen` 在 Composable 里直接调 `remainingSeconds`
        // 算倒计时，那里**没有 try/catch** —— period=0 ⇒ `timeSeconds % 0` 抛
        // `ArithmeticException` ⇒ 重组帧抛出 ⇒ **整页崩**，而不是"这条码不对"。
        assertEquals(1, TotpGenerator.remainingSeconds(period = 0, timeSeconds = 59))
        assertEquals(1, TotpGenerator.remainingSeconds(period = -5, timeSeconds = 59))
        assertEquals(10, TotpGenerator.remainingSeconds(period = 30, timeSeconds = 20))

        // progress 不能溢出成 NaN / Infinity（进度条会拿到垃圾值却看不出错）
        val p = TotpGenerator.progress(period = 0, timeSeconds = 59)
        assertTrue("period=0 时 progress 越界：$p", p in 0f..1f)

        // 生成侧同样不能退化成全 0（全 0 正是"异常被吞"的表征）
        val code = TotpGenerator.generateTotp(rfcSecret, timeSeconds = 59, period = 0, digits = 8)
        assertEquals("94287082", code)
    }

    @Test
    fun malformedPeriodInUriIsClampedAtTheParseBoundary() {
        // `toIntOrNull` 认 `0` / `-15` ⇒ 畸形值会一路带进 TotpConfig。
        // 夹在解析边界上，就不用指望每个消费点都记得兜。
        assertEquals(30, TotpGenerator.parse("otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&period=0")?.period)
        assertEquals(30, TotpGenerator.parse("otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&period=-15")?.period)
        assertEquals(60, TotpGenerator.parse("otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&period=60")?.period)
    }

    @Test
    fun steamIsNotInferredFromTheAccountNameAlone() {
        // 原先 `labelPart.contains("steam")` 会让这类**普通 TOTP 条目**被判成 Steam
        // ⇒ 改用 Base64 解码 + 25 字母表 ⇒ 算出的码永远不对，且用户无从归因。
        val config = TotpGenerator.parse(
            "otpauth://totp/github.com:steam@example.com?secret=JBSWY3DPEHPK3PXP&issuer=Github",
        )
        assertEquals(OtpType.TOTP, config?.type)
    }

    @Test
    fun steamStillDetectedViaExplicitMarkerOrIssuer() {
        // 收紧的只是"账号名"这一条腿；Steam 自己的两条主路必须照旧生效。
        val byEncoder = TotpGenerator.parse("otpauth://totp/x?secret=JBSWY3DPEHPK3PXP&encoder=steam")
        assertEquals(OtpType.STEAM, byEncoder?.type)
        val byIssuer = TotpGenerator.parse("otpauth://totp/Steam:me?secret=JBSWY3DPEHPK3PXP")
        assertEquals(OtpType.STEAM, byIssuer?.type)
    }
}
