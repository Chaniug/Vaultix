/*
 * Vaultix — app:autofill · parser
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.autofill.parser

/**
 * #307：UNKNOWN 字段的**原始控件元数据**诊断串（纯函数，便于 JVM 单测）。
 *
 * ## 为什么需要它
 *
 * 夸克（UC 内核）登录页实测：`fields=2 hints={UNKNOWN=2} targets=0` —— 字段进了表，
 * 但三个信号（autofillHints / inputType / formSignal）一个都没命中。现有日志只打分类
 * **结果**（`seq=UNKNOWN,UNKNOWN`），看不到**为什么**：是白名单漏了 html 属性名，
 * 还是内核压根没给语义。补这段把原始控件属性打出来，一次定位。
 *
 * ## 安全红线（与 [io.vaultix.vaultix.autofill.AutofillLogger] 一致）
 *
 * 只接收**控件元数据**（类名 / 输入类型 / html 属性名与属性值 / 资源 id / 提示文案），
 * 签名里**根本没有** `text` 参数 —— 编译期就不可能把用户输入值、账号、密码写进日志。
 * 所有字符串仍做长度截断，防超长属性刷屏或夹带异常内容。
 */
internal object UnknownFieldDiagnostics {

    /** 单个未知字段的原始元数据。所有字段均可空，缺信号就填 null。 */
    data class Probe(
        val className: String?,
        val inputType: Int,
        val autofillType: Int,
        val htmlTag: String?,
        val htmlAttributes: List<Pair<String, String>>?,
        val idEntry: String?,
        val viewHint: String?,
        val contentDescription: String?,
    )

    /** 单个字符串在日志里的最大长度（截断防刷屏 / 防夹带）。 */
    private const val MAX_PART_LEN = 80

    /** 十六进制进制（detekt MagicNumber：不用裸字面量 16）。 */
    private const val HEX_RADIX = 16

    /**
     * 格式化成一行诊断串：`probe cls=… inputType=0x…(…) a11yType=… tag=… attrs=[…] id=… hint=… cd=…`
     *
     * ⚠️ 只拼控件元数据；`inputType` 同时给十六进制与十进制，便于对照
     * [android.text.InputType] 常量（沙箱 stub jar 里常量值是 0，只能靠数值本身判断）。
     */
    fun format(probe: Probe): String = buildString {
        append("probe ")
        append("cls=${clip(probe.className)} ")
        append("inputType=0x${probe.inputType.toString(HEX_RADIX)}(${probe.inputType}) ")
        append("a11yType=${probe.autofillType} ")
        append("tag=${clip(probe.htmlTag)} ")
        append("attrs=[${formatAttrs(probe.htmlAttributes)}] ")
        append("id=${clip(probe.idEntry)} ")
        append("viewHint=${clip(probe.viewHint)} ")
        append("cd=${clip(probe.contentDescription)}")
    }

    /** html 属性按 `k=v` 列出；无属性输出 `-`。 */
    private fun formatAttrs(attrs: List<Pair<String, String>>?): String =
        if (attrs.isNullOrEmpty()) {
            "-"
        } else {
            attrs.joinToString(",") { (k, v) -> "${clip(k)}=${clip(v)}" }
        }

    /** 截断 + 去换行（防日志注入）：null 输出 `-`。 */
    private fun clip(value: String?): String {
        if (value.isNullOrEmpty()) return "-"
        return value
            .replace('\n', ' ')
            .replace('\r', ' ')
            .let { if (it.length > MAX_PART_LEN) it.take(MAX_PART_LEN) + "…" else it }
    }
}
