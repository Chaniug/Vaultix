package io.vaultix.vaultix.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Public
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import io.vaultix.common.OtpUriParser
import io.vaultix.common.TotpConfig
import io.vaultix.common.TotpGenerator
import io.vaultix.common.UriFormat
import io.vaultix.common.UriKind
import io.vaultix.model.CustomFieldType
import io.vaultix.common.CardBrand
import io.vaultix.common.CardBrandDetector
import io.vaultix.common.formatCardNumberGrouped
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
import io.vaultix.vaultix.ui.common.BadgeTone
import io.vaultix.vaultix.ui.common.ItemFormDialog
import io.vaultix.vaultix.ui.common.LINKED_FIELD_LABELS
import io.vaultix.vaultix.ui.common.SiteIcon
import io.vaultix.vaultix.ui.common.TypeBadge
import io.vaultix.vaultix.ui.common.itemTypeLabelRes
import android.content.Context
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.input.nestedscroll.nestedScroll
import io.vaultix.vaultix.ui.common.CapabilityIcon
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.WindowInsets
import io.vaultix.vaultix.ui.common.VaultixExpressiveTopBar
import io.vaultix.vaultix.ui.common.rememberImmersiveBarPadding
import io.vaultix.vaultix.ui.common.rememberScrollCollapseFraction
import io.vaultix.vaultix.ui.common.VaultixWavyProgress
import io.vaultix.vaultix.ui.theme.Spacing

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

/**
 * 详情页头部站点图标尺寸。
 *
 * 2026-09-16：48dp → **56dp**。头部是整页唯一的"视觉锚点"，而详情页下方全是
 * 12–16sp 的字段行；图标太小会让整页缺少落点，扫视时第一眼不知道落在哪
 * （M3 Expressive 的「hero 元素要足够大」同样适用于详情页头部）。
 */
private val DETAIL_HEADER_ICON = 56.dp

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
    serverOrigin: String?,
    showPassword: Boolean,
    onTogglePassword: () -> Unit,
    scrollState: ScrollState,
    actions: DetailActions,
) {
    Box(modifier = modifier) {
        when {
            busy -> VaultixWavyProgress(modifier = Modifier.align(Alignment.Center))
            item == null -> Text(
                text = stringResource(R.string.detail_missing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(Spacing.xxl),
            )
            else -> DetailSections(
                item = item,
                serverOrigin = serverOrigin,
                showPassword = showPassword,
                onTogglePassword = onTogglePassword,
                scrollState = scrollState,
                actions = actions,
            )
        }
    }
}

/** 分区卡片列表（按字段有无逐个渲染；独立成函数以拆分圈复杂度）。 */
@Composable
private fun DetailSections(
    item: VaultItem,
    serverOrigin: String?,
    showPassword: Boolean,
    onTogglePassword: () -> Unit,
    scrollState: ScrollState,
    actions: DetailActions,
) {
    // 让位必须做在**滚动内容里**（可滚动的 `Spacer`），不能做成外层容器 padding ——
    // 做在外面，内容就永远到不了顶栏那一条带子里（`.ai/ISSUES.md` #67）。
    val barPadding = rememberImmersiveBarPadding(rememberScrollCollapseFraction(scrollState))
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
    ) {
        Spacer(Modifier.height(barPadding))
        // 顶部头部：站点图标 + 一行摘要（网址数 / 2FA / 通行密钥）+ 用户名 + 类型徽标。
        // 标题**只在顶栏出现一次**，头部不再重复（见 [DetailHeader] 的 KDoc）。
        DetailHeader(item = item, serverOrigin = serverOrigin)
        Spacer(Modifier.height(20.dp))
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
            Spacer(Modifier.height(Spacing.md))
            UrisSection(item = item, onCopyUri = actions.onCopyUri)
        }
        if (item.totp != null) {
            Spacer(Modifier.height(Spacing.md))
            // 默认**不显示**验证码：点分区里的眼睛才显示（详情页不跑定时器，见 [TotpSection]）。
            TotpSection(totp = item.totp)
        }
        if (item.fido2Credentials.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.md))
            PasskeysSection(creds = item.fido2Credentials)
        }
        if (item.card != null) {
            Spacer(Modifier.height(Spacing.md))
            CardSection(item = item, onCopyField = actions.onCopyField)
        }
        if (item.sshKey != null) {
            Spacer(Modifier.height(Spacing.md))
            SshKeySection(item = item, onCopyField = actions.onCopyField)
        }
        if (item.identity != null) {
            Spacer(Modifier.height(Spacing.md))
            IdentitySection(item = item, onCopyField = actions.onCopyField)
        }
        if (item.customFields.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.md))
            CustomFieldsSection(item = item, onCopyField = actions.onCopyField)
        }
        if (item.notes.isNotBlank()) {
            Spacer(Modifier.height(Spacing.md))
            NotesSection(notes = item.notes)
        }
        Spacer(Modifier.height(Spacing.xl))
    }
}

