package io.vaultix.data.bitwarden.network

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Provider

/**
 * 请求头预挂 Bearer（修复「每次请求先 401 再刷新 → 重启/快速解锁后必报登录失效」）。
 *
 * - 仅 api 数据端点（路径以 /api/ 开头）预挂；identity 端点（prelogin / token）
 *   不挂——登录与刷新走 grant body，不依赖 Authorization；
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
