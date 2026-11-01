/*
 * Vaultix — data:kdbx
 * Copyright (C) 2026 Vaultix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * `VPX_` 工具字段的**单一真源**（W4 · JSON⇄KDBX 无损互转）。
 *
 * ## 为什么要「工具区」这个第三类字段
 *
 * KDBX 只有 5 个标准键，而 Bitwarden 的一个登录条目能装的东西远不止这些：
 * 多条 URI、条目 id、原始type……直接塞进自定义字段会有两个问题：
 *
 * 1. **会被认不出来**：反向转换时看到 `Url2`，无法判断它是「工具生成的第 2 条 URI」
 *    还是用户自己起的名字叫「Url2」⇒ 只能靠猜，猜错就改坏用户的库。
 * 2. **静默丢数据**：KDBX 的 `Url` 字段**只装一条**，多出来的无处可存 ——
 *    不做降级就是直接丢（施工单 §2.2）。
 *
 * ⇒ 凡是「转换器为了无损而生成的字段」，一律加 `VPX_` 前缀（施工单铁律 R2）。
 *   这样它与用户自己的字段**在名字上就分得开**，可逆还原靠的是规则而非猜测。
 *
 * ## 为什么不用 KP2A_ 前缀
 *
 * `KP2A_` 是同域项目 bw2keepass 的标识。照抄的后果是：本库写出的文件，
 * 别人看到 `KP2A_` 会以为「这是 bw2keepass 生成的」——**归属信息是错的**。
 * 用自己的前缀才能让「哪些是 Vaultix 转换器生成的」可识别。
 *
 * ## 与另两个键区的关系（永不相交，见 KdbxFieldKeys）
 *
 * - 标准区 5 键：`Title` / `UserName` / `Password` / `Url` / `Notes`
 * - OTP 区：`otp` / `TOTP Seed` / `TimeOtp-*` / …（见 KdbxTotpCodec）
 * - 通行密钥区：`KPEX_PASSKEY_*`（**KeePassDX 生态标准，必须互通**，见 KdbxPasskeyCodec）
 *
 * ⚠️ 本区与前两者不同：`KPEX_` 是**别人的标准**（照抄才能互通），
 * 而 `VPX_` 是**本应用私有约定**（自己定才可逆）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.kdbx

import io.vaultix.model.CustomFieldType
import io.vaultix.model.UriMatch
import io.vaultix.model.VaultItemType
import io.vaultix.model.VaultUri
import java.util.Locale

/**
 * `VPX_` 工具字段：领域模型 ⇄ KDBX 字段的无损降级通道。
 *
 * ## 它管什么、不管什么（2026-11-01 起分成两个键区）
 *
 * 工具字段**只该承载「领域模型有、KDBX 装不下」的东西**。逐项核过：
 *
 * | 来源 | 领域模型 | KDBX 装得下吗 | 落法 |
 * |---|---|---|---|
 * | 多条 `login.uris` | [VaultItem.uris] 列表 | ❌ `Url` 只装一条 | `VPX_URL_n` + `VPX_MATCH_n` |
 * | Bitwarden 条目 `id` | [VaultItem.id] | 无标准键 | `VPX_BW_ID` |
 * | Bitwarden 条目 `type` | [VaultItemType] | 无标准键 | `VPX_BW_TYPE` |
 * | 卡片 / 身份 / SSH | 载荷对象 | 无标准键，但**生态已有约定** | ⚠️ 见下 |
 * | `passwordHistory` | [VaultItem.passwordHistory] | KDBX 有原生 History | `VPX_PW_HISTORY_n` |
 *
 * ### ⚠️ 卡 / 身份 / SSH 用的是**生态键名**，不是 `VPX_` 前缀
 *
 * `card_*` / `identity_*` 是 KeePassXC 的 Bitwarden 导入器（`BitwardenReader.cpp`）
 * 已经写出去的键名。若本应用改用 `VPX_CARD_*`，双向都不通：
 * 用 KeePassXC 迁过来的库读不到卡，本应用转出的库在 KeePassXC 里也不像卡。
 *
 * ⇒ 判别标准是「**这个键名别人也在用吗**」：
 *   - 别人在用（`KPEX_PASSKEY_*` / `KP2A_URL_n` / `card_*` / `identity_*`）⇒ **照抄，只读不写**或双向写；
 *   - 只有本应用用（`VPX_*`）⇒ 私有约定，自己定才可逆。
 *
 * 这与「为什么不用 `KP2A_` 前缀」（见文件头）并不矛盾：那里说的是**自己的**新字段
 * 不该冒用别人的标识；这里说的是**别人已有的**字段名不该另起一个。
 *
 * ### 密码历史用 `VPX_PW_HISTORY_n` 而不是 KDBX 原生 History
 *
 * `VaultItem.passwordHistory` 已在 #219 建模，格式 `epoch毫秒|密码`，按 `n` **数值**升序。
 *
 * KeePassXC 走的是原生 `<History>`（整条快照 + `lastUsedDate`，见 `BitwardenReader.cpp`），
 * 那条路更"生态正确"，但它有两个**当前挡路**的事实：
 * 1. 原生 History 存的是**整条条目快照**，不是「一个旧密码」——
 *    本应用的领域模型只有「时间 + 密码」两项，写进 History 就得**编造**其余字段的快照值；
 * 2. 读回来要判断"哪一条历史对应哪个旧密码"，只能靠 `Password` 字段差异去猜 —
 *    用户在历史里改过备注、改过用户名，就会被误判成一条新密码。
 *
 * ⇒ 现在用私有键把**语义精确**地存下来；迁移到原生 History 是独立条目
 *   （`.ai/issues`），届时可两者并存读取（先读 History，缺失再读 `VPX_PW_HISTORY_n`）。
 */
internal object KdbxToolFields {

    /** 工具字段前缀（与 [KdbxFieldKeys.TOOL_PREFIX] 同源，此处为码本自留常量）。 */
    const val PREFIX = "VPX_"

    /** 多余 URI 的落法：`VPX_URL_1` / `VPX_URL_2` …（第 0 条进标准 `Url`）。 */
    const val URL_PREFIX = PREFIX + "URL_"

    /**
     * 额外网址的**生态键名**：`KP2A_URL_1` / `KP2A_URL_2` …（**只读不写**）。
     *
     * KeePassXC 的 `BitwardenReader.cpp` 把第 2 条起的网址写成
     * `EntryAttributes::AdditionalUrlAttribute + "_" + n`，而那个常量正是 `KP2A_URL`。
     * 于是所有"用 KeePassXC 从 Bitwarden 迁过来"的库，多出来的网址都躺在这个键名下。
     *
     * 本应用写的是 [URL_PREFIX]（私有前缀，因为要跟 `VPX_MATCH_n` 成对、且不冒认
     * KeePass2Android 的归属）；但**读**必须同时认这一套，否则那批库的多网址
     * 在 Vaultix 里只剩第一条。
     */
    const val LEGACY_URL_PREFIX = "KP2A_URL_"

