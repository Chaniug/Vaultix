/*
 * Vaultix — core:datastore
 * Copyright (C) 2026 Vaultix contributors
 *
 * Vaultix is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 */
package io.vaultix.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * **自动锁定档位**偏好：**全局默认 + 每库可覆盖**（模型定稿见
 * `.ai/decisions/多库锁模型-定稿.md` §9 / §10）。
 *
 * ## 为什么它是一个独立类（2026-09-30 抽自 `VaultixPreferences`）
 *
 * 下面这 11 个函数是 2026-09-30「档位模型修订」当天加进 `VaultixPreferences` 的，
 * 一次把那个类从 **36** 个函数顶到 **47**，越过 detekt `TooManyFunctions` 的单类 40 硬上限
 * ⇒ **推送即红 CI**（该步失败会连带 skip 掉 Build APK）。
 * `.ai/conventions/8.6-工程质量.md` §141 对这条违规的口径很明确：
 * **是「该抽类了」的信号，不是「该压注释 / 加 @Suppress」的信号**。
 *
 * 抽出来之后依赖反而更准：真正只关心「这个库多久锁」的调用方
 * （`AutoRestoreTrigger` / `VaultLockManagerImpl` / `AutoUnlockRepositoryImpl`）
 * 此前拿到的是**整个偏好门面**，现在只拿到档位这一件事。
 *
 * ⚠️ **下一个再加解锁 / 锁定手段时同样要提取，别堆回来。**
 *
 * ⚠️ 回退链（**覆盖 → 全局 → 更旧的裸分钟键 → 默认**）是本模块最容易写反的地方：
 * 写反的症状不是崩，而是**静默算错一个数**。真源说明见 [resolveVaultTimeout] 与
 * [globalDefaultTimeout]；回归门禁是 `VaultTimeoutScopeTest`（直接钉纯函数，不需要真 DataStore）。
 */
@Singleton
class VaultTimeoutPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    private companion object {
        /**
         * 旧版自动锁定档位键（裸 Int：0=立即 / N=分钟 / 负=从不）。
         *
         * 2026-09-11 起**只用于一次性迁移**，不再作为读取来源。保留键名以免用户数据丢失。
         * ⚠️ 2026-09-30 起它仍是**全局默认档位**回退链的第二环（见 [globalDefaultTimeout]）。
         */
        val AUTO_LOCK_MINUTES = intPreferencesKey("auto_lock_minutes")

        /**
         * **全局默认**自动锁定档位键（`VaultTimeout.toStorageValue()` 的编码）。
         *
         * 2026-09-30 起**转正**为「全局默认」的唯一真源（此前是 D3 之前的旧全局键，
         * 被降格为回退值；本轮模型修订把它的地位恢复回来 —— 见 [globalVaultTimeout]）。
         */
        val VAULT_TIMEOUT = intPreferencesKey("vault_timeout")

        /** 每库**覆盖**键的前缀（唯一拼接处；清理时按前缀扫）。 */
        const val PER_VAULT_TIMEOUT_PREFIX = "vault_timeout::"

