/*
 * Vaultix — data:bitwarden
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 加密流程严格对齐 Bitwarden 官方导出器：
 *   bitwarden/sdk-internal → crates/bitwarden-exporters/src/encrypted_json.rs
 *   bitwarden/sdk-internal → crates/bitwarden-crypto/src/keys/{pin_key,kdf,utils}.rs
 *
 * 官方流程（`export_encrypted_json`）：
 * ```rust
 * let salt = generate_random_bytes::<[u8; 16]>();   // 16 字节随机
 * let salt = B64::from(salt.as_slice());            // → Base64 字符串
 * // 用「Base64 字符串的 UTF-8 字节」做盐，绝非解码后的原始 16 字节
 * let key = PinKey::derive(password.as_bytes(), salt.to_string().as_bytes(), &kdf)?;
 * let enc_key_validation = Uuid::new_v4().to_string();   // ← 随机 UUID，不是固定串
 * ```
 *
 * ⚠️ **与 Bastion 的关键差异（互操作 bug）**：Bastion 的
 * `bitwarden/export/BitwardenJsonExport.kt` 把 `encKeyValidation` 加密为字面量
 * `"Bitwarden"`，而官方加密的是**随机 UUID v4**。官方导入器会校验「解密结果能否
 * 解析为 UUID」，Bastion 式文件会被拒。本实现以官方为准。
 *
 * 加密原语全部复用 [VaultixCrypto]，本文件不自行实现任何密码学。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.bitwarden.export

import io.vaultix.crypto.SecureBytes
import io.vaultix.crypto.SymmetricCryptoKey
import io.vaultix.crypto.VaultixCrypto
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bitwarden **密码保护**（password protected）加密导出器。
 *
 * 只实现「密码保护」一种：它用用户自选密码派生密钥、可导入任意账号，
 * 是「服务器损毁后自救」的解药。官方的另一形态「account restricted」
 * （仅能用原账号密钥解）在账号被删/密钥轮换后即失效，**不实现**。
 */
@Singleton
class BitwardenEncryptedExporter @Inject constructor(
    private val crypto: VaultixCrypto,
) {
    private val json = Json {
        // 未识别的字段一律忽略：官方未来加字段时本实现不至于解析失败
        ignoreUnknownKeys = true
        // 显式输出默认值，保证 Bool/Option 字段（如 encrypted/passwordProtected）始终在
        // 输出里——官方 serde 也总是输出它们。
        encodeDefaults = true
        prettyPrint = true
    }

    /**
     * 生成加密导出 JSON 字符串。
     *
     * @param plain 待加密的明文导出结构（由 [toExportCipher] 等映射而来）。
     * @param exportPassword 用户为**本次导出**设置的密码（与账号主密码无关）。
     * @param kdf 沿用账号 KDF 参数（官方行为：导出跟随账号设置）。
     * @return 可直接落盘的加密导出 JSON（UTF-8）。
     */
    fun export(plain: BitwardenPlainExport, exportPassword: String, kdf: ExportKdf): String {
        require(exportPassword.isNotEmpty()) { "Export password must not be empty" }

        // 1. 16 字节随机盐 → Base64 字符串（官方 generate_random_bytes + B64）
        // Base64 编码在此就地完成：core:crypto 的 ByteArray.encodeStandardBase64 是
        // internal，跨模块不可见，故本模块自持 STANDARD 编码器。
        val saltBytes = ByteArray(SALT_SIZE)
        SecureRandom().nextBytes(saltBytes)
        val salt = try {
            Base64.getEncoder().encodeToString(saltBytes)
        } finally {
            saltBytes.fill(0)
        }

        // 2. 用「Base64 字符串的 UTF-8 字节」派生导出密钥（官方注释明确要求，勿改）
        val exportKey = deriveExportKey(exportPassword, salt, kdf)

        return try {
            // 3. encKeyValidation = 加密「随机 UUID v4」（官方行为，非固定串）
            val validationPlaintext = UUID.randomUUID().toString()
            val encKeyValidation = crypto.encryptString(validationPlaintext, exportKey)

            // 4. data = 加密明文 JSON
            val plainJson = json.encodeToString(BitwardenPlainExport.serializer(), plain)
            val data = crypto.encryptString(plainJson, exportKey)

            val envelope = BitwardenEncryptedExport(
                salt = salt,
                kdfType = kdf.typeCode,
                kdfIterations = kdf.iterations,
                kdfMemory = kdf.memoryMb,
                kdfParallelism = kdf.parallelism,
                encKeyValidation = encKeyValidation,
                data = data,
            )
            json.encodeToString(BitwardenEncryptedExport.serializer(), envelope)
        } finally {
            exportKey.clear()
        }
    }

    /**
     * 由导出密码派生用于加密的对称密钥。
     *
     * 沿用账号 KDF（PBKDF2 或 Argon2id）得到 32 字节 kdfKey，再用
     * [VaultixCrypto.stretchMasterKey] 扩展为 `encKey ‖ macKey`（即 HKDF-Expand
     * info="enc"/"mac"）——正是官方 `PinKey::derive` + `stretch_key` 的等价流程。
     *
     * @param salt **Base64 字符串**（内部按 UTF-8 取字节作为盐，官方行为）。
     */
    private fun deriveExportKey(
        exportPassword: String,
        salt: String,
        kdf: ExportKdf,
    ): SymmetricCryptoKey {
        val kdfKey: SecureBytes = when (kdf.typeCode) {
            BitwardenExportKdfType.ARGON2_ID -> crypto.deriveMasterKeyArgon2(
                password = exportPassword,
                salt = salt,
                iterations = kdf.iterations,
                memoryMb = requireNotNull(kdf.memoryMb) { "Argon2id export requires kdfMemory" },
                parallelism = requireNotNull(kdf.parallelism) {
                    "Argon2id export requires kdfParallelism"
                },
            )

            else -> crypto.deriveMasterKeyPbkdf2(
                password = exportPassword,
                salt = salt,
                iterations = kdf.iterations,
            )
        }

        return try {
            crypto.stretchMasterKey(kdfKey)
        } finally {
            kdfKey.zero()
        }
    }

    private companion object {
        /** 官方盐长度：16 字节（`generate_random_bytes::<[u8; 16]>()`）。 */
        const val SALT_SIZE = 16
    }
}

/**
 * 导出使用的 KDF 参数快照（沿用账号设置）。
 *
 * @property typeCode 0=PBKDF2_SHA256、1=Argon2id。
 * @property iterations PBKDF2 迭代次数 / Argon2 迭代次数 t。
 * @property memoryMb Argon2 内存（MiB）；PBKDF2 时 null。
 * @property parallelism Argon2 并行度 p；PBKDF2 时 null。
 */
data class ExportKdf(
    val typeCode: Int,
    val iterations: Int,
    val memoryMb: Int? = null,
    val parallelism: Int? = null,
) {
    init {
        require(
            typeCode == BitwardenExportKdfType.PBKDF2_SHA256 ||
                typeCode == BitwardenExportKdfType.ARGON2_ID,
        ) {
            "Unsupported export KDF type: $typeCode"
        }
        require(iterations > 0) { "Export KDF iterations must be positive: $iterations" }
    }
}