    /**
     * Bitwarden 条目 id（保往返幂等 —— 转两次不会得到两个新条目）。
     *
     * ⚠️ **只写、不读回 [io.vaultix.model.VaultItem.id]** —— 这是刻意的，
     * 读回会造成**静默失败**：`KdbxItemWriter` 靠 `entryUuidOf(item.id)` 反查 uuid
     * 来定位条目（`updateEntry` / `moveToRecycleBin` / `permanentDelete` 都走它）。
     * 把 Bitwarden 的原始 id 灌进 `item.id` ⇒ `entryUuidOf` 解析不出 uuid ⇒
     * 写回**原样返回旧库并`applied = false`**，用户却看到"保存成功"。
     * ⇒ 保留原始 id 只作为**转换器的旁路信息**，KDBX 侧条目身份永远以自己的 uuid 为准。
     */
    const val BW_ID = PREFIX + "BW_ID"

    /**
     * Bitwarden 条目类型（`login` / `secureNote` / `card` / `identity` / `sshKey`）。
     *
     * ⚠️ 同样**只写**：`VaultItemType` 的 KDBX 侧推导规则是「无用户名无密码有备注 ⇒ SecureNote」
     * （见 `KdbxItemMapper`），把 `VPX_BW_TYPE` 读回来会让同一条目在
     * 「KDBX 原生」与「Bitwarden 转换产物」两种来源下**类型不一致** ⇒
     * 详情页/ 自动填充的呈现会随库的往返历史变来变去。
     * ⇒ 需要还原类型时由转换器**直接读这个字段**，不走领域模型。
     */
    const val BW_TYPE = PREFIX + "BW_TYPE"

    /**
     * Bitwarden 组织 id（`cipher.organizationId`）——**只写**，由转换器还原。
     *
     * ## 为什么"KDBX 没有组织"不等于"可以丢"
     *
     * KDBX 确实没有组织概念，看起来像"格式装不下、丢了活该"。但转换是**双向**的：
     * 用户把组织库导出成 JSON 转成 .kdbx 归档，若干月后再转回 Bitwarden，
     * 组织归属没了 ⇒ 那些共享密码**悄悄落进个人库**。
     * 共享库凭证进个人库是**权限事故**（离职后仍持有前司密码），
     * 而过程中没有任何提示 —— 这类"无声的降级"比报错更危险。
     *
     * ⇒ 用私有键带着走：KDBX 侧看不见（`customFieldsOf` 按工具区排除），
     *   但转换回 Bitwarden 时原样写回 `organizationId`。
     */
    const val BW_ORG = PREFIX + "BW_ORG"

    /**
     * Bitwarden 集合 id 列表（`cipher.collectionIds`）——`VPX_BW_COLLECTIONS`，**只写**。
     *
     * 格式：UUID 用 `,` 连接。安全依据是 **UUID 本身不含逗号**
     * （`BitwardenExportCipher.collectionIds` 的元素恒为 UUID 字符串），
     * 所以不需要转义；万一有人手工塞了含逗号的值，最坏是拆出一个非法 id，
     * 而**不是**整串丢失。
     */
    const val BW_COLLECTIONS = PREFIX + "BW_COLLECTIONS"

    /** [BW_COLLECTIONS] 的分隔符（见该常量的 KDoc 说明为何无需转义）。 */
    const val COLLECTION_SEPARATOR = ","

    /**
     * 时间戳的**毫秒余数**——`VPX_TIME_MS`，格式 `<createdAtMs>:<updatedAtMs>`。
     *
     * ## 为什么必须补这一手
     *
     * KDBX 的 `<Times>` 在**加密的 v4 文件**里是「自公元 1 年起的**整秒**」的
     * 8 字节二进制（解开 kotpass 0.13.0 的 `InstantKt.marshal` 核实过：
     * `Instant.getEpochSecond() + 62135596800L` 后直接 base64，
     * `getInstant` 反向 `ofEpochSecond`）⇒ **毫秒在格式层就没了**，
     * 与我们自己的代码无关，是规范的硬边界。
     *
     * 但"格式装不下"不等于"可以丢"（同 [BW_ORG] 的处境）：
     * 转换器若把 `1700000000123` 读回来成 `1700000000000`，
     * 就是**悄悄改写了用户数据**；而往返转换应当尽量无损。
     *
     * ⇒ `<Times>` 照常写（保证 KeePassXC / KeePassDX 看得懂秒级时间），
     *   本键只补**秒以下的余数**（0..999）。两个值都用 `Long` 承载，
     *   拼接格式固定、无逗号歧义；解析不出来就当作"没有余数"（旧文件天然如此）。
     *
     * 值恒为 `<ms1>:<ms2>`，**缺失即两级都为 0**（与"整秒"同义）。
     * 余数为 0 时仍写（`0:0`），这样"从 KDBX 原生条目"（无此键）
     * 和"转换来的条目"（余数恰好为 0）在语义上等价，不需要靠猜。
     */
    const val TIME_MS = PREFIX + "TIME_MS"

    /** [TIME_MS] 两个值之间的分隔符。 */
    const val TIME_MS_SEPARATOR = ':'

    /**
     * 把一对毫秒时间戳编码成 [TIME_MS] 的写法（只取秒以下余数）。
     *
     * 入参为 `null` 的那一项按 `0` 处理 —— 缺失与"整秒"同义。
     */
    fun encodeTimeMillis(createdAt: Long?, updatedAt: Long?): String {
        val created = (createdAt ?: 0L).mod(MILLIS_PER_SECOND)
        val updated = (updatedAt ?: 0L).mod(MILLIS_PER_SECOND)
        return "$created$TIME_MS_SEPARATOR$updated"
    }

    /**
     * 解出 [TIME_MS] 的两个余数；格式不对返回 `null`（**不猜**）。
     *
     * 调用方拿到 `null` 时应保持 KDBX `<Times>` 的整秒值不动，
     * 而不是把时间归零 —— 宁可少一点精度，也不能毁掉时间戳。
     */
    fun decodeTimeMillis(raw: String?): Pair<Long, Long>? {
        if (raw == null) return null
        val parts = raw.split(TIME_MS_SEPARATOR)
        if (parts.size != 2) return null
        val created = parts[0].trim().toLongOrNull() ?: return null
        val updated = parts[1].trim().toLongOrNull() ?: return null
        if (created !in 0L until MILLIS_PER_SECOND) return null
        if (updated !in 0L until MILLIS_PER_SECOND) return null
        return created to updated
    }

    /** 一秒的毫秒数（[encodeTimeMillis] / [decodeTimeMillis] 取余用）。 */
    const val MILLIS_PER_SECOND = 1000L

