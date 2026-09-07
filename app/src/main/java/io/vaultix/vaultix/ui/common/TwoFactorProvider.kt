package io.vaultix.vaultix.ui.common

/**
 * Bitwarden 2FA provider 常量与选择辅助。
 *
 * 值对齐官方枚举：0 = Authenticator(TOTP)，1 = Email（服务端自动发码），
 * 3 = Duo，4 = YubiKey OTP 等（其它 provider 仍可输入一次性码，只是无专有文案）。
 */
object TwoFactorProvider {
    const val AUTHENTICATOR = 0
    const val EMAIL = 1

    /** 默认选中项：优先身份验证器，其次邮箱，最后取服务器下发的第一个。 */
    fun defaultOf(providers: List<Int>): Int =
        providers.firstOrNull { it == AUTHENTICATOR }
            ?: providers.firstOrNull { it == EMAIL }
            ?: providers.firstOrNull()
            ?: AUTHENTICATOR

    fun labelOf(provider: Int): Int = when (provider) {
        AUTHENTICATOR -> io.vaultix.vaultix.R.string.two_factor_provider_totp
        EMAIL -> io.vaultix.vaultix.R.string.two_factor_provider_email
        else -> io.vaultix.vaultix.R.string.two_factor_provider_other
    }
}
