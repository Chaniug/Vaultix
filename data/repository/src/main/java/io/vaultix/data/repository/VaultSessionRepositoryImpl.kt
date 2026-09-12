package io.vaultix.data.repository

import io.vaultix.domain.VaultSessionRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [VaultSessionRepository] 的实现：把「查看层锁」透传给 [VaultSessionManager]。
 *
 * 刻意做成**零逻辑**的透传：标记的存放与并发保护只有一处（会话管理器），
 * 这里再加一层缓存就会出现两个真源（标记不同步 = 界面锁了但 autofill 以为没锁）。
 */
@Singleton
class VaultSessionRepositoryImpl @Inject constructor(
    private val sessions: VaultSessionManager,
) : VaultSessionRepository {

    override fun observeViewLockedVaultIds(): Flow<Set<String>> = sessions.viewLockedIds

    override fun isViewLocked(vaultId: String): Boolean = sessions.isViewLocked(vaultId)

    override fun anyViewLocked(): Boolean = sessions.anyViewLocked()

    override suspend fun viewLock(vaultId: String) = sessions.viewLock(vaultId)

    override suspend fun clearViewLock(vaultId: String) = sessions.clearViewLock(vaultId)

    override suspend fun clearAllViewLocks() = sessions.clearAllViewLocks()
}
