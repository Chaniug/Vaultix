package com.bastion.app.viewmodel.behavior

import com.bastion.app.data.PasswordEntry
import com.bastion.app.repository.PasswordRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * mockk 基础设施冒烟测试（Phase B.3 Step 0）。
 *
 * 目的：在给 `PasswordViewModel` 的有状态编排（删除 / 归档 / 跨存储迁移）补行为测试之前，
 * 先证明 mockk 在本项目的实际约束下真的可用。这些约束是：
 *
 * 1. `PasswordRepository` 是 Kotlin **final class**（79 个公开方法，不是接口）——
 *    普通继承式 Fake 写不出来，必须靠 mockk 的字节码插桩。
 * 2. 仓库方法大量是 `suspend fun`——需要 `coEvery` / `coVerify` 而非 `every` / `verify`。
 * 3. 领域模型 `PasswordEntry` 是 data class，需能作为返回值与参数自由构造。
 *
 * 本测试若变红，说明 mockk 版本与 Kotlin 2.0.21 的 metadata 不兼容
 * （典型症状：`incompatible version of Kotlin`），此时应检查
 * `gradle/libs.versions.toml` 中的 `mockk` 版本注释，切勿盲目升级到 1.14.x。
 */
class MockkInfrastructureSmokeTest {

    private fun sampleEntry(id: Long, title: String) = PasswordEntry(
        id = id,
        title = title,
        website = "https://example.com",
        username = "user$id",
        password = "encrypted-$id"
    )

    @Test
    fun `mockk can stub final class PasswordRepository`() = runTest {
        val repository = mockk<PasswordRepository>()
        val expected = listOf(sampleEntry(1L, "条目一"), sampleEntry(2L, "条目二"))

        coEvery { repository.getAllLocalPasswordEntries() } returns expected

        val actual = repository.getAllLocalPasswordEntries()

        assertEquals(2, actual.size)
        assertEquals("条目一", actual[0].title)
        assertEquals("条目二", actual[1].title)
    }

    @Test
    fun `coVerify can assert suspend call arguments`() = runTest {
        val repository = mockk<PasswordRepository>(relaxed = true)
        val entry = sampleEntry(42L, "待删除条目")

        repository.deletePasswordEntry(entry)

        coVerify(exactly = 1) { repository.deletePasswordEntry(entry) }
    }

    @Test
    fun `relaxed mock returns defaults for unstubbed suspend methods`() = runTest {
        // 编排类测试会顺带触碰几十个仓库方法，逐个打桩不现实；
        // relaxed 模式是后续行为测试的默认姿势，这里确认它对 suspend 同样生效。
        val repository = mockk<PasswordRepository>(relaxed = true)

        val entries = repository.getAllLocalPasswordEntries()

        assertTrue("relaxed mock 应返回空集合而非 null", entries.isEmpty())
    }
}
