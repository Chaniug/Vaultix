/*
 * Vaultix — app:ui:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.vaultix.vaultix.R

/**
 * **全屏编辑壳**：顶部标题行（含关闭）+ 可滚动正文 + 底部固定操作条。
 *
 * ⚠️ 2026-09-13 第二轮用户反馈：「编辑条目 / 验证码条目不是全屏显示，看起来不舒服。」
 *
 * 这两个编辑界面原本都是 `AlertDialog` —— 居中卡片、宽度被平台硬限制在 ~280dp，
 * 而条目编辑表单有「身份 17 字段 + 自定义字段」，塞进那个宽度里必然局促、必须蜷着滚动。
 *
 * 现在统一成**整页**：`Dialog(usePlatformDefaultWidth = false)` + 全屏 `Surface`。
 * 为什么不直接用一条路由去承载：编辑是**模态**的（它必须阻断「编辑中又被别处改掉」），
 * 走路由就要自己在导航栈上模拟模态语义；`Dialog` 天然就是模态，且不改动导航图。
 *
 * 三个区域的分工：
 * - 顶部：标题 + 关闭（关闭 = 取消，语义与「返回」一致）；
 * - 中部：`weight(1f)` + `verticalScroll` —— 表单再长也不会把按钮顶出屏幕，
 *   且 `imePadding()` 在壳上统一处理，键盘弹出时输入框不会被遮；
 * - 底部：**固定**的取消 / 确认（不随表单滚动，长表单也能一键保存）。
 *
 * @param onDismiss 关闭（顶部 ✕ / 系统返回 / 点外部）——调用方需自行处理「保存中不许关」。
 * @param onConfirm 底部主操作；[confirmEnabled] 为 `false` 时置灰（例如保存中）。
 * @param content 表单正文（[ColumnScope]，纵向排列；需要横排时内部自己套 `Row`）。
 */
@Composable
fun FullScreenDialogShell(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    confirmLabel: String,
    confirmEnabled: Boolean,
    /** 破坏性操作（如验证码编辑里的「移除」）；为 `null` 时不画，排在「取消」左侧。 */
    destructive: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val view = LocalView.current
    // ⚠️ 判「状态栏图标该用深色还是浅色」**不能看系统深色模式**，要看**我们实际画出来的底色**。
    // 2026-09-13 第三轮用户反馈：「状态栏沉浸的时候，**浅色模式下**状态栏的显示效果才有问题」
    // ——上一版我用的是 `isSystemInDarkTheme()`，而本 App 有**自己的主题设置**（浅色/深色/跟随系统
    // + OLED 纯黑 + 动态取色）：App 设成浅色、系统是深色时，图标被判成"浅色"，白图标压在白底上
    // 就是"看不见/像被遮住"。
    // 取 `surface` 的亮度最可靠 —— 它与真正渲染出来的底色永远一致（含 OLED / 动态取色）。
    val lightSurface = MaterialTheme.colorScheme.surface.luminance() > 0.5f

    Dialog(
        onDismissRequest = onDismiss,
        // ⚠️ 2026-09-13 第二轮用户反馈：「全屏的时候上下不沉浸」。
        // `Dialog` 的窗口默认会**避开系统栏**（decorFitsSystemWindows = true）⇒ 全屏 Surface
        // 铺不到状态栏与手势条，那两条露出的是弹窗遮罩的灰底 —— 看起来就是上下"缺了一块"。
        // 关掉它让内容真正 edge-to-edge，再由壳自己按 `statusBarsPadding` /
        // `navigationBarsPadding` 留出让位（与列表页顶栏的做法一致）。
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // ⚠️ 2026-09-13 第三轮用户反馈：「编辑页面虽然全屏沉浸了，但上面的通知栏配色好像不对，
        // 应该是被遮盖了。」
        // 根因：`Dialog` 有**自己的一扇窗口**，它默认会按主题给状态栏 / 手势条刷一层颜色；
        // 而我们的 `Surface` 已经铺满整屏 ⇒ 那层"主题色"压在最上面，看起来就是一条被遮住的色带。
        // 这里把系统栏刷成透明、并让图标颜色跟随深浅色 —— Surface 就能一路铺到屏幕最上/最下沿，
        // 与"沉浸式"一致。⚠️ 必须在 Dialog 内容里取 parent（这时 parent 才是这扇 dialog 窗口）。
        SideEffect {
            val dialogWindow = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            @Suppress("DEPRECATION") // SDK 35+ 用边到边渲染，但 transparent 仍是这段最省事的写法
            dialogWindow.statusBarColor = android.graphics.Color.TRANSPARENT
            @Suppress("DEPRECATION")
            dialogWindow.navigationBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.getInsetsController(dialogWindow, view).apply {
                isAppearanceLightStatusBars = lightSurface
                isAppearanceLightNavigationBars = lightSurface
            }
        }
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().imePadding()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 8.dp, end = 8.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.action_cancel),
                        )
                    }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp),
                    content = content,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (destructive != null) {
                        destructive()
                        Spacer(Modifier.width(8.dp))
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                        Text(confirmLabel)
                    }
                }
            }
        }
    }
}
