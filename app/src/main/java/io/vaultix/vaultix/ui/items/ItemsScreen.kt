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
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import io.vaultix.vaultix.ui.common.Capability
import io.vaultix.vaultix.ui.common.CapabilityIcon
import io.vaultix.vaultix.ui.common.EmptyState
import io.vaultix.vaultix.ui.common.capabilityTint
import io.vaultix.vaultix.ui.common.CloudSyncIcon
import io.vaultix.vaultix.ui.common.EntryCard
import io.vaultix.vaultix.ui.common.EntryCardIconSize
import io.vaultix.vaultix.ui.common.selectableItemTypes
import io.vaultix.vaultix.ui.common.EntryCardIconSpacing
import io.vaultix.vaultix.ui.common.EntryCardTextSpacing
import io.vaultix.vaultix.ui.common.EntryCardTitleLineHeight
import io.vaultix.vaultix.ui.common.ItemFormDialog
import io.vaultix.vaultix.ui.common.PressAndSwipeToDelete
import io.vaultix.vaultix.ui.common.SelectionActionBar
import io.vaultix.vaultix.ui.common.SiteIcon
import io.vaultix.vaultix.ui.common.TopBarTitle
import io.vaultix.vaultix.ui.common.VaultixExpressiveTopBar
import io.vaultix.vaultix.ui.common.VaultixSearchTopAppBar
import io.vaultix.vaultix.ui.common.itemTypeLabelRes
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
import kotlin.math.roundToInt

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
 * 列表**及其外壳**要整体让开的高度（2026-10-24）。
 *
 * ⚠️ 这与 [itemsTopInset]（只喂 `LazyColumn.contentPadding`）是**两件事**，别合并：
 *
 * | 量 | 喂给谁 | 为什么 |
 * |---|---|---|
 * | [itemsTopInset] | 列表的 `contentPadding.top` | 让内容能滑到顶栏之下（沉浸） |
 * | 本函数 | 列表的**外壳容器** `padding(top)` | 让**展开面板时可见的那一帧**有东西可看 |
 *
 * ## 为什么外壳也要让位（用户反馈：「往下滑动的时候点击筛选，显示效果很不好看」）
 *
 * 这是 2026-09-13「让位只走 contentPadding」那条纪律的**例外，且只在这一一种情况下成立**：
 * 面板自上而下展开，只能画出**已滚到顶栏之下**的那部分内容。列表在顶部时首条本来就在
 * 顶栏之下 ⇒ 每一帧都有卡片接住面板底边；列表滚下去之后，顶栏之下是一片**空白**，
 * 于是用户看到的是「面板往空处展开」，chip 行像是悬在半空 —— 这正是"不好看"的来源。
 *
 * ⇒ 外壳按 [QUICK_FILTER_TOP_ANCHOR] 下移，把列表**顶端**（而不是随手滚到某处的中间）
 * 露在面板下方；同时 `ItemsList` 会反向补偿滚动偏移，让可见内容**一像素都不跳**。
 * 收起面板时该项归零，一行代码的路径都与以前完全一致（沉浸式顶栏不受影响）。
 *
 * @param panelReveal 面板让列表外壳下移的量（[itemsFilterPanelReveal]）。
 */
private fun itemsShellTopInset(
    searchActive: Boolean,
    shellTopInset: Dp,
    panelReveal: Dp,
): Dp = if (searchActive) 0.dp else shellTopInset + panelReveal

/**
 * 面板展开时，列表外壳（列表视口的顶边）要下移多少 —— 即"面板下方至少露出多少列表"。
 *
 * 取 56dp 的理由：条目卡片视觉高约 [ENTRY_CARD_ESTIMATED_HEIGHT]（两行文字 + 16dp 上下
 * 内边距 ≈ 76dp），露出 56dp 恰好是**半张卡的上半部**（图标 + 标题）。
 * - 少于它（例如 40dp）只能露出卡片顶部圆角，读不出"下面有内容"；
 * - 多于它就要从列表里抢走一截视口，而面板本身已经占了 ~64dp。
 *
 * ⚠️ 它只在「面板展开 **且** 列表已滚离顶部」时生效，见 [rememberFilterBarCollapse]
 *   与 [rememberListEdgeHolder]：**没滚的时候不需要**（首条本来就在顶栏之下）。
 */
