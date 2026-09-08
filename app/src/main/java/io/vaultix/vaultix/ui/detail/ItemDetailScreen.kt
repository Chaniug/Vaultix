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
import androidx.compose.material.icons.filled.Public
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
import android.content.Intent
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.text.style.TextOverflow
import io.vaultix.common.OtpUriParser
import io.vaultix.common.TotpConfig
import io.vaultix.common.TotpGenerator
import io.vaultix.common.UriFormat
import io.vaultix.common.UriKind
import io.vaultix.model.CustomFieldType
import io.vaultix.model.VaultCard
import io.vaultix.model.VaultCustomField
import io.vaultix.model.VaultFido2Credential
import io.vaultix.model.VaultIdentity
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import io.vaultix.model.VaultLinkedId
import io.vaultix.model.VaultSshKey
import io.vaultix.model.VaultUri
import kotlinx.coroutines.delay
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.common.ItemFormDialog
import io.vaultix.vaultix.ui.common.LINKED_FIELD_LABELS
import io.vaultix.vaultix.ui.common.itemTypeLabelRes
import android.content.Context

/**
 * 详情页一次性事件 → 用户文案（Deleted 由调用方导航，返回 null）。
 * 抽取为纯函数以控制主 Composable 的圈复杂度与行长。
 */
/** 复制事件对应的文案资源 id：按类型（密码/网址/验证码/用户名）与是否自动清除选择。 */
private fun copyResId(event: ItemDetailViewModel.UiEvent.CopyDone): Int {
    val base = when {
        event.isField -> R.string.copy_field
        event.isPassword -> R.string.copy_password
        event.isUri -> R.string.copy_uri
        event.isTotp -> R.string.copy_totp
        else -> R.string.copy_username
    }
    if (event.clearSeconds <= 0) return base
    return when (base) {
        R.string.copy_field -> R.string.copy_field_clears
        R.string.copy_password -> R.string.copy_password_clears
        R.string.copy_uri -> R.string.copy_uri_clears
        R.string.copy_totp -> R.string.copy_totp_clears
        else -> R.string.copy_username_clears
    }
}

private fun detailEventMessage(context: Context, event: ItemDetailViewModel.UiEvent): String? =
    when (event) {
        is ItemDetailViewModel.UiEvent.CopyDone -> {
            val res = copyResId(event)
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

/** 隐藏型自定义字段未展开时的掩码长度上下限。 */
private const val HIDDEN_MASK_MIN = 6
private const val HIDDEN_MASK_MAX = 24

/**
 * 详情区的复制动作集合。
 * 收敛成单个对象是为了把 [DetailBodyContent] 的参数数压到门禁线内（≤8）。
 */
private class DetailActions(
    val onCopyUsername: () -> Unit,
    val onCopyPassword: () -> Unit,
    val onCopyUri: (String) -> Unit,
    val onCopyTotp: (String) -> Unit,
    val onCopyField: (String) -> Unit,
)

/** 详情内容区：忙碌转圈 / 缺失提示 / 分区卡片（独立以便控制主 Composable 圈复杂度）。 */
@Composable
private fun DetailBodyContent(
    modifier: Modifier,
    busy: Boolean,
    item: VaultItem?,
    showPassword: Boolean,
    onTogglePassword: () -> Unit,
    actions: DetailActions,
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
            else -> DetailSections(
                item = item,
                showPassword = showPassword,
                onTogglePassword = onTogglePassword,
                actions = actions,
            )
        }
    }
}

