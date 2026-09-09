/*
 * Vaultix — core:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 银行卡品牌识别规则（按卡号前缀/长度匹配 visa/mastercard/amex/unionpay 等）
 * 移植自 Bastion 项目（GPL-3.0，Copyright 2025 JoyinJoester）的
 * data/model/CardBrandDetector.kt，按 Vaultix 架构重写为独立、无 Android 依赖的
 * 纯算法模块。Bastion 注释指出其匹配规则源自 MIT 许可的 creditcards-types
 * （Keyguard 所用），此处沿用同一规则集，数值一致。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.common

/**
 * 银行卡品牌。
 * [displayName] 与 Bitwarden `brand` 字段取值一致（如 "Visa" / "Mastercard"），
 * 因此可直接回填到卡片条目的品牌字段，或作为检测结果的回退展示。
 */
enum class CardBrand(val displayName: String) {
    VISA("Visa"),
    MASTERCARD("Mastercard"),
    AMERICAN_EXPRESS("American Express"),
    DINERS_CLUB("Diners Club"),
    DISCOVER("Discover"),
    JCB("JCB"),
    UNIONPAY("UnionPay"),
    MAESTRO("Maestro"),
    MIR("Mir"),
    RUPAY("RuPay"),
    ELO("Elo"),
    DANKORT("Dankort"),
    MADA("Mada"),
    MEEZA("Meeza"),
    TROY("Troy"),
    UATP("UATP"),
    FORBRUGSFORENINGEN("Forbrugsforeningen"),
    UNKNOWN("Card"),
}

/** 卡号展示分组长度（每 N 位插一个空格）。 */
private const val CARD_NUMBER_GROUP_SIZE = 4

/**
 * 银行卡品牌识别（从卡号前缀/长度推导，兼容 Bitwarden 已存品牌名）。
 *
 * 设计要点（对齐 Bastion CardBrandDetector）：
 * - [detect] 允许「部分匹配」：卡号未输全时用 eagerPattern 给出最可能品牌（实时提示）；
 * - [detectStoredCard] 仅做完整匹配（已保存卡片不应被前缀误判）；
 * - 若已存品牌名可解析（[fromName]），优先采用已存品牌，避免重算漂移。
 */
object CardBrandDetector {
    private data class Rule(
        val brand: CardBrand,
        val pattern: Regex,
        val eagerPattern: Regex,
    )

    // MADA 规则集（源自 MIT 许可的 creditcards-types，经 Bastion 归纳），拆行以满足行宽门禁。
    private val madaPattern = (
        "^(4(0(0861|1757|3024|6136|6996|7(197|395)|9201)|1(2565|0621|0685|7633|9593)|" +
            "2(0132|1141|281(7|8|9)|689700|8(331|67(1|2|3)))|3(1361|2328|4107|9954)|" +
            "4(0(533|647|795)|5564|6(393|404|672))|5(5(036|708)|7865|7997|8456)|" +
            "6(2220|854(0|1|2|3))|7(4491)|8(301(0|1)|4783|609(4|5|6)|931(7|8|9))|93428)|" +
            "5(0(4300|6968|8160)|13213|2(0058|1076|4(130|514)|9(415|741))|" +
            "3(0(060|906)|1(095|196)|2013|5(825|989)|6023|7767|9931)|4(3(085|357)|9760)|" +
            "5(4180|7606|8563|8848)|8(5265|8(8(4(5|6|7|8|9)|5(0|1))|98(2|3))|9(005|206)))|" +
            "6(0(4906|5141)|36120)|9682(0(1|2|3|4|5|6|7|8|9)|1(0|1)))\\d{10}$"
        ).toRegex()

    private val madaEagerPattern = (
        "^(4(0(0861|1757|3024|6136|6996|7(197|395)|9201)|1(2565|0621|0685|7633|9593)|" +
            "2(0132|1141|281(7|8|9)|689700|8(331|67(1|2|3)))|3(1361|2328|4107|9954)|" +
            "4(0(533|647|795)|5564|6(393|404|672))|5(5(036|708)|7865|7997|8456)|" +
            "6(2220|854(0|1|2|3))|7(4491)|8(301(0|1)|4783|609(4|5|6)|931(7|8|9))|93428)|" +
            "5(0(4300|6968|8160)|13213|2(0058|1076|4(130|514)|9(415|741))|" +
            "3(0(060|906)|1(095|196)|2013|5(825|989)|6023|7767|9931)|4(3(085|357)|9760)|" +
            "5(4180|7606|8563|8848)|8(5265|8(8(4(5|6|7|8|9)|5(0|1))|98(2|3))|9(005|206)))|" +
            "6(0(4906|5141)|36120)|9682(0(1|2|3|4|5|6|7|8|9)|1(0|1)))"
        ).toRegex()

