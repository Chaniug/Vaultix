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
 * **离线品牌图标目录**（`app/src/main/res/drawable/brand_*.xml` 的索引）。
 *
 * ⚠️ 本文件由 `tools/brand-icons/generate.py` 生成，**不要手改**。
 *    要加图标：改 `tools/brand-icons/slugs.txt` 后重跑脚本 ——
 *    否则"文件在、索引里没有"= 永远不会被用到（不报错，只是静默不生效）。
 *
 * ## 图标从哪来
 *
 * | 来源 | 用途 | 许可 |
 * |---|---|---|
 * | simple-icons v16.36.0 | 主来源 | CC0-1.0 |
 * | Font Awesome Free 6（brands） | 补齐 simple-icons 因商标下掉的那批 | CC BY 4.0 |
 *
 * 商标归各品牌所有，此处仅用于**识别用户自己保存的登录站点**（与浏览器 favicon 同性质）。
 * 详见仓库根 `NOTICE`。
 *
 * ## 为什么是精选子集而不是全集
 *
 * 「不要让 app 体积膨胀太多」是硬前提：simple-icons 全集 3467 个 ≈ 4–6 MB，
 * 这里是 229 个常用站点，覆盖绝大多数密码条目。表外站点仍走网络图标或首字母兜底 ——
 * **不影响功能，只少一个标志**。
 * ---------------------------------------------------------------------------
 */

// 品牌色是**数据**（229 条 ARGB 字面量），逐个提成常量只会把表拆散、更难核对。
@file:Suppress("MagicNumber")

package io.vaultix.vaultix.ui.common

import io.vaultix.vaultix.R

/** 一个离线品牌图标：[drawableRes] 是白色单色路径，[color] 是品牌色（ARGB，用来画圆形底衬）。 */
internal class BrandIcon(
    val drawableRes: Int,
    val color: Long,
)

/**
 * 品牌 slug → 离线图标（只读表）。
 *
 * slug 就是 simple-icons 的文件名（如 `github`、`google`、`playstation`）。
 * 域名到 slug 的换算**不在这里**（见 [brandSlugForHost]）—— 两者分开才能各自单测：
 * 一个是"包里有啥"，一个是"这个域名像谁"。
 */
internal object BrandIconCatalog {

    private val icons: Map<String, BrandIcon> = buildMap(CAPACITY) {
        putAll(chunk0())
        putAll(chunk1())
        putAll(chunk2())
        putAll(chunk3())
        putAll(chunk4())
    }

    /** 查图标；null = 包里没有这个品牌（**不是错误**，调用方回退）。 */
    fun of(slug: String): BrandIcon? = icons[slug]

    /**
     * 全部已知 slug，**按长度降序**（供 [brandSlugForHost] 做前缀匹配）。
     *
     * ⚠️ 必须最长优先：否则 `microsoft` 会抢走 `microsoftazure` 这类更具体的前缀。
     * 这条约束是结构性的，不依赖当前数据长什么样。
     */
    val slugsLongestFirst: List<String> by lazy {
        icons.keys.sortedByDescending { it.length }
    }

    /** 图标总数（单测用它拦住"生成跑了一半 / 资源被误删"）。 */
    val size: Int get() = icons.size

    private const val CAPACITY = 229

