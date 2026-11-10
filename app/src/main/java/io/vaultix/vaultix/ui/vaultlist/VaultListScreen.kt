package io.vaultix.vaultix.ui.vaultlist

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.delay
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.vaultix.domain.VaultSyncStatus
import io.vaultix.model.KdbxCloudSyncStatus
import io.vaultix.model.VaultKind
import io.vaultix.model.VaultSummary
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.AppFlavor
import io.vaultix.vaultix.ui.common.AddVaultTypeDialog
import io.vaultix.vaultix.ui.common.KdbxConflictChoice
import io.vaultix.vaultix.ui.common.KdbxConflictDialog
import io.vaultix.vaultix.ui.common.deviceCanAuthenticate
import io.vaultix.vaultix.ui.common.KdbxSourceTarget
import io.vaultix.vaultix.ui.common.kdbxSourceTargetOf
import io.vaultix.vaultix.ui.common.syncLineToneOf
import io.vaultix.vaultix.ui.common.vaultOriginLabel
import io.vaultix.vaultix.ui.common.vaultSyncLineResOf
import io.vaultix.vaultix.ui.settings.QuickUnlockHost
import io.vaultix.vaultix.ui.shell.rememberBottomDockInset
import io.vaultix.vaultix.ui.theme.UiSize
import io.vaultix.vaultix.ui.theme.VaultixCorner
import io.vaultix.vaultix.ui.theme.Spacing

/**
 * 库列表（Docs/08 S3 最小版）。
 *
 * 交互基线（Docs/16 §4）：LargeTopAppBar 大标题随列表滚动缩放收起，
 * 由滚动位置驱动（nestedScroll），非定时器。
 */
/**
 * 切库前置动画时长（ms）。
 *
 * 240ms 取自 M3E「与点击相连的动作 ≤300ms」的区间中部：短到不拖沓，
 * 又足够让「徽章放大 + 色条亮满 + 其它卡退场」三件事被看见。
 * ⚠️ 别为了「更有仪式感」加到 400ms 以上 —— 那会把切库变成等待。
 */
private const val SWITCH_PRELUDE_MS = 240L

/**
 * 切库动画的数值常量。
 *
 * ⚠️ 必须提成常量：detekt `MagicNumber` 的 `ignoreNamedArgument` 只放行命名参数，
 *    `if (switching) 1.2f else 1f` 里的 `1.2f` 是裸字面量，会被判为魔法数
 *    （上一批 `repeat(4)` 就是这么红的）。
 */
private const val SWITCH_BADGE_SCALE = 1.2f
private const val BAR_ALPHA_IDLE = 0.4f
private const val CARD_DIMMED_ALPHA = 0.42f

/**
 * 库卡装饰参数（2026-11-09 23:32 用户「库列表缺少装饰、比较单调」）。
 *
 * 装饰只用 **Material 图标 + 同一套 tonal 颜色**，不引数据、不引位图：
 * - [CARD_TINT_ALPHA]：卡片底色取该库类型的 `container` 色，压到极淡。
 *   三张卡因此不是三块同色白板，而是「蓝 / 绿 / 紫」三档极浅的色块。
 * - [WATERMARK_ALPHA]：卡片右侧那枚类型图标。它和徽章**同一个图标、同一个 accent 色**，
 *   只是尺寸与透明度不同 —— 这是「一个系统的三种尺寸」，不是第二套装饰语言。
 * - [WATERMARK_OFFSET_X]：向右出血的距离，让图标被卡片圆角裁掉一部分，
 *   读作「图案从边缘延伸出去」而不是「贴了一个大图标」。
 *
 * ⚠️ [WATERMARK_SIZE] **不能超过卡片的自然高度**：水印和内容同在一个 `Box` 里，
 *    `Box` 按最大的子元素测量 ⇒ 水印一旦比内容高，卡片会被它**撑高**。
 *    无同步状态行时卡片自然高度约 79dp（色条 3 + 上下内边距 28 + 徽章 48），
 *    所以取 84dp：既比 48dp 徽章明显大一档，又只在有状态行时才可能略微撑高。
 *
 * ⚠️ [CARD_TINT_ALPHA] 的取值有个**物理下限**：M3 的 `*Container` 色本身明度就很高
 *    （浅色主题下 primaryContainer ≈ 88% 亮度），而卡片底 `surfaceContainerLow` ≈ 96%。
 *    两者只差 8% 明度，10% 混合后差异不足 1% —— 肉眼根本分不出（2026-11-10 00:13
 *    用户实测「差别看不出来」）。要看出色相至少需要 20% 以上，这里取 22%。
 *
 * ⚠️ 四个值都是观感旋钮：嫌花就降 [WATERMARK_ALPHA]，嫌淡就升 [CARD_TINT_ALPHA]。
 */
private const val CARD_TINT_ALPHA = 0.22f
private val WATERMARK_SIZE = 84.dp
private const val WATERMARK_ALPHA = 0.07f
private val WATERMARK_OFFSET_X = 30.dp
private val CARD_BORDER_WIDTH = 1.dp
private const val CARD_BORDER_ALPHA = 0.55f

