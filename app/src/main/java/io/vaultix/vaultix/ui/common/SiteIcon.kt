/*
 * Vaultix — app:ui:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
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

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.imageLoader
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import io.vaultix.common.SiteIconUrl
import io.vaultix.model.VaultItem
import kotlinx.coroutines.delay

/**
 * 图标内存缓存键的**唯一**构造点（`尺寸 + URL`）。
 *
 * ## 为什么键里必须带尺寸
 *
 * Coil 在显式设置 `memoryCacheKey` 时会**整条替换**它自己算的键 ——
 * `MemoryCacheService.newCacheKey()` 第一行就是：
 *
 * ```kotlin
 * request.memoryCacheKey?.let { return it }   // 快路径：不再追加 extras
 * ```
 *
 * 而 Coil 默认的键是 `MemoryCache.Key(base, extras)`，`extras` 里才装着尺寸/变换等信息。
 * 一旦我们显式给键，`extras` 就**空了** ⇒ 列表的 40dp 图标与详情页的 56dp 图标
 * 会共用同一条缓存。后果不是"糊"，而是更隐蔽的**来回重解码**：
 * 缓存里的尺寸与本次请求不符时 `isCacheValueValid` 判无效 → 重新解码 → 覆盖同一个键，
 * 下次换个尺寸再判无效……每进一次详情页就是一轮白跑。
 *
 * ⇒ 把像素尺寸编进键里，两种尺寸各存一条，双方都走"尺寸精确匹配"的最快路径。
 *
 * @param url 图标 URL（[SiteIconUrl.forHost] 的产物，已归一化主机名）。
 * @param px 请求的像素尺寸（**不是 dp**）。
 */
internal fun siteIconCacheKey(url: String, px: Int): String = "site-icon:$px:$url"

/**
 * 失败后等待多久再重试一次（毫秒）。
 *
 * 取 30s：短到用户滑一圈回来就能看到自愈，长到不会在断网时把每一行都变成轮询器。
 */
private const val ICON_RETRY_DELAY_MS = 30_000L

/** 最多重试几次。1 次足够覆盖"网络抖动 / DNS 超时"，再多就是拿用户的电换运气。 */
private const val ICON_MAX_RETRIES = 1

/**
 * 条目左侧图标：**站点图标**，取不到就回退首字母头像。
 *
 * ## 为什么必须有回退（而不是「加载中 / 加载失败」占位）
 * 站点图标来自网络（`<服务器>/icons/<域名>/icon.png`），下面每一种情况都会真的发生：
 * 自建 Vaultwarden 未开启图标代理、局域网地址、站点未被图标服务收录、
 * 条目根本没填网址、KDBX 库（origin 是 `content://`）。
 * 这些都不该让卡片显示一个破图或一片空白 —— 首字母头像始终是**可读的兜底信息**。
 *
 * ## 缓存（2026-11-01 重做）
 *
 * 内存 + 磁盘缓存都由 Coil 管理。**全局策略**（`respectCacheHeaders(false)`）见
 * `VaultixApplication.newImageLoader()` 的 KDoc；本文件负责**渲染侧的命中率**：
 *
 * | 场景 | 行为 |
 * |---|---|
 * | 内存缓存已有图（绝大多数滚动场景） | **同步**取位图直接 `Image` 绘制，不建 `ImageRequest`、不发协程 |
 * | 首次加载 / 缓存被回收 | 先画首字母（**不是空白**），加载成功后换成图标 |
 * | 加载失败 | 首字母；[ICON_RETRY_DELAY_MS] 后自动重试一次 |
 *
 * ## 为什么要在**组合期**同步查缓存
 *
 * 这是"滑动时图标从空白再长出来"的根治点。`LazyColumn` 的卡片滑出可见区会被销毁、
 * 滑回来时**重建 composable**；而 `AsyncImage` 无论如何都要先走一次协程调度
 * （`RealImageLoader.execute` 通过 `Dispatchers.Main.immediate` 排程），
 * 于是重建后的**第一帧必然是空的**。60Hz 下一帧 16.7ms，肉眼正好能捕捉到
 * "白圈闪一下"。
 *
 * 同步查 `ImageLoader.memoryCache` 是 O(1) 的 LRU 查找，命中后第一帧就有图，
 * 从根上消掉这个空帧 —— 而不是靠调短动画去掩盖它。
 *
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
    // dp → px 必须**按当前密度**换算（Coil 2.7 的 `size(Int)` 只收像素，没有 Dp 重载）。
    //
    // ⚠️ 旧实现死写 96px，等于假设密度恒为 2x：40dp 在 3x 屏需要 120px、56dp 的详情页
    // 需要 168px ⇒ 图标被**放大 1.25~1.75 倍**绘制，高密度机上一律发虚。
    // 这不是"不够清晰"，是解码尺寸从一开始就算错了。
    val density = LocalDensity.current
    val targetPx = remember(size, density) {
        with(density) { size.roundToPx() }.coerceAtLeast(1)
    }

    val imageLoader = LocalContext.current.imageLoader

    if (iconUrl == null) {
        FallbackAvatar(fallbackText = fallbackText, size = size, modifier = modifier)
    } else {
        // ★ 组合期同步命中：卡片重建的第一帧就有图（见类 KDoc「为什么要在组合期同步查缓存」）。
        val cachedBitmap = remember(iconUrl, targetPx) {
            imageLoader.memoryCache?.get(MemoryCache.Key(siteIconCacheKey(iconUrl, targetPx)))?.bitmap
        }
        if (cachedBitmap != null) {
            CachedSiteIcon(bitmap = cachedBitmap, size = size, modifier = modifier)
        } else {
            RemoteSiteIcon(
                iconUrl = iconUrl,
                targetPx = targetPx,
                fallbackText = fallbackText,
                size = size,
                modifier = modifier,
            )
        }
    }
}

/**
 * 快路径：内存缓存已有位图。
 *
 * 这里的意义不只是"省一次请求"，而是**不构造 `ImageRequest`、不启动协程、不查询磁盘** ——
 * 整条 `RealImageLoader.execute` 都被跳过。滚动时每张卡片都走这条路，
 * 因此它必须尽可能短。
 */
