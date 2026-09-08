package io.vaultix.data.repository

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.vaultix.domain.FolderRepository
import io.vaultix.domain.ItemRepository
import io.vaultix.domain.VaultRepository
import javax.inject.Singleton

/**
 * data:repository 装配：domain 接口 → data 实现（Docs/11：XxxRepository 接口在
 * domain，XxxRepositoryImpl 在 data）。
 *
 * 说明：vaults/ciphers DAO、Json、网络服务等依赖均来自 core:database / core:datastore /
 * data:bitwarden 各自的 Hilt 模块，此处只做接口绑定。
 */
@Module
@InstallIn(SingletonComponent::class)
interface RepositoryModule {

    @Binds
    @Singleton
    fun bindVaultRepository(impl: VaultRepositoryImpl): VaultRepository

    @Binds
    @Singleton
    fun bindItemRepository(impl: ItemRepositoryImpl): ItemRepository

    @Binds
    @Singleton
    fun bindFolderRepository(impl: FolderRepositoryImpl): FolderRepository
}
