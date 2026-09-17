/*
 * Vaultix — app:ui · settings
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.settings

import io.vaultix.domain.KdbxSyncReport
import io.vaultix.domain.KdbxSyncRepository
import io.vaultix.domain.VaultRepository
import io.vaultix.domain.VaultSyncReport
import io.vaultix.model.KdbxCloudSyncStatus
import io.vaultix.model.VaultKind
import io.vaultix.model.VaultSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * **逐库动作**（锁定 / 同步 / 退出 / 移除）的交互与执行。
 *
 * ## 为什么要有这个类（定稿 §11.10）
 *
 * 病根是**「退出数据库」是一个全局动作、范围不可见** ⇒ 用户误点一次就以为数据丢了。
 * 修它需要的不是"加一道确认"，而是**让每个库有自己的动作** —— 范围写在被点的那一行上，
 * 不可能再误伤别的库。本类就是这些"逐库动作"的执行处。
 *
 * ## 为什么不放进 `SettingsViewModel` / `VaultRepositoryImpl`
 *
 * - `VaultRepositoryImpl` 已**顶格 40 个函数**（detekt `TooManyFunctions`），再加就红；
 * - `SettingsViewModel` 也接近上限，且它管的是**偏好**，不是"对一个库做一件事"。
 * ⇒ 与 `QuickUnlockController` 同一套路：**独立控制器 + 独立状态流**。
 *
 * ## ★ 按钮按能力显示（不是全列出来再禁用）
 *
 * | 动作 | 本地 KDBX（`content://`） | 网盘 KDBX（`webdav:` / `onedrive:`） | Bitwarden |
 * |---|---|---|---|
 * | 锁定 | ✅ | ✅ | ✅ |
 * | 同步 | ❌ | ✅ | ✅ |
 * | 退出 | ❌ | ❌ | ✅ |
 * | 移除 | ✅ | ✅ | ✅ |
 *
 * - **本地 KDBX 没有「同步」**：它的真相就是那个文件本身，没有"另一端"可比；
 * - **KDBX 没有「退出」**：KDBX 没有账号、没有 token、条目也不落 Room ——
 *   「退出」与「锁定」是同一件事，给两个名字只会让人以为它们不同。
 *
 * ⚠️ 判据用**现成的 `origin` 前缀**（`KdbxFileSourceResolver` 那张表就是这么判的），
 * **不新加 `sourceType` 列** —— 一加就是两处真相，必然漂移。
 *
 * ## 同步的 `localChangedSinceLastSync` 从哪来
 *
 * `KdbxSyncRepository.sync` 要求调用方告诉它"本地改过没有"，**实现层不去猜**
 * （猜错两个方向都糟）。这里取持久化的 `vaults.syncStatus`：它描述的正是
 * "这个库与远端的差距"，比临时探一次内存可靠。
 */