    private fun chunk0(): Map<String, BrandIcon> = mapOf(
        "1password" to BrandIcon(R.drawable.brand_1password, 0xFF3B66BC),
        "acer" to BrandIcon(R.drawable.brand_acer, 0xFF83B81A),
        "adidas" to BrandIcon(R.drawable.brand_adidas, 0xFF000000),
        "airbnb" to BrandIcon(R.drawable.brand_airbnb, 0xFFFF5A5F),
        "airtable" to BrandIcon(R.drawable.brand_airtable, 0xFF18BFFF),
        "alibabacloud" to BrandIcon(R.drawable.brand_alibabacloud, 0xFFFF6A00),
        "aliexpress" to BrandIcon(R.drawable.brand_aliexpress, 0xFFFF4747),
        "alipay" to BrandIcon(R.drawable.brand_alipay, 0xFF1677FF),
        "amazon" to BrandIcon(R.drawable.brand_amazon, 0xFFFF9900),
        "amd" to BrandIcon(R.drawable.brand_amd, 0xFFED1C24),
        "americanexpress" to BrandIcon(R.drawable.brand_americanexpress, 0xFF2E77BC),
        "android" to BrandIcon(R.drawable.brand_android, 0xFF3DDC84),
        "androidstudio" to BrandIcon(R.drawable.brand_androidstudio, 0xFF3DDC84),
        "angular" to BrandIcon(R.drawable.brand_angular, 0xFF0F0F11),
        "ansible" to BrandIcon(R.drawable.brand_ansible, 0xFFEE0000),
        "anthropic" to BrandIcon(R.drawable.brand_anthropic, 0xFF191919),
        "apache" to BrandIcon(R.drawable.brand_apache, 0xFFD22128),
        "apachetomcat" to BrandIcon(R.drawable.brand_apachetomcat, 0xFFF8DC75),
        "apple" to BrandIcon(R.drawable.brand_apple, 0xFF000000),
        "applemusic" to BrandIcon(R.drawable.brand_applemusic, 0xFFFA243C),
        "applepay" to BrandIcon(R.drawable.brand_applepay, 0xFF000000),
        "appletv" to BrandIcon(R.drawable.brand_appletv, 0xFF000000),
        "appstore" to BrandIcon(R.drawable.brand_appstore, 0xFF0D96F6),
        "archlinux" to BrandIcon(R.drawable.brand_archlinux, 0xFF1793D1),
        "arduino" to BrandIcon(R.drawable.brand_arduino, 0xFF00878F),
        "asana" to BrandIcon(R.drawable.brand_asana, 0xFFF06A6A),
        "asus" to BrandIcon(R.drawable.brand_asus, 0xFF000000),
        "audible" to BrandIcon(R.drawable.brand_audible, 0xFFF8991C),
        "autodesk" to BrandIcon(R.drawable.brand_autodesk, 0xFF000000),
        "baidu" to BrandIcon(R.drawable.brand_baidu, 0xFF2932E1),
        "battledotnet" to BrandIcon(R.drawable.brand_battledotnet, 0xFF5E5E5E),
        "bilibili" to BrandIcon(R.drawable.brand_bilibili, 0xFF00A1D6),
        "binance" to BrandIcon(R.drawable.brand_binance, 0xFFF0B90B),
        "bitbucket" to BrandIcon(R.drawable.brand_bitbucket, 0xFF0052CC),
        "bitwarden" to BrandIcon(R.drawable.brand_bitwarden, 0xFF175DDC),
        "blender" to BrandIcon(R.drawable.brand_blender, 0xFFE87D0D),
        "blogger" to BrandIcon(R.drawable.brand_blogger, 0xFFFF5722),
        "bookingdotcom" to BrandIcon(R.drawable.brand_bookingdotcom, 0xFF5E5E5E),
        "box" to BrandIcon(R.drawable.brand_box, 0xFF0061D5),
        "caddy" to BrandIcon(R.drawable.brand_caddy, 0xFF1F88C0),
        "centos" to BrandIcon(R.drawable.brand_centos, 0xFF262577),
        "clickup" to BrandIcon(R.drawable.brand_clickup, 0xFF7B68EE),
        "cloudflare" to BrandIcon(R.drawable.brand_cloudflare, 0xFFF38020),
        "coinbase" to BrandIcon(R.drawable.brand_coinbase, 0xFF0052FF),
        "dailymotion" to BrandIcon(R.drawable.brand_dailymotion, 0xFF0A0A0A),
        "dart" to BrandIcon(R.drawable.brand_dart, 0xFF0175C2),
    )

