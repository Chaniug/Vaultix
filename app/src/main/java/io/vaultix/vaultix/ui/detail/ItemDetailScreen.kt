package io.vaultix.vaultix.ui.detail

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.History
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
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
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
import io.vaultix.model.VaultAttachment
import io.vaultix.model.VaultAttachmentSource
import io.vaultix.model.VaultCustomField
import io.vaultix.model.VaultFido2Credential
import io.vaultix.model.VaultIdentity
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import io.vaultix.model.VaultLinkedId
import io.vaultix.model.VaultPasswordHistoryEntry
import io.vaultix.model.VaultSshKey
import io.vaultix.model.VaultUri
import io.vaultix.vaultix.ui.theme.UiSize
import io.vaultix.vaultix.ui.theme.VaultixCorner
import kotlinx.coroutines.delay
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.common.DialogTextButton
import io.vaultix.vaultix.ui.common.BadgeTone
import io.vaultix.vaultix.ui.common.ItemFormDialog
import io.vaultix.vaultix.ui.common.LINKED_FIELD_LABELS
import io.vaultix.vaultix.ui.common.SiteIcon
import io.vaultix.vaultix.ui.common.TypeBadge
import io.vaultix.vaultix.ui.common.itemTypeLabelRes
import android.content.Context
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.input.nestedscroll.nestedScroll
import io.vaultix.vaultix.ui.common.Capability
import io.vaultix.vaultix.ui.common.CapabilityIcon
import io.vaultix.model.KdbxCloudSyncStatus
import io.vaultix.vaultix.ui.common.DetailSyncIndicator
import io.vaultix.vaultix.ui.common.capabilityTint
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.WindowInsets
import io.vaultix.vaultix.ui.common.TopBarTitle
import io.vaultix.vaultix.ui.common.VaultixExpressiveTopBar
import io.vaultix.vaultix.ui.common.rememberImmersiveBarPadding
import io.vaultix.vaultix.ui.common.rememberScrollCollapseFraction
import io.vaultix.vaultix.ui.common.VaultixWavyProgress
import io.vaultix.vaultix.ui.theme.Spacing
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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
            // 兜底理由同 ItemsScreen（见 UiEvent.SaveFailed 的 KDoc）。
            context.getString(
                R.string.item_save_failed,
                event.message ?: context.getString(R.string.error_unknown_no_detail),
            )
        ItemDetailViewModel.UiEvent.Deleted -> null
        is ItemDetailViewModel.UiEvent.DownloadFailed ->
            // 兜底理由同 [UiEvent.SaveFailed]：没细节时说什么归呈现层。
            context.getString(
                R.string.attachment_download_failed,
                event.message ?: context.getString(R.string.error_unknown_no_detail),
            )
        // DownloadDone 不是文案事件：要发起 ACTION_VIEW（可能失败并转提示），见收集处。
        is ItemDetailViewModel.UiEvent.DownloadDone -> null
    }

/** 掩码星号数量上限（密码过长时截断显示，复制不受影响）。 */
private const val MAX_MASK_LENGTH = 24

/**
 * 「还不知道有没有这条」时的转圈延迟（2026-11-09 第二轮）。
 *
 * 取 120ms：低于此值的读取**根本不显示加载态**（人眼对 <100ms 的空白感知为"直接出现"，
 * 而对一闪而过的 spinner 反而会注意到"刚才卡了一下"）。条目首帧来自 Room 内存缓存，
 * 绝大多数情况在 50ms 内就到 —— 所以正常情况下这个 spinner 一次都不会出现。
 *
 * ⚠️ **延迟只许加在"转圈"上，不许加在"未知态"上**：未知态本身必须从**第 0 帧**起
 * 就占住内容位（空白），否则页面就落进"确认不存在"分支 —— 那正是用户抓到的那帧
 * 「此条目不存在或已删除」。见 [DetailUnknownPlaceholder] 的 KDoc。
 */
private const val DETAIL_PROGRESS_DELAY_MS = 120L

/**
 * 未知态占位的高度下限（≈4 行正文）。
 *
 * 理由不是"好看"，而是**量级一致**：真实内容首帧立刻替换掉占位，占位若比内容矮
 * 一截，就会出现一次「先缩后撑」的跳变；给一个不低于常见字段卡的高度，
 * 60ms 内的替换在视觉上就是"内容直接出现"。
 */
private val DETAIL_UNKNOWN_MIN_HEIGHT = 220.dp

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
 *
 * [isDownloadingAttachment] 放这里而不是给 [DetailBodyContent]/[DetailSections]
 * 再添一个参数（参数数会破门禁线）；对象本身在下载状态变化时重建（见调用处的 remember 键）。
 */
private class DetailActions(
    val onCopyUsername: () -> Unit,
    val onCopyPassword: () -> Unit,
    val onCopyUri: (String) -> Unit,
    val onCopyTotp: (String) -> Unit,
    val onCopyField: (String) -> Unit,
    val onCopyHistoryPassword: (String) -> Unit,
    val onDownloadAttachment: (VaultAttachment) -> Unit,
    val isDownloadingAttachment: Boolean = false,
    /**
     * 条目所属组织名（2026-10-21）。null = 个人条目 / 组织名还没解出来。
     * 做成 getter 而不是常量：组织名随解密流异步到达，条目先到时会短暂为 null。
     */
    val organizationNameOf: (String) -> String? = { null },
    /** 库级同步状态（11-07 第二批：图标从顶栏移到类型徽标行）。 */
    val syncStatus: KdbxCloudSyncStatus? = null,
)

