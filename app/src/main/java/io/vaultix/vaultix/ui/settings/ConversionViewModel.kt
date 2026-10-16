/*
 * Vaultix — app:ui:settings
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 格式转换页 ViewModel（施工单 W5 · JSON ⇄ KDBX）。
 *
 * ## 与 [ImportExportViewModel] 的分工
 *
 * | | ImportExportViewModel | 本类 |
 * |---|---|---|
 * | 干什么 | 本库 ⇄ Bitwarden 加密备份 | Bitwarden 加密 JSON ⇄ .kdbx 文件 |
 * | 碰库解锁态 | 要（导出要读当前库明文） | **不碰**（来源 / 产物都是独立文件字节） |
 * | 失败影响 | — | 源文件只读，一个字节都不会被改 |
 *
 * 合成一个 ViewModel 的两个理由都不成立（状态机不同、先验条件不同），
 * 且 ImportExport 的 UiState 已经不小 —— 独立成类，各自可单测。
 *
 * ## 安全契约（对齐 Docs/09）
 *
 * - 文件内容（明文 JSON / KDBX 字节）由 UI 侧经 SAF 读入后**直接当参数传进来**，
 *   ViewModel 不持有、不进 UiState（同 ImportExportViewModel 的 decryptForPreview 模式）；
 * - 密码字段用完即弃：预览一出清文件密码、转换一成功清输出密码；
 * - 转换全程 `Dispatchers.Default`：600k PBKDF2 / Argon2 是 CPU 密集，
 *   放 Default 避免主界掉帧（repos 层自身不切调度器）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.vaultix.domain.JsonImportPreview
import io.vaultix.domain.VaultConversionException
import io.vaultix.domain.VaultConverterRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * 格式转换页 ViewModel：JSON→KDBX 与 KDBX→JSON 两个方向的状态机。
 *
 * 两个方向**互不共享状态**（除了错误提示），UI 上一次只操作一个方向，
 * 但两条链的输入文件不同、先验条件不同，合成一个会互相污染。
 */
