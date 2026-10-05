/*
 * Vaultix — data:kdbx
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License version 3 of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 「KeePass 里的通行密钥以 `KPEX_PASSKEY_*` 自定义字段承载」这一约定与字段清单参考
 * Bastion（GPL-3.0，Copyright 2025 JoyinJoester）的 `keepass/KeePassDxPasskeyCodec.kt`：
 *   - 该前缀来自 **KeePassDX** 的通行密钥插件，也是目前跨客户端最通行的落法
 *     （KeePassXC 走自己的 `KPXC_*` 体系且版本间有差异，Bastion 同样只认这一套）；
 *   - 私钥 PEM / credentialId / userHandle 三个字段常被设为「受保护」→ 读取时必须走
 *     `EntryValue.content`（自动解密）；
 *   - `KPEX_PASSKEY_FLAG_BE` / `_BS` 是**可备份**（Backup Eligible）与**当前处于备份态**
 *     （Backup State）两个 WebAuthn 语义标记 —— 缺失时按「可备份且已备份」处理
 *     （跨设备同步的通行密钥就是这个语义，见 `.ai/ISSUES.md` #32/#33）。
 * 本文件为独立实现：只做「字段集 ↔ 领域模型」的映射，不做密钥加解密（那是 passkey 层的事）。
 *
 * W2（2026-10-06）写回补齐：此前「只读映射（阶段 A）」的注释已作废 ——
 * 现在 [fromCredential] 负责写方向，[groupPasskeyFields] 负责多凭证拆解。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.kdbx

import io.vaultix.model.VaultFido2Credential
import java.util.Base64
import java.util.Locale

/**
 * KeePass 的通行密钥字段集（键名大小写不敏感；值已由调用方 `.content` 解密）。
 *
 * 全部字段都给了默认空串：**任一必填项缺失即视为「这条不是通行密钥」**
 * （半截数据映射成条目会造成「列表里有一条点进去什么都没有」的假象）。
 *
 * W2 补入 `rpName` / `userDisplayName` / `creationDate` 三个键：它们 bw2keepass 有、
 * 本库此前缺，字段集按两边**并集**取齐（10 个键，见施工单 §1.3）。
 */
data class KdbxPasskeyFields(
    val username: String = "",
    val privateKeyPem: String = "",
    val credentialId: String = "",
    val userHandle: String = "",
    val relyingParty: String = "",
    val rpName: String = "",
    val userDisplayName: String = "",
    val creationDate: String = "",
    val flagBe: String = "",
    val flagBs: String = "",
)

/**
 * `KPEX_PASSKEY_*` ⇄ [VaultFido2Credential] 的双向映射。
 *
 * - 读方向：字段表 → 分组 → [fromFieldMap] → [KdbxPasskeyFields] → [toCredential]
 * - 写方向：[fromCredential] → 字段表（敏感项由调用方按 [isProtectedField] 加密）
 *
 * ⚠️ **多凭证**：bw2keepass 用 `_1`/`_2` 数字后缀区分「一条登录挂多个通行密钥」。
 * 本库对齐该约定：第 0 条**不带后缀**（与既有单凭证文件兼容），第 n 条带 `_n`。
 */
object KdbxPasskeyCodec {

    const val FIELD_USERNAME = "KPEX_PASSKEY_USERNAME"
    const val FIELD_PRIVATE_KEY = "KPEX_PASSKEY_PRIVATE_KEY_PEM"
    const val FIELD_CREDENTIAL_ID = "KPEX_PASSKEY_CREDENTIAL_ID"
    const val FIELD_USER_HANDLE = "KPEX_PASSKEY_USER_HANDLE"
    const val FIELD_RELYING_PARTY = "KPEX_PASSKEY_RELYING_PARTY"
    const val FIELD_RP_NAME = "KPEX_PASSKEY_RP_NAME"
    const val FIELD_USER_DISPLAY_NAME = "KPEX_PASSKEY_USER_DISPLAY_NAME"
    const val FIELD_CREATION_DATE = "KPEX_PASSKEY_CREATION_DATE"
    const val FIELD_FLAG_BE = "KPEX_PASSKEY_FLAG_BE"
    const val FIELD_FLAG_BS = "KPEX_PASSKEY_FLAG_BS"

