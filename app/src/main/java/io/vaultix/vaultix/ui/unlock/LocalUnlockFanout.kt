/*
 * Vaultix — app:ui · unlock
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.unlock

import io.vaultix.domain.RoomUnlockOutcome
import io.vaultix.domain.UnlockRecoveryRepository
import io.vaultix.domain.VaultRepository
import io.vaultix.vaultix.autofill.AutofillLogger
import javax.crypto.Cipher
import kotlinx.coroutines.flow.first

/**
 * 「一次认证 → 打开多个库」的**唯一实现**（2026-09-16 新增；2026-09-29 房子化重写）。
 *
 * ## 为什么要有这个文件
 *
 * 在此之前，同一条逻辑在**两处**各写了一份：[UnlockViewModel.completeLocalUnlock]
 * 与 `AutofillActivity.completeLocalUnlockByKind`。两份都要处理「按库类型分流」，
 * 而这条分流一旦写错**不会报错、只会静默失效** —— KDBX 的包裹物走 Bitwarden
 * 那条路会解出完全错误的语义。两处各写一遍等于把同一个坑埋了两次，
 * 且很可能只修好一处。现在两处都调这里，坑只存在一处。
 *
 * ## 房子化后的形态（两级钥匙，定稿 2026-09-28）
 *
 * 旧版是「首个库用认证 cipher、其余库各自现取新 cipher」—— 后者正是 H2 病灶：
 * auth-per-use 的 KEK 下，那些新 cipher **没有任何人授权过**，指纹变更后全部失效。
 *
 * 新版里「按库类型分流」和「首库特殊化」都消失了：
 *
 * 1. **一次 Keystore 操作**：`completeFingerprintUnlock(cipher)` 用本次认证的
 *    cipher 解指纹门锁，房钥匙进内存（解锁路径上唯一碰 Keystore 的动作）；
 * 2. **N 次纯软件解密**：目标库与其余库一律 `unlockVaultFromRoom(id)` ——
 *    房间信封的 AES-GCM 解密 + 按库类型开库，全部封装在 repository 一侧。
 *
 * 库类型分流下沉到了 `VaultRepositoryImpl.unlockVaultFromRoom` 内部（按 vault 行
 * 的 kind 分流），本层不再感知 ——「一处实现」的保证从「共享这个 object」
 * 进一步收紧为「共享 repository 的同一方法」。
 */
internal object LocalUnlockFanout {

    /**
     * 「门锁刚开、顺手补建房间信封」这个动作的可注入形态（#243）。
     *
     * ⚠️ 用**函数式接口**而不是直接调仓库：本类的单测全靠假仓库（`LocalUnlockFanoutTest`），
     * 加一个 `suspend` 方法到 `VaultRepository` 会让那些假实现全部要补桩；
     * 而这件事与「开库」正交，独立成一个接缝更清楚。
     */
    fun interface RoomEnroller {

        /**
         * @param vaultId 用户点的那一个库。
         * @param outcome 本次开库的结论 —— 只有「信封不在 / 坏了」才值得补建
         *   （`Opened` 说明信封本来就在；`StaleCredentials` 是另一条自愈路径）。
         * @return true = 本次真的建好了信封（调用方据此**重试一次**开库）。
         */
        suspend fun enrollIfNeeded(vaultId: String, outcome: RoomUnlockOutcome): Boolean

        companion object {
            /** 不修（默认值）：行为与加本接缝之前逐字相同。 */
            val None: RoomEnroller = RoomEnroller { _, _ -> false }
        }
    }

    /** 一次 fanout 的结论。 */
    data class Result(
        /**
         * 首个库（用户点指纹要开的那个）的**最终**结论。
         *
         * ⚠️ #243 之后它可能是「补建房间信封 → 重试」的结果，而不是第一次
         * `unlockVaultFromRoom` 的结论。调用方只看这个即可。
         */
        val first: RoomUnlockOutcome,
        /** 其余库里成功打开的数量。 */
        val restOpened: Int,
        /** 其余库里未能打开的数量。 */
        val restFailed: Int,
        /**
         * 门锁是否解开了（房钥匙是否进了内存）。
         *
         * 为什么值得单独返回（2026-09-29 提速拆分引入）：调用方现在把「核心库」
         * 与「其余库」拆成两段执行 —— 第一段跑完后要判断**房钥匙在不在内存**，
         * 才能决定要不要接着补开其余库（没钥匙的话 `unlockVaultFromRoom` 必然全败，
         * 白跑一轮）。旧实现里这个信息隐含在 `first !is Unavailable` 中，拆分后
         * 必须显式化：首库可能是因为**房间信封坏**而 `Unavailable`，
         * 那时门锁其实是开着的，其余库照样能开。
         */
        val lockOpened: Boolean,
    )

