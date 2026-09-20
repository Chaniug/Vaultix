/*
 * Vaultix — app:ui:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * URL 规则见 [io.vaultix.common.SiteIconUrl]（端点与路径对照 Bitwarden Android 官方客户端：
 * `EnvironmentExtensions.kt:113-126` 的 `<base>/icons`、以及条目侧 `<base>/icons/<host>/icon.png`）。
 * 本文件只负责渲染与缓存（Coil + OkHttp）。
 *
 * 首字母头像的**彩色底衬**取自 Bitwarden Android 官方客户端的同类做法
 * （`ui/theme/Theme.kt` 里按 hash 从一组色相里取定值），此处为独立实现：
 * 色相由**标题的稳定哈希**决定，保证同一条目每次进来颜色都一样（不会"闪色"）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import io.vaultix.common.SiteIconUrl
import io.vaultix.model.VaultItem

/** 图标 PNG 通常接近正方形；统一按 2 倍密度请求，够清晰也不至于浪费流量。 */
private const val ICON_REQUEST_PX = 96

/**
 * 条目左侧图标：**站点图标**，取不到就回退首字母头像。
 *
 * ## 为什么必须有回退（而不是「加载中 / 加载失败」占位）
 * 站点图标来自网络（`<服务器>/icons/<域名>/icon.png`），下面每一种情况都会真的发生：
 * 自建 Vaultwarden 未开启图标代理、局域网地址、站点未被图标服务收录、
 * 条目根本没填网址、KDBX 库（origin 是 `content://`）。
 * 这些都不该让卡片显示一个破图或一片空白 —— 首字母头像始终是**可读的兜底信息**。
 *
 * ## 缓存
 * 内存 + 磁盘缓存都由 Coil 管理（这里显式写出，防将来被全局配置改掉）。
 * **刻意不带认证 / cookie**：图标端点按域名公开可访问；带上会话凭据会把一次
 * 「公开 CDN 取图」变成「已登录态的私密请求」，既无必要也扩大凭据暴露面。
 *
 * @param item 条目（域名取 [VaultItem.uris]，标题用于首字母兜底）。
 * @param serverOrigin 库的服务器地址（Bitwarden 库即其 origin；KDBX 的 `content://` 会
 *   被 [SiteIconUrl] 判为不可用 → 直接走首字母）。
 */
@Composable
fun SiteIcon(
    item: VaultItem,
    serverOrigin: String?,
    size: Dp = EntryCardIconSize,
    modifier: Modifier = Modifier,
) {
    SiteIconByHost(
        domain = remember(item.uris) { SiteIconUrl.hostOfItemUris(item.uris.map { it.uri }) },
        fallbackText = item.title,
        serverOrigin = serverOrigin,
        size = size,
        modifier = modifier,
    )
}

/**
 * 同上，但直接给域名（验证码页的条目只有域名，没有完整 [VaultItem]）。
 *
 * 两个入口共用同一实现，保证密码列表与验证码列表的图标尺寸 / 兜底观感完全一致。
 */
@Composable
fun SiteIconByHost(
    domain: String?,
    fallbackText: String,
    serverOrigin: String?,
    size: Dp = EntryCardIconSize,
    modifier: Modifier = Modifier,
) {
    val iconUrl = remember(domain, serverOrigin) {
        SiteIconUrl.forHost(serverOrigin, domain)
    }
    var failed by remember(iconUrl) { mutableStateOf(false) }
    val showFallback = iconUrl == null || failed
    // ★★ 必须 `remember`：`ImageRequest.Builder(...).build()` 每次**重组**都会跑一遍，
    // 而它的成本并不低（构造 ImageRequest + SizeResolver + Scale + 缓存键…）。
    //
    // 2026-09-14 真机实测（这正是用户报的「点筛选按钮有点卡顿」的根因）：
    // 点一个筛选 chip → 列表内容变化 → **每一行可见条目都重组** → 每行都重新构造一次
    // ImageRequest。gfxinfo 显示慢帧全部落在 UI 线程（`Number Slow UI thread: 22`、
    // `Slow bitmap uploads: 0`、GPU 99th 仅 3ms），且 22 个慢帧的耗时集中在
    // **34~65ms**（= 丢 2~4 帧），与「十几次点击各产生一次卡顿」完全吻合。
    // 缓存命中与否都省不掉这次构造 —— 省掉的是**每帧每行**的重复构造。
    val context = LocalContext.current
    val request = remember(iconUrl, context) {
        ImageRequest.Builder(context)
            .data(iconUrl)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .size(ICON_REQUEST_PX)
            .crossfade(true)
            .build()
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                // 图标多为透明 PNG：给白底，避免深色主题下深色描边糊在深色卡面上
                // （Bitwarden 同样给站点图标垫白底）。兜底态则用彩色头像，见下。
                if (showFallback) fallbackAvatar(fallbackText) else Color.White,
            ),
    ) {
        if (!showFallback) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                // 任何失败（404 / 超时 / 非法图片）都切回首字母：调用方无需区分原因。
                onError = { failed = true },
            )
        } else {
            Text(
                text = fallbackText.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                // 底衬已换成饱和色（见 [fallbackAvatar]），字必须用**对白/对黑**，
                // 不能再跟随主题的 onSurfaceVariant —— 那在彩色底上会读不清。
                color = fallbackAvatarContentColor(),
            )
        }
    }
}

