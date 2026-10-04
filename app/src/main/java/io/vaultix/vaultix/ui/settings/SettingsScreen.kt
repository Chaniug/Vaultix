package io.vaultix.vaultix.ui.settings

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
import androidx.compose.material.icons.filled.Build
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
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.mikepenz.markdown.m3.Markdown
import io.vaultix.domain.PIN_MIN_LENGTH
import io.vaultix.vaultix.BuildConfig
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.common.BiometricPrompter
import io.vaultix.vaultix.ui.common.deviceCanAuthenticate
import io.vaultix.vaultix.ui.common.DialogCloseButton
import io.vaultix.vaultix.ui.items.DisplayOptionsSheet
import io.vaultix.vaultix.ui.common.TopBarTitle
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
     * 「解锁方式」分区：进入**解锁方式**二级页（[UnlockMethodScreen]）。
     *
     * ★ 2026-09-30 晚新增：此前这一行**导航到 [VaultManagementRoute]（同一页）**，
     * 因为它当时是那一页里的一个组 ⇒ 两行入口指向同一处（用户真机反馈：
     * 「密码库设置和解锁方式打开好像都是同一个页面，这不对吧」）。
     * 这两件事的**作用域不同**（库 = 逐个；门锁 = 全局），故各自成页。
     *
     * ⚠️ **不给默认值**（与 [onOpenPermissions] 同一条纪律）：设置页有**两个**调用点
     * （独立 `SettingsRoute` 与主界面设置 Tab），给了默认空实现会让 Tab 那处**静默失效**。
     */
    onOpenUnlockMethod: () -> Unit,
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
    /**
     * 「关于」分区：进入**关于**二级页（源码仓库 / 反馈渠道 / 开源许可）。
     *
     * ★ 2026-09-28 新增：原先「源码与反馈」「开源许可」是设置首页上的两行，
     * 现按用户要求合并为一行入口，内容收进 [AboutAppScreen]（主流开源 App 的做法）。
     *
     * ⚠️ 与 [onOpenPermissions] 同理**不给默认值**：两个调用点都要显式接线，
     * 否则 Tab 那处会静默失效。
     */
    onOpenAbout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // ★ 2026-09-30 晚：「自动锁定」（全局默认档位）**已搬进「锁与安全」页**
    //   （[UnlockMethodScreen]）—— 它本就是"锁的超时"，与锁的方式同屏更合适
    //   （Android「设置 → 安全 → 屏幕锁定」亦如此）。本页不再持有它的状态与对话框。
    var showClipboardDialog by rememberSaveable { mutableStateOf(false) }
    // ⚠️ 原本这里还有一个 `showAboutDialog`（开源许可对话框）。2026-09-28 随「关于」组
    //    精简，许可内容搬进 [AboutAppScreen]，本页那个对话框**再也无人触发** ——
    //    留着它就是一段不可达代码（detekt 的 `UnusedParameter` 顺着 `onShowLicense`
    //    把它揪了出来）。已整体删除，别再在设置首页重建。
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
            // 本组现在是**入口区**：库管理 / 锁与安全（门锁 + 自动锁定）/ 防截屏 / 剪贴板 / 权限。
            // ⚠️ 2026-09-30 晚：「自动锁定」**搬进「锁与安全」页**（它是锁的超时），
            //    本页相应少一行 —— 见 [UnlockMethodScreen] 的 KDoc。
            VaultUnlockSection(
                viewModel = viewModel,
                state = state,
                onOpenVaultManagement = onOpenVaultManagement,
                onOpenUnlockMethod = onOpenUnlockMethod,
                onClipboardClear = { showClipboardDialog = true },
                // ★ 2026-09-28：权限管理从「关于」移入本组（见 VaultUnlockSection 的注释）。
                onOpenPermissions = onOpenPermissions,
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

            // ---- 关于（权限管理已移入「密码库与解锁」；2026-09-28 精简为 2 行）----
            AboutSection(
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
                onOpenAbout = onOpenAbout,
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
    if (showDeveloperLogs) {
        DeveloperLogsDialog(onDismiss = { showDeveloperLogs = false })
    }
    if (showUpdateDialog) {
        UpdateCheckDialog(
            checking = updateChecking,
            result = updateResult,
            error = updateError,
            // ⚠️ 传取值函数（不是快照值）：`?: false` 的兜底语义 = 偏好还没读出来时
            //    按"关闭镜像"处理 —— 拿假值当作"已开启"会让下载地址被悄悄代理。
            isMirrorEnabled = { viewModel.updateUseMirror.value ?: false },
            onOpenRelease = { url -> SystemSettingsIntents.openUrl(context, url) },
            onDismiss = { showUpdateDialog = false },
        )
    }
}

/**
 * 「检查更新」结论对话框。
 *
 * 做四件事：告诉用户**当前是哪个版本**、**远端是哪个版本**、**这一版改了什么**
 * （可滚动的更新日志）、**去哪下载**。
 *
 * 不在 App 内下载 / 安装 APK —— 对一个密码管理器来说，自动装上来路不明的安装包
 * 是比"多两步"严重得多的风险（理由见 [UpdateChecker] 的文件头）。
 *
 * ## 2026-09-28 改造（用户要求）
 *
 * 1. **更新日志**：`result.releaseNotes`（GitHub Release 的 GFM Markdown 原文）在一个
 *    **限高可滚动区**里用 Markdown 渲染器呈现。为什么必须限高而不是直出：release notes
 *    动辄几十行（分类标题 + 列表 + 链接），直出会把对话框撑爆、按钮被挤出屏幕。
 *    ⚠️ 用 `heightIn(max = …)` 而不是固定高度：短日志（两行）不该留一大块空白。
 *
 * ## 🔴 2026-09-28 二改：按 M3 的对话框构成收敛（用户问「是否符合安卓开发标准」）
 *
 * 用户问得对 —— 第一版有**两处不符合 Material 3**：
 *
 * 1. **对话框里放了持久化 `Switch`（国内镜像）**。M3 的对话框构成只有
 *    「标题 + 正文 + 最多 3 个动作」，它是**为一次决定**服务的；而那个开关是**持久设置**
 *    （写 DataStore、跨重启保持）—— 把一个设置项塞进瞬时的对话框，用户关掉对话框后
 *    就再也找不到它了，既不合规范也不可发现。
 *    ⇒ 已把它移到「关于」二级页，作为一个**标准设置行**（[SettingsRow] + `Switch`）。
 *    本对话框回到"只报告结果 + 两个动作"。
 * 2. **关闭动作写成「取消」**。这里没有任何可取消的操作（检查已经跑完了），
 *    「取消」会被读成"取消这次检查"，但检查其实已完成。M3 要求动作文案**如实描述结果**
 *    ⇒ 改为「关闭」([R.string.action_close])。
 *
 * ⚠️ **保留**更新日志在对话框内：长内容放对话框确实不是 M3 的首选（更规范的是独立页面
 * 或底部弹层），但"更新说明随版本一起展示"是业界通行做法，且已限高 + 可滚动；
 * 本项目**刻意不用底部弹层**（全仓库仅 1 处弹层，其余 25 处都是弹窗形态，
 * 见 `DialogShell.kt` 的取舍），改成弹层会立刻显得"不是同一个 App"。
 * ⇒ 这是一处**知情的偏离**，不是遗漏。
 *
 * @param onOpenRelease 打开下载页（传入的已是**经镜像转换后**的最终地址）。
 */
@Composable
private fun UpdateCheckDialog(
    checking: Boolean,
    result: UpdateCheckResult?,
    error: String?,
    /**
     * 「国内镜像」当前是否开启 —— 传**取值函数**而不是 `Boolean`。
     *
     * 开关已移出对话框（改到「关于」二级页），对话框与它不再有状态通道；
     * 而「前往下载」**必须**尊重用户的当前偏好。传函数 = 在点击那一刻才取值，
     * 因此即使用户刚在别处改过，也不会拿到对话框打开时的旧快照。
     */
    isMirrorEnabled: () -> Boolean,
    onOpenRelease: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 只在"确实有更新"时才展示日志区：已是最新时 GitHub 那段正文讲的是
    // "这一版改了什么"，与本机无关，摆出来只会让用户以为"我该更新"
    // （见 UpdateChecker 里 releaseNotes 只在有更新时填）。
    //
    // ⚠️ 用 `takeIf` 拿到**非空的结果对象本身**，而不是一个 `Boolean` 标志：
    //    标志位会把"有更新"这个事实与 `result` 脱钩，编译器于是无法把 `result`
    //    智能转换为非空 ⇒ 后面每处引用都得写 `result?.xxx`（Kotlin 也会就此发
    //    "Unnecessary safe call" 警告：它知道那里不可能是空，我们却在防)。
    //    提着对象走，**让类型系统承载这个保证**，`?.` 与警告就都不需要了。
    val updated = result?.takeIf { it.isUpdateAvailable }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_check_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                // ---- 版本结论 ----
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

                // ---- 更新日志（只在"确实有更新"时）----
                if (updated != null) {
                    Text(
                        text = stringResource(R.string.update_check_whats_new),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = Spacing.sm),
                    )
                    UpdateNotesBox(notes = updated.releaseNotes)
                }
            }
        },
        confirmButton = {
            // ⚠️ **只在"确实有更新"时**才给「前往下载」：动作必须与状态匹配。
            //    - 已是最新 → 没有可下载的东西，摆一个「前往下载」是多余且误导的
            //      （用户会以为有新版本没装）；此时只剩「关闭」一个动作；
            //    - 检查中 → 不给按钮（点了也只是等待）；
            //    - 检查失败 / 无法确定 → 同上，不诱导用户去下载。
            //    （M3：对话框动作应如实反映当前状态下**可做**的事。）
            if (updated != null) {
                TextButton(
                    onClick = {
                        // ⚠️ 镜像开关已移出对话框（见本函数 KDoc）：点击那一刻才读**当前**偏好，
                        //    而不是拿对话框打开时的快照 —— 开关是持久设置，随时可能被改。
                        onOpenRelease(
                            UpdateChecker.mirrorUrl(updated.releaseUrl, isMirrorEnabled()),
                        )
                    },
                ) {
                    Text(stringResource(R.string.update_check_go))
                }
            }
        },
        // ⚠️ 用「关闭」而不是「取消」：这里没有可取消的操作（检查已跑完），
        //    「取消」会被读成"取消这次检查"。M3 要求动作文案如实描述结果。
        dismissButton = { DialogCloseButton(onClick = onDismiss) },
    )
}

