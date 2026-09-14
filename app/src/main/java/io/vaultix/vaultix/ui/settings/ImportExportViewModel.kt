/*
 * Vaultix — app:ui:settings
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 信息架构对齐 Bitwarden 官方 Android 客户端（GPL-3.0，Copyright Bitwarden Inc.）的
 * `ui/platform/feature/settings/exportvault/ExportVaultViewModel.kt`：
 *   - JSON_ENCRYPTED 导出要求「文件密码 + 确认文件密码」两项一致；
 *   - 导出前先弹确认框，导出完成后把文件写到你选择的位置（SAF 保存）。
 * 导入侧对齐官方 `importvault`（选文件 → 文件密码 → 校验 → 导入）的交互骨架。
 *
 * ⚠️ 与官方的一处**有意偏差**：官方在导出前还要用主密码调服务端
 * `validatePassword`（账号态校验）。Vaultix 的目标场景恰是「服务器坏了要自救」，
 * 若导出依赖联网校验就本末倒置 —— 且 password-protected 导出的安全性完全由
 * **文件密码**承担（主密码校验只是防误操作，非密码学必需）。
 * 故本实现以「输入文件密码 + 二次确认 + 导出确认框」替代主密码联网校验，
 * 保证离线可用。KDF 沿用官方默认（PBKDF2-SHA256 / 600,000），与官方客户端互操作。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.vaultix.domain.ImportedVault
import io.vaultix.domain.VaultExportRepository
import io.vaultix.domain.VaultImportException
import io.vaultix.vaultix.session.ActiveVaultStore
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
 * 导入 / 导出页面 ViewModel（**一个 ViewModel 同时驱动导出与导入两条链路**）。
 *
 * 合一的理由：两条链路共享同一套「文件密码输入 / 忙碌态 / 错误提示 / 结果 Snackbar」
 * 骨架，拆成两个 ViewModel 会把这份骨架复制一遍；而它们的状态互斥
 * （页面上一次只做一件事），用一个 UiState 承载不会产生歧义。
 */
