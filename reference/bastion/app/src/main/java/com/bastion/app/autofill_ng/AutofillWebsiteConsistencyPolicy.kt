package com.bastion.app.autofill_ng

/**
 * P2：条目级 website 双向一致性校验（对齐 bitwarden `FilledDataBuilderImpl.fillLoginPartition`
 * 的第二道防线，采用「仅拒绝明确矛盾」的宽松版）。
 *
 * bitwarden 在构建 dataset 时逐字段校验 `data.website == cipher.website` 或
 * `androidapp://pkg == cipher.website`，不一致的 partition 直接丢弃。Bastion 若照搬「严格
 * 相等」，会让大量未填 website / appPackageName 的 KeePass 裸条目漏填。因此本策略只在存在
 * **明确矛盾**时拒绝，无信息可判时一律放行：
 *
 * 1. **web 页面**（pageWebDomain 非空）——域名是唯一判定轴：
 *    - 条目 website 含至少一个 web 域名时，只要**任一**域名的注册域与页面注册域一致即放行
 *      （支持子域名 / www 前缀 / http(s) / 端口 / 多 URI 换行分隔 / 大小写）；
 *      全部不一致 → 明确矛盾，拒绝；
 *    - 条目无任何 web 域名（如仅 androidapp:// 或空白）→ 无矛盾可判，放行
 *      （页面包名是浏览器包名，对 web 登录无判定意义）。
 * 2. **原生页面**（pageWebDomain 为空）——包名是判定主轴，标题为「包名过期」兜底：
 *    - 条目 appPackageName 或 website 中的 androidapp:// 包名存在时，只要**任一**等于当前
 *      包名即放行；
 *    - 包名全部不一致时，若条目标题/App 名与当前应用显示名归一化后互相包含（如
 *      「电影猎手」↔「电影_猎手」）→ 视为「包名过期」而非矛盾，放行（混淆/加固包名 App
 *      重打包会换包名，条目里的旧包名属常见情况）；无标题证据 → 明确矛盾，拒绝；
 *    - 条目无任何包名（如仅存服务官网域名）→ 放行（KeePass 常见约定：原生 App 登录也存
 *      服务官网域名）；
 * 3. **条目无 website 且无包名** → 放行（KeePass 裸条目 / WiFi 条目）。
 *
 * 注册域按「最后两级 label」近似（不引入公共后缀表，对 .com/.cn/.net 等常见域足够；
 * 最坏情况只是放行边缘情况，符合「仅拒绝矛盾」的定位）。IP 地址按完整 host 精确比较。
 */
internal object AutofillWebsiteConsistencyPolicy {

    /**
     * 判定条目与当前页面是否一致（无明确矛盾）。
     *
     * @param entryWebsite 条目的 website 字段（可为空 / URL / androidapp://pkg / 多 URI 换行分隔）
     * @param entryAppPackage 条目的 appPackageName 字段（可为空）
     * @param entryTitle 条目标题（供「包名过期」场景的标题证据判定）
     * @param entryAppName 条目关联应用名（同上）
     * @param pageWebDomain 当前页面的 web 域名（null 表示原生 App 上下文）
     * @param pagePackageName 当前页面的包名（可能为空）
     * @param pageAppDisplayName 当前应用显示名（供标题证据判定，可为空）
     */
    fun isConsistent(
        entryWebsite: String?,
        entryAppPackage: String?,
        entryTitle: String?,
        entryAppName: String?,
        pageWebDomain: String?,
        pagePackageName: String?,
        pageAppDisplayName: String?,
    ): Boolean {
        val entryHosts = extractWebHosts(entryWebsite)
        val entryPackages = (extractPackages(entryWebsite) + normalizePackage(entryAppPackage))
            .filterNotNull()
            .distinct()

        val pageHost = normalizeHost(pageWebDomain)
        if (pageHost != null) {
            // web 页面：域名是唯一判定轴
            if (entryHosts.isEmpty()) return true // 无 web 域名信息 → 无矛盾可判
            val pageKey = registrableKey(pageHost)
            return entryHosts.any { registrableKey(it) == pageKey }
        }

        // 原生页面：包名是判定主轴，标题为「包名过期」场景的强证据兜底。
        val pagePkg = normalizePackage(pagePackageName)
        if (entryPackages.isEmpty()) return true // 无包名信息 → 无矛盾可判
        if (pagePkg == null) return true // 页面包名未知 → 无法判定矛盾
        if (entryPackages.any { it == pagePkg }) return true // 包名一致 → 放行

        // 包名全部与当前包不一致：混淆/加固包名 App 每次重打包包名都可能变化，条目里存的
        // appPackageName（或 androidapp:// URI）过期属常见情况——若条目标题/App 名与当前
        // 应用显示名一致（如「电影猎手」↔「电影_猎手」），视为「包名过期」而非「明确绑定
        // 其它 App」的矛盾，放行；无标题证据的包名矛盾仍然拒绝（P2 防误填保留）。
        return hasTitleEvidence(entryTitle, entryAppName, pageAppDisplayName)
    }