    private fun chunk1(): Map<String, BrandIcon> = mapOf(
        "dashlane" to BrandIcon(R.drawable.brand_dashlane, 0xFF0E353D),
        "debian" to BrandIcon(R.drawable.brand_debian, 0xFFA81D33),
        "deezer" to BrandIcon(R.drawable.brand_deezer, 0xFF5E5E5E),
        "dell" to BrandIcon(R.drawable.brand_dell, 0xFF007DB8),
        "digitalocean" to BrandIcon(R.drawable.brand_digitalocean, 0xFF0080FF),
        "discord" to BrandIcon(R.drawable.brand_discord, 0xFF5865F2),
        "docker" to BrandIcon(R.drawable.brand_docker, 0xFF2496ED),
        "dropbox" to BrandIcon(R.drawable.brand_dropbox, 0xFF0061FF),
        "ea" to BrandIcon(R.drawable.brand_ea, 0xFF000000),
        "ebay" to BrandIcon(R.drawable.brand_ebay, 0xFFE53238),
        "elasticsearch" to BrandIcon(R.drawable.brand_elasticsearch, 0xFF005571),
        "epicgames" to BrandIcon(R.drawable.brand_epicgames, 0xFF313131),
        "etsy" to BrandIcon(R.drawable.brand_etsy, 0xFFF16521),
        "evernote" to BrandIcon(R.drawable.brand_evernote, 0xFF00A82D),
        "expedia" to BrandIcon(R.drawable.brand_expedia, 0xFF191E3B),
        "facebook" to BrandIcon(R.drawable.brand_facebook, 0xFF0866FF),
        "fedora" to BrandIcon(R.drawable.brand_fedora, 0xFF51A2DA),
        "figma" to BrandIcon(R.drawable.brand_figma, 0xFFF24E1E),
        "files" to BrandIcon(R.drawable.brand_files, 0xFF4285F4),
        "flutter" to BrandIcon(R.drawable.brand_flutter, 0xFF02569B),
        "gitea" to BrandIcon(R.drawable.brand_gitea, 0xFF609926),
        "github" to BrandIcon(R.drawable.brand_github, 0xFF181717),
        "gitlab" to BrandIcon(R.drawable.brand_gitlab, 0xFFFC6D26),
        "gmail" to BrandIcon(R.drawable.brand_gmail, 0xFFEA4335),
        "gogdotcom" to BrandIcon(R.drawable.brand_gogdotcom, 0xFF5E5E5E),
        "golang" to BrandIcon(R.drawable.brand_golang, 0xFF00ADD8),
        "google" to BrandIcon(R.drawable.brand_google, 0xFF4285F4),
        "googleanalytics" to BrandIcon(R.drawable.brand_googleanalytics, 0xFFE37400),
        "googlecalendar" to BrandIcon(R.drawable.brand_googlecalendar, 0xFF4285F4),
        "googlechrome" to BrandIcon(R.drawable.brand_googlechrome, 0xFF4285F4),
        "googlecloud" to BrandIcon(R.drawable.brand_googlecloud, 0xFF4285F4),
        "googledocs" to BrandIcon(R.drawable.brand_googledocs, 0xFF4285F4),
        "googledrive" to BrandIcon(R.drawable.brand_googledrive, 0xFF4285F4),
        "googlegemini" to BrandIcon(R.drawable.brand_googlegemini, 0xFF8E75B2),
        "googlemaps" to BrandIcon(R.drawable.brand_googlemaps, 0xFF4285F4),
        "googleplay" to BrandIcon(R.drawable.brand_googleplay, 0xFF414141),
        "googlesheets" to BrandIcon(R.drawable.brand_googlesheets, 0xFF34A853),
        "gotomeeting" to BrandIcon(R.drawable.brand_gotomeeting, 0xFFF68D2E),
        "grafana" to BrandIcon(R.drawable.brand_grafana, 0xFFF46800),
        "hp" to BrandIcon(R.drawable.brand_hp, 0xFF0096D6),
        "huawei" to BrandIcon(R.drawable.brand_huawei, 0xFFFF0000),
        "huggingface" to BrandIcon(R.drawable.brand_huggingface, 0xFFFFD21E),
        "icloud" to BrandIcon(R.drawable.brand_icloud, 0xFF3693F3),
        "ikea" to BrandIcon(R.drawable.brand_ikea, 0xFF0058A3),
        "instagram" to BrandIcon(R.drawable.brand_instagram, 0xFFFF0069),
        "intel" to BrandIcon(R.drawable.brand_intel, 0xFF0071C5),
    )