/** 分区卡片列表（按字段有无逐个渲染；独立成函数以拆分圈复杂度）。 */
@Composable
private fun DetailSections(
    item: VaultItem,
    showPassword: Boolean,
    onTogglePassword: () -> Unit,
    actions: DetailActions,
) {
    Column(
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
                onCopyUsername = actions.onCopyUsername,
                onCopyPassword = actions.onCopyPassword,
            )
        }
        if (item.uris.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            UrisSection(item = item, onCopyUri = actions.onCopyUri)
        }
        if (item.totp != null) {
            Spacer(Modifier.height(12.dp))
            TotpSection(item = item, onCopyTotp = actions.onCopyTotp)
        }
        if (item.fido2Credentials.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            PasskeysSection(creds = item.fido2Credentials)
        }
        if (item.card != null) {
            Spacer(Modifier.height(12.dp))
            CardSection(item = item, onCopyField = actions.onCopyField)
        }
        if (item.sshKey != null) {
            Spacer(Modifier.height(12.dp))
            SshKeySection(item = item, onCopyField = actions.onCopyField)
        }
        if (item.identity != null) {
            Spacer(Modifier.height(12.dp))
            IdentitySection(item = item, onCopyField = actions.onCopyField)
        }
        if (item.customFields.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            CustomFieldsSection(item = item, onCopyField = actions.onCopyField)
        }
        if (item.notes.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            NotesSection(notes = item.notes)
        }
        Spacer(Modifier.height(24.dp))
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
                title = { Text(text = item?.title.orEmpty()) },
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
        val actions = remember(viewModel) {
            DetailActions(
                onCopyUsername = viewModel::copyUsername,
                onCopyPassword = viewModel::copyPassword,
                onCopyUri = viewModel::copyUri,
                onCopyTotp = viewModel::copyTotp,
                onCopyField = viewModel::copyField,
            )
        }
        DetailBodyContent(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            busy = state.saving || state.deleting,
            item = item,
            showPassword = showPassword,
            onTogglePassword = { showPassword = !showPassword },
            actions = actions,
        )
    }

    if (editOpen && item != null) {
        // 表单按 item.type 决定可编辑字段：登录 / 银行卡 / 身份均已可编辑，
        // SSH 密钥仅名称 + 备注（专属段保留服务端原值，由 data 层合并上传）。
        ItemFormDialog(
            title = stringResource(R.string.edit_item_title),
            initial = item,
            folders = viewModel.folders.collectAsStateWithLifecycle().value,
            saving = state.saving,
            onDismiss = { editOpen = false },
            onSave = { updated ->
                viewModel.updateItem(updated)
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

@Composable
private fun UrisSection(item: VaultItem, onCopyUri: (String) -> Unit) {
    val context = LocalContext.current
    Column {
        SectionTitle(text = stringResource(R.string.section_uris))
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                item.uris.forEachIndexed { index, v ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                    val kind = UriFormat.classify(v.uri)
                    val canOpen = when (kind) {
                        is UriKind.Website -> true
                        is UriKind.AndroidApp -> isAppInstalled(context, kind.packageName)
                        is UriKind.Other -> false
                    }
                    UriRow(
                        kind = kind,
                        canOpen = canOpen,
                        onCopy = { onCopyUri(v.uri) },
                        onOpen = {
                            when (kind) {
                                is UriKind.Website -> openUri(context, kind.raw)
                                is UriKind.AndroidApp -> launchAndroidApp(context, kind.packageName)
                                is UriKind.Other -> { }
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * 单个 URI 行：[UriKind] 决定展示形态：
 * - [UriKind.AndroidApp]：显示「应用」标签 + 包名，已安装才出现打开按钮；
 * - [UriKind.Website]：显示原始网址，点击在浏览器打开；
 * - [UriKind.Other]：原样展示，无打开动作。
 */
@Composable
private fun UriRow(kind: UriKind, canOpen: Boolean, onCopy: () -> Unit, onOpen: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 4.dp)) {
            if (kind is UriKind.AndroidApp) {
                Text(
                    text = stringResource(R.string.uri_type_android_app),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = kind.packageName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = kind.raw,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (canOpen) {
            IconButton(onClick = onOpen) {
                Icon(
                    Icons.Filled.Public,
                    contentDescription = stringResource(R.string.item_open_uri),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        IconButton(onClick = onCopy) {
            Icon(
                Icons.Filled.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun TotpSection(item: VaultItem, onCopyTotp: (String) -> Unit) {
    val totp = item.totp ?: return
    val config = remember(totp) { OtpUriParser.parse(totp) }
    Column {
        SectionTitle(text = stringResource(R.string.section_totp))
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (config == null) {
                Text(
                    text = stringResource(R.string.totp_invalid),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                TotpCodeContent(config = config, onCopy = onCopyTotp)
            }
        }
    }
}

/** TOTP 验证码按 3 位分组显示（便于肉眼读取），与 Bitwarden 客户端一致。 */
private const val TOTP_CODE_GROUP_SIZE = 3

/** 毫秒 → 秒级时间戳的换算常数。 */
private const val MILLIS_PER_SECOND = 1000L

@Composable
private fun TotpCodeContent(config: TotpConfig, onCopy: (String) -> Unit) {
    var timeSeconds by remember { mutableStateOf(System.currentTimeMillis() / MILLIS_PER_SECOND) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(MILLIS_PER_SECOND)
            timeSeconds = System.currentTimeMillis() / MILLIS_PER_SECOND
        }
    }
    val code = remember(config, timeSeconds) {
        TotpGenerator.generateTotp(
            secret = config.secret,
            timeSeconds = timeSeconds,
            period = config.period,
            digits = config.digits,
            algorithm = config.algorithm,
        )
    }
    val remaining = TotpGenerator.remainingSeconds(config.period, timeSeconds)
    val progress = 1f - TotpGenerator.progress(config.period, timeSeconds)
    Column(modifier = Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = code.chunked(TOTP_CODE_GROUP_SIZE).joinToString(" "),
                style = MaterialTheme.typography.headlineSmall,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${remaining}s",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = { onCopy(code) }) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PasskeysSection(creds: List<VaultFido2Credential>) {
    Column {
        SectionTitle(text = stringResource(R.string.section_passkeys))
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.passkey_count, creds.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.passkey_readonly_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                creds.forEach { c ->
                    Spacer(Modifier.height(12.dp))
                    val name = c.userDisplayName.ifBlank {
                        c.userName.ifBlank { stringResource(R.string.passkey_unnamed) }
                    }
                    Text(text = name, style = MaterialTheme.typography.titleMedium)
                    if (c.rpId.isNotBlank()) {
                        Text(
                            text = "${stringResource(R.string.passkey_rp)}：${c.rpId}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (c.userName.isNotBlank()) {
                        Text(
                            text = "${stringResource(R.string.passkey_user)}：${c.userName}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    val creationDate = c.creationDate
                    if (creationDate != null) {
                        Text(
                            text = stringResource(R.string.passkey_created, creationDate),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun openUri(context: Context, uri: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)))
    }
}

/** 自定义字段分区（只读展示；Bitwarden cipher.fields 对齐）。 */
@Composable
private fun CustomFieldsSection(item: VaultItem, onCopyField: (String) -> Unit) {
    Column {
        SectionTitle(text = stringResource(R.string.section_custom_fields))
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                item.customFields.forEachIndexed { index, field ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                    CustomFieldRow(field = field, onCopy = { onCopyField(field.value) })
                }
            }
        }
    }
}

/** 单行自定义字段：按类型展示（隐藏默认掩码可点开、布尔显示是/否、链接显示关联字段名）。 */
@Composable
private fun CustomFieldRow(field: VaultCustomField, onCopy: () -> Unit) {
    var revealHidden by remember(field.name) { mutableStateOf(false) }
    val displayValue = when (field.type) {
        CustomFieldType.Boolean -> stringResource(
            if (field.value.equals("true", ignoreCase = true)) {
                R.string.custom_field_boolean_yes
            } else {
                R.string.custom_field_boolean_no
            },
        )
        CustomFieldType.Linked -> {
            val linkedName = linkedFieldName(field.linkedId)
            linkedName ?: field.value.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.custom_field_linked_unknown, field.linkedId ?: 0)
        }
        CustomFieldType.Hidden -> if (revealHidden) {
            field.value
        } else {
            "•".repeat(field.value.length.coerceAtLeast(HIDDEN_MASK_MIN).coerceAtMost(HIDDEN_MASK_MAX))
        }
        else -> field.value
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 4.dp)) {
            Text(
                text = field.name.ifBlank { stringResource(R.string.custom_field_unnamed) },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = displayValue,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = if (field.type == CustomFieldType.Hidden && !revealHidden) 1 else 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (field.type == CustomFieldType.Hidden) {
            IconButton(onClick = { revealHidden = !revealHidden }) {
                Icon(
                    imageVector = if (revealHidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        IconButton(onClick = onCopy) {
            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}


/** 关联字段编号 → 标准字段名（按 Bitwarden 官方分段编码；未知返回 null）。 */
@Composable
private fun linkedFieldName(linkedId: Int?): String? {
    val id = VaultLinkedId.fromCode(linkedId) ?: return null
    return LINKED_FIELD_LABELS[id]?.let { stringResource(it) }
}

/** 目标 Android 应用是否已安装（用于给 androidapp:// URI 显示「打开」按钮）。 */
private fun isAppInstalled(context: Context, packageName: String): Boolean =
    runCatching { context.packageManager.getLaunchIntentForPackage(packageName) != null }
        .getOrDefault(false)

/** 启动已安装的 Android 应用（androidapp://<package> 的打开动作）。 */
private fun launchAndroidApp(context: Context, packageName: String) {
    runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) context.startActivity(intent)
    }
}

/** 银行卡/SSH 等普通字段的「标签 + 值 + 复制」一行。monospace 用于密钥/卡号。 */
@Composable
private fun FieldRow(label: String, value: String, onCopy: (String) -> Unit, monospace: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 4.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = if (monospace) {
                    MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                maxLines = if (monospace) 4 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { onCopy(value) }) {
            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun CardSection(item: VaultItem, onCopyField: (String) -> Unit) {
    val card = item.card ?: return
    Column {
        SectionTitle(text = stringResource(R.string.section_card))
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (card.cardholderName.isNotBlank()) {
                    FieldRow(stringResource(R.string.card_cardholder), card.cardholderName, onCopyField)
                }
                if (card.brand.isNotBlank()) {
                    FieldRow(stringResource(R.string.card_brand), card.brand, onCopyField)
                }
                if (card.number.isNotBlank()) {
                    FieldRow(stringResource(R.string.card_number), card.number, onCopyField, monospace = true)
                }
                val expiry = cardExpiryText(card.expMonth, card.expYear)
                if (expiry.isNotBlank()) {
                    FieldRow(stringResource(R.string.card_expiry), expiry, onCopyField)
                }
                if (card.code.isNotBlank()) {
                    FieldRow(stringResource(R.string.card_cvv), card.code, onCopyField, monospace = true)
                }
            }
        }
    }
}

@Composable
private fun SshKeySection(item: VaultItem, onCopyField: (String) -> Unit) {
    val ssh = item.sshKey ?: return
    Column {
        SectionTitle(text = stringResource(R.string.section_ssh_key))
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (ssh.privateKey.isNotBlank()) {
                    FieldRow(
                        stringResource(R.string.ssh_private_key),
                        ssh.privateKey,
                        onCopyField,
                        monospace = true,
                    )
                }
                if (ssh.publicKey.isNotBlank()) {
                    FieldRow(
                        stringResource(R.string.ssh_public_key),
                        ssh.publicKey,
                        onCopyField,
                        monospace = true,
                    )
                }
                if (ssh.keyFingerprint.isNotBlank()) {
                    FieldRow(
                        stringResource(R.string.ssh_fingerprint),
                        ssh.keyFingerprint,
                        onCopyField,
                        monospace = true,
                    )
                }
            }
        }
    }
}

/** 拼装有效期文本（expMonth/expYear 任一为空则省略对应段）。 */
private fun cardExpiryText(expMonth: String, expYear: String): String {
    val m = expMonth.trim()
    val y = expYear.trim()
    return when {
        m.isNotBlank() && y.isNotBlank() -> "$m / $y"
        m.isNotBlank() -> m
        y.isNotBlank() -> y
        else -> ""
    }
}

/**
 * 身份信息分区（type=Identity）。按 Bitwarden canonical 顺序展示 17 字段，
 * 仅渲染非空字段，每个值可复制。覆盖 Bastion 仅显示少数字段的兼容缺陷。
 */
@Composable
private fun IdentitySection(item: VaultItem, onCopyField: (String) -> Unit) {
    val id = item.identity ?: return
    val rows = listOf(
        stringResource(R.string.identity_title) to id.title,
        stringResource(R.string.identity_first_name) to id.firstName,
        stringResource(R.string.identity_middle_name) to id.middleName,
        stringResource(R.string.identity_last_name) to id.lastName,
        stringResource(R.string.identity_address1) to id.address1,
        stringResource(R.string.identity_address2) to id.address2,
        stringResource(R.string.identity_address3) to id.address3,
        stringResource(R.string.identity_city) to id.city,
        stringResource(R.string.identity_state) to id.state,
        stringResource(R.string.identity_postal_code) to id.postalCode,
        stringResource(R.string.identity_country) to id.country,
        stringResource(R.string.identity_company) to id.company,
        stringResource(R.string.identity_email) to id.email,
        stringResource(R.string.identity_phone) to id.phone,
        stringResource(R.string.identity_ssn) to id.ssn,
        stringResource(R.string.identity_username) to id.username,
        stringResource(R.string.identity_passport) to id.passportNumber,
        stringResource(R.string.identity_license) to id.licenseNumber,
    ).filter { it.second.isNotBlank() }
    if (rows.isEmpty()) return
    Column {
        SectionTitle(text = stringResource(R.string.section_identity))
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                rows.forEach { (label, value) ->
                    FieldRow(label, value, onCopyField)
                }
            }
        }
    }
}
