package com.bastion.app.ui.gestures

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/**
 * 【方案 A】滑动删除门控状态（从密码单卡的 armed 模式抽象而来，供各列表统一使用）。
 *
 * 设计意图：滑动删除默认锁定，长按激活后 3 秒内可滑动，超时或完成操作后自动解除——
 * 列表滚动绝不会误触删除；激活期间 SwipeActions 会静态露出删除提示，可发现性好。
 *
 * 用法：
 * ```
 * val armState = rememberSwipeArmState()
 * SwipeActions(enabled = armState.armed, armed = armState.armed, ...) {
 *     Card(onLongClick = { armState.arm() }, ...)
 * }
 * ```
 * 长按同时承载其他语义（如进入多选）时，先 `arm()` 再执行原逻辑即可。
 */
class SwipeArmState {
    var armed: Boolean by mutableStateOf(false)
        private set

    /**
     * 每次 arm() 递增，作为超时 LaunchedEffect 的 key。
     *
     * 只拿 armed 当 key 会有个隐蔽 bug：已激活时再次 arm()，armed 值没变 →
     * LaunchedEffect 不重启 → 3 秒窗口仍从第一次激活起算，不会顺延。
     */
    var armToken by mutableStateOf(0)
        private set

    /** 长按激活：3 秒内允许该条目滑动（重复激活会重新计时）。 */
    fun arm() {
        armed = true
        armToken++
    }

    /** 解除激活：滑动完成、点击卡片或超时后调用。 */
    fun disarm() {
        armed = false
    }
}

@Composable
fun rememberSwipeArmState(autoDisarmMillis: Long = 3_000L): SwipeArmState {
    val state = remember { SwipeArmState() }
    LaunchedEffect(state.armToken, state.armed) {
        if (!state.armed) return@LaunchedEffect
        delay(autoDisarmMillis)
        state.disarm()
    }
    return state
}
