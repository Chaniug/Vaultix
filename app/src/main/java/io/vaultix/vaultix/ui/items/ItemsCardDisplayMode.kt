/*
 * Vaultix — app:ui:items
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 条目卡片的**信息密度**（对齐 Bastion `PasswordCardDisplayMode`）：
 *   - SHOW_ALL       → [All]：标题 + 副标题（用户名 / 类型徽标 / 卡号后四位）
 *   - TITLE_USERNAME → [TitleUsername]：标题 + 用户名
 *   - TITLE_ONLY     → [TitleOnly]：只有标题
 * 与「分组方式」一样属于**列表展示偏好**，不影响任何数据。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.ui.items

/** 条目卡片显示多少信息（`storageKey` 落偏好，改动需保持向后兼容）。 */
enum class ItemsCardDisplayMode(val storageKey: String) {
    /** 全部（默认）：标题 + 副标题。 */
    All("all"),

    /** 标题 + 用户名（隐藏卡号/类型等次要副标题）。 */
    TitleUsername("title_username"),

    /** 仅标题（最紧凑）。 */
    TitleOnly("title_only"),
    ;

    companion object {
        fun from(storageKey: String?): ItemsCardDisplayMode =
            entries.firstOrNull { it.storageKey == storageKey } ?: All
    }
}