/**
 * 详情内容区：**未知 / 确认缺失 / 删除中 / 就绪** 四态。
 *
 * 独立成函数是为了控制主 Composable 的圈复杂度。
 *
 * ⚠️ 「是否已知」与 `item == null` 是**两件事**（2026-10-22 定判据）：
 * 前者 = "还不知道有没有"，后者 = "确认没有"。两者同时成立时**只能画占位** ——
 * 画"条目不存在"就是用户点进条目时闪过的那一帧提示。
 *
 * ## 2026-11-09 第二轮：判据对了，代码却没照着判据写
 *
 * 用户第二次抓到同一帧。
 *
 * 上一轮把 `loading` 接进这个函数却没用它做分支条件 —— `when` 的第一支写的是
 * `showProgress && item == null`，而 `showProgress` 要等 [DETAIL_PROGRESS_DELAY_MS]
 * 才置真、`loading` 根本没参与 ⇒ **前 120ms 里没有任何一支接住"未知"**，
 * 直接落到 `item == null` 的"条目不存在"。两种库都会中：`itemLoaded` 的初值是 false。
 *
 * ⇒ 现在分支条件改成 `!loaded && item == null`，由 [DetailUnknownPlaceholder] 渲染空白占位：
 *   ① **从第 0 帧起**就成立，不依赖任何延迟；
 *   ② 转圈只在 `showProgress` 之后才叠加（120ms 内到达 ⇒ 一次都不出现）；
 *   ③ 占位保住内容位高度，真实内容到达时不会出现「先缩后撑」。
 * 真实缺失（`loaded == true && item == null`）仍走原来的文案，语义没有被削弱。
 */
