package io.vaultix.data.bitwarden.network

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Provider

/**
 * 请求头预挂 Bearer（修复「每次请求先 401 再刷新 → 重启/快速解锁后必报登录失效」）。
 *
 * - **只跳过** identity 端点（`/identity/`，prelogin / token 走 grant body）；
 *   ⚠️ 除它之外的任何路径，只要该 host 有 token 就无条件预挂 ——
 *   **不是**「仅 `/api/` 端点」。这意味着复用本 client 的非 Bitwarden 请求，
 *   其 `Authorization` 会被 `Bearer` 覆盖（还会把 token 发到对方服务器）。
 *   ⇒ WebDAV 必须走 `webDavProductionClient()` 派生的专用 client（见 #288），
 *   不要指望本拦截器按路径放行。
 * - access token 由认证层按到期时间预刷新（Bastion accessTokenExpiresAt 语义，
 *   见 BitwardenAuthRepository.accessTokenForHost）；
 * - 401 后的刷新重试由 [BitwardenAuthenticator] 负责，两者互不冲突。
 *
 * ⚠️ 注入 [Provider]<AccessTokenProvider> 而非实例：认证仓库经
 * ApiFactory → Retrofit.Builder → OkHttpClient 与本类构成构造期依赖环，
 * 与 BitwardenAuthenticator 同款断环点——请求到达时才解析（彼时认证层必已就绪）。
 */
class BitwardenAuthInterceptor(
    private val tokenProviderProvider: Provider<AccessTokenProvider>,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url
        if (url.encodedPath.startsWith(IDENTITY_PATH_PREFIX)) {
            return chain.proceed(request)
        }
        val token = tokenProviderProvider.get().accessToken(url.host)
        val authenticated = if (token.isNullOrBlank()) {
            request
        } else {
            request.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        }
        return chain.proceed(authenticated)
    }

    private companion object {
        const val IDENTITY_PATH_PREFIX = "/identity/"
    }
}
