package io.vaultix.vaultix.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.common.ItemFormDialog
import io.vaultix.vaultix.ui.common.itemTypeLabelRes
import android.content.Context

/**
 * 详情页一次性事件 → 用户文案（Deleted 由调用方导航，返回 null）。
 * 抽取为纯函数以控制主 Composable 的圈复杂度与行长。
 */
private fun detailEventMessage(context: Context, event: ItemDetailViewModel.UiEvent): String? =
    when (event) {
        is ItemDetailViewModel.UiEvent.CopyDone -> {
            val res = when {
                event.isPassword && event.clearSeconds > 0 -> R.string.copy_password_clears
                event.isPassword -> R.string.copy_password
                event.clearSeconds > 0 -> R.string.copy_username_clears
                else -> R.string.copy_username
            }
            if (event.clearSeconds > 0) {
                context.getString(res, event.clearSeconds)
            } else {
                context.getString(res)
            }
        }
        ItemDetailViewModel.UiEvent.CopyFailed -> context.getString(R.string.detail_copy_failed)
        ItemDetailViewModel.UiEvent.SaveSynced -> context.getString(R.string.item_saved_synced)
        ItemDetailViewModel.UiEvent.SaveQueued -> context.getString(R.string.item_saved_queued)
        is ItemDetailViewModel.UiEvent.SaveFailed ->
            context.getString(R.string.item_save_failed, event.message)
        ItemDetailViewModel.UiEvent.Deleted -> null
    }

/** 掩码星号数量上限（密码过长时截断显示，复制不受影响）。 */
private const val MAX_MASK_LENGTH = 24

/** 详情内容区：忙碌转圈 / 缺失提示 / 分区卡片（独立以便控制主 Composable 圈复杂度）。 */
@Composable
private fun DetailBodyContent(
    modifier: Modifier,
    busy: Boolean,
    item: VaultItem?,
    showPassword: Boolean,
    onTogglePassword: () -> Unit,
    onCopyUsername: () -> Unit,
    onCopyPassword: () -> Unit,
) {
    Box(modifier = modifier) {
        when {
            busy -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            item == null -> Text(
                text = stringResource(R.string.detail_missing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
            )
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                if (item.type != VaultItemType.Login) {
                    Text(
                        text = stringResource(itemTypeLabelRes(item.type)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (item.username.isNotBlank() || item.password.isNotBlank()) {
                    LoginSection(
                        item = item,
                        showPassword = showPassword,
                        onTogglePassword = onTogglePassword,
                        onCopyUsername = onCopyUsername,
                        onCopyPassword = onCopyPassword,
                    )
                }
                if (item.notes.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    NotesSection(notes = item.notes)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * 条目详情（Docs/08 S9 最小版）。
 *
 * 分区卡片：登录信息（用户名/密码，密码可显隐）+ 备注；用户名/密码支持复制
 * （敏感剪贴板 + 自动清除，反馈含秒数）。应用栏动作：编辑（表单预填）、
 * 删除（软删除 = 回收站，二次确认）。删除成功后回调返回列表。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemDetailScreen(
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    viewModel: ItemDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    var showPassword by rememberSaveable { mutableStateOf(false) }
    var editOpen by rememberSaveable { mutableStateOf(false) }
    var deleteConfirmOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                ItemDetailViewModel.UiEvent.Deleted -> onDeleted()
                else -> {
                    val message = detailEventMessage(context, event)
                    if (!message.isNullOrBlank()) {
                        snackbarHostState.showSnackbar(message)
                    }
                }
            }
        }
    }

    val item = state.item

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            LargeTopAppBar(
                title = { Text(text = item?.title ?: "") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    if (item != null) {
                        IconButton(onClick = { editOpen = true }) {
                            Icon(Icons.Filled.Edit, contentDescription = null)
                        }
                        IconButton(onClick = { deleteConfirmOpen = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = null)
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        DetailBodyContent(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            busy = state.saving || state.deleting,
            item = item,
            showPassword = showPassword,
            onTogglePassword = { showPassword = !showPassword },
            onCopyUsername = viewModel::copyUsername,
            onCopyPassword = viewModel::copyPassword,
        )
    }

    if (editOpen && item != null) {
        val isLogin = item.type == VaultItemType.Login
        val typeName = context.getString(itemTypeLabelRes(item.type))
        ItemFormDialog(
            title = stringResource(R.string.edit_item_title),
            initialName = item.title,
            initialUsername = item.username,
            initialPassword = item.password,
            initialNotes = item.notes,
            saving = state.saving,
            loginFieldsVisible = isLogin,
            editHint = if (isLogin) {
                null
            } else {
                context.getString(R.string.item_edit_type_fields_readonly, typeName)
            },
            onDismiss = { editOpen = false },
            onSave = { name, username, password, notes ->
                viewModel.updateItem(name, username, password, notes)
                editOpen = false
            },
        )
    }

    if (deleteConfirmOpen && item != null) {
        AlertDialog(
            onDismissRequest = { deleteConfirmOpen = false },
            title = { Text(stringResource(R.string.delete_item_title)) },
            text = { Text(stringResource(R.string.delete_item_message, item.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteConfirmOpen = false
                        viewModel.deleteItem()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.delete_item_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmOpen = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
    )
}

@Composable
private fun LoginSection(
    item: VaultItem,
    showPassword: Boolean,
    onTogglePassword: () -> Unit,
    onCopyUsername: () -> Unit,
    onCopyPassword: () -> Unit,
) {
    Column {
        SectionTitle(text = stringResource(R.string.section_login))
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (item.username.isNotBlank()) {
                DetailFieldRow(
                    label = stringResource(R.string.item_field_username),
                    value = item.username,
                    onCopy = onCopyUsername,
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 80.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
            }
            if (item.password.isNotBlank()) {
                val masked = "•".repeat(item.password.length.coerceAtMost(MAX_MASK_LENGTH))
                DetailFieldRow(
                    label = stringResource(R.string.item_field_password),
                    value = if (showPassword) item.password else masked,
                    valueFontFamily = FontFamily.Monospace,
                    extraAction = {
                        IconButton(onClick = onTogglePassword) {
                            Icon(
                                imageVector = if (showPassword) {
                                    Icons.Filled.VisibilityOff
                                } else {
                                    Icons.Filled.Visibility
                                },
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                    onCopy = onCopyPassword,
                )
            }
        }
    }
}

@Composable
private fun NotesSection(notes: String) {
    Column {
        SectionTitle(text = stringResource(R.string.section_notes))
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = notes,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun DetailFieldRow(
    label: String,
    value: String,
    onCopy: () -> Unit,
    extraAction: (@Composable () -> Unit)? = null,
    valueFontFamily: FontFamily? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = valueFontFamily,
            maxLines = 1,
            modifier = Modifier
                .weight(1f)
                .padding(end = 4.dp),
        )
        extraAction?.invoke()
        IconButton(onClick = onCopy) {
            Icon(
                Icons.Filled.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
