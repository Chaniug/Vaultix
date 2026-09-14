package io.vaultix.vaultix.session

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.vaultix.datastore.VaultixPreferences
import io.vaultix.domain.VaultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
        val store = store(selected = null, saved = "vault-a", unlocked = setOf("vault-a", "vault-b"))

        assertThat(store.resolve()).isEqualTo("vault-a")
    }

    @Test
    fun resolveFallsBackWhenSavedVaultIsLocked() = runTest {
        // 存档库被锁：不能返回它（读不出明文），退化到仍解锁的那个
        val store = store(selected = null, saved = "vault-a", unlocked = setOf("vault-b"))

        assertThat(store.resolve()).isEqualTo("vault-b")
    }

    @Test
    fun resolvePrefersSessionSelectionOverSavedDefault() = runTest {
        // 用户本次会话显式切到了 vault-b：会话内就听用户的，
        // 默认库（vault-a）留到下次冷启动再生效 —— 这正是两键拆分的意义。
        val store = store(selected = "vault-b", saved = "vault-a", unlocked = setOf("vault-a", "vault-b"))

        assertThat(store.resolve()).isEqualTo("vault-b")
    }

    @Test
    fun resolvePicksSmallestIdWhenSeveralUnlockedAndNothingSaved() = runTest {
        // Set 无序：结论必须稳定可复现，否则「同一份数据两次启动进不同库」
        val store = store(selected = null, saved = null, unlocked = setOf("vault-b", "vault-a"))

        assertThat(store.resolve()).isEqualTo("vault-a")
    }

    @Test
    fun resolveReturnsNullWhenEverythingIsLocked() = runTest {
        val store = store(selected = null, saved = "vault-a", unlocked = emptySet())

        assertThat(store.resolve()).isNull()
    }

    @Test
    fun selectTakesEffectImmediatelyAndDoesNotTouchDefaultVault() = runTest {
        val preferences = mockk<VaultixPreferences>(relaxed = true)
        every { preferences.activeVaultId } returns flowOf(null)
        every { preferences.defaultVaultId } returns flowOf("vault-a")
        val repository = mockk<VaultRepository>()
        every { repository.observeUnlockedVaultIds() } returns flowOf(setOf("vault-a", "vault-b"))
        val store = ActiveVaultStore(preferences, repository)

        // ⚠️ 必须先等 `init` 的收集协程**首帧落地**再 select：它跑在进程级
        // Dispatchers.Default 上（见 ActiveVaultStore.scope），若首帧晚于 select() 到达，
        // 就会把刚选的库覆盖回 pick() 的结果 —— 本地偶发、CI 长红（2026-09-12 定位）。
        store.activeVaultId.first { it != null }

        store.select("vault-b")

        // 同步读必须立刻可见：主界面 Tab / autofill 都在同一次交互里依赖它
        assertThat(store.current()).isEqualTo("vault-b")
        // 落盘在进程级 scope（Dispatchers.Default）上异步执行，故带超时等待
        coVerify(timeout = 2_000) { preferences.setActiveVaultId("vault-b") }
        // ★ 核心断言（2026-09-14 拆分）：切库**绝不能**写默认库，
        // 否则「临时切去看一眼另一个库」会静默改掉冷启动默认库。
        coVerify(exactly = 0) { preferences.setDefaultVaultId(any()) }
    }

    @Test
    fun setDefaultPersistsWithoutTouchingActiveVault() = runTest {
        val preferences = mockk<VaultixPreferences>(relaxed = true)
        every { preferences.activeVaultId } returns flowOf(null)
        every { preferences.defaultVaultId } returns flowOf(null)
        val repository = mockk<VaultRepository>()
        every { repository.observeUnlockedVaultIds() } returns flowOf(setOf("vault-a"))
        val store = ActiveVaultStore(preferences, repository)

        store.setDefault("vault-a")

        coVerify(timeout = 2_000) { preferences.setDefaultVaultId("vault-a") }
    }

    @Test
    fun setDefaultIfAbsentDelegatesToAtomicPreferenceWrite() = runTest {
        // 首次接入新库：委托给偏好层的**事务性**读改写（同一 edit 内判空 + 写），
        // 避免「两个库并发接入都读到空」的竞态。
        val preferences = mockk<VaultixPreferences>(relaxed = true)
        every { preferences.activeVaultId } returns flowOf(null)
        every { preferences.defaultVaultId } returns flowOf(null)
        // ⚠️ `trySetDefaultVaultIfAbsent` 是 `suspend fun` ⇒ 必须 `coEvery`
        //（`every` 只适用于普通函数，编译器会报「Suspension functions can only be
        // called within coroutine body」）。
        coEvery { preferences.trySetDefaultVaultIfAbsent("vault-new") } returns true
        val repository = mockk<VaultRepository>()
        every { repository.observeUnlockedVaultIds() } returns flowOf(emptySet())
        val store = ActiveVaultStore(preferences, repository)

        assertThat(store.setDefaultIfAbsent("vault-new")).isTrue()

        // ★ 不得走「无条件覆盖」那条路 —— 那会改掉用户设过的默认库。
        coVerify(exactly = 1) { preferences.trySetDefaultVaultIfAbsent("vault-new") }
        coVerify(exactly = 0) { preferences.setDefaultVaultId(any()) }
    }

    @Test
    fun setDefaultIfAbsentIgnoresBlankVaultId() = runTest {
        val preferences = mockk<VaultixPreferences>(relaxed = true)
        every { preferences.activeVaultId } returns flowOf(null)
        every { preferences.defaultVaultId } returns flowOf(null)
        val repository = mockk<VaultRepository>()
        every { repository.observeUnlockedVaultIds() } returns flowOf(emptySet())
        val store = ActiveVaultStore(preferences, repository)

        assertThat(store.setDefaultIfAbsent("")).isFalse()

        coVerify(exactly = 0) { preferences.trySetDefaultVaultIfAbsent(any()) }
    }

    private fun store(selected: String?, saved: String?, unlocked: Set<String>): ActiveVaultStore {
        val preferences = mockk<VaultixPreferences>(relaxed = true)
        every { preferences.activeVaultId } returns flowOf(selected)
        every { preferences.defaultVaultId } returns flowOf(saved)
        val repository = mockk<VaultRepository>()
        every { repository.observeUnlockedVaultIds() } returns flowOf(unlocked)
        return ActiveVaultStore(preferences, repository)
    }
}
