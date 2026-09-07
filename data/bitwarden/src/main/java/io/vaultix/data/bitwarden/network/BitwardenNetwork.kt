/*
 * Vaultix — data:bitwarden
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 网络层健壮性（Docs/17），其中 401 刷新与死连接防护的思路参考 Bastion 的
 * 实战结论（GPL-3.0，Copyright 2025 JoyinJoester），本文件为独立编写：
 *   - 超时 30s（Bastion 原为 60s，用户感知即"卡死"）
 *   - retryOnConnectionFailure + pingInterval：反代会静默关闭空闲连接，复用即挂死
 *   - 401 按 host 刷新并仅重试一次，priorResponse != null 即停，防死循环
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.bitwarden.network

import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import javax.inject.Provider

/**
 * 全局 Json 实例。
 *
 * - `ignoreUnknownKeys`：Bitwarden 服务端新增字段时**不崩溃**——这是第三方客户端
 *   最常见的线上事故（Docs/17 第 1 节，最高优先级）。
 * - `explicitNulls=false` / `coerceInputValues=true`：缺字段或类型不符时取默认值，
 *   而不是抛异常中断同步。
 */
val BitwardenJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

/**
 * 按主机刷新 access token。
 *
 * 多库（bitwarden.com / 自托管）场景下必须按 host 区分，否则会拿 A 库的 token
 * 去请求 B 库。由认证层实现并注入；M1 阶段提供默认空实现（见 di/NetworkModule）。
 */
fun interface TokenRefresher {
    /** 返回新的 access token；刷新失败返回 null（交由上层触发重新登录）。 */
    fun refresh(host: String): String?
}

/**
 * 出站请求的 access token 提供者（认证层实现，见 di/NetworkModule）。
 *
 * 与 [TokenRefresher] 的分工：本接口给**每个请求**预挂 Bearer（避免先 401 再
 * 刷新的两段式请求），刷新只作为 access token 过期后的兜底。
 */
fun interface AccessTokenProvider {
    /** 该 host 当前有效的 access token；无会话返回 null（请求不带 Authorization）。 */
    fun accessToken(host: String): String?
}

/**
 * 401 自动恢复：任意 Bitwarden 请求收到 401 时，按 host 刷新 token 后重试一次。
 *
 * ⚠️ 防死循环：`priorResponse != null` 说明已经重试过，直接放弃返回 null。
 *
 * ⚠️ 注入的是 [Provider]<TokenRefresher> 而非实例：刷新实现（认证仓库）经
 * ApiFactory → Retrofit.Builder → OkHttpClient 与本类构成构造期依赖环，
 * 这里延迟到收到 401 时才解析——彼时认证仓库必然已构造完成（Dagger 断环点）。
 */
class BitwardenAuthenticator(
    private val refresherProvider: Provider<TokenRefresher>,
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.priorResponse != null) return null

        val host = response.request.url.host
        val newToken = refresherProvider.get().refresh(host) ?: return null
        if (newToken.isBlank()) return null

        return response.request.newBuilder()
            .header("Authorization", "Bearer $newToken")
            .build()
    }
}

/** 服务端基址拼装。 */
object BitwardenEndpoints {
    const val OFFICIAL = "https://vault.bitwarden.com"
    const val OFFICIAL_EU = "https://vault.bitwarden.eu"

    fun identity(server: String): String = "${server.trimEnd('/')}/identity/"
    fun api(server: String): String = "${server.trimEnd('/')}/api/"
}