    private fun chunk2(): Map<String, BrandIcon> = mapOf(
        "intellijidea" to BrandIcon(R.drawable.brand_intellijidea, 0xFF000000),
        "java" to BrandIcon(R.drawable.brand_java, 0xFF007396),
        "javascript" to BrandIcon(R.drawable.brand_javascript, 0xFFF7DF1E),
        "jetbrains" to BrandIcon(R.drawable.brand_jetbrains, 0xFF000000),
        "kakaotalk" to BrandIcon(R.drawable.brand_kakaotalk, 0xFFFFCD00),
        "keeper" to BrandIcon(R.drawable.brand_keeper, 0xFFFFC700),
        "kotlin" to BrandIcon(R.drawable.brand_kotlin, 0xFF7F52FF),
        "kubernetes" to BrandIcon(R.drawable.brand_kubernetes, 0xFF326CE5),
        "lastpass" to BrandIcon(R.drawable.brand_lastpass, 0xFFD32D27),
        "lenovo" to BrandIcon(R.drawable.brand_lenovo, 0xFFE2231A),
        "line" to BrandIcon(R.drawable.brand_line, 0xFF00C300),
        "linkedin" to BrandIcon(R.drawable.brand_linkedin, 0xFF0A66C2),
        "linode" to BrandIcon(R.drawable.brand_linode, 0xFF00A95C),
        "linux" to BrandIcon(R.drawable.brand_linux, 0xFFFCC624),
        "lyft" to BrandIcon(R.drawable.brand_lyft, 0xFFFF00BF),
        "macos" to BrandIcon(R.drawable.brand_macos, 0xFF000000),
        "maildotru" to BrandIcon(R.drawable.brand_maildotru, 0xFF5E5E5E),
        "mariadb" to BrandIcon(R.drawable.brand_mariadb, 0xFF003545),
        "mastercard" to BrandIcon(R.drawable.brand_mastercard, 0xFFEB001B),
        "mastodon" to BrandIcon(R.drawable.brand_mastodon, 0xFF6364FF),
        "mediatek" to BrandIcon(R.drawable.brand_mediatek, 0xFFEC9430),
        "medium" to BrandIcon(R.drawable.brand_medium, 0xFF000000),
        "mega" to BrandIcon(R.drawable.brand_mega, 0xFFD9272E),
        "messenger" to BrandIcon(R.drawable.brand_messenger, 0xFF0866FF),
        "meta" to BrandIcon(R.drawable.brand_meta, 0xFF0467DF),
        "microsoft" to BrandIcon(R.drawable.brand_microsoft, 0xFF5E5E5E),
        "mongodb" to BrandIcon(R.drawable.brand_mongodb, 0xFF47A248),
        "mysql" to BrandIcon(R.drawable.brand_mysql, 0xFF4479A1),
        "neovim" to BrandIcon(R.drawable.brand_neovim, 0xFF57A143),
        "netflix" to BrandIcon(R.drawable.brand_netflix, 0xFFE50914),
        "netlify" to BrandIcon(R.drawable.brand_netlify, 0xFF00C7B7),
        "nextcloud" to BrandIcon(R.drawable.brand_nextcloud, 0xFF0082C9),
        "nginx" to BrandIcon(R.drawable.brand_nginx, 0xFF009639),
        "nike" to BrandIcon(R.drawable.brand_nike, 0xFF111111),
        "nodedotjs" to BrandIcon(R.drawable.brand_nodedotjs, 0xFF5E5E5E),
        "notion" to BrandIcon(R.drawable.brand_notion, 0xFF000000),
        "nvidia" to BrandIcon(R.drawable.brand_nvidia, 0xFF76B900),
        "obsidian" to BrandIcon(R.drawable.brand_obsidian, 0xFF7C3AED),
        "okx" to BrandIcon(R.drawable.brand_okx, 0xFF000000),
        "ollama" to BrandIcon(R.drawable.brand_ollama, 0xFF000000),
        "openstack" to BrandIcon(R.drawable.brand_openstack, 0xFFED1944),
        "opensuse" to BrandIcon(R.drawable.brand_opensuse, 0xFF73BA25),
        "openwrt" to BrandIcon(R.drawable.brand_openwrt, 0xFF00B5E2),
        "oppo" to BrandIcon(R.drawable.brand_oppo, 0xFF2D683D),
        "owncloud" to BrandIcon(R.drawable.brand_owncloud, 0xFF041E42),
        "patreon" to BrandIcon(R.drawable.brand_patreon, 0xFF000000),
    )