/**
 * 切库状态 + 「E 数据流」前置动画（P4-b，2026-11-09）。
 *
 * 点库卡后先让这张卡播 240ms 前置动画（徽章放大 + 上沿色条亮满 + 其它卡退场），
 * 再由这里的 `LaunchedEffect` 调 [onOpen] 导航。
 *
 * ⚠️ 只做**前置**动画，不做跨 NavHost 的共享元素：那需要 alpha 的
 *    `SharedTransitionLayout`（8.8 §6① 不允许），且本项目返回转场翻过车
 *    （`SizeTransform` 关成瞬时那条）。
 * ⚠️ 抽成独立函数是为了不把 `if` / `?.let` 加进 [VaultListScreen] 的圈复杂度
 *    —— 它本来就管着「加载中 / 真的没有 / 有库」三支，上限 14。
 * ⚠️ `remember` 而非 `rememberSaveable`：进程被杀后切换要么已完成、要么从未发生，
 *    没有值得恢复的中间态。
 *
 * @return 当前正在切入的库 id；null = 没有切换在进行。
 */
@Composable
private fun rememberSwitchingVault(
    vaults: List<VaultSummary>?,
    onOpen: (VaultSummary) -> Unit,
): MutableState<String?> {
    val switching = remember { mutableStateOf<String?>(null) }
    LaunchedEffect(switching.value) {
        val id = switching.value ?: return@LaunchedEffect
        delay(SWITCH_PRELUDE_MS)
        vaults?.firstOrNull { it.id == id }?.let(onOpen)
    }
    return switching
}

/** 开始切换：已有切换在进行时忽略后续点击（防连点触发多次导航）。 */
private fun startVaultSwitch(state: MutableState<String?>, vaultId: String) {
    if (state.value == null) state.value = vaultId
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultListScreen(
    onAddVault: () -> Unit,
    onAddKdbx: () -> Unit,
    onAddCloud: () -> Unit,
    onOpenVault: (VaultSummary) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: VaultListViewModel = hiltViewModel(),
) {
    // ⚠️ 可空：`null` = 库列表查询还没返回（**不是**"没有库"）。见 [VaultListViewModel.vaults]。
    val vaults by viewModel.vaults.collectAsStateWithLifecycle()
    val syncStatuses by viewModel.syncStatuses.collectAsStateWithLifecycle()
    val quickUnlockSuggest by viewModel.quickUnlockSuggest.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var vaultToRemove by remember { mutableStateOf<VaultSummary?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }

    // 切库状态与 240ms 前置动画：抽成独立 composable（[rememberSwitchingVault]），
    // 避免主函数圈复杂度越界 —— 它本来就管着「加载中 / 真的没有 / 有库」三支。
    val switchingVault = rememberSwitchingVault(vaults = vaults, onOpen = onOpenVault)

    /**
     * ★ 待用户拍板的冲突（null = 不显示对话框）。
     *
     * ⚠️ 用 `remember`（**不是** `rememberSaveable`）：它携带 `VaultSummary` 对象，
     * 没有可用的 Saver；而且进程被杀后"冲突"会由下一次同步重新发现 ——
     * 持久化的其实是 `vaults.syncStatus = CONFLICT` 那一列（真正的真相源）。
     */
    var pendingConflict by remember { mutableStateOf<Pair<String, String>?>(null) }

    // 启用引导：横幅的「启用」走共用控制器（`viewModel.quickUnlock.enableForVault`）；
    // 认证弹窗与「逐库问主密码」都由文件末尾的 QuickUnlockHost 承担。
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                VaultListViewModel.Event.Removed ->
                    snackbarHostState.showSnackbar(context.getString(R.string.vault_removed))
                is VaultListViewModel.Event.RemoveFailed ->
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.item_save_failed, event.notice.resolve(context)),
                    )
                // KDBX 网盘同步的结果：成功走 snackbar。
                is VaultListViewModel.Event.KdbxSynced ->
                    snackbarHostState.showSnackbar(event.notice.resolve(context))
                is VaultListViewModel.Event.KdbxSyncFailed ->
                    snackbarHostState.showSnackbar(
                        context.getString(
                            R.string.kdbx_conflict_resolve_failed,
                            event.notice.resolve(context),
                        ),
                    )
                // ★ 冲突**不走 snackbar** —— 它要用户做一次有代价的选择，
                //   一句自动消失的提示会把它降级成"哦，失败了啊"。
                is VaultListViewModel.Event.KdbxConflict ->
                    pendingConflict = event.vaultId to event.vaultName
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            LargeTopAppBar(
                title = { Text(text = stringResource(R.string.vault_list_title)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.settings_title),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            // 「+」= 添加库：Bitwarden 云端 或 本地 KDBX 文件（两种库类型二选一，
            // 与产品定义「登录时二选一」一致）。offline 分发只保留 KDBX。
            //
            // ⚠️ **空态时不显示**（2026-09-14 用户反馈「加号按钮有点多余吧」）：
            // 空库时页面上正中央就是两个接入按钮，右下角再来一个「+」既重复又把
            // 首启页面的仪式感打散（底部浮着一个圆钮，像没做完的页面）。
            // ⚠️ `null`（还不知道）时也不画：那一帧要么马上变成列表（按钮会突然冒出来），
            //    要么变成空态（按钮会闪一下又消失）。两种都是"凭空出现又消失的控件"。
            if (vaults?.isNotEmpty() == true) {
                // 2026-11-09 23:32 用户「库列表缺少装饰、比较单调」⇒ 圆钮改 Extended FAB。
                // 圆形「+」在这页里只是个孤零零的圆点（空态时还刻意不画），
                // 带文字的胶囊既说明动作，也给出一个视觉锚点，把右下角那片空白收住。
                ExtendedFloatingActionButton(
                    onClick = { showAddDialog = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.vault_add_fab)) },
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val list = vaults) {
                // ① 还不知道：**什么都不画**（只有顶栏）。
                //    这一支就是修闪烁的地方 —— 以前这里直接落到 ②，整屏画品牌首启页，
                //    十几毫秒后再跳成列表，人眼看到的就是"闪了一下连接密码库的页面"。
                //    ⚠️ 刻意不画转圈 / 骨架屏：空窗只有 15~40ms（Room 内存缓存），
                //       画任何东西都会变成"闪一下别的东西"；空白本身不误导。
                null -> Unit
                // ② 真的没有库：品牌首启页。
                emptyList<VaultSummary>() ->
                    EmptyVaultState(onConnectBitwarden = onAddVault, onOpenKdbx = onAddKdbx)
                else -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // 快速解锁启用引导（设备支持时显示；enroll 后自动消失）
                        val suggest = quickUnlockSuggest
                        if (suggest != null && deviceCanAuthenticate(LocalContext.current)) {
                            QuickUnlockBanner(
                                // 横幅「启用」= 把这个库纳入生效范围 + 立刻配指纹（与设置页同一控制器）。
                                onEnable = { viewModel.quickUnlock.enableForVault(suggest.id) },
                                onDismiss = viewModel::dismissQuickUnlockPrompt,
                            )
                        }
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .nestedScroll(scrollBehavior.nestedScrollConnection),
                            contentPadding = PaddingValues(
                                start = Spacing.lg,
                                end = Spacing.lg,
                                top = Spacing.sm,
                                // ⚠️ 2026-10-19：原为字面量 `96.dp`，**小于**真实需要的高度。
                                //    本页不在主壳的 Tab 里（`VaultListRoute`），但它自己的 Scaffold
                                //    下面压着悬浮胶囊底栏的同一套占位 —— 胶囊 60 + 上留白 6 + 下留白 20
                                //    = 86dp，**还要再加系统手势条**（4 键手势机约 32dp）⇒ 真实 ≈118dp。
                                //    写死 96 的后果：最后一张库卡片永远被胶囊压住一截、点不到
                                //    （本页是全 App 的落地页，`onSwitchVault` 也回这里）。
                                //    ⇒ 一律调 [rememberBottomDockInset]，**别再写字面量**：
                                //      手势条高度随机型 / 横竖屏变化，任何固定值都会在别的机型上再错一次。
                                bottom = Spacing.sm + rememberBottomDockInset(),
                            ),
                            verticalArrangement = Arrangement.spacedBy(Spacing.md),
                        ) {
                            // 库概览行（2026-11-09 23:32）：给列表一个「一共几个库、
                            // 几个还锁着」的锚点。它同时是「大标题 → 列表」之间的一段呼吸，
                            // 没有这行时两张卡直接贴在大标题下面，页面读起来是"一张图 + 几个框"。
                            item(key = "vault-list-overview") {
                                VaultListOverview(vaults = list)
                            }
                            items(list, key = { it.id }) { vault ->
                                VaultRow(
                                    vault = vault,
                                    syncStatus = syncStatuses[vault.id],
                                    switchingId = switchingVault.value,
                                    onStartSwitch = { startVaultSwitch(switchingVault, it) },
                                    onRemove = { vaultToRemove = vault },
                                    onSync = { viewModel.syncKdbxVault(vault.id) },
                                    syncEnabled = vault.syncStatus != null,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    VaultListDialogs(
        vaultToRemove = vaultToRemove,
        onDismissRemove = { vaultToRemove = null },
        onConfirmRemove = { vaultToRemove = null; viewModel.removeVault(it) },
        showAddDialog = showAddDialog,
        onDismissAdd = { showAddDialog = false },
        onAddVault = onAddVault,
        onAddKdbx = onAddKdbx,
        onAddCloud = onAddCloud,
    )

    // ★ KDBX 网盘冲突：三选项对话框（方案 §8 方案 B）。
    //
    // ⚠️ `onDismiss`（按返回键 / 点外面）**必须**等价于「稍后再决定」：
    //   不清状态的话它会永远停在 `SYNCING`（界面上是个转不完的圈）。
    pendingConflict?.let { (vaultId, vaultName) ->
        KdbxConflictDialog(
            vaultName = vaultName,
            onChoose = { choice ->
                pendingConflict = null
                viewModel.resolveKdbxConflict(vaultId, choice)
            },
            onDismiss = {
                pendingConflict = null
                viewModel.resolveKdbxConflict(vaultId, KdbxConflictChoice.DecideLater)
            },
        )
    }

    // 快速解锁的认证与流程对话框（与设置页**共用同一个控制器**）。
    QuickUnlockHost(viewModel.quickUnlock)
}

/**
 * 库列表页的对话框集合（移除确认 / 添加库类型）。
 *
 * 抽出来是为了 [VaultListScreen] 不超 detekt `LongMethod`(150) 上限，
 * 同时让「页面布局」与「对话框编排」各自可读。
 *
 * ⚠️ 2026-09-16：KDBX 启用主密码框已从这里**移除** —— 快速解锁收敛到
 * `QuickUnlockController` 之后，逐库问密码由 `QuickUnlockHost` 统一承担，
 * 不再需要页面自己再养一份单库流程（那份曾与设置页互为复制品）。
 */
@Composable
private fun VaultListDialogs(
    vaultToRemove: VaultSummary?,
    onDismissRemove: () -> Unit,
    onConfirmRemove: (String) -> Unit,
    showAddDialog: Boolean,
    onDismissAdd: () -> Unit,
    onAddVault: () -> Unit,
    onAddKdbx: () -> Unit,
    onAddCloud: () -> Unit,
) {
    if (vaultToRemove != null) {
        AlertDialog(
            onDismissRequest = onDismissRemove,
            title = { Text(stringResource(R.string.vault_remove_title)) },
            text = { Text(stringResource(R.string.vault_remove_message, vaultToRemove.name)) },
            confirmButton = {
                TextButton(onClick = { onConfirmRemove(vaultToRemove.id) }) {
                    Text(
                        text = stringResource(R.string.vault_remove_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRemove) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    // 添加库：三种类型三选一（Bitwarden 云端 / 本地 KDBX 文件 / 网盘上的 KDBX）。
    if (showAddDialog) {
        AddVaultTypeDialog(
            onConnectBitwarden = {
                onDismissAdd()
                onAddVault()
            },
            onOpenKdbx = {
                onDismissAdd()
                onAddKdbx()
            },
            onAddCloudKdbx = {
                onDismissAdd()
                onAddCloud()
            },
            onDismiss = onDismissAdd,
        )
    }
}

/**
 * 添加库的类型选择对话框已提到 [io.vaultix.vaultix.ui.common.AddVaultTypeDialog]：
 * 设置页「密码库」也要同一个入口（库列表路由在已有库时不可达，
 * 否则用户永远加不了本地 KDBX 库）。
 */

@Composable
private fun QuickUnlockBanner(
    onEnable: () -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Text(
                text = stringResource(R.string.quick_unlock_enroll_title),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.quick_unlock_banner_text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = Spacing.sm),
            ) {
                TextButton(onClick = onEnable) {
                    Text(stringResource(R.string.action_enable))
                }
                Spacer(Modifier.width(Spacing.sm))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_later))
                }
            }
        }
    }
}

/**
 * 空态（首次启动 / 已移除全部库）：**品牌页**，不是一句「还没有密码库」。
 *
 * 2026-09-14 用户逐条提出的四点，这里是它们的落地：
 * 1. 「空白区域太多了，第一次启动的仪式感太差」⇒ 顶部补品牌块（图标 + 应用名 + 定位），
 *    底部补一句说明，中间是两个大按钮，纵向自然铺开而不是中段一小坨；
 * 2. 「能不能换成软件的名字」⇒ 主标题取 `app_name`（Vaultix），
 *    不再用「还没有密码库」这种**状态陈述**当标题 —— 首启页该先告诉用户「这是谁」；
 * 3. 「做成两个方形的按钮，颜色不同：Bitwarden 亮蓝色，KDBX 金色」⇒ 两个**等宽等高的
 *    品牌色按钮**，各自带一行说明（云端同步 / 本地离线）；
 * 4. 加号按钮（FAB）在空态隐藏，理由见 `VaultListScreen` 的 `floatingActionButton`。
 *
 * ⚠️ 两个按钮**等权重**是有意的：定稿 §1① 明确「Bitwarden 与 KDBX 地位完全平等」，
 * 所以不能像以前那样「一个实心 Button + 一个 OutlinedButton」（那在视觉上把 KDBX
 * 降级成了次要选项）。区分靠**颜色语义**（蓝 = 云端 / 金 = 本地实体文件）。
 *
 * ⚠️ 这是全项目**少数几处刻意不用动态取色**的地方：品牌按钮若跟着壁纸变色，
 * 「蓝 = Bitwarden / 金 = KDBX」这套识别就废了。其余 UI 一律走 Material You。
 */
@Composable
private fun EmptyVaultState(onConnectBitwarden: () -> Unit, onOpenKdbx: () -> Unit) {
    // ★ 2026-10-19：横向边距 28dp 魔法数 → `Spacing.xl`(24)，与另外 5 处空态一致
    //   （审计 §2.6）。品牌块本身（72dp 图标 + headlineMedium + 两个等权按钮）
    //   保留不动 —— 它承载"这是谁 / 你从哪进"的信息，不是 [EmptyState] 的三行式
    //   能表达的东西；空态收敛**只统一间距与字号口径**，不把品牌页也压成一句标题。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_autofill_vaultix),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(EMPTY_BRAND_ICON),
        )
        Spacer(Modifier.height(Spacing.lg))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = stringResource(R.string.vault_list_empty_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(EMPTY_ACTIONS_TOP_GAP))
        if (AppFlavor.supportsBitwarden) {
            BrandVaultButton(
                label = stringResource(R.string.vault_connect_bitwarden),
                description = stringResource(R.string.vault_add_bitwarden_desc),
                container = BITWARDEN_BRAND_BLUE,
                content = Color.White,
                onClick = onConnectBitwarden,
            )
            Spacer(Modifier.height(Spacing.md))
        }
        // KDBX 入口对 full / offline **两种分发都开放**（本地库不需要网络，
        // offline 分发反而只有它可用 —— 此前这里写的是「即将支持」占位）。
        BrandVaultButton(
            label = stringResource(R.string.vault_open_kdbx),
            description = stringResource(R.string.vault_add_kdbx_desc),
            container = KDBX_BRAND_GOLD,
            content = KDBX_BRAND_GOLD_TEXT,
            onClick = onOpenKdbx,
        )
        Spacer(Modifier.height(Spacing.xl))
        Text(
            text = stringResource(R.string.vault_list_empty_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Bitwarden 官方品牌蓝（亮蓝）—— 只用于「接云端库」这个按钮，不是 Vaultix 主题色。
 *
 * 白色文字对比度约 5.9:1（WCAG AA 达标）。
 */
private val BITWARDEN_BRAND_BLUE = Color(0xFF175DDC)

/** KDBX / KeePass 侧的「黄铜金」——与「本地实体文件」语义绑定（金 = 实物 = 保险库）。 */
private val KDBX_BRAND_GOLD = Color(0xFFC8892A)

/** 金底上的文字色（深棕）：白字压在金上只有约 2.6:1，读不清，故用深色字（约 5.5:1）。 */
private val KDBX_BRAND_GOLD_TEXT = Color(0xFF2A1D07)

/**
 * 品牌色入口按钮：**主标题 + 一行说明**，两枚等宽等高。
 *
 * 说明文字压在按钮内部而不是另起一行，是为了让「点它会得到什么」与按钮**不可分离** ——
 * 否则用户要先把说明与按钮配对，而空库页正是最需要零歧义的地方。
 */
@Composable
private fun BrandVaultButton(
    label: String,
    description: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
        ),
        shape = VaultixCorner.Panel,
        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = UiSize.RowVerticalPadding),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = label, style = MaterialTheme.typography.titleMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = content.copy(alpha = 0.85f),
            )
        }
    }
}

