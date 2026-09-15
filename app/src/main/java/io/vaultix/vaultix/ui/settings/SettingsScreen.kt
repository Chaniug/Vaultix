package io.vaultix.vaultix.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import io.vaultix.datastore.VaultTimeout
import io.vaultix.domain.PIN_MIN_LENGTH
import io.vaultix.model.VaultKind
import io.vaultix.model.VaultSummary
import io.vaultix.vaultix.BuildConfig
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.common.BiometricPrompter
import io.vaultix.vaultix.ui.common.DialogActions
import io.vaultix.vaultix.ui.common.DialogBackButton
import io.vaultix.vaultix.ui.common.DialogDismissButton
import io.vaultix.vaultix.ui.common.DialogEmptyBody
import io.vaultix.vaultix.ui.common.DialogFootnote
import io.vaultix.vaultix.ui.common.DialogHeader
import io.vaultix.vaultix.ui.common.DialogSectionTitle
import io.vaultix.vaultix.ui.common.DialogSurface
import io.vaultix.vaultix.ui.items.DisplayOptionsSheet
import io.vaultix.vaultix.ui.common.VaultixExpressiveTopBar
import io.vaultix.vaultix.ui.common.rememberImmersiveBarPadding
import io.vaultix.vaultix.ui.common.rememberScrollCollapseFraction
import io.vaultix.vaultix.ui.common.TrashAutoDeleteDialog
import io.vaultix.vaultix.ui.common.rememberFragmentActivity
import io.vaultix.vaultix.ui.common.trashAutoDeleteLabel
import io.vaultix.vaultix.ui.theme.ThemeMode
import io.vaultix.vaultix.ui.theme.Spacing

/**
 * 设置页。**5 组**（原 7 组），自上而下：
 *
 * | 组 | 内容 |
 * |---|---|
 * | 密码库 | 当前密码库 · 添加密码库 |
 * | 解锁与隐私 | 快速解锁 · 自动锁定 · 防截屏 · 剪贴板清除 |
 * | 显示与填充 | 主题模式 · 动态取色 · 纯黑背景 · 条目显示 · 自动填充设置 |
 * | 数据 | 导入与导出 · 回收站清理 · **退出数据库**（error 色，置底） |
 * | 关于 | 权限管理 · 版本 · 源码与反馈 · 开源许可 |
 *
 * 组内顺序原则：入口类在前、开关类居中、**破坏性动作置底**。
 * 结构与文案的来龙去脉见 `.ai/decisions/设置页信息架构-定稿.md`（**别重新设计分组**）。
 *
 * 行与选择器交互范式参考 Bastion 设置页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAutofillSettings: () -> Unit,
    /** 「数据」分区：导入 / 导出加密备份（导航到二级页）。 */
    onOpenImportExport: () -> Unit = {},
    /** 主界面 Tab 内嵌模式：隐藏返回键（无上层可返回）。 */
    embedded: Boolean = false,
    /** 底部叠层悬浮栏占用的高度（宿主给；非内嵌时为 0）——内容要留出它，否则末项被压住。 */
    bottomInset: Dp = 0.dp,
    /**
     * 「密码库」分区：进入**密码库管理**二级页。
     *
     * ⚠️ 选库 / 加库 / 配解锁方式现在都在那一页（2026-09-15 用户要求把同属库管理的
     * 三件事放到一起）。这里只留一个入口 —— 首页的职责是分流，不是干活。
     */
    onOpenVaultManagement: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showAutoLockDialog by rememberSaveable { mutableStateOf(false) }
    var showClipboardDialog by rememberSaveable { mutableStateOf(false) }
    var showAboutDialog by rememberSaveable { mutableStateOf(false) }
    var showExitDatabaseDialog by rememberSaveable { mutableStateOf(false) }

    // 沉浸式顶栏：大标题随滚动缩小、状态栏区域由顶栏背景覆盖（对齐 Bastion）。
    val scrollState = rememberScrollState()
    val collapse = rememberScrollCollapseFraction(scrollState)
    val barPadding = rememberImmersiveBarPadding(collapse)

    Scaffold(
        // 顶栏浮在内容之上：Scaffold 不再为它预留高度。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        ) {
            // 顶部让位用**可滚动的 Spacer**，不用外层 `padding(top = barPadding)`：
            // 后者会把滚动视口整体下压 ⇒ 内容永远画不到顶栏区域，收起顶栏后
            // 顶栏下方留一条死区（「不沉浸」）；Spacer 会随内容一起滚走。
            Spacer(modifier = Modifier.height(barPadding))
            // ---- 密码库（活跃库 = 全局单一真源，详见 VaultSection 的 KDoc）----
            // 只剩一个入口：选库 / 加库 / 配解锁方式都在 VaultManagementScreen（二级页）。
            VaultSection(
                viewModel = viewModel,
                onOpenVaultManagement = onOpenVaultManagement,
            )

            // ---- 解锁与隐私（拆出去守住 detekt LongMethod ≤150）----
            // ⚠️ 「快速解锁」已移到「密码库管理」二级页（它按库登记，属于库而不属于隐私）；
            //    「退出数据库」是数据动作，已移到「数据」组置底（定稿 §2①）。
            UnlockPrivacySection(
                viewModel = viewModel,
                state = state,
                onAutoLock = { showAutoLockDialog = true },
                onClipboardClear = { showClipboardDialog = true },
            )

            // ---- 显示与填充（自动填充入口已并入，原单行组）----
            DisplaySection(
                viewModel = viewModel,
                dynamicColor = state.dynamicColor,
                onOpenAutofillSettings = onOpenAutofillSettings,
            )

            // ---- 数据（导入导出 / 回收站清理 / 退出数据库）----
            DataSection(
                viewModel = viewModel,
                onOpenImportExport = onOpenImportExport,
                onExitDatabase = { showExitDatabaseDialog = true },
            )

            // ---- 关于（权限管理已并入，原「其他」组仅此一行）----
            AboutSection(
                context = context,
                // ⚠️ **只显示 versionName，不拼 versionCode**（2026-09-14 用户报告）。
                // 原先拼成 `0.3.0 (3000)` / 旧版本是 `… (1)`，那个括号长得很像浏览器给
                // 重复下载加的后缀，用户会以为「下载的文件名带了 (1)，装完版本号里也有 (1)」
                // —— 两者其实毫无关系（前者是下载器加的，后者是 versionCode）。
                // 而且 versionName 对 debug 包已带短 sha（`0.3.0-dev-abc1234`），
                // 定位到具体代码绰绰有余；versionCode 是给系统判断新旧的，不是构建计数器，
                // 摆在界面上只会再次引起同样的误读。
                versionName = BuildConfig.VERSION_NAME,
                onShowLicense = { showAboutDialog = true },
            )
            Spacer(Modifier.height(Spacing.xxl))
            // 底部留出叠层悬浮底栏的高度（顶部的让位见上方 Spacer(barPadding)）：
            // 底栏改成叠层后内容铺到屏幕底，最后一项不再被胶囊压住。
            Spacer(Modifier.height(bottomInset))
        }
            SettingsTopBar(
                collapseFraction = collapse,
                embedded = embedded,
                onBack = onBack,
            )
        }
    }

    if (showAutoLockDialog) {
        AutoLockDialog(
            current = state.vaultTimeout,
            onSelect = {
                viewModel.setVaultTimeout(it)
                showAutoLockDialog = false
            },
            onDismiss = { showAutoLockDialog = false },
        )
    }
    if (showClipboardDialog) {
        ClipboardClearDialog(
            currentMs = state.clipboardClearMs,
            onSelect = {
                viewModel.setClipboardClearMs(it)
                showClipboardDialog = false
            },
            onDismiss = { showClipboardDialog = false },
        )
    }
    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text(stringResource(R.string.about_license)) },
            text = { Text(stringResource(R.string.about_license_body)) },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text(stringResource(R.string.action_back))
                }
            },
        )
    }
    if (showExitDatabaseDialog) {
        ExitDatabaseDialog(
            onConfirm = {
                showExitDatabaseDialog = false
                viewModel.exitDatabase()
            },
            onDismiss = { showExitDatabaseDialog = false },
        )
    }
}

