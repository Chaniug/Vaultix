/*
 * Vaultix — app:ui · settings
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * **「网盘账号」二级页** —— 网盘账号的**全生命周期**都在这里（2026-09-18 补齐"可操作"）。
 *
 * ## 为什么必须独立成页（这是病因，不是排版偏好）
 *
 * 用户实测：「**返回到添加密码库界面，登录状态就没了**」—— 而且「根本不用划掉后台」。
 *
 * | | 生命周期 |
 * |---|---|
 * | OneDrive 登录态 / WebDAV 凭据 | **长**（跨进程跨页面，活在 MSAL 缓存 / `SecureCredentialStore`） |
 * | 「添加密码库」那一页 | **短** —— 导航路由，**一返回 ViewModel 即销毁** |
 *
 * ⇒ 把长寿命的状态存在短寿命页面里，**必然"返回就没了"**。
 * 本页就是给它一个**与状态寿命匹配的宿主**：设置里的持久页面。
 *
 * ## 2026-09-18：从"只读清单"升级为"可操作"
 *
 * 上一版只能列出来。用户看到账号"未连接"却在本页什么都做不了，只能去别处碰运气。
 * 现在补齐：
 *
 * | 动作 | WebDAV | OneDrive |
 * |---|---|---|
 * | 新增账号 | ✅ 填地址/账号/密码 + **连接自检** | ✅ 登录 |
 * | 自检 | ✅ 独立【测试连接】按钮 + 4 态状态卡 | —（登录态即连接态） |
 * | 换号 | 重填即可（同一地址同一账号会覆盖） | ✅ 切换账号 |
 * | 注销 | ✅ 删凭据 | ✅ 清 MSAL 缓存 |
 *
 * ⚠️ **注销会连带影响同账号的所有库**（凭据是账号级、被多个库共用）⇒
 * 确认框里必须写出**影响面**，不能点了就删。
 *
 * ## 数据从哪来
 *
 * [CloudAccountInventory] —— **已保存的凭据/登录会话** ∪ **库的 origin**（见该文件头
 * 2026-09-18 的修正）。第二个来源提供"被哪些库在用"与"从哪个目录开始浏览"。
 *
 * ## 2026-11-12 第十一轮（#285 第 1 项）：账号卡精修
 *
 * 用户反馈「网盘卡片大小、颜色、UI 要更精美好看、与页面和谐一致」。真机截图三个硬伤：
 * ① 卡片套的是设置页「**一组多行**」的 `SettingsGroupCard` + `SettingsRow`（72dp 行 +
 *    20dp 行内距 + 一整行按钮）⇒ 一个账号占掉一大块又高又空的深灰板；
 * ② 「已连接」被放在**卡片外**单独一行，落在卡片与「添加账号」之间，读起来像第三段正文；
 * ③ 主操作走 `PrimaryActionButton`（48dp 满宽 FilledTonal 胶囊），「注销」却是
 *    40dp 的 `OutlinedButton`，中间还被 `Spacer(weight(1f))` 推到屏幕两端 ⇒ 两枚按钮
 *    一个大一个小、一重一轻。
 *
 * ⇒ 改为**一个账号 = 一张自洽的紧凑卡**：外壳仍是 `surfaceContainerLow` +
 *   `VaultixCorner.Panel`（与设置页同一套表面语言，**不另起色相**），内部自己排版；
 *   连接状态做成行尾徽标收进卡内；动作区两枚同规格 M3 按钮（`FilledTonalButton`
 *   主操作 + `OutlinedButton` 破坏性），主次靠填充而非尺寸区分。「添加账号」改用
 *   与卡片同款圆角。⚠️ 详见 [CloudAccountCard] 的 KDoc。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.settings

import androidx.annotation.StringRes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.common.DialogOutlinedActionButton
import io.vaultix.vaultix.remote.CloudAccount
import io.vaultix.vaultix.remote.CloudAccountKind
import io.vaultix.vaultix.ui.common.PrimaryActionButton
import io.vaultix.vaultix.ui.common.rememberFragmentActivity
import io.vaultix.vaultix.ui.theme.Spacing
import io.vaultix.vaultix.ui.theme.UiSize
import io.vaultix.vaultix.ui.theme.VaultixCorner

// ⚠️ 2026-10-19：`BUTTON_HEIGHT`（48dp）与 `INLINE_PROGRESS`（18dp）已删 ——
//   它们随主 CTA 统一进 `PrimaryActionButton`（见 `ui/common/PrimaryActionButton.kt`），
//   留在这里只会让下一个人以为"本页的按钮规格是本地定义的"。

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudAccountsScreen(
    onBack: () -> Unit,
    viewModel: CloudAccountsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    // ⚠️ OneDrive 登录**必须传真实 Activity**：MSAL 要拉起系统授权页，
    //   传 applicationContext 拿不到 Activity 就直接失败。
    val activity = rememberFragmentActivity()
    val focusManager = LocalFocusManager.current

    // ⚠️ 一次性提示（`state.notice` 是资源 id）整个交给 [NoticeSnackbar]：
    //   解析 + 弹出 + 消费三步都在那边，本函数不再持有 `?.let { }` 那几层判空。
    //   这是**为 detekt 复杂度服务的**：本函数此前评 16（上限 14），挪出这段后回到 14。
    NoticeSnackbar(state.notice, snackbarHostState, viewModel::consumeNotice)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cloud_accounts_title)) },
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
                .imePadding()
                .padding(horizontal = Spacing.xl),
        ) {
            Text(
                text = stringResource(R.string.cloud_accounts_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.sm),
            )
            Spacer(Modifier.height(Spacing.lg))

            // ⚠️ 「空」有两态必须分开（本项目的老坑）：还在读 vs 真的一个都没有。
            //    塌进一个 isEmpty() 就是**假空态** —— 用户会以为"我配的账号丢了"。
            when {
                state.loading -> Text(
                    text = stringResource(R.string.cloud_accounts_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                state.accounts.isEmpty() && !state.formVisible -> EmptyHint()

                else -> state.accounts.forEach { account ->
                    // ★ 探测放在 `key()` 里而不是裸写在 `forEach` 中：
                    //   `LaunchedEffect` 是**按调用位置**（而非参数值）与组合树匹配的，
                    //   账号列表一旦增删，后面每一项的"位置"都会前移 —— 裸写在循环里
                    //   会出现"A 的探测跑在 B 的槽位上"。`key(storedId)` 把身份显式绑上。
                    //   ⚠️ 这正是本项目里"列表 + 副作用"的老坑，别图省事省掉 key。
                    key(account.storedId) {
                        CloudAccountCard(
                            account = account,
                            busy = state.busy,
                            issue = state.oneDriveIssues[account.storedId],
                            onRemove = { viewModel.requestRemoval(account) },
                            onReconfigure = { viewModel.startReconfigure(account) },
                            onSignIn = { force ->
                                if (activity == null) {
                                    viewModel.reportMissingActivity()
                                } else {
                                    viewModel.signInOneDrive(activity, forceAccountChooser = force)
                                }
                            },
                        )
                        // 进页面就探一次「已连接但静默刷新可能已被系统掐断」——
                        // 这是 OneDrive 唯一会**悄悄坏掉**的状态（见 probeOneDriveHealth 的 KDoc）。
                        // ⚠️ key 里**只放探测真正依赖的东西**：`connected` 参与判定，
                        //    `oneDriveIssues` 不参与 —— 把后者也放进 key 会让每次探测结果
                        //    反过来重启一次探测（结果 → 重组 → key 变 → 再探），自己咬自己。
                        LaunchedEffect(account.storedId, account.connected) {
                            viewModel.probeOneDriveHealth(account)
                        }
                    }
                    Spacer(Modifier.height(Spacing.md))
                }
            }

            Spacer(Modifier.height(Spacing.md))
            if (state.formVisible) {
                AddAccountForm(
                    state = state,
                    viewModel = viewModel,
                    focusClear = { focusManager.clearFocus() },
                    onSignIn = { force ->
                        // ⚠️ 拿不到 Activity 就**如实报错**，不静默禁按钮
                        //    （那样用户只看到"点了没反应"，既不知原因也不知能做什么）。
                        if (activity == null) {
                            viewModel.reportMissingActivity()
                        } else {
                            viewModel.signInOneDrive(activity, forceAccountChooser = force)
                        }
                    },
                )
            } else {
                OutlinedButton(
                    onClick = viewModel::showAddForm,
                    modifier = Modifier.fillMaxWidth(),
                    // #285：与账号卡同款圆角 —— 改前全宽 pill 和上方卡片是两套形状语言。
                    shape = VaultixCorner.Panel,
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(UiSize.InlineIcon),
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text(stringResource(R.string.cloud_accounts_add))
                }
            }

            state.error?.let { error ->
                Spacer(Modifier.height(Spacing.sm))
                // 两类来源分开解析（见 `CloudError` 的 KDoc）。
                ErrorText(
                    when (error) {
                        is CloudError.Res -> stringResource(error.id)
                        is CloudError.Detail ->
                            stringResource(R.string.cloud_msg_onedrive_failed_detail, error.message)
                    },
                )
            }
            Spacer(Modifier.height(Spacing.xxl))
        }
    }

    // 注销确认：★ 必须写出**影响面** —— 凭据是账号级的，删了同账号所有库都打不开。
    state.pendingRemoval?.let { account ->
        AlertDialog(
            onDismissRequest = viewModel::dismissRemoval,
            title = { Text(stringResource(R.string.cloud_accounts_remove_title)) },
            text = {
                Text(
                    if (account.vaultCount > 0) {
                        stringResource(R.string.cloud_accounts_remove_impact, account.vaultCount)
                    } else {
                        stringResource(R.string.cloud_accounts_remove_no_impact)
                    },
                )
            },
            // ★ 2026-11-10：动作按钮统一走共用组件（#284 订正：规格单点真源；
            //   旧「intrinsic 缩进撑宽 / 逐字竖排」归因已被反编译证伪）。
            confirmButton = {
                DialogOutlinedActionButton(
                    text = stringResource(R.string.cloud_accounts_remove_confirm),
                    onClick = viewModel::confirmRemoval,
                    contentColor = MaterialTheme.colorScheme.error,
                )
            },
            dismissButton = {
                DialogOutlinedActionButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = viewModel::dismissRemoval,
                )
            },
        )
    }
}

// ---------------------------------------------------------------- 账号卡

/**
 * 单个账号卡（#285 第 1 项）：**一个账号 = 一张自洽的紧凑卡片**。
 *
 * 改前套 [SettingsGroupCard] + [SettingsRow] —— 那是设置页「一组多行」的壳，
 * 用到「一个账号一张卡」上会带来 72dp 行高 + 20dp 行内距 + 一整行按钮，真机上是
 * 一块又高又空的深灰板；连接状态还被放到**卡片外**单独一行，「已连接」三个字飘在
 * 卡片与「添加账号」之间，读起来像另一段正文。
 *
 * ⇒ 本轮改为专用紧凑卡：
 * - 外壳仍是 `surfaceContainerLow` + [VaultixCorner.Panel]（与设置页**同一套表面语言**，
 *   不另起色相；OLED 纯黑下这套色阶已由 #285 第 5 项压到中性灰，不再浮出脏灰块）；
 * - 顶部一行：来源图标 + 账号名（单行省略）+ 「来源 · N 个库在用」+ 行尾**状态徽标**；
 * - 断开原因（[issue]）收进卡内、贴着表头；
 * - 动作区两枚**同规格** M3 按钮：主操作 `FilledTonalButton` 占剩余宽，破坏性
 *   `OutlinedButton` 收在右端。不再用 [PrimaryActionButton]（48dp 满宽主 CTA 与
 *   40dp outlined「注销」并排时一个又宽又重、一个又窄又轻），也不再
 *   `Spacer(weight(1f))` 把两枚按钮推到屏幕两端。
 *
 * ⚠️ 状态**会变**（token 过期 / 凭据被清 / 静默刷新被掐断），所以做成行尾徽标而不是
 *    塞进副标题 —— 副标题只说稳定事实（来源 + 被谁在用），与改前那条纪律一致。
 */
