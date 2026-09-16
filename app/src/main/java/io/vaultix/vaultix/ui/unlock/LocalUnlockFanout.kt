/*
 * Vaultix — app:ui · unlock
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.unlock

import io.vaultix.domain.KdbxUnlockOutcome
import io.vaultix.domain.UnlockResult
import io.vaultix.domain.VaultRepository
import io.vaultix.model.VaultKind
import javax.crypto.Cipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 「一次认证 → 打开多个库」的**唯一实现**（2026-09-16 新增）。
 *
 * ## 为什么要有这个文件
 *
 * 在此之前，同一条逻辑在**两处**各写了一份：[UnlockViewModel.completeLocalUnlock]
 * 与 `AutofillActivity.completeLocalUnlockByKind`。两份都要处理「按库类型分流」
 * （Bitwarden 包对称密钥 / KDBX 包主密码），而这条分流一旦写错**不会报错、只会静默失效**
 * —— KDBX 的包裹物走 Bitwarden 那条路会解出完全错误的语义。
 * 两处各写一遍等于把同一个坑埋了两次，且很可能只修好一处（本项目已有同类教训）。
 *
 * 现在两处都调这里，坑只存在一处。
 *
 * ## 顺序与复用（关键）
 *
 * 快解的保护器 KEK 是 **auth-per-use**：一个 [Cipher] 只对一次认证有效。
 * 首个库必须用**本次认证传回的 cipher**；其余库则要趁 KEK 的授权窗口
 * 各自新建一个**解密** cipher（`prepareLocalUnlock`）。这与设置页登记时
 * 「一个 cipher 连续 wrap」是同一约束的两面：
 *
 * | 场景 | cipher 来源 | 用途 |
 * |---|---|---|
 * | 登记（写信封） | 一次认证，`newEncryptCipher` | 连续 wrap 多个库 |
 * | 解锁（读信封） | 首个用认证 cipher，其余各自 `prepareLocalUnlock` | 各自 unwrap 一个库 |
 *
 * ⚠️ 其余库的 cipher 取不到（`prepareLocalUnlock` 返回 null = 该库 KEK 也失效了）
 * 就**跳过该库**，不影响首个库 —— 首个库已经成功了，不能因为别的库失败而把它回滚。
 */
internal object LocalUnlockFanout {

    /** 一次 fanout 的结论。 */
    data class Result(
        /** 首个库（本次认证直接解封的那个）的结论。 */
        val first: UnlockResult,
        /** 其余库里成功打开的数量。 */
        val restOpened: Int,
        /** 其余库里未能打开的数量（cipher 取不到 / 解封失败）。 */
        val restFailed: Int,
    )

    /**
     * 解封 [first]，随后趁认证窗口解封 [rest] 里已启用快速解锁的库。
     *
     * ## 为什么首个库的结论要单独返回
     *
     * 调用方（解锁页）的核心诉求是"我这个库到底开了没有" —— 那是用户点指纹的目的。
     * 其余库是附带收益。若把成败混成一个数字，首个库失败时调用方就不知道
     * 该不该报错（"3 个里成了 2 个"到底是成功还是失败？）。
     * 分开返回，语义就没歧义：**首个成功 = 用户的目的达成**。
     *
     * @param cipher 本次 BiometricPrompt 认证返回的 cipher。**只能用于 [first]**。
     */
    suspend fun unlockAll(
        repository: VaultRepository,
        first: String,
        rest: List<String>,
        cipher: Cipher,
    ): Result {
        val firstResult = completeByKind(repository, first, cipher)
        var opened = 0
        var failed = 0
        for (id in rest) {
            // 每个库要用各自新建的解密 cipher：认证窗口内 `prepareLocalUnlock`
            // 会为该库的信封初始化一个 KEK cipher（IV 来自 payload）。
            val next = runCatching { repository.prepareLocalUnlock(id) }.getOrNull()
            if (next == null) {
                failed++
                continue
            }
            if (completeByKind(repository, id, next) == UnlockResult.Success) opened++ else failed++
        }
        return Result(first = firstResult, restOpened = opened, restFailed = failed)
    }

    /**
     * 按库类型走正确的本地解锁路径。
     *
     * ⚠️ **不能一律调 `completeLocalUnlock`**（定稿 §4）：那条路把包裹物当作
     * **Bitwarden 对称密钥**（`enc ‖ mac`）解析；KDBX 的包裹物是「主密码 + keyfile」，
     * 走过去会解出错误语义 —— `SymmetricCryptoKey.fromFullKey` 拿一段带魔数的字节
     * 当密钥，轻则解锁失败，重则把会话建立成一把错密钥。**必须先查 kind 再分流。**
     *
     * ⚠️ 这里**不吞**异常到 `false`：调用方靠返回的 [UnlockResult] 区分
     * 「凭据过期」与「环境错误」，吞掉会让"KDBX 主密码被改过"这种可自愈的情形
     * 变成一句无意义的"解锁失败"。
     */
    private suspend fun completeByKind(
        repository: VaultRepository,
        vaultId: String,
        cipher: Cipher,
    ): UnlockResult {
        val isKdbx = withContext(Dispatchers.IO) { isKdbxVault(repository, vaultId) }
        return if (isKdbx) {
            when (val outcome = repository.completeLocalUnlockKdbx(vaultId, cipher)) {
                KdbxUnlockOutcome.Opened -> UnlockResult.Success
                // ★ D3（定稿 §4.4）：指纹过了但包裹物打不开 ⇒ 主密码很可能已改。
                //   归类为凭据错误，让 UI 提示「主密码可能已变更」并引导重输。
                KdbxUnlockOutcome.StaleCredentials -> UnlockResult.InvalidCredentials
                is KdbxUnlockOutcome.Unavailable -> UnlockResult.Unknown(outcome.detail)
            }
        } else {
            repository.completeLocalUnlock(vaultId, cipher)
        }
    }

    /**
     * 该库是否为 KDBX。
     *
     * 以**库表**为准，不信 UI 侧的快照：解锁页的 `state.vault` 可能是首帧旧值，
     * 用过期快照分流正是"静默失效"的温床。查不到（库已被移除）按非 KDBX 处理，
     * 让 `completeLocalUnlock` 自己如实报错。
     */
    private suspend fun isKdbxVault(repository: VaultRepository, vaultId: String): Boolean =
        runCatching {
            repository.observeVaults().first().firstOrNull { it.id == vaultId }?.kind
        }.getOrNull() == VaultKind.KDBX
}
