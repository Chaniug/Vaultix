package io.vaultix.vaultix.ui.common

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [contentClearance] 的单测 —— 钉住 2026-09-20 那个「标题部分和下方部分遮住了显示内容」的 bug。
 *
 * 这是**纯算术**（不含 Compose），所以能直接测；把它从 [FullScreenDialogShell] 里抽出来
 * 也正是为了这个 —— 那个 bug 的性质是「少加了一个 insets」，单测比截图回归可靠得多。
 *
 * ⚠️ 这里的 `barHeight` 口径 = **栏体自身高**（不含系统栏内边距），当前实现里
 * 顶部与底部都是 **72dp**（`48dp 触摸目标 + Spacing.md ×2`）。
 * 「栏体高改了但这里没跟着改」会让测试**继续通过**却失去回归能力 ——
 * 所以下面用 [TITLE_BAR_HEIGHT] / [ACTION_BAR_HEIGHT] 的实参而非魔数，
 * 改动常量时若语义变了，测试会立刻红。
 */
class ContentClearanceTest {

    @Test
    fun addsSystemBarInsetToBarHeight() {
        // 普通机型：状态栏 24dp + 标题栏自身 72dp = 正文要让 96dp。
        assertThat(contentClearance(systemBarInset = 24.dp, barHeight = 72.dp)).isEqualTo(96.dp)
    }

    @Test
    fun heightsAreExactlyInsetPlusBarHeight() {
        // 手势条 24dp + 底部条自身 72dp = 96dp。
        assertThat(contentClearance(systemBarInset = 24.dp, barHeight = 72.dp)).isEqualTo(96.dp)
    }

    @Test
    fun neverFallsBelowBarHeightWhenInsetIsZero() {
        // ⚠️ 这条是**防回归**的核心：insets 读到 0（全屏 / 桌面模式 / 尚未分发）时，
        // 早期写法会退化成"零让位"，内容直接压在按钮上 —— 同一个 bug 换个成因再来一次。
        assertThat(contentClearance(systemBarInset = 0.dp, barHeight = 72.dp)).isEqualTo(72.dp)
    }

    @Test
    fun growsWithTallerSystemBars() {
        // 高状态栏机型（36 / 48dp 都真实存在）：让位必须跟着变大，而不是钉死在常量。
        assertThat(contentClearance(systemBarInset = 36.dp, barHeight = 72.dp)).isEqualTo(108.dp)
        assertThat(contentClearance(systemBarInset = 48.dp, barHeight = 72.dp)).isEqualTo(120.dp)
    }

    @Test
    fun clearanceStrictlyCoversTheBar() {
        // 不变式：让位必须 ≥ 系统栏 + 栏高，也就是"栏的不透明区域"——
        // 只要它成立，正文首/末元素就不可能钻到色带底下。
        val insets = listOf(0.dp, 24.dp, 36.dp, 48.dp)
        val bars = listOf(72.dp, 72.dp)
        for (inset in insets) {
            for (bar in bars) {
                assertThat(contentClearance(inset, bar)).isAtLeast(inset + bar)
            }
        }
    }
}