/**
 * 设置页顶栏（沉浸式：大标题随滚动缩小，状态栏区域由顶栏背景覆盖）。
 *
 * 抽成独立 composable 是为了让 [SettingsScreen] 主函数守住 detekt `LongMethod`（≤150 行）：
 * 返回键分支内联在宿主里要占十几行，而它与「分组内容」完全无关。
 *
 * ⚠️ 必须声明为 [BoxScope] 扩展：`Modifier.align(TopCenter)` 只在 Box 作用域内可用 ——
 * 顶栏是**叠在内容之上**的，Scaffold 不为它预留高度（见宿主的 `contentWindowInsets`）。
 *
 * @param embedded 主界面 Tab 内嵌模式：无上层可返回 ⇒ 不画返回键。
 */
@Composable
private fun BoxScope.SettingsTopBar(
    collapseFraction: Float,
    embedded: Boolean,
    onBack: () -> Unit,
) {
    VaultixExpressiveTopBar(
        title = stringResource(R.string.settings_title),
        collapseFraction = collapseFraction,
        modifier = Modifier.align(Alignment.TopCenter),
        navigationIcon = if (embedded) {
            null
        } else {
            {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
            }
        },
    )
}

/**
 * 「退出数据库」确认对话框。
 *
 * ⚠️ **必须有**：这一步会丢掉本地未上传的改动（待推送队列属本地缓存），
 * 一个没有确认的破坏性动作是不可接受的（用户可能刚离线编辑了十条条目）。
 * 文案把三件事说清：清什么（本地缓存）、不碰什么（远程 / 库本身）、丢什么（未上传改动）。
 */
@Composable
private fun ExitDatabaseDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Logout, contentDescription = null) },
        title = { Text(stringResource(R.string.setting_exit_database)) },
        text = { Text(stringResource(R.string.setting_exit_database_confirm)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.setting_exit_database_action),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/**
 * 解锁与隐私组（原「安全」）：自动锁定 / 防截屏 / 剪贴板清除。
 *
 * ⚠️ **「快速解锁」已从本组移出**（2026-09-15 用户要求把库管理三件事放一起）：
 * 它为**每个库**单独登记一条解锁凭据（`quickUnlockVaults` 就是"每库一行"的形态），
 * 语义上属于"库怎么打开"，属于库管理；夹在自动锁定与防截屏之间，用户要改
 * "某个库怎么解锁"得先想到来隐私组找。现落在 [VaultManagementScreen]（定稿 §10）。
 *
 * ⚠️ 「退出数据库」**也曾从本组移出** —— 它是清空本地缓存的**数据动作**，
 * 现落在「数据」组末尾（定稿 §2① / §3）。
 *
 * 组内顺序按「档位选择在前、开关在后」：自动锁定与剪贴板清除是档位，防截屏是开关。
 *
 * 拆成独立 composable 纯粹是为了让 [SettingsScreen] 主函数守住 detekt `LongMethod`
 * （≤150 行）——各对话框的显示状态仍留在宿主里，本函数只收回调。
 */
@Composable
private fun UnlockPrivacySection(
    viewModel: SettingsViewModel,
    state: SettingsViewModel.UiState,
    onAutoLock: () -> Unit,
    onClipboardClear: () -> Unit,
) {
    SettingsGroupTitle(stringResource(R.string.group_security))
    SettingsRow(
        icon = { Icon(Icons.Filled.Timer, contentDescription = null) },
        title = stringResource(R.string.setting_auto_lock),
        subtitle = vaultTimeoutLabel(state.vaultTimeout),
        onClick = onAutoLock,
    )
    SettingsRow(
        icon = { Icon(Icons.Filled.Shield, contentDescription = null) },
        title = stringResource(R.string.setting_screen_security),
        subtitle = stringResource(R.string.setting_screen_security_desc),
        trailing = {
            // ⚠️ 值未到达（null）时**禁用**开关：布尔开关没有「不确定」的视觉，
            // 与其拿默认值 true 冒充用户设置（那会先显示「开」再跳到「关」，
            // 2026-09-14 真机报告），不如短暂禁用 —— 禁用的灰开关传达的是
            // 「还没准备好」，而不是一个假答案。绝大多数情况下一帧内就到位。
            Switch(
                checked = state.screenSecurity ?: false,
                onCheckedChange = viewModel::setScreenSecurity,
                enabled = state.screenSecurity != null,
            )
        },
    )
    SettingsRow(
        icon = { Icon(Icons.Filled.VisibilityOff, contentDescription = null) },
        title = stringResource(R.string.setting_clipboard_clear),
        subtitle = clipboardClearLabel(state.clipboardClearMs),
        onClick = onClipboardClear,
    )
}

/** 关于组（含权限管理；同上：拆出去是为了满足函数长度门禁）。 */
@Composable
private fun AboutSection(
    context: Context,
    versionName: String,
    onShowLicense: () -> Unit,
) {
    SettingsGroupTitle(stringResource(R.string.group_about))
    // 权限管理原属「其他」组（该组仅此一行，独占一个组标题）
    // ⇒ 并入「关于」：它本来就是"关于这个 App"的元信息（定稿 §2②）。
    SettingsRow(
        icon = { Icon(Icons.Filled.Policy, contentDescription = null) },
        title = stringResource(R.string.permission_management_title),
        subtitle = stringResource(R.string.permission_management_subtitle),
        onClick = { openAppPermissionSettings(context) },
    )
    SettingsRow(
        icon = { Icon(Icons.Filled.Info, contentDescription = null) },
        title = stringResource(R.string.about_version),
        subtitle = versionName,
    )
    SettingsRow(
        icon = { Icon(Icons.Filled.Security, contentDescription = null) },
        title = stringResource(R.string.about_source),
        subtitle = stringResource(R.string.about_github_url),
        onClick = {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(context.getString(R.string.about_github_url))),
            )
        },
    )
    SettingsRow(
        icon = { Icon(Icons.Filled.Info, contentDescription = null) },
        title = stringResource(R.string.about_license),
        onClick = onShowLicense,
    )
}

