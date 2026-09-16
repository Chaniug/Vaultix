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

import io.vaultix.common.logging.VaultixLog
import io.vaultix.common.logging.redact
import io.vaultix.crypto.SecureBytes
import io.vaultix.crypto.SymmetricCryptoKey
import io.vaultix.crypto.VaultixCrypto
import io.vaultix.data.bitwarden.api.BitwardenIdentityApi
import io.vaultix.data.bitwarden.api.PreLoginRequest
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
 * 认证链路的日志 tag。
 *
 * 单独一个 tag 是**刻意**的：排查「登录已失效」（`.ai/issues/07` #8）时要能
 * `adb logcat -s VaultixAuth` 只看这一条链路，不被其它日志淹没。
 */
private const val TAG = "VaultixAuth"

/**
 * Bitwarden 认证编排。
 *
 * 时序（对齐 Bitwarden 官方客户端的**实际行为**，非文档表述）：
 * 1. accounts/prelogin 取该账号的 KDF 类型与参数；
 * 2. 以 **email.trim().lowercase() 为盐** 派生 MasterKey（PBKDF2 或 Argon2id，服务端决定）；
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
    }.observed("login", server)

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
    }.observed("login2fa", server)

    /**
     * 是否已登录过这一台服务器（有受保护的账号对称密钥；没密钥就谈不上本地解锁）。
     *
     * 注意区分两件事：「没密钥」= 从没登录成功过 ⇒ 只能走登录；
     * 「有密钥但 token 过期」= 只是令牌失效 ⇒ 刷新即可，本地解锁照旧能开库。
     * （token 只决定能不能同步；解锁只需要「主密码 + 账号密钥」，
     * 所以与 Bitwarden 官方客户端一样**离线也能开库**。）
     */
    fun hasStoredAccountKey(server: String): Boolean =
        !credentials.getString(CredentialKeys.protectedKey(server)).isNullOrBlank()

    /**
     * 已登录状态下用主密码**本地解锁**：派生 MasterKey，再解开本地保存的账号对称密钥。
     *
     * **全程不走 `connect/token`** —— 所以即使账号开了两步验证，这里也不会索要验证码。
     * 这是与 Bitwarden 官方客户端一致的行为：**输入主密码只是「解开库」**，不是「重新登录」；
     * 登录只发生在「添加库」与「会话被吊销」两种场合。
     *
     * @return 账号对称密钥；`null` = 无法本地解锁（没存过账号密钥 / 主密码不对 /
     *         取不到 KDF 参数），此时调用方应回退到完整登录流程。
     */
    suspend fun unlockWithMasterKey(
        server: String,
        email: String,
        password: String,
    ): SymmetricCryptoKey? {
        if (!hasStoredAccountKey(server)) return null
        val salt = email.trim().lowercase()
        // ① 先用本地持久化的 KDF 参数 —— 这样飞行模式也能开库（官方客户端同款行为）。
        val local = readKdfProfile(server)
        if (local != null) {
            val key = deriveAndUnpack(server, password, salt, local)
            if (key != null) return key
        }
        // ② 本地快照缺失（旧版本存的 / 服务器改了 KDF 配置）⇒ 取一次 prelogin 再试。
        //    取不到（无网络 / 账号不存在）就返回 null，由调用方回落登录 —— 不谎报成功。
        val remote = runCatching {
            apiFactory.identity(server).preLogin(PreLoginRequest(email)).toKdfProfile()
        }.getOrNull() ?: return null
        persistKdf(server, remote)
        return deriveAndUnpack(server, password, salt, remote)
    }

    /** 派生 MasterKey 并解包账号密钥（MasterKey 用完即擦，失败路径也不泄漏）。 */
    private fun deriveAndUnpack(
        server: String,
        password: String,
        salt: String,
        profile: KdfProfile,
    ): SymmetricCryptoKey? {
        val masterKey = deriveMasterKey(profile, password, salt)
        return try {
            unpackAccountKey(server, masterKey).getOrNull()
        } finally {
            masterKey.zero()
        }
    }

    private fun readKdfProfile(server: String): KdfProfile? =
        credentials.getString(CredentialKeys.kdfProfile(server))?.let { KdfProfile.parse(it) }

    private fun persistKdf(server: String, profile: KdfProfile) {
        credentials.putString(CredentialKeys.kdfProfile(server), profile.serialize())
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

        // 顺手把 KDF 参数存下来：下次冷启动解锁就不必再打一次 prelogin（见 unlockWithMasterKey）。
        val profile = pre.toKdfProfile()
        persistKdf(server, profile)
        val masterKey = deriveMasterKey(profile, password, salt)
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
            VaultixLog.d(TAG) { "refresh ok  server=${redact(server)}" }
            RefreshOutcome.Success(token.accessToken)
        } catch (error: HttpException) {
            val failure = refreshFailureKind(error.code())
            if (failure == RefreshFailure.Invalid) {
                // 服务端明确拒绝 refresh token（invalid_grant / 已吊销）→ 必须重新登录
                lastRefreshFailure[server] = RefreshFailure.Invalid
                // ★ 这条日志就是 #8 第三根因的分界线：只有服务端**明确拒绝** refresh token
                //  才算真失效（该引导重登）；其余一律 Transient（保留登录态）。
                //   以前没有日志时，"全网抖动被当成凭据失效"完全看不出来。
                VaultixLog.w(TAG) { "refresh invalid  server=${redact(server)}  http=${error.code()}" }
                RefreshOutcome.Invalid
            } else {
                // 403（WAF/反代拦截）/429/5xx：瞬时故障，保留登录态与凭据
                lastRefreshFailure[server] = RefreshFailure.Transient
                // 403(WAF/反代) / 429 / 5xx —— **不是**凭据问题，记下 http 码才分得清。
                VaultixLog.w(TAG) { "refresh transient  server=${redact(server)}  http=${error.code()}" }
                RefreshOutcome.Transient("刷新令牌时服务器返回 ${error.code()}")
            }
        } catch (error: Exception) {
            lastRefreshFailure[server] = RefreshFailure.Transient
            // 只记异常**类型**：message 可能带响应体（见 observed 的 KDoc）。
            VaultixLog.w(TAG) { "refresh error  server=${redact(server)}  kind=${error::class.simpleName}" }
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
    // OkHttp Authenticator 是同步回调（非 suspend），必须 runBlocking 桥接；此处为框架线程边界，注入调度器无测试收益。
    @Suppress("InjectDispatcher")
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
        credentials.remove(CredentialKeys.kdfProfile(server))
        lastRefreshFailure.remove(server)
        hostOf(server)?.let { serverByHost.remove(it) }
    }

    /**
     * 解包账号对称密钥：用 StretchedMasterKey 解开服务端返回的受保护密钥。
     *
     * 这是「登录成功」到「能显示条目」之间必经的一步——
     * 只有拿到账号对称密钥，才能用 [CipherMapper] 把密文 DTO 解密成 VaultItem。
     */
    fun unpackAccountKey(server: String, masterKey: SecureBytes): Result<SymmetricCryptoKey> =
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
        profile: KdfProfile,
        password: String,
        salt: String,
    ): SecureBytes {
        return when (profile.type) {
            KDF_PBKDF2 -> crypto.deriveMasterKeyPbkdf2(password, salt, profile.iterations)
            KDF_ARGON2ID -> crypto.deriveMasterKeyArgon2(
                password = password,
                salt = salt,
                iterations = profile.iterations,
                memoryMb = profile.memoryMb ?: DEFAULT_ARGON2_MEMORY_MB,
                parallelism = profile.parallelism ?: DEFAULT_ARGON2_PARALLELISM,
            )
            else -> throw IllegalArgumentException("Unsupported Kdf type: ${profile.type}")
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

/**
 * 认证结果的统一打点（2026-09-16 新增）。
 *
 * 排查「登录已失效」（`.ai/issues/07` #8）时要回答的**第一个问题**是：
 * 这次失败属于哪一类 —— 凭据真的坏了（该重登），还是网络/服务端抖动（该保留登录态）？
 * 没有日志时这个问题只能靠反复复现去猜（#8 当时就是这么定位的）。
 *
 * ## ⚠️ 只记**异常类型**，绝不记 message 或异常栈
 *
 * `retrofit2.HttpException.message` 形如 `HTTP 400 Bad Request`，**看似无害**；
 * 但其它异常的 message 完全可能携带服务端响应体，而响应体可能有 token 或账号信息。
 * ⇒ 这里**只取 `error::class.simpleName`**，并且**不把 throwable 传给日志**
 *   （传了就会打印 message + 栈）。想要栈，必须先逐类审计 message 是否安全 —— 那是另一件事。
 *
 * 同理**不记 email**（PII），server 经 [redact] 脱敏（只保留首尾各 2 字符）。
 */
private fun <T> Result<T>.observed(action: String, server: String): Result<T> =
    onSuccess {
        VaultixLog.d(TAG) { "$action ok  server=${redact(server)}" }
    }.onFailure { error ->
        VaultixLog.w(TAG) { "$action failed  server=${redact(server)}  kind=${error::class.simpleName}" }
    }
