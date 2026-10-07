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

import io.vaultix.model.UriMatch
import io.vaultix.model.VaultItemType
import io.vaultix.model.VaultUri
import java.util.Locale

/**
 * `VPX_` 工具字段：领域模型 ⇄ KDBX 字段的无损降级通道。
 *
 * ## 为什么只覆盖这三类
 *
 * 工具字段**只该承载「领域模型有、KDBX 装不下」的东西**。已逐项核过：
 *
 * | 来源 | 领域模型 | KDBX 装得下吗 | 处置 |
 * |---|---|---|---|
 * | 多条 `login.uris` | [VaultItem.uris] 列表 | ❌ `Url` 只装一条 | ⇒ 本类 `VPX_URL_n` |
 * | Bitwarden 条目 `id` | [VaultItem.id] | 无标准键 | ⇒ 本类 `VPX_BW_ID` |
 * | Bitwarden 条目 `type` | [VaultItemType] | 无标准键 | ⇒ 本类 `VPX_BW_TYPE` |
 * | Bitwarden `passwordHistory` | **领域模型无此字段** | — | ⚠️ **不做**（见下） |
 *
 * ### ⚠️ 为什么不做 `VPX_PW_HISTORY`（施工单 §2.2 曾列它）
 *
 * `VaultItem` **没有** `passwordHistory` 字段 —— Bitwarden 侧的
 * `BitwardenExportModels` 注释也写着「Vaultix 领域模型暂不承载，恒为 `null`」。
 * 此刻加一个 `VPX_PW_HISTORY` 会造出「**写进去就再也读不出来**」的字段：
 * 它没有承载者，转换时写进去，下一次转换读出来无处安放 ⇒ 数据在往返中凭空消失，
 * 而用户完全看不到任何提示。
 *
 * ⇒ **等`VaultItem` 真的建模了 `passwordHistory` 再补这个键。**
 *   届时的正确做法是在本文件加常量 + 在两侧接入，而不是现在写个空壳。
 */
internal object KdbxToolFields {

    /** 工具字段前缀（与 [KdbxFieldKeys.TOOL_PREFIX] 同源，此处为码本自留常量）。 */
    const val PREFIX = "VPX_"

    /** 多余 URI 的落法：`VPX_URL_1` / `VPX_URL_2` …（第 0 条进标准 `Url`）。 */
    const val URL_PREFIX = PREFIX + "URL_"

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

    /** 是否为 Android 应用 URI（`androidapp://` / `android://` / `android-app://`）。 */
    fun isAndroidAppUri(uri: String): Boolean = androidAppPackage(uri) != null

    // ---------------------------------------------------------------- 写方向

    /**
     * [VaultItem.uris] → 工具字段表（**只含标准 `Url` 装不下的那几条**）。
     *
     * 契约：
     * - 第 0 条**不写这里**（它进标准 `Url` 字段，由 [KdbxItemWriter] 处理）；
     * - 空串 / 纯空白 URI 跳过（不产出空字段）；
     * - 应用 URI（`androidapp://…`）**既不进标准 `Url` 也不进本表** ——
     *   它走 [appFieldsOf] 的字段对（`App Package Name`），理由见那里的说明。
     *
     * @return 键值对；`VPX_URL_n` 与 `VPX_MATCH_n` 按同一 `n` 成对产出。
     */
    fun fromUris(uris: List<VaultUri>): Map<String, String> {
        val extra = usableWebUris(uris)
        if (extra.size < 2) return emptyMap()
        val out = LinkedHashMap<String, String>()
        // ⚠️ 下标从 **1** 起：第 0 条归标准 `Url`，本表只承接"第 2 条起"。
        extra.drop(1).forEachIndexed { offset, uri ->
            val n = offset + 1
            out["$URL_PREFIX$n"] = uri.uri
            // ⚠️ match 取自**同一个过滤后对象**（`uri`），不能回头去索引原始 `uris` ——
            //    滤掉空串 / 应用 URI 后两个列表下标已错位，那样取到的是**另一条 URI 的规则**，
            //    症状是「自动填充的匹配档位莫名变了」，几乎无法归因。
            uri.match?.let { match ->
                out["$MATCH_PREFIX$n"] = match.name
            }
        }
        return out
    }

    /**
     * 能写进标准 `Url` / `VPX_URL_n` 的 URI：滤掉应用 URI、滤掉纯空白，**保持原顺序**。
     *
     * 单独成函数，是为了让 [fromUris] 与 `KdbxItemWriter.applyUris` 的"第 0 条"
     * 取自**同一份过滤结果** —— 否则标准 `Url` 与 `VPX_URL_1` 可能来自两条不同的口径
     * （一个滤了应用 URI、一个没滤），写出去的两条内容就**对不上第几轮是什么**。
     */
    private fun usableWebUris(uris: List<VaultUri>): List<VaultUri> =
        uris.filter { uri -> uri.uri.trim().isNotEmpty() && !isAndroidAppUri(uri.uri) }

    // ---------------------------------------------------------------- 读方向

    /**
     * 工具字段表 → [VaultItem.uris]（把标准 `Url` 与 `VPX_URL_n` 合回一个列表）。
     *
     * @param standardUrl 标准 `Url` 字段的值（可能为空）。
     * @param toolFields 其余全部字段的键值对（调用方负责先剥掉 OTP / 通行密钥区）。
     * @return 按「标准 Url 在前、`VPX_URL_1..n` 按序在后」拼接；空结果返回空列表。
     */
    fun toUris(standardUrl: String, toolFields: Map<String, String>): List<VaultUri> {
        val out = ArrayList<VaultUri>()
        standardUrl.trim().takeIf { it.isNotEmpty() }?.let { out.add(VaultUri(uri = it)) }
        extras(toolFields).forEach { (n, uri) ->
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
    fun extras(toolFields: Map<String, String>): List<Pair<Int, String>> =
        toolFields.entries
            .mapNotNull { (key, value) ->
                if (!key.startsWith(URL_PREFIX, ignoreCase = true)) return@mapNotNull null
                val n = key.drop(URL_PREFIX.length).toIntOrNull() ?: return@mapNotNull null
                value.trim().takeIf { it.isNotEmpty() }?.let { n to it }
            }
            .sortedBy { it.first }

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
        return androidAppPackage(raw)?.let { "androidapp://$it" }
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
    fun isToolFieldName(name: String): Boolean = name.startsWith(PREFIX, ignoreCase = true)
}
