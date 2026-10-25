/*
 * Vaultix — app:autofill · save
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.autofill.save

import android.os.SystemClock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** 一次「待用户确认保存」的凭据（只在内存里）。 */
data class PendingSave(
    val username: String,
    val password: String,
    val packageName: String?,
    val webDomain: String?,
    val stagedAtElapsedMs: Long,
)

/**
 * 自动填充「保存」的**明文暂存**（`.ai/ISSUES.md` #187，2026-10-26）。
 *
 * ## 它替换掉了什么（以及为什么必须换）
 *
 * 原实现把**明文密码**放进 Intent extras：
 * `VaultixAutofillService` → `AutofillSaveIntents.create(...)`
 * → `AutofillSaveActivity.getIntent()` → `SavedStateHandle`（`AutofillSaveViewModel`）。
 *
 * 这条链路上每一个环节都会**把 extras 落盘**：
 * - Activity 启动意图会被系统记进 `IntentSender` / 最近任务快照；
 * - `ComponentActivity` 在 `onSaveInstanceState` 时把整个 `SavedStateHandle` 序列化进
 *   `savedInstanceState` → 系统进程的 `/data/system_ce/<user>/recent_*` 与
 *   `snapshot` 目录都能翻到。即使本应用 `allowBackup=false`、`FLAG_SECURE` 全开，
 *   也管不到系统侧那份快照。
 *
 * 结论：**明文密码不能出现在任何会被序列化的容器里**（Intent / Bundle / SavedStateHandle /
 * DataStore）。所以改成「服务侧留底、Activity 只拿一张一次性票据」。
 *
 * ## 生命周期与边界
 *
 * - 单条暂存（新的保存请求覆盖旧的）：同一时刻只会有一次登录提交；
 * - [TTL_MS] 硬上限，超时即丢弃 —— 用户点了保存又晾着不管，不该永远留着一份明文；
 * - [consume] 是**一次性**的：取走即清空。重复取到的是 null（界面显示「信息已过期」），
 *   而不是把密码多留一份；
 * - 进程被杀 ⇒ 暂存自然消失（纯内存）⇒ 用户需要重新提交一次表单。
 *   这是**有意**的取舍：为了实现"跨进程恢复"而把明文写盘，正是本条要修掉的东西。
 *
 * 为什么不复用 [io.vaultix.vaultix.autofill.PendingFillStore]：后者存的是**解析结果**
 * （字段 id，不是值），语义与 TTL 都不同（那个是 120 秒的"解锁回填窗口"）。
 * 混在一起会让"这里到底有没有明文"变得看代码才能确认 —— 安全相关的状态越少越模糊越好。
 */
@Singleton
class PendingSaveStore @Inject constructor() {

    private class Slot(val id: String, val save: PendingSave)

    @Volatile
    private var slot: Slot? = null

    /**
     * 暂存一次待确认的保存，返回**一次性票据**（放进 Intent 的只有它）。
     *
     * 用随机 UUID 而不是自增序号：自增号可被猜到，虽然本进程不校验来源，
     * 但"猜不到的随机串"是这类一次性凭证的默认正确形状，没有成本。
     */
    fun stage(
        username: String,
        password: String,
        packageName: String?,
        webDomain: String?,
    ): String {
        val id = UUID.randomUUID().toString()
        slot = Slot(
            id = id,
            save = PendingSave(
                username = username,
                password = password,
                packageName = packageName,
                webDomain = webDomain,
                stagedAtElapsedMs = SystemClock.elapsedRealtime(),
            ),
        )
        return id
    }

    /** 按票据取走暂存（**一次性**：取到即清空；票据不匹配 / 过期均返回 null）。 */
    fun consume(id: String?): PendingSave? {
        val current = slot ?: return null
        if (id == null || current.id != id) return null
        slot = null
        val age = SystemClock.elapsedRealtime() - current.save.stagedAtElapsedMs
        return if (age > TTL_MS) null else current.save
    }

    /** 主动清空（保存流程结束 / 用户取消）。幂等。 */
    fun clear() {
        slot = null
    }

    private companion object {
        /**
         * 暂存有效期，60 秒。
         *
         * 依据：从「点系统保存提示」到「保存界面出现在前台」通常是**亚秒级**；
         * 60 秒足够覆盖"用户正在打电话 / 手动切回桌面再切回来"，
         * 又不至于让一份明文长期驻留内存。
         * 超时后的体验是"提示信息已过期，请重新登录一次"——比重放一份旧密码好。
         */
        const val TTL_MS = 60_000L
    }
}
