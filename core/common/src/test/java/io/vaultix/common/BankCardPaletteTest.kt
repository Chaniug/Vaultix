/*
 * Vaultix — core:common (test)
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 钉住「颜色由银行决定」这条契约。
 *
 * 上一版按条目 id 挑色，改回去不会报错、肉眼也只觉得"颜色好像变了"——
 * 只有断言能拦住。
 */
class BankCardPaletteTest {

    private fun tone(
        title: String,
        storedBrand: String = "",
        cardBrand: CardBrand = CardBrand.UNKNOWN,
        idHash: Int = 1,
    ): BankCardTone = BankCardPalette.resolve(title, storedBrand, cardBrand, idHash)

    @Test
    fun `同一家银行的不同条目颜色完全一致`() {
        val a = tone("招行工资卡", cardBrand = CardBrand.UNIONPAY, idHash = 11)
        val b = tone("招商银行信用卡", storedBrand = "Visa", cardBrand = CardBrand.VISA, idHash = 999)
        assertEquals(a, b)
        assertEquals("招商银行", a.bankName)
    }

    @Test
    fun `常见银行两两不撞色`() {
        val titles = listOf(
            "招商银行", "工商银行", "建设银行", "农业银行", "中国银行", "交通银行", "浦发银行",
            "中信银行", "光大银行", "民生银行", "平安银行", "兴业银行", "邮储银行", "广发银行",
        )
        val colors = titles.map { tone(it).startArgb }
        assertEquals("常见银行必须各占一组色相", titles.size, colors.distinct().size)
    }

    @Test
    fun `长关键词优先_bocom 不能被 boc 吃掉`() {
        val boc = tone("中国银行储蓄卡", storedBrand = "BOC")
        val bocom = tone("交通银行信用卡", storedBrand = "BOCOM")
        assertEquals("中国银行", boc.bankName)
        assertEquals("交通银行", bocom.bankName)
        assertNotEquals(boc.startArgb, bocom.startArgb)
    }

    @Test
    fun `长关键词优先_cmbc 不能被 cmb 吃掉`() {
        val cmb = tone("CMB 信用卡")
        val cmbc = tone("CMBC 借记卡")
        assertEquals("招商银行", cmb.bankName)
        assertEquals("民生银行", cmbc.bankName)
        assertNotEquals(cmb.startArgb, cmbc.startArgb)
    }

    @Test
    fun `关键词大小写与分隔符归一`() {
        assertEquals(tone("招行卡"), tone("招 商 银 行"))
        assertEquals(tone("ICBC 卡"), tone("工行卡"))
    }

    @Test
    fun `三字母缩写不误伤普通英文_ABCD 不是农行`() {
        val t = tone("ABCD corp card", storedBrand = "Visa", cardBrand = CardBrand.VISA)
        assertNull(t.bankName)
    }

    @Test
    fun `银行名写在 brand 字段里也能认出来`() {
        val t = tone(title = "我的卡", storedBrand = "建设银行")
        assertEquals("中国建设银行", t.bankName)
    }

    @Test
    fun `表外银行按名字稳定 hash_同名同色异名异色`() {
        val a = tone("宁波银行借记卡", idHash = 7)
        val aAgain = tone("宁波银行 另一张", idHash = 4242)
        val b = tone("江苏银行借记卡", idHash = 7)
        assertEquals(a, aAgain)
        assertEquals("宁波银行", a.bankName)
        assertNotEquals(a.startArgb, b.startArgb)
    }

    @Test
    fun `我的银行卡不是银行名`() {
        val t = tone("我的银行卡")
        assertNull(t.bankName)
    }

    @Test
    fun `没写银行名时按卡组织配色`() {
        val visa = tone("工资卡", storedBrand = "Visa", cardBrand = CardBrand.VISA)
        val unionpay = tone("工资卡", storedBrand = "银联", cardBrand = CardBrand.UNIONPAY)
        assertNull(visa.bankName)
        assertNull(unionpay.bankName)
        assertNotEquals(visa.startArgb, unionpay.startArgb)
    }

    @Test
    fun `连卡组织都没有时才退回 id 且必须稳定`() {
        val a = tone("一张卡", idHash = 123)
        val b = tone("一张卡", idHash = 123)
        assertEquals(a, b)
        assertNull(a.bankName)
    }
}
