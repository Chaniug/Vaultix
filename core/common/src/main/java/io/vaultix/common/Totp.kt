/*
 * Vaultix — core:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * OTP 算法（RFC 4226 HOTP / RFC 6238 TOTP：Base32 解码 → HMAC → 动态截断）、
 * mOTP（MD5(epoch/10 + secret + pin) 取数字前 6 位）、Yandex OTP（标准 TOTP）
 * 与 otpauth / motp URI 解析思路移植自 Bastion 项目（GPL-3.0，
 * Copyright 2025 JoyinJoester）的 util/TotpGenerator.kt 与 util/TotpUriParser.kt，
 * 按 Vaultix 架构重写为独立、无 Android 依赖的纯算法模块；数值与 RFC 一致。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.common

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 验证码类型（对齐 Bastion OtpType 与 Google Authenticator 迁移格式）。
 *
 * - [TOTP]：RFC 6238 时间型（默认）；
 * - [HOTP]：RFC 4226 计数器型（`otpauth://hotp`，携带 counter）；
 * - [STEAM]：Steam Guard（Base64 密钥 + 25 字符专属字母表，5 位）；
 * - [YANDEX]：Yandex OTP（标准 TOTP，`otpauth://yaotp`，仅类型标记不同）；
 * - [MOTP]：Mobile-OTP（MD5 型，`motp://`，步长固定 10 秒、需 PIN）。
 */
enum class OtpType { TOTP, HOTP, STEAM, YANDEX, MOTP }

/** Steam Guard 字母表（25 字符，区别于标准 Base32）。 */
private const val STEAM_ALPHABET = "23456789BCDFGHJKMNPQRTVWXY"

/** Steam 验证码固定码长。 */
private const val STEAM_DIGITS = 5

/** Steam 验证码固定步长（秒）。 */
private const val STEAM_PERIOD = 30

/** TOTP 默认步长（秒）与码长（buildUri 省略默认段用）。 */
private const val DEFAULT_PERIOD = 30
private const val DEFAULT_DIGITS = 6

/**
 * 默认 HMAC 算法（RFC 6238 §1.2：实现 MUST 支持 SHA-1）。
 *
 * 同时也是 [normalizeAlgorithm] 认不出写法时的**回落值**。
 *
     * ⚠️ 刻意**不**把认不出的原始串直接拼进 `Mac.getInstance`：那会抛
     * `NoSuchAlgorithmException`，而上层 `generateTotp` 的 `catch (_: Exception)`
     * 会把它吞成 `"0".repeat(digits)`（见 [TOTP_FAILURE_PLACEHOLDER]）⇒ 用户看到恒定的
     * `000000` 且没有任何提示。宁可回落到 RFC 默认语义，也不要产出一段看似有效实则永假的验证码。
     *
     * 📌 2026-10-10：下沉到归一化层的**部分**畸形串不再产生恒 `000000` 了，
     *    但**彻底算不出**的密钥（如非法 Base32）仍会走到占位符 —— 那条路径现在会
     *    被 [TotpGenerator.generateUi] 转成 `null`，由 UI 显式提示，不再静默。
     */
private const val DEFAULT_ALGORITHM = "SHA1"

/**
 * JCA 认的 HMAC-SHA 位数白名单（`HmacSHA<n>` 中 `<n>` 的部分）。
 *
 * 只放行这张表：表外的位数一律回落，既挡住注入式的奇怪串，也保证
 * `Mac.getInstance` 不会因为不认识的位数抛异常。
 */
private val SUPPORTED_SHA_BITS = setOf("1", "224", "256", "384", "512")

/**
 * 算法名的各种现实写法：`HMAC-SHA-256` / `SHA256` / `HmacSHA256` / `hmac-sha-1`。
 * 前缀、大小写、分隔符全都可选，只萃取出 `SHA` 后面的位数。
 */
private val ALGORITHM_BITS = Regex("^(?:HMAC[-_ ]?)?SHA[-_ ]?(\\d+)$")

/**
 * 把各种写法归一成 JCA 认的算法后缀（用于 `Mac.getInstance("Hmac$result")`）。
 *
 * ## 为什么非要这一层
 *
 * 以前直接写 `"Hmac$algorithm"`，于是：
 *
 * | 来源 | 写法 | 拼出来的名字 | 后果 |
 * |---|---|---|---|
 * | Bitwarden / Google Authenticator | `SHA256` | `HmacSHA256` | ✅ 正常 |
 * | KeePassXC / KeePassOTP 的 `TimeOtp-Algorithm` | **`HMAC-SHA-256`** | 🔴 抛异常 ⇒ 吞成 `000000` |
 *
 * KDBX 侧的 `KdbxTotpCodec.resolveSettings` 会把字段值 `trim().uppercase()` 原样透传，
 * 所以「用 KeePassXC 建的 SHA-256 条目，在 Vaultix 里永远显示 `000000`」是必现的。
 *
 * ## ⚠️ 为什么是**顶层函数**而不是某个 object 的成员
 *
 * 计算（`TotpGenerator.generateHmac`）与解析（`OtpUriParser.parseOtpAuth`）**两个 object
 * 都要用它** —— 挂在任何一个里，另一个就得写 `TotpGenerator.xxx` 这种跨模块倒依赖，
 * 或者直接复制一份（两个副本各自漂移，正是这类"归一化"最容易失守的方式）。
 *
 * 回归防线设在 **[TotpGenerator.generateHmac] 这一个咽点上**：所有路径（URI / KDBX
 * 字段 / 位置式 `30;6;SHA1`）最终都要过它，不可能有"某个入口忘了归一化"这种漏法。
 *
 * @return `SHA1` / `SHA224` / `SHA256` / `SHA384` / `SHA512`；认不出则 [DEFAULT_ALGORITHM]。
 */
