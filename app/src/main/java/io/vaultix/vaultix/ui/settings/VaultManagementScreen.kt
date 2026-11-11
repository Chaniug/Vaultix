/*
 * Vaultix — app:ui · settings
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.vaultix.datastore.VaultTimeout
import io.vaultix.model.VaultKind
import io.vaultix.model.VaultSummary
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.common.DialogOutlinedActionButton
import io.vaultix.vaultix.ui.common.DialogTextButton
import io.vaultix.vaultix.ui.vaultlist.SyncNotice
import io.vaultix.vaultix.ui.common.AddVaultTypeDialog
import io.vaultix.vaultix.ui.common.KdbxConflictChoice
import io.vaultix.vaultix.ui.vaultlist.kdbxSyncLineText
import io.vaultix.vaultix.ui.common.KdbxConflictDialog
import io.vaultix.vaultix.ui.theme.Spacing
import io.vaultix.vaultix.ui.theme.UiSize

/**
 * 「密码库管理」二级页（设置首页「密码库管理」一行进入）。
 *
 * ## 为什么要单独一页
 *
 * 2026-09-15 用户反馈：「设置页面的密码库、添加密码库、快速解锁设置，这几个好像都属于
 * 密码库管理的内容，我在想这些能不能放到一起管理。」
 *
 * 这条观察是对的，且可以量化：改之前这三件事**散在两个组**——
 * - 「密码库」组：当前密码库 · 添加密码库；
 * - 「解锁与隐私」组：快速解锁（夹在自动锁定与防截屏之间）。
 *
 * 「快速解锁」在语义上属于**库**（它为每个库单独登记一条解锁凭据，`quickUnlockVaults`
 * 就是"每库一行"的形态），把它放在隐私组里，用户要改"某个库怎么解锁"得先想到去隐私组找。
 *
 * ## 与 `.ai/decisions/设置页信息架构-定稿.md` 的关系
 *
 * 那份定稿 §3 把「快速解锁」放在「解锁与隐私」，并写了「**别重新设计分组**」。
 * 本页是**对该定稿的一次修订**（用户明确要求），差异与理由记在定稿的 §10。
 * 定稿的其余结论（破坏性动作置底、消灭单行组、文案三规则）**继续有效**，本页照办。
 *
 * ## 边界
 *
 * - 本页承载的是**库相关**的设置：有哪些库 / 正在浏览哪个 / 默认哪个 / 库从哪来。
 * - ★ **2026-09-30 晚：「解锁方式」已搬走**（独立页 [UnlockMethodScreen]）。
 *   此前它在**本页页首**（批次 C 的取舍），后果是设置首页的「密码库管理」与
 *   「解锁方式」两行**导航到同一页** —— 用户真机反馈：「打开好像都是同一个页面，这不对吧」。
 *   分开的依据是**作用域**：本页管"有哪些库、正在看哪个"（**逐个库**），
 *   那一页管"**怎么开锁**"（**全局**，所有库都遵从 —— 与系统 Keyguard 同构）。
 *   **流程宿主 [QuickUnlockHost] 一并搬走**：流程由哪一页发起，就由哪一页渲染对话框。
 * - ★ **「全部密码库」组（2026-09-18）取代了设置首页的全局「退出数据库」**：
 *   后者是**全局**动作、**范围不可见** ⇒ 误点一次就以为数据丢了（定稿 §11.10）。
 *   现在锁定 / 同步 / 退出 / 移除都在**每个库自己的 ⋮** 里，范围写在被点的那一行上。
 * - ★ **2026-09-30 批次 C（多库锁模型定稿 §8）**：选库从「当前库」行的弹窗改为
 *   **列表行内直选**（点行切浏览目标 / 去解锁；**默认库写进副标题**；行尾同步 + ⋮）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultManagementScreen(
    onBack: () -> Unit,
    /** 「添加 Bitwarden 云端库」（导航到登录 / 添加流程）。 */
    onAddBitwardenVault: () -> Unit = {},
    /** 「打开本地 KDBX 文件」（导航到文件选择流程）。 */
    onAddKdbxVault: () -> Unit = {},
    /** 「从网盘添加」（导航到 WebDAV / OneDrive 配置流程）。 */
    onAddCloudVault: () -> Unit = {},
    /** 「网盘账号」（导航到 OneDrive / WebDAV 的账号与凭据管理，定稿 §11.7）。 */
    onOpenCloudAccounts: () -> Unit = {},
    /** 本地 KDBX 的 ⋮ →「放到网盘」引导页（2026-11-10；只给方法，不代搬文件）。 */
    onOpenKdbxCloudGuide: () -> Unit = {},
    /** 点一个**未解锁**的库时，去它的解锁页输主密码。 */
    onOpenLockedVault: (String) -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val active by viewModel.activeVault.collectAsStateWithLifecycle()
    val default by viewModel.defaultVault.collectAsStateWithLifecycle()
    val switchable by viewModel.switchableVaults.collectAsStateWithLifecycle()
    val vaultActions = viewModel.vaultActions
    val actionDialog by vaultActions.dialog.collectAsStateWithLifecycle()
    val busyVaultId by vaultActions.busyVaultId.collectAsStateWithLifecycle()

    var showAddDialog by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.group_vaults)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = Spacing.xxl),
        ) {
            // ⚠️ 2026-11-10：这一行 `Spacing.sm` 是**必需**的，不是凑数。
            //   二级页的 Scaffold 已消费掉顶栏让出的 inset，内容是贴顶栏下沿开始画的；
            //   而本页首元素要么是不带外边距的 SettingsGroupCard，要么是裸 Text
            //   ⇒ 少这一行就有 0dp 上留白，与「自动填充」「权限管理」两页的 8dp 对不齐，
            //   同一层级的页面换一页就"顶上去一截"。自动填充 / 权限两页靠
            //   AutofillStatusCard / PermissionStatusCard 自带的 vertical = md 撑出边距，
            //   本页没有那张卡，只能自己补。
            Spacer(Modifier.height(Spacing.sm))
            // ---- 全部密码库（§8：行内单选浏览 + 默认标记 + 状态 + 同步 + ⋮）----
            // ⚠️ 库一个都没有时不画这一组（"空组 + 空态文案"是两种空，这里直接不出现更诚实：
            //    没有库时下面的「添加密码库」才是用户该看的东西）。
            if (switchable.isNotEmpty()) {
                SettingsGroupTitle(stringResource(R.string.vault_management_group_all))
                SettingsGroupCard {
                    switchable.forEachIndexed { index, vault ->
                        if (index > 0) SettingsDivider()
                        VaultActionRow(
                            vault = vault,
                            isActive = vault.id == active?.id,
                            isDefault = vault.id == default?.id,
                            busy = busyVaultId == vault.id,
                            actions = VaultRowActions(
                                // §8 点按语义：已解锁 → 只切浏览目标；未解锁 → 去解锁页
                                //（2026-09-15 空白页 bug 的教训，这个分支不能退化）。
                                onSelect = {
                                    if (vault.unlocked) {
                                        viewModel.selectVault(vault.id)
                                    } else {
                                        onOpenLockedVault(vault.id)
                                    }
                                },
                                // 设为默认只管冷启动先开谁，**不要求当下解锁**（既有决策）；
                                // 已解锁时顺带切过去，避免「活跃 A / 默认 B」的不一致观感。
                                onSetDefault = {
                                    if (vault.unlocked) viewModel.selectVault(vault.id)
                                    viewModel.setDefaultVault(vault.id)
                                },
                                onLock = { vaultActions.lock(vault) },
                                onSync = { vaultActions.sync(vault) },
                                onMoveToCloud = onOpenKdbxCloudGuide,
                                onSignOut = { vaultActions.requestSignOut(vault) },
                                onRemove = { vaultActions.requestRemove(vault) },
                            ),
                        )
                    }
                }
            }

            // ---- 库的来源（★ 2026-09-30 批次 C：原「当前库」组去掉「当前密码库」行后
            //      组名不再贴切 —— 选库已上提到上面每行的行内，这里只剩来源类入口）----
            SettingsGroupTitle(stringResource(R.string.vault_management_group_sources))
            SettingsGroupCard {
                SettingsRow(
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    title = stringResource(R.string.vault_add_fab),
                    subtitle = stringResource(
                        if (switchable.isEmpty()) {
                            R.string.settings_add_vault_desc
                        } else {
                            R.string.settings_add_vault_desc_another
                        },
                    ),
                    onClick = { showAddDialog = true },
                )
                // ★ 入口放在**这一组里**而不是设置首页新开一组：
                //   首页新开一个单行组会违反定稿 §2③「消灭单行组」。
                //   语义上也对 —— 它就是"库的来源怎么连"。
                SettingsDivider()
                SettingsRow(
                    icon = { Icon(Icons.Filled.Cloud, contentDescription = null) },
                    title = stringResource(R.string.cloud_accounts_title),
                    subtitle = stringResource(R.string.cloud_accounts_entry_desc),
                    onClick = onOpenCloudAccounts,
                )
            }
        }
    }

    // ---- 对话框 ----
    // ⚠️ 2026-09-30 晚：**每库「自动锁定」入口已按用户要求移除**（原话：「感觉冗余了」）。
    //    档位统一由设置首页的「自动锁定」一行掌管（对所有库生效）。
    //    逐库覆盖的数据层仍然保留（见 `VaultTimeoutPreferences.vaultTimeoutOverride` 的 KDoc：
    //    既有数据要能被正确解读、清理迁移要用、且单测钉住回退顺序），只是**不再有 UI 入口**
    //    ⇒ 用户不会再遇到"某个库被一条看不见的覆盖钉住、改了全局却不动"。

    if (showAddDialog) {
        AddVaultTypeDialog(
            onConnectBitwarden = {
                showAddDialog = false
                onAddBitwardenVault()
            },
            onOpenKdbx = {
                showAddDialog = false
                onAddKdbxVault()
            },
            onAddCloudKdbx = {
                showAddDialog = false
                onAddCloudVault()
            },
            onDismiss = { showAddDialog = false },
        )
    }

    // ---- 对话框 ----
    // ⚠️ 本页只有"添加库"一个对话框了：「库选择」早已上提到行内（§8）、
    //    「解锁方式」也已搬到独立页（[UnlockMethodScreen]，2026-09-30 晚）——
    //    **连同流程宿主 [QuickUnlockHost] 一起搬走**（拨开关会启动多步流程，
    //    那些对话框该由发起它的那一页渲染）。
    //    ⚠️ 别因为"这里以前有"而把它加回来：两页各挂一个宿主，
    //    同一个流程会在两处同时弹（或弹出在用户已经离开的那一页）。

    // ---- 逐库动作：确认 / 冲突拍板 / 结果 ----
    VaultActionDialogs(dialog = actionDialog, controller = vaultActions)
}

