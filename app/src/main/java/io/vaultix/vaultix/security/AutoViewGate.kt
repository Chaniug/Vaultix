/*
 * Vaultix — app:security
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 「从不」档的**界面门禁**（2026-10-30，用户诉求 = 对齐 Bitwarden 的保护强度）。
 *
 * ## 用户原话（这是本类存在的唯一理由）
 *
 * > 开启了从不锁定的设置，屏幕熄屏或者清除后台后，bitwarden 好像还是会需要指纹解锁。
 * > 但是填充并不会受影响……只是进入 bitwarden 这个软件的时候需要验证指纹，其他的不变。
 *
 * ## 两层锁，别混成一层
 *
 * | 层 | 存在哪 | 「从不」档下的行为 |
 * |---|---|---|
 * | 密钥层 | `VaultSessionManager.sessions` | **永不真锁** ⇒ 自动填充 / 凭据提供商直通 |
 * | 界面层 | `VaultSessionManager.viewLockedIds` | 熄屏 / 冷启动后要指纹 ⇒ **进 App 要验证** |
 *
 * 本类只打**界面层**的标记（`sessionRepository.viewLock(id, automatic = true)`），
 * 一个字节的密钥都不动 —— 这正是「填充不受影响」的实现方式。
 *
 * ## 触发点只有两个（多一个都会把「从不」变成「立即」）
 *
 * 1. **熄屏**（`ACTION_SCREEN_OFF` → `VaultLockManagerImpl.onScreenOff`）：
 *    用户把手机放下、装兜里，回来要确认一次身份。
 * 2. **冷启动自动恢复之后**（`AutoRestoreTrigger.reconcile` 的 restore 分支）：
 *    进程被清掉后重建，`AutoRestoreTrigger` 会**免认证**把库恢复回来
 *    （信封用的是免认证的 Keystore 密钥）。若不补一道门禁，
 *    「清掉后台再打开」就等于完全没验证 —— 那正是用户报的场景。
 *
 * ⚠️ **普通切后台不触发**：用户从 Vaultix 复制密码、切到别的 App 粘贴、再回来，
 * 是最高频的操作之一。把它也算作"离开"会让「从不」档退化成「立即」档，
 * 用户会把整个功能当成 bug 关掉。用户原话也只说了"熄屏"和"清后台"。
 *
 * ## 前置条件（缺一不可）
 *
 * 1. 该库档位 = `VaultTimeout.Never`（别的档位有自己的保护，不该被这层叠加）；
 * 2. 用户没关掉「从不锁定时仍需验证」开关（`autoViewGateOnNever`，默认开）；
 * 3. **指纹门锁可用**（`VaultRepository.fingerprintLockAvailable()`）；
 * 4. 开发者「保持不锁」没打开（debug 专用，见 `.ai/ISSUES.md` #213）。
 *
 * ⚠️ 第 3 条是**必须**的：解锁页的查看锁分支只给生物识别按钮，没有指纹时
 * `prepareFingerprintUnlock()` 返回 null、页面才降级到主密码表单
 * （`UnlockViewModel.startBiometricUnlock` 的 `cipher == null` 分支）。
 * 也就是说：**没有指纹时门禁 = 每次回来输主密码**，比不做门禁更糟。
 * 宁可让这些用户保持「从不」的免验证，也不要制造一个"看起来是 bug"的体验。
 */
package io.vaultix.vaultix.security

import io.vaultix.common.logging.VaultixLog
import io.vaultix.datastore.VaultTimeout
import io.vaultix.datastore.VaultTimeoutPreferences
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.domain.VaultRepository
import io.vaultix.domain.VaultSessionRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * 「从不」档熄屏 / 冷启动后的**界面门禁**（见文件头 KDoc）。
 *
 * 刻意做成**无状态**的：不缓存"这次该不该门禁"，每次都从偏好与档位现算。
 * 缓存会引入"改了开关但没生效"这类只有重启才好使的 bug，而本类的调用频率
 * 只有「熄屏」和「冷启动」两种，现算的成本可以忽略。
 */
