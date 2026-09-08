package io.vaultix.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 随机密码生成器（Bastion/Keyguard 同款核心算法）行为锁定。 */
class PasswordGeneratorTest {

    @Test
    fun generatesRequestedLength() {
        repeat(20) {
            val password = PasswordGenerator.generatePassword(length = 16)
            assertThat(password).hasLength(16)
        }
    }

    @Test
    fun honorsMinimumPerCharset() {
        val password = PasswordGenerator.generatePassword(
            length = 16,
            uppercase = true,
            lowercase = true,
            numbers = true,
            symbols = true,
            uppercaseMin = 2,
            lowercaseMin = 3,
            numbersMin = 2,
            symbolsMin = 3,
        )
        assertThat(password.count { it.isUpperCase() }).isAtLeast(2)
        assertThat(password.count { it.isLowerCase() }).isAtLeast(3)
        assertThat(password.count { it.isDigit() }).isAtLeast(2)
        assertThat(password.count { !it.isLetterOrDigit() }).isAtLeast(3)
    }

    @Test
    fun excludesSimilarAndAmbiguousChars() {
        val password = PasswordGenerator.generatePassword(
            length = 32,
            excludeSimilar = true,
            excludeAmbiguous = true,
        )
        assertThat(password.none { it in "0OlI1" }).isTrue()
        assertThat(password.none { it in "{}[]()/~`'\"" }).isTrue()
    }

    @Test
    fun allCharsetsDisabledYieldsEmpty() {
        val password = PasswordGenerator.generatePassword(
            length = 8,
            uppercase = false,
            lowercase = false,
            numbers = false,
            symbols = false,
        )
        assertThat(password).isEmpty()
    }

    @Test
    fun pinIsDigitsOnlyWithRequestedLength() {
        val pin = PasswordGenerator.generatePinCode(6)
        assertThat(pin).hasLength(6)
        assertThat(pin.all { it.isDigit() }).isTrue()
    }

    @Test
    fun passphraseHasRequestedWordsAndDelimiter() {
        val phrase = PasswordGenerator.generatePassphrase(wordCount = 4, delimiter = "-")
        assertThat(phrase.split("-")).hasSize(4)
    }

    @Test
    fun passphraseWithNumberContainsDigits() {
        val phrase = PasswordGenerator.generatePassphrase(wordCount = 4, includeNumber = true)
        assertThat(phrase.any { it.isDigit() }).isTrue()
    }
}
