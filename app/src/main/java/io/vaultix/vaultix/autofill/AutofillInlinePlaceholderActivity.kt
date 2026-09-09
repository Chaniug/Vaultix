/*
 * Vaultix — app:autofill
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * 内联建议占位 Activity（next-steps 批次④）：Credential / Autofill 的内联建议需要一个
 * 可解析的 PendingIntent 宿主；部分 ROM 对空隐式 Intent 行为不稳，故保留一个显式 no-op 目标。
 * 实际候选回灌走 AutofillActivity + 系统 sheet，此处从不渲染任何界面。
 */
package io.vaultix.vaultix.autofill

import android.app.Activity
import android.os.Bundle

class AutofillInlinePlaceholderActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
    }
}
