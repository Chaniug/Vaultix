package io.vaultix.vaultix.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.vaultix.vaultix.ui.addvault.AddCloudVaultScreen
import io.vaultix.vaultix.ui.addvault.AddKdbxScreen
import io.vaultix.vaultix.ui.addvault.AddVaultScreen
import io.vaultix.vaultix.ui.detail.ItemDetailScreen
import io.vaultix.vaultix.ui.items.ItemsScreen
import io.vaultix.vaultix.ui.items.ItemsViewModel
import io.vaultix.vaultix.ui.passkeys.PasskeysScreen
import io.vaultix.vaultix.ui.rootnav.RootNavState
import io.vaultix.vaultix.ui.rootnav.RootNavViewModel
import io.vaultix.vaultix.ui.settings.AutofillSettingsScreen
import io.vaultix.vaultix.ui.settings.ImportExportScreen
import io.vaultix.vaultix.ui.settings.SettingsScreen
import io.vaultix.vaultix.ui.settings.CloudAccountsScreen
import io.vaultix.vaultix.ui.settings.VaultManagementScreen
import io.vaultix.vaultix.ui.shell.MainShellScreen
import io.vaultix.vaultix.ui.shell.MainShellViewModel
import io.vaultix.vaultix.ui.shell.tabSwitchEnter
import io.vaultix.vaultix.ui.shell.tabSwitchExit
import io.vaultix.vaultix.ui.totp.TotpCodesScreen
import io.vaultix.vaultix.ui.trash.TrashScreen
import io.vaultix.vaultix.ui.unlock.UnlockScreen
import io.vaultix.vaultix.ui.unlock.UnlockViewModel
import io.vaultix.vaultix.ui.vaultlist.VaultListScreen
import io.vaultix.vaultix.ui.common.VaultixWavyProgress
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/**
 * 应用导航（Docs/08 §1，M1 闭环）。
 *
 * 2026-09-11 改造：**根导航由 [RootNavViewModel] 驱动**（对齐 Bitwarden
 * `RootNavViewModel` + `RootNavScreen`）。
 *
 * 2026-09-12 修复：**起始路由改为 Splash 占位 + 状态驱动换栈**，并新增
 * `RootNavState.Onboarding`（一个库都没有）与 `RootNavState.Splash`（首帧未到）。
 *
 *   RootNavState.Splash            ─▶ Splash（占位 loading，**必定结束**）
 *   RootNavState.Onboarding        ─▶ VaultList（首次使用，引导添加库）
 *   RootNavState.VaultLocked       ─▶ UnlockEntry（**有库**时的解锁界面，不是错误页）
 *   RootNavState.VaultUnlockedGraph ─▶ MainShell（**首帧**直达；解锁动作由解锁页跳转）
 *                                        │
 *                                        ├─ Tab 密码 ─▶ Items ─▶ ItemDetail
 *                                        ├─ Tab 验证码 ─▶ 通行密钥
 *                                        ├─ Tab 卡包 ─▶ 新建卡片
 *                                        └─ Tab 设置 ─▶ 自动填充设置
 *
 * 自动锁定后库全部锁定 → 状态回到 `VaultLocked` → 清栈回解锁入口。
 * offline 分发不展示「连接 Bitwarden」入口（AppFlavor）。
 */
