/*
 * Vaultix — app:security
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * 凭据提供商（Credential Provider）流程的**前台豁免窗口**。
 *
 * 解决的问题（现场真机症状）：
 *  1. 浏览器发起通行密钥请求 → CP 列出候选（此时库是解锁的，能列出说明已解锁）；
 *  2. 用户点候选 → 系统拉起 `PasskeyGetActivity`；
 *  3. 该 Activity 属于本 App 的另一个 Activity，`ProcessLifecycleOwner` 因此走
 *     `onStop` → `onStart`（同一进程内的 Activity 切换在 ProcessLifecycle 语义下
 *     等同于一次前后台切换）；
 *  4. `AutoLockController.onStart` 判定「回前台是否该锁」——`AutoLockPolicy
 *     .screenLockRequiresRelock(screenLocked) = screenLocked`，且部分路径下
 *     `timedOut` 为真 → `lockAll()`；
 *  5. 会话被清 → `ItemRepositoryImpl.observeState` 发空列表 → Activity 读到
 *     `cred == null` → 回灌失败 → 浏览器报「Authentication failed」。
 *
 * 也就是说：**用户从未离开 Vaultix 的语义前台**，只是系统在我们自己发起的凭据
 * 流程里切了个 Activity，却被自动锁定误判成「离开后又超时回来」。
 *
 * Bitwarden 的对应设计（`VaultLockManagerImpl`）：
 *  - `FOREGROUNDED → handleOnForeground()` 会**取消**超时任务（切回前台不锁）；
 *  - 另有一条 `OnAppRestart` 的自动填充豁免（autofill 触发的重启不清会话）。
 *
 * 本对象只提供「最近一次凭据流程启动时刻」这一个共享时间戳，供
 * [AutoLockController] 在回前台判定时短期豁免。**不持有任何密钥 / 不改变锁态**，
 * 只是让自动锁定知道「刚刚这次『前台切换』是我们自己造成的」。
 *
 * 安全性：豁免窗口极短（见 [EXEMPTION_WINDOW_MS]），且仅在「库当前是解锁的」前提下
 * 才可能被走到（`AutoLockController.onStart` 首行 `if (!anyUnlocked) return`）。
 * 因此窗口的存在只会让一次**本就要发生的锁定**延后数秒，不会把已锁的库解开。
 */
package io.vaultix.vaultix.security

import android.os.SystemClock

object CredentialFlowGuard {

    /**
     * 豁免窗口时长（毫秒）。
     *
     * 取值依据：从 CP 列出候选到用户点选、再到系统拉起 Activity，
     * 全部发生在同一段交互内，正常路径远小于 1 秒；留 8 秒覆盖
     * 「用户盯着候选列表犹豫一会儿才点」的情况。窗口过长会把用户真实的
     * 「切走 App 再回来」也豁免掉，故不能放宽。
     */
    const val EXEMPTION_WINDOW_MS = 8_000L

    /**
     * 最近一次凭据流程（GET/CREATE 候选点击、解锁引导）的启动时刻。
     *
     * 用 [SystemClock.elapsedRealtime]（含休眠的单调时钟）——它是**跨进程**可比的
     * 单调时间基准，且不受用户改系统时间影响（Bastion `SessionManager` 同款选择）。
     *
     * ⚠️ **初值必须是 0 而不是 `Long.MIN_VALUE`**（2026-08-11 修正，由真实单测抓出）：
     * 判定式是 `now - lastFlowStartedAtMs < WINDOW`。若 `lastFlowStartedAtMs` 取
     * `Long.MIN_VALUE`，则 `now - Long.MIN_VALUE` 会**整数溢出为负数**，而负数恒
     * `< WINDOW` → 判定「在窗口内」**恒为真** → 自动锁定被**永久抑制**。
     * 取 0 时 `now`（elapsedRealtime，恒为正且远大于窗口）减去 0 得到 `now`，
     * 不溢出，且 `now < WINDOW` 只在开机头 8 秒内成立——那期间也不可能有已解锁会话。
     */
    @Volatile
    var lastFlowStartedAtMs: Long = 0L
        private set

    /** 记录一次凭据流程启动（由 CP Service / Activity 在拉起对端前调用）。 */
    fun markFlowStarted() {
        lastFlowStartedAtMs = SystemClock.elapsedRealtime()
    }

    /** 当前是否处于豁免窗口内（供测试与诊断读取）。 */
    fun isWithinExemptionWindow(nowMs: Long = SystemClock.elapsedRealtime()): Boolean =
        nowMs > 0L && nowMs - lastFlowStartedAtMs < EXEMPTION_WINDOW_MS

    /** 仅供测试复位。 */
    fun resetForTest() {
        lastFlowStartedAtMs = 0L
    }
}