/**
 * 「密码库」分组：**一个入口**，通向 [VaultManagementScreen]。
 *
 * 为什么把入口留在设置首页而不是把库管理内容摊在这里：首页的职责是"分流"，
 * 不是"干活"。改之前这一组有「当前密码库」与「添加密码库」两行，而「快速解锁」
 * 却挂在「解锁与隐私」组 —— 三件同属库管理的事散在两处，用户要改"某个库怎么解锁"
 * 得先想到去隐私组找（2026-09-15 用户反馈）。
 *
 * 现在首页只回答"去哪管库"，具体动作（选库 / 加库 / 配解锁方式）都在二级页里，
 * 与「自动填充设置」「导入与导出」的层级保持一致。
 *
 * ⚠️ **不要**把二级页的对话框再搬回这里：`ActiveVaultDialog` /
 * `QuickUnlockManageDialog` 会各自拉起一串状态（KDBX 待验证 id、PIN 对话框步骤…），
 * 正好是当初为守 detekt `LongMethod` 才拆出去的东西，搬回来必然顶线。
 *
 * 为什么入口在设置页：Bastion 的多库是主界面里的一个**筛选维度**（`UnifiedCategoryFilterSelection`
 * 把库与文件夹 / 分类平级），而 Vaultix 是「登录时二选一」的**单活跃库**语义 —— 主界面
 * 不该出现库的概念，于是把多库入口整体下沉到设置页
 * （Docs/progress/main-shell-migration.md §0 与 A4）。
 *
 * 切换的影响面：主界面各 Tab、autofill 候选、Credential Provider 候选、保存回写目标
 * **同时**切到新库 —— 它们都只读 [io.vaultix.vaultix.data.ActiveVaultStore]，没有第二份状态。
 */
@Composable
private fun VaultSection(
    viewModel: SettingsViewModel,
    onOpenVaultManagement: () -> Unit,
) {
    val active by viewModel.activeVault.collectAsStateWithLifecycle()
    SettingsGroupTitle(stringResource(R.string.group_vaults))
    SettingsRow(
        icon = { Icon(Icons.Filled.Storage, contentDescription = null) },
        title = stringResource(R.string.vault_management_entry),
        // 副标题直接给**当前库名**：用户最常问的就是"我现在看的是哪个库"，
        // 放在这里就不必为了确认这件事再点进二级页。
        subtitle = active?.name ?: stringResource(R.string.settings_active_vault_none),
        onClick = onOpenVaultManagement,
    )
}

/**
 * 活跃库选择器。**列出全部库**（含未解锁）。
 *
 * ⚠️ 2026-09-14（issue #96）：原先只列已解锁库 ⇒ 未解锁的 KDBX 不在列表里，
 * 用户「找不到我的库」而以为库丢了。现在全部列出、未解锁项如实标注
 * 「未解锁 · 需先输入主密码」——**「找得到」优先于「点得动」**。
 *
 * ⚠️ 2026-09-15（用户报的空白页 bug）：**未解锁项不能「切过去」**。
 * 切过去后条目流为空（Bitwarden 无密钥 / KDBX 会话不在内存），条目页与验证码页
 * 会显示成「还没有保存的密码」——用户看到的就是**全白**；更糟的是「切库即锁旧库」
 * 会把原来能看的库一并锁掉，两个库都进不去。故未解锁项的点击语义 = **去解锁页**
 * （由 [onSelect] 的分支与宿主的 `onOpenLockedVault` 接线共同保证）。
 *
 * 两项动作分开（这是「活跃库 / 默认库」两键拆分的 UI 形态）：
 * - 点行 = 已解锁 → 切换**本次会话**看哪个；未解锁 → 去解锁页；
 * - 「设为默认」= 改**冷启动先开哪个**（唯一写入点，**不要求当下解锁**）。
 */
/**
 * 当前库选择（2026-09-15 重做）。
 *
 * ## 为什么换掉 `AlertDialog`
 *
 * 用户真机反馈「这个页面好简陋」。原来的 `AlertDialog` 有两个硬伤：
 * 1. **宽度被压到约屏宽 7 成**，而每行要放「库名 + 状态 + 设为默认」三件事 ⇒ 全挤在一起；
 * 2. `AlertDialog` 的 `text` 槽**不滚动**，库一多（>4 个）底部直接被截断、点不到。
 *
 * 改用 [BasicAlertDialog]（M3 里 `AlertDialog` 的自定义容器版本）：外壳仍由我们控制，
 * 但可以把 `surface` 撑到 0.92×0.8 屏，并给内部一个**可滚动的列表区**。
 * **注意**：不是换成底部弹层 —— 全项目 25 处弹窗都用弹窗形态，只为这两处改成
 * 弹层会立刻显得"这不是同一个 App"（见 `.ai` 的形态一致性约定）。
 *
 * ## 文案精简
 *
 * 底部那段 `settings_default_vault_hint`（「勾选表示本次使用该库；「设为默认」决定…」）
 * 是一段 60 余字的说明，且含"勾选"这种**指路语**（定稿 §4 明令禁止）。
 * 现改为一句话副标题，把交互含义收进各行副标题里（见 [VaultChoiceRow]）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActiveVaultDialog(
    vaults: List<VaultSummary>,
    activeId: String?,
    defaultId: String?,
    onSelect: (VaultSummary) -> Unit,
    onSetDefault: (VaultSummary) -> Unit,
    onDismiss: () -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        DialogSurface {
            DialogHeader(title = stringResource(R.string.settings_active_vault))

            if (vaults.isEmpty()) {
                DialogEmptyBody(stringResource(R.string.settings_active_vault_locked_hint))
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.lg),
                ) {
                    vaults.forEach { vault ->
                        VaultChoiceRow(
                            name = vault.name,
                            locked = !vault.unlocked,
                            selected = vault.id == activeId,
                            isDefault = vault.id == defaultId,
                            onClick = { onSelect(vault) },
                            onSetDefault = { onSetDefault(vault) },
                        )
                    }
                }
                DialogFootnote(stringResource(R.string.settings_default_vault_hint))
            }

            DialogActions {
                DialogDismissButton(onDismiss)
            }
        }
    }
}

/**
 * 库选择器里的一行（2026-09-15 按 M3 卡片规格重做，与 [SettingsRow] 同族）。
 *
 * ## 为什么改
 *
 * 用户真机反馈「当前密码库这个页面好简陋」。旧实现是**裸 `Row` + 裸 `TextButton`**：
 * 没有卡片承载、没有选中底色、行高不等、右侧「设为默认」是长文案按钮把整行撑歪。
 * 而同一页的设置项卡片（[SettingsRow]）却是 20dp 圆角 + 72dp 最小高 + primary 图标 ——
 * 落差就是「简陋」的来源。
 *
 * ## 改法（对齐项目既有规格，不发明新视觉）
 *
 * - 整行包进 **20dp 圆角 Card**，选中态用 `secondaryContainer` 底色（同 [EntryCard] 选中语义）；
 * - 左侧**图标槽**（28dp）+ 库名 + 状态副标题（两行结构，同 [SettingsRow]）；
 * - 选中标记从 `RadioButton` 改为 **`Check`**（同 `DisplayOptionsSheet.OptionRow`）——
 *   对话框里 `RadioButton` 自带一圈很大，会把两行文字挤窄；
 * - 「设为默认」从长文案 `TextButton` 改为 **星形 `IconButton`**：
 *   已是默认 → `Star` 实心 primary；否则 → `StarBorder` 轮廓可点。
 *   这样右侧列宽固定，不再因文案长短抖动（原来「设为默认」四个字要占近半行宽）。
 *
 * ## ⚠️ 保留的既有告诫
 *
 * **未解锁项不得用选中标记**：旧实现给它一个方向箭头（`KeyboardArrowRight`）表示
 * 「点了是去解锁，不是切过去」。这个语义是对的 —— 若给未解锁项画上 `Check`/单选圈，
 * 用户会看到"已选中但内容空白"，正是 2026-09-15 修掉的那个 bug 的观感。
 */
