package io.vaultix.vaultix.ui.items

import com.google.common.truth.Truth.assertThat
import io.vaultix.model.VaultKind
import org.junit.Test

/**
 * [cloudSyncBadgeOrNull] 的单测 —— 钉住 2026-11-10 真机反馈「本地 kdbx 也有小云图标」。
 *
 * 这个 bug 的性质值得单独说：三条各自正确的代码串成了错误结果。
 *
 * 1. `observeSyncStates` 对 KDBX 返回**空 map**（正确：文件即存储，没有云端这一层）；
 * 2. 读侧 `syncStates[id] ?: true` —— 空 map 查不到 ⇒ 每条都得到 `true`（对 Bitwarden 正确）；
 * 3. 行尾**无条件**画 `CloudSyncIcon(synced = true)` ⇒ 一朵「已同步」的云。
 *
 * 而组件 KDoc 早已写明「KDBX 库调用方不要渲染本组件」。
 *
 * ⚠️ 这里刻意**不测 Compose**，只测那个把"该不该画"决定下来的纯函数。
 * 用截图回归测它太贵（要起 instrumentation），而错误的形态又是"多了一个图标"——
 * 正是肉眼最容易漏掉的那类。纯函数一条断言就锁死了。
 */
class CloudSyncBadgeTest {

    // ── KDBX：一律 null（不画） ────────────────────────────────────────────────

    @Test
    fun `kdbx never draws a cloud even though syncStates defaults to true`() {
        // 这是**原 bug 的精确复现条件**：KDBX + 查不到状态（即 null）
        assertThat(cloudSyncBadgeOrNull(VaultKind.KDBX, null)).isNull()
    }

    @Test
    fun `kdbx never draws a cloud regardless of the looked up value`() {
        // 防御性：即使将来有人给 KDBX 也塞了值，也不该冒出云图标
        assertThat(cloudSyncBadgeOrNull(VaultKind.KDBX, true)).isNull()
        assertThat(cloudSyncBadgeOrNull(VaultKind.KDBX, false)).isNull()
    }

    @Test
    fun `unknown vault kind does not draw a cloud`() {
        // 库还没加载出来时 vaultKind 为 null ⇒ 按 KDBX 处理（宁可不画，也不闪一朵假云）
        assertThat(cloudSyncBadgeOrNull(null, null)).isNull()
        assertThat(cloudSyncBadgeOrNull(null, true)).isNull()
    }

    // ── Bitwarden：保留原有三种语义 ────────────────────────────────────────────

    @Test
    fun `bitwarden pending shows unsynced`() {
        assertThat(cloudSyncBadgeOrNull(VaultKind.BITWARDEN, false)).isFalse()
    }

    @Test
    fun `bitwarden synced shows synced`() {
        assertThat(cloudSyncBadgeOrNull(VaultKind.BITWARDEN, true)).isTrue()
    }

    @Test
    fun `bitwarden missing entry defaults to synced`() {
        // 新建后立刻可见、还没进 pending_ops 的那一瞬间 —— 不能闪一下「未同步」
        assertThat(cloudSyncBadgeOrNull(VaultKind.BITWARDEN, null)).isTrue()
    }

    // ── 判据本身 ──────────────────────────────────────────────────────────────

    @Test
    fun `only bitwarden can ever return non null`() {
        // 把判据写成可枚举的形式：除 BITWARDEN 外，任何输入都必须是 null。
        // 新增 VaultKind 时这条会立刻红 —— 提醒作者先想清楚"这个库有没有云端"。
        for (kind in VaultKind.entries) {
            val results = listOf(true, false, null).map { cloudSyncBadgeOrNull(kind, it) }
            if (kind == VaultKind.BITWARDEN) {
                assertThat(results).doesNotContain(null)
            } else {
                assertThat(results).containsExactly(null, null, null)
            }
        }
    }
}
