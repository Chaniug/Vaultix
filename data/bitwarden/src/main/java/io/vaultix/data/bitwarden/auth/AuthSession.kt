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
 * 「OkHttp Authenticator 需同步阻塞刷新」等结论参考 Bastion 项目
 * （GPL-3.0，Copyright 2025 JoyinJoester）的实战经验，本文件为独立编写。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.bitwarden.auth

/**
 * 一次成功登录的产物。
 *
 * @param masterKey 由主密码派生出的 MasterKey（**非** StretchedMasterKey）。
 *                  调用方用它在内存中解包账号对称密钥；用毕须调用 [masterKey].zero()，
 *                  **绝不落盘、不进日志**（见 Docs/09）。
 */
class AuthSession(
    val server: String,
    val email: String,
    val accessToken: String,
    val refreshToken: String?,
    val expiresIn: Int,
    val masterKey: io.vaultix.crypto.SecureBytes,
) {
    /** 释放会话持有的密钥材料。离开登录/解锁流程时调用。 */
    fun dispose() = masterKey.zero()
}

/** 登录失败的分类，便于上层给出可执行的提示。 */
enum class AuthError {
    /** 无法连接或超时 */
    NETWORK,
    /** 401：邮箱或主密码错误 */
    INVALID_CREDENTIALS,
    /** 需要二次验证（TOTP / 邮箱 / 新设备 OTP） */
    TWO_FACTOR_REQUIRED,
    /** 服务端返回了不支持的 KDF 类型 */
    UNSUPPORTED_KDF,
    UNKNOWN,
}