/**
 * 更新日志渲染区。
 *
 * ## 为什么是一个自绘容器而不是裸 [Markdown]
 *
 * 两件事必须由外层容器负责，Markdown 渲染器本身不提供：
 * 1. **限高**：日志长度不可控（GitHub 上有人写一整篇），必须给一个上界，
 *    否则对话框会被撑到屏幕外。用 `heightIn(max = …)` 让短日志自然收缩。
 * 2. **视觉边界**：日志是"引用来的内容"，与 App 自己的文案不是一回事。给它一层
 *    `surfaceContainerHighest` 底色 + 圆角，用户一眼就知道"这块是 GitHub 上的原文"。
 *
 * ## ⚠️ 这里**必须自带** `verticalScroll`（与上一版相反，2026-09-28 改回）
 *
 * 本块现在位于 `AlertDialog` 的 `text` 槽里，而**该槽自身不滚动**。
 * 于是外壳按内容收缩、日志超长则由**本块自己**滚 —— 这正是"卡片大小"的由来：
 * 短日志（两行）时整块只有两行高，长日志时到 [UPDATE_NOTES_MAX_HEIGHT] 封顶后内部滚。
 *
 * ⚠️ 上一版我曾**去掉**这里的 `verticalScroll`，理由是"外层已经在滚、同方向嵌套会崩"。
 * 那个顾虑本身没错，但外层滚动是**我自己加的**（当时用了 `DialogSurface` 且让其正文滚动）
 * —— 是自造约束，不是 `AlertDialog` 的限制。改用 `AlertDialog` 后外层不再滚，
 * 内层滚动既安全又必需。（教训：把"我这么做所以不能那样"写成了"那个控件不能这样"。）
 *
 * ## 为什么正文直接用 `Markdown(content)` 而不传自定义 colors/typography
 *
 * `-m3` 版本的 `Markdown` 默认样式已经吃 `MaterialTheme`（颜色 / 字体全走主题），
 * 传自定义值反而要在两处之间来回对账（主题改了忘改这里 → 观感漂移）。
 * **能用默认就用默认**，只有确实需要压字号时再引入 `markdownTypography`。
 *
 * ## `notes` 为空时的兜底
 *
 * 有更新但 Release 没写正文（CI 自动生成的预览版常见）→ 显示一句
 * [R.string.update_check_notes_empty]，而不是留一块空白（空白会被读成"渲染坏了"）。
 */
