/*
 * Vaultix — core:crypto
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * 本文件为 Vaultix 新增（Bastion 无对应实现）：Bastion 只有 AES-CBC + HMAC-SHA256，
 * 而 Vaultix 的 Docs/03-密码学与密钥管理.md 第 4 节要求本地密钥存储（生物识别解锁凭据、
 * OAuth token）走 Keystore AES-GCM，因此 core:crypto 必须提供 GCM 原语。
 */
package io.vaultix.crypto

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-GCM 变换名。 */
internal const val GCM_TRANSFORMATION = "AES/GCM/NoPadding"

/** GCM 认证标签长度（位）。128 位是 GCM 的标准强度，也是 Keystore 默认值。 */
internal const val GCM_TAG_BITS = 128

/** GCM 认证标签长度（字节）。 */
internal const val GCM_TAG_BYTES = GCM_TAG_BITS / 8

/** GCM nonce（IV）长度（字节）。NIST SP 800-38D 推荐的 96 位，避免额外 GHASH 重算。 */
internal const val GCM_NONCE_SIZE = 12

/**
 * AES-256-GCM 密封结果：nonce（12B）‖ ciphertext（含 16B 认证标签）。
 *
 * 信封编码：`VG1.<b64(nonce)>|<b64(ciphertext)>`。
 * 前缀刻意用非数字 `VG1` 而非 Bitwarden 的数字 type，避免与 EncString 的类型号冲突
 * （Bitwarden 的 type 是预留给上游的编号空间，Vaultix 不得占用）。
 *
 * @property nonce 96 位随机数，每次加密由 SecureRandom 生成，绝不复用
 * @property ciphertext 密文，尾部 16 字节为 GCM 标签（JCE 自动追加）
 */
class GcmSealed private constructor(
    val nonce: ByteArray,
    val ciphertext: ByteArray,
) {

    init {
        require(nonce.size == GCM_NONCE_SIZE) {
            "GCM nonce must be $GCM_NONCE_SIZE bytes, got ${nonce.size}"
        }
        require(ciphertext.size >= GCM_TAG_BYTES) {
            "GCM ciphertext must carry a $GCM_TAG_BYTES-byte tag, got ${ciphertext.size} bytes"
        }
    }

    /** 编码为可持久化字符串。 */
    fun encode(): String =
        "$ENVELOPE_PREFIX.${nonce.encodeStandardBase64()}|${ciphertext.encodeStandardBase64()}"

    /** 清零内部数组。 */
    fun wipe() {
        nonce.fill(0)
        ciphertext.fill(0)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GcmSealed) return false
        return MessageDigest.isEqual(nonce, other.nonce) &&
            MessageDigest.isEqual(ciphertext, other.ciphertext)
    }

    override fun hashCode(): Int {
        var result = nonce.contentHashCode()
        result = 31 * result + ciphertext.contentHashCode()
        return result
    }

    override fun toString(): String = "GcmSealed(ciphertext=${ciphertext.size} bytes)"

    companion object {
        /** 信封前缀：Vaultix GCM v1。 */
        const val ENVELOPE_PREFIX = "VG1"

        private const val ENVELOPE_PART_COUNT = 2

        /**
         * 组装密封结果。
         *
         * @param wipeSources 为 true 时清零入参数组（所有权转移）
         */
        fun of(
            nonce: ByteArray,
            ciphertext: ByteArray,
            wipeSources: Boolean = false,
        ): GcmSealed {
            val sealed = GcmSealed(
                nonce = nonce.copyOf(),
                ciphertext = ciphertext.copyOf(),
            )
            if (wipeSources) {
                nonce.fill(0)
                ciphertext.fill(0)
            }
            return sealed
        }

        /** 从 [encode] 产出的字符串还原。 */
        fun decode(raw: String): GcmSealed {
            require(raw.isNotBlank()) { "GCM envelope is blank" }
            require(raw.length <= MAX_CIPHER_STRING_LENGTH) {
                "GCM envelope too large: ${raw.length}"
            }

            val dotIndex = raw.indexOf('.')
            require(dotIndex != -1) { "Malformed GCM envelope: missing type prefix" }

            val prefix = raw.substring(0, dotIndex)
            require(prefix == ENVELOPE_PREFIX) {
                "Unsupported GCM envelope prefix: $prefix (expected $ENVELOPE_PREFIX)"
            }

            val parts = raw.substring(dotIndex + 1).split('|')
            require(parts.size == ENVELOPE_PART_COUNT) {
                "GCM envelope requires nonce|ciphertext, got ${parts.size} parts"
            }

            return GcmSealed(
                nonce = decodeStandardBase64(parts[0], "nonce"),
                ciphertext = decodeStandardBase64(parts[1], "ciphertext"),
            )
        }
    }
}

/**
 * AES-256-GCM 加密。
 *
 * @param key 32 字节密钥
 * @param plaintext 明文
 * @param aad 附加认证数据（不加密但参与完整性校验）；为 null 时不设置 AAD
 * @return 密封结果，nonce 由 [CryptoRandom] 生成
 */
internal fun aesGcmEncrypt(
    key: SecureBytes,
    plaintext: ByteArray,
    aad: ByteArray?,
): GcmSealed {
    val nonce = CryptoRandom.nextBytes(GCM_NONCE_SIZE)
    val secretKey = key.useBytes { SecretKeySpec(it, "AES") }
    val cipher = Cipher.getInstance(GCM_TRANSFORMATION)
    cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, nonce))
    if (aad != null) {
        cipher.updateAAD(aad)
    }
    val ciphertext = cipher.doFinal(plaintext)
    return GcmSealed.of(nonce = nonce, ciphertext = ciphertext, wipeSources = true)
}

/**
 * AES-256-GCM 解密。
 *
 * GCM 标签校验失败由 JCE 抛出 `AEADBadTagException`，调用方无需手工比对。
 *
 * @param key 32 字节密钥，必须与加密时一致
 * @param sealed 密封结果
 * @param aad 附加认证数据，必须与加密时一致
 * @throws javax.crypto.AEADBadTagException 标签校验失败（密钥错误或密文被篡改）
 */
internal fun aesGcmDecrypt(
    key: SecureBytes,
    sealed: GcmSealed,
    aad: ByteArray?,
): ByteArray {
    val secretKey = key.useBytes { SecretKeySpec(it, "AES") }
    val cipher = Cipher.getInstance(GCM_TRANSFORMATION)
    cipher.init(
        Cipher.DECRYPT_MODE,
        secretKey,
        GCMParameterSpec(GCM_TAG_BITS, sealed.nonce),
    )
    if (aad != null) {
        cipher.updateAAD(aad)
    }
    return cipher.doFinal(sealed.ciphertext)
}
