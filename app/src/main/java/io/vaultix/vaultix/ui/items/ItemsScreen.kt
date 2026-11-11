package io.vaultix.vaultix.ui.items

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.vaultix.model.VaultFolder
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import io.vaultix.model.VaultKind
import io.vaultix.model.VaultOrganization
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.common.AppLabel
import io.vaultix.vaultix.ui.common.Capability
import io.vaultix.vaultix.ui.common.CapabilityIcon
import io.vaultix.vaultix.ui.common.EmptyState
import io.vaultix.vaultix.ui.common.capabilityTint
import io.vaultix.vaultix.ui.common.CloudSyncIcon
import io.vaultix.vaultix.ui.common.KdbxSourceTarget
import io.vaultix.vaultix.ui.common.VaultSourceCloudIcon
import io.vaultix.vaultix.ui.common.kdbxSourceTargetOf
import io.vaultix.vaultix.ui.common.kdbxSyncLineRes
import io.vaultix.vaultix.ui.common.syncLineToneOf
import io.vaultix.vaultix.ui.common.EntryCard
import io.vaultix.vaultix.ui.common.EntryCardIconSize
import io.vaultix.vaultix.ui.common.VaultixMorphingProgress
import io.vaultix.vaultix.ui.common.selectableItemTypes
import io.vaultix.vaultix.ui.common.EntryCardIconSpacing
import io.vaultix.vaultix.ui.common.EntryCardTextSpacing
import io.vaultix.vaultix.ui.common.EntryCardTitleLineHeight
import io.vaultix.vaultix.ui.common.ItemFormDialog
import io.vaultix.vaultix.ui.common.PressAndSwipeToDelete
import io.vaultix.vaultix.ui.common.SelectionActionBar
import io.vaultix.vaultix.ui.common.SyncStatusDot
import io.vaultix.vaultix.ui.common.SiteIcon
import io.vaultix.vaultix.ui.common.syncStatusDotColor
import io.vaultix.vaultix.ui.common.syncStatusDotDescription
import io.vaultix.vaultix.ui.common.TopBarTitle
import io.vaultix.vaultix.ui.common.VaultixExpressiveTopBar
import io.vaultix.vaultix.ui.common.VaultixSearchTopAppBar
import io.vaultix.vaultix.ui.common.itemTypeLabelRes
import io.vaultix.vaultix.ui.common.BadgeTone
import io.vaultix.vaultix.ui.common.TypeBadge
import io.vaultix.vaultix.ui.common.rememberImmersiveBarPadding
import io.vaultix.vaultix.ui.common.rememberScrollCollapseFraction
import io.vaultix.vaultix.ui.common.MiddleEllipsizedText
import io.vaultix.vaultix.ui.common.toggleSelection
import io.vaultix.vaultix.ui.common.VaultCardInfo
import io.vaultix.vaultix.ui.common.syncBadgeColor
import io.vaultix.vaultix.ui.common.syncBadgeLabel
import io.vaultix.vaultix.ui.common.vaultCardInfoOf
import io.vaultix.vaultix.ui.common.vaultOriginLabel
import io.vaultix.vaultix.ui.common.VaultixWavyProgressBar
import io.vaultix.vaultix.ui.shell.BottomDockOccupiedHeight
import io.vaultix.vaultix.ui.theme.Spacing
import io.vaultix.vaultix.ui.theme.UiSize
import kotlinx.coroutines.launch

/**
 * 条目列表（Docs/08 S7 最小版）+ 新建条目对话框（S10 最小版）。
 *
 * - 大标题随滚动缩放（Docs/16 §4 交互基线）；
 * - 同步状态提示条 + 手动同步 / 立即锁定动作；
 * - 行点击 → 详情页；空态居中引导；新建走 dirty 队列 + 轻量推送。
 */

/** 同步成功/跳过提示的自动消失延迟（ms）。 */
private const val NOTE_DISMISS_DELAY_MS = 2_000L

/** 卡片之间的纵向间距（8dp；水平留白由 LazyColumn 的 contentPadding 统一给）。 */
private val ITEM_CARD_GAP = Spacing.sm

/** 分组折叠/展开的箭头动画时长（对齐 Bastion 的 200ms 补间）。 */
private const val GROUP_ANIM_MS = 200

/**
 * `LazyColumn` 的 `contentType`：**决定滚动时哪些项可以复用彼此的 composition**。
 *
 * ## 为什么必须写（省电的真头，不是微优化）
 *
 * `LazyColumn` 复用 composition 的前提是「新旧两项**结构相同**」。`key` 只保证
 * 「同一张卡在数据刷新后找得回自己的状态」，**不参与复用判定**。
 * 缺 `contentType` 时 LazyColumn 只能保守处理 —— **宁可重建也不复用**：
 * 每滚一下就有若干张卡被完整重组（`PressAndSwipeToDelete` → `EntryCard` →
 * `Row` → `SiteIcon` 整条链走一遍），CPU 与电池就在这里被消耗。
 *
 * ⚠️ **这不是内存泄漏**（`LazyColumn` 照样只组合可见项），是**重复劳动**：
 * 内存占用不变，但滚动时多出成倍的重组。
 *
 * ## 为什么只分两类（而不是每种卡片一个）
 *
 * 复用只在「结构相同」之间发生；分得越细越安全，但也越难命中。这里刻意只区分
 * **列表项**与**标题/状态行**两类 —— 前者（密码/验证码/通行密钥/SSH 各页）内部
 * 结构本来就统一，后者的 `VaultStatusRow` / `GroupHeader` 与之毫无共同点。
 *
 * ⚠️ 本常量与自动填充（`autofill/engine/AutofillItemIcon`）**无关**：
 * 填充面板走的是独立渲染链路，不经过本列表，**不得**借"省电"之名改填充侧行为。
 */
private object ItemsContentType {
    /** 密码/验证码/通行密钥等**条目卡片**（同构，可互相复用）。 */
    const val ITEM = "item"

    /** 「密码库状态」行与分组标题（结构与条目不同，禁止与 [ITEM] 混用）。 */
    const val HEADER = "header"
}

/**
 * `FilterChip` 的视觉高度（M3 规格：选中/未选中态均为 32dp，横向内边距 8dp + 描边 1dp）。
 *
 * ⚠️ 它是**面板高度算式的一半**（见 [QUICK_FILTER_ROW_HEIGHT]）。此前把 32 写死在
 * 注释里、把 48 写死成常量，于是"M3 改了 chip 高度"或"我们改了内边距"时，
 * 让位常数必须靠人记得手动改 —— 现在它是因子，不是人肉同步的对象。
 */
private val FILTER_CHIP_HEIGHT = 32.dp


/**
 * 列表顶部的「密码库状态」行：**来源 + 条目数**（2026-09-30 用户要求）。
 *
 * 例：`vault.bitwarden.com` · `42 个条目`；`OneDrive · Vaultix/我的库.kdbx` · `12 个条目`。
 *
 * ## 长文本怎么处理（用户问「需要分开或者是缩小字号吗」）
 *
 * **不缩字号**（理由见 `ui/common/MiddleEllipsis.kt` 的文件头：它是常驻信息，
 * 缩字号等于每一眼都在付代价，而且小屏缩得最狠）。改用两条各自的规则：
 *
 * 1. **来源（域名 / 路径）走中间省略**（保头保尾）：路径的头（哪台服务器）与尾
 *    （哪个文件）最关键，中间目录最可省。尾部省略会把"打开的是哪个库"先吃掉；
 * 2. **条目数与同步角标另起一行**（2026-10-01 施工单 L1）：它们**不参与压缩**，
 *    也就永远不会被长路径挤掉 —— 这是本块信息的"锚点"：
 *    来源可以被省略号截，但"有多少条 / 云端是不是旧的"必须始终完整可读。
 *
 * ⇒ 最坏情况（WebDAV 长路径 + 窄屏）长这样（**两行**）：
 * ```
 * 🗄 WebDAV · nas.local:50…我的密码库.kdbx
 *    云端有更新 · 12 个条目
 * ```
 *
 * ## 为什么是**冷**色的一行小字，而不是卡片 / 徽标
 *
 * 它是"我在哪"的**环境信息**，不是待办、不是状态告警（告警有 [SyncNoteBanner] 与
 * 同步提示条各司其职）。做成卡片会与真正的条目卡片抢视觉权重 ——
 * 用户扫列表时第一眼应该落在条目上，不是落在"我在哪个库"。
 */
