package io.vaultix.vaultix.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.hilt.navigation.compose.hiltViewModel
import io.vaultix.vaultix.ui.addvault.AddVaultScreen
import io.vaultix.vaultix.ui.items.ItemsScreen
import io.vaultix.vaultix.ui.unlock.UnlockScreen
import io.vaultix.vaultix.ui.unlock.UnlockViewModel
import io.vaultix.vaultix.ui.items.ItemsViewModel
import io.vaultix.vaultix.ui.vaultlist.VaultListScreen

/**
 * 应用导航（Docs/08 §1，M1 最小闭环）：
 *
 *   VaultList ──添加──▶ AddVault ──成功──▶ 返回列表（新库已解锁）
 *       │
 *       ├─ 点按已解锁库 ───────────────▶ Items
 *       └─ 点按已锁定库 ─▶ Unlock ─成功─▶ Items（替换 Unlock）
 *
 * 锁定任意库后回到列表。offline 分发不展示「连接 Bitwarden」入口（AppFlavor）。
 */
@Composable
fun VaultixApp() {
    val navController = rememberNavController()

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
            )
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
            val viewModel: ItemsViewModel = hiltViewModel(entry)
            ItemsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onLocked = { navController.popBackStack() },
            )
        }
    }
}