@HiltViewModel
class ImportExportViewModel @Inject constructor(
    private val exportRepository: VaultExportRepository,
    private val activeVaultStore: ActiveVaultStore,
) : ViewModel() {

    /** 导入 / 导出共用的错误分类（映射到本地化文案，UI 不拼字符串）。 */
    enum class ErrorKind { MALFORMED, WRONG_PASSWORD, UNSUPPORTED_KDF, GENERIC, VAULT_LOCKED }

    data class UiState(
        // ---- 导出 ----
        val exportPassword: String = "",
        val exportPasswordConfirm: String = "",
        val exportPasswordVisible: Boolean = false,
        val exporting: Boolean = false,
        // ---- 导入 ----
        val importUri: String? = null,
        val importFileName: String = "",
        val importPassword: String = "",
        val importPasswordVisible: Boolean = false,
        /** 已解密并映射、等待用户确认落库的预览内容（null = 还没解密）。 */
        val importPreview: ImportedVault? = null,
        val importing: Boolean = false,
        val decoding: Boolean = false,
        // ---- 共用 ----
        val error: ErrorKind? = null,
        val errorDetail: String? = null,
    ) {
        /** 导出按钮可用：两次密码一致、非空且不短于下限、非忙碌。 */
        val canExport: Boolean
            get() = !exporting &&
                exportPassword.length >= MIN_PASSWORD_LENGTH &&
                exportPassword == exportPasswordConfirm

        /** 导入按钮可用：选了文件 + 填了密码 + 非忙碌。 */
        val canDecrypt: Boolean
            get() = !decoding && !importUri.isNullOrBlank() && importPassword.isNotEmpty()

        /** 确认导入按钮可用：已解密出预览 + 非忙碌。 */
        val canApply: Boolean get() = !importing && importPreview != null

        companion object {
            /** 文件密码最短长度（对齐官方「弱密码」判定的下限口径）。 */
            const val MIN_PASSWORD_LENGTH = 8
        }
    }

    sealed interface Event {
        /** 请求创建导出文件（UI 侧拿到该字符串后走 SAF 保存；字符串即文件内容）。 */
        data class SaveExportFile(val fileName: String, val content: String) : Event

        /** 导出成功（写盘完成后由 UI 回执，这里只用于提示文案）。 */
        data object ExportDone : Event

        /** 导入成功，携带实际写入条目数。 */
        data class ImportDone(val count: Int) : Event
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    // ---- 导出 ----

    fun onExportPasswordChange(value: String) =
        _state.update { it.copy(exportPassword = value, error = null, errorDetail = null) }

    fun onExportPasswordConfirmChange(value: String) =
        _state.update { it.copy(exportPasswordConfirm = value, error = null, errorDetail = null) }

    fun onExportPasswordVisibleChange(visible: Boolean) =
        _state.update { it.copy(exportPasswordVisible = visible) }

    /**
     * 执行导出：读全库明文 → 加密 → 交给 UI 走 SAF 保存。
     *
     * 密码**用完即弃**：产出的 Event 里只带文件内容，UiState 里的两个密码字段
     * 在成功派发后立刻清空（对齐 Docs/09：明文 / 密码不在内存里久留）。
     */
    fun export() {
        val current = _state.value
        if (!current.canExport) return
        val vaultId = activeVaultStore.current()
        if (vaultId == null) {
            _state.update { it.copy(error = ErrorKind.VAULT_LOCKED) }
            return
        }

        _state.update { it.copy(exporting = true, error = null, errorDetail = null) }
        viewModelScope.launch {
            val result = runCatching {
                exportRepository.exportEncryptedJson(vaultId, current.exportPassword)
            }
            result.fold(
                onSuccess = { content ->
                    _state.update {
                        it.copy(
                            exporting = false,
                            exportPassword = "",
                            exportPasswordConfirm = "",
                        )
                    }
                    _events.send(Event.SaveExportFile(fileName = suggestFileName(), content = content))
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            exporting = false,
                            error = error.toErrorKind(),
                            errorDetail = error.message,
                        )
                    }
                },
            )
        }
    }

    /** SAF 写盘完成后的回执（Snackbar 提示由 UI 收集该 Event 触发）。 */
    fun onExportFileSaved() {
        viewModelScope.launch { _events.send(Event.ExportDone) }
    }

    // ---- 导入 ----

    fun onImportFilePicked(context: Context, uri: Uri?) {
        if (uri == null) return
        _state.update {
            it.copy(
                importUri = uri.toString(),
                importFileName = displayNameOf(context, uri) ?: uri.lastPathSegment.orEmpty(),
                // 换文件即清掉旧预览 / 旧密码，避免用 A 文件的密码解 B 文件。
                importPreview = null,
                importPassword = "",
                error = null,
                errorDetail = null,
            )
        }
    }

    fun onImportPasswordChange(value: String) =
        _state.update { it.copy(importPassword = value, error = null, errorDetail = null) }

    fun onImportPasswordVisibleChange(visible: Boolean) =
        _state.update { it.copy(importPasswordVisible = visible) }

    /** 解密文件并解密出预览（不落库）。 */
    fun decryptForPreview(context: Context, content: String) {
        val current = _state.value
        if (!current.canDecrypt) return

        _state.update { it.copy(decoding = true, error = null, errorDetail = null) }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    exportRepository.parseEncryptedJson(content, current.importPassword)
                }
            }
            result.fold(
                onSuccess = { imported ->
                    _state.update {
                        it.copy(
                            decoding = false,
                            importPreview = imported,
                            // 预览已出，密码用完即弃（后续 apply 不再需要它）。
                            importPassword = "",
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            decoding = false,
                            importPreview = null,
                            error = error.toErrorKind(),
                            errorDetail = error.message,
                        )
                    }
                },
            )
        }
    }

    /** 把预览内容逐条写入当前库（增量，不覆盖已有条目）。 */
    fun applyImport() {
        val current = _state.value
        val preview = current.importPreview ?: return
        if (!current.canApply) return
        val vaultId = activeVaultStore.current()
        if (vaultId == null) {
            _state.update { it.copy(error = ErrorKind.VAULT_LOCKED) }
            return
        }

        _state.update { it.copy(importing = true, error = null, errorDetail = null) }
        viewModelScope.launch {
            val result = runCatching { exportRepository.applyImportedVault(vaultId, preview) }
            result.fold(
                onSuccess = { count ->
                    _state.update {
                        it.copy(importing = false, importPreview = null, importUri = null, importFileName = "")
                    }
                    _events.send(Event.ImportDone(count))
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            importing = false,
                            error = error.toErrorKind(),
                            errorDetail = error.message,
                        )
                    }
                },
            )
        }
    }

    /** 取消预览（回到「选文件 + 输密码」态）。 */
    fun clearPreview() = _state.update { it.copy(importPreview = null, error = null, errorDetail = null) }

    /** 建议文件名（不含扩展名，扩展名由 SAF 的 MIME 推导）。 */
    private fun suggestFileName(): String =
        "vaultix-export-${System.currentTimeMillis()}"

    /** SAF 的显示名（选不到时退回 URI 末段）。 */
    private fun displayNameOf(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index < 0 || !cursor.moveToFirst()) return@use null
            cursor.getString(index)
        }
    }.getOrNull()

    /** 领域异常 → 可本地化的错误分类。 */
    private fun Throwable.toErrorKind(): ErrorKind = when (this) {
        is VaultImportException.WrongPassword -> ErrorKind.WRONG_PASSWORD
        is VaultImportException.UnsupportedKdf -> ErrorKind.UNSUPPORTED_KDF
        is VaultImportException.MalformedFile -> ErrorKind.MALFORMED
        is io.vaultix.domain.VaultNotUnlockedException -> ErrorKind.VAULT_LOCKED
        else -> ErrorKind.GENERIC
    }
}