@Singleton
class AutoViewGate @Inject constructor(
    private val vaultRepository: VaultRepository,
    private val sessionRepository: VaultSessionRepository,
    private val vaultTimeoutPrefs: VaultTimeoutPreferences,
    /**
     * **开发者**开关 `devRelaxScreenLock` 的读口。
     *
     * ⚠️ 产品开关 `autoViewGateOnNever` **不在**这里，它挂在 [vaultTimeoutPrefs] 上
     * —— 与"自动锁定档位"同组（它就是那个档位的一个附属策略）。
     *
     * 曾经写错过一次（2026-10-30 `812218b` 的 CI 红）：本类按
     * `preferences.autoViewGateOnNever` 去读，而 `VaultixPreferences` 里**根本没有**
     * 这个属性 ⇒ `Unresolved reference` + 三个 `Cannot infer type`。报错指向的是第一处
     * 使用点，而不是"属性不存在"这个事实，看着像泛型问题。
     * 判据：**加属性前先确认它在哪个类里**（`grep -rn "名字"`），别凭"这两个开关听起来是一组"。
     */
    private val preferences: VaultixPreferences,
) {

    /**
     * 对**所有当前已解锁**的库执行一次门禁判定。
     *
     * 幂等：已经打过标记的库再打一次没有副作用（`viewLock` 是集合并集）。
     *
     * 调用方：`VaultLockManagerImpl.onScreenOff` 与 `AutoRestoreTrigger`（恢复成功后）。
     */
    suspend fun gateUnlockedVaults() {
        val unlocked = runCatching {
            vaultRepository.observeUnlockedVaultIds().first()
        }.getOrElse { error ->
            // 读不到库列表 ⇒ 什么都不做。门禁是**便利性保护**，
            // 不能因为一次读取异常就把用户挡在门外（那不是安全，是可用性事故）。
            VaultixLog.w(TAG) { "autoViewGate → 读已解锁库失败，跳过：${error.message}" }
            return
        }
        if (unlocked.isEmpty()) return
        if (!gateEnabled()) return
        val candidates = neverTimeoutVaults(unlocked)
        if (candidates.isEmpty()) return
        if (!quickUnlockReady()) {
            VaultixLog.d(TAG) { "autoViewGate → 指纹门锁不可用，跳过（避免退化成每次输主密码）" }
            return
        }
        candidates.forEach { sessionRepository.viewLock(it, automatic = true) }
        VaultixLog.d(TAG) { "autoViewGate → 已对 ${candidates.size} 个「从不」库设界面门禁" }
    }

    /**
     * 两个开关是否都允许门禁。
     *
     * 任一读取失败都按**不门禁**处理：门禁失败最坏的结果是"这次没验证"，
     * 而"读不到偏好就锁死界面"会把用户挡在自己的密码库外面。
     * ⚠️ 这与 `VaultLockManagerImpl.devRelaxScreenLockEnabled()` 的 fail-closed
     * 方向**相反**，因为那处决定的是"要不要清密钥"（安全动作），
     * 这处决定的是"要不要挡界面"（可用性动作）。别把两处的默认值抄成一样。
     */
    private suspend fun gateEnabled(): Boolean {
        val product = runCatching { vaultTimeoutPrefs.autoViewGateOnNever.first() }.getOrDefault(false)
        if (!product) return false
        val devRelax = runCatching { preferences.devRelaxScreenLock.first() }.getOrDefault(false)
        return !devRelax
    }

    /** 只挑「从不」档的库 —— 其余档位有自己的锁定时机，不该被这层叠加。 */
    private suspend fun neverTimeoutVaults(vaultIds: Set<String>): List<String> =
        vaultIds.filter { id ->
            runCatching { vaultTimeoutPrefs.vaultTimeout(id).first() }.getOrNull() == VaultTimeout.Never
        }

    /**
     * 指纹门锁是否可用（**全局**判定，不是逐库）。
     *
     * 房子化之后门锁是全局的（`HouseKeyStore`），所以这里只问一次；
     * 逐库问会做 N 次 Keystore 调用，而熄屏是高频事件。
     */
    private suspend fun quickUnlockReady(): Boolean =
        runCatching { vaultRepository.fingerprintLockAvailable().first() }.getOrDefault(false)

    private companion object {
        const val TAG = "VaultixAutoViewGate"
    }
}