/**
 * 一行的全部动作回调（打包传递：单独列参数会撞上 detekt `LongParameterList`
 * 的实际上限 7 个 —— #130 的勘误，别再当 8）。
 */
private class VaultRowActions(
    /** 点整行：已解锁 → 切浏览目标；未解锁 → 去解锁页（§8 点按语义）。 */
    val onSelect: () -> Unit,
    /**
     * 设为默认库（冷启动先开谁，独立于浏览与解锁状态）。
     *
     * ★ 2026-11-08：入口从行尾星标移进 ⋮ 菜单 —— 它是一次性设置，不该占行内最高频的位置。
     */
    val onSetDefault: () -> Unit,
    val onLock: () -> Unit,
    val onSync: () -> Unit,
    /**
     * 「放到网盘…」：打开引导页（2026-11-10）。
     *
     * ⚠️ 它**不是**一次迁移：只讲三步，不碰文件。命名用 `onMoveToCloud` 是为了
     *    和菜单文案对应；真做迁移的那天，这个回调应该换成另一个名字（别再往里塞逻辑）。
     */
    val onMoveToCloud: () -> Unit,
    val onSignOut: () -> Unit,
    val onRemove: () -> Unit,
)

/**
 * 一个库 = 一行（§8 形态）：库名 + 状态/默认标记副标题 + 右侧**同步 + ⋮**。
 *
 * ★ 2026-09-30 批次 C：从「每行一个 ⋮」升级为**行内单选浏览**——
 * - 图标槽：当前浏览的库给 `Check`（显式单选，同旧库选择器的选中语义）；
 *   未解锁的库**永不画 Check**（「已选中但内容空白」的观感，2026-09-15 的教训）。
 * - 点整行 = 切浏览目标 / 去解锁。
 *
 * ★ 2026-11-08：行尾从「**星标 + ⋮**」改成「**同步 + ⋮**」。
 *
 * 起因：用户反馈「同步要点击库后面的 3 个点才展开，有点麻烦」。按 M3 的
 * `ListItem` 分层——`trailing` 只承诺放**一个高频动作**，其余进 overflow——
 * 这一行原来的排布正好是反的：
 * - 星标（设为默认库）是**一次性设置**，却占着最高频的位置；
 * - 同步是「改完就想点」的高频动作，却藏在 ⋮ 的第二层。
 *
 * ⇒ 星标降级成副标题里的「默认库」三个字（"哪个是默认"本来就是**状态**，
 *   不是动作），「设为默认库」移进 ⋮；行尾腾出来的位置给同步。
 *   条目页 ⋮ 菜单里的同步同理（见 `ItemsScreen` 的 `MenuSyncAction`）。
 *
 * ⚠️ 同步按钮**只在 [canSyncToRemote] 时出现**：本地 KDBX（`content://`）
 *    的真相就是那个文件，没有"另一端"，给它画同步按钮是假的。
 *
 * ★ 菜单项**按能力显示**，不是全列出来再禁用：
 * - 本地 KDBX 没有「同步」—— 同上；
 * - KDBX 没有「退出」—— 它没有账号、没有 token，退出与锁定是同一件事。
 *
 * 「状态放副标题」是 §11.10 明确要求的：真正要"看得见"的信息是**状态**，
 * 不是"这里可以点"。§8 之后副标题再带上**该库自己的**档位（D3）。
 */
