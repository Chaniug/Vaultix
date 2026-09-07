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
import io.vaultix.crypto.SymmetricCryptoKey
import io.vaultix.crypto.VaultixCrypto
import io.vaultix.data.bitwarden.api.BitwardenIdentityApi
import io.vaultix.data.bitwarden.api.PreLoginRequest
import io.vaultix.data.bitwarden.api.PreLoginResponse
import io.vaultix.data.bitwarden.api.TokenResponse
import io.vaultix.data.bitwarden.di.BitwardenApiFactory
import io.vaultix.data.bitwarden.network.BitwardenJson
import io.vaultix.datastore.SecureCredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException
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

    /**
     * 最近一次刷新失败的类型（按 server；无记录 = 未刷新过或最后成功）。
     * 供上层区分「凭据真失效（需重登）」与「瞬时故障（保留登录态重试）」，
     * 语义对齐 Bastion RefreshOutcome（GPL-3.0 参考）：
     * - [RefreshFailure.Invalid]：400/401，refresh token 被服务端拒绝 → 需重登；
     * - [RefreshFailure.Transient]：403/429/5xx/网络异常 → 可重试，**绝不误报失效**。
     */
    enum class RefreshFailure { Invalid, Transient }

    private val lastRefreshFailure = ConcurrentHashMap<String, RefreshFailure>()

    suspend fun login(
        server: String,
        email: String,
        password: String,
        deviceId: String,
        deviceName: String,
    ): Result<AuthSession> = runCatching {
        performLogin(
            server = server,
            email = email,
            password = password,
            deviceId = deviceId,
            deviceName = deviceName,
            twoFactor = null,
        )
    }

    /**
     * 两步验证登录：密码授权收到 400 `two_factor_required` 后，以相同 grant 追加
     * `twoFactorToken`（验证码）+ `twoFactorProvider` 重发（Bitwarden 经典 OAuth
     * 扩展，官方与 Vaultwarden 均支持；流程参考 Bastion，GPL-3.0 溯源）。
     *
     * 验证码错误/过期时服务端再次返回 two_factor_required → 抛
     * [TwoFactorInvalidException]；邮箱与密码错误归类与 [login] 相同。
     */
    suspend fun loginWithTwoFactor(
        server: String,
        email: String,
        password: String,
        provider: Int,
        code: String,
        deviceId: String,
        deviceName: String,
    ): Result<AuthSession> = runCatching {
        performLogin(
            server = server,
            email = email,
            password = password,
            deviceId = deviceId,
            deviceName = deviceName,
            twoFactor = TwoFactorSubmit(provider = provider, code = code),
        )
    }

    private suspend fun performLogin(
        server: String,
        email: String,
        password: String,
        deviceId: String,
        deviceName: String,
        twoFactor: TwoFactorSubmit?,
    ): AuthSession {
        val api = apiFactory.identity(server)

        val pre = api.preLogin(PreLoginRequest(email))
        val salt = email.trim().lowercase()

        val masterKey = deriveMasterKey(pre, password, salt)
        val hash = crypto.deriveMasterPasswordHash(masterKey, password)

        val fields = buildMap {
            put("grant_type", "password")
            put("username", email)
            put("password", hash)
            put("scope", "api offline_access")
            put("client_id", CLIENT_ID)
            put("deviceType", DEVICE_TYPE)
            put("deviceIdentifier", deviceId)
            put("deviceName", deviceName)
            if (twoFactor != null) {
                put("twoFactorToken", twoFactor.code)
                put("twoFactorProvider", twoFactor.provider.toString())
                put("twoFactorRemember", "0")
            }
        }
        val token = requestToken(api, fields, deviceId, deviceName, twoFactor != null)

        persist(server, token.accessToken, token.refreshToken, token.expiresIn)
        // 受保护的账号对称密钥：后续用它解密所有条目，务必一并持久化
        token.key?.let { credentials.putString(CredentialKeys.protectedKey(server), it) }
        lastRefreshFailure.remove(server)
        noteServer(server)

        return AuthSession(
            server = server,
            email = email,
            accessToken = token.accessToken,
            refreshToken = token.refreshToken,
            expiresIn = token.expiresIn,
            masterKey = masterKey,
        )
    }

    /** token 请求 + 2FA 错误结构解析（失败时抛可分类异常，HTTP 语义见 KDoc）。 */
    private suspend fun requestToken(
        api: BitwardenIdentityApi,
        fields: Map<String, String>,
        deviceId: String,
        deviceName: String,
        submittedTwoFactor: Boolean,
    ): TokenResponse {
        return try {
            api.token(
                fields = fields,
                // 设备登记/信任依赖 HTTP Header（Bastion 实测组合，GPL-3.0 溯源）
                deviceType = DEVICE_TYPE,
                deviceIdentifier = deviceId,
                deviceName = deviceName,
            )
        } catch (error: HttpException) {
            val providers = parseTwoFactorProviders(
                error.response()?.errorBody()?.string(),
            )
            if (providers != null) {
                throw if (submittedTwoFactor) {
                    // 已带验证码仍返回 2FA 挑战：验证码错误 / 过期
                    TwoFactorInvalidException()
                } else {
                    TwoFactorRequiredException(providers)
                }
            }
            throw error
        }
    }

    /**
     * 用 refresh_token 换新 access_token；并发调用只刷新一次。
     * @suppress TooGenericExceptionCaught：网络栈未知异常统一按 Transient 处理
     * （保留登录态可重试），与 Bastion refreshTokenDetailed 语义一致。
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun refresh(server: String): RefreshOutcome = refreshMutex.withLock {
        val refreshToken = credentials.getString(CredentialKeys.refresh(server))
            ?: return@withLock RefreshOutcome.Invalid

        try {
            val token = apiFactory.identity(server).token(
                mapOf(
                    "grant_type" to "refresh_token",
                    "refresh_token" to refreshToken,
                    "client_id" to CLIENT_ID,
                ),
            )
            persist(server, token.accessToken, token.refreshToken, token.expiresIn)
            lastRefreshFailure.remove(server)
            RefreshOutcome.Success(token.accessToken)
        } catch (error: HttpException) {
            val failure = refreshFailureKind(error.code())
            if (failure == RefreshFailure.Invalid) {
                // 服务端明确拒绝 refresh token（invalid_grant / 已吊销）→ 必须重新登录
                lastRefreshFailure[server] = RefreshFailure.Invalid
                RefreshOutcome.Invalid
            } else {
                // 403（WAF/反代拦截）/429/5xx：瞬时故障，保留登录态与凭据
                lastRefreshFailure[server] = RefreshFailure.Transient
                RefreshOutcome.Transient("刷新令牌时服务器返回 ${error.code()}")
            }
        } catch (error: Exception) {
            lastRefreshFailure[server] = RefreshFailure.Transient
            RefreshOutcome.Transient(error.message ?: "刷新令牌时网络异常")
        }
    }

    /**
     * 该 server 最近一次刷新失败类型；null = 无失败记录（登录成功 / 尚未刷新过）。
     * 供同步层把 401 归类为「真失效（重登）」或「瞬时（可重试）」。
     */
    fun refreshFailureOf(server: String): RefreshFailure? = lastRefreshFailure[server]

    /**
     * 出站请求预挂 Bearer 用（对齐 Bastion：只在 access token 有效期内直带，
     * 过期前 60s 预刷新；调用方为 OkHttp 同步线程，内部自行桥接 IO）。
     *
     * @return 可用的 access token；无会话 / 刷新被服务端拒绝返回 null
     * （null → 请求不带 Authorization，401 兜底或上层引导重登）。
     */
    fun accessTokenForHost(host: String): String? {
        val server = serverByHost[host] ?: return null
        return runBlocking(Dispatchers.IO) { resolveAccessToken(server) }
    }

    /** 取当前可用 token：有效期内直取；过期/临期 → 预刷新（Bastion 语义）。 */
    private suspend fun resolveAccessToken(server: String): String? {
        val stored = currentAccessToken(server) ?: return null
        val expiry = credentials.getString(CredentialKeys.accessExpiry(server))?.toLongOrNull()
        val fresh = expiry != null && expiry > System.currentTimeMillis() + REFRESH_LEAD_MS
        if (fresh) return stored
        // 过期或即将过期：预刷新。瞬时失败时退回旧 token（若仍有效期内）——
        // 仍可能成功；被服务端拒绝（Invalid）才返回 null。
        return when (val outcome = refresh(server)) {
            is RefreshOutcome.Success -> outcome.accessToken
            RefreshOutcome.Invalid -> null
            is RefreshOutcome.Transient -> currentAccessToken(server)
        }
    }

    fun currentAccessToken(server: String): String? =
        credentials.getString(CredentialKeys.access(server))

    /** 登记 host→server（登录 / 解锁成功时调用；供 401 刷新与请求拦截器反查）。 */
    fun registerServer(server: String) {
        noteServer(server)
    }

    fun logout(server: String) {
        credentials.remove(CredentialKeys.access(server))
        credentials.remove(CredentialKeys.refresh(server))
        credentials.remove(CredentialKeys.accessExpiry(server))
        credentials.remove(CredentialKeys.protectedKey(server))
        lastRefreshFailure.remove(server)
        hostOf(server)?.let { serverByHost.remove(it) }
    }

    /**
     * 解包账号对称密钥：用 StretchedMasterKey 解开服务端返回的受保护密钥。
     *
     * 这是「登录成功」到「能显示条目」之间必经的一步——
     * 只有拿到账号对称密钥，才能用 [CipherMapper] 把密文 DTO 解密成 VaultItem。
     */
    suspend fun unpackAccountKey(server: String, masterKey: SecureBytes): Result<SymmetricCryptoKey> =
        runCatching {
            val protected = credentials.getString(CredentialKeys.protectedKey(server))
                ?: error("No protected symmetric key stored for ")
            val stretched = crypto.stretchMasterKey(masterKey)
            try {
                crypto.decryptSymmetricKey(protected, stretched)
            } finally {
                // StretchedMasterKey 用毕即清，不留在内存
                stretched.clear()
            }
        }

    /** 供 [BitwardenTokenRefresher] 反查：OkHttp 只提供 host。 */
    fun findServerByHost(host: String): String? = serverByHost[host]

    private fun noteServer(server: String) {
        hostOf(server)?.let { serverByHost[it] = server }
    }

    private fun hostOf(server: String): String? =
        runCatching { URL(server).host }.getOrNull()?.takeIf { it.isNotBlank() }

    /** access token 到期毫秒 = now + expiresIn 秒（Bastion accessTokenExpiresAt 同款）。 */
    private fun persist(server: String, access: String, refresh: String?, expiresIn: Int) {
        credentials.putString(CredentialKeys.access(server), access)
        credentials.putString(
            CredentialKeys.accessExpiry(server),
            (System.currentTimeMillis() + expiresIn * MILLIS_PER_SECOND).toString(),
        )
        if (!refresh.isNullOrBlank()) {
            credentials.putString(CredentialKeys.refresh(server), refresh)
        }
    }

    private fun deriveMasterKey(
        pre: PreLoginResponse,
        password: String,
        salt: String,
    ): SecureBytes {
        val kdf = pre.resolvedKdf()
        val iterations = pre.resolvedIterations()
        return when (kdf) {
            KDF_PBKDF2 -> crypto.deriveMasterKeyPbkdf2(password, salt, iterations)
            KDF_ARGON2ID -> crypto.deriveMasterKeyArgon2(
                password = password,
                salt = salt,
                iterations = iterations,
                memoryMb = pre.resolvedMemoryMb() ?: DEFAULT_ARGON2_MEMORY_MB,
                parallelism = pre.resolvedParallelism() ?: DEFAULT_ARGON2_PARALLELISM,
            )
            else -> throw IllegalArgumentException("Unsupported Kdf type: {pre.kdf}")
        }
    }

    /** 解锁 / 添加库的结果分类，便于上层给出可执行的提示（Docs/10 §5）。 */
    private companion object {
        const val KDF_PBKDF2 = 0
        const val KDF_ARGON2ID = 1
        const val DEFAULT_ARGON2_MEMORY_MB = 64
        const val DEFAULT_ARGON2_PARALLELISM = 4
        const val CLIENT_ID = "mobile"
        /** 官方 DeviceType 枚举：0 = Android（此前误用 1 = iOS，服务器端显示错误设备类型）。 */
        const val DEVICE_TYPE = "0"
        /** 预刷新提前量（Bastion 同值 60s）：到期前即换新，避免请求撞 401。 */
        const val REFRESH_LEAD_MS = 60_000L
        const val MILLIS_PER_SECOND = 1_000L
    }
}

