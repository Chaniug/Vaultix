/*
 * Vaultix — app:ui:shell
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 窄屏底部导航条（悬浮胶囊 + 中央「+」按钮）。
 * 抽取自 Bastion 项目（GPL-3.0，Copyright 2025 JoyinJoester）的
 * ui/SimpleMainScreen.kt 内联实现（原为 bottomBar lambda 内约 110 行）：
 *   - 悬浮胶囊规格：RoundedCornerShape(50) + surfaceContainerHigh + tonal3/shadow6，
 *     height(60dp)，外层留白 start/end=12dp, top=6dp, bottom=20dp（总高 82dp）
 *   - 布局：左 2 Tab + 中间「+」+ 右 2 Tab（各 weight(1f) 均分）
 *   - 中央「+」：圆角方块（**非 FAB**）RoundedCornerShape(16dp) + primary，
 *     52×48dp，图标 26dp；行为随当前 Tab 变化（对齐 Bastion `when(currentTab)`）
 *
 * 同时**原样保留** Bastion 在该处沉淀的两条踩坑注释（见 [DockTabItem] 与
 * [VaultixBottomDock] 的 KDoc）——这两条是「点一个 tab 旁边跟着闪」的真实根因，
 * 简单化改写会重新引入该缺陷。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.glass.LiquidGlass
import io.vaultix.vaultix.ui.glass.LiquidGlassLayer
import io.vaultix.vaultix.ui.glass.LiquidGlassSource
import io.vaultix.vaultix.ui.glass.isDarkSurface
import io.vaultix.vaultix.ui.glass.liquidGlassStyle
import io.vaultix.vaultix.ui.glass.shouldApplyBlur
import io.vaultix.vaultix.ui.theme.UiSize
import io.vaultix.vaultix.ui.theme.VaultixCorner
import io.vaultix.vaultix.ui.theme.Spacing

/** 悬浮胶囊底栏固定高度（对齐 Bastion：胶囊 60dp + 留白 6/20 = 86dp 视觉占位）。 */
private val DockBarHeight = 60.dp

/**
 * 悬浮胶囊底栏的**总视觉占位高度**（胶囊 60 + 上留白 6 + 下留白 20 = 86dp）。
 *
 * 底栏改成**叠层悬浮**后（见 [AdaptiveMainScaffold]），内容会一直铺到屏幕底 ——
 * 好处是胶囊周围那圈留白能透出内容（酷安观感），代价是最后一条会被胶囊压住，
 * 所以各页列表必须把它算进 `contentPadding` 的底部。
 *
 * ⚠️ 2026-10-19：**这个常量只对默认字号成立**。2.0x 字号下 tab 的文字会把胶囊撑高
 * （见 [DockTabItem] 的 `heightIn`），此时真实占位比 86dp 大。所以：
 * - **列表让位一律调 [rememberBottomDockInset]**（它会按当前字号补上增量）；
 * - 只有「需要在非组合环境里引用一个名义值」的地方（如 `SelectionActionBar` 的文档、
 *   预览里的示意留白）才直接用这个常量。
 */
val BottomDockOccupiedHeight: Dp = DockBarHeight + UiSize.TightGap + UiSize.SecondaryIcon

/**
 * 2.0x 字号下 tab 文字把胶囊撑高的**增量上限**。
 *
 * 依据：tab 文字 12sp → 2.0x 时 24sp，行高约 32sp ≈ **16dp 增量**（12sp 行高约 16dp）。
 * 取 16dp 而不是精确测量：这里是**让位**用的保守值，宁可多留 16dp 的空白，
 * 也不能少留导致最后一条被压住 —— 多留的空白在默认字号下是透明的，看不出来。
 */
private val DOCK_MAX_FONT_GROWTH = 16.dp

/**
 * 列表底部应留出的高度 = 胶囊底栏占位 + **字体放大增量** + 系统手势条高度。
 *
 * 手势条高度随机型 / 横竖屏变化，故不做成常量。
 *
 * ⚠️ 2026-10-19：补上字体增量。改前只算 86dp + 手势条，2.0x 字号下胶囊被 tab 文字撑高
 * ⇒ 最后一条列表项仍然会被压住一截（审计 §3.3）。增量按 [LocalDensity] 的 `fontScale`
 * 线性插值：1.0x → 0，2.0x → [DOCK_MAX_FONT_GROWTH]，中间档按比例。
 */
@Composable
fun rememberBottomDockInset(): Dp {
    val fontScale = LocalDensity.current.fontScale
    // 只对"超过 1.0 的部分"补偿；用户把字号调小（<1.0）时不多留空白。
    val growth = DOCK_MAX_FONT_GROWTH * ((fontScale - 1f) / 1f).coerceIn(0f, 1f)
    return BottomDockOccupiedHeight +
        growth +
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
}

