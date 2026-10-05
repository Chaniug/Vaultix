/*
 * Vaultix — data:kdbx
 * Copyright (C) 2026 Vaultix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * **KDBX 条目写回**（M2 阶段 B · 批次 W1）。
 *
 * ## 唯一正确的做法：改内存里的 `KeePassDatabase`，不经领域模型重建
 *
 * 每条写操作都是「取现有库 → 只覆盖**领域模型承载的字段** → 返回新库」。
 * 之所以能保证不丢东西：kotpass 的模型是**不可变 data class**，
 * `copy(...)` 会原样带过所有我们没碰的字段（`KPEX_*`、未知自定义字段、附件、
 * 自定义图标、历史记录……）。⇒ **本文件任何地方都不允许"从领域模型重建一条 Entry"**
 * 那种写法 —— 重建必然丢掉领域模型没有建模的部分（見 8.3 保真铁律）。
 *
 * ## 三个容易出事的地方，各自的对策
 *
 * 1. **未变更的字段不重写**：例如 OTP。若把读出来的 otpauth URI 无条件写回 `otp` 字段，
 *    那些原本用 `TimeOtp-Secret-Hex` / `TOTP Seed` 存储的库会被**改写成另一种表示** ——
 *    值也许等价，但文件内容变了、别的工具的展示也随之变。⇒ **只在"用户确实改了"时才动它**，
 *    这就必须拿到 `before`（所以 [updateEntry] 要两个 item 参数，而不是只有一个）。
 * 2. **`moveEntry` 是"先删后加"**：目标组若不存在，条目会被删掉、加不回来 ⇒ **静默丢数据**。
 *    ⇒ 所有移动前都先确认目标组存在（[existingGroupUuid]），否则退回根组。
 * 3. **改条目必须留历史**（KeePass 语义）：走 `withHistory`，且**不可事后补**
 *    （见施工单 §2.7）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.kdbx

import app.keemobile.kotpass.constants.BasicField
import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.getEntry
import app.keemobile.kotpass.database.modifiers.modifyEntry
import app.keemobile.kotpass.database.modifiers.modifyGroup
import app.keemobile.kotpass.database.modifiers.modifyParentGroup
import app.keemobile.kotpass.database.modifiers.moveEntry
import app.keemobile.kotpass.database.modifiers.removeEntry
import app.keemobile.kotpass.database.modifiers.withHistory
import app.keemobile.kotpass.database.modifiers.withRecycleBin
import app.keemobile.kotpass.models.Entry
import app.keemobile.kotpass.models.EntryFields
import app.keemobile.kotpass.models.Group
import app.keemobile.kotpass.models.EntryValue
import io.vaultix.model.CustomFieldType
import io.vaultix.model.VaultItem
import java.util.UUID

/** 一次写操作的结果：新库 + 受影响的条目 uuid + **改动是否真的发生了**。 */
internal class KdbxWriteResult(
    val database: KeePassDatabase,
    /** 受影响的条目 uuid；null = 连 id 都解析不出来（调用方据此报错，而不是假装成功）。 */
    val entryUuid: UUID?,
    /**
     * 这次改动**是否真的发生了**。
     *
     * ⚠️ 与 [entryUuid] 分工不同：`entryUuid` 只表达"能不能解析出条目 id"，
     * 而 `applied = false` 还覆盖「**解析得出、但库里没有这一条**」——
     * 上游的 `modifyEntry` / `moveEntry` 找不到目标时**原样返回旧库、不抛异常**。
     * ⇒ 只看 `entryUuid` 会把"什么都没改"当成成功：上层随即落盘一份**内容未变**的文件，
     * 用户看到"保存成功"，而他的改动一个字都没进去（这类静默失败最难查）。
     */
    val applied: Boolean = true,
)

/** 条目在**回收站里**的说明（供 UI 文案用；我们按"是否位于回收站子树"判定）。 */
internal object KdbxItemWriter {

    /**
     * 新建条目。
     *
     * @param folderId 目标分组（`kdbx-group:<uuid>`）；null = 根组。
     *   ⚠️ 分组不存在时退回**根组**而不是静默失败：`modifyGroup` 找不到 uuid 就原样返回，
     *   那样条目会**看起来保存成功但哪里都没有**（list 里永远不出现）。
     */
    fun createEntry(database: KeePassDatabase, folderId: String?, item: VaultItem): KdbxWriteResult {
        val entry = Entry(
            uuid = UUID.randomUUID(),
            fields = applyDomainFields(existing = EntryFields.createDefault(), item = item, before = null),
        )
        val target = existingGroupUuid(database, folderId)
        val updated = if (target == null) {
            database.modifyParentGroup { copy(entries = entries + entry) }
        } else {
            database.modifyGroup(target) { copy(entries = entries + entry) }
        }
        return KdbxWriteResult(updated, entry.uuid)
    }

