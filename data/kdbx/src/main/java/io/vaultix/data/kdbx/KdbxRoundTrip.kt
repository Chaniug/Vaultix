/*
 * Vaultix — data:kdbx
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * **写回前的往返自检** —— 「同步下来的不损坏不错漏」的技术兑现点。
 *
 * ## 为什么要在写之前自检
 *
 * 编码是个**可能悄悄损坏数据**的操作：kotpass 的解析器对不认识的 XML 标签
 * 是静默丢弃的（见 `KdbxEncoder` 的说明）。如果只在"写完之后"发现问题，
 * 用户可能已经把损坏的库同步到网盘、覆盖了别的设备上的好副本。
 *
 * ⇒ 在**落盘之前**做一次 `encode → decode`，逐项比对关键内容；
 *   不一致就**不写**（宁可这次保存失败，也不能把坏库推出去）。
 *
 * ## 判据是「逐字段比对」，不是「字节相等」
 *
 * ⚠️ `encode` 每次都重新生成 IV / KDF 种子（`regenerateVectors`），
 *   **同样的库编码两次字节必然不同**。所以「字节相等」既做不到、
 *   也不是我们真正想要的。
 * ⇒ 比对的必须是**解码后的语义内容**：条目 UUID、字段、CustomData、分组结构。
 *
 * ## 为什么比对「UUID 集合」而不是「整个对象相等」
 *
 * kotpass 的数据类 `equals` 在**顺序**上敏感（`EntryFields` 甚至专门重写 equals
 * 来考虑顺序）。顺序变不等于数据坏 —— 那只是编码顺序差异。
 * ⇒ 关键判据取**集合语义**：同一个 UUID 的条目还在、它的字段和 CustomData 一致。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.kdbx

import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.decode
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.Group
import java.io.ByteArrayInputStream

/**
 * 往返自检的结论。
 *
 * 刻意做成**数据类而不是 Boolean**：失败时要能说清楚"哪儿不一致"，
 * 否则用户只看到「保存失败」，我们和用户都无法判断是不是严重。
 *
 * ⚠️ **必须 public**：它被 [`KdbxWriteFailure.RoundTripFailed`] 持有，
 * 而后者是 public 的失败类型（UI 要读 `mismatches` 才能上报"丢了什么"）。
 * internal 类型不能出现在 public 签名里（编译器实测拒绝）。
 */
