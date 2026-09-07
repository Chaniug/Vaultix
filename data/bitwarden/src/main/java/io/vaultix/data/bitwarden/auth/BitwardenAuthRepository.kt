/*
 * Vaultix — data:bitwarden
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 认证流程与参数以 Bitwarden 官方客户端实际行为为准；其中「盐为小写 email」、
 * 「OkHttp Authenticator 为同步回调需 runBlocking」等结论参考 Bastion 项目
 * （GPL-3.0，Copyright 2025 JoyinJoester）的实战经验，本文件为独立编写。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.bitwarden.auth

import io.vaultix.crypto.SecureBytes
import io.vaultix.crypto.VaultixCrypto
import io.vaultix.data.bitwarden.api.PreLoginRequest
import io.vaultix.data.bitwarden.api.PreLoginResponse
import io.vaultix.data.bitwarden.di.BitwardenApiFactory
import io.vaultix.datastore.SecureCredentialStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bitwarden 认证编排。
 *
 * 时序（对齐 Bitwarden 官方客户端的**实际行为**，非文档表述）：
 * 1. ccounts/prelogin 取该账号的 KDF 类型与参数；
 * 2. 以 **mail.trim().lowercase() 为盐** 派生 MasterKey（PBKDF2 或 Argon2id，服务端决定）；
 * 3. MasterPasswordHash = PBKDF2(MasterKey, password, 1)；
 * 4. connect/token（grant_type=password）换取 access / refresh token。
 *
 * 凭据一律经 [SecureCredentialStore] 加密落盘，**绝不明文存储**。
 */
@Singleton
class BitwardenAuthRepository @Inject constructor(
    private val apiFactory: BitwardenApiFactory,
    private val credentials: SecureCredentialStore,
    private val crypto: VaultixCrypto,
) {

    /** host -> server，供 401 刷新时反查（OkHttp 只给得到 host）。 */
    private val serverByHost = ConcurrentHashMap<String, String>()

    /** 刷新互斥：并发 401 时只放行一次，其余等待结果，避免重复刷新。 */
    private val refreshMutex = Mutex()

    suspend fun login(
        server: String,
        email: String,
        password: String,
        deviceId: String,
        deviceName: String,
    ): Result<AuthSession> = runCatching {
        val api = apiFactory.identity(server)

        val pre = api.preLogin(PreLoginRequest(email))
        val salt = email.trim().lowercase()

        val masterKey = deriveMasterKey(pre, password, salt)
        val hash = crypto.deriveMasterPasswordHash(masterKey, password)

        val token = api.token(
            mapOf(
                "grant_type" to "password",
                "username" to email,
                "password" to hash,
                "scope" to "api offline_access",
                "client_id" to CLIENT_ID,
                "deviceType" to DEVICE_TYPE,
                "deviceIdentifier" to deviceId,
                "deviceName" to deviceName,
            ),
        )

        persist(server, token.accessToken, token.refreshToken)
        noteServer(server)

        AuthSession(
            server = server,
            email = email,
            accessToken = token.accessToken,
            refreshToken = token.refreshToken,
            expiresIn = token.expiresIn,
            masterKey = masterKey,
        )
    }

    /** 用 refresh_token 换新 access_token；并发调用只刷新一次。 */
    suspend fun refresh(server: String): Result<String> = runCatching {
        refreshMutex.withLock {
            val refreshToken = credentials.getString(CredentialKeys.refresh(server))
                ?: error("No refresh token stored for ")

            val token = apiFactory.identity(server).token(
                mapOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to refreshToken,
                    "client_id" to CLIENT_ID,
                ),
            )
            persist(server, token.accessToken, token.refreshToken)
            token.accessToken
        }
    }

    fun currentAccessToken(server: String): String? =
        credentials.getString(CredentialKeys.access(server))

    fun logout(server: String) {
        credentials.remove(CredentialKeys.access(server))
        credentials.remove(CredentialKeys.refresh(server))
        hostOf(server)?.let { serverByHost.remove(it) }
    }

    /** 供 [BitwardenTokenRefresher] 反查：OkHttp 只提供 host。 */
    fun findServerByHost(host: String): String? = serverByHost[host]

    private fun noteServer(server: String) {
        hostOf(server)?.let { serverByHost[it] = server }
    }

    private fun hostOf(server: String): String? =
        runCatching { URL(server).host }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun persist(server: String, access: String, refresh: String?) {
        credentials.putString(CredentialKeys.access(server), access)
        if (!refresh.isNullOrBlank()) {
            credentials.putString(CredentialKeys.refresh(server), refresh)
        }
    }

    private fun deriveMasterKey(
        pre: PreLoginResponse,
        password: String,
        salt: String,
    ): SecureBytes = when (pre.kdf) {
        KDF_PBKDF2 -> crypto.deriveMasterKeyPbkdf2(password, salt, pre.kdfIterations)
        KDF_ARGON2ID -> crypto.deriveMasterKeyArgon2(
            password = password,
            salt = salt,
            iterations = pre.kdfIterations,
            memoryMb = pre.kdfMemory ?: DEFAULT_ARGON2_MEMORY_MB,
            parallelism = pre.kdfParallelism ?: DEFAULT_ARGON2_PARALLELISM,
        )
        else -> throw IllegalArgumentException("Unsupported Kdf type: {pre.kdf}")
    }

    private companion object {
        const val KDF_PBKDF2 = 0
        const val KDF_ARGON2ID = 1
        const val DEFAULT_ARGON2_MEMORY_MB = 64
        const val DEFAULT_ARGON2_PARALLELISM = 4
        const val CLIENT_ID = "mobile"
        const val DEVICE_TYPE = "1"
    }
}