    /**
     * 修改条目 —— **走 `withHistory`**（改前那一版进历史，KeePass/XC 里能看到）。
     *
     * @param before 改动前的领域模型（**必须提供**）：用来判定"哪些字段真的变了"，
     *   从而做到"没变的不重写"（见文件头第 1 条）。拿不到 before 就只能全量覆盖，
     *   那会把 OTP 等字段的原始表示改掉。
     * @return 目标条目不存在时**原样返回**（调用方据此报"条目不存在"，
     *   而不是写出一份看起来成功、实际什么都没改的结果）。
     */
    fun updateEntry(database: KeePassDatabase, before: VaultItem, after: VaultItem): KdbxWriteResult {
        val uuid = entryUuidOf(after.id) ?: return KdbxWriteResult(database, null, applied = false)
        val existing = database.getEntry { it.uuid == uuid }?.second
            ?: return KdbxWriteResult(database, uuid, applied = false)

        val updated = database.modifyEntry(uuid) {
            // ⚠️ `withHistory` 必须在**最外层**：它把"改前这一版"存成快照，
            //    再返回修改结果并把快照追加进结果的 history。
            withHistory {
                copy(fields = applyDomainFields(existing = fields, item = after, before = before))
            }
        }
        return KdbxWriteResult(updated, uuid)
    }

    /**
     * 删除 = **移进回收站**（KDBX 的语义；对齐 Bitwarden 的"软删除进回收站"）。
     *
     * 库还没有回收站时先建（`withRecycleBin` 幂等）。
     */
    fun moveToRecycleBin(database: KeePassDatabase, itemId: String): KdbxWriteResult {
        val uuid = entryUuidOf(itemId) ?: return KdbxWriteResult(database, null, applied = false)
        // 🔴 存在性守卫（2026-10-02 审计修复）：缺了它，删一个**不存在的**条目会
        //    静默"成功" —— `moveEntry` 找不到目标时**不抛异常、原样返回旧库**，
        //    于是 `applied` 保持默认 true ⇒ 上层去落盘一份内容完全没变的文件，
        //    而用户看到「已移入回收站」（其实什么都没发生）。
        //    对密码管理器而言最坏的一种错：**谎报成功**。
        database.getEntry { it.uuid == uuid }
            ?: return KdbxWriteResult(database, uuid, applied = false)
        // ⚠️ `withRecycleBin` 的 block 必须返回**库**（它自己的返回类型就是库），
        //    所以先把库算出来、再包成结果 —— 别想着在 block 里直接返回 KdbxWriteResult。
        val moved = database.withRecycleBin { recycleBinUuid -> moveEntry(uuid, recycleBinUuid) }
        return KdbxWriteResult(moved, uuid)
    }

    /**
     * 从回收站恢复。
     *
     * 回**原分组**（`Entry.previousParentGroup`，由 `moveEntry` 写入）；
     * 原分组已经不在了就退回根组。
     *
     * ⚠️ 这个守卫是必须的：`moveEntry` 是"先删后加"，目标组不存在时条目会**消失**。
     */
    fun restoreFromRecycleBin(database: KeePassDatabase, itemId: String): KdbxWriteResult {
        val uuid = entryUuidOf(itemId) ?: return KdbxWriteResult(database, null, applied = false)
        // ⚠️ `getEntry` 的谓词是**普通** lambda（`(Entry) -> Boolean`）⇒ 用 `it`；
        //    而下面 `getGroupBy` 的是**接收者** lambda（`Group.() -> Boolean`）⇒ 用 `this`。
        //    两者只差一个 `Get`/`By` 后缀，混用会直接编译不过（本项目实测过两次）。
        val entry = database.getEntry { it.uuid == uuid }?.second
            ?: return KdbxWriteResult(database, uuid, applied = false)
        val target = entry.previousParentGroup
            // 同样不用 `getGroupBy`（见 [existingGroupUuid] 的说明）。
            ?.takeIf { previous -> database.content.group.containsGroup(previous) }
            ?: database.content.group.uuid
        return KdbxWriteResult(database.moveEntry(uuid, target), uuid)
    }

