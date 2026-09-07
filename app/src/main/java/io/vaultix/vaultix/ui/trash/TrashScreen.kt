package io.vaultix.vaultix.ui.trash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.vaultix.model.VaultItem
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.common.itemTypeLabelRes

/**
 * 回收站（Docs/08 S19）：行 = 标题 + 类型徽标；动作 = 恢复 / 永久删除（二次确认）。
 * 永久删除后服务端 30 天保留语义不存在——该操作立即不可恢复，文案如实说明。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    onBack: () -> Unit,
    viewModel: TrashViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val trashItems by viewModel.trashItems.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var pendingForeverDelete by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            val message = when (event) {
                is TrashViewModel.UiEvent.Restored -> context.getString(
                    if (event.synced) R.string.trash_restore_synced else R.string.trash_restore_queued,
                )
                is TrashViewModel.UiEvent.DeletedForever -> context.getString(
                    if (event.synced) R.string.trash_deleted_forever else R.string.trash_delete_queued,
                )
                is TrashViewModel.UiEvent.Failed ->
                    context.getString(R.string.item_save_failed, event.message)
            }
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.trash_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(),
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (trashItems.isEmpty()) {
                EmptyTrashState()
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(trashItems, key = { it.id }) { item ->
                        TrashRow(
                            item = item,
                            busy = state.busy,
                            onRestore = { viewModel.restore(item.id) },
                            onDeleteForever = { pendingForeverDelete = item.id },
                        )
                    }
                }
            }
        }
    }

    val deletingItem = trashItems.firstOrNull { it.id == pendingForeverDelete }
    if (deletingItem != null) {
        AlertDialog(
            onDismissRequest = { pendingForeverDelete = null },
            title = { Text(stringResource(R.string.trash_delete_forever_title)) },
            text = { Text(stringResource(R.string.trash_delete_forever_message, deletingItem.title)) },
            confirmButton = {
                TextButton(
                    enabled = !state.busy,
                    onClick = {
                        pendingForeverDelete = null
                        viewModel.deleteForever(deletingItem.id)
                    },
                ) {
                    Text(
                        text = stringResource(R.string.trash_delete_forever_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingForeverDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun EmptyTrashState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.trash_empty_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(R.string.trash_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun TrashRow(
    item: VaultItem,
    busy: Boolean,
    onRestore: () -> Unit,
    onDeleteForever: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title.ifBlank { stringResource(R.string.items_item_unnamed) },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(itemTypeLabelRes(item.type)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onRestore, enabled = !busy) {
                    Text(stringResource(R.string.action_restore))
                }
                IconButton(onClick = onDeleteForever, enabled = !busy) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.trash_delete_forever_title),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
    }
}