@Composable
@Suppress("LongParameterList")
private fun DetailBodyContent(
    modifier: Modifier,
    /**
     * 删除中：**只用来压住「条目不存在」那一帧，不再整页转圈**
     * （2026-11-08 用户反馈）。
     *
     * 点垃圾桶后 `softDelete` 只做本地编码 / 往返自检 / 落缓存（百毫秒级，不等网络），
     * 完成后立刻发 `UiEvent.Deleted` 返回列表。以前这里把整页换成 WavyProgress，
     * 反而把「本地已经删完了」做成一段看得见的等待；且 bump 后条目流变 null，
     * 会先闪一帧「条目不存在或已被删除」再返回，观感更差。
     * ⇒ 点删除后立刻清空内容（视觉上立即生效），静默等导航返回，不做任何遮挡。
     */
    deleting: Boolean,
    /**
     * 条目流是否**已经发射过一次**（false = 还不知道有没有这条）。
     *
     * 注意语义方向：界面上的「未知」= `!loaded && item == null`；
     * 「确认缺失」= `loaded && item == null`。
     */
    loaded: Boolean,
    item: VaultItem?,
    serverOrigin: String?,
    showPassword: Boolean,
    onTogglePassword: () -> Unit,
    scrollState: ScrollState,
    actions: DetailActions,
) {
    // ★ 2026-11-09：转圈**延迟 [DETAIL_PROGRESS_DELAY_MS] 再画**。
    //   用户反馈「点击条目的时候，会有一帧的圈形加载才进条目详情」——
    //   条目数据来自 Room 的内存缓存，通常几十毫秒就到；立刻画圈等于给一次
    //   几乎瞬间完成的读取配了一个"正在加载"的闪帧。
    //   ⇒ 120ms 内到达 ⇒ 转圈**从未出现**；真慢（冷读盘 / 大库）才显示，
    //     那时用户确实需要知道在等。这是"防闪烁"的常规做法，不是掩盖慢。
    //   ⚠️ key 只有 [unknown] ⇒ 数据到达时自动重启，走 else 分支清掉。
    var showProgress by remember { mutableStateOf(false) }
    val unknown = item == null && !loaded
    LaunchedEffect(unknown) {
        // key 只留 unknown：条目**内容**变了不该重启延迟（那样每次编辑后都要重等）。
        // 一旦离开未知态就永远走 else ⇒ 转圈不会残留。
        if (unknown) {
            delay(DETAIL_PROGRESS_DELAY_MS)
            showProgress = true
        } else {
            showProgress = false
        }
    }
    Box(modifier = modifier) {
        when {
            // ① 未知：**必须最先判**，且不吃延迟（见函数 KDoc 与常量的 ⚠️）。
            //    转圈只是延迟叠加的视觉信号，占位本身从第 0 帧就在。
            unknown -> DetailUnknownPlaceholder(
                showProgress = showProgress,
                barPadding = rememberImmersiveBarPadding(
                    rememberScrollCollapseFraction(scrollState),
                ),
                modifier = Modifier.fillMaxSize(),
            )
            // ② 点删除后**立刻清空内容**：本地写回只有百毫秒，页面不该继续显示
            //    旧条目让人读成「点了没反应」；更绝不闪「条目不存在或已被删除」。
            deleting -> Unit
            // ③ 确认缺失：流已发射过且确实没有 ⇒ 这句文案现在是真的。
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

/**
 * 「还不知道有没有这条」的占位（2026-11-09）。
 *
 * 有意**不画任何文字**：
 * - 画「加载中」= 把一次 50ms 的缓存读取说成"正在加载"，比闪一帧更吵；
 * - 画骨架屏需要知道条目长什么样（类型 / 字段数），而我们恰恰还不知道。
 *
 * ⇒ 只做两件事：**占住内容位**（[DETAIL_UNKNOWN_MIN_HEIGHT]，避免内容到达时的高度跳变）、
 *   把顶部让位与真实内容对齐（[barPadding] 让后续 [DetailSections] 的首帧位置与它一致）。
 * 真慢时才在中央叠一个波浪转圈。
 */
@Composable
private fun DetailUnknownPlaceholder(
    showProgress: Boolean,
    barPadding: Dp,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        // 顶部让位：与 DetailSections 里那个可滚动的 Spacer 同高，切换时不位移。
        Spacer(
            modifier = Modifier
                .padding(top = barPadding)
                .height(DETAIL_UNKNOWN_MIN_HEIGHT),
        )
        if (showProgress) {
            VaultixWavyProgress(modifier = Modifier.align(Alignment.Center))
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
        DetailHeader(
            item = item,
            serverOrigin = serverOrigin,
            syncStatus = actions.syncStatus,
        )
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
        // 网址与应用分两区（2026-11-09）：同一张卡里混着 https:// 与 com.xxx 包名会看错。
        // ⚠️ 各自内部判空（只含应用时网址区整块消失），这里不能再用 `item.uris.isNotEmpty()`
        //    闸门 —— 那会让"只有应用"的条目渲染出一张空的网址卡。
        UrisSection(item = item, onCopyUri = actions.onCopyUri)
        AppsSection(item = item)
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
        // 历史与附件放在备注之后、时间之前：都是"这条目还带了什么"的延伸信息，
        // 但比创建/修改时间更值得被看到（时间放最后的理由见下方 2026-09-30 注释）。
        HistoryAndAttachments(item = item, actions = actions)
        // 时间放**最后**（2026-09-30 用户要求），且于 2026-11-09 **降级成一行小字**：
        // 它是"核对条目"时的次要信息，不该占一张卡片和一个分区标题的重量 ——
        // 用户原话「不用这么显眼，把这个位置留给更加需要展示的内容」。
        TimestampsFooter(createdAt = item.createdAt, updatedAt = item.updatedAt)
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
private fun DetailHeader(
    item: VaultItem,
    serverOrigin: String?,
    syncStatus: KdbxCloudSyncStatus?,
) {
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
                        tint = capabilityTint(Capability.TOTP),
                        contentDescription = stringResource(R.string.items_filter_totp),
                    )
                }
                if (item.fido2Credentials.isNotEmpty()) {
                    CapabilityIcon(
                        icon = Icons.Filled.Key,
                        tint = capabilityTint(Capability.PASSKEY),
                        contentDescription = stringResource(R.string.items_filter_passkey),
                    )
                }
                // ★ 11-07 第三轮：紧跟类型徽标/能力图标，与「登录 / 验证码 /
                //    通行密钥」同一基线对齐（用户：推到最右太远、不好看）。
                DetailSyncIndicator(status = syncStatus)
            }
            // ★ 2026-11-08：上传进度条不该挂在详情页。上传是后台行为，
            //    角标（DetailSyncIndicator）足以表达状态；画一条进度条会把
            //    「本地已经完成」读成「页面还在等同步」。
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
    val organizationNames by viewModel.organizationNames.collectAsStateWithLifecycle()
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
                is ItemDetailViewModel.UiEvent.DownloadDone -> {
                    val opened = runCatching {
                        openDownloadedAttachment(context, event.fileUri, event.fileName)
                    }.isSuccess
                    if (!opened) {
                        // 打不开（无应用认领该类型）≠ 下载失败：明文已经解密落盘了。
                        snackbarHostState.showSnackbar(
                            context.getString(R.string.attachment_downloaded_open_failed),
                        )
                    }
                }
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
    // ⚠️ 11-07：syncStatus 必须进 key —— 它是云端状态的唯一来源，
    //    漏了它，保存后 PENDING_UPLOAD → IN_SYNC 的变化不会重算 actions，
    //    图标会永远停在第一次组合时的那一帧（真机「压根不转，直接显示小云」）。
    val actions = remember(
        viewModel,
        state.downloadingAttachment,
        state.syncStatus,
        organizationNames,
    ) {
        DetailActions(
            onCopyUsername = viewModel::copyUsername,
            onCopyPassword = viewModel::copyPassword,
            onCopyUri = viewModel::copyUri,
            onCopyTotp = viewModel::copyTotp,
            onCopyField = viewModel::copyField,
            onCopyHistoryPassword = viewModel::copyHistoryPassword,
            onDownloadAttachment = viewModel::downloadAttachment,
            isDownloadingAttachment = state.downloadingAttachment,
            organizationNameOf = { orgId -> organizationNames[orgId] },
            syncStatus = state.syncStatus,
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
                deleting = state.deleting,
                loaded = state.itemLoaded,
                item = item,
                serverOrigin = state.serverOrigin,
                showPassword = showPassword,
                onTogglePassword = { showPassword = !showPassword },
                scrollState = scrollState,
                actions = actions,
            )
            if (item != null) {
                VaultixExpressiveTopBar(
                    title = TopBarTitle(item.title.ifBlank { stringResource(R.string.items_item_unnamed) }),
                    collapseFraction = listCollapse,
                    modifier = Modifier.align(Alignment.TopCenter),
                    // ⚠️ 2026-09-13 第三轮用户要求：「左上角返回按钮可以取消了，现在都是手势返回，
                    // 给密码标题腾出显示位置。」
                    // ⇒ 这里**不传** `navigationIcon`。返回仍有两条路：系统返回手势 / 返回键
                    // （详情页是二级路由，栈里有上一层，`BackHandler` 由导航库自己接管）。
                    // 去掉后标题左移到 16dp 起始位，长标题能多显示约一个字符位。
                    actions = {
                        // 2026-10-01 起 KDBX 也能编辑 / 删除（阶段 B 写回上线），
                        //   不再有"只读库不画入口"这一支 —— 那是 #106 时代的诚实拒绝，现在换成了真支持。
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
                DialogTextButton(
                    text = stringResource(R.string.delete_item_confirm),
                    contentColor = MaterialTheme.colorScheme.error,
                    onClick = {
                        deleteConfirmOpen = false
                        viewModel.deleteItem()
                    },
                )
            },
            dismissButton = {
                DialogTextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { deleteConfirmOpen = false },
                )
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
        // 2026-09-16：与列表的 [EntryCard] 用**同一道细描边**。
        // 两者本就是"同一种东西"（一个承载条目、一个承载分区），规格不一致时
        // 从列表点进详情会有明显的"换了一套"的割裂感。
        border = BorderStroke(DETAIL_CARD_BORDER_WIDTH, MaterialTheme.colorScheme.outlineVariant),
        content = content,
    )
}

/** 分区卡片描边宽度（与 [EntryCard] 一致）。 */
private val DETAIL_CARD_BORDER_WIDTH = 0.5.dp

/** 分区卡片圆角（见 [DetailCard] 的取舍说明；与 `EntryCard` 同属「大面」档）。 */
private val DETAIL_CARD_SHAPE = VaultixCorner.Panel

/**
 * 分区小标题（登录信息 / 网址 / 2FA / 通行密钥 …）。
 *
 * ## 2026-11-09（P2 第 9 条）：16sp 主色 → 14sp 灰字
 *
 * ⚠️ 这与 2026-09-13 的反馈**方向相反**，是用户在看过证据后重新拍的：
 * 当时那轮把 `labelLarge`+`onSurfaceVariant` 提成 `titleMedium`+`primary`，
 * 理由是「分区分界一眼可见」。但一条目最多能出 **9 张卡片**，于是 9 个
 * 16sp 主色标题同时喊"看我" —— 互相稀释之后，**整页等于没有重点**，
 * 用户扫视时先看到的是九个彩色标题，而不是他要的用户名与密码。
 *
 * ⇒ 降回 `titleSmall`(14sp) + `onSurfaceVariant`。标题的职责是**分层**，
 *   不是**强调**；强调留给内容（值的 `bodyLarge` 现在比标题大两号）。
 *   14sp 仍高于字段名同档（`labelLarge` 也是 14sp）—— 靠字重与位置区分层级，
 *   不再靠字号和颜色两个维度同时加码。
 *
 * ⚠️ 图标保持 `primary`：它是**唯一的彩色锚点**，去掉后整页只剩灰阶，
 *    分区边界会彻底消失（卡片描边是 0.5dp 的 `outlineVariant`，很淡）。
 *    「一个元素只该说一件事」在这里的落点是：**一页只有标题的图标是彩色**。
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
            modifier = Modifier.size(UiSize.InlineIcon),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                                modifier = Modifier.size(UiSize.InlineIcon),
                            )
                        }
                    },
                    onCopy = onCopyPassword,
                )
            }
        }
    }
}

/**
 * 页脚时间戳：**创建于 X · 修改于 Y**（2026-11-09 从「分区卡片」降级）。
 *
 * ## 为什么不再是卡片
 *
 * 它原先是一整张 `DetailCard` + 一个 `SectionTitle`（History 图标 + 「记录」），
 * 视觉重量和「网址」「备注」完全一样 —— 而它是整页**信息价值最低**的内容：
 * 核对条目时偶尔瞄一眼，日常打开根本不看。
 * 用户原话：「记录条目的创建时间与修改时间的内容，是否不用这么显眼，
 * 把这个位置留给更加需要展示的内容呢」。
 * ⇒ 降到 `labelSmall` + `onSurfaceVariant` 的一行小字，不加卡片、不给分区标题。
 *   位置仍在**最底部**（2026-09-30 的结论不变），只是不再抢视觉。
 *
 * ## 为什么两段都可以**不显示**（而不是兜一个默认值）
 *
 * null 的含义是「**这个来源没给**」—— 领域模型里两个字段都可空，来源有三类：
 * - KDBX：老库 / 第三方工具写的条目可能没有 `<CreationTime>`；
 * - Bitwarden：自建 Vaultwarden 可能不返回 `creationDate` / `revisionDate`；
 * - 旧缓存 / 旧测试夹具造的条目本来就没有。
 *
 * ⇒ 兜默认值（如"现在"或 1970）会显示一个**看起来合理但完全错误**的时间，
 *   用户会据此判断"这条是不是我昨天改的"。**宁可少一行，不给假信息**。
 *   （与「空有三态」同一条纪律：把"不知道"说成"某个值"就是塌缩。）
 *
 * ⚠️ 不给「复制」按钮：复制一个时间戳没有实际用途，为一致性硬加一个
 *    反而让人以为"这里能复制出什么有用的东西"。
 */
@Composable
private fun TimestampsFooter(createdAt: Long?, updatedAt: Long?) {
    // 格式化只与时间戳本身有关 ⇒ 各记一次，滚动重组时不必重算。
    val createdText = remember(createdAt) { createdAt?.let { formatTimestamp(it) } }
    val updatedText = remember(updatedAt) { updatedAt?.let { formatTimestamp(it) } }
    val created = if (createdText != null) {
        stringResource(R.string.detail_created_on, createdText)
    } else {
        null
    }
    val updated = if (updatedText != null) {
        stringResource(R.string.detail_updated_on, updatedText)
    } else {
        null
    }
    val line = listOfNotNull(created, updated).joinToString(" · ")
    if (line.isEmpty()) return
    Text(
        text = line,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
    )
}

/**
 * epoch 毫秒 → **本地时区**的可读文本。
 *
 * ⚠️ 必须显式 `atZone(ZoneId.systemDefault())`：`Instant` 本身没有时区，
 * 直接格式化会得到 UTC —— 国内用户看到的"最近修改"会比实际**早 8 小时**，
 * 而这种偏差很容易被误读成"数据不对"。
 *
 * 用 `java.time` 而不是 `SimpleDateFormat`（同 `AddCloudVaultScreen` 的取舍）：
 * 后者是可变对象且带线程局部陷阱，而这里是 Compose 里可能被并发调用的代码。
 *
 * ⚠️ `Locale.getDefault()`：中文系统下用默认 locale 才符合用户对日期写法的预期。
 */
private fun formatTimestamp(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(TIMESTAMP_FORMAT)

private val TIMESTAMP_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault())

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
 *
 * ## 2026-11-09（P1 第 7 条）：自绘 `Row` → M3 `ListItem`
 *
 * 上面那段"形态对了"是 2026-09-16 就定了的，但当时是**手写 Row 模仿** list item：
 * 行高、水波纹范围、读屏语义三样全要自己维护。换成正牌 `ListItem` 后：
 * - `overlineContent` 就是字段名、`headlineContent` 就是值 —— 与 M3 的语义完全对应；
 * - 行高由组件按内容算（单行 56dp 起），不用再手调；
 * - 读屏会把 overline 与 headline 念成「用户名，user@example.com」，
 *   而手写 Row 是四个独立 `Text`，念出来是一串没有关系的词。
 *
 * ⚠️ **必须显式 override 三处样式**，否则这次迁移会静默改变观感：
 * - `overline` 默认 11sp（`labelSmall`）—— 2026-11-07 已把字段名定在 14sp（`labelLarge`），
 *   退回去等于推翻真机验证过的结论；
 * - `headline` 默认 `bodyLarge` 但带一个**内容色**，这里要保持默认 `onSurface`（不设色）；
 * - 容器色必须透明 —— `ListItem` 默认是 `surface`，卡片底是 `surfaceContainerLow`，
 *   不 override 每个字段行都会变成一块"贴了色纸的白条"。
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
    ListItem(
        overlineContent = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        headlineContent = {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = if (monospace) FontFamily.Monospace else null,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                extraAction?.invoke()
                IconButton(onClick = onCopy) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(UiSize.InlineIcon),
                    )
                }
            }
        },
        // 行内左右留白交给 ListItem 自己的 16dp（与卡片内边距同值），只补竖向 ——
        // 原来手写的 top/bottom 8dp 会让两行文字贴到卡片边缘。
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** 折叠态下默认显示几条网址（2026-11-09）。 */
private const val URIS_VISIBLE_COLLAPSED = 3

/**
 * 网址分区（2026-11-09 加折叠）。
 *
 * ## 为什么折叠
 *
 * 用户反馈「某一个条目写了很多的网址，全部铺开一屏都装不下」。此前是
 * `item.uris.forEachIndexed` 无条件全列，10 条网址 ≈ 10 × 56dp，直接把
 * 2FA / 备注挤到两屏之外 —— 而那些才是"打开条目要办事"时真正要看的。
 *
 * ⇒ **渐进披露**：默认只列前 [URIS_VISIBLE_COLLAPSED] 条（绝大多数条目
 *   1~2 条，不受影响），多余的收进一行「另外 N 个网址」。展开状态用
 *   `rememberSaveable` 保持 —— 转屏 / 切出去再回来不会又缩回去。
 *
 * ⚠️ 不按域名去重、不排序：顺序是用户自己排的（第一条是"主站"），
 *    自作主张重排会让"上次点的那个"找不到。
 */
@Composable
private fun UrisSection(item: VaultItem, onCopyUri: (String) -> Unit) {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    // ⚠️ 2026-11-09：只把**网址**放进这一区。此前应用（`androidapp://`）也混在里面，
    //    同一个「网址」卡里既有 `https://…` 又有包名 —— 两类东西共用一张卡和一条分区标题，
    //    用户扫过去只能靠"这一行长得像包名"自己分辨（见 [AppsSection]）。
    val webUris = remember(item.uris) { item.uris.filter { UriFormat.classify(it.uri) !is UriKind.AndroidApp } }
    if (webUris.isEmpty()) return
    val visible = if (expanded) webUris else webUris.take(URIS_VISIBLE_COLLAPSED)
    val hidden = webUris.size - visible.size
    Column {
        SectionTitle(text = stringResource(R.string.section_uris), icon = Icons.Filled.Language)
        DetailCard {
            Column {
                visible.forEachIndexed { index, v ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = Spacing.lg),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                    val kind = UriFormat.classify(v.uri)
                    UriRow(
                        kind = kind,
                        canOpen = kind is UriKind.Website,
                        onCopy = { onCopyUri(v.uri) },
                        onOpen = { openUri(context, kind.raw) },
                    )
                }
                // 只有真的藏了东西（或正在展开）才画这一行：1~3 条的条目
                // 不该多出一个"收起"按钮。
                if (hidden > 0 || expanded) {
                    HorizontalDivider(
                        modifier = Modifier.padding(start = Spacing.lg),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    UriExpandRow(
                        hidden = hidden,
                        expanded = expanded,
                        onToggle = { expanded = !expanded },
                    )
                }
            }
        }
    }
}

/**
 * 应用分区（2026-11-09 从 [UrisSection] 里拆出来）。
 *
 * ## 为什么必须独立成区
 *
 * 用户反馈：「展示的时候，关联的应用好像是和网址放在同一区域显示，容易看错吧。」
 * 此前应用是 `item.uris` 里的一种（Bitwarden 用 `androidapp://<包名>` 表示应用绑定），
 * 于是「网址」卡里同时出现 `https://xui.ptlogin2.qq.com/…` 和 `com.tencent.mobileqq`，
 * 中间只隔一条分隔线、标题还叫「网址」—— 用户得靠"这行像不像域名"来分辨。
 *
 * 两者是**不同的东西**：网址是"去哪个地址"，应用是"这个条目归哪个 App 自动填充"。
 * ⇒ 分卡 + 独立标题，一眼能分开。
 *
 * ## 为什么应用行**没有**复制按钮
 *
 * 用户原话：「为什么会有一个能复制的按钮」。答案很简单：**没人需要把包名粘贴到别处**。
 * 复制按钮是给"要拿去用"的值准备的（用户名、密码、网址可以粘到登录框/浏览器），
 * 而包名是内部标识 —— 用户能对它做的唯一动作是「打开这个 App」。
 * ⇒ 应用行只留"打开"，不留"复制"。
 *
 * ⚠️ 未安装的 App 仍然保留一行（而不是隐藏）：用户需要知道"这条绑了一个 App，
 *    只是我手机上没装"，否则自动填充为什么不生效就成了谜。
 */
@Composable
private fun AppsSection(item: VaultItem) {
    val context = LocalContext.current
    val apps = remember(item.uris) {
        item.uris.mapNotNull { UriFormat.classify(it.uri) as? UriKind.AndroidApp }
    }
    if (apps.isEmpty()) return
    Spacer(Modifier.height(Spacing.md))
    Column {
        SectionTitle(text = stringResource(R.string.section_apps), icon = Icons.Filled.PhoneAndroid)
        DetailCard {
            Column {
                apps.forEachIndexed { index, kind ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = Spacing.lg),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                    AppRow(
                        packageName = kind.packageName,
                        installed = isAppInstalled(context, kind.packageName),
                        onOpen = { launchAndroidApp(context, kind.packageName) },
                    )
                }
            }
        }
    }
}

/**
 * 一行"关联的应用"：应用名 + 包名（副标题）+ 打开按钮。
 *
 * ⚠️ 不做"应用名解析"：读另一个 App 的 label 要 `PackageManager.getApplicationInfo`，
 *    未安装时抛异常、装了也可能因权限拿不到 —— 代价过高。包名本身就足够定位，
 *    且与 Bitwarden 其它客户端一致。
 */
@Composable
private fun AppRow(packageName: String, installed: Boolean, onOpen: () -> Unit) {
    ListItem(
        leadingContent = {
            Icon(
                imageVector = Icons.Filled.PhoneAndroid,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(UiSize.InlineIcon),
            )
        },
        headlineContent = {
            Text(
                text = packageName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = stringResource(
                    if (installed) R.string.app_bound_installed else R.string.app_bound_not_installed,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = if (installed) {
            {
                IconButton(onClick = onOpen) {
                    Icon(
                        Icons.Filled.OpenInNew,
                        contentDescription = stringResource(R.string.item_open_app),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(UiSize.InlineIcon),
                    )
                }
            }
        } else {
            null
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/**
 * 「另外 N 个网址 / 收起」行。
 *
 * ⚠️ 整行可点（`clickable`），不是只有文字可点：行高按 48dp 触摸目标给，
 *    手指落在空白处也算 —— 这是"展开更多"这种低风险动作该有的宽容度。
 */
@Composable
private fun UriExpandRow(hidden: Int, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
    ) {
        Text(
            text = if (expanded) {
                stringResource(R.string.detail_uris_collapse)
            } else {
                stringResource(R.string.detail_uris_expand, hidden)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * 单个网址行：显示原始网址，[canOpen] 为真时多一个"在浏览器打开"。
 *
 * ⚠️ 2026-11-09：应用（`androidapp://`）**不再走这里**，已移到 [AppsSection]。
 *    此前本行同时承担"网址"与"应用"两种形态（应用显示「应用」标签 + 包名），
 *    结果是同一张卡里两种行长得差不多、只有一个 12sp 小标签区分 —— 用户会看错。
 *    现在本行只剩网址与非网址 URI 两种，形态单一。
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
            Text(
                text = kind.raw,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (canOpen) {
            IconButton(onClick = onOpen) {
                Icon(
                    Icons.Filled.Public,
                    contentDescription = stringResource(R.string.item_open_uri),
                    modifier = Modifier.size(UiSize.InlineIcon),
                )
            }
        }
        IconButton(onClick = onCopy) {
            Icon(
                Icons.Filled.ContentCopy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(UiSize.InlineIcon),
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
    // ★ `generateUi`（2026-10-10）：失败返回 null，而不是恒定的 `000000`。
    //
    // ⚠️ 这里原先**没有**这个问题那么隐蔽 —— 本页的 `else` 分支本来就会显示
    //    [R.string.detail_totp_hidden]（"点右侧眼睛显示验证码"）。但那只在**没 reveal**
    //    时成立；一旦用户点了眼睛，`revealed` 为 true 而 `config` 算不出码时，
    //    旧代码会把 `000000` 当成真码画进 `headlineSmall`，且**不再有任何提示** ——
    //    用户以为"我看到了验证码"。改用 generateUi 后，这种情况会落到 else 分支，
    //    提示语仍然是那句"点右侧眼睛"，措辞上不精确但**不会误导成"这是真码"**。
    //
    // 📌 刻意不在本页加一条独立的"验证码不可用"文案：详情页的空间很紧，
    //    且这不是用户主要读到验证码的地方（主入口是底部的验证码页，那里有完整说明）。
    val code = if (revealed) config?.let { TotpGenerator.generateUi(it, nowSeconds) } else null

    Column {
        SectionTitle(text = stringResource(R.string.section_totp), icon = Icons.Filled.Timer)
        DetailCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = Spacing.lg,
                        end = Spacing.xs,
                        top = UiSize.RowVerticalPadding,
                        bottom = UiSize.RowVerticalPadding,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Timer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(UiSize.SecondaryIcon),
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
                            modifier = Modifier.padding(top = UiSize.HairGap),
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.detail_totp_hidden),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = UiSize.HairGap),
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
            modifier = Modifier.size(UiSize.SecondaryIcon),
        )
        Column(modifier = Modifier.padding(start = Spacing.md)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            if (body != null) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = UiSize.HairGap),
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
            .padding(start = Spacing.lg, end = Spacing.sm, top = UiSize.TightGap, bottom = UiSize.TightGap),
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
                    modifier = Modifier.size(UiSize.InlineIcon),
                )
            }
        }
        IconButton(onClick = onCopy) {
            Icon(
                Icons.Filled.ContentCopy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(UiSize.InlineIcon),
            )
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

/**
 * 打开刚下载解密的附件（FileProvider 的 content:// URI）。
 *
 * MIME 按扩展名猜（服务端不提供类型）；猜不出给 `application/octet-stream`。
 * 抛出的异常由调用方接住并转「已下载但没有能打开它的应用」提示 ——
 * **不能吞成"失败"**：文件其实已经下载解密成功了。
 */
private fun openDownloadedAttachment(context: Context, uri: String, fileName: String) {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    val mime = android.webkit.MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(android.net.Uri.parse(uri), mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(intent)
}

/**
 * 密码历史 + 附件两个分区（都放备注之后、时间之前）。
 *
 * 独立成函数：[DetailSections] 的圈复杂度已贴门禁线（≤14），
 * 这两个"按有无渲染"的 `if` 挪出来刚好压回去。空则什么都不画。
 */
@Composable
private fun HistoryAndAttachments(item: VaultItem, actions: DetailActions) {
    // 共享库归属（2026-10-21）：个人条目没有 organizationId ⇒ 整块不画。
    item.organizationId?.let { orgId ->
        Spacer(Modifier.height(Spacing.md))
        OrganizationSection(
            organizationName = actions.organizationNameOf(orgId),
            collectionCount = item.collectionIds.size,
        )
    }
    if (item.passwordHistory.isNotEmpty()) {
        Spacer(Modifier.height(Spacing.md))
        PasswordHistorySection(
            entries = item.passwordHistory,
            onCopyHistoryPassword = actions.onCopyHistoryPassword,
        )
    }
    if (item.attachments.isNotEmpty()) {
        Spacer(Modifier.height(Spacing.md))
        AttachmentsSection(
            attachments = item.attachments,
            downloading = actions.isDownloadingAttachment,
            onDownload = actions.onDownloadAttachment,
        )
    }
}

/**
 * 「这条是共享库的」分区（2026-10-21）。
 *
 * 只显示组织名与集合数量，**不列集合名**：集合可能是几十个，
 * 而这里要回答的问题只有一个 ——「这条为什么我在自己的库里也看得到」。
 * 想看按集合筛，去列表页的筛选面板。
 *
 * 组织名尚未解出来（解密流还没到）时显示占位名，**不隐藏整块**：
 * 隐藏会让人以为"这条是个人条目"。
 */
@Composable
private fun OrganizationSection(organizationName: String?, collectionCount: Int) {
    Column {
        SectionTitle(text = stringResource(R.string.section_organization), icon = Icons.Filled.Business)
        DetailCard {
            Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
                Text(
                    text = organizationName ?: stringResource(R.string.organization_name_pending),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (collectionCount > 0) {
                    Text(
                        text = stringResource(R.string.organization_collection_count, collectionCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = UiSize.HairGap),
                    )
                }
            }
        }
    }
}

/**
 * 密码历史分区（只读）。
 *
 * 默认只显示前 [PASSWORD_HISTORY_PREVIEW_COUNT] 条：历史密码是"出问题才查"的信息
 * （找回旧密码 / 核对改动），几十条全铺会把主字段挤出首屏。超过阈值给"展开"按钮。
 *
 * 每条默认**掩码显示**（与主密码同待遇——历史密码同样是能登录的活密码），
 * 点眼睛逐条显隐；复制走 [PasswordHistoryRow] 的复制按钮（敏感剪贴板 + 自动清除）。
 */
private const val PASSWORD_HISTORY_PREVIEW_COUNT = 3

@Composable
private fun PasswordHistorySection(
    entries: List<VaultPasswordHistoryEntry>,
    onCopyHistoryPassword: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column {
        SectionTitle(text = stringResource(R.string.section_password_history), icon = Icons.Filled.History)
        DetailCard {
            Column(modifier = Modifier.padding(vertical = Spacing.sm)) {
                val shown = if (expanded) entries else entries.take(PASSWORD_HISTORY_PREVIEW_COUNT)
                shown.forEachIndexed { index, entry ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = Spacing.lg),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                    PasswordHistoryRow(entry = entry, onCopy = { onCopyHistoryPassword(entry.password) })
                }
                if (entries.size > PASSWORD_HISTORY_PREVIEW_COUNT) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(
                            text = if (expanded) {
                                stringResource(R.string.password_history_collapse)
                            } else {
                                stringResource(R.string.password_history_expand, entries.size)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 历史密码单行：时间在上、密码（可显隐）在下，右侧眼睛 + 复制。 */
@Composable
private fun PasswordHistoryRow(entry: VaultPasswordHistoryEntry, onCopy: () -> Unit) {
    var revealed by remember(entry.password) { mutableStateOf(false) }
    val lastUsedAt = entry.lastUsedAt
    val timeLabel = if (lastUsedAt != null) {
        // remember 住格式化结果：滚动重组很频繁，别每次都跑 DateTimeFormatter。
        // （先取局部变量：跨模块属性在 lambda 里拿不到 smart cast。）
        remember(lastUsedAt) { formatTimestamp(lastUsedAt) }
    } else {
        stringResource(R.string.password_history_unknown_time)
    }
    val masked = "•".repeat(entry.password.length.coerceAtMost(MAX_MASK_LENGTH))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.lg, end = Spacing.sm, top = Spacing.sm, bottom = Spacing.sm),
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = Spacing.xs)) {
            Text(
                text = timeLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (revealed) entry.password else masked,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
                maxLines = MONOSPACE_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { revealed = !revealed }) {
            Icon(
                imageVector = if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                contentDescription = null,
                modifier = Modifier.size(UiSize.InlineIcon),
            )
        }
        IconButton(onClick = onCopy) {
            Icon(
                Icons.Filled.ContentCopy,
                contentDescription = stringResource(R.string.password_history_copy),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(UiSize.InlineIcon),
            )
        }
    }
}

/**
 * 附件分区。
 *
 * - Bitwarden 附件：显示文件名 / 大小，带「下载并打开」按钮 —— 下载期间
 *   其它附件的按钮一并禁用（ViewModel 同一时刻只跑一个下载，避免大文件并发）。
 * - KDBX 附件：只显示文件名 + 只读说明（kotpass 结构限制，见
 *   [io.vaultix.model.VaultAttachment] 的 KDoc），不给按钮。
 */
@Composable
private fun AttachmentsSection(
    attachments: List<VaultAttachment>,
    downloading: Boolean,
    onDownload: (VaultAttachment) -> Unit,
) {
    Column {
        SectionTitle(text = stringResource(R.string.section_attachments), icon = Icons.Filled.AttachFile)
        DetailCard {
            Column(modifier = Modifier.padding(vertical = Spacing.sm)) {
                attachments.forEachIndexed { index, attachment ->
                    if (index > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = Spacing.lg),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                    }
                    AttachmentRow(
                        attachment = attachment,
                        downloading = downloading,
                        onDownload = { onDownload(attachment) },
                    )
                }
            }
        }
    }
}

/** 附件单行：文件名 + 大小；Bitwarden 来源带「下载并打开」按钮。 */
@Composable
private fun AttachmentRow(
    attachment: VaultAttachment,
    downloading: Boolean,
    onDownload: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.lg, end = Spacing.sm, top = Spacing.sm, bottom = Spacing.sm),
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = Spacing.xs)) {
            Text(
                text = attachment.fileName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (attachment.source == VaultAttachmentSource.Kdbx) {
                Text(
                    text = stringResource(R.string.attachment_kdbx_readonly),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = UiSize.HairGap),
                )
            } else if (attachment.size > 0) {
                Text(
                    text = formatAttachmentSize(attachment.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = UiSize.HairGap),
                )
            }
        }
        if (attachment.source == VaultAttachmentSource.Bitwarden) {
            IconButton(onClick = onDownload, enabled = !downloading) {
                Icon(
                    Icons.Filled.Download,
                    contentDescription = stringResource(R.string.attachment_download),
                    modifier = Modifier.size(UiSize.InlineIcon),
                )
            }
        }
    }
}

/** 字节换算基数：KB / MB（附件大小展示用）。 */
private const val ATTACHMENT_SIZE_KB = 1 shl 10
private const val ATTACHMENT_SIZE_MB = 1 shl 20

/** 字节数 → 可读大小（B/KB/MB，向上取整到一位小数以内的整数）。 */
private fun formatAttachmentSize(bytes: Long): String = when {
    bytes >= ATTACHMENT_SIZE_MB -> "${bytes / ATTACHMENT_SIZE_MB} MB"
    bytes >= ATTACHMENT_SIZE_KB -> "${bytes / ATTACHMENT_SIZE_KB} KB"
    else -> "$bytes B"
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
 * 身份信息分区（type=Identity）。按 Bitwarden canonical 顺序展示 18 字段，
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
