/*
 * Vaultix — app:util
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * ---------------------------------------------------------------------------
 * 移植来源：Bastion（GPL-3.0，Copyright 2025 JoyinJoester）
 *   app/src/main/java/com/bastion/app/utils/UpdateChecker.kt
 *
 * 搬来的：**「设置 → 检查更新」这一项能力**（查 GitHub Releases API → 与本机版本比对
 * → 有更新则给出发布页入口）。这是 Bastion 设置页里 Vaultix 唯一还缺的一项：Vaultix
 * 的分发渠道就是 GitHub Release（正式版 `vX.Y.Z` + 滚动预览版 `preview`），用户本来
 * 就得去那下载，App 内给一个「有没有新构建」的答案顺理成章。
 *
 * ⚠️ **刻意没有搬**的部分（不是漏，是判断后不搬）：
 *   1. 下载 APK + 校验签名 + 拉起安装界面（`downloadApk` / `validateDownloadedApk`）。
 *      Vaultix 的预览包每次 CI 都覆盖同一个 `preview` release 的同一个附件名，
 *      版本无从在文件名里体现；而「自动装到用户机器上」对一个密码管理器来说是
 *      相当重的承诺（装错来源 = 把主密码交给了一个未签名的 APK）。这里只给
 *      **发布页链接**，下载与安装仍由用户在浏览器里完成 —— 这是最小可信面。
 *   2. 更新渠道设置项 / 国内镜像前缀列表 / 版本码即构建时间戳的判定。Vaultix 的
 *      versionCode 是 `X*1_000_000 + Y*1_000 + Z`（见 app/build.gradle.kts），
 *      不是 Unix 时间戳，Bastion 的「附件更新时间 > versionCode」判定在这里不成立。
 *      ⇒ 改为**按构建来源自动选渠道**（见 [checkForUpdate]），并用 commit SHA 比对
 *      预览版、语义化版本号比对正式版。
 * ---------------------------------------------------------------------------
 */
package io.vaultix.vaultix.util

import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** 一次检查的结论（设置页「检查更新」对话框的数据源）。 */
data class UpdateCheckResult(
    /** 本机版本名（`BuildConfig.VERSION_NAME`）。 */
    val currentVersion: String,
    /** 远端版本标识：正式版 = tag（`v0.3.0`）；预览版 = `dev-<短 sha>`。 */
    val latestVersion: String,
    /** Release 标题（预览版形如 `⚠️ Development Preview (dev-<sha>)`）。 */
    val releaseName: String?,
    /** 发布页地址（「前往下载」按钮打开它）。 */
    val releaseUrl: String,
    /** 远端比本机新。 */
    val isUpdateAvailable: Boolean,
    /** Release 发布时间（epoch 秒）；解析不出为 null。 */
    val publishedAtEpochSeconds: Long? = null,
    /**
     * Release notes 原文（GFM Markdown）；空或缺失为 null。
     *
     * ★ 2026-09-28 新增：用户在「检查更新」对话框里要能看到**这一版改了什么**，
     * 而不是只看到一个版本号。由 UI 层用 Markdown 渲染器呈现
     * （见 `SettingsScreen.UpdateCheckDialog`）。
     */
    val releaseNotes: String? = null,
)

/**
 * 检查 GitHub Releases 上有没有比本机更新的构建。
 *
 * **渠道自动判定**（这是与上游最大的差异，理由见文件头）：
 * - 本机版本名含 `-dev-`（CI 的 debug 包形如 `0.3.0-dev-78db0ed`）⇒ **预览渠道**，
 *   比对 preview release 标题里的 commit SHA 与本机的短 SHA（前缀匹配即同一构建）；
 * - 否则 ⇒ **正式渠道**，按语义化版本号比对 latest release 的 tag。
 *
 * 用户装的是哪个包就查哪个渠道 —— 预览版用户不该被「v0.3.0 是最新」打发掉，
 * 正式版用户也不该被一个 preview 构建提醒去装 debug 包。
 *
 * 任何网络 / 解析失败都以 [Result.failure] 返回，**不抛到调用方**（设置页的一次
 * 手动检查不该让页面崩掉）。
 */
object UpdateChecker {

    /** Release 列表页（「前往下载」兜底；也是检查不到时的去处）。 */
    const val RELEASES_PAGE_URL = "https://github.com/Chaniug/Vaultix/releases"