@Composable
private fun VaultStatusRow(card: VaultCardInfo, modifier: Modifier = Modifier) {
    // ★ 2026-10-01（施工单 L1）：**两行**。
    //   一行放不下"长来源 + 角标 + 条目数"三样 —— 用户原话是"右边的密码库名称 +
    //   路径条目放一起，一行很长"。挤在一行时，唯一的解法是**牺牲某一项的宽度**
    //   （来源被折短 / 条目数被挤成省略号），而这两项恰恰都是不该丢的。
    //   ⇒ 竖着分两行：第一行只放来源，第二行放角标与条目数。
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Storage,
                contentDescription = null,
                modifier = Modifier.size(UiSize.MicroIcon),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(Spacing.sm))
            // 来源独占整行宽度、由 [MiddleEllipsizedText] 自己决定折到几个字符。
            MiddleEllipsizedText(
                text = card.origin,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
        if (card.syncBadge != null || card.countText != null) {
            Row(
                // 缩进对齐来源文字（图标 + 图标后的间隙）⇒ 两行是一个整体信息块，
                // 而不是两个各自为政的行。
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = UiSize.MicroIcon + Spacing.sm, top = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 同步角标放在条目数**之前**：它比"有多少条"更紧急（云端可能还是旧的）。
                if (card.syncBadge != null) {
                    Text(
                        text = syncBadgeLabel(card.syncBadge),
                        // ⚠️ 两行之后"来源"不再替这一行吸收挤压 ⇒ 第二行的**可压缩项换成角标**
                        //    （`fill = false`：只占文字实际宽度，不把条目数顶到行尾）。
                        //    角标文案都很短（"云端有更新"），正常屏宽下压不到它头上。
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.labelMedium,
                        color = syncBadgeColor(card.syncBadge),
                        maxLines = 1,
                    )
                }
                if (card.countText != null) {
                    if (card.syncBadge != null) Spacer(Modifier.width(Spacing.sm))
                    // ⚠️ **不压缩**：条目数是这一行的锚点信息。分两行之后它已有整行
                    //    可用，不再需要跟长路径抢宽度 —— 这正是拆行的收益。
                    Text(
                        text = card.countText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * chip 行（[QuickFilterChips]）的上下内边距。**面板高与列表让位共用这一对值**
 * （见 [QUICK_FILTER_ROW_HEIGHT]），改它不会漏掉任何一端。
 *
 * ⚠️ 2026-10-24（用户反馈「全部、验证码、通行密钥这些展开栏目和底下的密码条目隔得很近」）：
 * 下内边距从 `Spacing.sm`(8dp) 提到 [Spacing.md](12dp)。
 *
 * 为什么只动下边、不动上边：**这两条缝挨着的邻居不一样**。
 * 上边挨着顶栏（顶栏展开时有不透明底色、收起时是浮着的按钮），8dp 够了；
 * 下边挨着**第一张条目卡片**，而卡片底色 `surfaceContainerLow` 与页面背景的对比度只有
 * 1.049（见 `EntryCard` 的 KDoc）—— 一条 8dp 的缝在像素上几乎读不出来，
 * 观感就是「chip 行坐在第一张卡片上」。12dp + 卡片自身的 0.5dp 描边，
 * 才让"面板结束 / 列表开始"这条边界成立（`.ai/conventions/8.4` 判据⑨）。
 */
private val QUICK_FILTER_ROW_PADDING_TOP = Spacing.sm
private val QUICK_FILTER_ROW_PADDING_BOTTOM = Spacing.md

/**
 * 展开后的快捷筛选 chip 行高度（上下内边距 + 32dp 高的 FilterChip）。
 *
 * 列表要按这个值**加高顶部留白**，否则 chip 行会直接压在第一条条目上
 * （`.ai/ISSUES.md` #62 记着「筛选条必须浮在内容之上」——浮着是对的，
 * 但**清单必须同步让位**，两件事不矛盾）。
 *
 * ⚠️ 2026-10-24 起它是**算式**，不再是字面量 48dp：内边距一改（见
 * [QUICK_FILTER_ROW_PADDING_BOTTOM]），面板与让位自动同步，不会再出现
 * "改了 chip 行内边距却忘了改让位常数"这种半截修复。
 */
private val QUICK_FILTER_ROW_HEIGHT =
    QUICK_FILTER_ROW_PADDING_TOP + FILTER_CHIP_HEIGHT + QUICK_FILTER_ROW_PADDING_BOTTOM

/**
 * 组织 chip 行高度（2026-10-21）：与上面那行同规格（上边不另加、下方留 [Spacing.sm]）。
 *
 * ⚠️ 只有「库里有组织」时才多这一行 —— 个人账号用户看到的面板高度必须与以前完全一致，
 * 否则他们会莫名多出一截空白（本项目的判据：**新增状态不能改变旧场景的观感**）。
 * ⇒ 正因为要保持这部分"完全一致"，[OrganizationFilterChips] 的下内边距**仍是 8dp**，
 *   上边距由上一行自带的下内边距提供（2026-10-24 前是 `bottom = Spacing.sm` 自守）。
 */
private val QUICK_FILTER_ORG_ROW_HEIGHT = Spacing.sm + FILTER_CHIP_HEIGHT

/**
 * 展开态面板与下方列表之间**额外**让出的呼吸缝（2026-10-24，用户反馈「隔得很近」）。
 *
 * 与面板高度用**同一个** `animateDpAsState` 驱动（见 [rememberQuickFilterRowInset]），
 * 因此它天然与 `expandVertically` 逐帧同步 —— 不会在动画中途露出缝隙或压住首条。
 *
 * 为什么不是把 12dp 直接加进 [QUICK_FILTER_ROW_PADDING_BOTTOM]：那会让「面板有多高」
 * 与「列表要让多少」共用一对内边距值，日后调任何一个都会牵动另一个。
 * 呼吸缝是**面板之外的空白**，是列表让位的职责，所以留在这里。
 */
private val QUICK_FILTER_LIST_GAP = Spacing.sm

/**
 * 搜索态的返回手势：主界面是**根路由**（导航栈里没有上一层），不拦系统返回就会
 * 直接结束 Activity 退回桌面 —— 用户期望的是「退出搜索、回到列表」。
 */
@Composable
private fun SearchBackHandler(enabled: Boolean, onClose: () -> Unit) {
    BackHandler(enabled = enabled) { onClose() }
}

/**
 * 展开后的快捷筛选 chip 行让位高（随展开/收起动画变化）。
 *
 * 抽成独立函数的原因有两个：`animateDpAsState` 的 label 得在这里写；主 composable
 * 已贴着 detekt `LongMethod ≤150` 的门禁线。
 *
 * ⚠️ 两条与 [QuickFilterPanel] 的契约（改一头必须改另一头）：
 * ① `animationSpec` 必须与面板的 `expandVertically` 同为 `tween(GROUP_ANIM_MS)`
 *   —— 不同源会在动画中途错位一帧（`.ai/conventions/8.4` 第 ⑦ 条）；
 * ② 值必须等于「面板真实高度 + [QUICK_FILTER_LIST_GAP]」。面板高度是
 *   `上下内边距 + 32dp chip`（有组织时再加一行），2026-10-24 起这份算式只在这里
 *   和 [QUICK_FILTER_ROW_HEIGHT] 各写一次。
 */
@Composable
private fun rememberQuickFilterRowInset(expanded: Boolean, organizationRow: Boolean = false): Dp =
    animateDpAsState(
        // 2026-10-21：有组织时面板多一行（组织 chip 行），让位必须同步加高
        // —— 否则组织 chip 行会压住第一条条目（与 #62 同一类问题）。
        targetValue = if (!expanded) {
            0.dp
        } else if (organizationRow) {
            QUICK_FILTER_ROW_HEIGHT + QUICK_FILTER_ORG_ROW_HEIGHT + QUICK_FILTER_LIST_GAP
        } else {
            QUICK_FILTER_ROW_HEIGHT + QUICK_FILTER_LIST_GAP
        },
        animationSpec = tween(GROUP_ANIM_MS),
        label = "items_filter_row_inset",
    ).value

/**
 * 列表顶部让位 = 沉浸式顶栏高 + 展开中的筛选行高。**这两个量都只能喂给
 * `LazyColumn.contentPadding`，绝不能做成外层容器的 padding。**
 *
 * ⚠️ 2026-09-13 用户反馈「密码条目往下滑动时上方还是不能透明，不够沉浸」。根因是
 * 状态栏让位被写成了 `Column` 里的**固定 `Spacer(Modifier.height(barPadding))`** ——
 * 列表视口被整体下压，内容永远画不到顶栏那一条带子里 ⇒ 顶栏再半透明也没有东西透出来。
 * 这与 `.ai/ISSUES.md` #67 是**同一个根因**，只是当时只修了验证码页 / 卡包页，密码页漏了。
 * 现在改成与 `TotpCodesScreen` / `CardWalletScreen` 完全一致的写法：让位只走
 * `contentPadding`，列表从 y=0 开始铺。
 *
 * ⚠️ 同步横幅（`SyncNoteBanner`）是列表的**兄弟节点**、不随列表滚动：它出现时会自己
 * 占掉「状态栏 + 横幅高」那一段。此时 `contentPadding` 若再加一份 `barPadding`，
 * 横幅与首条之间就会凭空多出一条 `barPadding` 的空白（旧 bug：下拉刷新区大块空白）。
 * 因此横幅可见时**只留筛选行高**。
 *
 * ⚠️ 搜索态是例外：`Scaffold` 已按 `ScaffoldDefaults.contentWindowInsets` 为搜索顶栏
 * 预留了高度（见调用点的 `contentWindowInsets`），这里必须归零。
 */

private fun itemsTopInset(
    searchActive: Boolean,
    barPadding: Dp,
    filterRowInset: Dp,
    bannerVisible: Boolean,
): Dp = when {
    searchActive -> 0.dp
    bannerVisible -> filterRowInset
    else -> barPadding + filterRowInset
}

/**
 * ⚠️ 2026-11-08：这里原有的「外壳下移 + 列表让位 + 逐项绘制补偿 + 滚动锚点」
 * 四件套**已整条拆除**（#182 补偿链，见 `.ai` 11-08 part3）。
 *
 * 它诞生于「展开筛选时偷偷把列表滚回顶部、但画面一像素不跳」。当晚用户否决了
 * 「偷偷回顶部」并加了「展开筛选时回到列表顶部」开关 —— 开关打开就等于**明确要求
 * 位置改变**，「画面不跳」这个目标本身不再成立，补偿链只剩负债：
 *
 * - 切筛选时两个 `LaunchedEffect` 都写 `listState`，锚点 `scrollToItem(0, 56)` 会被
 *   清成 0，首项净下沉 56dp ⇒ chips 与第一张卡片之间凭空多出一条空白（第 2 次起必现）；
 * - `holding` 判据拿 `filter == All` 冒充「列表在顶部」，开关一开就被永久锁死；
 * - 更根本的是 foundation 明确保证 `scrollToItem` 的 offset **不保证生效**：条目不够
 *   填满视口时会走 `toScrollBack` 把 offset 钳回 0 ⇒ SSH 这类短列表**补不回来**；
 * - 空态吃含 reveal 的 `topInset` 却没有 `compensate()`，额外再沉 56dp。
 *
 * 现在的口径：**让位只走 [itemsTopInset] → `LazyColumn.contentPadding.top`**（72dp），
 * 首项天然落在 chips 下方 8dp；滚动位置只在 [rememberFilterBarCollapse] **一处**写，
 * 且必须过偏好开关门控。位置类状态不再绑成「需要运行时凑齐的等式」。
 */

/**
 * 顶栏收起度 —— 把两条**与"筛选"耦合**的规则收在这里（主函数才能守住 detekt `LongMethod`）。
 *
 * **规则一：切换快筛 = 换了一份数据视图 ⇒ 列表回到顶部（但受偏好开关门控）。**
 * 2026-10-24 用户反馈「往下滑的时候点筛选会直接返回最上面，不友好」⇒ 默认**不回顶部**。
 * 只有用户在「显示选项」里打开「展开筛选时回到列表顶部」、且面板确实展开时才回。
 * ⚠️ **本函数是全页唯一允许写 `listState` 滚动位置的地方**（2026-11-08 收口）：
 * 此前另有 `rememberListEdgeHolder` 也在写，两处互相抢滚动位，是筛选区空白的根因之一。
 * 新增任何「回顶部 / 滚动到某处」的需求都请加在这里，不要再开第二个 `LaunchedEffect`。
 *
 * **规则二：筛选面板展开期间，收起度冻结为「展开」。**
 * 2026-09-15 用户实测：点开面板后连续切筛选（全部→验证码→通行密钥→SSH→**再点回全部**），
 * 标题会从 26sp 缩成 16sp、像"被滑下去了"，而且**那个状态下再点 chip 更卡**。
 * 根因是一圈**互相喂的状态**：收起度 ← 列表滚动位；而 `contentPadding.top` 里含着
 * `filterRowInset`（面板展开多出约 56dp）与 `barPadding`（又由收起度驱动）
 * ⇒ 面板一展开，顶部内边距变大，LazyColumn 把这份位移记进
 * `firstVisibleItemScrollOffset`：**视觉上列表还在顶部，判据 `offset > 8dp` 却已经成立**。
 * 面板展开时顶栏本来就该是展开的（标题刚被点过）⇒ 直接冻结，把回路切断。
 *
 * @param jumpToTop 「展开筛选时回到列表顶部」偏好；false 时**完全不碰**滚动位置。
 */
@Composable
private fun rememberFilterBarCollapse(
    listState: LazyListState,
    panelExpanded: Boolean,
    filter: ItemsQuickFilter,
    jumpToTop: Boolean,
): Float {
    // 唯一滚动位写入点：开关关闭 ⇒ 一个字节都不碰；打开 ⇒ 展开面板 / 换分类时直接回顶。
    // 不传 scrollOffset：拆掉补偿链后不再需要「首项向上滚出 56dp」这个不可靠的锚点
    // （短列表会被 foundation 钳回 0，见上方拆除说明）。
    LaunchedEffect(panelExpanded, filter, jumpToTop) {
        if (jumpToTop && panelExpanded) listState.scrollToItem(0)
    }
    val scrollCollapse = rememberScrollCollapseFraction(listState)
    return if (panelExpanded) 0f else scrollCollapse
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemsScreen(
    onBack: () -> Unit,
    onLocked: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenFolders: () -> Unit,
    onOpenItem: (VaultItem) -> Unit,
    /**
     * 验证码页出口；**null = 宿主不需要该入口**。
     *
     * 主界面（[io.vaultix.vaultix.ui.shell.MainShellScreen]）底部已有「验证码」页签，
     * 传 null 就不会在 ⋮ 菜单里重复出现；autofill 的条目路由（`ItemsRoute`）没有底部
     * 导航，必须传真值，否则那一侧将**没有任何**通往验证码页的路。
     */
    onOpenTotp: (() -> Unit)? = null,
    /**
     * 主界面 Tab 内嵌模式（main-shell-migration 阶段 2）：
     * 隐藏返回键（无上层可返回）与 FAB（「+」由底部导航条统一承载）。
     */
    embedded: Boolean = false,
    /**
     * 「切换密码库」出口（null = 只有一个库，菜单项隐藏）。
     *
     * issue #96：多库并存时此前**没有**任何语义正确的切库入口 —— 用户能碰到的
     * 只有「锁定」（语义相反），于是「添加了 KDBX 却找不到怎么进去」。
     */
    onSwitchVault: (() -> Unit)? = null,
    /**
     * 当前活跃库**未解锁**时，空态里「去解锁」的出口（2026-09-15 加的兜底）。
     *
     * 正常不该走到：设置页点未解锁的库会直接去解锁页。此出口是防「活跃库落在锁定库上」
     * 这一类静默状态（冷启动恢复 / 未来新入口）——兜底也要能自救，而不只是显示一句话。
     */
    onUnlockVault: () -> Unit = {},
    /** 外部「+」请求计数：非零即打开新建表单（宿主在消费后清零，避免重复弹出）。 */
    addRequest: Int = 0,
    onAddConsumed: () -> Unit = {},
    /**
     * 动作面板选中的新建类型（架构方案 A）。默认 Login，其他调用点不用改。
     * 只影响 `CreateItemDialog` 的 blankItem.type；`ItemFormDialog` 的
     * `fallbackFormType` 会按库类型兜底（KDBX 只收 Login / SecureNote）。
     */
    initialNewItemType: VaultItemType = VaultItemType.Login,
    /** 底部叠层悬浮栏占用的高度（宿主给；非内嵌时为 0）——列表要留出它，否则末条被压住。 */
    bottomInset: Dp = 0.dp,
    viewModel: ItemsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val visibleItems by viewModel.visibleItems.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val organizations by viewModel.organizations.collectAsStateWithLifecycle()
    val groupMode by viewModel.groupMode.collectAsStateWithLifecycle()
    val cardDisplayMode by viewModel.cardDisplayMode.collectAsStateWithLifecycle()
    val showIcon by viewModel.showIcon.collectAsStateWithLifecycle()
    val jumpToTop by viewModel.filterJumpToTop.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var displayOptionsOpen by rememberSaveable { mutableStateOf(false) }
    // 顶栏点库名 → 展开快捷筛选条（对齐 Bastion `titleExpanded`）。
    // **默认收起**（用户要求就是「点库名展开」这个动作本身；一直摊开会让列表少一行）。
    // 走 rememberSaveable：切 Tab 回来仍保持展开（用户刚点开就切走再回来，不该又收起来）。
    var quickFiltersExpanded by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val collapse = rememberFilterBarCollapse(
        listState,
        quickFiltersExpanded,
        state.quickFilter,
        jumpToTop,
    )
    val barPadding = rememberImmersiveBarPadding(collapse)
    // ⚠️ 挤在一行是有意的：主 composable 贴着 detekt `LongMethod ≤150` 的门禁线
    // （2026-09-13 这一批加了「让位走 contentPadding」后曾到 152 行，靠压这行回到 147）。
    val filterRowInset = rememberQuickFilterRowInset(quickFiltersExpanded, organizations.isNotEmpty())
    // 让位只走 contentPadding（见 [itemsTopInset]）：不再有外壳下移 / 反向补偿这一层。
    val listTopInset = itemsTopInset(searchActive, barPadding, filterRowInset, state.syncNote != null)
    // 「这个库在哪 · 有多少条」（2026-09-30 用户要求）：在本层算一次，供 ⋮ 菜单卡片与
    // 列表状态行**共用**（各算各的迟早出现"卡片 42 条、列表 41 条"）。
    // ⚠️ 未解锁时 countText 为 null：条目数不可知 ≠ 0。
    val vaultCard = state.vault?.let { v -> vaultCardInfoOf(v, state.items.size).copy(syncDot = state.syncDot) }
    // 搜索关闭动作（点 × 与系统返回共用）：退出搜索态 + 清空输入。
    val closeSearch: () -> Unit = { searchActive = false; viewModel.setQuery("") }
    SearchBackHandler(enabled = searchActive, onClose = closeSearch)
    // 折叠起来的分组 key（默认全部展开；存 saveable，切 Tab 回来不丢）。
    var collapsedGroups by rememberSaveable { mutableStateOf(emptySet<String>()) }
    // 长按多选（对齐 Bastion：长按条目 → 选择框 + 底部批量操作条）。
    // 用「集合非空」当开关，省掉一个必须与它同步的布尔量。
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    // 多选态优先吃掉返回手势：否则一按返回就整页退出，前面勾的全白勾了。
    BackHandler(enabled = selectedIds.isNotEmpty()) { selectedIds = emptySet() }

    // 分组（纯逻辑在 ItemsGrouping.kt；不分组时只有一项、标题为空 → UI 不画分组头）。
    val groups = rememberGroupedItems(visibleItems, folders, groupMode)

    // 底部导航条「+」→ 打开新建表单（Tab 内嵌时不展示自己的 FAB）
    AddRequestEffect(addRequest, onAddConsumed) {
        showCreateDialog = true
    }

    SaveEventSnackbar(viewModel = viewModel, hostState = snackbarHostState)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // 顶栏改为**浮在内容之上**（沉浸式，见 [VaultixExpressiveTopBar]）：
        // Scaffold 不再为顶栏预留高度，状态栏内边距由顶栏自己处理。
        contentWindowInsets =
            if (searchActive) ScaffoldDefaults.contentWindowInsets else WindowInsets(0, 0, 0, 0),
        topBar = {
            // 搜索态保留固定高度顶栏（输入框不能塞进会折叠的大标题里）。
            if (searchActive) {
                ItemsSearchBar(
                    query = state.query,
                    onQueryChange = viewModel::setQuery,
                    onClose = closeSearch,
                )
            }
        },
        floatingActionButton = {
            // 2026-10-01 起 KDBX 也能新建（阶段 B 写回上线），不再有"只读库不画 +"这回事。
            if (!embedded) {
                FloatingActionButton(onClick = { showCreateDialog = true }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.items_new_item))
                }
            }
        },
        // 多选态才出现，平时不占一寸屏幕（对齐 Bastion 底部批量操作条的出现时机）。
        bottomBar = {
            if (selectedIds.isNotEmpty()) {
                ItemsSelectionBar(
                    visibleItems = visibleItems,
                    selectedIds = selectedIds,
                    onSelectionChange = { selectedIds = it },
                    onDelete = { victims -> victims.forEach(viewModel::deleteItem) },
                )
            }
        },
    ) { padding ->
        val syncing by viewModel.isSyncing.collectAsStateWithLifecycle()
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 沉浸式状态栏让位**只走列表的 contentPadding**（见 [itemsTopInset]），
                // 这里**不再**放固定 Spacer —— 放了列表就永远滑不到顶栏之下，顶栏再半透明
                // 也透不出内容（用户反馈的「上方不能透明、不够沉浸」）。
                // 同步横幅是列表的**兄弟节点**（不随列表滚动），必须自己让开状态栏高度，
                // 否则会被顶栏半透明地压住；对应地，它可见时列表只留筛选行高，免得叠出空白。
                SyncNoteBanner(
                    note = state.syncNote,
                    topPadding = if (searchActive) 0.dp else barPadding,
                    onDismiss = viewModel::dismissSyncNote,
                    onRetry = viewModel::retrySync,
                )
                ItemsPullToRefresh(
                    syncing = syncing,
                    onRefresh = viewModel::retrySync,
                ) {
                    // 空态与列表的分流抽到 [ItemsBody]：本函数已贴着 detekt 的
                    // LongMethod ≤150（内联分支会直接顶破），且这里的参数传递本就很长。
                    ItemsBody(
                        visibleItems = visibleItems,
                        state = state,
                        groups = groups,
                        listState = listState,
                        groupMode = groupMode,
                        collapsedGroups = collapsedGroups,
                        displayMode = cardDisplayMode,
                        showIcon = showIcon,
                        selectedIds = selectedIds,
                        listTopInset = listTopInset,
                        bottomInset = bottomInset,
                        onUnlockVault = onUnlockVault,
                        onOpenItem = onOpenItem,
                        onToggleGroup = { key -> collapsedGroups = toggleSelection(collapsedGroups, key) },
                        onToggleSelect = { item -> selectedIds = toggleSelection(selectedIds, item.id) },
                        onDelete = { item ->
                            // 删完必须把 id 摘掉，否则底栏还会统计一条已不存在的条目。
                            selectedIds = selectedIds - item.id
                            viewModel.deleteItem(item)
                        },
                    )
                }
            }
            if (!searchActive) {
                // 顶栏 + 筛选层抽成 [ItemsTopLayer]（2026-11-09，#256）：
                // 这 30 行是纯接线，与主 Composable 的逻辑无耦合，
                // 但把 LongMethod 顶到 151 —— 第三次撞同一道线，说明该拆的是它。
                ItemsTopLayer(
                    state = state,
                    organizations = organizations,
                    embedded = embedded,
                    collapse = collapse,
                    barPadding = barPadding,
                    vaultCard = vaultCard,
                    quickFiltersExpanded = quickFiltersExpanded,
                    onToggleFilters = { quickFiltersExpanded = !quickFiltersExpanded },
                    onDismissFilters = { quickFiltersExpanded = false },
                    onDisplayOptions = { displayOptionsOpen = true },
                    onToggleSearch = { searchActive = true },
                    onBack = onBack,
                    onOpenTotp = onOpenTotp,
                    onOpenTrash = onOpenTrash,
                    onOpenFolders = onOpenFolders,
                    onLocked = onLocked,
                    onSwitchVault = onSwitchVault,
                    viewModel = viewModel,
                )
            }
        }
    }

    ItemDisplayOptionsHost(viewModel, displayOptionsOpen, onDismiss = { displayOptionsOpen = false })

    // 新建条目对话框的完整接线（2026-11-09 把保存 lambda 也收进宿主，见其 KDoc）。
    NewItemDialogHost(
        visible = showCreateDialog,
        folders = folders,
        vaultKind = state.vault?.kind,
        initialType = initialNewItemType,
        saving = state.saving,
        onDismiss = { showCreateDialog = false },
        onSave = { item ->
            viewModel.createItem(item)
            showCreateDialog = false
        },
        onCreateFolder = viewModel::createFolder,
    )
}

/**
 * 新建条目对话框的宿主（2026-10-27 抽出，#199；2026-11-09 扩参 + 收拢 lambda，#256）。
 *
 * 抽出的原因与 [ItemDisplayOptionsHost] 完全相同：主 Composable 贴着 detekt
 * `LongMethod ≤150` 的门禁线，加「文件夹管理」菜单项后到 151。
 * 这十几行是**一组完整的宿主接线**（把表单的开关 / 依赖数据 / 动作喂给对话框），
 * 拆出来有独立含义 —— 比继续压注释可读得多。
 *
 * ⚠️ **2026-11-09 第二次撞同一道线**：给表单加 `onCreateFolder` 后主 Composable 又到 151。
 * 这次的修法不是再抽一个新函数，而是把**原来留在主 Composable 里的那个 lambda 也搬进来**——
 * 保存成功后"关对话框"是**宿主**的职责（对话框自己不该管谁把它关掉），
 * 同一个语义单元的代码分居两处，才是这里反复超线的真正原因。
 *
 * @param onSave 保存条目。调用方负责"关掉对话框"，接线方不必知道这件事。
 */
@Composable
private fun NewItemDialogHost(
    visible: Boolean,
    folders: List<VaultFolder>,
    vaultKind: VaultKind?,
    initialType: VaultItemType,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (VaultItem) -> Unit,
    onCreateFolder: (name: String, onCreated: (String) -> Unit) -> Unit,
) {
    if (!visible) return
    CreateItemDialog(
        folders = folders,
        vaultKind = vaultKind,
        initialType = initialType,
        saving = saving,
        onDismiss = onDismiss,
        onSave = onSave,
        onCreateFolder = onCreateFolder,
    )
}

/**
 * 「显示选项」浮层的宿主（2026-10-21 从主 Composable 抽出）。
 *
 * 抽出的原因：主 Composable 贴着 detekt `LongMethod ≤150` 的门禁线，加了组织筛选后到 153。
 * 这 9 行是**一组完整的宿主接线**（把 ViewModel 的四个状态/动作喂给浮层），
 * 拆出来有独立含义 —— 比继续压注释可读得多。
 */
@Composable
private fun ItemDisplayOptionsHost(
    viewModel: ItemsViewModel,
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    val groupMode by viewModel.groupMode.collectAsStateWithLifecycle()
    val cardDisplayMode by viewModel.cardDisplayMode.collectAsStateWithLifecycle()
    val showIcon by viewModel.showIcon.collectAsStateWithLifecycle()
    val filterJumpToTop by viewModel.filterJumpToTop.collectAsStateWithLifecycle()
    DisplayOptionsHost(
        visible = visible,
        groupMode = groupMode,
        cardDisplayMode = cardDisplayMode,
        showIcon = showIcon,
        filterJumpToTop = filterJumpToTop,
        onDismiss = onDismiss,
        onGroupMode = viewModel::setGroupMode,
        onCardDisplayMode = viewModel::setCardDisplayMode,
        onShowIcon = viewModel::setShowIcon,
        onFilterJumpToTop = viewModel::setFilterJumpToTop,
    )
}

/**
 * 宿主「+」请求 → 打开新建表单；消费后由 [onConsumed] 清零，避免重复弹出。
 *
 * ⚠️ 2026-10-01 起**不再有"只读库要拦在这里"这一支**：KDBX 的条目写回上线（阶段 B），
 * 新建对两种库都成立。历史上这里会在 KDBX 库上弹一句"暂为只读"并把请求消费掉
 * （那是 `.ai/ISSUES.md` #106 的诚实拒绝，现在换成真支持）。
 */
@Composable
private fun AddRequestEffect(
    addRequest: Int,
    onConsumed: () -> Unit,
    onShow: () -> Unit,
) {
    LaunchedEffect(addRequest) {
        if (addRequest > 0) {
            onShow()
            onConsumed()
        }
    }
}

/**
 * 列表主体：空态与条目的分流（从 [ItemsScreen] 抽出，见调用处注释）。
 *
 * 三个空态各有前提，**不能混用**：库锁定 → 「输入主密码后即可查看」；
 * 整库为空 → 引导插画；搜索无果 → 「换个词试试」。
 */
@Composable
private fun ItemsBody(
    visibleItems: List<VaultItem>,
    state: ItemsViewModel.UiState,
    groups: List<ItemsGroup>,
    listState: LazyListState,
    groupMode: ItemsGroupMode,
    collapsedGroups: Set<String>,
    displayMode: ItemsCardDisplayMode,
    showIcon: Boolean,
    selectedIds: Set<String>,
    /** 列表顶部让位，直接喂 `LazyColumn.contentPadding.top`（见 [itemsTopInset]）。 */
    listTopInset: Dp,
    bottomInset: Dp,
    onUnlockVault: () -> Unit,
    onOpenItem: (VaultItem) -> Unit,
    onToggleGroup: (String) -> Unit,
    onToggleSelect: (VaultItem) -> Unit,
    onDelete: (VaultItem) -> Unit,
    /** 「这个库在哪 · 有多少条」（null = 未知则不画）。见 [ItemsScreen] 里的 `vaultCard`。 */
    vaultCard: VaultCardInfo? = null,
) {
    if (visibleItems.isEmpty()) {
        // 空态 / 搜不到：同样要让位，否则文案与插画被半透明顶栏压住。
        // ⚠️ 库锁定时**不能**显示「还没有保存的密码」——那是本页最误导人的一句话
        // （2026-09-15 用户报的空白页 bug）：条目都在，只是密文读不出来。
        ItemsEmptyState(
            query = state.query,
            topInset = listTopInset,
            locked = state.vault?.unlocked == false,
            onUnlock = onUnlockVault,
        )
        return
    }
    // 「这个库在哪 · 有多少条」由**本层**算好后传进来（顶栏的 ⋮ 菜单卡片用的是同一份，
    // 见 [ItemsScreen] 里的 `vaultCard`）—— 两处各算各的迟早差一条。
    ItemsList(
        groups = groups,
        listState = listState,
        grouped = groupMode != ItemsGroupMode.None,
        collapsedGroups = collapsedGroups,
        displayMode = displayMode,
        showIcon = showIcon,
        serverOrigin = state.vault?.origin,
        vaultOrigin = state.vault?.origin,
        topInset = listTopInset,
        bottomInset = bottomInset,
        onToggleGroup = onToggleGroup,
        selectedIds = selectedIds,
        onToggleSelect = onToggleSelect,
        onOpenItem = onOpenItem,
        syncStates = state.syncStates,
        // ★ 2026-11-10 第三轮：这里原本只传 `kind`，把「库在不在云上」这件事丢掉了 ——
        //   判据在 [cloudSyncBadgeOrNull] 里，它需要 origin 才能分出
        //   「OneDrive 上的 KDBX」（云端库徽标）与「本地文件」（不画）。
        vaultKind = state.vault?.kind,
        onDelete = onDelete,
        vaultCard = vaultCard,
    )
}

@Composable
private fun ItemsEmptyState(
    query: String,
    topInset: Dp,
    /** 当前库**未解锁**：空列表是「读不到」而非「没有」，文案必须区分开。 */
    locked: Boolean = false,
    onUnlock: () -> Unit = {},
) {
    Box(modifier = Modifier.fillMaxSize().padding(top = topInset)) {
        when {
            locked -> LockedItemsState(onUnlock = onUnlock)
            query.isBlank() -> EmptyItemsState()
            else -> NoSearchResultState()
        }
    }
}

/**
 * 库未解锁时的空态（2026-09-15 用户反馈的空白页 bug 的**兜底**）。
 *
 * 正常路径不会走到这里 —— 设置页点未解锁的库会直接去解锁页。但只要有任何一条路径
 * 让活跃库落在锁定库上（冷启动恢复、外部切库、未来新入口），本页就会是空的；
 * 那时**必须说清楚是"锁着"**，而不是说"你没有条目"。所以这个兜底是必要的，
 * 它把一类"静默错误"降级成"可理解的状态"。
 */
@Composable
private fun LockedItemsState(onUnlock: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.items_locked_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = stringResource(R.string.items_locked_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.sm),
        )
        TextButton(
            onClick = onUnlock,
            modifier = Modifier.padding(top = Spacing.md),
        ) {
            Text(stringResource(R.string.items_locked_action))
        }
    }
}