@Composable
private fun VaultCard(
    vault: VaultSummary,
    syncStatus: VaultSyncStatus?,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    /** 「立即同步」；null = 这个库没有网盘来源 ⇒ 菜单里不显示该项。 */
    onSyncNow: (() -> Unit)? = null,
    /** 正在切入这个库：播「数据流」前置动画（P4-b，2026-11-09 用户选 E 方案）。 */
    switching: Boolean = false,
    /** 别的库正在切入 ⇒ 这张退场让位。 */
    dimmed: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val badgeScale by animateFloatAsState(
        targetValue = if (switching) SWITCH_BADGE_SCALE else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "vaultBadgeScale",
    )
    val barAlpha by animateFloatAsState(
        targetValue = if (switching) 1f else BAR_ALPHA_IDLE,
        animationSpec = tween(durationMillis = 220),
        label = "vaultBarAlpha",
    )
    val cardAlpha by animateFloatAsState(
        targetValue = if (dimmed) CARD_DIMMED_ALPHA else 1f,
        animationSpec = tween(durationMillis = 160),
        label = "vaultCardAlpha",
    )
    // 库身份（图标 / 强调色 / 容器色 / 形状）：卡片的色调底、右侧水印与徽章**同源**。
    val identity = vaultIdentityOf(vault)
    Card(
        onClick = { if (!switching && !dimmed) onClick() },
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = cardAlpha },
        colors = CardDefaults.cardColors(
            // 容器色 = 主题的 surfaceContainerLow 上叠一层**该库类型**的 container 色。
            // 三张卡因此不是三块同色灰板，而是「蓝 / 绿 / 紫」三档极浅的色块；
            // 用 compositeOver 而不是半透明 containerColor，是为了保住原有的层次
            // （直接给 alpha 会透出 Scaffold 背景，卡片与底的关系反而消失）。
            containerColor = identity.container
                .copy(alpha = CARD_TINT_ALPHA)
                .compositeOver(scheme.surfaceContainerLow),
        ),
        border = BorderStroke(
            width = CARD_BORDER_WIDTH,
            color = scheme.outlineVariant.copy(alpha = CARD_BORDER_ALPHA),
        ),
    ) {
        Box {
            // 库类型水印（「库身份」的第二处复用：同图标、同 accent，只换尺寸与透明度）。
            // 向右出血 [WATERMARK_OFFSET_X]，被卡片圆角裁掉一部分 ——
            // 读作「图案从边缘延伸出去」，而不是「贴了一个大图标」。
            Icon(
                imageVector = identity.icon,
                contentDescription = null,
                tint = identity.accent,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = WATERMARK_OFFSET_X)
                    .size(WATERMARK_SIZE)
                    .graphicsLayer { alpha = WATERMARK_ALPHA },
            )
            Column {
                // 上沿色条：切换时从 40% 亮到 100%，读作「数据正在灌进这个库」。
                // ⚠️ 色条统一用 primary，不按库类型分色 —— 它表达的是「正在切换」，
                //    按类型分色会让同一张卡出现两个颜色信号（8.4：一个元素说一件事）。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(scheme.primary.copy(alpha = barAlpha)),
                )
                Row(
                    modifier = Modifier.padding(
                        start = Spacing.lg,
                        top = Spacing.md,
                        bottom = Spacing.lg,
                        end = Spacing.xs,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 库身份徽章（P4-a）：动效的 hero。缩放只作用于徽章，不动整张卡。
                    Box(
                        modifier = Modifier.graphicsLayer {
                            scaleX = badgeScale
                            scaleY = badgeScale
                        },
                    ) {
                        VaultIdentityBadge(vault = vault)
                    }
                    Spacer(Modifier.width(Spacing.md))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = vault.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = vaultSubtitle(vault),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        SyncStatusLine(syncStatus = syncStatus)
                        // KDBX 网盘库的**持久**同步状态（与上面那个"本次同步进行到哪"不同，
                        // 见 `KdbxCloudSyncStatus` 的说明）。null = 非网盘库，整行不渲染。
                        KdbxSyncStatusLine(vault)
                    }
                    if (!vault.unlocked) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = stringResource(R.string.vault_badge_locked),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(UiSize.InlineIcon),
                        )
                    }
                    VaultCardMenu(onRemove = onRemove, onSyncNow = onSyncNow)
                }
            }
        }
    }
}

