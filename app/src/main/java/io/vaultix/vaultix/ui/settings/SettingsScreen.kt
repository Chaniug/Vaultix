package io.vaultix.vaultix.ui.settings

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.Numbers
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
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
import io.vaultix.vaultix.util.SystemSettingsIntents
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
    /**
     * 「关于」分区：进入**权限引导**二级页。
     *
     * ⚠️ 2026-09-18 行为变更：原先这一行**直接跳系统应用信息页**（`openAppPermissionSettings`），
     * 现在先进一个应用内的二级页把「要什么权限、做什么用」讲清楚，再由那一页给出口。
     * 理由：系统页只列权限名与开关，不解释**为什么**要 —— 而用户对密码管理器的权限
     * 恰恰是最有戒心的，不解释就等于心虚。
     *
     * ⚠️ **不给默认值**（与 [onOpenAutofillSettings] 一致）：设置页有**两个**调用点
     * （独立 `SettingsRoute` 与主界面设置 Tab），给了默认空实现会让 Tab 那一处
     * **静默失效**（点了没反应，且编译期发现不了）。
     */
    onOpenPermissions: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showAutoLockDialog by rememberSaveable { mutableStateOf(false) }
    var showClipboardDialog by rememberSaveable { mutableStateOf(false) }
    var showAboutDialog by rememberSaveable { mutableStateOf(false) }
    // 开发者日志对话框（2026-09-21）。⚠️ 状态本身不区分构建类型；**入口**才做 debug 门禁，
    // 这样 release 包里既看不到入口、也不会多一条可达路径。
    var showDeveloperLogs by rememberSaveable { mutableStateOf(false) }

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
            )

            // ---- 关于（权限管理已并入，原「其他」组仅此一行）----
            AboutSection(
                context = context,
                // ⚠️ **版本号这一行只放 versionName；内部版本号另起一行。**（2026-09-21 修订）
                //
                // 历史：2026-09-14 曾把两者拼成 `0.3.0 (3000)`，用户报告「那个括号长得很像
                // 浏览器给重复下载加的后缀 (1)」⇒ 当时决定**只显示 versionName**。
                // 2026-09-21 用户又要求「关于页内容丰富」，于是改回**展示** versionCode，
                // 但**换一种呈现**：独立成行的「内部版本号」+ 标签。
                // ⇒ 误读的成因是"括号 + 无标签"，不是"显示了 versionCode"本身；
                //    标签化之后含义自明，那条原始担忧不再成立。
                // ⚠️ 不要改回括号拼接（会重新触发 2026-09-14 的误读）。
                versionName = BuildConfig.VERSION_NAME,
                // `VERSION_CODE` = `X*1_000_000 + Y*1_000 + Z`（见 app/build.gradle.kts），
                // 是**给系统判断新旧**用的，不是构建计数器；同一 VERSION 下的多个 dev 构建
                // 它的值**相同**（区分构建要靠 versionName 里的短 sha）。
                buildNumber = BuildConfig.VERSION_CODE,
                onOpenPermissions = onOpenPermissions,
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
            // ★ 2026-09-21：开发者模式入口，**仅 debug 构建渲染**。
            // release 包连入口都不出现 —— 不误导用户，也不多一条可达路径。
            if (BuildConfig.DEBUG) {
                DeveloperSection(onOpenLogs = { showDeveloperLogs = true })
            }
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
    if (showDeveloperLogs) {
        DeveloperLogsDialog(onDismiss = { showDeveloperLogs = false })
    }
    if (showUpdateDialog) {
        UpdateCheckDialog(
            checking = updateChecking,
            result = updateResult,
            error = updateError,
            onOpenRelease = { url -> SystemSettingsIntents.openUrl(context, url) },
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
                SettingsSwitch(
                    value = state.screenSecurity,
                    onCheckedChange = viewModel::setScreenSecurity,
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
    buildNumber: Int,
    onOpenPermissions: () -> Unit,
    onShowLicense: () -> Unit,
    onCheckUpdate: () -> Unit,
) {
    SettingsGroupTitle(stringResource(R.string.group_about))
    SettingsGroupCard {
        // 权限管理原属「其他」组（该组仅此一行，独占一个组标题）
        // ⇒ 并入「关于」：它本来就是"关于这个 App"的元信息（定稿 §2②）。
        // ⚠️ 2026-09-18：点击目标从「系统应用信息页」改为「应用内权限引导页」
        //    （见 [onOpenPermissions] 的说明）。组与行标题都不动（定稿已固定）。
        SettingsRow(
            icon = { Icon(Icons.Filled.Policy, contentDescription = null) },
            title = stringResource(R.string.permission_management_title),
            subtitle = stringResource(R.string.permission_management_subtitle),
            onClick = onOpenPermissions,
        )
        SettingsDivider()
        SettingsRow(
            icon = { Icon(Icons.Filled.Info, contentDescription = null) },
            title = stringResource(R.string.about_version),
            // ★ 2026-09-21 用户要求「关于里面的内容可以合并精简」：
            // 把「内部版本号」「更新渠道」折回**同一行**的副标题，而不是各占一行。
            // ⚠️ 仍然**不写成 `0.5.0 (5000000)` 那种括号后缀**（2026-09-14 用户曾把括号
            //    误读成下载器加的 `(1)`）——这里显式带「内部版本」标签，语义自明。
            // ⚠️ 渠道用短文案（预览版 / 正式版），长解释留在 `about_channel_preview` 那份里，
            //    避免副标题过长被省略号截断。
            subtitle = buildString {
                append(versionName)
                append(" · ")
                append(stringResource(R.string.about_build_number))
                append(' ')
                append(buildNumber)
                append(" · ")
                append(
                    stringResource(
                        if (BuildConfig.DEBUG) {
                            R.string.about_channel_preview_short
                        } else {
                            R.string.about_channel_stable_short
                        },
                    ),
                )
            },
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
            onClick = { SystemSettingsIntents.openUrl(context, context.getString(R.string.about_github_url)) },
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
 * 开发者组（**仅 debug 构建**渲染，2026-09-21）。
 *
 * 为什么单独成组、不并进「关于」：两者回答的是不同的问题 ——
 * 「关于」答"这个 App 是什么"，「开发者」答"出问题时怎么办"。
 * 混在一起会让"关于"里出现一个与元信息无关的可点项，且 release 包整组都会消失，
 * 语义上分开更干净。
 */
@Composable
private fun DeveloperSection(onOpenLogs: () -> Unit) {
    SettingsGroupTitle(stringResource(R.string.group_developer))
    SettingsGroupCard {
        SettingsRow(
            icon = { Icon(Icons.Filled.Build, contentDescription = null) },
            title = stringResource(R.string.developer_logs_entry),
            subtitle = stringResource(R.string.developer_logs_entry_desc),
            onClick = onOpenLogs,
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
                SettingsSwitch(
                    value = dynamicColor,
                    onCheckedChange = viewModel::setDynamicColor,
                )
            },
        )
        SettingsDivider()
        SettingsRow(
            icon = { Icon(Icons.Filled.Contrast, contentDescription = null) },
            title = stringResource(R.string.setting_oled_pure_black),
            subtitle = stringResource(R.string.setting_oled_pure_black_desc),
            trailing = {
                // ⚠️ 2026-09-26 由裸 Switch 改为 SettingsSwitch：本页其它开关（动态取色 /
                // 防截屏）都走「数据未到达时不猜状态」，唯独这一处漏了 —— 而它恰恰是
                // #84「三种空」里点名过的那个开关。同一页两种行为本身就是 bug。
                SettingsSwitch(
                    value = oledPureBlack,
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
 * 单选对话框：主题模式三态（跟随系统 / 浅色 / 深色）。
 */
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
