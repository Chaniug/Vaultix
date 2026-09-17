/*
 * Vaultix — core:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 新建 SSH 条目时「自己生成一对密钥」（定稿 §11.12）。
 *
 * ## ★★ 导出格式：为什么最终是 OpenSSH 新格式，而不是 PKCS#8 PEM
 *
 * 定稿 §11.12 的建议是「先做 PKCS#8 + `ssh-ed25519` 公钥，OpenSSH 新格式列第二期」。
 * **这个建议被 2026-09-18 的实测推翻了**，过程记在这里，别再走一遍：
 *
 * | 假设 | 实测（OpenSSH 9.6p1 / Ubuntu，`ssh-keygen -y -f key.pem`） |
 * |---|---|
 * | PKCS#8 PEM 的 **RSA** 私钥 | ✅ 能读（`ssh-keygen -y` 导出的公钥与本实现逐位一致） |
 * | PKCS#8 PEM 的 **Ed25519** 私钥 | 🔴 **`Load key: invalid format`** |
 *
 * ⇒ 也就是说：**Ed25519 走 PKCS#8 = 生成出来绝大多数 SSH 客户端直接用不了**。
 * 而定稿 §11.12 自己点名的失败模式正是「做出一个**生成了但用不上**的按钮」——
 * 照原建议做，就会正好撞上它。
 *
 * 所以本实现直接产出 **OpenSSH 新格式私钥**
 * （`-----BEGIN OPENSSH PRIVATE KEY-----`，内部 cipher=`none`、kdf=`none`）：
 * - ✅ `ssh` / `ssh-keygen` / `ssh-add` 直接可用（已实测比对）；
 * - ✅ PuTTYgen、KeePassXC 的 KeeAgent 也都读这种格式；
 * - 成本没有想象中高：**无口令**变体不需要 bcrypt-kdf —— 那才是定稿说的"独立一块"，
 *   而"不带口令"对存在密码管理器里的私钥是合理的（它已被库的主密码保护）。
 *
 * ⚠️ cipher=`none` 的**代价**要如实说：私钥文件本身不加密。但它在 Vaultix 里是
 * **条目字段**，受库的主密码保护 —— 与"导出的 .pem 落在磁盘上"是两种场景，
 * 不要拿后者的要求吓唬前者。
 *
 * ## 为什么必须自己实现 Ed25519，而不是调 `KeyPairGenerator.getInstance("Ed25519")`
 *
 * Android 的 JCA 从 **API 33** 才提供 Ed25519，而本项目 `minSdk = 26`。
 * 直接调用会在绝大多数设备上抛 `NoSuchAlgorithmException`。
 * ⇒ 走 Bouncy Castle（`bcprov-jdk18on`），它是纯 Java，与 API 级别无关。
 * （`core:crypto` 早已依赖同一个库，没有引入新的第三方。）
 *
 * ## 与 KeePassXC 的对齐（不可省）
 *
 * KeePassXC 把 SSH 密钥放在条目的**自定义字段**里，字段名逐字是
 * `publicKey` / `privateKey`。字段名写错 ⇒ 在 KeePassXC 里**看不到**。
 * 字段名映射在 `VaultSshKey` 上维护，本类只负责产出内容。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.common

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters

/**
 * SSH 密钥对生成器（纯 JVM；不依赖 `android.*`，可直接在 `src/test` 里跑）。
 */
object SshKeyGenerator {

    /** 可选算法。 */
    enum class Algorithm(
        /** OpenSSH blob 里的算法名（必须与 `ssh-keygen` 的输出逐字一致）。 */
        val sshName: String,
    ) {
        /** 现代、短、KeePassXC 默认 —— **推荐**。 */
        ED25519("ssh-ed25519"),

        /** 老服务器 / 老客户端兼容。3072 位是当下普遍接受的下限。 */
        RSA_3072("ssh-rsa"),
    }

    /**
     * 生成结果。
     *
     * @param publicKey 单行 OpenSSH 公钥行（`<算法> <base64>`，与 `id_*.pub` 同格式）；
     *   可直接交给 [SshFingerprint.of] 算指纹 —— 那正是"生成完立刻能核对"的关键。
     * @param privateKeyPem **OpenSSH 新格式**私钥文本（`ssh` / `ssh-keygen` 可直接用）。
     */
    data class GeneratedKeyPair(
        val algorithm: Algorithm,
        val publicKey: String,
        val privateKeyPem: String,
    )

