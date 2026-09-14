/*
 * Vaultix — data:bitwarden
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 门面把官方导出 / 导入流程（bitwarden/sdk-internal → crates/bitwarden-exporters）
 * 与 Vaultix 领域模型之间的**双向映射**收敛到一处：导出侧 [VaultItem.toExportCipher]、
 * 导入侧 [BitwardenExportCipher.toVaultItem] 均为本模块 internal 扩展函数，
 * 外部模块（data:repository）看不到，故由本门面统一暴露领域模型进出的窄接口。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.bitwarden.export

import io.vaultix.model.VaultFolder
import io.vaultix.model.VaultItem
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bitwarden 加密导出 / 导入的**领域模型门面**。
 *
 * 本模块内 [BitwardenEncryptedExporter] / [BitwardenEncryptedImporter] 只认导出模型
 * （[BitwardenPlainExport] 等），而 data:repository 只认领域模型（[VaultItem]）。
 * 两者之间的映射是本模块 internal，所以由本门面做「领域模型 → 导出模型 → 加密串」与
 * 反向的收敛，data:repository 无需（也无法）触碰导出模型。
 *
 * 不持有状态、不做 I/O —— 文件读写由 app 层经 SAF 完成（对齐 Docs/09：data 层不碰文件系统）。
 */
@Singleton
class BitwardenExportFacade @Inject constructor(
    private val exporter: BitwardenEncryptedExporter,
    private val importer: BitwardenEncryptedImporter,
) {

    /**
     * 领域条目 + 文件夹 → 加密导出 JSON。
     *
     * @param folders 库内文件夹（导出为 `folders` 数组；官方要求 id 为合法 UUID，
     *   Vaultix 的文件夹 id 来自服务端，形态一致）。
     * @param items 库内条目（已解密明文，仅在内存）。
     * @param exportPassword 本次导出的独立密码。
     */
    fun export(
        folders: List<VaultFolder>,
        items: List<VaultItem>,
        exportPassword: String,
    ): String {
        val plain = BitwardenPlainExport(
            folders = folders.map { BitwardenExportFolder(id = it.id, name = it.name) },
            items = items.map { it.toExportCipher() },
        )
        // 沿用账号 KDF 的默认口径：password-protected 导出的 KDF 只是给导入方的提示，
        // 官方测试与默认账号均为 PBKDF2-SHA256 600,000；导入方**总是**读文件内字段
        // （见 BitwardenEncryptedImporter.toKdf），故此处固定官方默认即完全互操作。
        return exporter.export(plain, exportPassword, DEFAULT_EXPORT_KDF)
    }

    /**
     * 加密导出 JSON → 领域条目 / 文件夹（**不落库**，供预览与后续导入）。
     *
     * @throws BitwardenImportException 分类失败原因（格式非法 / 密码错误 / KDF 不支持）。
     */
    fun parse(fileContent: String, exportPassword: String): ParsedExport {
        val result = importer.import(fileContent, exportPassword)
        val folders = result.plain.folders.map { VaultFolder(id = it.id, name = it.name) }
        // 未知类型条目返回 null（见 toVaultItem KDoc）——宁可丢弃也不猜测映射。
        val items = result.plain.items.mapNotNull { it.toVaultItem() }
        return ParsedExport(folders = folders, items = items)
    }

    private companion object {
        /** 官方默认导出 KDF：PBKDF2-HMAC-SHA256 / 600,000 次（账号默认口径）。 */
        val DEFAULT_EXPORT_KDF = ExportKdf(
            typeCode = BitwardenExportKdfType.PBKDF2_SHA256,
            iterations = 600_000,
        )
    }
}

/** 解析后的导出内容（领域模型形态）。 */
data class ParsedExport(
    val folders: List<VaultFolder>,
    val items: List<VaultItem>,
)
