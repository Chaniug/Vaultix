/*
 * Vaultix — app:ui · vaultlist
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.vaultlist

import androidx.annotation.StringRes

/**
 * 同步 / 删除这类操作给用户看的一句话，**分两类来源**。
 *
 * ## 为什么（审计 §3.4，2026-10-21）
 *
 * 改前 `Event.KdbxSynced.message` / `Event.KdbxSyncFailed.message` / `Event.RemoveFailed.message`
 * 都是 `String`，里面同时装着两种东西：
 *
 * | 改前写法 | 实际来源 | 问题 |
 * |---|---|---|
 * | `"已上传到云端"` | **我们自己写的** | 进了 Kotlin，`strings.xml` 里没有 ⇒ 翻不到、也查不出 |
 * | `report.reason` | 编排器已翻译好的 | 本来就该原样展示 |
 *
 * 两者长得一模一样 ⇒ 评审看不出哪句该进资源，哨兵脚本也无法区分。
 *
 * ## 两类（判据与 `UnlockUiError` / `CloudError` / `QuickUnlockText` 完全一致）
 *
 * - [Res]：**这句话是我们自己写的** ⇒ 必须在 `strings.xml`。
 * - [Detail]：来源层已经翻译成人话 ⇒ 原样展示，不再套前缀。
 */
sealed interface SyncNotice {
    /** 我们自己写的文案（`strings.xml` 里的资源）。 */
    data class Res(@StringRes val id: Int) : SyncNotice

    /** 来源层已经翻译好的一句话。 */
    data class Detail(val message: String) : SyncNotice
}