    /** RSA 的模长（位）。 */
    private const val RSA_KEY_BITS = 3072

    /** 私钥 PEM 的每行宽度（OpenSSH 新格式用 70，与 `ssh-keygen` 输出一致）。 */
    private const val PEM_LINE_WIDTH = 70

    private const val PEM_HEADER = "-----BEGIN OPENSSH PRIVATE KEY-----"
    private const val PEM_FOOTER = "-----END OPENSSH PRIVATE KEY-----"

    /** 新格式的文件魔数（含结尾 NUL）。 */
    private val MAGIC = "openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII)

    /** cipher / kdf 均为 `none`（无口令；见类注释里的取舍）。 */
    private const val NONE = "none"

    /** cipher=`none` 时的分组长度：填充按 8 字节对齐。 */
    private const val BLOCK_SIZE = 8

    /** 私钥注释（生成时还不知道"给谁用"，给个能认出来源的即可）。 */
    private const val COMMENT = "vaultix"

    /**
     * 生成一对密钥。
     *
     * ⚠️ **调用方必须在 UI 上给出备份提示**：私钥**不可再生**（丢了就是丢了，
     * 与"passkey 私钥是唯一不可再生的东西"同一类）。见定稿 §11.12。
     *
     * @throws java.security.GeneralSecurityException 底层算法不可用时抛出（不吞）：
     *   吞成"生成失败但不知道为什么"会让用户重复点一个永远不成功的按钮。
     */
    fun generate(algorithm: Algorithm): GeneratedKeyPair = when (algorithm) {
        Algorithm.ED25519 -> generateEd25519()
        Algorithm.RSA_3072 -> generateRsa()
    }

    private fun generateEd25519(): GeneratedKeyPair {
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val pair = generator.generateKeyPair()
        val public = pair.public as Ed25519PublicKeyParameters
        val private = pair.private as Ed25519PrivateKeyParameters
        val publicBlob = publicBlob(Algorithm.ED25519, public.encoded)
        return GeneratedKeyPair(
            algorithm = Algorithm.ED25519,
            publicKey = publicKeyLine(Algorithm.ED25519, publicBlob),
            // ★ 私钥段里的"私钥"是 64 字节：**种子 || 公钥** —— OpenSSH 的约定。
            //   少了后半段 32 字节，`ssh-keygen -y` 会报公钥与私钥不匹配。
            privateKeyPem = wrapPrivateKey(
                publicBlob = publicBlob,
                privateSection = privateSection(Algorithm.ED25519) {
                    writeSshBytes(public.encoded)
                    writeSshBytes(private.encoded + public.encoded)
                },
            ),
        )
    }

    private fun generateRsa(): GeneratedKeyPair {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(RSA_KEY_BITS)
        return fromRsa(generator.generateKeyPair())
    }

    private fun fromRsa(pair: KeyPair): GeneratedKeyPair {
        val public = pair.public as RSAPublicKey
        val private = pair.private as RSAPrivateCrtKey
        val publicBlob = stringField(Algorithm.RSA_3072.sshName) +
            mpintField(public.publicExponent) +
            mpintField(public.modulus)
        return GeneratedKeyPair(
            algorithm = Algorithm.RSA_3072,
            publicKey = publicKeyLine(Algorithm.RSA_3072, publicBlob),
            privateKeyPem = wrapPrivateKey(
                publicBlob = publicBlob,
                privateSection = privateSection(Algorithm.RSA_3072) {
                    writeMpint(private.modulus)
                    writeMpint(private.publicExponent)
                    writeMpint(private.privateExponent)
                    // iqmp = q⁻¹ mod p（CRT 参数，OpenSSH 的私有段要求它，顺序也不能变）
                    writeMpint(private.primeQ.modInverse(private.primeP))
                    writeMpint(private.primeP)
                    writeMpint(private.primeQ)
                },
            ),
        )
    }

    /** `<算法> <base64 blob>`。 */
    private fun publicKeyLine(algorithm: Algorithm, blob: ByteArray): String =
        "${algorithm.sshName} ${Base64.getEncoder().encodeToString(blob)}"

    /** Ed25519 的公钥 blob：`string 算法名` + `string 公钥`（32 字节）。 */
    private fun publicBlob(algorithm: Algorithm, keyBytes: ByteArray): ByteArray =
        stringField(algorithm.sshName) + stringField(keyBytes)