@Composable
private fun VaultActionRow(
    vault: VaultSummary,
    isActive: Boolean,
    isDefault: Boolean,
    busy: Boolean,
    actions: VaultRowActions,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    SettingsRow(
        icon = {
            when {
                // 当前浏览目标：显式单选标记（未解锁项永不画，见 KDoc）。
                isActive && vault.unlocked ->
                    Icon(Icons.Filled.Check, contentDescription = null)
                vault.kind == VaultKind.BITWARDEN ->
                    Icon(Icons.Filled.Cloud, contentDescription = null)
                else -> Icon(Icons.Filled.Storage, contentDescription = null)
            }
        },
        title = vault.name,
        subtitle = vaultStatusSubtitle(vault = vault, isDefault = isDefault),
        onClick = actions.onSelect,
        trailing = {
            if (busy) {
                // 忙碌时两个按钮都不给：一次只该跑一个动作，避免连点出两个相互打架的结果。
                CircularProgressIndicator(modifier = Modifier.size(UiSize.InlineProgress))
            } else {
                // 同步 = 本行唯一的高频动作，直接露出（1 步可达，不再经 ⋮）。
                if (vault.canSyncToRemote()) {
                    IconButton(onClick = actions.onSync) {
                        Icon(
                            Icons.Filled.Sync,
                            contentDescription = stringResource(R.string.vault_action_sync),
                        )
                    }
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.content_desc_more_options),
                        )
                    }
                    VaultActionMenu(
                        expanded = menuExpanded,
                        vault = vault,
                        isDefault = isDefault,
                        onDismiss = { menuExpanded = false },
                        actions = actions,
                    )
                }
            }
        },
    )
}

