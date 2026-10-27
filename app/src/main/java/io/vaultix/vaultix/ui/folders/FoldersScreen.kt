/*
 * Vaultix — app:ui:folders
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.folders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.vaultix.model.VaultKind
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.common.EmptyState
import io.vaultix.vaultix.ui.common.TopBarTitle
import io.vaultix.vaultix.ui.common.VaultixWavyProgress
import io.vaultix.vaultix.ui.common.VaultixExpressiveTopBar
import io.vaultix.vaultix.ui.common.rememberImmersiveBarPadding
import io.vaultix.vaultix.ui.theme.Spacing

/**
 * 文件夹管理页（#199）：按库类型分流的一页 CRUD。
 *
 * ## 页面结构的三处决定
 *
 * 1. **顶栏用 [VaultixExpressiveTopBar]**：全 App 的列表页顶栏统一在这个组件上。
 *    （回收站那页用的是 `LargeTopAppBar`，但那页不会随滚动隐身，属历史遗留，不跟它对齐。）
 * 2. **空态用 [EmptyState]**，且文案按"库是否可知"分开：库未知（多半是未解锁）时说
 *    "解锁后才有"，而不是让用户对着"还没有文件夹"去建一个注定失败的东西。
 * 3. 行尾「⋮」而不是整行长按：重命名 / 删除是**低频且互斥**的两个动作，
 *    放在行内溢出菜单里能表达"对**哪一行**做"；全局按钮表达不了这层归属。
 *
 * ## 删除确认的文案**必须按库类型分支**
 *
 * Bitwarden 的文件夹是扁平标签 ⇒ 删掉只让条目变"无文件夹"；
 * KDBX 的分组是树 ⇒ 删掉是递归删整棵子树（组 + 子组 + 条目全没）。
 * 用同一句"删除文件夹？"会让 KDBX 用户在毫无预警的情况下丢条目 ——
 * 这是本页唯一一处"两种库必须长得不一样"的地方。
 */
@Composable
fun FoldersScreen(
    onBack: () -> Unit,
    viewModel: FoldersViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val event by viewModel.event.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // 新建 / 重命名共用一个对话框状态机：null = 关闭。
    var dialog by rememberSaveable(stateSaver = FolderDialogSaver) {
        mutableStateOf<FolderDialogState?>(null)
    }
    // 待确认删除的文件夹 id（null = 无）。
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }

    val opFailed = stringResource(R.string.folder_op_failed)
    val createdMsg = stringResource(R.string.folder_created)
    val renamedMsg = stringResource(R.string.folder_renamed)
    val deletedMsg = stringResource(R.string.folder_deleted)

    LaunchedEffect(event) {
        val current = event ?: return@LaunchedEffect
        val message = when (current) {
            is FoldersViewModel.UiEvent.Succeeded -> when (current.what) {
                FoldersViewModel.Done.Created -> createdMsg
                FoldersViewModel.Done.Renamed -> renamedMsg
                FoldersViewModel.Done.Deleted -> deletedMsg
            }
            // 两层兜底：仓储给的 message 已经可展示；万一为 null 才用通用措辞。
            is FoldersViewModel.UiEvent.Failed -> current.message ?: opFailed
        }
        // 先消费再弹：旋转屏幕时不该重放同一条提示。
        viewModel.consumeEvent()
        snackbarHostState.showSnackbar(message)
    }

    val topPadding = rememberImmersiveBarPadding(collapseFraction = 0f)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                // 写操作进行中不许开新请求：仓储是**直连**，重复点会真的打出两次 API 调用。
                onClick = { if (!state.busy) dialog = FolderDialogState.Create },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.folders_add)) },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                state.loading -> VaultixWavyProgress(modifier = Modifier.align(Alignment.Center))

                state.rows.isEmpty() -> EmptyState(
                    title = stringResource(R.string.folders_empty_title),
                    body = emptyBodyFor(state.kind),
                    icon = Icons.Filled.Folder,
                    topInset = topPadding,
                )

                else -> LazyColumn(
                    contentPadding = PaddingValues(
                        start = Spacing.lg,
                        end = Spacing.lg,
                        top = topPadding + Spacing.sm,
                        // 底部给 FAB 让位，否则最后一行被按钮盖住（列表页的老问题）。
                        bottom = FAB_CLEARANCE,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.rows, key = { it.folder.id }) { row ->
                        FolderRow(
                            row = row,
                            busy = state.busy,
                            onRename = {
                                dialog = FolderDialogState.Rename(row.folder.id, row.folder.name)
                            },
                            onDelete = { pendingDelete = row.folder.id },
                        )
                    }
                }
            }

            VaultixExpressiveTopBar(
                title = TopBarTitle(text = stringResource(R.string.folders_title)),
                collapseFraction = 0f,
                modifier = Modifier.align(Alignment.TopCenter),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        }
    }

    FolderDialogHost(
        dialog = dialog,
        busy = state.busy,
        onDismiss = { dialog = null },
        onCreate = { name ->
            dialog = null
            viewModel.create(name)
        },
        onRename = { id, name ->
            dialog = null
            viewModel.rename(id, name)
        },
    )

    val deleting = state.rows.firstOrNull { it.folder.id == pendingDelete }
    if (deleting != null) {
        DeleteFolderDialog(
            name = deleting.folder.name,
            kind = state.kind,
            busy = state.busy,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                viewModel.delete(deleting.folder.id)
            },
        )
    }
}

