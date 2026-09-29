/*
 * Vaultix — data:repository
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 */

package io.vaultix.data.repository.kdbx

/**
 * 一份缓存的库文件快照（**密文** + 当时的版本令牌）。
 *
 * @property bytes kdbx 文件的**原始字节**（KDBX 本身就是密文，落盘安全边界见
 *   [KdbxFileCache] 的 KDoc）。
 * @property versionToken 取这份字节时服务端给的 `versionToken`；`null` = 该来源
 *   不提供版本信息（此时**无法**判定缓存是否仍是最新，见 [CachedKdbxFileSource]）。
 */
class CachedKdbxFile(val bytes: ByteArray, val versionToken: String?)

/**
 * KDBX 远端文件的**本地缓存**（多库锁模型定稿 **批次 B1**，2026-09-30）。
 *
 * ## 为什么需要它（实测依据）
 *
 * 真机实测：KDBX 解锁 3.58 s 里 **≈3.4 s 花在「读文件」上**
 * （`Kdbx.unlock` 是「先 `source.read()` → 再 KDF+解析」，见 `Kdbx.kt:117-148`；
 * 日志时序见定稿 §5 批次 B）。库源在 OneDrive，而设备上没有本地副本
 * ⇒ **每次解锁都要重新下载整份文件**。缓存后只需一次元数据往返（`stat()`）。
 *
 * ## 安全取舍（用户已复核同意）
 *
 * 缓存的是**已加密**的 kdbx —— KDBX 本就是「文件可放任意位置」的格式，
 * 其威胁模型已假定攻击者可能拿到文件；**比本项目现有的「从不」档免认证信封
 * （包房钥匙）更不敏感**。约束：
 *
 * 1. **只缓存远端来源**（SAF 本地的 token 是内容 SHA-256，缓存反而多做一次读）；
 * 2. **只存密文**，不存任何解密后的内容或密钥材料；
 * 3. **删库 / 退出登录时一并清**（见 `VaultRepositoryImpl`）；
 * 4. 落在 **app 私有存储**，且调用方应指向 `noBackupFilesDir`（不进系统备份）。
 *
 * ## 为什么是接口
 *
 * 实现要碰文件系统；解耦后 [CachedKdbxFileSource] 可以在纯 JVM 单测里
 * 配一个内存实现（本项目 `data:kdbx` 的既有纪律：可测优先）。
 */
interface KdbxFileCache {

    /** 读缓存；没有 / 不可信（尺寸对不上）时返回 `null`。 */
    suspend fun load(key: String): CachedKdbxFile?

    /** 写缓存（覆盖）。 */
    suspend fun save(key: String, file: CachedKdbxFile)

    /** 删缓存。幂等。 */
    suspend fun remove(key: String)
}
