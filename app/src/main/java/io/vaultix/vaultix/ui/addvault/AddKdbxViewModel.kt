/*
 * Vaultix — app:ui:addvault
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * 添加本地 KDBX（KeePass）库（M2 阶段 A：只读）。
 */
package io.vaultix.vaultix.ui.addvault

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.vaultix.domain.UnlockResult
import io.vaultix.domain.VaultRepository
import io.vaultix.vaultix.ui.error.UnlockUiError
import io.vaultix.vaultix.ui.error.toUnlockUiError
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 添加本地 KDBX 库。
 *
 * 与 [AddVaultViewModel]（Bitwarden）的差别：**没有服务器、没有账号、没有 2FA** ——
 * KDBX 的认证发生在文件上（主密码 + 可选 keyfile），全程离线。
 *
 * 两步式交互（选文件 → 输密码）而不是一次问全：KDBX 的 keyfile 是**可选**的，
 * 一上来就摆一个「密钥文件」输入框会让绝大多数（无 keyfile 的）用户以为必须提供。
 */
@HiltViewModel
class AddKdbxViewModel @Inject constructor(
    private val vaultRepository: VaultRepository,
) : ViewModel() {

    data class UiState(
        /** 选中的 `.kdbx` 文件（null = 还没选）。 */
        val fileUri: String? = null,
        val fileName: String = "",
        /** 可选的 keyfile（null = 该库不用密钥文件）。 */
        val keyFileUri: String? = null,
        val keyFileName: String = "",
        val password: String = "",
        val passwordVisible: Boolean = false,
        val submitting: Boolean = false,
        val error: UnlockUiError? = null,
    ) {
        /** 是否可以提交：选了文件 + 填了密码。 */
        val canSubmit: Boolean get() = !fileUri.isNullOrBlank() && password.isNotEmpty() && !submitting
    }

    sealed interface Event {
        data object VaultAdded : Event
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun onDatabasePicked(context: Context, uri: Uri?) {
        if (uri == null) return
        // 持久读权限由 UI 侧 takePersistableUriPermission 取得（见 AddKdbxScreen）：
        // 库文件必须跨进程重启仍可读，否则每次解锁都要求用户重新选文件。
        _state.update {
            it.copy(
                fileUri = uri.toString(),
                fileName = displayNameOf(context, uri) ?: uri.lastPathSegment.orEmpty(),
                error = null,
            )
        }
    }

    fun onKeyFilePicked(context: Context, uri: Uri?) {
        if (uri == null) return
        _state.update {
            it.copy(
                keyFileUri = uri.toString(),
                keyFileName = displayNameOf(context, uri) ?: uri.lastPathSegment.orEmpty(),
                error = null,
            )
        }
    }

    fun clearKeyFile() = _state.update { it.copy(keyFileUri = null, keyFileName = "") }

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }

    fun onPasswordVisibleChange(visible: Boolean) =
        _state.update { it.copy(passwordVisible = visible) }

    fun submit() {
        val current = _state.value
        if (current.fileUri.isNullOrBlank()) {
            _state.update { it.copy(error = UnlockUiError.Unknown("请先选择 .kdbx 数据库文件")) }
            return
        }
        if (current.password.isEmpty() || current.submitting) return

        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            val result = vaultRepository.addKdbxVault(
                sourceUri = current.fileUri,
                displayName = current.fileName,
                masterPassword = current.password,
                keyFileUri = current.keyFileUri,
            )
            if (result == UnlockResult.Success) {
                // 密码用完即弃（不留在 UiState 快照里）
                _state.update {
                    it.copy(submitting = false, password = "", error = null)
                }
                _events.send(Event.VaultAdded)
            } else {
                _state.update {
                    it.copy(submitting = false, error = result.toUnlockUiError())
                }
            }
        }
    }

    /** SAF 的显示名（选不到时退回 URI 末段）。 */
    private fun displayNameOf(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index < 0 || !cursor.moveToFirst()) return@use null
            cursor.getString(index)
        }
    }.getOrNull()
}
