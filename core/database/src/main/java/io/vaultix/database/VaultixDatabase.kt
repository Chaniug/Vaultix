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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.vaultix.database.dao.AtomicWriteDao
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
    version = 3,
    exportSchema = true,
)
abstract class VaultixDatabase : RoomDatabase() {
    abstract fun vaultDao(): VaultDao
    abstract fun cipherDao(): CipherDao
    abstract fun folderDao(): FolderDao
    abstract fun pendingOpDao(): PendingOpDao

    /**
     * 「本地行 + 入队」的**原子写**（2026-09-16 新增）。
     * 见 [io.vaultix.database.dao.AtomicWriteDao] 的 KDoc：它防的是
     * 「行落库了但队列没记上」这个中间态，那会导致条目被误删或被旧版覆盖。
     */
    abstract fun atomicWriteDao(): AtomicWriteDao

    companion object {
        /** v1→v2：vaults 增加 account 列（Bitwarden 账号邮箱，用于库列表展示）。 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE vaults ADD COLUMN account TEXT")
            }
        }

        /**
         * v2→v3：vaults 增加 KDBX 网盘同步的三列。
         *
         * ## 为什么三列都要可空（不加 NOT NULL DEFAULT）
         *
         * 存量库里**绝大多数是 Bitwarden 与本地 SAF 库**，它们跟网盘同步无关。
         * 若给 `syncStatus` 一个 `NOT NULL DEFAULT 'LOCAL_ONLY'`，
         * 那些库在 UI 上就会突然多出一个"仅本地"角标 —— 那是**凭空造出来的状态**，
         * 用户会以为自己的 Bitwarden 库出了同步问题。
         * ⇒ 三列全可空，null = "这个库不适用网盘同步"。
         *
         * ⚠️ 三列必须**一次加完**：分成三条 Migration 会让版本号与
         *    `@Database(version=…)` 对不上（Room 校验 schema 时会发现列缺失而崩）。
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE vaults ADD COLUMN syncStatus TEXT")
                db.execSQL("ALTER TABLE vaults ADD COLUMN remoteVersionToken TEXT")
                db.execSQL("ALTER TABLE vaults ADD COLUMN lastSyncedAt INTEGER")
            }
        }
    }
}