    /**
     * 国内加速镜像前缀（**只作用于「前往下载」的打开动作，不碰 API**）。
     *
     * ## 为什么只作用于下载链接
     *
     * GitHub 的 **API**（`api.github.com`）是查询接口，公开镜像稀少且不稳定 ——
     * 把 API 也换镜像，收益是"可能快一点"，代价是"可能整个检查失败"。
     * 而**下载页**（`github.com/.../releases/...`）是用户真正会打开的地方，
     * 国内直连经常很慢，这里替换成本最低、收益最直接。
     *
     * ## 用什么镜像
     *
     * 用 gh-proxy 系的**通用加速前缀**（形如 `https://ghproxy.net/`）—— 它把任意
     * `https://github.com/...` 代理成镜像地址，不改 URL 结构，因此**直接前缀拼接即可**，
     * 不需要为每个 release 维护映射表。
     *
     * ⚠️ 用户可在设置里关闭；关闭后一律返回原始地址。
     * ⚠️ 镜像列表**取第一个**；日后若某个挂掉，改这一处即可
     * （`grep MIRROR_PREFIXES`）。
     */
    private val MIRROR_PREFIXES = listOf("https://ghproxy.net/")

    private const val GITHUB_PREFIX = "https://github.com/"

    /**
     * 把一个 GitHub 网页地址换成镜像地址（供「前往下载」使用）。
     *
     * @param useMirror 用户是否启用镜像加速。**false 时原样返回**，调用方不必分支。
     * @return 镜像地址；若地址不是 `https://github.com/` 开头（已经被换过、或是
     *   `RELEASES_PAGE_URL` 之外的自定义地址），原样返回 —— **绝不盲目拼接**，
     *   否则会得到一个 `https://ghproxy.net/https://ghproxy.net/...` 的二次代理。
     */
    fun mirrorUrl(url: String, useMirror: Boolean): String {
        if (!useMirror) return url
        if (!url.startsWith(GITHUB_PREFIX)) return url
        val prefix = MIRROR_PREFIXES.first()
        return prefix + url
    }

    /**
     * 当前镜像的**主机名**（去掉 `https://` 前缀与结尾 `/`），供 UI 展示用。
     *
     * 用途：镜像开关打开后，对话框要如实告诉用户"你的下载页将被谁代理"
     * （`update_check_mirror_on_desc`）—— 只说"已加速"而不说是谁在代理，
     * 对一个密码管理器来说太含糊。取不到就退回整个前缀，保证 UI 永远有东西可显示。
     */
    fun mirrorHost(): String =
        MIRROR_PREFIXES.first().removePrefix("https://").removeSuffix("/")

    private const val LATEST_API_URL =
        "https://api.github.com/repos/Chaniug/Vaultix/releases/latest"

    private const val LIST_API_URL =
        "https://api.github.com/repos/Chaniug/Vaultix/releases?per_page=10"

    /** 预览包版本名里的 dev 标识与短 SHA 的分隔（CI 注入形如 `0.3.0-dev-78db0ed`）。 */
    private const val DEV_MARKER = "-dev-"

    /** 预览 release 标题里的 commit SHA：`… (dev-<40 位 sha>)`。 */
    private val PREVIEW_SHA = Regex("""dev-([0-9a-fA-F]{7,40})""")

    private const val SHORT_SHA_LENGTH = 7

    /** 版本号退化为「取前 N 段数字」时取几段（`X.Y.Z` 三段）。 */
    private const val VERSION_PARTS_LIMIT = 3

    private const val USER_AGENT = "Vaultix-Android"

