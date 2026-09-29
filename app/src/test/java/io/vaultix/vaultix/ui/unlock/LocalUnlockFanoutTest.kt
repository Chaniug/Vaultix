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
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.vaultix.domain.FingerprintDegradeReport
import io.vaultix.domain.RoomUnlockOutcome
import io.vaultix.domain.UnlockRecoveryRepository
import io.vaultix.domain.VaultRepository
import javax.crypto.Cipher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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

    // ---- 失效矩阵：门锁失败的分叉（定稿 §6，批次 4）----

    /**
     * 造一个失效善后假实现。
     *
     * @param keyInMemory 房钥匙是否还在内存 —— **唯一判据**，两路分叉由它决定。
     * @param roomsRemoved 降级时连带清掉的房间信封数。
     * @param remainingLocks 降级后是否还有别的门锁。
     */
    private fun recovery(
        keyInMemory: Boolean,
        roomsRemoved: Int = 0,
        remainingLocks: Boolean = false,
    ): UnlockRecoveryRepository = mockk {
        every { houseKeyInMemory } returns MutableStateFlow(keyInMemory)
        coEvery { markRearmPending() } just Runs
        coEvery { degradeFingerprintLock() } returns FingerprintDegradeReport(
            degraded = true,
            roomsRemoved = roomsRemoved,
            remainingLocks = remainingLocks,
        )
    }

    private fun failedRepository(): VaultRepository = mockk {
        coEvery { completeFingerprintUnlock(cipher) } returns false
    }

    @Test
    fun `开门态门锁失败_标记待重装而不降级`() = runTest {
        // ★ 本批最核心的分叉：房钥匙还在手上（PIN 开过门 / Never 档恢复过）⇒
        // **绝不许降级** —— 降级会连带清掉房间信封，用户每个库都得重新登记。
        val recovery = recovery(keyInMemory = true)
        val repository = failedRepository()

        val result = LocalUnlockFanout.unlockAll(
            repository = repository,
            first = "a",
            rest = listOf("b"),
            cipher = cipher,
            recovery = recovery,
        )

        coVerify(exactly = 1) { recovery.markRearmPending() }
        coVerify(exactly = 0) { recovery.degradeFingerprintLock() }
        // 文案必须说真话：钥匙还在 ⇒ 「需重新启用」，不是「已失效」（那会让人以为要重配）。
        val detail = (result.first as RoomUnlockOutcome.Unavailable).detail
        assertThat(detail).contains("重新启用")
        assertThat(detail).doesNotContain("已关闭")
    }

    @Test
    fun `关门态门锁失败_降级且如实说清已关闭`() = runTest {
        // 钥匙真的丢了 ⇒ 标记了也没人来兑现（补写要用内存房钥匙），只能降级。
        val recovery = recovery(keyInMemory = false)
        val repository = failedRepository()

        LocalUnlockFanout.unlockAll(
            repository = repository,
            first = "a",
            rest = listOf("b"),
            cipher = cipher,
            recovery = recovery,
        )

        coVerify(exactly = 1) { recovery.degradeFingerprintLock() }
        coVerify(exactly = 0) { recovery.markRearmPending() }
    }

    @Test
    fun `降级连带清掉房间信封时_文案要说重新启用`() = runTest {
        // ★ 硬约束 #5「绝不静默」：这是最后一把门锁 ⇒ 房间信封全清，
        // 用户下次进设置页会发现「库都不在快速解锁范围内」。
        // 若文案只说「请用主密码解锁」，用户会以为设置被重置了（看不到任何解释）。
        val recovery = recovery(keyInMemory = false, roomsRemoved = 3, remainingLocks = false)
        val repository = failedRepository()

        val result = LocalUnlockFanout.unlockAll(
            repository = repository,
            first = "a",
            rest = emptyList(),
            cipher = cipher,
            recovery = recovery,
        )

        val detail = (result.first as RoomUnlockOutcome.Unavailable).detail
        assertThat(detail).contains("已关闭")
        assertThat(detail).contains("重新启用")
    }

    @Test
    fun `降级但还有 PIN 锁时_提示改用 PIN`() = runTest {
        // 还有 PIN 锁 ⇒ 用户的库**没受影响**（房间信封被 trimRooms 保住）。
        // 提示语该指向那条真正可用的路（PIN），而不是让用户去输主密码。
        val recovery = recovery(keyInMemory = false, roomsRemoved = 0, remainingLocks = true)
        val repository = failedRepository()

        val result = LocalUnlockFanout.unlockAll(
            repository = repository,
            first = "a",
            rest = emptyList(),
            cipher = cipher,
            recovery = recovery,
        )

        val detail = (result.first as RoomUnlockOutcome.Unavailable).detail
        assertThat(detail).contains("PIN")
        assertThat(detail).doesNotContain("重新启用")
    }

    @Test
    fun `未接恢复依赖时_行为与批次三逐字相同`() = runTest {
        // ⚠️ 兼容性守卫：`AutofillActivity` 的自动填充路径不传 recovery，
        // 文案必须保持原样（那边没有界面呈现「需重新启用」，改了只会让日志对不上）。
        val result = LocalUnlockFanout.unlockAll(
            repository = failedRepository(),
            first = "a",
            rest = listOf("b"),
            cipher = cipher,
        )

        assertThat((result.first as RoomUnlockOutcome.Unavailable).detail)
            .isEqualTo("指纹门锁已失效，请用主密码解锁")
    }

    @Test
    fun `善后自身抛异常_不掩盖门锁失败这个主结论`() = runTest {
        // 善后是次要动作：它炸了也不能把「门锁解不开」这个主结论吞掉，
        // 更不能把一次认证失败升级成崩溃。
        val recovery = mockk<UnlockRecoveryRepository> {
            every { houseKeyInMemory } returns MutableStateFlow(false)
            coEvery { degradeFingerprintLock() } throws RuntimeException("db locked")
        }

        val result = LocalUnlockFanout.unlockAll(
            repository = failedRepository(),
            first = "a",
            rest = listOf("b"),
            cipher = cipher,
            recovery = recovery,
        )

        assertThat(result.first).isInstanceOf(RoomUnlockOutcome.Unavailable::class.java)
        assertThat((result.first as RoomUnlockOutcome.Unavailable).detail).isNotEmpty()
    }

    @Test
    fun `门锁成功时_完全不碰失效善后`() = runTest {
        // 正常路径不该有多余副作用：一次成功解锁不该留下任何「待重装」痕迹。
        val recovery = recovery(keyInMemory = true)

        LocalUnlockFanout.unlockAll(
            repository = repository(),
            first = "a",
            rest = listOf("b"),
            cipher = cipher,
            recovery = recovery,
        )

        coVerify(exactly = 0) { recovery.markRearmPending() }
        coVerify(exactly = 0) { recovery.degradeFingerprintLock() }
    }
}
