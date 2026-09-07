package io.vaultix.data.bitwarden.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 2FA 挑战解析（经典 OAuth two_factor_required 响应）：
 * - Vaultwarden：TwoFactorProviders 为字符串数组（["0","1"]）
 * - 官方 Bitwarden：数值数组（[0,1]）
 * - 双形态键名 PascalCase / camelCase
 */
class TwoFactorParsingTest {

    @Test
    fun vaultwarden_stringProviders() {
        val body = "{\"error\":\"two_factor_required\",\"error_description\":\"Two factor required.\"," +
            "\"TwoFactorProviders\":[\"0\",\"1\"]}"

        assertEquals(listOf(0, 1), parseTwoFactorProviders(body))
    }

    @Test
    fun officialBitwarden_numericProviders() {
        val body = """{"error":"two_factor_required","TwoFactorProviders":[0,3,4]}"""

        assertEquals(listOf(0, 3, 4), parseTwoFactorProviders(body))
    }

    @Test
    fun camelCaseKeys_alsoAccepted() {
        val body = """{"twoFactorProviders":["1"]}"""

        assertEquals(listOf(1), parseTwoFactorProviders(body))
    }

    @Test
    fun invalidGrant_hasNoProviders_returnsNull() {
        val body = """{"error":"invalid_grant","error_description":"Invalid username or password."}"""

        assertNull(parseTwoFactorProviders(body))
    }

    @Test
    fun nonJsonBody_returnsNull() {
        assertNull(parseTwoFactorProviders("<html>gateway error</html>"))
        assertNull(parseTwoFactorProviders(null))
    }

    @Test
    fun emptyProvidersArray_returnsNull() {
        assertNull(parseTwoFactorProviders("""{"error":"two_factor_required","TwoFactorProviders":[]}"""))
    }
}