    private fun chunk3(): Map<String, BrandIcon> = mapOf(
        "paypal" to BrandIcon(R.drawable.brand_paypal, 0xFF003087),
        "perplexity" to BrandIcon(R.drawable.brand_perplexity, 0xFF1FB8CD),
        "php" to BrandIcon(R.drawable.brand_php, 0xFF777BB4),
        "pinterest" to BrandIcon(R.drawable.brand_pinterest, 0xFFBD081C),
        "playstation" to BrandIcon(R.drawable.brand_playstation, 0xFF0070D1),
        "podman" to BrandIcon(R.drawable.brand_podman, 0xFF892CA0),
        "postgresql" to BrandIcon(R.drawable.brand_postgresql, 0xFF4169E1),
        "prometheus" to BrandIcon(R.drawable.brand_prometheus, 0xFFE6522C),
        "protonmail" to BrandIcon(R.drawable.brand_protonmail, 0xFF6D4AFF),
        "proxmox" to BrandIcon(R.drawable.brand_proxmox, 0xFFE57000),
        "pycharm" to BrandIcon(R.drawable.brand_pycharm, 0xFF000000),
        "python" to BrandIcon(R.drawable.brand_python, 0xFF3776AB),
        "qualcomm" to BrandIcon(R.drawable.brand_qualcomm, 0xFF3253DC),
        "quora" to BrandIcon(R.drawable.brand_quora, 0xFFB92B27),
        "rabbitmq" to BrandIcon(R.drawable.brand_rabbitmq, 0xFFFF6600),
        "raspberrypi" to BrandIcon(R.drawable.brand_raspberrypi, 0xFFA22846),
        "react" to BrandIcon(R.drawable.brand_react, 0xFF61DAFB),
        "reddit" to BrandIcon(R.drawable.brand_reddit, 0xFFFF4500),
        "redhat" to BrandIcon(R.drawable.brand_redhat, 0xFFEE0000),
        "redis" to BrandIcon(R.drawable.brand_redis, 0xFFFF4438),
        "riotgames" to BrandIcon(R.drawable.brand_riotgames, 0xFFEB0029),
        "ruby" to BrandIcon(R.drawable.brand_ruby, 0xFFCC342D),
        "rust" to BrandIcon(R.drawable.brand_rust, 0xFF000000),
        "safari" to BrandIcon(R.drawable.brand_safari, 0xFF006CFF),
        "samsung" to BrandIcon(R.drawable.brand_samsung, 0xFF1428A0),
        "shopify" to BrandIcon(R.drawable.brand_shopify, 0xFF7AB55C),
        "signal" to BrandIcon(R.drawable.brand_signal, 0xFF3B45FD),
        "sketch" to BrandIcon(R.drawable.brand_sketch, 0xFFF7B500),
        "skype" to BrandIcon(R.drawable.brand_skype, 0xFF00AFF0),
        "slack" to BrandIcon(R.drawable.brand_slack, 0xFF4A154B),
        "snapchat" to BrandIcon(R.drawable.brand_snapchat, 0xFFFFFC00),
        "sony" to BrandIcon(R.drawable.brand_sony, 0xFFFFFFFF),
        "soundcloud" to BrandIcon(R.drawable.brand_soundcloud, 0xFFFF5500),
        "sourceforge" to BrandIcon(R.drawable.brand_sourceforge, 0xFFFF6600),
        "spotify" to BrandIcon(R.drawable.brand_spotify, 0xFF1ED760),
        "sqlite" to BrandIcon(R.drawable.brand_sqlite, 0xFF003B57),
        "squarespace" to BrandIcon(R.drawable.brand_squarespace, 0xFF000000),
        "steam" to BrandIcon(R.drawable.brand_steam, 0xFF000000),
        "stripe" to BrandIcon(R.drawable.brand_stripe, 0xFF635BFF),
        "substack" to BrandIcon(R.drawable.brand_substack, 0xFFFF6719),
        "svelte" to BrandIcon(R.drawable.brand_svelte, 0xFFFF3E00),
        "swift" to BrandIcon(R.drawable.brand_swift, 0xFFF05138),
        "synology" to BrandIcon(R.drawable.brand_synology, 0xFFB5B5B6),
        "taobao" to BrandIcon(R.drawable.brand_taobao, 0xFFE94F20),
        "target" to BrandIcon(R.drawable.brand_target, 0xFFCC0000),
        "telegram" to BrandIcon(R.drawable.brand_telegram, 0xFF26A5E4),
    )