/** 库选择行圆角（与 [SettingsRow] 同族，略小以体现"行内行"）。 */
private val VAULT_ROW_CORNER = 20.dp

/** 库选择行最小高度（两行文本 + 图标，比 [SettingsRow] 的 72dp 略矮，对话框空间紧）。 */
private val VAULT_ROW_MIN_HEIGHT = 64.dp

/** 库选择行图标槽（28dp，与 [SettingsRow] 一致）。 */
private val VAULT_ICON_BOX = 28.dp

@Composable
internal fun VaultChoiceRow(
    name: String,
    locked: Boolean,
    selected: Boolean,
    isDefault: Boolean,
    onClick: () -> Unit,
    onSetDefault: () -> Unit,
) {
    val container = when {
        selected -> MaterialTheme.colorScheme.secondaryContainer
        // ⚠️ 未选中态必须比 DialogSurface 的面板底（surfaceContainerHigh）**低**一档，
        // 否则同色相叠、卡片边界消失（与 [UnlockOptionRow] 同一个病，2026-09-15）。
        else -> MaterialTheme.colorScheme.surfaceContainerLowest
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs)
            .clickable(onClick = onClick, role = Role.Button),
        shape = RoundedCornerShape(VAULT_ROW_CORNER),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = VAULT_ROW_MIN_HEIGHT)
                .padding(start = Spacing.lg, end = Spacing.sm, top = Spacing.md, bottom = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 图标槽（28dp）：未解锁给箭头（去解锁），已解锁给「库」图标。
            Box(
                modifier = Modifier.size(VAULT_ICON_BOX),
                contentAlignment = Alignment.Center,
            ) {
                if (locked) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Storage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(Modifier.width(Spacing.md))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (locked) {
                        stringResource(R.string.settings_vault_locked_tap_to_unlock)
                    } else {
                        stringResource(R.string.settings_vault_unlocked_hint)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // 选中标记（未解锁项不画 —— 见 KDoc 里的告诫）。
            if (selected && !locked) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = Spacing.sm),
                )
            }

            // 「设为默认」：星形开关。已是默认时不可点（再点无意义）。
            IconButton(
                onClick = onSetDefault,
                enabled = !isDefault,
            ) {
                Icon(
                    imageVector = if (isDefault) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = stringResource(
                        if (isDefault) {
                            R.string.settings_vault_default_badge
                        } else {
                            R.string.settings_vault_set_default
                        },
                    ),
                    tint = if (isDefault) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

/**
 * 显示与填充组（原「外观」+ 原「自动填充」入口，批次④对齐 Bastion 主题能力）：
 * 主题模式三态 + 动态取色 + 纯黑背景 + 条目显示 + 自动填充设置入口。
 *
 * 合并理由：这两类都是「**界面怎么呈现 / 怎么替你填**」，而「自动填充」原先只有
 * 一个二级页入口却独占一个组标题 —— 合并后每组 ≥2 行，消灭了两个语义重叠的小组
 * （定稿 §2③ / §3）。
 *
 * 状态在 ViewModel 单独流上（不进 [SettingsViewModel.UiState]，避免六流 combine 的
 * Array 转型噪音）；切换主题立即生效（MainActivity 收集）。
 */
@Composable
private fun DisplaySection(
    viewModel: SettingsViewModel,
    /** null = 偏好尚未读出（见 [SettingsViewModel.UiState.dynamicColor]）。 */
    dynamicColor: Boolean?,
    onOpenAutofillSettings: () -> Unit,
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val oledPureBlack by viewModel.oledPureBlack.collectAsStateWithLifecycle()
    var showThemeDialog by rememberSaveable { mutableStateOf(false) }
    var showDisplayOptions by rememberSaveable { mutableStateOf(false) }

    SettingsGroupTitle(stringResource(R.string.group_appearance))
    SettingsRow(
        icon = { Icon(Icons.Filled.DarkMode, contentDescription = null) },
        title = stringResource(R.string.setting_theme_mode),
        subtitle = themeModeLabel(ThemeMode.from(themeMode)),
        onClick = { showThemeDialog = true },
    )
    SettingsRow(
        icon = { Icon(Icons.Filled.Palette, contentDescription = null) },
        title = stringResource(R.string.setting_dynamic_color),
        subtitle = stringResource(R.string.setting_dynamic_color_desc),
        trailing = {
            // 同「防截屏」：值未到达时禁用，不用默认值冒充用户设置
            Switch(
                checked = dynamicColor ?: false,
                onCheckedChange = viewModel::setDynamicColor,
                enabled = dynamicColor != null,
            )
        },
    )
    SettingsRow(
        icon = { Icon(Icons.Filled.Contrast, contentDescription = null) },
        title = stringResource(R.string.setting_oled_pure_black),
        subtitle = stringResource(R.string.setting_oled_pure_black_desc),
        trailing = {
            Switch(
                checked = oledPureBlack,
                onCheckedChange = viewModel::setOledPureBlack,
            )
        },
    )
    // 条目列表的显示选项（分组方式 / 卡片信息密度 / 是否显示图标）——
    // 与密码 Tab 顶栏那个按钮共用同一个弹层与同一份偏好，改哪边都实时生效。
    SettingsRow(
        icon = { Icon(Icons.Filled.ViewAgenda, contentDescription = null) },
        title = stringResource(R.string.items_display_options),
        subtitle = stringResource(R.string.setting_display_options_desc),
        onClick = { showDisplayOptions = true },
    )
    // 自动填充入口（二级页）—— 原先是只有一行的独立分组，现并入本组末尾。
    SettingsRow(
        icon = { Icon(Icons.Filled.Password, contentDescription = null) },
        title = stringResource(R.string.autofill_settings_entry),
        subtitle = stringResource(R.string.autofill_settings_entry_desc),
        onClick = onOpenAutofillSettings,
    )

    if (showDisplayOptions) {
        SettingsDisplayOptionsHost(
            viewModel = viewModel,
            onDismiss = { showDisplayOptions = false },
        )
    }

    if (showThemeDialog) {
        ThemeModeDialog(
            current = ThemeMode.from(themeMode),
            onSelect = {
                viewModel.setThemeMode(it.name.lowercase())
                showThemeDialog = false
            },
            onDismiss = { showThemeDialog = false },
        )
    }
}

/**
 * 设置页里的「显示选项」宿主：把 `SettingsViewModel` 的偏好接进
 * [DisplayOptionsSheet]（与密码 Tab 顶栏按钮同一个弹层，改哪边都一致）。
 */
@Composable
private fun SettingsDisplayOptionsHost(
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit,
) {
    val groupMode by viewModel.itemsGroupMode.collectAsStateWithLifecycle()
    val cardDisplayMode by viewModel.itemsCardDisplayMode.collectAsStateWithLifecycle()
    val showIcon by viewModel.itemsShowIcon.collectAsStateWithLifecycle()
    DisplayOptionsSheet(
        groupMode = groupMode,
        cardDisplayMode = cardDisplayMode,
        showIcon = showIcon,
        onDismiss = onDismiss,
        onGroupMode = viewModel::setItemsGroupMode,
        onCardDisplayMode = viewModel::setItemsCardDisplayMode,
        onShowIcon = viewModel::setItemsShowIcon,
    )
}

/**
 * 数据组（批次④）：导入与导出 / 回收站清理 / **退出数据库**。
 *
 * 顺序原则（定稿 §3）：入口类在前、档位选择居中、**破坏性动作置底**。
 * 「退出数据库」是本页**唯一不可逆**的动作，置底 + `error` 色，避免误触
 * ——它原先夹在「解锁与隐私」的常用开关之间（定稿 §2①）。
 *
 * 「导入与导出」入口对齐 Bitwarden 官方「设置 → 导出密码库 / 导入数据」：
 * 导出 = 加密 JSON 备份，导入 = 从备份增量恢复。回收站清理档位与回收站页顶栏入口
 * 共用 [TrashAutoDeleteDialog] 与同一偏好键，改哪边都实时生效。
 */
@Composable
private fun DataSection(
    viewModel: SettingsViewModel,
    onOpenImportExport: () -> Unit,
    onExitDatabase: () -> Unit,
) {
    val trashDays by viewModel.trashAutoDeleteDays.collectAsStateWithLifecycle()
    var showTrashDialog by rememberSaveable { mutableStateOf(false) }

    SettingsGroupTitle(stringResource(R.string.group_data))
    SettingsRow(
        icon = { Icon(Icons.Filled.SwapVert, contentDescription = null) },
        title = stringResource(R.string.import_export_entry),
        subtitle = stringResource(R.string.import_export_entry_desc),
        onClick = onOpenImportExport,
    )
    SettingsRow(
        icon = { Icon(Icons.Filled.DeleteSweep, contentDescription = null) },
        title = stringResource(R.string.setting_trash_auto_delete),
        subtitle = trashAutoDeleteLabel(trashDays),
        onClick = { showTrashDialog = true },
    )
    SettingsRow(
        icon = { Icon(Icons.Filled.Logout, contentDescription = null) },
        title = stringResource(R.string.setting_exit_database),
        subtitle = stringResource(R.string.setting_exit_database_desc),
        titleColor = MaterialTheme.colorScheme.error,
        onClick = onExitDatabase,
    )

    if (showTrashDialog) {
        TrashAutoDeleteDialog(
            currentDays = trashDays,
            onSelect = viewModel::setTrashAutoDeleteDays,
            onDismiss = { showTrashDialog = false },
        )
    }
}

/** 系统应用信息页（权限管理）：唯一能改运行时权限的入口，系统不提供应用内开关。 */
private fun openAppPermissionSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            ),
        )
    }
}


