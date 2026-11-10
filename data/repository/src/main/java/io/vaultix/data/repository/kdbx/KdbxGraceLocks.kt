/*
 * Vaultix — data:repository
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */

package io.vaultix.data.repository.kdbx

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 切库时**延迟**锁定其它 KDBX 库（**宽限期**，2026-11-10）。
 *
 * ## 为什么需要它（真机反馈）
 *
 * 用户原话：
 *
 * > 我有 Bitwarden 库 A，OneDrive 上的 KDBX 库 B，以及本地 KDBX 库 C。
 * > 随便在一个库点击切换库的时候，会发现 B 和 C 都会有一个被锁定，
 * > 打开 C 的时候 B 会锁定，打开 B 的时候 C 会锁定，而 A 库不受影响。
 * > 而且 OneDrive 上的库在被锁起来的时候，需要验证指纹解锁，
 * > 这时候指纹解锁的速度就很慢了。
 *
 * 「B/C 互锁」本身是 `#60` 的既有决策（KDBX 的密钥是整库明文，多库同时解锁
 * 违反「同时只能进一个库」的内存约束），**这条决策没变** —— 本类只改**时机**。
 *
 * 变的是代价：旧实现是「一进新库就立刻 `Kdbx.lock`」，于是
 *
 * ```
 * 进 C → 锁 B（丢整库明文）
 * 切回 B → 会话没了 → Kdbx.unlock 全流程：
 *          OneDrive stat 往返 + Argon2（缓存命中也要 ≈1.2s）+ 解析整库
 * ```
 *
 * 而这件事在「来回对比两个库」时会被反复触发 —— 用户感受到的就是
 * 「切一次库，解锁一次，慢得离谱」。
 *
 * ## 做法：把「立刻锁」换成「N 秒后锁」
 *
 * | 时刻 | 行为 |
 * |---|---|
 * | 进入库 X | 其它已解锁 KDBX 开始倒计时（各一份） |
 * | 宽限期内切回库 Y | **撤销 Y 的倒计时** ⇒ 会话还在 ⇒ 解锁瞬时 |
 * | 倒计时到点 | 真锁（`Kdbx.lock` + 清查看锁 + 通知代次） |
 * | 手动锁 / 退出 / 删库 | 立即锁，并撤销该库的倒计时（幂等，不等宽限） |
 *
 * ## 为什么是 60 秒
 *
 * - 太短（10s 级）：覆盖不了「打开 B 看一眼，切回 C 抄一个密码」这种往返；
 * - 太长（5min 级）：明文在内存里停留过久，`#60` 的初衷就被架空了；
 * - 60 秒 ≈ 一次「查看 → 对比 → 返回」的自然时长，也是常见的后台宽限档位。
 *
 * ## 为什么不是「完全不锁」
 *
 * 完全不锁会把「同时只能进一个库」这条内存约束彻底废掉，而它不是没有道理：
 * KDBX 会话握的是整库明文的对象图（不是一把 32 字节密钥）。折中放在**时间维度**
 * （只允许最近两个库在 60 秒内短暂并存），比放在「库数量」维度更贴近真实使用。
 *
 * ## ★ 为什么单独成类而不是写进 `VaultRepositoryImpl`
 *
 * 1. 那边函数数**正好卡在 detekt 的 40 上限**，任何新增都会爆；
 * 2. 这里的核心是「到期才执行」的时序，必须能用**虚拟时钟**验证 ——
 *    而 `VaultRepositoryImpl` 拖着 Android / Room / Keystore，纯 JVM 测不了时序。
 *
 * ⚠️ 本类**不做**「App 进后台立即锁」：那需要把 `flush` 再穿过接口一层，
 * 而收益只是「后台少留 60 秒明文」。**60 秒后必然锁**，边界已经收住；
 * 真要更保守，改 [DEFAULT_GRACE_MS] 一处即可。
 */
internal class KdbxGraceLocks(
    /**
     * 倒计时所在的 scope。
     *
     * ⚠️ 生产必须是**应用级**（进程存活期），不能是页面 / ViewModel 作用域：
     * 用户切库后原页面立刻销毁，用短生命周期 scope 会让倒计时在导航瞬间被取消
     * ⇒ 库**永远不锁**。这比「锁得太早」危险得多，而且是静默的。
     */
    private val scope: CoroutineScope,
    private val graceMs: Long = DEFAULT_GRACE_MS,
    /**
     * 到点后的实际锁定动作（由仓储提供：`Kdbx.lock` + 清查看锁 + 代次自增）。
     *
     * ⚠️ 必须是 `suspend`：锁库要落 `markUserLocked` 之类的磁盘动作；
     * 传普通 lambda 会让调用方被迫 `runBlocking`。
     */
    private val onExpire: suspend (String) -> Unit,
) {

    /** vaultId → 倒计时 job。只含「当前未活跃、等待被锁」的库。 */
    private val pending = ConcurrentHashMap<String, Job>()

    /**
     * 进入 [keepVaultId] 之后的收敛：**撤销它的倒计时**，给其余已解锁库排期。
     *
     * @param unlocked 进入动作发生时的已解锁集合（由调用方现取，本类不持有库状态）
     */
    fun keep(keepVaultId: String, unlocked: Collection<String>) {
        // ★ 顺序：先撤销自己，再给别人排期。
        //   反过来也能工作（两者集合不重叠），但先把"我要留着"这件事落定，
        //   读代码时不必再推演一遍。
        cancel(keepVaultId)
        unlocked.filter { it != keepVaultId }.forEach(::schedule)
    }

    /** 撤销某库的倒计时（切回它、或它被立即锁定 / 删除时调用）。 */
    fun cancel(vaultId: String) {
        pending.remove(vaultId)?.cancel()
    }

    /** 撤销全部倒计时（全量锁定 / 退出数据库）。 */
    fun cancelAll() {
        pending.keys.toList().forEach(::cancel)
    }

    /**
     * 当前在倒计时的库（**诊断与测试用**）。
     *
     * 故意暴露成只读集合：它不含任何密钥material，只是一串库 id。
     */
    fun pendingIds(): Set<String> = pending.keys.toSet()

    /**
     * 排期一次「[graceMs] 后锁定」。
     *
     * 幂等：同一个库已有活跃倒计时时**不重置计时**。
     * ⚠️ 「不重置」是刻意的 —— 否则用户每点一次别的库，这个库的倒计时就重新开始，
     * 高频操作下会无限续期（等于不锁）。宽限期算的是「离开它之后过了多久」，
     * 不是「最后一次操作距今多久」。
     */
    private fun schedule(vaultId: String) {
        if (pending[vaultId]?.isActive == true) return
        pending[vaultId] = scope.launch {
            delay(graceMs)
            // ⚠️ 先摘掉再执行：`onExpire` 内部若同步触发 `cancel(vaultId)`，
            //   晚摘会 Cancel 掉**当前这个正在跑的协程**（自己取消自己）。
            pending.remove(vaultId)
            onExpire(vaultId)
        }
    }

    companion object {
        /** 宽限期：60 秒（见文件头的取值理由）。 */
        const val DEFAULT_GRACE_MS: Long = 60_000L
    }
}
