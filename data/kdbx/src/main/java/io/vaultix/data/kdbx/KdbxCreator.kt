/*
 * Vaultix — data:kdbx
 * Copyright (C) 2026 Vaultix contributors
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * **新建一个空白 KDBX 库**（M2 阶段 B · 批次 W0）。
 *
 * ## 为什么这是阶段 B 里唯一"从零造物"的地方
 *
 * 阶段 A 与 B 的其它动作都建立在"已有一个库、读成内存模型、改它"之上；
 * 只有这里是**从无到有** —— 因此也是唯一需要把「格式选型」写死的地方。
 *
 * ## ★ 本应用写出的格式（2026-09-30 用户拍板：只做最新、不向下兼容）
 *
 * 判据不是"我们觉得哪个好"，而是**用户 2026-09-30 的硬要求**：
 * 「库的标准要匹配最新的 kdbx 格式，只做最新兼容，4.1 以上，不向下兼容 3.0 等」
 * 「库要能被 DX、XC 之类的 kdbx 密码管理器打开」。
 *
 * 于是写入参数**全部取 kotpass 的默认值**（已逐字节反编译核实，
 * 见 `.ai/` 中的施工单 §2.5；`KdbxFormat.SUPPORTED_MAJOR` 也据此定为 4）：
 *
 * | 项 | 取值 | 出处 |
 * |---|---|---|
 * | 格式版本 | **4.1** | `FormatVersion(4, 1)`（`DatabaseHeader.Ver4x.Companion.create`） |
 * | 加密套件 | **AES-256** | `BaseCiphers.Aes`（★ 不是 ChaCha20 —— 老版 XC/DX 不认） |
 * | 压缩 | **GZip** | `Compression.GZip`（XC 的默认一致） |
 * | KDF | **Argon2** | `KdfParameters.Argon2.default(seed32)` |
 *
 * ⚠️ **不要"顺手加强"这些参数**：换 cipher（如 ChaCha20）或换 KDF（如 Argon2id 高参数）
 *   都可能让**别人**（XC/DX）打不开或提示"KDF 参数过旧/过新"。
 *   验收标准是**三方互操作**，不是"我们的参数看起来更强"。
 *   ⇒ 真要改，必须先在两边的真机上实测过（施工单 §6.0）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.kdbx

import app.keemobile.kotpass.cryptography.EncryptedValue
import app.keemobile.kotpass.database.Credentials
import app.keemobile.kotpass.database.KeePassDatabase
import app.keemobile.kotpass.database.modifiers.withRecycleBin
import app.keemobile.kotpass.models.Meta

/**
 * 新建库的产物：**内存模型 + 编码后的字节 + 打开它所用的凭据**。
 *
 * 三者一起返回是刻意的：调用方建完库要**立刻登记会话**，而登记会话用的 `credentials`
 * 必须与编码时那一组**完全一致**（拿另一组 encode 会得到一个"自己都打不开"的文件，
 * 见 [KdbxSession.credentials] 的 KDoc）。分开算两次凭据就等于给这个坑留了门。
 */
internal class CreatedKdbx(
    val database: KeePassDatabase,
    val credentials: Credentials,
    /** 编码后的 `.kdbx` 文件字节。 */
    val bytes: ByteArray,
    /** 诊断标签（只记"用了什么凭据形态"，不含任何密钥材料）。 */
    val credentialLabel: String,
)

/** 新建空白 KDBX 库（纯函数；落盘由调用方负责，便于单测直接喂字节给 `KdbxOpener`）。 */
internal object KdbxCreator {

    /** 写入文件头的生成器标识（XC/DX 的「数据库信息」里会显示它）。 */
    const val GENERATOR: String = "Vaultix"

    /** 新建库的根组名兜底（名字为空时用；组名不能是空串）。 */
    const val DEFAULT_ROOT_NAME: String = "Vaultix"

    /** 目标格式（文档与错误文案共用；单个字符串，别在别处再写一遍）。 */
    const val TARGET_FORMAT: String = "KDBX 4.1"

