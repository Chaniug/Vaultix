/*
 * Vaultix — app:ui:shell
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 中央「+」的动作面板（架构方案 A，2026-11-09）。
 *
 * 为什么不扩 Tab：便签是「条目类型」（`VaultItemType.SecureNote`，已有筛选器），
 * 生成器是「动作」（`PasswordGeneratorDialog`，已在表单里用），卡包是「集合视图」。
 * 三者层级不同，并列成 Tab 会把「目的地 / 内容类型 / 动作」混成一层。
 * ⇒ 卡包位置与身份完全不动，`currentTab` / `SaveableStateProvider` / `contentKey`
 *   一行不改；新增能力全部从「+」的动作面板进入。
 *
 * ## 2026-11-09 第三轮（22:20 用户真机反馈）
 *
 * - **删掉「新建」标题**：它和下面的主卡说的是同一件事，白占一行高度。
 *   面板顶部改留一段空白，主卡自己就是焦点。
 * - **删掉生成器前的分隔线**：卡片之间本来就有间距，再画一条线是第二个分组信号。
 * - **生成器改成通栏长条卡**：与类型卡同构（剪影在上、文字在下），
 *   只是剪影形状是长条 —— 用形状区分「工具」而不是靠一条分隔线。
 * - **剪影加颜色点缀**：每个类型取一组 M3 色板角色（primary / secondary /
 *   tertiary 的 container 与 accent），不再全是灰蓝。⚠️ 全部走 `colorScheme`
 *   角色，**不硬编码品牌色**，动态取色与 8.8 §6② 都不受影响。
 *
 * ## 2026-11-12 第八轮（#282）：剪影卡片配色活泼化
 *
 * 用户反馈「剪影卡片的颜色偏单调，能稍微多加一两个色系的配色吗」。本轮只动
 * [glyphPalette] 与两处绘制参数：三色系在「卡底 / 剪影底 / 重点形状」交叉铺开、
 * 卡底 alpha 0.45 → 0.68、便签补一枚 accent 色块。**不新增任何 vector、不写死色值、
 * 不引入额外重组**（`glyphPalette` 仍是一次 `colorScheme` 读取的纯函数）。
 * 详见 [GlyphPalette] 的 KDoc：为什么不硬造第 4 个色相。
 *
 * ## 2026-11-12 第十轮（#285）：★ 判据反转 —— 改用 M3 fixed accent roles
 *
 * 用户反馈「按安卓最新设计规范重新配色，不要奇怪颜色，浅色 / 深色都兼顾，同时每张卡
 * 有视觉记忆点」。**「奇怪颜色」正是 #282/#283 那套做法本身**：三色系 `xxxContainer`
 * 交叉铺开，再叠「卡底 0.68 / 剪影底 0.62」两层半透明（深色档还各提到 0.94 / 0.92），
 * 在近黑 `surface` 上五个色相被洗成浑浊灰 —— #283 已经补过一次「画纯 + onContainer
 * 配对」，但只要还靠 `xxxContainer`（它**随主题翻转**：浅色是浅色、深色变暗色）+ alpha
 * 分档，深浅两档就永远要各调一遍，治标不治本。
 *
 * ⇒ 换 **M3 fixed accent roles**（2023 起引入，2025 Color System 已成 26+ roles）：
 *   `primaryFixed` / `secondaryFixed` / `tertiaryFixed` 及各自的 `Dim` / `onXxxFixed` /
 *   `onXxxFixedVariant`。它们的设计契约就是**浅色与深色主题下保持同一固定色值**，
 *   专供「不随主题翻转身份」的强调面（固定徽标、剪影卡、widget）。
 *   - 「浅深都兼顾」⇒ 不再需要 `isDarkSurface` 判档、不再需要任何 alpha 常量：
 *     卡底直接画纯 fixed 色，浅色下是一层柔和 tonal 面，深色下是一块明亮粉彩卡，
 *     两者都是 M3 预期形态；
 *   - 「不要奇怪颜色」⇒ 三色系取蓝 225° / 青 195° / 绿 145°、饱和度压到 22%~35.5%
 *     （见 `VaultixBrandColor` §五，有单测逐项钉住），不再交叉叠色；
 *   - 「每张卡有记忆点」⇒ 3 色相 × fixed / fixedDim 两档，5 种类型各有组合，
 *     加上各自的字段剪影形状，颜色与形状双重区分；
 *   - 文案 / 线条一律用配对的 `onXxxFixed` / `onXxxFixedVariant`（实测 6~12:1，
 *     过 WCAG AA），不拿 `onSurface` 顶替 —— #283 这条教训保留。
 *
 * ⚠️ 动态取色路径同样成立：`dynamicLightColorScheme` 会把 fixed 角色映射成壁纸派生
 *    的 tonal palette，颜色仍随壁纸走；静态色板这边 #285 也补齐了 12 个 fixed 角色，
 *    所以「开 / 关动态取色」两档不再跳色（见 `VaultixBrandColor` §五）。
 *
 * ⚠️ **不硬造第 4 个色相**：M3 只保证 primary/secondary/tertiary 三个强调色相，
 *    自己算色相会破坏动态取色（#282 的同一条结论）。`error` 是危险语义色，不能拿来
 *    当类型标识。
 *
 * ⚠️ 用稳定的 `ModalBottomSheet`，不用 M3E 的 `FloatingActionButtonMenu`
 *    （8.8 §6①：alpha API 只用于叶子组件，不承载导航级动作）。
 * ⚠️ 面板里放 `Surface` 卡片，不是 Popup / DropdownMenu 选择器。
 * ⚠️ 独立生成器对话框由**宿主**渲染（[StandalonePasswordGeneratorDialog]）：
 *    若把开关留在本 composable 里，收起面板后它随组合一起销毁，对话框根本弹不出来。
 * ⚠️ 每个视觉块都抽成顶层 private composable：`AddActionSheet()` 要留足
 *    detekt `LongMethod ≤150` 与 `CyclomaticComplexMethod ≤14` 的余量。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.shell

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.vaultix.model.VaultItemType
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.common.PasswordGeneratorDialog
import io.vaultix.vaultix.ui.common.itemTypeLabelRes
import io.vaultix.vaultix.ui.theme.Spacing
import io.vaultix.vaultix.ui.theme.VaultixCorner
import kotlinx.coroutines.launch

/**
 * 一类条目的剪影配色（#285：全部取自 M3 **fixed accent roles**）。
 *
 * 四个字段是**同一色相家族**里的四档，浅色 / 深色主题下拿到的是同一组固定色值
 * （这正是 fixed roles 的设计契约），所以卡片身份不随主题翻转，也不需要判深浅档：
 *
 * | 字段 | 角色 | 用途 |
 * |---|---|---|
 * | [container] | `xxxFixed` / `xxxFixedDim` | 整张类型卡的底色（**纯色**，不叠 alpha） |
 * | [wash] | 同家族另一档 | 剪影内层小卡背景，与卡底拉开一档明暗 |
 * | [onContainer] | `onXxxFixed` | 类型名 + 剪影重点形状（最高对比） |
 * | [onContainerVariant] | `onXxxFixedVariant` | 剪影次要线条（柔和但仍可达） |
 *
 * ⚠️ 与 #282/#283 的交叉表不同：[wash] **刻意与 [container] 同色系** ——「一张卡里
 *    塞两个色系」正是用户说的「奇怪颜色」；层次改由 Fixed / FixedDim 两档明暗表达。
 * ⚠️ 前景一律 `onXxxFixed` / `onXxxFixedVariant`，不拿 `onSurface` / `onSurfaceVariant`
 *    顶替（#283 的教训保留：容器底必须配 on-container）。
 */
