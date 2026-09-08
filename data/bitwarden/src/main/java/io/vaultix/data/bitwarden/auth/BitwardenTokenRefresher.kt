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

import io.vaultix.data.bitwarden.network.TokenRefresher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 401 反应式恢复的 [TokenRefresher] 实现（对应 Docs/17 网络健壮性条目）。
 *
 * ⚠️ 关键点：OkHttp Authenticator.authenticate 是**同步回调**，不是 suspend 函数，
 * 因此这里必须用 runBlocking(Dispatchers.IO) 桥接到挂起的刷新逻辑——
 * 这是 Bastion 实战中确认过的写法（其 refreshForHost 内部同样是 runBlocking）。
 *
 * 这是 access token 过期后的**兜底**路径：正常请求已由请求拦截器预挂有效
 * Bearer（过期前 60s 预刷新，见 BitwardenAuthRepository.accessTokenForHost）。
 * 刷新失败（含 Transient）返回 null → 401 原样上行，由上层按
 * refreshFailureOf(server) 区分「真失效需重登」与「瞬时故障可重试」。
 */
@Singleton
class BitwardenTokenRefresher @Inject constructor(
    private val authRepository: BitwardenAuthRepository,
) : TokenRefresher {

    @Suppress("InjectDispatcher") // OkHttp Authenticator 是同步回调（非 suspend），必须 runBlocking 桥接
    override fun refresh(host: String): String? {
        val server = authRepository.findServerByHost(host) ?: return null
        return runBlocking(Dispatchers.IO) {
            val outcome = authRepository.refresh(server)
            (outcome as? RefreshOutcome.Success)?.accessToken
        }
    }
}
