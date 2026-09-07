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
 * 中的 `SymmetricCryptoKey` 搬运改造而来。改造点：
 *   1) `encKey` / `macKey` 由裸 `ByteArray` 换成 [SecureBytes]（Docs/03 第 4 节要求）；
 *   2) data class → 普通 class：`ByteArray` 在 data class 自动生成的 equals 中
 *      不做内容比较，且我们需要自定义清零语义；
 *   3) 新增 [gcmKey]：AES-GCM 使用独立子密钥，避免与 CBC 复用同一密钥材料。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.crypto

import java.nio.charset.StandardCharsets

/**
 * Bitwarden 账号对称密钥：64 字节 = 32 字节 AES-256 加密密钥 ‖ 32 字节 HMAC-SHA256 密钥。
 *
 * 来源（Docs/03 第 2.3 节）：受保护对称密钥用 StretchedMasterKey 解包后得到 64 字节。
 *
 * @property encKey AES-256 加密密钥（32 字节）
 * @property macKey HMAC-SHA256 密钥（32 字节）
 */
class SymmetricCryptoKey(
    val encKey: SecureBytes,
    val macKey: SecureBytes,
) {

    /** 便捷构造：从裸字节数组拷贝一份（不清零入参）。 */
    constructor(encKey: ByteArray, macKey: ByteArray) : this(
        SecureBytes.of(encKey),
        SecureBytes.of(macKey),
    )

    init {
        require(encKey.size == KEY_SIZE) {
            "encKey must be $KEY_SIZE bytes, got ${encKey.size}"
        }
        require(macKey.size == KEY_SIZE) {
            "macKey must be $KEY_SIZE bytes, got ${macKey.size}"
        }
    }

    @Volatile
    private var cachedGcmKey: SecureBytes? = null

    /**
     * AES-256-GCM 专用子密钥：`HKDF-Expand(encKey, info="gcm", 32)`。
     *
     * 刻意不复用 [encKey] 本体：同一密钥材料在不同 AEAD/分组模式下复用会放大
     * nonce 误用带来的后果。HKDF-Expand 以 [encKey] 作为 PRK 是安全的——
     * [encKey] 本身即 32 字节均匀随机密钥。
     *
     * 结果缓存；[clear] 时一并清零。
     */
    val gcmKey: SecureBytes
        @Synchronized
        get() = cachedGcmKey ?: SecureBytes.adopt(
            encKey.useBytes { hkdfExpand(prk = it, info = GCM_INFO, length = KEY_SIZE) },
        ).also { cachedGcmKey = it }

    /** 清零全部密钥材料（含缓存的 GCM 子密钥）。幂等；清零后对象不可再用。 */
    @Synchronized
    fun clear() {
        cachedGcmKey?.zero()
        cachedGcmKey = null
        encKey.zero()
        macKey.zero()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SymmetricCryptoKey) return false
        // 恒定时间比较：与 Bastion 一致，密钥比较一律走防时序原语
        //（SecureBytes.equals 内部即 MessageDigest.isEqual）。
        return encKey == other.encKey && macKey == other.macKey
    }

    override fun hashCode(): Int {
        var result = encKey.hashCode()
        result = 31 * result + macKey.hashCode()
        return result
    }

    override fun toString(): String = "SymmetricCryptoKey(enc=**, mac=**)"

    companion object {
        /** 单个子密钥长度（字节）。 */
        const val KEY_SIZE = 32

        /** 完整对称密钥长度（字节）：enc ‖ mac。 */
        const val FULL_KEY_SIZE = 64

        private val GCM_INFO: ByteArray = "gcm".toByteArray(StandardCharsets.UTF_8)

        /** 从 64 字节裸密钥拆分。用于解包账号对称密钥。 */
        fun fromFullKey(fullKey: ByteArray): SymmetricCryptoKey {
            require(fullKey.size == FULL_KEY_SIZE) {
                "Full symmetric key must be $FULL_KEY_SIZE bytes, got ${fullKey.size}"
            }
            return SymmetricCryptoKey(
                encKey = fullKey.copyOfRange(0, KEY_SIZE),
                macKey = fullKey.copyOfRange(KEY_SIZE, FULL_KEY_SIZE),
            )
        }

        /** 生成一把全新的随机 64 字节对称密钥（新建 Bitwarden 账号 / 本地库时使用）。 */
        fun random(): SymmetricCryptoKey = SymmetricCryptoKey(
            encKey = SecureBytes.random(KEY_SIZE),
            macKey = SecureBytes.random(KEY_SIZE),
        )
    }
}