internal fun normalizeAlgorithm(raw: String): String {
    val bits = ALGORITHM_BITS.matchEntire(raw.trim().uppercase(Locale.US))?.groupValues?.get(1)
    return if (bits != null && bits in SUPPORTED_SHA_BITS) "SHA$bits" else DEFAULT_ALGORITHM
}

/**
 * 计算失败时的占位符。
 *
 * > **它只该出现在"密钥本身就是坏的"这种非交互场景**，绝不该出现在用户正盯着的界面上。
 *
 * 历史：这是 [TotpGenerator] 四个生成函数的 `catch` 返回值。原设计意图是好的 ——
 * 「宁可不崩溃」，且它对**自动填充**是正确的选择（[AutofillDatasetFactory.totpCode]
 * 等方法签名是 `String?`，把 null 糊进数据集会引入一个新分支，而"填不了就别填"
 * 本来就等于 null）。
 *
 * 但同一套返回值被复制到了**用户正在看的界面**上，于是产生一个静默失效：
 * 一个密钥格式坏掉的条目，用户看到的是**恒定不变的 `000000`**，页面不报错、也没有任何
 * 提示（[TotpCodesScreen] 的 `totp_invalid_secret` 只在**编辑表单**里用，管不到列表）。
 * 用户没有任何线索判断"是软件错了、还是我抄错了、还是对方站点不认" —— 只会一遍遍复制
 * 这个永远不会变的码。
 *
 * ⇒ 拆成两条路径（判据只有一个，见 [isPlaceholder]）：
 *
 * | 场景 | 入口 | 失败时 |
 * |---|---|---|
 * | 自动填充 / 后台（非交互） | [TotpGenerator.generate] 等 | 仍返回本占位符，行为不变 |
 * | 用户正看着的界面（交互） | [TotpGenerator.generateUi] | 返回 null ⇒ UI 显示「验证码不可用」 |
 *
 * ⚠️ **判据只能有一个**：UI 用来判断"这个码是不是失败的"必须调 [isPlaceholder]，
 *    而不是自己比 `code == "000000"`。后者会在**密钥合法、验证码恰好是 000000** 时误判
 *    （此时不该切到错误态 —— 那条码虽然离谱但**是对的**，用户照抄能过）。
 *    两种失败返回同一个占位符，正是为了让这条单一判据成立；哪天想给它们不同的占位符，
 *    要同时补上"这次失败到底是哪种"的区分方式。
 */
const val TOTP_FAILURE_PLACEHOLDER: String = "000000"

/**
 * `code` 是否是一个**计算失败**的占位符（而不是真实验证码）。
 *
 * 只应与 [TotpGenerator.generateUi]（失败返回 null）配合使用 —— 那里 null 是唯一的失败信号，
 * 判空即可。本函数是给**能拿到非空码、但需要在展示层复核**的路径用的兜底判据，
 * 免得别处再写一遍 `== "000000"` 这种会误伤的散装判断。
 *
 * @param digits 该条目的期望码长（mOTP/Steam 分别是 6/5 位，占位符长度随之不同）。
 */
fun isTotpFailurePlaceholder(code: String, digits: Int = 6): Boolean =
    code.length == digits.coerceIn(1, 10) && code.all { it == '0' }

/** mOTP 固定步长（秒）与码长。 */
private const val MOTP_PERIOD = 10
private const val MOTP_DIGITS = 6

// URI 编解码辅助常量
private const val HEX_DIGIT_OFFSET = 10
private const val HEX_BITS_PER_DIGIT = 4
private const val ESCAPE_SEQUENCE_LENGTH = 3
private const val BYTE_MASK = 0xFF

@Suppress("MagicNumber")
object TotpGenerator {

    /**
     * 按当前时间步长生成 TOTP 验证码。
     *
     * @param secret Base32 编码的密钥（大小写不敏感，忽略空白与连字符）
     * @param timeSeconds 当前 Unix 秒（默认取系统时间，便于测试注入）
     * @param period 时间步长（秒），默认 30
     * @param digits 验证码位数，1–10，默认 6
     * @param algorithm HMAC 算法名：SHA1 / SHA256 / SHA512，默认 SHA1
     */
    fun generateTotp(
        secret: String,
        timeSeconds: Long = System.currentTimeMillis() / 1000,
        period: Int = 30,
        digits: Int = 6,
        algorithm: String = "SHA1",
    ): String {
        val safeDigits = digits.coerceIn(1, 10)
        return try {
            val timeStep = timeSeconds / safePeriod(period)
            val key = decodeBase32(secret)
            val hmac = generateHmac(key, timeStep, algorithm)
            truncateHmac(hmac, safeDigits)
        } catch (_: Exception) {
            placeholderFor(safeDigits)
        }
    }

    /**
     * 生成 HOTP 验证码（RFC 4226），基于计数器而非时间。
     * 与 [generateTotp] 共享 HMAC + 动态截断实现，仅输入是显式 counter。
     */
    fun generateHotp(
        secret: String,
        counter: Long,
        digits: Int = 6,
        algorithm: String = "SHA1",
    ): String {
        val safeDigits = digits.coerceIn(1, 10)
        return try {
            val key = decodeBase32(secret)
            val hmac = generateHmac(key, counter, algorithm)
            truncateHmac(hmac, safeDigits)
        } catch (_: Exception) {
            placeholderFor(safeDigits)
        }
    }

