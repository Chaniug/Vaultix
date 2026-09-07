/*
 * Vaultix — core:crypto
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 本文件由 Bastion 项目（GPL-3.0，Copyright 2025 JoyinJoester）的
 *   bastion/Bastion/app/src/main/java/com/bastion/app/bitwarden/crypto/BitwardenCrypto.kt
 * 中的 PBKDF2 / HKDF 私有方法搬运改造而来（原 `pbkdf2Sha256` / `hkdfExpand` / `hkdf`）。
 * Vaultix 将其下沉为 internal 顶层纯函数：
 *   1) 去掉 `object` 单例耦合，便于 JVM 单元测试直接调用；
 *   2) 去掉 `android.util.Base64` 依赖（本文件不涉及 Base64，见 Base64Codec.kt）；
 *   3) 保留 BouncyCastle 的 PBKDF2 实现——MasterKey 是任意二进制，JCE 的
 *      `PBEKeySpec` 强制 `char[]`，无法安全承载原始字节。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.params.KeyParameter
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HMAC-SHA256 的 JCE 算法名。 */
internal const val HMAC_SHA256 = "HmacSHA256"

/** SHA-256 输出长度（字节）。 */
internal const val SHA256_LENGTH = 32

/** SHA-256 摘要。 */
internal fun sha256(data: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(data)

/**
 * PBKDF2-HMAC-SHA256（BouncyCastle 实现）。
 *
 * 与 Bastion 保持一致：直接以原始字节作为 seed 与 salt，避免 `char[]` 转换带来的
 * 编码失真（JCE 的 `PBEKeySpec` 只接受 `char[]`，而 MasterKey 是 32 字节任意二进制）。
 *
 * @param seed 口令材料（原始字节）
 * @param salt 盐（原始字节）
 * @param iterations 迭代次数，必须为正
 * @param lengthBytes 输出长度（字节），必须为正
 */
internal fun pbkdf2Sha256(
    seed: ByteArray,
    salt: ByteArray,
    iterations: Int,
    lengthBytes: Int,
): ByteArray {
    require(iterations > 0) { "PBKDF2 iterations must be positive: $iterations" }
    require(lengthBytes > 0) { "PBKDF2 output length must be positive: $lengthBytes" }

    val generator = PKCS5S2ParametersGenerator(SHA256Digest())
    generator.init(seed, salt, iterations)
    val params = generator.generateDerivedMacParameters(lengthBytes * 8)
    val key = (params as KeyParameter).key
    return key
}

/**
 * HKDF-Extract（RFC 5869）：`prk = HMAC(salt, seed)`。
 *
 * Bastion 原实现命名为 `hkdf`，语义即 Extract-then-Expand，此处拆分命名更准确。
 */
internal fun hkdfExtract(salt: ByteArray, seed: ByteArray): ByteArray {
    val mac = Mac.getInstance(HMAC_SHA256)
    mac.init(SecretKeySpec(salt, HMAC_SHA256))
    return mac.doFinal(seed)
}

/**
 * HKDF-Expand（RFC 5869，SHA-256）。
 *
 * @param prk 伪随机密钥（>= 32 字节）
 * @param info 上下文信息（如 "enc" / "mac"）
 * @param length 期望输出长度（字节）
 */
internal fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
    require(length > 0) { "HKDF output length must be positive: $length" }

    val mac = Mac.getInstance(HMAC_SHA256)
    mac.init(SecretKeySpec(prk, HMAC_SHA256))

    val blocks = (length + SHA256_LENGTH - 1) / SHA256_LENGTH
    val output = ByteArray(length)
    var block = ByteArray(0)
    var position = 0

    for (index in 1..blocks) {
        mac.reset()
        mac.update(block)
        mac.update(info)
        mac.update(index.toByte())
        val next = mac.doFinal()
        if (index > 1) {
            block.fill(0)
        }
        block = next

        val copyLength = minOf(SHA256_LENGTH, length - position)
        System.arraycopy(block, 0, output, position, copyLength)
        position += copyLength
    }
    block.fill(0)

    return output
}

/** 便捷封装：Extract + Expand 一步完成。 */
internal fun hkdf(
    seed: ByteArray,
    salt: ByteArray,
    info: ByteArray,
    length: Int,
): ByteArray {
    val prk = hkdfExtract(salt = salt, seed = seed)
    return try {
        hkdfExpand(prk = prk, info = info, length = length)
    } finally {
        prk.fill(0)
    }
}

/** 计算 `HMAC-SHA256(key, iv ‖ data)`，用于 EncString 的 encrypt-then-MAC。 */
internal fun computeCbcMac(iv: ByteArray, data: ByteArray, macKey: ByteArray): ByteArray {
    val mac = Mac.getInstance(HMAC_SHA256)
    mac.init(SecretKeySpec(macKey, HMAC_SHA256))
    mac.update(iv)
    mac.update(data)
    return mac.doFinal()
}
