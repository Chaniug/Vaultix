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
import io.vaultix.model.VaultItemType
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
        // ⚠️ times 不用写：`Entry` 数据类默认 `times = TimeData.create()`
        //    （创建/访问/修改/位置四个时间全填 now）——显式写一遍只会漏掉
        //    lastAccessTime / locationChanged，严格更差。更新侧的时间戳刷新
        //    同样由 kotpass 负责，见 [updateEntry] 里的查证记录。
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
            //
            // ⚠️ 时间戳**不用我们管**（2026-10-16 查证，别"修"）：kotpass 的
            //    `Group.modifyEntry` 内部就会 `times?.copy(lastAccessTime = now,
            //    lastModificationTime = now) ?: TimeData.create()` —— 我们写的
            //    times 会被上游用**原条目的** times 重算后整个盖掉。同理
            //    `createEntry` 靠 `Entry` 数据类默认 `TimeData.create()`（now），
            //    `moveEntry` 刷 `locationChanged`。三处合计：时间戳全链路已有。
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
        // ⚠️ 四个标准字段一律走 [protectedAware]（A6 · 2026-10-16）：原字段带
        //    `Protected="True"`（内存保护，KeePassXC/DX 可给任意字段标）就必须继续带。
        //    此前只有密码做了保留（一个 `existing.password is Encrypted` 分支），
        //    Title/UserName/Notes 无条件写 Plain ⇒ 第三方库里受保护的用户名/备注
        //    被**静默剥掉保护**——值不丢，但内存里不再受流密码保护，
        //    对密码管理器是保真度事故。
        fields = fields + (BasicField.Title.key to protectedAware(existing, BasicField.Title.key, item.title))
        fields = fields + (BasicField.UserName.key to protectedAware(existing, BasicField.UserName.key, item.username))
        fields = fields + (BasicField.Password.key to protectedAware(existing, BasicField.Password.key, item.password))
        fields = fields + (BasicField.Notes.key to protectedAware(existing, BasicField.Notes.key, item.notes))
        fields = applyUris(fields, item = item, before = before)
        fields = applyTotp(fields, item = item, before = before)
        fields = applyPasskeys(fields, item = item, before = before)
        fields = applyFavorite(fields, item = item, before = before)
        fields = applySecureNote(fields, item = item, before = before)
        return applyCustomFields(fields, item = item, before = before)
    }

    /**
     * URI 的**无损降级**（W4）：第 1 条进标准 `Url`，其余进 `VPX_URL_n`。
     *
     * ## 为什么不能只取第一条
     *
     * KDBX 的 `Url` 字段**只装一条**。此前 `applyDomainFields` 直接取
     * `item.uris.firstOrNull()`，多出来的 URL **在写回时凭空消失** ——
     * 一条挂 3 个网址的登录，改一次标题就被抹掉 2 个网址，且无任何提示。
     *
     * ## 为什么应用 URI（`androidapp://`）要另走字段
     *
     * 把 `androidapp://com.example` 塞进标准 `Url`，KeePassXC/DX 会**当域名去匹配**
     * （永远匹配不上）⇒ 自动填充对 Android 应用**整体失效**。KeePassDX 的做法是
     * 写进 `App Package Name` 字段；Bastion 读的也正是这个键（GPL-3.0，
     * `reference/bastion/.../KeePassKdbxService.kt:3779`，兼容 `PackageName` 等别名）。
     *
     * ## 删除判据
     *
     * 沿用另两处的「before 有、after 没有才是真删除」：清掉**本次不再产生**的
     * `VPX_URL_n` / `VPX_MATCH_n`，但第三方工具写进来的、我们不产出的工具字段一律不动。
     * ⚠️ 下标重排要**整段替换**（见下）—— 用户把 3 条 URL 删到 2 条时，
     * 旧 `VPX_URL_2` 会变成新 `VPX_URL_1` 的值，若只按键名增删，
     * 留下的会是**错位的旧值**（比丢数据更坏：网址还在，但对应关系错了）。
     */
    internal fun applyUris(
        fields: EntryFields,
        item: VaultItem,
        before: VaultItem?,
    ): EntryFields {
        val usable = item.uris.filter { it.uri.isNotBlank() }
        val appFields = KdbxToolFields.appFieldsOf(usable)
        // 标准 Url 只收**非应用** URI；应用 URI 走 App Package Name 字段。
        val webUris = usable.filterNot { KdbxToolFields.isAndroidAppUri(it.uri) }
        val desired = LinkedHashMap<String, String>()
        desired.putAll(KdbxToolFields.fromUris(usable))
        desired.putAll(appFields)

        // ① 清：上一轮产生、本轮不再需要的工具键（含应用字段对）。
        //    ⚠️ 整段删除而不是按键名增删 —— 下标重排时旧 `VPX_URL_2` 的值
        //    会变成新 `VPX_URL_1`，按键名删会留下**错位的旧值**。
        var result = fields.minus(previousToolKeys(before).filter { key -> key !in desired })

        // ② 标准 Url：值未变则**一个字都不动**（同 applyTotp 的纪律）。
        val primary = webUris.firstOrNull()?.uri.orEmpty()
        if (result[BasicField.Url.key]?.content != primary) {
            result = result + (BasicField.Url.key to EntryValue.Plain(primary))
        }

        // ③ 增改：值确实变了才写。
        desired.forEach { (key, value) ->
            if (result[key]?.content == value) return@forEach
            result = result + (key to EntryValue.Plain(value))
        }
        return result
    }

    /**
     * 上一轮（`before`）会产出的全部工具区键名（`VPX_URL_n` / `VPX_MATCH_n` / 应用字段对）。
     *
     * 全部走 [KdbxToolFields] 的两个产出函数重算一遍 —— **不另抄一份键名清单**，
     * 抄一份就会与写侧漂移（那正是 W1 立`KdbxFieldKeys` 单一真源要治的病）。
     */
    private fun previousToolKeys(before: VaultItem?): List<String> {
        if (before == null) return emptyList()
        return LinkedHashSet<String>().apply {
            addAll(KdbxToolFields.fromUris(before.uris).keys)
            addAll(KdbxToolFields.appFieldsOf(before.uris).keys)
        }.toList()
    }

    /**
     * 标准字段的写入值（A6 · 2026-10-16）：**原字段带内存保护就继续带**。
     *
     * KDBX 的 `Protected="True"` 是**内存保护**标记（值在进程内存里过流密码），
     * KeePassXC / KeePassDX 允许给**任意**字段标，不只密码。kotpass 用
     * [EntryValue.Encrypted] 表达这个状态，[EntryValue.Plain] 则是裸值。
     *
     * 读方向用 `.content` 两种都能读（受保护的自动解密）⇒ "读得到"不代表
     * "应该改写成哪种"：原本受保护的继续受保护、原本明文的保持明文。
     *
     * 此前的写法只有密码做了保留（一个 `existing.password is Encrypted` 分支），
     * Title / UserName / Notes 无条件写 Plain ⇒ 第三方库里受保护的用户名 /
     * 备注被**静默剥掉保护**：值不丢，但保护属性丢了 —— 对密码管理器是
     * 保真度事故，且用户无从察觉。
     *
     * ⚠️ 语义与 Kotlin 侧的 typed accessor（`EntryFields.password` 等）逐字等价：
     * 都是 `fields[BasicField.X.key]` 的 Map.get（大小写敏感，已知坑，见
     * [KdbxToolFields.extras] 的说明）—— 泛化不改变任何既有行为。
     */
    private fun protectedAware(existing: EntryFields, key: String, value: String): EntryValue =
        if (existing[key] is EntryValue.Encrypted) {
            EntryValue.Encrypted(EncryptedValue.fromString(value))
        } else {
            EntryValue.Plain(value)
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
        var result = fields.minus(removed)

        // ② 增改：值或可见性任一变过才写（没变的不动，保持原表示）。
        val previousByName = before?.customFields.orEmpty().associateBy { it.name }
        desired.values.forEach { field ->
            // R1 铁律（W1）：保留键（标准 / OTP / 通行密钥）不可被自定义字段覆盖。
            // kotpass 键名大小写敏感，判区必须自己折叠大小写；名为 "Title"/"title" 的
            // 自定义字段会直接覆盖库名（现网 bug，数据毁），命中即跳过，绝不写入。
            if (KdbxFieldKeys.isReserved(field.name)) {
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

    /**
     * 通行密钥写回（W2）：把 [VaultItem.fido2Credentials] 写成 `KPEX_PASSKEY_*` 字段。
     *
     * ⚠️ 必须走**专属通道**，不能塞进 [applyCustomFields]：后者按 R1 跳过保留键，
     *   而通行密钥区正是保留区；反过来若走自定义字段，私钥 PEM 又会被明文展示在详情页。
     *
     * ⚠️ 私钥 PEM / credentialId / userHandle 按 [KdbxPasskeyCodec.isProtectedField]
     *   **受保护**写入（`EntryValue.Encrypted`）：写明文等于
     *   「KDBX 被别人打开即泄露私钥」（施工单 §4 风险表）。
     */
    internal fun applyPasskeys(
        fields: EntryFields,
        item: VaultItem,
        before: VaultItem?,
    ): EntryFields {
        val desired = LinkedHashMap<String, String>()
        item.fido2Credentials.forEachIndexed { index, credential ->
            desired.putAll(KdbxPasskeyCodec.fromCredential(credential, index))
        }
        // KeePassDX 的空占位标记：表示「这个条目是通行密钥条目」。
        if (item.fido2Credentials.isNotEmpty()) {
            desired[KdbxPasskeyCodec.FIELD_PASSKEY] = ""
        }
        // 删除判据沿用 applyCustomFields：「before 有、after 没有」才是真删除 ——
        // 第三方工具写进来的、领域模型没读出来的键一律不动。
        val previous = ArrayList<String>()
        if (before?.fido2Credentials?.isNotEmpty() == true) {
            previous += KdbxPasskeyCodec.FIELD_PASSKEY
        }
        before?.fido2Credentials.orEmpty().forEachIndexed { index, credential ->
            previous += KdbxPasskeyCodec.fromCredential(credential, index).keys
        }
        var result = fields.minus(previous.filter { key -> key !in desired })
        desired.forEach { (key, value) ->
            if (result[key]?.content == value) return@forEach
            result = result + (key to if (KdbxPasskeyCodec.isProtectedField(key)) {
                EntryValue.Encrypted(EncryptedValue.fromString(value))
            } else {
                EntryValue.Plain(value)
            })
        }
        return result
    }

    /**
     * 收藏（A1 · 2026-10-13）：[VaultItem.favorite] ⇄ `VPX_FAVORITE` 工具字段。
     *
     * ## 写入纪律（与另几个区同构）
     *
     * 1. **值未变则一个字都不动**：`before.favorite == after.favorite` 直接返回 ——
     *    保持字段的原始形态（别的工具/旧版本写的 `"1"` 继续是 `"1"`，
     *    一次无关编辑不该把用户的库改写得满是 `"true"`）；
     * 2. **收藏 ⇒ 写 `"true"`**（不写 `"false"`，见 [KdbxToolFields.FAVORITE] 的 KDoc）；
     * 3. **取消收藏 ⇒ 删键**：缺失即假，与"从未收藏"在文件里同形。
     *
     * ## 为什么不过 R1 保留区、也不进自定义字段
     *
     * 它有**专属通道**（同通行密钥）：`VPX_` 前缀不在 [KdbxFieldKeys.isReserved]
     * 的判区里（那会让它被 `applyCustomFields` 跳过而永远写不进去），
     * 读侧 `customFieldsOf` 又按工具区把它排除（不会以自定义字段身份出现在详情页）。
     * 两个方向都不漏，正是"专属键区"的结构性好处。
     */
    internal fun applyFavorite(
        fields: EntryFields,
        item: VaultItem,
        before: VaultItem?,
    ): EntryFields {
        // ① 没变过就不碰（含新建时 before == null 的全量路径）。
        if (before != null && before.favorite == item.favorite) return fields
        val existingKey = fields.keys.firstOrNull {
            it.equals(KdbxToolFields.FAVORITE, ignoreCase = true)
        }
        return when {
            item.favorite -> {
                if (fields[KdbxToolFields.FAVORITE]?.content == KdbxToolFields.FAVORITE_TRUE) {
                    fields
                } else {
                    fields + (KdbxToolFields.FAVORITE to EntryValue.Plain(KdbxToolFields.FAVORITE_TRUE))
                }
            }
            // ② 取消收藏：删键（含第三方可能写的大小写变体）。
            existingKey != null -> fields.minus(existingKey)
            else -> fields
        }
    }

    /**
     * 安全笔记类型标记（A4 · 2026-10-16）：[VaultItem.type] ⇄ `VPX_SECURE_NOTE` 工具字段。
     *
     * ## 为什么必须显式存类型
     *
     * KDBX 没有条目类型，读侧只能推导。推导规则（无用户名 + 无密码 + 有笔记）在
     * **空笔记的安全笔记**上静默翻转：三个标准字段全空 ⇒ 读回变成登录
     * （用户明明建的是安全笔记）。⇒ Vaultix 自建的条目带标记，读侧 marker 优先；
     * 第三方条目没有标记，仍走启发式（兼容不动）。
     *
     * ## 写入纪律（与 [applyFavorite] 同构）
     *
     * 1. **类型未变则一个字都不动**（`before.type == after.type` 直接返回）——
     *    一次无关编辑不重写标记，第三方写的 `"1"` 原样保留；
     * 2. **安全笔记 ⇒ 写 `"true"`**（已是真值则不碰）；
     * 3. **类型离开安全笔记 ⇒ 删键**（含第三方可能写的大小写变体）——
     *    删了才回落到启发式，与"从未标记"同形。
     *
     * ⚠️ 与收藏不同，这里**不写 `"false"`**的理由见 [KdbxToolFields.SECURE_NOTE] 的 KDoc：
     * 缺失不等于登录（第三方笔记型条目靠启发式），给每条登录镶恒假字段纯属噪音。
     */
    internal fun applySecureNote(
        fields: EntryFields,
        item: VaultItem,
        before: VaultItem?,
    ): EntryFields {
        // ① 没变过就不碰（含新建时 before == null 的全量路径）。
        if (before != null && before.type == item.type) return fields
        val existingKey = fields.keys.firstOrNull {
            it.equals(KdbxToolFields.SECURE_NOTE, ignoreCase = true)
        }
        return when {
            item.type == VaultItemType.SecureNote -> {
                if (fields[KdbxToolFields.SECURE_NOTE]?.content == KdbxToolFields.SECURE_NOTE_TRUE) {
                    fields
                } else {
                    fields + (KdbxToolFields.SECURE_NOTE to EntryValue.Plain(KdbxToolFields.SECURE_NOTE_TRUE))
                }
            }
            // ② 类型离开安全笔记：删键（回落到启发式，与"从未标记"同形）。
            existingKey != null -> fields.minus(existingKey)
            else -> fields
        }
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