    /**
     * 生成 Yandex OTP 验证码。Yandex 使用标准 TOTP 算法（与 Google Authenticator
     * 兼容），仅类型标记不同（otpauth://yaotp），故直接委托 [generateTotp]。
     */
    fun generateYandexCode(
        secret: String,
        timeSeconds: Long = System.currentTimeMillis() / 1000,
        period: Int = DEFAULT_PERIOD,
        digits: Int = DEFAULT_DIGITS,
        algorithm: String = "SHA1",
    ): String = generateTotp(secret, timeSeconds, period, digits, algorithm)

    /**
     * 生成 Mobile-OTP（mOTP）验证码。
     *
     * 算法：MD5(epoch + secret + pin) 的十六进制串中依次取数字字符，凑满 6 位；
     * 不足 6 位右侧补 0。其中 epoch = Unix 秒 / 10（mOTP 步长固定 10 秒），
     * secret 为原始字符串（非 Base32）。
     */
    fun generateMobileOtp(
        secret: String,
        pin: String,
        timeSeconds: Long = System.currentTimeMillis() / 1000,
    ): String {
        return try {
            val epoch = timeSeconds / MOTP_PERIOD
            val data = "$epoch$secret$pin"
            val digest = MessageDigest.getInstance("MD5").digest(data.toByteArray(Charsets.UTF_8))
            val hex = digest.joinToString(separator = "") { String.format(Locale.US, "%02x", it) }
            val digitsOnly = hex.filter { it.isDigit() }
            if (digitsOnly.length >= MOTP_DIGITS) {
                digitsOnly.take(MOTP_DIGITS)
            } else {
                digitsOnly.padEnd(MOTP_DIGITS, '0')
            }
        } catch (_: Exception) {
            placeholderFor(MOTP_DIGITS)
        }
    }

    /** 当前验证码的剩余有效秒数（向上取整到步长边界）。 */
    fun remainingSeconds(period: Int = 30, timeSeconds: Long = System.currentTimeMillis() / 1000): Int {
        val safe = safePeriod(period)
        val remainder = (timeSeconds % safe).toInt()
        return safe - remainder
    }

    /** 当前时间步长的进度（0.0 刚刷新 → 1.0 即将刷新），用于倒计时进度条。 */
    fun progress(period: Int = 30, timeSeconds: Long = System.currentTimeMillis() / 1000): Float {
        val safe = safePeriod(period)
        val remaining = remainingSeconds(safe, timeSeconds)
        return 1.0f - (remaining.toFloat() / safe)
    }

    /**
     * 步长兜底：`period <= 0` 一律按 [DEFAULT_PERIOD] 算。
     *
     * ## 🔴 为什么这一处守卫这么重要
     *
     * `otpauth://...?period=0`（以及负数）是**能被解析成功**的畸形输入：
     * `parseOtpAuth` 的 `params["period"]?.toIntOrNull()` 取到 `0` 就照用，
     * 于是下游 `timeSeconds % period` / `timeSeconds / period` 抛
     * `ArithmeticException: divide by zero`。
     *
     * 致命之处在于它在**哪里**抛出：`TotpCodesScreen` 在 Composable 里直接调
     * [remainingSeconds] 算倒计时 —— Compose 的重组帧没有 try/catch，
     * 抛出即**整页崩溃**（不是"这条验证码显示不对"，是"验证码列表页打不开"）。
     *
     * ⇒ 三个入口（[generateTotp] / [remainingSeconds] / [progress]）统一过这里，
     *   任何一个漏掉都会留下一条**可被外部数据触发的崩溃路径**。
     *
     * ⚠️ 注意 [generateTotp] 里的 `catch (_: Exception)` 能兜住除零（所以那里不会崩、
     *    只是出 `000000`），但 [remainingSeconds] 是**无 catch 的纯计算** ——
     *    「有保护的那条路径看起来正常」会掩盖边上的真崩溃，这正是它躲了这么久的原因。
     */
    internal fun safePeriod(period: Int): Int = if (period > 0) period else DEFAULT_PERIOD

    /**
     * 构造指定码长的失败占位符（全部 `0`）。
     *
     * 四个生成函数的 `catch` 分支**统一从这里出**，而不是各写一遍 `"0".repeat(n)` ——
     * 因为失败信号的"长相"必须唯一，[isTotpFailurePlaceholder] 才敢用一条判据认它。
     * 哪天有人给某个类型改了占位符长相而没同步判据，那条散装写法就会静默失效
     * （失败被当成真码显示，正是本次要修的病）。
     */
    private fun placeholderFor(digits: Int): String =
        TOTP_FAILURE_PLACEHOLDER.take(digits.coerceIn(1, 10)).padEnd(digits.coerceIn(1, 10), '0')

    /** 遮罩时至少保留的位数（见 [mask]）。 */
    private const val MASK_KEEP_MIN = 3

    /** 遮罩字符。用 `*`（U+002A）：用户 2026-09-23 反馈圆点"不好看"，星号更像被藏起来的密码。 */
    private const val MASK_CHAR = '*'

