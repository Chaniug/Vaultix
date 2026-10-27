package io.vaultix.domain

import io.vaultix.model.VaultFolder
import kotlinx.coroutines.flow.Flow

/**
 * 文件夹（Bitwarden folder / KDBX 分组）的读与写。
 *
 * ## 读
 *
 * 名称需要会话密钥解密（Bitwarden）或来自内存会话（KDBX），因此未解锁时列表为空
 * （不是抛异常 —— 让 UI 平滑降级为「无文件夹可选」，与锁定状态下的条目列表语义一致）。
 *
 * ## 写（2026-10-27 新增 · #199）
 *
 * 三个写方法**共用一个契约**：
 * - 名称一律先过 [FolderNamePolicy]（空 / 超长 / 含路径分隔符直接拒绝）；
 * - 失败返回 `Result.failure`，`message` 是**可直接展示的**一句话；
 * - 成功后该库的 `observeFolders` 会自然刷新（不额外发事件）。
 *
 * ## 🔴 为什么 Bitwarden 侧**直连服务端**而不进离线队列
 *
 * 条目的写是「本地先行 + `pending_ops` 排队 + 轻量推送」。文件夹**不能**照搬：
 *
 * 1. `pending_ops` 的语义是 `cipherId` + 五种 cipher 操作，文件夹塞不进去；
 * 2. 更要命的是 `pruneRemovedRows`：它把「服务端没返回的 folder 行」当成**已删**。
 *    本地刚建、还没推上去的文件夹会被**下一次同步静默清掉**（用户看到自己建的文件夹消失）；
 * 3. `FolderDao` 只有整体替换语义，没有单行 upsert。
 *
 * ⇒ 直连：**成功才写本地**，失败**一个字都不改**。代价是「离线时不能建文件夹」，
 * 用一句明确的错误提示换掉数据不一致 —— 这个取舍是有意的，别在后续"顺手统一"时改掉。
 *
 * 推演见 `.ai/issues/06-界面与交互.md` #199。
 */
interface FolderRepository {

    /** 观察某库的文件夹列表（解锁后才有内容）。 */
    fun observeFolders(vaultId: String): Flow<List<VaultFolder>>

    /**
     * 新建文件夹。
     *
     * @param name 用户输入（内部会 [FolderNamePolicy] 校验并 trim）。
     */
    suspend fun createFolder(vaultId: String, name: String): Result<Unit>

    /** 重命名文件夹；[folderId] 必须属于该库。 */
    suspend fun renameFolder(vaultId: String, folderId: String, name: String): Result<Unit>

    /**
     * 删除文件夹。
     *
     * ⚠️ **两种库语义不同**，UI 的确认文案必须区分：
     * - Bitwarden：文件夹是扁平标签，**条目不会被删**，只变成「无文件夹」；
     * - KDBX：分组是树，删除 = **递归删整棵子树**（子组 + 组内条目一起没）。
     */
    suspend fun deleteFolder(vaultId: String, folderId: String): Result<Unit>
}