/**
 * 窄屏底部导航条：悬浮胶囊 + 中央「+」。
 *
 * ## ⚠️ 为什么「+」是圆角方块而不是 FAB
 * 对齐 Bastion 实测样式（酷安规格）：胶囊内中央一块接近撑满高度的主色圆角方形，
 * 视觉上与两侧 tab 同层，而非悬浮于其上的 FAB。**勿改成 FloatingActionButton。**
 *
 * ## ⚠️ 为什么 tab 项必须用 [key] 包裹
 * 见 [DockTabItem] KDoc —— 嵌套函数 + 无 key 会导致组合身份不稳定，
 * 表现为「点一个 tab、旁边那个 pill 也跟着闪」。
 *
 * @param tabs 数据 tab（**不含设置**；设置由调用方决定是否固定在末尾）。
 * @param selected 当前选中项。
 * @param onSelect 切换 tab。
 * @param onAdd 中央「+」点击；入参为当前 Tab，由宿主按 [VaultixNavItem.addTarget] 分发。
 */
@Composable
fun VaultixBottomDock(
    tabs: List<VaultixNavItem>,
    selected: VaultixNavItem,
    onSelect: (VaultixNavItem) -> Unit,
    onAdd: (VaultixNavItem) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 液态玻璃底栏（设置页「主题模式 → 液态玻璃底栏」，默认关）。
     *
     * 开启后胶囊改为**半透明 + 实时模糊其后的内容**，而不是 `surfaceContainerHigh` 实色。
     * 参数放在末尾且给默认值，是为了让既有调用点与预览一行都不改。
     */
    liquidGlass: Boolean = false,
    /**
     * 液态玻璃的**背景源**（2026-10-27 第二轮）。由 [MainShellScreen] 挂在 Tab 内容区上，
     * 这里只负责采样。
     *
     * ⚠️ 为 null 时退化成"一块纯色玻璃"（不糊任何东西）—— 预览 / 单测环境就是这种情况。
     * **不要**因此把模糊挂回自己身上：那正是用户截图里"图标全糊"的那个 bug。
     */
    glassSource: LiquidGlassSource? = null,
) {
    // 外层留白：bottom 20dp 让胶囊明显抬离系统手势条，底部透出内容（酷安观感）。
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Spacing.md, end = Spacing.md, top = UiSize.TightGap, bottom = UiSize.SecondaryIcon)
    ) {
        if (liquidGlass) {
            // ★ 液态玻璃分支（2026-10-27 第二轮重做）。
            //
            // 🔴 第一版错在哪：把 `graphicsLayer { renderEffect = 模糊 }` 直接挂在本层，
            //    RenderEffect 会糊掉**本图层的一切**，包括 [DockRow] 里的图标与文字
            //    ⇒ 真机上整条胶囊连同按钮一起变成灰雾（用户截图）。
            //    现在模糊只作用于"采样 [glassSource] 的那一层"，DockRow 在**另一层**。
            // ⚠️ 这里**不能**继续用 Surface：会给胶囊铺一层不透明底色，
            //    模糊就被那块纯色盖掉了（见 ui/glass/LiquidGlass.kt 的顺序说明）。
            //    阴影只能靠外层 modifiers —— Surface 的 shadowElevation 会随实色一起丢掉。
            // ⚠️ 判深浅**不能看 `isSystemInDarkTheme()`** —— 本 App 有自己的「主题模式」
            //   （浅色 / 深色 / 跟随系统 + 动态取色 + OLED 纯黑）。App 设成深色、系统是浅色时，
            //   按系统判会把填充取成"浅色档"（更实），玻璃就不再透。
            //   取实际渲染出来的底色亮度最可靠（与 `FullScreenDialogShell` 的做法一致）。
            val surface = MaterialTheme.colorScheme.surfaceContainerHigh
            val blurAvailable = shouldApplyBlur(requested = true)
            LiquidGlassLayer(
                shape = VaultixCorner.Pill,
                enabled = true,
                source = glassSource,
                style = liquidGlassStyle(
                    darkTheme = isDarkSurface(surface),
                    surface = surface,
                    onSurface = MaterialTheme.colorScheme.onSurface,
                    blurAvailable = blurAvailable,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(elevation = LiquidGlass.ShadowElevation, shape = VaultixCorner.Pill),
            ) {
                DockRow(tabs = tabs, selected = selected, onSelect = onSelect, onAdd = onAdd)
            }
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = VaultixCorner.Pill,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 3.dp,
                shadowElevation = 6.dp,
            ) {
                DockRow(tabs = tabs, selected = selected, onSelect = onSelect, onAdd = onAdd)
            }
        }
    }
}

/**
 * 胶囊内的 tab 行（两种外观共用）。
 *
 * 抽出来是为了让"实色"与"液态玻璃"两条分支**共用同一份布局**：
 * 否则以后调 tab 间距 / 中央「+」尺寸时只会改一处，
 * 而另一条分支会静默停在旧规格（本项目已因"同一规则写两处"栽过三次，#120/#193/#195）。
 */
