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

    /** 一次 fanout 的结论。 */
    data class Result(
        /** 首个库（用户点指纹要开的那个）的结论。 */
        val first: RoomUnlockOutcome,
        /** 其余库里成功打开的数量。 */
        val restOpened: Int,
        /** 其余库里未能打开的数量。 */
        val restFailed: Int,
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
    ): Result {
        // ★ 诊断埋点（2026-09-17 补、房子化后保留）：排「指纹过了却又让人解锁」
        //   必须能量化到"哪个库、哪种结论"，否则只能猜。
        AutofillLogger.d("fanout start first=$first rest=${rest.size}")
        // 门锁解不开（KEK 失效 / 信封损坏）：没钥匙，各库全是 NoKey —— 早退，
        // 不白跑循环。首库给主密码回退提示（定稿 §6：不静默重试，如实降级）。
        val lockOpened = runCatching { repository.completeFingerprintUnlock(cipher) }
            .getOrElse { false }
        if (!lockOpened) {
            val detail = handleLockFailure(recovery)
            AutofillLogger.d("fanout lock → 门锁解封失败（$detail）")
            return Result(
                first = RoomUnlockOutcome.Unavailable(detail),
                restOpened = 0,
                restFailed = rest.size,
            )
        }
        // 目标库与其余库地位完全相同（首库特殊化已随 H2 一起消失）。
        val firstResult = runCatching { repository.unlockVaultFromRoom(first) }
            .getOrElse { RoomUnlockOutcome.Unavailable(it.message ?: "打开失败") }
        AutofillLogger.d("fanout first=$first → ${describe(firstResult)}")
        var opened = 0
        var failed = 0
        for (id in rest) {
            val outcome = runCatching { repository.unlockVaultFromRoom(id) }
                .getOrElse { RoomUnlockOutcome.Unavailable(it.message ?: "打开失败") }
            if (outcome is RoomUnlockOutcome.Opened) opened++ else failed++
            AutofillLogger.d("fanout rest=$id → ${describe(outcome)}")
        }
        AutofillLogger.d(
            "fanout done first=${describe(firstResult)} opened=$opened failed=$failed",
        )
        return Result(first = firstResult, restOpened = opened, restFailed = failed)
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
    private suspend fun handleLockFailure(recovery: UnlockRecoveryRepository?): String {
        if (recovery == null) return LOCK_FAILED_FALLBACK
        return runCatching {
            // 判据唯一 = 房钥匙在不在内存（不是「信封在不在」—— 见 unlockAll 的 KDoc）。
            val keyInMemory = recovery.houseKeyInMemory.first()
            if (keyInMemory) {
                recovery.markRearmPending()
                REARM_PENDING
            } else {
                val report = recovery.degradeFingerprintLock()
                if (report.roomsRemoved > 0) {
                    DEGRADED_ROOMS_REMOVED
                } else if (report.remainingLocks) {
                    DEGRADED_PIN_REMAINS
                } else {
                    DEGRADED_FALLBACK
                }
            }
        }.getOrElse {
            AutofillLogger.d("fanout lock → 失效善后失败：${it.message}")
            LOCK_FAILED_FALLBACK
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
    // 说明：本 object 在 app 层但**不是 Composable**（`AutofillActivity` 也调它），
    // 拿不到 `stringResource`，故返回字面量与 `UnlockViewModel.localUnlockFailureText`
    // 同款。真源在 `strings.xml`，此处为无法访问资源的调用路径的镜像 ——
    // 两处措辞必须一致（`check_orphan_strings.py` 之外，此处靠 review 守）。

    private const val LOCK_FAILED_FALLBACK = "指纹门锁已失效，请用主密码解锁"

    /** 开门态：钥匙在手，等下次认证补写 —— 别说「已失效」（那是假话）。 */
    private const val REARM_PENDING = "指纹门锁需要重新启用（下次使用指纹时自动完成）"

    /** 关门态：锁已关 + 房间信封连带清掉 —— 必须说清「要重新启用」。 */
    private const val DEGRADED_ROOMS_REMOVED =
        "系统指纹已变更，指纹解锁已关闭；快速解锁范围内已无密码库，请用主密码解锁后重新启用"

    /** 关门态：锁已关但还有 PIN 锁在，用户的库没受影响。 */
    private const val DEGRADED_PIN_REMAINS = "系统指纹已变更，指纹解锁已关闭，请改用 PIN 或主密码解锁"

    /** 关门态：锁已关且再无其它门锁（且本来就没有房间信封）。 */
    private const val DEGRADED_FALLBACK = "系统指纹已变更，指纹解锁已关闭，请用主密码解锁"
}
