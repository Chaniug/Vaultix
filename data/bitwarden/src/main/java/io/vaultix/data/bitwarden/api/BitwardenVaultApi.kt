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
 * 端点划分方式参考 Bastion 项目（GPL-3.0，Copyright 2025 JoyinJoester）的 BitwardenApi.kt；
 * 具体路径与请求/响应以 Bitwarden 官方 API 为准，本文件为独立编写。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.data.bitwarden.api

import io.vaultix.data.bitwarden.model.AttachmentDownloadResponse
import io.vaultix.data.bitwarden.model.AttachmentUploadRequest
import io.vaultix.data.bitwarden.model.AttachmentUploadResponse
import io.vaultix.data.bitwarden.model.CipherRequest
import io.vaultix.data.bitwarden.model.CipherResponse
import io.vaultix.data.bitwarden.model.FolderDto
import io.vaultix.data.bitwarden.model.FolderListResponse
import io.vaultix.data.bitwarden.model.FolderRequest
import io.vaultix.data.bitwarden.model.SyncResponse
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.PUT
import retrofit2.http.Path

/**
 * Bitwarden 数据服务（`/api` 基址）端点。
 *
 * M1 首批只覆盖同步与条目 / 文件夹 CRUD；附件下载 2026-10-21 补齐，Send 仍留后续。
 */
interface BitwardenVaultApi {

    /** 全量同步。 */
    @GET("sync")
    suspend fun sync(): SyncResponse

    /**
     * 轻量预检：只返回时间戳（毫秒）。
     * ⚠️ Vaultwarden 会忽略 `sinceRevisionDate` 增量游标（等于空转），
     *    因此必须先查这里，再决定是否走全量 [sync]。
     */
    @GET("accounts/revision-date")
    suspend fun revisionDate(): Long

    @GET("ciphers/{id}")
    suspend fun cipher(@Path("id") id: String): CipherResponse

    @POST("ciphers")
    suspend fun createCipher(@Body body: CipherRequest): CipherResponse

    @PUT("ciphers/{id}")
    suspend fun updateCipher(@Path("id") id: String, @Body body: CipherRequest): CipherResponse

    /** 软删（进回收站）。 */
    @DELETE("ciphers/{id}")
    suspend fun softDeleteCipher(@Path("id") id: String)

    /**
     * 附件下载地址（2026-10-21）。
     *
     * ⚠️ 返回的是**签名 URL**（第三方对象存储），下载请求**不能**走挂了
     * Bearer 的 OkHttp client —— token 会泄漏给第三方域名（见
     * [io.vaultix.data.bitwarden.attachment.BitwardenAttachmentDownloader]）。
     */
    @GET("ciphers/{id}/attachment/{attachmentId}")
    suspend fun attachmentDownload(
        @Path("id") id: String,
        @Path("attachmentId") attachmentId: String,
    ): AttachmentDownloadResponse

    // ---- 附件写方向（P0 · 2026-11-28）------------------------------------

    /**
     * 申请附件上传地址（`POST /ciphers/{id}/attachment/v2`）。
     *
     * ⚠️ 走默认 client（带 Bearer）—— 这是**官方 API 端点**，不是第三方对象存储。
     * 响应里的 `cipherResponse.attachments` 用于更新本地列表（不要走普通写回）。
     */
    @POST("ciphers/{id}/attachment/v2")
    suspend fun createAttachmentUploadUrl(
        @Path("id") id: String,
        @Body body: AttachmentUploadRequest,
    ): AttachmentUploadResponse

    /**
     * Direct Multipart 上传（`fileUploadType=0`，目标仍在本服务端 ⇒ 带 Bearer）。
     * `key` part 是 EncString 文本，`data` part 是**密文字节**，别对调。
     */
    @Multipart
    @POST("ciphers/{id}/attachment/{attachmentId}")
    suspend fun uploadAttachmentDirect(
        @Path("id") id: String,
        @Path("attachmentId") attachmentId: String,
        @Part key: MultipartBody.Part,
        @Part data: MultipartBody.Part,
    ): Response<Unit>

    /** 删除单个附件（404 视为已不存在，由编排器判成功）。 */
    @DELETE("ciphers/{id}/attachment/{attachmentId}")
    suspend fun deleteAttachment(
        @Path("id") id: String,
        @Path("attachmentId") attachmentId: String,
    ): Response<Unit>

    /** 永久删除。 */
    @DELETE("ciphers/{id}/delete")
    suspend fun permanentDeleteCipher(@Path("id") id: String)

    @PUT("ciphers/{id}/restore")
    suspend fun restoreCipher(@Path("id") id: String): CipherResponse

    @GET("folders")
    suspend fun folders(): FolderListResponse

    @POST("folders")
    suspend fun createFolder(@Body body: FolderRequest): FolderDto

    @PUT("folders/{id}")
    suspend fun updateFolder(@Path("id") id: String, @Body body: FolderRequest): FolderDto

    @DELETE("folders/{id}")
    suspend fun deleteFolder(@Path("id") id: String)
}
