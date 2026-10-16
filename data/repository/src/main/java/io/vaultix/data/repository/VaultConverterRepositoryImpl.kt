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
 * JSON 侧的加解密形状由 data:bitwarden 的 `export/BitwardenExportFacade`
 * （对齐 bitwarden/sdk-internal → bitwarden-exporters）承担；KDBX 侧的编码 /
 * 解码由 data:kdbx 的 [Kdbx] 门面（kotpass）承担。本实现只做**编排**：
 *   正向：BW JSON →（facade.parse）领域模型 →（Kdbx.buildFromItems）kdbx 字节；
 *   反向：kdbx 字节 →（Kdbx.readForConversion）领域模型 →（facade.export）加密 JSON。
 * 落盘 / 读盘一律在 app 层经 SAF 完成，本类不碰文件系统（对齐 Docs/09）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.repository

import io.vaultix.crypto.di.CryptoDispatcher
import io.vaultix.data.bitwarden.export.BitwardenExportFacade
import io.vaultix.data.bitwarden.export.BitwardenImportException
import io.vaultix.data.kdbx.Kdbx
import io.vaultix.data.kdbx.KdbxFailure
import io.vaultix.data.kdbx.KdbxOpenError
import io.vaultix.data.kdbx.KdbxWriteFailure
import io.vaultix.domain.ConversionOutput
import io.vaultix.domain.ConversionStats
import io.vaultix.domain.JsonImportPreview
import io.vaultix.domain.VaultConverterRepository
import io.vaultix.domain.VaultConversionException
import io.vaultix.model.VaultFolder
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 格式转换编排（W5 · JSON⇄KDBX）。
 *
 * ## 两个方向的非对称（刻意如此，别"顺手抹平"）
 *
 * | 方向 | 文件夹 | id | 类型 |
 * |---|---|---|---|
 * | JSON → KDBX | **平铺丢弃**（施工单 §2.4 用户拍板：先不映射层级） | 存 `VPX_BW_ID` 旁路 | 存 `VPX_BW_TYPE` 旁路 |
 * | KDBX → JSON | 分组 → folders（id 重映射为 UUID） | 取 `VPX_BW_ID`，无则裸 uuid | 取 `VPX_BW_TYPE`（带载荷守卫） |
 *
 * 正向"丢文件夹"是有据可查的拍板（见 `KdbxToolFields` 文件头与施工单 §2.4），
 * 预览计数会告知；反向保留分组是因为 KDBX 本来就有分组、BW JSON 也有 folders，
 * 一一对应不需要猜。
 */
