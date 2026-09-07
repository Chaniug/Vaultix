package com.bastion.app.autofill_ng

/**
 * 字段结构化提升策略。
 *
 * 对齐 bitwarden `AutofillParserImpl.updateForMissingUsernameFields` 的判据：不靠精度分数，
 * 而是靠字段之间的**结构关系**来认定账号框。
 *
 * 背景：WebView 登录页（Via 等轻量浏览器）会把账号框和密码框都报成
 * `TYPE_TEXT_VARIATION_WEB_EDIT_TEXT`，两者都没有任何可用的 hint / 术语信号。密码框还能靠
 * password 术语找回，账号框则完全无迹可循——唯一可靠的线索是「它就在密码框上面」。
 *
 * 早期实现把这类无信号字段一律兜底成 `USERNAME:LOWEST`，等于用「弱肯定」表达「未知」，
 * 导致孤立的搜索栏也被当成低置信账号框放行（京东搜索栏误弹密码条目）。本策略改为：
 * 无信号字段先归入 UNKNOWN（明确的否定），仅在满足结构条件时才提升为账号框。
 */
internal object AutofillFieldPromotionPolicy {

    /**
     * 参与提升判定所需的最小字段信息。刻意不依赖解析器内部模型，以便纯 JVM 单测。
     */
    data class Candidate(
        /** 是否为密码类字段（PASSWORD / NEW_PASSWORD） */
        val isPassword: Boolean,
        /** 是否为账号类字段（USERNAME / EMAIL_ADDRESS / PHONE_NUMBER） */
        val isAccount: Boolean,
        /** 是否为未识别字段（UNKNOWN），即可提升的候选来源 */
        val isUnknown: Boolean,
        /** 字段当前是否可见 */
        val isVisible: Boolean,
        /** 节点遍历序号，用于确定「上下相邻」关系 */
        val traversalIndex: Int,
    )

    /**
     * 表示「无候选可提升」的返回值。
     */
    const val NO_PROMOTION: Int = -1

    /**
     * 在 [candidates] 中挑出应被提升为账号框的那一个，返回其在列表中的下标；
     * 不满足提升条件时返回 [NO_PROMOTION]。
     *
     * 提升需同时满足三个条件，缺一不可：
     * 1. **存在密码框** —— 孤立的搜索栏没有密码框相伴，因此永远不会被提升；
     * 2. **尚无账号框** —— 已经识别出账号框时不再画蛇添足；
     * 3. **紧邻密码框之上**（候选列表下标 +1 即密码框）—— 只认这一个。
     *
     * 另要求候选可见，避免把已隐藏的历史输入框错认成当前账号框（与账号类字段的
     * 可见性要求保持一致）。
     *
     * 第 3 条严格对齐 bitwarden `AutofillParserImpl.updateForMissingUsernameFields` 原文：
     * ```
     * if (autofillView is AutofillView.Unused && passwordPositions.contains(index + 1))
     * ```
     * 早期实现用的是「首个密码框之前、**离它最近**」——不限距离，于是 Edge 访问 GitHub 时
     * 顶部搜索框（离页面内某个密码框最近但并不相邻）被提升成账号框，聚焦它就弹出密码条目。
     *
     * 实现要点：bitwarden 直接比较 `autofillViews` 的列表下标，因为那份列表天然按
     * 遍历顺序构建。**本仓库不能照抄** —— 调用方传入的 `candidates` 顺序并不保证等于
     * `traversalIndex` 顺序（见 `returnsListIndexNotTraversalIndex` 用例），
     * 直接拿列表下标判 `+1` 会漏提升。
     * 因此这里**先按 traversalIndex 排序**，在排序后的序列上判「紧邻」，
     * 再返回该候选在**原始列表中的下标**（调用方用它去取 `items[promotionIndex]`）。
     *
     * 另注意：不能用 `traversalIndex + 1 == passwordTraversalIndex` 判相邻 ——
     * 两个输入框之间隔着容器 / 标签等非字段节点，traversalIndex 差值远大于 1，
     * 那样判定永远不成立，会直接废掉整条召回路径（Via 登录页会崩）。
     */
    fun selectUsernameNeighborIndex(candidates: List<Candidate>): Int {
        if (candidates.none { it.isPassword }) return NO_PROMOTION
        if (candidates.any { it.isAccount }) return NO_PROMOTION

        // 按遍历顺序（即视觉上的先后）排序，保留原始列表下标以便返回。
        val ordered = candidates
            .mapIndexed { listIndex, candidate -> listIndex to candidate }
            .sortedBy { (_, candidate) -> candidate.traversalIndex }

        for (i in 0 until ordered.lastIndex) {
            val (listIndex, candidate) = ordered[i]
            val (_, next) = ordered[i + 1]
            if (candidate.isUnknown && candidate.isVisible && next.isPassword) {
                return listIndex
            }
        }
        return NO_PROMOTION
    }
}
