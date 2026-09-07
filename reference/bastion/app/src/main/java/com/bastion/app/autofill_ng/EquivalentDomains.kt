package com.bastion.app.autofill_ng

/**
 * 等价域名表 —— 对齐 bitwarden `CipherMatchingManagerImpl` 的
 * `equivalentDomains` + `globalEquivalentDomains`。
 *
 * 语义：同一组内的域名**共享同一套账号体系**。保存了组内任一域名的凭据，在访问
 * 组内其它域名时应作为**精确匹配**提供 —— bitwarden 对等价域名的判定返回
 * `MatchResult.EXACT`（而非 FUZZY），见 `checkUriForDomainMatch`：
 * ```
 * matchingDomains.exactMatches.contains(domain) -> MatchResult.EXACT
 * ```
 * 其中 `exactMatchDomains` 就是由命中的等价域名组整体填充的。
 *
 * ⚠️ **安全提醒：本表是安全相关数据**。
 * 一旦把两个并不共享账号体系的域名列为等价，就会把 A 站的密码提供给 B 站。
 * bitwarden 的等价域名由**服务端下发**；本 fork 无服务端，故在此内置一份
 * 高确信度的最小集合。扩展时务必逐个确认，宁缺勿滥。
 *
 * 收录原则：仅收录「同一公司、同一登录体系、换域名后账号不变」这类公开且广为人知的组合。
 */
internal object EquivalentDomains {

    /** 每组内域名互为等价；组间互不等价。 */
    private val GROUPS: List<Set<String>> = listOf(
        // Google 账号体系统辖
        setOf(
            "google.com",
            "gmail.com",
            "googlemail.com",
            "youtube.com",
        ),
        // Microsoft 账号体系统辖
        setOf(
            "microsoft.com",
            "live.com",
            "outlook.com",
            "hotmail.com",
            "office.com",
            "xbox.com",
        ),
        // Apple ID 统辖
        setOf(
            "apple.com",
            "icloud.com",
            "me.com",
            "mac.com",
        ),
        // Yahoo 账号体系统辖
        setOf(
            "yahoo.com",
            "ymail.com",
            "rocketmail.com",
        ),
    )

    private val groupIndexByDomain: Map<String, Int> by lazy {
        val map = HashMap<String, Int>(GROUPS.sumOf { it.size } * 2)
        GROUPS.forEachIndexed { index, group ->
            group.forEach { domain -> map[domain] = index }
        }
        map
    }

    /**
     * 判断两个域名是否等价：同一域名，或同属一个等价组。
     *
     * 入参应当是**基域**（先经 [PublicSuffixList.baseDomain] 归约），
     * 直接传完整 host 会因 `www.`/子域前缀而比较不出结果。
     */
    fun isEquivalent(left: String, right: String): Boolean {
        if (left.isBlank() || right.isBlank()) return false
        if (left == right) return true
        val leftGroup = groupIndexByDomain[left] ?: return false
        val rightGroup = groupIndexByDomain[right] ?: return false
        return leftGroup == rightGroup
    }
}
