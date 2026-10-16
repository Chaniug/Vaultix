/*
 * Vaultix — data:kdbx
 * Copyright (C) 2026 Vaultix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * **转换专用**的 KDBX 出入入口（W5 · Bitwarden JSON ⇄ KDBX 无损互转）。
 *
 * ## 为什么转换不能复用会话制入口（[Kdbx.unlock] / [Kdbx.createItem]）
 *
 * 会话制入口围绕「一个已解锁的库」展开：解锁要登记会话、写回要凭据在场。
 * 而转换的产物 / 来源都是**一次性文件字节**：
 *   - JSON → KDBX：从零造一个**新库**（用户指定的新主密码），与任何已解锁库无关；
 *   - KDBX → JSON：打开一个**还没进库**的文件（用户刚选的 .kdbx，还不是"我们的库"）。
 * 两者都不该往 [KdbxSessionStore] 里登记任何东西 —— 那会让整库明文在内存里
 * 逗留到用户手动锁库，与 Docs/09「明文用完即弃」正相反。
 *
 * ⇒ 本文件的两个函数都是**无会话纯批处理**：字节进、字节出（或领域模型出）。
 *
 * ## 与「写回保真」铁律的关系
 *
 * [KdbxItemWriter] 文件头写着「不许从领域模型重建 Entry」—— 那条纪律管的是
 * **已存在库的修改**（重建会丢 KPEX_* / 附件 / 历史）。本文件的 `build` 看起来
 * 正是在"从领域模型建条目"，但它是**从无到有造新库**：没有既有条目可保真，
 * 每一条都是新建（走 [KdbxItemWriter.createEntry] 而非自制 Entry）。
 * 两条路径不矛盾，别以"保真"为由把 build 也改成 modify 风格。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.kdbx

import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.modifiers.modifyEntry
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryValue
import app.keemobile.kotpass.models.Group
import io.vaultix.model.VaultFolder
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType

/**
 * 转换读出的一次性内容（**不含数据库本体、不含凭据**）。
 *
 * 与 [KdbxUnlockedContent] 的刻意差异：
 * - 不带回收站条目 —— Bitwarden 导出 JSON 没有"已删除"对应物，转过去只会造出
 *   官方客户端读不懂的条目；
 * - 不带 [KdbxMappedContent.groupPaths] / 凭据标签 —— 转换不需要写回，也不需要诊断。
 *
 * ⚠️ [items] 已应用**转换器旁路还原**（见 [KdbxConversionEngine.read]）：
 *   - `id` = `VPX_BW_ID`（保往返幂等）；无该字段时退回**条目 uuid 的裸形态**
 *     （必须是合法 UUID：Bitwarden 官方导入器对非 UUID id 在反序列化阶段直接报错，
 *     见 `BitwardenExportCipher.id` 的注释）；
 *   - `type` = `VPX_BW_TYPE`（带载荷守卫）；无该字段时保持读路径的 Login/SecureNote 推导。
 */
data class KdbxConversionContent(
    val items: List<VaultItem>,
    val folders: List<VaultFolder>,
)

/**
 * 转换引擎（纯逻辑；文件字节由调用方读进来 / 落盘，便于单测直接喂 [ByteArray]）。
 */
internal object KdbxConversionEngine {

