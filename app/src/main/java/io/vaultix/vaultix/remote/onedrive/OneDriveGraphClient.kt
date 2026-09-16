/*
 * Vaultix — app / OneDrive
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * ★ 溯源声明（GPL-3.0）：请求形状（OkHttp 手写 REST + `/me/drive` 基址 + eTag 版本令牌 +
 *   412 视为「远端已变化」）移植自 Bastion（`com.bastion.app.utils.OneDriveKeePassFileSource`，
 *   同项目作者的另一个应用，GPL-3.0）。本文件只保留**鉴权 spike 需要的最小读能力**。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.remote.onedrive

import io.vaultix.common.logging.VaultixLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** 网盘上的一个条目（目录或文件）。 */
data class OneDriveEntry(
    val id: String,
    val name: String,
    val isDirectory: Boolean,
    /** 版本令牌（优先 `eTag`）：条件写的 `If-Match` 用它，也是冲突检测的判据。 */
    val versionToken: String?,
    val sizeBytes: Long?,
    val lastModified: String?,
) {
    /** 是否是 KeePass 数据库文件（大小写不敏感 —— OneDrive 上两种写法都常见）。 */
    val isKdbx: Boolean get() = !isDirectory && name.endsWith(".kdbx", ignoreCase = true)
}

/**
 * Microsoft Graph 的最小客户端（**只读**，够跑通"登录 → 列文件"这条验证链）。
 *
 * ## 为什么手写 OkHttp 而不是引入 Graph SDK
 *
 * 与上游 Bastion 同取向：Graph 的官方 Android SDK 体积大、传递依赖多（还带 GMS），
 * 而本项目只用到十来个端点。手写 REST 的代价是"要自己处理分页与错误码"，
 * 但换来可控的体积与依赖面。
 *
 * ## 为什么只做读
 *
 * KDBX 目前是 **M2 阶段 A（只读）**：本地库都还不能写回，网盘侧更没有写入的场景。
 * 写路径（`PUT .../content` + 超大文件的分片 upload session + `If-Match` 条件写）
 * 等阶段 B（写回）落地后再补 —— 那部分**没有任何理由先写**：
 * 写了也无处可用，只会白白扩大要维护的代码面。
 */
@Singleton
class OneDriveGraphClient @Inject constructor() {

    /**
     * 列出一个目录下的子项。
     *
     * @param directoryPath 相对 OneDrive 根目录的路径（null / 空 = 根目录）。
     * @param accessToken [OneDriveAuthManager.acquireAccessToken] 取到的 token。
     */
    suspend fun listChildren(
        accessToken: String,
        directoryPath: String? = null,
    ): List<OneDriveEntry> = withContext(Dispatchers.IO) {
        val firstUrl = buildChildrenRelativeUrl(directoryPath)
        val collected = mutableListOf<OneDriveDriveItemDto>()
        var nextUrl: String? = firstUrl
        var page = 0

        // Graph 用 `@odata.nextLink` 分页；不跟到底会静默漏掉后半段（库文件常排在后面）。
        while (nextUrl != null) {
            page++
            val payload = executeJsonRequest(nextUrl, accessToken)
            val response = json.decodeFromString<OneDriveChildrenResponseDto>(payload)
            collected += response.value
            nextUrl = response.nextLink
        }
        VaultixLog.d(TAG) {
            "OneDrive 列目录完成：path=${directoryPath ?: "<root>"} pages=$page items=${collected.size}"
        }

        collected.map { item ->
            OneDriveEntry(
                id = item.id,
                name = item.name,
                isDirectory = item.folder != null,
                versionToken = item.eTag ?: item.cTag,
                sizeBytes = item.size,
                lastModified = item.lastModifiedDateTime,
            )
        }.sortedWith(
            // 目录在前，其余按名称不区分大小写 —— 用户的库文件因此不会被随机顺序埋掉。
            compareBy<OneDriveEntry> { !it.isDirectory }
                .thenBy { it.name.lowercase() },
        )
    }

    /** 连通性自检：能不能拿到根目录的第一页？（失败原因原样抛出，由调用方翻译给用户） */
    suspend fun testConnection(accessToken: String): Result<Unit> = runCatching {
        executeJsonRequest("/me/drive/root?${'$'}select=id", accessToken)
        Unit
    }

    private fun buildChildrenRelativeUrl(directoryPath: String?): String {
        val normalized = directoryPath?.trim()?.trim('/')?.takeIf { it.isNotEmpty() }
        return if (normalized == null) {
            "/me/drive/root/children"
        } else {
            "/me/drive/root:/${encodePath(normalized)}:/children"
        }
    }

    /** 逐段编码：路径里的中文 / 空格 / `#` 不编码会让 Graph 返回 400 或找错文件。 */
    private fun encodePath(path: String): String = path
        .split('/')
        .filter { it.isNotBlank() }
        .joinToString("/") { segment -> java.net.URLEncoder.encode(segment, "UTF-8") }

    private fun executeJsonRequest(relativeOrAbsoluteUrl: String, accessToken: String): String {
        val url = if (relativeOrAbsoluteUrl.startsWith("https://", ignoreCase = true)) {
            relativeOrAbsoluteUrl
        } else {
            "$GRAPH_BASE_URL$relativeOrAbsoluteUrl"
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                VaultixLog.w(TAG) { "Graph 请求失败：HTTP ${response.code} ${request.url.encodedPath}" }
                // 401/403 单独说清楚：它是"要重新登录"而不是"网络不好"，
                // 两者的用户动作完全不同（前者去重新登录，后者去检查网络）。
                val hint = when (response.code) {
                    401 -> "OneDrive 登录已失效，请重新登录"
                    403 -> "OneDrive 拒绝了该请求（权限不足，可能需要重新授权）"
                    404 -> "OneDrive 上找不到该路径"
                    else -> null
                }
                throw IOException(hint ?: body.ifBlank { "OneDrive 请求失败：HTTP ${response.code}" })
            }
            return body
        }
    }

    private companion object {
        const val TAG = "OneDriveGraph"
        const val GRAPH_BASE_URL = "https://graph.microsoft.com/v1.0"

        /** Graph 的目录查询是纯 IO，跟随重定向交给 OkHttp 默认行为。 */
        val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class OneDriveChildrenResponseDto(
    /** 注意字段名带 `@`，故必须显式注解，不能靠命名推导。 */
    @SerialName("@odata.nextLink")
    val nextLink: String? = null,
    val value: List<OneDriveDriveItemDto> = emptyList(),
)

@Serializable
private data class OneDriveDriveItemDto(
    val id: String = "",
    val name: String = "",
    val size: Long? = null,
    /** `eTag`/`cTag` 的 JSON 名首字母大写，与 Kotlin 属性名不同，需显式注解。 */
    @SerialName("eTag")
    val eTag: String? = null,
    @SerialName("cTag")
    val cTag: String? = null,
    val lastModifiedDateTime: String? = null,
    /** 非 null 表示这是目录（Graph 的约定：靠字段有无区分类型）。 */
    val folder: OneDriveFolderFacetDto? = null,
)

@Serializable
private data class OneDriveFolderFacetDto(
    val childCount: Int = 0,
)