    /**
     * 永久删除（不进回收站）。
     *
     * ⚠️ 上游会**同时写墓碑** `DeletedObjects` —— 那是给同步用的"这条没了"记录，
     * 别以为它多余而清掉。
     */
    fun permanentDelete(database: KeePassDatabase, itemId: String): KdbxWriteResult {
        val uuid = entryUuidOf(itemId) ?: return KdbxWriteResult(database, null, applied = false)
        // 🔴 存在性守卫（2026-10-02 审计修复）：与 [moveToRecycleBin] 同源 ——
        //    `removeEntry` 找不到 uuid 时同样静默返回旧库，`applied` 仍旧为 true
        //    ⇒ 用户看到"已永久删除"，条目却还在原地（下次打开还在列表里）。
        database.getEntry { it.uuid == uuid }
            ?: return KdbxWriteResult(database, uuid, applied = false)
        return KdbxWriteResult(database.removeEntry(uuid), uuid)
    }

    // ------------------------------------------------------------------ 字段映射

    /**
     * 把**领域模型承载的字段**覆盖到 [existing] 上；其余字段一律原样保留。
     *
     * @param before 改动前的领域模型；null = 新建（没有"变没变"可言，OTP 全量按 after 写）。
     */
    private fun applyDomainFields(
        existing: EntryFields,
        item: VaultItem,
        before: VaultItem?,
    ): EntryFields {
        var fields = existing
        fields = fields + (BasicField.Title.key to EntryValue.Plain(item.title))
        fields = fields + (BasicField.UserName.key to EntryValue.Plain(item.username))
        fields = fields + (BasicField.Password.key to passwordValue(existing, item.password))
        fields = fields + (BasicField.Notes.key to EntryValue.Plain(item.notes))
        // KDBX 只有**一个** URL 字段（读方向也只映射成一条 VaultUri）⇒ 取第一条。
        // 多出来的 URL 是无处可存的，由调用方在 UI 层限制（见 KdbxItemWriter 的文件头说明）。
        fields = fields + (BasicField.Url.key to EntryValue.Plain(item.uris.firstOrNull()?.uri.orEmpty()))
        fields = applyTotp(fields, item = item, before = before)
        return applyCustomFields(fields, item = item, before = before)
    }

    /**
     * 密码字段：**保持它原本的"是否受保护"形态**。
     *
     * KeePass 的约定是密码字段 `Protected="True"`（内存/磁盘上都加密），但历史与第三方
     * 工具写过明文。读方向用 `fields.password?.content` 两种都能读，所以"读得到"不代表
     * "应该改写成哪种"。⇒ 原本受保护的继续受保护、原本明文的保持明文，
     * 避免一次编辑把用户的字段保护策略悄悄翻面。
     */
    private fun passwordValue(existing: EntryFields, password: String): EntryValue =
        if (existing.password is EntryValue.Encrypted) {
            EntryValue.Encrypted(EncryptedValue.fromString(password))
        } else {
            EntryValue.Plain(password)
        }

    /**
     * TOTP：**只在真的变了的时候才动**（见文件头第 1 条）。
     *
     * 写法用 `otp` 字段装 `otpauth://` URI —— 那是 KeePassXC 的现行约定，我们自己的读方向
     * （`KdbxTotpCodec.toOtpAuthUri` 认 `FIELD_OTP`）也认。**同时清掉**旧的
     * `TimeOtp-*` / `TOTP Seed` 系列：同一个库里有两种表示且在打架时，
     * 不同工具会各读各的、显示出两个不同的验证码。
     *
     * 判据用"值与 before 是否相同"而不是"字段在不在"：读方向已经把各种形态归一成了 URI，
     * 所以**值相同 ⇒ 用户没改过** ⇒ 一个字都不动（原来用 Hex 存法的继续用 Hex）。
     */
    private fun applyTotp(fields: EntryFields, item: VaultItem, before: VaultItem?): EntryFields {
        val unchanged = before != null && before.totp == item.totp
        if (unchanged) return fields

        // ⚠️ 必须按**实际键名**筛，不能 `minus(OTP_FIELD_NAMES)`：
        //    `OTP_FIELD_NAMES` 里全是**小写**（`isOtpFieldName` 会先 lowercase 再比），
        //    而 `EntryFields.minus` 是**精确匹配** ⇒ 拿它去减 `TimeOtp-Secret-Hex`
        //    这种混合大小写的真实键名，一个都减不掉（本文件单测实测：字段没被清掉）。
        //    ⇒ 统一用码本的判据 `isOtpFieldName`（读方向也用它），别再抄一份键名清单。
        val otpKeys = fields.keys.filter { KdbxTotpCodec.isOtpFieldName(it) }
        val cleared = fields.minus(otpKeys)
        val secret = item.totp?.takeIf { it.isNotBlank() } ?: return cleared
        return cleared + (KdbxTotpCodec.FIELD_OTP to EntryValue.Plain(secret))
    }

