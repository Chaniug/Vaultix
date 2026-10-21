package io.vaultix.domain

import io.vaultix.model.VaultCollection
import io.vaultix.model.VaultOrganization
import kotlinx.coroutines.flow.Flow

/**
 * 组织 / 集合读取（只读，2026-10-21，P1 组织视图）。
 *
 * 与 [FolderRepository] 同策略：Vaultix **不提供**组织的创建 / 成员 / 权限 /
 * 集合归属编辑 —— 那些是管理端（网页端 / 官方客户端）的职责。
 * 这里只负责"让用户看得见共享库的结构，并能按它筛条目"。
 *
 * 名称需要组织密钥解密，未解锁时两个流都给空列表（不抛异常，UI 平滑降级）。
 */
interface OrganizationRepository {

    /** 该库所属的全部组织（未解锁为空）。 */
    fun observeOrganizations(vaultId: String): Flow<List<VaultOrganization>>

    /** 该库可见的全部集合（未解锁为空）。 */
    fun observeCollections(vaultId: String): Flow<List<VaultCollection>>
}
