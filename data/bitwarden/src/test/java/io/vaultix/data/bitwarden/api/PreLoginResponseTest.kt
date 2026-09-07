package io.vaultix.data.bitwarden.api

import io.vaultix.data.bitwarden.network.BitwardenJson
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * prelogin 响应双形态兼容（2026-09-08 真机踩坑回归）：
 * - 官方 Bitwarden：PascalCase（Kdf / KdfIterations / …）
 * - Vaultwarden ≥1.33：camelCase（kdf / kdfIterations / …）
 * - 缺失全部形态 → PreLoginFieldsMissingException（可读错误，而非序列化裸错）
 */
class PreLoginResponseTest {

    @Test
    fun decode_officialBitwardenPascalCase() {
        val json = """{"Kdf":0,"KdfIterations":600000,"KdfMemory":null,"KdfParallelism":null}"""

        val pre = BitwardenJson.decodeFromString<PreLoginResponse>(json)

        assertEquals(0, pre.resolvedKdf())
        assertEquals(600_000, pre.resolvedIterations())
        assertNull(pre.resolvedMemoryMb())
        assertNull(pre.resolvedParallelism())
    }

    @Test
    fun decode_vaultwardenCamelCase() {
        // 真机抓包（Vaultwarden 新版实际响应，含冗余的 kdfSettings/KdfSettings 双形态）
        val json = """{"kdf":0,"kdfIterations":600000,"kdfMemory":null,"kdfParallelism":null,""" +
            """"salt":null,"KdfSettings":{"KdfType":0,"Iterations":600000}}"""

        val pre = BitwardenJson.decodeFromString<PreLoginResponse>(json)

        assertEquals(0, pre.resolvedKdf())
        assertEquals(600_000, pre.resolvedIterations())
    }

    @Test
    fun decode_pascalTakesPrecedence_whenBothShapesPresent() {
        val json = """{"Kdf":1,"KdfIterations":3,"kdf":0,"kdfIterations":999999}"""

        val pre = BitwardenJson.decodeFromString<PreLoginResponse>(json)

        assertEquals(1, pre.resolvedKdf())
        assertEquals(3, pre.resolvedIterations())
    }

    @Test
    fun decode_missingBothShapes_throwsReadableException() {
        val json = """{"unrelated":"payload"}"""

        val pre = BitwardenJson.decodeFromString<PreLoginResponse>(json)

        val error = assertThrows(PreLoginFieldsMissingException::class.java) { pre.resolvedKdf() }
        // 消息对用户可读（会以「出错了：…」形式展示），不得是 kotlinx 裸错
        assertEquals(true, error.message?.contains("Bitwarden 兼容服务端") == true)
    }
}
