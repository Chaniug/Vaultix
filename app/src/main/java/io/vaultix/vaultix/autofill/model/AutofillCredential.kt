/*
 * Vaultix — app:autofill · model
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.autofill.model

import io.vaultix.model.UriMatch

/**
 * 自动填充用的解耦凭据（内存瞬时对象）。
 *
 * Service 在 `onFillRequest` 里把 [io.vaultix.model.VaultItem] 映射成本结构，
 * 匹配器只吃本类型、完全不依赖 Vaultix 仓储 / KeePass（对齐 Bastion
 * BitwardenLikeAutofillMatcherNg 的解耦设计，GPL-3.0，Copyright 2025 JoyinJoester）。
 *
 * @param uris 可匹配网址（Bitwarden login.uris，可多值；可含 androidapp:// 包名 Uri）。
 *   每条 URI 携带自身的 [UriMatch] 规则，缺失时按 Bitwarden 默认「基域匹配（Domain）」。
 * @param appPackageName 包名匹配来源（v1 暂未反查，预留）。
 * @param requiresReprompt 主密码二次验证：填充前需再验主密码（走认证回灌路径）。
 */
data class AutofillCredential(
    val vaultId: String,
    val itemId: String,
    val name: String,
    val username: String,
    val password: String,
    val totp: String = "",
    val uris: List<AutofillUri> = emptyList(),
    val appPackageName: String = "",
    val isFavorite: Boolean = false,
    val requiresReprompt: Boolean = false,
)

/**
 * 一条可匹配网址 + 其匹配规则（对齐 Bitwarden login.uris[i].match）。
 * [match] 缺省为 [UriMatch.Domain]，与 Bitwarden「基域匹配」默认行为一致。
 */
data class AutofillUri(
    val uri: String,
    val match: UriMatch = UriMatch.Domain,
)