    private fun chunk4(): Map<String, BrandIcon> = mapOf(
        "terraform" to BrandIcon(R.drawable.brand_terraform, 0xFF844FBA),
        "threads" to BrandIcon(R.drawable.brand_threads, 0xFF000000),
        "tidal" to BrandIcon(R.drawable.brand_tidal, 0xFF000000),
        "tiktok" to BrandIcon(R.drawable.brand_tiktok, 0xFF000000),
        "todoist" to BrandIcon(R.drawable.brand_todoist, 0xFFE44332),
        "trello" to BrandIcon(R.drawable.brand_trello, 0xFF0052CC),
        "tripadvisor" to BrandIcon(R.drawable.brand_tripadvisor, 0xFF34E0A1),
        "truenas" to BrandIcon(R.drawable.brand_truenas, 0xFF0095D5),
        "tumblr" to BrandIcon(R.drawable.brand_tumblr, 0xFF36465D),
        "twitch" to BrandIcon(R.drawable.brand_twitch, 0xFF9146FF),
        "twitter" to BrandIcon(R.drawable.brand_twitter, 0xFF1DA1F2),
        "typescript" to BrandIcon(R.drawable.brand_typescript, 0xFF3178C6),
        "uber" to BrandIcon(R.drawable.brand_uber, 0xFF000000),
        "ubiquiti" to BrandIcon(R.drawable.brand_ubiquiti, 0xFF0559C9),
        "ubisoft" to BrandIcon(R.drawable.brand_ubisoft, 0xFF000000),
        "ubuntu" to BrandIcon(R.drawable.brand_ubuntu, 0xFFE95420),
        "uniqlo" to BrandIcon(R.drawable.brand_uniqlo, 0xFFFF0000),
        "vercel" to BrandIcon(R.drawable.brand_vercel, 0xFF000000),
        "viber" to BrandIcon(R.drawable.brand_viber, 0xFF7360F2),
        "vim" to BrandIcon(R.drawable.brand_vim, 0xFF019733),
        "vimeo" to BrandIcon(R.drawable.brand_vimeo, 0xFF1AB7EA),
        "virtualbox" to BrandIcon(R.drawable.brand_virtualbox, 0xFF2F61B4),
        "visa" to BrandIcon(R.drawable.brand_visa, 0xFF1A1F71),
        "vivo" to BrandIcon(R.drawable.brand_vivo, 0xFF415FFF),
        "vmware" to BrandIcon(R.drawable.brand_vmware, 0xFF607078),
        "vultr" to BrandIcon(R.drawable.brand_vultr, 0xFF007BFC),
        "webex" to BrandIcon(R.drawable.brand_webex, 0xFF000000),
        "webstorm" to BrandIcon(R.drawable.brand_webstorm, 0xFF000000),
        "wechat" to BrandIcon(R.drawable.brand_wechat, 0xFF07C160),
        "weibo" to BrandIcon(R.drawable.brand_weibo, 0xFFDF2029),
        "whatsapp" to BrandIcon(R.drawable.brand_whatsapp, 0xFF25D366),
        "wikipedia" to BrandIcon(R.drawable.brand_wikipedia, 0xFF000000),
        "windows" to BrandIcon(R.drawable.brand_windows, 0xFF0078D4),
        "wix" to BrandIcon(R.drawable.brand_wix, 0xFF0C6EFC),
        "woocommerce" to BrandIcon(R.drawable.brand_woocommerce, 0xFF96588A),
        "wordpress" to BrandIcon(R.drawable.brand_wordpress, 0xFF21759B),
        "x" to BrandIcon(R.drawable.brand_x, 0xFF000000),
        "xbox" to BrandIcon(R.drawable.brand_xbox, 0xFF107C10),
        "xiaomi" to BrandIcon(R.drawable.brand_xiaomi, 0xFFFF6900),
        "yahoo" to BrandIcon(R.drawable.brand_yahoo, 0xFF6001D2),
        "yandex" to BrandIcon(R.drawable.brand_yandex, 0xFFFC3F1D),
        "youtube" to BrandIcon(R.drawable.brand_youtube, 0xFFFF0000),
        "zhihu" to BrandIcon(R.drawable.brand_zhihu, 0xFF0084FF),
        "zoho" to BrandIcon(R.drawable.brand_zoho, 0xFFE42527),
        "zoom" to BrandIcon(R.drawable.brand_zoom, 0xFF0B5CFF),
    )
}