    private const val CONNECT_TIMEOUT_SECONDS = 10L
    private const val READ_TIMEOUT_SECONDS = 15L
    private const val CALL_TIMEOUT_SECONDS = 20L

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    suspend fun checkForUpdate(currentVersion: String): Result<UpdateCheckResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (DEV_MARKER in currentVersion) {
                    checkPreview(currentVersion)
                } else {
                    checkStable(currentVersion)
                }
            }
        }

    /** 正式渠道：latest release 的 tag 与本机版本名做语义化比对。 */
    private fun checkStable(currentVersion: String): UpdateCheckResult {
        val release = json.decodeFromString(
            GitHubRelease.serializer(),
            httpGet(LATEST_API_URL),
        )
        val tag = release.tagName.trim()
        return UpdateCheckResult(
            currentVersion = currentVersion,
            latestVersion = tag,
            releaseName = release.name?.takeIf { it.isNotBlank() },
            releaseUrl = release.htmlUrl.ifBlank { RELEASES_PAGE_URL },
            isUpdateAvailable = compareVersionTags(tag, currentVersion) > 0,
            publishedAtEpochSeconds = parseIsoEpochSeconds(release.publishedAt),
            // ⚠️ 仅在**有更新**时给正文：已是最新时对话框不展示日志，省得白渲染一大段。
            releaseNotes = release.body
                ?.takeIf { it.isNotBlank() }
                ?.takeIf { compareVersionTags(tag, currentVersion) > 0 },
        )
    }

    /**
     * 预览渠道：取最新的 prerelease，用其标题里的 commit SHA 与本机短 SHA 比对。
     *
     * ⚠️ 不用「发布时间」判定：preview release 是**同一个 release 反复覆盖附件**
     * （CI 每次都传同一个 `app-full-debug.apk`），`published_at` 停在首次创建那天，
     * 拿它跟本机比会得到「你比远端新」这种荒谬结论。commit SHA 才是构建身份。
     *
     * 远端 SHA 取不到（标题格式变了）时**不谎报有更新**：宁可显示「无法确定」，
     * 也不要让用户为了一个不存在的新版本去折腾下载。
     */
    private fun checkPreview(currentVersion: String): UpdateCheckResult {
        val releases = json.decodeFromString(
            ListSerializer(GitHubRelease.serializer()),
            httpGet(LIST_API_URL),
        )
        val preview = releases.firstOrNull { it.prerelease }
            ?: throw IOException("未找到预览版发布")
        val remoteSha = PREVIEW_SHA.find(preview.name.orEmpty())?.groupValues?.getOrNull(1)
        val localSha = currentVersion.substringAfter(DEV_MARKER).trim().lowercase()
        val hasUpdate = !remoteSha.isNullOrEmpty() &&
            localSha.isNotEmpty() &&
            !remoteSha.lowercase().startsWith(localSha)
        return UpdateCheckResult(
            currentVersion = currentVersion,
            latestVersion = remoteSha?.take(SHORT_SHA_LENGTH)?.let { "dev-$it" }
                ?: preview.tagName,
            releaseName = preview.name?.takeIf { it.isNotBlank() },
            releaseUrl = preview.htmlUrl.ifBlank { RELEASES_PAGE_URL },
            isUpdateAvailable = hasUpdate,
            publishedAtEpochSeconds = parseIsoEpochSeconds(preview.publishedAt),
            // 预览渠道：只在"确认有更新"时给正文（同 checkStable 的理由）。
            releaseNotes = preview.body?.takeIf { it.isNotBlank() }?.takeIf { hasUpdate },
        )
    }

    /**
     * GET 一个 GitHub API 地址，返回响应体。
     *
     * 失败统一抛 [IOException] —— 网络层的所有意外（无网络 / 无 INTERNET 权限 /
     * 限流 403 / 空响应）在这里收敛成一个可展示的错误，调用方只处理一种。
     */
    private fun httpGet(url: String): String {
        val body = runCatching {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        }.getOrNull()
        if (body.isNullOrBlank()) throw IOException("未能获取发布信息")
        return body
    }

    /** ISO-8601 UTC 时间戳 → epoch 秒；解析不出返回 null（时间只是附加信息，不该让检查失败）。 */
    private fun parseIsoEpochSeconds(value: String?): Long? =
        value?.takeIf { it.isNotBlank() }?.let {
            runCatching { Instant.parse(it).epochSecond }.getOrNull()
        }

    /** 发布日期展示：epoch 秒 → 本地时区 `yyyy-MM-dd`。 */
    fun formatPublishedDate(epochSeconds: Long): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochSecond(epochSeconds))

    /**
     * 语义化版本号比对：`candidate` 新于 `current` 返回正数。
     *
     * 移植自 Bastion `UpdateChecker.compareVersionTags`：先按 `v?X.Y.Z` 解析，
     * 解析不出再退化为「取前三段数字」，都解析不出才按字符串比。
     */
    fun compareVersionTags(candidate: String, current: String): Int {
        val candidateParts = candidate.semanticVersionParts()
        val currentParts = current.semanticVersionParts()
        if (candidateParts.isEmpty() || currentParts.isEmpty()) {
            return candidate.trim().compareTo(current.trim(), ignoreCase = true)
        }
        val maxSize = maxOf(candidateParts.size, currentParts.size)
        for (index in 0 until maxSize) {
            val left = candidateParts.getOrElse(index) { 0 }
            val right = currentParts.getOrElse(index) { 0 }
            if (left != right) return left.compareTo(right)
        }
        return 0
    }

    private fun String.semanticVersionParts(): List<Int> {
        val semantic = Regex("""(?i)v?(\d+)\.(\d+)\.(\d+)""").find(this)
        if (semantic != null) {
            return semantic.groupValues.drop(1).mapNotNull { it.toIntOrNull() }
        }
        return Regex("\\d+").findAll(this)
            .take(VERSION_PARTS_LIMIT)
            .mapNotNull { it.value.toIntOrNull() }
            .toList()
    }
}

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String,
    val name: String? = null,
    val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    /** Release notes（GFM Markdown）。UPDATE 2026-09-28：展示更新日志用。 */
    val body: String? = null,
)
