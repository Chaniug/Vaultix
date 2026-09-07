/*
 * Vaultix — core:database
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.database

import androidx.room.Database
import androidx.room.RoomDatabase
import io.vaultix.database.dao.CipherDao
import io.vaultix.database.dao.FolderDao
import io.vaultix.database.dao.PendingOpDao
import io.vaultix.database.dao.VaultDao
import io.vaultix.database.entity.CipherEntity
import io.vaultix.database.entity.FolderEntity
import io.vaultix.database.entity.PendingOpEntity
import io.vaultix.database.entity.VaultEntity

/**
 * 本地密文缓存库。
 *
 * 只落盘密文（EncString），明文一律不入库；清单一律见 Docs/09。
 *
 * version 1 为初版。后续升级必须提供 Migration（不可依赖
 * fallbackToDestructiveMigration，否则用户本地数据会被清空）。
 */
@Database(
    entities = [
        VaultEntity::class,
        CipherEntity::class,
        FolderEntity::class,
        PendingOpEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class VaultixDatabase : RoomDatabase() {
    abstract fun vaultDao(): VaultDao
    abstract fun cipherDao(): CipherDao
    abstract fun folderDao(): FolderDao
    abstract fun pendingOpDao(): PendingOpDao
}