/**
 * refresh 结果三分（语义对齐 Bastion RefreshOutcome）：
 * - [Success]：新 access token 已持久化；
 * - [Invalid]：refresh token 被服务端拒绝（400/401）或本地缺失 → 需要重新登录；
 * - [Transient]：网络异常 / 403（WAF）/429/5xx → 登录态保留，可稍后重试，
 *   **绝不因此把用户踢去重新登录**。
 */
sealed interface RefreshOutcome {
    data class Success(val accessToken: String) : RefreshOutcome
    data object Invalid : RefreshOutcome
    data class Transient(val detail: String) : RefreshOutcome
}

/**
 * HTTP 状态 → 刷新失败类型（纯函数，可单测）：
 * 只有 400/401 认定凭据真失效；403/429/5xx 归瞬时（对齐 Bastion：403 是
 * WAF/反代拦截，重试基本无效但**绝不等于登录过期**）。
 * @suppress MagicNumber：400/401 为 HTTP 状态码语义常量，命名化反而降低可读性。
 */
@Suppress("MagicNumber")
internal fun refreshFailureKind(code: Int): BitwardenAuthRepository.RefreshFailure = when (code) {
    400, 401 -> BitwardenAuthRepository.RefreshFailure.Invalid
    else -> BitwardenAuthRepository.RefreshFailure.Transient
}

