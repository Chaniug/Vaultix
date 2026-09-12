/*
 * Vaultix — data:kdbx
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
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
 * 本文件为独立实现：只做「字段集 → 领域模型」的映射，不做密钥加解密（那是 passkey 层的事）。
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
 */
data class KdbxPasskeyFields(
    val username: String = "",
    val privateKeyPem: String = "",
    val credentialId: String = "",
    val userHandle: String = "",
    val relyingParty: String = "",
    val flagBe: String = "",
    val flagBs: String = "",
)

/**
 * `KPEX_PASSKEY_*` → [VaultFido2Credential]。
 *
 * ⚠️ 这是**只读映射**（M2 阶段 A）：不生成、不改写、不删除 KDBX 里的通行密钥。
 * 写回要等阶段 B（需要保真写回 + 「插件字段一律原样保留」的铁律）。
 */
object KdbxPasskeyCodec {

    const val FIELD_USERNAME = "KPEX_PASSKEY_USERNAME"
    const val FIELD_PRIVATE_KEY = "KPEX_PASSKEY_PRIVATE_KEY_PEM"
    const val FIELD_CREDENTIAL_ID = "KPEX_PASSKEY_CREDENTIAL_ID"
    const val FIELD_USER_HANDLE = "KPEX_PASSKEY_USER_HANDLE"
    const val FIELD_RELYING_PARTY = "KPEX_PASSKEY_RELYING_PARTY"
    const val FIELD_FLAG_BE = "KPEX_PASSKEY_FLAG_BE"
    const val FIELD_FLAG_BS = "KPEX_PASSKEY_FLAG_BS"

    /** 插件惯用的空占位字段（值恒为空串，仅表示「这个条目是通行密钥条目」）。 */
    const val FIELD_PASSKEY = "Passkey"

    /**
     * 全部与通行密钥有关的字段名（**大小写不敏感**）。
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
        FIELD_FLAG_BE,
        FIELD_FLAG_BS,
        FIELD_PASSKEY,
    ).map { it.lowercase(Locale.ROOT) }.toSet()

    /** 该字段名是否属于通行密钥家族（大小写不敏感）。 */
    fun isPasskeyFieldName(name: String): Boolean = name.lowercase(Locale.ROOT) in PASSKEY_FIELD_NAMES

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
        return VaultFido2Credential(
            // ⚠️ credentialId 在 WebAuthn 里是**字节串**：KeePassDX 存的是 base64url
            // 无填充文本。这里只在能解出字节时才转成标准 base64（Bitwarden 侧的表示），
            // 解不出来就原样保留 —— 硬转换会把「非 base64 的旧数据」变成空串（等于丢凭证）。
            credentialId = normalizeCredentialId(credentialId),
            rpId = rpId,
            rpName = title.removeSuffix(PASSKEY_TITLE_SUFFIX).trim().ifBlank { rpId },
            userName = userName,
            userDisplayName = userName,
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
        )
    }

    /**
     * 是否需要备份态标记。
     *
     * `FLAG_BS` 为真 = 该凭证当前处于备份状态。**保留给写回阶段**（阶段 A 只读，
     * 不需要写这个语义），但映射时不丢信息：调用方可据此在 UI 上做区分。
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

    /** 宽松布尔解析（`true/1/yes` 与 `false/0/no`；其余返回 null 交给调用方定默认值）。 */
    private fun parseBooleanCompat(raw: String): Boolean? = when (raw.trim().lowercase(Locale.ROOT)) {
        "true", "1", "yes" -> true
        "false", "0", "no" -> false
        else -> null
    }
}
