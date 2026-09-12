package io.vaultix.vaultix.session

import com.google.common.truth.Truth.assertThat
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.domain.VaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * 活跃库取值的门禁测试（2026-09-12 阶段 2「★ 全局活跃库真源」）。
 *
 * 背景：autofill / Credential Provider / 保存回写此前各自「遍历所有已解锁库」，
 * 两库同时解锁时同一站点会冒出两条来源不同的候选、保存时也不知道写回哪个库。
 * 收敛后三处统一读 [ActiveVaultStore.resolve]，本文件把它「只产出一个库」的语义钉死。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActiveVaultStoreTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun resolveKeepsSavedVaultWhileItStaysUnlocked() = runTest {
        val store = store(saved = "vault-a", unlocked = setOf("vault-a", "vault-b"))

        assertThat(store.resolve()).isEqualTo("vault-a")
    }

    @Test
    fun resolveFallsBackWhenSavedVaultIsLocked() = runTest {
        // 存档库被锁：不能返回它（读不出明文），退化到仍解锁的那个
        val store = store(saved = "vault-a", unlocked = setOf("vault-b"))

        assertThat(store.resolve()).isEqualTo("vault-b")
    }

    @Test
    fun resolvePicksSmallestIdWhenSeveralUnlockedAndNothingSaved() = runTest {
        // Set 无序：结论必须稳定可复现，否则「同一份数据两次启动进不同库」
        val store = store(saved = null, unlocked = setOf("vault-b", "vault-a"))

        assertThat(store.resolve()).isEqualTo("vault-a")
    }

    @Test
    fun resolveReturnsNullWhenEverythingIsLocked() = runTest {
        val store = store(saved = "vault-a", unlocked = emptySet())

        assertThat(store.resolve()).isNull()
    }

    @Test
    fun selectTakesEffectImmediatelyAndPersists() = runTest {
        val preferences = mockk<VaultixPreferences>(relaxed = true)
        every { preferences.defaultVaultId } returns flowOf(null)
        val repository = mockk<VaultRepository>()
        every { repository.observeUnlockedVaultIds() } returns flowOf(setOf("vault-a", "vault-b"))
        val store = ActiveVaultStore(preferences, repository)

        store.select("vault-b")

        // 同步读必须立刻可见：主界面 Tab / autofill 都在同一次交互里依赖它
        assertThat(store.current()).isEqualTo("vault-b")
        // 落盘在进程级 scope（Dispatchers.Default）上异步执行，故带超时等待
        coVerify(timeout = 2_000) { preferences.setDefaultVaultId("vault-b") }
    }

    private fun store(saved: String?, unlocked: Set<String>): ActiveVaultStore {
        val preferences = mockk<VaultixPreferences>(relaxed = true)
        every { preferences.defaultVaultId } returns flowOf(saved)
        val repository = mockk<VaultRepository>()
        every { repository.observeUnlockedVaultIds() } returns flowOf(unlocked)
        return ActiveVaultStore(preferences, repository)
    }
}
