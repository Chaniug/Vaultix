/*
 * Vaultix — app:ui:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * ---------------------------------------------------------------------------
 * **域名 → 品牌 slug**（离线图标包的查表规则）。
 *
 * ## 它解决什么
 *
 * 用户要求「Bitwarden 与 KDBX 一块生效，离线也能看到常用图标，且**本地优先**」。
 * 图标包里存的是**品牌名**（`github` / `google` / `microsoft`），而条目里只有
 * **网址**（`login.microsoftonline.com`）。本文件就是那一步换算。
 *
 * ## 为什么单独一个纯函数
 *
 * 它是整条图标链路上**唯一会判错**的地方：
 * 判错不会崩、不会报错，只会安静地少一个图标（或更糟：张冠李戴）。
 * ⇒ 抽成不碰 Android 的纯函数，好在 JVM 单测里把边界钉死（同 `avatarHue` 的取向）。
 *
 * ## 匹配顺序（从准到松，命中即返回）
 *
 * | 步 | 例 | 说明 |
 * |---|---|---|
 * | ① 别名 | `outlook.com` → `microsoft` | 手写表，专治"品牌名 ≠ 域名" |
 * | ② 主域标签精确 | `github.com` → `github` | 绝大多数站点走这条 |
 * | ③ 子域标签精确 | `docs.google.com` → `google` | `docs` 不在表里，继续往左找 |
 * | ④ 主域标签前缀 | `microsoftonline.com` → `microsoft` | 只在**主域标签**上做，且有长度下限 |
 *
 * ⚠️ **只用主域标签做前缀匹配**：拿子域（`docs` / `login` / `mail`）去前缀匹配会
 * 命中一堆偶然相同的短 slug（例如 slug `x` 会吃掉所有以 x 开头的子域）。
 * ⚠️ 前缀匹配要求 slug ≥ [PREFIX_MIN_SLUG_LEN]：表里有 `x` 这种单字母品牌，
 * 不设下限的话任何 `x*` 主域都会变成 X 的图标 —— 那是**张冠李戴**，比没图标糟。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.common

/**
 * 由主机名找离线品牌图标（null = 包里没有这个品牌，调用方继续往下兜底）。
 *
 * @param host 主机名或网址（`github.com` / `https://mail.google.com/x` / `WWW.GitHub.COM:443`
 *   都能吃），见 [normalizeHost]。空 / IP / `localhost` ⇒ null。
 */
internal fun brandSlugForHost(host: String?): String? {
    val labels = normalizeHost(host)?.split('.')?.filter { it.isNotEmpty() } ?: return null
    if (labels.size < 2) return null

    val suffixLabels = if (labels.takeLast(2).joinToString(".") in MULTI_PART_SUFFIXES) 2 else 1
    val baseIndex = labels.size - suffixLabels - 1
    if (baseIndex < 0) return null
    val base = labels[baseIndex]

    // ①②③ 主域标签优先，再逐级往左看子域；每一级都先查别名再查精确匹配。
    //
    // ⚠️ 必须**逐级**做"别名→精确"，不能先把所有别名查一遍：
    //    `live.google.com` 的别名表里有 `live`→`microsoft`，若先查别名就会
    //    把一个 Google 站点标成微软 —— 张冠李戴比没图标糟得多。
    for (i in baseIndex downTo 0) {
        val label = labels[i]
        ALIASES[label]?.let { alias -> if (BrandIconCatalog.of(alias) != null) return alias }
        if (BrandIconCatalog.of(label) != null) return label
    }

    // ④ 前缀：只对主域标签做（见文件头的两条告警）。
    if (base.length >= PREFIX_MIN_HOST_LEN) {
        BrandIconCatalog.slugsLongestFirst.firstOrNull { slug ->
            slug.length >= PREFIX_MIN_SLUG_LEN && base.startsWith(slug)
        }?.let { return it }
    }
    return null
}

/**
 * 归一化主机名：小写、去 scheme / 路径 / 查询串 / 端口 / 结尾点。
 *
 * @return null = 不是一个可用的"带点的域名"（空串、`localhost`、IP 地址）。
 *   ⚠️ IP 地址必须排除：`192.168.1.1` 的主域标签是 `1`，任何前缀匹配都可能命中，
 *   而它本来就不该有品牌图标（局域网设备）。
 */
private fun normalizeHost(raw: String?): String? {
    val trimmed = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    val noScheme = trimmed.substringAfter("://", trimmed)
    val hostOnly = noScheme.substringBefore('/').substringBefore('?').substringBefore('#')
    val noPort = hostOnly.substringBefore(':').trimEnd('.')
    if (noPort.isEmpty() || noPort == "localhost") return null
    // IPv4：四段全数字；IPv6 已经在上面被 `:` 截断（剩下的形态也不含点）。
    if (noPort.all { it.isDigit() || it == '.' }) return null
    return noPort
}

/**
 * 两段式公共后缀（`co.uk` / `com.cn` …）。
 *
 * ⚠️ 只是**够用**的最小集合，不是完整的 Public Suffix List：
 * 我们要判的是"品牌标签在哪一位"，而 PSL 的完整语义（`*.ck` 之类）在这里用不上。
 * 漏登记一个后缀的最坏后果是**多看一眼左边的标签**（比如把 `mail` 当主域），
 * 不会崩，也不会误判成别人。
 */
private val MULTI_PART_SUFFIXES = setOf(
    "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn", "ac.cn",
    "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk",
    "com.hk", "org.hk", "com.tw", "com.mo", "com.sg", "com.my", "com.ph", "co.id",
    "co.jp", "or.jp", "ne.jp", "co.kr", "or.kr",
    "com.au", "net.au", "org.au", "co.nz",
    "com.br", "com.mx", "com.ar", "com.co", "com.pe", "com.tr", "com.ae", "co.za",
)

/**
 * 手写别名表：**品牌名与域名对不上**的常见站点。
 *
 * ⚠️ 只放"前缀匹配救不回来"的：
 * `microsoftonline` 靠前缀就能落到 `microsoft`，**不要**在这里重复登记 ——
 * 两份真相迟早漂移，而漂移的表现是"某天某个站点图标突然没了"，极难自查。
 * @key 主域标签（不含后缀）。
 */
private val ALIASES = mapOf(
    // 微软：登录入口常用 outlook / office / live 这类独立域名。
    "outlook" to "microsoft",
    "office" to "microsoft",
    "live" to "microsoft",
    "azure" to "microsoft",
    // 阿里云：域名是 aliyun，图标包里叫 alibabacloud。
    "aliyun" to "alibabacloud",
    // 谷歌的企业邮局用独立域名。
    "googlemail" to "gmail",
    // 腾讯系：图标包里只有 wechat（没有 qq）。
    "weixin" to "wechat",
)

/**
 * 前缀匹配的**域名侧**最短长度：`x.com` 这种短主域不参与前缀匹配（只能精确命中）。
 * 4 是实测下限 —— `qq.com`(2) / `x.com`(1) 都不该被前缀规则拖进别的品牌。
 */
private const val PREFIX_MIN_HOST_LEN = 4

/**
 * 前缀匹配的**品牌侧**最短长度。
 *
 * ⚠️ 3 是"短到仍然有意义"的边界（`x` / `vk` / `qq` 这类不参与前缀匹配）。
 */
private const val PREFIX_MIN_SLUG_LEN = 3