    /** 插件惯用的空占位字段（值恒为空串，仅表示「这个条目是通行密钥条目」）。 */
    const val FIELD_PASSKEY = "Passkey"

    /**
     * 全部与通行密钥有关的字段名（**大小写不敏感**，不含序号后缀）。
     *
     * 用途：映射自定义字段时排除它们 —— 否则详情页会把私钥 PEM 当成一个
     * 「隐藏自定义字段」展示出来（受保护字段在 UI 上只是掩码，仍可复制）。
     */
    val PASSKEY_FIELD_NAMES: Set<String> = setOf(
        FIELD_USERNAME,
        FIELD_PRIVATE_KEY,
        FIELD_CREDENTIAL_ID,
        FIELD_USER_HANDLE,
        FIELD_RELYING_PARTY,
        FIELD_RP_NAME,
        FIELD_USER_DISPLAY_NAME,
        FIELD_CREATION_DATE,
        FIELD_FLAG_BE,
        FIELD_FLAG_BS,
        FIELD_PASSKEY,
    ).map { it.lowercase(Locale.ROOT) }.toSet()

    /** 写回时**必须受保护**的字段（对齐 KeePassDX 惯例，见文件头溯源）。 */
    private val PROTECTED_FIELD_NAMES: Set<String> = setOf(
        FIELD_PRIVATE_KEY,
        FIELD_CREDENTIAL_ID,
        FIELD_USER_HANDLE,
    ).map { it.lowercase(Locale.ROOT) }.toSet()

    private val INDEX_SUFFIX = Regex("_[0-9]+$")

    /**
     * 该字段名是否属于通行密钥家族（大小写不敏感，**含 `_n` 多凭证后缀**）。
     *
     * ⚠️ 必须后缀感知：否则第 2 条凭证的 `KPEX_PASSKEY_USERNAME_1` 会被当成普通
     * 自定义字段，在详情页把**私钥 PEM** 展示出来（泄密 —— 正是 W1 想挡的事）。
     */
    fun isPasskeyFieldName(name: String): Boolean =
        baseName(name).lowercase(Locale.ROOT) in PASSKEY_FIELD_NAMES

    /** 该字段是否应**受保护**写入（私钥 PEM / credentialId / userHandle）。 */
    fun isProtectedField(name: String): Boolean =
        baseName(name).lowercase(Locale.ROOT) in PROTECTED_FIELD_NAMES

    /** 去掉结尾的 `_n` 序号后缀，还原规范字段名。 */
    fun baseName(name: String): String = name.replace(INDEX_SUFFIX, "")

    /** 结尾 `_n` 的序号；无后缀 = 0。 */
    fun indexOf(name: String): Int =
        INDEX_SUFFIX.find(name)?.value?.removePrefix("_")?.toIntOrNull() ?: 0

    /** 这条条目是否是通行密钥条目（任一必需字段非空即可 —— 与 KeePassDX 的判定一致）。 */
    fun isPasskey(fields: KdbxPasskeyFields): Boolean =
        listOf(
            fields.username,
            fields.privateKeyPem,
            fields.credentialId,
            fields.userHandle,
            fields.relyingParty,
        ).any { it.isNotBlank() }