/**
 * 下拉刷新的伸手区（含指示器让位）。
 *
 * ⚠️ 指示器默认贴在**容器顶部**（= 整页最上面），而列表首条要到 [topInset]
 * （状态栏 + 72dp 顶栏）之下才开始横向铺开。于是下拉时「指示器上方 + 指示器到首条之间」
 * 会露出两段纯背景 —— 正是用户反馈的「下拉处有一块很大的空隙」。
 * 把指示器整体下移到顶栏之下：空隙消失，它正好出现在「内容开始的地方」，
 * 与首条视觉上连成一体。
 */
@Composable
private fun ItemsPullToRefresh(
    syncing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val pullState = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = syncing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
        state = pullState,
        // ⚠️ 2026-09-13 用户嫌默认指示器"一块白、压着站点图标"，当时直接置空 ⇒
        //    下拉手势还在，却**全程零反馈**（2026-11-08 真机复现：用户不知道同步跑没跑）。
        //    ⇒ 恢复指示器，但换掉那块白：用 `primaryContainer` 作底、`onPrimaryContainer`
        //    作进度色，并固定在容器顶部居中（不再压在首条卡片左侧）。
        //    只在 `syncing` 时绘制，静止时一个节点都不渲染，不占列表空间。
        indicator = {
            if (syncing) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = Spacing.sm),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    VaultixMorphingProgress(indicatorSize = 32.dp)
                }
            }
        },
        content = content,
    )
}

