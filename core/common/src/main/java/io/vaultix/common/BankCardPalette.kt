/*
 * Vaultix — core:common
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 一句话：**卡面颜色由「银行」决定，不由「条目」决定。**
 *
 * 上一版按 `item.id.hashCode()` 从 5 组渐变里挑：同一张卡确实稳定，但**招行的两张卡
 * 可能一红一蓝、招行和建行可能撞成同一个蓝** —— 颜色跟"这是哪家银行"毫无关系，
 * 用户没法靠颜色认卡。⇒ 改成按银行名解析，同名恒同色、异名尽量异色。
 *
 * ⚠️ 这个库里没有「发卡行」字段：`VaultCard.brand` 存的是**卡组织**（Visa / 银联），
 *    银行名只可能出现在条目标题或用户手填的 brand 文本里。所以解析分四级：
 *      ① 常见银行关键词表（显式分配、色相故意拉开，保证互不撞色）
 *      ② 表外但能从标题里提出「XX银行」⇒ 用该名字稳定 hash
 *      ③ 连银行名都没有 ⇒ 按卡组织配色
 *      ④ 卡组织也 UNKNOWN ⇒ 退回条目 id 稳定 hash（只兜底，不再是主逻辑）
 *
 * ⚠️ 关键词表**必须按长度降序匹配**：`boc` 是「中国银行」、`bocom` 是「交通银行」，
 *    `cmb` 是「招商」、`cmbc` 是「民生」。按书写顺序 `firstOrNull` 会让短词吃掉长词，
 *    交通银行被染成中行的颜色 —— 这种错在界面上完全看不出原因。
 *
 * ⚠️ 本文件**不 import Compose / Android**：颜色是 ARGB `Long`（与 `Color(0xFF…)` 同形），
 *    UI 层再包成 `Color`。理由是纯领域逻辑要能在 JVM 单测里直接断言，且中文关键词表
 *    属于数据、不属于 UI 文案（放 ui/ 会被 `check_ui_hardcoded_text.py` 当成硬编码中文）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.common

/** 一组卡面渐变色；[bankName] 为 null 表示「没识别出银行，按卡组织上的色」。 */
data class BankCardTone(
    val bankName: String?,
    val startArgb: Long,
    val endArgb: Long,
)

/** 银行 → 卡面配色的唯一判据。 */
object BankCardPalette {

    /**
     * 18 组深色渐变。刻意把色相铺开（红 / 橙 / 蓝 / 绿 / 紫 / 青 / 靛 …），
     * 而不是照抄各家 logo 色 —— 工行、中行、招行都是红色，照抄只会得到三张一样的卡。
     * 全部保持足够暗，卡面上的白字才读得清。
     */
    private val TONES: List<Pair<Long, Long>> = listOf(
        0xFF9E1B4AL to 0xFF540D2AL, // 0  玫红
        0xFFB23A17L to 0xFF63200DL, // 1  朱橙
        0xFF14489BL to 0xFF0A2757L, // 2  深蓝
        0xFF1B6B43L to 0xFF0C3B27L, // 3  森绿
        0xFF8C1D2CL to 0xFF4E0F18L, // 4  酒红
        0xFF3F3D9EL to 0xFF201F57L, // 5  靛紫
        0xFF0E6E7AL to 0xFF073C44L, // 6  孔雀青
        0xFF9A6410L to 0xFF553606L, // 7  琥珀
        0xFF6A3D9EL to 0xFF3A2059L, // 8  紫罗兰
        0xFF0F7A63L to 0xFF084537L, // 9  青绿
        0xFF8F3A2BL to 0xFF4E1D15L, // 10 砖棕
        0xFF3A5673L to 0xFF1D2C3EL, // 11 蓝灰
        0xFF35603AL to 0xFF1A3320L, // 12 松绿
        0xFF7B2352L to 0xFF42112DL, // 13 绯紫
        0xFF2B3440L to 0xFF151A22L, // 14 石墨
        0xFF0F5F8AL to 0xFF073349L, // 15 海蓝
        0xFF5C6224L to 0xFF303413L, // 16 橄榄
        0xFF175C62L to 0xFF0A3034L, // 17 深青
    )

    private data class BankRule(val name: String, val keywords: List<String>, val toneIndex: Int)

