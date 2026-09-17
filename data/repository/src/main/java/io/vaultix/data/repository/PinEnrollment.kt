/*
 * Vaultix — data:repository
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.data.repository

import io.vaultix.data.kdbx.Kdbx
import io.vaultix.data.repository.kdbx.KdbxFileSourceResolver
import io.vaultix.domain.PinEnrollOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「应用内 PIN」登记的**落盘动作**（2026-09-16 从 [VaultRepositoryImpl] 抽出）。
 *
 * ## 为什么单独一个类
 *
 * [VaultRepositoryImpl] 在加完「一个 PIN 打开多个库」后到了 43 个函数，
 * 触到本仓库 detekt `TooManyFunctions` 的 40 上限（Docs/16）。
 * 但更实际的理由是**职责**：本仓库先前已把 PIN 的**存储**（[PinUnlockStore]）与
 * PIN 的**编排**（[PinEnrollmentCoordinator]）分了出去，剩下的这一小块——
 * 「这一个库到底往信封里包什么字节」——是第三个独立关注点，本该一起走。
 *
 * ⚠️ **下一个再往里加解锁手段时，同样要提取，而不是继续堆回 [VaultRepositoryImpl]。**
 * （同 [PinUnlockStore] / [PinEnrollmentCoordinator] 抽出去时留的告诫。）
 *
 * ## 三种东西，三处放（改这块之前先读这张表）
 *
 * | 关注点 | 住在哪 |
 * | --- | --- |
 * | 信封怎么存、怎么开、失败计数 | [PinUnlockStore] |
 * | 给哪些库设、缺主密码怎么报 | [PinEnrollmentCoordinator] |
 * | 包什么明文进信封（本类） | 会话密钥 / 主密码 + keyfile |
 */