    /**
     * 主密码（可选 keyfile）→ kotpass 凭据。
     *
     * ⚠️ 与读路径的 [buildCredentialCandidates] **不同**：那里要枚举 keyfile 的历史形态
     * （XML / 裸 32 字节 / hex 文本），因为不知道别的工具当初写的是哪一种；
     * 而**新建**时是我们自己决定写什么，只有一种形态 —— 直接把 keyfile 原始字节喂进去即可。
     * 别在这里"顺手"复用候选枚举：那会把一个明确的动作变成一次猜测。
     *
     * ## ★★★ keyfile 必须先 copy：kotpass 会**原地改写**传进去的数组
     *
     * 上游 `EncryptedValue.fromBinary(bytes)` 的实现是：
     *
     * ```kotlin
     * val salt = ByteArray(bytes.size); random.nextBytes(salt)
     * for (i in bytes.indices) { bytes[i] = bytes[i] xor salt[i] }   // ← 改的是**调用方的数组**
     * ```
     *
     * 那是它"内存里不裸放密钥"的设计（`getBinary()` 再 XOR 回来），
     * 对**它自己那一份**是自洽的 —— 但**调用方手上的数组从此就是废数据**。
     * 而 `parseKeyfile(32 字节)` 恰恰是"**原样返回同一个数组引用**"（上游源码），
     * 于是我们传进去的那份 32 字节 keyfile 会被就地 XOR 掉。
     *
     * 症状（本文件单测实测）：**同一个数组第二次交给 kotpass，就用它开不了库了** ——
     * 而第一次是成功的，所以不会有任何报错线索。
     *
     * ⇒ 边界只此一处：**交给 kotpass 之前先 copy**，调用方（以及我们自己持有的那份）
     *   永远不被动。⚠️ 不要以为"反正开完就不用了" —— keyfile 字节会被快速解锁的信封
     *   复用（同一份 keyfile 服务多个库），一旦被改写就是"某个库突然打不开了"。
     */
    fun credentialsFor(password: String, keyFileBytes: ByteArray?): Credentials =
        if (keyFileBytes == null || keyFileBytes.isEmpty()) {
            Credentials.from(EncryptedValue.fromString(password))
        } else {
            Credentials.from(
                EncryptedValue.fromString(password),
                keyFileBytes.copyOf(),
            )
        }

    /**
     * 造一个**空白 4.1 库**：一个根组 + 一个回收站组。
     *
     * 为什么要先建回收站（而不是等用户第一次删除时再建）：
     * - 「删除」在 KDBX 里的语义是**移进回收站**（`Meta.recycleBinUuid` 指向的组），
     *   不是直接抹掉。库一开始就没有回收站的话，第一次删除要么得**临时补建**
     *   （一个"删除"动作里偷偷改了库结构，很难解释），要么只能退化成**永久删除**；
     * - 而且 `Meta.recycleBinEnabled` 一旦是真的，XC/DX 的界面会正确显示「回收站」分组 ——
     *   用户在第一台设备上建库、第二台设备上删除，行为一致。
     */
    fun blankDatabase(name: String, credentials: Credentials): KeePassDatabase {
        val rootName = name.ifBlank { DEFAULT_ROOT_NAME }
        val created = KeePassDatabase.Ver4x.create(
            rootName = rootName,
            // ⚠️ `meta` 没有默认值（kotpass 的 `create` 必填），必须显式给。
            //    `generator` 写成本应用名：XC/DX 的「数据库信息」里显示的就是它 ——
            //    留着 kotpass 的默认值等于让别人以为这库是 kotpass 建的。
            meta = Meta(name = rootName, generator = GENERATOR),
            credentials = credentials,
        )
        // withRecycleBin 的 block 形如 `KeePassDatabase.(recycleBinUuid) -> KeePassDatabase`；
        // 这里只取"当前这份（已带回收站）"，不改内容 ⇒ 返回 `this`（= 形参里的那个库）。
        return created.withRecycleBin { this }
    }

    /**
     * 新建 + 编码一步到位。
     *
     * @param name 库名（**同时**用作根组名与 `Meta.name`：XC/DX 两处都会显示，
     *   留空一份会让同一份库在不同工具里叫两个名字）。
     */
    fun createEmpty(
        name: String,
        password: String,
        keyFileBytes: ByteArray? = null,
    ): CreatedKdbx {
        val credentials = credentialsFor(password, keyFileBytes)
        val database = blankDatabase(name, credentials)
        return CreatedKdbx(
            database = database,
            credentials = credentials,
            bytes = KdbxEncoder.encode(database),
            credentialLabel = if (keyFileBytes == null || keyFileBytes.isEmpty()) {
                LABEL_PASSWORD_ONLY
            } else {
                LABEL_PASSWORD_KEY
            },
        )
    }
}

/** 诊断用标签（与读路径的候选标签同风格；**不含任何密钥材料**）。 */
private const val LABEL_PASSWORD_ONLY = "created/password-only"
private const val LABEL_PASSWORD_KEY = "created/password+key"