@Singleton
class VaultConverterRepositoryImpl @Inject constructor(
    private val facade: BitwardenExportFacade,
    @CryptoDispatcher private val cryptoDispatcher: CoroutineDispatcher,
) : VaultConverterRepository {

    override suspend fun previewJsonImport(
        fileContent: String,
        filePassword: String,
    ): JsonImportPreview = withContext(cryptoDispatcher) {
        val parsed = try {
            facade.parse(fileContent, filePassword)
        } catch (error: BitwardenImportException) {
            throw error.toConversionException()
        }
        JsonImportPreview(
            folders = parsed.folders,
            items = parsed.items,
            stats = statsOf(parsed.items, skippedCount = parsed.skippedCount),
        )
    }

    override suspend fun convertJsonToKdbx(
        preview: JsonImportPreview,
        outputPassword: String,
        databaseName: String,
    ): ConversionOutput = withContext(cryptoDispatcher) {
        // 注意：这里**不再解析 JSON** —— 解密发生在 preview 阶段，文件密码那时已弃。
        // 重新解析就要把文件密码再留到转换，违背"用完即弃"（Docs/09）。
        val bytes = Kdbx.buildFromItems(name = databaseName, password = outputPassword, items = preview.items)
            .getOrElse { error -> throw error.toConversionException() }
        ConversionOutput(bytes = bytes, stats = statsOf(preview.items))
    }

    override suspend fun convertKdbxToJson(
        fileBytes: ByteArray,
        filePassword: String,
        exportPassword: String,
    ): ConversionOutput = withContext(cryptoDispatcher) {
        val content = Kdbx.readForConversion(fileBytes, filePassword)
            .getOrElse { error -> throw error.toConversionException() }
        // ★ id 重映射（不是可选优化）：KDBX 侧的 folder id 是 `kdbx-group:<uuid>`、
        //   item id 是 `kdbx-entry:<uuid>`（KdbxMappedContent 的前缀约定），而
        //   Bitwarden 官方导入器要求 id 是**合法 UUID 形态**（BitwardenExportFolder.id
        //   的注释：非 UUID 在反序列化阶段直接报错）⇒ 不过这一关，产出的 JSON
        //   官方客户端根本读不进去。条目侧 id 已由 KdbxConversionEngine 还原成
        //   裸 UUID / VPX_BW_ID，这里只处理文件夹。
        val (folders, folderIdRemap) = remapFolderIds(content.folders)
        val items = content.items.map { item ->
            item.copy(folderId = item.folderId?.let { folderIdRemap[it] ?: it })
        }
        val json = facade.export(folders = folders, items = items, exportPassword = exportPassword)
        ConversionOutput(bytes = json.toByteArray(Charsets.UTF_8), stats = statsOf(items))
    }

    // ------------------------------------------------------------------ 计数

    /**
     * 转换计数（预览与产物共用一套口径）。
     *
     * ⚠️ `typeDegradedCount` 的口径是**卡片 / 身份 / SSH 三类**：它们的载荷在
     * KDBX 侧没有承载（`KdbxItemWriter` 不写、读路径不产出），转过去只剩标题与
     * 备注。这是领域模型 ↔ KDBX 的既有能力边界（Vaultix 的 KDBX 库本来只有
     * Login / SecureNote 两种形态），计数是为了让它在转换**前**可见。
     */
    private fun statsOf(items: List<VaultItem>, skippedCount: Int = 0): ConversionStats =
        ConversionStats(
            itemCount = items.size,
            totpCount = items.count { !it.totp.isNullOrBlank() },
            passkeyCount = items.sumOf { it.fido2Credentials.size },
            skippedCount = skippedCount,
            typeDegradedCount = items.count {
                it.type == VaultItemType.Card || it.type == VaultItemType.Identity || it.type == VaultItemType.SshKey
            },
        )

    // ------------------------------------------------------------------ id 重映射

    /**
     * `kdbx-group:<uuid>` → 全新 UUID（BW JSON 的合法 id 形态）。
     *
     * @return 重映射后的文件夹列表 + 「旧 id → 新 id」对照（条目侧要用）。
     *
     * ⚠️ `internal` 而不是 private：这是"产出合法性"的关键一跳（BW 官方导入器
     *    对非 UUID id 直接报错），值得被单测直接钉住。
     */
    internal fun remapFolderIds(folders: List<VaultFolder>): Pair<List<VaultFolder>, Map<String, String>> {
        val remap = folders.associate { it.id to UUID.randomUUID().toString() }
        return folders.map { it.copy(id = remap.getValue(it.id)) } to remap
    }

    // ------------------------------------------------------------------ 异常翻译

    /**
     * data 层异常 → [VaultConversionException]（domain 不引 data 类型，同
     * `VaultExportRepositoryImpl.parseEncryptedJson` 的分层理由）。
     *
     * ⚠️ 两类"密码错"必须合成一个 [VaultConversionException.WrongPassword]：
     *   JSON 文件密码与 KDBX 主密码在 UI 上是两个不同的输入框，但失败时用户
     *   看到的应该是"这个密码不对"并指向**当前正在填的那个框** —— 由 UI 层
     *   根据所在分区给文案，域层只负责归类。
     */
    private fun Throwable.toConversionException(): VaultConversionException = when (this) {
        is VaultConversionException -> this
        is BitwardenImportException -> when (this) {
            is BitwardenImportException.WrongPassword -> VaultConversionException.WrongPassword()
            is BitwardenImportException.UnsupportedKdf -> VaultConversionException.UnsupportedKdf()
            else -> VaultConversionException.MalformedJson()
        }
        is KdbxWriteFailure.RoundTripFailed -> VaultConversionException.WriteCheckFailed(
            result.mismatches.joinToString("；"),
        )
        is KdbxFailure -> fromKdbxFailure()
        else -> VaultConversionException.Generic(message.orEmpty())
    }

    /**
     * [KdbxFailure] 的分支翻译（从 [toConversionException] 拆出：合在一起的圈复杂度
     * 超了 detekt 阈值 14，而两组分支各自都是完整语义单元，拆开更可读）。
     */
    private fun KdbxFailure.fromKdbxFailure(): VaultConversionException =
        when (val error = this.error) {
            is KdbxOpenError.NotKdbxFile -> VaultConversionException.NotKdbxFile()
            is KdbxOpenError.UnsupportedVersion -> VaultConversionException.UnsupportedKdbxVersion(error.version)
            is KdbxOpenError.InvalidCredentials -> VaultConversionException.WrongPassword()
            is KdbxOpenError.Unknown -> VaultConversionException.Generic(error.detail)
            is KdbxOpenError.SourceUnavailable -> VaultConversionException.Generic(error.detail)
            // NotUnlocked / RemoteCredentialsMismatch：转换路径没有"已解锁库"概念，
            // 真出现即编程错误，连同 message 一起归入 Generic 便于定位。
            else -> VaultConversionException.Generic(this.message.orEmpty())
        }
}