@Composable
private fun DockRow(
    tabs: List<VaultixNavItem>,
    selected: VaultixNavItem,
    onSelect: (VaultixNavItem) -> Unit,
    onAdd: (VaultixNavItem) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(DockBarHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 布局：左 2 个 + 中间「+」+ 右 2 个（不足则用 Spacer 占位保持「+」居中）。
        val leftTabs = tabs.take(2)
        val rightTabs = tabs.drop(2).take(2)

        leftTabs.forEach { item ->
            key(item) {
                DockTabItem(
                    item = item,
                    isSelected = item == selected,
                    onClick = { onSelect(item) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        repeat(2 - leftTabs.size) { Spacer(modifier = Modifier.weight(1f)) }

        // 中央「+」：独立函数无法访问 RowScope.weight，故此处显式传 weight 修饰符。
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            AddButton(currentTab = selected, onAdd = onAdd)
        }

        rightTabs.forEach { item ->
            key(item) {
                DockTabItem(
                    item = item,
                    isSelected = item == selected,
                    onClick = { onSelect(item) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        repeat(2 - rightTabs.size) { Spacer(modifier = Modifier.weight(1f)) }
    }
}

/** 中央「+」：主色圆角方形（52×48dp，圆角 16dp，图标 26dp）。 */
@Composable
private fun AddButton(
    currentTab: VaultixNavItem,
    onAdd: (VaultixNavItem) -> Unit,
) {
    val addLabel = stringResource(R.string.nav_add)
    Surface(
        onClick = { onAdd(currentTab) },
        shape = VaultixCorner.Panel,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier
            .width(52.dp)
            .height(48.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = addLabel,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

/**
 * 底栏单个 tab 项（悬浮胶囊内的图标 + 文字）。
 *
 * ## ⚠️ 必须是【顶层】composable
 * 不能定义在 `bottomBar` / 其他 lambda 内部再经 forEach 调用 —— 嵌套函数在每次
 * 重组时会被重新定义，导致内部 `remember` / 动画的组合身份不稳定，点击一个 tab
 * 触发整行重组时动画状态会重启，表现为「**点一个、旁边那个 pill 也跟着闪**」。
 * 同时调用处必须用 `key(item.key)` 包裹，保证每个 tab 有稳定的组合槽。
 *
 * ## ⚠️ 选中色必须【瞬切】，不可用 animateColorAsState
 * 此前 Bastion 用 `animateColorAsState(tween(200))`，点击新 tab 时「新 pill 渐现」
 * 与「旧 pill 渐隐」两个动画并行 200ms，期间两个 pill 同时处于中间色，用户感知
 * 就是「**点一个、旁边那个也跟着闪**」。且下方 contentTint 本就是瞬间切换，
 * 背景若走渐变会与文字色不同步、更显抖动。改为瞬切后选中态与文字色严格同步。
 */
@Composable
private fun DockTabItem(
    item: VaultixNavItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(item.labelRes)
    // 选中色块包住「图标+文字」整体（酷安样式，圆角矩形非细长条）。瞬切，无动画 —— 见 KDoc。
    val pillColor = if (isSelected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        Color.Transparent
    }
    val contentTint = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(VaultixCorner.Panel)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                // ★ 2026-10-19：自绘 tab 必须**自己声明语义**。
                //   这是导航不是按钮 —— 读屏要报「密码，标签页，已选中」
                //   而不是「密码，按钮」。缺 `role` + `selected` 时 TalkBack 完全读不出
                //   "我现在在哪一页"，这对 4 个 tab 的底栏是**功能缺失**，不是观感问题。
                //   `role` 让读屏按"标签页"播报；`selected` 提供选中态；
                //   `onClickLabel` 与 `CollectionInfo` 由 `selected` 自动推导，无需手写。
                role = Role.Tab,
                onClick = { onClick() },
            )
            .semantics {
                // ⚠️ 必须写显式 `selected`：`clickable` 的 `role` 只影响播报措辞，
                //    **不会**自动带上选中态 —— 那句"已选中"完全来自这里。
                this.selected = isSelected
                // 图标与文字都会被子节点并入；给整项一个稳定名字，避免读到两遍。
                contentDescription = label
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 56.dp)
                // ⚠️ 2026-10-19：`height(48.dp)` → `heightIn(min = 48.dp)`。
                //    固定高在 2.0x 字体下会把「22dp 图标 + 12sp 文字」裁掉下半截；
                //    `heightIn` 保 48dp 的下限（触摸目标不缩），但允许它随字号长高。
                //    代价是 60dp 的胶囊可能被撑高 —— 这是**对的**取舍：宁可底栏变高，
                //    也不能让 tab 文字被裁成半个字（见 .ai/conventions/8.4 §字体缩放）。
                .heightIn(min = 48.dp)
                .clip(VaultixCorner.Panel)
                .background(pillColor),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = item.icon,
                // 名字已在 Box 层声明，图标不再重复播报。
                contentDescription = null,
                tint = contentTint,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = contentTint,
            )
        }
    }
}
