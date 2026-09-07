/*
 * Vaultix — core:database
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 密码库（一个 Bitwarden 账号或一个 KDBX 文件）。
 */
@Entity(tableName = "vaults")
data class VaultEntity(
    @PrimaryKey val id: String,
    /** VaultKind 名称：BITWARDEN / KDBX */
    val kind: String,
    val displayName: String,
    /** 服务端 URL 或本地文件 URI */
    val origin: String,
    /** 上次同步的服务端 revision（Bitwarden 用；KDBX 为 null） */
    val revisionDate: String? = null,
    val createdAt: Long,
)

/**
 * 条目缓存。
 *
 * ⚠️ **只存密文**：[encryptedPayload] 为服务端原始 CipherDto 序列化结果，
 * 字段均为 EncString。这样既保证落盘安全，也保证与服务端往返无损
 * （明文字段的解析一律发生在内存，用完即清零，见 core:crypto 的 SecureBytes）。
 */
@Entity(
    tableName = "ciphers",
    foreignKeys = [
        ForeignKey(
            entity = VaultEntity::class,
            parentColumns = ["id"],
            childColumns = ["vaultId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("vaultId"), Index(value = ["vaultId", "favorite"])],
)
data class CipherEntity(
    @PrimaryKey val id: String,
    val vaultId: String,
    val type: Int,
    /** 密文载荷（CipherDto JSON，字段保持服务端原样） */
    val encryptedPayload: String,
    val revisionDate: String,
    val deletedDate: String? = null,
    val folderId: String? = null,
    val favorite: Boolean = false,
)

@Entity(
    tableName = "folders",
    foreignKeys = [
        ForeignKey(
            entity = VaultEntity::class,
            parentColumns = ["id"],
            childColumns = ["vaultId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("vaultId")],
)
data class FolderEntity(
    @PrimaryKey val id: String,
    val vaultId: String,
    /** EncString 密文 */
    val encryptedName: String,
    val revisionDate: String,
)

/** 待上传操作（离线时入队，联网后推送）。 */
@Entity(tableName = "pending_ops", indices = [Index("vaultId")])
data class PendingOpEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val vaultId: String,
    val cipherId: String,
    /** PendingOpType 名称：CREATE / UPDATE / SOFT_DELETE / DELETE / RESTORE */
    val op: String,
    /** CipherRequest JSON（密文）；删除类操作为 null */
    val payload: String? = null,
    val createdAt: Long,
    val retryCount: Int = 0,
)