private val QUICK_FILTER_TOP_ANCHOR = 56.dp

/** 条目卡片的估算视觉高（两行文字 + `EntryCard` 的 16dp 上下内边距）。仅用于锚点取值推导。 */
private val ENTRY_CARD_ESTIMATED_HEIGHT = 76.dp

/**
 * 顶栏收起度 —— 把两条**与"筛选"耦合**的规则收在这里（主函数才能守住 detekt `LongMethod`）。
 *
 * **规则一：切换快筛 = 换了一份数据视图 ⇒ 列表回到顶部。**
 * 不重置的话，旧滚动位会被新数据集继承（而顶部内边距会把它"藏"起来，肉眼看不出已位移）：
 * 既让顶栏误判收起，也让每次切筛选白多一次重新布局。
 *
 * **规则二：筛选面板展开期间，收起度冻结为「展开」。**
 * 2026-09-15 用户实测：点开面板后连续切筛选（全部→验证码→通行密钥→SSH→**再点回全部**），
 * 标题会从 26sp 缩成 16sp、像"被滑下去了"，而且**那个状态下再点 chip 更卡**。
 * 根因是一圈**互相喂的状态**：收起度 ← 列表滚动位；而 `contentPadding.top` 里含着
 * `filterRowInset`（面板展开多出约 56dp）与 `barPadding`（又由收起度驱动）
 * ⇒ 面板一展开，顶部内边距变大，LazyColumn 把这份位移记进
 * `firstVisibleItemScrollOffset`：**视觉上列表还在顶部，判据 `offset > 8dp` 却已经成立**。
 * 面板展开时顶栏本来就该是展开的（标题刚被点过）⇒ 直接冻结，把回路切断。
 */
@Composable
private fun rememberFilterBarCollapse(
    listState: LazyListState,
    panelExpanded: Boolean,
    filter: ItemsQuickFilter,
): Float {
    // 只在**换了一份数据视图**时回到顶部（规则一）；面板开关靠 [rememberListEdgeHolder] 处理。
    LaunchedEffect(filter) { listState.scrollToItem(0) }
    val scrollCollapse = rememberScrollCollapseFraction(listState)
    return if (panelExpanded) 0f else scrollCollapse
}

