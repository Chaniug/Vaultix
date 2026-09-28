/*
 * Vaultix — data:repository
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.data.repository

import io.vaultix.datastore.SecureCredentialStore
import io.vaultix.datastore.VaultixPreferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 旧「每库信封」模型残留的**一次性检测与清理**（房子化批次 2）。
 *
 * ## 为什么会有残留
 *
 * 房子化之前，快速解锁是**每库一把信封**：门锁（Keystore KEK / Argon2id）直包
 * **该库的凭据**，另配两个按库的元数据键。老用户设备上有三样东西：
 *
 * | 残留 | 在哪 | 键形 |
 * |---|---|---|
 * | 旧指纹信封 | `SecureCredentialStore` | `local_unlock_key::<vaultId>` |
 * | 旧 PIN 信封 | `SecureCredentialStore` | `local_pin_key::<vaultId>` |
 * | 旧 PIN 失败计数 | `SecureCredentialStore` | `local_pin_attempts::<vaultId>` |
 * | 旧「本地解锁已启用」标记 | DataStore | `local_unlock_enabled_<vaultId>` |
 * | 旧「PIN 解锁已启用」标记 | DataStore | `pin_unlock_enabled_<vaultId>` |
 *
 * 定稿 §8 明确**不写新旧互转的兼容层**（旧信封 = 门锁直包每库凭据，与房子钥匙无关，
 * 无法自动升级）⇒ 老用户必须**重新登记一次**，而这批残留就成了永远不会被再读的垃圾。
 *
 * ## 为什么必须删，而不只是"留着不管"
 *
 * 信封里躺的是**库的主密码 / 对称密钥的密文**。留着就是一份"应用自己都不再认识、
 * 却仍然躺在 Keystore 里"的凭据副本 —— 一旦哪天有人按老键名去读（或逆向时看到），
 * 它就是一份本不该存在的攻击面。删掉是**最小暴露面**那条纪律的直接要求。
 *
 * ## ★ 清理时机由调用方决定：只在**新体系真的能用之后**才删
 *
 * 本类**只提供**检测与删除，不自己决定"什么时候该删"。
 * 判定权在 `QuickUnlockController`：它只有在本次会话**至少建成一个房间信封**之后
 * 才调 [clearLegacyRemains] —— 这就是「同批生效或整体回退」里"不半新半旧"的落点：
 * 新体系还没站起来就把旧的掀了，用户会两头落空。
 *
 * ⚠️ 反过来，**没建成也绝不能删**：删了旧的、新的又没立起来，用户等于被清空了
 * 快速解锁却什么都没得到。
 */
@Singleton
class LegacyQuickUnlockCleanup @Inject constructor(
    private val secureStore: SecureCredentialStore,
    private val preferences: VaultixPreferences,
) {

    /**
     * 是否还有旧体系的残留（任一处有就算有）。
     *
     * ⚠️ **只读键名，不解密任何值**：判定"有没有"不需要知道里面是什么，
     * 顺手解密反而会把旧的凭据明文带进内存（多一份暴露面，毫无收益）。
     */
    suspend fun hasLegacyRemains(): Boolean = withContext(Dispatchers.IO) {
        legacyEnvelopeKeys().isNotEmpty() || preferences.legacyQuickUnlockKeys().isNotEmpty()
    }

    /**
     * 清掉全部旧体系残留（幂等；没有残留时什么也不做）。
     *
     * @return 删除报告，供调用方如实展示 / 记录。
     */
    suspend fun clearLegacyRemains(): LegacyCleanupReport = withContext(Dispatchers.IO) {
        val envelopes = legacyEnvelopeKeys()
        envelopes.forEach(secureStore::remove)
        val prefKeys = preferences.legacyQuickUnlockKeys()
        // 空集也照调一次 remove 是白开一次 DataStore 写事务（改了文件、跑了 fsync、
        // 一个字节没变）；也让"没有残留"和"清掉了残留"在调用轨迹上无法区分。
        val removedPrefs = if (prefKeys.isEmpty()) 0 else preferences.removeLegacyQuickUnlockKeys(prefKeys)
        LegacyCleanupReport(
            envelopesRemoved = envelopes.size,
            preferenceKeysRemoved = removedPrefs,
        )
    }

    /** 旧信封 / 旧计数在 `SecureCredentialStore` 里的键（三个前缀）。 */
    private fun legacyEnvelopeKeys(): List<String> = LEGACY_PREFIXES.flatMap(secureStore::keysWithPrefix)

    private companion object {
        /**
         * 旧信封的三个键前缀。
         *
         * ⚠️ 这些字符串是**历史数据的指纹**，不是当前代码的常量：它们来自已在批次 1
         * 删除的 `LocalUnlockEnrollment.LOCAL_UNLOCK_STORAGE_PREFIX` 与
         * `PinUnlockStore.ENVELOPE_PREFIX` / `ATTEMPTS_PREFIX`。改一个字符就再也认不出
         * 老数据 —— **不得改动**。
         */
        val LEGACY_PREFIXES = listOf(
            "local_unlock_key::",
            "local_pin_key::",
            "local_pin_attempts::",
        )
    }
}

/**
 * [LegacyQuickUnlockCleanup.clearLegacyRemains] 的结果。
 *
 * 两个数分开报而不是合成一个总数：信封（凭据密文）与偏好键（元数据）是两种性质
 * 完全不同的东西，合起来报会让"删掉了 3 个"这种说法失去意义。
 */
data class LegacyCleanupReport(
    /** 删掉的旧信封 / 旧 PIN 计数条目数。 */
    val envelopesRemoved: Int,
    /** 删掉的旧偏好键数。 */
    val preferenceKeysRemoved: Int,
)
