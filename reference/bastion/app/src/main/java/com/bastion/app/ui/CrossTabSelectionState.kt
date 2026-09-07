package com.bastion.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * 跨 tab 选择模式桥接状态容器（拆分计划第五批，批 5）。
 *
 * SimpleMainScreen 中 TOTP / 证件 / 银行卡三个 tab 的选择模式遵循同一结构：
 * 子组件把「是否在选择模式 / 已选数量 / 退出 / 全选 / 移动分类 / 删除 / 收藏」
 * 写进来，主函数再原样转发给共享的批量操作栏。原先三组共 19 个
 * `var xxx by remember { mutableStateOf(...) }` 全部内联在主函数体内，
 * 每个注册都展开为内联 Compose 读写指令，是 SimpleMainScreen 超出 ART
 * 16384 指令上限（实测 16652）的主要构成之一。下沉为容器后主函数每组
 * 仅持有 1 个 remember 引用。
 *
 * 语义等价性：字段为 mutableStateOf 委托属性，读写点从 `var x` 换为
 * `state.x`，重组订阅与原 var 完全一致；写入口/读出口的转发链路不变。
 */
internal class CrossTabSelectionState {
    var isSelectionMode: Boolean by mutableStateOf(false)
    var selectedCount: Int by mutableIntStateOf(0)
    var onExit: () -> Unit by mutableStateOf({})
    var onSelectAll: () -> Unit by mutableStateOf({})
    var onMoveToCategory: () -> Unit by mutableStateOf({})
    var onDelete: () -> Unit by mutableStateOf({})

    /** 仅银行卡组使用（收藏回调）；TOTP / 证件组保持默认空实现。 */
    var onFavorite: () -> Unit by mutableStateOf({})
}

@Composable
internal fun rememberCrossTabSelectionState(): CrossTabSelectionState {
    return remember { CrossTabSelectionState() }
}
