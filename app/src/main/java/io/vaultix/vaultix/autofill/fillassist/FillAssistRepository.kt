/*
 * Vaultix — app:autofill · fillassist
 * Copyright (C) 2026 Vaultix contributors
 *
 * 「填充辅助」规则表的获取与缓存。机制对齐 Bitwarden `FillAssistManagerImpl`
 * （GPL-3.0，© Bitwarden Inc.）：拉 manifest → 比对 `cid` 决定是否需要新文件 →
 * 拉 forms → 落盘；刷新间隔 6 小时（与浏览器端一致）。
 *
 * 与上游的差异：上游把规则 URL 放在服务端 config（`environment.fillAssistRules`），
 * 并受 feature flag + 设置开关双重门控。Vaultix 的服务器配置链路尚未打通，
 * 故这里**直接使用同一份公开地址**（Bitwarden 官方 release 资产），
 * 保证后续规则更新能自动吃到；门控简化为不做（始终启用）。
 */
package io.vaultix.vaultix.autofill.fillassist

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** 规则表的拉取与本地缓存（线程安全：内存快照 + 互斥刷新）。 */
class FillAssistRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val refreshMutex = Mutex()
    private val cacheFile: File get() = File(context.filesDir, CACHE_FILE_NAME)

    @Volatile
    private var snapshot: FillAssistRules? = null

    /**
     * 当前可用规则（内存快照优先；首次调用时从磁盘恢复）。
     * 读不到（从未拉过 / 解析失败）返回 [FillAssistRules.EMPTY]，行为等同没有填充辅助。
     */
    fun currentRules(): FillAssistRules {
        snapshot?.let { return it }
        val parsed = readCache()?.forms
            ?.takeIf { it.isNotEmpty() }
            ?.let(FillAssistJsonParser::parseForms)
            ?: FillAssistRules.EMPTY
        snapshot = parsed
        return parsed
    }

    /**
     * 距上次成功刷新超过 [UPDATE_INTERVAL_MS] 才真正联网；并发调用只跑一次。
     * 任何失败都静默保留旧规则（填充辅助是**增益**，不能因为拉不到而影响填充）。
     */
    suspend fun refreshIfStale() = withContext(Dispatchers.IO) {
        val cache = readCache()
        val fresh = System.currentTimeMillis() - cache.fetchedAtMillis < UPDATE_INTERVAL_MS
        if (fresh || !refreshMutex.tryLock()) return@withContext
        try {
            refresh(cache)
        } finally {
            refreshMutex.unlock()
        }
    }

    private fun refresh(cache: FillAssistCacheFile) {
        val manifestRaw = httpGet("$BASE_URL/$MANIFEST_FILE") ?: return
        val manifest = FillAssistJsonParser.parseManifest(manifestRaw) ?: return
        // cid 未变 → 只更新刷新时间戳，省掉一次 25KB 下载。
        if (manifest.cid.isNotEmpty() && manifest.cid == cache.cid) {
            writeCache(cache.copy(fetchedAtMillis = System.currentTimeMillis()))
            return
        }
        val formsRaw = httpGet("$BASE_URL/${manifest.filename}") ?: return
        val rules = FillAssistJsonParser.parseForms(formsRaw) ?: return
        writeCache(
            FillAssistCacheFile(
                fetchedAtMillis = System.currentTimeMillis(),
                cid = manifest.cid,
                forms = formsRaw,
            ),
        )
        snapshot = rules
    }

    private fun httpGet(url: String): String? = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    }.getOrNull()

    private fun readCache(): FillAssistCacheFile {
        val file = cacheFile
        if (!file.isFile) return FillAssistCacheFile()
        return runCatching { json.decodeFromString(FillAssistCacheFile.serializer(), file.readText()) }
            .getOrElse { FillAssistCacheFile() }
    }

    private fun writeCache(value: FillAssistCacheFile) {
        runCatching {
            cacheFile.writeText(json.encodeToString(FillAssistCacheFile.serializer(), value))
        }
    }

    private companion object {
        /** 与上游一致的公开规则地址（Bitwarden 官方 release 资产）。 */
        const val BASE_URL = "https://github.com/bitwarden/map-the-web/releases/latest/download"

        const val MANIFEST_FILE = "manifest.json"
        const val CACHE_FILE_NAME = "fill-assist-cache.json"

        /** 刷新间隔：6 小时（对齐上游与浏览器端实现）。 */
        const val UPDATE_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}

/** 磁盘缓存形态：原始 forms 文本 + `cid` + 上次刷新时间（原始文本便于离线重解析）。 */
@Serializable
private data class FillAssistCacheFile(
    val fetchedAtMillis: Long = 0L,
    val cid: String = "",
    val forms: String = "",
)