    /**
     * 私钥段（**未填充**）：两个相同的校验字 + 算法名 + 算法私有参数 + 注释。
     *
     * [body] 写入各算法自己的私有参数（Ed25519 与 RSA 的字段顺序不同，别互相抄）。
     */
    private fun privateSection(
        algorithm: Algorithm,
        body: ByteArrayOutputStream.() -> Unit,
    ): ByteArray = ByteArrayOutputStream().apply {
        val check = SecureRandom().nextInt()
        writeInt(check)
        writeInt(check)
        writeSshString(algorithm.sshName)
        body()
        writeSshString(COMMENT)
    }.toByteArray()

    /**
     * 组装 OpenSSH 新格式容器并输出 PEM。
     *
     * ```
     * "openssh-key-v1\0" · string cipher · string kdf · string kdfoptions
     *   · uint32 密钥数 · string 公钥 blob · string（填充后的）私钥段
     * ```
     */
    private fun wrapPrivateKey(publicBlob: ByteArray, privateSection: ByteArray): String {
        val container = ByteArrayOutputStream().apply {
            write(MAGIC)
            writeSshString(NONE)
            writeSshString(NONE)
            writeSshBytes(ByteArray(0))
            writeInt(1)
            writeSshBytes(publicBlob)
            writeSshBytes(pad(privateSection))
        }.toByteArray()
        return buildString {
            appendLine(PEM_HEADER)
            Base64.getEncoder().encodeToString(container)
                .chunked(PEM_LINE_WIDTH)
                .forEach { appendLine(it) }
            append(PEM_FOOTER)
        }
    }

    /**
     * 私钥段按分组长度补齐，填充字节是 `1, 2, 3, …`。
     *
     * ⚠️ 即使 cipher=`none`，**也必须补齐** —— OpenSSH 按分组读这一段，
     * 长度不对会直接判为格式错误（正是"看起来写对了、就是读不出来"的典型）。
     */
    private fun pad(section: ByteArray): ByteArray {
        val remainder = section.size % BLOCK_SIZE
        val padLength = if (remainder == 0) 0 else BLOCK_SIZE - remainder
        if (padLength == 0) return section
        return section + ByteArray(padLength) { (it + 1).toByte() }
    }

    // ---- OpenSSH 线格式（RFC 4253 §5）----

    /** `string` = 4 字节大端长度 + 字节。 */
    private fun stringField(value: ByteArray): ByteArray = lengthPrefix(value.size) + value

    private fun stringField(value: String): ByteArray = stringField(value.toByteArray(Charsets.UTF_8))

    /**
     * `mpint` = 大端整数，去掉前导零；最高位为 1 时前置 `0x00`（**符号位**，不是填充）。
     *
     * ⚠️ 漏掉这一步的话，模数最高位为 1 的那部分密钥会算出**错误的指纹**，
     * 而且错得很隐蔽（另一端能解析、只是对不上）。
     */
    private fun mpintField(value: BigInteger): ByteArray {
        val magnitude = value.toByteArray()
        var start = 0
        while (start < magnitude.size && magnitude[start] == ZERO_BYTE) start++
        val significant = magnitude.copyOfRange(start, magnitude.size)
        val needsSignByte = significant.isNotEmpty() && significant[0] < 0
        return lengthPrefix(significant.size + if (needsSignByte) 1 else 0) +
            (if (needsSignByte) byteArrayOf(ZERO_BYTE) else ByteArray(0)) +
            significant
    }

    private fun lengthPrefix(size: Int): ByteArray = byteArrayOf(
        (size ushr SHIFT_24 and BYTE_MASK).toByte(),
        (size ushr SHIFT_16 and BYTE_MASK).toByte(),
        (size ushr SHIFT_8 and BYTE_MASK).toByte(),
        (size and BYTE_MASK).toByte(),
    )

    private fun ByteArrayOutputStream.writeInt(value: Int) = write(lengthPrefix(value))

    private fun ByteArrayOutputStream.writeSshString(value: String) = write(stringField(value))

    private fun ByteArrayOutputStream.writeSshBytes(value: ByteArray) = write(stringField(value))

    private fun ByteArrayOutputStream.writeMpint(value: BigInteger) = write(mpintField(value))

    private const val ZERO_BYTE: Byte = 0

    /** 一个字节的掩码（大端拆分用）。 */
    private const val BYTE_MASK = 0xFF

    /** 大端拆字节的位移量（24 / 16 / 8）。 */
    private const val SHIFT_24 = 24
    private const val SHIFT_16 = 16
    private const val SHIFT_8 = 8
}
