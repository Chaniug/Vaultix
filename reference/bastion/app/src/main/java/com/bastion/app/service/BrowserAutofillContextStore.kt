package com.bastion.app.service

import com.bastion.app.logging.runCatchingObserved
import android.content.Context
import android.net.Uri
import java.io.File
import java.net.IDN
import java.util.Locale

/**
 * 浏览器填充上下文存储：记录“某包最近一次识别到的网址域名”，供自动填充服务在
 * AssistStructure 未携带 webDomain 时做域名回退匹配。
 *
 * 跨进程说明：写入方是运行在独立进程(:accessibility)的无障碍服务，读取方是运行在
 * 独立进程(:autofill)的自动填充服务。此前用进程内 object 单例，两进程各持一份，
 * 导致 :accessibility 写入的上下文对 :autofill 不可见，浏览器填充回退失效。
 * 现改为基于应用 filesDir 的文件存储（同 UID 下跨进程共享），读取每次重新读文件，
 * 写入使用临时文件 + 原子 rename，避免读到半写内容。
 */
object BrowserAutofillContextStore {
    private const val MAX_DOMAIN_AGE_MS = 60_000L
    private const val FILE_NAME = "bastion_browser_autofill_ctx_v1"
    private const val FILE_NAME_TMP = "bastion_browser_autofill_ctx_v1.tmp"

    data class Snapshot(
        val packageName: String,
        val domain: String,
        val updatedAt: Long,
    )

    // 同进程内的轻量缓存：减少同进程高频读取时的文件 IO（跨进程以文件为准）。
    @Volatile
    private var latestSnapshot: Snapshot? = null

    @Volatile
    private var appContext: Context? = null

    private val fileLock = Any()

    /** 由各进程的服务在连接时调用，提供应用上下文以便读写 filesDir 文件。 */
    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    @Synchronized
    fun update(packageName: String, rawUrlOrDomain: String) {
        val normalizedPackage = packageName.trim()
        val normalizedDomain = normalizeDomain(rawUrlOrDomain) ?: return
        val snap = Snapshot(
            packageName = normalizedPackage,
            domain = normalizedDomain,
            updatedAt = System.currentTimeMillis(),
        )
        latestSnapshot = snap
        writeSnapshotToFile(snap)
    }

    fun getRecentDomain(packageName: String, maxAgeMs: Long = MAX_DOMAIN_AGE_MS): String? {
        // 跨进程优先读文件（:accessibility 写入、:autofill 读取）；同进程内存缓存作为兜底。
        val snapshot = readSnapshotFromFile() ?: latestSnapshot ?: return null
        if (!snapshot.packageName.equals(packageName.trim(), ignoreCase = true)) return null
        if (System.currentTimeMillis() - snapshot.updatedAt > maxAgeMs) return null
        return snapshot.domain
    }

    private fun writeSnapshotToFile(snap: Snapshot) {
        val ctx = appContext ?: return
        synchronized(fileLock) {
            runCatchingObserved {
                val dir = ctx.filesDir
                val target = File(dir, FILE_NAME)
                val tmp = File(dir, FILE_NAME_TMP)
                tmp.writeText("${snap.packageName}\n${snap.domain}\n${snap.updatedAt}")
                if (!tmp.renameTo(target)) {
                    // rename 失败时直接覆盖写（极端情况下可能读到半写内容，但读取端会做格式校验）。
                    target.writeText("${snap.packageName}\n${snap.domain}\n${snap.updatedAt}")
                }
                runCatchingObserved { tmp.delete() }
            }
        }
    }

    private fun readSnapshotFromFile(): Snapshot? {
        val ctx = appContext ?: return null
        return synchronized(fileLock) {
            runCatchingObserved {
                val file = File(ctx.filesDir, FILE_NAME)
                if (!file.exists()) return@runCatchingObserved null
                val lines = file.readLines()
                if (lines.size < 3) return@runCatchingObserved null
                val pkg = lines[0].trim()
                val domain = lines[1].trim()
                val ts = lines[2].trim().toLongOrNull() ?: return@runCatchingObserved null
                if (pkg.isBlank() || domain.isBlank()) return@runCatchingObserved null
                Snapshot(pkg, domain, ts)
            }.getOrNull()
        }
    }

    private fun normalizeDomain(rawValue: String): String? {
        val candidate = rawValue.trim()
        if (candidate.isBlank()) return null

        val host = runCatchingObserved {
            val parsed = Uri.parse(candidate)
            when {
                !parsed.host.isNullOrBlank() -> parsed.host
                candidate.contains("://") -> null
                else -> Uri.parse("https://$candidate").host
            }
        }.getOrNull() ?: return null

        val asciiHost = runCatchingObserved { IDN.toASCII(host.trim().trimEnd('.')) }.getOrNull() ?: return null
        val normalized = asciiHost.lowercase(Locale.ROOT)
        return normalized.takeIf { it.isNotBlank() }
    }
}
