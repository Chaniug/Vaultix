package com.bastion.app.ui

import android.util.Log
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bastion.app.logging.runCatchingObserved
import com.bastion.app.viewmodel.CategoryFilter
import com.bastion.app.viewmodel.PasswordViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private const val PASSWORD_SCROLL_LOG_TAG = "PasswordScrollDebug"
private const val PASSWORD_EMPTY_STATE_DEBOUNCE_MS = 220L

/**
 * 密码列表的滚动/空状态容器（拆分计划第四步批 3）。
 * 聚合 LazyListState、顶部 Bar 收起动画、顶部留白与空状态防抖，
 * 原先这段约 88 行状态注册内联在主函数中，是 JIT 指令超限的组成部分之一。
 */
internal class PasswordListScrollState(
    val listState: LazyListState,
    scrollCollapseFractionState: State<Float>,
    listTopPaddingState: State<Dp>,
    showEmptyStateWithHeadersState: State<Boolean>,
) {
    val scrollCollapseFraction by scrollCollapseFractionState
    val listTopPadding by listTopPaddingState
    val showEmptyStateWithHeaders by showEmptyStateWithHeadersState
}

@Composable
internal fun rememberPasswordListScrollState(
    viewModel: PasswordViewModel,
    currentListItemKeys: List<String>,
    scrollToTopRequestKey: Int,
    fastScrollRequestKey: Int,
    fastScrollProgress: Float,
    allowScrollPositionPersistence: Boolean,
    onBackToTopVisibilityChange: (Boolean) -> Unit,
    shouldShowEmptyState: Boolean,
    usesLazyColumn: Boolean,
    currentFilter: CategoryFilter,
): PasswordListScrollState {
    val showEmptyStateWithHeadersState = remember { mutableStateOf(false) }
    LaunchedEffect(shouldShowEmptyState) {
        if (!shouldShowEmptyState) {
            showEmptyStateWithHeadersState.value = false
            return@LaunchedEffect
        }
        delay(PASSWORD_EMPTY_STATE_DEBOUNCE_MS)
        showEmptyStateWithHeadersState.value = true
    }
    val listState = rememberPasswordListLazyListState(
        viewModel = viewModel,
        currentListItemKeys = currentListItemKeys,
        scrollToTopRequestKey = scrollToTopRequestKey,
        fastScrollRequestKey = fastScrollRequestKey,
        fastScrollProgress = fastScrollProgress,
        allowScrollPositionPersistence = allowScrollPositionPersistence,
        onBackToTopVisibilityChange = onBackToTopVisibilityChange
    )
    // 顶部 Bar 收起判定（快照式）：滚动越过一个很小的阈值(8dp)就整体切换，
    // 不随滚动距离连续缩放（用户反馈"逐渐缩小"不自然）。回到列表顶部自动恢复展开。
    val scrollCollapseThresholdPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        8.dp.toPx()
    }
    val scrollCollapseFractionState = remember(listState) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0 ||
                listState.firstVisibleItemScrollOffset.toFloat() > scrollCollapseThresholdPx
            ) {
                1f
            } else {
                0f
            }
        }
    }
    // 列表顶部留白跟随 Bar 当前高度联动：展开 88dp / 收起 48dp。
    // 必须与 ExpressiveTopBar 的 barMinHeight 保持一致，否则首条内容会被 Bar 吞掉。
    // 另加状态栏高度：沉浸式布局下内容从屏幕顶部开始，首条要落在状态栏+Bar 之下。
    val statusBarTopPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val listTopPaddingState = animateDpAsState(
        targetValue = statusBarTopPadding +
            androidx.compose.ui.unit.lerp(72.dp, 48.dp, scrollCollapseFractionState.value),
        animationSpec = tween(200),
        label = "list_top_padding"
    )
    var lastHandledFilterForScrollReset by remember {
        mutableStateOf<CategoryFilter?>(null)
    }
    LaunchedEffect(currentFilter, usesLazyColumn) {
        val previousFilter = lastHandledFilterForScrollReset
        if (previousFilter == null) {
            lastHandledFilterForScrollReset = currentFilter
            return@LaunchedEffect
        }
        if (previousFilter == currentFilter) {
            return@LaunchedEffect
        }
        lastHandledFilterForScrollReset = currentFilter
        Log.d(
            PASSWORD_SCROLL_LOG_TAG,
            "source=v1_filter_change_force_top from=$previousFilter to=$currentFilter usesLazyColumn=$usesLazyColumn"
        )
        if (usesLazyColumn) {
            runCatchingObserved {
                listState.scrollToItem(0, 0)
            }.onFailure { throwable ->
                if (throwable is CancellationException) return@onFailure
                Log.w(
                    PASSWORD_SCROLL_LOG_TAG,
                    "source=v1_filter_change_force_top_failed to=$currentFilter",
                    throwable
                )
            }
        }
        viewModel.updatePasswordListScrollPosition(
            0,
            0,
            null,
            source = "v1_filter_change_force_top"
        )
    }
    return PasswordListScrollState(
        listState = listState,
        scrollCollapseFractionState = scrollCollapseFractionState,
        listTopPaddingState = listTopPaddingState,
        showEmptyStateWithHeadersState = showEmptyStateWithHeadersState
    )
}
