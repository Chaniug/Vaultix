package com.bastion.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.bastion.app.data.PasswordEntry

/**
 * 密码列表的对话框/弹窗状态容器（拆分计划第四步批 1）。
 * 原先这些 mutableStateOf 全部内联在 [com.bastion.app.ui.password.PasswordListContent] 主函数中，
 * 是该函数 JIT 指令超限（18199 > 16384）的组成部分之一，下沉后主函数减少 17 个 remember 注册。
 */
internal class PasswordListDialogState {
    // QuickStatus 弹窗（批量转移 / 批量删除 / KeePass 同步）与切后台抑制 key
    var showQuickStatusTransferDialog by mutableStateOf(false)
    var showQuickStatusDeleteDialog by mutableStateOf(false)
    var showQuickStatusKeePassSyncDialog by mutableStateOf(false)
    var backgroundedTransferOperationId by mutableStateOf<Long?>(null)
    var backgroundedDeleteOperationId by mutableStateOf<Long?>(null)
    var backgroundedKeePassSyncKey by mutableStateOf<String?>(null)

    // 批量操作对话框（批量删除 / 移动分类 / 手动堆叠确认）
    var showBatchDeleteDialog by mutableStateOf(false)
    var showMoveToCategoryDialog by mutableStateOf(false)
    var showManualStackConfirmDialog by mutableStateOf(false)
    var selectedManualStackMode by mutableStateOf(ManualStackDialogMode.STACK)

    // 详情对话框
    var showDetailDialog by mutableStateOf(false)
    var selectedPasswordForDetail by mutableStateOf<PasswordEntry?>(null)

    // 单项删除的密码验证对话框
    var passwordInput by mutableStateOf("")
    var passwordError by mutableStateOf(false)
    var itemToDelete by mutableStateOf<PasswordEntry?>(null)
    var singleItemPasswordInput by mutableStateOf("")
    var showSingleItemPasswordVerify by mutableStateOf(false)
}

@Composable
internal fun rememberPasswordListDialogState(): PasswordListDialogState {
    return remember { PasswordListDialogState() }
}
