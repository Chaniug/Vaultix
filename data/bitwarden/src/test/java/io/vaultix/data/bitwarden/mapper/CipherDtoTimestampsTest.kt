/*
 * Vaultix — data:bitwarden（单测）
 * Copyright (C) 2026 Vaultix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.data.bitwarden.mapper

import io.vaultix.crypto.SymmetricCryptoKey
import io.vaultix.crypto.VaultixCrypto
import io.vaultix.data.bitwarden.model.CipherDto
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 条目**时间字段**（创建 / 最近修改）在 Bitwarden 侧的接入（2026-09-30 用户要求）。
 *
 * ## 这条为什么值得单独钉
 *
 * 用户原话：「这个好像 bitwarden 上库里面有显示的吧，只是我们没有接入而已吧」——
 * **判断是对的**：服务端一直在返回 `creationDate` / `revisionDate`，
 * 而我们的 `CipherDto` 此前**根本没声明 `creationDate`** ⇒ 被
 * kotlinx.serialization 的 `ignoreUnknownKeys` **静默丢掉**（不报错、不留痕）。
 *
 * 这类「服务端有、DTO 没声明」的丢失是最难自查的一类：
 * 没有异常、没有日志，只有"界面上少了个东西"。⇒ 两条断言分别守住
 * **字段名对不对**（JSON 反序列化）与 **有没有真的映射进领域模型**（`toDomain`）。
 */
class CipherDtoTimestampsTest {

    private val crypto = VaultixCrypto(Dispatchers.Default)
    private val mapper = CipherMapper(crypto)
    private val accountKey = SymmetricCryptoKey.random()

    @Test
    fun `CipherDto 能从服务端 JSON 里读出 creationDate 与 revisionDate`() {
        // 这条专门守"字段名 / 声明"：`creationDate` 曾经没声明 ⇒ 反序列化时就没了。
        val json = Json { ignoreUnknownKeys = true }

        val dto = json.decodeFromString<CipherDto>(
            """
            {
              "id": "c1",
              "type": 1,
              "creationDate": "2026-03-04T05:06:07.000Z",
              "revisionDate": "2026-09-30T12:34:56.789Z"
            }
            """.trimIndent(),
        )

        assertEquals("2026-03-04T05:06:07.000Z", dto.creationDate)
        assertEquals("2026-09-30T12:34:56.789Z", dto.revisionDate)
    }

    @Test
    fun `toDomain 把两个时间映射成 epoch 毫秒`() {
        val dto = CipherDto(
            id = "c1",
            creationDate = "2026-03-04T05:06:07.000Z",
            revisionDate = "2026-09-30T12:34:56.789Z",
        )

        val item = mapper.toDomain(dto, accountKey)

        assertEquals(1_772_600_767_000L, item.createdAt)
        assertEquals(1_790_771_696_789L, item.updatedAt)
    }

    @Test
    fun `时间解析不了时是 null（不兜默认值）`() {
        // ★ 兜"现在"或 1970 会显示一个**看起来合理但完全错误**的时间，
        //   用户会据此判断数据新旧 ⇒ 宁可这一行不显示。
        val dto = CipherDto(
            id = "c1",
            creationDate = "不是一个时间",
            revisionDate = "",
        )

        val item = mapper.toDomain(dto, accountKey)

        assertNull(item.createdAt)
        assertNull(item.updatedAt)
    }
}