/** 单选对话框：主题模式三态（跟随系统 / 浅色 / 深色）。 */
@Composable
private fun ThemeModeDialog(
    current: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_theme_mode)) },
        text = {
            Column {
                ThemeMode.entries.forEach { mode ->
                    SingleChoiceRow(
                        label = themeModeLabel(mode),
                        selected = mode == current,
                        onClick = { onSelect(mode) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun themeModeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> stringResource(R.string.theme_mode_system)
    ThemeMode.LIGHT -> stringResource(R.string.theme_mode_light)
    ThemeMode.DARK -> stringResource(R.string.theme_mode_dark)
}

/**
 * 快速解锁「启用」的认证副作用收集器。
 *
 * 收到 [SettingsViewModel.Event.PromptForEnroll]（已 init 的 ENCRYPT cipher）即弹
 * BiometricPrompt；认证通过后用本次 cipher 包裹密钥并落盘，对话框内状态随之翻为
 * 「已启用」。取消/失败什么都不做 —— 维持「未启用」，用户可再试。
 *
 * 另一条事件 [SettingsViewModel.Event.PromptForKdbxPassword] 只把「哪个库在等密码」
 * 交给调用方（KDBX 必须先要主密码，定稿 §4.5）。
 *
 * 独立成 composable 而非内联在 [SettingsScreen]，一是避免后者超长（detekt LongMethod），
 * 二是把「认证副作用」与「页面布局」解耦。
 */
@Composable
internal fun QuickUnlockEnrollEffect(
    viewModel: SettingsViewModel,
    onPromptKdbxPassword: (String) -> Unit,
    onEnrollReady: () -> Unit,
) {
    val activity = rememberFragmentActivity()
    val enrollTitle = stringResource(R.string.quick_unlock_enroll_title)
    val cancelText = stringResource(R.string.action_cancel)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsViewModel.Event.PromptForEnroll -> {
                    // 指纹框已弹出 ⇒ KDBX 密码框使命完成，收起（避免叠两层对话框）。
                    onEnrollReady()
                    val host = activity ?: return@collect
                    BiometricPrompter(host).authenticate(
                        cipher = event.cipher,
                        title = enrollTitle,
                        cancelText = cancelText,
                        onSuccess = { cipher -> viewModel.enrollWithCipher(event.vaultId, cipher) },
                        // 取消/失败：维持「未启用」，可再试。
                        // ⚠️ 顺手丢弃 KDBX 的暂存凭据 —— 指纹没弹成，那份主密码明文
                        // 就没有理由继续留在内存里（Bitwarden 侧无暂存，是空操作）。
                        onError = { _, _, _ -> viewModel.discardPendingKdbxEnroll() },
                    )
                }
                is SettingsViewModel.Event.PromptForKdbxPassword -> {
                    onPromptKdbxPassword(event.vaultId)
                }
            }
        }
    }
}

/**
 * 快速解锁「**生效范围**」：列出全部库，逐个决定这把指纹钥匙管不管它们。
 *
 * 心智模型（用户提出、定稿 §4.7 采纳）：**用户只有一把 Keystore 密钥**，
 * 所以「快速解锁是一个能力，作用于哪些库由用户勾选」——勾选即决定
 * 「这把钥匙串上挂几把钥匙」。
 *
 * ⚠️ 两条登记流程**不同**，故 UI 也要区分（[QuickUnlockVaultUi.kind]）：
 * - Bitwarden：勾选 → 直接弹指纹（密钥在会话里）；
 * - KDBX：勾选 → **先弹主密码输入框** → 校验通过 → 再弹指纹（§4.5）。
 *
 * ⚠️ 历史坑：此前本对话框**只能关不能开**（启用入口仅库列表页横幅），
 * 而横幅「以后再说」会永久置位 `isQuickUnlockPromptDismissed` → 用户彻底
 * 失去启用路径。现已补上对称的「启用」动作，消除该入口死角。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuickUnlockManageDialog(
    vaults: List<SettingsViewModel.QuickUnlockVaultUi>,
    canAuthenticate: Boolean,
    onEnable: (String) -> Unit,
    onDisable: (String) -> Unit,
    onPinSet: (SettingsViewModel.QuickUnlockVaultUi) -> Unit,
    onPinDisable: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        DialogSurface {
            DialogHeader(title = stringResource(R.string.settings_quick_unlock))

            if (vaults.isEmpty()) {
                DialogEmptyBody(stringResource(R.string.quick_unlock_manage_none))
            } else {
                // ⚠️ 必须可滚动：本对话框纵向内容随库数增长，不可滚动时底部会被截断，
                // 用户既看不到也点不到最后一项。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                ) {
                    DialogSectionTitle(
                        title = stringResource(R.string.quick_unlock_section_scope),
                        hint = stringResource(R.string.quick_unlock_scope_hint),
                        icon = { Icon(Icons.Filled.Key, contentDescription = null) },
                    )

                    // 2026-09-15 三改（用户：「可以合并设置的」）：
                    // 改前是**两个大段**（指纹段列出所有库 → 应用内 PIN 段再列出所有库），
                    // 同一个库名在屏幕上出现两次、滚动还要来回找。
                    // 现在改为**一个库一张卡、卡内两行**（指纹 / 应用内 PIN），
                    // 心智模型从"两把钥匙分别管哪些库"变成"这个库有哪几把钥匙"。
                    vaults.forEach { vault ->
                        VaultUnlockCard(
                            vault = vault,
                            canAuthenticate = canAuthenticate,
                            onEnable = onEnable,
                            onDisable = onDisable,
                            onPinSet = onPinSet,
                            onPinDisable = onPinDisable,
                        )
                    }
                    Spacer(Modifier.height(Spacing.sm))
                }
            }

            DialogActions {
                DialogBackButton(onDismiss)
            }
        }
    }
}

/**
 * 单个密码库的解锁设置卡片：**卡片头（库名）+ 卡内两行（指纹 / 应用内 PIN）**。
 *
 * ## 为什么合并（2026-09-15 三改）
 *
 * 改前结构是「指纹段（列出全部库）→ 分隔线 → 应用内 PIN 段（再列出全部库）」，
 * 两个问题：
 * 1. **同一个库名在屏幕上出现两次**，用户读到第二个时必须回头确认"这是同一个库吗"；
 * 2. 想给某个库同时配指纹和 PIN，要在两段之间**滚动来回找**。
 *
 * ⚠️ 合并的**不是两种手段**（那必须并列，见 [SettingsViewModel.QuickUnlockVaultUi.pinEnabled]
 * 的注释：两者是彼此独立的路径，不能合成一个「已启用」），
 * 而是**分组维度** —— 从"按手段分组"改成"按库分组"。
 *
 * ## 保住的既有告诫
 *
 * - 卡内两行仍是「正文在上、动作在下」的纵向骨架（[UnlockOptionRow] 的告诫继续有效：
 *   不得退回 `ListItem` + `trailingContent`，窄宽度下按钮会与文字相叠）；
 * - 两行之间用 [HorizontalDivider] 分隔而非留白：同一个库的两条**独立**路径，
 *   需要一条明确的边界说明"这是两件事"（此处与"段落之间不该用分隔线"不矛盾 —— 那里
 *   是两个**段落**，这里是同一段内的两条**并列项**，M3 的 list divider 正用于此）。
 */
@Composable
internal fun VaultUnlockCard(
    vault: SettingsViewModel.QuickUnlockVaultUi,
    canAuthenticate: Boolean,
    onEnable: (String) -> Unit,
    onDisable: (String) -> Unit,
    onPinSet: (SettingsViewModel.QuickUnlockVaultUi) -> Unit,
    onPinDisable: (String) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        shape = RoundedCornerShape(VAULT_ROW_CORNER),
        colors = CardDefaults.cardColors(
            // ⚠️ 必须比 DialogSurface 的面板（surfaceContainerHigh）**低**一档，
            // 否则同色相叠、卡片边界消失（见 [UnlockOptionRow] 的 KDoc）。
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ---- 卡片头：库名 + 类型徽标 ----
            Text(
                text = vault.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(
                    start = Spacing.lg,
                    end = Spacing.lg,
                    top = Spacing.md,
                    bottom = Spacing.xs,
                ),
            )

            // ---- 行 1：指纹 ----
            UnlockOptionRow(
                icon = { Icon(Icons.Filled.Fingerprint, contentDescription = null) },
                title = stringResource(R.string.quick_unlock_section_biometric),
                summary = when {
                    // 已启用：徽标已说明状态，副标题无可补充 ⇒ 留空。
                    vault.enabled -> ""
                    // 仅当设备确实支持认证时才提示「可启用」，
                    // 否则维持原「未启用」说明，避免给出无法完成的指引。
                    canAuthenticate -> stringResource(
                        if (vault.kind == VaultKind.KDBX) {
                            R.string.quick_unlock_disabled_kdbx
                        } else {
                            R.string.quick_unlock_disabled
                        },
                    )
                    else -> stringResource(R.string.quick_unlock_device_unsupported)
                },
                enabled = vault.enabled,
            ) {
                if (vault.enabled) {
                    ActionSpacer()
                    TextButton(onClick = { onDisable(vault.vaultId) }) {
                        Text(stringResource(R.string.quick_unlock_disable))
                    }
                } else if (canAuthenticate) {
                    ActionSpacer()
                    TextButton(onClick = { onEnable(vault.vaultId) }) {
                        Text(stringResource(R.string.quick_unlock_enable))
                    }
                }
            }

            // ---- 行 2：应用内 PIN ----
            // 分隔线说明"这是另一件独立的事"（PIN 不需要系统认证，与指纹无依赖）。
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = Spacing.lg),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
            UnlockOptionRow(
                icon = { Icon(Icons.Filled.Pin, contentDescription = null) },
                title = stringResource(R.string.pin_section_title),
                summary = if (vault.pinEnabled) {
                    stringResource(R.string.pin_enabled_digits, PIN_MIN_LENGTH)
                } else {
                    stringResource(R.string.pin_disabled_summary)
                },
                enabled = vault.pinEnabled,
            ) {
                if (vault.pinEnabled) {
                    TextButton(onClick = { onPinDisable(vault.vaultId) }) {
                        Text(stringResource(R.string.pin_disable))
                    }
                }
                // 「修改 / 设置 PIN」是主操作 ⇒ 始终靠右。
                ActionSpacer()
                TextButton(onClick = { onPinSet(vault) }) {
                    Text(
                        stringResource(
                            if (vault.pinEnabled) R.string.pin_change else R.string.pin_enable,
                        ),
                    )
                }
            }
        }
    }
}

