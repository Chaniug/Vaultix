package io.vaultix.vaultix.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
 *   RootNavState.VaultUnlockedGraph ─▶ VaultList ──添加──▶ AddVault
 *                                        │
 *                                        ├─ 点按已解锁库 ─▶ Items ─▶ ItemDetail
 *                                        └─ 点按已锁定库 ─▶ Unlock ─▶ Items
 *
 *   RootNavState.VaultLocked ─▶ 有库：Unlock（**解锁界面，不是错误页**）
 *                               无库：VaultList（首次使用，引导添加）
 *
 * 自动锁定（AutoLockController.lockEvents）触发时清掉所有上层路由回列表。
 * offline 分发不展示「连接 Bitwarden」入口（AppFlavor）。
 */
@Composable
fun VaultixApp() {
    val navController = rememberNavController()
    val shellViewModel: VaultShellViewModel = hiltViewModel()
    val rootNavViewModel: RootNavViewModel = hiltViewModel()
    val lockEpoch by shellViewModel.lockEpoch.collectAsStateWithLifecycle()
    val rootNavState by rootNavViewModel.rootNavState.collectAsStateWithLifecycle()

    // 起始路由：由根导航状态决定（改为「写死 VaultList」→「按锁态路由」）。
    // 用 remember 固化首次计算结果——NavHost 的 startDestination 在组合期间不应变化，
    // 后续状态变化由下面的 LaunchedEffect 负责导航。
    val startDestination = remember { resolveStartDestination(rootNavState) }

    LaunchedEffect(lockEpoch) {
        if (lockEpoch > 0) {
            navController.navigate(VaultListRoute) {
                popUpTo(VaultListRoute) { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    // 根导航状态**变为** VaultLocked（如自动锁定）：把导航栈收回到解锁入口，
    // 避免用户停留在已失效的明文界面上（对齐 Bitwarden RootNavScreen 的重定向）。
    LaunchedEffect(rootNavState) {
        if (rootNavState is RootNavState.VaultLocked && lockEpoch > 0) {
            navController.navigate(UnlockEntryRoute) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        // 解锁入口（根导航直达）：无参数版本，内部自动选中第一个已锁定的库。
        composable<UnlockEntryRoute> { entry ->
            val viewModel: UnlockViewModel = hiltViewModel(entry)
            UnlockScreen(
                viewModel = viewModel,
                onUnlocked = {
                    navController.navigate(VaultListRoute) {
                        popUpTo<UnlockEntryRoute> { inclusive = true }
                    }
                },
            )
        }
        composable<VaultListRoute> {
            VaultListScreen(
                onAddVault = { navController.navigate(AddVaultRoute) },
                onOpenVault = { vault ->
                    if (vault.unlocked) {
                        navController.navigate(ItemsRoute(vault.id))
                    } else {
                        navController.navigate(UnlockRoute(vault.id))
                    }
                },
                onOpenSettings = { navController.navigate(SettingsRoute) },
            )
        }
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
        composable<UnlockRoute> { entry ->
            val viewModel: UnlockViewModel = hiltViewModel(entry)
            UnlockScreen(
                viewModel = viewModel,
                onUnlocked = {
                    navController.navigate(ItemsRoute(viewModel.vaultId)) {
                        popUpTo<UnlockRoute> { inclusive = true }
                    }
                },
            )
        }
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
}

/**
 * 依根导航状态决定起始路由（对齐 Bitwarden `RootNavScreen` 的 `VaultLocked -> 解锁页`）。
 *
 * ⚠️ **首次使用（一个库都还没有）要进 VaultList 而不是解锁页**：
 * 此时没有任何库可解锁，把用户扔在解锁页会卡死（无库可输）。Bitwarden 用
 * `isLoggedIn` 区分，Vaultix 用「有没有库」区分。
 */
private fun resolveStartDestination(state: RootNavState): Any = when (state) {
    // 锁定态且**有库** → 解锁入口；无库的情况由 UnlockViewModel 自行兜底回列表，
    // 但更干净的做法是这里就区分——UnlockViewModel 若发现无库会立即 finish 回列表。
    RootNavState.VaultLocked -> UnlockEntryRoute
    RootNavState.VaultUnlockedGraph -> VaultListRoute
}
