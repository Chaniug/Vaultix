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
import io.vaultix.database.dao.VaultDao
import io.vaultix.domain.FolderRepository
import io.vaultix.domain.ImportDuplicatePolicy
import io.vaultix.domain.ImportPlan
import io.vaultix.domain.ImportResult
import io.vaultix.domain.ImportedVault
import io.vaultix.domain.ItemRepository
import io.vaultix.domain.VaultExportRepository
import io.vaultix.domain.VaultImportException
import io.vaultix.domain.VaultNotUnlockedException
import io.vaultix.model.VaultFolder
import io.vaultix.model.VaultKind
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
    private val vaultDao: VaultDao,
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

    override suspend fun applyImportedVault(vaultId: String, imported: ImportedVault): ImportResult {
        if (!isUnlocked(vaultId)) throw VaultNotUnlockedException(vaultId)

        // 以**写入这一刻**的库状态为准（预览只是预告，不能当凭据）。
        val existing = itemRepository.observeItems(vaultId).first()
        val duplicatePlan = ImportDuplicatePolicy.plan(imported.items, existing)
        val supportsAllTypes = targetSupportsAllTypes(vaultId)

        // 先建文件夹：任何失败都在写条目之前中止（条目还一条没动）。
        val folderIdMap = ensureFolders(vaultId, imported.folders)

        var written = 0
        var unsupported = 0
        var failed = 0
        var firstFailure: Throwable? = null
        duplicatePlan.newItems.forEach { item ->
            if (!supportsAllTypes && item.type !in KDBX_SUPPORTED_TYPES) {
                unsupported++
                return@forEach
            }
            val remapped = item.copy(
                // 目标库重新分配 id（createItem 会覆盖，这里显式置空以免误导）。
                id = "",
                // 源端 folderId 是导出库的 id：按**id 映射**到目标库，找不到则清空。
                folderId = item.folderId?.let { sourceId -> folderIdMap[sourceId] },
            )
            val result = itemRepository.createItem(vaultId, remapped)
            if (result.isSuccess) {
                written++
            } else {
                failed++
                firstFailure = firstFailure ?: result.exceptionOrNull()
            }
        }
        // 一条都没写进去且确实有失败 ⇒ 抛出去让 UI 报错，而不是"0 条成功"的假结果。
        if (written == 0 && failed > 0) firstFailure?.let { throw it }

        return ImportResult(
            written = written,
            duplicateExisting = duplicatePlan.existingDuplicates.size,
            duplicateInFile = duplicatePlan.inFileDuplicates.size,
            unsupported = unsupported,
            failed = failed,
        )
    }

    override suspend fun previewImport(vaultId: String, imported: ImportedVault): ImportPlan {
        if (!isUnlocked(vaultId)) throw VaultNotUnlockedException(vaultId)

        // 只看"当前可浏览的条目"：回收站里的条目按设计就是用户不要了，
        // 拿它来挡导入会让用户怎么导都导不进去。
        val existing = itemRepository.observeItems(vaultId).first()
        val plan = ImportDuplicatePolicy.plan(imported.items, existing)
        val supportsAllTypes = targetSupportsAllTypes(vaultId)
        val unsupported = if (supportsAllTypes) {
            0
        } else {
            plan.newItems.count { it.type !in KDBX_SUPPORTED_TYPES }
        }
        val existingFolderNames = folderRepository.observeFolders(vaultId).first()
            .map { it.name }
            .toSet()
        val newFolderCount = imported.folders
            .map { it.name }
            .filter { it.isNotBlank() }
            .distinct()
            .count { it !in existingFolderNames }
        return ImportPlan(
            newItemCount = plan.newItems.size - unsupported,
            duplicateCount = plan.existingDuplicates.size,
            // 去重后取前几个：同一标题撞多条时不必重复显示同一个名字。
            duplicateTitles = plan.existingDuplicates
                .map { it.existingTitle }
                .distinct()
                .take(ImportPlan.IMPORT_CONFLICT_SAMPLE),
            duplicateInFileCount = plan.inFileDuplicates.size,
            unsupportedCount = unsupported,
            newFolderCount = newFolderCount,
        )
    }

    /**
     * 确保 [folders] 在目标库里都有对应行，返回「源端 id → 目标 id」。
     *
     * - 先按**名字**复用目标库已有文件夹（同名的取第一个，`putIfAbsent`）；
     * - 缺失的逐个新建，新建成功后立刻写进名字表 —— 同一份文件里两个同名文件夹
     *   会复用刚建的那一个，而不是建两遍（与预览的 `distinct()` 口径一致）；
     * - 任何一次新建失败都**直接抛出**：此时条目还一条没写，重试即可；
     *   绝不能把条目静默倒进「无文件夹」（那是不可逆的组织信息丢失）。
     */
    private suspend fun ensureFolders(
        vaultId: String,
        folders: List<VaultFolder>,
    ): Map<String, String> {
        if (folders.isEmpty()) return emptyMap()
        val nameToId = LinkedHashMap<String, String>()
        folderRepository.observeFolders(vaultId).first()
            .forEach { folder -> nameToId.putIfAbsent(folder.name, folder.id) }
        val sourceToTarget = HashMap<String, String>(folders.size)
        folders.forEach { folder ->
            val name = folder.name
            if (name.isBlank()) return@forEach
            val targetId = nameToId[name] ?: folderRepository.createFolder(vaultId, name)
                .getOrElse { throw it }
                .also { created -> nameToId[name] = created.id }
                .id
            sourceToTarget[folder.id] = targetId
        }
        return sourceToTarget
    }

    /** 目标库是否支持全部 5 种条目类型（KDBX 只收 Login / SecureNote）。 */
    private suspend fun targetSupportsAllTypes(vaultId: String): Boolean =
        VaultKind.fromName(vaultDao.get(vaultId)?.kind) != VaultKind.KDBX

    /** 解锁判定：内存会话（Bitwarden）或 KDBX 会话任一在场即视为已解锁。 */
    private fun isUnlocked(vaultId: String): Boolean =
        sessions.isUnlocked(vaultId) || Kdbx.isUnlocked(vaultId)
}
