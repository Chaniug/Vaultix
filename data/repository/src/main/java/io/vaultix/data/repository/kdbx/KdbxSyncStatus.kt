/*
 * Vaultix — data:repository
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * **KDBX 网盘同步状态机** —— 方案 §9 的落地。词汇表照用 Bastion 的，
 * 因为那套名字已经过实战检验（尤其是 [PENDING_UPLOAD_WITH_LOCAL_CHANGES]）。
 *
 * ## 为什么状态要显式建模（而不是"成功/失败"两个布尔）
 *
 * 同步有**多种"看起来成功但其实没完"**的中间态，不建模就会静默丢失改动：
 *
 * - 「已上传，但上传期间本地又改了」——如果只记"已同步"，那笔改动会**永远留在本地**，
 *   用户以为同步好了，换台设备却看不到（这是最容易漏的竞态）。
 * - 「远端变了」——不是错误，是**需要用户拍板**的状态；混进 FAILED 会让 UI
 *   提示"同步失败，请重试"，而重试**永远失败**。
 *
 * ## 与 `VaultEntity.syncStatus` 的关系
 *
 * 这个枚举的 `name` 直接落进 `vaults.syncStatus` 列（可空）。
 * null = 该库不适用网盘同步（Bitwarden / 本地 SAF）——
 * **不要**给它们填一个 `LOCAL_ONLY`，那会在 UI 上凭空造出一个角标。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.repository.kdbx

/** KDBX 网盘库的同步状态。 */
enum class KdbxSyncStatus {
    /**
     * 只存在本地，还没有网盘来源（或网盘来源还没配好）。
     *
     * ⚠️ 与"远端还没建文件"不是一回事 —— 后者是 [PENDING_UPLOAD]。
     */
    LOCAL_ONLY,

    /** 本地与远端一致（最后一次同步成功，且之后双方都没改）。 */
    IN_SYNC,

    /** 正在同步（拉或推进行中）。 */
    SYNCING,

    /** 本地有改动，还没推上去。 */
    PENDING_UPLOAD,

    /** 远端有改动，本地还没拉。 */
    REMOTE_CHANGED,

    /**
     * ★ **已经上传，但上传期间本地又改了** —— 必须再跑一轮。
     *
     * ## 为什么单独一个状态（这是最容易漏的竞态）
     *
     * 流程：读本地 → 编码 → 上传（耗时）→ 期间用户又改了本地 → 上传成功。
     * 如果这时直接标成 [IN_SYNC]，那笔"上传期间产生的改动"就**永远推不上去**：
     * 状态说已同步，下次也不会再推它，换台设备看到的是旧版。
     * ⇒ 上传完成后**再检查一次本地版本**，变了就落到本状态，由下一轮继续推。
     */
    PENDING_UPLOAD_WITH_LOCAL_CHANGES,

    /** 两边都改了 —— 需要用户拍板（见 `KdbxWriteFailure.Conflict`）。 */
    CONFLICT,

    /** 上次同步失败（网络 / 凭据 / IO）。可重试。 */
    FAILED;

    /**
     * 是否"有待办事项"（UI 据此决定要不要给用户提示）。
     *
     * ⚠️ [CONFLICT] 与 [FAILED] 都算 —— 它们都需要用户看一眼。
     * 但 [SYNCING] 不算（那是正常的进行中）。
     */
    val needsAttention: Boolean
        get() = this == CONFLICT || this == FAILED || this == PENDING_UPLOAD_WITH_LOCAL_CHANGES

    /**
     * 是否"还需要再跑一轮同步"。
     *
     * ⚠️ 把 [PENDING_UPLOAD_WITH_LOCAL_CHANGES] 也算进来是关键：
     * 它**必须**触发下一轮（见该状态的 KDoc）。
     */
    val needsAnotherRound: Boolean
        get() = needsAttention || this == PENDING_UPLOAD || this == REMOTE_CHANGED
}

/**
 * 同步状态的**转移函数**（照方案 §9 的 `markXxx` 一族）。
 *
 * ## 为什么把转移集中在一个对象里
 *
 * 状态机最容易出的问题是"某个调用点直接把状态写成某个值"，绕过了转移规则
 * （比如忘了在"上传成功"时检查本地是否又变了 ⇒ 落到 [KdbxSyncStatus.IN_SYNC] 而不是
 * [KdbxSyncStatus.PENDING_UPLOAD_WITH_LOCAL_CHANGES]）。
 * 集中在这里意味着**规则只有一处**，调用点没有"自己决定"的机会。
 *
 * ⚠️ 这些都是**纯函数**（输入当前状态 + 事实 → 输出新状态），
 * 不碰数据库、不碰网络 ⇒ 可以用单测把每种转移都覆盖到。
 */
object KdbxSyncTransitions {

    /** 本地改了但还没推。 */
    fun markLocalChanges(): KdbxSyncStatus = KdbxSyncStatus.PENDING_UPLOAD

    /** 远端变了但还没拉。 */
    fun markRemoteChanges(): KdbxSyncStatus = KdbxSyncStatus.REMOTE_CHANGED

    /** 开始同步（拉或推）。 */
    fun markSyncing(): KdbxSyncStatus = KdbxSyncStatus.SYNCING

    /**
     * ★ 上传成功之后该落到哪个状态。
     *
     * @param localChangedDuringUpload **上传期间本地是否又改了**。
     *
     * 这是本对象**最重要的一个转移**：把"上传期间又改了"从"看起来成功"里
     * 区分出来（见 [KdbxSyncStatus.PENDING_UPLOAD_WITH_LOCAL_CHANGES] 的说明）。
     * 漏掉这个判断的表现是：用户改了、看到"同步成功"、换设备发现改动不在 ——
     * 而且是**永远不在**（状态已是 IN_SYNC，不会重试）。
     */
    fun markUploaded(localChangedDuringUpload: Boolean): KdbxSyncStatus =
        if (localChangedDuringUpload) {
            KdbxSyncStatus.PENDING_UPLOAD_WITH_LOCAL_CHANGES
        } else {
            KdbxSyncStatus.IN_SYNC
        }

    /** 下载成功之后：本地已被远端内容替换 ⇒ 一致。 */
    fun markDownloaded(): KdbxSyncStatus = KdbxSyncStatus.IN_SYNC

    /**
     * 两边都改了 ⇒ 冲突，需要用户拍板。
     *
     * ⚠️ 与 [markSyncFailure] 分开：冲突**不是失败**，重试解决不了它。
     */
    fun markConflict(): KdbxSyncStatus = KdbxSyncStatus.CONFLICT

    /** 同步失败（网络 / 凭据 / IO）。可重试。 */
    fun markSyncFailure(): KdbxSyncStatus = KdbxSyncStatus.FAILED

    /**
     * 该不该**拒写并报冲突**？
     *
     * 判据是方案 §8 方案 B 的三态表：**远端变了 且 本地也变了** ⇒ 冲突。
     *
     * | 本地改了 | 远端改了 | 动作 |
     * |---|---|---|
     * | 否 | 否 | 什么都不用做 |
     * | 否 | 是 | 拉（远端优先，本地没东西可丢） |
     * | 是 | 否 | 推（本地优先，远端没东西可丢） |
     * | **是** | **是** | ★ **冲突：不静默覆盖，让用户决定** |
     */
    fun isConflict(localChanged: Boolean, remoteChanged: Boolean): Boolean =
        localChanged && remoteChanged
}
