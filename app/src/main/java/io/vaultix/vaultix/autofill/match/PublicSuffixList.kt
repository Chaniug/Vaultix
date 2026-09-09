/*
 * Vaultix — app:autofill · match
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * 基域（registrable domain）计算，对齐 Bitwarden 对公共后缀列表（PSL）的使用
 * （GPL-3.0，Copyright 2025 JoyinJoester）。此处为精简版多段后缀集合，覆盖常见
 * 双段公共后缀；完整 PSL（约数千条）可后续按 Bitwarden 口径整体替换。
 */
package io.vaultix.vaultix.autofill.match

/** 公共后缀计算（基域 / registrable domain）。 */
object PublicSuffixList {

    // 仅一段标签的主机（localhost / 单标签内网名）直接视为基域，无需再切分。
    private const val MIN_LABELS_FOR_REGISTRABLE_DOMAIN = 2
    // 普通基域取最后两段标签；多段公共后缀的基域取最后三段。
    private const val REGISTRABLE_DOMAIN_LABEL_COUNT = 2
    private const val MULTI_LEVEL_BASE_DOMAIN_LABEL_COUNT = 3

    // 常见多段公共后缀（co.uk / com.cn / com.br …）；命中时基域取倒数三段。
    private val MULTI_LEVEL_SUFFIXES = setOf(
        "co.uk", "org.uk", "gov.uk", "ac.uk", "me.uk",
        "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn",
        "com.br", "com.au", "co.jp", "co.nz", "com.tw", "com.hk",
        "co.in", "com.sg", "com.mx", "com.ar",
    )

    /**
     * 返回 [host] 的基域（注册域）。
     * 例：`sub.example.com` → `example.com`；`sub.example.co.uk` → `example.co.uk`。
     * 不足两段时原样返回；自动忽略前导 `www.`。
     */
    fun baseDomain(host: String): String {
        val h = host.lowercase().removePrefix("www.")
        val labels = h.split('.')
        if (labels.size <= MIN_LABELS_FOR_REGISTRABLE_DOMAIN) return h
        val lastTwo = labels.takeLast(REGISTRABLE_DOMAIN_LABEL_COUNT).joinToString(".")
        return if (MULTI_LEVEL_SUFFIXES.contains(lastTwo)) {
            labels.takeLast(MULTI_LEVEL_BASE_DOMAIN_LABEL_COUNT).joinToString(".")
        } else {
            lastTwo
        }
    }
}
