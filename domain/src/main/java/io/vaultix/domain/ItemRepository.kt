package io.vaultix.domain

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
}

/** 新建条目后的落点状态。 */
enum class VaultSaveOutcome {
    /** 已推送到服务器（本地行按服务端 id 重建） */
    Synced,

    /** 网络不可用：已安全落本地并进入待推送队列 */
    Queued,
}