/** 分组（纯逻辑在 [groupItems]；不分组时只有一项、标题为空 → UI 不画分组头）。 */
@Composable
private fun rememberGroupedItems(
    visibleItems: List<VaultItem>,
    folders: List<VaultFolder>,
    groupMode: ItemsGroupMode,
): List<ItemsGroup> {
    val context = LocalContext.current
    return remember(visibleItems, folders, groupMode) {
        groupItems(
            items = visibleItems,
            folders = folders,
            mode = groupMode,
            typeLabel = { context.getString(itemTypeLabelRes(it.type)) },
            unnamedLabel = context.getString(R.string.items_item_unnamed),
            noFolderLabel = context.getString(R.string.items_group_no_folder),
            ungroupedLabel = context.getString(R.string.items_group_ungrouped),
            // 应用键翻中文走 AppLabel（customFields 别名优先，装了再 PackageManager），
            // 翻不出来兜底显示包名本身 —— 与详情页「关联应用」的显示口径一致。
            siteLabel = { key, isApp ->
                if (isApp) {
                    AppLabel.installedLabel(context, key) ?: key
                } else {
                    key
                }
            },
        )
    }
}

/** 保存 / 删除事件的 Snackbar 提示（抽出来压主函数的行数）。 */
@Composable
private fun SaveEventSnackbar(
    viewModel: ItemsViewModel,
    hostState: SnackbarHostState,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.saveEvents.collect { event ->
            val message = when (event) {
                ItemsViewModel.SaveEvent.SavedSynced ->
                    context.getString(R.string.item_saved_synced)
                ItemsViewModel.SaveEvent.SavedQueued ->
                    context.getString(R.string.item_saved_queued)
                is ItemsViewModel.SaveEvent.Failed ->
                    // ⚠️ 异常没带 message 时在这里兜一句话，**不在 ViewModel 里**：
                    //   "没细节时说什么"是呈现决定。见 SaveEvent.Failed 的 KDoc。
                    context.getString(
                        R.string.item_save_failed,
                        event.message ?: context.getString(R.string.error_unknown_no_detail),
                    )
                is ItemsViewModel.SaveEvent.Deleted ->
                    context.getString(R.string.items_deleted_to_trash, event.title)
            }
            hostState.showSnackbar(message)
        }
    }
}

/** 显示选项弹层的宿主（`visible=false` 时什么都不渲染）。 */
@Composable
private fun DisplayOptionsHost(
    visible: Boolean,
    groupMode: ItemsGroupMode,
    cardDisplayMode: ItemsCardDisplayMode,
    showIcon: Boolean,
    /** 展开筛选时是否把列表拉回顶端（默认关；见 [rememberFilterBarCollapse] 的门控）。 */
    filterJumpToTop: Boolean = false,
    onDismiss: () -> Unit,
    onGroupMode: (ItemsGroupMode) -> Unit,
    onCardDisplayMode: (ItemsCardDisplayMode) -> Unit,
    onShowIcon: (Boolean) -> Unit,
    onFilterJumpToTop: (Boolean) -> Unit = {},
) {
    if (!visible) return
    DisplayOptionsSheet(
        groupMode = groupMode,
        cardDisplayMode = cardDisplayMode,
        showIcon = showIcon,
        filterJumpToTop = filterJumpToTop,
        onDismiss = onDismiss,
        onGroupMode = onGroupMode,
        onCardDisplayMode = onCardDisplayMode,
        onShowIcon = onShowIcon,
        onFilterJumpToTop = onFilterJumpToTop,
    )
}

/** 搜索态顶栏（固定高度，见 [VaultixSearchTopAppBar]）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemsSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    VaultixSearchTopAppBar(
        searchTerm = query,
        placeholder = stringResource(R.string.items_search_hint),
        onSearchTermChange = onQueryChange,
        onClose = onClose,
        clearIconContentDescription = stringResource(R.string.items_search_clear),
        scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(),
    )
}

/**
 * 顶栏层：筛选面板 + 沉浸式顶栏，以及它们需要的那一整套回调接线（2026-11-09，#256）。
 *
 * ## 为什么要单独一层
 *
 * 这 30 行本身不含任何逻辑 —— 全是「从 `state` 取几个值、把几个回调往下传」。
 * 但它把 `ItemsScreen` 顶到了 detekt `LongMethod` 的 151 行。
 * **这是同一道线的第三次**（前两次见 `ItemDisplayOptionsHost` / `NewItemDialogHost`），
 * 说明该拆的从来不是"随便一段"，而是这块**纯接线区**。
 *
 * 传 `viewModel` 而不是把 5 个回调逐个拆开：这一层的职责就是"把 ViewModel 接到顶栏上"，
 * 拆成 5 个 lambda 只是把 30 行变成 40 行，没有换来任何解耦。
 * 需要替换数据源时，替换的是这一层，不是这里。
 */
@Composable
private fun BoxScope.ItemsTopLayer(
    state: ItemsViewModel.UiState,
    organizations: List<VaultOrganization>,
    embedded: Boolean,
    collapse: Float,
    barPadding: Dp,
    vaultCard: VaultCardInfo?,
    quickFiltersExpanded: Boolean,
    onToggleFilters: () -> Unit,
    onDismissFilters: () -> Unit,
    onDisplayOptions: () -> Unit,
    onToggleSearch: () -> Unit,
    onBack: () -> Unit,
    onOpenTotp: (() -> Unit)?,
    onOpenTrash: () -> Unit,
    onOpenFolders: () -> Unit,
    onLocked: () -> Unit,
    onSwitchVault: (() -> Unit)?,
    viewModel: ItemsViewModel,
) {
    QuickFilterPanel(
        visible = quickFiltersExpanded,
        selected = state.quickFilter,
        topPadding = barPadding,
        onSelect = viewModel::setQuickFilter,
        onDismiss = onDismissFilters,
        organizations = organizations,
        selectedOrganizationId = state.organizationFilter,
        onSelectOrganization = viewModel::setOrganizationFilter,
    )
    ItemsTopBar(
        title = itemsTitle(
            vaultName = state.vault?.name.orEmpty(),
            filter = state.quickFilter,
            filterLabel = stringResource(quickFilterLabelRes(state.quickFilter)),
        ),
        collapseFraction = collapse,
        embedded = embedded,
        titleExpanded = quickFiltersExpanded,
        onTitleClick = onToggleFilters,
        onBack = onBack,
        onDisplayOptions = onDisplayOptions,
        onToggleSearch = onToggleSearch,
        onOpenTotp = onOpenTotp,
        onOpenTrash = onOpenTrash,
        onOpenFolders = onOpenFolders,
        onRetrySync = viewModel::retrySync,
        onLock = { viewModel.lockNow(onLocked) },
        onSwitchVault = onSwitchVault,
        vaultCard = vaultCard,
    )
}

/**
 * 沉浸式顶栏 + **胶囊动作组**（🔍 搜索 + ⋮ 更多）。
 *
 * 用户要求（`.ai/ISSUES.md` #60 后续批次第 2 批）：「顶栏胶囊化（🔍 搜索 + ⋮ 更多＝
 * 同步 / 锁定 / 回收站）+ 点左上角库名展开/收起分类筛选」。
 *
 * 为什么把 6 个图标收成 2 个：一排 6 个 IconButton 在小屏上把标题挤成省略号，
 * 且「验证码 / 回收站 / 同步 / 锁定」都不是高频动作（低频动作进 overflow 是 M3 的
 * 既定做法，Bastion 的 `PasswordListTopSection` 同样只留搜索 + ⋮）。
 *
 * 抽成独立 composable 是为了把主函数的行数与圈复杂度压回门禁线内
 * （detekt `LongMethod` ≤150 / `CyclomaticComplexMethod` ≤14）。
 */
@Composable
private fun BoxScope.ItemsTopBar(
    title: TopBarTitle,
    collapseFraction: Float,
    embedded: Boolean,
    titleExpanded: Boolean,
    onTitleClick: () -> Unit,
    onBack: () -> Unit,
    onDisplayOptions: () -> Unit,
    onToggleSearch: () -> Unit,
    /** null = 宿主没有验证码页可去（主界面底部已有该页签）⇒ 菜单里不出现这一项。 */
    onOpenTotp: (() -> Unit)?,
    onOpenTrash: () -> Unit,
    onOpenFolders: () -> Unit,
    onRetrySync: () -> Unit,
    onLock: () -> Unit,
    onSwitchVault: (() -> Unit)?,
    /** 当前库（给 ⋮ 菜单顶部的卡片用）；null = 库未知，卡片不画。 */
    vaultCard: VaultCardInfo?,
) {
    VaultixExpressiveTopBar(
        title = title,
        collapseFraction = collapseFraction,
        modifier = Modifier.align(Alignment.TopCenter),
        navigationIcon = if (embedded) {
            null
        } else {
            {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                    )
                }
            }
        },
        onTitleClick = onTitleClick,
        titleExpanded = titleExpanded,
        titleClickHint = stringResource(R.string.items_quick_filter_hint),
        actions = {
            IconButton(onClick = onToggleSearch) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = stringResource(R.string.items_search),
                )
            }
            // 「显示选项」= **看的方式**（分组 / 密度 / 图标），不是对数据的动作。
            // 2026-09-14 用户要求把它从 ⋮ 里挪到胶囊上：⋮ 菜单留给「对库做的动作」
            // （回收站 / 同步 / 锁定），而显示方式是随时会调的视图状态，多一次展开
            // 实在没必要。代价是胶囊从 2 个图标变 3 个 —— 用户明确要的取舍。
            IconButton(onClick = onDisplayOptions) {
                Icon(
                    Icons.Filled.ViewAgenda,
                    contentDescription = stringResource(R.string.items_display_options),
                )
            }
            ItemsMoreMenu(
                vaultCard = vaultCard,
                onSwitchVault = onSwitchVault,
                onOpenTotp = onOpenTotp,
                onOpenTrash = onOpenTrash,
                onOpenFolders = onOpenFolders,
                onRetrySync = onRetrySync,
                onLock = onLock,
            )
        },
    )
}