    /**
     * 领域条目 → **平铺** KDBX 4.1 库字节（W5 · JSON → KDBX 方向）。
     *
     * ## 为什么用「输出库主密码」而不是复用某个已解锁库的凭据
     *
     * 产物是一个**独立文件**，它的主密码由用户在转换时指定（施工单 W5 流程）——
     * 与源 JSON 的文件密码、与任何已解锁库的密码都无关。三者在密码学上必须分开，
     * 否则"用 A 库的密码加密 B 库的内容"会让用户完全无法预期哪个密码开哪个文件。
     *
     * ## 三条纪律（每条都对应一个具体的坏味道）
     *
     * 1. **平铺**（施工单 §2.4 用户拍板）：Bitwarden 文件夹层级不映射为 KDBX 分组，
     *    全部条目落根组。目录信息的有无由调用方在预览计数里如实告知，不静默。
     * 2. **旁路字段保幂等**：每条写入 `VPX_BW_ID` / `VPX_BW_TYPE`（施工单 §2.2），
     *    反向转换据此还原 id 与类型 —— 转两次不会得到两个新 id。
     * 3. **落盘前自检**（[KdbxRoundTrip.verify]）：编码后立刻解码回来逐字段比对，
     *    不一致就返回失败、**一个字节都不交出去**（与 [Kdbx.save] 同款保障）。
     *
     * ⚠️ **Card / Identity / SshKey 的载荷不在 KDBX 承载范围**（KDBX 无类型概念，
     *    [KdbxItemWriter.applyDomainFields] 只写标准字段 + TOTP / 通行密钥 / 工具区）：
     *    这三类条目转过去只剩标题与备注。这是领域模型 ↔ KDBX 的**既有能力边界**
     *    （Vaultix 的 KDBX 库本来就只有 Login / SecureNote 两种条目形态），
     *    不是本函数的缺陷；调用方必须在预览里计数告知，别让它静默。
     *
     * ⚠️ 编码要跑 KDF（真机数百毫秒），调用方应在 IO/Default 调度器上调用
     *    （本函数刻意不做内部 `withContext`，与 [Kdbx.mutate] 同款约定：
     *    调度交给调用方，避免"两层各自切一半"）。
     */
    fun build(name: String, password: String, items: List<VaultItem>): Result<ByteArray> {
        val credentials = KdbxCreator.credentialsFor(password, keyFileBytes = null)
        var database = KdbxCreator.blankDatabase(name, credentials)
        items.forEach { item ->
            // folderId = null ⇒ 落根组（平铺，见上）。
            val written = KdbxItemWriter.createEntry(database = database, folderId = null, item = item)
            database = written.database
            // createEntry 的 uuid 是它自己生成的，恒非空；空即上游行为变更，直接炸而不是猜。
            val uuid = written.entryUuid ?: error("createEntry 未返回条目 uuid")
            database = database.modifyEntry(uuid) {
                copy(fields = fields + provenanceFields(item))
            }
        }
        val checked = KdbxRoundTrip.verify(database, credentials)
        if (!checked.isSafeToWrite) {
            return Result.failure(KdbxWriteFailure.RoundTripFailed(checked))
        }
        return Result.success(checked.bytes)
    }

    /**
     * 转换器旁路字段（`VPX_BW_ID` / `VPX_BW_TYPE`）—— **只写、不走领域读路径**。
     *
     * 理由见 [KdbxToolFields.BW_ID] / [KdbxToolFields.BW_TYPE] 的 KDoc：
     * 把 BW 原始 id 读回 `item.id` 会让 `entryUuidOf` 解析不出 uuid、写回静默失败；
     * 把 BW 类型读回来会让同一库在两种来源下类型不一致。⇒ 它们只作为**转换器的
     * 旁路信息**存在，由 [read] 直接消费。
     *
     * ⚠️ 两个键都走 [EntryValue.Plain]（不受保护）：id 与类型名不是秘密，
     *    给它们标 `Protected="True"` 只会让第三方工具多弹一次"受保护字段"提示。
     */
    private fun provenanceFields(item: VaultItem): Map<String, EntryValue> = buildMap {
        if (item.id.isNotBlank()) {
            put(KdbxToolFields.BW_ID, EntryValue.Plain(item.id))
        }
        put(KdbxToolFields.BW_TYPE, EntryValue.Plain(KdbxToolFields.typeName(item.type)))
    }

    /**
     * KDBX 字节 → 转换用领域模型（W5 · KDBX → JSON 方向）；**不登记会话**。
     *
     * ## 为什么刻意不 put 进 [KdbxSessionStore]
     *
     * 用户选的文件**可能根本不想加进库**（"我只想把它转成 JSON 扔给官方客户端"）。
     * 登记会话会让整库明文活到手动锁库，还可能覆盖同 id 库的既有会话 ——
     * 一次转换的副作用不该延伸到用户的库列表。
     *
     * ## 失败分类
     *
     * 复用 [KdbxOpener.open] 的 [KdbxOpenError] 家族（不是 KDBX / 版本不支持 /
     * 密码不对），由调用方翻译成用户文案。
     */
    fun read(bytes: ByteArray, password: String): Result<KdbxConversionContent> {
        val session = KdbxOpener.open(bytes = bytes, password = password, keyFileBytes = null)
            .getOrElse { error -> return Result.failure(error) }
        val content = session.database.toMappedContent()
        val provenance = provenanceOf(session.database)
        val items = content.items.map { item -> restoreProvenance(item, provenance[item.id]) }
        return Result.success(KdbxConversionContent(items = items, folders = content.folders))
    }

