/*
 * Vaultix — data:kdbx
 * Copyright (C) 2026 Vaultix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.data.kdbx

import io.vaultix.model.VaultFolder
import io.vaultix.model.VaultItem
import java.util.UUID

/**
 * 一次映射的产物（条目 + 分组 + 诊断计数）。
 *
 * 公开可见：它是 [KdbxUnlockedContent]（对外快照）的**内部形态**，携带阶段 B 写回
 * 需要的 `groupPaths`（uuid → 分组路径），因此不能只留在模块内。
 */
data class KdbxMappedContent(
    val items: List<VaultItem>,
    val folders: List<VaultFolder>,
    /** 回收站里的条目数（与 [trashItems] 同源，供只要个数的 UI 用）。 */
    val recycleBinCount: Int,
    /**
     * 回收站子树里的条目（施工单 S5）。
     *
     * ⚠️ 与 [items] **互斥**：同一条条目要么在 [items]、要么在这里，不会两边都出现
     * （回收站是独立视图，对齐 Bitwarden 的 `deletedDate` 语义）。
     */
    val trashItems: List<KdbxTrashItem>,
    /** 分组路径 → uuid（阶段 B 写回时要用）。 */
    val groupPaths: Map<UUID, String>,
    /**
     * **库内二进制附件索引**：`hash(hex) → 名字`（2026-10-21）。
     *
     * ## 为什么只存"名字"不存字节
     *
     * 附件字节属于**整库**（`meta.binaries`），同一份内容挂在多个条目上只存一次；
     * 而 `VaultItem` 是**每条一条**的视图。把字节塞进 `KdbxMappedContent` 意味着
     * **每次读列表都要把库里所有附件读进内存** —— 一个 80MB 的库会直接把列表页拖垮。
     *
     * ⇒ 这里只留"有哪些 hash、各自叫什么"，字节在真正要导出时按 hash 现取
     * （`Kdbx.binaryOf(vaultId, hash)`）。这也与 `VaultAttachment` 只承载元信息的约定一致。
     */
    val binaryNames: Map<String, String> = emptyMap(),
)

/**
 * 回收站里的一条（KDBX 版）。
 *
 * ⚠️ [lastModifiedAtMillis] 是**最后修改时间**，**不是删除时间** ——
 * KDBX 的 `<Times>` 里根本没有"删除时间"这个字段：把条目移进回收站只是换了个组，
 * `LastModificationTime` 会跟着变，但它在语义上仍是"最后修改"。
 * ⇒ 拿它当删除时间是本阶段的**近似**，UI 展示时必须如实标注
 * （例如「最后修改」而不是「删除于」）—— 声称库里记了删除时刻是在编造数据。
 */
data class KdbxTrashItem(
    val item: VaultItem,
    val lastModifiedAtMillis: Long?,
)

/** 分组 uuid → Vaultix 文件夹 id（前缀避免与 Bitwarden 的 folder id 撞车）。 */
fun folderIdOf(groupUuid: UUID): String = "kdbx-group:$groupUuid"

/** 条目 uuid → Vaultix 条目 id。 */
internal fun itemIdOf(entryUuid: UUID): String = "kdbx-entry:$entryUuid"
