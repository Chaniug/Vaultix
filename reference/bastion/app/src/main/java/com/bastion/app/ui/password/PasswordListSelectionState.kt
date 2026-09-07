package com.bastion.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * 密码列表的选择模式状态容器（拆分计划第四步批 2）。
 * 原先这些 mutableStateOf 全部内联在主函数中，下沉以减少 JIT 指令超限。
 */
internal class PasswordListSelectionState {
    var isSelectionMode by mutableStateOf(false)
    var selectedItemKeys by mutableStateOf(setOf<String>())
    var swipeSelectionAnchorKey by mutableStateOf<String?>(null)
}

@Composable
internal fun rememberPasswordListSelectionState(): PasswordListSelectionState {
    return remember { PasswordListSelectionState() }
}