    /** 应用旁路还原（id + type），见 [read] 与 [KdbxConversionContent] 的说明。 */
    private fun restoreProvenance(item: VaultItem, prov: EntryProvenance?): VaultItem {
        val restoredId = prov?.bwId?.takeIf { it.isNotBlank() }
            // 无 VPX_BW_ID（第三方库 / 用户手建条目）⇒ 退回条目 uuid 的裸形态。
            // ⚠️ 必须剥掉 `kdbx-entry:` 前缀：Bitwarden 官方导入器要求 cipher id 是
            //    合法 UUID（BitwardenExportCipher.id 的注释），带前缀会反序列化报错。
            ?: entryUuidOf(item.id)?.toString()
            ?: item.id
        return item.copy(id = restoredId, type = restoredType(prov?.bwType, item))
    }

    /**
     * `VPX_BW_TYPE` → [VaultItemType]（**带载荷守卫**）。
     *
     * ## 守卫挡的是什么
     *
     * Card/Identity/SshKey 的载荷 KDBX 侧不承载（[build] 不写、读路径不产出）⇒
     * 若照字面还原类型，导出侧 `toExportCard` 的 `requireNotNull(payload)`
     * 会**直接抛异常**，整次转换崩在最后一个条自上。
     * ⇒ 载荷为空时不还原，保持读路径推导的 Login/SecureNote：内容（标题/备注/
     *   自定义字段）不丢，只是类型回退；预览计数会提前告知用户有多少条这类条目。
     *
     * ## 无标记时保持推导
     *
     * `bwType` 为空（第三方库）⇒ 一个字都不改：读路径的 Login/SecureNote 推导
     * 是 KeePass 生态的 convention，与第三方工具的解释一致。
     */
    private fun restoredType(bwType: String?, item: VaultItem): VaultItemType {
        if (bwType.isNullOrBlank()) return item.type
        val restored = KdbxToolFields.typeOf(bwType)
        return when (restored) {
            VaultItemType.Card -> restored.takeIf { item.card != null }
            VaultItemType.Identity -> restored.takeIf { item.identity != null }
            VaultItemType.SshKey -> restored.takeIf { item.sshKey != null }
            else -> restored
        } ?: item.type
    }

    /** 旁路字段采集（全库遍历，含子组；只认本库写出的两个键）。 */
    private fun provenanceOf(database: KeePassDatabase): Map<String, EntryProvenance> {
        val out = mutableMapOf<String, EntryProvenance>()
        fun walk(group: Group) {
            group.entries.forEach { entry ->
                val bwId = fieldValue(entry, KdbxToolFields.BW_ID)
                val bwType = fieldValue(entry, KdbxToolFields.BW_TYPE)
                if (!bwId.isNullOrBlank() || !bwType.isNullOrBlank()) {
                    out[itemIdOf(entry.uuid)] = EntryProvenance(bwId, bwType)
                }
            }
            group.groups.forEach(::walk)
        }
        walk(database.content.group)
        return out
    }

    /**
     * 键名**大小写不敏感**取值。
     *
     * kotpass 的 `EntryFields.get(String)` 是大小写敏感的 `Map.get`（已 javap 核实）——
     * 别的工具改过键名大小写时，敏感 `get` 会静默拿到 null ⇒ 旁路还原整体失效。
     */
    private fun fieldValue(entry: Entry, key: String): String? =
        entry.fields.entries
            .firstOrNull { (name, _) -> name.equals(key, ignoreCase = true) }
            ?.value?.content

    /** 一条旁路记录（内部形态；对外只以还原后的 [VaultItem] 出现）。 */
    private data class EntryProvenance(val bwId: String?, val bwType: String?)
}
