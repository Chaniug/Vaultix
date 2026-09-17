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
    /** 账号标签（Bitwarden = 邮箱，用于「Bitwarden · alice@mail.com」；KDBX 为 null），v2 新增 */
    val account: String? = null,
    /** 上次同步的服务端 revision（Bitwarden 用；KDBX 为 null） */
    val revisionDate: String? = null,
    val createdAt: Long,
    /**
     * ★ KDBX 网盘同步状态（`KdbxSyncStatus` 的名称），v3 新增。
     *
     * ⚠️ **必须可空**（null = 非网盘库 / 还没同步过）：
     * Bitwarden 库与本地 SAF 库都不适用，用 `LOCAL_ONLY` 之类的默认值去填它们
     * 会制造"本该没有状态的地方有了状态"，UI 就会在 Bitwarden 库上显示一个
     * 毫无意义的"仅本地"角标。
     */
    val syncStatus: String? = null,
    /**
     * ★ 上次成功同步时远端的版本令牌（eTag / 内容 SHA-256），v3 新增。
     *
     * 它是**冲突检测的基线**：保存前拿它当条件写的 `expectedVersion`，
     * 服务端据此判定"别人是不是改过了"。
     *
     * ⚠️ 这不是密钥材料（只是一串服务端给的不透明标识），落 Room 是安全的。
     */
    val remoteVersionToken: String? = null,
    /**
     * ★ 上次成功同步的时间（毫秒），v3 新增。用于展示"上次同步：3 分钟前"。
     */
    val lastSyncedAt: Long? = null,
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
