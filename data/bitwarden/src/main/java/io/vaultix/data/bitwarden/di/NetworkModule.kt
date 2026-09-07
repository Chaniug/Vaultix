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
 * 请求头组（Cloudflare 兼容指纹）参考 Bastion 项目（GPL-3.0，Copyright 2025
 * JoyinJoester）bitwarden/api/BitwardenApiFactory.kt 的 header interceptor：
 * 完整桌面 Chrome UA + Sec-Ch-Ua 系列 + Keyguard-Client，用于绕过自托管站点
 * 前置的 Cloudflare/WAF 对非浏览器 UA 的拦截；Bitwarden-Client-Name/Version
 * 供官方服务器按客户端形态决定返回结构。数值与 Bastion 一致（其在 CF 后的
 * Vaultwarden 实例上实测可用）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.bitwarden.di

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.vaultix.data.bitwarden.api.BitwardenIdentityApi
import io.vaultix.data.bitwarden.auth.BitwardenTokenRefresher
import io.vaultix.data.bitwarden.api.BitwardenVaultApi
import io.vaultix.data.bitwarden.auth.BitwardenAuthRepository
import io.vaultix.data.bitwarden.network.AccessTokenProvider
import io.vaultix.data.bitwarden.network.BitwardenAuthenticator
import io.vaultix.data.bitwarden.network.BitwardenEndpoints
import io.vaultix.data.bitwarden.network.BitwardenJson
import io.vaultix.data.bitwarden.network.TokenRefresher
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    // 网络超时策略（Docs/17 P0）：30s 内失败要能被用户感知为"失败"而非"卡死"；
    // ping 间隔 30s 与读超时同值，防反代静默断连后长时间空等。
    private const val CONNECT_TIMEOUT_SECONDS = 30L
    private const val READ_TIMEOUT_SECONDS = 30L
    private const val WRITE_TIMEOUT_SECONDS = 30L
    private const val PING_INTERVAL_SECONDS = 30L

    // ---- Cloudflare/WAF 兼容请求指纹（与 Bastion 同值，见文件头溯源）----
    /** 完整桌面 Chrome UA：自托管站点前置 CF 时常拦截非浏览器 UA。 */
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.6778.140 Safari/537.36"
    private const val SEC_CH_UA = "\"Not.A/Brand\";v=\"8\", \"Chromium\";v=\"131\""
    private const val SEC_CH_UA_MOBILE = "?0"
    private const val SEC_CH_UA_PLATFORM = "Linux"
    /** Keyguard 生态标识（Bastion/Keyguard 同款，部分 CF 规则依赖）。 */
    private const val KEYGUARD_CLIENT = "1"
    /**
     * 官方服务器按客户端名/版本决定返回结构（新 cipher type、SSH Key 字段、2FA 流程）；
     * 与 Bastion 一致声明 desktop，两端服务器均接受。
     */
    private const val CLIENT_NAME = "desktop"
    private const val CLIENT_VERSION = "2025.1.0"

    @Provides @Singleton
    fun provideJson(): Json = BitwardenJson

    /**
     * 默认实现：尚未接入认证层时不做刷新（返回 null，401 会透传给上层）。
     * 认证模块就绪后应提供真实实现覆盖此绑定。
     */
    @Provides @Singleton
    fun provideTokenRefresher(impl: BitwardenTokenRefresher): TokenRefresher = impl

    @Provides @Singleton
    fun provideAccessTokenProvider(impl: BitwardenAuthRepository): AccessTokenProvider =
        AccessTokenProvider { host -> impl.accessTokenForHost(host) }

    @Provides @Singleton
    fun provideAuthenticator(refresher: Provider<TokenRefresher>): Authenticator =
        BitwardenAuthenticator(refresher)

    @Provides @Singleton
    fun provideOkHttpClient(
        authenticator: Authenticator,
        authInterceptor: okhttp3.Interceptor,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // 反代（nginx / Cloudflare）会静默关闭空闲连接，复用即挂死
            .retryOnConnectionFailure(true)
            .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
            .authenticator(authenticator)
            // 请求头指纹（User-Agent 等，见文件头溯源）
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .header("Keyguard-Client", KEYGUARD_CLIENT)
                    .header("Accept-Language", java.util.Locale.getDefault().toLanguageTag())
                    .header("Sec-Ch-Ua", SEC_CH_UA)
                    .header("Sec-Ch-Ua-Mobile", SEC_CH_UA_MOBILE)
                    .header("Sec-Ch-Ua-Platform", SEC_CH_UA_PLATFORM)
                    .header("Bitwarden-Client-Name", CLIENT_NAME)
                    .header("Bitwarden-Client-Version", CLIENT_VERSION)
                    .build()
                chain.proceed(request)
            }
            // 预挂 Bearer：避免「每次请求先 401 再刷新」→ 重启后快速解锁必失效
            .addInterceptor(authInterceptor)
            .build()

    @Provides @Singleton
    fun provideAuthInterceptor(
        provider: Provider<AccessTokenProvider>,
    ): okhttp3.Interceptor =
        io.vaultix.data.bitwarden.network.BitwardenAuthInterceptor(provider)

    /**
     * 只提供 Builder，不固定 baseUrl：用户可配置官方或自托管服务器，
     * 由 [BitwardenApiFactory] 在运行时按库地址创建实例。
     */
    @Provides @Singleton
    fun provideRetrofitBuilder(json: Json, client: OkHttpClient): Retrofit.Builder =
        Retrofit.Builder()
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
}

/**
 * 按服务器地址创建 API 实例。
 *
 * 之所以不直接注入 API：Vaultix 支持多库（多个 Bitwarden / Vaultwarden 账号），
 * 每个库的 baseUrl 不同，必须在运行时按库构造。
 */
@Singleton
class BitwardenApiFactory @Inject constructor(
    private val builder: Retrofit.Builder,
) {
    fun identity(server: String): BitwardenIdentityApi = builder
        .baseUrl(BitwardenEndpoints.identity(server))
        .build()
        .create(BitwardenIdentityApi::class.java)

    fun vault(server: String): BitwardenVaultApi = builder
        .baseUrl(BitwardenEndpoints.api(server))
        .build()
        .create(BitwardenVaultApi::class.java)
}
