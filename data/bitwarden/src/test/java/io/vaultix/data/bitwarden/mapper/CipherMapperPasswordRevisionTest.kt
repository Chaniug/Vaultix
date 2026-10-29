/*
 * Vaultix — data:bitwarden (test)
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.data.bitwarden.mapper

import io.vaultix.crypto.SymmetricCryptoKey
import io.vaultix.crypto.VaultixCrypto
import io.vaultix.data.bitwarden.model.CipherDto
import io.vaultix.data.bitwarden.model.LoginDto
import io.vaultix.data.bitwarden.model.PasswordHistoryDto
import io.vaultix.data.bitwarden.time.BitwardenTimestamps
import io.vaultix.model.VaultItem
import io.vaultix.model.VaultItemType
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 改密码的两笔账：`passwordRevisionDate` + `passwordHistory`（`.ai/ISSUES.md` #209）。
 *
 * 这两笔账以前都只做「透传服务端原值」⇒ 用户在 Vaultix 里改多少次密码，服务端与其它
 * 客户端看到的都是建条目那天的状态，而**没有任何报错**。所以这里钉的是"改了必须留下痕迹、
 * 没改必须不动、解不开必须不造假"三条。
 */
// detekt MagicNumber 只放行属性声明里的字面量 ⇒ 规模相关的数字提到文件顶层。
// ⚠️ `const val` 必须是顶层或 object 成员 —— 写在普通 class 里是编译错误。
private const val HISTORY_CAP = 20
private const val SEED_COUNT = 25

class CipherMapperPasswordRevisionTest {

    private val crypto = VaultixCrypto(Dispatchers.Default)
    private val mapper = CipherMapper(crypto)
    private val accountKey = SymmetricCryptoKey.random()

    private fun stored(oldPassword: String, revision: String? = "2020-01-01T00:00:00.000Z"): CipherDto =
        CipherDto(
            id = "c1",
            type = 1,
            name = crypto.encryptString("条目", accountKey),
            login = LoginDto(
                username = crypto.encryptString("u", accountKey),
                password = crypto.encryptString(oldPassword, accountKey),
                passwordRevisionDate = revision?.let { crypto.encryptString(it, accountKey) },
            ),
        )

    private fun item(password: String, type: VaultItemType = VaultItemType.Login) = VaultItem(
        id = "c1",
        title = "条目",
        username = "u",
        password = password,
        type = type,
    )

    @Test
    fun changingPassword_updatesRevisionDateAndAppendsHistory() {
        val dto = stored("old-pass")
        val request = mapper.toUpdateRequest(item("new-pass"), dto, accountKey)

        // ① 修订日期：必须是**现在**，而不是 2020 年那个原值。
        val revisionCipher = request.login?.passwordRevisionDate
        assertNotNull("改了密码就必须写新的 passwordRevisionDate", revisionCipher)
        val revision = revisionCipher?.let { crypto.decryptToString(it, accountKey) }
        assertNotEquals("2020-01-01T00:00:00.000Z", revision)
        assertNotNull("新时间必须是可解析的 Bitwarden 时间串", BitwardenTimestamps.parse(revision))

        // ② 历史：追加一条，内容是**旧密码** + 旧密码的停用时间（= 旧的 revision）。
        assertEquals(1, request.passwordHistory.size)
        val entry = request.passwordHistory.first()
        assertEquals("历史里存的是旧密码", "old-pass", crypto.decryptToString(entry.password, accountKey))
        assertEquals(
            "lastUsedDate = 旧密码最后一次生效的时间",
            "2020-01-01T00:00:00.000Z",
            crypto.decryptToString(entry.lastUsedDate, accountKey),
        )
    }

    @Test
    fun keepingPassword_leavesBothUntouched() {
        val dto = stored("same-pass")
        val request = mapper.toUpdateRequest(item("same-pass"), dto, accountKey)

        assertEquals("没改密码就不该动修订日期", dto.login?.passwordRevisionDate, request.login?.passwordRevisionDate)
        assertTrue("没改密码就不该加历史", request.passwordHistory.isEmpty())
    }

    @Test
    fun clearingPassword_leavesBothUntouched() {
        // 空密码按"不写"处理（Client 侧表单清空密码是个有歧义的动作，交给人决定）。
        val dto = stored("old-pass")
        val request = mapper.toUpdateRequest(item(""), dto, accountKey)

        assertEquals(dto.login?.passwordRevisionDate, request.login?.passwordRevisionDate)
        assertTrue(request.passwordHistory.isEmpty())
    }

    @Test
    fun nonLoginType_leavesBothUntouched() {
        val dto = stored("old-pass")
        val request = mapper.toUpdateRequest(item("new-pass", VaultItemType.SecureNote), dto, accountKey)

        assertEquals(dto.login?.passwordRevisionDate, request.login?.passwordRevisionDate)
        assertTrue(request.passwordHistory.isEmpty())
    }

    @Test
    fun undecryptableOldPassword_stillUpdatesRevisionButSkipsHistory() {
        // ★ 保守边界：旧密文解不开 ⇒ 拿不到"旧密码是什么" ⇒ **不往历史里塞一条空记录**
        //   （那是一条假数据，用户会以为自己某天用过空密码）。修订日期照写：
        //   "此刻重写了密码"这个事实是成立的。
        val dto = CipherDto(
            id = "c1",
            type = 1,
            name = crypto.encryptString("条目", accountKey),
            login = LoginDto(password = "2.not-a-real-cipher||broken"),
        )
        val request = mapper.toUpdateRequest(item("new-pass"), dto, accountKey)

        assertNotNull(request.login?.passwordRevisionDate)
        assertTrue("旧密码解不开时不得追加历史", request.passwordHistory.isEmpty())
    }

    @Test
    fun historyIsCappedToTheMostRecentEntries() {
        // 服务端不设上限，但每次保存都要整坨上传 ⇒ 保留最近 20 条。
        val dto = stored("old-pass").copy(
            passwordHistory = (1..SEED_COUNT).map { i ->
                PasswordHistoryDto(
                    password = crypto.encryptString("p$i", accountKey),
                    lastUsedDate = crypto.encryptString("2020-01-01T00:00:00.000Z", accountKey),
                )
            },
        )
        val request = mapper.toUpdateRequest(item("new-pass"), dto, accountKey)

        assertEquals(HISTORY_CAP, request.passwordHistory.size)
        // 保留的是**最近**的：新追加的那条必须在末尾。
        val last = request.passwordHistory.last().password
        assertEquals("末尾应是刚退役的 old-pass", "old-pass", crypto.decryptToString(last, accountKey))
    }
}
