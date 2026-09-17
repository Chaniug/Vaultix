/*
 * Vaultix — data:kdbx
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * KDBX 写回的**安全落盘**：原子替换 + `.kdbx.bak` 备份。
 *
 * ## 为什么必须原子替换（而不是直接覆写）
 *
 * 「写一半崩溃」= **整库损坏**。KDBX 是一个整体加密的文件，没有"部分有效"这回事：
 * 头部写进去、内容没写完 ⇒ 文件既解不开也不是原来那份，用户的密码全没了。
 * 顺序 **临时文件 → fsync → rename** 让"替换"成为**单个原子操作**：
 * rename 之前旧文件完好，rename 之后新文件完好，**没有中间态**。
 *
 * ## 为什么还要 `.kdbx.bak`
 *
 * 原子替换只防"写到一半失败"，**不防"写完了但内容是坏的"**（编码 bug、
 * 我们的映射层改错了内存里的库）。`.bak` 是那种情况下的**唯一退路** ——
 * 它保存的是**替换前的那一份**。代价是每库多一份文件大小，值得。
 *
 * ## 顺序：先备份，再替换
 *
 * ❌ 错误顺序：写临时文件 → rename → 才备份
 *    —— 那时旧文件已经被覆盖，备份下来的就是新的（等于没备份）。
 * ✅ 正确顺序：**先把当前文件复制成 .bak**（在它还没被碰之前）→ 写临时文件 → rename。
 *
 * ## 与「明文不落盘」的关系
 *
 * 本文件写的全部是**已在内存里加密好的 `.kdbx` 字节**（密文），
 * 与 KDBX 明文只活在内存的约定**不冲突**。
 * ⚠️ 但**临时文件名不能泄露信息**，且失败时必须清理（见 [discardTemp]）——
 *   一个残留的 `*.tmp` 里躺着完整密钥库，是没必要留的风险面。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.kdbx

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * 把 [bytes] 安全地写入 [target]（原子替换 + 备份）。
 *
 * @param target 目标文件（本地 SAF 场景下是实现方给的**沙箱副本路径**；
 *   网盘场景下是 App 私有目录里的工作副本，再由上传逻辑推上去）。
 * @param backupSuffix 备份后缀。默认 `.bak` ⇒ `vault.kdbx` → `vault.kdbx.bak`。
 * @return 备份文件（若产生了备份）；调用方可用它在出错时提示用户。
 */
internal object KdbxAtomicWriter {

    const val BACKUP_SUFFIX: String = ".bak"

    fun write(target: File, bytes: ByteArray): File? {
        val parent = requireParentDirectory(target)

        // ① 先把当前内容备份走 —— 必须在动 target 之前。
        //    ⚠️ 用 copyTo 而不是 rename：rename 会让 target 消失一瞬间，
        //      万一后面失败，用户就没文件了。copy 之后 target 始终存在。
        val backup = if (target.exists()) {
            val bak = File(parent, target.name + BACKUP_SUFFIX)
            runCatching { target.copyTo(bak, overwrite = true) }
                .getOrElse { error ->
                    // 备份失败**不阻断**写入（宁可没有退路也要先把用户的新改动存下来），
                    // 但要让调用方知道 —— 返回 null 即"本次没有备份"。
                    null
                }
        } else {
            null
        }

        // ② 写临时文件 + fsync。
        //    临时文件建在**同一个目录**下：跨目录 rename 可能退化成 copy+delete，
        //    那就重新引入了"非原子"的窗口。
        val temp = File(parent, target.name + ".tmp")
        try {
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.flush()
                // fsync：不 fsync 的话，进程崩溃时数据可能还躺在页缓存里，
                // rename 之后文件"存在但内容为空/截断"。
                output.fd.sync()
            }
        } catch (error: IOException) {
            deleteQuietly(temp)
            throw error
        }

        // ③ 原子替换。
        replaceAtomically(target = target, temp = temp, bytes = bytes)
        return backup
    }

    /**
     * 取（必要时创建）目标文件的父目录。
     *
     * ⚠️ 抽出来是为了让 [write] 的 throw 数落在 detekt `ThrowsCount`（上限 2）之内 ——
     * 而这里的两条都是**前置条件**（没有父目录 / 建不出目录），语义上属于同一类，
     * 放在一起也更好读。放宽阈值不是选项：那等于把门禁关掉。
     */
    private fun requireParentDirectory(target: File): File {
        val parent = target.parentFile
            ?: throw IOException("目标文件没有父目录：${target.path}")
        if (!parent.exists() && !parent.mkdirs()) {
            throw IOException("无法创建目录：${parent.path}")
        }
        return parent
    }

    /**
     * 用 [temp] 替换 [target]（原子优先，退化时覆写）。
     *
     * ## 两条路径
     *
     * 1. **正常**：`target.delete()` 成功后 `renameTo` ⇒ 原子。
     * 2. **退化**：少数文件系统不允许 delete 后 rename（或被占用）⇒ 就地覆写。
     *    不再原子，但内容写进去了；且**此时 backup 一定已存在**，最坏情况仍有退路。
     *
     * ⚠️ 无论走哪条，**临时文件都必须清掉**：残留的 `.tmp` 里是一份完整的密钥库。
     */
    private fun replaceAtomically(target: File, temp: File, bytes: ByteArray) {
        if (target.exists() && !target.delete()) {
            runCatching {
                FileOutputStream(target).use { output ->
                    output.write(bytes)
                    output.flush()
                    output.fd.sync()
                }
            }.getOrElse { error ->
                deleteQuietly(temp)
                throw IOException("写入失败，且无法替换目标文件：${target.path}", error)
            }
            deleteQuietly(temp)
            return
        }

        if (!temp.renameTo(target)) {
            deleteQuietly(temp)
            throw IOException("无法替换目标文件：${target.path}")
        }
    }

    /** 清理临时文件。**失败时一定要调** —— 残留的 `.tmp` 里是完整的密钥库。 */
    fun discardTemp(target: File) {
        deleteQuietly(File(target.parentFile, target.name + ".tmp"))
    }

    /**
     * 直接删除一个文件、忽略失败。
     *
     * ⚠️ 刻意**不**与 [discardTemp] 重载成同一个名字：两者参数都是 `File`，
     * 重载解析会歧义（编译器实测报 `overload resolution ambiguity`）。
     * 一个收「目标文件」、一个收「要删的文件」，语义不同，名字就该不同。
     */
    private fun deleteQuietly(file: File) {
        runCatching { if (file.exists()) file.delete() }
    }

    /** 该库的备份文件。 */
    fun backupOf(target: File): File = File(target.parentFile, target.name + BACKUP_SUFFIX)
}
