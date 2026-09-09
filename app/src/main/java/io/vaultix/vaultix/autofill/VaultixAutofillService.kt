/*
 * Vaultix — app:autofill
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * 部分匹配/解析逻辑后续里程碑移植自 Bastion（GPL-3.0，Copyright 2025 JoyinJoester），
 * 移植处均保留溯源声明（见 match/ 与 parser/ 包内对应文件）。
 */
package io.vaultix.vaultix.autofill

import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import dagger.hilt.android.AndroidEntryPoint

/**
 * 系统自动填充服务（M2-a 骨架）。
 *
 * 密码填充走 Android 老 `AutofillService` API（API 26+）；与 `MainActivity` 同进程同 UID，
 * 后续里程碑可经 Hilt 注入 [io.vaultix.domain.ItemRepository] / [io.vaultix.domain.VaultRepository]
 * 直接读取已解锁库的解密明文。
 *
 * 本里程碑（M2-a.1）仅完成注册与空响应占位；解析/匹配/回填在 M2-a.2~a.6 接入。
 */
@AndroidEntryPoint
class VaultixAutofillService : AutofillService() {

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback,
    ) {
        // M2-a.1 骨架：占位，后续接入解析 → 匹配 → 回填。
        // 取消时直接回空响应，系统会丢弃本次请求，无需再回调。
        if (cancellationSignal.isCanceled) {
            callback.onSuccess(null)
            return
        }
        callback.onSuccess(FillResponse.Builder().build())
    }

    @Suppress("UnusedParameter")
    override fun onSaveRequest(
        request: SaveRequest,
        callback: SaveCallback,
    ) {
        // v1 仅填充（fill），保存流程（onSaveRequest）为 M2 后续里程碑。
        callback.onSuccess()
    }
}
