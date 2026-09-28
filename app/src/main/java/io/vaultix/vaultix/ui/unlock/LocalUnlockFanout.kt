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
import io.vaultix.domain.VaultRepository
import io.vaultix.vaultix.autofill.AutofillLogger
import javax.crypto.Cipher

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
     * @param cipher 本次 BiometricPrompt 认证返回的 cipher —— 只用于解**门锁**
     *   （一次 Keystore 操作），各库房间信封与它无关。
     */
    suspend fun unlockAll(
        repository: VaultRepository,
        first: String,
        rest: List<String>,
        cipher: Cipher,
    ): Result {
        // ★ 诊断埋点（2026-09-17 补、房子化后保留）：排「指纹过了却又让人解锁」
        //   必须能量化到"哪个库、哪种结论"，否则只能猜。
        AutofillLogger.d("fanout start first=$first rest=${rest.size}")
        // 门锁解不开（KEK 失效 / 信封损坏）：没钥匙，各库全是 NoKey —— 早退，
        // 不白跑循环。首库给主密码回退提示（定稿 §6：不静默重试，如实降级）。
        val lockOpened = runCatching { repository.completeFingerprintUnlock(cipher) }
            .getOrElse { false }
        if (!lockOpened) {
            AutofillLogger.d("fanout lock → 门锁解封失败（KEK 失效/信封损坏），全部回退主密码")
            return Result(
                first = RoomUnlockOutcome.Unavailable("指纹门锁已失效，请用主密码解锁"),
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
}