/**
 * 顶栏「更多」菜单（⋮）：**当前库卡片** + 按性质分组的动作（2026-09-30 用户选定「方案 B」）。
 *
 * ## 为什么改（原实现是四件事平铺成一列）
 *
 * 平铺的毛病不在"不够好看"，而在**把四种性质不同的东西混在一起**：
 *
 * | 动作 | 性质 |
 * |---|---|
 * | 切换密码库 | **换上下文**（我在看哪个库） |
 * | 验证码 / 回收站 | **去另一个页面**（不改数据） |
 * | 同步 / 锁定 | **对当前库做动作** ⇒ 固定在底部一行，只给图标不给文字（见 [MenuSyncLockRow]） |
 *
 * ⇒ 顶上一张卡片先回答"我在哪、有多少条"，下面按**前往 / 操作**分组。
 *   分组标题给的是"这几项是同一类事"这条信息 —— 那是平铺列表里**读不出来的东西**。
 *
 * ⚠️ 菜单项顺序 = 使用频率（2026-09-14 用户指定的重排）：回收站 → 同步 → 锁定。
 *
 * ⚠️ 「同步」与「锁定」的关系在 2026-11-08~09 改了**四轮**，当前定稿是第 4 轮：
 * 1. 合成一栏（同步整行 + 尾部锁图标）→ 用户反馈"挤、怕误触"，
 *    两个热区零间隙相接，也违反 M3「一个 menu item = 一个动作」；
 * 2. 各占一行 → 锁定仍是最底部整行可点，拇指从下往上扫容易擦到；
 * 3. 同步改为「只在需要动手时出现」+ 锁定缩成右下角图标 →
 *    正常态菜单里只剩一把锁，用户反馈"孤零零的，也没有颜色灯"；
 * 4. **定稿**：底部固定一行 `[灯] [同步] ←weight→ [锁定]`，
 *    全部不写字 —— 灯给颜色、同步可点、锁定靠右（见 [MenuSyncLockRow]）。
 *    状态词交给列表顶部的常驻横幅，菜单里不再复述。
 *
 * ⇒ 这一处反复了四轮，教训是：**"删掉元素"能解决拥挤，但会制造空旷**；
 *   真正该做的是"元素保留、文字去掉"。下次改这里前先读 [MenuSyncLockRow] 的 KDoc。
 *
 * ⚠️ 「切换密码库」放在**最上面**（2026-09-14，issue #96）：多库并存时这是
 * 「我要换个库看」的**唯一正确语义入口**。此前用户能碰到的只有「锁定」
 * （语义恰好相反），导致 KDBX 库「添加了却找不到」，只能靠摸到设置页。
 *
 * ⚠️ 「验证码」只在**调用方给了入口且底部导航没有该页签**时显示（`onOpenTotp == null`
 * 即整项隐藏）：主界面底部已经有「验证码」页签，菜单里再来一个是重复入口；
 * 而 autofill 的条目路由（`ItemsRoute`）**没有**底部导航，那里必须留着它。
 * 「显示选项」已于同日移出本菜单（见 [ItemsTopBar] 的胶囊）。
 */
@Composable
private fun ItemsMoreMenu(
    /** 当前库（null = 库未知，此时不画卡片，菜单退化为纯动作列表）。 */
    vaultCard: VaultCardInfo?,
    onSwitchVault: (() -> Unit)?,
    onOpenTotp: (() -> Unit)?,
    onOpenTrash: () -> Unit,
    onOpenFolders: () -> Unit,
    onRetrySync: () -> Unit,
    onLock: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        // ★ 2026-11-08 三次调整：⋮ 上挂一枚状态角标（M3 `Badge`）。
        //   起因：后台上传与失败原先只由**菜单里**的状态点表达，菜单不展开就等于没有 ——
        //   用户原话「上传和失败总也要一点提示才对」。
        //   ⇒ 用 badge 而不是横幅：不占布局、不需要用户确认，收起菜单时也看得到。
        //   颜色沿用 [syncStatusDotColor]：琥珀=上传中、红=失败、蓝=冲突/有新版本；
        //   `SUCCESS` 不挂（常态不喧哗，那是 quiet default 的一半）。
        IconButton(onClick = { expanded = true }) {
            BadgedBox(
                badge = {
                    val dot = vaultCard?.syncDot
                    if (dot != null && dot != SyncStatusDot.SUCCESS) {
                        Badge(containerColor = syncStatusDotColor(dot))
                    }
                },
            ) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.items_more_actions),
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (vaultCard != null) {
                MenuVaultCard(card = vaultCard)
                HorizontalDivider()
            }
            MenuGroupTitle(R.string.items_menu_group_navigate)
            // 只有一个库时不必显示（没有可切换的对象），故回调为 null 即整项隐藏。
            if (onSwitchVault != null) {
                MenuAction(Icons.Filled.SwapHoriz, R.string.items_switch_vault) {
                    expanded = false
                    onSwitchVault()
                }
            }
            if (onOpenTotp != null) {
                MenuAction(Icons.Filled.QrCode2, R.string.totp_screen_title) {
                    expanded = false
                    onOpenTotp()
                }
            }
            MenuAction(Icons.Filled.Delete, R.string.trash_title) {
                expanded = false
                onOpenTrash()
            }
            // #199：文件夹是**数据**（能建/改名/删），不是"看的方式"，所以放「前往」组
            // 而不是"显示选项"卡片 —— 那里调的是分组/密度，都是视图状态。
            MenuAction(Icons.Filled.Folder, R.string.folders_menu_entry) {
                expanded = false
                onOpenFolders()
            }
            // ★ 2026-11-09 四次调整（用户真机反馈「没有颜色灯显示同步状态，
            //   感觉锁按钮那一块孤零零的」）。此前为了"同步只在异常时出现"，
            //   把常态那颗绿点一起藏了 —— 菜单底部只剩一把锁，是设计上的漏。
            //   ⇒ 底部固定一行三元素：**灯 / 同步 / 锁定**，全部不写字。
            //     · 灯只给颜色（异常原因由顶部横幅说，这里不复述）；
            //     · 同步常驻可点，两个动作之间用 weight 撑开，不是零间隙相接；
            //     · 锁定仍在最右（低风险在左、高风险在右）。
            HorizontalDivider()
            MenuSyncLockRow(
                card = vaultCard,
                onSync = {
                    expanded = false
                    onRetrySync()
                },
                onLock = {
                    expanded = false
                    onLock()
                },
            )
        }
    }
}

/**
 * 菜单底部固定的一行：**状态灯 / 同步 / 锁定**（2026-11-09 四次调整）。
 *
 * ## 为什么是这三个元素挤一行
 *
 * 用户真机反馈两件事，方向相反、必须同时满足：
 * 1. 「没有颜色灯显示同步状态，感觉锁按钮那一块孤零零的」—— 常态下也要看得见状态；
 * 2. 「文案不要这么多」—— 但不能靠"把状态词写一行"来实现可见性。
 *
 * ⇒ 灯只给**颜色**（6dp 圆点，状态词交给顶部横幅），同步与锁定只给**图标**。
 *   一行三个不写字的元素，宽度绰绰有余，也不再复述状态。
 *
 * ## 布局：低风险在左、高风险在右
 *
 * `[●] [⟳] ←———— weight ————→ [🔒]`
 * - 同步在左：它可重复、无副作用，误触成本接近零；
 * - 锁定在最右：锁了要重新输主密码，物理上离拇指扫动路径最远的位置留给它。
 * - 两者之间是 `weight` 撑出来的**真实空白**（不是相邻热区），
 *   所以不存在"两个动作零间隙"那个老问题。
 *
 * ⚠️ 锁定**不染 error 红**：锁库可恢复，不是不可逆动作。
 */
@Composable
private fun MenuSyncLockRow(
    card: VaultCardInfo?,
    onSync: () -> Unit,
    onLock: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val dot = card?.syncDot
        if (dot != null) {
            Icon(
                imageVector = Icons.Filled.Circle,
                // 灯不带文字，但读屏仍要能拿到状态 —— 用完整一句，不是状态词的复述。
                contentDescription = syncStatusDotDescription(dot),
                tint = syncStatusDotColor(dot),
                modifier = Modifier
                    .padding(horizontal = Spacing.md)
                    .size(UiSize.MicroIcon),
            )
        }
        IconButton(onClick = onSync) {
            Icon(
                imageVector = Icons.Filled.Sync,
                contentDescription = stringResource(R.string.items_sync),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onLock) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = stringResource(R.string.items_lock),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 菜单顶部的**当前库卡片**：库名 · 条目数（同一行）/ 来源（下一行）。
 *
 * ## ⚠️ 宽度必须**固定**（2026-10-04 用户真机对照后发现）
 *
 * 此前是 `widthIn(min = MENU_CARD_MIN_WIDTH)` —— **只有下限**，实际宽度由内容撑开。
 * 于是同一份代码在两种库下量出两种宽度（真机 density 3.47）：
 * - Bitwarden 库：`pwd.vv1234.cn · 219 个条目` ⇒ 贴到下限，窄
 * - KDBX 库：`OneDrive · Keepass/valkjin.kdbx · 217 个条目` ⇒ 被内容撑宽
 * ⇒ 同一位置的浮层在切库后**宽度会跳变**，用户视角就是"换个库界面就变形了"
 * （用户原话：「展开后与 bitwarden 那种密码库路径对比，就很不协调」）。
 *
 * ⇒ 改成 `width(MENU_CARD_WIDTH)`：切库时宽度**恒定**，浮层不跳。
 * 代价是长路径改由**中间省略**兜底（`MiddleEllipsizedText` 已有），
 * 这与本项目的取舍一致：**外框稳定 > 内容全展示**。
 *
 * ## 与列表状态行的分层（刻意不同，不是漂移）
 *
 * - 列表行：常驻、窄、必须一眼扫过 ⇒ 折到自己塞得下的长度（保头保尾）；
 * - 菜单卡片：用户主动展开、空间宽裕 ⇒ 尽量给全，实在放不下才中间省略。
 * 同一份数据、两种密度 —— 而不是让两处都去迁就最窄的那个。
 */
@Composable
private fun MenuVaultCard(card: VaultCardInfo) {
    Column(
        modifier = Modifier
            .width(MENU_CARD_WIDTH)
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
    ) {
        // ★ 2026-11-08：条目数紧跟库名，副标题只留来源。此前拼成
        //   「来源 · 条目数」压在路径末尾，KDBX 长路径会把它一起省略掉。
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = card.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            card.countText?.let { count ->
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = count,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Text(
            text = card.origin,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // ⚠️ 固定宽度后**必须单行中间省略**：两行会把分隔线顶开、菜单高度随内容跳。
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // ★ 第三行：同步状态（2026-11-10 真机反馈）。
        //
        // 此前这里**没有**这一行，而真机上却出现了「已同步」—— 那是错位来源：
        // 它原本挂在库列表卡片的 `KdbxSyncStatusLine` 上，用户在**条目页**看到的那句
        // 其实是别处的渲染。现在两处用**同一个纯函数**（`vaultSyncLineResOf`）：
        // 本地 KDBX 拿到 null ⇒ 两处都不写，网盘 KDBX 两处都写同一句。
        card.syncLine?.let { lineRes ->
            Text(
                text = stringResource(lineRes),
                style = MaterialTheme.typography.labelSmall,
                color = syncLineToneOf(lineRes),
                maxLines = 1,
            )
        }
    }
}

/** 分组小标题：只回答"下面这几项是同一类事"，不加图标（避免与动作项抢辨识度）。 */
@Composable
private fun MenuGroupTitle(@StringRes labelRes: Int) {
    Text(
        text = stringResource(labelRes),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Spacing.lg, top = Spacing.sm, bottom = Spacing.xs),
    )
}

/**
 * 菜单卡片的**固定**宽度（2026-10-04 用户真机对照后定稿）。
 *
 * ⚠️ 此前是 `MENU_CARD_MIN_WIDTH = 208.dp` 且用 `widthIn(min =)` ——
 * **只有下限**，实际宽度由内容撑开 ⇒ 切库时浮层宽度会跳：
 * Bitwarden 库（短）贴到下限，KDBX 库（长）被撑宽 ⇒ 「很不协调」。
 *
 * 取 280.dp 的理由：能容下 KDBX 常见的 `OneDrive · Keepass/valkjin.kdbx`
 * （bodySmall 下 ≈ 220dp）并给行尾状态灯留位，窄屏（≥320dp）仍留得住边距。
 * 条目数已移到库名后（2026-11-08），长路径走**中间省略**（保头保尾，
 * 尾段的文件名是用户最想确认的），高度与切库无关。
 */
private val MENU_CARD_WIDTH = 280.dp

/**
 * 菜单项（图标 + 文案），**一个 item 一个动作**。
 *
 * ⚠️ 2026-11-08 清掉了 `destructive` / `trailing` 两个参数：已无调用点使用非默认值，
 *    留着只会让人以为还能标红。锁定现在是普通菜单项 —— 锁库可恢复（重新输主密码），
 *    不属破坏性动作，`error` 色留给删库/清空这类真正不可逆的入口。
 */
@Composable
private fun MenuAction(
    icon: ImageVector,
    @StringRes labelRes: Int,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Text(
                text = stringResource(labelRes),
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        onClick = onClick,
    )
}


/**
 * 快捷筛选面板：顶栏下方的一排 chip（验证码 / 通行密钥 / SSH / 笔记 / 收藏 …）。
 *
 * **浮在内容之上**（与顶栏同层，`topPadding = rememberImmersiveBarPadding(...)`）：
 * 若把它塞进可滚动的 `Column`，列表一滚它就跟着滚走，而顶栏的箭头还指着「已展开」——
 * 用户会以为筛选条坏了（`.ai/ISSUES.md` #57 的同一类问题）。
 *
 * [visible] 为 true 时同时铺一层全屏透明遮罩：点面板以外任何地方即收起，免去找关闭按钮。
 */
@Composable
private fun BoxScope.QuickFilterPanel(
    visible: Boolean,
    selected: ItemsQuickFilter,
    topPadding: Dp,
    onSelect: (ItemsQuickFilter) -> Unit,
    onDismiss: () -> Unit,
    /**
     * 组织列表（2026-10-21）：**空则不画组织行**，面板高度与以前完全一致
     * （个人账号用户不该因为这次改动看到多出来的空白）。
     */
    organizations: List<VaultOrganization> = emptyList(),
    selectedOrganizationId: String? = null,
    onSelectOrganization: (String?) -> Unit = {},
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(GROUP_ANIM_MS)) +
            expandVertically(
                expandFrom = Alignment.Top,
                // ⚠️ 2026-10-04 用户反馈「点左上角折叠筛选按钮时遮住下方的密码条目」。
                //   根因是**两个动画不同源**：
                //     · 面板高度 = `AnimatedVisibility` 的 `expandVertically`，
                //       默认用 spring 规格（时长与 200ms 无关）；
                //     · 列表让位 = `rememberQuickFilterRowInset` 的
                //       `animateDpAsState(tween(GROUP_ANIM_MS))`（200ms）。
                //   两者曲线不同时长 ⇒ **动画进行中面板比让位高**，
                //   那多出来的一截就压在第一条条目上（动画结束才落回正确位置）。
                //   ⇒ 显式给同一个 `tween(GROUP_ANIM_MS)`，面板与让位**逐帧同步**，
                //   任何一帧都不会出现"面板高于让位"。
                //   ⚠️ 改这里必须同步改 [rememberQuickFilterRowInset]，反之亦然 ——
                //   这两处是同一份契约的两端，改一头不改另一头就会重现本bug。
                animationSpec = tween(GROUP_ANIM_MS),
            ),
        exit = fadeOut(animationSpec = tween(GROUP_ANIM_MS)) +
            shrinkVertically(
                shrinkTowards = Alignment.Top,
                animationSpec = tween(GROUP_ANIM_MS),
            ),
        modifier = Modifier.align(Alignment.TopCenter),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 透明遮罩：点面板以外任何地方即收起，免去找关闭按钮。
            // 用 `matchParentSize` 而不是 `fillMaxSize`：它不参与父 Box 的尺寸测量，
            // 因此不会把「只剩 chip 行高度」的父布局撑成满屏。
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    ),
            )
            Column {
                QuickFilterChips(
                    selected = selected,
                    topPadding = topPadding,
                    onSelect = onSelect,
                )
                if (organizations.isNotEmpty()) {
                    OrganizationFilterChips(
                        organizations = organizations,
                        selectedId = selectedOrganizationId,
                        onSelect = onSelectOrganization,
                    )
                }
            }
        }
    }
}

