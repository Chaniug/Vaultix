/*
 * Vaultix — data:kdbx
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * KDBX 库的**写回**（M2 阶段 B）。
 *
 * ## 与只读阶段的关系
 *
 * 阶段 A 只做「读成领域模型」；写回要做的是把**内存里的 `KeePassDatabase`**
 * （会话持有的那一份，可能已被改动）重新编码成 `.kdbx` 字节。
 *
 * ⇒ **不经领域模型往返**。这一点是关键：真正的写回只能是
 *   「改内存里的 `KeePassDatabase` → `encode`」，
 *   若走「领域模型 → 重建库」则必然丢掉所有未映射的东西
 *   （`KPEX_*`、未知自定义字段、附件、历史记录、自定义图标……）。
 *   本文件只负责**编码**这一步；「怎么改」由调用方对 `KeePassDatabase` 施加。
 *
 * ## 保真（`8.3-M2KDBX.md` 的铁律）
 *
 * 「插件字段（`KPEX_*`）与不认识的自定义字段一律原样保留」——
 * 已实测（`RoundTripTest`）：kotpass 的 `CustomData` 是 `Map<String, CustomDataValue>`，
 * **任意 key 都会往返保真**；条目自定义字段同理（`EntryFields` 也是 Map）。
 *
 * ⚠️ **但未知的 XML 顶层标签会被静默丢弃** —— kotpass 的 `Entry`/`Group` 解析是
 *   `when (childNode.nodeName) { … }` 且**没有 `else` 分支**。
 *   KDBX 4.1 的标准标签都被覆盖了，但**第三方扩展塞进来的新标签会消失**。
 *   ⇒ 这是 `KdbxFidelityReporter` 要登记的东西，**不能让"丢字段"是静默的**。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.kdbx

import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.encode
import java.io.ByteArrayOutputStream

/**
 * 把 [database] 编码成 KDBX 字节。
 *
 * 直接复用 [KDBX_CIPHER_PROVIDERS]（与解码同一个装配）——
 * **编解码两侧的密码套件必须一致**，否则会出现「自己写的库自己打不开」
 * （或更糟：用了默认套件写出一个丢失 Twofish 支持的文件）。
 *
 * ⚠️ `encode` 内部会先 `regenerateVectors(random, cipherProviders)`
 *   **重新生成 IV / 变换种子 / 加密随机流** ⇒ 同样的库编码两次字节必然不同。
 *   这是正确行为（每次写都换随机数），但意味着**不能用"字节相等"判断"内容没变"**，
 *   要比对的是**解码后的内容**（见 [KdbxRoundTrip]）。
 *
 * @return 完整的 `.kdbx` 文件字节。
 */
internal object KdbxEncoder {

    fun encode(database: KeePassDatabase): ByteArray {
        val out = ByteArrayOutputStream()
        // 只传 cipherProviders：其余参数（XmlContentParser / KdfProvider / SecureRandom）
        // 用 kotpass 的默认值 —— 它们不依赖调用方，显式传反而会引入"两处装配漂移"的风险。
        database.encode(outputStream = out, cipherProviders = KDBX_CIPHER_PROVIDERS)
        return out.toByteArray()
    }
}