/**
 * 「面板展开 ⇒ 列表顶端露在面板下方」的主动滚动态（2026-10-24）。
 *
 * ## 它解决什么
 *
 * 用户原话：「在往下滑动的时候点击筛选，显示效果很不好看」。往下滑之后再点库名，
 * 顶栏之下没有任何内容，面板就往空白里展开（见 [itemsShellTopInset] 的说明）。
 * 这里把列表**滚到顶端**，让 first = 第 0 项、offset = [QUICK_FILTER_TOP_ANCHOR]
 * —— 也就是 `contentPadding.top` **正好**把内容压到面板下沿，然后由外壳再下移同一个值
 * ⇒ 可见内容**一像素都不跳**，只是"面板下面接的是列表的头"而不是任意处。
 *
 * ## 为什么必须"先让位、再滚"
 *
 * ⚠️ **顺序是硬性的**：`scrollToItem(0, anchor)` 的上限是
 * `contentPadding.top - viewport`，而让位（外壳 `padding` 与列表 `contentPadding`）要
 * **下一帧**才生效。若先滚，那一刻 `padding.top` 还是 0 ⇒ 锚点被夹到 0 ⇒ 滚了等于没滚
 * （`scrollToItem` 只记录"目标位"，不会在让位生效后自己修正）。所以这里用两帧：
 * 第 ① 帧只把让位打上去，第 ② 帧才滚。
 *
 * ## 与规则一（切筛选回顶部）的分工
 *
 * "切筛选 ⇒ 回第 0 项"仍然成立且**优先**：换的是数据视图，不能把旧滚动位带过去。
 * 面板已展开时用户是"在看面板期间切 chip"，那一次直接 `scrollToItem(0)`，
 * `firstVisibleItemScrollOffset` 归零 ⇒ [rememberScrollCollapseFraction] 自然读成"未收起"，
 * 顶栏不会在切换时缩一下（2026-09-15 用户报的那个毛病不再出现），
 * 也就不需要再靠"冻结收起度"压着它。
 *
 * ## ⚠️ 2026-10-24 当晚：这个行为**默认关闭**了（用户反馈）
 *
 * 用户原话：「现在往下滑动的时候，发现点击筛选，会直接返回最上面。这样设定不友好」。
 * 补偿只保证"不跳"，没保证"不换位置" —— **位置连续性本身**才是用户的诉求：
 * 他正看到第 30 条，点个筛选是为了在**这个上下文里**换一维，不是从头再看一遍。
 *
 * ⇒ 改成偏好项 [ItemsViewModel.filterJumpToTop]，默认 `false`（= 本函数整条不生效，
 *   `holding` 恒为 false，`reveal` 恒为 0，回到 #182 之前的滚动行为）。
 *   想要"展开即回到顶部"的人在「显示选项」里自己打开。
 *
 * ⚠️ 关掉之后的代价（如实记）：往下滑再展开面板时，顶栏之下仍是空白 —— 那正是 #182
 *   想解决的"难看"。这是**用户选的取舍**，不是我们又替他猜了一次。
 *
 * @param jumpToTop 偏好开关（false = 完全不插手滚动位置）。
 * @return 终态是否为「列表顶端卡在面板下方」。调用方据此决定外壳要不要下移
 *   （见 [itemsFilterPanelReveal]）。
 */
@Composable
private fun rememberListEdgeHolder(
    listState: LazyListState,
    panelExpanded: Boolean,
    filter: ItemsQuickFilter,
    jumpToTop: Boolean,
): Boolean {
    var holding by remember { mutableStateOf(false) }
    // 当前卡点值：从状态里读会给组合期引入一次快照读（`firstVisibleItemScrollOffset`），
    // 滚一下重组一次；而本函数每帧的结果只取决于"第 ① 项 + 是否已展开"。
    val anchorPx = with(LocalDensity.current) { QUICK_FILTER_TOP_ANCHOR.toPx().roundToInt() }
    LaunchedEffect(panelExpanded, filter, jumpToTop) {
        // 偏好关闭（默认）：**一个字节都不碰滚动位置** —— 不只是"不滚到卡点"，
        // 连"切 chip 回第 0 项"也不做（那条规则属于 #182 配套的一部分，
        // 用户要的是"保持原位"，不是"换一种方式回顶部"）。
        if (!panelExpanded || !jumpToTop) {
            holding = false
            return@LaunchedEffect
        }
        val atTop = filter == ItemsQuickFilter.All &&
            listState.firstVisibleItemIndex == 0 &&
            listState.firstVisibleItemScrollOffset <= anchorPx
        if (atTop) {
            // 列表本就在顶部：让位（contentPadding）已经把内容推到面板下方，滚动位原样保留。
            holding = false
        } else if (!holding) {
            holding = true
            // ① 打让位（下一帧生效）→ ② 再滚到锚点。
            withFrameNanos { }
            listState.scrollToItem(0, anchorPx)
        } else {
            // 已经在卡点态：切 chip 只换数据视图，回到第 0 项的默认位置（不留半截滚动位）。
            listState.scrollToItem(0)
        }
    }
    return holding
}