    /**
     * 自定义字段：**按名字逐个对齐**，不是整体替换。
     *
     * 保留的键（5 个标准字段 + OTP 系列 + 通行密钥系列）**绝不触碰** ——
     * 它们在领域模型里各有专属载体，若被当成"用户删掉的自定义字段"清理掉，
     * 后果是**通行密钥静默失效**（`KPEX_*` 一丢，条目上的 passkey 就没了，不可逆）。
     *
     * 删除判据是"**在 before 里有、在 after 里没有**"（真正的删除动作），
     * 而不是"不在 after 里"（那会把第三方工具写进来的、领域模型没读出来的键一起清掉）。
     */
    internal fun applyCustomFields(fields: EntryFields, item: VaultItem, before: VaultItem?): EntryFields {
        val desired = item.customFields
            .filter { it.name.isNotBlank() }
            .associate { it.name to it }

        // ① 删：只删"上次有、这次没有了"的那几个。
        val removed = before?.customFields.orEmpty()
            .map { it.name }
            .filter { name -> name.isNotBlank() && name !in desired }
        // ★ W1（JSON⇄KDBX 无损互转）：被 R1 跳过的保留键计数（命中即不写入，见下）。
        var skippedReserved = 0
        var result = fields.minus(removed)

        // ② 增改：值或可见性任一变过才写（没变的不动，保持原表示）。
        val previousByName = before?.customFields.orEmpty().associateBy { it.name }
        desired.values.forEach { field ->
            // R1 铁律（W1）：保留键（标准 / OTP / 通行密钥）不可被自定义字段覆盖。
            // kotpass 键名大小写敏感，判区必须自己折叠大小写；名为 "Title"/"title" 的
            // 自定义字段会直接覆盖库名（现网 bug，数据毁），命中即跳过，绝不写入。
            if (KdbxFieldKeys.isReserved(field.name)) {
                skippedReserved++
                return@forEach
            }
            val previous = previousByName[field.name]
            if (previous != null && previous.value == field.value && previous.type == field.type) {
                return@forEach
            }
            result = result + (
                field.name to if (field.type == CustomFieldType.Hidden) {
                    EntryValue.Encrypted(EncryptedValue.fromString(field.value))
                } else {
                    EntryValue.Plain(field.value)
                }
                )
        }
        return result
    }

    // ------------------------------------------------------------------ 解析

    /**
     * `kdbx-group:<uuid>` → uuid；**并确认这个组在库里真的存在**。
     *
     * 不存在就返回 null（= 落到根组）。这个守卫针对的是上游 `modifyGroup` 的行为：
     * 匹配不到 uuid 时**原样返回**，于是条目"保存成功"却哪里都不在。
     *
     * ## ⚠️ 为什么存在性判断自己遍历，不用上游的 `getGroupBy`
     *
     * 单测里实测到 **`KeePassDatabase.getGroupBy` 会返回 null，即使谓词对根组恒真**
     * 且该组确实在 `content.group.groups` 里（同一份数据上 `content.group.groups.any {...}`
     * 是 true，而 `getGroupBy { uuid == 那个 uuid }` 是 false）。
     * 上游源码看着没问题（`if (predicate(root)) root else findChildGroup(...)`），
     * 但**行为与源码不符的第三方 API 不能拿来做数据安全判断** ——
     * 用它的后果是"分组找不到 ⇒ 静默落到根组"，正是本守卫要防的那件事。
     * ⇒ 自己走一遍树：逻辑只有几行，且**确定性**由我们掌握。
     */
    private fun existingGroupUuid(database: KeePassDatabase, folderId: String?): UUID? {
        val uuid = groupUuidOf(folderId) ?: return null
        return uuid.takeIf { candidate -> database.content.group.containsGroup(candidate) }
    }

    /** 深度优先找分组（含根组自身）。 */
    private fun Group.containsGroup(target: UUID): Boolean {
        if (uuid == target) return true
        return groups.any { it.containsGroup(target) }
    }
}

/** 与 [folderIdOf] 同源（改一处要改两处 —— 两个函数放在同一个文件里就是为了这个）。 */
internal fun groupUuidOf(folderId: String?): UUID? =
    folderId?.removePrefix(FOLDER_ID_PREFIX)?.let { runCatching { UUID.fromString(it) }.getOrNull() }

/** 与 [itemIdOf] 同源。 */
internal fun entryUuidOf(itemId: String): UUID? =
    itemId.removePrefix(ENTRY_ID_PREFIX).let { runCatching { UUID.fromString(it) }.getOrNull() }

private const val FOLDER_ID_PREFIX = "kdbx-group:"
private const val ENTRY_ID_PREFIX = "kdbx-entry:"