private data class GlyphPalette(
    val container: Color,
    val wash: Color,
    val onContainer: Color,
    val onContainerVariant: Color,
)

/**
 * 五种类型各取一组 fixed accent roles，**全部来自 [MaterialTheme.colorScheme]**，
 * 不写死任何色值 ⇒ 动态取色 / 静态品牌板 / 深浅主题都自动成立。
 *
 * ## 2026-11-12 第十轮（#285）：判据反转 —— 废弃 `xxxContainer` + alpha 分档
 *
 * #282 把三色系 `primaryContainer` / `secondaryContainer` / `tertiaryContainer` 在
 * 「卡底 / 剪影底 / 重点形状」交叉铺开，#283 又补了 `onXxxContainer` 配对与深浅两档
 * alpha（深色 0.94 / 0.92，浅色 0.68 / 0.62）。但 `xxxContainer` **随主题翻转**
 * （浅色是浅色、深色变暗色），只要还靠它 + 半透明分档，深浅两档就得各调一遍，
 * 近黑 `surface` 上几个色相仍被洗成浑浊灰 —— 这就是用户说的「奇怪颜色」。
 *
 * ⇒ 换 fixed accent roles：卡底直接画**纯** `xxxFixed` / `xxxFixedDim`，文案与线条用
 *   配对的 `onXxxFixed` / `onXxxFixedVariant`。浅色下是一层柔和 tonal 面、深色下是
 *   一块明亮粉彩卡，两者都是 M3 预期形态，**一行深浅判定、一个 alpha 常量都不需要**。
 *
 * ### 交叉表（3 色相 × Fixed / FixedDim 两档，5 种类型互不撞色）
 *
 * | 类型 | 色相 | 卡底 [GlyphPalette.container] | [GlyphPalette.wash] |
 * |---|---|---|---|
 * | Login | primary 蓝 225° | `primaryFixed` | `primaryFixedDim` |
 * | SecureNote | secondary 青 195° | `secondaryFixed` | `secondaryFixedDim` |
 * | Card | tertiary 绿 145° | `tertiaryFixed` | `tertiaryFixedDim` |
 * | Identity | primary 蓝 225° | `primaryFixedDim` | `primaryFixed` |
 * | SshKey | secondary 青 195° | `secondaryFixedDim` | `secondaryFixed` |
 *
 * ⚠️ Identity / SshKey 用 Dim 档作卡底、Fixed 档作 wash：与前三张**同色相但明暗反了一档**，
 *    配合各自的字段剪影形状足以区分，又不硬造第 4 个色相（见文件头）。
 * ⚠️ 色值本身的饱和度 / 对比度 / 浅深同值由 `VaultixBrandColorTest` 逐项钉住，这里只接线。
 */
