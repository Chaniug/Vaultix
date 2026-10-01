package io.vaultix.data.repository

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.vaultix.data.repository.kdbx.KdbxCreateRepositoryImpl
import io.vaultix.data.repository.kdbx.KdbxSyncRepositoryImpl
import io.vaultix.domain.AutoUnlockRepository
import io.vaultix.domain.FolderRepository
import io.vaultix.domain.ItemRepository
import io.vaultix.domain.KdbxCreateRepository
import io.vaultix.domain.KdbxSyncRepository
import io.vaultix.domain.RoomResealRepository
import io.vaultix.domain.UnlockRecoveryRepository
import io.vaultix.domain.VaultExportRepository
import io.vaultix.domain.VaultRepository
import io.vaultix.domain.VaultSessionRepository
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

    @Binds
    @Singleton
    fun bindVaultSessionRepository(impl: VaultSessionRepositoryImpl): VaultSessionRepository

    @Binds
    @Singleton
    fun bindVaultExportRepository(impl: VaultExportRepositoryImpl): VaultExportRepository

    /**
     * KDBX 网盘同步。
     *
     * ⚠️ 独立绑定而不是并进 [VaultRepository]：后者实现类已**正好 40 个函数**
     * （detekt `TooManyFunctions` 硬上限），再加就爆。见 `KdbxSyncRepository` 的说明。
     */
    @Binds
    @Singleton
    fun bindKdbxSyncRepository(impl: KdbxSyncRepositoryImpl): KdbxSyncRepository

    /**
     * 新建空白 KDBX 库（M2 阶段 B · 批次 W0）。
     *
     * ⚠️ 独立绑定而不是并进 [VaultRepository]：后者实现类已**正好 40 个函数**
     * （detekt `TooManyFunctions` 硬上限），再加就爆。见 `KdbxCreateRepository` 的说明。
     */
    @Binds
    @Singleton
    fun bindKdbxCreateRepository(impl: KdbxCreateRepositoryImpl): KdbxCreateRepository

    /**
     * 「从不锁定」档自动恢复（同 [bindKdbxSyncRepository] 的独立绑定理由）。
     * 触发编排（档位/内存态变化 → 调它）在 app 层 `AutoRestoreTrigger`。
     */
    @Binds
    @Singleton
    fun bindAutoUnlockRepository(impl: AutoUnlockRepositoryImpl): AutoUnlockRepository

    /**
     * 快速解锁失效矩阵的善后（重装 / 降级；同 [bindKdbxSyncRepository] 的独立绑定理由）。
     * 触发者 = 解锁失败现场（`LocalUnlockFanout`）与登记向导指纹段，见接口 KDoc。
     */
    @Binds
    @Singleton
    fun bindUnlockRecoveryRepository(impl: UnlockRecoveryRepositoryImpl): UnlockRecoveryRepository

    /**
     * 房间信封重包（某库换了主密码之后；同 [bindKdbxSyncRepository] 的独立绑定理由）。
     * 调用点 = 解锁页「输新主密码」分支与设置页「重新纳入此库」，见接口 KDoc。
     */
    @Binds
    @Singleton
    fun bindRoomResealRepository(impl: RoomResealRepositoryImpl): RoomResealRepository
}