    /**
     * 解指纹门锁，随后逐库开房间信封。
     *
     * ## 为什么首个库的结论要单独返回
     *
     * 调用方（解锁页）的核心诉求是"我这个库到底开了没有" —— 那是用户点指纹的目的。
     * 其余库是附带收益。若把成败混成一个数字，首个库失败时调用方就不知道
     * 该不该报错（"3 个里成了 2 个"到底是成功还是失败？）。
     * 分开返回，语义就没歧义：**首个成功 = 用户的目的达成**。
     *
     * ## 门锁失败时的分叉（失效矩阵，定稿 §6；批次 4）
     *
     * 指纹门锁解不开有两种**完全不同**的原因，混为一谈会犯两类错误：
     *
     * | 局面 | 判据 | 动作 |
     * |---|---|---|
     * | **开门态**：信封解不开但房钥匙还在手上 | `recovery.houseKeyInMemory` | [UnlockRecoveryRepository.markRearmPending]（下次认证补写新信封） |
     * | **关门态**：钥匙也丢了 | 同上取反 | [UnlockRecoveryRepository.degradeFingerprintLock]（禁用该锁 + 回主密码） |
     *
     * ⚠️ **为什么不能一律降级**：KEK 因「用户重录指纹」而失效时，房钥匙可能仍在内存
     * （PIN 开过门 / Never 档自动恢复过）。此时降级会把一把**本来能修好的**锁砸掉，
     * 且 `trimRoomsIfNoLocksRemain` 会连带清空全部房间信封 —— 用户每个库都要重新登记。
     *
     * ⚠️ **为什么不能一律 rearm**：钥匙真的不在内存时，标记了也没人能来兑现
     * （补写要用内存里的房钥匙），用户会一直看到「需重新启用」却永远好不了。
     *
     * ## 为什么判据是「钥匙在不在」而不是「信封在不在」
     *
     * 「信封在」回答的是**用户开没开锁**，不是**钥匙在不在手上**。用错会把
     * 开门态误判成关门态 —— 那正是上面说的「砸掉能修好的锁」。
     *
     * @param cipher 本次 BiometricPrompt 认证返回的 cipher —— 只用于解**门锁**
     *   （一次 Keystore 操作），各库房间信封与它无关。
     * @param recovery 失效善后（可空 = 不接失效矩阵，行为与批次 3 完全一致）。
     *   留成可空是为了让「门锁解不开」的既有调用点（如 `AutofillActivity` 的
     *   自动填充路径）不必为了这一个分支而多注入一个依赖 —— 那些路径本来就不该
     *   做 rearm/降级（没有 UI 可以呈现「需重新启用」），降级由用户主动解锁时触发。
     */
    suspend fun unlockAll(
        repository: VaultRepository,
        first: String,
        rest: List<String>,
        cipher: Cipher,
        recovery: UnlockRecoveryRepository? = null,
        /**
         * ★ 门锁刚开、但 [first] 不在快速解锁范围内时，补建它的房间信封（#243）。
         *
         * 空实现 = 不修（行为与加本参数之前逐字相同）。生产调用点传的是
         * `repository.enrollVaultAfterFingerprintUnlock(...)` —— 见 `UnlockViewModel`
         * 的 `roomEnroller`。
         */
        roomEnroller: RoomEnroller = RoomEnroller.None,
    ): Result {
        // ★ 诊断埋点（2026-09-17 补、房子化后保留）：排「指纹过了却又让人解锁」
        //   必须能量化到"哪个库、哪种结论"，否则只能猜。
        AutofillLogger.d("fanout start first=$first rest=${rest.size}")
        // 门锁解不开（KEK 失效 / 信封损坏）：没钥匙，各库全是 NoKey —— 早退，
        // 不白跑循环。首库给主密码回退提示（定稿 §6：不静默重试，如实降级）。
        val lockOpened = runCatching { repository.completeFingerprintUnlock(cipher) }
            .getOrElse { false }
        if (!lockOpened) {
            val failure = handleLockFailure(recovery)
            AutofillLogger.d("fanout lock → 门锁解封失败（${failure.name}）")
            return Result(
                first = RoomUnlockOutcome.Unavailable(failure.name),
                restOpened = 0,
                restFailed = rest.size,
                lockOpened = false,
            )
        }
        // 目标库与其余库地位完全相同（首库特殊化已随 H2 一起消失）。
        val firstResult = runCatching { repository.unlockVaultFromRoom(first) }
            .getOrElse { RoomUnlockOutcome.Unavailable(it.message ?: OPEN_FAILED) }
        AutofillLogger.d("fanout first=$first → ${describe(firstResult)}")
        // ★ #243：门锁已经开了（房钥匙在内存），此刻补建房间信封是**纯软件**的。
        //
        //   为什么必须在这里、而不是"等用户下次进设置页"：`NotEnrolled` 说明这个库
        //   压根没有房间信封 —— 而用户刚刚通过了一次认证，这正是补它最便宜的时刻
        //   （不需要第二次认证、主密码由调用方按库提供）。不补的后果是解锁页永远
        //   不显示指纹入口（用户 2026-11-04 报障的原文）。
        //
        //   ⚠️ 只补 [first]：其余库本来就不在范围内，补它们等于替用户扩大范围。
        //   ★ 补建成功后**原地重试一次**：信封里的凭据就是刚才那次认证对应的
        //     主密码（KDBX）/ 会话密钥（Bitwarden），它必然能开库 —— 不重试的话
        //     用户会看到「已纳入快速解锁」却仍然开不了库，下一次还得再输密码
        //     （那正是 #243 的第二个症状）。
        // ⚠️ 只在「信封不在 / 坏了」时才叫补建 —— `Opened` 说明本来就没事，
        //   `StaleCredentials` 是另一条自愈路径（重包，见 `RoomResealRepository`）。
        //   漏了这道判断会让每次成功解锁都白跑一次备料（KDBX 还多一次 Argon2）。
        val needsEnroll = firstResult is RoomUnlockOutcome.Unavailable
        val enrolledNow = needsEnroll &&
            runCatching { roomEnroller.enrollIfNeeded(first, firstResult) }.getOrDefault(false)
        val finalFirstResult = if (enrolledNow) retryAfterEnroll(repository, first) else firstResult
        var opened = 0
        var failed = 0
        for (id in rest) {
            val outcome = runCatching { repository.unlockVaultFromRoom(id) }
                .getOrElse { RoomUnlockOutcome.Unavailable(it.message ?: OPEN_FAILED) }
            if (outcome is RoomUnlockOutcome.Opened) opened++ else failed++
            AutofillLogger.d("fanout rest=$id → ${describe(outcome)}")
        }
        AutofillLogger.d(
            "fanout done first=${describe(finalFirstResult)} opened=$opened failed=$failed",
        )
        return Result(
            first = finalFirstResult,
            restOpened = opened,
            restFailed = failed,
            lockOpened = true,
        )
    }

