/*
 * Vaultix — domain
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * **Bitwarden JSON ⇄ KDBX 双向转换**（施工单 Docs/progress/json-kdbx-lossless-conversion.md · W5）。
 *
 * 与 [VaultExportRepository] 的分界：那个管「加密 JSON 进 / 出**已解锁的库**」，
 * 本接口管「加密 JSON 与 **.kdbx 文件**互转」——产物 / 来源都是独立文件字节，
 * 与任何库的解锁状态无关（用户在服务器宕机时的自救路径）。
 *
 * ⚠️ 安全契约（对齐 Docs/09，与 [VaultExportRepository] 同款）：
 *   - 明文只在内存流转，用完即弃；
 *   - 本接口不接收也不返回任何持久化路径 —— 文件读写由 app 层经 SAF 完成；
 *   - **只读输入**：转换失败时源文件一个字节都不会被碰（没有任何写入口）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.domain

import io.vaultix.model.VaultFolder
import io.vaultix.model.VaultItem

/**
 * 格式转换仓储（窄接口：UI 只需要"预览 / 转 KDBX / 转 JSON"三件事）。
 *
 * 实现位于 data:repository（编排 data:bitwarden 的加解密 + data:kdbx 的编码）。
 * 纯 Kotlin、无 Android 依赖，便于注入 fake 单测。
 */
interface VaultConverterRepository {

    /**
     * 解密 Bitwarden 加密 JSON 并给出**预览**（不转换、不落库）。
     *
     * 预览是转换前的**知情环节**：用户在这一步看到"将转换多少条、其中多少条
     * TOTP / 通行密钥、多少条卡片类会降级、多少条未知类型被跳过"——
     * 这些数字背后是 KDBX 格式的真实能力边界，不该在转换完成后才让人发现。
     *
     * ⚠️ 返回的 [JsonImportPreview.items] 是**已解密明文**：调用方（ViewModel）
     *   持有它直到转换完成或用户取消，与导入预览（[VaultExportRepository]）同款纪律。
     *
     * @throws VaultConversionException 分类失败原因（格式非法 / 密码错误 / KDF 不支持）。
     */
    suspend fun previewJsonImport(fileContent: String, filePassword: String): JsonImportPreview

    /**
     * 把 [previewJsonImport] 的结果**平铺**转换成 KDBX 4.1 文件字节。
     *
     * @param outputPassword **输出库的主密码**（用户在转换时指定；与源 JSON 的
     *   文件密码无关，两者在密码学上必须分开）。
     * @param databaseName 新库名称（同时用作根组名）。
     * @throws VaultConversionException 分类失败原因（自检失败 = 软件缺陷，单独一类）。
     */
    suspend fun convertJsonToKdbx(
        preview: JsonImportPreview,
        outputPassword: String,
        databaseName: String,
    ): ConversionOutput

    /**
     * 把 KDBX 文件字节转换成 Bitwarden 加密导出 JSON。
     *
     * @param fileBytes .kdbx 文件原文（密文；由 app 层经 SAF 读入）。
     * @param filePassword 该库的主密码（用它打开，不登记任何会话）。
     * @param exportPassword 产出的加密 JSON 的独立密码。
     * @throws VaultConversionException 分类失败原因（不是 KDBX / 版本不支持 / 密码不对）。
     */
    suspend fun convertKdbxToJson(
        fileBytes: ByteArray,
        filePassword: String,
        exportPassword: String,
    ): ConversionOutput
}

/**
 * JSON 侧的预览（**已解密明文** + 计数）。
 *
 * 与 [ImportedVault] 的差异：多带一份 [ConversionStats]——导入是"进库"（丢没丢
 * 列表里数得出来），转换是"换格式"（降级与跳过必须**转换前**就说清）。
 */
