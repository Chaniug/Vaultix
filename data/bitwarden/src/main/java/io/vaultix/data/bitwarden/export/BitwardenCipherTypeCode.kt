/*
 * Vaultix — data:bitwarden
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 条目类型编号对齐 Bitwarden 官方导出结构（bitwarden/sdk-internal →
 * crates/bitwarden-exporters/src/{json.rs,models.rs} 的 `CipherType`）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.bitwarden.export

/**
 * Bitwarden 条目类型编号（对齐官方 `CipherType` 的导出取值）。
 *
 * ⚠️ 官方导出器会**过滤掉** BankAccount / Passport / DriversLicense——
 * Vaultix 领域模型目前也不含这三类，因此映射是全量覆盖、无遗漏分支。
 *
 * 导出侧与导入侧共用本对象，避免两处编号定义漂移。
 */
internal object BitwardenCipherTypeCode {
    const val LOGIN = 1
    const val SECURE_NOTE = 2
    const val CARD = 3
    const val IDENTITY = 4
    const val SSH_KEY = 5
}

/**
 * 网址匹配规则编号（对齐官方 `UriMatchType` 的导出取值）。
 *
 * 单独成对象而非内联字面量：这些数字是对外格式的一部分（写进 JSON 的
 * `login.uris[].match`），散落成裸数字既过不了 MagicNumber 门禁，
 * 也让「改一个编号要找全文件」成为隐患。
 */
internal object BitwardenUriMatchCode {
    const val DOMAIN = 0
    const val HOST = 1
    const val STARTS_WITH = 2
    const val EXACT = 3
    const val REGULAR_EXPRESSION = 4
    const val NEVER = 5
}

/** 重申密码策略编号（对齐官方 `RepromptType`）。 */
internal object BitwardenRepromptCode {
    const val NONE = 0
    const val PASSWORD = 1
}

/** 自定义字段类型编号（对齐官方 `FieldType`）。 */
internal object BitwardenFieldTypeCode {
    const val TEXT = 0
    const val HIDDEN = 1
    const val BOOLEAN = 2
    const val LINKED = 3
}

/** 安全笔记类型编号（对齐官方 `SecureNoteType`，0 = 普通笔记）。 */
internal object BitwardenSecureNoteTypeCode {
    const val GENERIC = 0
}