/**
 * 库概览行：`3 个密码库 · 2 个已锁定`。
 *
 * 纯派生自 [vaults]，不引新数据源、不加 ViewModel 依赖。
 * 「全部已解锁」时只报库数量 —— 说一句「0 个已锁定」是噪音。
 */
@Composable
private fun VaultListOverview(vaults: List<VaultSummary>) {
    val lockedCount = vaults.count { !it.unlocked }
    Text(
        text = if (lockedCount > 0) {
            stringResource(R.string.vault_list_overview_locked, vaults.size, lockedCount)
        } else {
            stringResource(R.string.vault_list_overview, vaults.size)
        },
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Spacing.xs),
    )
}

/**
 * 列表里的一行库卡：把「切换态 / 同步入口」这些条件从 `items` 的 lambda 里搬出来。
 *
 * ⚠️ 抽它是为了 [VaultListScreen] 的圈复杂度：lambda 里只要出现 `&&` / `if` / `?.`，
 *    detekt 都会算进**外层函数**。列表页本来就有「加载中 / 真的没有 / 有库」三支，
 *    再塞条件就会像上一版那样直接报到 17（上限 14）。
 */
@Composable
private fun VaultRow(
    vault: VaultSummary,
    syncStatus: VaultSyncStatus?,
    switchingId: String?,
    onStartSwitch: (String) -> Unit,
    onRemove: (VaultSummary) -> Unit,
    onSync: () -> Unit,
    syncEnabled: Boolean,
) {
    VaultCard(
        vault = vault,
        syncStatus = syncStatus,
        switching = vault.id == switchingId,
        dimmed = switchingId != null && vault.id != switchingId,
        onClick = { onStartSwitch(vault.id) },
        onRemove = { onRemove(vault) },
        // ⚠️ 只在"该库确实是网盘库"时给同步入口。给 Bitwarden / 本地 SAF 库也画一个
        //    「立即同步」是错的方向：前者走自己的同步链、后者根本没有远端。
        onSyncNow = if (syncEnabled) onSync else null,
    )
}

