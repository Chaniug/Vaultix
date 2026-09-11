package io.vaultix.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [VaultTimeout] 档位模型的单元测试。
 *
 * 覆盖三类断言：
 *  1. 存储值编码 / 反序列化**双射**（含已知值必须还原为具名档位，保证 `==` 稳定）；
 *  2. **旧档位一次性迁移**——旧 `-1`（旧语义『从不』）必须映射到 [VaultTimeout.Never]，
 *     **绝不能**映射到 [VaultTimeout.OnAppRestart]（新语义『重启即锁』）。语义相反，
 *     是本轮最危险的回归点；
 *  3. 非法值兜底不崩溃。
 */
class VaultTimeoutTest {

    @Test
    fun `storage value encoding matches documented table`() {
        assertEquals(-2, VaultTimeout.toStorageValue(VaultTimeout.Never))
        assertEquals(-1, VaultTimeout.toStorageValue(VaultTimeout.OnAppRestart))
        assertEquals(0, VaultTimeout.toStorageValue(VaultTimeout.Immediately))
        assertEquals(5, VaultTimeout.toStorageValue(VaultTimeout.FiveMinutes))
        assertEquals(240, VaultTimeout.toStorageValue(VaultTimeout.FourHours))
        assertEquals(7, VaultTimeout.toStorageValue(VaultTimeout.Custom(7)))
    }

    @Test
    fun `every named timeout survives a storage round trip`() {
        val all = listOf(
            VaultTimeout.Never,
            VaultTimeout.OnAppRestart,
            VaultTimeout.Immediately,
            VaultTimeout.OneMinute,
            VaultTimeout.FiveMinutes,
            VaultTimeout.FifteenMinutes,
            VaultTimeout.ThirtyMinutes,
            VaultTimeout.OneHour,
            VaultTimeout.FourHours,
        )
        for (timeout in all) {
            assertEquals(
                "往返后应与原档位相等：$timeout",
                timeout,
                VaultTimeout.fromStorageValue(VaultTimeout.toStorageValue(timeout)),
            )
        }
    }

    @Test
    fun `known minute values decode to named objects not Custom`() {
        // 关键：若是 Custom(5) 则 `VaultTimeout.FiveMinutes == it` 为 false，
        // UI 的单选状态会失效。
        assertEquals(VaultTimeout.FiveMinutes, VaultTimeout.fromStorageValue(5))
        assertEquals(VaultTimeout.OneHour, VaultTimeout.fromStorageValue(60))
        assertEquals(VaultTimeout.FourHours, VaultTimeout.fromStorageValue(240))
        assertNotEquals(VaultTimeout.Custom(5), VaultTimeout.FiveMinutes)
    }

    @Test
    fun `unknown positive values become Custom`() {
        assertEquals(VaultTimeout.Custom(7), VaultTimeout.fromStorageValue(7))
        assertEquals(VaultTimeout.Custom(1440), VaultTimeout.fromStorageValue(1440))
    }

    @Test
    fun `legacy negative minutes migrate to Never and never to OnAppRestart`() {
        // ★ 本轮最危险的回归点：旧 -1 是「从不」，新 -1 是「重启即锁」，语义相反。
        assertEquals(VaultTimeout.Never, VaultTimeout.fromLegacyMinutes(-1))
        assertNotEquals(VaultTimeout.OnAppRestart, VaultTimeout.fromLegacyMinutes(-1))
        // 任意负数都按「从不」处理（旧实现的 neverAutoLock 判定就是 minutes < 0）。
        assertEquals(VaultTimeout.Never, VaultTimeout.fromLegacyMinutes(-99))
    }

    @Test
    fun `legacy non-negative minutes map to the same timeout`() {
        assertEquals(VaultTimeout.Immediately, VaultTimeout.fromLegacyMinutes(0))
        assertEquals(VaultTimeout.FiveMinutes, VaultTimeout.fromLegacyMinutes(5))
        // 旧候选里的非标准档位（10 / 300 / 1440）保留为 Custom，不丢用户设置。
        assertEquals(VaultTimeout.Custom(10), VaultTimeout.fromLegacyMinutes(10))
        assertEquals(VaultTimeout.Custom(300), VaultTimeout.fromLegacyMinutes(300))
        assertEquals(VaultTimeout.Custom(1440), VaultTimeout.fromLegacyMinutes(1440))
    }

    @Test
    fun `legacy migration result stays stable across another round trip`() {
        for (legacy in listOf(-1, 0, 1, 5, 15, 30, 60, 240, 10, 300, 1440)) {
            val migrated = VaultTimeout.fromLegacyMinutes(legacy)
            assertEquals(
                "legacy=$legacy 迁移后再往返应稳定",
                migrated,
                VaultTimeout.fromStorageValue(VaultTimeout.toStorageValue(migrated)),
            )
        }
    }

    @Test
    fun `invalid storage values fall back to default without crashing`() {
        assertEquals(VaultTimeout.DEFAULT, VaultTimeout.fromStorageValue(-5))
        assertEquals(VaultTimeout.DEFAULT, VaultTimeout.fromStorageValue(Int.MIN_VALUE + 1))
    }

    @Test
    fun `default is five minutes`() {
        assertEquals(VaultTimeout.FiveMinutes, VaultTimeout.DEFAULT)
    }

    @Test
    fun `never has null minutes and on app restart has minus one`() {
        assertEquals(null, VaultTimeout.Never.vaultTimeoutInMinutes)
        assertEquals(-1, VaultTimeout.OnAppRestart.vaultTimeoutInMinutes)
    }
}
