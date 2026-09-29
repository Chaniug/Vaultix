/*
 * Vaultix — domain
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * **房间信封重包**的领域入口（2026-09-29，批次 4，定稿 §6 目标 3）。
 *
 * ## 为什么不并进 `VaultRepository`
 *
 * 同 `UnlockRecoveryRepository` / `KdbxSyncRepository`：`VaultRepositoryImpl` 已
 * **正好 40 个函数**（detekt `TooManyFunctions` 硬上限）。语义上也分开 ——
 * 本接口处理的是「某库换了主密码之后，把它的房间信封重新包一遍」这一件窄事。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.domain

/**
 * 房间信封重包（实现见 `data:repository` 的 `RoomResealRepositoryImpl`）。
 *
 * ## 它解决的问题
 *
 * 房间信封里躺着的是**该库的解锁凭据**（KDBX = 主密码 + keyfile 派生 payload，
 * Bitwarden = 会话 full key）。用户在别处改了 KDBX 主密码之后，信封里的旧密码
 * 就失效了 —— 表现为 `RoomUnlockOutcome.StaleCredentials`（"主密码已过时"）。
 *
 * 旧行为到这一步就结束了：UI 让用户**重新输一次主密码**、当次能开，但**信封没换**，
 * 下次快速解锁又 Stale，用户每周都要重输一遍。
 *
 * 本接口补上缺的另一半：**用户输的新密码顺手把该库的房间信封重包掉**。
 *
 * ## 只管房间，不碰门锁（关键边界）
 *
 * 重包**只**写 `house_room::<vaultId>` 那一个键，用的是内存里那把**没变过的**
 * 房钥匙（`HouseKeyStore.sealRoom`）。**不碰指纹信封、不碰 PIN 信封、不需要任何
 * 系统认证** —— 门锁包的房钥匙本身没有失效，凭什么要用户再过一次指纹？
 *
 * ## 前置：房钥匙必须在内存
 *
 * 这是自明的：没有房钥匙就没有封装用的密钥。而这条前置**自动成立** ——
 * 调用本接口的场景必然已经过了门锁（`openRoom` 在无钥匙时返回 `NoKey` 而非
 * `Damaged`，用户根本走不到「输新主密码」那一步）。实现里仍然如实报
 * [RoomResealOutcome.HouseKeyUnavailable] 而不是抛异常：编排出错时给一句人话，
 * 好过给一个崩溃。
 */
interface RoomResealRepository {

    /**
     * 用新的解锁凭据重包某库的房间信封（覆盖旧信封）。
     *
     * 内部会**先校验后包裹**（复用 `LocalUnlockEnrollment` 的同一条纪律）：
     * KDBX 先 `Kdbx.verify` 真验一次，密码不对就报
     * [RoomResealOutcome.InvalidCredentials] —— 绝不把「启用成功、但躺的是错密码」
     * 这种假成功写进盘里（那会让用户的快速解锁从此永久失败且看不出原因）。
     *
     * @param vaultId 目标库。
     * @param newMasterPassword KDBX 的新主密码。Bitwarden 库忽略它
     *   （房间信封包的是内存会话密钥，派生自当前会话，与用户输入的密码无关）。
     * @return 逐情况的结论，见 [RoomResealOutcome]。
     */
    suspend fun resealRoom(vaultId: String, newMasterPassword: String): RoomResealOutcome
}

/** 一次房间信封重包的结论（四态：「空有多态」纪律 —— 每种状态用户动作都不同）。 */
sealed interface RoomResealOutcome {
    /** 新信封已落盘，此后快速解锁用新的凭据即可打开该库。 */
    data object Resealed : RoomResealOutcome

    /**
     * 新密码**验不过**（用户输错了）。
     *
     * ⚠️ 与 [Failed] 分开：这是用户可自纠的，UI 该让他重输而不是报错。
     */
    data object InvalidCredentials : RoomResealOutcome

    /**
     * 房钥匙不在内存（不该发生，见接口 KDoc 的「自动成立」）。
     *
     * 走到这里说明上游编排有 bug —— 如实上报，不静默假装成功。
     */
    data object HouseKeyUnavailable : RoomResealOutcome

    /** 其它失败（库不存在 / 库文件读不到 / 类型不识别），附可读原因。 */
    data class Failed(val detail: String) : RoomResealOutcome
}