/**
 * ⋮ 菜单（按能力出现，见 [VaultActionRow]；破坏性动作置底且标红 —— 定稿 §2①）。
 *
 * ⚠️ 「同步」**不在**这里（2026-11-08）：它已经露在行尾。同一个动作既在行内
 *    又在菜单里，只会让菜单变长、让用户犹豫点哪个 —— M3 的 overflow 只收
 *    「行内没位置放的低频动作」。
 */
@Composable
private fun VaultActionMenu(
    expanded: Boolean,
    vault: VaultSummary,
    isDefault: Boolean,
    onDismiss: () -> Unit,
    actions: VaultRowActions,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        // ★ 2026-11-08：星标降级后，「设为默认库」落到这里；已经是默认库时整项不显示
        //   （显示一个点了没反应的项，比不显示更让人困惑）。
        if (!isDefault) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.settings_vault_set_default)) },
                leadingIcon = { Icon(Icons.Filled.StarBorder, contentDescription = null) },
                onClick = {
                    onDismiss()
                    actions.onSetDefault()
                },
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.vault_action_lock)) },
            leadingIcon = { Icon(Icons.Filled.Lock, contentDescription = null) },
            onClick = {
                onDismiss()
                actions.onLock()
            },
        )
        // ★ 2026-11-10：本地 KDBX 的「放到网盘…」—— 它进的是**说明页**，
        //   不是"立即上传"（理由见 `KdbxCloudGuideScreen` 的 KDoc）。
        //   放在「退出」之前：同属非破坏性动作，且比"移除"更常用。
        if (vault.canMoveToCloud()) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.vault_action_move_to_cloud)) },
                leadingIcon = { Icon(Icons.Filled.CloudUpload, contentDescription = null) },
                onClick = {
                    onDismiss()
                    actions.onMoveToCloud()
                },
            )
        }
        if (vault.canSignOutOfDevice()) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.vault_action_signout)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null) },
                onClick = {
                    onDismiss()
                    actions.onSignOut()
                },
            )
        }
        // 破坏性动作**置底且标红**（定稿 §2①），与上面各项隔一层视觉分区。
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.error,
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.vault_remove_menu)) },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = {
                    onDismiss()
                    actions.onRemove()
                },
            )
        }
    }
}

