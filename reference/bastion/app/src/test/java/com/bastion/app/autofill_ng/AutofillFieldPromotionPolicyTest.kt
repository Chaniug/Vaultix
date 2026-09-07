package com.bastion.app.autofill_ng

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 纯 JVM 单测：验证「密码框上方邻居提升」策略 [AutofillFieldPromotionPolicy]，
 * 不依赖 AssistStructure，因此无需 Robolectric。
 *
 * 覆盖用户指定的回归场景与边界：
 * - 京东搜索栏：孤立 WEB_EDIT_TEXT（UNKNOWN）无密码框相伴 → 不提升（不误弹）；
 * - Via 等轻量浏览器登录页：账号框是 UNKNOWN 且紧邻密码框上方 → 提升为账号框（不漏弹）；
 * - 电影猎手等原生登录页：已显式识别账号框 → 不再提升，避免重复/错位；
 * - 边界：多密码框、多 UNKNOWN、UNKNOWN 位于密码框之后、不可见 UNKNOWN。
 */
class AutofillFieldPromotionPolicyTest {

    /**
     * 京东搜索栏：页面只有一个孤立的 WEB_EDIT_TEXT（现在归为 UNKNOWN），没有密码框。
     * 缺少「存在密码框」这一前提 → 永不提升，杜绝误弹。
     */
    @Test
    fun jdSearchBar_isolatedUnknownWithoutPassword_isNotPromoted() {
        val index = AutofillFieldPromotionPolicy.selectUsernameNeighborIndex(
            listOf(
                candidate(isUnknown = true, isVisible = true, traversalIndex = 5),
            )
        )
        assertEquals(AutofillFieldPromotionPolicy.NO_PROMOTION, index)
    }

    /**
     * Via 登录页：账号框与密码框都被报成 WEB_EDIT_TEXT，账号框归为 UNKNOWN，
     * 且它位于密码框之前（上方）。应被提升为账号框下标。
     */
    @Test
    fun viaLoginPage_unknownAbovePassword_isPromoted() {
        val index = AutofillFieldPromotionPolicy.selectUsernameNeighborIndex(
            listOf(
                candidate(isUnknown = true, isVisible = true, traversalIndex = 0),
                candidate(isPassword = true, isVisible = true, traversalIndex = 10),
            )
        )
        assertEquals(0, index)
    }

    /**
     * 电影猎手等原生登录页：已经显式识别出账号框（USERNAME/EMAIL/PHONE 之一），
     * 不再画蛇添足去提升其它 UNKNOWN 字段。
     */
    @Test
    fun nativeLoginPage_withExplicitAccount_isNotPromoted() {
        val index = AutofillFieldPromotionPolicy.selectUsernameNeighborIndex(
            listOf(
                candidate(isUnknown = true, isVisible = true, traversalIndex = 0),
                candidate(isAccount = true, isVisible = true, traversalIndex = 5),
                candidate(isPassword = true, isVisible = true, traversalIndex = 10),
            )
        )
        assertEquals(AutofillFieldPromotionPolicy.NO_PROMOTION, index)
    }

    /**
     * 边界：密码框上方存在多个 UNKNOWN，只提升离它最近（traversalIndex 最大）的那个，
     * 不波及页面其它文本框。
     */
    @Test
    fun multipleUnknownAbovePassword_onlyClosestIsPromoted() {
        val index = AutofillFieldPromotionPolicy.selectUsernameNeighborIndex(
            listOf(
                candidate(isUnknown = true, isVisible = true, traversalIndex = 0),
                candidate(isUnknown = true, isVisible = true, traversalIndex = 5),
                candidate(isPassword = true, isVisible = true, traversalIndex = 10),
            )
        )
        assertEquals(1, index)
    }

    /**
     * 边界：UNKNOWN 位于密码框之后（下方），不满足「上方邻居」条件 → 不提升。
     */
    @Test
    fun unknownBelowPassword_isNotPromoted() {
        val index = AutofillFieldPromotionPolicy.selectUsernameNeighborIndex(
            listOf(
                candidate(isPassword = true, isVisible = true, traversalIndex = 10),
                candidate(isUnknown = true, isVisible = true, traversalIndex = 20),
            )
        )
        assertEquals(AutofillFieldPromotionPolicy.NO_PROMOTION, index)
    }

    /**
     * 边界：以首个（最靠上）密码框为锚点。位于两个密码框之间的 UNKNOWN 因
     * traversalIndex >= 首个密码框，仍不被提升；只认该密码框上方的那一个。
     */
    @Test
    fun multiplePasswords_anchorIsFirstPassword() {
        val index = AutofillFieldPromotionPolicy.selectUsernameNeighborIndex(
            listOf(
                candidate(isUnknown = true, isVisible = true, traversalIndex = 0),
                candidate(isPassword = true, isVisible = true, traversalIndex = 10),
                candidate(isUnknown = true, isVisible = true, traversalIndex = 15),
                candidate(isPassword = true, isVisible = true, traversalIndex = 30),
            )
        )
        assertEquals(0, index)
    }

    /**
     * 边界：不可见的 UNKNOWN 不会被错当成当前账号框（与账号类字段可见性要求一致）。
     */
    @Test
    fun hiddenUnknownAbovePassword_isNotPromoted() {
        val index = AutofillFieldPromotionPolicy.selectUsernameNeighborIndex(
            listOf(
                candidate(isUnknown = true, isVisible = false, traversalIndex = 0),
                candidate(isPassword = true, isVisible = true, traversalIndex = 10),
            )
        )
        assertEquals(AutofillFieldPromotionPolicy.NO_PROMOTION, index)
    }

    /**
     * 边界：已存在账号框时，即便密码框上方还有 UNKNOWN，也不应再提升第二个账号框，
     * 防止原生登录页出现重复账号框识别。
     */
    @Test
    fun existingAccountSuppressesUnknownNeighborPromotion() {
        val index = AutofillFieldPromotionPolicy.selectUsernameNeighborIndex(
            listOf(
                candidate(isUnknown = true, isVisible = true, traversalIndex = 0),
                candidate(isAccount = true, isVisible = true, traversalIndex = 5),
                candidate(isPassword = true, isVisible = true, traversalIndex = 10),
            )
        )
        assertEquals(AutofillFieldPromotionPolicy.NO_PROMOTION, index)
    }

    /**
     * 边界：返回值必须是「列表下标」而非 traversalIndex。这里把密码框放在列表首位、
     * 待提升的 UNKNOWN 放在下标 1，验证返回的是 1 而不是它的 traversalIndex。
     */
    @Test
    fun returnsListIndexNotTraversalIndex() {
        val index = AutofillFieldPromotionPolicy.selectUsernameNeighborIndex(
            listOf(
                candidate(isPassword = true, isVisible = true, traversalIndex = 100),
                candidate(isUnknown = true, isVisible = true, traversalIndex = 50),
            )
        )
        assertEquals(1, index)
    }

    private fun candidate(
        isPassword: Boolean = false,
        isAccount: Boolean = false,
        isUnknown: Boolean = false,
        isVisible: Boolean = true,
        traversalIndex: Int,
    ) = AutofillFieldPromotionPolicy.Candidate(
        isPassword = isPassword,
        isAccount = isAccount,
        isUnknown = isUnknown,
        isVisible = isVisible,
        traversalIndex = traversalIndex,
    )
}
