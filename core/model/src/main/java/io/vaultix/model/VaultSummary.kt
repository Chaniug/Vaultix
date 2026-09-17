package io.vaultix.model

/**
 * 密码库类型（对应 VaultEntity.kind 的枚举名）。
 *
 * BITWARDEN = 云端同步库；KDBX = 本地文件库（M2）。
 */
enum class VaultKind {
    BITWARDEN,
    KDBX,
    ;

    companion object {
        /** 从 Room `vaults.kind` 字符串解析（BITWARDEN / KDBX）；未知值返回 null 由上层处理。 */
        fun fromName(name: String?): VaultKind? = entries.firstOrNull { it.name == name }
    }
}

/**
 * KDBX 网盘同步状态（对应 `vaults.syncStatus` 列，网盘库专用）。
 *
 * ⚠️ 与 `io.vaultix.domain.VaultSyncStatus`（**Bitwarden** 的同步运行时状态，
 *   一个 data class）**不是一回事**，两者名字刻意区分开：那个描述"本次同步到哪一步了"
 *   （瞬态、进程内），这个描述"这个库与远端的差距"（**持久化**在 `vaults.syncStatus` 列）。
 *   混用会让 UI 拿瞬态去渲染持久状态（比如刷新一次就"好了"）。
 *
 * ## 为什么是**可空**而不是给个默认值
 *
 * ⚠️ `null` 表示「这个库不适用网盘同步」（Bitwarden 库、本地 SAF 库）。
 * 给它们填 [LOCAL_ONLY] 之类会让 UI 在 Bitwarden 库上渲染出一个毫无意义的
 * 「仅本地」角标 —— 「不适用」与「仅本地」是两件事，必须分开。
 *
 * ⚠️ 本枚举与 `data:repository` 的 `KdbxSyncStatus` **名字一一对应**
 * （那一个是落库的真相，这一个只是给 UI 读的投影）。
 * 之所以在 `core:model` 再定义一份：`core:model` 不能依赖 `data:repository`
 * （依赖方向是 data → core）。两边的名字必须同步改，改一处要改两处。
 */
enum class KdbxCloudSyncStatus {
    /** 只存在本地，还没有网盘来源。 */
    LOCAL_ONLY,

    /** 本地与远端一致。 */
    IN_SYNC,

    /** 正在同步。 */
    SYNCING,

    /** 本地有改动，还没推上去。 */
    PENDING_UPLOAD,

    /** 远端有改动，本地还没拉。 */
    REMOTE_CHANGED,

    /** ★ 已上传，但上传期间本地又改了 —— 必须再跑一轮。 */
    PENDING_UPLOAD_WITH_LOCAL_CHANGES,

    /** 两边都改了 —— 需要用户拍板。 */
    CONFLICT,

    /** 上次同步失败，可重试。 */
    FAILED,
    ;

    /** 是否需要用户看一眼（UI 据此决定要不要给提示）。 */
    val needsAttention: Boolean
        get() = this == CONFLICT || this == FAILED || this == PENDING_UPLOAD_WITH_LOCAL_CHANGES

    companion object {
        /** 从 Room `vaults.syncStatus` 字符串解析；null / 未知值一律返回 null（= 不适用）。 */
        fun fromName(name: String?): KdbxCloudSyncStatus? = entries.firstOrNull { it.name == name }
    }
}

/**
 * UI 面向的库摘要（由 VaultRepository 聚合 Room 行 + 会话状态产生）。
 *
 * @param account 账号标签（Bitwarden = 邮箱；KDBX 为 null），如 S3 规格的
 *                「Bitwarden · alice@mail.com」。
 * @param unlocked 当前是否已解锁（内存会话存在且密钥未清零）。
 * @param syncStatus 网盘同步状态；**null = 该库不适用网盘同步**（见 [KdbxCloudSyncStatus]）。
 */
data class VaultSummary(
    val id: String,
    val kind: VaultKind,
    val name: String,
    val account: String? = null,
    val origin: String,
    val unlocked: Boolean = false,
    val syncStatus: KdbxCloudSyncStatus? = null,
)