data class KdbxRoundTripResult(
    /** 编码后的字节（校验通过时可直接落盘）。 */
    val bytes: ByteArray,
    /** 往返后仍然存在的条目数 / 往返前的条目数。 */
    val entryCountBefore: Int,
    val entryCountAfter: Int,
    /** 逐个 UUID 比对后**不一致**的条目描述（空 = 完全一致）。 */
    val mismatches: List<String>,
    /**
     * ★ 保真度登记：本次往返**丢失**的信息（空 = 没有可察觉的丢失）。
     *
     * 存 [KdbxFidelityNote] 而不是 `String`：`Kdbx.save` 要把它与
     * [KdbxFidelity.inspect] 的结果**合并成同一个 `List<KdbxFidelityNote>`**
     * 交给上层（否则 UI 拿到的两类提示形状不一致，还得自己转换）。
     */
    val fidelityLosses: List<KdbxFidelityNote>,
) {
    /** 是否可以安全落盘。**有 mismatch 就不行**；保真度丢失只登记、不阻断。 */
    val isSafeToWrite: Boolean get() = mismatches.isEmpty() && entryCountAfter >= entryCountBefore

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * 往返自检器。
 *
 * 用法（写回路径）：
 * ```
 * val checked = KdbxRoundTrip.verify(session.database, credentials)
 * if (!checked.isSafeToWrite) return Result.failure(KdbxWriteError.RoundTripFailed(checked))
 * KdbxAtomicWriter.write(target, checked.bytes)
 * ```
 */
internal object KdbxRoundTrip {

    /**
     * 编码 [database] 并立刻解码回来比对。
     *
     * @param credentials 必须是**打开这个库时用的那一组**（否则解不开，会误报成损坏）。
     */
    fun verify(database: KeePassDatabase, credentials: Credentials): KdbxRoundTripResult {
        val before = flatten(database.content.group)
        val beforeByUuid = before.associateBy { it.uuid }

        val bytes = KdbxEncoder.encode(database)

        val decoded = runCatching {
            ByteArrayInputStream(bytes).use { input ->
                KeePassDatabase.decode(
                    inputStream = input,
                    credentials = credentials,
                    cipherProviders = KDBX_CIPHER_PROVIDERS,
                )
            }
        }.getOrElse { error ->
            // 连自己刚写出来的字节都解不开 ⇒ 编码链路有根本问题，绝不能落盘。
            return KdbxRoundTripResult(
                bytes = bytes,
                entryCountBefore = before.size,
                entryCountAfter = 0,
                mismatches = listOf("编码结果无法解码：${error.message}"),
                fidelityLosses = emptyList(),
            )
        }

        val after = flatten(decoded.content.group)
        val afterByUuid = after.associateBy { it.uuid }

        val mismatches = mutableListOf<String>()

        // ① 条目丢失（最严重）
        val lost = beforeByUuid.keys - afterByUuid.keys
        if (lost.isNotEmpty()) {
            mismatches += "往返后丢失 ${lost.size} 个条目"
        }

        // ② 逐个比对「还在的条目」的字段与 CustomData（集合语义，不比顺序）
        for ((uuid, original) in beforeByUuid) {
            val roundTripped = afterByUuid[uuid] ?: continue
            val fieldDiff = diffFields(original, roundTripped)
            if (fieldDiff != null) mismatches += "条目 $uuid：$fieldDiff"
        }

        // ③ 分组结构（名称集合）
        val groupsLost = groupNames(database.content.group) - groupNames(decoded.content.group)
        if (groupsLost.isNotEmpty()) {
            mismatches += "往返后丢失分组：$groupsLost"
        }

        return KdbxRoundTripResult(
            bytes = bytes,
            entryCountBefore = before.size,
            entryCountAfter = after.size,
            mismatches = mismatches,
            // 直接带 KdbxFidelityNote 列表：上层要把它与 inspect() 的结果合并，
            // 形状一致才不用再转换（也就不会在转换里丢掉 kind 分类）。
            fidelityLosses = KdbxFidelity.describeLosses(database, decoded),
        )
    }

    /** 递归摊平所有条目（含子分组）。 */
    private fun flatten(root: Group): List<Entry> {
        val out = mutableListOf<Entry>()
        fun walk(group: Group) {
            out += group.entries
            group.groups.forEach(::walk)
        }
        walk(root)
        return out
    }

    /** 递归取所有分组名。 */
    private fun groupNames(root: Group): Set<String> {
        val out = mutableSetOf<String>()
        fun walk(group: Group) {
            out += group.name
            group.groups.forEach(::walk)
        }
        walk(root)
        return out
    }

    /**
     * 比对两个条目的**字段名集合**与**CustomData 键值**。
     *
     * @return 不一致的描述；null = 一致。
     */
    private fun diffFields(original: Entry, roundTripped: Entry): String? {
        // 字段名（值是加密的，直接比会因 IV 不同而不等 ⇒ 比"有哪些字段"）
        val originalFields = original.fields.keys
        val roundTrippedFields = roundTripped.fields.keys
        if (originalFields != roundTrippedFields) {
            val lost = originalFields - roundTrippedFields
            val added = roundTrippedFields - originalFields
            return "字段集合变化（丢=$lost 多=$added）"
        }

        // ★ CustomData（`KPEX_*` 插件字段就住在这里 —— 丢了等于通行密钥失效）
        val originalCustom = original.customData.mapValues { it.value.value }
        val roundTrippedCustom = roundTripped.customData.mapValues { it.value.value }
        if (originalCustom != roundTrippedCustom) {
            val lost = originalCustom.keys - roundTrippedCustom.keys
            val changed = originalCustom.filter { (k, v) ->
                roundTrippedCustom.containsKey(k) && roundTrippedCustom[k] != v
            }.keys
            return "CustomData 变化（丢=$lost 值变=$changed）"
        }
        return null
    }
}