    // 其余规则集源自 MIT 许可的 creditcards-types（经 Bastion 归纳），见文件头溯源声明。
    private val rules = listOf(
        Rule(
            CardBrand.UNIONPAY,
            "^62[0-5]\\d{13,16}$".toRegex(),
            "^62".toRegex(),
        ),
        Rule(
            CardBrand.ELO,
            (
                "^(4[035]|5[0]|6[235])(6[7263]|9[90]|1[2416]|7[736]|8[9]|0[04579]|5[0])" +
                    "([0-9])([0-9])\\d{10}$"
                ).toRegex(),
            (
                "^(4[035]|5[0]|6[235])(6[7263]|9[90]|1[2416]|7[736]|8[9]|0[04579]|5[0])" +
                    "([0-9])([0-9])"
                ).toRegex(),
        ),
        Rule(CardBrand.MADA, madaPattern, madaEagerPattern),
        Rule(
            CardBrand.MEEZA,
            "^5078(03|08|09|10)\\d{10}$".toRegex(),
            "^5078(03|08|09|10)".toRegex(),
        ),
        Rule(
            CardBrand.MIR,
            "^220[0-4]\\d{12}$".toRegex(),
            "^220[0-4]".toRegex(),
        ),
        Rule(
            CardBrand.TROY,
            "^9792\\d{12}$".toRegex(),
            "^9792".toRegex(),
        ),
        Rule(
            CardBrand.DANKORT,
            "^5019\\d{12}$".toRegex(),
            "^5019".toRegex(),
        ),
        Rule(
            CardBrand.FORBRUGSFORENINGEN,
            "^600722\\d{10}$".toRegex(),
            "^600".toRegex(),
        ),
        Rule(
            CardBrand.UATP,
            "^1\\d{14}$".toRegex(),
            "^1".toRegex(),
        ),
        Rule(
            CardBrand.AMERICAN_EXPRESS,
            "^3[47]\\d{13}$".toRegex(),
            "^3[47]".toRegex(),
        ),
        Rule(
            CardBrand.DINERS_CLUB,
            "^3(0[0-5]|[68]\\d)\\d{11,16}$".toRegex(),
            "^3(0|[68])".toRegex(),
        ),
        Rule(
            CardBrand.DISCOVER,
            (
                "^(6011\\d{12}|65\\d{14}|64[4-9]\\d{13}|622(12[6-9]|1[3-9]\\d|[2-8]\\d{2}|" +
                    "9[01]\\d|92[0-5])\\d{10})$"
                ).toRegex(),
            "^(6011|65|64[4-9]|622(12[6-9]|1[3-9]|[2-8]|9[01]|92[0-5]))".toRegex(),
        ),
        Rule(
            CardBrand.JCB,
            "^35\\d{14}$".toRegex(),
            "^35".toRegex(),
        ),
        Rule(
            CardBrand.MAESTRO,
            "^(5018|5020|5038|5893|6304|6759|6761|6762|6763)\\d{8,15}$".toRegex(),
            "^(5(018|0[23]|[68])|6[37]|60111|60115|60117([56]|7[56])|60118[0-5]|64[0-3]|66)".toRegex(),
        ),
        Rule(
            CardBrand.RUPAY,
            "^(60\\d|65\\d|81\\d|82\\d|508|353|356)\\d{13}$".toRegex(),
            "^(60|65|81|82|508|353|356)".toRegex(),
        ),
        Rule(
            CardBrand.MASTERCARD,
            "^(5[1-5][0-9]{2}|222[1-9]|22[3-9][0-9]|2[3-6][0-9]{2}|27[01][0-9]|2720)\\d{12}$".toRegex(),
            "^(2[3-7]|22[2-9]|5[1-5])".toRegex(),
        ),
        Rule(
            CardBrand.VISA,
            "^4\\d{12}(\\d{3}|\\d{6})?$".toRegex(),
            "^4".toRegex(),
        ),
    )