@HiltViewModel
class ConversionViewModel @Inject constructor(
    private val converterRepository: VaultConverterRepository,
) : ViewModel() {

    /** 转换失败分类（映射到本地化文案，UI 不拼字符串）。 */
    enum class ErrorKind {
        MALFORMED_JSON,
        WRONG_PASSWORD,
        NOT_KDBX,
        UNSUPPORTED_KDBX_VERSION,
        UNSUPPORTED_KDF,
        WRITE_CHECK_FAILED,
        GENERIC,
    }

    data class UiState(
        // ---- JSON → KDBX ----
        /** 已选 JSON 文件的显示名（空 = 未选；同时充当"选过了"的标记）。 */
        val jsonFileName: String = "",
        val jsonPassword: String = "",
        val jsonPasswordVisible: Boolean = false,
        /** 解密出的预览（null = 还没预览）；持有的是**已解密明文**，同导入预览纪律。 */
        val jsonPreview: JsonImportPreview? = null,
        val jsonPreviewing: Boolean = false,
        val outputName: String = DEFAULT_DATABASE_NAME,
        val outputPassword: String = "",
        val outputPasswordConfirm: String = "",
        val outputPasswordVisible: Boolean = false,
        val convertingToKdbx: Boolean = false,
        // ---- KDBX → JSON ----
        /** 已选 KDBX 文件的显示名（空 = 未选）。 */
        val kdbxFileName: String = "",
        val kdbxPassword: String = "",
        val kdbxPasswordVisible: Boolean = false,
        val exportPassword: String = "",
        val exportPasswordConfirm: String = "",
        val exportPasswordVisible: Boolean = false,
        val convertingToJson: Boolean = false,
        // ---- 共用 ----
        val error: ErrorKind? = null,
        val errorDetail: String? = null,
    ) {
        /** 预览按钮可用：选了 JSON + 填了密码 + 未预览过 + 非忙碌。 */
        val canPreviewJson: Boolean
            get() = !jsonPreviewing && !convertingToKdbx &&
                jsonFileName.isNotEmpty() && jsonPassword.isNotEmpty() && jsonPreview == null

        /** 转 KDBX 按钮可用：已预览 + 库名非空 + 两次输出密码一致且达长度下限。 */
        val canConvertToKdbx: Boolean
            get() = !convertingToKdbx && jsonPreview != null &&
                outputName.isNotBlank() &&
                outputPassword.length >= MIN_PASSWORD_LENGTH &&
                outputPassword == outputPasswordConfirm

        /** 转 JSON 按钮可用：选了 KDBX + 填了库密码 + 两次导出密码一致且达长度下限。 */
        val canConvertToJson: Boolean
            get() = !convertingToJson && kdbxFileName.isNotEmpty() && kdbxPassword.isNotEmpty() &&
                exportPassword.length >= MIN_PASSWORD_LENGTH &&
                exportPassword == exportPasswordConfirm

        companion object {
            /** 文件密码最短长度（与导入 / 导出同一口径：对齐官方「弱密码」判定下限）。 */
            const val MIN_PASSWORD_LENGTH = 8

            /** 新库默认名（同时用作根组名；用户可改，空值不让过 —— 见 [canConvertToKdbx]）。 */
            const val DEFAULT_DATABASE_NAME = "Vaultix"
        }
    }

    sealed interface Event {
        /**
         * 请求把 KDBX 产物写入用户选定的位置（UI 侧走 SAF 保存）。
         *
         * 不用 data class：ByteArray 进 data class 的 equals 会比较引用，
         * 事件又恰好只该被消费一次（抄 ConversionOutput 的手动 equals 纪律太隆重，
         * 直接不做 data class 更直白）。
         *
         * [itemCount] 供保存成功后的 Snackbar 用（"已生成 KDBX 文件（N 个条目）"）——
         * 放在事件里而不是等 UI 回执再问，是因为保存结果纯属 UI 层事务，
         * ViewModel 不为它多留一个入口。
         */
        class SaveKdbxFile(
            val fileName: String,
            val bytes: ByteArray,
            val itemCount: Int,
        ) : Event

        /** 请求把加密 JSON 产物写入用户选定的位置（[itemCount] 同 [SaveKdbxFile]）。 */
        data class SaveJsonFile(
            val fileName: String,
            val content: String,
            val itemCount: Int,
        ) : Event
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    // ---- JSON → KDBX：选文件 → 预览 → 设输出密码 → 转换 ----

    /** 记录选中的 JSON 文件（内容由 UI 读好后直接当参数传给 [previewJsonImport]）。 */
    fun onJsonFilePicked(fileName: String) {
        _state.update {
            it.copy(
                jsonFileName = fileName,
                // 换文件即清掉旧预览 / 旧密码：拿 A 文件的密码解 B 文件是最容易犯的错。
                jsonPreview = null,
                jsonPassword = "",
                error = null,
                errorDetail = null,
            )
        }
    }

    fun onJsonPasswordChange(value: String) =
        _state.update { it.copy(jsonPassword = value, error = null, errorDetail = null) }

    fun onJsonPasswordVisibleChange(visible: Boolean) =
        _state.update { it.copy(jsonPasswordVisible = visible) }

    /**
     * 解密 JSON 并给出预览（不转换、不落库）。
     *
     * @param fileContent UI 侧读好的文件全文（密文信封）。
     */
    fun previewJsonImport(fileContent: String) {
        val current = _state.value
        if (!current.canPreviewJson) return

        _state.update { it.copy(jsonPreviewing = true, error = null, errorDetail = null) }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    converterRepository.previewJsonImport(fileContent, current.jsonPassword)
                }
            }
            result.fold(
                onSuccess = { preview ->
                    _state.update {
                        it.copy(
                            jsonPreviewing = false,
                            jsonPreview = preview,
                            // 预览已出，文件密码用完即弃（后续转换用不到它）。
                            jsonPassword = "",
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            jsonPreviewing = false,
                            jsonPreview = null,
                            error = error.toErrorKind(),
                            errorDetail = error.message,
                        )
                    }
                },
            )
        }
    }

    fun onOutputNameChange(value: String) =
        _state.update { it.copy(outputName = value, error = null, errorDetail = null) }

    fun onOutputPasswordChange(value: String) =
        _state.update { it.copy(outputPassword = value, error = null, errorDetail = null) }

    fun onOutputPasswordConfirmChange(value: String) =
        _state.update { it.copy(outputPasswordConfirm = value, error = null, errorDetail = null) }

    fun onOutputPasswordVisibleChange(visible: Boolean) =
        _state.update { it.copy(outputPasswordVisible = visible) }

    /**
     * 执行 JSON → KDBX 转换，产物经 [Event.SaveKdbxFile] 交 UI 走 SAF 保存。
     *
     * 输出密码**用完即弃**（成功派发后立刻清空三个字段）。
     */
    fun convertJsonToKdbx() {
        val current = _state.value
        val preview = current.jsonPreview
        if (!current.canConvertToKdbx || preview == null) return

        _state.update { it.copy(convertingToKdbx = true, error = null, errorDetail = null) }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    converterRepository.convertJsonToKdbx(
                        preview = preview,
                        outputPassword = current.outputPassword,
                        databaseName = current.outputName,
                    )
                }
            }
            result.fold(
                onSuccess = { output ->
                    _state.update {
                        it.copy(
                            convertingToKdbx = false,
                            outputPassword = "",
                            outputPasswordConfirm = "",
                        )
                    }
                    _events.send(
                        Event.SaveKdbxFile(
                            fileName = suggestKdbxFileName(),
                            bytes = output.bytes,
                            itemCount = output.stats.itemCount,
                        ),
                    )
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            convertingToKdbx = false,
                            error = error.toErrorKind(),
                            errorDetail = error.message,
                        )
                    }
                },
            )
        }
    }

    // ---- KDBX → JSON：选文件 → 输两套密码 → 转换 ----

    /** 记录选中的 KDBX 文件（内容由 UI 读好后直接当参数传给 [convertKdbxToJson]）。 */
    fun onKdbxFilePicked(fileName: String) {
        _state.update {
            it.copy(
                kdbxFileName = fileName,
                kdbxPassword = "",
                error = null,
                errorDetail = null,
            )
        }
    }

    fun onKdbxPasswordChange(value: String) =
        _state.update { it.copy(kdbxPassword = value, error = null, errorDetail = null) }

    fun onKdbxPasswordVisibleChange(visible: Boolean) =
        _state.update { it.copy(kdbxPasswordVisible = visible) }

    fun onExportPasswordChange(value: String) =
        _state.update { it.copy(exportPassword = value, error = null, errorDetail = null) }

    fun onExportPasswordConfirmChange(value: String) =
        _state.update { it.copy(exportPasswordConfirm = value, error = null, errorDetail = null) }

    fun onExportPasswordVisibleChange(visible: Boolean) =
        _state.update { it.copy(exportPasswordVisible = visible) }

    /**
     * 执行 KDBX → JSON 转换，产物经 [Event.SaveJsonFile] 交 UI 走 SAF 保存。
     *
     * 两套密码（库密码 / 导出密码）用完即弃。
     *
     * @param fileBytes UI 侧读好的 .kdbx 原文（密文）。
     */
    fun convertKdbxToJson(fileBytes: ByteArray) {
        val current = _state.value
        if (!current.canConvertToJson) return

        _state.update { it.copy(convertingToJson = true, error = null, errorDetail = null) }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    converterRepository.convertKdbxToJson(
                        fileBytes = fileBytes,
                        filePassword = current.kdbxPassword,
                        exportPassword = current.exportPassword,
                    )
                }
            }
            result.fold(
                onSuccess = { output ->
                    _state.update {
                        it.copy(
                            convertingToJson = false,
                            kdbxPassword = "",
                            exportPassword = "",
                            exportPasswordConfirm = "",
                        )
                    }
                    _events.send(
                        Event.SaveJsonFile(
                            fileName = suggestJsonFileName(),
                            content = output.bytes.toString(Charsets.UTF_8),
                            itemCount = output.stats.itemCount,
                        ),
                    )
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            convertingToJson = false,
                            error = error.toErrorKind(),
                            errorDetail = error.message,
                        )
                    }
                },
            )
        }
    }

    /**
     * 建议的 KDBX 文件名。
     *
     * ⚠️ 后缀**自己带**：`.kdbx` 没有注册 MIME（见 ConversionScreen 的 KDBX_MIME），
     * SAF 的 CreateDocument 不会替 `application/octet-stream` 补任何扩展名 ——
     * 不带后缀的产物落进用户文件夹， KeePass 客户端认不出、用户也认不出。
     */
    private fun suggestKdbxFileName(): String =
        "vaultix-converted-${System.currentTimeMillis()}.kdbx"

    /** 建议的加密 JSON 文件名（与导出一致的前缀，便于用户在文件管理器里认亲）。 */
    private fun suggestJsonFileName(): String =
        "vaultix-export-${System.currentTimeMillis()}.json"

    /** 领域异常 → 可本地化的错误分类。 */
    private fun Throwable.toErrorKind(): ErrorKind = when (this) {
        is VaultConversionException.MalformedJson -> ErrorKind.MALFORMED_JSON
        is VaultConversionException.WrongPassword -> ErrorKind.WRONG_PASSWORD
        is VaultConversionException.NotKdbxFile -> ErrorKind.NOT_KDBX
        is VaultConversionException.UnsupportedKdbxVersion -> ErrorKind.UNSUPPORTED_KDBX_VERSION
        is VaultConversionException.UnsupportedKdf -> ErrorKind.UNSUPPORTED_KDF
        is VaultConversionException.WriteCheckFailed -> ErrorKind.WRITE_CHECK_FAILED
        is VaultConversionException.Generic -> ErrorKind.GENERIC
        else -> ErrorKind.GENERIC
    }
}