/**
 * 外壳要不要下移：**只在「面板展开 + 列表处于卡点态」时**。
 *
 * 其余情况一律 0.dp —— 特别是"列表在顶部，点开面板"这条最高频的路径，
 * 外壳一行都不动（与 2026-10-24 之前完全一致）。
 */
private fun itemsFilterPanelReveal(expanded: Boolean, holding: Boolean): Dp =
    if (expanded && holding) QUICK_FILTER_TOP_ANCHOR else 0.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemsScreen(
    onBack: () -> Unit,
    onLocked: () -> Unit,
    onOpenTrash: () -> Unit,
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
    val collapse = rememberFilterBarCollapse(listState, quickFiltersExpanded, state.quickFilter)
    val barPadding = rememberImmersiveBarPadding(collapse)
    // ⚠️ 挤在一行是有意的：主 composable 贴着 detekt `LongMethod ≤150` 的门禁线
    // （2026-09-13 这一批加了「让位走 contentPadding」后曾到 152 行，靠压这行回到 147）。
    val filterRowInset = rememberQuickFilterRowInset(quickFiltersExpanded, organizations.isNotEmpty())
    val listTopInset = itemsTopInset(searchActive, barPadding, filterRowInset, state.syncNote != null)
    // 2026-10-24：面板展开**且列表已滚离顶部**时，外壳下移一截并反向补偿滚动偏移
    // （见 [itemsShellTopInset]）。偏好默认关 ⇒ reveal 恒为 0，同改动前完全一致。
    val holdingEdge = rememberListEdgeHolder(listState, quickFiltersExpanded, state.quickFilter, jumpToTop)
    val reveal = itemsFilterPanelReveal(quickFiltersExpanded, holdingEdge)
    val shellTopInset = itemsShellTopInset(searchActive, listTopInset, reveal)
    // 「这个库在哪 · 有多少条」（2026-09-30 用户要求）：在本层算一次，供 ⋮ 菜单卡片与
    // 列表状态行**共用**（各算各的迟早出现"卡片 42 条、列表 41 条"）。
    // ⚠️ 未解锁时 countText 为 null：条目数不可知 ≠ 0。
    val vaultCard = state.vault?.let { vault -> vaultCardInfoOf(vault, state.items.size) }
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
                // 下拉伸手区（指示器让位与状态都在这里闭环，见 [ItemsPullToRefresh]）。
                // ⚠️ 2026-10-24：这里多了一层 `padding(top = reveal)` —— 它是**唯一**的
                // 外层让位，且只在「面板展开 + 列表已滚离顶部」时非零（见
                // [itemsShellTopInset]）；`ItemsList` 会反向补偿滚动偏移，可见内容不跳。
                ItemsPullToRefresh(
                    syncing = syncing,
                    onRefresh = viewModel::retrySync,
                    modifier = Modifier.padding(top = reveal),
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
                        listTopInset = shellTopInset,
                        scrollCompensation = -reveal,
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
                QuickFilterPanel(
                    visible = quickFiltersExpanded,
                    selected = state.quickFilter,
                    topPadding = barPadding,
                    onSelect = viewModel::setQuickFilter,
                    onDismiss = { quickFiltersExpanded = false },
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
                    onTitleClick = { quickFiltersExpanded = !quickFiltersExpanded },
                    onBack = onBack,
                    onDisplayOptions = { displayOptionsOpen = true },
                    onToggleSearch = { searchActive = true },
                    onOpenTotp = onOpenTotp,
                    onOpenTrash = onOpenTrash,
                    onRetrySync = viewModel::retrySync,
                    onLock = { viewModel.lockNow(onLocked) },
                    onSwitchVault = onSwitchVault,
                    vaultCard = vaultCard,
                )
            }
        }
    }

    ItemDisplayOptionsHost(viewModel, displayOptionsOpen, onDismiss = { displayOptionsOpen = false })

    if (showCreateDialog) {
        CreateItemDialog(
            folders = folders,
            vaultKind = state.vault?.kind,
            saving = state.saving,
            onDismiss = { showCreateDialog = false },
            onSave = { item ->
                viewModel.createItem(item)
                showCreateDialog = false
            },
        )
    }
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
    /**
     * 列表要留出的顶部让位（**已含**面板展开时的锚点让位，见 [itemsShellTopInset]）。
     *
     * ⚠️ 名字沿用 `listTopInset`，但它现在喂的是"外壳下移之后的可用高度"——
     * 与外壳那层 `padding(top = reveal)` 是同一个量的两半，任何一半单独改都会让内容跳。
     */
    listTopInset: Dp,
    /**
     * 逐项绘制偏移（px，负值 = 往上画），用于抵消外壳 `padding(top)` 造成的位移。
     *
     * 只在「面板展开 + 列表已滚离顶部」时非零（= `-QUICK_FILTER_TOP_ANCHOR`）；
     * 空态不需要它（那里本来就没有"内容不该跳"这回事），所以只往 [ItemsList] 传。
     */
    scrollCompensation: Dp = 0.dp,
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
        topInset = listTopInset,
        scrollCompensation = scrollCompensation,
        bottomInset = bottomInset,
        onToggleGroup = onToggleGroup,
        selectedIds = selectedIds,
        onToggleSelect = onToggleSelect,
        onOpenItem = onOpenItem,
        syncStates = state.syncStates,
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
        // ⚠️ 2026-09-13 第二轮用户反馈：「下拉刷新的时候，密码条目左边有一个刷新按钮但是
        // 没显示出来是白色的，可以移除了吧，不好看。」
        // 那枚指示器的底色是 `surfaceContainerHigh`，浅色主题下几乎与页面同色 —— 看起来
        // 就是"一块白"；而且它落在首条卡片左侧，正压着站点图标。
        // 这里**不再画指示器**：下拉手势与同步逻辑原样保留（静默触发），手动同步入口仍在
        // 顶栏 ⋮ 菜单里 —— 不靠一枚看不清的圆点来提示。因此 `topInset` 参数也随之取消。
        indicator = {},
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
    /** 展开筛选时是否把列表拉回顶端（默认关；见 [rememberListEdgeHolder] 的 KDoc）。 */
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
 * | 同步 | 对当前库**做动作** |
 * | 锁定 | **破坏性动作** |
 *
 * ⇒ 顶上一张卡片先回答"我在哪、有多少条"，下面按**前往 / 操作**分组。
 *   分组标题给的是"这几项是同一类事"这条信息 —— 那是平铺列表里**读不出来的东西**。
 *
 * ⚠️ 菜单项顺序 = 使用频率（2026-09-14 用户指定的重排）：回收站 → 同步 → 锁定。
 * 「锁定」不归任何一组，单独用分隔线隔开（破坏性动作不挨着常用动作，对齐 M3 建议）。
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
    onRetrySync: () -> Unit,
    onLock: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.items_more_actions),
            )
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
            MenuGroupTitle(R.string.items_menu_group_actions)
            MenuAction(Icons.Filled.Refresh, R.string.items_sync) {
                expanded = false
                onRetrySync()
            }
            HorizontalDivider()
            // 「锁定」= 锁查看层（不清密钥，一次生物识别即回来），见 ISSUES #60 1b
            MenuAction(Icons.Filled.Lock, R.string.items_lock, destructive = true) {
                expanded = false
                onLock()
            }
        }
    }
}

