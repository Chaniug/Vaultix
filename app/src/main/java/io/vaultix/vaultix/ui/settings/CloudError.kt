/*
 * Vaultix — app:ui · settings
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.ui.settings

import androidx.annotation.StringRes

/**
 * 网盘账号页的错误，**分成两类来源**（照搬 `UnlockUiError.Validation` / `Detail` 的分法）。
 *
 * ## 为什么要分（审计 §3.4，2026-10-20）
 *
 * 改前这两类都塞在 `String? error` 里，于是"我们自己写的话"和"来源层翻译好的话"
 * 长得一模一样 —— 结果前者被抄进了 Kotlin（`strings.xml` 里根本没有这些句子）。
 *
 * | 类型 | 来源 | 展示 |
 * |---|---|---|
 * | [Res] | 我们在 ViewModel 里判定的（校验、拿不到 Activity…） | `stringResource(id)` |
 * | [Detail] | 来源层已翻译好的一句话（如 MSAL 抛出的原因） | 套 `cloud_msg_onedrive_failed_detail` 前缀 |
 *
 * ⇒ 判据一句话：**这句话是不是我们自己写的**？是 ⇒ [Res]；否 ⇒ [Detail]。
 * 与 `UnlockUiError` 的判据完全一致，别另发明一套。
 */
sealed interface CloudError {
    /** 我们自己写的文案，必须在 `strings.xml` 里。 */
    data class Res(@StringRes val id: Int) : CloudError

    /** 来源层已经翻译好的话，原样透传（只套一层说明前缀）。 */
    data class Detail(val message: String) : CloudError
}