    /**
     * 收藏标记（A1 · 2026-10-13）：`"true"` = 收藏，**缺失即非收藏**。
     *
     * ## 为什么落工具区，而不是某个标准键
     *
     * KDBX **没有"收藏"的标准字段**（5 个标准键里没有它，`Meta` 也没有条目级旗标）。
     * 候选方案逐个核过：
     *
     * | 方案 | 否决理由 |
     * |---|---|
     * | 复用 `Tags` | KeePassXC/DX 的 Tags 是**用户可见、可编辑**的字符串列表，往里塞 `favorite` 会被用户当普通标签删掉/改掉，且别的工具会显示出来 |
     * | 复用某个自定义字段名（如 `Favorite`） | 用户完全可能自己起一个叫 `Favorite` 的自定义字段 ⇒ **撞名即互相覆盖**（正是 R1 要防的那类事故） |
     * | `Icon`/自定义图标 | 图标是**展示层**信息，改字段保护策略都不该动它；且丢失图标不等于取消收藏（语义不对） |
     *
     * ⇒ 按 R2 落 `VPX_FAVORITE`：私有前缀保证**不与任何用户字段撞名**，
     * 读侧 `KdbxItemMapper` 认它、写侧 `KdbxItemWriter.applyFavorite` 管它，
     * `customFieldsOf` 按工具区排除（不会以自定义字段的身份出现在详情页）。
     *
     * ⚠️ **与 BW_ID / BW_TYPE 不同，这个键是双向的**：领域模型有 [io.vaultix.model.VaultItem.favorite]
     * 承载它，读回来不会造成任何"两套真相"——收藏只有 Vaultix 自己写，
     * 第三方工具写的同名字段不存在（前缀挡住了），故不存在撞名风险。
     *
     * 值只写 `"true"` / 删键，**不写 `"false"`**：缺失即假，
     * 少一个字段比多一个恒假字段干净（取消收藏即删除，与"从未收藏"在文件里同形）。
     */
    const val FAVORITE = PREFIX + "FAVORITE"

    /** 收藏字段的**真值**文本（其余一切值——含 `"1"`/`"yes"`——读侧都认，写侧只写这个）。 */
    const val FAVORITE_TRUE = "true"

    /**
     * 安全笔记类型标记（A4 · 2026-10-16）：`"true"` = 安全笔记，**缺失即无标记**
     * （回落到启发式，见下）。
     *
     * ## 为什么需要它：启发式会把「空笔记的安全笔记」翻转成登录
     *
     * KDBX **没有条目类型**（KeePass 只有标准五键，登录/安全笔记之分是 Vaultix 的领域概念）。
     * 此前的推导规则是纯启发式：`无用户名 && 无密码 && 有笔记 ⇒ SecureNote`
     * （`KdbxItemMapper`）。它在一种完全正常的内容上**静默翻转类型**：
     * 用户建一条**空笔记的安全笔记**（表单的备注可空，只填标题 + 自定义字段完全合法）
     * ⇒ 存盘时三个字段全空 ⇒ 下次读回 `isNote = false` ⇒ **类型变成登录**。
     * Bitwarden 侧类型是服务端显式字段，无此问题 —— 又是"两个库两种命运"。
     *
     * ## 读写规则（与 FAVORITE 同构，但读侧是三态）
     *
     * - 写：类型是 SecureNote ⇒ 写 `"true"`；类型离开 SecureNote ⇒ 删键；
     *   值未变（`before.type == after.type`）一字不动。
     * - 读：**marker 优先** —— 有标记按标记（宽松真值，见 [isSecureNoteTrue]）；
     *   **无标记才回落启发式**（第三方工具写的条目没有这个键，必须保持兼容）。
     *
     * ⚠️ 与 FAVORITE 的关键差异：收藏"缺失即假"是对的（没星就是没星）；
     * 而这里**缺失不等于登录** —— 第三方写的笔记型条目（有笔记、无账号密码）
     * 靠启发式判为安全笔记，KeePass 生态也这么 convention。所以：
     * 只写 `"true"` 不写 `"false"`（不给每条登录都镶一个恒假字段），
     * 代价是「Vaultix 自建的、只有笔记的登录条目」仍会被启发式判成安全笔记 ——
     * 该内容形态本身歧义，且与第三方工具的解释一致，可接受。
     */
    const val SECURE_NOTE = PREFIX + "SECURE_NOTE"

    /** 安全笔记标记的**真值**文本（其余一切值读侧按宽松真值认，写侧只写这个）。 */
    const val SECURE_NOTE_TRUE = "true"

    // ---------------------------------------------------------------- 载荷键区（2026-11-01）

    /**
     * 主密码二次验证标记（`VPX_REPROMPT`）：`"true"` = 打开该条目需再次验证主密码。
     *
     * 对齐 Bitwarden `cipher.reprompt`（0/1）。KDBX 没有这个概念，但 **KeePassXC 有**
     * 「二次确认（Protected / History 限制）」—— 那属于库级策略，不是条目级旗标，
     * 无法一一对应 ⇒ 只能落工具字段。
     *
     * ⚠️ 只写 `"true"`、`None` 时删键（同 [FAVORITE] 的纪律）：Bitwarden 的默认值就是 0，
     *    给每个条目镶一个恒假字段纯属噪音；而 `VaultReprompt` 只有 `None` / `Password`
     *    两态（见领域模型 [io.vaultix.model.VaultReprompt]），所以"缺失即 None"无歧义。
     */
    const val REPROMPT = PREFIX + "REPROMPT"

    /**
     * 银行卡载荷前缀：**`card_<字段名>`**（如 `card_number`）。
     *
     * ## 为什么是六个字段而不是一段 JSON
     *
     * 一段 JSON 塞进一个字段，用户没法单独复制卡号，别的客户端也没法按名字取值。
     * 拆成六个独立字段后，字段名与 KeePassXC 的 `Card` 模板逐字同名（`cardholderName` /
     * `brand` / `number` / `expMonth` / `expYear` / `code`）。
     *
     * ## ⚠️ 为什么**不用** `VPX_` 前缀（2026-11-01 修正）
     *
     * 这里原先写的是 `VPX_CARD_number`，理由是本文件顶部那条「工具字段一律加 `VPX_`」。
     * 那条纪律**用错了场合**：`VPX_` 的用途是标记「这是 Vaultix 转换器为了无损而造的、
     * 用户不该看见的字段」，而 `card_*` 恰恰相反 —— 它是 **KeePassXC 的
     * `BitwardenReader.cpp` 自己写的键名**（`entry->attributes()->set("card_" + attr, …)`），
     * 属于**别人已经定好的标准**，和 `KPEX_PASSKEY_*` 同一性质。
     *
     * 本项目对 `KPEX_` 的处置是「照抄才能互通」（见文件头），`card_*` / `identity_*`
     * 必须遵循同一条：
     *
     * | 方向 | 用 `VPX_CARD_` | 用 `card_` |
     * |---|---|---|
     * | KeePassXC 导入的库 → Vaultix | ❌ 读不到，卡片变普通条目 | ✅ 认得出 |
     * | Vaultix 转出的库 → KeePassXC | 显示成 `VPX_CARD_number`（能看懂但不像卡） | ✅ 与它自己导入的完全一致 |
     *
     * 代价：`card_` 不带私有前缀，可能与用户自建的同名字段相撞。用两条纪律兜住 ——
     * ① **读**只取 [cardKeys] 里那 6 个已知名字（`card_我自己的备注` 不受影响）；
     * ② **写**只删这 6 个名字（[LEGACY_CARD_PREFIX] 的旧键另算），绝不按前缀整片清。
     *
     * ## 哪些受保护
     *
     * [PAYLOAD_PROTECTED] 列出的键（卡号 / CVV）按 `EntryValue.Encrypted` 写 ——
     * 卡号与 CVV 在 Bitwarden 侧是加密载荷，落 KDBX 时转明文等于**主动降级**。
     * 姓名 / 品牌 / 有效期不敏感，写明文（让用户能直接在别的工具里看见）。
     */
    const val CARD_PREFIX = "card_"

