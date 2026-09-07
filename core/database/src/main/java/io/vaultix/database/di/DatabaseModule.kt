/*
 * Vaultix — core:database
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.database.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.vaultix.database.VaultixDatabase
import io.vaultix.database.dao.CipherDao
import io.vaultix.database.dao.FolderDao
import io.vaultix.database.dao.PendingOpDao
import io.vaultix.database.dao.VaultDao
import androidx.room.Room
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): VaultixDatabase =
        Room.databaseBuilder(context, VaultixDatabase::class.java, "vaultix.db").build()

    @Provides fun provideVaultDao(db: VaultixDatabase): VaultDao = db.vaultDao()
    @Provides fun provideCipherDao(db: VaultixDatabase): CipherDao = db.cipherDao()
    @Provides fun provideFolderDao(db: VaultixDatabase): FolderDao = db.folderDao()
    @Provides fun providePendingOpDao(db: VaultixDatabase): PendingOpDao = db.pendingOpDao()
}
