package io.vaultix.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 首次启动引导的两个偏好位（2026-10-24）。
 *
 * ## 为什么值得钉
 *
 * 「要不要展示引导」的判断是 **「一个库都没有」且「没看过」**。第二个条件完全依赖
 * 这两个键，而它们出错的方式都是**静默**的：
 *
 * 1. **默认值写反**（默认 `true`）⇒ 全新安装直接跳过引导，功能等于没做，且**不报错**；
 * 2. **键名写错**（读一套、写另一套）⇒ 用户看完引导后每次冷启动都会再看一遍。
 *    开发机上通常早就有库、根本不走这条分支，所以很难在开发时发现；
 * 3. **两个标记混用** ⇒ 用户点了「跳过」之后，"自动填充要不要配"这一步就再也问不到了。
 *
 * 第 2 条用 [keyNamesAreStable] 硬编码键名来钉：**键名是对用户既有数据的接口**，
 * 改名 = 老用户被重新引导一次。
 *
 * ## ⚠️ 为什么用真 DataStore（写到临时目录），而不是手写一个内存桩
 *
 * 本轮第一版用了手写的 `MemoryStore`，四条用例全挂（`expected true but was false`），
 * **而产品代码是对的**。原因很隐蔽：`DataStore.edit { }` 这个扩展**内部也读 `data`**
 * 来做"当前值 + 变换"的合成 —— 一个只在 `updateData` 里改字段的桩，写进去的值
 * 在下一次 `edit` 时会被它自己旧值覆盖掉。手写桩越"简单"，越容易造出一条
 * **产品里根本不存在**的执行路径，然后让人去改产品代码迁就假象。
 *
 * 用官方 `PreferenceDataStoreFactory` 配 `TemporaryFolder`：走的是与线上完全相同的
 * 读写实现，只把落盘位置换成临时目录。代价是每条用例几百毫秒，值得。
 */
class OnboardingPreferencesTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** DataStore 的后台作用域 —— 用例结束必须关掉，否则测试进程会被挂住的协程拖住。 */
    private val storeScope = CoroutineScope(Dispatchers.IO + Job())

    private val dataStore: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(scope = storeScope) {
            tempFolder.newFile("onboarding.preferences_pb")
        }
    }

    @After
    fun tearDown() {
        storeScope.cancel()
    }

    private fun prefs() = OnboardingPreferences(dataStore)

    /**
     * ⚠️ **故意**硬编码存储键名：它们是对**用户既有数据**的接口，改名 = 静默让老用户
     * 重看一遍引导。这里若失败，说明键名被改了 —— 要么改回来，要么补一次迁移。
     *
     * 用的是本文件自己的常量（不引用生产代码的 private companion），
     * 否则"测试跟着实现一起改"就白测了。
     */
    @Test
    fun keyNamesAreStable() {
        assertEquals("onboarding_seen", KEY_SEEN.name)
        assertEquals("onboarding_fill_setup_prompted", KEY_FILL_SETUP_PROMPTED.name)
    }

    /** 默认值必须是「没看过 / 没问过」，否则全新安装不会出现引导。 */
    @Test
    fun defaultsAreFalseForFreshInstall() = runBlocking {
        assertEquals(false, prefs().onboardingSeen.first())
        assertEquals(false, prefs().fillSetupPrompted.first())
    }

    /** 写入后立刻读回 —— 这条同时兜住"读一套键、写另一套键"。 */
    @Test
    fun markOnboardingSeenFlipsOnlyItsOwnKey() = runBlocking {
        val p = prefs()
        p.markOnboardingSeen()

        assertEquals(true, p.onboardingSeen.first())
        // 只该动自己那把键：混用会让"跳过后再也问不到自动填充"。
        assertEquals(false, p.fillSetupPrompted.first())
    }

    @Test
    fun markFillSetupPromptedFlipsOnlyItsOwnKey() = runBlocking {
        val p = prefs()
        p.markFillSetupPrompted()

        assertEquals(true, p.fillSetupPrompted.first())
        assertEquals(false, p.onboardingSeen.first())
    }

    /** 两个标记互不覆盖：都写过之后应同时为 true。 */
    @Test
    fun bothFlagsCoexist() = runBlocking {
        val p = prefs()
        p.markFillSetupPrompted()
        p.markOnboardingSeen()

        assertEquals(true, p.onboardingSeen.first())
        assertEquals(true, p.fillSetupPrompted.first())
    }

    /**
     * 键已存在时仍读作 `true`。
     *
     * 刻意绕开被测的两个 setter、直接写底层 store，从而**单独验证读路径**：
     * 如果读的是另一把键，这条会挂。
     */
    @Test
    fun readsPreexistingTrueFromStore() = runBlocking {
        dataStore.edit {
            it[KEY_SEEN] = true
            it[KEY_FILL_SETUP_PROMPTED] = true
        }

        val p = prefs()
        assertEquals(true, p.onboardingSeen.first())
        assertEquals(true, p.fillSetupPrompted.first())
    }

    private companion object {
        val KEY_SEEN = booleanPreferencesKey("onboarding_seen")
        val KEY_FILL_SETUP_PROMPTED = booleanPreferencesKey("onboarding_fill_setup_prompted")
    }
}