    /**
     * 把验证码遮罩成「保留前若干位」的形态（**纯展示用**，不参与复制 / 填充）。
     *
     * 用户 2026-09-21 要求：「6 位数的验证码，隐藏到只显示 3 位数」。
     * 保留位数 = `maxOf([MASK_KEEP_MIN], code.length / 2)`：
     *
     * | 码长 | 保留 | 遮罩后 |
     * |---|---|---|
     * | 6 | 3 | `123***` |
     * | 7 | 3 | `123****` |
     * | 8 | 4 | `1234****` |
     *
     * 为什么要有 `[MASK_KEEP_MIN]` 下限：**位数很短的码不能被遮到只剩 1~2 位**，
     * 否则用户根本无法自行辨认（那不是"隐藏"，是把功能废掉）。
     *
     * ⚠️ **已知代价（2026-09-23，用户主动选择接受）**：遮罩字符原本是 `•`（U+2022），
     * 换成 `*` 是因为用户反馈圆点不好看。两者的**东亚宽度属性不同** —— `•` 是
     * Ambiguous（中日韩字体常按全角渲染），`*` 是 Narrow，且等宽字体里 `*` 的字形盒
     * 普遍**比数字窄**。后果是遮罩后的字符串与数字列**不完全等宽**，位数变化
     * （6 位码 ↔ 8 位码、HOTP 计数器进位）时整串宽度会有可见的跳动。
     * 渲染侧已用 `FontFamily.Monospace` 兜底，但**这是字体相关的，无法在沙箱内验证**。
     * 若日后想消除跳动又不回到圆点，可换 `●`（U+25CF，宽度属性与 `•` 相同、观感更实）。
     *
     * ⚠️ **本函数只在渲染层调用**；复制与自动填充一律用**原始** code ——
     * 这是"隐藏不影响复制/填充"这条验收项的**唯一**保证方式。
     */
    fun mask(code: String, keepMin: Int = MASK_KEEP_MIN): String {
        val keep = maxOf(keepMin, code.length / 2)
        if (keep >= code.length) return code
        return code.take(keep) + MASK_CHAR.toString().repeat(code.length - keep)
    }

    /**
     * 唯一的 HMAC 咽点 —— 算法名的归一化**只在这里做一次**。
     *
     * 见 [normalizeAlgorithm]：不归一化的话，KeePassXC 写的 `HMAC-SHA-256`
     * 会拼成 `HmacHMAC-SHA-256`，抛异常后一路被上层吞成恒定的 `000000`。
     */
    private fun generateHmac(key: ByteArray, counter: Long, algorithm: String): ByteArray {
        val algorithmName = "Hmac${normalizeAlgorithm(algorithm)}"
        val mac = Mac.getInstance(algorithmName)
        mac.init(SecretKeySpec(key, algorithmName))
        val buffer = ByteBuffer.allocate(8)
        buffer.putLong(counter)
        return mac.doFinal(buffer.array())
    }

    /**
     * 按 [TotpConfig] 生成当前验证码（五类型统一入口）。
     * HOTP 不依赖时间（用 config.counter）；mOTP 需 config.pin。
     *
     * ⚠️ 计算失败时返回 [TOTP_FAILURE_PLACEHOLDER]（恒 `0`）。
     *    **非交互场景（自动填充 / 后台复制）继续用它** —— 那些调用点的语义是
     *    「拿不到就别用」，占位符与 null 等价且不必改签名。
     *    凡是**要显示给用户**的地方，请改用 [generateUi]（失败返回 null）。
     */
    fun generate(
        config: TotpConfig,
        timeSeconds: Long = System.currentTimeMillis() / 1000,
    ): String = when (config.type) {
        OtpType.STEAM -> generateSteamTotp(config.secret, timeSeconds, config.period)
        OtpType.HOTP -> generateHotp(config.secret, config.counter, config.digits, config.algorithm)
        OtpType.YANDEX -> generateYandexCode(
            config.secret,
            timeSeconds,
            config.period,
            config.digits,
            config.algorithm,
        )
        OtpType.MOTP -> generateMobileOtp(config.secret, config.pin, timeSeconds)
        OtpType.TOTP -> generateTotp(
            config.secret,
            timeSeconds,
            config.period,
            config.digits,
            config.algorithm,
        )
    }

    /**
     * ★ 面向**界面**的验证码生成：失败返回 `null`，而不是一个看起来像验证码的假码。
     *
     * ## 为什么必须有这个入口（2026-10-10）
     *
     * [generate] 失败时返回 [TOTP_FAILURE_PLACEHOLDER]，对自动填充是合适的；但同样的返回值
     * 被用在了**用户正看着的验证码列表**上，结果是一条密钥坏掉的条目会显示一个**永不变化**的
     * `000000`，页面不报错也不提示 —— 用户只能一遍遍复制这个死码，还以为是对方站点的问题。
     *
     * 把"失败"编码成 `null` 之后，UI 能明确落到「验证码不可用」这一步，
     * 且**不再需要拿 `000000` 做字符串比较**去猜（那种猜法在真码恰好为 `000000` 时会误判）。
     *
     * ## 为什么不是 `Result`
     *
     * 调用方（Compose 重组里的 `val code = ...`）只需要"有没有值"这一个信息；
     * `Result` 会逼着每个调用点写 `getOrNull()`，等于把 null 又绕回来一遍。
     *
     * ⚠️ 本函数**共用** [generate] 的全部实现，不是一个并行算法 ——
     *    它只是把占位符翻译成 null，两者不可能算出不同的码。
     */
    fun generateUi(
        config: TotpConfig,
        timeSeconds: Long = System.currentTimeMillis() / 1000,
    ): String? {
        val code = generate(config, timeSeconds)
        return code.takeUnless { isTotpFailurePlaceholder(it, effectiveDigits(config)) }
    }

