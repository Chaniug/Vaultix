/*
 * Vaultix — app:autofill · save
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.vaultix.autofill.save

import android.os.Build
import android.service.autofill.SaveInfo
import android.view.autofill.AutofillId
import io.vaultix.vaultix.autofill.model.ParsedStructure

/**
 * 保存提示（`SaveInfo`）构造。
 *
 * ⚠️ **没有 SaveInfo 就永远不会有 `onSaveRequest`**——框架只在 FillResponse 里带了
 * SaveInfo 时才会在用户提交表单后回调保存（此前 `onSaveRequest` 是空实现，
 * 根因就是响应里没挂 SaveInfo）。
 *
 * requiredIds（API 28+）传账号 + 密码框：两者都有值时才提示保存，
 * 避免「只填了个搜索词就弹保存」。
 */
object AutofillSaveInfo {

    fun build(parsed: ParsedStructure): SaveInfo? {
        val ids: List<AutofillId> = listOfNotNull(parsed.usernameId, parsed.passwordId)
        if (ids.isEmpty()) return null
        val types = SaveInfo.SAVE_DATA_TYPE_USERNAME or SaveInfo.SAVE_DATA_TYPE_PASSWORD
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            SaveInfo.Builder(types, ids.toTypedArray()).build()
        } else {
            @Suppress("DEPRECATION")
            SaveInfo.Builder(types).build()
        }
    }
}