@Composable
fun VaultixApp() {
    val navController = rememberNavController()
    val rootNavViewModel: RootNavViewModel = hiltViewModel()
    // Compose 无法直接注入进程级单例，用空壳 ViewModel 做依赖桥（活跃库真源）
    val shellBridge: MainShellViewModel = hiltViewModel()
    val rootNavState by rootNavViewModel.rootNavState.collectAsStateWithLifecycle()
    // 首帧是否已经落过路由：落过之后「已解锁」不再重复换栈（换栈会重建主界面、
    // 丢失 Tab 位置），只有「锁定」才强制收敛回解锁页。
    var routedOnce by rememberSaveable { mutableStateOf(false) }

    // ⚠️ 起始路由固定为启动占位：库列表首帧到达前**不下结论**。
    // 旧实现用 remember 固化首帧计算结果——而首帧永远是"锁定"（Flow 初始值是
    // VaultLocked），于是：全新安装（一个库都没有）→ 永久停在解锁页 → 解锁页没有
    // 库可解锁 → 一直转圈；且固化之后后续状态变化也不会再改起始路由。
    // 现在 startDestination 只承担"未知"语义，真正的落点由下面的 LaunchedEffect 驱动。
    LaunchedEffect(rootNavState) {
        when (val state = rootNavState) {
            // 首帧未到：停在启动占位，什么都不做
            RootNavState.Splash -> return@LaunchedEffect
            // 有库但锁着（含自动锁定 / 主页查看锁）→ 解锁入口，清掉所有已失效的明文界面。
            // ⚠️ 这里**每次**都收敛：自动锁定必须能把手上的条目页关掉。
            // 查看层锁（state.vaultId 非空）同样收敛，但解锁页只做一次认证就回来 ——
            // 会话密钥没被清，所以不必重登。
            is RootNavState.VaultLocked ->
                navController.navigateToRoot(UnlockEntryRoute(vaultId = state.vaultId.orEmpty()))
            // 首次使用（一个库都没有）→ 库列表引导添加
            RootNavState.Onboarding -> navController.navigateToRoot(VaultListRoute)
            // 已解锁：仅在首帧落一次（对齐 Bastion「解锁即进主界面」）；
            // 解锁动作本身由 UnlockScreen 的 onUnlocked 负责跳转。
            RootNavState.VaultUnlockedGraph -> if (!routedOnce) {
                navController.navigateToRoot(MainShellRoute)
            }
        }
        routedOnce = true
    }

    NavHost(
        navController = navController,
        startDestination = SplashRoute,
        // ⚠️ 2026-09-13 第二轮用户反馈「从密码条目 / 验证码条目页面返回时动画有点卡顿」。
        // 根因：**全屏淡入淡出**。`fadeIn`/`fadeOut` 要给整屏（1256×2760）做一次 alpha 合成，
        // 而 alpha 层的实现是离屏渲染（`saveLayer`）—— 列表页 + 详情页同时在场时，每帧都要
        // 多分配/合成一整屏的离屏缓冲，正是掉帧的来源。滑动本身只是 `translationX`，几乎免费。
        //
        // 因此这里**去掉 fade，只保留位移**：两页都不透明，新页滑入时就自然盖住让位页 ——
        // 观感与 Shared Axis X 一致（方向连续），但少了整屏合成那一笔开销。
        // ⚠️ 2026-09-13 第六轮（用户拍板）：**不要再做花哨的转场**。
        // 用户原话：「我不要这些花里花哨的动画。验证码页面返回的那种就可以了，几乎没有动效的。」
        // ⇒ 直接把二级路由的过渡换成**和 Tab 切换同一套**（`tabSwitchEnter/tabSwitchExit`
        // = `fadeIn + slideInVertically(1/16 屏高)`）：
        //   · 观感上"几乎没有动效"，且全 App 手感统一（切 Tab 与进出二级页一致）；
        //   · 双向对称（进入与返回同一套），没有方向感上的刻意设计；
        //   · 位移只有 1/16 屏高 —— 不违背"别让容器尺寸补间"（那是尺寸，不是位移），
        //     也不会再出现"整屏滑走"那种被用户读作"多余"的动效。
        // ⚠️ 历史上这里试过：1/12 轻推 + fade（被读成"缩小"）、整屏 Shared Axis X
        // （被读成"快速右滑、多余"）。**别再往上加动效**，用户明确不要。
        enterTransition = { tabSwitchEnter() },
        exitTransition = { tabSwitchExit() },
        popEnterTransition = { tabSwitchEnter() },
        popExitTransition = { tabSwitchExit() },
        // ⚠️ 2026-09-13 第三轮用户反馈：「返回时会看到详情页缩小的 1~2 帧画面，很多地方都有」。
        // 根因：导航库给目的地的转场套了一层 **`SizeTransform`** —— 它会在过渡期间**动画容器的
        // 尺寸**。两个页面的测量尺寸只要差一点点（例如详情页的内容还没填满、或键盘/insets 差一档），
        // 容器就被"补间"成中间尺寸，居中的内容看起来就是**整页缩小了一帧半帧**。
        // 我们的转场只要位移，容器尺寸**必须**恒定 ⇒ 显式关掉它（`null` = 不做尺寸补间）。
        sizeTransform = null,
    ) {
        // 导航图按链路拆分（detekt LongMethod ≤150 行）
        vaultEntryGraph(navController, shellBridge)
        settingsGraph(navController)
        itemsGraph(navController)
    }
}