@Composable
private fun glyphPalette(type: VaultItemType): GlyphPalette {
    val scheme = MaterialTheme.colorScheme
    return when (type) {
        VaultItemType.Login -> GlyphPalette(
            scheme.primaryFixed, scheme.primaryFixedDim,
            scheme.onPrimaryFixed, scheme.onPrimaryFixedVariant,
        )
        VaultItemType.SecureNote -> GlyphPalette(
            scheme.secondaryFixed, scheme.secondaryFixedDim,
            scheme.onSecondaryFixed, scheme.onSecondaryFixedVariant,
        )
        VaultItemType.Card -> GlyphPalette(
            scheme.tertiaryFixed, scheme.tertiaryFixedDim,
            scheme.onTertiaryFixed, scheme.onTertiaryFixedVariant,
        )
        VaultItemType.Identity -> GlyphPalette(
            scheme.primaryFixedDim, scheme.primaryFixed,
            scheme.onPrimaryFixed, scheme.onPrimaryFixedVariant,
        )
        VaultItemType.SshKey -> GlyphPalette(
            scheme.secondaryFixedDim, scheme.secondaryFixed,
            scheme.onSecondaryFixed, scheme.onSecondaryFixedVariant,
        )
    }
}

/**
 * 「+」动作面板。
 *
 * @param currentTab 决定主卡是哪一项（复用 [VaultixNavItem.addTarget]），
 *   保住「在验证码页按 + 就是新建验证码」这条既有高频路径。
 * @param onDefaultAction 执行当前 Tab 原来的分发逻辑（切 Tab + 递增对应 addRequest）。
 * @param onPickType 新建指定类型的条目；宿主切到密码 Tab 并把类型透传给表单。
 * @param onGeneratePassword 打开独立生成器（由宿主渲染，见文件头）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddActionSheet(
    currentTab: VaultixNavItem,
    onDefaultAction: () -> Unit,
    onPickType: (VaultItemType) -> Unit,
    onGeneratePassword: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // 先收起弹层再执行动作：否则选中态会在关闭动画里跳一下
    // （与 `DisplayOptionsSheet` 同款处理，照抄已编译过的写法）。
    fun pick(apply: () -> Unit) {
        scope.launch {
            if (sheetState.isVisible) sheetState.hide()
            onDismiss()
            apply()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = Spacing.xl)
                .navigationBarsPadding(),
        ) {
            // 22:20：删掉「新建」标题 —— 它与主卡重复，还占掉一整行。
            Spacer(Modifier.height(Spacing.lg))

            // 8.4：同一屏只有一个 Filled 面 —— 主卡。它是当前 Tab 的默认新建动作，
            // 也是方案 A「1 步变 2 步」里那第二步的落点（拇指几乎在原处）。
            //
            // [heroType] 只用于主卡右侧的剪影水印。验证码没有自己的 [VaultItemType]
            // （它走 `totpAddRequest`），剪影沿用登录 —— 验证码本就是登录条目上的
            // TOTP 字段，不为它硬造第六种剪影（那是给「一个系统」开第二个系统）。
            val (defaultLabel, heroType) = when (currentTab.addTarget) {
                VaultixAddTarget.Login -> R.string.dock_add_default_login to VaultItemType.Login
                VaultixAddTarget.Totp -> R.string.dock_add_default_totp to VaultItemType.Login
                VaultixAddTarget.Card -> R.string.dock_add_default_card to VaultItemType.Card
            }
            AddHeroCard(
                label = stringResource(defaultLabel),
                type = heroType,
                onClick = { pick(onDefaultAction) },
            )

            Text(
                text = stringResource(R.string.dock_add_other_types),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.md),
            )
            AddTypeGrid(
                types = standaloneTypes(currentTab),
                onPick = { type -> pick { onPickType(type) } },
            )

            // 22:20：去掉分隔线，生成器自己成为一张通栏长条卡。
            Spacer(Modifier.height(Spacing.sm))
            AddGeneratorTile(onClick = { pick(onGeneratePassword) })
        }
    }
}

/**
 * 网格里要显示的条目类型：**所有 Tab 恒定 4 个**。
 *
 * ⚠️ 验证码页的 [VaultixAddTarget.Totp] **没有**对应的 [VaultItemType]
 *    （验证码不是条目类型，走 `totpAddRequest`），早期版本因此返回 null、
 *    一格都不排除 ⇒ 用户反馈「验证码页 5 格、其余页 4 格」。
 *    ⇒ 三个分支各排除一个，Totp 排除 [VaultItemType.Login]（登录在密码页有主卡直达）。
 *
 * ⚠️ 不在面板里按库类型过滤：`ItemFormDialog` 已有 `fallbackFormType`，
 *    面板过滤反而要与库摘要加载时序做同步，是多余的第二个真源。
 */