data class JsonImportPreview(
    /** 文件内的文件夹（源端 id 与名字；KDBX 侧按施工单 §2.4 平铺，不映射层级）。 */
    val folders: List<VaultFolder>,
    /** 文件内的条目（已映射为领域模型；`folderId` 仍是源端 id）。 */
    val items: List<VaultItem>,
    /** 各项计数（UI 预览卡直接渲染）。 */
    val stats: ConversionStats,
)

/**
 * 一次转换的产物（**字节** + 计数）。
 *
 * 刻意不带建议文件名：命名是 UI 层的事（与 `ImportExportViewModel.suggestFileName`
 * 同款分工），domain 不掺和展示策略。字节的落盘由 app 层经 SAF 完成。
 */
class ConversionOutput(
    /** 产物字节（.kdbx 文件或加密 JSON 的 UTF-8 字节）。 */
    val bytes: ByteArray,
    /** 本次转换的计数（成功提示里展示，让用户知道产物的构成）。 */
    val stats: ConversionStats,
) {
    // ByteArray 进 data class 的 equals 会比较引用 —— 抄 KdbxRoundTripResult 的做法，
    // 手动按内容语义实现（本类型不进集合，仅为消除警告与防御误用）。
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * 转换计数（预览与产物共用）。
 *
 * | 字段 | 含义 |
 * |---|---|
 * | [itemCount] | 实际进入转换的条目数 |
 * | [totpCount] | 其中带 TOTP 验证码的条目数 |
 * | [passkeyCount] | 其中通行密钥凭证总数（一条登录可挂多个） |
 * | [skippedCount] | 因未知类型被**跳过**的条目数（仅 JSON 侧有意义） |
 * | [typeDegradedCount] | 卡片 / 身份 / SSH 条目数——转 KDBX 后仅保留标题与备注 |
 */
data class ConversionStats(
    val itemCount: Int,
    val totpCount: Int,
    val passkeyCount: Int,
    val skippedCount: Int,
    val typeDegradedCount: Int,
) {
    companion object {
        /** 空计数（空文件 / 空库）。 */
        val EMPTY = ConversionStats(
            itemCount = 0,
            totpCount = 0,
            passkeyCount = 0,
            skippedCount = 0,
            typeDegradedCount = 0,
        )
    }
}

/**
 * 转换失败原因分类（UI 据此给出可执行提示，而非笼统「转换失败」）。
 *
 * 与 data 层异常一一对应；domain 不引 data 类型，故在此重声明（同
 * [VaultImportException] 的分层理由）。
 */
sealed class VaultConversionException(message: String) : Exception(message) {

    /** JSON 不是合法加密导出信封（格式非法 / 缺必需字段）。 */
    class MalformedJson : VaultConversionException("文件不是合法的 Bitwarden 加密导出")

    /** 任一方向的密码错误（JSON 文件密码 / KDBX 主密码）。 */
    class WrongPassword : VaultConversionException("密码不正确")

    /** 所选文件不是 KDBX（选错文件 / 文件损坏）。 */
    class NotKdbxFile : VaultConversionException("所选文件不是 KDBX 格式")

    /** KDBX 版本不受支持（本应用只开 4.x）。 */
    class UnsupportedKdbxVersion(val version: String) :
        VaultConversionException("不支持的 KDBX 版本：$version")

    /** JSON 内声明的 KDF 不受支持。 */
    class UnsupportedKdf : VaultConversionException("文件内声明的 KDF 不受支持")

    /**
     * 编码后的往返自检不通过 —— **这是软件缺陷，不是用户的错**。
     *
     * 单独一类而不并进 [Generic]：用户动作完全不同（前者要反馈 Bug，后者可重试）。
     */
    class WriteCheckFailed(val detail: String) :
        VaultConversionException("转换结果未通过写入自检，已取消：$detail")

    /** 其他（IO、编码异常等）；[detail] 为原始信息。 */
    class Generic(detail: String) :
        VaultConversionException(detail.ifBlank { "转换失败" })
}