/**
 * 启动占位屏：库列表首帧到达前的唯一内容。
 *
 * 与解锁页里的 loading 的区别：这个 loading **一定会结束**（首帧一到就被
 * 真实路由替换掉），解锁页那个是"等一个永远不会来的库"。
 */
@Composable
private fun SplashScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        VaultixWavyProgress()
    }
}

/**
 * 二级路由转场：**Shared Axis X**（Material motion 的标准「前进 / 返回」动效）。
 *
 * ⚠️ 2026-09-13 第二轮用户反馈：「返回的时候画面是一个缩小的，感觉不太舒服，
 * 找一个当前最流行最舒服的切换效果」。上一版是「140ms + 只推 1/12 屏宽 + 淡入淡出」——
 * 位移太小、时长太短，两页几乎在原地交叉淡化，观感就像**整页缩了一下**。
 *
 * 现在换成 Material 官方的 Shared Axis X：
 * - **进入**（push）：新页从**右侧整屏**滑入，旧页向左**轻微**让位（1/4 屏）；
 * - **返回**（pop）：正好相反 —— 旧页整屏右滑出去，被返回的页从左侧 1/4 屏处滑回原位。
 *
 * ⚠️ **不要加 fade**：整屏 alpha 合成要离屏渲染，两页同时在场时每帧多一次全屏合成 ⇒ 掉帧
 * （用户反馈"返回的时候动画有点卡顿"）。位移本身只是 `translationX`，几乎零成本。
 *
 * 为什么它"舒服"：方向与空间关系是**连续**的（前进 = 向右展开，返回 = 收回原处），
 * 用户不需要在脑中重建层级；这也是 Android 14 预测式返回的默认语言。
 * 全 App 的二级路由（详情 / 编辑 / 通行密钥 / 回收站 / 设置子页）共用这一套，
 * 不再有「某几页有动画、某几页硬切」的不一致。
 */
private const val NAV_ANIM_MS = 300

// 注：这里曾有一个 `NAV_OUTGOING_DIVISOR`（让位页位移 = 屏宽 / 它，取 4）—— **已删除**。
// 它就是"返回时画面缩一下"的元凶：让位页只挪 1/4 屏 ⇒ 过渡期间存在"两页各占一部分屏幕"的
// 中间帧。现在的转场是「覆盖 / 揭开」，**没有让位页**。详见下方 NavHost 的注释。

/** Material motion 的标准缓动（emphasized decelerate 的常用近似）。 */
private val NAV_EASING = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** 把导航栈整体替换为 [route]（清掉其上层所有界面，含启动占位）。 */
private fun NavHostController.navigateToRoot(route: Any) {
    navigate(route) {
        popUpTo(0) { inclusive = true }
        launchSingleTop = true
    }
}

/**
 * 解锁链路：启动占位 → 解锁 → 库列表 → 主界面 Tab 容器。
 *
 * 拆出来是为让 [VaultixApp] 守住 detekt `LongMethod`（≤150 行）——导航图按
 * 「解锁 / 设置 / 条目」三段切，每段语义内聚。
 */