/**
 * 库身份（P4-a，2026-11-09；同日晚收成**一份定义**，2026-11-10 补「本地 vs 网盘」的判别）。
 *
 * 图标 / 形状 / 颜色按**来源**区分，全部取自 `colorScheme` 角色，不硬编码品牌色：
 * - KDBX · **网盘**（OneDrive / WebDAV）→ 圆形 + `secondaryContainer` + 云同步图标；
 * - KDBX · **本地文件** → 圆角方 + `tertiaryContainer` + 文件图标；
 * - 云端 Bitwarden → 大圆角 + `primaryContainer` + 云图标。
 *
 * ⚠️ ★★ **判据是"来源"，不是"有没有 syncStatus"**（2026-11-10 晚真机反馈修）。
 *
 * 此前这一行写的是 `vault.syncStatus != null`。正常数据下两者同义（补初始状态的那段
 * 代码只给网盘库补）—— 可是**本地库的这一列一旦被写成某个值**（旧版本 / 迁移 /
 * 别的写入点），这张卡就立刻换上圆形云徽章，和 OneDrive 上的库长得一模一样。
 * 用户原话：
 *
 * > 本地 kdbx……点击一下同步。居然变成了已同步的本地文件，这样合理吗，虽然条目没有云图标，
 * > 但是在密码库列表里外观效果和 onedrive 上的库的列表外观好像一样的。
 *
 * 「外观一样」的根源就在这里：一个**数据列有没有值**被拿来回答了
 * 「**这个文件存在哪**」。后者只有 `origin` 知道（`kdbxSourceTargetOf`，唯一真源）。
 * ⇒ 判据换成来源；`syncStatus` 只决定**第三行写什么**，不再决定"长什么样"。
 *
 * ⚠️ **一次定义、三处复用**：徽章、[VaultCard] 右侧的出血水印、卡片极淡的类型色调底。
 *    三处同源才不会出现「同一个库在不同地方长得不一样」——8.4 记着
 *    「一屏两套规格」是观感廉价的首要来源。
 * ⚠️ 它同时是切库动效的 hero（[VaultCard] 里的 `badgeScale`）：没有这枚徽章，
 *    E 方案的「徽章先亮 → 数据流入」就没有可联动的对象，只能退回整屏淡入。
 */
private data class VaultIdentity(
    val icon: ImageVector,
    val accent: Color,
    val container: Color,
    val shape: Shape,
)

/** 三种库身份的容器形状（提到顶层：避免每次重组都新建 `Shape` 对象）。 */
private val BadgeShapeCloudSynced = RoundedCornerShape(percent = 50)
private val BadgeShapeLocalKdbx = RoundedCornerShape(14.dp)
private val BadgeShapeCloud = RoundedCornerShape(18.dp)

/**
 * 这张卡是不是「网盘上的库」 —— **只认 `origin`**。
 *
 * Bitwarden 恒为 true（它本来就在服务器上）；KDBX 按 `kdbxSourceTargetOf` 判，
 * 畸形 / 未知来源一律按本地处理（`LocalFile` 的退化行为，与条目页云徽章同一个函数）。
 */
