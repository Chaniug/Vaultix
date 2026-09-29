/*
 * Vaultix — domain
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * **「从不锁定」档自动恢复**的领域入口（2026-09-29，对齐 Bitwarden autoUnlockKey）。
 *
 * ## 为什么不并进 `VaultRepository`
 *
 * 与 `KdbxSyncRepository` 同一条理由：`VaultRepositoryImpl` 已**正好 40 个函数**
 * （detekt `TooManyFunctions` 硬上限），加任何方法都爆门禁。语义上也该分开 ——
 * 快速解锁门锁的开/关属 `VaultRepository`（用户配置动作），而本接口管的是
 * **进程死亡后的会话恢复**（系统生命周期动作，无用户交互）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.domain

import kotlinx.coroutines.flow.Flow

/**
 * 「从不锁定」档的自动恢复（实现见 `data:repository` 的 `AutoUnlockRepositoryImpl`）。
 *
 * 模型（Bitwarden `userAutoUnlockKey` 的房子化等价物）：
 * - 档位 = Never 且房钥匙在内存（解锁/开门锁）→ 落一份「免认证 Keystore 密钥
 *   包裹的房钥匙」信封（幂等）；
 * - 进程死亡后（房钥匙随内存消失）→ 解信封复得钥匙 → 逐库开房间 → 会话恢复，
 *   **无需任何用户交互**；
 * - 用户主动锁库 / 档位离开 Never → 删信封（「锁定」是真锁定）。
 *
 * 触发编排（谁在什么时机调这里）在 app 层的 `AutoRestoreTrigger`，
 * 本接口只承载**可被复用与测试的动作**。
 */
interface AutoUnlockRepository {

    /** 房钥匙是否在内存（协调器的观察源；false = 进程重启后的初始态）。 */
    val houseKeyInMemory: Flow<Boolean>

    /** 自动恢复信封是否已落盘（只读键名，不过 Keystore）。 */
    suspend fun hasEnvelope(): Boolean

    /**
     * 写自动恢复信封（幂等：已存在直接返回 true 不重写）。
     *
     * @return false = 房钥匙不在内存，此刻写不了。调用方等 [houseKeyInMemory]
     *   变化再试，不要轮询。
     */
    suspend fun enrollEnvelope(): Boolean

    /**
     * 执行一次自动恢复：解信封复得房钥匙 → 对全部建了房间信封的库调
     * `VaultRepository.unlockVaultFromRoom`（纯软件解密，无 KDF / 无网络 / 不触发 2FA）。
     *
     * 逐库独立成败（某库凭据过期 / 文件移走不影响其它库），结论进 [AutoRestoreReport]。
     *
     * @return false = 信封不存在或不可解（恢复未发生，用户走正常解锁）。
     */
    suspend fun restore(): AutoRestoreReport

    /** 删信封（主动锁库 / 离开 Never 档）。幂等。 */
    suspend fun removeEnvelope()

    /**
     * 解除某库的「用户主动锁」标记（**多库锁模型定稿 D1**，2026-09-29）。
     *
     * 语义：该库**重新解锁成功**即视为用户撤回了「锁」的意图 —— 下一次进程死亡后
     * 它重新回到自动恢复的范围内。
     *
     * ⚠️ 为什么把「清标记」放在本接口（而不是解锁路径上各写一遍）：解锁成功有
     * 至少 5 条路径（主密码 / 2FA / 房间信封 × 库类型），散着写过一轮必然漏一条；
     * 由 `AutoRestoreTrigger` 的**解锁成功钩子**统一调用（它本来就 observe
     * `observeUnlockedVaultIds()` 的新增）。
     */
    suspend fun clearUserLock(vaultId: String)

    /**
     * **离场软锁**（2026-09-29 新增，对齐 Bitwarden 的「Never 档离场仍锁」）。
     *
     * ## 为什么需要它：两种「锁」不能混为一谈
     *
     * | | 触发 | 房钥匙 | 本信封 | 回来自动开 |
     * |---|---|---|---|---|
     * | **软锁** | 用户离开 App（划后台 / 锁屏） | 清零 | **保留** | ✅ 免交互 |
     * | **硬锁** | 用户点「锁定」/ 退出数据库 | 清零 | **删除** | ❌ 过门锁 |
     *
     * 两者在「密钥是否清零」上完全一致，**唯一差别是信封的留与删**。
     * 混成一种的后果（本次修复的 bug）：Never 档下用户划掉后台，因为
     * `Never` 分支根本不触发任何锁定，房钥匙一直留在内存里 ——
     * 用户体感「库从来没锁过」，且进程被内存转储时可捞到钥匙。
     *
     * ⇒ 软锁 = 清会话 + 清房钥匙 + **明确保留**信封。用户回到前台时
     * [restore] 会免交互把库开回来，所以体感与「一直开着」一致，
     * 但**后台期间密钥确实不在内存**。
     *
     * @return 实际被软锁的库 id（诊断 / 测试用）。
     */
    suspend fun softLock(): List<String>
}

/** 一次自动恢复的结论（不含任何密钥材料；诊断与测试断言用）。 */
data class AutoRestoreReport(
    /** 信封是否成功解开（房钥匙是否回到内存）。 */
    val envelopeOpened: Boolean,
    /** 建有房间信封的库总数。 */
    val roomCount: Int,
    /** 成功恢复会话的库数。 */
    val opened: Int,
    /** 未能恢复的库 id（凭据过期 / 文件不可读等，逐库独立原因）。 */
    val failedVaultIds: List<String>,
)