/**
 * 状态副标题：默认标记（+ 解锁态 + 网盘同步态）。
 *
 * ★ 2026-11-08：「默认库」从行尾星标降级成这里的**文字**。它本来就是状态
 *    （"冷启动先开哪个"），做成可点按钮等于用最高频的位置放最低频的动作。
 *    放在第一段是因为它决定"这个库有多重要"，比解锁态更该被先看到。
 *
 * ⚠️ 2026-09-30 晚：**档位段已删**。它随「每库自动锁定入口」一起进来（§8/D3），
 * 而那个入口用户已要求移除 ⇒ 每行再重复一遍同一个全局档位只是噪音
 * （所有库的档位现在必然相同）。档位只在设置首页那一行说一次。
 */
@Composable
private fun vaultStatusSubtitle(vault: VaultSummary, isDefault: Boolean): String {
    val default = if (isDefault) stringResource(R.string.settings_vault_default_badge) else null
    val unlocked = stringResource(
        if (vault.unlocked) R.string.vault_status_unlocked else R.string.vault_status_locked,
    )
    // ★ 2026-11-10 晚：判据改走**全项目唯一**那句（`vaultSyncLineResOf`）。
    //   此前这里自带一份 `when`，且把 `LOCAL_ONLY` 映射成「已同步」——
    //   本地文件被说成"已同步"，正是用户这轮反馈的来源之一。
    //   本地 KDBX 现在恒为 null（本页因此不再出现那一段）。
    val sync = kdbxSyncLineText(vault)
    return listOfNotNull(default, unlocked, sync).joinToString(" · ")
}

