package io.vaultix.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 档位作用域：**全局默认 + 每库覆盖**的回退顺序（2026-09-30 模型修订）。
 *
 * ## 为什么值得单独一个文件钉它
 *
 * 这条链的失败方式不是"崩"，而是**静默算错一个数**，有两种：
 *
 * 1. **顺序写反** ⇒ 用户改了全局行、某个库纹丝不动（它被一条覆盖钉住，而覆盖本该优先）；
 * 2. **旧键语义方向** ⇒ 旧 `auto_lock_minutes` 的 `-1` 是「**从不**」，而新编码的
 *    `-1` 是「**重启时锁定**」——**正好相反**。读反了会把用户明确选的"永不锁定"
 *    静默改成"重启即锁"（`VaultTimeout.fromLegacyMinutes` 的 KDoc 把这条列为
 *    "本次改动最危险的回归点"）。
 *
 * 断言直接打在被抽出的**纯函数**上（`resolveVaultTimeout` / `resolveVaultTimeoutOverride` /
 * `globalDefaultTimeout`），因此不需要真的起一个 DataStore；下面那个 `noDataStore`
 * 只是为了让 `VaultTimeoutPreferences` 能被构造出来 —— 本文件一个字节都不会读写它。
 */
class VaultTimeoutScopeTest {

    /** 只为构造：本文件的被测函数都不碰 `dataStore`（见类 KDoc）。 */
    private val noDataStore = object : DataStore<Preferences> {
        override val data: Flow<Preferences> = flowOf(emptyPreferences())

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences = transform(emptyPreferences())
    }

    private val prefs = VaultTimeoutPreferences(noDataStore)

    /** ⚠️ 这里**故意**硬编码存储键格式：它是对**用户既有数据**的接口，改名 = 静默丢档位。 */
    private fun overrideKey(vaultId: String) = intPreferencesKey("vault_timeout::$vaultId")

    private val globalKey = intPreferencesKey("vault_timeout")
    private val legacyKey = intPreferencesKey("auto_lock_minutes")

    /** 构造一份内存偏好（只放与档位相关的键）。 */
    private fun memory(
        global: Int? = null,
        legacy: Int? = null,
        overrides: Map<String, Int> = emptyMap(),
    ): Preferences = mutablePreferencesOf(
        *(
            listOfNotNull(
                global?.let { globalKey to it },
                legacy?.let { legacyKey to it },
            ) + overrides.map { (id, value) -> overrideKey(id) to value }
            ).toTypedArray(),
    )

    @Test
    fun `没有覆盖时跟随全局`() {
        val p = memory(global = 60)

        assertEquals(VaultTimeout.OneHour, prefs.resolveVaultTimeout(p, "v1"))
        assertEquals(null, prefs.resolveVaultTimeoutOverride(p, "v1"))
    }

    @Test
    fun `有覆盖时覆盖优先于全局，且不影响别的库`() {
        val p = memory(global = 60, overrides = mapOf("v1" to VaultTimeout.STORAGE_NEVER))

        // ★ 顺序写反（全局优先）时这一条会红。
        assertEquals(VaultTimeout.Never, prefs.resolveVaultTimeout(p, "v1"))
        assertEquals(VaultTimeout.Never, prefs.resolveVaultTimeoutOverride(p, "v1"))
        // 「全局默认 + 每库可覆盖」的核心承诺：一个库的覆盖不动其它库。
        assertEquals(VaultTimeout.OneHour, prefs.resolveVaultTimeout(p, "v2"))
    }

    @Test
    fun `全局键优先于更旧的裸分钟键`() {
        val p = memory(global = 15, legacy = 60)

        assertEquals(VaultTimeout.FifteenMinutes, prefs.globalDefaultTimeout(p))
    }

    @Test
    fun `旧裸分钟键的 -1 是「从不」，不是「重启时锁定」`() {
        val p = memory(legacy = -1)

        // ⚠️ 方向读反 ⇒ 用户选的「永不锁定」会变成「重启即锁」。
        assertEquals(VaultTimeout.Never, prefs.globalDefaultTimeout(p))
    }

    @Test
    fun `新编码里 -1 是「重启时锁定」——与旧键方向相反，不能混读`() {
        assertEquals(VaultTimeout.OnAppRestart, prefs.globalDefaultTimeout(memory(global = -1)))
        assertEquals(VaultTimeout.Never, prefs.globalDefaultTimeout(memory(legacy = -1)))
    }

    @Test
    fun `两把键都没有时落到默认档`() {
        assertEquals(VaultTimeout.DEFAULT, prefs.globalDefaultTimeout(memory()))
    }

    @Test
    fun `「跟随全局」与「显式设为从不」是两件事`() {
        val following = memory(global = 60)
        val pinned = memory(global = 60, overrides = mapOf("v1" to VaultTimeout.STORAGE_NEVER))

        // 用某个哨兵值（如 0）表示"跟随"就会把这两者混起来 ⇒ 对话框会显示错初值。
        assertEquals(null, prefs.resolveVaultTimeoutOverride(following, "v1"))
        assertEquals(VaultTimeout.Never, prefs.resolveVaultTimeoutOverride(pinned, "v1"))
    }

    @Test
    fun `迁移门控键已不是读取依据`() {
        // 旧实现要点亮 `auto_lock_migrated_v2` 才读全局键；该标记全仓库无写入点，
        // 一旦某设备为 false，新写的全局值就**静默读不到**。本断言钉住"不再看它"。
        val staleGate = mutablePreferencesOf(
            booleanPreferencesKey("auto_lock_migrated_v2") to false,
            globalKey to 30,
        )

        assertEquals(VaultTimeout.ThirtyMinutes, prefs.globalDefaultTimeout(staleGate))
    }
}