private fun NavGraphBuilder.vaultEntryGraph(
    navController: NavHostController,
    shellBridge: MainShellViewModel,
) {
    composable<SplashRoute> { SplashScreen() }
    // 解锁入口（根导航直达）：无参数版本，内部自动选中第一个已锁定的库。
    composable<UnlockEntryRoute> { entry ->
        val viewModel: UnlockViewModel = hiltViewModel(entry)
        UnlockScreen(
            viewModel = viewModel,
            onUnlocked = {
                // 对齐 Bastion「解锁即进主界面」：不回库列表，直接进 Tab 容器
                shellBridge.activeVaultStore.select(viewModel.vaultId)
                navController.navigateToRoot(MainShellRoute)
            },
            // 兜底：确实没有可解锁的库（例如列表异步变化）时退回库列表，
            // 不把用户留在"永远转圈"的解锁页上。
            onNoVault = { navController.navigateToRoot(VaultListRoute) },
            // 换一个库：回「我的密码库」页（列出全部库，含未解锁项）。
            onSwitchVault = { navController.navigate(VaultListRoute) },
        )
    }
    composable<VaultListRoute> {
        VaultListScreen(
            onAddVault = { navController.navigate(AddVaultRoute) },
            onAddKdbx = { navController.navigate(AddKdbxRoute) },
            onAddCloud = { navController.navigate(AddCloudVaultRoute) },
            onOpenVault = { vault ->
                if (vault.unlocked) {
                    // 已解锁：定为活跃库后直接进主界面（A4：库是筛选状态，不是导航参数）
                    shellBridge.activeVaultStore.select(vault.id)
                    navController.navigate(MainShellRoute)
                } else {
                    navController.navigate(UnlockRoute(vault.id))
                }
            },
            onOpenSettings = { navController.navigate(SettingsRoute) },
        )
    }
    // 主界面（Tab 容器：密码 / 验证码 / 卡包 / 设置 + 中央「+」）
    composable<MainShellRoute> {
        val activeVaultId by shellBridge.activeVaultStore.activeVaultId
            .collectAsStateWithLifecycle()
        // 「切换密码库」出口只在**多库并存**时有意义（issue #96）：单库时传 null，
        // 菜单项整项隐藏 —— 与「遍历所有库渲染能力开关」的坑（#93）同款纪律：
        // 能力不存在时隐藏，而不是给一个点了没意义的入口。
        val vaultCount by shellBridge.vaultCount.collectAsStateWithLifecycle()
        MainShellScreen(
            onOpenItem = { item ->
                activeVaultId?.let { vaultId ->
                    navController.navigate(ItemRoute(vaultId = vaultId, itemId = item.id))
                }
            },
            onOpenTrash = {
                activeVaultId?.let { navController.navigate(TrashRoute(vaultId = it)) }
            },
            onOpenPasskeys = {
                activeVaultId?.let { navController.navigate(PasskeysRoute(vaultId = it)) }
            },
            onOpenAutofillSettings = { navController.navigate(AutofillSettingsRoute) },
            onOpenImportExport = { navController.navigate(ImportExportRoute) },
            // 设置 Tab 内的「密码库管理」二级页：添加库（Bitwarden / 本地 KDBX）与
            // 「点未解锁的库去解锁」现在都是那一页内部的页内动作，主壳只负责导航过去。
            onOpenVaultManagement = { navController.navigate(VaultManagementRoute) },
            // 条目页空态里的兜底：活跃库锁着时去解它（与库数量无关，单库也需要）。
            onUnlockActiveVault = {
                activeVaultId?.let { navController.navigate(UnlockRoute(it)) }
            },
            // 页内主动锁定：交回根入口（若已全锁，RootNavState 会自己弹解锁页）
            onLocked = { navController.navigateToRoot(VaultListRoute) },
            // 切换密码库 → 「我的密码库」页（列出全部库，未解锁项可点进去输密码）。
            onSwitchVault = if (vaultCount > 1) {
                { navController.navigate(VaultListRoute) }
            } else {
                null
            },
        )
    }
    composable<UnlockRoute> { entry ->
        val viewModel: UnlockViewModel = hiltViewModel(entry)
        UnlockScreen(
            viewModel = viewModel,
            onUnlocked = {
                // 对齐 Bastion「解锁即进主界面」：不回库列表，直接进 Tab 容器
                shellBridge.activeVaultStore.select(viewModel.vaultId)
                navController.navigateToRoot(MainShellRoute)
            },
            // 该库在这期间被移除：退回上层（库列表），不要停在转圈页
            onNoVault = {
                if (!navController.popBackStack()) {
                    navController.navigateToRoot(VaultListRoute)
                }
            },
            // 换一个库：直达「我的密码库」页（清栈，避免解锁页在返回栈里反复出现）。
            onSwitchVault = { navController.navigateToRoot(VaultListRoute) },
        )
    }
}

/**
 * 设置链路：设置首页 → 自动填充二级设置 / 密码库管理二级页 → 添加库。
 */
