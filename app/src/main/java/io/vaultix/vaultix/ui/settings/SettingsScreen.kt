package io.vaultix.vaultix.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.remember
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
import io.vaultix.vaultix.util.UpdateCheckResult
import io.vaultix.vaultix.util.UpdateChecker

/**
 * 设置页。**4 组**（原 7 组，2026-09-16 再并为 4 组），自上而下：
 *
 * | 组 | 内容 |
 * |---|---|
 * | 密码库与解锁 | 密码库管理 · 自动锁定 · 防截屏 · 剪贴板清除 |
 * | 显示与填充 | 主题模式 · 动态取色 · 纯黑背景 · 条目显示 · 自动填充设置 |
 * | 数据 | 导入与导出 · 回收站清理 · **退出数据库**（error 色，置底） |
 * | 关于 | 权限管理 · 版本 · **检查更新** · 源码与反馈 · 开源许可 |
 *
 * 组内顺序原则：入口类在前、开关类居中、**破坏性动作置底**。
 * 结构与文案的来龙去脉见 `.ai/decisions/设置页信息架构-定稿.md`（**别重新设计分组**）。
 *
 * 2026-09-16 的两处改动（详见各组的 KDoc）：
 * 1. 原「密码库」只剩一个入口行却独占组标题 ⇒ 并入「解锁与隐私」成「**密码库与解锁**」；
 * 2. 从 Bastion 搬来「**检查更新**」（[UpdateChecker]）：查 GitHub Releases 并给发布页链接。
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

    // 「检查更新」：一次手动检查，结论只活在这一次打开对话框期间。
    var showUpdateDialog by rememberSaveable { mutableStateOf(false) }
    var updateChecking by rememberSaveable { mutableStateOf(false) }
    var updateError by rememberSaveable { mutableStateOf<String?>(null) }
    // ⚠️ 结果对象不用 rememberSaveable：`UpdateCheckResult` 不是可序列化类型，
    //    为了保存它去引入 Parcelable 包装不划算 ⇒ 旋转屏幕后**结论会丢**。
    var updateResult by remember { mutableStateOf<UpdateCheckResult?>(null) }
    // ⚠️ 因此 key 里带上 `updateResult` / `updateError`：旋转后结论丢了 ⇒ 自动重查一次，
    //    而不是让用户对着一个"暂时无法确定"的空对话框发呆。
    //    已经有结论时（key 未变）直接跳过 —— 这也要求点击入口先清结论（见调用处）。
    LaunchedEffect(showUpdateDialog, updateResult, updateError) {
        if (!showUpdateDialog) return@LaunchedEffect
        if (updateResult != null || updateError != null) return@LaunchedEffect
        updateChecking = true
        val outcome = UpdateChecker.checkForUpdate(BuildConfig.VERSION_NAME)
        // ⚠️ **先把 checking 落回 false，再写结论**：写结论会改变本 LaunchedEffect 的
        //    key ⇒ 协程随即被取消。若把 `updateChecking = false` 放在下面，
        //    它永远执行不到，对话框会一直停在「正在检查…」。
        updateChecking = false
        outcome
            .onSuccess { updateResult = it }
            .onFailure { updateError = it.message.orEmpty() }
    }

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
            // ---- 密码库与解锁（活跃库 = 全局单一真源，详见 VaultUnlockSection 的 KDoc）----
            // 只剩一个入口：选库 / 加库 / 配解锁方式都在 VaultManagementScreen（二级页）。
            // ⚠️ 2026-09-16 合并：原先「密码库」只有这一个入口行却独占一个组标题，
            //    而它和自动锁定 / 防截屏 / 剪贴板清除讲的是同一件事（库怎么开、开了怎么锁）。
            VaultUnlockSection(
                viewModel = viewModel,
                state = state,
                onOpenVaultManagement = onOpenVaultManagement,
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
                // ⚠️ 每次点击都**先清掉上一次的结论**：结论是"一次性"的，
                // 留着旧结果会让下面那次 LaunchedEffect 直接跳过（见那里的注释），
                // 用户就会看到上一次的答案。
                onCheckUpdate = {
                    updateResult = null
                    updateError = null
                    showUpdateDialog = true
                },
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
    if (showUpdateDialog) {
        UpdateCheckDialog(
            checking = updateChecking,
            result = updateResult,
            error = updateError,
            onOpenRelease = { url -> openUrl(context, url) },
            onDismiss = { showUpdateDialog = false },
        )
    }
}

/**
 * 「检查更新」结论对话框。
 *
 * 只做三件事：告诉用户**当前是哪个版本**、**远端是哪个版本**、**去哪下载**。
 * 不在 App 内下载 / 安装 APK —— 对一个密码管理器来说，自动装上来路不明的安装包
 * 是比"多两步"严重得多的风险（理由见 [UpdateChecker] 的文件头）。
 */
