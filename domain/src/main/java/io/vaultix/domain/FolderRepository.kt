package io.vaultix.domain

import io.vaultix.model.VaultFolder
import kotlinx.coroutines.flow.Flow

/**
 * 文件夹读取（只读）。
 *
 * M1 只支持「选用已有文件夹」——文件夹的增删改由 Bitwarden 官方端 /
 * 网页端管理（与 Bastion 同策略：编辑器不碰文件夹维护，避免双端冲突）。
 * 未来 KDBX 引擎可把分组映射为同一领域模型。
 *
 * 名称需要会话密钥解密，因此未解锁时列表为空（不是抛异常——
 * 让 UI 平滑降级为「无文件夹可选」，与锁定状态下的条目列表语义一致）。
 */
interface FolderRepository {

    /** 观察某库的文件夹列表（解锁后才有内容）。 */
    fun observeFolders(vaultId: String): Flow<List<VaultFolder>>
}