    /**
     * 房间信封刚补建好之后，**立刻**再开一次 [first]（#243）。
     *
     * 补建时用的凭据就是本次认证已经验过的那一份（`prepareKdbx` 里 `Kdbx.verify`
     * 过、或会话密钥本来就在内存），所以这次重试**不会**再要一次密码、也不该失败；
     * 真失败就如实返回那次的新结论（不硬编 `Opened`）。
     *
     * @return 重试后的结论 —— 调用方应以它为准（而不是补建前那个 `NotEnrolled`）。
     */
    private suspend fun retryAfterEnroll(
        repository: VaultRepository,
        vaultId: String,
    ): RoomUnlockOutcome {
        val retried = runCatching { repository.unlockVaultFromRoom(vaultId) }
            .getOrElse { RoomUnlockOutcome.Unavailable(it.message ?: OPEN_FAILED) }
        AutofillLogger.d("fanout first=$vaultId → 补建信封后重试 ${describe(retried)}")
        return retried
    }

    /**
     * 只补开「其余库」（核心库已开、事件已放行之后才调）。
     *
     * 单独抽出来的理由见调用方（`UnlockViewModel.completeLocalUnlock`）的 KDoc：
     * 其余库是附加收益，不该挡住用户进他点的那一个库。
     *
     * @param houseKeyInMemory 房钥匙是否已在内存。false 时直接返回 ——
     *   没有钥匙，`unlockVaultFromRoom` 必然全败，白跑一轮还刷一堆日志。
     * @return 成功打开的数量（调用方通常不关心，留作诊断与测试断言）。
     */
    suspend fun openRest(
        repository: VaultRepository,
        rest: List<String>,
        houseKeyInMemory: Boolean,
    ): Int {
        if (rest.isEmpty() || !houseKeyInMemory) {
            AutofillLogger.d("fanout rest → 跳过（rest=${rest.size} key=$houseKeyInMemory）")
            return 0
        }
        var opened = 0
        for (id in rest) {
            val outcome = runCatching { repository.unlockVaultFromRoom(id) }
                .getOrElse { RoomUnlockOutcome.Unavailable(it.message ?: OPEN_FAILED) }
            if (outcome is RoomUnlockOutcome.Opened) opened++
            AutofillLogger.d("fanout rest=$id → ${describe(outcome)}")
        }
        AutofillLogger.d("fanout rest done opened=$opened failed=${rest.size - opened}")
        return opened
    }

