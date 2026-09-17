/*
 * Vaultix — domain
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * **KDBX 网盘同步**的领域入口（方案 §11 批次 5）。
 *
 * ## 为什么不并进 `VaultRepository`
 *
 * 🔴 `VaultRepositoryImpl` 已经**正好 40 个函数**（detekt `TooManyFunctions` 硬上限，
 * 见 `config/detekt/detekt.yml` 的 `allowedFunctionsPerClass: 40`）。
 * 往里加任何一个方法都会让门禁变红 —— 而"把上限调高"是**最差**的处理：
 * 那个数是从 Docs/16 来的架构约定，不是随手定的。
 *
 * 除了条数，语义上也该分开：
 * - `VaultRepository` 管的是**库的生命周期**（添加 / 解锁 / 锁定 / 移除）；
 * - 本接口管的是**一个库的内容与远端之间的差异怎么收敛**。
 *
 * Bitwarden 那边的对应物是 `syncVault` —— 它的语义（服务端 revision 仲裁）
 * 与 KDBX 的（条件写 + 用户拍板）**根本不同**，混在一个方法里只会让两边都变形。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.domain

import kotlinx.coroutines.flow.Flow

/**
 * KDBX 网盘同步（OneDrive / WebDAV）。
 *
 * 实现见 `data:repository` 的 `KdbxSyncRepositoryImpl`。
 */
interface KdbxSyncRepository {

    /**
     * 跑一次同步（本地 ⇄ 远端），返回**可区分的结果**而不是布尔。
     *
     * @param localChangedSinceLastSync 自上次同步以来**本地有没有改动**。
     *   由调用方提供 —— 实现层**不去猜**（猜错的两个方向都糟：猜"没改"会漏推
     *   用户的编辑，猜"改了"会无谓地报冲突）。
     */
    suspend fun sync(vaultId: String, localChangedSinceLastSync: Boolean): KdbxSyncReport

    /**
     * 冲突时用户拍板「**用远端覆盖本地**」。
     *
     * 语义：把远端当前版本拉下来、**替换本地会话**，本地未上传的改动被丢弃。
     * ⚠️ UI 必须先弹确认（这是数据丢失点之一）。
     */
    suspend fun resolveUsingRemote(vaultId: String): KdbxSyncReport

    /**
     * 冲突时用户拍板「**用本地覆盖远端**」（强写，绕过条件写）。
     *
     * ⚠️ 这是**唯一**会绕过条件检查的写入路径：远端这一刻的内容会被永久丢弃。
     */
    suspend fun resolveUsingLocal(vaultId: String): KdbxSyncReport

    /**
     * 冲突时用户拍板「**稍后再决定**」：状态落回 [KdbxSyncReport.Conflict]，
     * 不做任何 IO。
     *
     * ⚠️ 它**必须**是个真方法而不是"UI 自己关对话框"：不清掉中间态的话，
     * 界面上会留下一个永远转不完的"同步中…"。
     */
    suspend fun defer(vaultId: String)

    /** 该库是否配置了可用的网盘来源；false = UI 不显示同步入口。 */
    fun cloudSyncAvailable(vaultId: String): Flow<Boolean>

    /**
     * 上报「上传期间本地又改了」。
     *
     * ⚠️ 方案 §9 点名的**最容易漏的竞态**：用户在同步进行中继续编辑。
     * 漏掉它 ⇒ 那笔改动**永远推不上去**（状态已记成"已同步"，不会重试）。
     */
    suspend fun notifyLocalChangedDuringUpload(vaultId: String)
}