@Composable
private fun UpdateCheckDialog(
    checking: Boolean,
    result: UpdateCheckResult?,
    error: String?,
    onOpenRelease: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
        title = { Text(stringResource(R.string.update_check_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                when {
                    checking -> Text(
                        text = stringResource(R.string.update_check_checking),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    error != null -> Text(
                        text = if (error.isBlank()) {
                            stringResource(R.string.update_check_failed)
                        } else {
                            stringResource(R.string.update_check_failed_fmt, error)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    result == null -> Text(
                        text = stringResource(R.string.update_check_unknown),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    result.isUpdateAvailable -> {
                        Text(
                            text = stringResource(
                                R.string.update_check_available_fmt,
                                result.latestVersion,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = stringResource(
                                R.string.update_check_current_fmt,
                                result.currentVersion,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        UpdatePublishedLine(result.publishedAtEpochSeconds)
                    }
                    else -> {
                        Text(
                            text = stringResource(R.string.update_check_latest),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = stringResource(
                                R.string.update_check_current_fmt,
                                result.currentVersion,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            // 检查中不给按钮（点了也只是等待）；有结论时「前往下载」直达发布页。
            if (!checking) {
                TextButton(
                    onClick = {
                        onOpenRelease(result?.releaseUrl ?: UpdateChecker.RELEASES_PAGE_URL)
                    },
                ) {
                    Text(stringResource(R.string.update_check_go))
                }
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
 * 检查更新对话框里的「发布于 …」一行。
 *
 * 预览渠道的版本标识只是 `dev-<短 sha>`，光看它无法判断新旧 —— **发布日期才是用户
 * 真正能对照的信息**（"我上周下的，这周又有一版"）。拿不到就整行不画。
 */
@Composable
private fun UpdatePublishedLine(publishedAtEpochSeconds: Long?) {
    if (publishedAtEpochSeconds == null) return
    Text(
        text = stringResource(
            R.string.update_check_published_fmt,
            UpdateChecker.formatPublishedDate(publishedAtEpochSeconds),
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
 * 密码库与解锁组（2026-09-16 由原「密码库」+ 原「解锁与隐私」合并）。
 *
 * ## 为什么合并
 *
 * 改前「密码库」组**只有一行**（密码库管理入口），却独占一个组标题；它旁边就是
 * 「解锁与隐私」的四行（自动锁定 / 防截屏 / 剪贴板清除）。而这两组讲的是同一件事：
 * **库怎么打开、打开后怎么锁**。拆成两组唯一的后果是屏幕顶上多了一条标题和一段留白，
 * 用户扫读时反而要判断「我现在看的到底是库还是隐私」。
 *
 * 组内顺序：**入口在前、档位居中、开关在后** ——
 * 密码库管理（去二级页）→ 自动锁定（档位）→ 防截屏（开关）→ 剪贴板清除（档位）。
 *
 * ## 保住的既有告诫（2026-09-15 的三次调整，别退回去）
 *
 * - **「快速解锁」在 [VaultManagementScreen] 二级页**，不在这里。它按**每个库**单独
 *   登记一条解锁凭据（`quickUnlockVaults` 就是"每库一行"的形态），语义上属于库管理；
 * - **「退出数据库」在「数据」组置底**：它是清空本地缓存的**数据动作**，不是解锁设置。
 *
 * 拆成独立 composable 纯粹是为了让 [SettingsScreen] 主函数守住 detekt `LongMethod`
 * （≤150 行）——各对话框的显示状态仍留在宿主里，本函数只收回调。
 */
@Composable
private fun VaultUnlockSection(
    viewModel: SettingsViewModel,
    state: SettingsViewModel.UiState,
    onOpenVaultManagement: () -> Unit,
    onAutoLock: () -> Unit,
    onClipboardClear: () -> Unit,
) {
    val active by viewModel.activeVault.collectAsStateWithLifecycle()
    SettingsGroupTitle(stringResource(R.string.group_vault_unlock))
    SettingsGroupCard {
        SettingsRow(
            icon = { Icon(Icons.Filled.Storage, contentDescription = null) },
            title = stringResource(R.string.vault_management_entry),
            // 副标题直接给**当前库名**：用户最常问的就是"我现在看的是哪个库"，
            // 放在这里就不必为了确认这件事再点进二级页。
            subtitle = active?.name ?: stringResource(R.string.settings_active_vault_none),
            onClick = onOpenVaultManagement,
        )
        SettingsDivider()
        SettingsRow(
            icon = { Icon(Icons.Filled.Timer, contentDescription = null) },
            title = stringResource(R.string.setting_auto_lock),
            subtitle = vaultTimeoutLabel(state.vaultTimeout),
            onClick = onAutoLock,
        )
        SettingsDivider()
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
        SettingsDivider()
        SettingsRow(
            icon = { Icon(Icons.Filled.VisibilityOff, contentDescription = null) },
            title = stringResource(R.string.setting_clipboard_clear),
            subtitle = clipboardClearLabel(state.clipboardClearMs),
            onClick = onClipboardClear,
        )
    }
}

/**
 * 关于组（含权限管理 / 检查更新）。
 *
 * 为什么入口在设置页：Bastion 的多库是主界面里的一个**筛选维度**（`UnifiedCategoryFilterSelection`
 * 把库与文件夹 / 分类平级），而 Vaultix 是「登录时二选一」的**单活跃库**语义 —— 主界面
 * 不该出现库的概念，于是把多库入口整体下沉到设置页
 * （Docs/progress/main-shell-migration.md §0 与 A4）。
 *
 * 「检查更新」是 2026-09-16 从 Bastion 搬来的最后一块拼图：Vaultix 的分发渠道就是
 * GitHub Release，用户本来就得去那下载；这里只回答「有没有比本机新的构建」并给出
 * 发布页链接，**不在 App 内下载安装**（理由见 [UpdateChecker] 的文件头）。
 */
@Composable
private fun AboutSection(
    context: Context,
    versionName: String,
    onShowLicense: () -> Unit,
    onCheckUpdate: () -> Unit,
) {
    SettingsGroupTitle(stringResource(R.string.group_about))
    SettingsGroupCard {
        // 权限管理原属「其他」组（该组仅此一行，独占一个组标题）
        // ⇒ 并入「关于」：它本来就是"关于这个 App"的元信息（定稿 §2②）。
        SettingsRow(
            icon = { Icon(Icons.Filled.Policy, contentDescription = null) },
            title = stringResource(R.string.permission_management_title),
            subtitle = stringResource(R.string.permission_management_subtitle),
            onClick = { openAppPermissionSettings(context) },
        )
        SettingsDivider()
        SettingsRow(
            icon = { Icon(Icons.Filled.Info, contentDescription = null) },
            title = stringResource(R.string.about_version),
            subtitle = versionName,
        )
        SettingsDivider()
        SettingsRow(
            icon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
            title = stringResource(R.string.about_check_update),
            subtitle = stringResource(R.string.about_check_update_desc),
            onClick = onCheckUpdate,
        )
        SettingsDivider()
        SettingsRow(
            icon = { Icon(Icons.Filled.Security, contentDescription = null) },
            title = stringResource(R.string.about_source),
            subtitle = stringResource(R.string.about_github_url),
            onClick = { openUrl(context, context.getString(R.string.about_github_url)) },
        )
        SettingsDivider()
        SettingsRow(
            icon = { Icon(Icons.Filled.Info, contentDescription = null) },
            title = stringResource(R.string.about_license),
            onClick = onShowLicense,
        )
    }
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
        // 否则同色相叠、卡片边界消失（与 VaultUnlockCard 同一个病，2026-09-15）。
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
    SettingsGroupCard {
        SettingsRow(
            icon = { Icon(Icons.Filled.DarkMode, contentDescription = null) },
            title = stringResource(R.string.setting_theme_mode),
            subtitle = themeModeLabel(ThemeMode.from(themeMode)),
            onClick = { showThemeDialog = true },
        )
        SettingsDivider()
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
        SettingsDivider()
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
        SettingsDivider()
        // 条目列表的显示选项（分组方式 / 卡片信息密度 / 是否显示图标）——
        // 与密码 Tab 顶栏那个按钮共用同一个弹层与同一份偏好，改哪边都实时生效。
        SettingsRow(
            icon = { Icon(Icons.Filled.ViewAgenda, contentDescription = null) },
            title = stringResource(R.string.items_display_options),
            subtitle = stringResource(R.string.setting_display_options_desc),
            onClick = { showDisplayOptions = true },
        )
        SettingsDivider()
        // 自动填充入口（二级页）—— 原先是只有一行的独立分组，现并入本组末尾。
        SettingsRow(
            icon = { Icon(Icons.Filled.Password, contentDescription = null) },
            title = stringResource(R.string.autofill_settings_entry),
            subtitle = stringResource(R.string.autofill_settings_entry_desc),
            onClick = onOpenAutofillSettings,
        )
    }

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
    SettingsGroupCard {
        SettingsRow(
            icon = { Icon(Icons.Filled.SwapVert, contentDescription = null) },
            title = stringResource(R.string.import_export_entry),
            subtitle = stringResource(R.string.import_export_entry_desc),
            onClick = onOpenImportExport,
        )
        SettingsDivider()
        SettingsRow(
            icon = { Icon(Icons.Filled.DeleteSweep, contentDescription = null) },
            title = stringResource(R.string.setting_trash_auto_delete),
            subtitle = trashAutoDeleteLabel(trashDays),
            onClick = { showTrashDialog = true },
        )
        SettingsDivider()
        SettingsRow(
            icon = { Icon(Icons.Filled.Logout, contentDescription = null) },
            title = stringResource(R.string.setting_exit_database),
            subtitle = stringResource(R.string.setting_exit_database_desc),
            titleColor = MaterialTheme.colorScheme.error,
            onClick = onExitDatabase,
        )
    }

    if (showTrashDialog) {
        TrashAutoDeleteDialog(
            currentDays = trashDays,
            onSelect = viewModel::setTrashAutoDeleteDays,
            onDismiss = { showTrashDialog = false },
        )
    }
}

/**
 * 用浏览器打开一个链接。
 *
 * ⚠️ 必须 `runCatching`：系统里可能**没有任何**能处理 `ACTION_VIEW` 的组件
 * （受限资料 profile / 精简 ROM），此时 `startActivity` 抛 `ActivityNotFoundException`
 * —— 点一下「源码与反馈」就把 App 崩掉是不可接受的。
 */
private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
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
 * 快速解锁「**生效范围**」：列出全部库，逐个决定这个库用什么方式打开。
 *
 * ## 心智模型（2026-09-16 四改：从"两个开关"改为"三选一"）
 *
 * 改前每库一张卡、卡内两行（指纹 / 应用内 PIN）**各带一个开关**，问题是：
 * 用户面对的不是"设置"，而是"判断题"——他得先自己判断"这两个开关冲突吗"，
 * 才能决定要不要都打开。真机上「还是不太好看」的根因就在这：**决策成本没被收敛**。
 *
 * 四改把它压成一次单选（[UnlockChoice]）：**指纹 / 应用内 PIN / 每次输主密码**。
 * 用户只需回答一个问题：「这个库怎么进？」
 *
 * ### ⚠️ 单选是**呈现层**的，不是数据层的（关键约定，别改坏）
 *
 * 底层仍是 [SettingsViewModel.QuickUnlockVaultUi.enabled] 与 `pinEnabled` **两个独立布尔**，
 * 二者可以同时为真（既登记了 Keystore 指纹钥匙、又设了应用内 PIN，解锁页两条路径都能走）。
 * 这个能力**保留不动** —— 本次只改呈现，[SettingsViewModel] 一行未动。
 *
 * 由此带来两条必须遵守的规则：
 * 1. **两个都开的历史数据**：按优先级（指纹 > PIN）显示为一个选中项，界面不会出现
 *    "两个都选中"这种自相矛盾的状态；
 * 2. **用户点任何一项 ⇒ 顺手关掉另一项**（见 [onChoice]）。互斥由**动作**实现，
 *    而非由数据约束实现 ⇒ 代价是"用户之后仍可能从其它入口把两个都开上"，
 *    但那属于能力保留的必然结果，且届时本对话框仍会按优先级正确显示。
 *
 * ### 第三个选项「每次输主密码」为什么必须显式列出
 *
 * 改前两个开关都关掉时，界面只有两个「未启用」，用户看不出这**就等于**"每次输主密码"。
 * 把它列成同级的第三个选项，用户才有一个**肯定的**退出方式 ——
 * 而不是"把两个开关都关掉"这种否定式操作（后者正是误触与困惑的来源）。
 *
 * ## 两条登记流程的差异仍然存在（[SettingsViewModel.QuickUnlockVaultUi.kind]）
 *
 * - Bitwarden：选指纹 → 直接弹系统认证（密钥在会话里）；
 * - KDBX：选指纹 → **先弹主密码输入框** → 校验通过 → 再弹系统认证（§4.5）。
 *
 * ⚠️ 这是**流程**差异，不是**选项**差异：不影响三个选项本身的并列关系，
 * 只在选中指纹时由后续弹窗承担。选项副标题会就地预告这一点（KDBX 版文案不同）。
 *
 * ## 保住的既有告诫
 *
 * - ⚠️ 历史坑：此前本对话框**只能关不能开**（启用入口仅库列表页横幅），
 *   而横幅「以后再说」会永久置位 `isQuickUnlockPromptDismissed` → 用户彻底
 *   失去启用路径。三改补上了对称的「启用」动作，四改的选项点击**同样是启用入口**，
 *   该死角继续处于消除状态。
 * - ⚠️ 必须可滚动：内容随库数增长，不可滚动时底部会被截断。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuickUnlockManageDialog(
    vaults: List<SettingsViewModel.QuickUnlockVaultUi>,
    canAuthenticate: Boolean,
    onEnable: (String) -> Unit,
    onDisable: (String) -> Unit,
    onPinSet: (SettingsViewModel.QuickUnlockVaultUi) -> Unit,
    /**
     * 「改用 PIN」专用入口：设置成功后**顺带关闭该库的指纹**。
     *
     * ⚠️ 与 [onPinSet] 分开是必须的：两者语义不同（一个是"修改 PIN"、一个是"换用 PIN"），
     * 若共用一个入口，就没法区分"用户是想改 PIN"还是"想从指纹换成 PIN" ——
     * 前者绝不能关指纹（用户可能两种都想要），见 ViewModel 里那个标记的 KDoc。
     */
    onPinSetSwitchingFromBiometric: (SettingsViewModel.QuickUnlockVaultUi) -> Unit,
    onPinDisable: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    /**
     * 选中某一项 ⇒ 开启它、并关掉同一库的另一种免密方式。
     *
     * 抽成局部函数是为了让 `when` 保持一行调用，避免给 [QuickUnlockManageDialog]
     * 增加圈复杂度（本仓库 detekt `CyclomaticComplexMethod` 上限 14，且**同文件**
     * 被调函数的复杂度会累加进调用方 —— 见 `.ai/conventions/8.6`）。
     */
    fun onChoice(vault: SettingsViewModel.QuickUnlockVaultUi, choice: UnlockChoice) {
        when (choice) {
            UnlockChoice.MASTER_ONLY -> {
                if (vault.enabled) onDisable(vault.vaultId)
                if (vault.pinEnabled) onPinDisable(vault.vaultId)
            }
            UnlockChoice.BIOMETRIC -> {
                // 已经是指纹 ⇒ 空操作：重复点选不该把已登记的钥匙拆了重建。
                if (!vault.enabled) onEnable(vault.vaultId)
                if (vault.pinEnabled) onPinDisable(vault.vaultId)
            }
            UnlockChoice.PIN -> {
                // ⚠️ 两件事的顺序与时机都是刻意的：
                // 1. **不在这里关指纹** —— PIN 设置是独立流程，用户可能中途取消；
                //    先关指纹再设 PIN，取消后就成了"指纹没了、PIN 也没设成"的静默数据丢失。
                //    改为走 openPinDialogSwitchingFromBiometric，由 ViewModel 在
                //    PIN **真正落盘后**才关指纹（见该方法的 KDoc）。
                // 2. 已设过 PIN 时不重复弹框 —— 换 PIN 走「修改 PIN」辅助动作。
                if (!vault.pinEnabled) onPinSetSwitchingFromBiometric(vault)
            }
        }
    }

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
                        hint = stringResource(R.string.quick_unlock_scope_hint_pick),
                        icon = { Icon(Icons.Filled.Key, contentDescription = null) },
                    )

                    // 2026-09-15 三改（用户：「可以合并设置的」）：从"指纹段 + PIN 段
                    // 各列一遍全部库"改为**一库一卡**，同名不再出现两次。
                    // 2026-09-16 四改（用户：「还是不太好看，有没有让设置简单点的方案」）：
                    // 卡内从"两行并列开关"再收敛为"三选一"，见本函数的 KDoc。
                    vaults.forEach { vault ->
                        VaultUnlockCard(
                            vault = vault,
                            canAuthenticate = canAuthenticate,
                            onChoice = { choice -> onChoice(vault, choice) },
                            onPinSet = { onPinSet(vault) },
                        )                    }
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
 * 一个库的解锁方式三选一（**互斥呈现**，底层两布尔见 [QuickUnlockManageDialog] 的 KDoc）。
 *
 * ⚠️ 顺序即优先级：`BIOMETRIC > PIN > MASTER_ONLY`。两个都开的历史数据取**前者**，
 * 与 `when` 的判断顺序（先 `enabled` 后 `pinEnabled`）保持一致 —— 两处顺序若不一致，
 * 会出现"卡片显示指纹被选中、点一下 PIN 却把指纹关了"的错乱。
 */
private enum class UnlockChoice { BIOMETRIC, PIN, MASTER_ONLY }

/**
 * 单个密码库的解锁设置卡片：**库名 + 三选一的解锁方式**。
 *
 * ## 四改前后的结构对比（2026-09-16）
 *
 * | | 改前（三改） | 改后（四改） |
 * |---|---|---|
 * | 卡内结构 | 两行并列开关（指纹 / PIN） | 三选一（指纹 / PIN / 主密码） |
 * | 每行元素 | 图标 + 标题 + 状态徽标 + 副标题 + 动作栏（最多 2 个按钮） | 单选控件 + 标题 + 一行副标题 |
 * | 可点区域 | 最多 4 个 | 3 个（且语义互斥，不会纠结"要不要都开"） |
 * | 状态表达 | 徽标文字「已启用/未启用」+ 副标题 + 按钮，**同一件事说三遍** | 单选控件**本身**就是状态 |
 *
 * 砍掉的三样东西，都是**说明书口气**或**重复叙事**，不是信息：
 * 1. `● 已启用 / ● 未启用` 文字徽标 —— 单选控件的选中态已足够明确；
 * 2. "点此处启用 / 未启用（设备不支持）" 类操作指引 —— 讲了"怎么点"，
 *    而用户真正要判断的是"选了之后会怎样"，故副标题只讲**后果**；
 * 3. 独立的动作栏 —— 动作并入选项本身（点选项 = 启用），只在**已选中**时
 *    才需要暴露辅助动作（PIN 的「修改」/「关闭」）。
 *
 * ⚠️ 保住的告诫：`Card` 底色必须比 `DialogSurface` 面板（`surfaceContainerHigh`）
 * **低**一档，否则同色相叠、卡片边界消失（三改"毛坯房"归因第 1 条）。
 *
 * ⚠️ 可见性为 `private`（四改前是 `internal`）：参数里的 [UnlockChoice] 是本文件私有的
 * 枚举，`internal` 函数暴露 `private` 类型会编译报错（"exposes its private type"）。
 * 而本组件四改后**只被同文件的 [QuickUnlockManageDialog] 调用**，无需跨文件可见。
 */
@Composable
private fun VaultUnlockCard(
    vault: SettingsViewModel.QuickUnlockVaultUi,
    canAuthenticate: Boolean,
    onChoice: (UnlockChoice) -> Unit,
    onPinSet: () -> Unit,
) {
    // ⚠️ 顺序即优先级，与 UnlockChoice 的枚举顺序一致（见其 KDoc）。
    val selected = when {
        vault.enabled -> UnlockChoice.BIOMETRIC
        vault.pinEnabled -> UnlockChoice.PIN
        else -> UnlockChoice.MASTER_ONLY
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        shape = RoundedCornerShape(VAULT_ROW_CORNER),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm)) {
            // ---- 卡片头：库名 ----
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
                    top = Spacing.xs,
                    bottom = Spacing.xs,
                ),
            )

            UnlockChoiceRow(
                title = stringResource(R.string.quick_unlock_section_biometric),
                summary = stringResource(biometricSummaryRes(vault, canAuthenticate)),
                selected = selected == UnlockChoice.BIOMETRIC,
                // 设备不支持认证时不给点：点了也走不完流程，允许点等于给出
                // 一个必然失败的承诺（副标题已说明原因）。
                enabled = canAuthenticate,
                onSelect = { onChoice(UnlockChoice.BIOMETRIC) },
            )

            UnlockChoiceRow(
                title = stringResource(R.string.pin_section_title),
                summary = stringResource(R.string.quick_unlock_option_pin_summary, PIN_MIN_LENGTH),
                selected = selected == UnlockChoice.PIN,
                enabled = true,
                onSelect = { onChoice(UnlockChoice.PIN) },
            )

            UnlockChoiceRow(
                title = stringResource(R.string.quick_unlock_master_only),
                summary = stringResource(R.string.quick_unlock_option_master_summary),
                selected = selected == UnlockChoice.MASTER_ONLY,
                enabled = true,
                onSelect = { onChoice(UnlockChoice.MASTER_ONLY) },
            )

            // 已选中的 PIN 才需要辅助动作。
            // ⚠️ 这里**只留「修改 PIN」**，不放「关闭 PIN 解锁」：单选模型下"关闭"
            //    等价于"改成别的选项"，与下面第三个选项语义重复，摆出来反而让用户
            //    困惑"这和「每次输主密码」有什么区别"。关闭交给点第三个选项完成 ——
            //    这正是单选模型带来的简化，不要退回去再摆一个关闭按钮。
            if (selected == UnlockChoice.PIN && vault.pinEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(
                        start = Spacing.lg,
                        end = Spacing.lg,
                        bottom = Spacing.xs,
                    ),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onPinSet) {
                        Text(stringResource(R.string.quick_unlock_action_change_pin))
                    }
                }
            }
        }
    }
}

/**
 * 指纹选项的副标题。
 *
 * ⚠️ 判断顺序不能乱：
 * 1. **已启用优先**：密钥已建立，再说"需输主密码以建立钥匙"是误导
 *    （用户会以为要重来一遍）。故 `vault.enabled` 必须排在最前。
 * 2. 设备不支持其次：此时给通用说明，不泄漏 KDBX 的流程细节（反正也用不了）。
 * 3. 最后才按 kind 分：KDBX 待启用时需要预告"会再要一次主密码"，
 *    否则用户点下去突然弹密码框会困惑（这正是原本 quick_unlock_disabled_kdbx 的职责）。
 *
 * 抽成函数是因为它是本卡唯一需要判断的分支 —— 留在 [VaultUnlockCard] 里会与
 * `selected` 的 `when` 叠加，把 Compose 主函数的圈复杂度推过 14（见 8.6 的实测结论）。
 */
private fun biometricSummaryRes(
    vault: SettingsViewModel.QuickUnlockVaultUi,
    canAuthenticate: Boolean,
): Int = when {
    vault.enabled -> R.string.quick_unlock_option_biometric_on
    !canAuthenticate -> R.string.quick_unlock_option_biometric_unsupported
    vault.kind == VaultKind.KDBX -> R.string.quick_unlock_option_biometric_kdbx_summary
    else -> R.string.quick_unlock_option_biometric_summary
}

/**
 * 卡内的一行**单选项**（无圆角外壳，归属 [VaultUnlockCard] 的卡片）。
 *
 * ## 为什么不用现成的 `RadioButton` 行样式
 *
 * 本项目的对话框宽度本就窄（`DIALOG_WIDTH_RATIO = 0.92`，且面板内还有
 * `Spacing.lg` 双侧留白），M3 的 `RadioButton` 自带 48dp 最小触达尺寸与
 * 较大内边距，三项叠起来会把卡片撑得很高 —— 而"简单"的一大来源就是**看得完**。
 *
 * 这里改用**自绘圆点**（16dp）+ 整行可点：视觉更紧凑，触达区域反而更大
 * （整行都是点击区，不受 48dp 图标束缚）。同时自带无障碍语义
 * （`selectableGroup` + `selectable` + `Role.RadioButton`），不影响 TalkBack。
 *
 * ⚠️ 传 `enabled = false` 时使用 `disabledAlpha`：**必须仍然可见**，
 * 因为"设备不支持"本身是需要传达的信息，而不是该被隐藏的行。
 */
@Composable
private fun UnlockChoiceRow(
    title: String,
    summary: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    val contentAlpha = if (enabled) 1f else DISABLED_ALPHA
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UnlockChoiceIndicator(
            selected = selected,
            enabled = enabled,
        )
        Spacer(Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
    }
}

/**
 * 单选项的**自绘指示器**（选中 = 实心圆 + 外环；未选中 = 仅外环）。
 *
 * ⚠️ 不用 `RadioButton`：它带 48dp 最小尺寸与额外内边距，三个选项会把卡片撑高
 * （见 [UnlockChoiceRow] 的 KDoc）。
 */
@Composable
private fun UnlockChoiceIndicator(selected: Boolean, enabled: Boolean) {
    val alpha = if (enabled) 1f else DISABLED_ALPHA
    val accent = MaterialTheme.colorScheme.primary.copy(alpha = alpha)
    val ring = when {
        !enabled -> MaterialTheme.colorScheme.outline.copy(alpha = DISABLED_ALPHA)
        selected -> accent
        else -> MaterialTheme.colorScheme.outline
    }
    Box(
        modifier = Modifier.size(CHOICE_INDICATOR_SIZE),
        contentAlignment = Alignment.Center,
    ) {
        // 外环
        Box(
            modifier = Modifier
                .size(CHOICE_INDICATOR_SIZE)
                .border(width = CHOICE_RING_WIDTH, color = ring, shape = CircleShape),
        )
        // 内点（仅选中时）
        if (selected) {
            Box(
                modifier = Modifier
                    .size(CHOICE_DOT_SIZE)
                    .background(color = accent, shape = CircleShape),
            )
        }
    }
}

/** 单选项指示器外径。 */
private val CHOICE_INDICATOR_SIZE = 20.dp

/** 单选项指示器外环宽度。 */
private val CHOICE_RING_WIDTH = 2.dp

/** 单选项选中时的内点直径（外径 20 - 两侧环宽 2×2 - 视觉留白 4）。 */
private val CHOICE_DOT_SIZE = 10.dp

/**
 * 不可用行的整体透明度。
 *
 * ⚠️ 不可用的**指纹选项**仍需完整可读（"设备不支持"是要传达的信息，不是该藏起来的东西），
 * 只是降低对比度表示"点不动"。故取 0.38（M3 disabled 的推荐量级）而非更低。
 */
private const val DISABLED_ALPHA = 0.38f

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
