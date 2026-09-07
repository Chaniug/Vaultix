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

/**
 * prelogin 响应。
 *
 * ⚠️ 双形态兼容（2026-09-08 真机验证踩坑）：官方 Bitwarden server 返回
 * PascalCase（`Kdf`/`KdfIterations`），**Vaultwarden ≥ 1.33 返回 camelCase**
 * （`kdf`/`kdfIterations`，其服务端默认 JSON 命名策略为 camelCase），两形态
 * 字段会同时出现在部分响应里。全部声明为可空默认值 + [resolvedXxx] 合并，
 * 二者皆缺时抛出带用户可读消息的异常（UI 显示「出错了：…」而非序列化裸错）。
 */
@Serializable
data class PreLoginResponse(
    // ---- 官方 Bitwarden：PascalCase ----
    @SerialName("Kdf") val kdf: Int? = null,
    @SerialName("KdfIterations") val kdfIterations: Int? = null,
    @SerialName("KdfMemory") val kdfMemory: Int? = null,
    @SerialName("KdfParallelism") val kdfParallelism: Int? = null,
    // ---- Vaultwarden ≥1.33：camelCase ----
    @SerialName("kdf") val kdfLower: Int? = null,
    @SerialName("kdfIterations") val kdfIterationsLower: Int? = null,
    @SerialName("kdfMemory") val kdfMemoryLower: Int? = null,
    @SerialName("kdfParallelism") val kdfParallelismLower: Int? = null,
) {

    fun resolvedKdf(): Int =
        (kdf ?: kdfLower) ?: throw PreLoginFieldsMissingException()

    fun resolvedIterations(): Int =
        (kdfIterations ?: kdfIterationsLower) ?: throw PreLoginFieldsMissingException()

    fun resolvedMemoryMb(): Int? = kdfMemory ?: kdfMemoryLower

    fun resolvedParallelism(): Int? = kdfParallelism ?: kdfParallelismLower
}

/** prelogin 响应缺少 KDF 参数：服务端不是 Bitwarden 兼容实现或被网关改写。 */
class PreLoginFieldsMissingException :
    IllegalArgumentException(
        "服务器未返回 KDF 参数（Kdf/KdfIterations），可能不是 Bitwarden 兼容服务端，" +
            "或请求被网关拦截改写。请检查服务器地址。",
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