/**
 * 详情页头部：站点图标 + **一行摘要** + 用户名 + 类型徽标。
 *
 * ⚠️ 2026-09-13 用户反馈两件事，本函数一并解决：
 * 1. 「密码标题好像有两个、显示重复了，而且位置太低」—— 此前 `LargeTopAppBar` 与这里
 *    **各画了一遍 `item.title`**。现在标题**只由顶栏承担**：它天然在最上方，收起时自动
 *    变小，且与滚动联动；头部不再重复画标题。
 * 2. 「留白的那半部分可以显示有几条网址、含 2FA、通行密钥之类的内容」—— 省下的标题位
 *    换成 [detailSummary] 的一行摘要。这几项是"核对条目"时要一眼看到的信息，
 *    此前必须滚下去逐个分区看。
 */
@Composable
private fun DetailHeader(item: VaultItem, serverOrigin: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SiteIcon(item = item, serverOrigin = serverOrigin, size = DETAIL_HEADER_ICON)
        Spacer(Modifier.width(Spacing.lg))
        Column(modifier = Modifier.weight(1f)) {
            val summary = detailSummary(item)
            if (summary.isNotEmpty()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (item.username.isNotBlank()) {
                Text(
                    text = item.username,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = Spacing.sm),
            ) {
                TypeBadge(stringResource(itemTypeLabelRes(item.type)), BadgeTone.NEUTRAL)
                // 能力用「小图标」而不是文字胶囊（对齐列表行尾，见 [CapabilityIcon]）。
                if (!item.totp.isNullOrBlank()) {
                    CapabilityIcon(
                        icon = Icons.Filled.Timer,
                        tint = MaterialTheme.colorScheme.primary,
                        contentDescription = stringResource(R.string.items_filter_totp),
                    )
                }
                if (item.fido2Credentials.isNotEmpty()) {
                    CapabilityIcon(
                        icon = Icons.Filled.Key,
                        tint = MaterialTheme.colorScheme.tertiary,
                        contentDescription = stringResource(R.string.items_filter_passkey),
                    )
                }
            }
        }
    }
}

/**
 * 头部摘要：`N 个网址 · 2FA · N 个通行密钥`（没有的项直接不出现，都没有则返回空串）。
 *
 * 用一句话而不是三枚徽标：这里是**概览**（可数信息），徽标区回答的是**分类**
 * （这条是什么类型 / 有什么能力），两者分工不同，不该挤在同一种视觉语言里。
 */