    /**
     * 领域凭证 → `KPEX_PASSKEY_*` 字段表（W2 写方向）。
     *
     * @param index 凭证在条目内的序号；0 不带后缀，n≥1 带 `_n`（对齐 bw2keepass）。
     * @param backupEligible [FIELD_FLAG_BE] / @param backedUp [FIELD_FLAG_BS]。
     *   ⚠️ **领域模型没有这两个位**（`VaultFido2Credential` 未建模备份态），
     *   故取文档化默认值「可备份且已备份」—— 那正是同步型通行密钥的语义。
     *   代价：第三方写入的 `false` 会被改写成默认真值，**这两位不参与往返保真**
     *   （W6 需确认 KeePassXC/DX 侧无副作用）。
     *
     * 空值一律**不写**（不产出空字段，免得把库弄脏）。
     */
    fun fromCredential(
        credential: VaultFido2Credential,
        index: Int,
        backupEligible: Boolean = true,
        backedUp: Boolean = true,
    ): Map<String, String> {
        val suffix = if (index <= 0) "" else "_$index"
        val out = LinkedHashMap<String, String>()

        fun put(key: String, value: String?) {
            val trimmed = value?.trim().orEmpty()
            if (trimmed.isNotEmpty()) out[key + suffix] = trimmed
        }
        put(FIELD_USERNAME, credential.userName)
        put(FIELD_PRIVATE_KEY, credential.keyValue)
        put(FIELD_CREDENTIAL_ID, toBase64Url(credential.credentialId))
        put(FIELD_USER_HANDLE, credential.userHandle)
        put(FIELD_RELYING_PARTY, credential.rpId)
        put(FIELD_RP_NAME, credential.rpName)
        put(FIELD_USER_DISPLAY_NAME, credential.userDisplayName)
        put(FIELD_CREATION_DATE, credential.creationDate)
        out[FIELD_FLAG_BE + suffix] = backupEligible.toString()
        out[FIELD_FLAG_BS + suffix] = backedUp.toString()
        return out
    }

    /**
     * 扁平字段表 → 多组通行密钥字段（按 `_n` 后缀拆分；空后缀 = 第 0 条，排最前）。
     *
     * 只收通行密钥家族的键（[isPasskeyFieldName]），组内键名统一还原成**无后缀的规范名**，
     * 便于 [fromFieldMap] 直接取用。
     */
    fun groupPasskeyFields(raw: Map<String, String>): List<Map<String, String>> {
        val groups = LinkedHashMap<Int, LinkedHashMap<String, String>>()
        raw.forEach { (key, value) ->
            if (!isPasskeyFieldName(key)) return@forEach
            val group = groups.getOrPut(indexOf(key)) { LinkedHashMap() }
            group[baseName(key)] = value
        }
        return groups.entries.sortedBy { it.key }.map { it.value }
    }

    /** 单个分组 → [KdbxPasskeyFields]；键名匹配**大小写不敏感**（KeePass 工具大小写随意）。 */
    fun fromFieldMap(raw: Map<String, String>): KdbxPasskeyFields {
        fun value(key: String): String =
            raw.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value.orEmpty()

        return KdbxPasskeyFields(
            username = value(FIELD_USERNAME),
            privateKeyPem = value(FIELD_PRIVATE_KEY),
            credentialId = value(FIELD_CREDENTIAL_ID),
            userHandle = value(FIELD_USER_HANDLE),
            relyingParty = value(FIELD_RELYING_PARTY),
            rpName = value(FIELD_RP_NAME),
            userDisplayName = value(FIELD_USER_DISPLAY_NAME),
            creationDate = value(FIELD_CREATION_DATE),
            flagBe = value(FIELD_FLAG_BE),
            flagBs = value(FIELD_FLAG_BS),
        )
    }