/**
 * 菜单顶部的**当前库卡片**：库名 + 来源 · 条目数。
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
        Text(
            text = card.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // 来源与条目数拼成一行：分号（`·`）分隔，条目数缺失（未解锁）时只剩来源。
        val subtitle = listOfNotNull(card.origin, card.countText).joinToString(separator = " · ")
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // ⚠️ 固定宽度后**必须回到单行中间省略**（此前是 `maxLines = 2`）。
            // 宽度恒定了，"给全"就不成立：KDBX 的 `OneDrive · Keepass/valkjin.kdbx · 217 个条目`
            // 在 280dp 里放不下两行，第二行会把下面的分隔线顶开、菜单高度随内容跳。
            // ⇒ 保头保尾折成一行（`MiddleEllipsizedText`），高度与切库无关。
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // 同步状态**单独一行**（卡片有空间）：它与"在哪 / 多少条"是不同性质的信息，
        // 挤进副标题会既丢颜色、又让长路径把它推到看不见。
        if (card.syncBadge != null) {
            Text(
                text = syncBadgeLabel(card.syncBadge),
                style = MaterialTheme.typography.labelMedium,
                color = syncBadgeColor(card.syncBadge),
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
 * 取 280.dp 的理由：能容下 KDBX 最常见的
 * `OneDrive · Keepass/valkjin.kdbx · 217 个条目`（约 40 字符，bodySmall 下 ≈ 250dp），
 * 且在窄屏（≥320dp）上仍留得住边距。固定后长文本走**中间省略**（保头保尾，
 * 尾段的条目数与文件名是用户最想确认的），高度与切库无关。
 */