/** 2FA 提交参数。 */
private data class TwoFactorSubmit(val provider: Int, val code: String)

/**
 * 服务端要求两步验证（密码授权返回 400 two_factor_required）。
 *
 * @param providers Bitwarden 2FA provider 枚举：0 = Authenticator(TOTP)，
 *                  1 = Email（服务端自动发码），3 = Duo，4 = YubiKey 等；
 *                  M1 UI 只引导 0/1。
 */
class TwoFactorRequiredException(val providers: List<Int>) : Exception(
    "Two-factor authentication required: providers=$providers",
)

/** 已带验证码提交仍返回 2FA 挑战：验证码错误或已过期。 */
class TwoFactorInvalidException : Exception("Two-factor code is invalid or expired")

/**
 * 从 token 错误响应体解析 TwoFactorProviders（Bitwarden 经典 OAuth 扩展字段，
 * 兼容 Vaultwarden 的字符串数组与官方客户端的数值数组两种形态）。
 *
 * @return 有 2FA 挑战返回 provider 列表；响应非 JSON / 无该字段返回 null（交由
 *          上层按 HTTP 状态分类）。
 */
internal fun parseTwoFactorProviders(errorBody: String?): List<Int>? {
    val root = errorBody?.let { body ->
        runCatching { BitwardenJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
    } ?: return null

    fun providersFrom(value: kotlinx.serialization.json.JsonElement?): List<Int>? {
        val array = value as? JsonArray ?: return null
        if (array.isEmpty()) return null
        val providers = array.mapNotNull { element ->
            val primitive = (element as? JsonPrimitive) ?: return@mapNotNull null
            primitive.content.toIntOrNull()
        }
        return providers.takeIf { it.isNotEmpty() }
    }

    // 官方 PascalCase 与 Vaultwarden camelCase 双形态
    val providers = providersFrom(root["TwoFactorProviders"])
        ?: providersFrom(root["twoFactorProviders"])
    return providers
}
