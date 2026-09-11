package io.vaultix.vaultix.security

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * app:security 装配：锁定管理器接口 → 实现。
 *
 * 说明：`VaultTimeout` 是纯数据模型（sealed class），无需绑定；
 * 本模块只负责 [VaultLockManager]。
 */
@Module
@InstallIn(SingletonComponent::class)
interface SecurityModule {

    @Binds
    @Singleton
    fun bindVaultLockManager(impl: VaultLockManagerImpl): VaultLockManager
}