    /**
     * 常见发卡行。索引 0~13 各自独占一组色相，绝不重复。
     *
     * ⚠️ 三字母缩写太容易误伤普通英文（"ABC" 会命中 "ABCD card"），农行因此只收
     *    `abcbank` / `agriculturalbank` + 中文名；其余短缩写靠中文名与长英文兜着。
     */
    private val BANKS = listOf(
        BankRule("招商银行", listOf("招商银行", "招商", "招行", "cmb"), 0),
        BankRule("中国工商银行", listOf("工商银行", "工商", "工行", "icbc"), 1),
        BankRule("中国建设银行", listOf("建设银行", "建设", "建行", "ccb"), 2),
        BankRule("中国农业银行", listOf("农业银行", "农业", "农行", "abcbank", "agriculturalbank"), 3),
        BankRule("中国银行", listOf("中国银行", "中行", "boc", "bankofchina"), 4),
        BankRule("交通银行", listOf("交通银行", "交通", "交行", "bocom", "bankofcommunications"), 5),
        BankRule("浦发银行", listOf("浦发银行", "浦发", "浦东发展", "spdb"), 6),
        BankRule("中信银行", listOf("中信银行", "中信", "citic"), 7),
        BankRule("光大银行", listOf("光大银行", "光大", "ceb", "everbright"), 8),
        BankRule("民生银行", listOf("民生银行", "民生", "cmbc"), 9),
        BankRule("平安银行", listOf("平安银行", "平安", "pingan", "pab"), 10),
        BankRule("兴业银行", listOf("兴业银行", "兴业", "cib", "industrialbank"), 11),
        BankRule("邮储银行", listOf("邮储银行", "邮储", "邮政储蓄", "psbc"), 12),
        BankRule("广发银行", listOf("广发银行", "广发", "gdb", "cgb"), 13),
    )

    private data class Keyword(val text: String, val bankName: String, val toneIndex: Int)

    /** 展平后**按长度降序**：长关键词先命中，短词不吃长词（见文件头）。 */
    private val KEYWORDS: List<Keyword> = BANKS
        .flatMap { rule -> rule.keywords.map { Keyword(it, rule.name, rule.toneIndex) } }
        .sortedByDescending { it.text.length }

    /** 卡组织兜底配色：没写银行名时，至少 Visa / 银联 / 万事达各有各的颜色。 */
    private val BRAND_TONES = mapOf(
        CardBrand.VISA to 2,
        CardBrand.MASTERCARD to 1,
        CardBrand.UNIONPAY to 0,
        CardBrand.AMERICAN_EXPRESS to 6,
        CardBrand.DISCOVER to 7,
        CardBrand.JCB to 13,
        CardBrand.DINERS_CLUB to 5,
        CardBrand.MAESTRO to 15,
        CardBrand.MIR to 11,
    )

    private val SEPARATORS = Regex("[\\s\\-_·•.,()（）/]")
    private val BANK_NAME = Regex("[\\u4e00-\\u9fa5]{2,6}银行")
    private val NOT_A_BANK = listOf(
        "银行卡", "我的", "这张", "信用", "储蓄", "借记", "工资", "副卡", "主卡", "所有",
    )

    /**
     * 解析一张卡的卡面配色。
     *
     * @param title 条目标题（用户最爱在这里写「招行工资卡」）
     * @param storedBrand `VaultCard.brand` 原文（可能是卡组织，也可能被填成银行名）
     * @param cardBrand 已由 [CardBrandDetector] 从卡号 / brand 推出的卡组织
     * @param idHash 条目 id 的 hash，**只在最后一级兜底**用
     */
    fun resolve(
        title: String,
        storedBrand: String,
        cardBrand: CardBrand,
        idHash: Int,
    ): BankCardTone {
        val source = normalize("$title $storedBrand")
        val known = KEYWORDS.firstOrNull { source.contains(it.text) }
        if (known != null) {
            return tone(known.bankName, known.toneIndex)
        }

        val extracted = extractBankName(title, storedBrand)
        if (extracted != null) {
            return tone(extracted, Math.floorMod(extracted.hashCode(), TONES.size))
        }

        val brandTone = BRAND_TONES[cardBrand]
        if (brandTone != null) {
            return tone(null, brandTone)
        }
        if (cardBrand != CardBrand.UNKNOWN) {
            return tone(null, Math.floorMod(cardBrand.ordinal, TONES.size))
        }
        return tone(null, Math.floorMod(idHash, TONES.size))
    }

    private fun tone(bankName: String?, index: Int): BankCardTone {
        val (start, end) = TONES[Math.floorMod(index, TONES.size)]
        return BankCardTone(bankName = bankName, startArgb = start, endArgb = end)
    }

    private fun normalize(raw: String): String = raw.lowercase().replace(SEPARATORS, "")

    /** 表外银行：从「宁波银行借记卡」里提出「宁波银行」，作为稳定 hash 的 key。 */
    private fun extractBankName(vararg parts: String): String? = parts
        .asSequence()
        .flatMap { BANK_NAME.findAll(it).map { match -> match.value } }
        .firstOrNull { candidate -> NOT_A_BANK.none { candidate.contains(it) } }
}
