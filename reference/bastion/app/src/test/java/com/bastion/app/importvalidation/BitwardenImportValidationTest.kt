package com.bastion.app.importvalidation

import com.bastion.app.bitwarden.crypto.BitwardenCrypto
import com.bastion.app.bitwarden.import.BitwardenJsonImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 回归测试：Bitwarden 加密导出（密码保护）的导入解密。
 *
 * 关键历史 bug：旧实现在 [BitwardenJsonImport.decryptAndParse] 中把
 * `encKeyValidation_DO_NOT_EDIT` 的解密结果拿去和字面量 "Bitwarden" 比较，
 * 但 Bitwarden 官方导出的该字段解密后是**导出时生成的随机 GUID**
 * （例如用户上报的真实导出文件解出 `bc0f364c-73b1-44fc-96cc-af43fd213bc4`），
 * 永远不等于 "Bitwarden"，于是**无论密码是否正确都报「密码不正确」**。
 *
 * 修复后，密码是否正确以 HMAC 校验是否通过为依据，不再做字面量比较。
 * 本测试用「随机 GUID 验证串」复现官方导出格式，确保该 bug 不再复发。
 *
 * 说明：本测试刻意放在不含 `bitwarden/` 目录段的包下（仓库 .gitignore 忽略了
 * `bitwarden/` 测试目录）。测试仅使用 javax/bouncycastle 纯 JVM 路径构造 type-2
 * 密文，不依赖 android.util.Base64，可在普通 JVM 单测中运行。
 */
class BitwardenImportValidationTest {

    private val random = SecureRandom()

    @Test
    fun officialExport_withGuidValidation_decryptsSuccessfully() {
        val password = "ay%1->Tt{=fH_PsI=w,MB&E;"
        val (salt, iterations) = makeSaltAndIterations()

        // 用与导入完全相同的派生方式计算密钥，并手工构造 type-2 密文。
        val masterKey = BitwardenCrypto.deriveMasterKeyPbkdf2(password, salt, iterations)
        val key = BitwardenCrypto.stretchMasterKey(masterKey)

        // 官方导出：validation 明文为随机 GUID，而非 "Bitwarden"
        val guid = "bc0f364c-73b1-44fc-96cc-af43fd213bc4"
        val plainJson = """{"encrypted":false,"folders":[],"items":[]}"""

        val validation = buildType2(guid, key)
        val data = buildType2(plainJson, key)

        val content = """
            {
              "encrypted": true,
              "passwordProtected": true,
              "salt": "$salt",
              "kdfType": 0,
              "kdfIterations": $iterations,
              "encKeyValidation_DO_NOT_EDIT": "$validation",
              "data": "$data"
            }
        """.trimIndent()

        val result = BitwardenJsonImport.decryptAndParse(content, password)
        assertEquals(0, result.folders.size)
        assertEquals(0, result.items.size)
    }

    @Test
    fun bastionOwnExport_withLiteralBitwardenValidation_stillDecrypts() {
        val password = "another-pass-123"
        val (salt, iterations) = makeSaltAndIterations()

        val masterKey = BitwardenCrypto.deriveMasterKeyPbkdf2(password, salt, iterations)
        val key = BitwardenCrypto.stretchMasterKey(masterKey)

        // Bastion 自导出：validation 明文为字面量 "Bitwarden"
        val validation = buildType2("Bitwarden", key)
        val data = buildType2("""{"encrypted":false,"folders":[],"items":[]}""", key)

        val content = """
            {
              "encrypted": true,
              "passwordProtected": true,
              "salt": "$salt",
              "kdfType": 0,
              "kdfIterations": $iterations,
              "encKeyValidation_DO_NOT_EDIT": "$validation",
              "data": "$data"
            }
        """.trimIndent()

        val result = BitwardenJsonImport.decryptAndParse(content, password)
        assertEquals(0, result.folders.size)
        assertEquals(0, result.items.size)
    }

    @Test
    fun wrongPassword_throwsSecurityException() {
        val password = "correct-password"
        val (salt, iterations) = makeSaltAndIterations()

        val masterKey = BitwardenCrypto.deriveMasterKeyPbkdf2(password, salt, iterations)
        val key = BitwardenCrypto.stretchMasterKey(masterKey)

        val validation = buildType2("some-guid", key)
        val data = buildType2("{}", key)

        val content = """
            {
              "encrypted": true,
              "passwordProtected": true,
              "salt": "$salt",
              "kdfType": 0,
              "kdfIterations": $iterations,
              "encKeyValidation_DO_NOT_EDIT": "$validation",
              "data": "$data"
            }
        """.trimIndent()

        assertThrows(SecurityException::class.java) {
            BitwardenJsonImport.decryptAndParse(content, "definitely-wrong-password")
        }
    }

    private fun makeSaltAndIterations(): Pair<String, Int> {
        val saltBytes = ByteArray(16).also { random.nextBytes(it) }
        // 测试用较小迭代次数以加快 CI；算法与迭代次数无关。
        return Base64.getEncoder().withoutPadding().encodeToString(saltBytes) to 1500
    }

    private fun buildType2(plaintext: String, key: BitwardenCrypto.SymmetricCryptoKey): String {
        val iv = ByteArray(16).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.encKey, "AES"), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.macKey, "HmacSHA256"))
        mac.update(iv)
        val macBytes = mac.doFinal(encrypted)

        return "2.${b64(iv)}|${b64(encrypted)}|${b64(macBytes)}"
    }

    private fun b64(bytes: ByteArray): String =
        Base64.getEncoder().withoutPadding().encodeToString(bytes)
}
