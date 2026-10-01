/*
 * Vaultix — app:ui:addvault
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 本地 KDBX 库的入口 —— **打开已有** 与 **新建空白** 两态（M2 阶段 A 只读 → 阶段 B 可写）。
 *
 * ## ★ 为什么两态在**同一个页面**里，而不是两个入口
 *
 * 用户 2026-10-01 的诉求是"在打开 kdbx 这里也做新建"。两个动作填的东西高度重叠
 * （都要主密码、都可选 keyfile），拆到两个入口会让"我要的是哪个"这件事在**进入之前**
 * 就得选对 —— 而用户此刻往往还没想清楚。
 *
 * ## ★★ 但两态的**文案必须各说各的**，不能共用
 *
 * | | 打开已有 | 新建空白 |
 * |---|---|---|
 * | 主密码的含义 | **验证**一个既有的密码 | **设定**一个新的密码 |
 * | 系统面板 | `OpenDocument`（选**已存在**的文件） | `CreateDocument`（**输入新文件名**） |
 *
 * 混用一句话（比如一律叫"请输入主密码"）会让用户不知道自己在做哪件事，
 * 而 KDBX 的主密码**设错了没有任何找回途径** —— 这是本项目里少数"错了就永远错了"
 * 的地方，文案上不能含糊。
 *
 * ## ★ 为什么新建态要先问密码、**最后**才弹系统面板
 *
 * `CreateDocument` 一旦确认，系统就**真的建了一个空文件**。若把选位置放在第一步，
 * 用户后面填错了密码 / 改主意了，磁盘上就留下一个 0 字节的孤儿 `.kdbx`。
 * ⇒ 顺序固定为「填表 → 校验 → 弹面板 → 写文件」：面板取消 = 什么都没发生。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.addvault

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.vaultix.domain.KdbxAddOutcome
import io.vaultix.domain.KdbxCreateRepository
import io.vaultix.domain.NewKdbxVaultOutcome
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
 * 本地 KDBX 库的添加页（打开 / 新建两态）。
 *
 * 与 [AddVaultViewModel]（Bitwarden）的差别：**没有服务器、没有账号、没有 2FA** ——
 * KDBX 的认证发生在文件上（主密码 + 可选 keyfile），全程离线。
 *
 * 打开态是两步式交互（选文件 → 输密码）而不是一次问全：KDBX 的 keyfile 是**可选**的，
 * 一上来就摆一个「密钥文件」输入框会让绝大多数（无 keyfile 的）用户以为必须提供。
 */
