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
 * 端点与字段以 Bitwarden 官方 API 为准；接口划分方式参考 Bastion 项目
 * （GPL-3.0，Copyright 2025 JoyinJoester）的 BitwardenApi.kt，本文件为独立编写。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.bitwarden.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST

/**
 * Bitwarden 身份服务（identity 基址）端点。
 *
 * M1 首批只声明两个端点：prelogin（取 KDF 参数）与 connect/token
 * （登录与刷新共用，按 grant_type 区分）。
 */
interface BitwardenIdentityApi {

    /** 取该账号的 KDF 类型与参数，用于派生 Master Key。 */
    @POST("accounts/prelogin")
    suspend fun preLogin(@Body request: PreLoginRequest): PreLoginResponse

    /**
     * connect/token：grant_type=password 为登录，refresh_token 为刷新。
     * 以 FieldMap 承接，避免为每种 grant 各建一个请求模型。
     */
    @FormUrlEncoded
    @POST("connect/token")
    suspend fun token(@FieldMap fields: Map<String, String>): TokenResponse
}

@Serializable
data class PreLoginRequest(
    @SerialName("email") val email: String,
)

@Serializable
data class PreLoginResponse(
    @SerialName("Kdf") val kdf: Int,
    @SerialName("KdfIterations") val kdfIterations: Int,
    @SerialName("KdfMemory") val kdfMemory: Int? = null,
    @SerialName("KdfParallelism") val kdfParallelism: Int? = null,
)

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_in") val expiresIn: Int,
    @SerialName("Key") val key: String? = null,
    @SerialName("PrivateKey") val privateKey: String? = null,
)
