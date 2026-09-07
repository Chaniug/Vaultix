package com.bastion.app.utils

import com.bastion.app.logging.runCatchingObserved
import android.content.Context
import com.thegrizzlylabs.sardineandroid.DavResource
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.bastion.app.data.KeepassRemoteSource
import com.bastion.app.security.SecurityManager
import com.bastion.app.webdav.WebDavConditionalWriter
import com.bastion.app.webdav.WebDavCredentials
import com.bastion.app.webdav.WebDavGateway
import com.bastion.app.webdav.WebDavPreconditionException
import com.bastion.app.webdav.WebDavWriteMode
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

data class KeePassLocalMirrorPaths(
    val workingCopyPath: String,
    val cacheCopyPath: String
)

class WebDavKeePassFileSource(
    private val serverUrl: String,
    private val username: String,
    private val password: String,
    private val remotePath: String? = null
) : KeePassFileSource {
    private val normalizedServerUrl = serverUrl.trim().trimEnd('/')
    private val normalizedRemotePath = normalizeOptionalRemotePath(remotePath)
    private val remoteUrl = buildRemoteUrl(normalizedServerUrl, normalizedRemotePath)
    // 经由 WebDavGateway 构造，复用统一的拦截器链（预置式 Basic Auth / 限流 / UA）与超时配置。
    // 旧实现用裸 `OkHttpSardine()` + setCredentials()，会绕过网关：
    // 既没有超时与限流保护，双重设置凭据还会走 challenge-response，与网关注释要求相反。
    private val sardine by lazy {
        // kdbx 密码库动辄数 MB，用大文件档位，避免被 15s callTimeout 掐断。
        WebDavGateway.buildBulkClient(WebDavCredentials(username.trim(), password))
    }

    /**
     * 条件写入器：与 sardine 共享同一套拦截器链（预置式 Basic Auth / 限流 / UA），
     * 用于 `If-Match` 原子条件 PUT，弥补 sardine-android 0.8 不支持条件头的缺口。
     */
    private val conditionalWriter by lazy {
        WebDavConditionalWriter(
            WebDavGateway.buildOkHttpClient(
                WebDavCredentials(username.trim(), password)
            )
        )
    }

    override suspend fun stat(): FileSourceStat = withContext(Dispatchers.IO) {
        requireRemotePath()
        val resource = resolveResource(remoteUrl)
        if (resource != null) {
            val etag = normalizeEtag(resource.etag)
            val etagOrNull = etag.takeIf { it.isNotBlank() }
            return@withContext FileSourceStat(
                versionToken = etagOrNull
                    ?: resource.modified?.time?.toString()
                    ?: resource.contentLength?.toString(),
                etag = etagOrNull,
                lastModified = resource.modified?.time,
                sizeBytes = resource.contentLength,
                isDirectory = resource.isDirectory,
                displayName = resource.name
            )
        }

        val exists = webDavPathExists(remoteUrl)
        if (!exists) {
            throw IOException("远端文件不存在: $normalizedRemotePath")
        }

        FileSourceStat(
            versionToken = null,
            etag = null,
            lastModified = null,
            sizeBytes = null,
            isDirectory = false,
            displayName = normalizedRemotePath.substringAfterLast('/')
        )
    }

    override suspend fun read(): ByteArray = withContext(Dispatchers.IO) {
        requireRemotePath()
        if (!webDavPathExists(remoteUrl)) {
            throw IOException("远端文件不存在: $normalizedRemotePath")
        }
        sardine.get(remoteUrl).use { input ->
            input.readBytes()
        }
    }

    override suspend fun write(
        bytes: ByteArray,
        expectedVersion: String?
    ): FileSourceWriteResult = withContext(Dispatchers.IO) {
        requireRemotePath()
        val parentUrl = buildRemoteUrl(normalizedServerUrl, parentPathOf(normalizedRemotePath))
        if (parentUrl.isNotBlank() && !webDavPathExists(parentUrl)) {
            throw IOException("远端目录不存在: ${parentPathOf(normalizedRemotePath)}")
        }

        // 并发写防护：服务器支持 ETag 时，使用 OkHttp 条件 PUT（If-Match）让服务端
        // 在 HTTP 边界强制前置条件，真正消除 stat→PUT 窗口内的并发覆盖（TOCTOU）。
        // 无 ETag 的服务器退化为"写前 stat 版本预检"（缩小窗口）+ "写后读回校验"。
        if (!expectedVersion.isNullOrBlank()) {
            val current = runCatchingObserved { stat() }.getOrNull()
            val currentEtag = current?.etag
            if (currentEtag != null) {
                if (!current.matchesExpectedVersion(expectedVersion)) {
                    throw IOException("远端文件已变化，请先重新同步")
                }
                try {
                    conditionalWriter.write(
                        targetUrl = remoteUrl,
                        bytes = bytes,
                        mode = WebDavWriteMode.IF_MATCH,
                        expectedVersion = currentEtag
                    )
                } catch (e: WebDavPreconditionException) {
                    // 条件 PUT 被服务端拒绝（并发写入已发生）：明确报冲突
                    throw IOException("远端文件已变化，请先重新同步", e)
                }
            } else {
                if (current != null && !current.matchesExpectedVersion(expectedVersion)) {
                    throw IOException("远端文件已变化，请先重新同步")
                }
                sardine.put(remoteUrl, bytes, KEEPASS_KDBX_MIME_TYPE)
            }
        } else {
            // 首次上传/强制覆盖：
            // 旧实现是直接 sardine.put 无条件覆盖，多设备同时首次上传（或清库后重绑）
            // 会互相静默覆盖，属于数据丢失。改为 If-None-Match: *，让服务端在文件已存在时
            // 直接拒绝，交由上层提示用户重新同步，而不是盲目覆盖别人的数据。
            try {
                conditionalWriter.write(
                    targetUrl = remoteUrl,
                    bytes = bytes,
                    mode = WebDavWriteMode.CREATE_ONLY,
                    // CREATE_ONLY 语义即"仅当不存在时写入"，无需 If-Match 值
                    expectedVersion = null
                )
            } catch (e: WebDavPreconditionException) {
                throw IOException("远端文件已存在，请先重新同步后再上传", e)
            }
        }

        // 读回校验：部分 WebDAV 供应商可能返回成功但未完整写入（截断/损坏）。
        // 写后立即读取远端并比对 SHA-256，不一致则报错，避免本地误以为同步成功。
        try {
            val writtenBytes = read()
            if (!writtenBytes.contentEquals(bytes)) {
                throw IOException(
                    "远端文件写入校验失败（读回内容与本地不一致），" +
                        "可能是网络中断或服务器截断，请重新同步"
                )
            }
        } catch (e: IOException) {
            if (e.message?.contains("远端文件写入校验失败") == true) throw e
            // 读回本身失败（网络抖动）：不阻断，交由下次同步校验
        }

        val latest = runCatchingObserved { stat() }.getOrDefault(FileSourceStat())
        FileSourceWriteResult(
            versionToken = latest.versionToken,
            etag = latest.etag,
            lastModified = latest.lastModified
        )
    }

    override suspend fun listChildren(): List<FileSourceEntry> = withContext(Dispatchers.IO) {
        listDirectory(
            if (normalizedRemotePath.isBlank()) {
                ""
            } else {
                val stat = runCatchingObserved { stat() }.getOrNull()
                if (stat?.isDirectory == true) normalizedRemotePath else parentPathOf(normalizedRemotePath)
            }
        )
    }

    override suspend fun createFile(name: String): FileSourceEntry = withContext(Dispatchers.IO) {
        val targetPath = buildChildPath(parentPathOf(normalizedRemotePath), name)
        createFileInDirectory(parentPathOf(targetPath), name)
    }

    override suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatchingObserved {
            val targetDirectory = when {
                normalizedRemotePath.isBlank() -> ""
                runCatchingObserved { stat() }.getOrNull()?.isDirectory == true -> normalizedRemotePath
                else -> parentPathOf(normalizedRemotePath)
            }
            val targetUrl = buildRemoteUrl(normalizedServerUrl, targetDirectory).ifBlank { normalizedServerUrl }
            if (!webDavPathExists(targetUrl)) {
                throw IOException("无法访问 WebDAV 路径: $targetUrl")
            }
            Unit
        }
    }

    suspend fun listDirectory(directoryPath: String? = null): List<FileSourceEntry> = withContext(Dispatchers.IO) {
        val normalizedDirectoryPath = normalizeOptionalRemotePath(directoryPath)
        val targetUrl = buildRemoteUrl(normalizedServerUrl, normalizedDirectoryPath).ifBlank { normalizedServerUrl }
        if (!webDavPathExists(targetUrl)) {
            throw IOException(
                if (normalizedDirectoryPath.isBlank()) {
                    "无法访问 WebDAV 根目录"
                } else {
                    "远端目录不存在: $normalizedDirectoryPath"
                }
            )
        }
        sardine.list(targetUrl)
            .filterNot { resource ->
                normalizeResourceUrl(resource.href?.toString())
                    .equals(normalizeResourceUrl(targetUrl), ignoreCase = true)
            }
            .map { resource ->
                FileSourceEntry(
                    id = resource.href?.toString(),
                    name = resource.name,
                    path = buildChildPath(normalizedDirectoryPath, resource.name),
                    isDirectory = resource.isDirectory,
                    versionToken = resource.etag?.takeIf { it.isNotBlank() }
                        ?: resource.modified?.time?.toString()
                        ?: resource.contentLength?.toString(),
                    lastModified = resource.modified?.time,
                    sizeBytes = resource.contentLength
                )
            }
            .sortedWith(
                compareBy<FileSourceEntry> { !it.isDirectory }
                    .thenBy { it.name.lowercase(Locale.ROOT) }
            )
    }

    suspend fun createDirectory(parentPath: String?, name: String): FileSourceEntry = withContext(Dispatchers.IO) {
        val normalizedParentPath = normalizeOptionalRemotePath(parentPath)
        val targetPath = buildChildPath(normalizedParentPath, name)
        val targetUrl = buildRemoteUrl(normalizedServerUrl, targetPath)
        if (webDavPathExists(targetUrl)) {
            throw IOException("同名目录已存在")
        }
        sardine.createDirectory(targetUrl)
        FileSourceEntry(
            id = targetUrl,
            name = name.trim(),
            path = targetPath,
            isDirectory = true
        )
    }

    suspend fun createFileInDirectory(
        parentPath: String?,
        name: String,
        bytes: ByteArray = ByteArray(0)
    ): FileSourceEntry = withContext(Dispatchers.IO) {
        val normalizedParentPath = normalizeOptionalRemotePath(parentPath)
        val targetPath = buildChildPath(normalizedParentPath, name)
        val targetUrl = buildRemoteUrl(normalizedServerUrl, targetPath)
        val parentUrl = buildRemoteUrl(normalizedServerUrl, normalizedParentPath)
        if (parentUrl.isNotBlank() && !webDavPathExists(parentUrl)) {
            throw IOException(
                if (normalizedParentPath.isBlank()) {
                    "远端目录不存在"
                } else {
                    "远端目录不存在: $normalizedParentPath"
                }
            )
        }
        if (webDavPathExists(targetUrl)) {
            throw IOException("同名文件已存在")
        }
        sardine.put(targetUrl, bytes, KEEPASS_KDBX_MIME_TYPE)
        val latest = runCatchingObserved { resolveResource(targetUrl) }.getOrNull()
        FileSourceEntry(
            id = latest?.href?.toString() ?: targetUrl,
            name = latest?.name ?: name.trim(),
            path = targetPath,
            isDirectory = false,
            versionToken = latest?.etag?.takeIf { it.isNotBlank() }
                ?: latest?.modified?.time?.toString()
                ?: latest?.contentLength?.toString(),
            lastModified = latest?.modified?.time,
            sizeBytes = latest?.contentLength?.takeIf { it >= 0L } ?: bytes.size.toLong()
        )
    }

    private fun resolveResource(targetUrl: String): DavResource? {
        val directResources = runCatchingObserved { sardine.list(targetUrl) }.getOrNull().orEmpty()
        directResources.firstOrNull { resource ->
            normalizeResourceUrl(resource.href?.toString()).equals(
                normalizeResourceUrl(targetUrl),
                ignoreCase = true
            )
        }?.let { return it }
        directResources.firstOrNull()?.let { return it }

        val parentUrl = buildRemoteUrl(normalizedServerUrl, parentPathOf(normalizedRemotePath))
        if (parentUrl.isBlank()) return null
        val fileName = normalizedRemotePath.substringAfterLast('/')
        return runCatchingObserved { sardine.list(parentUrl) }
            .getOrNull()
            .orEmpty()
            .firstOrNull { !it.isDirectory && it.name.equals(fileName, ignoreCase = true) }
    }

    private fun normalizeResourceUrl(url: String?): String {
        return url.orEmpty().trimEnd('/')
    }

    private fun webDavPathExists(targetUrl: String): Boolean {
        runCatchingObserved { sardine.exists(targetUrl) }
            .onSuccess { return it }
        return runCatchingObserved { sardine.list(targetUrl) }
            .map { true }
            .getOrElse { false }
    }

    private fun requireRemotePath() {
        if (normalizedRemotePath.isBlank()) {
            throw IllegalStateException("未指定远端文件路径")
        }
    }

    private fun FileSourceStat.matchesExpectedVersion(expectedVersion: String): Boolean {
        val expected = normalizeEtag(expectedVersion)
        return expected.isBlank() ||
            expected == normalizeEtag(etag) ||
            expected == versionToken ||
            expected == lastModified?.toString() ||
            expected == sizeBytes?.toString()
    }

    /**
     * ETag 归一化：剥离弱校验前缀 `W/` 与包裹引号。
     *
     * Nextcloud / ownCloud 等实现常返回 `W/"abc"` 或 `"abc"` 形式，
     * 直接作为 If-Match 值或做字符串比对，会被部分服务器以 412 拒绝，
     * 或导致版本比对失败而误报"远端已变化"。
     */
    private fun normalizeEtag(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.trim()
            .removePrefix("W/")
            .removePrefix("w/")
            .trim()
            .trim('"')
    }

    companion object {
        fun normalizeRemotePath(remotePath: String): String {
            val normalized = remotePath
                .trim()
                .replace('\\', '/')
                .trimStart('/')
                .replace(Regex("/+"), "/")
            if (normalized.isBlank()) {
                throw IllegalArgumentException("远端文件路径不能为空")
            }
            return normalized
        }

        fun normalizeOptionalRemotePath(remotePath: String?): String {
            val normalized = remotePath
                ?.trim()
                ?.replace('\\', '/')
                ?.trim('/')
                ?.replace(Regex("/+"), "/")
                .orEmpty()
            return normalized
        }

        fun buildRemoteUrl(serverUrl: String, remotePath: String?): String {
            val normalizedServerUrl = serverUrl.trim().trimEnd('/')
            val normalizedPath = remotePath
                ?.trim()
                ?.replace('\\', '/')
                ?.trim('/')
                .orEmpty()
            return if (normalizedPath.isBlank()) {
                normalizedServerUrl
            } else {
                "$normalizedServerUrl/$normalizedPath"
            }
        }

        fun parentPathOf(remotePath: String): String {
            if (remotePath.isBlank()) {
                return ""
            }
            val normalized = normalizeRemotePath(remotePath)
            val index = normalized.lastIndexOf('/')
            return if (index <= 0) "" else normalized.substring(0, index)
        }

        fun buildChildPath(parentPath: String, name: String): String {
            val sanitizedName = name.trim().trim('/').ifBlank {
                throw IllegalArgumentException("文件名不能为空")
            }
            require('/' !in sanitizedName) { "文件名不能包含路径分隔符" }
            return if (parentPath.isBlank()) sanitizedName else "$parentPath/$sanitizedName"
        }

    }
}