/** 逐库动作的三种对话框：确认（退出 / 移除）· 冲突拍板 · 结果。 */
@Composable
private fun VaultActionDialogs(
    dialog: VaultActionsController.Dialog?,
    controller: VaultActionsController,
) {
    when (dialog) {
        is VaultActionsController.Dialog.SignOut -> VaultSignOutDialog(
            vaultName = dialog.vault.name,
            onConfirm = { controller.confirmSignOut(dialog.vault) },
            onDismiss = controller::dismiss,
        )
        is VaultActionsController.Dialog.Remove -> VaultRemoveDialog(
            vaultName = dialog.vault.name,
            onConfirm = { controller.confirmRemove(dialog.vault) },
            onDismiss = controller::dismiss,
        )
        // 冲突拍板**复用**条目页那套三选项对话框（`KdbxConflictDialog`）：
        // 三个选项的后果说明是方案 §8 逐字定过的，再写一份必然漂移。
        is VaultActionsController.Dialog.Conflict -> KdbxConflictDialog(
            vaultName = dialog.vault.name,
            onChoose = { choice ->
                when (choice) {
                    KdbxConflictChoice.KeepLocalUpload -> controller.resolveUsingLocal(dialog.vault)
                    KdbxConflictChoice.KeepRemoteDownload -> controller.resolveUsingRemote(dialog.vault)
                    // ⚠️ 「稍后再决定」也要回调：关对话框这个动作本身要落成
                    //    "状态停在冲突"，否则会卡在一个永远转不完的「同步中…」。
                    KdbxConflictChoice.DecideLater -> controller.deferConflict(dialog.vault.id)
                }
            },
            // 按返回 / 点外面关掉 **等同于**「稍后再决定」（该对话框自己的约定）。
            onDismiss = { controller.deferConflict(dialog.vault.id) },
        )
        is VaultActionsController.Dialog.Result -> VaultActionResultDialog(
            text = actionResultText(dialog.outcome, dialog.vaultName),
            isSuccess = isSuccessOutcome(dialog.outcome),
            onDismiss = controller::dismiss,
        )
        null -> Unit
    }
}

@Composable
private fun VaultSignOutDialog(
    vaultName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vault_action_signout)) },
        text = { Text(stringResource(R.string.vault_signout_confirm, vaultName)) },
        // ★ 2026-11-10：动作按钮统一走共用组件（#284 订正：规格单点真源；
        //   旧「intrinsic 缩进撑宽 / 逐字竖排」归因已被反编译证伪）。
        confirmButton = {
            DialogOutlinedActionButton(
                text = stringResource(R.string.vault_signout_action),
                onClick = onConfirm,
            )
        },
        dismissButton = {
            DialogOutlinedActionButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
            )
        },
    )
}

@Composable
private fun VaultRemoveDialog(
    vaultName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vault_remove_title)) },
        text = { Text(stringResource(R.string.vault_remove_message, vaultName)) },
        confirmButton = {
            // 破坏性确认用 error 色：它与"确定"這種中性动作不是同一类。
            DialogOutlinedActionButton(
                text = stringResource(R.string.vault_remove_confirm),
                onClick = onConfirm,
                contentColor = MaterialTheme.colorScheme.error,
            )
        },
        dismissButton = {
            DialogOutlinedActionButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
            )
        },
    )
}

@Composable
private fun VaultActionResultDialog(text: String, isSuccess: Boolean, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        // 结果已经发生，没有可确认或可放弃的动作 ⇒ 低强调 TextButton + 「完成 / 关闭」。
        confirmButton = {
            DialogTextButton(
                text = stringResource(if (isSuccess) R.string.action_done else R.string.action_close),
                onClick = onDismiss,
            )
        },
    )
}

/**
 * 结果对话框按钮文案的分流：成功类用「完成」，失败类一律「关闭」。
 *
 * ⚠️ 这个对话框只**报告结果**（动作已经跑完、没有可取消的东西），按 `DialogShell.kt`
 * 的纪律：失败结果写「完成」会误导成"事情做成了"。分类以 `VaultActionsController.Outcome`
 * 实际枚举为准 —— 失败类 = `Failed` / `SyncFailed` / `SyncUnsupported` /
 * `SyncNeedsUnlock` / `SyncLocked` / `SyncStaleBaseline`（正文都是「失败 / 不支持 /
 * 需先解锁 / 已暂停」这类），其余为成功或中性的「已… / 一致」。
 */