@Composable
private fun CachedSiteIcon(bitmap: Bitmap, size: Dp, modifier: Modifier) {
    // `asImageBitmap()` 每次重组都会包一层新对象 ⇒ 必须 remember，否则 painter 每帧都换
    // 实例，Compose 会当成"换了一张图"而重绘。
    val painter = remember(bitmap) { BitmapPainter(bitmap.asImageBitmap()) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            // 图标多为透明 PNG：给白底，避免深色主题下深色描边糊在深色卡面上
            // （Bitwarden 同样给站点图标垫白底）。
            .background(Color.White),
    ) {
        Image(
            painter = painter,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * 慢路径：缓存里没有，需要真的去取。
 *
 * ## 为什么先画首字母再叠图片
 *
 * 旧实现在"加载中"时 `AsyncImage` 什么都没画，底色又是白圆 ⇒ 用户看到的就是
 * **一个白圈**，随后图标突然出现 —— 这正是「从空白再加载出来」的字面来源。
 * 改成"首字母始终在底层、图标成功后再盖上去"之后，任何时刻都有可读内容。
 *
 * ## 失败重试
 *
 * 旧实现失败后把 `failed = true` 一路带到这张卡被回收为止：一屏里只要有一条
 * 因为 DNS 抖动失败，它就**永远**停在首字母（除非用户滑到它被销毁再重建）。
 * 现在记录失败时刻，[ICON_RETRY_DELAY_MS] 后清标志并重建请求，最多 [ICON_MAX_RETRIES] 次。
 */
@Composable
private fun RemoteSiteIcon(
    iconUrl: String,
    targetPx: Int,
    fallbackText: String,
    size: Dp,
    modifier: Modifier,
) {
    val context = LocalContext.current
    // 失败时刻（0 = 未失败）。用时间戳而不是布尔：既能判"正在失败"，
    // 又能作为 `LaunchedEffect` 的 key 让每次失败都重新计时。
    var failedAt by remember(iconUrl) { mutableLongStateOf(0L) }
    var retries by remember(iconUrl) { mutableIntStateOf(0) }

    val cacheKey = remember(iconUrl, targetPx) { siteIconCacheKey(iconUrl, targetPx) }
    // ★★ 必须 `remember`：`ImageRequest.Builder(...).build()` 每次**重组**都会跑一遍，
    // 而它的成本并不低（构造 ImageRequest + SizeResolver + Scale + 缓存键…）。
    //
    // 2026-09-14 真机实测（这正是用户报的「点筛选按钮有点卡顿」的根因）：
    // 点一个筛选 chip → 列表内容变化 → **每一行可见条目都重组** → 每行都重新构造一次
    // ImageRequest。gfxinfo 显示慢帧全部落在 UI 线程（`Number Slow UI thread: 22`、
    // `Slow bitmap uploads: 0`、GPU 99th 仅 3ms），且 22 个慢帧的耗时集中在
    // **34~65ms**（= 丢 2~4 帧），与「十几次点击各产生一次卡顿」完全吻合。
    // 缓存命中与否都省不掉这次构造 —— 省掉的是**每帧每行**的重复构造。
    val request = remember(iconUrl, context, targetPx, retries) {
        ImageRequest.Builder(context)
            .data(iconUrl)
            .size(targetPx)
            // 显式键：见 [siteIconCacheKey] 的 KDoc（尺寸必须进键）。
            .memoryCacheKey(cacheKey)
            // 让 Coil 在"本帧还没有结果"时就用内存缓存里的同一张图兜底 —— 与上面的
            // 同步预取互补：预取管"卡片重建"，这条管"同一张卡里的状态切换"。
            .placeholderMemoryCacheKey(cacheKey)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            // ⚠️ 2026-10-04 用户反馈「往下滑动密码条目时，图标看起来一直在加载」——
            //   取证结论：**并没有在下载**。真凶是 `crossfade(true)`：
            //   `LazyColumn` 里卡片滑进/滑出可见区会**重建 composable**，
            //   而 `AsyncImage` 每次重建都把淡入动画**从头播一遍**（约 100ms 的 alpha 渐变）
            //   ⇒ 每张卡片进出场都白播一次动画，看着像在加载。
            //   代价是**实打实的每帧开销**（每张可见卡片一次 alpha 动画 + 一层重绘），
            //   与「滚动卡顿」直接相关 ⇒ 关掉。
            //
            //   ⚠️ 若将来要"首次联网下载时淡入、缓存命中时瞬显"，
            //   正确做法是分两套 ImageRequest（按缓存命中与否区分），**不要**把
            //   crossfade 整体开回来 —— 那正是本条修掉的东西。
            .crossfade(false)
            .build()
    }

    val painter = rememberAsyncImagePainter(
        model = request,
        // ⚠️ 必须显式传 `imageLoader`：带 `onError` 的那个重载**没有**默认值，
        //    漏掉它就会落到另一个只有 `onState` 的重载上而报"参数不存在"。
        imageLoader = context.imageLoader,
        onError = { failedAt = SystemClock.elapsedRealtime() },
    )
    val loaded = painter.state is AsyncImagePainter.State.Success

    // 失败后延迟重试一次：清标志 + 递增 retries ⇒ request 的 `remember` key 变化 ⇒ 重新加载。
    // ⚠️ 不用"失败即永久标记"：网络抖动不该让某个条目在本次浏览里一直显示首字母。
    LaunchedEffect(failedAt) {
        if (failedAt != 0L && retries < ICON_MAX_RETRIES) {
            delay(ICON_RETRY_DELAY_MS)
            retries += 1
            failedAt = 0L
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (loaded) Color.White else fallbackAvatar(fallbackText)),
    ) {
        if (loaded) {
            Image(
                painter = painter,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // 加载中 / 失败 / 待重试：一律显示首字母。**任何时刻都不是空白**。
            FallbackInitial(text = fallbackText)
        }
    }
}

/**
 * 兜底：没有域名可拼 URL（KDBX 库、未填网址、非法域名）时的首字母头像。
 *
 * 与 [RemoteSiteIcon] 的"加载中"态共用 [FallbackInitial]，保证两者字形完全一致 ——
 * 否则同一个条目在"有域名但没取到"和"根本没域名"两种情况下会显示成两种大小。
 */
@Composable
private fun FallbackAvatar(fallbackText: String, size: Dp, modifier: Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(fallbackAvatar(fallbackText)),
    ) {
        FallbackInitial(text = fallbackText)
    }
}

/** 首字母字形（彩色底衬之上）。底衬已换成饱和色，字必须固定用白。 */
@Composable
private fun FallbackInitial(text: String) {
    Text(
        text = text.take(1).uppercase(),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        // 底衬是饱和色（见 [fallbackAvatar]），字必须用**对白/对黑**，
        // 不能再跟随主题的 onSurfaceVariant —— 那在彩色底上会读不清。
        color = fallbackAvatarContentColor(),
    )
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
