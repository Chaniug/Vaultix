package com.bastion.app.bitwarden.service

import android.content.Context
import android.content.SharedPreferences

/**
 * SSH 原生 Type 5 上传能力策略（按 vault 记忆）。
 *
 * 背景：Bastion 曾因「Vaultwarden 等兼容服务端不支持 Type 5，/sync 时降级 Type 1
 * 并丢弃 sshKey 数据」而把 SSH 降级为 Type 1 + 自定义字段上传（官方端显示为登录）。
 * 2026 年主流自建服务端（Vaultwarden ≥ 1.34.0、nodewarden 等）均已原生支持
 * Type 5，因此上传升级为与 Bitwarden 官方同构的原生结构。
 *
 * 策略：默认尝试原生 Type 5；当某 vault 上 Type 5 被 4xx 校验拒绝时进入冷却期
 * （期内继续用降级格式，避免每轮同步反复被拒），冷却期满自动重探；Type 5 成功
 * 上传即清零失败记录。这样无论服务端新旧，都能自动收敛到可用且最原生的格式。
 */
object SshNativeUploadPolicy {

    private const val PREFS_NAME = "bitwarden_ssh_upload_policy"
    private const val KEY_FAIL_AT_PREFIX = "ssh_type5_fail_at_"
    private const val PROBE_COOLDOWN_MS = 7L * 24 * 60 * 60 * 1000

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 该 vault 当前是否允许尝试原生 Type 5 上传。
     * 从未失败过（key 缺省 0）→ 允许；处于冷却期 → 不允许。
     */
    fun isNativeUploadAllowed(context: Context, vaultId: Long): Boolean {
        val failAt = prefs(context).getLong(KEY_FAIL_AT_PREFIX + vaultId, 0L)
        if (failAt == 0L) return true
        return System.currentTimeMillis() - failAt >= PROBE_COOLDOWN_MS
    }

    /** 原生 Type 5 被服务端拒绝（4xx 校验类错误）→ 记录失败时间，进入冷却期。 */
    fun markNativeUploadFailed(context: Context, vaultId: Long) {
        prefs(context).edit()
            .putLong(KEY_FAIL_AT_PREFIX + vaultId, System.currentTimeMillis())
            .apply()
    }

    /** 原生 Type 5 上传成功 → 清零失败记录（仅当本次请求确实是 Type 5 时调用）。 */
    fun markNativeUploadSucceeded(context: Context, vaultId: Long) {
        prefs(context).edit()
            .putLong(KEY_FAIL_AT_PREFIX + vaultId, 0L)
            .apply()
    }
}