private val MENU_CARD_WIDTH = 280.dp

/** 菜单项（图标 + 文案；[destructive] 用 error 色标出不可逆 / 中断性动作）。 */
@Composable
private fun MenuAction(
    icon: ImageVector,
    @StringRes labelRes: Int,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Text(
                text = stringResource(labelRes),
                color = if (destructive) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (destructive) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
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
    topInset: Dp,
    /** 逐项绘制偏移（px 换算后，负值 = 往上画），抵消外壳 `padding(top)` 的位移；见 [ItemsBody]。 */
    scrollCompensation: Dp = 0.dp,
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
     * 「这个库在哪 · 有多少条」（null = 库未知，整行不画）。
     *
     * 由 [ItemsBody] 经 `vaultCardInfoOf` 组装（那里才有 [io.vaultix.model.VaultSummary]
     * 与条目总数），本函数只负责画 —— "域名/路径怎么来"的口径集中在 `ui/common/VaultOriginLabel.kt`。
     */
    vaultCard: VaultCardInfo? = null,
) {
    // ⚠️ 反向补偿（2026-10-24）：外壳 `padding(top = reveal)` 会把整个列表挪下去，
    // 而 `contentPadding.top` 只负责"第一项从哪开始画"。两者在**滚动容器内部**是同一笔，
    // ⇒ 若只加外壳，内容会跟着往下跳一截（"点筛选，列表整体下沉了"）；把这份位移在
    // 绘制期减回去，可见内容就**一像素都不动** —— 用户看到的只有「面板盖住了上面几行」。
    // 用 `graphicsLayer` 而不是改 `contentPadding`：后者会挪动真实布局位置，
    // 连第一项的位置也跟着变（那就白补偿了）。
    val compensationPx = with(LocalDensity.current) { scrollCompensation.toPx() }
    fun Modifier.compensate(): Modifier =
        if (compensationPx == 0f) this else this.graphicsLayer { translationY = compensationPx }
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
                VaultStatusRow(card = vaultCard, modifier = Modifier.compensate())
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
                        modifier = Modifier.compensate(),
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
                        modifier = Modifier.compensate(),
                    ) {
                        ItemRow(
                            item = item,
                            serverOrigin = serverOrigin,
                            displayMode = displayMode,
                            showIcon = showIcon,
                            isSelectionMode = selectedIds.isNotEmpty(),
                            isSelected = item.id in selectedIds,
                            // 缺省 true：新建刚可见、还没入队的那一瞬间不该闪一下「未同步」。
                            synced = syncStates[item.id] ?: true,
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
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (VaultItem) -> Unit,
) {
    val blankItem = remember { VaultItem(id = "", title = "") }
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
        onDismiss = onDismiss,
        onSave = onSave,
    )
}

/** 顶栏动作已收敛为「胶囊里的 🔍 + ⋮」（见 [ItemsMoreMenu]）。 */

/** 同步状态提示条：进行中 = 细进度条；成功/跳过 = 短暂提示后自动消失；警告 = 常驻到下次同步。 */
@Composable
private fun SyncNoteBanner(
    note: ItemsViewModel.SyncNote?,
    topPadding: Dp,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    LaunchedEffect(note) {
        if (note is ItemsViewModel.SyncNote.Success || note is ItemsViewModel.SyncNote.Skipped) {
            kotlinx.coroutines.delay(NOTE_DISMISS_DELAY_MS)
            onDismiss()
        }
    }
    // ⚠️ note 为空时必须**直接 return**（一个节点都不渲染）：调用方不再用 `if` 包着它，
    // 若这里返回一个只剩 padding 的空容器，就会凭空留出一条空气。
    if (note == null) return

    Surface(
        color = when (note) {
            is ItemsViewModel.SyncNote.Warning -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        // padding 放在 Surface **外面**：让横幅整体落在顶栏之下，而不是色块被顶栏压住。
        modifier = Modifier.fillMaxWidth().padding(top = topPadding),
    ) {
        when (note) {
            ItemsViewModel.SyncNote.InProgress -> {
                VaultixWavyProgressBar(modifier = Modifier.fillMaxWidth())
            }
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
            .clip(RoundedCornerShape(Spacing.sm))
            .clickable(onClick = onToggle)
            // ★ 2026-10-19：展开态**只有箭头方向**在表达，箭头 `contentDescription = null`
            //   且只是旋转 —— 读屏完全不知道这组现在是展开还是收起。
            //   `stateDescription` 让读屏补一句「已展开 / 已收起」（TalkBack 念作 state），
            //   比把两个词塞进 `contentDescription` 更准：它**变化时**会被主动播报。
            .semantics {
                stateDescription = expandedStateText
            }
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
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
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = UiSize.TightGap),
        )
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = UiSize.TightGap),
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
    /** 该条目是否已同步上云（true = 云端最新，false = 本地待推送）。 */
    synced: Boolean,
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
                synced = synced,
                onToggleSelect = onToggleSelect,
            )
        }
    }
}

