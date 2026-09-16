/*
 * Vaultix — data:repository
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.data.repository

import io.vaultix.database.dao.VaultDao
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.domain.PinEnrollOutcome
import io.vaultix.model.VaultKind
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「**一个 PIN 打开多个库**」的配齐编排（2026-09-16 用户诉求）。
 *
 * ## 为什么单独一个类
 *
 * 直接原因是 detekt `TooManyFunctions`：`VaultRepositoryImpl` 与 `SettingsViewModel`
 * 加完这块逻辑后都到了 43（上限 40）。但这不是为了绕门禁 —— 这块逻辑本身就是
 * **编排**（逐个库判断该走哪条登记路径），与"库生命周期"是两件事。
 * 抽出来之后，`VaultRepositoryImpl` 回到"单个动作"的职责，边界更清楚了。
 *
 * ⚠️ **下一个再往里加解锁手段时，同样要提取，而不是继续堆回 `VaultRepositoryImpl`。**
 * （这与 `PinUnlockStore` 抽出去时留的告诫是同一条。）
 *
 * ## 它不自己完成登记 —— 登记动作由调用方注入
 *
 * 本类只负责**决策**（这个库是 KDBX 还是 Bitwarden？该不该报"缺主密码"？），
 * 真正的包裹与落盘通过 [enroll] 回调委托出去。这样：
 * - 不需要反向依赖 `VaultRepositoryImpl`（否则是循环依赖）；
 * - `enrollPinKdbx`（现已内化进本类）里「先真解一次库校验凭据、通过才包裹」的铁律**只有一处实现**，
 *   不会被复制到本类里慢慢漂移。
 *
 * ## 硬约束：为什么必须"一次配齐"，而不是设一次就自动通用
 *
 * 两种库**包进信封的东西本质不同**：
 * - Bitwarden 包的是**会话里的对称密钥**（库正解锁 ⇒ 直接可取，无需任何密码）；
 * - KDBX 包的是**「主密码 + keyfile 字节」** —— KDBX 会话里**根本没有主密码**，
 *   必须由用户当场输入一次。
 *
 * ⇒ 同一 PIN 要覆盖多个库，KDBX 那部分的主密码**早晚要被收集一次**。
 *   最省事的收法就是"设置 PIN 时一次问清"。配齐之后解锁链路一行都不用改：
 *   每个库本来就有自己的信封，输入同一个 PIN 即可各开各的。
 *
 * ## 安全边界不变（每库独立信封 + 独立失败计数）
 *
 * 同一 PIN 值会被**分别**包裹进各库自己的信封（各库密钥不同 ⇒ 密文亦不同）。
 * 因此某个库在别处重设 PIN 只影响它自己；失败计数也按库独立，
 * 一个库输错锁住不会连带锁死其它库。
 */
@Singleton
class PinEnrollmentCoordinator @Inject constructor(
    private val vaultDao: VaultDao,
    private val pinUnlockStore: PinUnlockStore,
    private val preferences: VaultixPreferences,
    private val enrollment: PinEnrollment,
) {

    /** 全部库的 id（供 UI 列出 PIN 覆盖候选，含 KDBX）。 */
    suspend fun candidateVaultIds(): List<String> = vaultDao.observeAll().first().map { it.id }

    /**
     * 把同一个 PIN 配到 [vaultIds]（调用方已按用户勾选过滤）。
     *
     * ⚠️ **只处理传进来的库**，绝不自己遍历全表：库表里有几个库 ≠ 用户想设几个，
     * 静默给没选的库设 PIN 属于**越权改配置**（见 domain 里 `enrollPinForVaults` 的 KDoc）。
     *
     * ⚠️ **逐库独立处理，绝不整体回滚**：某个 KDBX 库主密码打错，不该让已经成功的
     * Bitwarden 登记一起作废 —— 那会逼用户为一次笔误重设全部库。
     * 部分成功是真实状态，如实逐库返回。
     *
     * ## 为什么 KDBX 校验也在这里（2026-09-16 移到本类）
     *
     * KDBX 的登记不是"把字节包起来"就完了 —— 必须先**真的拿这组凭据解一次库**，
     * 通过才落盘（`VaultixKdbxEngine.verify*`）。否则会得到「启用成功、但躺的是错密码」，
     * 用户要到下次解锁才发现，那时已经分不清是 PIN 错还是密码错。
     *
     * 校验动作走 [VaultixKdbxEngine] 的**校验专用入口**（只验不开库），
     * 因此本类不必持有会话、也不必回调 [VaultRepositoryImpl] —— 那正是抽本类时
     * 想避免的反向依赖（会成环）。
     */
    suspend fun enrollForVaults(
        vaultIds: List<String>,
        pin: String,
        masterPassword: String,
    ): Map<String, PinEnrollOutcome> {
        if (vaultIds.isEmpty()) return emptyMap()
        // 位数不对直接全部拒绝：没必要为每个库各跑一遍昂贵校验（KDBX 侧是 Argon2id）。
        pinUnlockStore.validate(pin)?.let { rejected ->
            return vaultIds.associateWith { rejected }
        }
        // ⚠️ 空密码且存在 KDBX 库时，逐个报"缺少主密码"而**不是静默跳过** ——
        //    跳过会让用户以为"配好了"，实际那个库根本没配上（假状态）。
        val hasMaster = masterPassword.isNotBlank()
        return vaultIds.associateWith { vaultId ->
            enrollOne(vaultId, pin, masterPassword, hasMaster)
        }
    }

    /**
     * 单个库的登记。
     *
     * 抽成私有方法而不是写在 `associateWith` 的 lambda 里：那个 lambda 内含 3~4 个分支，
     * 直接叠在 [enrollForVaults] 上会推高它的圈复杂度（本仓库 detekt 上限 14，见 8.6）。
     */
    private suspend fun enrollOne(
        vaultId: String,
        pin: String,
        masterPassword: String,
        hasMasterPassword: Boolean,
    ): PinEnrollOutcome {
        val row = vaultDao.get(vaultId) ?: return PinEnrollOutcome.Failed("本地不存在该库")
        return when (VaultKind.fromName(row.kind)) {
            VaultKind.BITWARDEN -> enrollment.enrollBitwarden(vaultId, pin)
            VaultKind.KDBX -> {
                if (!hasMasterPassword) {
                    // 没收到主密码就无法为 KDBX 组信封（会话里没有它）。
                    // 如实报错让 UI 提示补输，而不是假装成功。
                    PinEnrollOutcome.Failed("需要该库的主密码才能设置 PIN")
                } else {
                    // keyfile URI 与快速解锁登记**同源**，不在这里再问一次。
                    // ⚠️ 取值失败要降级成 null 而不是抛：没配 keyfile 的库是常态。
                    val keyFileUri = runCatching {
                        preferences.kdbxKeyFileUri(vaultId).first()
                    }.getOrNull()
                    // origin = KDBX 库文件本身的 URI（KDBX 库里它就是主键，见 addKdbxVault）。
                    enrollment.enrollKdbx(vaultId, pin, masterPassword, keyFileUri, row.origin)
                }
            }
            // 未知类型（数据损坏 / 未来新增类型）：不猜，如实报错。
            null -> PinEnrollOutcome.Failed("无法识别该库类型")
        }
    }
}