private fun standaloneTypes(currentTab: VaultixNavItem): List<VaultItemType> {
    val excluded = when (currentTab.addTarget) {
        VaultixAddTarget.Login -> VaultItemType.Login
        VaultixAddTarget.Card -> VaultItemType.Card
        VaultixAddTarget.Totp -> VaultItemType.Login
    }
    return VaultItemType.entries.filter { it != excluded }
}

/** 主卡：当前 Tab 的默认新建动作，全屏唯一的主色面。 */
@Composable
private fun AddHeroCard(
    label: String,
    type: VaultItemType,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xl)
            .heightIn(min = HERO_MIN_HEIGHT),
        shape = VaultixCorner.Panel,
        color = MaterialTheme.colorScheme.primary,
        contentColor = onPrimary,
    ) {
        Box {
            // 类型剪影水印（[TypeGlyph] 的第二处复用）：与网格卡**同一个形状本体**，
            // 放大 [HERO_GLYPH_SCALE] 倍、贴右（缩放后靠 Surface 的 shape 裁出出血）、
            // 整体压到 [HERO_GLYPH_ALPHA]。
            //
            // ⚠️ 必须写在 Row **之前**：后写的在上层，这样文字与「+」不会被水印盖住。
            // ⚠️ [HERO_GLYPH_BOX] 必须 **≤ 卡片最小高度**：同在一个 Box 里会按最大
            //    子元素测量，水印画布一旦更高就会把整张卡撑高（库列表的水印踩过同一个坑）。
            TypeGlyph(
                type = type,
                accent = onPrimary,
                line = onPrimary.copy(alpha = HERO_GLYPH_LINE_ALPHA),
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(HERO_GLYPH_BOX)
                    .graphicsLayer {
                        alpha = HERO_GLYPH_ALPHA
                        scaleX = HERO_GLYPH_SCALE
                        scaleY = HERO_GLYPH_SCALE
                    },
            )
            Row(
                modifier = Modifier.padding(Spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(VaultixCorner.Panel)
                        .background(onPrimary.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(Modifier.width(Spacing.md))
                // 2026-11-10：删掉副标题「当前页面的默认新建动作」。
                // ① 它是**解释功能**的话，不是内容 —— 用户看一眼卡片就知道是"新建登录"；
                // ② 它让主卡比下面每张类型卡都高，一屏里出现两套高度节奏。
                // 删字后主卡收到 [HERO_MIN_HEIGHT]（= 图标 40 + 上下各 16），
                // 与末尾的生成器卡同高，形成「72 / 104×2 / 72」的对称节奏。
                Text(text = label, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/**
 * 主卡的尺寸与剪影水印参数。
 *
 * ⚠️ [HERO_GLYPH_BOX]（72dp）**等于** [HERO_MIN_HEIGHT]（72dp）是刻意的：
 *    水印和内容同在一个 `Box` 里，Box 按最大子元素测量 —— 水印画布更高就会把卡撑高。
 *    72dp 画布经 [HERO_GLYPH_SCALE] 放大后的**可见**尺寸约 104dp，已经足够铺满右侧。
 *
 * 📐 2026-11-10：删除副标题后 76 → **72**（= 图标 40 + `Spacing.lg` ×2）。
 *    与末尾的生成器卡同高，一屏里因此只有两种高度：通栏动作卡 72 / 类型网格卡 104。
 */
private val HERO_MIN_HEIGHT = 72.dp
private val HERO_GLYPH_BOX = 72.dp
private const val HERO_GLYPH_SCALE = 1.45f
private const val HERO_GLYPH_ALPHA = 0.13f
private const val HERO_GLYPH_LINE_ALPHA = 0.55f

/** 2 列网格；末行只有一格时用 Spacer 补位，保持两列对齐。 */
@Composable
private fun AddTypeGrid(
    types: List<VaultItemType>,
    onPick: (VaultItemType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        types.chunked(2).forEach { rowTypes ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                rowTypes.forEach { type ->
                    AddTypeTile(
                        type = type,
                        onPick = { onPick(type) },
                        modifier = Modifier.weight(1f),
                    )
                }
                if (rowTypes.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * 单个类型卡：彩色字段剪影 + 类型名。
 *
 * ⚠️ #285：卡底画**纯** [GlyphPalette.container]（fixed 角色，浅深同值），
 *    `contentColor` 配 [GlyphPalette.onContainer]。不再 `isDarkSurface` 判档、
 *    不再叠 `TILE_ALPHA_*` —— 那套正是「奇怪颜色」的来源（见文件头第十轮）。
 * ⚠️ 保留 #283 的「容器底配 on-container 前景」结论，只是角色从 `xxxContainer` 换成
 *    `xxxFixed`：拿 `onSurface` 顶替容器底依旧是错的。
 */
@Composable
private fun AddTypeTile(
    type: VaultItemType,
    onPick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = glyphPalette(type)
    Surface(
        onClick = onPick,
        modifier = modifier.heightIn(min = 104.dp),
        shape = VaultixCorner.Panel,
        color = palette.container,
        contentColor = palette.onContainer,
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            ItemFieldGlyph(
                type = type,
                palette = palette,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp),
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = stringResource(itemTypeLabelRes(type)),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/**
 * 生成器：与类型卡**同构**的通栏卡（剪影在上、文字在下），
 * 只是剪影是长条状 —— 靠形状而不是分隔线区分「工具」层级。
 *
 * 📐 2026-11-10：删掉副标题后高度收到 [GENERATOR_MIN_HEIGHT]
 *    （= 剪影 20 + `Spacing.sm` + 标签 20 + `Spacing.md` ×2），与主卡同高。
 *
 * ⚠️ #285：类型卡走 `glyphPalette`，生成器是**单独一张**，直接取 tertiary fixed 家族
 *    （`tertiaryFixed` 底 + `onTertiaryFixed` / `onTertiaryFixedVariant` 前景），
 *    与类型卡的绿系呼应但不参与 5 型交叉表。同样不判深浅、不叠 alpha。
 */
@Composable
private fun AddGeneratorTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xl)
            .heightIn(min = GENERATOR_MIN_HEIGHT),
        shape = VaultixCorner.Panel,
        color = scheme.tertiaryFixed,
        contentColor = scheme.onTertiaryFixed,
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            GlyphGenerator(
                accent = scheme.onTertiaryFixed,
                line = scheme.onTertiaryFixedVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(GENERATOR_GLYPH_HEIGHT),
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = stringResource(R.string.dock_add_generate_password),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/**
 * 生成器卡的高度与剪影高度。
 *
 * 两者必须**一起改**：`GENERATOR_MIN_HEIGHT` = 剪影 + `Spacing.sm` + 一行 `labelLarge`
 * + 上下 `Spacing.md` ×2。只改剪影不改高度会在卡底留一条看不见的空白（`heightIn`
 * 不会自动收紧），只改高度不改剪影则内容会顶出裁切。
 *
 * ⚠️ 为什么不跟类型卡一样取 104dp：类型卡里画的是"整张条目长什么样"（需要高度），
 *    这里画的是一串字符块（不需要）；强行拉齐只会让底部多出一块空。
 *
 * ⚠️ 声明顺序受 `.ai/tools/check_compile_smells.py` 的规则 B 约束：
 *    [LABEL_LINE_HEIGHT] / [GENERATOR_GLYPH_HEIGHT] 必须在 [GENERATOR_MIN_HEIGHT] 之前。
 */
private val GENERATOR_GLYPH_HEIGHT = 20.dp

/** 一行 `labelLarge` 的行高（M3 规范：14sp 字号 / 20sp 行高）。 */
private val LABEL_LINE_HEIGHT = 20.dp

private val GENERATOR_MIN_HEIGHT =
    GENERATOR_GLYPH_HEIGHT + Spacing.sm + LABEL_LINE_HEIGHT + Spacing.md * 2

/**
 * 条目类型的**字段剪影**：不画抽象图标，画这一类型条目长什么样。
 *
 * 银行卡画磁条 + 四位点、安全笔画三行文本、身份画头像 + 两行、SSH 画等宽字符块、
 * 登录画站点块 + 两行。全部用 Compose 基础形状绘制，**不新增任何 vector 资源**。
 *
 * ⚠️ #285：内层小卡背景画**纯** [GlyphPalette.wash]（与卡底同色系的另一档 fixed），
 *    重点形状用 [GlyphPalette.onContainer]、线条用 [GlyphPalette.onContainerVariant]。
 *    不再接 `dark: Boolean`、不再 `onSurfaceVariant × 0.34` / `onWash × 0.62` 分深浅——
 *    fixed 角色浅深同值，一套配对走到底。
 */
@Composable
private fun ItemFieldGlyph(
    type: VaultItemType,
    palette: GlyphPalette,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(palette.wash)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        TypeGlyph(
            type = type,
            accent = palette.onContainer,
            line = palette.onContainerVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 类型剪影的**形状本体**，不带任何容器。
 *
 * ⚠️ 两处复用：[ItemFieldGlyph]（网格卡内的剪影小卡）与 [AddHeroCard]（主卡的出血水印）。
 *    同源是「搭配」的前提 —— 8.4 记着「一屏两套规格」是观感廉价的首要来源。
 *    2026-11-10 00:18 用户反馈主卡「比较素，与下面的剪影卡片风格要能搭配」，
 *    当时主卡只有一枚 Material「+」图标，与网格的抽象剪影是两套语言。
 * ⚠️ #285：形状本体不关心颜色，[accent] / [line] 全由调用方给 —— 网格卡给 fixed
 *    家族的 `onXxxFixed` / `onXxxFixedVariant`，主卡水印给 `onPrimary`。
 */
@Composable
private fun TypeGlyph(
    type: VaultItemType,
    accent: Color,
    line: Color,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        when (type) {
            VaultItemType.Login -> GlyphLogin(accent = accent, line = line)
            VaultItemType.SecureNote -> GlyphNote(accent = accent, line = line)
            VaultItemType.Card -> GlyphCard(accent = accent, line = line)
            VaultItemType.Identity -> GlyphIdentity(accent = accent, line = line)
            VaultItemType.SshKey -> GlyphSsh(accent = accent, line = line)
        }
    }
}

/** 剪影里的一条「文字」：固定高 3dp，宽度由调用方给，模拟字段行的长短。 */
@Composable
private fun GlyphLine(width: Dp, color: Color) {
    Box(
        modifier = Modifier
            .width(width)
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(color),
    )
}

/** 生成器剪影：一排等宽字符块，读作「刚生成的一串密码」。 */
@Composable
private fun GlyphGenerator(accent: Color, line: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = 0.14f))
            .padding(horizontal = Spacing.sm),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 命名参数：detekt MagicNumber 只对未命名位置参数报错。
            repeat(times = 12) {
                Box(
                    modifier = Modifier
                        .width(6.dp)
                        .height(10.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(accent),
                )
            }
            GlyphLine(width = 14.dp, color = line)
        }
    }
}

@Composable
private fun GlyphLogin(accent: Color, line: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(accent),
        )
        Spacer(Modifier.width(Spacing.sm))
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            GlyphLine(width = 48.dp, color = line)
            GlyphLine(width = 32.dp, color = line)
        }
    }
}

/**
 * 安全便签：三行文本剪影。**#282 起第一行用 accent 画一枚短色块**（便签标签），
 * 之前三行全是灰线，这整张卡一点彩色都没有，是「偏单调」最明显的一格。
 */
@Composable
private fun GlyphNote(accent: Color, line: Color) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        GlyphLine(width = 20.dp, color = accent)
        GlyphLine(width = 52.dp, color = line)
        GlyphLine(width = 38.dp, color = line)
    }
}

@Composable
private fun GlyphCard(accent: Color, line: Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(5.dp))
            .background(accent.copy(alpha = 0.16f))
            .padding(Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .background(accent),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(times = 4) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(line),
                )
            }
        }
    }
}

@Composable
private fun GlyphIdentity(accent: Color, line: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(15.dp)
                .clip(VaultixCorner.Pill)
                .background(accent),
        )
        Spacer(Modifier.width(Spacing.sm))
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            GlyphLine(width = 46.dp, color = line)
            GlyphLine(width = 30.dp, color = line)
        }
    }
}

@Composable
private fun GlyphSsh(accent: Color, line: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(accent),
        )
        GlyphLine(width = 18.dp, color = line)
        GlyphLine(width = 12.dp, color = line)
        GlyphLine(width = 16.dp, color = line)
    }
}

/**
 * 独立生成器（不在条目表单里）。复用现有 `PasswordGeneratorDialog`，
 * 「使用」在没有表单可回填时改为**复制到剪贴板**并给一次可见反馈。
 *
 * ⚠️ 用 Android 原生 ClipboardManager / Toast，不引入任何 Compose alpha API；
 *    生成逻辑本身一行未改，仍是表单里那一份。
 */
@Composable
fun StandalonePasswordGeneratorDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    PasswordGeneratorDialog(
        onUse = { generated ->
            val clipboard =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Vaultix password", generated))
            Toast.makeText(
                context,
                R.string.dock_add_password_copied,
                Toast.LENGTH_SHORT,
            ).show()
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}
