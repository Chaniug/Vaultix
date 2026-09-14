/*
 * Vaultix — data:repository
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 导出 / 导入的密码学流程与数据形状由 data:bitwarden 的
 * `export/BitwardenExportFacade`（对齐 bitwarden/sdk-internal → bitwarden-exporters）
 * 承担；本实现只负责「领域仓储编排」：
 *   导出：读条目 / 文件夹（内存明文）→ 交给门面加密 → 返回字符串；
 *   导入：门面解密解析 → 预览 → applyImportedVault 逐条 createItem（增量）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.repository

import io.vaultix.crypto.di.CryptoDispatcher
import io.vaultix.data.bitwarden.export.BitwardenExportFacade
import io.vaultix.data.bitwarden.export.BitwardenImportException
import io.vaultix.data.kdbx.Kdbx
import io.vaultix.domain.FolderRepository
import io.vaultix.domain.ImportedVault
import io.vaultix.domain.ItemRepository
import io.vaultix.domain.VaultExportRepository
import io.vaultix.domain.VaultImportException
import io.vaultix.domain.VaultNotUnlockedException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bitwarden 加密 JSON 导出 / 导入实现。
 *
 * ## 为什么用入口仓储而不是直连 DAO
 * - 条目读取复用 [ItemRepository.observeItems] 的完整解密链路（含 KDBX 分流、损坏
 *   条目跳过），不重写一遍领域模型映射；
 * - 文件夹读取复用 [FolderRepository.observeFolders]（名称解密）。
 *
 * ## 库种类
 * 同时支持 **Bitwarden** 与 **KDBX** 库：两者都经 [ItemRepository.observeItems] /
 * [FolderRepository.observeFolders] 暴露统一的领域模型（KDBX 读路径由仓储内部分流），
 * 故导出 / 导入对库种类无感。唯一前置条件是库**已解锁**（需要内存密钥）。
 *
 * @suppress TooGenericExceptionCaught：见各方法内的精确捕获。
 */
@Singleton
class VaultExportRepositoryImpl @Inject constructor(
    private val itemRepository: ItemRepository,
    private val folderRepository: FolderRepository,
    private val sessions: VaultSessionManager,
    private val facade: BitwardenExportFacade,
    @CryptoDispatcher private val cryptoDispatcher: CoroutineDispatcher,
) : VaultExportRepository {

    override suspend fun exportEncryptedJson(vaultId: String, exportPassword: String): String {
        if (!isUnlocked(vaultId)) throw VaultNotUnlockedException(vaultId)
        return withContext(cryptoDispatcher) {
            // 取当前快照（first() 拿一次已解密列表即可，导出是一次性批处理）。
            val items = itemRepository.observeItems(vaultId).first()
            val folders = folderRepository.observeFolders(vaultId).first()
            facade.export(folders = folders, items = items, exportPassword = exportPassword)
        }
    }

    override fun parseEncryptedJson(fileContent: String, exportPassword: String): ImportedVault =
        try {
            val parsed = facade.parse(fileContent, exportPassword)
            ImportedVault(folders = parsed.folders, items = parsed.items)
        } catch (error: BitwardenImportException) {
            // data 层异常 → domain 分类异常（domain 不引 data 类型）。
            throw when (error) {
                is BitwardenImportException.WrongPassword -> VaultImportException.WrongPassword()
                is BitwardenImportException.UnsupportedKdf -> VaultImportException.UnsupportedKdf()
                else -> VaultImportException.MalformedFile()
            }
        }

    override suspend fun applyImportedVault(vaultId: String, imported: ImportedVault): Int {
        if (!isUnlocked(vaultId)) throw VaultNotUnlockedException(vaultId)

        // 文件夹：按名字匹配目标库已有文件夹（Vaultix 不支持客户端新建文件夹）。
        val folderIdByName = existingFolderIdByName(vaultId)

        var written = 0
        imported.items.forEach { item ->
            val remapped = item.copy(
                // 目标库重新分配 id（createItem 会覆盖，这里显式置空以免误导）。
                id = "",
                // 源端 folderId 是导出库的 UUID；按名字映射到目标库的文件夹 id，找不到则清空。
                folderId = item.folderId?.let { sourceId ->
                    imported.folders.firstOrNull { it.id == sourceId }
                        ?.let { folderIdByName[it.name] }
                },
            )
            val result = itemRepository.createItem(vaultId, remapped)
            if (result.isSuccess) written++
        }
        return written
    }

    /** 目标库「文件夹名 → id」映射（同名取第一个，导出与导入库同名文件夹的常见情形）。 */
    private suspend fun existingFolderIdByName(vaultId: String): Map<String, String> =
        folderRepository.observeFolders(vaultId).first()
            .associate { it.name to it.id }

    /** 解锁判定：内存会话（Bitwarden）或 KDBX 会话任一在场即视为已解锁。 */
    private fun isUnlocked(vaultId: String): Boolean =
        sessions.isUnlocked(vaultId) || Kdbx.isUnlocked(vaultId)
}
