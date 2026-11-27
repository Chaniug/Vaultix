/*
 * Vaultix — data:bitwarden
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 溯源声明（GPL-3.0 合规）
 * 上传的协议分派（Direct Multipart / Azure PUT）与密钥包裹顺序参考 Bastion 项目
 * （GPL-3.0，Copyright 2025 JoyinJoester）的 `BitwardenAttachmentExecutor`；
 * 本文件为独立实现，不复用其本地缓存 / 私有库逻辑（Vaultix 走 Bitwarden 路线）。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.bitwarden.attachment

import io.vaultix.crypto.SymmetricCryptoKey
import io.vaultix.crypto.VaultixCrypto
import io.vaultix.data.bitwarden.api.BitwardenVaultApi
import io.vaultix.data.bitwarden.di.AttachmentHttpClient
import io.vaultix.data.bitwarden.di.BitwardenApiFactory
import io.vaultix.data.bitwarden.model.AttachmentDto
import io.vaultix.data.bitwarden.model.AttachmentUploadRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bitwarden 附件**加密 + 上传**编排器（P0 · 2026-11-28，与 Downloader 并列）。
 *
 * 链路：生成附件独立密钥并包裹 → 加密文件字节与文件名 →
 * `POST /attachment/v2` 拿上传方式 → 按 `fileUploadType` 上传 → 返回条目快照。
 *
 * ## 两条上传路径，client 完全不同（安全红线）
 *
 * | `fileUploadType` | 目标 | client | 原因 |
 * |---|---|---|---|
 * | **0 = Direct Multipart** | 本服务端 `/api/ciphers/...` | 默认 Retrofit（带 Bearer） | 同源 API，需要鉴权 |
 * | **1 = Azure Blob PUT** | 第三方签名 URL | [@AttachmentHttpClient]（**无** Bearer） | 令牌不能 leaking 给对象存储日志 |
 *
 * ⚠️ 方案文档早期版本把 0/1 写反了；以本表与 `AttachmentUploadResponse` 注释为准
 * （bastion 常量 `FILE_UPLOAD_TYPE_DIRECT=0` 亦一致）。
 *
 * ## 密钥生命周期
 *
 * 附件密钥（64B）在**每一个**失败分支都 `clear()`，成功路径在 `finally` 清；
 * 包裹态 EncString（`wrappedKey`）是服务端要求的持久形式，不含明文材料。
 */
@Singleton
class BitwardenAttachmentUploader @Inject constructor(
    private val apiFactory: BitwardenApiFactory,
    @AttachmentHttpClient private val httpClient: OkHttpClient,
    private val crypto: VaultixCrypto,
) {
    /** 一次上传的结果：`attachments` 取自响应的 `cipherResponse`，用于本地列表更新。 */
    data class Uploaded(
        val attachmentId: String,
        val wrappedKey: String,
        /** 加密后的文件名 EncString（服务端存的就是它，本地兜底构造 DTO 时要用）。 */
        val encryptedFileName: String,
        val attachments: List<AttachmentDto>,
        val encryptedSize: Long,
    )

    /**
     * 把一个附件明文加密并上传到 [cipherId]。**不关闭**任何调用方持有的流。
     *
     * @param wrappingKey 用于包裹新附件密钥的钥匙（条目密钥优先，账号密钥兜底）。
     *   下载端会用候选密钥逐个尝试解包，因此这里选哪把都能被解开。
     */
    suspend fun upload(
        server: String,
        cipherId: String,
        fileName: String,
        plain: ByteArray,
        wrappingKey: SymmetricCryptoKey,
    ): Result<Uploaded> = withContext(Dispatchers.IO) {
        runCatching {
            val (attachmentKey, wrappedKey) =
                BitwardenAttachmentCrypto.generateAndWrapAttachmentKey(wrappingKey, crypto)

            val encrypted: ByteArray
            val encryptedFileName: String
            try {
                encrypted = BitwardenAttachmentCrypto.encrypt(plain, attachmentKey)
                encryptedFileName =
                    BitwardenAttachmentCrypto.encryptStringForAttachment(fileName, wrappingKey, crypto)
            } catch (e: Throwable) {
                attachmentKey.clear()
                throw e
            }

            val api = apiFactory.vault(server)
            val meta = try {
                api.createAttachmentUploadUrl(
                    cipherId,
                    AttachmentUploadRequest(
                        key = wrappedKey,
                        fileName = encryptedFileName,
                        fileSize = encrypted.size.toString(),
                    ),
                )
            } catch (e: Throwable) {
                encrypted.fill(0)
                attachmentKey.clear()
                throw e
            }

            val attachmentId = meta.attachmentId?.takeIf { it.isNotBlank() }
                ?: meta.cipherResponse?.attachments?.maxByOrNull { it.id }?.id
                ?: run {
                    encrypted.fill(0)
                    attachmentKey.clear()
                    error("服务端未返回附件 id")
                }

            try {
                when (meta.fileUploadType) {
                    FILE_UPLOAD_TYPE_DIRECT -> uploadDirect(
                        api = api,
                        cipherId = cipherId,
                        attachmentId = attachmentId,
                        wrappedKey = wrappedKey,
                        encrypted = encrypted,
                    )
                    else -> uploadAzure(
                        url = meta.url ?: error("服务端未返回 Azure 上传地址"),
                        encrypted = encrypted,
                    )
                }
            } catch (e: Throwable) {
                encrypted.fill(0)
                attachmentKey.clear()
                throw e
            } finally {
                encrypted.fill(0)
                attachmentKey.clear()
            }

            Uploaded(
                attachmentId = attachmentId,
                wrappedKey = wrappedKey,
                encryptedFileName = encryptedFileName,
                attachments = meta.cipherResponse?.attachments.orEmpty(),
                encryptedSize = encrypted.size.toLong(),
            )
        }
    }

    /** Direct Multipart：同源 API，用默认带 Bearer 的 Retrofit client。 */
    private suspend fun uploadDirect(
        api: BitwardenVaultApi,
        cipherId: String,
        attachmentId: String,
        wrappedKey: String,
        encrypted: ByteArray,
    ) {
        val keyPart = MultipartBody.Part.createFormData("key", wrappedKey)
        val dataPart = MultipartBody.Part.createFormData(
            "data",
            "blob",
            encrypted.toRequestBody(OCTET_STREAM.toMediaType()),
        )
        val response = api.uploadAttachmentDirect(cipherId, attachmentId, keyPart, dataPart)
        require(response.isSuccessful) { "附件上传失败：HTTP ${response.code()}" }
    }

    /** Azure Blob PUT：第三方签名 URL，**必须**走无鉴权 client。 */
    private suspend fun uploadAzure(url: String, encrypted: ByteArray) {
        val request = Request.Builder()
            .url(url)
            .put(encrypted.toRequestBody(OCTET_STREAM.toMediaType()))
            .header("x-ms-blob-type", "BlockBlob")
            .build()
        httpClient.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "附件上传失败：HTTP ${response.code}" }
        }
    }

    companion object {
        /** 官方 `FileUploadType.Direct`（上传回本服务端，带 Bearer）。 */
        private const val FILE_UPLOAD_TYPE_DIRECT = 0

        private const val OCTET_STREAM = "application/octet-stream"
    }
}