@Composable
private fun CloudAccountCard(
    account: CloudAccount,
    busy: Boolean,
    /** 该账号**当前**的断开原因（仅 OneDrive 有；见 `probeOneDriveHealth`）。 */
    issue: String?,
    onRemove: () -> Unit,
    onReconfigure: () -> Unit,
    onSignIn: (forceAccountChooser: Boolean) -> Unit,
) {
    val isOneDrive = account.kind == CloudAccountKind.ONEDRIVE
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = VaultixCorner.Panel,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            AccountCardHeader(account = account, isOneDrive = isOneDrive)
            if (issue != null) {
                AccountIssueLine(issue)
            }
            Spacer(Modifier.height(Spacing.md))
            AccountCardActions(
                account = account,
                isOneDrive = isOneDrive,
                busy = busy,
                onRemove = onRemove,
                onReconfigure = onReconfigure,
                onSignIn = onSignIn,
            )
        }
    }
}

/** 卡片表头：来源图标 + 稳定事实 + **会变**的连接状态（行尾徽标）。 */
@Composable
private fun AccountCardHeader(account: CloudAccount, isOneDrive: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (isOneDrive) Icons.Filled.Cloud else Icons.Filled.Storage,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(UiSize.SecondaryIcon),
        )
        Spacer(Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = account.label,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(account.kind.subtitleRes()) + " · " +
                    stringResource(R.string.cloud_accounts_used_by, account.vaultCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(Spacing.sm))
        ConnectionBadge(connected = account.connected)
    }
}