    /** 身份信息载荷前缀：`identity_<字段名>`（如 `identity_email`）。理由见 [CARD_PREFIX]。 */
    const val IDENTITY_PREFIX = "identity_"

    /**
     * 旧版银行卡前缀（`VPX_CARD_`），**只读不写**。
     *
     * 2026-11-01 之前转换出的库用的是它。写侧改键名后若不认旧键，用户上一次转好的
     * 库再读一遍就**凭空丢卡**；写侧清理也要认它，否则改完类型的条目里会残留旧键。
     */
    const val LEGACY_CARD_PREFIX = PREFIX + "CARD_"

    /** 旧版身份前缀（`VPX_IDENTITY_`），作用同 [LEGACY_CARD_PREFIX]。 */
    const val LEGACY_IDENTITY_PREFIX = PREFIX + "IDENTITY_"

    /** SSH 密钥载荷前缀：`VPX_SSH_<字段名>`（`privateKey` / `publicKey` / `keyFingerprint`）。 */
    const val SSH_PREFIX = PREFIX + "SSH_"

    /**
     * 密码历史：`VPX_PW_HISTORY_1..n?`（十进制，1 为**最早**一条），
     * 值为 `epoch毫秒|密码`。
     *
     * ## 为什么存在这里，而不是 KDBX 原生的 History
     *
     * KDBX 的 `Entry.history` 是**整条快照**（改前那一版的所有字段），不是"密码历史"，
     * 且 kotpass 的 `withHistory` 只替我们管理"本次编辑前的那一版"。硬塞进去有两次坑：
     * 1. 快照会带上当时的用户名/网址，用户回滚一次历史就等于回滚了整条条目；
     * 2. 每次编辑都会**再追加一份**，条目用久了历史里躺着几十条重复快照。
     *
     * ⇒ 用编号字段自己管：一条历史 = 一个键，可逆、可读、与历史快照互不干扰。
     *
     * ## 为什么带时间戳，且用 `|` 分隔
     *
     * Bitwarden 的 `passwordHistory[i]` 有 `lastUsedDate`，丢了它就等于丢了
     * "什么时候改的密码"这一半信息。`|` 是**不会出现在 epoch 毫秒里**的最简分隔符；
     * 密码本身可以含 `|`（解析时只切第一个），所以顺序必须是「时间在前」。
     *
     * ⚠️ 值**不加密**：密码历史里的旧密码同样是敏感信息，但它们与当前密码同处
     *    一个已加密的 KDBX 文件内（文件级加密已覆盖），再加逐字段加密只会让
     *    第三方工具展示成一串乱码，反而丢掉可读性。
     */
    const val PW_HISTORY_PREFIX = PREFIX + "PW_HISTORY_"

    /** 历史的「时间|密码」分隔符（解析只切第一个）。 */
    const val HISTORY_SEPARATOR = "|"

    /** 卡 / 身份载荷里**必须受保护**的字段名（小写）。 */
    private val PAYLOAD_PROTECTED: Set<String> = setOf("number", "code", "ssn", "passportnumber", "licensenumber")

    /** 银行卡字段顺序（与 Bitwarden `card` 载荷逐字段对应，顺序即写侧产出顺序）。 */
    private val CARD_KEYS = listOf("cardholderName", "brand", "number", "expMonth", "expYear", "code")

    /** 身份信息字段顺序（对齐 Bitwarden identity 的 18 字段）。 */
    private val IDENTITY_KEYS = listOf(
        "title", "firstName", "middleName", "lastName",
        "address1", "address2", "address3", "city", "state", "postalCode", "country",
        "company", "email", "phone", "ssn", "username", "passportNumber", "licenseNumber",
    )

    /** SSH 密钥字段顺序。 */
    private val SSH_KEYS = listOf("privateKey", "publicKey", "keyFingerprint")

    /**
     * KeePassXC 的 Bitwarden 导入器**额外**写的两个合并字段（`BitwardenReader.cpp`）：
     * `identity_name`（姓名空格拼接）与 `identity_address`（多行地址）。
     *
     * ⚠️ 它们是**只读**的：本应用写侧只写 18 个分段键（分段可逆，合并不可逆）。
     *   列在这里只为「读回来 + 不再漏进自定义字段区」。
     */
    private val IDENTITY_MERGED_KEYS = listOf("name", "address")

    /** 银行卡键表（供写侧按域模型取值；读侧据此还原）。 */
    fun cardKeys(): List<String> = CARD_KEYS

    /** 身份信息键表（18 个**分段**键，写侧就是按它写的）。 */
    fun identityKeys(): List<String> = IDENTITY_KEYS

    /**
     * 身份信息在 KDBX 里**归本应用管**的全部键名 = 18 个分段键 + 2 个 KeePassXC 合并键。
     *
     * ⚠️ 为什么清理要单独一个表：合并键本应用**从不写**，但它由别的工具写进来后
     *   已被读侧摊回分段（[isIdentityPayloadKey] 认它）——
     *   若清理仍只认 18 个分段键，用户把"身份"改成"登录"之后，
     *   `identity_address` 会**留在原地**，下一次读回来又被摊成身份 ⇒ **类型复活**。
     */
    fun identityOwnedKeys(): List<String> = IDENTITY_KEYS + IDENTITY_MERGED_KEYS

    /** SSH 键表。 */
    fun sshKeys(): List<String> = SSH_KEYS

    /** 该载荷字段是否应受保护（卡号 / CVV / 护照 / 执照 / SSN）。 */
    fun isPayloadProtected(field: String): Boolean = field.lowercase(Locale.ROOT) in PAYLOAD_PROTECTED

    // ------------------------------------------------------- 自定义字段的类型（2026-11-01）

