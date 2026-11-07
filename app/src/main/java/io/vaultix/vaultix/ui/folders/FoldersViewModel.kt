/*
 * Vaultix — app:ui:folders
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.folders

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.vaultix.domain.FolderRepository
import io.vaultix.domain.ItemRepository
import io.vaultix.domain.VaultRepository
import io.vaultix.model.VaultFolder
import io.vaultix.model.VaultKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 文件夹管理（#199）：列表 + 新建 / 重命名 / 删除。
 *
 * ## 为什么单独开一页
 *
 * 「显示选项」是**看的方式**（分组 / 密度 / 图标）；文件夹是**数据本身**。
 * 把"能删数据"放在显示选项里，等于让用户为了调视图而路过一个删除入口。
 * ⋮ 菜单的「前往」组语义正好：去一个关于数据的页面。
 *
 * ## 一处刻意选择：`busy` 是**全局**单标志，不是"每个文件夹一个"
 *
 * 页面是单实例、对话框是模态的 ⇒ 三类写操作同时只可能有一个在跑。
 * 用集合维护"哪一行在忙"只会多出一处可能与真实状态脱节的地方。
 * 它同时兼作**防连点**：请求进行中所有写入口都关掉。
 */
@HiltViewModel
class FoldersViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val folderRepository: FolderRepository,
    itemRepository: ItemRepository,
    vaultRepository: VaultRepository,
) : ViewModel() {

    val vaultId: String = checkNotNull(savedStateHandle[ARG_VAULT_ID])

    /** 一行文件夹 + 条目数（让"删掉会怎样"在点之前就看得见）。 */
    data class FolderRowUi(
        val folder: VaultFolder,
        val itemCount: Int,
    )

    data class UiState(
        val rows: List<FolderRowUi> = emptyList(),
        /** 库类型：决定删除确认文案（Bitwarden 只脱标签 / KDBX 递归删子树）。 */
        val kind: VaultKind? = null,
        /**
         * 回收站条目数（11-07）。KDBX 的回收站是 `meta.recycleBinUuid` 指向的特殊
         * 分组，`KdbxItemMapper` 有意不把它放进 [rows]（对齐 Bitwarden 软删语义，
         * 且 `guardedGroupUuid` 禁止删/改名回收站组）⇒ UI 画一条**只读**行：
         * 让 XC 里建的那个分组可见，又不给破坏入口。null = 不适用 / 读不到。
         */
        val recycleBinCount: Int? = null,
        val loading: Boolean = true,
        val busy: Boolean = false,
    )

    /**
     * 操作成功。⚠️ 只带**语义**不带文案 —— ViewModel 里不写面向用户的字
     * （`.ai/tools/check_ui_hardcoded_text.py` 会拦；文案全在 `strings.xml`）。
     */
    enum class Done { Created, Renamed, Deleted }

    sealed interface UiEvent {
        data class Succeeded(val what: Done) : UiEvent

        /** 失败：**原样**透传仓储给的 message（它已经是可展示的一句话）。 */
        data class Failed(val message: String?) : UiEvent
    }

    private val _event = MutableStateFlow<UiEvent?>(null)
    val event: StateFlow<UiEvent?> = _event.asStateFlow()

    private val busy = MutableStateFlow(false)

    /** 每个文件夹下的条目数（Bitwarden 的 folderId / KDBX 的完整路径，两者都是字符串）。 */
    private val itemCounts: StateFlow<Map<String, Int>> = itemRepository.observeItems(vaultId)
        .map { items ->
            items.mapNotNull { it.folderId }.groupingBy { it }.eachCount()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyMap())

    val state: StateFlow<UiState> = combine(
        folderRepository.observeFolders(vaultId),
        vaultRepository.observeVaults(),
        itemRepository.observeTrash(vaultId),
        itemCounts,
        busy,
    ) { folders, vaults, trash, counts, isBusy ->
        val isKdbx = vaults.firstOrNull { it.id == vaultId }?.kind == VaultKind.KDBX
        UiState(
            rows = folders
                .map { FolderRowUi(folder = it, itemCount = counts[it.id] ?: 0) }
                // 名称排序放在这里而不是仓储：仓储的 `observeFolders` 已被其它消费方
                // （表单下拉框、分组视图）共用，那边要保持库内原始顺序。
                .sortedBy { it.folder.name.lowercase() },
            kind = vaults.firstOrNull { it.id == vaultId }?.kind,
            recycleBinCount = if (isKdbx) trash.size else null,
            loading = false,
            busy = isBusy,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        UiState(),
    )

    fun create(name: String) = launchWrite(Done.Created) {
        // 仓储返回新建文件夹（含 id）；本页只关心成功/失败。
        folderRepository.createFolder(vaultId, name).map { }
    }

    fun rename(folderId: String, newName: String) = launchWrite(Done.Renamed) {
        folderRepository.renameFolder(vaultId, folderId, newName)
    }

    fun delete(folderId: String) = launchWrite(Done.Deleted) {
        folderRepository.deleteFolder(vaultId, folderId)
    }

    /** 事件消费后清空，避免旋转屏幕重放同一条提示。 */
    fun consumeEvent() {
        _event.value = null
    }

    /**
     * 三个写操作的公共外壳：置忙 → 执行 → 清忙 → 发事件。
     *
     * ⚠️ 11-07 真机：KDBX 文件夹删除 100% 闪退（18:46:18 `app died`）。
     *    此前这里只有 `try/finally`：**没有 catch**。`block()` 若直接抛异常
     *    （而不是返回 `Result.failure`），会穿出 `viewModelScope.launch` 成为未捕获异常
     *    ⇒ 整个进程被杀，用户看不到任何提示。注释说「意外不该把页面卡在忙碌态」，
     *    但 `finally` 只清了 busy，没拦住异常本身。
     *    ⇒ 用 `runCatching` 把意外转成 `UiEvent.Failed`（Snackbar 可见，也方便定位
     *      根因）。⚠️ 不用 catch(Exception)：detekt 的 TooGenericExceptionCaught
     *      连 Exception 都判过宽，而这里要兜的正是「任何意外都别让进程死」。
     *      取消必须原样抛出（runCatching 默认会吞掉它）。
     */
    private fun launchWrite(what: Done, block: suspend () -> Result<Unit>) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                val guarded = runCatching { block() }.getOrElse { error ->
                    // 取消是协程的正常收尾，不是「写操作失败」，必须原样抛出。
                    if (error is CancellationException) throw error
                    Result.failure(error)
                }
                _event.value = guarded.fold(
                    onSuccess = { UiEvent.Succeeded(what) },
                    // message 允许为 null，**不在这里填兜底文案** —— 理由同
                    // `ItemDetailViewModel.UiEvent.SaveFailed`：兜底措辞属于展示层。
                    onFailure = { UiEvent.Failed(it.message) },
                )
            } finally {
                busy.value = false
            }
        }
    }

    companion object {
        const val ARG_VAULT_ID = "vaultId"
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
