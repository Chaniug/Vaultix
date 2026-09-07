package io.vaultix.vaultix.ui.vaultlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.vaultix.model.VaultSummary
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.AppFlavor

/**
 * 库列表（Docs/08 S3 最小版）。
 *
 * 交互基线（Docs/16 §4）：LargeTopAppBar 大标题随列表滚动缩放收起，
 * 由滚动位置驱动（nestedScroll），非定时器。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultListScreen(
    onAddVault: () -> Unit,
    onOpenVault: (VaultSummary) -> Unit,
    viewModel: VaultListViewModel = hiltViewModel(),
) {
    val vaults by viewModel.vaults.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text(text = stringResource(R.string.vault_list_title)) },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            // 仅 full 分发可添加 Bitwarden 库；offline 分发等 M2 的 KDBX 入口
            if (AppFlavor.supportsBitwarden && vaults.isNotEmpty()) {
                FloatingActionButton(onClick = onAddVault) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.vault_add_fab))
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (vaults.isEmpty()) {
                EmptyVaultState(onConnectBitwarden = onAddVault)
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(vaults, key = { it.id }) { vault ->
                        VaultCard(vault = vault, onClick = { onOpenVault(vault) })
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyVaultState(onConnectBitwarden: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.vault_list_empty_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(R.string.vault_list_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        if (AppFlavor.supportsBitwarden) {
            Button(onClick = onConnectBitwarden) {
                Text(stringResource(R.string.vault_connect_bitwarden))
            }
        } else {
            Text(
                text = stringResource(R.string.vault_kdbx_coming_soon),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VaultCard(vault: VaultSummary, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = vault.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = vault.account ?: vault.origin,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!vault.unlocked) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = stringResource(R.string.vault_badge_locked),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
