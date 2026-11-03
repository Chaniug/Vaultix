/*
 * Vaultix — data:kdbx
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * **KDBX 自定义图标的字节缓存**（2026-11-03，图标三级兜底的第一级）。
 *
 * ## 为什么字节不放在 `VaultItem` 里
 *
 * `VaultItem.customIconId` 只存 UUID，字节在这里按 UUID 存一份。两个理由：
 *
 * 1. **去重**：同一个自定义图标常被多个条目共用（用户在 KeePassXC 里给一组条目设同一图标），
 *    放进条目就是 N 份内存副本；
 * 2. **Compose 稳定性**：`VaultItem` 里一旦出现 `ByteArray`（引用相等），整个 data class
 *    的 equals 就退化成"看是不是同一个数组"，列表会平白多出重组 —— 这在密码列表这种
 *    一屏几十行的场景里是实打实的掉帧来源。
 *
 * ## 生命周期
 *
 * 只活在内存里，随**库解锁时的解析**填充（`KeePassDatabase.toMappedContent`）。
 * `lockAll()` 时整体清空（见 `Kdbx.lockAll`）；`lock(vaultId)` **不清** ——
 * 清不干净（这里没有"这个图标属于哪个库"的信息），而残留只是几 KB 的 PNG，
 * 下次解锁会按 UUID 覆盖。**宁可多留一点内存，也不要引入一张会漂移的反向索引表**。
 *
 * ⚠️ 内容是**用户数据**（图标本身不是密钥，但属于库内容）：不进日志、不落盘。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.kdbx

import java.util.concurrent.ConcurrentHashMap

internal object KdbxCustomIconStore {

    /** UUID 字符串 → PNG 原始字节。用 `ConcurrentHashMap`：映射在解锁线程，读取在 UI 线程。 */
    private val icons = ConcurrentHashMap<String, ByteArray>()

    fun put(id: String, png: ByteArray) {
        if (png.isEmpty()) return
        icons[id] = png
    }

    fun get(id: String?): ByteArray? = id?.let { icons[it] }

    fun clear() {
        icons.clear()
    }
}