/**
 * 卡内的**一行**解锁手段（由 [VaultUnlockCard] 调用两次：指纹 / 应用内 PIN）。
 *
 * ⚠️ **不要退回 `ListItem` + `trailingContent`**：对话框的可用宽度本来就窄，
 * 而这里每行最多有两个动作按钮（如「关闭 PIN 解锁」+「修改 PIN」）。
 * 两者挤在同一行时，正文会被压成多行并与按钮**叠在一起**（2026-09-14 真机报告
 * 「文字叠加、UI 错乱」）。改成「正文在上、动作右对齐换行在下」后，
 * 无论按钮多宽都不可能压到文字 —— 纵向增长是**可见且可读**的失败方式。
 *
 * ## 2026-09-15 二次重做：修「毛坯房」
 *
 * 用户真机反馈这页「看起来很廉价、像毛坯房」。归因是**三个可量化的结构问题**：
 *
 * 1. 🔴 **卡片与面板同色，层级归零**（最致命）。`DialogSurface` 的面板底就是
 *    `surfaceContainerHigh`，而本行之前也用 `surfaceContainerHigh @ 55%`。
 *    同色叠同色 ⇒ 卡片边界**根本看不见**，整屏糊成一片灰 ——
 *    「毛坯房」三个字的来源。改：卡片底用 `surfaceContainerLowest`
 *    （比面板**低**一档 ⇒ 深色主题下更深、浅色下更白），与面板拉开明确层级。
 *    ⚠️ 这是 M3 的标准层级用法：容器色**阶梯**本就是 `Lowest < Low < Base < High`，
 *    嵌套内容该往**低**走，不是往同色叠 alpha。
 * 2. **状态徽标与副标题重复叙事**：启用时副标题也写「已启用」，一行字说两遍。
 *    改：徽标负责状态，副标题只负责**信息**（怎么启用 / 为何不可用 / 位数）。
 * 3. **动作按钮无主次**：启用与关闭等权重并排、还都贴着右边留一片空。
 *    改用 `ActionSpacer()`（`weight(1f)` 占位）把主操作推到行尾，形成两端对齐的动作栏。
 *
 * ## 2026-09-15 三改：不再是 Card，改为卡内的行
 *
 * 用户要求「可以合并设置的」后，**卡片**的职责上移到 [VaultUnlockCard]（一库一卡），
 * 本组件降级为卡内的一行 ⇒ **去掉自己的 Card 外壳与水平外边距**，
 * 改由 `icon` 槽位提供手段图标（指纹 / PIN），与行标题一起构成扫读锚点。
 *
 * @param icon 手段图标（28dp 槽位）。两行共处一张卡时，图标是区分二者最快的锚点。
 */
