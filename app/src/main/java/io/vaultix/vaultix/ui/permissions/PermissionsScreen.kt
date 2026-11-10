/*
 * Vaultix — app:ui · permissions
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 信息架构（对齐 Bastion「设置 → 权限」二级页 + Vaultix 自己的「自动填充」二级页）
 *
 * 结构照抄 `AutofillSettingsScreen`：**顶部三态状态卡**（未就绪 errorContainer /
 * 需注意 tertiaryContainer / 全部就绪 primaryContainer）+ 按用途分组的设置卡片。
 * 「检测 → 状态卡 → 分组引导」这条链路在自动填充页已被验证过（用户能一眼看出
 * "到底缺不缺、缺什么"），权限页是同一类问题，**不另起一套规格**
 * （8.4：一屏里出现两套规格 = 观感廉价的首要来源）。
 *
 * 未搬运 Bastion 的部分：它的权限页带「一键跳转各厂商自启管理」白名单
 * （针对国产 ROM 的后台存活优化）。Vaultix 没有后台常驻需求（无同步守护进程），
 * 搬过来会变成一组**点了也没用的入口** —— 宁可没有。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.permissions

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.vaultix.vaultix.R
import io.vaultix.vaultix.ui.settings.SettingsDivider
import io.vaultix.vaultix.ui.settings.SettingsGroupCard
import io.vaultix.vaultix.ui.settings.SettingsGroupTitle
import io.vaultix.vaultix.ui.theme.Spacing
import io.vaultix.vaultix.ui.theme.UiSize
import io.vaultix.vaultix.ui.theme.VaultixCorner
import io.vaultix.vaultix.util.SystemSettingsIntents

/**
 * 标题列的最小宽度（约 4 个汉字 @ `bodyLarge` 16sp）。
 *
 * 只用来兜住"标题被行尾挤成竖排"这一个底：正常宽度下行尾取宽不过 40dp 上下，
 * 这个下限永远碰不到；一旦真碰到（窄屏 + 2.0x 字号），宁可让行尾换到下一行。
 */
private val PERMISSION_TITLE_MIN_WIDTH = 88.dp

/** 用途说明的最大行数：与 `SettingsRow` 的副标题同口径（两行 + 省略号）。 */
private const val PERMISSION_PURPOSE_MAX_LINES = 2

/**
 * 行内水平内边距 / 图标格 / 图标间距。
 *
 * ⚠️ 三个值必须与 `SettingsComponents.kt` 的 `SETTINGS_ROW_PADDING_H`(20dp)、
 * `SETTINGS_ICON_BOX`(28dp)、`SETTINGS_ICON_GAP`(`UiSize.InlineIcon`) 逐字一致：
 * [SettingsDivider] 的缩进就是按这三者算的，差 1dp 分隔线就会与文字左缘错开。
 * 本页自绘行布局是为了让「状态与标题同行」，但**不另起一套尺寸规格**。
 */
private val PERMISSION_ROW_PADDING_H = 20.dp
private val PERMISSION_ICON_BOX = 28.dp
private val PERMISSION_ICON_GAP = UiSize.InlineIcon