    /**
     * 该 config **实际会产出**的码长 —— [isTotpFailurePlaceholder] 的判据必须用它，
     * 而不是 `config.digits`。
     *
     * ## ⚠️ 为什么不能直接用 `config.digits`（2026-10-10 实测踩到）
     *
     * 两个类型的**真实码长与 `config.digits` 无关**：
     *
     * | 类型 | 真实码长 | `config.digits` 默认值 |
     * |---|---|---|
     * | [OtpType.STEAM] | 5（[STEAM_DIGITS]，硬编码） | 6 |
     * | [OtpType.MOTP] | 6（[MOTP_DIGITS]，硬编码） | 6 |
     *
     * 于是"Steam 算不出码"时：`generate` 给出 `"00000"`（5 位），而判据拿 `digits=6`
     * 去比长度 ⇒ **不相等 ⇒ 判成不是占位符 ⇒ [generateUi] 把 `"00000"` 原样返回**。
     * 结果正是本次要修的病本身：UI 上出现一个恒定不变的假码。
     *
     * 更阴的是：`TotpCodesScreen` 里 `entry.digits` 与 `entry.type` 不是同一个来源
     * （digits 来自 `toDisplay`，Steam 时会被写成 5），所以这条在某些构造顺序下
     * **不会**暴露 —— 也就是说，光靠"UI 上看着对"永远发现不了它。必须靠测试把
     * "Steam 坏密钥 → null"这条断言钉死。
     */
    private fun effectiveDigits(config: TotpConfig): Int = when (config.type) {
        OtpType.STEAM -> STEAM_DIGITS
        OtpType.MOTP -> MOTP_DIGITS
        else -> config.digits
    }

    /**
     * 生成 Steam Guard 验证码（5 位，Steam 专属 25 字符字母表）。
     *
     * 与 Bitwarden / Bastion / Keyguard 的 Steam 兼容实现一致：
     * - 密钥为 **Base64**（区别于标准 TOTP 的 Base32），解码为 20 字节；
     * - HMAC-SHA1（period 固定 30s）；
     * - 动态截断为 31 位整数后，依次对 25 字符字母表取模得 5 位码。
     *
     * @param secret Base64 编码的 Steam 共享密钥（忽略空白；自动补齐 Base64 填充位）。
     */
    fun generateSteamTotp(
        secret: String,
        timeSeconds: Long = System.currentTimeMillis() / 1000,
        period: Int = STEAM_PERIOD,
    ): String {
        val safePeriod = if (period <= 0) STEAM_PERIOD else period
        return try {
            val timeStep = timeSeconds / safePeriod
            val key = decodeBase64(secret)
            val hmac = generateHmac(key, timeStep, "SHA1")
            val offset = hmac[hmac.size - 1].toInt() and 0x0F
            val binary = ((hmac[offset].toInt() and 0x7F) shl 24) or
                ((hmac[offset + 1].toInt() and 0xFF) shl 16) or
                ((hmac[offset + 2].toInt() and 0xFF) shl 8) or
                (hmac[offset + 3].toInt() and 0xFF)
            var code = binary.toLong() and 0x7FFFFFFF
            buildString {
                repeat(STEAM_DIGITS) {
                    append(STEAM_ALPHABET[(code % STEAM_ALPHABET.length).toInt()])
                    code /= STEAM_ALPHABET.length
                }
            }
        } catch (_: Exception) {
            placeholderFor(STEAM_DIGITS)
        }
    }

    /** Base64 解码（自动补齐填充位；Steam 密钥常缺 pad）。 */
    private fun decodeBase64(encoded: String): ByteArray {
        val clean = encoded.trim().replace(Regex("[\\s]"), "")
        if (clean.isEmpty()) return ByteArray(0)
        val pad = clean.length % 4
        val padded = if (pad != 0) clean + "=".repeat(4 - pad) else clean
        return java.util.Base64.getDecoder().decode(padded)
    }

    private fun truncateHmac(hmac: ByteArray, digits: Int): String {
        val safeDigits = digits.coerceIn(1, 10)
        val offset = hmac[hmac.size - 1].toInt() and 0x0F
        val binary = ((hmac[offset].toInt() and 0x7F) shl 24) or
            ((hmac[offset + 1].toInt() and 0xFF) shl 16) or
            ((hmac[offset + 2].toInt() and 0xFF) shl 8) or
            (hmac[offset + 3].toInt() and 0xFF)
        val otp = binary.toLong() and 0x7FFFFFFF
        return String.format(Locale.US, "%0${safeDigits}d", otp % powersOfTen(safeDigits))
    }

    private fun powersOfTen(digits: Int): Long {
        var v = 1L
        repeat(digits) { v *= 10 }
        return v
    }

    /** Base32 解码（RFC 4648 字母表，忽略空白与连字符）。 */
    fun decodeBase32(encoded: String): ByteArray {
        val clean = encoded.replace(Regex("[\\s\\-]"), "").uppercase(Locale.US)
        val base32Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val output = mutableListOf<Byte>()
        var buffer = 0
        var bitsLeft = 0
        for (char in clean) {
            val value = base32Chars.indexOf(char)
            if (value == -1) continue
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                output.add(((buffer shr (bitsLeft - 8)) and 0xFF).toByte())
                bitsLeft -= 8
            }
        }
        return output.toByteArray()
    }
}

/**
 * OTP 配置（归一化后的可计算参数，五类型统一模型）。
 *
 * @param type 验证码类型（见 [OtpType]）；Steam 兼容旧标记：`type=STEAM`。
 * @param counter HOTP 计数器（仅 HOTP 使用）。
 * @param pin mOTP PIN 码（仅 mOTP 计算；Yandex URI 若携带也在此透传）。
 */