@HiltViewModel
class AddKdbxViewModel @Inject constructor(
    private val vaultRepository: VaultRepository,
    private val kdbxCreateRepository: KdbxCreateRepository,
) : ViewModel() {

    /** 页面处于哪一态（用户可在页内切换）。 */
    enum class Mode {
        /** 打开一个已存在的 `.kdbx`。 */
        Open,

        /** 新建一个空白库。 */
        Create,
    }

    data class UiState(
        val mode: Mode = Mode.Open,
        /** 选中的 `.kdbx` 文件（null = 还没选）。仅 [Mode.Open] 用。 */
        val fileUri: String? = null,
        val fileName: String = "",
        /** 可选的 keyfile（null = 该库不用密钥文件）。 */
        val keyFileUri: String? = null,
        val keyFileName: String = "",
        /** 主密码。打开态 = 既有密码；新建态 = 要设定的新密码。 */
        val password: String = "",
        val passwordVisible: Boolean = false,
        /** 新建态：二次确认输入（打开态不用 —— 没有"输错"这回事，验不过就是验不过）。 */
        val passwordRepeat: String = "",
        /** 新建态：库名（同时是根组名，会写进文件）。 */
        val vaultName: String = "",
        val submitting: Boolean = false,
        val error: UnlockUiError? = null,
    ) {
        /** 打开态能否提交：选了文件 + 填了密码。 */
        val canSubmitOpen: Boolean
            get() = !fileUri.isNullOrBlank() && password.isNotEmpty() && !submitting

        /**
         * 新建态能否提交：填了库名 + 密码 + 二次确认（且不看是否一致 ——
         * 不一致要让用户**点下去看到原因**，而不是按钮灰着一个猜不出为什么的状态）。
         */
        val canSubmitCreate: Boolean
            get() = vaultName.isNotBlank() && password.isNotEmpty() &&
                passwordRepeat.isNotEmpty() && !submitting
    }

    sealed interface Event {
        /**
         * 添加成功 —— **分「新增 / 已存在并覆盖」两态**（`.ai/ISSUES.md` #94）。
         *
         * ⚠️ 必须分开：KDBX 库 id 就是文件 URI，重复添加同一文件会覆盖同一行、
         * 列表零变化。若只发一个「成功」，UI 无法向用户解释「为什么列表没变」，
         * 用户读到的就是「点了一点反应都没有」。
         */
        data class VaultAdded(val isUpdate: Boolean) : Event

        /** 新库已创建（新建态专用）。**只有这一个分支** —— 见 [NewKdbxVaultOutcome] 的说明。 */
        data object VaultCreated : Event
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** 切换打开 / 新建。**清掉错误**：上一种模式留下的报错对新模式没有意义。 */
    fun onModeChange(mode: Mode) = _state.update { it.copy(mode = mode, error = null) }

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

    fun onPasswordRepeatChange(value: String) =
        _state.update { it.copy(passwordRepeat = value, error = null) }

    fun onVaultNameChange(value: String) = _state.update { it.copy(vaultName = value, error = null) }

    fun onPasswordVisibleChange(visible: Boolean) =
        _state.update { it.copy(passwordVisible = visible) }

    // ------------------------------------------------------------ 打开态

    fun submit() {
        val current = _state.value
        if (current.fileUri.isNullOrBlank()) {
            // 校验类文案走 `UnlockUiError.Validation`（→ `strings.xml`），
            // 而不是拼一句中文塞进 `Detail` —— 后者绕过资源系统，会与同类文案分家。
            _state.update { it.copy(error = UnlockUiError.Validation.FileNotPicked) }
            return
        }
        if (current.password.isEmpty() || current.submitting) return

        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            val outcome = vaultRepository.addKdbxVault(
                sourceUri = current.fileUri,
                displayName = current.fileName,
                masterPassword = current.password,
                keyFileUri = current.keyFileUri,
            )
            when (outcome) {
                // 允许覆盖 + 成功反馈（D5 定稿，见 .ai/decisions/库选择与快速解锁-逻辑定稿.md §7）
                KdbxAddOutcome.Added, KdbxAddOutcome.Updated -> {
                    // 密码用完即弃（不留在 UiState 快照里）
                    _state.update { it.copy(submitting = false, password = "", error = null) }
                    _events.send(Event.VaultAdded(isUpdate = outcome is KdbxAddOutcome.Updated))
                }

                is KdbxAddOutcome.Failed -> {
                    _state.update {
                        it.copy(submitting = false, error = outcome.result.toUnlockUiError())
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------ 新建态

    /**
     * 新建态的第一段：**校验表单，然后交给 UI 去弹系统保存面板**。
     *
     * ## 为什么这里不直接建库
     *
     * `ActivityResultContracts.CreateDocument` 只能由 UI 层启动（它要 Activity 的结果回调），
     * ViewModel 里拿不到。⇒ 本方法只做"该不该弹"，返回 true 时 UI 才 launch 面板。
     *
     * ⚠️ **顺序**：先校验、后面板。反过来的话用户取消面板时我们已经建了一个空文件
     * （见文件头的说明）。
     */
    fun requestSaveLocation(): Boolean {
        val current = _state.value
        // 以下每一条都**先设错误再返回 false**：按钮不该是"灰着一个猜不出为什么"的状态。
        val validationError = validateCreateForm(current)
        if (validationError != null) {
            _state.update { it.copy(error = validationError) }
            return false
        }
        return true
    }

    /**
     * 新建态的第二段：用户已在系统面板确认了保存位置 → 真正建库。
     *
     * @param uri 系统面板返回的目标文件 URI。**null = 用户取消** —— 什么都不做
     *   （不留痕迹，也不需要提示：取消是用户的明确意图）。
     * @param persistedUri 已经取得持久读写授权的 URI。
     *   ★ 为什么单独一个参数：授权是 UI 侧的副作用（`takePersistableUriPermission`），
     *   而"授权失败"必须与"用户取消"区分开 —— 前者建了库也会在重启后读不到。
     *   传 null 表示授权这一步失败了。
     * @param keyFileBytes 已读好的 keyfile 字节（null = 不用 keyfile）。
     *   ★ 为什么由 UI 读、不在这里读：读 keyfile 要 `ContentResolver`，
     *   而 ViewModel 持有 `Context` 字段就是一次泄漏（它比 Activity 活得久）。
     *   UI 读字节是**一次性动作**，传进来即可，ViewModel 不必认识 Android。
     */
    fun createVault(uri: Uri?, persistedUri: Uri?, keyFileBytes: ByteArray?) {
        if (uri == null) return
        if (persistedUri == null) {
            _state.update {
                it.copy(
                    submitting = false,
                    error = UnlockUiError.Validation.SaveLocationUnavailable,
                )
            }
            return
        }
        val current = _state.value
        if (current.submitting) return
        _state.update { it.copy(submitting = true, error = null) }

        viewModelScope.launch {
            val outcome = kdbxCreateRepository.createVault(
                targetUri = persistedUri.toString(),
                displayName = current.vaultName,
                masterPassword = current.password,
                keyFileBytes = keyFileBytes,
            )
            when (outcome) {
                is NewKdbxVaultOutcome.Created -> {
                    // 与打开态同款：密码用完即弃。
                    _state.update {
                        it.copy(
                            submitting = false,
                            password = "",
                            passwordRepeat = "",
                            error = null,
                        )
                    }
                    _events.send(Event.VaultCreated)
                }

                is NewKdbxVaultOutcome.Failed -> _state.update {
                    it.copy(submitting = false, error = outcome.result.toUnlockUiError())
                }
            }
        }
    }

    /**
     * 新建表单的校验。返回 null = 通过。
     *
     * 抽成独立函数是为了让 [requestSaveLocation] 一眼可读（"先校验、再返回"），
     * 而不是让五条 `if` 把主线埋掉。
     */
    private fun validateCreateForm(current: UiState): UnlockUiError? = when {
        current.vaultName.isBlank() -> UnlockUiError.Validation.VaultNameEmpty
        current.password.isEmpty() -> UnlockUiError.Validation.PasswordEmpty
        current.password != current.passwordRepeat -> UnlockUiError.Validation.PasswordMismatch
        else -> null
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