@Singleton
class PinEnrollment @Inject constructor(
    private val pinUnlockStore: PinUnlockStore,
    /**
     * 内存里的对称密钥（Bitwarden 侧要包的就是它）。
     *
     * ⚠️ 只依赖会话管理器，**不依赖 [VaultRepositoryImpl]** —— 反过来会让两者成环。
     * 这也是 KDBX 校验走 [Kdbx.verify] 独立入口而不是复用仓储 `unlockKdbxInternal` 的原因。
     */
    private val sessions: VaultSessionManager,
    /**
     * 「origin ⇒ 文件来源」的解析。
     *
     * ⚠️ 与 [LocalUnlockEnrollment] / [VaultRepositoryImpl] 共用**同一张**判别表
     * （见 [KdbxFileSourceResolver] 的 KDoc）。本类此前自带一个只认 SAF `content://`
     * 的 `KdbxSource` lambda ⇒ 网盘库在这里必然"凭据无效"，而真因是文件读不到。
     */
    private val kdbxFileSources: KdbxFileSourceResolver,
) {

    /**
     * 读 keyfile 的原始字节。
     *
     * ⚠️ 只包 URI 不行：授权可能失效、用户可能换过文件。而且 KDBX 的信封里躺的
     * 必须是**字节**（解锁时没有 URI 可读，见 `Kdbx.unlock` 的 `keyFileBytes` 参数）。
     */
    private suspend fun readKeyFile(keyFileUri: String?): ByteArray? =
        kdbxFileSources.readBytes(keyFileUri)

    /**
     * Bitwarden：把**当前会话里的对称密钥**包进 PIN 信封。
     *
     * 之所以当场不需要任何密码：对称密钥已经在会话里（用户是拿主密码解锁进来的），
     * 直接包裹即可。这也意味着**库未解锁时无从包裹** ⇒ 返回 `SessionUnavailable`，
     * UI 据此提示"先用主密码打开它"，而不是报一句无从下手的"失败"。
     */
    suspend fun enrollBitwarden(vaultId: String, pin: String): PinEnrollOutcome =
        withContext(Dispatchers.IO) {
            val key = sessions.keyOf(vaultId)
                ?: return@withContext PinEnrollOutcome.SessionUnavailable
            wrap(vaultId, pin, buildFullKey(key))
        }

    /**
     * KDBX：把「主密码 + keyfile 字节」包进 PIN 信封。
     *
     * ## 为什么必须"先校验、后包裹"（本类最重要的一条）
     *
     * `PinKeyWrapper.wrap` 只负责**封字节**、不管字节对不对。先包后校会得到
     * 「启用成功、但躺的是错密码」—— 用户要到下次解锁才看到「PIN 对了却打不开库」，
     * 那时已经无从判断是 PIN 错还是密码错。故此处**必须先真解一次库**。
     *
     * ## 为什么这里**可以**当场 wrap（与生物识别路径不同）
     *
     * PIN 的保护器是 `PinKeyWrapper` + `SecureCredentialStore` 的硬件外层密钥，
     * **不需要系统认证** ⇒ 不存在「cipher 还没被授权」的问题。生物识别那侧的 KEK 是
     * auth-per-use，所以必须等 BiometricPrompt 之后再 wrap（2026-09-14 的闪退根因）。
     *
     * ## 与「一个 PIN 打开多个库」的关系
     *
     * KDBX 的会话里**没有**主密码（开库用的是派生密钥），所以要包就得现在问。
     * 这正是「一个 PIN 打开多个库」必须**一次配齐**的根本原因；也正因为包进去的
     * 确实是能开库的东西，配齐之后**解锁链路一行都不用改**。
     *
     * ## 为什么 keyfile 读不出来时**拒绝**而不是降级成"仅主密码"
     *
     * keyfile 不是可选装饰：用它开库时，少它一个字节就是**开不了**。若在这里静默
     * 降级，信封里会躺一组"永远解不开这个库"的凭据 —— 用户拿 PIN 解锁只会看到
     * 「PIN 不对」，而真正的原因（keyfile 读不到）无从得知。
     * ⇒ 让 [Kdbx.verify] 拿同样的 keyfile 去验，读不到自然验不过，如实报错。
     *
     * @param keyFileUri 该库的 keyfile URI（null / 空 = 只用主密码）。
     *   ⚠️ 由调用方给出：URI 存在偏好里，本类不碰偏好。
     * @param originUri KDBX 库文件本身的 URI（KDBX 库里它就是主键）。
     *   null 视为库不存在 —— 宁可报错也不猜一个路径去读。
     */
    suspend fun enrollKdbx(
        vaultId: String,
        pin: String,
        masterPassword: String,
        keyFileUri: String?,
        originUri: String?,
    ): PinEnrollOutcome = withContext(Dispatchers.IO) {
        val origin = originUri
            ?: return@withContext PinEnrollOutcome.Failed("本地不存在该库")
        val source = kdbxFileSources.fileSourceFor(origin)
            ?: return@withContext PinEnrollOutcome.Failed("这个库还没有可用的文件来源")

        // ★ keyfile **只读一次**，校验与包裹用的是同一份字节。
        //   原先分两处各读一次（`verify` 里一次、`encode` 前一次）⇒ 两次之间用户
        //   若换了 keyfile，"验过的"和"包进去的"就不是同一份 —— 得到的正是本类
        //   最想避免的那种信封：**看起来配好了，实际打不开**。
        val keyFileBytes = readKeyFile(keyFileUri)

        // ★ 先校验、后包裹。校验走 `Kdbx.verify`（**只验不开库**，不碰会话），
        //   理由见 `Kdbx.verify` 的 KDoc：真开库会把明文拉进内存、并覆盖已有会话。
        val verified = Kdbx.verify(
            source = source,
            password = masterPassword,
            keyFileBytes = keyFileBytes,
        )
        if (!verified) return@withContext PinEnrollOutcome.InvalidCredentials

        wrap(vaultId, pin, KdbxUnlockPayload.encode(masterPassword, keyFileBytes))
    }

    /**
     * 位数校验 + 落盘（两条路径共用的尾部，避免校验规则写两遍而漂移）。
     *
     * ⚠️ 校验必须先于 [PinUnlockStore.persist]：位数都不对的 PIN 没有任何理由
     * 被包成一个"看起来能用"的信封（`persist` 内部会置开关，包了就真启用了）。
     */
    private suspend fun wrap(vaultId: String, pin: String, payload: ByteArray): PinEnrollOutcome {
        val rejected = pinUnlockStore.validate(pin)
        if (rejected != null) {
            // 拒绝就别留着明文在内存里等 GC。
            payload.fill(0)
            return rejected
        }
        // ⚠️ persist 会接管并清零这份明文（所有权转移），这里不再持有它。
        pinUnlockStore.persist(vaultId, pin, payload)
        return PinEnrollOutcome.Enrolled
    }
}
