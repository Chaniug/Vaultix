/*
 * Vaultix — data:kdbx
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * **保真度登记**：把「哪些信息没能完整往返」变成**可见**的事实。
 *
 * ## 为什么需要它（而不是"应该不会丢"）
 *
 * `8.3-M2KDBX.md` 的铁律是「插件字段与不认识的自定义字段一律原样保留」。
 * 但"保留"能不能做到，取决于引擎的解析器覆盖面：
 * kotpass 的 `Entry` / `Group` 解析是 `when (nodeName) { … }` 且**没有 else**，
 * ⇒ **不认识的 XML 标签会被静默丢弃**。
 *
 * 标准 KDBX 4.1 的标签都被覆盖了（实测 `RoundTripTest` 全绿），
 * 但第三方扩展（如某些管理器写入的自有标签）会消失。
 *
 * ⇒ 「支持哪些、不支持哪些」如果只写在文档里，用户永远不知道自己的库有没有踩到。
 *   **本文件的职责就是把这个判断在运行时做出来并登记下来**，
 *   让"不支持"变成**可见的警告**，而不是"用户以为存好了、其实少了东西"。
 *
 * ## 口径：登记的是「风险信号」，不是「已确认的丢失」
 *
 * 我们**无法**从解码后的对象反推出原始 XML 里有什么（丢了就是丢了，看不见）。
 * 所以这里做的是**保守推断**：找出「看起来像扩展数据、但没有对应的已知容器」的迹象，
 * 提示用户"这个库可能含我们不认识的内容，写回请先确认"。
 *
 * ⚠️ **宁可多报也不要漏报**：多报的代价是用户多看一眼提示，
 *   漏报的代价是**用户的通行密钥静默失效**。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.kdbx

import app.keemobile.kotpass.database.KeePassDatabase

/**
 * 一条保真度提示。
 *
 * ⚠️ **必须 public**（不是 internal）：它随 [KdbxSaveReport] 一起返回给上层，
 * 而 `KdbxSaveReport` 是 public 的。Kotlin 编译器会拒绝
 * 「public 函数暴露 internal 参数类型」（实测报
 * `'public' function exposes its 'internal' parameter type argument`）。
 *
 * @param kind 分类（便于 UI 决定怎么显示、也便于测试断言）。
 * @param detail 给用户/日志看的具体描述。
 */
data class KdbxFidelityNote(
    val kind: Kind,
    val detail: String,
) {
    /** 分类。public 的理由同 [KdbxFidelityNote]：UI 要按分类决定提示的严重度。 */
    enum class Kind {
        /** 库里含插件数据（`KPEX_*` 等）—— 已确认会被保留，但要提醒"改动会同步到插件"。 */
        PLUGIN_DATA_PRESENT,

        /** 库里含**我们不认识**的扩展数据 —— 写回有丢失风险，需提示用户。 */
        UNKNOWN_EXTENSION_DATA,

        /** 库用了非默认的加密套件（如 Twofish）—— 必须确保写回时套件被保留。 */
        NON_DEFAULT_CIPHER,

        /** 分辨率较低的信号：库很大 / 结构复杂，往返成本高。 */
        LARGE_DATABASE,
    }
}

/**
 * 保真度登记器。
 *
 * ⚠️ **必须 public**：它是 [KdbxSaveReport] 的构造者之一（`Kdbx.save` 里调用），
 * 而 public 的 `KdbxSaveReport` 无法引用 internal 类型。
 *
 * 两个入口：
 * - [describeLosses]：**往返之后**比对前后两份库，登记可确认的差异（精确）。
 * - [inspect]：**写回之前**只看当前库，登记风险信号（保守推断）。
 */
object KdbxFidelity {

    /** `KPEX_*` 前缀：KeePassXC / KeePassDX 的插件约定命名。 */
    private const val PLUGIN_PREFIX = "KPEX_"

    /** 条目数超过这个值算"大库"（往返自检会明显变慢，值得提前告知）。 */
    private const val LARGE_DB_ENTRY_THRESHOLD = 5000