/**
 * 条目卡片第二行（对齐 Bastion `PasswordCardDisplayMode`）：
 * TitleOnly 不显示；All 显示用户名、没用户名则显示类型徽标；TitleUsername 只显示用户名。
 */
@Composable
private fun ItemRowSubtitle(item: VaultItem, displayMode: ItemsCardDisplayMode) {
    if (displayMode == ItemsCardDisplayMode.All && item.username.isNotBlank()) {
        Text(
            text = item.username,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else if (displayMode == ItemsCardDisplayMode.All && item.type != VaultItemType.Login) {
        Text(
            text = stringResource(itemTypeLabelRes(item.type)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
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
        )
    }
}

/**
 * 条目卡片行尾：多选态是勾选框，常态是能力徽标。
 *
 * 对齐 Bastion：选择态 `Checkbox` 顶掉菜单/徽标位；非选择态才展示条目自身的标签。
 */
@Composable
private fun ItemRowTrailing(
    item: VaultItem,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    synced: Boolean,
    onToggleSelect: () -> Unit,
) {
    if (isSelectionMode) {
        Checkbox(checked = isSelected, onCheckedChange = { onToggleSelect() })
        return
    }
    // 能力徽标：一眼看出这条有没有 2FA 验证码、有没有绑通行密钥。
    // 两者可**同时**存在（一条登录条目既带 TOTP 又绑了 passkey），故不是 if/else。
    // 文案复用筛选维度的短词，避免另造一串近义词。
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 云端同步状态（待推送 = 云加斜杠，error 色跳出来）。
        CloudSyncIcon(synced = synced)
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