internal fun isRemoteBackedVault(kind: VaultKind, origin: String): Boolean = when (kind) {
    VaultKind.BITWARDEN -> true
    VaultKind.KDBX -> kdbxSourceTargetOf(origin) != KdbxSourceTarget.LocalFile
}

@Composable
private fun vaultIdentityOf(vault: VaultSummary): VaultIdentity {
    val scheme = MaterialTheme.colorScheme
    val remote = isRemoteBackedVault(vault.kind, vault.origin)
    // ⚠️ 只看"是不是网盘"与"是不是 KDBX"，**不看 syncStatus**（理由见 [VaultIdentity] 的 KDoc）。
    val remoteKdbx = remote && vault.kind == VaultKind.KDBX
    val localKdbx = !remote && vault.kind == VaultKind.KDBX
    return when {
        remoteKdbx -> VaultIdentity(
            icon = Icons.Filled.CloudSync,
            accent = scheme.secondary,
            container = scheme.secondaryContainer,
            shape = BadgeShapeCloudSynced,
        )

        localKdbx -> VaultIdentity(
            icon = Icons.Filled.Description,
            accent = scheme.tertiary,
            container = scheme.tertiaryContainer,
            shape = BadgeShapeLocalKdbx,
        )

        else -> VaultIdentity(
            icon = Icons.Filled.Cloud,
            accent = scheme.primary,
            container = scheme.primaryContainer,
            shape = BadgeShapeCloud,
        )
    }
}

/** 库身份徽章：`VaultIdentity` 的第一处复用（48dp 实色容器 + 24dp 图标）。 */
@Composable
private fun VaultIdentityBadge(vault: VaultSummary, modifier: Modifier = Modifier) {
    val identity = vaultIdentityOf(vault)
    Box(
        modifier = modifier
            .size(UiSize.TouchTarget)
            .clip(identity.shape)
            .background(identity.container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = identity.icon,
            contentDescription = null,
            tint = identity.accent,
            modifier = Modifier.size(UiSize.BadgeIcon),
        )
    }
}

/**
 * 卡片副标题：**给用户看的字段必须是用户语义的**（`.ai/issues/05-KDBX本地库.md` #95）。
 *
 * - Bitwarden：`account`（邮箱）→ 退化到 `origin`（`https://...`，本身可读）；
 * - KDBX：`account` 恒为 null，而 `origin`/`id` 对**本地** SAF 库**就是** `content://` URI
 *   （`addKdbxVault` 用 `vaultId = sourceUri`）⇒ 直接渲染会露出
 *   `content://com.android.externalstorage.documents/document/primary%3A...` 这种
 *   给系统看的、URL 编码过的字符串。
 *
 * ## ⚠️ KDBX 分支**不能**在这里自己判来源（2026-11-10 真机反馈修正）
 *
 * 此前这里写的是 `KDBX -> stringResource(vault_card_kdbx_subtitle, vault.name)`，
 * 即**无条件**「本地文件 · 库名」。于是 OneDrive / WebDAV 上的 KDBX 库卡片也显示
 * 「本地文件」—— 用户原话：
 *
 * > 库列表，这两个显示是对的，但是处于 onedrive 上的 kdbx，在这里也会显示出本地文件，这样不对吧
 *
 * 而 `ui/common/VaultOriginLabel.kt` 里**早就有一份**会区分
 * `本地文件 / OneDrive / WebDAV` 的实现（`vaultOriginLabel`），
 * 密码页的库状态行用的就是它。两处各判各的 ⇒ 同一份数据在两个页面叫两个名字。
 *
 * ⇒ 现在统一走 [vaultOriginLabel]。这个函数只保留 Bitwarden 那一支的
 * 「account 优先」差异（库状态行显示的是服务器地址，卡片这里显示账号邮箱）。
 *
 * ## ★ 2026-11-10 晚：**本地** KDBX 不再复述文件名
 *
 * 本地库原来的第二行是「本地文件 · new.kdbx」，而这一行**正上方**的标题就是
 * `new.kdbx` —— 同一个文件名连着出现两次，一个有信息量的字段（库有多大 / 几条）
 * 却被挤出了卡片。来源本身（"是不是本地"）已经由**徽章形状**（圆角方 vs 圆形）
 * 和 `vault_suffix` 承担，第二行不必再说一遍。
 *
 * 网盘库仍然保留「OneDrive · 路径」：那条路径是**用户认库的依据**（同名文件在
 * 不同目录很常见），不能省。
 */
@Composable
private fun vaultSubtitle(vault: VaultSummary): String = when (vault.kind) {
    VaultKind.KDBX ->
        // ★ 本地 / OneDrive / WebDAV 三种来源都在 vaultOriginLabel 里分好，
        //   这里只做**本地那一支**的省略（见上面 KDoc），不重复判断来源。
        if (isRemoteBackedVault(vault.kind, vault.origin)) {
            vaultOriginLabel(vault)
        } else {
            stringResource(R.string.vault_suffix)
        }

    VaultKind.BITWARDEN -> vault.account ?: vault.origin
}

