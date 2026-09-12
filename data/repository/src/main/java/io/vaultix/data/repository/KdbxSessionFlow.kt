/*
 * Vaultix — data:repository
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * KDBX 会话变化的**可观察桥**。
 *
 * ## 为什么需要它
 * `data:kdbx` 的会话持有者刻意**不暴露 Flow**：它握着整库明文，是纯内存结构，
 * 给它挂 `MutableStateFlow` 会把「明文」与「可观察状态」耦合在一起（一个
 * `println(state)` 就可能把明文写进日志）。但上层确实需要知道「KDBX 库解锁了 / 锁了」：
 * - `VaultRepositoryImpl` 要把 KDBX 会话合并进「已解锁库」集合与库摘要；
 * - `ItemRepositoryImpl` 的读路径分流要靠它重新读一次会话拿条目。
 *
 * 于是把「变化通知」单独抽成本类：**只携带一个代次计数，不携带任何库内容**。
 * 订阅方拿到新代次后自己去 `Kdbx.contentOf/unlockedIds` 取数。
 *
 * ## 纪律
 * 任何**改变 KDBX 会话集合**的动作（解锁 / 锁定 / 切换活跃库时锁旧库 / 移除库 /
 * 退出数据库 / 全量锁定）都必须调一次 [bump] —— 漏一处就会出现「解锁了但列表还是空的」
 * 这类只在特定路径复现的问题。
 */
@Singleton
class KdbxSessionFlow @Inject constructor() {

    private val revision = MutableStateFlow(0)

    /** 会话集合每变一次自增一次（初始 0；UI 不该依赖具体数值，只依赖「变了」）。 */
    val revisionFlow: Flow<Int> = revision.asStateFlow()

    /** 供 `combine` 用的轻量信号（值本身无意义，仅表示「KDBX 会话集合可能变了」）。 */
    fun asSignal(): Flow<Unit> = revision.map { }

    /** 会话集合发生变化（解锁 / 锁定 / 移除 / 切换库）。 */
    fun bump() {
        revision.update { it + 1 }
    }
}