private fun NavGraphBuilder.settingsGraph(navController: NavHostController) {
    composable<SettingsRoute> {
        SettingsScreen(
            onBack = { navController.popBackStack() },
            onOpenAutofillSettings = { navController.navigate(AutofillSettingsRoute) },
            onOpenImportExport = { navController.navigate(ImportExportRoute) },
            // 「密码库」组现在只有一个入口：选库 / 加库 / 配解锁方式全在二级页。
            onOpenVaultManagement = { navController.navigate(VaultManagementRoute) },
        )
    }
    composable<VaultManagementRoute> {
        VaultManagementScreen(
            onBack = { navController.popBackStack() },
            onAddBitwardenVault = { navController.navigate(AddVaultRoute) },
            onAddKdbxVault = { navController.navigate(AddKdbxRoute) },
            onAddCloudVault = { navController.navigate(AddCloudVaultRoute) },
            onOpenCloudAccounts = { navController.navigate(CloudAccountsRoute) },
            // 点未解锁的库 → 去解锁页（2026-09-15 修的空白页 bug，语义不能退化）。
            onOpenLockedVault = { vaultId -> navController.navigate(UnlockRoute(vaultId)) },
        )
    }
    composable<CloudAccountsRoute> {
        CloudAccountsScreen(onBack = { navController.popBackStack() })
    }
    composable<AutofillSettingsRoute> {
        AutofillSettingsScreen(onBack = { navController.popBackStack() })
    }
    composable<ImportExportRoute> {
        ImportExportScreen(onBack = { navController.popBackStack() })
    }
    composable<AddVaultRoute> {
        AddVaultScreen(
            onBack = { navController.popBackStack() },
            onAdded = { navController.popBackStack() },
        )
    }
    // 添加本地 KDBX 库：成功后回到库列表（新库已解锁，可直接点进去）
    composable<AddKdbxRoute> {
        AddKdbxScreen(
            onBack = { navController.popBackStack() },
            onAdded = { navController.popBackStack() },
        )
    }
    // 从网盘添加 KDBX 库（WebDAV / OneDrive）：成功后的返回语义与本地路径一致。
    composable<AddCloudVaultRoute> {
        AddCloudVaultScreen(
            onBack = { navController.popBackStack() },
            onAdded = { navController.popBackStack() },
        )
    }
}

/**
 * 条目链路：条目列表 → 验证码 → 通行密钥 → 回收站 → 条目详情。
 */
private fun NavGraphBuilder.itemsGraph(navController: NavHostController) {
    composable<ItemsRoute> { entry ->
        val route = entry.toRoute<ItemsRoute>()
        val viewModel: ItemsViewModel = hiltViewModel(entry)
        ItemsScreen(
            viewModel = viewModel,
            onBack = { navController.popBackStack() },
            onLocked = { navController.popBackStack() },
            onOpenTrash = {
                navController.navigate(TrashRoute(vaultId = route.vaultId))
            },
            onOpenItem = { item ->
                navController.navigate(ItemRoute(vaultId = route.vaultId, itemId = item.id))
            },
            onOpenTotp = {
                navController.navigate(TotpCodesRoute(vaultId = route.vaultId))
            },
            // 库锁定的兜底出口（路由带 vaultId，直接去解锁页）。
            onUnlockVault = { navController.navigate(UnlockRoute(route.vaultId)) },
        )
    }
    composable<TotpCodesRoute> { entry ->
        val route = entry.toRoute<TotpCodesRoute>()
        TotpCodesScreen(
            onBack = { navController.popBackStack() },
            onOpenPasskeys = {
                navController.navigate(PasskeysRoute(vaultId = route.vaultId))
            },
        )
    }
    composable<PasskeysRoute> {
        // ⚠️ 这里**不传** `vaultId`：`PasskeysViewModel` 用 `hiltViewModel()` 构造，
        // 它的 `SavedStateHandle` 直接来自本导航条目 —— 也就是 `PasskeysRoute` 携带的
        // `vaultId`，`checkNotNull(savedStateHandle[ARG_VAULT_ID])` 因此拿得到值。
        // 显式再传一遍反而会多出一个无人使用的参数（detekt `UnusedParameter` 会拦）。
        PasskeysScreen(
            onBack = { navController.popBackStack() },
        )
    }
    composable<TrashRoute> {
        TrashScreen(onBack = { navController.popBackStack() })
    }
    composable<ItemRoute> { entry ->
        // 详情页不再传 `onBack`：左上角返回箭头已按要求去掉（手势返回 / 返回键由导航库接管），
        // 该参数随之成为死参 —— 项目开了 detekt `UnusedParameter`，必须一并清掉。
        ItemDetailScreen(
            onDeleted = {
                navController.popBackStack()
            },
        )
    }
}