        /**
         * 「档位作用域」迁移标记（2026-09-30）。
         *
         * 取代了已删除的 `auto_lock_migrated_v2`：那个标记**没有任何写入点**
         * （见 [globalDefaultTimeout] 的说明），作为读取门控是纯粹的隐患。
         * 本标记的用途只有一个：让 [purgeLegacyPerVaultTimeoutsOnce] 只跑一次。
         */
        val TIMEOUT_SCOPE_MIGRATED = booleanPreferencesKey("vault_timeout_scope_v2")
    }

    private val safeData: Flow<Preferences> = dataStore.safePreferencesFlow()

    /**
     * **某库**的自动锁定档位（有效值 = 覆盖 ?: 全局默认）。
     *
     * 存储键 `vault_timeout::<vaultId>`（**仅当用户为该库单独指定过**才存在）。
     *
     * ## ★ 2026-09-30 下午：模型由「每库一份」修订为「**全局默认 + 每库可覆盖**」
     *
     * 用户拍板（推翻 D3 的"档位每库一份"）：主流密码管理器（Bitwarden 按账号存、
     * KeePassDX 是 App 级、1Password 全局）之所以"一个设置就够"，前提是它们
     * **一次只有一个解锁对象**；Vaultix 是两个异构后端同时解锁，且两者重开成本
     * 差 100 倍（BW ≈33ms / KDBX ≈1.2s+），所以"全局一个值"要保留为**默认**，
     * 而"某库单独指定"降级为**覆盖**。
     *
     * 回退链（见 [globalDefaultTimeout]）：**更具体者优先** —— 该库的显式键 →
     * 全局键 [VAULT_TIMEOUT] → 更旧的 [AUTO_LOCK_MINUTES] → [VaultTimeout.DEFAULT]。
     *
     * 旧键 `auto_lock_minutes` 的语义陷阱见 [VaultTimeout.fromLegacyMinutes]：
     * 它的 `-1` 表示「从不」，而新模型的 `-1` 表示「重启时锁定」——**正好相反**。
     */
    fun vaultTimeout(vaultId: String): Flow<VaultTimeout> = safeData.map { prefs ->
        resolveVaultTimeout(prefs, vaultId)
    }

    /**
     * **全局默认**档位 —— 所有没单独指定档位的库共用这一档。
     *
     * 设置页「自动锁定」行读写的就是它（副标题「所有密码库默认：…」）。
     */
    fun globalVaultTimeout(): Flow<VaultTimeout> = safeData.map { globalDefaultTimeout(it) }

    /**
     * 某库的**显式覆盖**；`null` = 该库没单独指定过 ⇒ **跟随全局**。
     *
     * 与 [vaultTimeout]（有效值）的区别就是这个 `null`：档位对话框靠它决定
     * 「跟随全局设置」那一项是否被选中（两者都返回同一个数时，没法从值上分辨）。
     */
    fun vaultTimeoutOverride(vaultId: String): Flow<VaultTimeout?> = safeData.map { prefs ->
        resolveVaultTimeoutOverride(prefs, vaultId)
    }

    /**
     * 有效档位的**纯函数**形式（不碰 DataStore）——回退顺序的唯一判定点。
     *
     * ⚠️ 抽出来只有一个目的：让 `VaultTimeoutScopeTest` 能用内存 `Preferences`
     * 直接钉住**回退顺序**（覆盖 → 全局 → 更旧的裸分钟键 → 默认）。
     * 这条顺序是本模块最容易写反的地方，而它写反的症状极隐蔽
     * （"改了全局、某个库纹丝不动"）。
     */
    internal fun resolveVaultTimeout(prefs: Preferences, vaultId: String): VaultTimeout =
        resolveVaultTimeoutOverride(prefs, vaultId) ?: globalDefaultTimeout(prefs)

    /**
     * 覆盖的**纯函数**形式；`null` = 该库跟随全局（这一区分是档位对话框初值的依据）。
     */
    internal fun resolveVaultTimeoutOverride(prefs: Preferences, vaultId: String): VaultTimeout? =
        prefs[vaultTimeoutKey(vaultId)]?.let { VaultTimeout.fromStorageValue(it) }

    /**
     * 全局默认档位的回退链：全局键 → 更旧的裸分钟键 → [VaultTimeout.DEFAULT]。
     *
     * ⚠️ **2026-09-30 去掉了原来的 `auto_lock_migrated_v2` 门控**。理由（取证得出结论）：
     * 那个标记**全仓库没有任何写入点**（`grep` 只剩定义与读取），而 `VAULT_TIMEOUT`
     * 有历史写入 ⇒「全局键有值」本身就证明迁移发生过。留着它只会让"读哪把键"
     * 取决于一个没人维护的布尔：一旦某台设备的标记是 false，**新写的全局值就静默读不到**。
     * ⇒ 改成**纯"更具体者优先"**（与每库覆盖同一条规则），这条链就自洽了。
     *
     * `internal`（而非 private）：纯函数，单测直接钉（见 [resolveVaultTimeout]）。
     */
    internal fun globalDefaultTimeout(prefs: Preferences): VaultTimeout =
        prefs[VAULT_TIMEOUT]
            ?.let { VaultTimeout.fromStorageValue(it) }
            ?: prefs[AUTO_LOCK_MINUTES]
                ?.let { VaultTimeout.fromLegacyMinutes(it) }
            ?: VaultTimeout.DEFAULT

    /**
     * 写**全局默认**档位（不影响任何已单独指定档位的库）。
     */
    suspend fun setGlobalVaultTimeout(value: VaultTimeout) {
        dataStore.edit { prefs ->
            prefs[VAULT_TIMEOUT] = VaultTimeout.toStorageValue(value)
        }
    }

    /**
     * 写**某库**的档位（= 建立一条**覆盖**）。
     *
     * ⚠️ **只写这一个键**，不碰全局键、也不清 `auto_lock_minutes` ——
     * 后者仍是"更老的安装"的回退值，清了就是迁移事故（见 [vaultTimeout] 的 KDoc）。
     */
    suspend fun setVaultTimeout(vaultId: String, value: VaultTimeout) {
        dataStore.edit { prefs ->
            prefs[vaultTimeoutKey(vaultId)] = VaultTimeout.toStorageValue(value)
        }
    }

    /**
     * 删掉某库的覆盖 ⇒ 它回到**跟随全局**。
     *
     * ⚠️ 只删**这一个库**的键；其它库的覆盖与全局键都不动。
     */
    suspend fun clearVaultTimeoutOverride(vaultId: String) {
        dataStore.edit { prefs -> prefs.remove(vaultTimeoutKey(vaultId)) }
    }

    /**
     * 一次性清理：删掉 **D3 那版 UI（"每库一份"）留下的全部覆盖键**。
     *
     * ## 为什么这不是"删用户数据"
     *
     * D3 那一版的入口是**设置首页的一行**（副标题给活跃库的档位），用户点它时的心智
     * 就是"设一个全局档位" —— 但它写的是**活跃库的键** ⇒ 每点一次就给一个库留下一条
     * 覆盖。那些覆盖**不代表"这个库要单独指定"**，只代表"当时活跃的是它"。
     * 值也与全局相同（同一台设备取证：3 条键的值一致）。
     *
     * 不清理的症状很具体：用户改了新的全局行，**两个库纹丝不动** ——
     * 因为两条覆盖把它们钉住了 ⇒ 用户必然报「改了没用」。
     *
     * 幂等：由 [TIMEOUT_SCOPE_MIGRATED] 门控，只跑一次；此后用户**有意**建立的覆盖
     * （在 ⋮ 里显式选的档位）不会被清掉。
     */
    suspend fun purgeLegacyPerVaultTimeoutsOnce() {
        dataStore.edit { prefs ->
            if (prefs[TIMEOUT_SCOPE_MIGRATED] == true) return@edit
            prefs.asMap().keys
                .filterIsInstance<Preferences.Key<Int>>()
                .filter { it.name.startsWith(PER_VAULT_TIMEOUT_PREFIX) }
                .forEach { prefs.remove(it) }
            prefs[TIMEOUT_SCOPE_MIGRATED] = true
        }
    }

    private fun vaultTimeoutKey(vaultId: String) = intPreferencesKey("$PER_VAULT_TIMEOUT_PREFIX$vaultId")
}