/**
 * 横向可滚的 chip 行（chip 数会随维度增加，小屏必须能横滑）。
 *
 * ## 方案 B（2026-10-04 定稿）：chip 行**融进页面**，不自带浮层底色
 *
 * ⚠️ 此前这里裹了一层 `Surface(color = surface, tonalElevation = 2.dp)`，
 * 而 `colorScheme.surface` **与页面背景同值**（真机像素取证：两者都是
 * `rgb(13,14,17)`，density 3.47）。于是 chip 行那 48dp 让位区在像素层面
 * **与间隙属于同一块颜色** ⇒ 人眼读不出「chip 行底边在哪」⇒
 * 观感就是 chip 行**坐在第一张卡片上**、卡片顶部圆角被吃掉一半。
 *
 * 几何让位本身一直是**对的**（实测间隙 72.5dp 确实存在），
 * 错的是**边界不可见**。⇒ 这类问题调 `contentPadding` 数值、
 * 改 `animationSpec` 都修不好（`.ai/conventions/8.4` 判据⑨）。
 *
 * ⇒ 方案 B 的做法是**删掉那层壳**而不是给它换个颜色：
 * 融进页面后，chip 靠**自身的底色 + 边框 + 选中态图标**表达，
 * 不再依赖一块「比背景略高」的板子撑出浮层感。
 *
 * @param topPadding 状态栏 + 顶栏高（与列表 `contentPadding` 用的是同一个
 *   `rememberImmersiveBarPadding`，**必须共用** —— 否则 chip 行会与列表让位错位）。
 */
@Composable
private fun QuickFilterChips(
    selected: ItemsQuickFilter,
    topPadding: Dp,
    onSelect: (ItemsQuickFilter) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPadding)
            .horizontalScroll(rememberScrollState())
            // ⚠️ 上下内边距**必须**取自 [QUICK_FILTER_ROW_PADDING_TOP] /
            //    [QUICK_FILTER_ROW_PADDING_BOTTOM]，与 [QUICK_FILTER_ROW_HEIGHT] 同源 ——
            //    写死字面量就会重现"面板变高了、让位没变"的半截修复（2026-10-04 第 ⑦ 条）。
            .padding(
                start = Spacing.md,
                top = QUICK_FILTER_ROW_PADDING_TOP,
                end = Spacing.md,
                bottom = QUICK_FILTER_ROW_PADDING_BOTTOM,
            ),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ItemsQuickFilter.entries.forEach { filter ->
            FilterChip(
                selected = filter == selected,
                onClick = { onSelect(filter) },
                label = { Text(stringResource(quickFilterLabelRes(filter))) },
                leadingIcon = if (filter == selected) {
                    { Icon(Icons.Filled.Check, contentDescription = null) }
                } else {
                    null
                },
            )
        }
    }
}

/**
 * 组织 chip 行（2026-10-21）：第一枚是「不限组织」，其后每个组织一枚。
 *
 * 与上面那排的关系：**单选且互斥** —— 选中任一组织会把快捷筛选重置为「全部」
 * （见 `ItemsViewModel.setOrganizationFilter`），反过来选快捷筛选也会清掉组织。
 * 两套同时生效会造出"组织 A 里的验证码"这种回不去的组合，而顶栏标题只能显示一种。
 *
 * ⚠️ 高度必须与 [QUICK_FILTER_ORG_ROW_HEIGHT] 一致（列表让位按它计算）。
 * 该行**不自带上内边距**：上边距由 [QuickFilterChips] 的下内边距提供，
 * 两行之间的缝因此只有一处来源（2026-10-24 起上面那行是 12dp，组织行跟着它）。
 */
@Composable
private fun OrganizationFilterChips(
    organizations: List<VaultOrganization>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = Spacing.md, end = Spacing.md, bottom = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = selectedId == null,
            onClick = { onSelect(null) },
            label = { Text(stringResource(R.string.items_filter_organization_clear)) },
            leadingIcon = if (selectedId == null) {
                { Icon(Icons.Filled.Check, contentDescription = null) }
            } else {
                null
            },
        )
        organizations.forEach { org ->
            val isSelected = org.id == selectedId
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(if (isSelected) null else org.id) },
                label = { Text(org.name) },
                leadingIcon = if (isSelected) {
                    { Icon(Icons.Filled.Check, contentDescription = null) }
                } else {
                    { Icon(Icons.Filled.Business, contentDescription = null) }
                },
            )
        }
    }
}

/** 筛选维度 → 文案（[ItemsQuickFilter.All] 为「全部」）。 */
@StringRes
private fun quickFilterLabelRes(filter: ItemsQuickFilter): Int = when (filter) {
    ItemsQuickFilter.All -> R.string.items_filter_all
    ItemsQuickFilter.Totp -> R.string.items_filter_totp
    ItemsQuickFilter.Passkey -> R.string.items_filter_passkey
    ItemsQuickFilter.Ssh -> R.string.items_filter_ssh
    ItemsQuickFilter.Note -> R.string.items_filter_note
    ItemsQuickFilter.Favorite -> R.string.items_filter_favorite
}

/**
 * 顶栏标题：无筛选时只有库名；有筛选时**库名 26sp + 筛选名 14sp 独立第二行**。
 *
 * ## 为什么筛选名必须出现在标题里
 *
 * 筛选生效后列表条数会明显变少，标题不写清「现在在看什么」，
 * 用户第一反应是「我的条目丢了」（`.ai/ISSUES.md` 里同类误报的常见来源）。
 *
 * ## ⚠️ 2026-10-04：为什么从「拼一个串」改成「两级标题」（方案 β）
 *
 * 此前是 `itemsTitle` 拼成 `"库名 · 筛选名"` 交给顶栏折行，真机观感很差：
 * - `Bitwarden · 验证` / `码` —— **孤字行**（第二行只剩一个字），像标题坏了；
 * - `valkjin.kdbx · 验` / `证码` —— 同样折，而且被折行逼到 **20sp**（`TITLE_TWO_LINE_SP`），
 *   库名这个最重要的信息反而被降级。
 *
 * ⇒ 改为 [TopBarTitle]：库名保持 26sp **不折行**，筛选名以 14sp 小字另起一行。
 * 主次分明，且两个字号都不用缩。
 *
 * ⚠️ 顺带修正一个此前的判断：折叠并非 KDBX 特有 —— `Bitwarden · 验证`（11 个字）
 * 在同一块 26sp 区域里同样放不下，**两个库都在折**。所以这是**顶栏通用问题**，
 * 修在共享组件里而不是 ItemsScreen 局部。
 */
private fun itemsTitle(vaultName: String, filter: ItemsQuickFilter, filterLabel: String): TopBarTitle =
    TopBarTitle(
        text = vaultName,
        subtitle = if (filter == ItemsQuickFilter.All) null else filterLabel,
    )

/**
 * 条目列表（分组 + 「按住后滑动删除」）。
 *
 * 抽成独立 composable 的原因：主函数加了顶栏/分组/沉浸式留白后行数越界
 * （detekt `LongMethod` ≤150 / `CyclomaticComplexMethod` ≤14，本项目门禁在 CI 之前跑）。
 */
@Composable
private fun ItemsList(
    groups: List<ItemsGroup>,
    listState: LazyListState,
    grouped: Boolean,
    collapsedGroups: Set<String>,
    displayMode: ItemsCardDisplayMode,
    showIcon: Boolean,
    serverOrigin: String?,
    /** 当前库的 `origin`（**判「库在不在云上」只认它**，见 [cloudSyncBadgeOrNull]）。 */
    vaultOrigin: String?,
    topInset: Dp,
    bottomInset: Dp,
    onToggleGroup: (String) -> Unit,
    /** 多选：当前勾选的条目 id 集合（非空即处于多选态）。 */
    selectedIds: Set<String>,
    /** 多选：切换某条的勾选（长按卡片也走它，见 [PressAndSwipeToDelete]）。 */
    onToggleSelect: (VaultItem) -> Unit,
    onOpenItem: (VaultItem) -> Unit,
    onDelete: (VaultItem) -> Unit,
    /** 条目 id → 是否已同步上云（行尾云图标，缺省视为已同步）。 */
    syncStates: Map<String, Boolean> = emptyMap(),
    /**
     * 当前库类型（null = 还没加载出来）。
     *
     * ⚠️ 只为了一件事：行尾那朵云画成什么（见 [cloudSyncBadgeOrNull]）。
     * 判据在那个纯函数里，本函数只负责把它传下去；而「在不在云上」要的是
     * 下面的 [vaultOrigin]，不是本参数 —— 两轮真机反馈都把这两件事混成过一件。
     */
    vaultKind: VaultKind? = null,
    /**
     * 「这个库在哪 · 有多少条」（null = 库未知，整行不画）。
     *
     * 由 [ItemsBody] 经 `vaultCardInfoOf` 组装（那里才有 [io.vaultix.model.VaultSummary]
     * 与条目总数），本函数只负责画 —— "域名/路径怎么来"的口径集中在 `ui/common/VaultOriginLabel.kt`。
     */
    vaultCard: VaultCardInfo? = null,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        // 顶部留白 = 状态栏 + 顶栏高 + 展开中的筛选行，全部走 contentPadding
        // （**不是**外层容器 padding）。这样收起顶栏后内容能滑到半透明顶栏之下 ——
        // 沉浸感的来源；同时筛选行展开时首条条目会自动被推下去，不再被 chip 压住。
        // 底部同理：悬浮胶囊底栏是叠层浮在内容之上的，末条要留出它的高度。
        // 水平 16dp 也由列表统一留白（卡片自身不再带外边距）——
        // 对齐 Bastion `PasswordListScrollableContent` 的 contentPadding 结构。
        contentPadding = PaddingValues(
            start = Spacing.lg,
            top = topInset,
            end = Spacing.lg,
            bottom = Spacing.sm + bottomInset,
        ),
        verticalArrangement = Arrangement.spacedBy(ITEM_CARD_GAP),
    ) {
        // 「这个库在哪 · 有多少条」放**最上面**（2026-09-30 用户要求）：它是列表的
        // "表头"（我在哪个库、里面有多少东西），先有上下文再看内容。
        // 只读提示排在它下面 —— 那是"为什么按钮少了"的解释，属于对**内容**的补充。
        if (vaultCard != null) {
            item(key = "vault-status", contentType = ItemsContentType.HEADER) {
                VaultStatusRow(card = vaultCard)
            }
        }
        groups.forEach { group ->
            if (grouped) {
                item(key = "header:${group.key}", contentType = ItemsContentType.HEADER) {
                    GroupHeader(
                        title = group.title,
                        count = group.items.size,
                        expanded = group.key !in collapsedGroups,
                        onToggle = { onToggleGroup(group.key) },
                    )
                }
            }
            if (group.key !in collapsedGroups) {
                items(group.items, key = { it.id }, contentType = { ItemsContentType.ITEM }) { item ->
                    // 「长按选中 → 左滑 → 松手过半 → 二次确认」删除
                    // （软删除进回收站，见 ItemsViewModel.deleteItem）。
                    // 长按**选中**由内部 [ItemRow]/[EntryCard] 的 `onLongClick` 独占；本容器
                    // 只负责滑动删除信号（见 [PressAndSwipeToDelete]），不再回调选中，
                    // 避免一次长按触发两次 toggle（进不了多选）。
                    // ⚠️ 2026-09-16：**必须已选中才允许左滑**（用户反馈「直接滑动就删除」
                    // 误触太多）—— 未选中时组件完全不挂手势，横向滑动彻底不存在。
                    PressAndSwipeToDelete(
                        onDelete = { onDelete(item) },
                        selectable = item.id in selectedIds,
                    ) {
                        ItemRow(
                            item = item,
                            serverOrigin = serverOrigin,
                            displayMode = displayMode,
                            showIcon = showIcon,
                            isSelectionMode = selectedIds.isNotEmpty(),
                            isSelected = item.id in selectedIds,
                            // 缺省 true：新建刚可见、还没入队的那一瞬间不该闪一下「未同步」。
                            // KDBX 的「在不在云上」由 origin 判，见 [cloudSyncBadgeOrNull]。
                            cloud = cloudSyncBadgeOrNull(
                                vaultKind = vaultKind,
                                // ★ 必须用**调用方传进来的 `vaultOrigin`**，不能图省事复用
                                //   `serverOrigin`（favicon 用的那个）。
                                //   两者此刻恰好同源（都来自 `state.vault?.origin`），但语义不同：
                                //   favicon 那条一旦为别的库换算法（例如按条目定位站点），
                                //   徽章就会跟着漂 —— 而"库在哪"只有一个真源。
                                vaultOrigin = vaultOrigin,
                                syncedInVault = syncStates[item.id],
                            ),
                            onToggleSelect = { onToggleSelect(item) },
                            onClick = { onOpenItem(item) },
                        )
                    }
                }
            }
        }
    }
}