@Composable
private fun detailSummary(item: VaultItem): String {
    val parts = buildList {
        if (item.uris.isNotEmpty()) {
            add(stringResource(R.string.detail_summary_uris, item.uris.size))
        }
        // "2FA" 是三字母行业缩写，不随语言变化，直接写在代码里。
        if (!item.totp.isNullOrBlank()) add("2FA")
        if (item.fido2Credentials.isNotEmpty()) {
            add(stringResource(R.string.detail_summary_passkeys, item.fido2Credentials.size))
        }
    }
    return parts.joinToString(" · ")
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
    onDeleted: () -> Unit,
    viewModel: ItemDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    // 沉浸式浮动顶栏用：内容滚动状态 → 顶栏收起比例（与密码 / 验证码 / 卡包页同一套）。
    val scrollState = rememberScrollState()
    val listCollapse = rememberScrollCollapseFraction(scrollState)

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
        // 顶栏不再由 Scaffold 预留高度（沉浸式），状态栏内边距由顶栏自己处理。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
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
        // ⚠️ 2026-09-13 第二轮用户反馈：「密码详情页，标题距离上边还存在一些间隙不好看，
        // 排版和布局不好看，滑动的时候效果不好看。」
        // 根因：这里原本用 `LargeTopAppBar` —— 它在「动作行」和「大标题」之间自带一段
        // 固定留白（Material 的 large 规格），而且大标题的收起动画幅度大、和内容不同步。
        // 现在换成这个项目自己的**沉浸式浮动顶栏**（[VaultixExpressiveTopBar]，密码 /
        // 验证码 / 卡包三个列表都在用）：栏高 72dp→48dp 平滑收起、内容从 y=0 起铺、
        // 没有任何"多出来的一段空白"。四个页面从此是同一套顶栏语言。
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            DetailBodyContent(
                modifier = Modifier.fillMaxSize(),
                busy = state.saving || state.deleting,
                item = item,
                serverOrigin = state.serverOrigin,
                showPassword = showPassword,
                onTogglePassword = { showPassword = !showPassword },
                scrollState = scrollState,
                actions = actions,
            )
            if (item != null) {
                VaultixExpressiveTopBar(
                    title = item.title.ifBlank { stringResource(R.string.items_item_unnamed) },
                    collapseFraction = listCollapse,
                    modifier = Modifier.align(Alignment.TopCenter),
                    // ⚠️ 2026-09-13 第三轮用户要求：「左上角返回按钮可以取消了，现在都是手势返回，
                    // 给密码标题腾出显示位置。」
                    // ⇒ 这里**不传** `navigationIcon`。返回仍有两条路：系统返回手势 / 返回键
                    // （详情页是二级路由，栈里有上一层，`BackHandler` 由导航库自己接管）。
                    // 去掉后标题左移到 16dp 起始位，长标题能多显示约一个字符位。
                    actions = {
                        IconButton(onClick = { editOpen = true }) {
                            Icon(Icons.Filled.Edit, contentDescription = null)
                        }
                        IconButton(onClick = { deleteConfirmOpen = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = null)
                        }
                    },
                )
            }
        }
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

/**
 * 分区卡片的**统一外框**（底色 / 圆角一处定义）。
 *
 * ⚠️ 2026-09-16：此前各分区**各自拍一个容器色**——登录信息、备注用
 * `surfaceContainerHigh`，网址 / 2FA / 卡片 / SSH / 身份用 `surfaceContainerLow`。
 * 同一层级的九张卡片两种底色，扫一眼就是"深浅不一的花斑"，这是详情页
 * 「观感不统一」的根因（`.ai/conventions/8.4-UI·观感.md`：同级容器必须同色）。
 * 现在全部收口到本函数的 `surfaceContainerLow`，任何分区都不再自己指定颜色。
 *
 * 圆角取 16dp（M3 Expressive 分组容器量级）：比 M3 默认 filled-card 的 12dp
 * 更"有形状"，又不至于像 28dp 那样在窄屏上吃掉内容宽度。
 */
@Composable
private fun DetailCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        shape = DETAIL_CARD_SHAPE,
        modifier = Modifier.fillMaxWidth(),
        content = content,
    )
}