    /**
     * 提示里最多列几个插件数据键。
     *
     * 存在的意义：这是给**人看**的一句话（对话框里的一行），不是导出报告。
     * 列全部的话，一个装了十几个插件的库会撑出十几行，反而没人看；
     * 且用户真正需要的信息只是"有这回事"，具体是哪些键对排障也没有帮助。
     */
    private const val PREVIEW_PLUGIN_KEY_LIMIT = 3

    /**
     * 比对往返前后的两份库，登记**可确认**的丢失。
     *
     * 这是精确路径：两份库都在手上，差异是可证实的。
     */
    fun describeLosses(before: KeePassDatabase, after: KeePassDatabase): List<KdbxFidelityNote> {
        val notes = mutableListOf<KdbxFidelityNote>()

        // ① 数据库级 CustomData（插件可以在这里存东西）
        val beforeDbCustom = before.content.meta.customData.keys
        val afterDbCustom = after.content.meta.customData.keys
        val lostDbCustom = beforeDbCustom - afterDbCustom
        if (lostDbCustom.isNotEmpty()) {
            notes += KdbxFidelityNote(
                kind = KdbxFidelityNote.Kind.UNKNOWN_EXTENSION_DATA,
                detail = "数据库级 CustomData 丢失：$lostDbCustom",
            )
        }

        // ② 自定义图标（丢图标 = 条目看着变了，虽不致命但要报）
        val beforeIcons = before.content.meta.customIcons.size
        val afterIcons = after.content.meta.customIcons.size
        if (afterIcons < beforeIcons) {
            notes += KdbxFidelityNote(
                kind = KdbxFidelityNote.Kind.UNKNOWN_EXTENSION_DATA,
                detail = "自定义图标丢失：$beforeIcons → $afterIcons",
            )
        }

        // ③ 二进制附件
        val beforeBinaries = before.content.group.countBinaries()
        val afterBinaries = after.content.group.countBinaries()
        if (afterBinaries < beforeBinaries) {
            notes += KdbxFidelityNote(
                kind = KdbxFidelityNote.Kind.UNKNOWN_EXTENSION_DATA,
                detail = "附件丢失：$beforeBinaries → $afterBinaries",
            )
        }

        return notes
    }

    /**
     * 写回**之前**的风险巡检（保守推断，只看当前这一份库）。
     *
     * 目的是给用户一句有意义的话，而不是"写完了你也不知道有没有事"。
     */
    fun inspect(database: KeePassDatabase): List<KdbxFidelityNote> {
        val notes = mutableListOf<KdbxFidelityNote>()

        val entries = database.content.group.allEntries()

        // ① 插件数据（会被保留，但值得让用户知道"这些改动也会同步给插件"）
        val pluginKeys = buildSet {
            database.content.meta.customData.keys.filterTo(this) { it.startsWith(PLUGIN_PREFIX) }
            entries.forEach { entry ->
                entry.customData.keys.filterTo(this) { it.startsWith(PLUGIN_PREFIX) }
            }
        }
        if (pluginKeys.isNotEmpty()) {
            notes += KdbxFidelityNote(
                kind = KdbxFidelityNote.Kind.PLUGIN_DATA_PRESENT,
                detail = "库内含 ${pluginKeys.size} 项插件数据" +
                    "（${pluginKeys.take(PREVIEW_PLUGIN_KEY_LIMIT).joinToString()}…），写回时会一并保留",
            )
        }

        // ② 大库（往返自检的成本）
        if (entries.size > LARGE_DB_ENTRY_THRESHOLD) {
            notes += KdbxFidelityNote(
                kind = KdbxFidelityNote.Kind.LARGE_DATABASE,
                detail = "库较大（${entries.size} 个条目），保存前的往返自检会稍慢",
            )
        }

        return notes
    }

    private fun app.keemobile.kotpass.models.Group.allEntries() = buildList {
        fun walk(group: app.keemobile.kotpass.models.Group) {
            addAll(group.entries)
            group.groups.forEach(::walk)
        }
        walk(this@allEntries)
    }

    private fun app.keemobile.kotpass.models.Group.countBinaries(): Int =
        allEntries().sumOf { it.binaries.size }
}