    /**
     * 自定义字段**类型**的落法：`VPX_FIELD_META_<字段名>`。
     *
     * ## 为什么必须存
     *
     * KDBX 的自定义字段只有两种状态：明文（`EntryValue.Plain`）与内存保护
     * （`EntryValue.Encrypted`）。而 Bitwarden 有四种：
     *
     * | Bitwarden | KDBX 能表达吗 | 不存类型会怎样 |
     * |---|---|---|
     * | `Text`（0） | ✅ Plain | — |
     * | `Hidden`（1） | ✅ Encrypted | — |
     * | `Boolean`（2） | ❌ 只剩 `"true"` 这串字 | 读回来变成 `Text`：界面从开关变成一行字 |
     * | `Linked`（3） | ❌ 只剩值 | 读回来变成 `Text`：`linkedId` 没了，"这字段就是用户名"的语义消失 |
     *
     * `Boolean` / `Linked` 是**静默降级**的典型：值一个不差，只有类型换了，
     * 所以任何"只比 value 不比 type"的往返测试都抓不到（这正是它躲了两轮的原因）。
     *
     * ## 为什么键名里直接拼字段名
     *
     * 必须**按字段名**索引，不能按下标：用户可能在 KeePassXC 里插一个新字段，
     * 下标一错位，类型就会**张冠李戴**（把 A 的 Boolean 安到 B 头上）——
     * 那比丢失更坏，因为它看起来"有值有类型"，没人会怀疑。
     *
     * 键名拼字段名是安全的：KDBX 的 `<Key>` 是任意 XML 文本（空格/中文都合法），
     * 而 `VPX_` 前缀保证它不会与用户字段撞名（用户若真建了同名字段，
     * 它自己会先被 [isToolFieldName] 排除）。
     */
    const val FIELD_META_PREFIX = PREFIX + "FIELD_META_"

    /** 某自定义字段的类型元数据键名。 */
    fun fieldMetaKey(fieldName: String): String = FIELD_META_PREFIX + fieldName

    /**
     * 编码类型元数据；返回 null 表示**不需要存**（`Text` / `Hidden` 由条目值形态即可推出）。
     *
     * 格式：`Boolean` 或 `Linked:100`（`Linked` 且 `linkedId == null` 时为纯 `Linked`）。
     */
    fun encodeFieldMeta(type: CustomFieldType, linkedId: Int?): String? = when (type) {
        CustomFieldType.Boolean -> "Boolean"
        CustomFieldType.Linked -> linkedId?.let { "Linked:$it" } ?: "Linked"
        CustomFieldType.Text, CustomFieldType.Hidden -> null
    }