/**
 * 连接状态徽标：M3 标准 container / on-container 配对。
 *
 * 已连接用 `secondaryContainer`（比 primary 更中性的强调色），未连接用 `errorContainer`；
 * 两者都随主题翻转是**预期**的（状态色不是「不随主题翻转的身份色」，与 #285 第 4 项
 * 剪影卡用 fixed roles 的场景不同），且饱和度由 `VaultixBrandColorTest` 钉住上限。
 */
@Composable
private fun ConnectionBadge(connected: Boolean) {
    Surface(
        shape = VaultixCorner.Pill,
        color = if (connected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.errorContainer
        },
        contentColor = if (connected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onErrorContainer
        },
    ) {
        Text(
            text = stringResource(
                if (connected) {
                    R.string.cloud_accounts_connected
                } else {
                    R.string.cloud_accounts_disconnected
                },
            ),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        )
    }
}

/** 断开原因：贴着表头、收在卡内，不再是一闪而过的 snackbar。 */
@Composable
private fun AccountIssueLine(issue: String) {
    Text(
        text = issue,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = Spacing.sm),
    )
}

/**
 * 动作区：两枚**同规格** M3 按钮（都是 40dp 原生高度）。
 *
 * 主次靠 `FilledTonal` / `Outlined` 的填充区分，不靠尺寸；破坏性动作统一 `error`
 * 内容色（连边框一起，而不是只给 [Text] 上色）。WebDAV 已连接时没有主操作，
 * 用一个 `weight(1f)` 的占位把「移除」仍收在右端，避免按钮跑到左边。
 */
