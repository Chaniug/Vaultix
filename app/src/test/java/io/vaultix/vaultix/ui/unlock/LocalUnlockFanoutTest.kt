/*
 * Vaultix — app:ui · unlock (test)
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.unlock

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.vaultix.domain.RoomUnlockOutcome
import io.vaultix.domain.VaultRepository
import javax.crypto.Cipher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * 「一次认证 → 打开多个库」的扇出语义门禁（房子化批次 2 新增，单测第四类）。
 *
 * ## 这批用例钉死的是**扇出形状**，不是某次开库成功与否
 *
 * 房子化（定稿 2026-09-28）把扇出从
 *
 * ```
 * 旧：1 次 Keystore（首库） + N-1 次 Keystore（其余库各自现取新 cipher）
 * 新：1 次 Keystore（解门锁，一次授权一次使用） + N 次纯软件解密（房间信封）
 * ```
 *
 * 换了骨架。旧形状里那 N-1 次正是 **H2 病灶**：auth-per-use 的 KEK 下，
 * 那些现取的 cipher **没有任何人授权过**，指纹一变更就全部静默失效
 * （表现为"指纹过了却又让人输主密码"，且只影响部分库）。
 *
 * ⇒ 断言的**重点不是"开了几个库"，而是"碰了几次 Keystore"**：
 * `completeFingerprintUnlock` **恰好 1 次**，且不随库数增长。
 * 只要哪天有人为了省事给某个库单独取一把新 cipher，这条就立刻红。
 *
 * ## 为什么要分开断言首库与其余库
 *
 * 首库的结论是**用户点指纹的目的**（"我这个库开了没有"），其余库是附带收益。
 * 混成一个数字就没法回答"该不该报错"（"3 个里成了 2 个"到底是成功还是失败？）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalUnlockFanoutTest {

    private val cipher: Cipher = mockk()

    /**
     * 装一个假的 repository，并记录每个库被开了几次。
     *
     * @param lockOpened 门锁解封是否成功（`false` = KEK 失效 / 信封损坏）。
     * @param roomCalls 逐库调用计数（**断言 Keystore 次数之外的另一半证据**）。
     * @param outcomeOf 逐库结论（要测"某个库坏了"时在这里返回非 Opened）。
     *   放在最后是为了让调用方能写成尾随 lambda（`repository { id -> ... }`）。
     */
    private fun repository(
        lockOpened: Boolean = true,
        roomCalls: MutableList<String> = mutableListOf(),
        outcomeOf: (String) -> RoomUnlockOutcome = { RoomUnlockOutcome.Opened },
    ): VaultRepository = mockk {
        coEvery { completeFingerprintUnlock(cipher) } returns lockOpened
        coEvery { unlockVaultFromRoom(any()) } answers { call ->
            val id = call.invocation.args.first() as String
            roomCalls += id
            outcomeOf(id)
        }
    }

    // ---- 扇出形状（本文件的核心）----

    @Test
    fun `扇出恰好一次 Keystore 且不随库数增长`() = runTest {
        val roomCalls = mutableListOf<String>()
        val repository = repository(roomCalls = roomCalls)

        val result = LocalUnlockFanout.unlockAll(
            repository = repository,
            first = "a",
            rest = listOf("b", "c", "d"),
            cipher = cipher,
        )

        // ★ 硬约束 #2：一次生物识别授权 = 一次 Keystore 操作。
        coVerify(exactly = 1) { repository.completeFingerprintUnlock(cipher) }
        // 4 个库 = 4 次软件解密，且**全都**走房间信封，没有第二把 cipher。
        assertThat(roomCalls).containsExactly("a", "b", "c", "d").inOrder()
        assertThat(result.restOpened).isEqualTo(3)
        assertThat(result.restFailed).isEqualTo(0)
    }

    @Test
    fun `库数增加时 Keystore 次数仍是 1`() = runTest {
        val repository = repository()

        LocalUnlockFanout.unlockAll(
            repository = repository,
            first = "a",
            rest = List(9) { "rest-$it" },
            cipher = cipher,
        )

        // 10 个库也只碰一次 Keystore —— 旧形状下这里会是 10 次（H2）。
        coVerify(exactly = 1) { repository.completeFingerprintUnlock(cipher) }
        coVerify(exactly = 10) { repository.unlockVaultFromRoom(any()) }
    }

    @Test
    fun `只有一个库时也是一次 Keystore 加一次软件解密`() = runTest {
        val repository = repository()

        val result = LocalUnlockFanout.unlockAll(
            repository = repository,
            first = "a",
            rest = emptyList(),
            cipher = cipher,
        )

        coVerify(exactly = 1) { repository.completeFingerprintUnlock(cipher) }
        coVerify(exactly = 1) { repository.unlockVaultFromRoom("a") }
        assertThat(result.first).isEqualTo(RoomUnlockOutcome.Opened)
        assertThat(result.restOpened).isEqualTo(0)
    }

    // ---- 门锁解不开：早退，一次软件解密都不跑 ----

    @Test
    fun `门锁解封失败时不再碰任何房间信封`() = runTest {
        val roomCalls = mutableListOf<String>()
        val repository = repository(lockOpened = false, roomCalls = roomCalls)

        val result = LocalUnlockFanout.unlockAll(
            repository = repository,
            first = "a",
            rest = listOf("b", "c"),
            cipher = cipher,
        )

        // 没钥匙 ⇒ 各库全是"打不开"，跑循环纯属白跑（且会在日志里演出 N 条假失败）。
        assertThat(roomCalls).isEmpty()
        assertThat(result.restOpened).isEqualTo(0)
        // 如实降级（定稿 §6）：明确告诉用户回退主密码，而不是静默重试。
        assertThat(result.first).isInstanceOf(RoomUnlockOutcome.Unavailable::class.java)
        assertThat((result.first as RoomUnlockOutcome.Unavailable).detail).contains("主密码")
    }

    @Test
    fun `门锁解封抛异常时不崩溃且如实降级`() = runTest {
        val repository = mockk<VaultRepository> {
            // 真实场景：KEK 被系统作废时 `doFinal` 会抛 UserNotAuthenticatedException。
            coEvery { completeFingerprintUnlock(cipher) } throws RuntimeException("KEK invalidated")
        }

        val result = LocalUnlockFanout.unlockAll(
            repository = repository,
            first = "a",
            rest = listOf("b"),
            cipher = cipher,
        )

        assertThat(result.first).isInstanceOf(RoomUnlockOutcome.Unavailable::class.java)
        coVerify(exactly = 0) { repository.unlockVaultFromRoom(any()) }
    }

    // ---- 逐库独立成败 ----

    @Test
    fun `某个库房间信封坏了不影响其它库`() = runTest {
        val repository = repository { id ->
            when (id) {
                "b" -> RoomUnlockOutcome.Unavailable("房间信封损坏")
                "c" -> RoomUnlockOutcome.StaleCredentials
                else -> RoomUnlockOutcome.Opened
            }
        }

        val result = LocalUnlockFanout.unlockAll(
            repository = repository,
            first = "a",
            rest = listOf("b", "c", "d"),
            cipher = cipher,
        )

        assertThat(result.first).isEqualTo(RoomUnlockOutcome.Opened)
        // 3 个其余库里只有 d 成了：坏的两个如实计入 failed，没有牵连 d。
        assertThat(result.restOpened).isEqualTo(1)
        assertThat(result.restFailed).isEqualTo(2)
        // 坏掉的库**不会**让扇出提前终止（d 仍然被开了一次）。
        coVerify(exactly = 1) { repository.unlockVaultFromRoom("d") }
    }

    @Test
    fun `首库失败也照常返回其余库的结论`() = runTest {
        val repository = repository { id ->
            if (id == "a") RoomUnlockOutcome.StaleCredentials else RoomUnlockOutcome.Opened
        }

        val result = LocalUnlockFanout.unlockAll(
            repository = repository,
            first = "a",
            rest = listOf("b", "c"),
            cipher = cipher,
        )

        // 首库是"用户点指纹的目的"，它的结论**单独**返回（不与其余库混成一个数字）。
        assertThat(result.first).isEqualTo(RoomUnlockOutcome.StaleCredentials)
        assertThat(result.restOpened).isEqualTo(2)
    }

    @Test
    fun `单个库开房抛异常时算进失败而不中止扇出`() = runTest {
        val repository = mockk<VaultRepository> {
            coEvery { completeFingerprintUnlock(cipher) } returns true
            coEvery { unlockVaultFromRoom("a") } throws RuntimeException("boom")
            coEvery { unlockVaultFromRoom("b") } returns RoomUnlockOutcome.Opened
        }

        val result = LocalUnlockFanout.unlockAll(
            repository = repository,
            first = "a",
            rest = listOf("b"),
            cipher = cipher,
        )

        assertThat(result.first).isInstanceOf(RoomUnlockOutcome.Unavailable::class.java)
        assertThat(result.restOpened).isEqualTo(1)
        assertThat(result.restFailed).isEqualTo(0)
    }
}