/**
 * 新建 / 重命名对话框的宿主。
 *
 * 抽出来的原因：主 Composable 的圈复杂度到 18（detekt 上限 14）—— 它同时承担
 * 「列表分流 + 顶栏 + 两个对话框 + 删除确认」。这一块是**一组完整接线**
 * （把状态机翻成两种标题 + 两种回调），与 `ItemsScreen.NewItemDialogHost` 同性质。
 * ⚠️ 别把它合回去"省一个函数" —— 那只会让下一次加动作时再撞一次同一道门禁。
 */
@Composable
private fun FolderDialogHost(
    dialog: FolderDialogState?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
    onRename: (id: String, name: String) -> Unit,
) {
    when (dialog) {
        null -> Unit
        FolderDialogState.Create -> FolderNameDialog(
            title = stringResource(R.string.folder_name_new_title),
            initialName = "",
            busy = busy,
            onDismiss = onDismiss,
            onConfirm = onCreate,
        )
        is FolderDialogState.Rename -> FolderNameDialog(
            title = stringResource(R.string.folder_name_rename_title),
            initialName = dialog.name,
            busy = busy,
            onDismiss = onDismiss,
            onConfirm = { name -> onRename(dialog.id, name) },
        )
    }
}

/**
 * 空态正文按"库是否可知"分流。
 *
 * 库未知 ⇒ 基本是未解锁（或库刚被移除）。这两种情况都**不该**劝用户去建 ——
 * 建了也只会失败。抽成函数是为了让主 Composable 的圈复杂度留出余量（detekt 上限 14）。
 */
@Composable
private fun emptyBodyFor(kind: VaultKind?): String =
    stringResource(
        if (kind == null) R.string.folders_locked_body else R.string.folders_empty_body,
    )

/** 底部给 FAB 让出的高度：FAB 56dp + 上下两级呼吸位。 */
private val FAB_CLEARANCE = Spacing.xxl * 2

/** 新建 / 重命名共用的一种状态。 */
private sealed interface FolderDialogState {
    data object Create : FolderDialogState
    data class Rename(val id: String, val name: String) : FolderDialogState
}

private const val TYPE_CREATE = "create"
private const val TYPE_RENAME = "rename"

/**
 * `rememberSaveable` 的保存器：转屏 / 进程重建后对话框不该关掉（用户可能正在打字）。
 *
 * 存成扁平的 `listOf(type, id, name)` —— [FolderDialogState] 是私有 sealed interface，
 * 给它单独写一个泛型 `Saver` 要额外暴露实现类；三元组最省事且可读。
 */
private val FolderDialogSaver = listSaver<FolderDialogState?, String>(
    save = { state ->
        when (state) {
            null -> emptyList()
            FolderDialogState.Create -> listOf(TYPE_CREATE)
            is FolderDialogState.Rename -> listOf(TYPE_RENAME, state.id, state.name)
        }
    },
    restore = { saved ->
        when (saved.firstOrNull()) {
            TYPE_CREATE -> FolderDialogState.Create
            TYPE_RENAME -> FolderDialogState.Rename(id = saved[1], name = saved[2])
            else -> null
        }
    },
)

/**
 * 删除确认：**文案按库类型分支**（理由见 [FoldersScreen] 的 KDoc）。
 *
 * ⚠️ KDBX 侧**不显示条目数**，尽管本页确实聚合出了一个数字。
 * 原因：`FoldersViewModel` 的计数只统计 `folderId` 恰好等于该分组**完整路径**的条目，
 * **子组里的条目没算进去** ⇒ 数字系统性偏小。
 * 给一个偏小的数字比不给更危险（用户会想"才 2 条，删吧"），而算准它需要重走整棵树
 * ——那是 `KdbxItemWriter.deletionCostOf` 的活，为一个确认框去跑一次文件解析不划算。
 * ⇒ KDBX 只说"连同里面的条目一起删"，Bitwarden 侧照常给数（那边是扁平统计，准确）。
 */
@Composable
private fun DeleteFolderDialog(
    name: String,
    kind: VaultKind?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val isKdbx = kind == VaultKind.KDBX
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Text(
                stringResource(
                    if (isKdbx) {
                        R.string.folder_delete_kdbx_title
                    } else {
                        R.string.folder_delete_bitwarden_title
                    },
                ),
            )
        },
        text = {
            Text(
                if (isKdbx) {
                    // 只有一个占位符（名字）；`String.format` 多传参数不会崩，但没法表达"不算数"。
                    stringResource(R.string.folder_delete_kdbx_message, name)
                } else {
                    stringResource(R.string.folder_delete_bitwarden_message, name)
                },
            )
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.action_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/** 一行：文件夹图标 + 名字 + 条目数 + 溢出菜单（重命名 / 删除）。 */
@Composable
private fun FolderRow(
    row: FoldersViewModel.FolderRowUi,
    busy: Boolean,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = Spacing.lg,
                    top = Spacing.md,
                    bottom = Spacing.md,
                    end = Spacing.xs,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.folder.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.folders_item_count, row.itemCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(enabled = !busy, onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.folders_menu_entry),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.folder_action_rename)) },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.folder_action_delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}
