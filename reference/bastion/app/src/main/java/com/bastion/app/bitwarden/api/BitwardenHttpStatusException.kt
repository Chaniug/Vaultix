package com.bastion.app.bitwarden.api

/**
 * Bitwarden 服务端返回非 2xx 状态码。
 *
 * 保留 [code]，让上层能区分两类完全不同的失败：
 *
 * - **400 / 401**：refresh token 被服务端拒绝（`invalid_grant` 等）→ 凭据确实失效，
 *   必须让用户重新登录；
 * - **403 / 429 / 5xx**：服务端或前置网关（Cloudflare/WAF/反代）临时拒绝 → 属于可恢复错误，
 *   应退避重试，**绝不**清空本地凭据或把用户踢去重新登录。
 *
 * 这一点对齐 Bitwarden 官方客户端：官方只在 "Invalid Access Token" / "Invalid Security Stamp"
 * 时才触发 logout，403 根本不在登出判定里。
 */
class BitwardenHttpStatusException(
    val code: Int,
    val errorBody: String? = null
) : Exception("Bitwarden HTTP $code")
