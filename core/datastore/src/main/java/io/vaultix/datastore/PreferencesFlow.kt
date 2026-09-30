/*
 * Vaultix — core:datastore
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import java.io.IOException

/**
 * 偏好流的**统一读取兜底**：读取异常（首次运行 / 文件损坏）时退回空配置，避免整条流挂掉。
 *
 * ## 为什么值得抽成一个函数（2026-09-30）
 *
 * 这段 `catch` 是**策略**，不是样板：它只吞 [IOException]（文件不存在 / 损坏），
 * 其余异常**必须原样抛** —— 一并吞掉就等于把真实故障伪装成「设置全都是默认值」。
 *
 * 2026-09-30 把档位偏好抽成 [VaultTimeoutPreferences] 后，本模块出现了**第二个**读取点。
 * 两份拷贝一旦漂移（最可能的漂法是漏掉 `else throw`），症状是**静默读到空偏好**：
 * 不崩、不报错，只是用户的档位/开关集体回到默认值 —— 这类问题极难归因。
 * ⇒ 策略只留一份。
 */
internal fun DataStore<Preferences>.safePreferencesFlow(): Flow<Preferences> =
    data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }
