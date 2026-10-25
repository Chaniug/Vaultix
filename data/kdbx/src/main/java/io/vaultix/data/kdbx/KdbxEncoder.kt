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
import java.security.MessageDigest

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
 *   🔴 **推论（#185）：内容哈希会随每次编码漂移，绝对不能拿来当基线。**
 *   详见 [contentFingerprint] 的说明 —— 那里有两个真实踩过的坑。
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

    /**
     * **内容指纹**：对库的**明文映射内容**做 SHA-256，而不是对编码后的字节。
     *
     * ## 为什么不能直接哈希 [encode] 的结果（两个真实踩过的坑，2026-10-25 · #185）
     *
     * 1. **编码不确定**：见类头 —— 每次 `encode` 都重派生内容密钥、重生成 IV / 随机流，
     *    同一份库编两次字节不同 ⇒ 拿它当"内容有没有变"的判据，**所有库永远算"变过"**。
     * 2. **编码太贵**：一次 `encode` 与一次解锁（KDF）同级。为了"比一下指纹"再编码一遍，
     *    等于每次保存多付数百毫秒 —— 这种代价迟早被人关掉，而它关掉的那一刻
     *    防线就没了（与 `verifyWrite` 那套"太贵所以被跳过"是同一类教训）。
     *
     * ## 为什么可以用 [KdbxMappedContent]
     *
     * 它是**读路径的唯一出口**（`contentOf` 也给这一份），来自会话里那个库的当前内容：
     * 内容没变 ⇒ 映射没变 ⇒ 指纹不变；内容变了 ⇒ 几乎必然不同。
     * 这与 `KdbxRoundTrip` 的取舍一致 —— 那个类比对的就是解码后的内容，
     * 因为"字节相等"在 KDBX 里根本不成立。
     *
     * ## 🔴 为什么**不**把 `updatedAt` 算进去（第三个坑，实测踩到）
     *
     * 首次实现把 `updatedAt` 一起喂进摘要，结果**连"同一份内容编两次"都对不上**：
     * 远端那份是别的设备重编码的，它的 `<Times>` 与本地完全是两回事 ——
     * 于是"远端只是重新编码、内容一模一样"会被判成"内容变了"，
     * 本该省掉的一次「请先同步」照样弹出来（防线变成纯噪音）。
     *
     * 更要命的是**它挡不住真正的危险**：覆盖远端丢失的是"别人改的字段值"，
     * 而字段值已经逐字进了摘要；时间戳就算对不上，也不会让你丢一条密码。
     * ⇒ 取舍很清楚：**宁可在时间戳上宽一点，也不能在字段值上漏一点。**
     *
     * @return 十六进制指纹；**同一进程内可比**，不承诺跨版本稳定（升级后应视为"没记录"）。
     */
    fun contentFingerprint(content: KdbxMappedContent): String {
        val digest = MessageDigest.getInstance("SHA-256")
        // ⚠️ 必须把**回收站子树**一起算进去：在回收站里删/恢复也不会改动 [items]，
        //    漏掉它就会出现"内容其实变了、指纹却没变"的假阴性 —— 而假阴性在这里的
        //    直接后果是**放过一次可能覆盖远端改动的写入**（比假阳性危险得多）。
        val living = content.items.asSequence().map { it to false }
        val trashed = content.trashItems.asSequence().map { it.item to true }
        (living + trashed).forEach { (item, inTrash) ->
            digest.update(if (inTrash) TRASHED_MARK else LIVING_MARK)
            digest.update(item.id.toByteArray(Charsets.UTF_8))
            digest.update(item.title.toByteArray(Charsets.UTF_8))
            digest.update(item.username.toByteArray(Charsets.UTF_8))
            digest.update(item.password.toByteArray(Charsets.UTF_8))
            digest.update(item.notes.toByteArray(Charsets.UTF_8))
            // ⚠️ 这里**刻意不 digest `updatedAt`**：见 KDoc「为什么不把 updatedAt 算进去」。
        }
        content.folders.forEach { folder ->
            digest.update(folder.id.toByteArray(Charsets.UTF_8))
            digest.update(folder.name.toByteArray(Charsets.UTF_8))
        }
        digest.update(content.recycleBinCount.toString().toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    /** 在用 / 回收站标记：同一条目从"在用"挪进回收站，指纹也必须跟着变。 */
    private const val LIVING_MARK = 0.toByte()
    private const val TRASHED_MARK = 1.toByte()
}