@Composable
private fun AccountCardActions(
    account: CloudAccount,
    isOneDrive: Boolean,
    busy: Boolean,
    onRemove: () -> Unit,
    onReconfigure: () -> Unit,
    onSignIn: (forceAccountChooser: Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val mainModifier = Modifier.weight(1f)
        when {
            isOneDrive -> FilledTonalButton(
                onClick = { onSignIn(account.connected) },
                enabled = !busy,
                modifier = mainModifier,
            ) {
                AccountActionContent(
                    busy = busy,
                    icon = Icons.Filled.Cloud,
                    text = stringResource(
                        if (account.connected) {
                            R.string.cloud_accounts_onedrive_switch
                        } else {
                            R.string.cloud_accounts_onedrive_signin
                        },
                    ),
                )
            }
            !account.connected -> FilledTonalButton(
                onClick = onReconfigure,
                enabled = !busy,
                modifier = mainModifier,
            ) {
                AccountActionContent(
                    busy = false,
                    icon = null,
                    text = stringResource(R.string.cloud_accounts_reconfigure),
                )
            }
            else -> Spacer(mainModifier)
        }
        OutlinedButton(
            onClick = onRemove,
            enabled = !busy,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Text(stringResource(R.string.cloud_accounts_remove))
        }
    }
}

