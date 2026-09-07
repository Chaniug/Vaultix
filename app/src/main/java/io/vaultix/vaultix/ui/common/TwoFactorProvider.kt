package io.vaultix.vaultix.ui.common

/**
 * Bitwarden 2FA provider 常量与选择辅助。
 *
 * 枚举与官方一致（Bastion 同源注释）：0 = Authenticator(TOTP)，1 = Email
 * （服务端自动发码），2 = Duo，3 = YubiKey OTP（硬件密钥），4 = U2F，
 * 6 = Organization Duo，7 = WebAuthn。
 *
 * 可输入一次性码的方式：0/1/2/3（及 6，与 Duo 相同协议）。U2F/WebAuthn
 * 需要浏览器式交互，桌面/移动 API 客户端不展示（服务器会下发可输入项）。
 */
object TwoFactorProvider {
    const val AUTHENTICATOR = 0
    const val EMAIL = 1
    const val DUO = 2
    const val YUBIKEY = 3
    const val U2F = 4
    const val ORGANIZATION_DUO = 6
    const val WEBAUTHN = 7

    /** 默认选中项：优先身份验证器，其次邮箱，再次 Duo/YubiKey，最后服务器第一个。 */
    fun defaultOf(providers: List<Int>): Int =
        providers.firstOrNull { it == AUTHENTICATOR }
            ?: providers.firstOrNull { it == EMAIL }
            ?: providers.firstOrNull { it in listOf(DUO, YUBIKEY, ORGANIZATION_DUO) }
            ?: providers.firstOrNull()
            ?: AUTHENTICATOR

    /** 是否可通过输入一次性码/安全码完成（U2F/WebAuthn 除外）。 */
    fun codeInputSupported(provider: Int): Boolean =
        provider != U2F && provider != WEBAUTHN

    fun labelOf(provider: Int): Int = when (provider) {
        AUTHENTICATOR -> io.vaultix.vaultix.R.string.two_factor_provider_totp
        EMAIL -> io.vaultix.vaultix.R.string.two_factor_provider_email
        DUO, ORGANIZATION_DUO -> io.vaultix.vaultix.R.string.two_factor_provider_duo
        YUBIKEY -> io.vaultix.vaultix.R.string.two_factor_provider_yubikey
        else -> io.vaultix.vaultix.R.string.two_factor_provider_other
    }
}
