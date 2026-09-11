package io.vaultix.domain

import io.vaultix.model.VaultFido2Credential
import io.vaultix.model.VaultItem
import kotlinx.coroutines.flow.Flow

/**
 * 条目读写入口（Docs/01 ItemRepository）。
 *
 * 实现位于 data:repository。密文 ↔ 明文映射与加解密全部发生在 data 层；
 * UI 只会拿到已解密的 [VaultItem]（仅在解锁会话内存在于内存，见 Docs/09）。
 */
interface ItemRepository {

    /**
     * 观察某个库的条目明文列表（Room 密文快照 + 内存会话密钥解密）。
     *
     * - 库未解锁时发出空列表（UI 不应在未解锁状态进入条目页，此处是兜底）；
     * - 个别条目密文损坏 / 密钥不符时**跳过该条**（服务端脏数据不应拖垮整个列表，
     *   与 CipherMapper 的降级策略一致——列表层面再兜一层，保证可浏览）。
     */
    fun observeItems(vaultId: String): Flow<List<VaultItem>>

    /**
     * 观察回收站明文列表（deletedDate 非空的行；服务端回收站保留 30 天，
     * 被服务端永久清除的行会在下次成功全量同步后被收敛删除）。
     *
     * 每行附带 [TrashEntry.deletedDate]（ISO-8601），供 UI 做自动清理倒计时。
     */
    fun observeTrash(vaultId: String): Flow<List<TrashEntry>>

    /**
     * 观察单个条目明文（详情页用）。
     *
     * - [itemId] 属于其它库 / 库未解锁 / 条目不存在 → null；
     * - 本地编辑/删除后由 Room 流自动重发最新值。
     */
    fun observeItem(vaultId: String, itemId: String): Flow<VaultItem?>

    /**
     * 新建条目：
     * 1. 分配本地 uuid，密文化后落 Room（列表立即可见，离线安全）；
     * 2. 写入 pending_ops 队列；
     * 3. 随即轻量推送（`POST /ciphers`，不等整库下载，见 Bastion 事故教训）。
     *
     * @param item 传明文字段即可；[VaultItem.id] 会被实现覆盖（本地临时 id）。
     * @return [VaultSaveOutcome.Synced] = 已推上云；[VaultSaveOutcome.Queued] =
     *         已落本地待联网推送（下次同步自动重试）。
     */
    suspend fun createItem(vaultId: String, item: VaultItem): Result<VaultSaveOutcome>

    /**
     * 更新条目：明文 → 密文 → 覆盖本地行（id/revisionDate/folderId 不变）→
     * UPDATE 入队 → 轻量推送（`PUT /ciphers/{id}`）。
     *
     * ⚠️ 与新建不同：更新**沿用原 id**，服务端无新 id，无重映射。
     */
    suspend fun updateItem(vaultId: String, item: VaultItem): Result<VaultSaveOutcome>

    /**
     * 软删除（移至回收站，Docs/08 S19 口径）：
     * 本地行标记 deletedDate（列表立即隐藏）→ SOFT_DELETE 入队 → 轻量推送
     * （`DELETE /ciphers/{id}`；服务端 30 天后自动永久清理）。
     */
    suspend fun softDeleteItem(vaultId: String, itemId: String): Result<VaultSaveOutcome>

    /**
     * 从回收站恢复：
     * 本地行清除 deletedDate（列表立即恢复显示）→ RESTORE 入队 → 轻量推送
     * （`PUT /ciphers/{id}/restore`）。离线时本地先行、队列联网补推。
     */
    suspend fun restoreItem(vaultId: String, itemId: String): Result<VaultSaveOutcome>

    /**
     * 永久删除（不可恢复）：
     * DELETE 入队 → 删除本地行 → 轻量推送（`DELETE /ciphers/{id}/delete`）。
     * 离线时本地行已移除，队列保留，联网后推送（服务端 404 = 已删除 → 弃单）。
     */
    suspend fun permanentDeleteItem(vaultId: String, itemId: String): Result<VaultSaveOutcome>

    /**
     * 回收站自动清理（批次③，对齐 Bastion TrashViewModel.cleanupExpiredItemsNow）：
     * 把删除时间早于 `now - [autoDeleteDays]` 天的回收站行按 [permanentDeleteItem]
     * 同口径处理（DELETE 入队 → 删本地行 → 轻量推送），保证离线时服务端也会被删。
     *
     * @param autoDeleteDays 保留天数；`<= 0` 表示不自动清空（直接返回 0，无副作用）。
     * @return 本次实际清理的条数（失败静默为 0——清理是后台辅助动作，不打断 UI）。
     */
    suspend fun cleanupExpiredTrash(vaultId: String, autoDeleteDays: Int): Int

    /**
     * 设置某登录条目的通行密钥集合（整体替换）。
     *
     * 通行密钥**永远绑定在某个登录条目（密码条目）上**（对齐 Bitwarden login.fido2Credentials），
     * 不存在独立的通行密钥条目。本方法用于「保存通行密钥」（追加到该登录条目）与
     * 「删除通行密钥」（过滤掉指定 credentialId 后整体写回）。
     *
     * 实现：取出该条目当前明文 → 替换其 [VaultItem.fido2Credentials] → 走 [updateItem]
     * 的合并写回（toUpdateRequest 按 credentialId **保留未改动条目的服务端原密文**，
     * 仅对新增条目加密 —— 防止私钥材料被覆写成 null，见 P0 审计）。
     */
    suspend fun updateFido2Credentials(
        vaultId: String,
        itemId: String,
        credentials: List<VaultFido2Credential>,
    ): Result<VaultSaveOutcome>

    /**
     * 从登录条目移除单个通行密钥（按 credentialId 过滤后整体写回）。
     *
     * 注意：仅移除本地的该凭证引用；若服务端另存了密钥材料，下次全量同步可能重新下发，
     * 与 Bitwarden/Keyguard 的行为一致（通行密钥由服务器/平台管理，客户端删除为本地视图收敛）。
     */
    suspend fun removeFido2Credential(
        vaultId: String,
        itemId: String,
        credentialId: String,
    ): Result<VaultSaveOutcome>
}

/** 新建条目后的落点状态。 */
enum class VaultSaveOutcome {
    /** 已推送到服务器（本地行按服务端 id 重建） */
    Synced,

    /** 网络不可用：已安全落本地并进入待推送队列 */
    Queued,
}

/**
 * 回收站行：明文条目 + 删除时间。
 *
 * [deletedDate] 为 ISO-8601 字符串（与数据库行一致；本地写入 `Instant.now().toString()`，
 * 服务端同步下行同格式），供 `TrashCleanupPolicy` 计算剩余天数倒计时。
 */
data class TrashEntry(
    val item: VaultItem,
    val deletedDate: String,
)
