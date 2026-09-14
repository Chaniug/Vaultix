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
 * 解密流程对齐 Bitwarden 官方导入路径：
 *   bitwarden/sdk-internal → crates/bitwarden-exporters/src/encrypted_json.rs（测试段）
 * 官方测试印证了校验方式：用导出密码 + 盐派生 PinKey，解密
 * `encKeyValidation`，**能解析为 UUID 即证明密码正确**。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.bitwarden.export

import io.vaultix.crypto.MacVerificationException
import io.vaultix.crypto.SecureBytes
import io.vaultix.crypto.SymmetricCryptoKey
import io.vaultix.crypto.VaultixCrypto
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bitwarden 加密导出文件的解析结果。
 *
 * @property plain 解密后的明文导出结构。
 * @property kdf 文件内声明的 KDF 参数（导入时按此派生密钥，与当前账号设置无关）。
 */
data class BitwardenImportResult(
    val plain: BitwardenPlainExport,
    val kdf: ExportKdf,
)

/** 导入失败原因（分类明确，UI 据此给出可执行提示，而非笼统「导入失败」）。 */
sealed class BitwardenImportException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {

    /** 文件不是合法 JSON，或不是加密导出信封（缺少必需字段）。 */
    class MalformedFile(cause: Throwable? = null) :
        BitwardenImportException("File is not a valid Bitwarden encrypted export", cause)

    /** 导出密码错误（`encKeyValidation` 解密失败或结果不是 UUID）。 */
    class WrongPassword(cause: Throwable? = null) :
        BitwardenImportException("Incorrect export password", cause)

    /** 文件内声明的 KDF 参数不受支持（如未来新增的 KDF 类型）。 */
    class UnsupportedKdf(typeCode: Int) :
        BitwardenImportException("Unsupported KDF type in export file: $typeCode")

    /** 信封声称加密，但关键字段缺失。 */
    class IncompleteEnvelope(field: String) :
        BitwardenImportException("Encrypted export is missing required field: $field")
}

/**
 * Bitwarden **密码保护**加密导出的导入器。
 *
 * 与 [BitwardenEncryptedExporter] 互为逆过程：解析信封 → 按文件内 KDF 派生密钥 →
 * 校验 `encKeyValidation` → 解密 `data` → 解析明文 JSON。
 *
 * ⚠️ 解密出的明文 JSON 含全部凭据明文，**只在内存中流转**：调用方负责立即消费
 * （映射回领域模型 / 落库），不得写日志、不得持久化（对齐 Docs/09）。
 */
@Singleton
class BitwardenEncryptedImporter @Inject constructor(
    private val crypto: VaultixCrypto,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * 解密并解析加密导出文件。
     *
     * @param fileContent 文件全文（UTF-8 解码后的字符串）。
     * @param exportPassword 用户在导出时设置的密码。
     * @throws BitwardenImportException.MalformedFile 文件格式不合法
     * @throws BitwardenImportException.WrongPassword 密码错误
     * @throws BitwardenImportException.UnsupportedKdf KDF 不受支持
     */
    fun import(fileContent: String, exportPassword: String): BitwardenImportResult {
        val envelope = parseEnvelope(fileContent)
        val kdf = envelope.toKdf()

        val exportKey = deriveExportKey(exportPassword, envelope.salt, kdf)
        try {
            verifyExportKey(envelope.encKeyValidation, exportKey)
            val plainJson = decryptData(envelope.data, exportKey)
            val plain = parsePlain(plainJson)
            return BitwardenImportResult(plain = plain, kdf = kdf)
        } finally {
            exportKey.clear()
        }
    }

    /** 解析加密信封；缺字段 / 非 JSON 一律归类为 [BitwardenImportException.MalformedFile]。 */
    private fun parseEnvelope(content: String): BitwardenEncryptedExport =
        try {
            json.decodeFromString(BitwardenEncryptedExport.serializer(), content).also {
                if (it.salt.isEmpty()) throw BitwardenImportException.IncompleteEnvelope("salt")
                if (it.data.isEmpty()) throw BitwardenImportException.IncompleteEnvelope("data")
                if (it.encKeyValidation.isEmpty()) {
                    throw BitwardenImportException.IncompleteEnvelope("encKeyValidation")
                }
            }
        } catch (error: BitwardenImportException) {
            throw error
        } catch (error: SerializationException) {
            throw BitwardenImportException.MalformedFile(error)
        } catch (error: IllegalArgumentException) {
            // Json 在解析出结构性非法内容时也会抛 IAE（如非法数字字面量）
            throw BitwardenImportException.MalformedFile(error)
        }

    /**
     * 校验导出密码是否正确。
     *
     * 官方做法（见 `encrypted_json.rs` 测试）：解密 `encKeyValidation`，
     * **能解析为合法 UUID** 即说明密钥正确。这里照做——比直接尝试解密 `data`
     * 更早失败、且错误分类更准确（不必等 JSON 解析报错才反推密码错）。
     *
     * 实现上把「解密失败」「结构非法」「解出来不是 UUID」三种失败统一归到
     * [WrongPassword]——它们对用户是同一个可行动结论（密码不对），且原异常一律
     * 作为 cause 带上，便于排障时区分「密码错」与「文件被改坏」。
     */
    private fun verifyExportKey(encKeyValidation: String, exportKey: SymmetricCryptoKey) {
        val failure: Throwable? = runCatching {
            UUID.fromString(crypto.decryptToString(encKeyValidation, exportKey))
        }.exceptionOrNull()

        // 单一出口：任何失败都映射为 WrongPassword（原异常链保留在 cause 里）
        if (failure != null) {
            throw BitwardenImportException.WrongPassword(failure)
        }
    }

    /** 解密 `data` 字段得到明文 JSON 字符串。 */
    private fun decryptData(data: String, exportKey: SymmetricCryptoKey): String =
        try {
            crypto.decryptToString(data, exportKey)
        } catch (error: MacVerificationException) {
            throw BitwardenImportException.WrongPassword(error)
        }

    /** 解析明文 JSON；结构不符归类为 [BitwardenImportException.MalformedFile]。 */
    private fun parsePlain(plainJson: String): BitwardenPlainExport =
        try {
            json.decodeFromString(BitwardenPlainExport.serializer(), plainJson)
        } catch (error: SerializationException) {
            throw BitwardenImportException.MalformedFile(error)
        } catch (error: IllegalArgumentException) {
            throw BitwardenImportException.MalformedFile(error)
        }

    /**
     * 信封声明的 KDF → [ExportKdf]。
     *
     * 与导出侧共用 [ExportKdf] 构造校验；typeCode 越界时抛
     * [BitwardenImportException.UnsupportedKdf]（而非 [ExportKdf] 的 require 崩溃）。
     */
    private fun BitwardenEncryptedExport.toKdf(): ExportKdf {
        if (
            kdfType != BitwardenExportKdfType.PBKDF2_SHA256 &&
            kdfType != BitwardenExportKdfType.ARGON2_ID
        ) {
            throw BitwardenImportException.UnsupportedKdf(kdfType)
        }
        return ExportKdf(
            typeCode = kdfType,
            iterations = kdfIterations,
            memoryMb = kdfMemory,
            parallelism = kdfParallelism,
        )
    }

    /** 由导出密码 + 文件内盐派生对称密钥（流程同导出侧，见 [BitwardenEncryptedExporter]）。 */
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
}
