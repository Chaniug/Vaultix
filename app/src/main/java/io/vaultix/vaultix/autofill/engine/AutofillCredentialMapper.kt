/*
 * Vaultix — app:autofill · engine
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * 领域条目 → 自动填充凭据的映射（纯 Kotlin，不依赖 Android 框架，可 JVM 单测）。
 */
package io.vaultix.vaultix.autofill.engine

import io.vaultix.model.UriMatch
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import io.vaultix.model.VaultReprompt
import io.vaultix.vaultix.autofill.model.AutofillCredential
import io.vaultix.vaultix.autofill.model.AutofillUri
import io.vaultix.vaultix.autofill.model.FillContext
import io.vaultix.vaultix.autofill.model.ParsedStructure

/** 把已解锁的库条目映射为匹配器 / 填充规划器吃的解耦结构。 */
object AutofillCredentialMapper {

    /**
     * 登录条目 → [AutofillCredential]。
     *
     * - [VaultItem.uris] 逐条保留自身的 [UriMatch]（缺省按 Bitwarden 默认基域匹配）；
     * - [VaultItem.totp] 透传给填充规划器（真正算码在 Service 侧调用 TotpGenerator）。
     */
    fun toCredential(vaultId: String, item: VaultItem): AutofillCredential = AutofillCredential(
        vaultId = vaultId,
        itemId = item.id,
        name = item.title,
        username = item.username,
        password = item.password,
        totp = item.totp.orEmpty(),
        uris = item.uris.map { AutofillUri(it.uri, it.match ?: UriMatch.Domain) },
        isFavorite = item.favorite,
        requiresReprompt = item.reprompt == VaultReprompt.Password,
    )

    /** 可作为登录候选（有账号或密码；无密码的「仅 TOTP」条目也允许填充账号）。 */
    fun isLoginCandidate(item: VaultItem): Boolean =
        item.type == VaultItemType.Login && (item.username.isNotBlank() || item.password.isNotBlank())

    /** 可作为卡片候选。 */
    fun isCardCandidate(item: VaultItem): Boolean = item.type == VaultItemType.Card && item.card != null

    /** 可作为身份候选。 */
    fun isIdentityCandidate(item: VaultItem): Boolean =
        item.type == VaultItemType.Identity && item.identity != null

    /**
     * 解析结果 → 填充上下文（是否在场账号/密码框 + 出现的字段语义集合）。
     * 供 [FillPlanner] 判定登录 / 卡片 / 身份上下文。
     */
    fun toFillContext(parsed: ParsedStructure): FillContext = FillContext(
        packageName = parsed.packageName,
        webDomain = parsed.webDomain,
        webUri = parsed.webUri,
        hasUsernameField = parsed.usernameId != null,
        hasPasswordField = parsed.passwordId != null,
        presentHints = parsed.fields.map { it.hint }.toSet(),
    )
}