/**
 * 权限引导二级页（设置首页「关于 → 权限管理」进入）。
 *
 * ## 这一页要回答的两个问题
 *
 * 1. **「这 App 要哪些权限、拿去干什么？」** —— 每项都有一句用途说明，
 *    并明确写出"不做什么"（相机不拍照不落盘、通知不带内容）。用户对密码管理器的
 *    权限是有戒心的，**不解释就等于心虚**；
 * 2. **「现在缺哪一项、怎么补？」** —— 顶部状态卡汇总"还有几项没就绪"，
 *    每项行尾给一个**能直接动作**的按钮（能就地弹授权框的就地弹，不能的跳系统页）。
 *
 * ## ⚠️ 状态必须每次回前台重算
 *
 * 用户从系统设置页返回时，`checkSelfPermission` 的结论已经变了，但 Compose 不会
 * 自己知道 —— 不重算就会停在旧状态（"我明明刚开了，这里还说没开"）。
 * 用 `DisposableEffect + ON_RESUME` 重算，与 `AutofillSettingsScreen` 同一套写法。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val checker = remember(context) { PermissionStatusChecker(context) }
    // ⚠️ 初值**每次进入组合都现算**（不用 `remember` 缓存）：本页的整个意义就是
    //    "反映此刻的系统状态"，缓存一个可能已经过期的结论只会骗人。
    //    `checker.entries()` 是幂等的只读系统查询，重算开销可忽略。
    var entries by remember { mutableStateOf(checker.entries()) }
    // 权限请求发起后，无论结果如何都要重算（用户可能只授予了其中一个）。
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { entries = checker.entries() }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                entries = checker.entries()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ★ 2026-11-05：状态卡改成三态 —— ① Denied = 用户能补的「待允许」；
    //   ② Unavailable = 硬件决定、补不了；③ 两者都为 0 才是「一切就绪」。
    //   ⚠️ 不能把 ② 混进 ①（单测 `待办计数只数 Denied 不数 Unavailable` 的口径）：
    //   那会让没有指纹的设备永远显示「有 1 项待允许」、永远到不了「一切就绪」。
    //   但只数 Denied 又会让「一切就绪」和一行「设备不支持」同屏 ⇒ 拆成两个计数。
    val pendingCount = entries.count { it.state == PermissionState.Denied }
    val unsupportedCount = entries.count { it.state == PermissionState.Unavailable }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.permission_management_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = Spacing.xxl),
        ) {
            // 上下各 `Spacing.md`：与 `AutofillStatusCard` 同一份边距，卡上方不再贴顶栏。
            PermissionStatusCard(
                pendingCount = pendingCount,
                unsupportedCount = unsupportedCount,
            )

            SettingsGroupTitle(stringResource(R.string.permission_group_needed))
            SettingsGroupCard {
                entries.forEachIndexed { index, entry ->
                    if (index > 0) SettingsDivider()
                    PermissionRow(
                        entry = entry,
                        onRequest = { permission -> launcher.launch(permission) },
                    )
                }
            }

            // 页脚：告诉用户「这一页只是索引，真正的开关在系统里」——
            // 避免有人以为页面上的按钮是"应用自带的开关"。
            Text(
                text = stringResource(R.string.permission_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
            )
        }
    }
}

/**
 * 顶部三态状态卡（与 `AutofillStatusCard` 同一规格：16dp 圆角 / 三态配色）。
 *
 * 「全部就绪」必须是**一个明确的好消息**（primaryContainer + 对勾），
 * 而不是什么都不显示 —— 用户点进"权限管理"就是想确认一句"没问题吧"，
 * 页面得正面回答他（8.4：永远给用户一个肯定的结果，别让他靠"没有红字"去推理）。
 *
 * 下方间距取 `Spacing.md`（12dp，与 `AutofillStatusCard` 一致），再交给
 * [SettingsGroupTitle] 自带的 `top = 16dp` —— 卡片到分组标题共 28dp。
 * 曾经两处都写 `Spacing.md`（12 + 16 = 28dp），改上下同值后仍是 28dp；
 * 这次只是把"卡上方 12dp"对齐自动填充页（原本是 0，卡贴着顶栏）。
 */
@Composable
private fun PermissionStatusCard(pendingCount: Int, unsupportedCount: Int) {
    val allGood = pendingCount == 0 && unsupportedCount == 0
    val hasPending = pendingCount > 0
    val container = if (allGood) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }
    val content = if (allGood) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onTertiaryContainer
    }
    // ⚠️ 2026-10-24：圆角 28dp → [VaultixCorner.Panel]（16dp），与紧挨着它的
    // [SettingsGroupCard] 同档。28dp 是"对话框级"的值，用在一个和分组卡并排的
    // 普通卡片上 ⇒ 两张卡左右边缘齐了、**圆角曲率不齐**，观感上仍是"两套规格"
    // （`VaultixCorner` KDoc：28dp 只留给 M3 对话框，不进"面"的三档）。
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        shape = VaultixCorner.Panel,
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(UiSize.RowVerticalPadding),
            ) {
                Icon(
                    imageVector = if (allGood) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = content,
                )
                // ⚠️ 早先标题用带 %1$d 的字符串却没传实参，权限没开时原样显示
                //    「有 %1$d 项待允许」（用户看到的"乱码"），而实参反倒错传给了
                //    不需要参数的副标题。现在两者各归各位。
                val titleRes = when {
                    allGood -> R.string.permission_status_all_good
                    hasPending -> R.string.permission_status_pending
                    else -> R.string.permission_status_unsupported
                }
                Text(
                    text = if (allGood) {
                        stringResource(titleRes)
                    } else {
                        stringResource(
                            titleRes,
                            if (hasPending) pendingCount else unsupportedCount,
                        )
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = content,
                )
            }
            Text(
                text = stringResource(
                    when {
                        allGood -> R.string.permission_status_all_good_desc
                        hasPending -> R.string.permission_status_pending_desc
                        else -> R.string.permission_status_unsupported_desc
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = content.copy(alpha = 0.9f),
            )
        }
    }
}

