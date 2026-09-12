package io.vaultix.vaultix.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
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
import io.vaultix.vaultix.ui.addvault.AddVaultScreen
import io.vaultix.vaultix.ui.detail.ItemDetailScreen
import io.vaultix.vaultix.ui.items.ItemsScreen
import io.vaultix.vaultix.ui.items.ItemsViewModel
import io.vaultix.vaultix.ui.passkeys.PasskeysScreen
import io.vaultix.vaultix.ui.rootnav.RootNavState
import io.vaultix.vaultix.ui.rootnav.RootNavViewModel
import io.vaultix.vaultix.ui.settings.AutofillSettingsScreen
import io.vaultix.vaultix.ui.settings.SettingsScreen
import io.vaultix.vaultix.ui.shell.MainShellScreen
import io.vaultix.vaultix.ui.shell.MainShellViewModel
import io.vaultix.vaultix.ui.totp.TotpCodesScreen
import io.vaultix.vaultix.ui.trash.TrashScreen
import io.vaultix.vaultix.ui.unlock.UnlockScreen
import io.vaultix.vaultix.ui.unlock.UnlockViewModel
import io.vaultix.vaultix.ui.vaultlist.VaultListScreen

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
        CircularProgressIndicator()
    }
}

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
        )
    }
    composable<VaultListRoute> {
        VaultListScreen(
            onAddVault = { navController.navigate(AddVaultRoute) },
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
            // 页内主动锁定：交回根入口（若已全锁，RootNavState 会自己弹解锁页）
            onLocked = { navController.navigateToRoot(VaultListRoute) },
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
        )
    }
}

/**
 * 设置链路：设置首页 → 自动填充二级设置 → 添加库。
 */
private fun NavGraphBuilder.settingsGraph(navController: NavHostController) {
    composable<SettingsRoute> {
        SettingsScreen(
            onBack = { navController.popBackStack() },
            onOpenAutofillSettings = { navController.navigate(AutofillSettingsRoute) },
        )
    }
    composable<AutofillSettingsRoute> {
        AutofillSettingsScreen(onBack = { navController.popBackStack() })
    }
    composable<AddVaultRoute> {
        AddVaultScreen(
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
        PasskeysScreen(
            onBack = { navController.popBackStack() },
        )
    }
    composable<TrashRoute> {
        TrashScreen(onBack = { navController.popBackStack() })
    }
    composable<ItemRoute> { entry ->
        ItemDetailScreen(
            onBack = { navController.popBackStack() },
            onDeleted = {
                navController.popBackStack()
            },
        )
    }
}