/**
 * 卡片更多菜单：同步（仅网盘库）/ 移除库（破坏性动作；确认对话框在列表层）。
 *
 * ⚠️ 同步为什么留在这里、而不像「密码库管理」那样露在行尾（2026-11-08）：
 * 两页的任务不同。本页的任务是**挑一个库进去**，卡片整体是一个导航目标，
 * 右上一个 ×2 的热区会把「进入」这件事挤小；而管理页的任务就是**维护每个库**，
 * 同步在那里才是主操作。同一个动作在两页暴露程度不同是 M3 允许的，
 * 但**名字必须一致** —— 所以这里用 `vault_action_sync`（同步），不再叫「立即同步」。
 */
@Composable
private fun VaultCardMenu(onRemove: () -> Unit, onSyncNow: (() -> Unit)? = null) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menuOpen = true }) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.vault_more_actions),
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (onSyncNow != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.vault_action_sync)) },
                    leadingIcon = {
                        Icon(Icons.Filled.Sync, contentDescription = null)
                    },
                    onClick = {
                        menuOpen = false
                        onSyncNow()
                    },
                )
            }
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(R.string.vault_remove_menu),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    menuOpen = false
                    onRemove()
                },
            )
        }
    }
}

/**
 * KDBX 同步状态的**一句话**（库列表卡片第三行 / 管理页同一句）。
 *
 * 判据只此一份（`ui/common/VaultOriginLabel.kt` 的 `vaultSyncLineResOf`），
 * 两页各抄一份是"同一状态两种措辞"的老路。
 *
 * @return `null` = 这一行不该出现。
 */
@Composable
internal fun kdbxSyncLineText(vault: VaultSummary): String? =
    vaultSyncLineResOf(vault.kind, vault.origin, vault.syncStatus)?.let { stringResource(it) }

/**
 * KDBX 网盘库的**持久**同步状态行（`vaults.syncStatus`）。
 *
 * ## 与 [SyncStatusLine] 的分工（别合并）
 *
 * | | SyncStatusLine | 本函数 |
 * |---|---|---|
 * | 数据 | `VaultSyncStatus[]`（Bitwarden 的**本次**同步进度） | `vaults.syncStatus`（**持久**差距） |
 * | 生命周期 | 进程内，一次同步跑完就回到常态 | 跨重启，直到真的对上 |
 * | 静默时 | 不显示（保持列表安静） | 也要显示（用户需要知道"还没推上去"） |
 *
 * ⚠️ 关键差异在最后一行：本状态**不该静默**。它代表"本地有改动还没上云"
 * 或"云端有更新还没拉" —— 不显示等于让用户以为一切都好。
 *
 * ⚠️ ★ **本地 KDBX 永不显示**（2026-11-10 晚）：判据在 [vaultSyncLineResOf]，
 *    本函数不再自己 `when (status)`。此前 `LOCAL_ONLY` 被兜成「已同步」，
 *    于是「本地文件 · new.kdbx / 已同步」—— 那个文件根本没有"另一端"，
 *    「已同步」是**凭空造出来的结论**，也正是「本地库和 OneDrive 库外观一样」的来源。
 */
@Composable
private fun KdbxSyncStatusLine(vault: VaultSummary) {
    val res = vaultSyncLineResOf(vault.kind, vault.origin, vault.syncStatus) ?: return
    Text(
        text = stringResource(res),
        style = MaterialTheme.typography.labelSmall,
        color = syncLineToneOf(res),
        maxLines = 1,
    )
}

/**
 * 卡片行内同步状态轻提示（Bastion SyncStatusIndicator 语义的轻量裁剪）：
 * - 同步中 → 「正在同步…」（primary）；
 * - 最近一次结果是失败且尚无更新的成功 → 「同步失败：…」（error）；
 * - 其余（含静默成功）不显示，保持列表安静（Bastion 静默同步语义）。
 */
@Composable
private fun SyncStatusLine(syncStatus: VaultSyncStatus?) {
    val s = syncStatus ?: return
    val errorIsLatest = (s.lastErrorAt ?: Long.MIN_VALUE) >= (s.lastSuccessAt ?: Long.MIN_VALUE)
    val lastError = s.lastError
    val (text, color) = when {
        s.isRunning -> stringResource(R.string.vault_syncing) to MaterialTheme.colorScheme.primary
        errorIsLatest && lastError != null ->
            stringResource(R.string.vault_sync_failed, lastError) to MaterialTheme.colorScheme.error
        else -> return
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        modifier = Modifier.padding(top = UiSize.HairGap),
    )
}

/** 首启页品牌图标尺寸（72dp）。比 [EmptyState] 的 48dp 大一档：这是品牌位不是提示位。 */
private val EMPTY_BRAND_ICON = 72.dp

/** 品牌页「定位语」与两个入口按钮之间的间距（原为字面量 36dp，收进命名常量）。 */
private val EMPTY_ACTIONS_TOP_GAP = 36.dp

/**
 * 把 [SyncNotice] 解析成**可上屏的一句话**。
 *
 * ⚠️ 这里拿 `Context` 而不是 `stringResource`：snackbar 是在 `LaunchedEffect` 里
 * 用 `suspend` 的 `showSnackbar` 弹的，不在 Composable 作用域内 —— 那是本页既有
 * 的做法（同一处其它分支也都在 `context.getString`），保持一致即可。
 *
 * ⚠️ [SyncNotice.Res] 全部是**无参**资源，所以不需要格式化参数。若将来某条要带参，
 * 先给类型加上 `args`，别在调用点就地拼字符串 —— 那会让这里重新变成双源。
 */
private fun SyncNotice.resolve(context: android.content.Context): String = when (this) {
    is SyncNotice.Res -> context.getString(id)
    is SyncNotice.Detail -> message
}