object WebDavKeePassSupport {
    fun createFileSource(
        source: KeepassRemoteSource,
        securityManager: SecurityManager
    ): WebDavKeePassFileSource {
        require(source.baseUrl?.isNotBlank() == true) { "WebDAV 基础地址不能为空" }
        val username = source.usernameEncrypted?.let { securityManager.decryptData(it) }.orEmpty()
        val password = source.passwordEncrypted?.let { securityManager.decryptData(it) }.orEmpty()
        return WebDavKeePassFileSource(
            serverUrl = source.baseUrl,
            username = username,
            password = password,
            remotePath = source.remotePath
        )
    }

    fun buildLocalMirrorPaths(sourceId: Long, remotePath: String): KeePassLocalMirrorPaths {
        val fileName = displayNameFromRemotePath(remotePath)
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .ifBlank { "remote.kdbx" }
        val baseDir = "keepass_remote/webdav_$sourceId"
        return KeePassLocalMirrorPaths(
            workingCopyPath = "$baseDir/working_$fileName",
            cacheCopyPath = "$baseDir/cache_$fileName"
        )
    }

    fun displayNameFromRemotePath(remotePath: String): String {
        val normalized = WebDavKeePassFileSource.normalizeRemotePath(remotePath)
        return normalized.substringAfterLast('/').ifBlank { "remote.kdbx" }
    }

    fun writeRelativeFile(
        context: Context,
        relativePath: String,
        bytes: ByteArray
    ) {
        val file = File(context.filesDir, relativePath)
        val parent = file.parentFile ?: throw IOException("无效的文件路径")
        if (!parent.exists()) {
            parent.mkdirs()
        }
        val tempFile = File(parent, "${file.name}.tmp")
        FileOutputStream(tempFile).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        if (file.exists() && !file.delete()) {
            throw IOException("无法替换本地工作副本")
        }
        if (!tempFile.renameTo(file)) {
            FileOutputStream(file).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            tempFile.delete()
        }
    }

    fun deleteRelativeFile(context: Context, relativePath: String?) {
        if (relativePath.isNullOrBlank()) return
        val file = File(context.filesDir, relativePath)
        if (file.exists()) {
            file.delete()
        }
        file.parentFile?.takeIf { it.exists() && it.listFiles().isNullOrEmpty() }?.delete()
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString(separator = "") { byte ->
            "%02x".format(Locale.US, byte.toInt() and 0xff)
        }
    }
}
