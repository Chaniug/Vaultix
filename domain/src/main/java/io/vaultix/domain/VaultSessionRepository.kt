package io.vaultix.domain

import kotlinx.coroutines.flow.Flow

/**
 * 会话**查看层**（ViewLocked）的领域入口。
 *
 * 为什么单独成接口而不是塞进 [VaultRepository]：两者语义完全不同 ——
 * [VaultRepository] 管「库的生命周期 + 数据同步」（改密钥、碰网络、动数据库），
 * 而查看层锁只读写一个**内存标记**：不派生密钥、不联网、不动任何持久化状态。
 * 混在一起会让「锁一下界面」这种轻动作看起来像一次重量级会话变更。
 *
 * 语义边界（本次规范化的核心）：
 * | 动作 | 密钥 | 恢复方式 | 走哪条路 |
 * |---|---|---|---|
 * | 超时锁定 / 退出数据库 | **清零** | 主密码（+2FA）+ 联网 | [VaultRepository.lockVault] |
 * | 主页锁按钮（查看层锁） | **保留** | 一次生物识别 | [viewLock] / [clearViewLock] |
 *
 * ⚠️ 查看锁是**界面门禁**，不是加密门禁：进程存活期间密钥仍在内存里。
 * 它挡的是「别人拿起你的手机看到明文」，不是「拿到设备就能解密」。
 * 真正的加密边界由 [VaultRepository.lockVault] 负责。
 */
interface VaultSessionRepository {

    /**
     * 已「锁查看层」的库 id 集合。
     *
     * 根导航（`RootNavViewModel`）据此把界面收回解锁页；**但不清会话密钥**，
     * 因此认证通过后不需要联网、不需要 2FA，一步回到已解锁视图。
     */
    fun observeViewLockedVaultIds(): Flow<Set<String>>

    /** 同步快照判断（非挂起场景，如导航守卫 / ViewModel 构造期）。 */
    fun isViewLocked(vaultId: String): Boolean

    /** 任一库处于查看锁（解锁页自动选库 / 根导航判据用）。 */
    fun anyViewLocked(): Boolean

    /**
     * 锁**查看层**：只置内存标记，**不清零、不移除会话密钥**。
     *
     * 未解锁时幂等 no-op（没有会话可保护，标记也无意义）。
     */
    suspend fun viewLock(vaultId: String)

    /** 认证通过后清除查看锁（幂等）。 */
    suspend fun clearViewLock(vaultId: String)

    /** 清空全部查看锁（真锁 / 退出数据库 / 全量解锁成功时一并调用）。 */
    suspend fun clearAllViewLocks()
}