class VaultActionsController(
    private val vaultRepository: VaultRepository,
    private val kdbxSyncRepository: KdbxSyncRepository,
    private val scope: CoroutineScope,
) {

    /** 需要用户拍板的四种情形（一次只会有一种，故用一个可空字段而不是四个布尔）。 */
    sealed interface Dialog {

        /** 退出某库（清本机缓存，需重新登录）。 */
        data class SignOut(val vault: VaultSummary) : Dialog

        /** 移除某库（只删本机这一行）。 */
        data class Remove(val vault: VaultSummary) : Dialog

        /** 网盘同步撞上"两边都改了"，必须用户拍板（**重试永远好不了**）。 */
        data class Conflict(val vault: VaultSummary) : Dialog

        /** 结果反馈（成功 / 失败都要有，见下方「不做静默失败」）。 */
        data class Result(val outcome: Outcome, val vaultName: String) : Dialog
    }

    /**
     * 一次动作的结论。
     *
     * ⚠️ 为什么**失败也是一态**：静默失败是本项目反复踩的坑（见 `.ai/README.md`
     * 「今天的共同病根」）。同步失败若不报，用户会以为"已经传上去了"。
     */
    sealed interface Outcome {
        data object Locked : Outcome

        data object SignedOut : Outcome

        data object Removed : Outcome

        /** 同步完成（网盘同步没有条目数概念时 [count] 为 null）。 */
        data class Synced(val count: Int?) : Outcome

        /** 两边一致，什么都没做 —— 这不是失败，要如实说"没变化"。 */
        data object SyncUnchanged : Outcome

        data class SyncFailed(val reason: String) : Outcome

        data object SyncUnsupported : Outcome

        /**
         * 🔴 远端更新、本地未改，但替换本地会话**需要重新解锁**。
         *
         * ⚠️ 必须与"已同步"分开：当成成功，用户会以为自己已经在看最新内容。
         */
        data object SyncNeedsUnlock : Outcome

        data class Failed(val detail: String) : Outcome
    }

    private val _dialog = MutableStateFlow<Dialog?>(null)

    /** 当前要展示的对话框；null = 没有。 */
    val dialog: StateFlow<Dialog?> = _dialog.asStateFlow()

    private val _busyVaultId = MutableStateFlow<String?>(null)

    /** 正在跑动作的库 id（行上显示转圈，并挡住重复点击）。 */
    val busyVaultId: StateFlow<String?> = _busyVaultId.asStateFlow()

    /** 丢弃内存密钥，秒解（不碰任何缓存与远程）。 */
    fun lock(vault: VaultSummary) = run(vault) { vaultRepository.lockVault(it); Outcome.Locked }

    /**
     * 触发一次同步。
     *
     * 按库类型分派：网盘 KDBX 走 `KdbxSyncRepository`，Bitwarden 走 `syncVault` ——
     * 两者的仲裁语义根本不同（条件写+用户拍板 vs 服务端 revision），压成一个方法
     * 会让两边都变形（见 `KdbxSyncRepository` 的类注释）。
     */
    fun sync(vault: VaultSummary) = run(vault) {
        if (vault.kind == VaultKind.KDBX) {
            syncKdbx(vault)
        } else {
            when (val report = vaultRepository.syncVault(it)) {
                is VaultSyncReport.Success -> Outcome.Synced(report.cipherCount)
                is VaultSyncReport.Skipped -> Outcome.SyncUnchanged
                is VaultSyncReport.Blocked -> Outcome.SyncFailed(report.reason)
                is VaultSyncReport.Retryable -> Outcome.SyncFailed(report.reason)
                is VaultSyncReport.Fatal -> Outcome.SyncFailed(report.reason)
                is VaultSyncReport.Unsupported -> Outcome.SyncUnsupported
            }
        }
    }

    /**
     * 网盘 KDBX 的同步。
     *
     * @return `null` = 撞上冲突、已交给 [Dialog.Conflict] 接管（此时**不能**再弹结果对话框，
     *   否则会把它盖掉 —— 用户看到"没变化"而实际什么都没定下来）。
     */
    private suspend fun syncKdbx(vault: VaultSummary): Outcome? {
        val report = kdbxSyncRepository.sync(vault.id, localChanged(vault))
        return when (report) {
            is KdbxSyncReport.InSync -> Outcome.SyncUnchanged
            is KdbxSyncReport.Uploaded -> Outcome.Synced(null)
            is KdbxSyncReport.Downloaded -> Outcome.Synced(null)
            is KdbxSyncReport.NeedsReload -> Outcome.SyncNeedsUnlock
            is KdbxSyncReport.NoCloudSource -> Outcome.SyncUnsupported
            is KdbxSyncReport.Failed -> Outcome.SyncFailed(report.reason)
            // ★ 冲突**不在这里收敛**：拒写之后重试永远好不了，必须让用户拍板。
            is KdbxSyncReport.Conflict -> {
                _dialog.value = Dialog.Conflict(vault)
                null
            }
        }
    }

    /** 退出：清本机缓存与凭据（远程不动）。先出确认对话框。 */
    fun requestSignOut(vault: VaultSummary) {
        _dialog.value = Dialog.SignOut(vault)
    }

    /** 移除：只删本机这一行（远程不动）。先出确认对话框。 */
    fun requestRemove(vault: VaultSummary) {
        _dialog.value = Dialog.Remove(vault)
    }

    /** 退出：确认后执行（[vault] 只在执行时用于取名字，动作本身按 id 走）。 */
    fun confirmSignOut(vault: VaultSummary) = run(vault) { vaultRepository.signOut(it); Outcome.SignedOut }

    /** 移除：确认后执行。 */
    fun confirmRemove(vault: VaultSummary) = run(vault) { vaultRepository.removeVault(it); Outcome.Removed }

    /** 冲突拍板：用本机覆盖远端（**唯一**绕过条件检查的写入路径）。 */
    fun resolveUsingLocal(vault: VaultSummary) = run(vault) { resolve(it, local = true) }

    /** 冲突拍板：用远端覆盖本机（本机未上传的改动会被丢弃）。 */
    fun resolveUsingRemote(vault: VaultSummary) = run(vault) { resolve(it, local = false) }

    private suspend fun resolve(vaultId: String, local: Boolean): Outcome {
        val report = if (local) {
            kdbxSyncRepository.resolveUsingLocal(vaultId)
        } else {
            kdbxSyncRepository.resolveUsingRemote(vaultId)
        }
        return when (report) {
            is KdbxSyncReport.Failed -> Outcome.SyncFailed(report.reason)
            else -> Outcome.Synced(null)
        }
    }

    /**
     * 冲突拍板「稍后再决定」：状态落回冲突、不做 IO。
     *
     * ⚠️ 必须是个真方法而不是"UI 自己关对话框"：不清中间态会留下一个永远转不完的
     * 「同步中…」（见 `KdbxSyncRepository.defer` 的注释）。
     */
    fun deferConflict(vaultId: String) {
        scope.launch { kdbxSyncRepository.defer(vaultId) }
        dismiss()
    }

    fun dismiss() {
        _dialog.value = null
    }

    /**
     * 统一的执行壳：置忙 → 跑 → 出结论。
     *
     * ⚠️ 异常**不吞成空**：catch 后变成 [Outcome.Failed] 展示出来，
     * 否则一次崩溃在用户看来就是"点了没反应"。
     */
    private fun run(vault: VaultSummary, block: suspend (String) -> Outcome?) {
        scope.launch {
            _busyVaultId.value = vault.id
            val outcome = runCatching { block(vault.id) }
                .getOrElse { Outcome.Failed(it.message.orEmpty()) }
            _busyVaultId.value = null
            // null = 已有别的对话框接管（例如冲突拍板），此时不要盖掉它。
            if (outcome != null) _dialog.value = Dialog.Result(outcome, vault.name)
        }
    }

    /** 自上次同步以来本地有没有改动（取持久化的 `syncStatus`，不去猜）。 */
    private fun localChanged(vault: VaultSummary): Boolean = when (vault.syncStatus) {
        KdbxCloudSyncStatus.PENDING_UPLOAD,
        KdbxCloudSyncStatus.PENDING_UPLOAD_WITH_LOCAL_CHANGES,
        KdbxCloudSyncStatus.CONFLICT,
        -> true
        else -> false
    }
}

/**
 * 该库有没有"另一端"可同步。
 *
 * `content://` = 本地 SAF 文件（真相就是那个文件，没有远端）⇒ 没有同步项。
 * 网盘 KDBX（`webdav:` / `onedrive:`）与 Bitwarden（origin 是服务器地址）都有。
 */
internal fun VaultSummary.canSyncToRemote(): Boolean = !origin.startsWith(CONTENT_SCHEME)

/**
 * 该库有没有"退出"这回事。
 *
 * KDBX 没有账号、没有 token、条目不落 Room ⇒ 「退出」与「锁定」同义，
 * 给出两个名字只会让人以为它们是不同的操作。
 */
internal fun VaultSummary.canSignOutOfDevice(): Boolean = kind == VaultKind.BITWARDEN

/** 本地 SAF 库 origin 的前缀（与 `KdbxFileSourceResolver` 的判别表一致）。 */
private const val CONTENT_SCHEME = "content://"
