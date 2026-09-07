/*
 * Vaultix — data:bitwarden
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.data.bitwarden.auth

/**
 * 凭据在 [io.vaultix.datastore.SecureCredentialStore] 中的键。
 *
 * ⚠️ 必须按 server 隔离：Vaultix 支持多库（多个 Bitwarden / Vaultwarden 账号），
 * 若 key 不含实际 server 值，不同库的 token 会互相覆盖。
 * 这里刻意用字符串拼接而非模板，避免生成脚本时的转义歧义。
 */
internal object CredentialKeys {
    private const val PREFIX_ACCESS = "bw_access::"
    private const val PREFIX_REFRESH = "bw_refresh::"
    private const val PREFIX_PROTECTED_KEY = "bw_protected_key::"

    fun access(server: String): String = PREFIX_ACCESS + server
    fun refresh(server: String): String = PREFIX_REFRESH + server

    /** 受保护的账号对称密钥（EncString），需用 StretchedMasterKey 解包。 */
    fun protectedKey(server: String): String = PREFIX_PROTECTED_KEY + server
}