@Composable
internal fun UnlockOptionRow(
    icon: @Composable () -> Unit,
    title: String,
    summary: String,
    enabled: Boolean,
    actions: @Composable RowScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 图标锚点（主色）。与 [SettingsRow] 的图标槽同为 28dp，保持一族。
            CompositionLocalProvider(
                LocalContentColor provides MaterialTheme.colorScheme.primary,
            ) {
                Box(
                    modifier = Modifier.size(VAULT_ICON_BOX),
                    contentAlignment = Alignment.Center,
                ) {
                    icon()
                }
            }
            Spacer(Modifier.width(Spacing.md))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 状态徽标：一眼看出这条手段开没开，不用读整句副标题。
            UnlockStateBadge(enabled = enabled)
        }
        // 副标题只在**有信息**时出现（徽标已表达的状态不再复述，见 KDoc 第 2 条）。
        if (summary.isNotEmpty()) {
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // 左缩进 = 图标槽 + 间距，让说明与标题左对齐（而不是顶到图标下面）。
                modifier = Modifier.padding(
                    start = VAULT_ICON_BOX + Spacing.md,
                    top = Spacing.xs,
                ),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}

/**
 * 解锁手段的状态徽标（已启用 / 未启用）。
 *
 * 用「小圆点 + 文字」而不是只靠副标题措辞区分：两行文字（"已启用" / "点此处启用"）
 * 在字号相同时扫读很慢，加一个色点让状态**先于**文字被看见。
 */