data class TotpConfig(
    val secret: String,
    val period: Int = 30,
    val digits: Int = 6,
    val algorithm: String = "SHA1",
    val type: OtpType = OtpType.TOTP,
    val counter: Long = 0,
    val pin: String = "",
) {
    /** 是否为 Steam Guard（type 为 STEAM；保留旧字段便于调用方逐步迁移）。 */
    val steam: Boolean get() = type == OtpType.STEAM
}

/**
 * 归一化后可展示的 OTP（供 UI 实时计算验证码、展示发行方/账号）。
 */
data class ParsedTotp(
    val secret: String,
    val period: Int,
    val digits: Int,
    val algorithm: String,
    val type: OtpType = OtpType.TOTP,
    val counter: Long = 0,
    val pin: String = "",
    /** otpauth label 中的发行方（issuer）；裸密钥时无。 */
    val issuer: String,
    /** otpauth label 中的账号（冒号后的部分）；裸密钥时无。 */
    val account: String,
    /** 展示用标题：优先 issuer，其次 account，再次 secret 截断。 */
    val label: String,
) {
    /** 是否为 Steam Guard（type 为 STEAM；保留旧字段便于调用方逐步迁移）。 */
    val steam: Boolean get() = type == OtpType.STEAM
}

/**
 * OTP URI 解析（兼容裸 base32 密钥降级）。
 *
 * - `otpauth://totp|hotp|yaotp/Label?secret=...&issuer=...&period=...&digits=...&algorithm=...`
 *   （hotp 附 `counter=`；`encoder=steam` 或 issuer 含 steam/yandex 时自动识别类型）
 * - `motp://Issuer:Account?secret=...&pin=...`（mOTP，步长固定 10s / 6 位）
 * - 裸 base32 串（无 scheme 前缀）→ 按默认 TOTP（6 位 / 30s / SHA1）处理，
 *   对齐 Bitwarden 实际存储（login.totp 常以裸密钥形式出现）。
 * - 解析失败返回 null（调用方降级展示原始串）。
 */
object OtpUriParser {

    private val base32Alphabet = Regex("^[A-Z2-7]+=*$")

    private val motpPattern = Regex("^motp://(.*?):(.*?)\\?(.*)$", RegexOption.IGNORE_CASE)

    fun parse(input: String): TotpConfig? {
        val normalized = input.trim()
        if (normalized.isEmpty()) return null
        return when {
            normalized.startsWith("otpauth://", ignoreCase = true) -> parseOtpAuth(normalized)
            normalized.startsWith("motp://", ignoreCase = true) -> parseMotp(normalized)
            else -> parseBareSecret(normalized)
        }
    }

