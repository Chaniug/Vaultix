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
 * 本文件只负责渲染与缓存（Coil + OkHttp），外观沿用 Vaultix 既有首字母头像规格
 * （`surfaceContainerHigh` 圆底 + onSurfaceVariant 字色），保证「有图标 / 没图标」
 * 两种状态下卡片左侧的尺寸与底色完全一致（不会一跳一跳）。
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

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                // 图标多为透明 PNG：给白底，避免深色主题下深色描边糊在深色卡面上
                // （Bitwarden 同样给站点图标垫白底）。兜底态则用容器色，与首字母头像一致。
                if (showFallback) MaterialTheme.colorScheme.surfaceContainerHigh else Color.White,
            ),
    ) {
        if (!showFallback) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(iconUrl)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .size(ICON_REQUEST_PX)
                    .crossfade(true)
                    .build(),
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 由「库 origin」+「条目」直接产出 URL 的便捷入口（供无 Compose 上下文的调用点复用）。
 *
 * 放进 UI 而不是 core:common：core:common 已依赖 core:model，但把「条目 → URL」
 * 这层便利方法留在消费侧，可避免 core:common 里出现第二处模型耦合点。
 */
fun siteIconUrlFor(serverOrigin: String?, item: VaultItem): String? =
    SiteIconUrl.forHost(serverOrigin, SiteIconUrl.hostOfItemUris(item.uris.map { it.uri }))
