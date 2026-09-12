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
    /** 回收站里的条目数（本阶段不映射，明确告知用户而不是静默吞掉）。 */
    val recycleBinCount: Int,
    /** 分组路径 → uuid（阶段 B 写回时要用）。 */
    val groupPaths: Map<UUID, String>,
)

/** 分组 uuid → Vaultix 文件夹 id（前缀避免与 Bitwarden 的 folder id 撞车）。 */
internal fun folderIdOf(groupUuid: UUID): String = "kdbx-group:$groupUuid"

/** 条目 uuid → Vaultix 条目 id。 */
internal fun itemIdOf(entryUuid: UUID): String = "kdbx-entry:$entryUuid"