/**
 * 单个权限行：图标 + 标题 + 用途 + 右侧状态/动作。
 *
 * ## 2026-11-05 重做「设置字符没对齐」
 *
 * 之前复用 `SettingsRow` 的 `trailing` 槽，而那个槽**垂直居中于整行**：
 * 左列是「标题 + 两行副标题」，右列状态因此落在两行之间，看起来比标题低一截；
 * 再叠加 `TextButton` 自带 24dp 内边距 vs 裸 `Text` 贴边，两种行尾又各贴各的左缘。
 *
 * ⇒ 改成 **状态与标题同一行、用途说明在下一行**，右侧所有形态共用一个外框。
 * 状态于是与标题共享基线，四行的右列必然落在同一条竖线上。
 *
 * 三个尺寸常量刻意与 `SettingsRow` 取同值（见常量区 KDoc），好让 [SettingsDivider]
 * 的缩进正好落在文字左缘 —— 自绘行布局是为了改**对齐关系**，不是另起一套规格。
 *
 * 用途说明**不再截断成省略号**：它是这一页存在的理由，用两行半换一个「不…」不划算；
 * 页面本来就可滚动，四行高度不齐才是真的乱。
 *
 * 右侧**按状态与能力分三种**，不让用户猜：
 * - `Granted`（通知除外）→ 「已允许」陈述；通知项仍给「去设置」（总开关在系统里）；
 * - `Denied` → 可点动作：能就地弹系统授权框的写「授予」，否则写「去设置」；
 * - `Unavailable` → 「设备不支持」，**不给任何按钮**（点了也没用的按钮比没有更糟）。
 */
@Composable
private fun PermissionRow(
    entry: PermissionEntry,
    onRequest: (String) -> Unit,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PERMISSION_ROW_PADDING_H, vertical = Spacing.md),
        verticalAlignment = Alignment.Top,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.primary,
        ) {
            // 容器与标题行同高（48dp），图标在其中居中 ⇒ 图标与标题文字共享中线。
            Box(
                modifier = Modifier
                    .width(PERMISSION_ICON_BOX)
                    .height(UiSize.TouchTarget),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = iconFor(entry.id),
                    contentDescription = null,
                    modifier = Modifier.size(PERMISSION_ICON_BOX),
                )
            }
        }
        Spacer(Modifier.width(PERMISSION_ICON_GAP))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(entry.titleRes),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    // ⚠️ 2026-11-10：`widthIn(min = …)` 是防"竖排"的最后一道保险。
                    //   标题列理论上还有 weight(1f) 的剩余宽度，但行尾宽度会随**文案**
                    //   变化（「去设置」3 字 / 「设备不支持」5 字），窄屏 + 大字号下
                    //   仍可能被压到 1 字/行。给一个约 4 个汉字的宽度下限，宁可让行尾
                    //   换到下一行，也不要标题竖着写。
                    modifier = Modifier
                        .weight(1f)
                        .widthIn(min = PERMISSION_TITLE_MIN_WIDTH),
                )
                PermissionTrailing(
                    entry = entry,
                    onRequest = onRequest,
                    onOpenSettings = { openSystemPageFor(context, entry.id) },
                )
            }
            Text(
                text = stringResource(entry.purposeRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // ⚠️ 2026-11-10：与 SettingsRow 的副标题同口径（两行 + 省略号）。
                //   不限行数时，「网络访问」那条 34 字的说明会在这张卡里独占三行，
                //   四行的高度差到 60dp，分组感被拉散。
                maxLines = PERMISSION_PURPOSE_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = Spacing.xs, end = Spacing.sm),
            )
        }
    }
}

/**
 * 行尾的动作 / 状态区。
 *
 * 「通知」这一项有**两层开关**（运行时权限 + 系统通知总开关），所以已授予时
 * 也仍然给一个「去设置」入口 —— 只有那里能看到总开关是否被关掉
 * （权限是 GRANTED 但通知发不出来，是最容易让人困惑的一种状态）。
 *
 * ## ⚠️ 2026-11-05 二次修「没对齐」
 *
 * 2026-10-24 那轮把行尾装进同一个 `TrailingSlot`，但**保留**了 `TextButton`：
 * 它自带 24dp 内边距，而裸 `Text` 没有 ⇒ 两种行尾的文字左边缘仍差一截，
 * 整行垂直居中又让状态比标题低一截。真机截图里红框圈出的正是这一列。
 *
 * ⇒ 这次彻底统一：**不再用 `TextButton`**，可点项改成 `Box + clickable`，
 * 与纯文本标签共用同一套 `labelLarge`、同一条右边线、同一个 48dp 触控框。
 * 配合 [PermissionRow] 把状态提到标题行，水平与垂直两条轴线一起对齐。
 */