/** 新建条目对话框（独立函数：把主 composable 的行数压回门禁线内）。 */
@Composable
private fun CreateItemDialog(
    folders: List<VaultFolder>,
    vaultKind: VaultKind?,
    initialType: VaultItemType,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (VaultItem) -> Unit,
    onCreateFolder: (name: String, onCreated: (String) -> Unit) -> Unit,
) {
    // 把动作面板选中的类型交给表单：`ItemFormDialog` 从 initial.type 读初始筹码，
    // 且 fallbackFormType 会把它兜到该库支持的第一种，KDBX 不会存出「字段全空」的残骸。
    val blankItem = remember(initialType) {
        VaultItem(id = "", title = "", type = initialType)
    }
    ItemFormDialog(
        title = stringResource(R.string.items_new_item),
        initial = blankItem,
        folders = folders,
        saving = saving,
        // 新建可选类型（登录 / 银行卡 / 身份 / 安全笔记 / SSH）；
        // 编辑态不开，避免改类型让原类型载荷失去意义。
        typeEditable = true,
        // A3：KDBX 库只放行登录 / 安全笔记（mapper 只产这两种，写回对其余三种
        // 静默丢字段 ⇒ 放行等于让用户存一个"字段全空"的残骸）。
        selectableTypes = selectableItemTypes(vaultKind),
        onCreateFolder = onCreateFolder,
        onDismiss = onDismiss,
        onSave = onSave,
    )
}

/** 顶栏动作已收敛为「胶囊里的 🔍 + ⋮」（见 [ItemsMoreMenu]）。 */

/**
 * 结果型提示条是否**自动消失**（成功 / 跳过 / 非警告的 KDBX 结果）。
 *
 * ⚠️ 抽成扩展属性而不是写在 [SyncNoteBanner] 里：本函数的分支数已贴近 detekt
 *   `CyclomaticComplexMethod ≤14`（2026-11-08 首轮 CI 实测 15 被挡），判据外移后
 *   横幅主体只留「要不要画 / 画哪种」两件事。
 */
private val ItemsViewModel.SyncNote.isTransientResult: Boolean
    get() = when (this) {
        is ItemsViewModel.SyncNote.Success,
        is ItemsViewModel.SyncNote.Skipped -> true
        is ItemsViewModel.SyncNote.Notice -> !warning
        else -> false
    }

/** 需要 errorContainer 底色的警告态（含 KDBX 结果的 warning 分支）。 */
private val ItemsViewModel.SyncNote.isWarningLike: Boolean
    get() = when (this) {
        is ItemsViewModel.SyncNote.Warning -> true
        is ItemsViewModel.SyncNote.Notice -> warning
        else -> false
    }

/**
 * 同步状态提示条：进行中 = 细进度条；成功/跳过 = 短暂提示后自动消失；警告 = 常驻到下次同步。
 *
 * 手动同步的一次性结果走 [ItemsViewModel.SyncNote.Notice]；Bitwarden 编排器走其余态。
 *
 * ★ 2026-11-08：库级**后台上传故意不进横幅** —— 那是 30~70s 的网络请求，
 *   挂一条常驻横幅会让用户把「本地已保存」误读成「删除还在卡着」。
 *   上传状态由 ⋮ 状态点与详情页角标表达，失败时角标给重试入口。
 */
@Composable
private fun SyncNoteBanner(
    note: ItemsViewModel.SyncNote?,
    topPadding: Dp,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    LaunchedEffect(note) {
        if (note?.isTransientResult == true) {
            kotlinx.coroutines.delay(NOTE_DISMISS_DELAY_MS)
            onDismiss()
        }
    }
    // ⚠️ note 为空时必须**直接 return**（一个节点都不渲染）：调用方不再用 `if` 包着它，
    // 若这里返回一个只剩 padding 的空容器，就会凭空留出一条空气。
    if (note == null) return

    Surface(
        color = if (note.isWarningLike) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        // padding 放在 Surface **外面**：让横幅整体落在顶栏之下，而不是色块被顶栏压住。
        modifier = Modifier.fillMaxWidth().padding(top = topPadding),
    ) {
        when (note) {
            ItemsViewModel.SyncNote.InProgress -> {
                // 此前这里只有一条波浪进度条、零文字：真机 18:08 那次「提示有更新
                // → 条目出现」中间空了 36 秒，界面全程没说在等什么。
                Column {
                    VaultixWavyProgressBar(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = stringResource(R.string.items_sync_in_progress),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            horizontal = Spacing.lg,
                            vertical = UiSize.TightGap,
                        ),
                    )
                }
            }
            is ItemsViewModel.SyncNote.Notice -> SyncNoticeRow(note, onRetry)
            is ItemsViewModel.SyncNote.Warning -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = UiSize.TightGap),
                ) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(Spacing.lg),
                    )
                    Text(
                        text = note.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = Spacing.sm),
                    )
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
            }
            is ItemsViewModel.SyncNote.Success -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = UiSize.TightGap),
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(Spacing.lg),
                    )
                    Text(
                        text = stringResource(R.string.items_sync_done, note.cipherCount),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = Spacing.sm),
                    )
                }
            }
            ItemsViewModel.SyncNote.Skipped -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = UiSize.TightGap),
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(Spacing.lg),
                    )
                    Text(
                        text = stringResource(R.string.items_sync_skipped),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = Spacing.sm),
                    )
                }
            }
        }
    }
}

/** KDBX 手动同步的一次性结果（成功自动消失 / 警告常驻并给「重试」）。 */
@Composable
private fun SyncNoticeRow(note: ItemsViewModel.SyncNote.Notice, onRetry: () -> Unit) {
    // ⚠️ fallback 无条件取一次：Elvis 右侧调 @Composable 会让组合树分支不稳定。
    val fallback = stringResource(note.resId)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = UiSize.TightGap),
    ) {
        Icon(
            imageVector = if (note.warning) Icons.Filled.Warning else Icons.Filled.Check,
            contentDescription = null,
            tint = if (note.warning) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.primary
            },
            modifier = Modifier.size(Spacing.lg),
        )
        Text(
            text = note.detail ?: fallback,
            style = MaterialTheme.typography.bodySmall,
            color = if (note.warning) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = Spacing.sm),
        )
        if (note.warning) {
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}

@Composable
private fun EmptyItemsState() {
    // ★ 2026-10-19：改用统一的 [EmptyState]（审计 §2.6）。
    //   本处原本就是"标题 + 正文"的正确形态，收进组件后只变横向边距（页面给 → 24dp）。
    EmptyState(
        title = stringResource(R.string.items_empty_title),
        body = stringResource(R.string.items_empty_body),
    )
}

/** 搜索无命中时的空态（区别于「库里还没有条目」）。 */
@Composable
private fun NoSearchResultState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.items_search_empty),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 分组标题（可点折叠/展开）。
 *
 * 观感对齐 Bastion `PasswordMenuSection`：小标题 + 数量 + 箭头，箭头随展开态旋转 0°↔180°
 * （200ms 补间）。`stickyHeader` 不用：本列表是 Collapsible section 语义（可整个收起），
 * 粘性头会与「收起后整组消失」的预期冲突。
 *
 * ## 2026-11-12 第八轮（#282 第 5 项）：分组头「成块」
 *
 * 用户反馈「按域名分组还不错，但 UI 可以稍微做得好看一点」。此前这条头是**透明 Row**：
 * 一行 `primary` 小字加一串灰色数字飘在背景上，与下面有描边的 [EntryCard] 之间没有
 * 层次，读起来像列表里混进了一行正文。本轮三处低开销调整（全在绘制阶段，无新增状态）：
 *
 * 1. 整条铺一层 `surfaceContainerLow` 低档容器底 ⇒ 头与卡片同为「面」，但它是**分节**；
 * 2. 标题占 `weight(1f)` 把计数推到行尾 ⇒ 一眼扫得到「这组几条」；
 * 3. 标题色 `primary → onSurface`、箭头保留 `primary` ⇒ 整屏不再到处是主色，
 *    唯一的强调留给「可点」的箭头（配色语义见 `CapabilityIcon` 的结论：不是颜色不够，
 *    是颜色没有语义）。
 *
 * ## ⚠️ 2026-11-12 第九轮（#283）：第 2 条的「计数胶囊」已反转作废
 *
 * 用户反馈「站点分类后面有个数字点，比如 baidu.com 有 2 条后面就是数字 2，这个
 * 数字的 UI 很不好看，给人一种要点击的感觉，强迫症受不了，不要这么显眼」。
 * #282 把计数做成了 `tertiaryContainer` + `CircleShape` + `padding` 的高对比小圆点 ——
 * 在 M3 里这套视觉语言就是 chip / badge，天然带**可点击暗示**，而分组头唯一的点击
 * 目标是整条 Row（箭头与 `stateDescription` 已表达）。等于凭空造了第二个假目标。
 *
 * ⇒ 计数退回 `onSurfaceVariant` 的纯文字：**无容器底、无形状、无内边距**；
 *    `weight(1f)` 的右对齐保留，扫读「这组几条」的能力不变，视觉噪音归零。
 *    判据同步反转：`ItemsRowStyleTest` 规则 3 现在**禁止**计数出现 `.background(` /
 *    `CircleShape` / `tertiaryContainer`。
 *
 * ⚠️ 箭头旋转动画是**既有**的、且分组头数量远少于条目数，不构成「每行开销」，保留；
 *    新施工点一律不引入任何动画或状态。整条容器底（第 1 条）本轮**没有**被推翻。
 */
@Composable
private fun GroupHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val expandedStateText = stringResource(
        if (expanded) R.string.items_group_expanded else R.string.items_group_collapsed,
    )
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        animationSpec = tween(GROUP_ANIM_MS),
        label = "group_arrow",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            // #282：低档容器底让分组头与卡片同属「面」，但读成分节而不是一行正文。
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(Spacing.sm),
            )
            .clip(RoundedCornerShape(Spacing.sm))
            .clickable(onClick = onToggle)
            // ★ 2026-10-19：展开态**只有箭头方向**在表达，箭头 `contentDescription = null`
            //   且只是旋转 —— 读屏完全不知道这组现在是展开还是收起。
            //   `stateDescription` 让读屏补一句「已展开 / 已收起」（TalkBack 念作 state），
            //   比把两个词塞进 `contentDescription` 更准：它**变化时**会被主动播报。
            .semantics {
                stateDescription = expandedStateText
            }
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(UiSize.InlineIcon).graphicsLayer { rotationZ = rotation },
        )
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = UiSize.TightGap)
                .weight(1f),
        )
        // #283：计数退回「一行低调的次要文字」，不要胶囊。
        // #282 的 tertiaryContainer + CircleShape + padding 把它做成高对比彩色圆点，
        // 用户原话「给人一种要点击的感觉」—— 它长得像 chip / badge，而这里既不可点
        // 也不需要被强调；整条 Row 才是唯一点击目标。⇒ 去掉底 / 形状 / 内边距，
        // 只留 onSurfaceVariant 小字，右对齐靠标题的 weight(1f)，扫读能力不变。
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemRow(
    item: VaultItem,
    serverOrigin: String?,
    displayMode: ItemsCardDisplayMode,
    showIcon: Boolean,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    /**
     * 行尾那朵云的语义（null = 不画）。由 [cloudSyncBadgeOrNull] 判定，本函数只负责画。
     *
     * ⚠️ 不要在这里加任何"KDBX 就怎样"的分支：判据只有一处（同 `vaultOriginLabel` 的纪律），
     * 多一处就会出现"条目页画了、TOTP 页没画"这种同规格不同呈现。
     */
    cloud: ItemCloudBadge?,
    onToggleSelect: () -> Unit,
    onClick: () -> Unit,
) {
    // 卡片外框规格见 [EntryCard]（对齐 Bastion PasswordEntryCard：M3 默认 Card 底色/高度 +
    // 12dp 圆角 + 16dp 内边距 + 标题 SemiBold + 6dp 行距）。
    // 多选态：整行点击 = 勾选（上游 `cardInteractionModifier` 同款分支）。
    // ⚠️ 长按选中由本卡片自己的 `onLongClick` **独占**（见 [EntryCard] 文档）。外层
    // [PressAndSwipeToDelete] 只负责「长按成立后进入拖拽删除」的信号，不再回调选中，
    // 否则一次长按会同时触发两次 toggle（净无操作）—— 正是用户反馈「长按进不了多选」的根因。
    EntryCard(
        onClick = if (isSelectionMode) onToggleSelect else onClick,
        onLongClick = if (isSelectionMode) null else onToggleSelect,
        selected = isSelected,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (showIcon) {
                // 站点图标（`<服务器>/icons/<域名>/icon.png`），取不到回退首字母头像。
                // 此前这里恒为首字母 —— 整库都是字母块，正是用户反馈的「图标是 bug」。
                SiteIcon(item = item, serverOrigin = serverOrigin)
                Spacer(Modifier.width(EntryCardIconSpacing))
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(EntryCardTextSpacing),
            ) {
                Text(
                    text = item.title.ifBlank { stringResource(R.string.items_item_unnamed) },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    // ⚠️ 显式收窄行高：`titleMedium` 默认 24sp 行高对"恒为一行、
                    //    且被 maxLines=1 截断"的标题是纯浪费 —— 那一截余量会变成
                    //    标题上方看不见的空白，把文本块在卡片里往下推。
                    //    取整的来龙去脉见 [EntryCardTitleLineHeight] 的 KDoc。
                    lineHeight = EntryCardTitleLineHeight,
                    // ⚠️ 这里**新增**了 `maxLines = 1`，是一次**行为变更**（长标题原先会折行）。
                    //    理由：TOTP / 卡包 / 通行密钥三个列表用的都是同一个 [EntryCard]，
                    //    且早已是 `maxLines = 1` —— 密码页是**唯一的例外**。
                    //    留例外就是「同一规格在不同页长得不一样」（8.4 的头号廉价感来源），
                    //    所以这里不是"顺手改行为"，而是把漏掉的一处**补齐**。
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 副标题（信息密度见 [ItemRowSubtitle]）。
                ItemRowSubtitle(item = item, displayMode = displayMode)
            }
            ItemRowTrailing(
                item = item,
                isSelectionMode = isSelectionMode,
                isSelected = isSelected,
                cloud = cloud,
                onToggleSelect = onToggleSelect,
            )
        }
    }
}