@Composable
private fun UpdateNotesBox(notes: String?) {
    val hasNotes = !notes.isNullOrBlank()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // ⚠️ 不加 horizontal padding：外层 `AlertDialog` 的 text 槽已有自己的内边距，
            //    再叠一个 Spacing.xl 会把日志区挤得比正文窄一截（观感像缩进错了）。
            //    这个 padding 是上一版配 `DialogSurface` 时加的，随外壳更换一并去掉。
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(12.dp),
            )
            // ⚠️ 顺序：先 `heightIn(max)` 给上界，再 `verticalScroll` —— 反过来的话
            //    滚动容器会拿到无界高度约束，measure 阶段直接抛 IllegalStateException。
            .heightIn(max = UPDATE_NOTES_MAX_HEIGHT)
            .verticalScroll(rememberScrollState())
            .padding(Spacing.md),
    ) {
        if (hasNotes) {
            // ⚠️ **必须显式传 `modifier`**：库的默认值是 `Modifier.fillMaxSize()`，
            //    在上面的 `heightIn(max)` 约束下它会撑满到上界 —— 结果是
            //    两行日志也占一整块底色，看起来像"渲染出一大块空白"。
            //    传 `fillMaxWidth()` 让它按内容高度收缩，`heightIn(max)` 只在上界兜底。
            Markdown(content = notes.orEmpty(), modifier = Modifier.fillMaxWidth())
        } else {
            Text(
                text = stringResource(R.string.update_check_notes_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 更新日志滚动区的高度上界。
 *
 * 取值理由：对话框已改成**按内容收缩的 `AlertDialog`**（不再是 0.8 屏高的面板），
 * 所以这个上界不再是"填满面板剩下的空"，而是**限制卡片不要长成整屏**：
 * 版本结论 + 标题 + 按钮约占 200dp，日志再给 240dp ⇒ 最长约 440dp，
 * 约三分之一屏 —— 够看十来行，又不至于把"发现新版本 x.y.z"（**主信息**）挤出视野。
 * 超过就走日志区内部滚动。
 */
private val UPDATE_NOTES_MAX_HEIGHT = 240.dp

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
        title = TopBarTitle(stringResource(R.string.settings_title)),
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
    /** ★ 2026-09-30 晚：「锁与安全」已是**独立页**（此前与库管理页同址）。 */
    onOpenUnlockMethod: () -> Unit,
    onClipboardClear: () -> Unit,
    onOpenPermissions: () -> Unit,
) {
    val active by viewModel.activeVault.collectAsStateWithLifecycle()
    // D7（多库锁模型定稿批次 C，2026-09-30）：「解锁方式」行的副标题 = 指纹/PIN 当前状态。
    // 控制器本就是 lazy 的，设置页是它的宿主，这里收集不引入新的生命周期。
    val quickUnlockState by viewModel.quickUnlock.state.collectAsStateWithLifecycle()
    val canAuthenticate = deviceCanAuthenticate(LocalContext.current)
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
        // ★ D7（多库锁模型定稿批次 C，2026-09-30）：「解锁方式」上提到设置首页。
        //   指纹/PIN 两把门锁是**全局**的 —— 管的是整栋房子（全部库），不挑库。
        //   ★ 2026-09-30 晚修正：它**有独立的一页**（[UnlockMethodScreen]）——
        //     在此之前这行导航到的是"密码库管理"那一页（它当时是那页里的一个组），
        //     于是两行入口打开同一个页面（用户真机反馈「这不对吧」）。
        //     作用域不同就该各成页：库 = 逐个；门锁 = 全局（与系统 Keyguard 同构）。
        SettingsRow(
            icon = { Icon(Icons.Filled.Key, contentDescription = null) },
            title = stringResource(R.string.lock_and_security_title),
            subtitle = unlockMethodSubtitle(quickUnlockState, canAuthenticate),
            onClick = onOpenUnlockMethod,
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
        SettingsDivider()
        // ★ 2026-09-28：从「关于」组移入本组（用户拍板）。
        //   理由：用户是在**权限 / 隐私**语境下找它，而不是在"这个 App 是什么"语境下。
        //   本组语义本就是"库怎么开、开了怎么锁、隐私怎么护"，权限管理正落在最后一项。
        // ⚠️ 定稿 `设置页信息架构-定稿.md` §2② 曾把它并进「关于」，本轮**刻意推翻**
        //   （定稿的结构目标"消灭单行组"不受影响：本组移入后仍 ≥2 行）。
        // ⚠️ 2026-09-18：点击目标从「系统应用信息页」改为「应用内权限引导页」
        //    （见 [onOpenPermissions] 的说明）。
        SettingsRow(
            icon = { Icon(Icons.Filled.Policy, contentDescription = null) },
            title = stringResource(R.string.permission_management_title),
            subtitle = stringResource(R.string.permission_management_subtitle),
            onClick = onOpenPermissions,
        )
    }
}

/**
 * 「解锁方式」行的副标题（D7）：指纹 / PIN 两把门锁的当前状态，一行汇总。
 *
 * ⚠️ 与库管理页两个开关行的副标题**同源不同形**：那边逐行说明（含生效库数），
 * 首页这里只要"开没开"。刻意不复用 `QuickUnlockDialogs` 的 `biometricSummary`——
 * 那个带 `readyCount`（数字随库表波动），首页行不需要那么细，复用反而把两处
 * 文案耦合在一起。
 */
@Composable
private fun unlockMethodSubtitle(
    state: QuickUnlockController.UiState,
    canAuthenticate: Boolean,
): String = stringResource(
    R.string.settings_unlock_method_summary_fmt,
    when {
        // 设备不支持认证 → 「不可用」优先（先说原因，同 biometricSummary 的顺序纪律）。
        !canAuthenticate -> stringResource(R.string.settings_unlock_state_unavailable)
        state.biometric is QuickUnlockController.CapabilityState.On ->
            stringResource(R.string.settings_unlock_state_on)
        else -> stringResource(R.string.settings_unlock_state_off)
    },
    when (state.pin) {
        is QuickUnlockController.CapabilityState.On ->
            stringResource(R.string.settings_unlock_state_on)
        else -> stringResource(R.string.settings_unlock_state_off)
    },
)

/**
 * 关于组（2026-09-28 精简：5 行 → 2 行）。
 *
 * ## 本轮精简（用户原话：「关于部分感觉还是很冗余」）
 *
 * | 原 | 现 |
 * |---|---|
 * | 权限管理（独立一行） | **移入「密码库与解锁」组**（用户拍板，见下） |
 * | 版本 + 检查更新（两行） | **合并为一行**：副标题给版本信息，点击即检查更新 |
 * | 源码与反馈 + 开源许可（两行） | **合并为一行**「关于」→ 二级页 [AboutAppScreen] |
 *
 * ⚠️ **推翻 `设置页信息架构-定稿.md` §2②**：定稿当初把「权限管理」从「其他」组并入本组
 * （理由：它是"关于这个 App"的元信息）。2026-09-28 用户要求移入「安全」类分组
 * —— 理由同样成立且更贴合**用户找它的心智**：用户是在"权限/隐私"语境下找它，
 * 不是在"这个 App 是什么"语境下找。定稿的**结构目标（消灭单行组）不受影响**：
 * 移入后「密码库与解锁」组仍 ≥2 行，「关于」组仍有 ≥2 行。
 *
 * ## 为什么入口在设置页
 *
 * Bastion 的多库是主界面里的一个**筛选维度**（`UnifiedCategoryFilterSelection`
 * 把库与文件夹 / 分类平级），而 Vaultix 是「登录时二选一」的**单活跃库**语义 —— 主界面
 * 不该出现库的概念，于是把多库入口整体下沉到设置页
 * （Docs/progress/main-shell-migration.md §0 与 A4）。
 *
 * 「检查更新」是 2026-09-16 从 Bastion 搬来的最后一块拼图：Vaultix 的分发渠道就是
 * GitHub Release，用户本来就得去那下载；这里只回答「有没有比本机新的构建」并给出
 * 发布页链接，**不在 App 内下载安装**（理由见 [UpdateChecker] 的文件头）。
 *
 * ⚠️ 参数里**没有** `onShowLicense` / `context`（2026-09-28 删）：许可对话框已随
 * 「源码与反馈 + 开源许可 → 关于一行」的合并搬进 [AboutAppScreen]。它们留在这里
 * 就是两个**无人使用**的参数，detekt `UnusedParameter` 会拦。
 */
@Composable
private fun AboutSection(
    versionName: String,
    buildNumber: Int,
    onOpenAbout: () -> Unit,
    onCheckUpdate: () -> Unit,
) {
    SettingsGroupTitle(stringResource(R.string.group_about))
    SettingsGroupCard {
        SettingsRow(
            // ★ 2026-09-28：图标从 `Refresh` 改为 `Info` —— 这一行现在**同时代表版本与更新**
            //    （点击才触发检查），用「刷新」图标会让人以为它是"改动"而非"查看 + 检查"。
            icon = { Icon(Icons.Filled.Info, contentDescription = null) },
            title = stringResource(R.string.about_version),
            // ★ 2026-09-28：版本信息折进副标题，点击**即**检查更新（原来要另起一行再点）。
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
            onClick = onCheckUpdate,
        )
        SettingsDivider()
        SettingsRow(
            // ★ 2026-09-28：合并原「源码与反馈」+「开源许可」两行 ⇒ 一个「关于」入口。
            //    这也是主流开源 App 的做法（一个 About 页装下仓库 / 反馈 / 许可证）。
            icon = { Icon(Icons.Filled.Info, contentDescription = null) },
            title = stringResource(R.string.about_entry_title),
            subtitle = stringResource(R.string.about_entry_desc),
            onClick = onOpenAbout,
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


/** 剪贴板清除毫秒 → 文案。 */
@Composable
private fun clipboardClearLabel(ms: Long): String = when (ms) {
    0L -> stringResource(R.string.clipboard_clear_off)
    else -> stringResource(
        R.string.clipboard_clear_seconds_fmt,
        ms / AutoLockPresets.MS_PER_SECOND,
    )
}