    private fun parseOtpAuth(uri: String): TotpConfig? {
        val lower = uri.lowercase(Locale.US)
        if (!lower.startsWith("otpauth://")) return null
        // 结构（scheme/authority）按小写解析以忽略大小写；query 保留原串以正确还原 secret
        val authority = lower.substringAfter("otpauth://").substringBefore("/")
        if (authority != "totp" && authority != "hotp" && authority != "yaotp") return null
        val labelRaw = uriDecode(uri.substringAfter("otpauth://").substringBefore("?").substringAfter("/"))
        // label 的发行方用于 Steam / Yandex 识别；账号部分由 parseToDisplay 使用
        val labelIssuer = splitLabel(labelRaw).first
        val params = parseQuery(uri.substringAfter("?", ""))
        val secret = params["secret"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val issuerParam = params["issuer"]?.trim().orEmpty()
        val finalIssuer = issuerParam.ifBlank { labelIssuer }
        val algorithmRaw = (params["algorithm"] ?: DEFAULT_ALGORITHM).uppercase(Locale.US)
        val type = detectOtpType(authority, finalIssuer, algorithmRaw, params["encoder"].orEmpty())
        val counter = if (type == OtpType.HOTP) params["counter"]?.toLongOrNull() ?: 0L else 0L
        val pin = if (type == OtpType.YANDEX) params["pin"].orEmpty() else ""
        // ⚠️ `period=0` / 负数是**能解析成功**的畸形输入（`toIntOrNull` 认它）：
        //    原样存进 TotpConfig 会让下游整除/取模抛 ArithmeticException。
        //    ⇒ 在**解析边界**就收掉，别指望每个消费点都记得兜。
        val period = params["period"]?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_PERIOD
        val digits = if (type == OtpType.STEAM) {
            STEAM_DIGITS
        } else {
            params["digits"]?.toIntOrNull() ?: DEFAULT_DIGITS
        }
        val algorithm = if (type == OtpType.STEAM) {
            DEFAULT_ALGORITHM
        } else {
            normalizeAlgorithm(algorithmRaw)
        }
        return TotpConfig(
            secret = secret,
            period = period,
            digits = digits,
            algorithm = algorithm,
            type = type,
            counter = counter,
            pin = pin,
        )
    }

    /** 解析 `motp://Issuer:Account?secret=...&pin=...`（mOTP 固定 10s / 6 位）。 */
    private fun parseMotp(uri: String): TotpConfig? {
        val match = motpPattern.matchEntire(uri) ?: return null
        val queryParams = parseQuery(match.groupValues[3])
        val secret = queryParams["secret"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return TotpConfig(
            secret = secret,
            period = MOTP_PERIOD,
            digits = MOTP_DIGITS,
            algorithm = "SHA1",
            type = OtpType.MOTP,
            pin = queryParams["pin"].orEmpty(),
        )
    }

    /**
     * 检测 OTP 类型（对齐 Bastion detectOtpType）：authority 优先（hotp/yaotp），
     * 其次 issuer/label/encoder 的 Steam、issuer 的 Yandex 特征，默认 TOTP。
     */
    private fun detectOtpType(
        authority: String,
        issuer: String,
        algorithm: String,
        encoder: String,
    ): OtpType = when {
        authority == "hotp" -> OtpType.HOTP
        authority == "yaotp" -> OtpType.YANDEX
        isSteam(issuer, algorithm, encoder) -> OtpType.STEAM
        issuer.contains("yandex", ignoreCase = true) -> OtpType.YANDEX
        else -> OtpType.TOTP
    }

    /** 拆 otpauth label（可能形如 `Issuer:account` 或仅 `account`）。 */
    private fun splitLabel(label: String): Pair<String, String> {
        val trimmed = label.trim()
        if (!trimmed.contains(":")) return "" to trimmed
        val issuer = trimmed.substringBefore(":").trim()
        val account = trimmed.substringAfter(":").trim()
        return issuer to account
    }

    /**
     * Steam Guard 识别：满足其一即判定为 Steam 验证码（密钥为 Base64，非 Base32）。
     * 与 Bitwarden / Bastion 的识别口径一致（encoder=steam 为 Bastion 的显式标记）。
     */
    /**
     * Steam Guard 识别（密钥为 Base64，非 Base32；5 位 25 字符字母表）。
     *
     * 判定顺序即优先级：**显式标记 `encoder=steam` → 发行方含 "steam" → 算法字段等于 "steam"**。
     *
     * ## ⚠️ 为什么**不再**看 labelPart（账号部分）
     *
     * 原先四个条件里有一条 `labelPart.contains("steam")` —— 它会在
     * `otpauth://totp/github.com:steam@example.com?...` 这类**普通 TOTP 条目**上误判：
     * 账号名里带 "steam" 但走的是标准 Base32 + 6 位 SHA1。误判的后果不是显示难看，
     * 而是**按 Steam 算法算出一个完全不同的码** ⇒ 这条验证码**永远不对**，
     * 且用户完全无从判断是自己输还是软件错。
     *
     * ⇒ 拿掉 labelPart 不损失 Steam 的识别率：真实的 Steam Guard 条目一定有
     *    `issuer=Steam` 或 label 的发行方段是 `Steam`（`detectOtpType` 传的是
     *    [finalIssuer] 与 label 的发行方段，两者都不含账号部分）。
     */
    private fun isSteam(issuer: String, algorithm: String, encoder: String): Boolean {
        val s = "steam"
        return encoder.equals(s, ignoreCase = true) ||
            issuer.contains(s, ignoreCase = true) ||
            algorithm.equals(s, ignoreCase = true)
    }

    /**
     * 解析并归一为可展示结构（含发行方/账号/标题）。无效输入返回 null。
     * UI 统一走此入口，无需关心裸密钥 / otpauth / motp 的差异。
     */
    fun parseToDisplay(input: String): ParsedTotp? {
        val config = parse(input) ?: return null
        val normalized = input.trim()
        val (issuer, account) = when {
            normalized.startsWith("otpauth://", ignoreCase = true) -> {
                val labelRaw = uriDecode(
                    normalized.substringAfter("otpauth://").substringBefore("?").substringAfter("/"),
                )
                val (labelIssuer, labelAccount) = splitLabel(labelRaw)
                val paramIssuer = parseQuery(normalized.substringAfter("?", ""))["issuer"]?.trim().orEmpty()
                (paramIssuer.ifBlank { labelIssuer }) to labelAccount
            }
            normalized.startsWith("motp://", ignoreCase = true) -> {
                val match = motpPattern.matchEntire(normalized)
                if (match == null) {
                    "" to ""
                } else {
                    val issuerRaw = uriDecode(match.groupValues[1]).trim()
                    val accountRaw = uriDecode(match.groupValues[2]).trim()
                    // Bastion 口径：issuer 空时回退 account，再空回退 "mOTP"
                    (issuerRaw.ifBlank { accountRaw.ifBlank { "mOTP" } }) to accountRaw
                }
            }
            else -> "" to ""
        }
        val label = issuer.takeIf { it.isNotBlank() }
            ?: account.takeIf { it.isNotBlank() }
            ?: config.secret.take(8)
        return ParsedTotp(
            secret = config.secret,
            period = config.period,
            digits = config.digits,
            algorithm = config.algorithm,
            type = config.type,
            counter = config.counter,
            pin = config.pin,
            issuer = issuer,
            account = account,
            label = label,
        )
    }

    /**
     * 由归一化 [TotpConfig] 构造可长期存储的 URI（五类型统一出口）：
     * - TOTP：`otpauth://totp/<issuer:account>?secret=...`（Bitwarden 兼容格式）；
     * - HOTP：`otpauth://hotp/...?counter=...`；
     * - YANDEX：`otpauth://yaotp/...`（pin 非空时透传）；
     * - STEAM：保留 `issuer=Steam` 并附 `encoder=steam` 标记；
     * - MOTP：`motp://issuer:account?secret=...&pin=...`。
     * 全部值按 RFC 3986 编码（Base64 密钥中的 `+ / =` 不会被截断或误解码）。
     */
    fun buildUri(config: TotpConfig, issuer: String = "", account: String = ""): String {
        return if (config.type == OtpType.MOTP) {
            buildMotpUri(config, issuer, account)
        } else {
            buildOtpAuth(config, issuer, account)
        }
    }

    private fun buildMotpUri(config: TotpConfig, issuer: String, account: String): String {
        val query = buildString {
            append("secret=").append(uriEncode(config.secret))
            if (config.pin.isNotBlank()) append("&pin=").append(uriEncode(config.pin))
        }
        return "motp://${uriEncode(issuer)}:${uriEncode(account)}?$query"
    }

    private fun buildOtpAuth(config: TotpConfig, issuer: String, account: String): String {
        val effectiveIssuer = if (config.steam) "Steam" else issuer
        val label = buildString {
            if (effectiveIssuer.isNotBlank()) append(effectiveIssuer)
            if (effectiveIssuer.isNotBlank() && account.isNotBlank()) append(":")
            append(account)
        }.ifBlank { effectiveIssuer.ifBlank { config.secret.take(8) } }
        val authority = when (config.type) {
            OtpType.HOTP -> "hotp"
            OtpType.YANDEX -> "yaotp"
            else -> "totp"
        }
        return "otpauth://$authority/${uriEncode(label)}?${otpAuthQuery(config, effectiveIssuer)}"
    }

    /** otpauth query 段（secret/issuer/counter/period/digits/algorithm/encoder/pin）。 */
    private fun otpAuthQuery(config: TotpConfig, effectiveIssuer: String): String = buildString {
        append("secret=").append(uriEncode(config.secret))
        if (effectiveIssuer.isNotBlank()) {
            append("&issuer=").append(uriEncode(effectiveIssuer))
        }
        if (config.type == OtpType.HOTP) append("&counter=").append(config.counter)
        if (config.period != DEFAULT_PERIOD && config.type != OtpType.HOTP) {
            append("&period=").append(config.period)
        }
        if (config.digits != DEFAULT_DIGITS) append("&digits=").append(config.digits)
        if (config.algorithm != "SHA1") append("&algorithm=").append(config.algorithm)
        if (config.steam) append("&encoder=steam")
        if (config.type == OtpType.YANDEX && config.pin.isNotBlank()) {
            append("&pin=").append(uriEncode(config.pin))
        }
    }

    /**
     * 旧签名兼容入口（TOTP / Steam 两用）。新代码请用 [buildUri]。
     */
    fun buildOtpAuthUri(
        secret: String,
        issuer: String = "",
        account: String = "",
        period: Int = 30,
        digits: Int = 6,
        algorithm: String = "SHA1",
        steam: Boolean = false,
    ): String = buildUri(
        TotpConfig(
            secret = secret,
            period = period,
            digits = digits,
            algorithm = algorithm,
            type = if (steam) OtpType.STEAM else OtpType.TOTP,
        ),
        issuer = issuer,
        account = account,
    )

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, String>()
        for (segment in query.split("&")) {
            if (segment.isBlank()) continue
            val key = uriDecode(segment.substringBefore("="))
            val rawValue = segment.substringAfter("=", "")
            if (key.isNotBlank()) {
                result[key] = uriDecode(rawValue)
            }
        }
        return result
    }

    private fun parseBareSecret(raw: String): TotpConfig? {
        // 裸 base32 密钥：去除常见前缀/分隔，校验是否为合法 base32
        val candidate = raw.trim().replace(Regex("[\\s:;,]+"), "").uppercase(Locale.US)
        if (candidate.isEmpty() || !base32Alphabet.matches(candidate)) return null
        return TotpConfig(secret = candidate)
    }
}

/** 是否为 RFC 3986 unreserved 字符（uriEncode 无需转义的部分）。 */
private fun isUnreserved(byte: Int): Boolean {
    val c = byte.toChar()
    return c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '~'
}

/**
 * RFC 3986 编码（unreserved 之外全部 %XX，含 `+ / =`）。
 * 对齐 android.net.Uri.encode 的语义（Base64 密钥可安全入 query）。
 */
internal fun uriEncode(raw: String): String {
    val out = StringBuilder(raw.length)
    for (byte in raw.toByteArray(Charsets.UTF_8)) {
        val value = byte.toInt() and 0xFF
        if (isUnreserved(value)) {
            out.append(value.toChar())
        } else {
            out.append('%').append(String.format(Locale.US, "%02X", value))
        }
    }
    return out.toString()
}

private fun hexValue(char: Char): Int = when (char) {
    in '0'..'9' -> char - '0'
    in 'a'..'f' -> char - 'a' + HEX_DIGIT_OFFSET
    in 'A'..'F' -> char - 'A' + HEX_DIGIT_OFFSET
    else -> -1
}

/**
 * RFC 3986 解码（%XX → 字节；`+` 按字面保留）。
 * 对齐 android.net.Uri.decode 的语义——注意与 URLDecoder 不同：
 * Base64 密钥中的 `+` 不会被误转为空格。
 */
internal fun uriDecode(raw: String): String {
    if (!raw.contains('%')) return raw
    val out = ByteArrayOutputStream(raw.length)
    var index = 0
    while (index < raw.length) {
        val char = raw[index]
        // 合法 %XX：'%' 后至少还有两个字符且均为十六进制位
        val validEscape = char == '%' && index + 2 < raw.length
        val high = if (validEscape) hexValue(raw[index + 1]) else -1
        val low = if (validEscape) hexValue(raw[index + 2]) else -1
        if (high >= 0 && low >= 0) {
            out.write((high shl HEX_BITS_PER_DIGIT) or low)
            index += ESCAPE_SEQUENCE_LENGTH
        } else {
            out.write(char.code and BYTE_MASK)
            index += 1
        }
    }
    return out.toString("UTF-8")
}