    /**
     * 标题级强证据：条目标题或 App 名与页面应用显示名在归一化后互相包含。
     * 归一化：小写 + 去掉下划线/连字符/空格/点（「电影_猎手」→「电影猎手」）。
     */
    private fun hasTitleEvidence(
        entryTitle: String?,
        entryAppName: String?,
        pageAppDisplayName: String?,
    ): Boolean {
        if (pageAppDisplayName.isNullOrBlank()) return false
        val pageKey = normalizeTitle(pageAppDisplayName) ?: return false
        if (pageKey.length < 2) return false
        val entryKeys = listOfNotNull(entryTitle?.let(::normalizeTitle), entryAppName?.let(::normalizeTitle))
        return entryKeys.any { key ->
            key.length >= 2 && (key.contains(pageKey) || pageKey.contains(key))
        }
    }

    private fun normalizeTitle(value: String): String? {
        val normalized = value.lowercase().replace(TITLE_SEPARATORS, "").trim()
        return normalized.takeIf { it.isNotBlank() }
    }

    // --- 归一化与解析 ---

    private fun extractWebHosts(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(WHITESPACE).mapNotNull { uri ->
            val token = uri.trim()
            if (token.isBlank() || token.startsWith("androidapp://") || token.startsWith("android-app://")) {
                return@mapNotNull null
            }
            val host = parseHost(token) ?: return@mapNotNull null
            host
        }.distinct()
    }

    private fun extractPackages(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(WHITESPACE).mapNotNull { uri ->
            val token = uri.trim()
            when {
                token.startsWith("androidapp://") -> normalizePackage(token.removePrefix("androidapp://"))
                token.startsWith("android-app://") -> normalizePackage(token.removePrefix("android-app://"))
                else -> null
            }
        }.filterNotNull()
    }

    /** 解析任意 URL/裸 host 为纯 host（小写、去 www、去端口、去路径、去尾点）。 */
    private fun parseHost(raw: String): String? {
        val lowered = raw.lowercase()
        val noScheme = lowered.substringAfter("://", lowered)
        val host = noScheme
            .substringBefore('/')
            .substringBefore(':')
            .trim()
            .trimEnd('.')
            .removePrefix("www.")
        return host.takeIf { it.isNotBlank() }
    }

    private fun normalizeHost(value: String?): String? =
        value?.let { parseHost(it) }

    private fun normalizePackage(value: String?): String? {
        if (value.isNullOrBlank()) return null
        var v = value.trim()
        v = v.removePrefix("androidapp://").removePrefix("android-app://")
        v = v.substringBefore('/').trimEnd('/').trim()
        return v.lowercase().takeIf { it.isNotBlank() }
    }

    /**
     * 注册域 key：IP 或 ≤2 段 label 返回自身；否则取最后两级（如 login.example.com → example.com）。
     * 注意：不含公共后缀表，对 .com.cn 这类二级公共后缀会返回 com.cn（与 example.com 区分开），
     * 符合「不同注册域 = 矛盾」的预期。
     */
    private fun registrableKey(host: String): String {
        val labels = host.split('.')
        return if (labels.size <= 2) host else labels.takeLast(2).joinToString(".")
    }

    private val WHITESPACE = Regex("\\s+")
    private val TITLE_SEPARATORS = Regex("[_\\-\\s.]+")
}