/**
 * 主操作按钮内容：进行中显转圈而不是只把按钮置灰。
 *
 * MSAL 拉系统授权页要等好几秒，**灰按钮和正在跑的按钮长得一样**会被反复点
 * （与 `OneDriveSignInBlock` / `ProbeButton` 同一条纪律）。`icon == null` 时不加
 * 前置 Spacer，让纯文字按钮的文字不无故偏右。
 */
@Composable
private fun AccountActionContent(busy: Boolean, icon: ImageVector?, text: String) {
    if (busy) {
        CircularProgressIndicator(
            modifier = Modifier.size(UiSize.InlineProgress),
            strokeWidth = UiSize.InlineProgressStroke,
        )
        Spacer(Modifier.width(Spacing.sm))
    } else if (icon != null) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(UiSize.InlineIcon),
        )
        Spacer(Modifier.width(Spacing.sm))
    }
    Text(text)
}

@Composable
private fun EmptyHint() {
    Text(
        text = stringResource(R.string.cloud_accounts_empty),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// ---------------------------------------------------------------- 新增账号

/**
 * 新增账号：**选来源 → 填该来源要的东西 → 连接**。
 *
 * WebDAV 有独立的【测试连接】按钮（而不是"填完直接连"）——
 * 用户填错地址是常态，先让他确认一次"这台机器能连上"，比连上之后再报错清楚得多。
 */
@Composable
private fun AddAccountForm(
    state: CloudAccountsViewModel.UiState,
    viewModel: CloudAccountsViewModel,
    focusClear: () -> Unit,
    onSignIn: (forceAccountChooser: Boolean) -> Unit,
) {
    Text(
        text = stringResource(R.string.cloud_accounts_add_title),
        style = MaterialTheme.typography.titleSmall,
    )
    Spacer(Modifier.height(Spacing.sm))
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        CloudAccountKind.entries.forEach { kind ->
            FilterChip(
                selected = kind == state.kind,
                // ★ 2026-11-03（#238）：**切来源前必须先让输入框失去焦点**。
                //
                // 两条分支的输入框形态完全不同 —— WebDAV 有 3 个 `OutlinedTextField`，
                // OneDrive 一个都没有。切换时 WebDAV 那组节点被直接从组合里移除，
                // 而焦点仍留在已被移除的节点上 ⇒ 那一帧的重测量队列里出现了一个
                // 「已被移除、子节点已清空」的 OutlinedTextField ⇒
                // `OutlinedTextFieldMeasurePolicy` 里
                // `measurables.fastFirst { it.layoutId == TextFieldId }` 找不到子节点，
                // 抛 `NoSuchElementException: Collection contains no element matching the predicate`
                // ⇒ 主线程未捕获异常 ⇒ **闪退**（2026-11-03 真机日志实证，荣耀 BKQ-AN00 / SDK 37）。
                //
                // ⚠️ 判据的不对称正说明这是漏做：同一个文件里 WebDAV 的【测试连接】
                // 按钮**本来就**先 `focusClear()` 再干活，chip 这里却直接改状态。
                // ⇒ 别把 `focusClear()` 当成"顺手加的"，它与 `ProbeButton` 是同一条纪律。
                onClick = {
                    focusClear()
                    viewModel.onKindChange(kind)
                },
                label = { Text(stringResource(kind.subtitleRes())) },
            )
        }
    }
    Spacer(Modifier.height(Spacing.md))

    // ★ `key()` 按来源绑定子树身份：不让 Compose 在「3 个输入框」与「纯文字+按钮」
    //   这两条形态完全不同的分支之间复用节点。清焦点治的是"当前这一帧"，
    //   `key` 治的是"节点身份错位"—— 两者是**不同层**的防护，别只留一个。
    if (state.kind == CloudAccountKind.WEBDAV) {
        key(CloudAccountKind.WEBDAV) {
            WebDavFields(state = state, viewModel = viewModel)
            Spacer(Modifier.height(Spacing.md))
            ProbeButton(state = state, onClick = {
                focusClear()
                viewModel.probeWebDav()
            })
            Spacer(Modifier.height(Spacing.sm))
            ProbeStatusCard(state = state)
        }
    } else {
        key(CloudAccountKind.ONEDRIVE) {
            OneDriveSignInBlock(state = state, onSignIn = onSignIn)
        }
    }

    Spacer(Modifier.height(Spacing.sm))
    OutlinedButton(
        onClick = viewModel::hideAddForm,
        modifier = Modifier.fillMaxWidth(),
        // #285：与「添加账号」同款圆角，展开 / 收起时形状不跳。
        shape = VaultixCorner.Panel,
    ) {
        Text(stringResource(R.string.action_cancel))
    }
}

/**
 * OneDrive 来源的内容块：一句说明 + 【登录 Microsoft 账号】。
 *
 * 抽成独立函数是为了让上面 `key(...)` 里只剩"给定来源渲染什么"这一件事 ——
 * 不抽的话，`else` 分支里嵌 `key { }` 会再多两层缩进，
 * 而 `AddAccountForm` 已经在 detekt 的复杂度上限边缘（见 `CloudAccountsScreen` 的旧注释）。
 */
@Composable
private fun OneDriveSignInBlock(
    state: CloudAccountsViewModel.UiState,
    onSignIn: (forceAccountChooser: Boolean) -> Unit,
) {
    Text(
        text = stringResource(R.string.cloud_accounts_onedrive_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(Spacing.md))
    // ★ 与 WebDAV 的【测试连接】保持同一形态：强按钮 + 图标 + **进行中显式可见**。
    //
    // 改前这里是"登录中"唯一的反馈只有 `busy` 把按钮置灰 —— 而 MSAL 要拉起系统
    // 授权页、再等用户选账户，中间可能好几秒。**一个灰按钮和一个正在跑的按钮长得一样**，
    // 用户会以为自己没点上而反复点。
    // （WebDAV 那侧本来就做对了：`ProbeButton` 在 PROBING 时显进度圈。）
    PrimaryActionButton(
        text = stringResource(R.string.cloud_accounts_onedrive_signin),
        onClick = { onSignIn(false) },
        busy = state.busy,
        icon = Icons.Filled.Cloud,
        toned = true,
    )
}

@Composable
private fun WebDavFields(
    state: CloudAccountsViewModel.UiState,
    viewModel: CloudAccountsViewModel,
) {
    OutlinedTextField(
        value = state.serverUrl,
        onValueChange = viewModel::onServerUrlChange,
        label = { Text(stringResource(R.string.add_cloud_server)) },
        supportingText = { Text(stringResource(R.string.add_cloud_server_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    if (state.insecureHttp) {
        // 只提示、不阻拦：局域网 NAS 用 http 是常态，拦下来等于让这批人用不了。
        Text(
            text = stringResource(R.string.add_cloud_insecure_http),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = Spacing.xs),
        )
    }
    Spacer(Modifier.height(Spacing.sm))
    OutlinedTextField(
        value = state.username,
        onValueChange = viewModel::onUsernameChange,
        label = { Text(stringResource(R.string.add_cloud_username)) },
        singleLine = true,
        leadingIcon = {
            Icon(Icons.Filled.Person, contentDescription = null)
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(Spacing.sm))
    OutlinedTextField(
        value = state.password,
        onValueChange = viewModel::onPasswordChange,
        label = { Text(stringResource(R.string.add_cloud_password)) },
        singleLine = true,
        visualTransformation = state.password.visualTransformation(state.passwordVisible),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { viewModel.onPasswordVisibleChange(!state.passwordVisible) }) {
                Icon(
                    imageVector = if (state.passwordVisible) {
                        Icons.Filled.VisibilityOff
                    } else {
                        Icons.Filled.Visibility
                    },
                    contentDescription = null,
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    // ★ #288：真机现场用户填了登录密码 + 没关微信二次验证，服务端回 403，
    //   界面却只说「账号或密码不正确」⇒ 无限重输。这两件事必须在**填之前**说清，
    //   光靠失败文案补救已经晚了一轮。
    Text(
        text = stringResource(R.string.cloud_webdav_password_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.xs),
    )
}

@Composable
private fun ProbeButton(state: CloudAccountsViewModel.UiState, onClick: () -> Unit) {
    // ★ 2026-10-19：原来这里是全项目唯一用 **filled `Button`** 的主 CTA，
    //   而它上面那句注释自己写着「与 WebDAV 的【测试连接】保持同一形态」——
    //   形态对的，规格是散的。统一走 [PrimaryActionButton] 的 `Primary` 档
    //   （"测试连接"确实是这一屏的终点动作，比别处「登录」更该是主色）。
    PrimaryActionButton(
        text = stringResource(R.string.cloud_accounts_test_connection),
        onClick = onClick,
        enabled = state.canProbe,
        busy = state.probe == ProbeState.PROBING,
        toned = false,
    )
}

/**
 * 连接状态卡（4 态）。
 *
 * ⚠️ 刻意不做成 `Boolean`：**"没测过"** 和 **"测过但失败"** 是两件不同的事 ——
 * 前者用户该去测，后者用户该去改地址。塌成一个"连接失败"红字，
 * 会把"你还没测"说成"你配错了"。
 */
@Composable
private fun ProbeStatusCard(state: CloudAccountsViewModel.UiState) {
    if (state.probe == ProbeState.IDLE && state.probeMessage == null) return
    val (text, color) = when (state.probe) {
        ProbeState.IDLE -> stringResource(R.string.cloud_accounts_probe_idle) to
            MaterialTheme.colorScheme.onSurfaceVariant
        ProbeState.PROBING -> stringResource(R.string.cloud_accounts_probe_running) to
            MaterialTheme.colorScheme.onSurfaceVariant
        ProbeState.OK -> stringResource(R.string.cloud_accounts_probe_ok) to
            MaterialTheme.colorScheme.primary
        ProbeState.FAILED -> (state.probeMessage ?: stringResource(R.string.cloud_accounts_probe_failed)) to
            MaterialTheme.colorScheme.error
    }
    Surface(
        shape = VaultixCorner.Small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            modifier = Modifier.padding(Spacing.md),
        )
    }
}

// ---------------------------------------------------------------- 小组件

@Composable
private fun ErrorText(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

private fun CloudAccountKind.subtitleRes(): Int = when (this) {
    CloudAccountKind.ONEDRIVE -> R.string.cloud_accounts_kind_onedrive
    CloudAccountKind.WEBDAV -> R.string.cloud_accounts_kind_webdav
}

private fun String.visualTransformation(visible: Boolean): VisualTransformation =
    if (visible) VisualTransformation.None else PasswordVisualTransformation()

/**
 * 把一条一次性提示（资源 id）弹成 snackbar，弹完通知 ViewModel 消费掉。
 *
 * ⚠️ 解析点必须在**组合里**：`notice` 现在存的是资源 id（审计 §3.4），
 *   而 `stringResource` 只能在可组合作用域调用。所以这里同时承担"解析"这件事，
 *   而不是像常见写法那样把字符串传进来。
 *
 * ⚠️ `LaunchedEffect` 的 key 用**解析后的字符串**：id 相同时若 `values-xx`
 *   换掉了那条资源，用 id 当 key 不会重弹；用字符串则两种变化都能触发。
 *   这里的语义本来就是"显示这一句话"，用字符串更贴。
 */
@Composable
private fun NoticeSnackbar(
    @StringRes noticeId: Int?,
    hostState: SnackbarHostState,
    onConsumed: () -> Unit,
) {
    val noticeText = noticeId?.let { stringResource(it) }
    LaunchedEffect(noticeText) {
        if (noticeText != null) {
            hostState.showSnackbar(noticeText)
            onConsumed()
        }
    }
}