/** 分区卡片圆角（见 [DetailCard] 的取舍说明）。 */
private val DETAIL_CARD_SHAPE = RoundedCornerShape(16.dp)

/**
 * 分区小标题（登录信息 / 网址 / 2FA / 通行密钥 …）。
 *
 * ⚠️ 2026-09-13 用户反馈「登录信息、网址这些小标题是否要稍微大一点，优化一下排版」：
 * `labelLarge` → **`titleMedium`(16sp)**，并用 `primary` 主色 —— 分区之间的分界
 * 一眼可见，整页读起来有层次（此前 14sp + `onSurfaceVariant` 灰字，和正文几乎同级）。
 * 下内边距 6dp → 8dp：标题与它自己的卡片贴紧，与上一张卡片拉开。
 */
@Composable
private fun SectionTitle(text: String, icon: ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = Modifier.padding(start = Spacing.xs, bottom = Spacing.sm),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
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
        SectionTitle(text = stringResource(R.string.section_login), icon = Icons.Filled.Person)
        DetailCard {
            if (item.username.isNotBlank()) {
                DetailFieldRow(
                    label = stringResource(R.string.item_field_username),
                    value = item.username,
                    onCopy = onCopyUsername,
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = Spacing.lg),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
            }
            if (item.password.isNotBlank()) {
                val masked = "•".repeat(item.password.length.coerceAtMost(MAX_MASK_LENGTH))
                DetailFieldRow(
                    label = stringResource(R.string.item_field_password),
                    value = if (showPassword) item.password else masked,
                    monospace = true,
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
        SectionTitle(text = stringResource(R.string.section_notes), icon = Icons.Filled.Notes)
        DetailCard {
            Text(
                text = notes,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(Spacing.lg),
            )
        }
    }
}

/** 等宽值（密钥 / 卡号）的最大行数：私钥可能很长，给足可视空间又不至于撑爆整页。 */
private const val MONOSPACE_MAX_LINES = 4

/** 普通值的最大行数：紧凑单行，长值截断（复制取到的仍是完整值）。 */
private const val PLAIN_MAX_LINES = 1

/**
 * 详情页**唯一**的字段行形态：小标签在上、值在下，右侧复制（+ 可选附加动作）。
 *
 * ⚠️ 2026-09-16：此前页面里同时存在两种字段行 —— 登录区是「标签左 64dp 定宽 +
 * 值居右」的横排，卡片 / SSH / 身份是竖排。同一页两种排版，读起来像两个页面拼的。
 * 统一成竖排两行式（M3 list item 的 headline + supporting text 形态）：
 * 长值（网址 / 私钥 / 备注）有自己的一整行，不会被 64dp 的标签列挤成省略号。
 */
@Composable
private fun DetailFieldRow(
    label: String,
    value: String,
    onCopy: () -> Unit,
    extraAction: (@Composable () -> Unit)? = null,
    monospace: Boolean = false,
    maxLines: Int = if (monospace) MONOSPACE_MAX_LINES else PLAIN_MAX_LINES,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.lg, end = Spacing.sm, top = Spacing.sm, bottom = Spacing.sm),
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = Spacing.xs)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = if (monospace) FontFamily.Monospace else null,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
        SectionTitle(text = stringResource(R.string.section_uris), icon = Icons.Filled.Language)
        DetailCard {
            Column {
                item.uris.forEachIndexed { index, v ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = Spacing.lg),
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
            .padding(start = Spacing.lg, end = Spacing.sm, top = Spacing.xs, bottom = Spacing.xs),
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = Spacing.xs)) {
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

/**
 * 2FA 分区：**默认不显示验证码，点右侧眼睛才显示**。
 *
 * 2026-09-13 用户反馈：「动态验证码是不显示的，提示此页不刷新验证码 —— 我的想法是这里
 * 做一个眼睛一样的按钮，点击就能显示验证码，默认是看不到的。动态验证码的文案用 2FA 即可。」
 *
 * 于是这里同时满足两件事：
 * - **默认零开销**：不打开眼睛就不跑定时器，沿用 2026-09-12「详情页别每秒重算」的降功耗
 *   约束（当时的做法是"干脆不给看"，现在改成"按需给看"）；
 * - **打开即实时**：眼睛打开期间每秒重算（`LaunchedEffect(revealed)` 里的 `while`），
 *   关掉随协程取消立刻停 —— 不会留一个后台每秒唤醒的定时器。
 *
 * 解析失败（`totp` 不是合法 otpauth / base32）时 `config` 为 null，此时眼睛点了也不给码
 * （只保留提示行）—— **绝不显示一个会算错的码**。
 */
@Composable
private fun TotpSection(totp: String?) {
    val config = remember(totp) { totp?.let(OtpUriParser::parse) }
    var revealed by rememberSaveable { mutableStateOf(false) }
    var nowSeconds by remember { mutableLongStateOf(System.currentTimeMillis() / MILLIS_PER_SECOND) }
    LaunchedEffect(revealed) {
        while (revealed) {
            nowSeconds = System.currentTimeMillis() / MILLIS_PER_SECOND
            delay(TOTP_TICK_MS)
        }
    }
    val code = if (revealed) config?.let { TotpGenerator.generate(it, nowSeconds) } else null

    Column {
        SectionTitle(text = stringResource(R.string.section_totp), icon = Icons.Filled.Timer)
        DetailCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Spacing.lg, end = Spacing.xs, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Timer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = Spacing.md),
                ) {
                    Text(
                        text = stringResource(R.string.detail_totp_present),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (code != null) {
                        Text(
                            text = code,
                            style = MaterialTheme.typography.headlineSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.detail_totp_hidden),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                IconButton(onClick = { revealed = !revealed }) {
                    Icon(
                        imageVector = if (revealed) {
                            Icons.Filled.VisibilityOff
                        } else {
                            Icons.Filled.Visibility
                        },
                        contentDescription = stringResource(R.string.detail_totp_toggle),
                    )
                }
            }
        }
    }
}

/** TOTP 验证码按 3 位分组显示（便于肉眼读取），与 Bitwarden 客户端一致。 */
private const val TOTP_CODE_GROUP_SIZE = 3

/** 毫秒 → 秒。TOTP 的时间步长以秒计，而 `System.currentTimeMillis()` 是毫秒。 */
private const val MILLIS_PER_SECOND = 1_000L

/** 详情页「眼睛打开」后的验证码刷新间隔（ms）。关掉眼睛即停。 */
private const val TOTP_TICK_MS = 1_000L

/**
 * 通行密钥分区：**只显示条数**。
 *
 * ⚠️ 2026-09-13 用户反馈：「通行密钥只是显示条目，不要提示在验证码页面的指纹按钮里打开，
 * 这些提示有点多余了，简洁一点。」⇒ 去掉 `detail_passkey_where` 那句指路文案。
 * 条数（`passkey_count`）保留 —— 它是"这条能不能免密登录"的唯一线索，不能只剩分区标题。
 */
@Composable
private fun PasskeysSection(creds: List<VaultFido2Credential>) {
    Column {
        SectionTitle(text = stringResource(R.string.section_passkeys), icon = Icons.Filled.Fingerprint)
        DetailCard {
            HintRow(
                icon = Icons.Filled.Fingerprint,
                title = stringResource(R.string.passkey_count, creds.size),
                body = null,
            )
        }
    }
}

/**
 * 「图标 + 标题 (+ 说明)」的静态提示行。
 *
 * `body` 可为 `null`：用户明确说过「不要那些多余的指路提示，简洁一点」——
 * 这时只画标题，不留一行空说明（留空行会让卡片显得没画完）。
 */
@Composable
private fun HintRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.padding(start = Spacing.md)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            if (body != null) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
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
        SectionTitle(text = stringResource(R.string.section_custom_fields), icon = Icons.Filled.Tune)
        DetailCard {
            Column(modifier = Modifier.padding(vertical = Spacing.sm)) {
                item.customFields.forEachIndexed { index, field ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = Spacing.lg),
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
            .padding(start = Spacing.lg, end = Spacing.sm, top = 6.dp, bottom = 6.dp),
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = Spacing.xs)) {
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
private fun CardSection(item: VaultItem, onCopyField: (String) -> Unit) {
    val card = item.card ?: return
    // 品牌识别：优先采用已存品牌名，否则从卡号推导（对齐 Bastion CardBrandDetector）。
    val detected = CardBrandDetector.detect(card.number, card.brand)
    val brandLabel = if (detected != CardBrand.UNKNOWN) detected.displayName else card.brand
    Column {
        SectionTitle(text = stringResource(R.string.section_card), icon = Icons.Filled.CreditCard)
        DetailCard {
            Column(modifier = Modifier.padding(vertical = Spacing.sm)) {
                if (card.cardholderName.isNotBlank()) {
                    DetailFieldRow(
                        label = stringResource(R.string.card_cardholder),
                        value = card.cardholderName,
                        onCopy = { onCopyField(card.cardholderName) },
                    )
                }
                if (brandLabel.isNotBlank()) {
                    DetailFieldRow(
                        label = stringResource(R.string.card_brand),
                        value = brandLabel,
                        onCopy = { onCopyField(brandLabel) },
                    )
                }
                if (card.number.isNotBlank()) {
                    val number = formatCardNumberGrouped(card.number)
                    DetailFieldRow(
                        label = stringResource(R.string.card_number),
                        value = number,
                        monospace = true,
                        onCopy = { onCopyField(number) },
                    )
                }
                val expiry = cardExpiryText(card.expMonth, card.expYear)
                if (expiry.isNotBlank()) {
                    DetailFieldRow(
                        label = stringResource(R.string.card_expiry),
                        value = expiry,
                        onCopy = { onCopyField(expiry) },
                    )
                }
                if (card.code.isNotBlank()) {
                    DetailFieldRow(
                        label = stringResource(R.string.card_cvv),
                        value = card.code,
                        monospace = true,
                        onCopy = { onCopyField(card.code) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SshKeySection(item: VaultItem, onCopyField: (String) -> Unit) {
    val ssh = item.sshKey ?: return
    Column {
        SectionTitle(text = stringResource(R.string.section_ssh_key), icon = Icons.Filled.Key)
        DetailCard {
            Column(modifier = Modifier.padding(vertical = Spacing.sm)) {
                if (ssh.privateKey.isNotBlank()) {
                    DetailFieldRow(
                        label = stringResource(R.string.ssh_private_key),
                        value = ssh.privateKey,
                        monospace = true,
                        onCopy = { onCopyField(ssh.privateKey) },
                    )
                }
                if (ssh.publicKey.isNotBlank()) {
                    DetailFieldRow(
                        label = stringResource(R.string.ssh_public_key),
                        value = ssh.publicKey,
                        monospace = true,
                        onCopy = { onCopyField(ssh.publicKey) },
                    )
                }
                if (ssh.keyFingerprint.isNotBlank()) {
                    DetailFieldRow(
                        label = stringResource(R.string.ssh_fingerprint),
                        value = ssh.keyFingerprint,
                        monospace = true,
                        onCopy = { onCopyField(ssh.keyFingerprint) },
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
        SectionTitle(text = stringResource(R.string.section_identity), icon = Icons.Filled.Badge)
        DetailCard {
            Column(modifier = Modifier.padding(vertical = Spacing.sm)) {
                rows.forEach { (label, value) ->
                    DetailFieldRow(label = label, value = value, onCopy = { onCopyField(value) })
                }
            }
        }
    }
}
