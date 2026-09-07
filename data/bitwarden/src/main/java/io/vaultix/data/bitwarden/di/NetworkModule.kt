/*
 * Vaultix — data:bitwarden
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
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

    @Provides @Singleton
    fun provideJson(): Json = BitwardenJson

    /**
     * 默认实现：尚未接入认证层时不做刷新（返回 null，401 会透传给上层）。
     * 认证模块就绪后应提供真实实现覆盖此绑定。
     */
    @Provides @Singleton
    fun provideTokenRefresher(impl: BitwardenTokenRefresher): TokenRefresher = impl

    @Provides @Singleton
    fun provideAuthenticator(refresher: Provider<TokenRefresher>): Authenticator =
        BitwardenAuthenticator(refresher)

    @Provides @Singleton
    fun provideOkHttpClient(authenticator: Authenticator): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // 反代（nginx / Cloudflare）会静默关闭空闲连接，复用即挂死
            .retryOnConnectionFailure(true)
            .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
            .authenticator(authenticator)
            .build()

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