@Composable
private fun UnlockStateBadge(enabled: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(BADGE_DOT_SIZE)
                .background(
                    color = if (enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    shape = CircleShape,
                ),
        )
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text = stringResource(
                if (enabled) R.string.settings_unlock_state_on else R.string.settings_unlock_state_off,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** 状态徽标圆点直径。 */
private val BADGE_DOT_SIZE = 8.dp

/**
 * 动作栏占位：把后面的按钮推到行尾，与左侧正文的两端对齐。
 *
 * 用在"只有一个右侧按钮"的行（如「关闭」），避免整行只有一个按钮孤零零贴在左边。
 */
@Composable
private fun RowScope.ActionSpacer() {
    Spacer(Modifier.weight(1f))
}

/**
 * PIN 设置对话框的宿主：按 [SettingsViewModel.PinDialogState] 选一帧渲染。
 *
 * 单独成宿主的原因：PIN 的两步（输 PIN → KDBX 输主密码）是**同一个流程的两个阶段**，
 * 让它们在同一个宿主里切换，才能保证「上一步收下的 PIN」不会因为 UI 重组而丢失。
 */
@Composable
internal fun PinDialogHost(viewModel: SettingsViewModel) {
    // ⚠️ 必须先用局部 `val` 接住：委托属性（`by`）**无法智能转换**，
    // 直接在 `when` 里用 `state is ...` 会编译不过。
    val state = viewModel.pinDialog.collectAsStateWithLifecycle().value
    when (state) {
        SettingsViewModel.PinDialogState.Idle -> Unit
        is SettingsViewModel.PinDialogState.Entering ->
            PinSetDialog(state = state, viewModel = viewModel)
        is SettingsViewModel.PinDialogState.AskingKdbxPassword ->
            PinKdbxPasswordDialog(state = state, viewModel = viewModel)
    }
}

/**
 * 设置 PIN（第一步）：输入两次。
 *
 * 用 `NumberPassword` 键盘：PIN 是纯数字，弹全键盘只会让用户多找一次数字行。
 * 两次输入一致性与位数校验都在 ViewModel（见 `confirmPinEntry`），这里只负责画错误。
 */
@Composable
private fun PinSetDialog(
    state: SettingsViewModel.PinDialogState.Entering,
    viewModel: SettingsViewModel,
) {
    AlertDialog(
        onDismissRequest = viewModel::dismissPinDialog,
        title = { Text(stringResource(R.string.pin_set_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.pin_set_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.md))
                PinField(
                    value = state.pin,
                    labelRes = R.string.pin_label,
                    onValueChange = viewModel::onPinChange,
                )
                Spacer(Modifier.height(Spacing.sm))
                PinField(
                    value = state.confirm,
                    labelRes = R.string.pin_confirm_label,
                    onValueChange = viewModel::onPinConfirmChange,
                )
                DialogErrorText(state.error)
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::confirmPinEntry) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissPinDialog) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/** KDBX 第二步：再输一次主密码（会话里没有它，PIN 无从包裹）。 */
@Composable
private fun PinKdbxPasswordDialog(
    state: SettingsViewModel.PinDialogState.AskingKdbxPassword,
    viewModel: SettingsViewModel,
) {
    AlertDialog(
        onDismissRequest = viewModel::dismissPinDialog,
        title = { Text(stringResource(R.string.pin_kdbx_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.pin_kdbx_message, state.vaultName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.md))
                OutlinedTextField(
                    value = state.password,
                    onValueChange = viewModel::onPinKdbxPasswordChange,
                    label = { Text(stringResource(R.string.kdbx_master_password_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                DialogErrorText(state.error)
            }
        },
        confirmButton = {
            TextButton(onClick = viewModel::confirmKdbxPasswordForPin) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissPinDialog) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/** 纯数字、遮蔽输入的字段（PIN 两格共用：样式只有一处，改就一起改）。 */
@Composable
private fun PinField(value: String, labelRes: Int, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(labelRes)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 对话框内的错误行；无错误时不占位（不要留一行空高，会让对话框忽高忽低）。 */
@Composable
private fun DialogErrorText(message: String?) {
    if (message == null) return
    Spacer(Modifier.height(Spacing.sm))
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

/**
 * KDBX 启用快速解锁前的主密码输入框（定稿 §4.5 —— 这一步**无法省略**）。
 *
 * 「宽松」取向（§4.4）的 UI 落地：
 * - 输错 ⇒ 只显示错误、**输入框保留**、按钮可再点（不清空、不关框）；
 * - 校验期间按钮禁用，避免连点产生多个 cipher。
 */
@Composable
internal fun KdbxQuickUnlockPasswordDialog(
    vaultName: String,
    state: SettingsViewModel.KdbxEnrollState,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by rememberSaveable { mutableStateOf("") }
    val busy = state is SettingsViewModel.KdbxEnrollState.Ready
    val errorText = when (state) {
        is SettingsViewModel.KdbxEnrollState.WrongPassword -> stringResource(R.string.kdbx_quick_unlock_wrong_password)
        is SettingsViewModel.KdbxEnrollState.Failed -> state.detail
        is SettingsViewModel.KdbxEnrollState.Unavailable -> stringResource(R.string.quick_unlock_device_unsupported)
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.kdbx_quick_unlock_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.kdbx_quick_unlock_message, vaultName),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(Spacing.md))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.kdbx_master_password_label)) },
                    singleLine = true,
                    isError = errorText != null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (errorText != null) {
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        text = errorText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(password) },
                enabled = password.isNotEmpty() && !busy,
            ) {
                Text(stringResource(R.string.action_enable))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/**
 * 单选列表对话框：自动锁定档位。
 *
 * 档位集合对齐 Bitwarden `VaultTimeout`（立即 / 1 / 5 / 15 / 30 / 60 / 240 分钟 /
 * 重启时 / 从不 / 自定义），取代旧的裸 Int 档位表。
 */
@Composable
private fun AutoLockDialog(
    current: VaultTimeout,
    onSelect: (VaultTimeout) -> Unit,
    onDismiss: () -> Unit,
) {
    var customOpen by rememberSaveable { mutableStateOf(false) }
    var customText by rememberSaveable { mutableStateOf("") }
    var customInvalid by rememberSaveable { mutableStateOf(false) }

    if (customOpen) {
        AlertDialog(
            onDismissRequest = {
                customOpen = false
                customInvalid = false
            },
            title = { Text(stringResource(R.string.auto_lock_custom_title)) },
            text = {
                OutlinedTextField(
                    value = customText,
                    onValueChange = {
                        customText = it.filter { c -> c.isDigit() }.take(AutoLockPresets.CUSTOM_MINUTES_DIGITS)
                        customInvalid = false
                    },
                    isError = customInvalid,
                    supportingText = if (customInvalid) {
                        { Text(stringResource(R.string.auto_lock_custom_invalid)) }
                    } else {
                        null
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val value = customText.toIntOrNull()
                    if (value == null || value < 1 || value > AutoLockPresets.MAX_CUSTOM_MINUTES) {
                        customInvalid = true
                    } else {
                        onSelect(VaultTimeout.Custom(value))
                    }
                }) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { customOpen = false; customInvalid = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_auto_lock)) },
        text = {
            Column {
                AutoLockPresets.VALUES.forEach { timeout ->
                    SingleChoiceRow(
                        label = vaultTimeoutLabel(timeout),
                        selected = timeout == current,
                        onClick = { onSelect(timeout) },
                    )
                }
                SingleChoiceRow(
                    label = stringResource(R.string.auto_lock_custom),
                    selected = current is VaultTimeout.Custom,
                    onClick = { customOpen = true },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/** 单选列表对话框：复制后自动清除剪贴板时长。 */
@Composable
private fun ClipboardClearDialog(
    currentMs: Long,
    onSelect: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setting_clipboard_clear)) },
        text = {
            Column {
                SettingsViewModel.CLIPBOARD_PRESETS_MS.forEach { ms ->
                    SingleChoiceRow(
                        label = clipboardClearLabel(ms),
                        selected = ms == currentMs,
                        onClick = { onSelect(ms) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun SingleChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = Spacing.xs),
        )
    }
}


// ---- 档位文案（对齐 Bitwarden VaultTimeout 的档位命名）----

/**
 * 自动锁定档位 → 文案。
 *
 * `when` 作用于 sealed class（穷尽分支，漏档编译器会报错），取代旧的裸 Int `when`
 * （后者漏一个档位只会在运行时静默落到 else）。
 */
@Composable
private fun vaultTimeoutLabel(timeout: VaultTimeout): String = when (timeout) {
    VaultTimeout.Never -> stringResource(R.string.auto_lock_never)
    VaultTimeout.Immediately -> stringResource(R.string.auto_lock_immediately)
    VaultTimeout.OnAppRestart -> stringResource(R.string.auto_lock_on_restart)
    VaultTimeout.OneMinute,
    VaultTimeout.FiveMinutes,
    VaultTimeout.FifteenMinutes,
    VaultTimeout.ThirtyMinutes ->
        // 这几个预设档位的 minutes 恒非空（各自 override 为非空 Int）；
        // 组合分支不做智能转换，故显式 requireNotNull 断言该不变量。
        stringResource(
            R.string.auto_lock_minutes_fmt,
            requireNotNull(timeout.vaultTimeoutInMinutes),
        )
    VaultTimeout.OneHour,
    VaultTimeout.FourHours ->
        stringResource(
            R.string.auto_lock_hour_fmt,
            requireNotNull(timeout.vaultTimeoutInMinutes) / AutoLockPresets.MINUTES_PER_HOUR,
        )
    is VaultTimeout.Custom -> when {
        timeout.vaultTimeoutInMinutes % AutoLockPresets.MINUTES_PER_HOUR == 0 ->
            stringResource(
                R.string.auto_lock_hour_fmt,
                timeout.vaultTimeoutInMinutes / AutoLockPresets.MINUTES_PER_HOUR,
            )
        timeout.vaultTimeoutInMinutes % AutoLockPresets.MINUTES_PER_DAY == 0 ->
            stringResource(
                R.string.auto_lock_day_fmt,
                timeout.vaultTimeoutInMinutes / AutoLockPresets.MINUTES_PER_DAY,
            )
        else -> stringResource(R.string.auto_lock_minutes_fmt, timeout.vaultTimeoutInMinutes)
    }
}

/** 剪贴板清除毫秒 → 文案。 */
@Composable
private fun clipboardClearLabel(ms: Long): String = when (ms) {
    0L -> stringResource(R.string.clipboard_clear_off)
    else -> stringResource(
        R.string.clipboard_clear_seconds_fmt,
        ms / AutoLockPresets.MS_PER_SECOND,
    )
}

/**
 * 自动锁定档位表与文案换算常量。
 *
 * 档位对齐 Bitwarden `VaultTimeout`：立即 / 1 / 5 / 15 / 30 / 60 / 240 分钟 /
 * 重启时 / 从不（+ 自定义）。旧的 10 / 300 / 1440 三个非标准档位已从候选中移除——
 * 存量用户设置若落在这些值上，迁移时保留为 [VaultTimeout.Custom]，**不会丢失**
 * （见 `VaultTimeout.fromLegacyMinutes`）。
 */
@Suppress("MagicNumber")
private object AutoLockPresets {
    /** 单选候选（顺序即 UI 顺序）。 */
    val VALUES: List<VaultTimeout> = listOf(
        VaultTimeout.Immediately,
        VaultTimeout.OneMinute,
        VaultTimeout.FiveMinutes,
        VaultTimeout.FifteenMinutes,
        VaultTimeout.ThirtyMinutes,
        VaultTimeout.OneHour,
        VaultTimeout.FourHours,
        VaultTimeout.OnAppRestart,
        VaultTimeout.Never,
    )

    const val MINUTES_PER_HOUR = 60
    const val MINUTES_PER_DAY = 1440
    const val MS_PER_SECOND = 1000

    /** 自定义分钟数输入上限。 */
    const val MAX_CUSTOM_MINUTES = 100_000

    /** 自定义输入框位数上限。 */
    const val CUSTOM_MINUTES_DIGITS = 6
}
