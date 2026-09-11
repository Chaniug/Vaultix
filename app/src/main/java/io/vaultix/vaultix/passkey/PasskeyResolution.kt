/*
 * Vaultix — app:passkey
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * 通行密钥候选解析（从 VaultixCredentialProviderService 拆出）。
 *
 * 为什么单独成文件：detekt 2.0.0-alpha.6 的 CyclomaticComplexMethod 会把**同文件**被调用
 * 私有函数的复杂度累加进调用方（实测：拆成同文件私有 helper 后 resolvePasskeys 反而从 19
 * 涨到 41）。把这批 helper 移到独立文件后，detekt 逐文件分析、无法跨文件累加，各函数各自
 * 独立度量（均 < 14），既满足门禁又保持逻辑清晰。
 */
package io.vaultix.vaultix.passkey

import io.vaultix.common.WebAuthn
import io.vaultix.domain.ItemRepository
import io.vaultix.model.VaultFido2Credential
import io.vaultix.model.VaultItemType
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/** 通行密钥候选（跨库扁平化后的三元组 + 展示标题）。 */
internal data class PasskeyMatch(
    val vaultId: String,
    val itemId: String,
    val credential: VaultFido2Credential,
    val loginTitle: String,
)

/** 各层级丢弃计数，供 [resolvePasskeyMatches] 诊断日志使用。 */
internal data class PasskeyCounts(
    val total: Int,
    val unusable: Int,
    val rpIdMiss: Int,
)

/**
 * RP ID 归一化（对齐 Bastion `PasskeyRpIdNormalizer.normalize`）。
 *
 * 步骤：`trim` → 去尾部根点 `.` → 小写 → Unicode 域名转 punycode（`IDN.toASCII`）。
 *
 * 为什么必须做：RP 请求里的 `rpId` 是 **punycode ASCII**（如 `xn--fiq228c.cn`），
 * 而库里可能存着注册时的 Unicode 形态（如 `中文.cn`）或带大小写差异的形态。
 * 不归一化 ⇒ 字符串不等 ⇒ 候选为空。
 *
 * `IDN.toASCII` 失败（非法域名）时回退到小写形态，宁可多比不可漏比。
 */
internal fun normalizeRpId(raw: String): String {
    val trimmed = raw.trim().trimEnd('.')
    if (trimmed.isEmpty()) return ""
    val lower = trimmed.lowercase(java.util.Locale.ROOT)
    return runCatching { java.net.IDN.toASCII(lower, java.net.IDN.USE_STD3_ASCII_RULES) }
        .getOrDefault(lower)
}

/** 归一化后比较两个 rpId 是否等价。 */
private fun isSameRpId(a: String, b: String): Boolean =
    normalizeRpId(a) == normalizeRpId(b)

/**
 * 通行密钥可用性判定（候选展示阶段）。
 *
 * **只检查元数据完整性**，不检查 `keyValue`（私钥材料）——对齐 Keyguard
 * 的做法（检查 `keyAlgorithm` / `keyType` 等元数据字段，签名时才取密钥）。
 *
 * 为什么不检查 keyValue：
 * Bitwarden 官方端创建的 passkey，私钥存在 Android Keystore，**服务端不存
 * keyValue**。从 Bitwarden 同步过来的 passkey 的 `keyValue` 字段解密后为空
 * （CipherMapper 第 443 行 `.takeIf { it.isNotBlank() }` 返回 null）。
 */
private fun isUsablePasskey(cred: VaultFido2Credential): Boolean =
    cred.credentialId.isNotBlank() &&
        cred.rpId.isNotBlank() &&
        (cred.keyType.isNullOrBlank() || cred.keyType == "public-key")

/** allowCredentials：[{type,id,transports}] 中的 id（base64url）。 */
internal fun parseAllowedCredentialIds(json: JSONObject): List<String> {
    val arr = json.optJSONArray("allowCredentials") ?: return emptyList()
    val ids = mutableListOf<String>()
    for (i in 0 until arr.length()) {
        arr.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }?.let { ids += it }
    }
    return ids
}

/** 容错比较 storedId 与允许列表 id（两种 base64 形态都试，转原始字节比较）。 */
private fun idMatches(storedId: String, allowed: String): Boolean {
    val a = runCatching { WebAuthn.decodeBase64UrlOrStandard(storedId) }
        .getOrNull() ?: return false
    val b = runCatching { WebAuthn.decodeBase64UrlOrStandard(allowed) }
        .getOrNull() ?: return false
    return a.contentEquals(b)
}

/** 遍历已解锁库的全部登录条目 fido2，产出 rpId 匹配的候选并累计各级计数。 */
internal suspend fun collectPasskeyMatches(
    itemRepository: ItemRepository,
    unlocked: Set<String>,
    rpId: String,
): Pair<List<PasskeyMatch>, PasskeyCounts> {
    val rpMatched = mutableListOf<PasskeyMatch>()
    var total = 0
    var unusable = 0
    var rpIdMiss = 0
    for (vaultId in unlocked) {
        val items = runCatching { itemRepository.observeItems(vaultId).first() }
            .getOrDefault(emptyList())
        for (item in items) {
            if (item.type != VaultItemType.Login) continue
            for (cred in item.fido2Credentials) {
                total++
                val usable = isUsablePasskey(cred)
                val sameRp = isSameRpId(cred.rpId, rpId)
                if (usable && sameRp) {
                    rpMatched += PasskeyMatch(vaultId, item.id, cred, item.title)
                } else if (!usable) {
                    unusable++
                } else {
                    rpIdMiss++
                }
            }
        }
    }
    return rpMatched to PasskeyCounts(total, unusable, rpIdMiss)
}

/**
 * allowCredentials 过滤。**关键：严格过滤后为空要回退到「不过滤」**。
 * 理由：`allowCredentials` 是 RP 的**提示**而非授权门（规范允许空）。用户若已在
 * 「另一台设备 / 另一个客户端」注册过该 RP 的 passkey，本地这份 credentialId 与
 * RP 下发的列表对不上——严格过滤会把**唯一可用的候选也删掉**，表现为候选列表为空。
 * 回退到不严格过滤，至少让用户看到并尝试；真用不了的条目会在签名阶段失败，
 * 但那比「什么都不弹」可诊断得多。
 */
internal fun applyAllowedFilter(
    rpMatched: List<PasskeyMatch>,
    allowed: List<String>,
    log: (String) -> Unit,
): List<PasskeyMatch> {
    if (allowed.isEmpty()) return rpMatched
    val strict = rpMatched.filter { m -> allowed.any { idMatches(m.credential.credentialId, it) } }
    if (strict.isNotEmpty()) return strict
    log("GET resolve allowCredentials mismatch (allowed=${allowed.size}) → fallback to rpId-only")
    return rpMatched
}

/** 仅用于「有记录却筛不出候选」时的诊断：收集库内实际存储的 rpId（只含域名，非敏感）。 */
internal suspend fun collectStoredRpIds(
    itemRepository: ItemRepository,
    unlocked: Set<String>,
): Set<String> {
    val stored = mutableSetOf<String>()
    for (vaultId in unlocked) {
        val items = runCatching { itemRepository.observeItems(vaultId).first() }
            .getOrDefault(emptyList())
        for (item in items) {
            for (cred in item.fido2Credentials) {
                stored += cred.rpId
            }
        }
    }
    return stored
}
