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

import io.vaultix.data.kdbx.KdbxFileSource
import io.vaultix.database.dao.VaultDao

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
     * ★ **本地 SAF 库没有远端** ⇒ 状态就应该是 [KdbxSyncStatus.LOCAL_ONLY]。
     *
     * ## 为什么需要这条（2026-11-10 晚，真机反馈）
     *
     * 「本地 kdbx → 新建条目 → 点同步」⇒ 界面显示「已同步」。
     * 根因是编排器把本地文件当成了"远端"（`SafKdbxFileSource` 能 stat/read 自己），
     * 走完三态判定后写进了 [KdbxSyncStatus.IN_SYNC]。
     *
     * ⇒ 编排器现在在入口就挡住（不产生任何 IO），并把**已经被写歪的那一行**纠回来。
     *   只挡不纠的话，旧数据会一直在界面上说谎。
     *
     * ⚠️ 这是**唯一的**"把状态写回 LOCAL_ONLY"的入口：别的转移都不该产生它
     *   （"没有远端"是配置事实，不是同步的结果）。
     */
    fun markLocalOnly(): KdbxSyncStatus = KdbxSyncStatus.LOCAL_ONLY

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
     * ★ **刚登记进库表的那一行**该带什么同步状态（2026-11-02 补）。
     *
     * ## 为什么不是 `null`（这是本轮修掉的一个真 bug）
     *
     * `null` 在 `vaults.syncStatus` 上的语义是「**该库不适用网盘同步**」
     * （见 `KdbxSyncStatus` 的类 KDoc 与 `VaultixEntities` 的字段注释）——
     * Bitwarden / 本地 SAF 库才用 null。而一条新登记**网盘**库确实有云端，
     * 只是还一次都没同步过。
     *
     * 更实际的后果在 UI 侧：`VaultListScreen` 有
     * `onSyncNow = if (vault.syncStatus != null)` —— 写成 null 会让新建的网盘库
     * **没有「立即同步」入口**，用户只能退出重进（或等自动同步）才等得到它。
     *
     * ## 为什么落 [PENDING_UPLOAD] 而不是 [IN_SYNC]
     *
     * [IN_SYNC] 是**结论**："本地与远端一致，且基线令牌可用"。刚建/刚加入的库
     * 恰恰两样都没有：`remoteVersionToken` 为空、本机内容还没跟远端对过一次。
     * 标成 IN_SYNC 就是一句没有依据的断言，而且会让 `impliesLocalChanges` 返回 false ——
     * 万一用户在第一次同步前就编辑过，那笔改动会被判成"没改"。
     *
     * [PENDING_UPLOAD] 是**事实**："本机有条件推上去，但还推过"。它同时满足：
     *  - `needsAnotherRound = true` ⇒ 自动上传会来跑一次（跑的结果可能是
     *    `AlreadyInSync` / `Uploaded` / 冲突，三种都由编排器自己判）。
     *    ⚠️ 首次同步时 `remoteVersionToken` 为空 ⇒ `remoteChanged` 为真，
     *    而 `localChanged` 也为真 ⇒ 会判成**冲突**。这是**正确**的结局而不是误报：
     *    远端确实有一份我们没看过的内容，必须由人拍板（绝不能让"条件是空的"
     *    变成"允许覆盖"）；
     *  - `impliesLocalChanges = true` ⇒ 即使首次同步前用户改了东西也不会被漏推。
     *
     * @param hasCloudSource 该 origin 是不是**网盘**来源。本地 SAF / Bitwarden 一律 `null`
     *   （"不适用"），否则 UI 会在一个根本没有云端的库上渲染「待上传」角标。
     */
    fun forNewlyRegisteredVault(hasCloudSource: Boolean): KdbxSyncStatus? =
        if (hasCloudSource) KdbxSyncStatus.PENDING_UPLOAD else null

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