    /**
     * 映射为领域凭证；关键字段缺失返回 null（宁可当普通条目，也不给半截凭证）。
     *
     * @param title 条目名（`rpName` 的兜底；KeePassDX 把条目名写成 `<站点> [Passkey]`，
     *   这里去掉那个后缀，避免列表里显示「GitHub [Passkey]」这种机器味）。
     */
    fun toCredential(fields: KdbxPasskeyFields, title: String = ""): VaultFido2Credential? {
        val rpId = fields.relyingParty.trim().lowercase(Locale.ROOT)
        val credentialId = fields.credentialId.trim()
        val privateKey = fields.privateKeyPem.trim()
        val userName = fields.username.trim()
        val userHandle = fields.userHandle.trim()
        // 逐个判空（而不是把 5 个条件塞进一个 `||`）：detekt 的 ComplexCondition 上限是 3，
        // 且分开写时哪一项缺失一眼可见。
        if (rpId.isBlank()) return null
        if (credentialId.isBlank()) return null
        if (privateKey.isBlank()) return null
        if (userName.isBlank()) return null
        if (userHandle.isBlank()) return null
        val fallbackRpName = title.removeSuffix(PASSKEY_TITLE_SUFFIX).trim().ifBlank { rpId }
        return VaultFido2Credential(
            // ⚠️ credentialId 在 WebAuthn 里是**字节串**：KeePassDX 存的是 base64url
            // 无填充文本。这里只在能解出字节时才转成标准 base64（Bitwarden 侧的表示），
            // 解不出来就原样保留 —— 硬转换会把「非 base64 的旧数据」变成空串（等于丢凭证）。
            credentialId = normalizeCredentialId(credentialId),
            rpId = rpId,
            rpName = fields.rpName.trim().ifBlank { fallbackRpName },
            userName = userName,
            userDisplayName = fields.userDisplayName.trim().ifBlank { userName },
            userHandle = userHandle,
            // 私钥 PEM 原样进 keyValue：WebAuthn 签名时由 passkey 层解析
            //（与 Bitwarden 的 keyValue 语义一致，见 SavePasskeyDialog 的写入路径）。
            keyValue = privateKey,
            keyAlgorithm = KEY_ALGORITHM_ES256,
            keyType = KEY_TYPE_PUBLIC_KEY,
            keyCurve = KEY_CURVE_P256,
            // ⚠️ 恒 0（不实现计数器）：`.ai/ISSUES.md` #33 的结论 —— 同步型通行密钥
            // 递增计数器必然跨设备分叉，规范允许用 0 表示「不实现」。
            counter = 0L,
            discoverable = true,
            creationDate = fields.creationDate.trim().takeIf { it.isNotBlank() },
        )
    }

    /**
     * 是否需要备份态标记。
     *
     * `FLAG_BS` 为真 = 该凭证当前处于备份状态。写方向见 [fromCredential]。
     */
    fun isBackedUp(fields: KdbxPasskeyFields): Boolean =
        parseBooleanCompat(fields.flagBs) ?: true

    /** `BE`（Backup Eligible）：缺失时按「可备份」处理（同步型通行密钥的语义）。 */
    fun isBackupEligible(fields: KdbxPasskeyFields): Boolean =
        parseBooleanCompat(fields.flagBe) ?: true

    private const val PASSKEY_TITLE_SUFFIX = " [Passkey]"
    private const val KEY_ALGORITHM_ES256 = "ES256"
    private const val KEY_TYPE_PUBLIC_KEY = "public-key"
    private const val KEY_CURVE_P256 = "P-256"

    /** base64url（无填充）→ 标准 base64；已是标准形态 / 解不出来时原样返回。 */
    private fun normalizeCredentialId(raw: String): String {
        if (raw.contains('+') || raw.contains('/')) return raw
        val decoded = runCatching {
            val padded = raw.replace('-', '+').replace('_', '/')
                .let { if (it.length % 4 == 0) it else it + "=".repeat(4 - it.length % 4) }
            Base64.getDecoder().decode(padded)
        }.getOrNull() ?: return raw
        if (decoded.isEmpty()) return raw
        return Base64.getEncoder().encodeToString(decoded)
    }

    /**
     * 标准 base64 → base64url（无填充）；解不出来时原样返回（不破坏非 base64 的旧数据）。
     *
     * 与 [normalizeCredentialId] 互为逆运算 ⇒ `credentialId` 能写→读往返一致。
     */
    fun toBase64Url(raw: String): String {
        val trimmed = raw.trim()
        val decoded = runCatching { Base64.getDecoder().decode(trimmed) }.getOrNull() ?: return trimmed
        if (decoded.isEmpty()) return trimmed
        return Base64.getUrlEncoder().withoutPadding().encodeToString(decoded)
    }

    /** 宽松布尔解析（`true/1/yes` 与 `false/0/no`；其余返回 null 交给调用方定默认值）。 */
    private fun parseBooleanCompat(raw: String): Boolean? = when (raw.trim().lowercase(Locale.ROOT)) {
        "true", "1", "yes" -> true
        "false", "0", "no" -> false
        else -> null
    }
}