    /** 品牌名关键词 → 品牌（子串匹配，对齐 Bastion fromName 语义）。 */
    private val brandNameKeywords = listOf(
        "unionpay" to CardBrand.UNIONPAY,
        "chinaunionpay" to CardBrand.UNIONPAY,
        "union" to CardBrand.UNIONPAY,
        "银联" to CardBrand.UNIONPAY,
        "americanexpress" to CardBrand.AMERICAN_EXPRESS,
        "amex" to CardBrand.AMERICAN_EXPRESS,
        "美国运通" to CardBrand.AMERICAN_EXPRESS,
        "dinersclub" to CardBrand.DINERS_CLUB,
        "diners" to CardBrand.DINERS_CLUB,
        "mastercard" to CardBrand.MASTERCARD,
        "master" to CardBrand.MASTERCARD,
        "万事达" to CardBrand.MASTERCARD,
        "visa" to CardBrand.VISA,
        "discover" to CardBrand.DISCOVER,
        "maestro" to CardBrand.MAESTRO,
        "mir" to CardBrand.MIR,
        "rupay" to CardBrand.RUPAY,
        "jcb" to CardBrand.JCB,
        "elo" to CardBrand.ELO,
        "dankort" to CardBrand.DANKORT,
        "mada" to CardBrand.MADA,
        "meeza" to CardBrand.MEEZA,
        "troy" to CardBrand.TROY,
        "uatp" to CardBrand.UATP,
        "forbrugsforeningen" to CardBrand.FORBRUGSFORENINGEN,
    )

    /** 从卡号（允许未输全）识别品牌；[storedBrand] 优先（已存品牌名可解析时直接采用）。 */
    fun detect(number: String, storedBrand: String = ""): CardBrand =
        detectInternal(number = number, storedBrand = storedBrand, allowPartial = true)

    /** 已保存卡片的严格识别：仅完整匹配，避免前缀误判。 */
    fun detectStoredCard(number: String, storedBrand: String = ""): CardBrand =
        detectInternal(number = number, storedBrand = storedBrand, allowPartial = false)

    private fun detectInternal(
        number: String,
        storedBrand: String,
        allowPartial: Boolean,
    ): CardBrand {
        val brandFromName = fromName(storedBrand)
        if (brandFromName != CardBrand.UNKNOWN) {
            return brandFromName
        }

        val digits = number.filter(Char::isDigit)
        if (digits.isBlank()) {
            return CardBrand.UNKNOWN
        }

        val exactBrand = rules.firstOrNull { it.pattern.matches(digits) }?.brand
        if (exactBrand != null || !allowPartial) {
            return exactBrand ?: CardBrand.UNKNOWN
        }

        return rules.firstOrNull { it.eagerPattern.containsMatchIn(digits) }?.brand
            ?: CardBrand.UNKNOWN
    }

    /** 将品牌名（任意大小写/空格/连字符）解析为 [CardBrand]；无法识别返回 [CardBrand.UNKNOWN]。 */
    fun fromName(name: String): CardBrand {
        val normalized = name
            .trim()
            .lowercase()
            .replace(" ", "")
            .replace("-", "")
            .replace("_", "")
        if (normalized.isBlank()) {
            return CardBrand.UNKNOWN
        }

        brandNameKeywords.firstOrNull { normalized.contains(it.first) }?.second?.let { return it }

        return CardBrand.values().firstOrNull {
            it.name.lowercase().replace("_", "") == normalized ||
                it.displayName.lowercase().replace(" ", "") == normalized
        } ?: CardBrand.UNKNOWN
    }
}

/**
 * 卡号展示用分组（每 [CARD_NUMBER_GROUP_SIZE] 位插一个空格，保留原非数字字符以维持掩码形态）。
 * 仅用于界面展示；存储与匹配仍以原始数字串为准。
 */
fun formatCardNumberGrouped(raw: String): String {
    val normalized = raw.filter { it.isDigit() || it == ' ' }
    return normalized
        .replace(" ", "")
        .chunked(CARD_NUMBER_GROUP_SIZE)
        .joinToString(" ")
}