/**
 * 把一个**刚接入**的 KDBX 网盘库（新添加 / 新建）记录成三种结局之一。
 *
 * ## 为什么需要它（#242，2026-11-03 真机实测）
 *
 * 旧行为是：新登记 ⇒ `PENDING_UPLOAD` + **基线令牌留空**。而编排器的三态判据是
 * `remoteChanged = (远端令牌 != 基线令牌)` —— 基线为空时**恒为真**，于是：
 *
 * ```
 * 添加 OneDrive KDBX → 状态 PENDING_UPLOAD（impliesLocalChanges = true）
 *                   → 基线 null ⇒ remoteChanged = true
 *                   → isConflict(true, true) = true ⇒ **每次都报冲突**
 * ```
 *
 * 用户看到的就是「打开 OneDrive 上的 kdbx 就是冲突」——
 * 而实际上他**一个字节都没改过**。这不是"正确但严格"，是**基线错了**：
 * 「用户刚确认过的这一版远端内容」就该是基线。
 *
 * ## 三种结局（由**远端事实**决定，不由愿望决定）
 *
 * ## 判据：令牌优先，令牌拿不到才用内容对账
 *
 * 「远端那一版的字节，与本机刚接入的这份是否同源」——用的是与编排器
 * 已有的内容对账（`adoptRemoteIfSameContent`）**同一类**证据，不是新造一套：
 *
 * | stat 令牌 | 本地有可比字节 | 内容 | 结局 |
 * |---|---|---|---|
 * | 有 | — | 不比 | [AdoptBaselineOutcome.Token]，状态 `IN_SYNC` |
 * | 无 | 无 | 不知道 | [AdoptBaselineOutcome.Unknown]（**保持**调用方的保守默认值） |
 * | 无 | 有 | 一致 | [AdoptBaselineOutcome.Token]（令牌留空、状态 `IN_SYNC`） |
 * | 无 | 有 | 不同 / 读不出来 | ★ [AdoptBaselineOutcome.RemoteIsNewer]，`REMOTE_CHANGED` | *
 * ★ 第四行是**数据安全的分界**。添加时用户输的那次主密码，开的可能只是
 * **远端当前文件**（典型：从网盘添加、本机还没有缓存），而那种"打开"在引擎侧
 * 根本不会写入本地缓存 ⇒ 本机没有一段可以称作"我这一版"的字节。
 * 此时若写成 `LOCAL_ONLY` / `PENDING_UPLOAD`，自动上传会拿**别的库 / 旧缓存**去条件写，
 * 远端那份被静默覆盖。⇒ 必须 `REMOTE_CHANGED`：只读、不推，等一次正常拉取对齐。
 *
 * [AdoptBaselineOutcome.Unknown] 与 [AdoptBaselineOutcome.RemoteIsNewer] 是同一个
 * 保守原则的两面：「读不到」不许升级成「已经对齐」（那是静默覆盖的入口）；
 * 「读到了不一样」更不许升级。唯一允许写 `IN_SYNC` 的情形是
 * 「令牌拿到了」或「字节比过了且一致」。
 *
 * ⚠️ **本地 SAF 库不适用**：它的令牌是内容 SHA-256，`stat` 恒等于本地文件本身，
 * 补基线没有意义（也没有"远端"可对齐），调用方应跳过。
 *
 * @param localBytes 本机刚接入的那份字节。**必须是"用户这次真正打开的那一份"**，
 *   不能随手拿缓存里的字节充数（缓存可能属于同一库的旧版本）。传 `null` 表示
 *   "本机没有可信的本地版本"，那就只剩 stat 一条路（多数情况够用）。
 * @param contentMatchesRemote ★ 内容对账的具体实现，由调用方注入（见 `localBytes`）。
 *   默认实现一律返回 `false` —— **fail-closed**：没有对账能力时宁可少写一个
 *   `IN_SYNC`，也不能因为没有异议就推定一致（那正是静默覆盖的入口）。
 *   生产的唯一实现是 `Kdbx.adoptBaselineIfMatches`（在 `data:repository` 里装配）。
 * @return 实际写下的结局。
 */
suspend fun KdbxSyncTransitions.adoptRemoteBaseline(
    vaultId: String,
    source: KdbxFileSource,
    vaultDao: VaultDao,
    localBytes: ByteArray? = null,
    contentMatchesRemote: suspend (vaultId: String, localBytes: ByteArray) -> Boolean =
        { _, _ -> false },
): AdoptBaselineOutcome {
    // ① 有令牌 ⇒ 这就是"远端当前是哪一版"的直接答案，不需要下载整份来比。
    val remoteVersion = runCatching { source.stat().versionToken }.getOrNull()
    if (!remoteVersion.isNullOrBlank()) {
        vaultDao.updateSyncState(
            id = vaultId,
            status = KdbxSyncStatus.IN_SYNC.name,
            versionToken = remoteVersion,
            syncedAt = System.currentTimeMillis(),
        )
        return AdoptBaselineOutcome.Token
    }

    // ② 没令牌 ⇒ 只能用内容对账。没有任何一侧的内容可比 ⇒ 不许断言对齐。
    if (localBytes == null) return AdoptBaselineOutcome.Unknown
    val remoteIsSame = runCatching { contentMatchesRemote(vaultId, localBytes) }.getOrDefault(false)
    if (!remoteIsSame) {
        // ★ 远端是另一版（或读不出来）⇒ 只读状态：本地内容**不上传**。
        val previous = vaultDao.get(vaultId)
        vaultDao.updateSyncState(
            id = vaultId,
            status = KdbxSyncStatus.REMOTE_CHANGED.name,
            // ⚠️ 令牌原样带回：DAO 是 `SET col = :v`，这里没有新令牌可写，
            //    传 null 会抹掉可能存在的旧基线（虽然此刻它通常是空的）。
            versionToken = previous?.remoteVersionToken,
            syncedAt = previous?.lastSyncedAt,
        )
        return AdoptBaselineOutcome.RemoteIsNewer
    }

    // ③ 内容一致但令牌拿不到 ⇒ 状态可以是 IN_SYNC（内容确实是同一版），基线留空。
    //    代价是**下一次同步**会判一次 `remoteChanged = true`（null != 令牌）⇒
    //    走一次 `REMOTE_CHANGED` 分支，由编排器的内容对账降级回 `IN_SYNC`
    //    并顺手补上令牌 —— 那一次是**只读**的，不会覆盖任何东西。
    val previousToken = vaultDao.get(vaultId)?.remoteVersionToken
    vaultDao.updateSyncState(
        id = vaultId,
        status = KdbxSyncStatus.IN_SYNC.name,
        versionToken = previousToken,
        syncedAt = System.currentTimeMillis(),
    )
    return AdoptBaselineOutcome.Token
}

/** [adoptRemoteBaseline] 的结局。 */
enum class AdoptBaselineOutcome {
    /** 已经与远端对齐（令牌拿到，或内容比过一致）⇒ 状态 `IN_SYNC`。 */
    Token,

    /** 远端是**另一版** ⇒ 状态 `REMOTE_CHANGED`（只读，绝不自动上传）。 */
    RemoteIsNewer,

    /** 什么都判不出来（离线 / 凭据过期 / 本机无内容）⇒ 状态保持调用方的保守默认值。 */
    Unknown,
}
