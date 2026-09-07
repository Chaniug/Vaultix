/*
 * Vaultix — data:bitwarden
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规，**不可删除**）
 * 本文件的「空库保护」设计衍生自 Bastion 项目（GPL-3.0，Copyright 2025 JoyinJoester）
 * 的 sync/EmptyVaultProtection.kt；Bastion 注明其参考 Keyguard 的安全同步策略。
 * 本文件按 Vaultix 的 String 型 vaultId 与日志规范改写，同样以 GPL-3.0 发布。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.bitwarden.sync

/**
 * 同步结果。分类参考 Bastion 的 SyncExecutionOutcome：把失败区分成
 * 可重试 / 被保护拦截 / 致命，便于上层给出**可执行的**提示，而不是笼统报错。
 */
sealed interface SyncOutcome {

    data class Success(val cipherCount: Int, val folderCount: Int) : SyncOutcome

    /** 预检发现服务端无变化，跳过全量（省流量、省电量）。 */
    data object Skipped : SyncOutcome

    /** 被保护机制拦截（如空库保护），需用户确认后才能继续。 */
    data class Blocked(val reason: String) : SyncOutcome

    /** 网络等可恢复错误，可稍后重试。 */
    data class RetryableError(val message: String) : SyncOutcome

    /** 无法自动恢复（如 token 失效需重新登录）。 */
    data class FatalError(val message: String) : SyncOutcome
}
