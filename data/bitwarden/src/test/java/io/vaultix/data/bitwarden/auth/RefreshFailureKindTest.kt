package io.vaultix.data.bitwarden.auth

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * refresh 失败归类回归（Bastion RefreshOutcome 语义对齐，Docs/17）：
 * - 400/401（invalid_grant / 已吊销）→ Invalid = 需要重新登录；
 * - 403（CF/WAF）/429/5xx → Transient = 保留登录态可重试，**绝不误报登录失效**
 *   （自托管 + Cloudflare 场景最容易把 WAF 拦截误判成凭据失效——Bastion 明确
 *   警告过此误伤，见其 BitwardenHttpStatusException / ApiFactory 注释）。
 */
class RefreshFailureKindTest {

    @Test
    fun authRejectionMapsToInvalid() {
        assertEquals(BitwardenAuthRepository.RefreshFailure.Invalid, refreshFailureKind(400))
        assertEquals(BitwardenAuthRepository.RefreshFailure.Invalid, refreshFailureKind(401))
    }

    @Test
    fun transientStatusesMapToTransient() {
        // WAF / 反代拦截（403）、限流（429）、网关（502/503/504）均不误报失效
        assertEquals(BitwardenAuthRepository.RefreshFailure.Transient, refreshFailureKind(403))
        assertEquals(BitwardenAuthRepository.RefreshFailure.Transient, refreshFailureKind(429))
        assertEquals(BitwardenAuthRepository.RefreshFailure.Transient, refreshFailureKind(500))
        assertEquals(BitwardenAuthRepository.RefreshFailure.Transient, refreshFailureKind(502))
        assertEquals(BitwardenAuthRepository.RefreshFailure.Transient, refreshFailureKind(503))
    }
}
