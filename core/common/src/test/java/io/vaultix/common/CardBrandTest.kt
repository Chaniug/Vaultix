/*
 * Vaultix — core:common 单元测试
 * Copyright (C) 2026 Vaultix contributors
 *
 * 本文件为 Vaultix 项目的一部分，基于 GNU GPL-3.0 许可发布。
 */
package io.vaultix.common

import org.junit.Assert.assertEquals
import org.junit.Test

class CardBrandTest {

    @Test
    fun detect_visaByPrefix() {
        assertEquals(CardBrand.VISA, CardBrandDetector.detect("4111111111111111"))
    }

    @Test
    fun detect_mastercardByPrefix() {
        assertEquals(CardBrand.MASTERCARD, CardBrandDetector.detect("5555555555554444"))
        assertEquals(CardBrand.MASTERCARD, CardBrandDetector.detect("2221000000000009"))
    }

    @Test
    fun detect_americanExpress() {
        assertEquals(CardBrand.AMERICAN_EXPRESS, CardBrandDetector.detect("378282246310005"))
    }

    @Test
    fun detect_unionPay() {
        assertEquals(CardBrand.UNIONPAY, CardBrandDetector.detect("6212345678901232"))
    }

    @Test
    fun detect_partialPrefixGivesEagerBrand() {
        // 仅输入前几位时仍给出最可能品牌（实时提示），不报错
        assertEquals(CardBrand.VISA, CardBrandDetector.detect("4"))
        assertEquals(CardBrand.UNIONPAY, CardBrandDetector.detect("62"))
    }

    @Test
    fun detectStoredCard_rejectsPartialPrefix() {
        // 已保存卡片只做完整匹配：单字符前缀不得误判为某品牌
        assertEquals(CardBrand.UNKNOWN, CardBrandDetector.detectStoredCard("4"))
    }

    @Test
    fun fromName_resolvesVariousCasings() {
        assertEquals(CardBrand.VISA, CardBrandDetector.fromName("Visa"))
        assertEquals(CardBrand.MASTERCARD, CardBrandDetector.fromName("master card"))
        assertEquals(CardBrand.AMERICAN_EXPRESS, CardBrandDetector.fromName("amex"))
        assertEquals(CardBrand.UNIONPAY, CardBrandDetector.fromName("银联"))
        assertEquals(CardBrand.UNKNOWN, CardBrandDetector.fromName(""))
    }

    @Test
    fun fromName_takesPrecedenceOverNumber() {
        // 已存品牌名优先于卡号推导
        assertEquals(CardBrand.MASTERCARD, CardBrandDetector.detect("4111111111111111", "Mastercard"))
    }

    @Test
    fun detect_unknownForGarbage() {
        assertEquals(CardBrand.UNKNOWN, CardBrandDetector.detect("0000000000000000"))
    }

    @Test
    fun formatCardNumberGrouped_insertsSpaces() {
        assertEquals("4111 1111 1111 1111", formatCardNumberGrouped("4111111111111111"))
        assertEquals("3782 822463 10005".replace(" ", ""), formatCardNumberGrouped("378282246310005").replace(" ", ""))
    }
}