private fun isSuccessOutcome(outcome: VaultActionsController.Outcome): Boolean = when (outcome) {
    // 全用 `is` 分支：sealed interface 的穷尽性检查在「is 与等值条件混写」时
    // 偶尔判不出来，本机又跑不了编译 ⇒ 统一写法最稳。data object 也支持 is。
    is VaultActionsController.Outcome.SyncFailed,
    is VaultActionsController.Outcome.Failed,
    is VaultActionsController.Outcome.SyncUnsupported,
    is VaultActionsController.Outcome.SyncNeedsUnlock,
    is VaultActionsController.Outcome.SyncLocked,
    is VaultActionsController.Outcome.SyncStaleBaseline -> false
    is VaultActionsController.Outcome.Locked,
    is VaultActionsController.Outcome.SignedOut,
    is VaultActionsController.Outcome.Removed,
    is VaultActionsController.Outcome.SyncUnchanged,
    is VaultActionsController.Outcome.Synced -> true
}

/** 结果 → 文案（成功与失败都给得出一句话，见 `VaultActionsController.Outcome`）。 */
@Composable
private fun actionResultText(
    outcome: VaultActionsController.Outcome,
    vaultName: String,
): String = when (outcome) {
    VaultActionsController.Outcome.Locked ->
        stringResource(R.string.vault_result_locked, vaultName)
    VaultActionsController.Outcome.SignedOut ->
        stringResource(R.string.vault_result_signed_out, vaultName)
    VaultActionsController.Outcome.Removed -> stringResource(R.string.vault_removed)
    is VaultActionsController.Outcome.Synced -> outcome.count?.let {
        stringResource(R.string.vault_result_sync_done_with_count, vaultName, it)
    } ?: stringResource(R.string.vault_result_sync_done, vaultName)
    VaultActionsController.Outcome.SyncUnchanged ->
        stringResource(R.string.vault_result_sync_unchanged, vaultName)
    is VaultActionsController.Outcome.SyncFailed ->
        stringResource(
            R.string.vault_result_sync_failed,
            vaultName,
            syncNoticeText(outcome.reason),
        )
    VaultActionsController.Outcome.SyncUnsupported ->
        stringResource(R.string.vault_result_sync_unsupported, vaultName)
    VaultActionsController.Outcome.SyncNeedsUnlock ->
        stringResource(R.string.vault_result_sync_needs_unlock, vaultName)
    VaultActionsController.Outcome.SyncLocked ->
        stringResource(R.string.vault_result_sync_locked, vaultName)
    VaultActionsController.Outcome.SyncStaleBaseline ->
        stringResource(R.string.vault_result_sync_stale_baseline, vaultName)
    is VaultActionsController.Outcome.Failed ->
        stringResource(R.string.vault_result_failed, vaultName, outcome.detail)
}


// ⚠️ 档位对话框与档位文案**不在这里**：`AutoLockDialog` / `vaultTimeoutLabel` 已提升为
// `SettingsComponents.kt` 的 internal（设置页的全局档位 + 本页的每库覆盖共用一个弹窗，
// 复制一份必然漂移）。本页只负责"给哪个库开对话框"与结果落库。

/**
 * [SyncNotice] → 一句话。
 *
 * ⚠️ 抽成独立函数是**为 detekt 的复杂度服务**，不是为了好看：[actionResultText]
 * 已经是个 11 分支的 `when`，把这里的 2 分支内联进去会把圈复杂度顶到 15
 * （上限 14，2026-10-21 实测报红）。⇒ 以后往 `SyncNotice` 加分支，加在这里，
 * **不要**往 `actionResultText` 里塞。
 */
@Composable
private fun syncNoticeText(notice: SyncNotice): String = when (notice) {
    is SyncNotice.Res -> stringResource(notice.id)
    is SyncNotice.Detail -> notice.message
}
