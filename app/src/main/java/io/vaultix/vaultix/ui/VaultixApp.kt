package io.vaultix.vaultix.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import io.vaultix.vaultix.ui.settings.SettingsScreen
import io.vaultix.vaultix.ui.totp.TotpCodesScreen
import io.vaultix.vaultix.ui.trash.TrashScreen
import io.vaultix.vaultix.ui.unlock.UnlockScreen
import io.vaultix.vaultix.ui.unlock.UnlockViewModel
import io.vaultix.vaultix.ui.vaultlist.VaultListScreen

/**
 * 应用导航（Docs/08 §1，M1 闭环）：
 *
 *   VaultList ──添加──▶ AddVault ──成功──▶ 返回列表（新库已解锁）
 *       │
 *       ├─ 点按已解锁库 ─────▶ Items ──点条目──▶ ItemDetail（编辑/删除）
 *       └─ 点按已锁定库 ──▶ Unlock ──成功──▶ Items（替换 Unlock）
 *
 * 自动锁定（AutoLockController.lockEvents）触发时清掉所有上层路由回列表。
 * offline 分发不展示「连接 Bitwarden」入口（AppFlavor）。
 */
@Composable
fun VaultixApp() {
    val navController = rememberNavController()
    val shellViewModel: VaultShellViewModel = hiltViewModel()
    val lockEpoch by shellViewModel.lockEpoch.collectAsStateWithLifecycle()

    LaunchedEffect(lockEpoch) {
        if (lockEpoch > 0) {
            navController.navigate(VaultListRoute) {
                popUpTo(VaultListRoute) { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = VaultListRoute,
    ) {
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
            SettingsScreen(onBack = { navController.popBackStack() })
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
        composable<PasskeysRoute> { entry ->
            val route = entry.toRoute<PasskeysRoute>()
            PasskeysScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable<TrashRoute> { entry ->
            val route = entry.toRoute<TrashRoute>()
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