/**
 * 条目行尾那朵云画成什么样 —— 纯函数，可 JVM 单测。
 *
 * ## 起因（2026-11-10 真机反馈，两轮）
 *
 * 第一轮：
 *
 * > 本地 kdbx，没有上传云端。怎么条目显示上也有小云图标。这样不对。本地和云端应该有所区别。
 *
 * 根因是一条**默认值**串起来的三段：
 *
 * 1. `ItemRepository.observeSyncStates` 对 KDBX 恒返回空 map（**这是对的**，文件即存储）；
 * 2. 读侧写的是 `syncStates[item.id] ?: true` —— 空 map 查不到任何 id ⇒ 每条都是 `true`；
 * 3. 行尾**无条件**画 `CloudSyncIcon(synced = true)` ⇒ 一朵「已同步」的实心云。
 *
 * 当时的修法是「KDBX 一律不画」。**那是过度修正** —— 第二轮：
 *
 * > onedrive 上的 kdbx 条目，居然没有小云图标，这样和本地的 kdbx 条目看起来没有区别啊。
 *
 * 两轮反馈放在一起，真正要回答的问题是**两个**，不是三个：
 *
 * | 问题 | 由谁回答 |
 * |---|---|
 * | 「这个库本身在云上吗」 | `VaultSummary.origin`（[kdbxSourceTargetOf] 解析） |
 * | 「这一条推上云了吗」 | Bitwarden 的 `pending_ops` |
 *
 * 上一轮用一个「KDBX ⇒ null」把**两个问题**一起答了，于是第二问的答案被套到第一问上。
 *
 * ## 判据
 *
 * | 库 | 行尾 | 回答的是 |
 * |---|---|---|
 * | Bitwarden | [ItemCloudBadge.SYNCED] / [PENDING] | 第二问（这一条推了吗） |
 * | KDBX·OneDrive / WebDAV | [ItemCloudBadge.CLOUD_VAULT]（带勾云，#282） | 第一问（库在云上） |
 * | KDBX·本地 SAF | `null`（不画） | 哪儿都不在，没什么可说的 |
 *
 * ⚠️ **形状相同不代表语义相同（#282）**：KDBX 云端库与 Bitwarden「已同步」现在都画
 * 带勾云（用户要求统一），但 [ItemCloudBadge.CLOUD_VAULT] 与 [ItemCloudBadge.SYNCED]
 * 仍是两个枚举值、两套读屏文案、两个数据源。KDBX 依旧**不产出**条目级同步态，
 * 本地 KDBX 依旧**不画云** —— 这三条由 `CloudSyncBadgeTest` 钉住，别因为形状统一就合并。
 *
 * ⚠️ `vaultKind` 未知（还没加载出来）按**不画**处理：宁可少一个图标，
 * 也不要在加载瞬间闪一朵错的云。
 *
 * @param vaultKind 当前库类型；`null` = 还没加载出来
 * @param vaultOrigin 当前库的 `origin`；`null` 同上。**判 KDBX 是否在云上只认它**
 *   （复用 `kdbxSourceTargetOf`，那是「这个库在哪」的唯一真源）
 * @param syncedInVault Bitwarden 的待推送表；`null` = 刚建、还没入队 ⇒ 按已同步（不闪）
 */
internal fun cloudSyncBadgeOrNull(
    vaultKind: VaultKind?,
    vaultOrigin: String?,
    syncedInVault: Boolean?,
): ItemCloudBadge? = when (vaultKind) {
    VaultKind.BITWARDEN ->
        if (syncedInVault ?: true) ItemCloudBadge.SYNCED else ItemCloudBadge.PENDING

    VaultKind.KDBX ->
        // 来源未知（origin 缺失 / 解析失败）⇒ 按 LocalFile 处理，不画。
        // `kdbxSourceTargetOf` 已经把"坏 origin"退化成本地文件，但那种库连
        // 是不是云盘都不确定 —— 不画比画错安全（同 #263 的判据）。
        if (vaultOrigin != null && kdbxSourceTargetOf(vaultOrigin) != KdbxSourceTarget.LocalFile) {
            ItemCloudBadge.CLOUD_VAULT
        } else {
            null
        }

    null -> null
}

/** 条目行尾那朵云的**语义**（渲染在 `ItemRowTrailing`，不要在这里判断"画不画"）。 */
internal enum class ItemCloudBadge {
    /** Bitwarden：这一条已推送，云端是最新的。云 + 对勾，弱化色。 */
    SYNCED,

    /** Bitwarden：本地有改动还没推上去。云 + 斜杠，error 色（要跳出来）。 */
    PENDING,

    /** KDBX 网盘库：整个库在云端。#282 起用**带勾云**（与 [SYNCED] 同形），弱化色；语义仍不同，见 [cloudSyncBadgeOrNull]。 */
    CLOUD_VAULT,
}

/**
 * 条目卡片第二行（对齐 Bastion `PasswordCardDisplayMode`）：
 * TitleOnly 不显示；All 显示用户名、没用户名则显示类型徽标；TitleUsername 只显示用户名。
 *
 * ## 2026-11-12 第八轮（#282 第 5 项）：显示效果与类型徽标
 *
 * 两处「编译过、但真机才看得出来」的问题：
 *
 * 1. **没有截断**。用户名是用户自由输入，长邮箱 / 长 URL 会把卡片撑成两行，
 *    一屏能看到的条目数跟着变 —— 与标题已有的 `maxLines = 1` 不对称。
 *    两处用户名 `Text` 全部补 `maxLines = 1` + `Ellipsis`，行高恒定。
 * 2. **类型用纯文字 `primary` 色**，与详情页 `ItemDetailScreen` 的
 *    [TypeBadge]（`BadgeTone.NEUTRAL` 胶囊）不是一套语言。列表改用同一个组件、
 *    同一个 tone，两页的类型标识从此一致 —— 正是 8.4 点名的「同一规格在不同页
 *    长得不一样」这个头号廉价感来源。
 *
 * ⚠️ 刻意**不改字号、不改字重**：[TypeBadge] 用 `labelSmall`（比原来的 `bodySmall`
 *    还轻一档），方向是「更轻」；项目 8.8 §5 明确不采纳更大的 display 字号 / 更重的字重。
 *    质感来自 tonal 容器底与统一的组件，不是把字放大。
 */
@Composable
private fun ItemRowSubtitle(item: VaultItem, displayMode: ItemsCardDisplayMode) {
    if (displayMode == ItemsCardDisplayMode.All && item.username.isNotBlank()) {
        Text(
            text = item.username,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    } else if (displayMode == ItemsCardDisplayMode.All && item.type != VaultItemType.Login) {
        TypeBadge(
            text = stringResource(itemTypeLabelRes(item.type)),
            tone = BadgeTone.NEUTRAL,
        )
    } else if (
        displayMode == ItemsCardDisplayMode.TitleUsername &&
        item.type == VaultItemType.Login &&
        item.username.isNotBlank()
    ) {
        Text(
            text = item.username,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 行尾是否**有任何**点缀（#282 第 5 项）。
 *
 * 整组图标现在装在一个 tonal 胶囊里；没有点缀时那个胶囊必须**完全不画**，
 * 否则本地 KDBX 的普通条目每行都会多一颗空灰点。抽成纯函数是为了把
 * 「什么时候画容器」与「画什么」分开，也方便门禁扫源码钉住早退。
 */
private fun hasRowTrailing(item: VaultItem, cloud: ItemCloudBadge?): Boolean =
    cloud != null ||
        item.favorite ||
        !item.totp.isNullOrBlank() ||
        item.fido2Credentials.isNotEmpty()

/**
 * 条目卡片行尾：多选态是勾选框，常态是能力徽标。
 *
 * 对齐 Bastion：选择态 `Checkbox` 顶掉菜单/徽标位；非选择态才展示条目自身的标签。
 *
 * ## 2026-11-12 第八轮（#282 第 5 项）：点缀成组
 *
 * 用户点名「条目上的同步、验证码、通行密钥之类的小点缀上下点功夫」。此前四枚
 * 16dp 裸图标以 4dp 间距散在行尾、没有任何容器 ⇒ 既没有「一组」的读感，
 * 也与卡片里其他有 tonal 底的元素（[TypeBadge]）语言不一致。
 *
 * 做法是给**整组**一个 `surfaceContainerHigh` 胶囊底，而不是给每枚图标各包一个圆底：
 *
 * - 一个 `Modifier.background` = 一次绘制，零新增 composable 层级、零状态、零动画；
 * - 四枚圆点排成一串是视觉噪声，且会与 `CapabilityIcon` 的「刻意克制」结论打架；
 * - 图标的语义色（验证码 `primary` / 通行密钥 `tertiary` / 待同步 `error`）
 *   **一个不动**，tonal 底只负责把它们归到一组，不抢语义。
 *
 * ⚠️ `CapabilityIcon` / `CloudSyncIcon` / `VaultSourceCloudIcon` 都是**共享组件**
 *    （详情页、库列表也在用），本轮只在列表这一层包容器，不改它们本身。
 * ⚠️ 没有点缀时靠 [hasRowTrailing] 早退，绝不画空胶囊。
 */
@Composable
private fun ItemRowTrailing(
    item: VaultItem,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    /** null = 这个库既不在云上、也没有条目级同步状态 ⇒ 不画（见 [cloudSyncBadgeOrNull]）。 */
    cloud: ItemCloudBadge?,
    onToggleSelect: () -> Unit,
) {
    if (isSelectionMode) {
        Checkbox(checked = isSelected, onCheckedChange = { onToggleSelect() })
        return
    }
    if (!hasRowTrailing(item, cloud)) return
    // 能力徽标：一眼看出这条有没有 2FA 验证码、有没有绑通行密钥。
    // 两者可**同时**存在（一条登录条目既带 TOTP 又绑了 passkey），故不是 if/else。
    // 文案复用筛选维度的短词，避免另造一串近义词。
    Row(
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = CircleShape,
            )
            .padding(horizontal = UiSize.TightGap, vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 行尾那朵云：**地点**与**状态**仍是两件事，只是 #282 起统一了「已上云」的形状。
        //
        // ⚠️ 四轮演进的收敛点，别再退回任何一轮：
        //    ① 无条件画 `CloudSyncIcon(true)` ⇒ 本地 KDBX 也挂「已同步」（#263）；
        //    ② 「KDBX 一律不画」 ⇒ OneDrive 库与本地库长得一样（#266）；
        //    ③ 裸云（地点）/ 对勾云（状态）分开 ⇒ 用户觉得两朵云不一致（#282 本轮反馈）。
        //    正解：判据继续问**两个问题**（库在云上吗 / 这条推上去了吗），
        //    但「已上云」的两种答案共用带勾云；本地库依旧不画。
        when (cloud) {
            ItemCloudBadge.SYNCED -> CloudSyncIcon(synced = true)
            ItemCloudBadge.PENDING -> CloudSyncIcon(synced = false)
            ItemCloudBadge.CLOUD_VAULT -> VaultSourceCloudIcon()
            null -> Unit
        }
        // 收藏星标（对齐 Bitwarden：收藏条目在行尾点一颗星，
        // 不必进详情也能一眼把常用项从整库里挑出来）。
        if (item.favorite) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = stringResource(R.string.item_favorite),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(Spacing.lg),
            )
        }
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
    }
}

/**
 * 密码条目页的底部批量操作条（仅多选态出现）。
 *
 * 抽成独立函数是为了不让主 composable 越过 detekt `LongMethod ≤150` 门禁 ——
 * 「全选 / 清空 / 删除」三组动作在这里闭环，主函数只留一个状态流转的出口。
 */
@Composable
private fun ItemsSelectionBar(
    visibleItems: List<VaultItem>,
    selectedIds: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
    onDelete: (List<VaultItem>) -> Unit,
) {
    SelectionActionBar(
        selectedCount = selectedIds.size,
        // ⚠️ 主 Tab 页底部有**悬浮 Dock**（叠层、不让位），必须把这块高度让出去，
        //    否则操作栏落在 Dock 底下 —— 用户反馈的「看不到也点不到」就是这个。
        dockInset = BottomDockOccupiedHeight,
        onClear = { onSelectionChange(emptySet()) },
        onDelete = {
            onDelete(visibleItems.filter { it.id in selectedIds })
            onSelectionChange(emptySet())
        },
    )
}