/**
 * 首字母头像的**彩色底衬**（2026-09-20）。
 *
 * ## 为什么要上色
 * 用户提问：「感觉目前界面的配色略微单一了。」
 *
 * 排查发现：三个列表页（密码 / 验证码 / 通行密钥）左端那个 40dp 头像，
 * 取不到站点图标时**全是同一个灰底**（旧实现恒为 `surfaceContainerHigh` +
 * `onSurfaceVariant` 字色）。而现实是**兜底才是常态** ——
 * 自建 Vaultwarden 不开图标代理、局域网地址、KDBX 库（无 origin）全都走兜底。
 * 于是一屏几十条里，最显眼的一列元素全是灰的 ⇒ 观感自然"单色"。
 *
 * ## 为什么底色可以上色，而容器色不行
 * M3 对**大面积容器**（页面底、卡片、对话框）确实要求低饱和，那里改不得。
 * 但 40dp 的圆形头像是一个**独立的小色块**，它的职责恰恰是"把这一条和那一条区分开"。
 * Bitwarden 官方客户端就是这么做的（按 hash 取色相）——这不是背离 M3，
 * 而是 M3 「bounded regions that need emphasis」那条的正当用法。
 *
 * ## 为什么用标题哈希而不是随机
 * 头像每次都必须是同一个颜色。用随机色或按列表下标取色，滚动一遍颜色就全变了，
 * 用户会把"颜色"误读成"状态"（以为条目变了）。哈希取色则稳定且**同一条目恒定**。
 *
 * ⚠️ 只取色相、固定饱和度和明度：这样深浅主题下都能保证与白/黑字的对比度。
 * 不要改成随主题切换整套色表 —— 那会让同一条目在切换主题后"换了身份"。
 */
private fun fallbackAvatar(seed: String): Color {
    val hue = avatarHue(seed)
    return Color.hsl(hue = hue, saturation = AVATAR_SATURATION, lightness = AVATAR_LIGHTNESS)
}

/**
 * 由标题算出稳定色相（0°..330°，12 档）。
 *
 * 抽成 `internal` 纯函数是为了**能被单测钉住** —— 这里有两个只在边界上才发作的坑
 * （见下），靠真机看是看不出来的：
 *
 * 1. **`Int.MIN_VALUE` 没有对应的正数**：`seed.hashCode().absoluteValue` 对它是负数，
 *    算出的色相为负 ⇒ `Color.hsl` 行为未定义。所以先取模再修正。
 * 2. **色相必须落在 12 档上**：`%` 在 Kotlin 里对负数返回负值，不加修正会得到
 *    负数档位。修正式 `(x % n + n) % n` 对正负输入都给出 `0..n-1`。
 */
internal fun avatarHue(seed: String): Float {
    val bucket = ((seed.hashCode() % AVATAR_HUE_STEPS) + AVATAR_HUE_STEPS) % AVATAR_HUE_STEPS
    return bucket * (HUE_WHEEL_DEGREES / AVATAR_HUE_STEPS)
}

/** 色相环总度数（把 0..n-1 的档位换算成 0°..360° 的色相）。 */
private const val HUE_WHEEL_DEGREES = 360f

/** 彩色底衬上的文字色：固定白字（底衬明度已锁定，见 [fallbackAvatar] 的告警）。 */
private fun fallbackAvatarContentColor(): Color = Color.White

/** 色相档数：12 档 = 每 30° 一档，相邻头像肉眼可分辨又不会花。 */
internal const val AVATAR_HUE_STEPS = 12

/** 饱和度：0.45 —— 够"有色"，又不至于在深色主题里刺眼。 */
internal const val AVATAR_SATURATION = 0.45f

/**
 * 明度：**0.32** —— 实测调出来的值，不要凭感觉动。
 *
 * 白字要求对比度 ≥ 4.5:1（WCAG AA 正文）。**青色/黄色区最吃亏**：
 * 同样是 HSL 明度，青黄两色的相对亮度远高于红蓝。实测各档对白字的最差对比度：
 *
 * | 明度 | 最差档位对比度 | |
 * |---|---|---|
 * | 0.45 | **2.57** | ❌ 青黄区完全读不清 |
 * | 0.32 | **4.75** | ✅ 12 档全部达标 |
 *
 * 二分求解得上限是 0.331（对白字），这里取 0.32 留一点余量。
 * ⚠️ 往亮处调之前**必须重跑一遍 12 个色相的对比度**，不能只看某一个色相顺眼就改。
 */
internal const val AVATAR_LIGHTNESS = 0.32f

/**
 * 由「库 origin」+「条目」直接产出 URL 的便捷入口（供无 Compose 上下文的调用点复用）。
 *
 * 放进 UI 而不是 core:common：core:common 已依赖 core:model，但把「条目 → URL」
 * 这层便利方法留在消费侧，可避免 core:common 里出现第二处模型耦合点。
 */
fun siteIconUrlFor(serverOrigin: String?, item: VaultItem): String? =
    SiteIconUrl.forHost(serverOrigin, SiteIconUrl.hostOfItemUris(item.uris.map { it.uri }))