    /**
     * 门锁解封失败时的失效善后分叉，返回**给用户看的原因**。
     *
     * 三种局面（见 [unlockAll] 的 KDoc 表）：
     *
     * 1. **无 [recovery] 接入** ⇒ 不给原因之外的承诺，用批次 3 的原文案
     *    （「指纹门锁已失效，请用主密码解锁」）—— 行为与加本批之前逐字相同。
     * 2. **开门态**（房钥匙还在）⇒ 标记待重装，文案说「需重新启用」而不是
     *    「已失效」：前者是真话（下次认证就修好了），后者会让用户以为要重配。
     * 3. **关门态** ⇒ 降级（禁用该锁），文案说「已关闭」+ 回退主密码 ——
     *    **绝不静默**（硬约束 #5）。若连带清了房间信封，文案要提「需要重新启用
     *    快速解锁」而不是只说「用主密码解锁」（否则用户下次进设置页会莫名发现
     *    库都不在范围内，以为是 bug）。
     *
     * ⚠️ 全部动作都包在 `runCatching` 里：善后失败不能掩盖「门锁解不开」这个
     * 主结论，更不能把一次认证失败升级成崩溃。
     */
    private suspend fun handleLockFailure(recovery: UnlockRecoveryRepository?): LocalUnlockMessage {
        if (recovery == null) return LocalUnlockMessage.LockFailed
        return runCatching {
            // 判据唯一 = 房钥匙在不在内存（不是「信封在不在」—— 见 unlockAll 的 KDoc）。
            val keyInMemory = recovery.houseKeyInMemory.first()
            if (keyInMemory) {
                recovery.markRearmPending()
                LocalUnlockMessage.RearmPending
            } else {
                val report = recovery.degradeFingerprintLock()
                if (report.roomsRemoved > 0) {
                    LocalUnlockMessage.DegradedRoomsRemoved
                } else if (report.remainingLocks) {
                    LocalUnlockMessage.DegradedPinRemains
                } else {
                    LocalUnlockMessage.Degraded
                }
            }
        }.getOrElse {
            AutofillLogger.d("fanout lock → 失效善后失败：${it.message}")
            LocalUnlockMessage.LockFailed
        }
    }

    /**
     * [RoomUnlockOutcome] 的可读描述（**不含任何密钥材料**）。
     *
     * `Unavailable` 要带上 detail：「主密码已过时」与「文件读不到」都落在这一支，
     * 而那两者的用户动作完全不同。
     */
    private fun describe(outcome: RoomUnlockOutcome): String = when (outcome) {
        RoomUnlockOutcome.Opened -> "Opened"
        RoomUnlockOutcome.StaleCredentials -> "StaleCredentials"
        is RoomUnlockOutcome.Unavailable -> "Unavailable(${outcome.detail})"
    }

    // ---- 门锁失败的用户可见文案（定稿 §6：绝不静默）----
    //
    // ★ 2026-10-21 §3.4：这里原本有 **5 个中文字面量常量**（`LOCK_FAILED_FALLBACK` /
    //   `REARM_PENDING` / `DEGRADED_*`），注释写着"与 UnlockViewModel 那处措辞必须一致，
    //   靠 review 守"。那就是靠人守的双源 —— 两处都在 Kotlin 里、都不在 strings.xml，
    //   既翻不了，孤儿串检查也看不见（它只扫 strings.xml）。
    //   ⇒ 现在统一由 [LocalUnlockMessage] 承载（只带资源 id），本 object 只**产出枚举**，
    //   解析交给有 `Context` 的调用方（`UnlockViewModel` / `AutofillActivity`）。
    //   ⚠️ 别再把中文写回本文件：本 object 拿不到资源不是"写死中文"的理由。

    private const val OPEN_FAILED = "open_failed"
}