@Composable
private fun PermissionTrailing(
    entry: PermissionEntry,
    onRequest: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    TrailingSlot {
        when (entry.state) {
            PermissionState.Granted -> if (entry.id == PermissionIds.NOTIFICATIONS) {
                // 唯一需要"去看总开关"的已授予项。
                TrailingAction(
                    text = stringResource(R.string.permission_action_settings),
                    onClick = onOpenSettings,
                )
            } else {
                TrailingLabel(
                    text = stringResource(R.string.permission_state_granted),
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            PermissionState.Denied -> TrailingAction(
                text = stringResource(
                    if (entry.canRequestInApp) {
                        R.string.permission_action_grant
                    } else {
                        R.string.permission_action_settings
                    },
                ),
                onClick = {
                    val permission = entry.runtimePermission
                    if (permission != null) onRequest(permission) else onOpenSettings()
                },
            )

            PermissionState.Unavailable -> TrailingLabel(
                text = stringResource(R.string.permission_state_unavailable),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 行尾内容的统一外框：**48dp 触控高 + 右对齐 + 统一内边距 + 按内容取宽**。
 *
 * ## ⚠️ 2026-11-10 去掉了「固定最小宽度 112dp」（用户报告：「通知」二字竖排）
 *
 * 旧实现给外框写死 `widthIn(min = TRAILING_MIN_WIDTH)`(112dp)，本意是让四行的行尾
 * 落在同一条右边线上。但**行尾是硬约束、标题是软约束**：一行里放不下时，Compose
 * 会先压缩 `weight(1f)` 的那一列（标题 + 副标题），而不是撑宽卡片。于是：
 *
 * | 行 | 标题 | 行尾 | 中间剩给标题的宽度 |
 * |---|---|---|---|
 * | 相机 | 相机（2 字） | 已允许 | 约 185dp，够 |
 * | **通知** | **通知（2 字）** | **去设置** | **112 + 内边距把 2 个字挤到 1 字/行** |
 * | 指纹 / 设备密码 | 8 字 | 设备不支持 | 已经折成 2 行，看不出是"被挤的" |
 *
 * 也就是说 112dp 对一个 2 字标题来说**占了整行宽度的三分之一**，而它的收益
 * 只是"右边线齐"这件在灰度上根本看不出来的事。**为了对齐而牺牲可读性，是本末倒置。**
 *
 * ⇒ 改为 `wrapContentWidth(Alignment.End)`：行尾按自己的文字取宽（「已允许」30dp、
 *    「去设置」42dp），右边线仍然靠 `contentAlignment = End` 对齐到卡片内边距，
 *    "齐"的效果不变，标题却凭空多出 70dp。
 *
 * ⚠️ 别再用 `widthIn(min = …)` 把它加回来，也**不要**改成 `fillMaxWidth` ——
 *    后者会把 `TrailingAction` 的点击热区扩满整行宽度，点标题也会误触「去设置」。
 */
@Composable
private fun TrailingSlot(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .wrapContentWidth(Alignment.End)
            .heightIn(min = UiSize.TouchTarget)
            .padding(horizontal = Spacing.md),
        contentAlignment = Alignment.CenterEnd,
    ) {
        content()
    }
}

/**
 * 可点的行尾动作：与 [TrailingLabel] 同一套字号。
 *
 * ⚠️ 2026-11-10：**不要**加 `fillMaxWidth()`。旧版有它，于是「去设置」的热区横跨
 * 整行剩余宽度 —— 点标题那一侧也会触发跳转；外框去掉固定宽度之后，这个副作用的
 * 面积只会更大。热区由文字 + 外框内边距构成，48dp 高由 [TrailingSlot] 保证。
 */
@Composable
private fun TrailingAction(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .wrapContentWidth()
            .heightIn(min = UiSize.TouchTarget)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
    }
}

/** 纯文本状态（与可点动作同一套字号/字重，避免一屏两种规格）。 */
@Composable
private fun TrailingLabel(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        maxLines = 1,
    )
}

/** 「未授予」时该跳哪个系统页（每项各不相同：相机/通知跳各自设置，指纹跳录入页）。 */
private fun openSystemPageFor(context: Context, id: String) {
    when (id) {
        PermissionIds.CAMERA -> SystemSettingsIntents.openAppDetails(context)
        PermissionIds.NOTIFICATIONS -> SystemSettingsIntents.openAppNotificationSettings(context)
        PermissionIds.BIOMETRICS -> SystemSettingsIntents.openBiometricEnroll(context)
        else -> SystemSettingsIntents.openAppDetails(context)
    }
}

/** 每项各自的图标（与行文语义对齐，不用一个通用盾牌糊弄所有行）。 */
private fun iconFor(id: String): ImageVector = when (id) {
    PermissionIds.CAMERA -> Icons.Filled.PhotoCamera
    PermissionIds.NOTIFICATIONS -> Icons.Filled.Notifications
    PermissionIds.BIOMETRICS -> Icons.Filled.Fingerprint
    PermissionIds.NETWORK -> Icons.Filled.Language
    else -> Icons.Filled.CheckCircle
}