    /**
     * 解码类型元数据；认不出时返回 null（调用方回落到「按值形态推导」）。
     *
     * ⚠️ 不认识的类型名**不猜测**：宁可退回 Text，也不把某个陌生字符串
     *   当成 Boolean 去渲染（界面上会多出一个开关，用户会以为值坏了）。
     */
    fun decodeFieldMeta(raw: String?): Pair<CustomFieldType, Int?>? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val head = text.substringBefore(':')
        val tail = text.substringAfter(':', "").toIntOrNull()
        return when {
            head.equals("Boolean", ignoreCase = true) -> CustomFieldType.Boolean to null
            head.equals("Linked", ignoreCase = true) -> CustomFieldType.Linked to tail
            head.equals("Text", ignoreCase = true) -> CustomFieldType.Text to null
            head.equals("Hidden", ignoreCase = true) -> CustomFieldType.Hidden to null
            else -> null
        }
    }

    /**
     * 从**全部字段**里挑出某个前缀的载荷，返回「字段名（去掉前缀）→ 值」。
     *
     * @param prefix [CARD_PREFIX] / [IDENTITY_PREFIX] / [SSH_PREFIX]。
     * @return 键为**去掉前缀后的原始大小写**（写侧就是按 [cardKeys] 的驼峰写的）。
     */
    fun payloadOf(fields: Map<String, String>, vararg prefixes: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        // ⚠️ 按参数顺序**逐个前缀**扫，`putIfAbsent` ⇒ 先给的前缀赢。
        //    调用方一律「新键在前、旧键在后」，于是同一条目两套键都在时读新键。
        prefixes.forEach { prefix ->
            fields.forEach { (key, value) ->
                if (!key.startsWith(prefix, ignoreCase = true)) return@forEach
                val name = key.substring(prefix.length)
                if (name.isNotBlank()) out.putIfAbsent(name, value)
            }
        }
        return out
    }

    /**
     * 按 [order] 取值，**键名大小写不敏感**（别的工具改过大小写也读得回来）。
     *
     * @return 与 `order` 等长的列表；缺失项为空串。
     */
    fun orderedValues(payload: Map<String, String>, order: List<String>): List<String> {
        val byLower = payload.entries.associate { it.key.lowercase(Locale.ROOT) to it.value }
        return order.map { byLower[it.lowercase(Locale.ROOT)].orEmpty() }
    }

    /** 密码历史编码：`epoch毫秒|密码`。 */
    fun encodeHistory(lastUsedAt: Long?, password: String): String =
        "${lastUsedAt ?: 0L}$HISTORY_SEPARATOR$password"

    /**
     * 密码历史解码；格式不对时返回「时间为 null + 整串当密码」——
     * **宁可把整串当密码也不丢内容**（丢了的旧密码是找不回来的）。
     */
    fun decodeHistory(raw: String): Pair<Long?, String> {
        val index = raw.indexOf(HISTORY_SEPARATOR)
        if (index < 0) return null to raw
        val stamp = raw.substring(0, index).toLongOrNull()
        return stamp?.takeIf { it > 0L } to raw.substring(index + 1)
    }

    /**
     * 工具字段名是否属于**载荷区**（卡 / 身份 / SSH / 密码历史）。
     *
     * 读侧把载荷区从「自定义字段」里排掉（[isToolFieldName] 已经覆盖全部 `VPX_`），
     * 写侧据此判断"哪些键是本条目产出的"以便清理。
     */
    fun isPayloadFieldName(name: String): Boolean =
        isCardPayloadKey(name) ||
            isIdentityPayloadKey(name) ||
            name.startsWith(LEGACY_CARD_PREFIX, ignoreCase = true) ||
            name.startsWith(LEGACY_IDENTITY_PREFIX, ignoreCase = true) ||
            name.startsWith(SSH_PREFIX, ignoreCase = true) ||
            name.startsWith(PW_HISTORY_PREFIX, ignoreCase = true)

    /**
     * 是否**已知**的银行卡字段（`card_number` 等 6 个）。
     *
     * ⚠️ 必须按**完整键名**比，不能只看 `card_` 前缀：那个前缀同时可能落在用户自定义的
     * `card_我的备注` 上，把它当载荷 ⇒ 详情页里看不见（被 [isToolFieldName] 排除）、
     * 用户却还在别处能改到它 ⇒ **字段凭空消失**。
     */
    fun isCardPayloadKey(name: String): Boolean =
        CARD_KEYS.any { name.equals(CARD_PREFIX + it, ignoreCase = true) }

    /** 是否**已知**的身份字段（`identity_email` 等 18 个）；判据同 [isCardPayloadKey]。 */
    fun isIdentityPayloadKey(name: String): Boolean =
        IDENTITY_KEYS.any { name.equals(IDENTITY_PREFIX + it, ignoreCase = true) } ||
            // ⚠️ KeePassXC 额外写的两个**合并**字段（见 KdbxItemMapper.toVaultIdentity）：
            //    读侧会把它们摊回分段，所以必须在这里认成"已消费的载荷" ——
            //    否则它们会**同时**出现在自定义字段区，用户看到一行原文多行地址。
            IDENTITY_MERGED_KEYS.any { name.equals(IDENTITY_PREFIX + it, ignoreCase = true) }

    /**
     * 多余 URI 的匹配规则：`VPX_MATCH_n`（与 [URL_PREFIX] 同下标）。
     *
     * ⚠️ 为什么连匹配规则也要存：Bitwarden 每条 URI 都带 `match`（0–5），
     * 丢了它自动填充的匹配档位就会**退回默认「基域匹配」** —— 用户会突然发现
     * 某条 URI 匹配得比之前宽松（或严格）了，且完全无从追溯。
     */
    const val MATCH_PREFIX = PREFIX + "MATCH_"

    /**
     * 应用包名的落法（`androidapp://` 专用，见 [androidAppPackage]）。
     *
     * ⚠️ 键名**不是**施工单里写的 `AndroidApp`，而是 `App Package Name`：
     *   Bastion（GPL-3.0，`reference/bastion/.../KeePassKdbxService.kt:3779`）
     *   读的是 `AppPackageName` / `AppName`，并兼容 `BastionAppPackageName` /
     *   `PackageName` / `KP2A_APP` 等多个别名。写自己发明的名字 ⇒ 别的工具读不到。
     */
    const val APP_PACKAGE = "App Package Name"

    /** 应用名（`androidapp://` 专用；KeePassDX 用它显示「哪个 App」）。 */
    const val APP_NAME = "App Name"

    /** `androidapp://` 与 `android://` 两个 scheme（KeePass2Android / KeePassDX 都用）。 */
    private val ANDROID_APP_SCHEMES = listOf("androidapp://", "android-app://", "android://")

    /**
     * Android 应用 URI → 包名；不是应用 URI 返回 null。
     *
     * 形如 `androidapp://com.example.app` ⇒ `com.example.app`。
     * 带额外路径时（`androidapp://pkg/path`）只取包名段——包名本身不含 `/`。
     */
    fun androidAppPackage(uri: String): String? {
        val trimmed = uri.trim()
        val scheme = ANDROID_APP_SCHEMES.firstOrNull { trimmed.startsWith(it, ignoreCase = true) }
            ?: return null
        val rest = trimmed.substring(scheme.length).trim().trim('/')
        if (rest.isEmpty()) return null
        // ⚠️ 只取第一段：包名不含 `/`，其后若还有内容就是路径/参数，丢弃。
        val pkg = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        return pkg.takeIf { it.isNotBlank() && it.contains('.') }
    }

    /**
     * 包名字段的**值** → 归一化包名；认不出返回 null。
     *
     * ##⚠️ 为什么需要它（不能直接调[androidAppPackage]）
     *
     * [APP_PACKAGE] 字段里存的是**裸包名**（`com.example.mail`），不是完整 URI ——
     * 这是 KeePassDX 的约定，Bastion 读的也是裸包名。
     * 而 [androidAppPackage] 只认 `androidapp://` 开头 ⇒ 直接拿它读字段**恒为 null**。
     *
     * 症状极具迷惑性：写侧明明写进去了（单元测试可以断言字段存在），
     * 读侧却一条应用 URI 都读不回来 ⇒「应用条目整体消失」，
     * 而日志里没有任何错误，看起来像"库里本来就没有"。
     *
     * ⇒ 字段值有两种可能（**裸包名** / **完整 URI**，有的工具写后者），两种都认。
     */
    fun normalizeAppPackage(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        // ① 已是完整 URI（有的工具这么写）。
        androidAppPackage(trimmed)?.let { return it }
        // ② 裸包名：去掉可能带的路径/参数段，再验一次合法性。
        val bare = trimmed
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .trim()
        return bare.takeIf { it.isNotBlank() && it.contains('.') && !it.contains(' ') }
    }

    /** 是否为 Android 应用 URI（`androidapp://` / `android://` / `android-app://`）。 */
    fun isAndroidAppUri(uri: String): Boolean = androidAppPackage(uri) != null

    // ---------------------------------------------------------------- 写方向

    /**
     * [VaultItem.uris] → 工具字段表（**按下标全套落盘**，含第 0 条与应用 URI）。
     *
     * ## 2026-11-01 改过一次口径，为什么
     *
     * 旧口径：应用 URI（`androidapp://…`）**不进本表**，只写一个 `App Package Name`。
     * 代价是三条静默丢失（用户："数据不能遗漏"）：
     * 1. **位置**：读回时应用 URI 被追加到末尾 ⇒ `[a, app, b]` 变成 `[a, b, app]`；
     * 2. **match**：`App Package Name` 只是一个包名，装不下匹配档位
     *    ⇒ 本该「永不匹配」的网址变成「基域匹配」，自动填充变得比用户设的更宽松；
     * 3. **第二个起的应用 URI** 整条消失（`App Package Name` 只有一个字段位）。
     *
     * 新口径：**只在「列表里有应用 URI」时启用**，此时全部 URI 按原始下标落
     * `VPX_URL_n` / `VPX_MATCH_n`（含 index 0）。`App Package Name` 仍然照写
     * （给 KeePassDX 认），但它降级为**兼容副本**，不再承担"唯一载体"的职责。
     *
     * ## 为什么按需升级、而不是一律用新口径
     *
     * 纯 web 列表时，旧口径产出的 `n` **恰好等于原始下标**（"第 0 条进 `Url`、
     * 其余从 1 起"与"原始下标"是同一个数），两种口径完全等价。
     * ⇒ 没必要为了统一而给每条登录都镶一个 `VPX_URL_0`：那是**纯冗余**，
     *   还会让老库/新库在同一份数据上产出不同的字节（破坏"值没变就一个字都不动"）。
     *
     * ## 兼容：`VPX_URL_0` 的存在即"新格式"标记
     *
     * 旧格式从不写 `VPX_URL_0`（第 0 条进标准 `Url`）。读侧见到它就按新口径
     * 重建整个列表（并忽略标准 `Url`，避免第一条重复），见不到就走旧口径。
     *
     * @return 键值对；`VPX_URL_n` 与 `VPX_MATCH_n` 按同一 `n` 成对产出。
     */
    fun fromUris(uris: List<VaultUri>): Map<String, String> {
        val usable = uris.filter { it.uri.trim().isNotEmpty() }
        if (usable.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        // 触发条件只有"含应用 URI"：只有它无法用旧口径表达（旧口径会把它整条滤掉）。
        if (usable.any { isAndroidAppUri(it.uri) }) {
            usable.forEachIndexed { index, uri ->
                out["$URL_PREFIX$index"] = uri.uri
                uri.match?.let { match -> out["$MATCH_PREFIX$index"] = match.name }
            }
            return out
        }
        // 旧口径：第 0 条归标准 `Url`，只把它的 match 存下来（`Url` 装不下匹配规则）。
        usable.drop(1).forEachIndexed { offset, uri ->
            val n = offset + 1
            out["$URL_PREFIX$n"] = uri.uri
            uri.match?.let { match -> out["$MATCH_PREFIX$n"] = match.name }
        }
        usable.first().match?.let { match -> out["${MATCH_PREFIX}0"] = match.name }
        return out
    }

    // ---------------------------------------------------------------- 读方向

    /**
     * 工具字段表 → [VaultItem.uris]（把标准 `Url` 与 `VPX_URL_n` 合回一个列表）。
     *
     * ## 两种口径，靠 `VPX_URL_0` 是否存在分流（2026-11-01）
     *
     * - **新格式**（本应用 2026-11-01 起写的库）：`VPX_URL_0..n` 是**完整列表**，
     *   按下标排序即原顺序。此时**忽略标准 `Url`** —— 它只是一份给外部工具看的
     *   冗余副本，重复计入会让第一条网址出现两次。
     * - **旧格式 / 第三方库**：没有 `VPX_URL_0`。标准 `Url` 是第 0 条，
     *   `VPX_URL_1..n` 是其余的，`KP2A_URL_n`（KeePassXC）也在这个分支里被收进来。
     *
     * @param standardUrl 标准 `Url` 字段的值（可能为空）。
     * @param toolFields 其余全部字段的键值对（调用方负责先剥掉 OTP / 通行密钥区）。
     * @return 按原顺序排列的 URI 列表；空结果返回空列表。
     */
    fun toUris(standardUrl: String, toolFields: Map<String, String>): List<VaultUri> {
        val extra = extras(toolFields)
        // 新格式判据：`VPX_URL_0` 存在（旧格式从不写它）。
        if (extra.any { (n, _) -> n == 0 }) {
            return extra.map { (n, uri) -> VaultUri(uri = uri, match = matchOf(toolFields, n)) }
        }
        val out = ArrayList<VaultUri>()
        // ⚠️ 标准 `Url` 那条也要取回 `VPX_MATCH_0` —— 它在 KDBX 里没有字段装匹配规则，
        //    只能借工具字段存（见 [fromUris]）。漏这一步 ⇒ 第 0 条的档位永远丢。
        standardUrl.trim().takeIf { it.isNotEmpty() }?.let {
            out.add(VaultUri(uri = it, match = matchOf(toolFields, 0)))
        }
        extra.forEach { (n, uri) ->
            out.add(VaultUri(uri = uri, match = matchOf(toolFields, n)))
        }
        return out
    }

    /**
     * 工具字段表 → 额外 URI 列表（`VPX_URL_n` → 归一化键名，**已按 n 升序**）。
     *
     * 单独暴露是因为写侧也要用同一套下标规则（`fromUris` 与本函数必须成对）。
     *
     * ⚠️ 前缀比对**大小写不敏感**，与 [matchOf] / [isToolFieldName] 保持一致。
     *   kotpass 的 `EntryFields.get(String)` 就是普通 `Map.get`（已 `javap` 核实），
     *   **键名大小写敏感** ⇒ 同一个 `VPX_URL_1`，KeePassXC 与本库大小写不一致时
     *   在本库 `get` 得到 null、在 `startsWith` 也匹配不上 ⇒ 整条 URI 静默消失。
     *   这里不敏感，就能在别的工具改过大小写时仍然读回来。
     */
    fun extras(toolFields: Map<String, String>): List<Pair<Int, String>> {
        val byIndex = LinkedHashMap<Int, String>()
        // ⚠️ 两套前缀都要扫，**本应用产出的 `VPX_URL_n` 优先**（同一 n 两处都有值时取它）。
        //    `KP2A_URL_n` 是 KeePassXC 从 Bitwarden 导入时写的，见 [LEGACY_URL_PREFIX]。
        listOf(URL_PREFIX, LEGACY_URL_PREFIX).forEach { prefix ->
            toolFields.entries
                .mapNotNull { (key, value) ->
                    if (!key.startsWith(prefix, ignoreCase = true)) return@mapNotNull null
                    val n = key.drop(prefix.length).toIntOrNull() ?: return@mapNotNull null
                    value.trim().takeIf { it.isNotEmpty() }?.let { n to it }
                }
                .sortedBy { it.first }
                .forEach { (n, uri) -> byIndex.putIfAbsent(n, uri) }
        }
        return byIndex.entries.sortedBy { it.key }.map { it.key to it.value }
    }

    /** `VPX_MATCH_n` → [UriMatch]；认不出时按 Bitwarden 默认（null = 基域匹配）。 */
    private fun matchOf(toolFields: Map<String, String>, n: Int): UriMatch? =
        toolFields.entries
            .firstOrNull { (key, _) -> key.equals("$MATCH_PREFIX$n", ignoreCase = true) }
            ?.value
            ?.trim()
            ?.let { raw -> UriMatch.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } }

    /**
     * 该条目的包名字段 → `androidapp://<pkg>` 形态的 URI。
     *
     * ⚠️ **读侧必须吃别名**，且比对**大小写不敏感**：本库只写 [APP_PACKAGE] 一个键，
     *   但库可能是 KeePassDX / bw2keepass / Bastion 写的，键名有多种叫法。
     *   `javap` 核实过 kotpass `EntryFields.get(String)` 就是普通 `Map.get` ⇒ **大小写敏感**，
     *   所以 `AppPackageName` / `app package name` / `KP2A_APP` 都取不到。
     *   只认自己写的那一种 ⇒ **别人的库在我们这儿读不出应用条目**，等于单向丢数据。
     *
     * 别名清单来自 Bastion（GPL-3.0）的 `getFieldValueIgnoreCase` 实参，
     * 见 `reference/bastion/.../KeePassKdbxService.kt:3780-3793`（已剔除 Monica /
     * Bastion 自有前缀的两项 —— 那是他们内部字段，不是通用约定）。
     * 写侧仍只写 [APP_PACKAGE]：别名**只读不写**，多写会把用户的库塞满重复字段。
     *
     * @param fields 该条目的**全部**字段键值对（调用方自己筛，本函数只管认包名键）。
     * @return 规范化后的 `androidapp://` URI；认不出包名返回 null。
     */
    fun appUriOf(fields: Map<String, String>): String? {
        val raw = fields.entries
            .firstOrNull { (key, _) -> APP_PACKAGE_KEYS.any { key.equals(it, ignoreCase = true) } }
            ?.value
            .orEmpty()
        // ⚠️ 必须走 normalizeAppPackage（认裸包名），不能走 androidAppPackage（只认 URI）——
        //    字段里存的就是裸包名，用错函数 ⇒ 读回恒为 null（CI run 37610581524 实测）。
        return normalizeAppPackage(raw)?.let { "androidapp://$it" }
    }

    /**
     * 应用 URI → `App Package Name` 字段（**只写一个键**）。
     *
     * ⚠️ 为什么应用 URI 不走 `Url` 也不走 `VPX_URL_n`：KeePassXC / KeePassDX 见到
     *   `Url` 里的 `androidapp://` 会**当成域名去匹配**（匹配必然失败），
     *   而 KeePassDX 的做法是把包名写进 `App Package Name` 字段。
     *   塞 `Url` 的后果不是"显示怪"，是**自动填充对 Android 应用整体失效**。
     *   （bw2keepass `engine.js:222` 与 Bastion 都独立处理，本库对齐。）
     *
     * ##⚠️ 已知取舍：多个应用 URI 只保留第一个
     *
     * `App Package Name` 是**单个**字段位（KeePassDX 约定就是一条一个包名），
     * 没有 `App Package Name_2` 那种落法 ⇒ 第 2 个应用 URI 在 KDBX 侧无处可存。
     *
     * **为什么不改成「全部塞进 `VPX_URL_n`」**：那些 `androidapp://` 会被 KeePassXC
     * 当域名去匹配（必然匹配不上）⇒ 等于**为了多存一条而让所有应用 URI 的匹配都失效**。
     * 宁可少一条，也不要全部失效。
     *
     * ⇒ 该行为由 `KdbxToolFieldsRoundTripTest."多个应用 URI 时只保留第一个"`钉死，
     *   改前先读那段KDoc。真正的解法是等 KDBX 侧有 `_n` 约定的字段位。
     */
    fun appFieldsOf(uris: List<VaultUri>): Map<String, String> {
        val app = uris.firstOrNull { isAndroidAppUri(it.uri) } ?: return emptyMap()
        val pkg = androidAppPackage(app.uri) ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        out[APP_PACKAGE] = pkg
        // 应用名留空：领域模型只有 URI，没有单独的 appName 字段。
        // ⚠️ 不猜、不写空字段（写空字段等于让别的工具以为"用户清空过应用名"）。
        return out
    }

    /**
     * 包名字段名的**别名全集**（读侧认，写侧只用 [APP_PACKAGE]）。
     *
     * 前两个是 KeePassDX / Bastion 的标准写法，其余是历史与第三方叫法。
     * 顺序即优先级（[appUriOf] 取第一个命中的）。
     */
    private val APP_PACKAGE_KEYS: List<String> = listOf(
        APP_PACKAGE,
        "AppPackageName",
        "AndroidAppPackageName",
        "PackageName",
        "KP2A_APP",
    )

    // ---------------------------------------------------------------- type / id

    /**
     * [VaultItemType] → Bitwarden 的类型码（1=Login、2=SecureNote、3=Card、4=Identity、5=SshKey）。
     *
     * ⚠️ 独立于 bitwarden 模块的 private 映射：那边是**导出**用的 private 常量对象
     * （`BitwardenCipherTypeCode`），本库 data:kdbx 不得反向依赖 data:bitwarden
     * （见 `data/kdbx/build.gradle.kts`「不得反向依赖 domain / app」）。
     * ⇒ 数值在此**重写一遍**，判据以 [typeOf] 的 KDoc 里列的实测来源为准。
     */
    fun typeName(type: VaultItemType): String = when (type) {
        VaultItemType.Login -> "login"
        VaultItemType.SecureNote -> "secureNote"
        VaultItemType.Card -> "card"
        VaultItemType.Identity -> "identity"
        VaultItemType.SshKey -> "sshKey"
    }

    /**
     * Bitwarden 类型码 → [VaultItemType]；认不出按 `login`（KDBX 无类型概念）。
     *
     * ## 数字码的实测取值（别凭印象改）
     *
     * 官方 `CipherType` 从 **1** 起，`0` 不分配给这五种类型：
     *
     * | 码 | 类型 |
     * |---|---|
     * | 1 | Login |
     * | 2 | SecureNote |
     * | 3 | Card |
     * | 4 | Identity |
     * | 5 | SshKey |
     *
     * 取值来自本仓库 `data/bitwarden/.../export/BitwardenCipherTypeCode.kt`
     * （`LOGIN=1 … SSH_KEY=5`）与 `BitwardenExportModels.kt` 的 `@property type` 注释
     * （「1=Login、2=SecureNote、3=Card、4=Identity」）。
     *
     * ⚠️ ⚠️ 这里曾把 2 写成 Card、3 写成 Identity、4 写成 SshKey —— **整段错位一位**。
     *   错位不报错，只会让"卡片"在往返后变成"身份"，且用户完全看不出来。
     *   ⇒ 判定依据只认上面两处仓库内可查的出处，不认记忆。
     */
    fun typeOf(raw: String?): VaultItemType {
        val name = raw?.trim().orEmpty().lowercase(Locale.ROOT)
        return when (name) {
            "securenote", "note", "2" -> VaultItemType.SecureNote
            "card", "3" -> VaultItemType.Card
            "identity", "4" -> VaultItemType.Identity
            "sshkey", "5" -> VaultItemType.SshKey
            else -> VaultItemType.Login
        }
    }

    /** 是否属于工具区（`VPX_` 前缀，大小写不敏感）。 */
    fun isToolFieldName(name: String): Boolean =
        name.startsWith(PREFIX, ignoreCase = true) ||
            isCardPayloadKey(name) ||
            isIdentityPayloadKey(name) ||
            isAdditionalUrlKey(name) ||
            // ⚠️ 应用包名也是"已消费的载荷"（读侧 [appUriOf] 把它合成了 `androidapp://`
            //    URI）。不认它，详情页就会把同一个包名**再显示一次**自定义字段 ——
            //    用户看到"网址里有它、自定义字段里也有它"，改掉其中一个就互相矛盾。
            isAppPackageKey(name)

    /** 是否为应用包名字段（含 KeePassDX / Bastion / 第三方用过的全部别名）。 */
    private fun isAppPackageKey(name: String): Boolean =
        APP_PACKAGE_KEYS.any { name.equals(it, ignoreCase = true) }

    /** 是否为 `KP2A_URL_n`（KeePassXC / KeePass2Android 约定的「第 n 条额外网址」）。 */
    fun isAdditionalUrlKey(name: String): Boolean {
        if (!name.startsWith(LEGACY_URL_PREFIX, ignoreCase = true)) return false
        return name.drop(LEGACY_URL_PREFIX.length).toIntOrNull() != null
    }

    /**
     * 宽松真值判定（`true` / `1` / `yes`，大小写不敏感）；认不出一律 false。
     *
     * 读侧唯一口径：第三方工具若往 `VPX_FAVORITE` 写过别的真值形态（理论上不会，
     * 私有前缀挡住了；防御的是"用户手改库"这种小众场景），也按真处理。
     * 写侧只产出 [FAVORITE_TRUE]（见 [FAVORITE] 的 KDoc）。
     */
    fun isFavoriteTrue(raw: String?): Boolean = isTruthyFlag(raw)

    /**
     * [SECURE_NOTE] 的宽松真值判定（与 [isFavoriteTrue] 同一口径，见 [isTruthyFlag]）。
     *
     * 读侧唯一口径：用户手改库写成 `"1"` / `"yes"` 也按安全笔记处理；
     * 写侧只产出 [SECURE_NOTE_TRUE]。
     */
    fun isSecureNoteTrue(raw: String?): Boolean = isTruthyFlag(raw)

    /**
     * 布尔旗标的宽松真值（`true` / `1` / `yes`，大小写不敏感，首尾空白不算数）；
     * 认不出一律 false。`VPX_` 布尔键（收藏 / 安全笔记标记）共用的唯一口径。
     */
    private fun isTruthyFlag(raw: String?): Boolean = when (raw?.trim()?.lowercase(Locale.ROOT)) {
        "true", "1", "yes" -> true
        else -> false
    }
}
